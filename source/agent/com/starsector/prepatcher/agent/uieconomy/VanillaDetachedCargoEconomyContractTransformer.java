package com.starsector.prepatcher.agent.uieconomy;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.PrepatcherLog;
import com.starsector.prepatcher.agent.patch.StructuralMismatchException;

/**
 * Proves that the current vanilla Economy tripleStep is only three nextStep
 * calls and that getEconomy returns the exact ReachEconomy field. No bytecode
 * is changed; the result gates the detached-Cargo vanilla skip at runtime.
 */
public final class VanillaDetachedCargoEconomyContractTransformer implements ClassFileTransformer {
  public static final String TARGET = VanillaDetachedCargoEconomyContract.TARGET;
  private static final String RUNTIME = "com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge";

  private final boolean enabled;
  private final ClassLoader runtimeLoader;

  public VanillaDetachedCargoEconomyContractTransformer(boolean enabled, ClassLoader runtimeLoader) {
    this.enabled = enabled;
    this.runtimeLoader = runtimeLoader;
  }

  @Override
  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain, byte[] classfileBuffer) {
    if (!enabled || !TARGET.equals(className)) return null;
    if (!runtimeVisibleFrom(loader)) {
      reject("SKIPPED_LOADER", "target loader cannot see runtime bridge");
      return null;
    }
    try {
      ClassNode node = read(classfileBuffer);
      VanillaDetachedCargoEconomyContract patch = new VanillaDetachedCargoEconomyContract();
      Match<VanillaDetachedCargoEconomyContract.Plan> match = patch.match(node);
      if (match.alreadyApplied || match.plan == null) { throw mismatch(match.reason); }
      patch.mutate(match.plan);
      patch.verify(match.plan, node);
      if (!publishRuntime(true)) {
        record("SKIPPED_RUNTIME");
        return null;
      }
      record("READY");
      PrepatcherLog.info("READY campaignCargoNoGlobalEconomyStep vanilla contract: "
          + "Economy.tripleStep=3x nextStep, getEconomy=exact ReachEconomy");
    } catch (StructuralMismatchException mismatch) {
      reject("SKIPPED_STRUCTURAL", mismatch.getMessage());
    } catch (Throwable failure) {
      publishRuntime(false);
      record("SKIPPED_ERROR");
      PrepatcherLog.error(
          "SKIPPED_ERROR vanilla detached-Cargo economy contract; " + "original tripleStep remains active", failure);
    }
    return null;
  }

  private void reject(String status, String reason) {
    publishRuntime(false);
    record(status);
    PrepatcherLog
        .warn(status + " vanilla detached-Cargo economy contract: " + reason + "; original tripleStep remains active");
  }

  private boolean publishRuntime(boolean operational) {
    if (runtimeLoader == null) return true;
    try {
      Class<?> bridge = Class.forName(RUNTIME, false, runtimeLoader);
      Method publish = bridge.getMethod("setVanillaDetachedCargoEconomyContract", boolean.class, String.class);
      publish.invoke(null, operational, null);
      return true;
    } catch (Throwable failure) {
      PrepatcherLog.warn("Could not publish vanilla detached-Cargo economy contract: " + failure.getClass().getName());
      return false;
    }
  }

  private boolean runtimeVisibleFrom(ClassLoader loader) {
    if (runtimeLoader == null) return true;
    for (ClassLoader current = loader; current != null; current = current.getParent()) {
      if (current == runtimeLoader) return true;
    }
    return false;
  }

  private static ClassNode read(byte[] bytes) {
    ClassNode node = new ClassNode(Opcodes.ASM8);
    new ClassReader(bytes).accept(node, 0);
    return node;
  }

  private static void record(String status) {
    System.setProperty("starsector.prepatcher.detachedCargoVanillaEconomyContract", status);
  }
}
