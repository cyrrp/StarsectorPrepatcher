package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import static com.starsector.prepatcher.agent.Contract.*;

/** Guards the exact market-open Economy.nextStep call with an explicit runtime action. */
final class AoTDMarketOpenContextTransformer implements ClassFileTransformer {
  static final String TARGET = MarketOpenEconomyStep.TARGET;

  private final boolean enabled;
  private final ClassLoader runtimeLoader;

  AoTDMarketOpenContextTransformer(boolean enabled, ClassLoader runtimeLoader) {
    this(enabled, enabled, enabled, runtimeLoader);
  }

  AoTDMarketOpenContextTransformer(boolean contextEnabled, boolean conditionOnlyEnabled, ClassLoader runtimeLoader) {
    this(contextEnabled, conditionOnlyEnabled, false, runtimeLoader);
  }

  AoTDMarketOpenContextTransformer(boolean contextEnabled, boolean conditionOnlyEnabled,
      boolean vanillaMarketOpenLocalizationEnabled, ClassLoader runtimeLoader) {
    this.enabled = contextEnabled || conditionOnlyEnabled || vanillaMarketOpenLocalizationEnabled;
    this.runtimeLoader = runtimeLoader;
  }

  @Override
  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain, byte[] classfileBuffer) {
    if (!enabled || !TARGET.equals(className)) return null;
    if (!runtimeVisibleFrom(loader)) {
      record("SKIPPED_LOADER");
      PrepatcherLog.warn("AoTD market-open context not patched: target loader=" + loaderName(loader)
          + ", runtime loader=" + loaderName(runtimeLoader));
      return null;
    }
    try {
      ClassNode node = read(classfileBuffer);
      if (!TARGET.equals(node.name)) return null;
      MarketOpenEconomyStep patch = new MarketOpenEconomyStep();
      Match<MarketOpenEconomyStep.Plan> match = patch.match(node);
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
      PrepatcherLog.info("APPLIED aotdMarketOpenContext to " + className
          + ": explicit condition-only/live-market guard with original" + " Economy.nextStep global fallback");
      return transformed;
    } catch (StructuralMismatchException mismatch) {
      record("SKIPPED_STRUCTURAL");
      PrepatcherLog.warn("SKIPPED_STRUCTURAL aotdMarketOpenContext in " + className + ": " + mismatch.getMessage());
      return null;
    } catch (Throwable failure) {
      record("SKIPPED_ERROR");
      PrepatcherLog.error(
          "SKIPPED_ERROR aotdMarketOpenContext in " + className + "; vanilla market-open behavior remains active.",
          failure);
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

  private static void record(String status) {
    System.setProperty("starsector.prepatcher.aotdMarketOpenContextPatch", status);
    System.setProperty("starsector.prepatcher.planetConditionMarketOpenNoGlobalEconomyStepPatch", status);
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
