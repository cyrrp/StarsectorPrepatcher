package com.starsector.prepatcher.agent.uieconomy;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.instructionIndex;
import static com.starsector.prepatcher.agent.bytecode.Queries.nextMeaningful;
import static com.starsector.prepatcher.agent.bytecode.Queries.previousMeaningful;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.patch.StructuredPatch;

/**
 * Removes the exact sector-wide economy refresh whose only trigger is opening a
 * read-only UI surface. Each target has an independent structural contract and
 * marker value; a changed call site retains the original tripleStep()
 * invocation.
 */
final class ReadOnlyUiEconomyStep implements StructuredPatch<ReadOnlyUiEconomyStep.Plan> {
  private static final String COMMAND_TAB = "com/fs/starfarer/campaign/command/F";
  private static final String COMMODITY_DETAIL_V2 =
      "com/fs/starfarer/campaign/ui/marketinfo/cdd/CommodityDetailDialogV2";
  private static final String COMMODITY_DETAIL_LEGACY = "com/fs/starfarer/campaign/ui/marketinfo/CommodityDetailDialog";
  private static final String MARKET_CMD = "com/fs/starfarer/api/impl/campaign/rulecmd/salvage/MarketCMD";
  private static final String NEX_MARKET_CMD = "com/fs/starfarer/api/impl/campaign/rulecmd/salvage/Nex_MarketCMD";

  private static final String GLOBAL = "com/fs/starfarer/api/Global";
  private static final String SECTOR_API = "com/fs/starfarer/api/campaign/SectorAPI";
  private static final String ECONOMY_API = "com/fs/starfarer/api/campaign/econ/EconomyAPI";
  private static final String MARKET_API_DESC = "Lcom/fs/starfarer/api/campaign/econ/MarketAPI;";
  private static final String COMMODITY_ON_MARKET = "com/fs/starfarer/campaign/econ/CommodityOnMarket";
  private static final String MARKER = "spp$patched$readOnlyUiNoGlobalEconomyStep";
  private static final String MARKER_PREFIX = "StarsectorPrepatcher:read-only-ui-economy-v1:";

  enum Target {
    COMMAND_TAB(ReadOnlyUiEconomyStep.COMMAND_TAB), COMMODITY_DETAIL_V2(
        ReadOnlyUiEconomyStep.COMMODITY_DETAIL_V2), COMMODITY_DETAIL_LEGACY(
            ReadOnlyUiEconomyStep.COMMODITY_DETAIL_LEGACY), MARKET_CMD(
                ReadOnlyUiEconomyStep.MARKET_CMD), NEX_MARKET_CMD(ReadOnlyUiEconomyStep.NEX_MARKET_CMD);

    final String className;

    Target(String className) {
      this.className = className;
    }

    static Target forName(String className) {
      for (Target target : values()) {
        if (target.className.equals(className)) return target;
      }
      return null;
    }
  }

  record Plan(Target target, MethodNode method, MethodInsnNode getSector, MethodInsnNode getEconomy,
      MethodInsnNode tripleStep, List<FieldNode> classFields) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    Target target = Target.forName(node.name);
    if (target == null) return Match.incompatible("unsupported target " + node.name);
    FieldNode marker = field(node, MARKER);
    if (marker != null) {
      requireMarker(marker, target);
      requirePatchedShape(node, target);
      return Match.alreadyApplied("read-only UI economy-step postcondition matches");
    }

    MethodNode method = targetMethod(node, target);
    MethodInsnNode tripleStep = requireOriginalShape(node, method, target);
    MethodInsnNode getEconomy = (MethodInsnNode) previousMeaningful(tripleStep);
    MethodInsnNode getSector = (MethodInsnNode) previousMeaningful(getEconomy);
    return Match.plan(new Plan(target, method, getSector, getEconomy, tripleStep, node.fields));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    MethodNode method = plan.method();
    method.instructions.remove(plan.getSector());
    method.instructions.remove(plan.getEconomy());
    method.instructions.remove(plan.tripleStep());
    plan.classFields()
        .add(new FieldNode(Opcodes.ASM8,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, MARKER,
            "Ljava/lang/String;", null, markerValue(plan.target())));
    PatchReport report = new PatchReport();
    report.add("removed read-only UI global economy step", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requirePatchedShape(node, plan.target());
  }

  private static MethodInsnNode requireOriginalShape(ClassNode node, MethodNode method, Target target) {
    requireNoMarker(field(node, MARKER));
    requireTargetOwner(node, target);
    requireConcreteInstance(method, target);
    if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
      throw mismatch("target method gained exception regions");
    }
    requireCount("class tripleStep calls", countCalls(node, ECONOMY_API, "tripleStep", "()V"), 1);
    requireCount("target tripleStep calls", countCalls(method, ECONOMY_API, "tripleStep", "()V"), 1);

