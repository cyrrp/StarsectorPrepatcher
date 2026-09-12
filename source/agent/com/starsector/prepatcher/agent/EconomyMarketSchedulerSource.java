package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import java.util.List;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.MarketScheduler.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.*;
import static com.starsector.prepatcher.agent.Queries.*;

/** Routes the central Economy market call through the shared CampaignEngine tick. */
final class EconomyMarketSchedulerSource implements StructuredPatch<EconomyMarketSchedulerSource.Plan> {
  record Plan(ClassNode node, MethodNode advance, MethodInsnNode original) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode advance = requireMethod(node, "advance", "(F)V");
    PatchState registration =
        marketSchedulerRegistrationState(node, MARKET_SCHEDULER_COMPONENT_ECONOMY, "Economy market scheduler");
    String market = "com/fs/starfarer/api/campaign/econ/MarketAPI";
    String scheduledDesc = "(L" + market + ";FI)V";
    List<MethodInsnNode> originals = calls(advance, Opcodes.INVOKEINTERFACE, market, "advance", "(F)V");
    List<MethodInsnNode> hooks = calls(advance, Opcodes.INVOKESTATIC, HOOKS, "advanceMarketScheduled", scheduledDesc);

    boolean patched = originals.isEmpty() && hooks.size() == 1;
    if (patched) {
      requireEconomyMarketAdvanceOperands(hooks.get(0), true, 0);
      if (registration != PatchState.PATCHED) {
        return Match.incompatible("Economy market scheduler source lacks component registration");
      }
      return Match.alreadyApplied("Economy market scheduler source postcondition matches");
    }
    if (originals.size() != 1 || !hooks.isEmpty() || registration == PatchState.PATCHED) {
      return Match.incompatible("Economy market scheduler source is missing, duplicated, or partially patched");
    }
    MethodInsnNode original = originals.get(0);
    requireEconomyMarketAdvanceOperands(original, false, 0);
    return Match.plan(new Plan(node, advance, original));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    String market = "com/fs/starfarer/api/campaign/econ/MarketAPI";
    String scheduledDesc = "(L" + market + ";FI)V";
    plan.advance().instructions.insertBefore(plan.original(), new InsnNode(Opcodes.ICONST_0));
    plan.advance().instructions.set(plan.original(),
        new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "advanceMarketScheduled", scheduledDesc, false));
    installMarketSchedulerRegistration(plan.node(), MARKET_SCHEDULER_COMPONENT_ECONOMY, "Economy market scheduler");
    PatchReport report = new PatchReport();
    report.add("central economy source routed through common market scheduler", 1);
    report.add("market scheduler economy component registration", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    String market = "com/fs/starfarer/api/campaign/econ/MarketAPI";
    String scheduledDesc = "(L" + market + ";FI)V";
    requireCount("Economy common market scheduler calls",
        countCalls(plan.advance(), Opcodes.INVOKESTATIC, HOOKS, "advanceMarketScheduled", scheduledDesc), 1);
    requireCount("Economy vanilla central calls",
        countCalls(plan.advance(), Opcodes.INVOKEINTERFACE, market, "advance", "(F)V"), 0);
    if (marketSchedulerRegistrationState(node, MARKET_SCHEDULER_COMPONENT_ECONOMY,
        "Economy market scheduler") != PatchState.PATCHED) {
      throw mismatch("Economy market scheduler component registration is absent");
    }
  }

  private static void requireEconomyMarketAdvanceOperands(MethodInsnNode call, boolean patched, int expectedSource) {
    AbstractInsnNode source = patched ? previousMeaningful(call) : null;
    AbstractInsnNode amount = patched ? previousMeaningful(source) : previousMeaningful(call);
    AbstractInsnNode market = previousMeaningful(amount);
    if (patched && !isIntegerLdc(source, expectedSource)) {
      throw mismatch("Economy market scheduler source operand changed structurally");
    }
    if (!(amount instanceof VarInsnNode amountLoad) || amountLoad.getOpcode() != Opcodes.FLOAD || amountLoad.var != 1
        || !(market instanceof VarInsnNode marketLoad) || marketLoad.getOpcode() != Opcodes.ALOAD
        || marketLoad.var != 2) {
      throw mismatch("Economy market scheduler receiver/amount operands changed structurally");
    }
  }
}
