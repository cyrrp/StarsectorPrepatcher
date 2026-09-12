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
import static com.starsector.prepatcher.agent.bytecode.Queries.instructionIndex;
import static com.starsector.prepatcher.agent.bytecode.Queries.nextMeaningful;
import static com.starsector.prepatcher.agent.bytecode.Queries.previousMeaningful;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.patch.StructuredPatch;

/**
 * Exact-call-site mutation context patch for the vanilla market overview
 * panel. The four proven policy setters are replaced by runtime wrappers that
 * publish a one-shot reason/scope context, and recreateWithEconUpdate keeps the
 * original global Economy.tripleStep() behind the shared mutation guard for
 * unknown callers.
 */
final class MarketOverviewMutationRefresh implements StructuredPatch<MarketOverviewMutationRefresh.Plan> {
  private static final String TARGET = "com/fs/starfarer/campaign/ui/marketinfo/s";
  private static final String ACTION = "actionPerformed";
  private static final String ACTION_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)V";
  private static final String RECREATE = "recreateWithEconUpdate";
  private static final String RECREATE_DESC = "()V";
  private static final String MARKET_API = "com/fs/starfarer/api/campaign/econ/MarketAPI";
  private static final String ECONOMY_API = "com/fs/starfarer/api/campaign/econ/EconomyAPI";
  private static final String GLOBAL = "com/fs/starfarer/api/Global";
  private static final String SECTOR_API = "com/fs/starfarer/api/campaign/SectorAPI";
  private static final String RUNTIME = "com/fs/starfarer/api/StarsectorPrepatcherUiEconomyRuntime";
  private static final String MARKET_DESC = "L" + MARKET_API + ";";
  private static final String LEGACY_MARKET_FIELD = "String.interface$float";
  private static final String REPAIRED_MARKET_FIELD = "String_interface$float";
  private static final String MARKER = "spp$patched$marketOverviewMutationRefresh";
  private static final String MARKER_VALUE = "StarsectorPrepatcher:market-overview-mutation-refresh-v1";

  private static final String APPLY_FREE_PORT = "applyVanillaFreePortMutation";
  private static final String APPLY_IMMIGRATION_CLOSED = "applyVanillaImmigrationClosedMutation";
  private static final String APPLY_STOCKPILE_POLICY = "applyVanillaStockpilePolicyMutation";
  private static final String APPLY_IMMIGRATION_INCENTIVES = "applyVanillaImmigrationIncentivesMutation";
  private static final String BOOL_MUTATION_DESC = "(" + MARKET_DESC + "Z)V";
  private static final String BOXED_BOOL_MUTATION_DESC = "(" + MARKET_DESC + "Ljava/lang/Boolean;)V";
  private static final String ECON_STEP_GUARD = "shouldHandleVanillaUiMutationEconomyStep";
  private static final String ECON_STEP_GUARD_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Z";

  record Plan(MethodNode action, MethodNode recreate, List<MethodInsnNode> freePort,
      List<MethodInsnNode> immigrationClosed, List<MethodInsnNode> stockpile, List<MethodInsnNode> incentives,
      MethodInsnNode tripleStep, String marketField, List<FieldNode> classFields) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    String marketField = requireMarketFieldName(node);
    FieldNode marker = field(node, MARKER);
    if (marker != null) {
      requireMarker(marker);
      requirePatchedShape(node, marketField);
      return Match.alreadyApplied("market overview mutation refresh postcondition matches");
    }

    requireOriginalShape(node, marketField);
    MethodNode action = requireMethod(node, ACTION, ACTION_DESC);
    MethodNode recreate = requireMethod(node, RECREATE, RECREATE_DESC);
    return Match.plan(new Plan(action, recreate, calls(action, MARKET_API, "setFreePort", "(Z)V"),
        calls(action, MARKET_API, "setImmigrationClosed", "(Z)V"),
        calls(action, MARKET_API, "setUseStockpilesForShortages", "(Z)V"),
        calls(action, MARKET_API, "setImmigrationIncentivesOn", "(Ljava/lang/Boolean;)V"),
        uniqueCall(recreate, ECONOMY_API, "tripleStep", "()V"), marketField, node.fields));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    // Both free-port calls are wrapped. The runtime records a targeted/global
    // scope only when the value actually changes, so closing immigration
    // while free port is already off can still use the proven local path.
    for (MethodInsnNode call : plan.freePort()) replaceWithStatic(call, APPLY_FREE_PORT, BOOL_MUTATION_DESC);
    for (MethodInsnNode call : plan.immigrationClosed()) {
      replaceWithStatic(call, APPLY_IMMIGRATION_CLOSED, BOOL_MUTATION_DESC);
    }
    replaceWithStatic(plan.stockpile().get(0), APPLY_STOCKPILE_POLICY, BOOL_MUTATION_DESC);
    replaceWithStatic(plan.incentives().get(0), APPLY_IMMIGRATION_INCENTIVES, BOXED_BOOL_MUTATION_DESC);

    MethodNode method = plan.recreate();
    int economyLocal = method.maxLocals;
    method.maxLocals = economyLocal + 1;
    LabelNode afterStep = new LabelNode();
    InsnList guard = new InsnList();
    guard.add(new VarInsnNode(Opcodes.ASTORE, economyLocal));
    guard.add(new VarInsnNode(Opcodes.ALOAD, economyLocal));
    guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
    guard.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET, plan.marketField(), MARKET_DESC));
    guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, ECON_STEP_GUARD, ECON_STEP_GUARD_DESC, false));
    guard.add(new JumpInsnNode(Opcodes.IFNE, afterStep));
    guard.add(new VarInsnNode(Opcodes.ALOAD, economyLocal));
    method.instructions.insertBefore(plan.tripleStep(), guard);
    method.instructions.insert(plan.tripleStep(), afterStep);

    plan.classFields()
        .add(new FieldNode(Opcodes.ASM8,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, MARKER,
            "Ljava/lang/String;", null, MARKER_VALUE));
    PatchReport report = new PatchReport();
    report.add("market overview mutation wrappers", 1);
    report.add("recreate economy-step guard", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requirePatchedShape(node, plan.marketField());
  }

  private static void replaceWithStatic(MethodInsnNode call, String runtimeName, String desc) {
    call.setOpcode(Opcodes.INVOKESTATIC);
    call.owner = RUNTIME;
    call.name = runtimeName;
    call.desc = desc;
    call.itf = false;
  }

  private static void requireOriginalShape(ClassNode node, String marketField) {
    requireNoMarker(field(node, MARKER));
    requireResolvedMarketField(node, marketField);
    MethodNode action = requireMethod(node, ACTION, ACTION_DESC);
    MethodNode recreate = requireMethod(node, RECREATE, RECREATE_DESC);
    requireConcreteInstance(action, ACTION);
    requireConcreteInstance(recreate, RECREATE);
    if (action.tryCatchBlocks != null && !action.tryCatchBlocks.isEmpty()) {
      throw mismatch("actionPerformed gained exception regions");
    }
    requireCount("setFreePort calls", calls(action, MARKET_API, "setFreePort", "(Z)V").size(), 2);
    requireCount("setImmigrationClosed calls", calls(action, MARKET_API, "setImmigrationClosed", "(Z)V").size(), 2);
    requireCount("stockpile-policy calls", calls(action, MARKET_API, "setUseStockpilesForShortages", "(Z)V").size(), 1);
    requireCount("immigration-incentive calls",
        calls(action, MARKET_API, "setImmigrationIncentivesOn", "(Ljava/lang/Boolean;)V").size(), 1);
    requireCount("action recreate calls", calls(action, TARGET, RECREATE, RECREATE_DESC).size(), 5);

    // Verify the four UI anchors and order rather than relying only on the
    // occurrence number of an obfuscated setter.
    int freePortButton =
        callIndex(action, "com/fs/starfarer/campaign/ui/marketinfo/oOoO", "getFreePort", "()Lcom/fs/starfarer/ui/n;");
    int closedButton =
        callIndex(action, "com/fs/starfarer/campaign/ui/marketinfo/oOoO", "getClosed", "()Lcom/fs/starfarer/ui/n;");
    int stockpileButton = callIndex(action, "com/fs/starfarer/campaign/ui/marketinfo/ShippingPanel", "getUseStockpiles",
        "()Lcom/fs/starfarer/ui/n;");
    int incentivesButton = callIndex(action, "com/fs/starfarer/campaign/ui/marketinfo/oOoO", "getIncentivesToggle",
        "()Lcom/fs/starfarer/ui/n;");
    int adminButton =
        callIndex(action, "com/fs/starfarer/campaign/ui/marketinfo/t", "getPortrait", "()Lcom/fs/starfarer/ui/n;");
    if (!(freePortButton >= 0 && freePortButton < closedButton && closedButton < stockpileButton
        && stockpileButton < incentivesButton && incentivesButton < adminButton)) {
      throw mismatch("market policy branch anchors/order changed");
    }
    List<MethodInsnNode> freePortCalls = calls(action, MARKET_API, "setFreePort", "(Z)V");
    List<MethodInsnNode> closedCalls = calls(action, MARKET_API, "setImmigrationClosed", "(Z)V");
    int firstFreePort = instructionIndex(action, freePortCalls.get(0));
    int secondFreePort = instructionIndex(action, freePortCalls.get(1));
    int firstClosed = instructionIndex(action, closedCalls.get(0));
    int secondClosed = instructionIndex(action, closedCalls.get(1));
    if (!(freePortButton < firstFreePort && firstFreePort < firstClosed && firstClosed < closedButton
        && closedButton < secondFreePort && secondFreePort < secondClosed && secondClosed < stockpileButton)) {
      throw mismatch("free-port/immigration mutation layout changed");
    }
    requireOriginalRecreate(recreate);
  }

  private static void requireOriginalRecreate(MethodNode method) {
    if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
      throw mismatch("recreateWithEconUpdate gained exception regions");
    }
    requireCount("recreate tripleStep calls", calls(method, ECONOMY_API, "tripleStep", "()V").size(), 1);
    requireCount("recreate guards", calls(method, RUNTIME, ECON_STEP_GUARD, ECON_STEP_GUARD_DESC).size(), 0);
    MethodInsnNode triple = uniqueCall(method, ECONOMY_API, "tripleStep", "()V");
    AbstractInsnNode getEconomyInsn = previousMeaningful(triple);
    AbstractInsnNode getSectorInsn = previousMeaningful(getEconomyInsn);
    if (!(getEconomyInsn instanceof MethodInsnNode getEconomy) || getEconomy.getOpcode() != Opcodes.INVOKEINTERFACE
        || !getEconomy.itf || !SECTOR_API.equals(getEconomy.owner) || !"getEconomy".equals(getEconomy.name)
        || !("()L" + ECONOMY_API + ";").equals(getEconomy.desc) || !(getSectorInsn instanceof MethodInsnNode getSector)
        || getSector.getOpcode() != Opcodes.INVOKESTATIC || getSector.itf || !GLOBAL.equals(getSector.owner)
        || !"getSector".equals(getSector.name) || !("()L" + SECTOR_API + ";").equals(getSector.desc)) {
      throw mismatch("exact Global->Sector->Economy tripleStep chain changed");
    }
  }

  private static void requirePatchedShape(ClassNode node, String marketField) {
    requireResolvedMarketField(node, marketField);
    requireMarker(field(node, MARKER));
    MethodNode action = requireMethod(node, ACTION, ACTION_DESC);
    MethodNode recreate = requireMethod(node, RECREATE, RECREATE_DESC);
    requireCount("patched vanilla free-port calls", calls(action, MARKET_API, "setFreePort", "(Z)V").size(), 0);
    requireCount("patched vanilla immigration-closed calls",
        calls(action, MARKET_API, "setImmigrationClosed", "(Z)V").size(), 0);
    requireCount("patched vanilla stockpile calls",
        calls(action, MARKET_API, "setUseStockpilesForShortages", "(Z)V").size(), 0);
    requireCount("patched vanilla incentives calls",
        calls(action, MARKET_API, "setImmigrationIncentivesOn", "(Ljava/lang/Boolean;)V").size(), 0);
    requireCount("free-port wrappers", calls(action, RUNTIME, APPLY_FREE_PORT, BOOL_MUTATION_DESC).size(), 2);
    requireCount("immigration wrappers", calls(action, RUNTIME, APPLY_IMMIGRATION_CLOSED, BOOL_MUTATION_DESC).size(),
        2);
    requireCount("stockpile wrappers", calls(action, RUNTIME, APPLY_STOCKPILE_POLICY, BOOL_MUTATION_DESC).size(), 1);
    requireCount("incentive wrappers",
        calls(action, RUNTIME, APPLY_IMMIGRATION_INCENTIVES, BOXED_BOOL_MUTATION_DESC).size(), 1);
    requireCount("guarded tripleStep calls", calls(recreate, ECONOMY_API, "tripleStep", "()V").size(), 1);
    requireCount("mutation economy guards", calls(recreate, RUNTIME, ECON_STEP_GUARD, ECON_STEP_GUARD_DESC).size(), 1);
    MethodInsnNode guard = uniqueCall(recreate, RUNTIME, ECON_STEP_GUARD, ECON_STEP_GUARD_DESC);
    AbstractInsnNode marketReadInsn = previousMeaningful(guard);
    AbstractInsnNode thisLoadInsn = previousMeaningful(marketReadInsn);
    AbstractInsnNode economyLoadInsn = previousMeaningful(thisLoadInsn);
    if (!(marketReadInsn instanceof FieldInsnNode marketRead) || marketRead.getOpcode() != Opcodes.GETFIELD
        || !TARGET.equals(marketRead.owner) || !marketField.equals(marketRead.name)
        || !MARKET_DESC.equals(marketRead.desc) || !(thisLoadInsn instanceof VarInsnNode thisLoad)
        || thisLoad.getOpcode() != Opcodes.ALOAD || thisLoad.var != 0
        || !(economyLoadInsn instanceof VarInsnNode economyLoad) || economyLoad.getOpcode() != Opcodes.ALOAD) {
      throw mismatch("mutation guard identity arguments changed");
    }
    AbstractInsnNode branchInsn = nextMeaningful(guard);
    if (!(branchInsn instanceof JumpInsnNode branch) || branch.getOpcode() != Opcodes.IFNE) {
      throw mismatch("mutation guard branch changed");
    }
  }

  private static void requireConcreteInstance(MethodNode method, String name) {
    if ((method.access & Opcodes.ACC_PUBLIC) == 0
        || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
      throw mismatch(name + " access changed");
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
    List<MethodInsnNode> calls = calls(method, owner, name, desc);
    requireCount(name + desc + " calls", calls.size(), 1);
    return calls.get(0);
  }

  private static int callIndex(MethodNode method, String owner, String name, String desc) {
    int index = 0;
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof MethodInsnNode call && owner.equals(call.owner) && name.equals(call.name)
          && desc.equals(call.desc))
        return index;
      index++;
    }
    return -1;
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

  private static String requireMarketFieldName(ClassNode node) {
    FieldNode match = null;
    for (FieldNode candidate : node.fields) {
      if (!LEGACY_MARKET_FIELD.equals(candidate.name) && !REPAIRED_MARKET_FIELD.equals(candidate.name)) {
        continue;
      }
      if (match != null) { throw mismatch("market field aliases are ambiguous"); }
      match = candidate;
    }
    if (match == null || !MARKET_DESC.equals(match.desc) || (match.access & Opcodes.ACC_STATIC) != 0) {
      throw mismatch("market identity field changed");
    }
    return match.name;
  }

  private static void requireResolvedMarketField(ClassNode node, String name) {
    String resolved = requireMarketFieldName(node);
    if (!resolved.equals(name)) { throw mismatch("market field alias changed during transformation"); }
  }

  private static void requireNoMarker(FieldNode marker) {
    if (marker != null) throw mismatch("owned marker already exists");
  }

  private static void requireMarker(FieldNode marker) {
    if (marker == null
        || marker.access != (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC)
        || !"Ljava/lang/String;".equals(marker.desc) || !MARKER_VALUE.equals(marker.value)) {
      throw mismatch("owned marker is missing or malformed");
    }
  }

  private static void requireCount(String label, int actual, int expected) {
    if (actual != expected) { throw mismatch(label + " expected=" + expected + " actual=" + actual); }
  }
}