    MethodInsnNode triple = uniqueCall(method, ECONOMY_API, "tripleStep", "()V");
    if (triple.getOpcode() != Opcodes.INVOKEINTERFACE || !triple.itf) {
      throw mismatch("tripleStep invocation kind changed");
    }
    AbstractInsnNode getEconomyInsn = previousMeaningful(triple);
    AbstractInsnNode getSectorInsn = previousMeaningful(getEconomyInsn);
    if (!(getEconomyInsn instanceof MethodInsnNode getEconomy) || getEconomy.getOpcode() != Opcodes.INVOKEINTERFACE
        || !getEconomy.itf || !SECTOR_API.equals(getEconomy.owner) || !"getEconomy".equals(getEconomy.name)
        || !("()L" + ECONOMY_API + ";").equals(getEconomy.desc)) {
      throw mismatch("exact SectorAPI.getEconomy receiver chain changed");
    }
    if (!(getSectorInsn instanceof MethodInsnNode getSector) || getSector.getOpcode() != Opcodes.INVOKESTATIC
        || getSector.itf || !GLOBAL.equals(getSector.owner) || !"getSector".equals(getSector.name)
        || !("()L" + SECTOR_API + ";").equals(getSector.desc)) {
      throw mismatch("exact Global.getSector receiver chain changed");
    }

