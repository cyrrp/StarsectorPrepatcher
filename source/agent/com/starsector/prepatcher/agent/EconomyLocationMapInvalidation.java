package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;
import java.util.List;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Flow.*;
import static com.starsector.prepatcher.agent.Mutations.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.PersistentState.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.*;
import static com.starsector.prepatcher.agent.Queries.*;

final class EconomyLocationMapInvalidation implements StructuredPatch<EconomyLocationMapInvalidation.Plan> {
  record Plan(ClassNode node, MethodNode advance, MethodInsnNode dirty, MethodInsnNode update, AbstractInsnNode flag,
      AbstractInsnNode secondThis, AbstractInsnNode secondFieldInsn, boolean persistent, String hookName,
      String hookDesc) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode advance = requireMethod(node, "advance", "(F)V");
    String reach = "com/fs/starfarer/campaign/econ/reach/ReachEconomy";
    String reachDesc = "L" + reach + ";";
    boolean persistent =
        hasExactSyntheticField(node, ECONOMY_PERSISTENT_STATE_FIELD, PERSISTENT_STATE_DESC, persistentStateAccess());
    String hookName = persistent ? "updateEconomyLocationMapIfNeededPersistent" : "updateEconomyLocationMapIfNeeded";
    String hookDesc = persistent ? "(" + reachDesc + PERSISTENT_STATE_DESC + ")V" : "(" + reachDesc + ")V";
    List<MethodInsnNode> dirtyCalls =
        calls(advance, Opcodes.INVOKEVIRTUAL, reach, "setLocationCacheNeedsUpdate", "(Z)V");
    List<MethodInsnNode> updateCalls = calls(advance, Opcodes.INVOKEVIRTUAL, reach, "updateLocationMap", "()V");
    int hooks = countCalls(advance, Opcodes.INVOKESTATIC, HOOKS, hookName, hookDesc);
    boolean original = dirtyCalls.size() == 1 && updateCalls.size() == 1 && hooks == 0;
    boolean patched = dirtyCalls.isEmpty() && updateCalls.isEmpty() && hooks == 1;
    if (patched) {
      MethodInsnNode hook =
          only(calls(advance, Opcodes.INVOKESTATIC, HOOKS, hookName, hookDesc), "Economy.advance location-cache hook");
      Frame<SourceValue>[] frames = sourceFrames(node.name, advance);
      FieldInsnNode economyField =
          optionalSourceField(argumentSource(advance, hook, frames, 0), Opcodes.GETFIELD, node.name, reachDesc);
      if (economyField == null) {
        return Match.incompatible("Economy.advance location-cache hook economy argument changed");
      }
      if (persistent) {
        FieldInsnNode stateField = optionalSourceField(argumentSource(advance, hook, frames, 1), Opcodes.GETFIELD,
            node.name, PERSISTENT_STATE_DESC);
        if (stateField == null || !stateField.name.equals(ECONOMY_PERSISTENT_STATE_FIELD)) {
          return Match.incompatible("Economy.advance persistent location-cache state changed");
        }
      }
      return Match.alreadyApplied("Economy.advance location-cache hook and argument postcondition match");
    }
    if (!original) {
      return Match.incompatible("Economy.advance location-cache sequence is missing, duplicated, or partially patched");
    }

    MethodInsnNode dirty = dirtyCalls.get(0);
    MethodInsnNode update = updateCalls.get(0);
    AbstractInsnNode flag = previousMeaningful(dirty);
    AbstractInsnNode firstFieldInsn = previousMeaningful(flag);
    AbstractInsnNode firstThis = previousMeaningful(firstFieldInsn);
    AbstractInsnNode secondThis = nextMeaningful(dirty);
    AbstractInsnNode secondFieldInsn = nextMeaningful(secondThis);
    AbstractInsnNode secondUpdate = nextMeaningful(secondFieldInsn);
    if (flag == null || flag.getOpcode() != Opcodes.ICONST_1 || !(firstFieldInsn instanceof FieldInsnNode firstField)
        || firstField.getOpcode() != Opcodes.GETFIELD || !firstField.owner.equals(node.name)
        || !firstField.desc.equals(reachDesc) || !(firstThis instanceof VarInsnNode firstLoad)
        || firstLoad.getOpcode() != Opcodes.ALOAD || firstLoad.var != 0
        || !(secondThis instanceof VarInsnNode secondLoad) || secondLoad.getOpcode() != Opcodes.ALOAD
        || secondLoad.var != 0 || !(secondFieldInsn instanceof FieldInsnNode secondField)
        || secondField.getOpcode() != Opcodes.GETFIELD || !secondField.owner.equals(node.name)
        || !secondField.name.equals(firstField.name) || !secondField.desc.equals(reachDesc) || secondUpdate != update) {
      return Match.incompatible("Economy.advance automatic dirty/update sequence changed structurally");
    }
    return Match.plan(
        new Plan(node, advance, dirty, update, flag, secondThis, secondFieldInsn, persistent, hookName, hookDesc));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    MethodNode advance = plan.advance();
    advance.instructions.remove(plan.flag());
    advance.instructions.remove(plan.secondThis());
    advance.instructions.remove(plan.secondFieldInsn());
    advance.instructions.remove(plan.update());
    if (plan.persistent()) {
      InsnList state = new InsnList();
      state.add(new VarInsnNode(Opcodes.ALOAD, 0));
      state.add(
          new FieldInsnNode(Opcodes.GETFIELD, plan.node().name, ECONOMY_PERSISTENT_STATE_FIELD, PERSISTENT_STATE_DESC));
      advance.instructions.insertBefore(plan.dirty(), state);
    }
    makeStatic(plan.dirty(), HOOKS, plan.hookName(), plan.hookDesc());
    PatchReport report = new PatchReport();
    report.add(plan.persistent() ? "epoch/audit ReachEconomy location-map invalidation"
        : "validated ReachEconomy location-map invalidation", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireCount("Economy location-cache hook",
        countCalls(plan.advance(), Opcodes.INVOKESTATIC, HOOKS, plan.hookName(), plan.hookDesc()), 1);
  }
}
