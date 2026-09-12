package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import static com.starsector.prepatcher.agent.Contract.*;

/**
 * Exact-call-site mutation context patch for the vanilla market
 * overview panel. Unknown callers of recreateWithEconUpdate() retain the
 * original global Economy.tripleStep().
 */
final class MarketOverviewMutationTransformer implements ClassFileTransformer {
  static final String TARGET = "com/fs/starfarer/campaign/ui/marketinfo/s";

  private final boolean enabled;
  private final ClassLoader runtimeLoader;

  MarketOverviewMutationTransformer(boolean enabled, ClassLoader runtimeLoader) {
    this.enabled = enabled;
    this.runtimeLoader = runtimeLoader;
  }

  boolean isTargetEnabled(String internalName) {
    return enabled && TARGET.equals(internalName);
  }

  @Override
  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain, byte[] classfileBuffer) {
    if (!enabled || !TARGET.equals(className)) return null;
    if (!runtimeVisibleFrom(loader)) {
      record("SKIPPED_LOADER");
      PrepatcherLog.warn("Market overview mutation refresh not patched: target loader=" + loaderName(loader)
          + ", runtime loader=" + loaderName(runtimeLoader));
      return null;
    }
    try {
      ClassNode node = read(classfileBuffer);
      if (!TARGET.equals(node.name)) return null;
      MarketOverviewMutationRefresh patch = new MarketOverviewMutationRefresh();
      Match<MarketOverviewMutationRefresh.Plan> match = patch.match(node);
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
      PrepatcherLog.info("APPLIED market overview mutation refresh to " + className
          + ": exact policy mutators publish one-shot reason/scope context;"
          + " shared recreate helper keeps global fallback for unknown actions");
      return transformed;
    } catch (StructuralMismatchException mismatch) {
      record("SKIPPED_STRUCTURAL");
      PrepatcherLog.warn("SKIPPED_STRUCTURAL market overview mutation refresh in " + className + ": "
          + mismatch.getMessage() + "; original mutation calls and tripleStep remain active");
      return null;
    } catch (Throwable failure) {
      record("SKIPPED_ERROR");
      PrepatcherLog.error("SKIPPED_ERROR market overview mutation refresh in " + className
          + "; original mutation behavior remains active", failure);
      return null;
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

  static String statusProperty() {
    return "starsector.prepatcher.marketOverviewMutationPatch";
  }

  private static void record(String status) {
    System.setProperty(statusProperty(), status);
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
