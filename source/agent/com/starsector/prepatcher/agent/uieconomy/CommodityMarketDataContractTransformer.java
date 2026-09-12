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
 * Exact-current-game semantic gate for filtered CommodityMarketData
 * reconstruction. It changes no bytecode. Structural drift leaves the original
 * doubleStep/tripleStep paths active.
 */
public final class CommodityMarketDataContractTransformer implements ClassFileTransformer {
  public static final String TARGET = CommodityMarketDataCommitContract.TARGET;
  private static final String RUNTIME = "com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge";

  private final boolean enabled;
  private final ClassLoader runtimeLoader;

  public CommodityMarketDataContractTransformer(boolean enabled, ClassLoader runtimeLoader) {
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
      ClassNode node = new ClassNode(Opcodes.ASM8);
      new ClassReader(classfileBuffer).accept(node, 0);
      CommodityMarketDataCommitContract patch = new CommodityMarketDataCommitContract();
      Match<CommodityMarketDataCommitContract.Plan> match = patch.match(node);
      if (match.alreadyApplied || match.plan == null) { throw mismatch(match.reason); }
      patch.mutate(match.plan);
      patch.verify(match.plan, node);
      if (!publish(true, null)) {
        record("SKIPPED_RUNTIME");
        return null;
      }
      record("READY");
      PrepatcherLog.info("READY vanilla affected-commodity commit contract");
    } catch (StructuralMismatchException mismatch) {
      reject("SKIPPED_STRUCTURAL", mismatch.getMessage());
    } catch (Throwable failure) {
      publish(false, failure.getClass().getName());
      record("SKIPPED_ERROR");
      PrepatcherLog.error(
          "SKIPPED_ERROR vanilla affected-commodity commit contract; " + "original global economy steps remain active",
          failure);
    }
    return null;
  }

  private void reject(String status, String reason) {
    publish(false, reason);
    record(status);
    PrepatcherLog.warn(status + " vanilla affected-commodity commit contract: " + reason
        + "; original global economy steps remain active");
  }

  private boolean publish(boolean operational, String reason) {
    if (runtimeLoader == null) return true;
    try {
      Class<?> bridge = Class.forName(RUNTIME, false, runtimeLoader);
      Method method = bridge.getMethod("setCommodityMarketDataContract", boolean.class, String.class);
      method.invoke(null, operational, reason);
      return true;
    } catch (Throwable failure) {
      PrepatcherLog.warn("Could not publish affected-commodity contract: " + failure.getClass().getName());
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

  private static void record(String status) {
    System.setProperty("starsector.prepatcher.commodityMarketDataContract", status);
  }
}
