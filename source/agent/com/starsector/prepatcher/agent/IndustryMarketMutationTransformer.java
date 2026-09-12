package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;

import static com.starsector.prepatcher.agent.Contract.*;

/**
 * Exact vanilla industry-mutation patch. Only the five proven dialog
 * branches publish a one-shot mutation context. Unknown custom industry
 * options continue through the original IndustryListPanel.tripleStep(). The
 * dialog wrappers remain inert until both dialog and panel surfaces verify.
 */
final class IndustryMarketMutationTransformer implements ClassFileTransformer {
  static final String INDUSTRY_DIALOG = IndustryMarketMutations.TARGET;
  static final String INDUSTRY_PANEL = IndustryOverviewMutationGuard.TARGET;
  static final Set<String> TARGET_CLASSES = Set.of(INDUSTRY_DIALOG, INDUSTRY_PANEL);

  private final boolean enabled;
  private final ClassLoader runtimeLoader;
  private final Set<String> verifiedTargets = new HashSet<>();
  private boolean groupRejected;

  IndustryMarketMutationTransformer(boolean enabled, ClassLoader runtimeLoader) {
    this.enabled = enabled;
    this.runtimeLoader = runtimeLoader;
    System.setProperty(groupStatusProperty(), enabled ? "awaiting-targets" : "disabled");
  }

  boolean isTargetEnabled(String internalName) {
    return enabled && TARGET_CLASSES.contains(internalName);
  }

  @Override
  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain, byte[] classfileBuffer) {
    if (!enabled || !TARGET_CLASSES.contains(className)) return null;
    if (!runtimeVisibleFrom(loader)) {
      record(className, "SKIPPED_LOADER");
      rejectGroup("loader-mismatch");
      PrepatcherLog.warn("Industry market-mutation patch not applied: target loader=" + loaderName(loader)
          + ", runtime loader=" + loaderName(runtimeLoader));
      return null;
    }
    try {
      ClassNode node = read(classfileBuffer);
      if (!className.equals(node.name)) return null;
      if (INDUSTRY_DIALOG.equals(className)) return transformDialog(node);
      return transformPanel(node);
    } catch (StructuralMismatchException mismatch) {
      record(className, "SKIPPED_STRUCTURAL");
      rejectGroup("structural-mismatch");
      PrepatcherLog.warn("SKIPPED_STRUCTURAL industry market-mutation patch in " + className + ": "
          + mismatch.getMessage() + "; original industry mutation and tripleStep remain active");
      return null;
    } catch (Throwable failure) {
      record(className, "SKIPPED_ERROR");
      rejectGroup("transform-error");
      PrepatcherLog.error(
          "SKIPPED_ERROR industry market-mutation patch in " + className + "; original behavior remains active",
          failure);
      return null;
    }
  }

  private byte[] transformDialog(ClassNode node) {
    IndustryMarketMutations patch = new IndustryMarketMutations();
    Match<IndustryMarketMutations.Plan> match = patch.match(node);
    if (match.alreadyApplied) {
      record(node.name, "ALREADY_APPLIED");
      acceptTarget(node.name);
      return null;
    }
    if (match.plan == null) { throw mismatch(match.reason); }
    patch.mutate(match.plan);
    patch.verify(match.plan, node);
    byte[] transformed = write(node);
    patch.verify(match.plan, read(transformed));
    record(node.name, "APPLIED");
    acceptTarget(node.name);
    PrepatcherLog.info("APPLIED exact industry affected-commodity wrappers to " + node.name
        + ": upgrade/downgrade/remove/cancel only");
    return transformed;
  }

  private byte[] transformPanel(ClassNode node) {
    IndustryOverviewMutationGuard patch = new IndustryOverviewMutationGuard();
    Match<IndustryOverviewMutationGuard.Plan> match = patch.match(node);
    if (match.alreadyApplied) {
      record(node.name, "ALREADY_APPLIED");
      acceptTarget(node.name);
      return null;
    }
    if (match.plan == null) { throw mismatch(match.reason); }
    patch.mutate(match.plan);
    patch.verify(match.plan, node);
    byte[] transformed = write(node);
    patch.verify(match.plan, read(transformed));
    record(node.name, "APPLIED");
    acceptTarget(node.name);
    PrepatcherLog.info("APPLIED one-shot industry mutation guard to " + node.name
        + ".recreateOverview; unknown/custom callers retain tripleStep");
    return transformed;
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

  private static byte[] write(ClassNode node) {
    ClassWriter writer = new LoaderNeutralClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    node.accept(writer);
    return writer.toByteArray();
  }

  static String statusProperty(String className) {
    return "starsector.prepatcher.industryMarketMutationPatch." + className.replace('/', '.');
  }

  static String groupStatusProperty() {
    return "starsector.prepatcher.industryMarketMutationPatchGroup";
  }

  private synchronized void acceptTarget(String className) {
    if (groupRejected) return;
    verifiedTargets.add(className);
    System.setProperty(groupStatusProperty(),
        verifiedTargets.containsAll(TARGET_CLASSES) ? "ready" : "awaiting-targets");
  }

  private synchronized void rejectGroup(String reason) {
    groupRejected = true;
    System.setProperty(groupStatusProperty(), "disabled-" + reason);
  }

  private static void record(String className, String status) {
    System.setProperty(statusProperty(className), status);
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
