package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Markers.*;
import static com.starsector.prepatcher.agent.Mutations.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.StrategicJump.*;

/**
 * Transformation surface: StrategicModule.findNearestSafeJumpPoint and only the
 * straight-line hostile-market score feeding the accepted-destination candidate
 * path. No other patch currently targets this class or method.
 */
final class StrategicJumpDestinationFirst implements StructuredPatch<StrategicJumpDestinationFirst.Plan> {
  record Plan(MethodNode method, StrategicJumpCommon common, StrategicJumpOriginal original) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode method = requireMethod(node, "findNearestSafeJumpPoint", STRATEGIC_JUMP_METHOD_DESC);
    if ((method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) != (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) {
      return Match.incompatible("StrategicModule.findNearestSafeJumpPoint access changed");
    }

    StrategicJumpCommon common = inspectStrategicJumpCommon(node.name, method);
    boolean owned = hasPatchMarker(node, STRATEGIC_JUMP_DESTINATION_FIRST_PATCH_ID);
    StrategicJumpOriginal original = inspectStrategicJumpOriginal(common, false);
    StrategicJumpLazy lazy = inspectStrategicJumpLazy(common, false);

    if (owned) {
      if (lazy == null || original != null) {
        return Match.incompatible("owned destination-first patch has an incomplete or mixed post-state");
      }
      return Match.alreadyApplied("destination predicate precedes the guarded hostile-market scan");
    }
    if (lazy != null) {
      return Match.alreadyApplied("destination-first bytecode is already present without this optimizer marker");
    }
    if (original == null) {
      return Match.incompatible("expected the vanilla eager hostile-market score before getDestinations");
    }
    return Match.plan(new Plan(method, common, original));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    MethodNode method = plan.method();
    StrategicJumpCommon common = plan.common();
    StrategicJumpOriginal original = plan.original();

    InsnList sentinel = new InsnList();
    sentinel.add(new LdcInsnNode(STRATEGIC_JUMP_UNCOMPUTED));
    sentinel.add(new VarInsnNode(Opcodes.ISTORE, common.unsafeLocal()));
    method.instructions.insertBefore(original.start(), sentinel);
    removeRange(method, original.start(), original.end());

    LabelNode scoreReady = new LabelNode();
    InsnList lazyScore = new InsnList();
    lazyScore.add(new VarInsnNode(Opcodes.ILOAD, common.unsafeLocal()));
    lazyScore.add(new LdcInsnNode(STRATEGIC_JUMP_UNCOMPUTED));
    lazyScore.add(new JumpInsnNode(Opcodes.IF_ICMPNE, scoreReady));
    lazyScore.add(new VarInsnNode(Opcodes.ALOAD, common.fleetLocal()));
    lazyScore.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/CampaignFleetAPI",
        "getFaction", "()Lcom/fs/starfarer/api/campaign/FactionAPI;", true));
    lazyScore.add(new VarInsnNode(Opcodes.ALOAD, common.jumpPointLocal()));
    lazyScore.add(new LdcInsnNode(Float.valueOf(4000f)));
    lazyScore.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/fs/starfarer/api/util/Misc", "getNumHostileMarkets",
        HOSTILE_MARKETS_DESC, false));
    lazyScore.add(new InsnNode(Opcodes.ICONST_2));
    lazyScore.add(new InsnNode(Opcodes.IMUL));
    lazyScore.add(new VarInsnNode(Opcodes.ISTORE, common.unsafeLocal()));
    lazyScore.add(scoreReady);
    // The new join has the same locals and an empty stack as the existing
    // accepted-destination frame immediately preceding this block.
    lazyScore.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
    method.instructions.insertBefore(common.candidateStart(), lazyScore);

    PatchReport report = new PatchReport();
    report.add("destination-first hostile-market guards", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    StrategicJumpCommon patchedCommon = inspectStrategicJumpCommon(node.name, plan.method());
    if (inspectStrategicJumpOriginal(patchedCommon, false) != null
        || inspectStrategicJumpLazy(patchedCommon, false) == null) {
      throw mismatch("destination-first mutation did not produce the complete lazy post-state");
    }
  }
}
