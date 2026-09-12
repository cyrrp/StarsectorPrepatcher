package com.starsector.prepatcher.agent.strategicjump;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
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
import com.starsector.prepatcher.agent.PrepatcherTransformer;
import com.starsector.prepatcher.agent.patch.StructuredPatch;

public final class StrategicJumpDestinationRetarget implements StructuredPatch<StrategicJumpDestinationRetarget.Plan> {
  private static final String STRATEGIC_JUMP_RETARGET_HOOK = "strategicJumpDestinationRetargeted";
  private static final String STRATEGIC_JUMP_RETARGET_HOOK_DESC =
      "(Lcom/fs/starfarer/api/campaign/JumpPointAPI$JumpDestination;)V";

  record Plan(MethodNode method, AbstractInsnNode exit) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode method = requireMethod(node, "setDestination", "(Lcom/fs/starfarer/api/campaign/SectorEntityToken;)V");
    boolean owned = hasPatchMarker(node, STRATEGIC_JUMP_DESTINATION_INDEX_PATCH_ID);
    boolean installed = strategicJumpRetargetHookState(method, false);
    if (owned) {
      if (!installed) return Match.incompatible("owned JumpDestination retarget hook is missing");
      return Match.alreadyApplied("JumpDestination.setDestination already updates the strategic index");
    }
    if (installed) { return Match.alreadyApplied("foreign JumpDestination retarget hook is already present"); }

    AbstractInsnNode exit = requireSingleReturn(method, "JumpDestination.setDestination");
    FieldInsnNode write = null;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
          || !field.owner.equals(PrepatcherTransformer.JUMP_DESTINATION) || !field.name.equals("destination")
          || !field.desc.equals("Lcom/fs/starfarer/api/campaign/SectorEntityToken;")) {
        continue;
      }
      if (write != null) throw mismatch("JumpDestination destination field has multiple writes");
      write = field;
    }
    if (write == null || method.instructions.indexOf(write) > method.instructions.indexOf(exit)) {
      throw mismatch("JumpDestination destination field write was not found before return");
    }
    return Match.plan(new Plan(method, exit));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    InsnList hook = new InsnList();
    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
    hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, STRATEGIC_JUMP_RETARGET_HOOK,
        STRATEGIC_JUMP_RETARGET_HOOK_DESC, false));
    plan.method().instructions.insertBefore(plan.exit(), hook);

    PatchReport report = new PatchReport();
    report.add("JumpDestination retarget epilogues", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    strategicJumpRetargetHookState(plan.method(), true);
  }

  private static boolean strategicJumpRetargetHookState(MethodNode method, boolean required) {
    List<MethodInsnNode> hooks =
        calls(method, Opcodes.INVOKESTATIC, HOOKS, STRATEGIC_JUMP_RETARGET_HOOK, STRATEGIC_JUMP_RETARGET_HOOK_DESC);
    if (hooks.size() != 1) {
      if (required) throw mismatch("JumpDestination retarget hook count changed");
      return false;
    }
    AbstractInsnNode exit = requireSingleReturn(method, "JumpDestination.setDestination");
    MethodInsnNode hook = hooks.get(0);
    VarInsnNode receiver =
        requireVar(previousMeaningful(hook), Opcodes.ALOAD, "JumpDestination retarget hook receiver");
    if (receiver.var != 0 || nextMeaningful(hook) != exit) {
      if (required) throw mismatch("JumpDestination retarget hook epilogue changed");
      return false;
    }
    return true;
  }
}
