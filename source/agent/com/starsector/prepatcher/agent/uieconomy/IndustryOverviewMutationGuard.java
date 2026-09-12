package com.starsector.prepatcher.agent.uieconomy;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.nextMeaningful;
import static com.starsector.prepatcher.agent.bytecode.Queries.previousMeaningful;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.patch.StructuredPatch;

/**
 * Panel surface of the industry market-mutation patch. recreateOverview keeps
 * the original IndustryListPanel.tripleStep() behind the shared mutation
 * guard so unknown/custom callers retain the global economy step. This class
 * is half of an atomic group with IndustryMarketMutations; the group
 * bookkeeping lives in IndustryMarketMutationTransformer.
 */
final class IndustryOverviewMutationGuard implements StructuredPatch<IndustryOverviewMutationGuard.Plan> {
  static final String TARGET = "com/fs/starfarer/campaign/ui/marketinfo/IndustryListPanel";

  private static final String RECREATE = "recreateOverview";
  private static final String RECREATE_DESC = "()V";

  private static final String MARKET_API = "com/fs/starfarer/api/campaign/econ/MarketAPI";
  private static final String ECONOMY_API = "com/fs/starfarer/api/campaign/econ/EconomyAPI";
  private static final String GLOBAL = "com/fs/starfarer/api/Global";
  private static final String SECTOR_API = "com/fs/starfarer/api/campaign/SectorAPI";
  private static final String RUNTIME = "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge";

  private static final String MARKET_DESC = "L" + MARKET_API + ";";
  private static final String PANEL_MARKET_FIELD = "market";

  private static final String ECON_STEP_GUARD = "shouldHandleVanillaUiMutationEconomyStep";
  private static final String ECON_STEP_GUARD_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Z";

  private static final String PANEL_MARKER = "spp$patched$industryOverviewMutationGuard";
  private static final String PANEL_MARKER_VALUE = "StarsectorPrepatcher:industry-overview-mutation-guard-v1";