    switch (target) {
      case COMMAND_TAB -> requireCommandAnchor(triple);
      case COMMODITY_DETAIL_V2, COMMODITY_DETAIL_LEGACY -> requireCommodityAnchor(triple);
      case MARKET_CMD, NEX_MARKET_CMD -> requireMarketDefensesAnchor(method, getSector, triple, target.className);
    }
    return triple;
  }

  private static void requireCommandAnchor(MethodInsnNode triple) {
    if (nextMeaningful(triple) == null || nextMeaningful(triple).getOpcode() != Opcodes.RETURN) {
      throw mismatch("Command constructor tripleStep is no longer terminal");
    }
  }

  private static void requireCommodityAnchor(MethodInsnNode triple) {
    AbstractInsnNode loadThis = nextMeaningful(triple);
    AbstractInsnNode loadCommodity = nextMeaningful(loadThis);
    AbstractInsnNode getMarketInsn = nextMeaningful(loadCommodity);
    if (!(loadThis instanceof VarInsnNode thisLoad) || thisLoad.getOpcode() != Opcodes.ALOAD || thisLoad.var != 0
        || !(loadCommodity instanceof VarInsnNode commodityLoad) || commodityLoad.getOpcode() != Opcodes.ALOAD
        || commodityLoad.var != 1 || !(getMarketInsn instanceof MethodInsnNode getMarket)
        || getMarket.getOpcode() != Opcodes.INVOKEVIRTUAL || !COMMODITY_ON_MARKET.equals(getMarket.owner)
        || !"getMarket".equals(getMarket.name) || !"()Lcom/fs/starfarer/campaign/econ/Market;".equals(getMarket.desc)) {
      throw mismatch("commodity-detail post-step market/faction initialization anchor changed");
    }
  }

  private static void requireMarketDefensesAnchor(MethodNode method, MethodInsnNode getSector, MethodInsnNode triple,
      String surfaceOwner) {
    AbstractInsnNode branchInsn = previousMeaningful(getSector);
    AbstractInsnNode marketReadInsn = previousMeaningful(branchInsn);
    AbstractInsnNode loadThisInsn = previousMeaningful(marketReadInsn);
    if (!(branchInsn instanceof JumpInsnNode branch) || branch.getOpcode() != Opcodes.IFNULL
        || !(marketReadInsn instanceof FieldInsnNode marketRead) || marketRead.getOpcode() != Opcodes.GETFIELD
        || !surfaceOwner.equals(marketRead.owner) || !"market".equals(marketRead.name)
        || !MARKET_API_DESC.equals(marketRead.desc) || !(loadThisInsn instanceof VarInsnNode thisLoad)
        || thisLoad.getOpcode() != Opcodes.ALOAD || thisLoad.var != 0) {
      throw mismatch("MarketCMD market-null guard changed");
    }
    AbstractInsnNode afterStep = nextMeaningful(triple);
    if (!(afterStep instanceof VarInsnNode interactionFleetLoad) || interactionFleetLoad.getOpcode() != Opcodes.ALOAD
        || interactionFleetLoad.var != 2 || nextMeaningful(branch.label) != afterStep) {
      throw mismatch("MarketCMD null-guard join changed");
    }

    int interaction = instructionIndex(method, surfaceOwner, "getInteractionTargetForFIDPI",
        "()Lcom/fs/starfarer/api/campaign/CampaignFleetAPI;");
    int stationFleet =
        instructionIndex(method, surfaceOwner, "getStationFleet", "()Lcom/fs/starfarer/api/campaign/CampaignFleetAPI;");
    int stationState = instructionIndex(method, surfaceOwner, "getStationState",
        "()Lcom/fs/starfarer/api/impl/campaign/rulecmd/salvage/" + "MarketCMD$StationState;");
    int globalStep = instructionIndex(method, ECONOMY_API, "tripleStep", "()V");
    if (interaction < 0 || stationFleet <= interaction || stationState <= stationFleet || globalStep <= stationState) {
      throw mismatch("MarketCMD defense state is no longer fully captured before tripleStep");
    }
  }

  private static void requirePatchedShape(ClassNode node, Target target) {
    requireMarker(field(node, MARKER), target);
    requireTargetOwner(node, target);
    MethodNode method = targetMethod(node, target);
    requireConcreteInstance(method, target);
    requireCount("patched class tripleStep calls", countCalls(node, ECONOMY_API, "tripleStep", "()V"), 0);
    requireCount("patched target tripleStep calls", countCalls(method, ECONOMY_API, "tripleStep", "()V"), 0);
  }

  private static MethodNode targetMethod(ClassNode node, Target target) {
    return switch (target) {
      case COMMAND_TAB -> requireMethod(node, "<init>", "(Ljava/lang/Object;Lcom/fs/starfarer/coreui/_$o;)V");
      case COMMODITY_DETAIL_V2,
          COMMODITY_DETAIL_LEGACY ->
        requireMethod(node, "<init>",
            "(Lcom/fs/starfarer/campaign/econ/CommodityOnMarket;" + "Lcom/fs/starfarer/ui/newui/L;"
                + "Lcom/fs/starfarer/ui/interfacenew;" + "Lcom/fs/starfarer/ui/oo0O$o;)V");
      case MARKET_CMD, NEX_MARKET_CMD -> requireMethod(node, "showDefenses", "(Z)V");
    };
  }

  private static void requireConcreteInstance(MethodNode method, Target target) {
    if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
      throw mismatch("target method is no longer concrete instance code");
    }
    if (target == Target.MARKET_CMD || target == Target.NEX_MARKET_CMD) {
      if ((method.access & Opcodes.ACC_PROTECTED) == 0) {
        throw mismatch("MarketCMD.showDefenses is no longer protected");
      }
    } else if ((method.access & Opcodes.ACC_PUBLIC) == 0) { throw mismatch("UI constructor is no longer public"); }
  }


  private static MethodInsnNode uniqueCall(MethodNode method, String owner, String name, String desc) {
    MethodInsnNode result = null;
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof MethodInsnNode call && owner.equals(call.owner) && name.equals(call.name)
          && desc.equals(call.desc)) {
        if (result != null) { throw mismatch("multiple " + owner + '.' + name + desc); }
        result = call;
      }
    }
    if (result == null) throw mismatch("missing " + owner + '.' + name + desc);
    return result;
  }

  private static int countCalls(ClassNode node, String owner, String name, String desc) {
    int count = 0;
    for (MethodNode method : node.methods) count += countCalls(method, owner, name, desc);
    return count;
  }

  private static int countCalls(MethodNode method, String owner, String name, String desc) {
    int count = 0;
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof MethodInsnNode call && owner.equals(call.owner) && name.equals(call.name)
          && desc.equals(call.desc))
        count++;
    }
    return count;
  }


  private static MethodNode requireMethod(ClassNode node, String name, String desc) {
    MethodNode result = null;
    for (MethodNode method : node.methods) {
      if (name.equals(method.name) && desc.equals(method.desc)) {
        if (result != null) { throw mismatch("duplicate method " + name + desc); }
        result = method;
      }
    }
    if (result == null) throw mismatch("missing method " + name + desc);
    return result;
  }

  private static FieldNode field(ClassNode node, String name) {
    for (FieldNode field : node.fields) if (name.equals(field.name)) return field;
    return null;
  }

  private static void requireNoMarker(FieldNode marker) {
    if (marker != null) throw mismatch("owned marker already exists");
  }

  private static void requireMarker(FieldNode marker, Target target) {
    int access = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
    if (marker == null || marker.access != access || !"Ljava/lang/String;".equals(marker.desc)
        || !markerValue(target).equals(marker.value)) {
      throw mismatch("owned marker is missing or malformed");
    }
  }

  private static String markerValue(Target target) {
    return MARKER_PREFIX + switch (target) {
      case COMMAND_TAB -> "command-tab";
      case COMMODITY_DETAIL_V2 -> "commodity-detail-v2";
      case COMMODITY_DETAIL_LEGACY -> "commodity-detail-legacy";
      case MARKET_CMD -> "market-defenses";
      case NEX_MARKET_CMD -> "nex-market-defenses";
    };
  }

  private static void requireTargetOwner(ClassNode node, Target target) {
    if (target == Target.NEX_MARKET_CMD && !MARKET_CMD.equals(node.superName)) {
      throw mismatch("Nex_MarketCMD superclass changed");
    }
  }

  private static void requireCount(String label, int actual, int expected) {
    if (actual != expected) { throw mismatch(label + " expected=" + expected + " actual=" + actual); }
  }
}
