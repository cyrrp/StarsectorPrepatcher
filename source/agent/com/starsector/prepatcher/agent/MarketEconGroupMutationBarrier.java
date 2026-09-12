package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.EconomyGroupIndex.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.*;
import static com.starsector.prepatcher.agent.Queries.*;

final class MarketEconGroupMutationBarrier implements StructuredPatch<MarketEconGroupMutationBarrier.Plan> {
  record Plan(ClassNode node, MethodNode method, FieldInsnNode write) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode method = requireMethod(node, "setEconGroup", "(Ljava/lang/String;)V");
    int hooks = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "markEconomyGroupStructureChanged", "()V");
    PatchState registration =
        economyGroupIndexRegistrationState(node, ECONOMY_GROUP_INDEX_COMPONENT_MARKET, "Market economy-group index");
    if (hooks == 1 && registration == PatchState.PATCHED) {
      requireMarketEconGroupMutationShape(node.name, method, true);
      return Match.alreadyApplied("Market econ-group mutation barrier is complete");
    }
    if (hooks != 0 || registration == PatchState.PATCHED) {
      return Match.incompatible("Market economy-group index mutation patch is partial/foreign");
    }
    requireMarketEconGroupMutationShape(node.name, method, false);
    FieldInsnNode write = only(fields(method, Opcodes.PUTFIELD, node.name, "econGroup", "Ljava/lang/String;"),
        "Market.setEconGroup field write");
    return Match.plan(new Plan(node, method, write));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    plan.method().instructions.insert(plan.write(),
        new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "markEconomyGroupStructureChanged", "()V", false));
    installEconomyGroupIndexRegistration(plan.node(), ECONOMY_GROUP_INDEX_COMPONENT_MARKET,
        "Market economy-group index");

    PatchReport report = new PatchReport();
    report.add("Market.setEconGroup epoch barrier", 1);
    report.add("Market group-index capability registration", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireMarketEconGroupMutationShape(node.name, plan.method(), true);
  }
}