  record Plan(MethodNode recreate, MethodInsnNode tripleStep, List<FieldNode> classFields) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    FieldNode marker = field(node, PANEL_MARKER);
    if (marker != null) {
      requireMarker(marker);
      requirePatchedPanel(node);
      return Match.alreadyApplied("industry overview mutation guard postcondition matches");
    }
    requireNoMarker(marker);
    FieldNode market = field(node, PANEL_MARKET_FIELD);
    if (market == null || !MARKET_DESC.equals(market.desc) || (market.access & Opcodes.ACC_STATIC) != 0) {
      throw mismatch("IndustryListPanel.market field changed");
    }
    MethodNode recreate = requireMethod(node, RECREATE, RECREATE_DESC);
    requireConcreteInstance(recreate);
    requireCount("tripleStep", calls(recreate, ECONOMY_API, "tripleStep", "()V").size(), 1);
    requireCount("existing guards", calls(recreate, RUNTIME, ECON_STEP_GUARD, ECON_STEP_GUARD_DESC).size(), 0);
    MethodInsnNode triple = uniqueCall(recreate, ECONOMY_API, "tripleStep", "()V");
    AbstractInsnNode getEconomyInsn = previousMeaningful(triple);
    AbstractInsnNode getSectorInsn = previousMeaningful(getEconomyInsn);
    if (!(getEconomyInsn instanceof MethodInsnNode getEconomy) || getEconomy.getOpcode() != Opcodes.INVOKEINTERFACE
        || !SECTOR_API.equals(getEconomy.owner) || !"getEconomy".equals(getEconomy.name)
        || !(("()L" + ECONOMY_API + ";").equals(getEconomy.desc))
        || !(getSectorInsn instanceof MethodInsnNode getSector) || getSector.getOpcode() != Opcodes.INVOKESTATIC
        || !GLOBAL.equals(getSector.owner) || !"getSector".equals(getSector.name)
        || !(("()L" + SECTOR_API + ";").equals(getSector.desc))) {
      throw mismatch("exact Global->Economy->tripleStep chain changed");
    }
    return Match.plan(new Plan(recreate, triple, node.fields));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    MethodNode method = plan.recreate();
    MethodInsnNode triple = plan.tripleStep();
    int economyLocal = method.maxLocals;
    method.maxLocals = economyLocal + 1;
    LabelNode afterStep = new LabelNode();
    InsnList guard = new InsnList();
    guard.add(new VarInsnNode(Opcodes.ASTORE, economyLocal));
    guard.add(new VarInsnNode(Opcodes.ALOAD, economyLocal));
    guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
    guard.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET, PANEL_MARKET_FIELD, MARKET_DESC));
    guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, ECON_STEP_GUARD, ECON_STEP_GUARD_DESC, false));
    guard.add(new JumpInsnNode(Opcodes.IFNE, afterStep));
    guard.add(new VarInsnNode(Opcodes.ALOAD, economyLocal));
    method.instructions.insertBefore(triple, guard);
    method.instructions.insert(triple, afterStep);
    plan.classFields()
        .add(new FieldNode(Opcodes.ASM8,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, PANEL_MARKER,
            "Ljava/lang/String;", null, PANEL_MARKER_VALUE));
    PatchReport report = new PatchReport();
    report.add("industry overview mutation guard", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requirePatchedPanel(node);
  }

  private static void requirePatchedPanel(ClassNode node) {
    requireMarker(field(node, PANEL_MARKER));
    MethodNode recreate = requireMethod(node, RECREATE, RECREATE_DESC);
    requireCount("preserved tripleStep fallback", calls(recreate, ECONOMY_API, "tripleStep", "()V").size(), 1);
    requireCount("industry mutation guard", calls(recreate, RUNTIME, ECON_STEP_GUARD, ECON_STEP_GUARD_DESC).size(), 1);
    MethodInsnNode guard = uniqueCall(recreate, RUNTIME, ECON_STEP_GUARD, ECON_STEP_GUARD_DESC);
    AbstractInsnNode marketReadInsn = previousMeaningful(guard);
    AbstractInsnNode thisLoadInsn = previousMeaningful(marketReadInsn);
    AbstractInsnNode economyLoadInsn = previousMeaningful(thisLoadInsn);
    if (!(marketReadInsn instanceof FieldInsnNode marketRead) || marketRead.getOpcode() != Opcodes.GETFIELD
        || !TARGET.equals(marketRead.owner) || !PANEL_MARKET_FIELD.equals(marketRead.name)
        || !MARKET_DESC.equals(marketRead.desc) || !(thisLoadInsn instanceof VarInsnNode thisLoad)
        || thisLoad.getOpcode() != Opcodes.ALOAD || thisLoad.var != 0
        || !(economyLoadInsn instanceof VarInsnNode economyLoad) || economyLoad.getOpcode() != Opcodes.ALOAD) {
      throw mismatch("industry guard identity arguments changed");
    }
    AbstractInsnNode branchInsn = nextMeaningful(guard);
    if (!(branchInsn instanceof JumpInsnNode branch) || branch.getOpcode() != Opcodes.IFNE) {
      throw mismatch("industry guard branch changed");
    }
  }

  private static void requireConcreteInstance(MethodNode method) {
    if ((method.access & Opcodes.ACC_PUBLIC) == 0
        || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
      throw mismatch(RECREATE + " access changed");
    }
  }

  private static List<MethodInsnNode> calls(MethodNode method, String owner, String name, String desc) {
    List<MethodInsnNode> result = new ArrayList<>();
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof MethodInsnNode call && owner.equals(call.owner) && name.equals(call.name)
          && desc.equals(call.desc))
        result.add(call);
    }
    return result;
  }

  private static MethodInsnNode uniqueCall(MethodNode method, String owner, String name, String desc) {
    List<MethodInsnNode> result = calls(method, owner, name, desc);
    requireCount(name + desc, result.size(), 1);
    return result.get(0);
  }

  private static MethodNode requireMethod(ClassNode node, String name, String desc) {
    for (MethodNode method : node.methods) {
      if (name.equals(method.name) && desc.equals(method.desc)) return method;
    }
    throw mismatch("missing method " + name + desc);
  }

  private static FieldNode field(ClassNode node, String name) {
    for (FieldNode field : node.fields) if (name.equals(field.name)) return field;
    return null;
  }

  private static void requireNoMarker(FieldNode marker) {
    if (marker != null) throw mismatch("owned marker already exists");
  }

  private static void requireMarker(FieldNode marker) {
    if (marker == null
        || marker.access != (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC)
        || !"Ljava/lang/String;".equals(marker.desc) || !PANEL_MARKER_VALUE.equals(marker.value)) {
      throw mismatch("owned marker is missing or malformed");
    }
  }


  private static void requireCount(String label, int actual, int expected) {
    if (actual != expected) { throw mismatch(label + " expected=" + expected + " actual=" + actual); }
  }
}
