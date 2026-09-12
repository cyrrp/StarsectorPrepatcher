package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import java.util.List;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Markers.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.Queries.*;

final class StrategicJumpLocationMutation implements StructuredPatch<StrategicJumpLocationMutation.Plan> {
  private static final String STRATEGIC_JUMP_LOCATION_HOOK = "strategicJumpLocationEntityChanged";
  private static final String STRATEGIC_JUMP_LOCATION_HOOK_DESC =
      "(Lcom/fs/starfarer/api/campaign/LocationAPI;Ljava/lang/Object;)V";

  record Plan(MethodNode add, MethodNode remove) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode add = requireMethod(node, "addObjectReal", "(Ljava/lang/Object;)V");
    MethodNode remove = requireMethod(node, "removeObjectReal", "(Ljava/lang/Object;)V");
    boolean owned = hasPatchMarker(node, STRATEGIC_JUMP_DESTINATION_INDEX_PATCH_ID);
    boolean addHook = strategicJumpLocationHookState(add, false);
    boolean removeHook = strategicJumpLocationHookState(remove, false);
    if (owned) {
      if (!addHook || !removeHook) { return Match.incompatible("owned BaseLocation topology hooks are incomplete"); }
      return Match.alreadyApplied("BaseLocation entity mutations already update the strategic index");
    }
    if (addHook || removeHook) { return Match.alreadyApplied("foreign BaseLocation topology hook is already present"); }

    requireSingleReturn(add, "BaseLocation.addObjectReal");
    requireSingleReturn(remove, "BaseLocation.removeObjectReal");
    requireCount("BaseLocation ObjectRepository.add calls", countCalls(add, Opcodes.INVOKEVIRTUAL,
        "com/fs/util/container/repo/ObjectRepository", "add", "(Ljava/lang/Object;)Z"), 1);
    requireCount("BaseLocation ObjectRepository.remove calls", countCalls(remove, Opcodes.INVOKEVIRTUAL,
        "com/fs/util/container/repo/ObjectRepository", "remove", "(Ljava/lang/Object;)Z"), 1);
    return Match.plan(new Plan(add, remove));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    installStrategicJumpLocationHook(plan.add());
    installStrategicJumpLocationHook(plan.remove());

    PatchReport report = new PatchReport();
    report.add("BaseLocation jump-source mutation epilogues", 2);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    strategicJumpLocationHookState(plan.add(), true);
    strategicJumpLocationHookState(plan.remove(), true);
  }

  private static void installStrategicJumpLocationHook(MethodNode method) {
    AbstractInsnNode exit = requireSingleReturn(method, "BaseLocation." + method.name);
    InsnList hook = new InsnList();
    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
    hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
    hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, STRATEGIC_JUMP_LOCATION_HOOK,
        STRATEGIC_JUMP_LOCATION_HOOK_DESC, false));
    method.instructions.insertBefore(exit, hook);
  }

  private static boolean strategicJumpLocationHookState(MethodNode method, boolean required) {
    List<MethodInsnNode> hooks =
        calls(method, Opcodes.INVOKESTATIC, HOOKS, STRATEGIC_JUMP_LOCATION_HOOK, STRATEGIC_JUMP_LOCATION_HOOK_DESC);
    if (hooks.size() != 1) {
      if (required) throw mismatch("BaseLocation topology hook count changed in " + method.name + method.desc);
      return false;
    }
    AbstractInsnNode exit = requireSingleReturn(method, "BaseLocation." + method.name);
    MethodInsnNode hook = hooks.get(0);
    VarInsnNode entity = requireVar(previousMeaningful(hook), Opcodes.ALOAD, "BaseLocation topology entity");
    VarInsnNode receiver = requireVar(previousMeaningful(entity), Opcodes.ALOAD, "BaseLocation topology receiver");
    if (receiver.var != 0 || entity.var != 1 || nextMeaningful(hook) != exit) {
      if (required) throw mismatch("BaseLocation topology hook epilogue changed");
      return false;
    }
    return true;
  }
}
