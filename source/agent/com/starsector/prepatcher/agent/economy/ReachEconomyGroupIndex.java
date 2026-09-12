package com.starsector.prepatcher.agent.economy;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.economy.EconomyGroupIndex.*;
import static com.starsector.prepatcher.agent.bytecode.Mutations.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.economy.PersistentState.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.patch.PatchState;

public final class ReachEconomyGroupIndex implements StructuredPatch<ReachEconomyGroupIndex.Plan> {
  private static final String GETTER_DESC = "(Ljava/lang/String;)Ljava/util/List;";
  private static final String HOOK_DESC =
      "(L" + REACH_ECONOMY + ";Ljava/lang/Object;Ljava/lang/String;)Ljava/util/List;";

  record Plan(ClassNode node, MethodNode original, MethodNode addMarket, AbstractInsnNode addPop,
      MethodNode removeMarket, AbstractInsnNode removePop) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    boolean fieldPresent =
        hasExactSyntheticField(node, ECONOMY_GROUP_INDEX_STATE_FIELD, PERSISTENT_STATE_DESC, persistentStateAccess());
    MethodNode original = requireEconomyGroupIndexWrapperSource(node, GETTER_DESC, HOOK_DESC);
    requireEconomyGroupRawGetterShape(node.name, original);
    PatchState addState = economyGroupMutationState(node, "addMarket");
    PatchState removeState = economyGroupMutationState(node, "removeMarket");
    PatchState registration = economyGroupIndexRegistrationState(node, ECONOMY_GROUP_INDEX_COMPONENT_REACH_ECONOMY,
        "ReachEconomy economy-group index");

    boolean wrapperPatched = original.name.equals(ECONOMY_GROUP_INDEX_RAW_GETTER);
    if (fieldPresent && wrapperPatched && addState == PatchState.PATCHED && removeState == PatchState.PATCHED
        && registration == PatchState.PATCHED) {
      requireEconomyGroupIndexWrapperPostcondition(node, GETTER_DESC, HOOK_DESC);
      return Match.alreadyApplied("ReachEconomy economy-group index wrapper is complete");
    }
    if (fieldPresent || wrapperPatched || addState == PatchState.PATCHED || removeState == PatchState.PATCHED
        || registration == PatchState.PATCHED) {
      return Match.incompatible("ReachEconomy economy-group index is partial/foreign");
    }

    MethodNode addMarket = requireMethod(node, "addMarket", "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V");
    MethodNode removeMarket = requireMethod(node, "removeMarket", "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V");
    AbstractInsnNode addPop = economyGroupMutationPopAnchor(node, addMarket, "addMarket");
    AbstractInsnNode removePop = economyGroupMutationPopAnchor(node, removeMarket, "removeMarket");
    return Match.plan(new Plan(node, original, addMarket, addPop, removeMarket, removePop));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    ClassNode node = plan.node();
    node.fields.add(new FieldNode(Opcodes.ASM8, persistentStateAccess(), ECONOMY_GROUP_INDEX_STATE_FIELD,
        PERSISTENT_STATE_DESC, null, null));
    MethodNode wrapper = renameForPrivateOriginal(node, plan.original(), ECONOMY_GROUP_INDEX_RAW_GETTER);
    installEconomyGroupIndexWrapper(node.name, wrapper, HOOK_DESC);
    insertEconomyGroupInvalidation(plan.addMarket(), node.name, plan.addPop());
    insertEconomyGroupInvalidation(plan.removeMarket(), node.name, plan.removePop());
    installEconomyGroupIndexRegistration(node, ECONOMY_GROUP_INDEX_COMPONENT_REACH_ECONOMY,
        "ReachEconomy economy-group index");

    PatchReport report = new PatchReport();
    report.add("owner-local econ-group index wrapper", 1);
    report.add("ReachEconomy group mutation epochs", 2);
    report.add("ReachEconomy group-index capability registration", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireEconomyGroupIndexWrapperPostcondition(node, GETTER_DESC, HOOK_DESC);
    requireCount("ReachEconomy add-market owner invalidation hook", countCalls(plan.addMarket(), Opcodes.INVOKESTATIC,
        HOOKS, "markEconomyGroupStructureChanged", "(Ljava/lang/Object;)V"), 1);
    requireCount("ReachEconomy remove-market owner invalidation hook", countCalls(plan.removeMarket(),
        Opcodes.INVOKESTATIC, HOOKS, "markEconomyGroupStructureChanged", "(Ljava/lang/Object;)V"), 1);
  }
}
