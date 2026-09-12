package com.starsector.prepatcher.agent.economy;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.economy.MarketAdvance.*;
import static com.starsector.prepatcher.agent.economy.MarketScheduler.*;
import static com.starsector.prepatcher.agent.bytecode.Mutations.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.patch.PatchState;

public final class MarketComponentStepReplay implements StructuredPatch<MarketComponentStepReplay.Plan> {
  private static final String MARKET_STEP_REPLAY_BODY = "smo$advanceSingleStep";

  private final int componentId;

  public MarketComponentStepReplay(int componentId) {
    this.componentId = componentId;
  }

  record Plan(ClassNode node, MethodNode original) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    if (componentId < 1 || componentId > 3) {
      return Match.incompatible("invalid market component replay id " + componentId);
    }
    int schedulerComponent = marketReplaySchedulerComponent(componentId);
    PatchState registration =
        marketSchedulerRegistrationState(node, schedulerComponent, node.name + " market-step replay");
    MethodNode raw = optionalMethod(node, MARKET_STEP_REPLAY_BODY, "(F)V");
    int batchCalls = countCallsInClass(node, HOOKS, "hasCurrentMarketAdvanceBatch", "(" + MARKET_API_DESC + ")Z");
    if (raw != null || batchCalls != 0 || registration != PatchState.ORIGINAL) {
      requireMarketComponentStepReplayShape(node, componentId);
      return Match.alreadyApplied("market component step-replay wrapper matches");
    }
    requireMarketComponentReplayVanillaAnchors(node, componentId);
    MethodNode original = requireMethod(node, "advance", "(F)V");
    return Match.plan(new Plan(node, original));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    MethodNode wrapper = renameForPrivateOriginal(plan.node(), plan.original(), MARKET_STEP_REPLAY_BODY);
    installMarketComponentReplayWrapper(plan.node(), wrapper, componentId);
    installMarketSchedulerRegistration(plan.node(), marketReplaySchedulerComponent(componentId),
        plan.node().name + " market-step replay");
    PatchReport report = new PatchReport();
    report.add("exact market component step replay", 1);
    report.add("market-step replay scheduler capability", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireMarketComponentStepReplayShape(node, componentId);
  }

  private static int marketReplaySchedulerComponent(int componentId) {
    return switch (componentId) {
      case 1 -> MARKET_SCHEDULER_COMPONENT_MILITARY_BASE;
      case 2 -> MARKET_SCHEDULER_COMPONENT_LIONS_GUARD;
      case 3 -> MARKET_SCHEDULER_COMPONENT_RECENT_UNREST;
      default -> throw mismatch("invalid market component replay id " + componentId);
    };
  }

