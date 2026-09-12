package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import java.util.List;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.MarketScheduler.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.*;
import static com.starsector.prepatcher.agent.Queries.*;

final class EntityMarketSchedulerSource implements StructuredPatch<EntityMarketSchedulerSource.Plan> {
  record Plan(ClassNode node, MethodNode advance, MethodInsnNode target) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode advance = requireMethod(node, "advance", "(F)V");
    PatchState registration = marketSchedulerRegistrationState(node, MARKET_SCHEDULER_COMPONENT_ENTITY,
        "BaseCampaignEntity market scheduler");
    String market = "com/fs/starfarer/api/campaign/econ/MarketAPI";
    String hookDesc = "(L" + market + ";FI)V";
    List<MethodInsnNode> originals = calls(advance, Opcodes.INVOKEINTERFACE, market, "advance", "(F)V");
    List<MethodInsnNode> hooks = calls(advance, Opcodes.INVOKESTATIC, HOOKS, "advanceMarketScheduled", hookDesc);
    boolean originalState = originals.size() == 1 && hooks.isEmpty();
    boolean patchedState = originals.isEmpty() && hooks.size() == 1;
    if (!originalState && !patchedState) {
      return Match
          .incompatible("BaseCampaignEntity market scheduler bridge is missing, duplicated, " + "or partially patched");
    }
    requireCount("BaseCampaignEntity planet-condition predicate",
        countCalls(advance, Opcodes.INVOKEINTERFACE, market, "isPlanetConditionMarketOnly", "()Z"), 1);

    MethodInsnNode target = originalState ? originals.get(0) : hooks.get(0);
    AbstractInsnNode source = patchedState ? previousMeaningful(target) : null;
    AbstractInsnNode amount = patchedState ? previousMeaningful(source) : previousMeaningful(target);
    AbstractInsnNode fieldInsn = previousMeaningful(amount);
    AbstractInsnNode owner = previousMeaningful(fieldInsn);
    if (!(amount instanceof VarInsnNode amountLoad) || amountLoad.getOpcode() != Opcodes.FLOAD || amountLoad.var != 1
        || !(fieldInsn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
        || !field.owner.equals(node.name) || !field.desc.equals("L" + market + ";")
        || !(owner instanceof VarInsnNode ownerLoad) || ownerLoad.getOpcode() != Opcodes.ALOAD || ownerLoad.var != 0) {
      return Match.incompatible("BaseCampaignEntity market scheduler receiver/amount shape changed");
    }
    if (patchedState && !isIntegerLdc(source, 1)) {
      return Match.incompatible("BaseCampaignEntity market scheduler source is not PLANET_CONDITION");
    }

    AbstractInsnNode predicateBranchInsn = previousMeaningful(owner);
    AbstractInsnNode predicateInsn = previousMeaningful(predicateBranchInsn);
    AbstractInsnNode predicateFieldInsn = previousMeaningful(predicateInsn);
    AbstractInsnNode predicateOwnerInsn = previousMeaningful(predicateFieldInsn);
    AbstractInsnNode nullBranchInsn = previousMeaningful(predicateOwnerInsn);
    AbstractInsnNode nullFieldInsn = previousMeaningful(nullBranchInsn);
    AbstractInsnNode nullOwnerInsn = previousMeaningful(nullFieldInsn);
    AbstractInsnNode join = nextMeaningful(target);
    if (!(predicateBranchInsn instanceof JumpInsnNode predicateBranch) || predicateBranch.getOpcode() != Opcodes.IFEQ
        || !(predicateInsn instanceof MethodInsnNode predicate)
        || !callMatches(predicate, Opcodes.INVOKEINTERFACE, market, "isPlanetConditionMarketOnly", "()Z")
        || !(predicateFieldInsn instanceof FieldInsnNode predicateField)
        || predicateField.getOpcode() != Opcodes.GETFIELD || !predicateField.owner.equals(field.owner)
        || !predicateField.name.equals(field.name) || !predicateField.desc.equals(field.desc)
        || !(predicateOwnerInsn instanceof VarInsnNode predicateOwner) || predicateOwner.getOpcode() != Opcodes.ALOAD
        || predicateOwner.var != 0 || !(nullBranchInsn instanceof JumpInsnNode nullBranch)
        || nullBranch.getOpcode() != Opcodes.IFNULL || !(nullFieldInsn instanceof FieldInsnNode nullField)
        || nullField.getOpcode() != Opcodes.GETFIELD || !nullField.owner.equals(field.owner)
        || !nullField.name.equals(field.name) || !nullField.desc.equals(field.desc)
        || !(nullOwnerInsn instanceof VarInsnNode nullOwner) || nullOwner.getOpcode() != Opcodes.ALOAD
        || nullOwner.var != 0 || join == null || nextMeaningful(predicateBranch.label) != join
        || nextMeaningful(nullBranch.label) != join || hasExternalControlFlowEntry(advance, nullOwnerInsn, target)) {
      return Match.incompatible("BaseCampaignEntity planet-condition guards no longer own the market call");
    }

    if (patchedState) {
      if (registration != PatchState.PATCHED) {
        return Match.incompatible("BaseCampaignEntity market scheduler source lacks component registration");
      }
      return Match.alreadyApplied("BaseCampaignEntity market scheduler source postcondition matches");
    }
    if (registration == PatchState.PATCHED) {
      return Match.incompatible("BaseCampaignEntity market scheduler registration exists without its source bridge");
    }
    return Match.plan(new Plan(node, advance, target));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    String market = "com/fs/starfarer/api/campaign/econ/MarketAPI";
    String hookDesc = "(L" + market + ";FI)V";
    plan.advance().instructions.insertBefore(plan.target(), new InsnNode(Opcodes.ICONST_1));
    plan.advance().instructions.set(plan.target(),
        new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "advanceMarketScheduled", hookDesc, false));
    installMarketSchedulerRegistration(plan.node(), MARKET_SCHEDULER_COMPONENT_ENTITY,
        "BaseCampaignEntity market scheduler");
    PatchReport report = new PatchReport();
    report.add("planet-condition source routed through common market scheduler", 1);
    report.add("market scheduler entity component registration", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    String market = "com/fs/starfarer/api/campaign/econ/MarketAPI";
    String hookDesc = "(L" + market + ";FI)V";
    requireCount("BaseCampaignEntity market scheduler bridge",
        countCalls(plan.advance(), Opcodes.INVOKESTATIC, HOOKS, "advanceMarketScheduled", hookDesc), 1);
    requireCount("retired direct planet-condition Market.advance",
        countCalls(plan.advance(), Opcodes.INVOKEINTERFACE, market, "advance", "(F)V"), 0);
    if (marketSchedulerRegistrationState(node, MARKET_SCHEDULER_COMPONENT_ENTITY,
        "BaseCampaignEntity market scheduler") != PatchState.PATCHED) {
      throw mismatch("BaseCampaignEntity market scheduler component registration is absent");
    }
  }
}
