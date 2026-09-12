package com.starsector.prepatcher.agent.uieconomy;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Set;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.PrepatcherLog;
import com.starsector.prepatcher.agent.patch.StructuralMismatchException;

/**
 * Removes exact sector-wide economy refreshes whose only trigger is opening a
 * read-only UI surface. Each target has an independent structural contract and
 * marker; a changed call site retains the original tripleStep() invocation.
 */
public final class ReadOnlyUiEconomyStepTransformer implements ClassFileTransformer {
  public static final String COMMAND_TAB = "com/fs/starfarer/campaign/command/F";
  public static final String COMMODITY_DETAIL_V2 =
      "com/fs/starfarer/campaign/ui/marketinfo/cdd/CommodityDetailDialogV2";
  public static final String COMMODITY_DETAIL_LEGACY = "com/fs/starfarer/campaign/ui/marketinfo/CommodityDetailDialog";
  public static final String MARKET_CMD = "com/fs/starfarer/api/impl/campaign/rulecmd/salvage/MarketCMD";
  public static final String NEX_MARKET_CMD = "com/fs/starfarer/api/impl/campaign/rulecmd/salvage/Nex_MarketCMD";
  public static final Set<String> TARGET_CLASSES =
      Set.of(COMMAND_TAB, COMMODITY_DETAIL_V2, COMMODITY_DETAIL_LEGACY, MARKET_CMD, NEX_MARKET_CMD);

  private final boolean commandTabEnabled;
  private final boolean commodityDetailEnabled;
  private final boolean marketDefensesEnabled;
  private final ClassLoader runtimeLoader;

  public ReadOnlyUiEconomyStepTransformer(boolean commandTabEnabled, boolean commodityDetailEnabled,
      boolean marketDefensesEnabled, ClassLoader runtimeLoader) {
    this.commandTabEnabled = commandTabEnabled;
    this.commodityDetailEnabled = commodityDetailEnabled;
    this.marketDefensesEnabled = marketDefensesEnabled;
    this.runtimeLoader = runtimeLoader;
  }

  public boolean isTargetEnabled(String className) {
    return switch (className) {
      case COMMAND_TAB -> commandTabEnabled;
      case COMMODITY_DETAIL_V2, COMMODITY_DETAIL_LEGACY -> commodityDetailEnabled;
      case MARKET_CMD, NEX_MARKET_CMD -> marketDefensesEnabled;
      default -> false;
    };
  }

  @Override
  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain, byte[] classfileBuffer) {
    if (!TARGET_CLASSES.contains(className) || !isTargetEnabled(className)) return null;
    if (!acceptsLoader(className, loader)) {
      record(className, "SKIPPED_LOADER");
      PrepatcherLog.warn("SKIPPED_LOADER readOnlyUiNoGlobalEconomyStep " + className + ": target loader="
          + loaderName(loader) + ", runtime loader=" + loaderName(runtimeLoader));
      return null;
    }

    try {
      ClassNode node = read(classfileBuffer);
      if (!className.equals(node.name)) { throw mismatch("unexpected owner " + node.name); }
      ReadOnlyUiEconomyStep patch = new ReadOnlyUiEconomyStep();
      Match<ReadOnlyUiEconomyStep.Plan> match = patch.match(node);
      if (match.alreadyApplied) {
        record(className, "ALREADY_APPLIED");
        return null;
      }
      if (match.plan == null) { throw mismatch(match.reason); }
      patch.mutate(match.plan);
      patch.verify(match.plan, node);

      ClassWriter writer = new ClassWriter(0);
      node.accept(writer);
      byte[] transformed = writer.toByteArray();
      if (!patch.match(read(transformed)).alreadyApplied) { throw mismatch("owned marker is missing or malformed"); }
      record(className, "APPLIED");
      PrepatcherLog.info("APPLIED readOnlyUiNoGlobalEconomyStep to " + className
          + ": removed exact Global.getSector() -> getEconomy()" + " -> tripleStep() UI-only sequence");
      return transformed;
    } catch (StructuralMismatchException mismatch) {
      record(className, "SKIPPED_STRUCTURAL");
      PrepatcherLog.warn("SKIPPED_STRUCTURAL readOnlyUiNoGlobalEconomyStep in " + className + ": "
          + mismatch.getMessage() + "; original tripleStep remains active");
      return null;
    } catch (Throwable failure) {
      record(className, "SKIPPED_ERROR");
      PrepatcherLog.error(
          "SKIPPED_ERROR readOnlyUiNoGlobalEconomyStep in " + className + "; original tripleStep remains active",
          failure);
      return null;
    }
  }


  private boolean acceptsLoader(String className, ClassLoader loader) {
    if (runtimeLoader == null) return true;
    if (NEX_MARKET_CMD.equals(className)) {
      // Nexerelin is owned by its child mod loader. This transformation adds only a
      // constant marker and removes a call sequence, so it needs no cross-loader bridge.
      return isSameOrChildLoader(loader, runtimeLoader);
    }
    return loader == runtimeLoader;
  }

  static public boolean isSameOrChildLoader(ClassLoader candidate, ClassLoader expectedParent) {
    if (candidate == null || expectedParent == null) return candidate == expectedParent;
    for (ClassLoader current = candidate; current != null; current = current.getParent()) {
      if (current == expectedParent) return true;
    }
    return false;
  }


  private static ClassNode read(byte[] bytes) {
    ClassNode node = new ClassNode(Opcodes.ASM8);
    new ClassReader(bytes).accept(node, 0);
    return node;
  }

  private static void record(String className, String status) {
    System.setProperty(statusProperty(className), status);
  }

  static public String statusProperty(String className) {
    return switch (className) {
      case COMMAND_TAB -> "starsector.prepatcher.commandTabNoGlobalEconomyStepPatch";
      case COMMODITY_DETAIL_V2 -> "starsector.prepatcher.commodityDetailV2NoGlobalEconomyStepPatch";
      case COMMODITY_DETAIL_LEGACY -> "starsector.prepatcher.commodityDetailLegacyNoGlobalEconomyStepPatch";
      case MARKET_CMD -> "starsector.prepatcher.marketDefensesNoGlobalEconomyStepPatch";
      case NEX_MARKET_CMD -> "starsector.prepatcher.nexMarketDefensesNoGlobalEconomyStepPatch";
      default -> "starsector.prepatcher.unknownReadOnlyUiEconomyPatch";
    };
  }

  private static String loaderName(ClassLoader loader) {
    if (loader == null) return "bootstrap";
    return loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
  }

}
