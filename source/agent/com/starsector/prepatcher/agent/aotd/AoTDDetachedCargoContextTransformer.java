package com.starsector.prepatcher.agent.aotd;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.StructuralMismatchException;
import com.starsector.prepatcher.agent.PrepatcherLog;

/**
 * Owns the exact temporary-market branch shared by detached campaign Cargo
 * and generated-loot transfer panels. The runtime guard handles only proven
 * skip/local cases; unknown economy implementations preserve tripleStep() as
 * their global fallback.
 */
public final class AoTDDetachedCargoContextTransformer implements ClassFileTransformer {
  public static final String TARGET = DetachedCargoEconomyStep.TARGET;

  private final boolean enabled;
  private final ClassLoader runtimeLoader;

  public AoTDDetachedCargoContextTransformer(boolean enabled, ClassLoader runtimeLoader) {
    this.enabled = enabled;
    this.runtimeLoader = runtimeLoader;
  }

  @Override
  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain, byte[] classfileBuffer) {
    if (!enabled || !TARGET.equals(className)) return null;
    if (!runtimeVisibleFrom(loader)) {
      record("SKIPPED_LOADER");
      disableRuntime("detached Cargo target loader cannot see runtime bridge");
      PrepatcherLog.warn("AoTD detached-Cargo context not patched: target loader=" + loaderName(loader)
          + ", runtime loader=" + loaderName(runtimeLoader));
      return null;
    }
    try {
      ClassNode node = read(classfileBuffer);
      if (!TARGET.equals(node.name)) return null;
      DetachedCargoEconomyStep patch = new DetachedCargoEconomyStep();
      Match<DetachedCargoEconomyStep.Plan> match = patch.match(node);
      if (match.alreadyApplied) {
        record("ALREADY_APPLIED");
        return null;
      }
      if (match.plan == null) { throw mismatch(match.reason); }
      patch.mutate(match.plan);
      patch.verify(match.plan, node);

      ClassWriter writer = new LoaderNeutralClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
      node.accept(writer);
      byte[] transformed = writer.toByteArray();
      patch.verify(match.plan, read(transformed));
      record("APPLIED");
      System.setProperty("starsector.prepatcher.aotdUiCallContexts", "ready");
      PrepatcherLog.info("APPLIED campaignCargoNoGlobalEconomyStep to " + className
          + ": exact fake_market CARGO/LOOT skip + live-market vanilla coalescing + "
          + "owned-AoTD action + original global fallback");
      return transformed;
    } catch (StructuralMismatchException mismatch) {
      record("SKIPPED_STRUCTURAL");
      disableRuntime("detached Cargo structural mismatch: " + mismatch.getMessage());
      PrepatcherLog
          .warn("SKIPPED_STRUCTURAL campaignCargoNoGlobalEconomyStep in " + className + ": " + mismatch.getMessage());
      return null;
    } catch (Throwable failure) {
      record("SKIPPED_ERROR");
      disableRuntime("detached Cargo transformer error: " + failure.getClass().getName());
      PrepatcherLog.error("SKIPPED_ERROR campaignCargoNoGlobalEconomyStep in " + className
          + "; original Cargo behavior remains active.", failure);
      return null;
    }
  }

  private void disableRuntime(String reason) {
    if (runtimeLoader == null) return;
    try {
      Class<?> bridge = Class.forName("com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge", false, runtimeLoader);
      Method disable = bridge.getMethod("disableAoTDUiEconomyDispatch", String.class);
      disable.invoke(null, reason);
    } catch (Throwable ignored) {
      // The runtime may not yet be initialized in an offline/early loader.
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
    System.setProperty("starsector.prepatcher.campaignCargoNoGlobalEconomyStepPatch", status);
    System.setProperty("starsector.prepatcher.aotdDetachedCargoContextPatch", status);
    System.setProperty("starsector.prepatcher.lootTransferNoGlobalEconomyStepPatch", status);
  }

  private static String loaderName(ClassLoader loader) {
    if (loader == null) return "bootstrap";
    return loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
  }

  private static final class LoaderNeutralClassWriter extends ClassWriter {
    LoaderNeutralClassWriter(int flags) {
      super(flags);
    }
    @Override
    protected String getCommonSuperClass(String type1, String type2) {
      return "java/lang/Object";
    }
  }
}