  private static void requireMarketComponentReplayVanillaAnchors(ClassNode node, int componentId) {
    MethodNode advance = requireMethod(node, "advance", "(F)V");
    if (componentId == 1 || componentId == 2) {
      requireCount(node.name + " BaseIndustry.advance anchor",
          countCalls(advance, Opcodes.INVOKESPECIAL, BASE_INDUSTRY, "advance", "(F)V"), 1);
      requireCount(node.name + " IntervalUtil.advance anchor",
          countCalls(advance, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/api/util/IntervalUtil", "advance", "(F)V"),
          componentId == 1 ? 2 : 1);
      requireCount(node.name + " IntervalUtil.intervalElapsed anchor", countCalls(advance, Opcodes.INVOKEVIRTUAL,
          "com/fs/starfarer/api/util/IntervalUtil", "intervalElapsed", "()Z"), 1);
    } else {
      requireField(node, "penalty", "I");
      requireField(node, "untilDecrease", "F");
      int removals = 0;
      for (AbstractInsnNode insn : advance.instructions.toArray()) {
        if (insn instanceof MethodInsnNode call && call.name.equals("removeSpecificCondition")) removals++;
      }
      requireCount("RecentUnrest removeSpecificCondition anchor", removals, 1);
    }
  }

  private static void installMarketComponentReplayWrapper(ClassNode node, MethodNode wrapper, int componentId) {
    final boolean recent = componentId == 3;
    final String marketOwner =
        recent ? "com/fs/starfarer/api/impl/campaign/econ/BaseMarketConditionPlugin" : BASE_INDUSTRY;
    InsnList code = new InsnList();
    LabelNode noBatch = new LabelNode();
    LabelNode outerCheck = new LabelNode();
    LabelNode outerBody = new LabelNode();
    LabelNode innerCheck = new LabelNode();
    LabelNode innerBody = new LabelNode();
    LabelNode nextRun = new LabelNode();
    LabelNode done = new LabelNode();

    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    if (recent) {
      code.add(new FieldInsnNode(Opcodes.GETFIELD, marketOwner, "market", MARKET_API_DESC));
    } else {
      code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "getMarket", "()" + MARKET_API_DESC, false));
    }
    code.add(new VarInsnNode(Opcodes.ASTORE, 2));
    code.add(new VarInsnNode(Opcodes.ALOAD, 2));
    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "hasCurrentMarketAdvanceBatch",
        "(" + MARKET_API_DESC + ")Z", false));
    code.add(new JumpInsnNode(Opcodes.IFEQ, noBatch));
    code.add(new VarInsnNode(Opcodes.ALOAD, 2));
    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "currentMarketAdvanceBatchRunCount",
        "(" + MARKET_API_DESC + ")I", false));
    code.add(new VarInsnNode(Opcodes.ISTORE, 3));
    code.add(new VarInsnNode(Opcodes.ILOAD, 3));
    code.add(new JumpInsnNode(Opcodes.IFLE, noBatch));
    code.add(new InsnNode(Opcodes.ICONST_0));
    code.add(new VarInsnNode(Opcodes.ISTORE, 4));
    code.add(outerCheck);
    code.add(new FrameNode(Opcodes.F_FULL, 5,
        new Object[]{node.name, Opcodes.FLOAT, MARKET_API, Opcodes.INTEGER, Opcodes.INTEGER}, 0, null));
    code.add(new VarInsnNode(Opcodes.ILOAD, 4));
    code.add(new VarInsnNode(Opcodes.ILOAD, 3));
    code.add(new JumpInsnNode(Opcodes.IF_ICMPLT, outerBody));
    code.add(new JumpInsnNode(Opcodes.GOTO, done));
    code.add(outerBody);
    code.add(new FrameNode(Opcodes.F_FULL, 5,
        new Object[]{node.name, Opcodes.FLOAT, MARKET_API, Opcodes.INTEGER, Opcodes.INTEGER}, 0, null));
    code.add(new VarInsnNode(Opcodes.ALOAD, 2));
    code.add(new VarInsnNode(Opcodes.ILOAD, 4));
    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "currentMarketAdvanceBatchRunAmount",
        "(" + MARKET_API_DESC + "I)F", false));
    code.add(new VarInsnNode(Opcodes.FSTORE, 5));
    code.add(new VarInsnNode(Opcodes.ALOAD, 2));
    code.add(new VarInsnNode(Opcodes.ILOAD, 4));
    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "currentMarketAdvanceBatchRunRepeats",
        "(" + MARKET_API_DESC + "I)I", false));
    code.add(new VarInsnNode(Opcodes.ISTORE, 6));
    code.add(new InsnNode(Opcodes.ICONST_0));
    code.add(new VarInsnNode(Opcodes.ISTORE, 7));
    code.add(innerCheck);
    code.add(new FrameNode(Opcodes.F_FULL, 8, new Object[]{node.name, Opcodes.FLOAT, MARKET_API, Opcodes.INTEGER,
        Opcodes.INTEGER, Opcodes.FLOAT, Opcodes.INTEGER, Opcodes.INTEGER}, 0, null));
    code.add(new VarInsnNode(Opcodes.ILOAD, 7));
    code.add(new VarInsnNode(Opcodes.ILOAD, 6));
    code.add(new JumpInsnNode(Opcodes.IF_ICMPLT, innerBody));
    code.add(new JumpInsnNode(Opcodes.GOTO, nextRun));
    code.add(innerBody);
    code.add(new FrameNode(Opcodes.F_FULL, 8, new Object[]{node.name, Opcodes.FLOAT, MARKET_API, Opcodes.INTEGER,
        Opcodes.INTEGER, Opcodes.FLOAT, Opcodes.INTEGER, Opcodes.INTEGER}, 0, null));
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new VarInsnNode(Opcodes.FLOAD, 5));
    code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, MARKET_STEP_REPLAY_BODY, "(F)V", false));
    code.add(pushInt(componentId));
    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "recordMarketComponentReplayedStep", "(I)V", false));
    if (recent) {
      code.add(new VarInsnNode(Opcodes.ALOAD, 2));
      code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "shouldContinueRecentUnrestReplay",
          "(" + MARKET_API_DESC + ")Z", false));
      code.add(new JumpInsnNode(Opcodes.IFEQ, done));
    }
    code.add(new IincInsnNode(7, 1));
    code.add(new JumpInsnNode(Opcodes.GOTO, innerCheck));
    code.add(nextRun);
    code.add(new FrameNode(Opcodes.F_FULL, 8, new Object[]{node.name, Opcodes.FLOAT, MARKET_API, Opcodes.INTEGER,
        Opcodes.INTEGER, Opcodes.FLOAT, Opcodes.INTEGER, Opcodes.INTEGER}, 0, null));
    code.add(new IincInsnNode(4, 1));
    code.add(new JumpInsnNode(Opcodes.GOTO, outerCheck));
    code.add(noBatch);
    code.add(new FrameNode(Opcodes.F_FULL, 3, new Object[]{node.name, Opcodes.FLOAT, MARKET_API}, 0, null));
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new VarInsnNode(Opcodes.FLOAD, 1));
    code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, MARKET_STEP_REPLAY_BODY, "(F)V", false));
    code.add(done);
    code.add(new FrameNode(Opcodes.F_FULL, 3, new Object[]{node.name, Opcodes.FLOAT, MARKET_API}, 0, null));
    code.add(new InsnNode(Opcodes.RETURN));
    replaceMethodBody(wrapper, code, 8);
  }

  private static void requireMarketComponentStepReplayShape(ClassNode node, int componentId) {
    MethodNode wrapper = requireMethod(node, "advance", "(F)V");
    MethodNode raw = requireMethod(node, MARKET_STEP_REPLAY_BODY, "(F)V");
    if ((raw.access & Opcodes.ACC_PRIVATE) == 0 || (raw.access & Opcodes.ACC_SYNTHETIC) == 0) {
      throw mismatch(node.name + " replay body is not private synthetic");
    }
    requireCount(node.name + " replay batch check",
        countCalls(wrapper, Opcodes.INVOKESTATIC, HOOKS, "hasCurrentMarketAdvanceBatch", "(" + MARKET_API_DESC + ")Z"),
        1);
    requireCount(node.name + " replay run count", countCalls(wrapper, Opcodes.INVOKESTATIC, HOOKS,
        "currentMarketAdvanceBatchRunCount", "(" + MARKET_API_DESC + ")I"), 1);
    requireCount(node.name + " replay run amount", countCalls(wrapper, Opcodes.INVOKESTATIC, HOOKS,
        "currentMarketAdvanceBatchRunAmount", "(" + MARKET_API_DESC + "I)F"), 1);
    requireCount(node.name + " replay run repeats", countCalls(wrapper, Opcodes.INVOKESTATIC, HOOKS,
        "currentMarketAdvanceBatchRunRepeats", "(" + MARKET_API_DESC + "I)I"), 1);
    requireCount(node.name + " replay raw calls",
        countCalls(wrapper, Opcodes.INVOKESPECIAL, node.name, MARKET_STEP_REPLAY_BODY, "(F)V"), 2);
    requireCount(node.name + " replay metric",
        countCalls(wrapper, Opcodes.INVOKESTATIC, HOOKS, "recordMarketComponentReplayedStep", "(I)V"), 1);
    requireCount(node.name + " wrapper recursion", countCalls(wrapper, -1, node.name, "advance", "(F)V"), 0);
    requireState(node.name + " scheduler semantic registration", marketSchedulerRegistrationState(node,
        marketReplaySchedulerComponent(componentId), node.name + " market-step replay"), PatchState.PATCHED);
    if (componentId == 3) {
      requireCount("RecentUnrest replay removal break", countCalls(wrapper, Opcodes.INVOKESTATIC, HOOKS,
          "shouldContinueRecentUnrestReplay", "(" + MARKET_API_DESC + ")Z"), 1);
    }
  }
}
