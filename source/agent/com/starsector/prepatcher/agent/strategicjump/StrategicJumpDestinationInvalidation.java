package com.starsector.prepatcher.agent.strategicjump;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Markers.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.patch.StructuredPatch;

/**
 * JumpPoint topology mutation epilogues feeding the incremental strategic index:
 * addDestination, clearDestinations and removeDestination each notify before their
 * single normal return.
 */
public final class StrategicJumpDestinationInvalidation
    implements StructuredPatch<StrategicJumpDestinationInvalidation.Plan> {
  private static final String STRATEGIC_JUMP_INVALIDATE_HOOK = "strategicJumpDestinationsChanged";
  private static final String STRATEGIC_JUMP_INVALIDATE_HOOK_DESC = "(Lcom/fs/starfarer/api/campaign/JumpPointAPI;)V";
  private static final String STRATEGIC_JUMP_ADDED_HOOK = "strategicJumpDestinationAdded";
  private static final String STRATEGIC_JUMP_ADDED_HOOK_DESC = "(Lcom/fs/starfarer/api/campaign/JumpPointAPI;"
      + "Lcom/fs/starfarer/api/campaign/JumpPointAPI$JumpDestination;)V";

  record Plan(MethodNode add, MethodNode clear, MethodNode remove) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode add =
        requireMethod(node, "addDestination", "(Lcom/fs/starfarer/api/campaign/JumpPointAPI$JumpDestination;)V");
    MethodNode clear = requireMethod(node, "clearDestinations", "()V");
    MethodNode remove =
        requireMethod(node, "removeDestination", "(Lcom/fs/starfarer/api/campaign/SectorEntityToken;)V");
    boolean owned = hasPatchMarker(node, STRATEGIC_JUMP_DESTINATION_INDEX_PATCH_ID);
    boolean addHook = strategicJumpPointHookState(add, true, false);
    boolean clearHook = strategicJumpPointHookState(clear, false, false);
    boolean removeHook = strategicJumpPointHookState(remove, false, false);
    int installed = (addHook ? 1 : 0) + (clearHook ? 1 : 0) + (removeHook ? 1 : 0);
    if (owned) {
      if (installed != 3) { return Match.incompatible("owned JumpPoint topology hooks are incomplete"); }
      return Match.alreadyApplied("JumpPoint topology mutations already update the strategic index");
    }
    if (installed != 0) { return Match.alreadyApplied("foreign JumpPoint topology hooks are already present"); }

    requireSingleReturn(add, "JumpPoint.addDestination");
    requireSingleReturn(clear, "JumpPoint.clearDestinations");
    requireSingleReturn(remove, "JumpPoint.removeDestination");
    requireCount("JumpPoint.addDestination List.add calls",
        countCalls(add, Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z"), 1);
    requireCount("JumpPoint.clearDestinations List.clear calls",
        countCalls(clear, Opcodes.INVOKEINTERFACE, "java/util/List", "clear", "()V"), 1);
    requireCount("JumpPoint.removeDestination List.remove calls",
        countCalls(remove, Opcodes.INVOKEINTERFACE, "java/util/List", "remove", "(Ljava/lang/Object;)Z"), 1);
    return Match.plan(new Plan(add, clear, remove));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    installStrategicJumpPointHook(plan.add(), true);
    installStrategicJumpPointHook(plan.clear(), false);
    installStrategicJumpPointHook(plan.remove(), false);

    PatchReport report = new PatchReport();
    report.add("JumpPoint incremental topology epilogues", 3);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    strategicJumpPointHookState(plan.add(), true, true);
    strategicJumpPointHookState(plan.clear(), false, true);
    strategicJumpPointHookState(plan.remove(), false, true);
  }

  private static void installStrategicJumpPointHook(MethodNode method, boolean added) {
    AbstractInsnNode exit = requireSingleReturn(method, "JumpPoint." + method.name);
    InsnList hook = new InsnList();
    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
    if (added) hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
    hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
        added ? STRATEGIC_JUMP_ADDED_HOOK : STRATEGIC_JUMP_INVALIDATE_HOOK,
        added ? STRATEGIC_JUMP_ADDED_HOOK_DESC : STRATEGIC_JUMP_INVALIDATE_HOOK_DESC, false));
    method.instructions.insertBefore(exit, hook);
  }

  private static boolean strategicJumpPointHookState(MethodNode method, boolean added, boolean required) {
    String hookName = added ? STRATEGIC_JUMP_ADDED_HOOK : STRATEGIC_JUMP_INVALIDATE_HOOK;
    String hookDesc = added ? STRATEGIC_JUMP_ADDED_HOOK_DESC : STRATEGIC_JUMP_INVALIDATE_HOOK_DESC;
    List<MethodInsnNode> hooks = calls(method, Opcodes.INVOKESTATIC, HOOKS, hookName, hookDesc);
    if (hooks.size() != 1) {
      if (required) throw mismatch("JumpPoint hook count changed in " + method.name + method.desc);
      return false;
    }
    AbstractInsnNode exit = requireSingleReturn(method, "JumpPoint." + method.name);
    MethodInsnNode hook = hooks.get(0);
    if (nextMeaningful(hook) != exit) {
      if (required) throw mismatch("JumpPoint hook is not the normal epilogue in " + method.name + method.desc);
      return false;
    }
    AbstractInsnNode previous = previousMeaningful(hook);
    if (added) {
      VarInsnNode destination = requireVar(previous, Opcodes.ALOAD, "JumpPoint.addDestination hook argument");
      VarInsnNode receiver =
          requireVar(previousMeaningful(destination), Opcodes.ALOAD, "JumpPoint.addDestination hook receiver");
      if (destination.var != 1 || receiver.var != 0) {
        if (required) throw mismatch("JumpPoint.addDestination hook operands changed");
        return false;
      }
    } else {
      VarInsnNode receiver = requireVar(previous, Opcodes.ALOAD, "JumpPoint mutation hook receiver");
      if (receiver.var != 0) {
        if (required) throw mismatch("JumpPoint mutation hook receiver changed");
        return false;
      }
    }
    return true;
  }
}
