package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import java.util.List;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.*;
import static com.starsector.prepatcher.agent.Queries.*;

final class CommodityTemporalBaseBinding implements StructuredPatch<CommodityTemporalBaseBinding.Plan> {
  private static final String OWNER_FIELD = "spp$commodityTemporalOwner";
  private static final String ROLE_FIELD = "spp$commodityTemporalRole";
  private static final int FIELD_ACCESS = Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC;
  private static final String HOOK_NAME = "markCommodityTemporalOwnerDirty";
  private static final String HOOK_DESC = "(Ljava/lang/Object;ILjava/lang/String;)V";

  private record TemporalDirtyMethod(String name, String desc, int sourceLocal) {
  }

  private record BoundTarget(MethodNode method, TemporalDirtyMethod target) {
  }

  record Plan(ClassNode node, List<BoundTarget> targets) {
  }

  private static final List<TemporalDirtyMethod> TARGETS = List.of(
      new TemporalDirtyMethod("applyMods", "(Lcom/fs/starfarer/api/combat/MutableStat;)V", -1),
      new TemporalDirtyMethod("applyMods", "(Lcom/fs/starfarer/api/combat/StatBonus;)V", -1),
      new TemporalDirtyMethod("modifyFlat", "(Ljava/lang/String;FLjava/lang/String;)V", 1),
      new TemporalDirtyMethod("modifyPercent", "(Ljava/lang/String;FLjava/lang/String;)V", 1),
      new TemporalDirtyMethod("modifyPercentAlways", "(Ljava/lang/String;FLjava/lang/String;)V", 1),
      new TemporalDirtyMethod("modifyMult", "(Ljava/lang/String;FLjava/lang/String;)V", 1),
      new TemporalDirtyMethod("modifyMultAlways", "(Ljava/lang/String;FLjava/lang/String;)V", 1),
      new TemporalDirtyMethod("modifyFlatAlways", "(Ljava/lang/String;FLjava/lang/String;)V", 1),
      new TemporalDirtyMethod("unmodify", "()V", -1), new TemporalDirtyMethod("unmodify", "(Ljava/lang/String;)V", 1),
      new TemporalDirtyMethod("unmodifyFlat", "(Ljava/lang/String;)V", 1),
      new TemporalDirtyMethod("unmodifyPercent", "(Ljava/lang/String;)V", 1),
      new TemporalDirtyMethod("unmodifyMult", "(Ljava/lang/String;)V", 1),
      new TemporalDirtyMethod("setBaseValue", "(F)V", -1));

  @Override
  public Match<Plan> match(ClassNode node) {
    FieldNode owner = uniqueField(node, OWNER_FIELD);
    FieldNode role = uniqueField(node, ROLE_FIELD);
    if (owner != null || role != null) {
      requirePrivateSyntheticField(owner, OWNER_FIELD, "Ljava/lang/Object;", FIELD_ACCESS);
      requirePrivateSyntheticField(role, ROLE_FIELD, "I", FIELD_ACCESS);
      for (TemporalDirtyMethod target : TARGETS) {
        requireCommodityTemporalBaseDirtyPrologue(node, requireMethod(node, target.name(), target.desc()), target);
      }
      return Match.alreadyApplied("MutableStat temporal binding and dirty postcondition match");
    }
    if (uniqueField(node, "smo$commodityTemporalBinding") != null) {
      return Match.incompatible("legacy commodity temporal binding field is already present");
    }
    List<BoundTarget> bound = new java.util.ArrayList<>();
    for (TemporalDirtyMethod target : TARGETS) {
      MethodNode method = requireMethod(node, target.name(), target.desc());
      requireCount(target.name() + target.desc() + " existing temporal dirty hooks",
          countCalls(method, Opcodes.INVOKESTATIC, HOOKS, HOOK_NAME, HOOK_DESC), 0);
      bound.add(new BoundTarget(method, target));
    }
    return Match.plan(new Plan(node, bound));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    ClassNode node = plan.node();
    node.fields.add(new FieldNode(Opcodes.ASM8, FIELD_ACCESS, OWNER_FIELD, "Ljava/lang/Object;", null, null));
    node.fields.add(new FieldNode(Opcodes.ASM8, FIELD_ACCESS, ROLE_FIELD, "I", null, null));
    for (BoundTarget bound : plan.targets()) {
      insertCommodityTemporalBaseDirtyPrologue(node, bound.method(), bound.target());
    }

    PatchReport report = new PatchReport();
    report.add("bound MutableStat mutation notifications", plan.targets().size());
    report.add("private owner/role binding fields", 2);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    for (BoundTarget bound : plan.targets()) {
      requireCommodityTemporalBaseDirtyPrologue(node, bound.method(), bound.target());
    }
  }

  private static void insertCommodityTemporalBaseDirtyPrologue(ClassNode node, MethodNode method,
      TemporalDirtyMethod target) {
    AbstractInsnNode first = firstExecutable(method);
    if (first == null) throw mismatch(target.name() + target.desc() + " has no body");
    LabelNode skip = new LabelNode();
    InsnList code = new InsnList();
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, OWNER_FIELD, "Ljava/lang/Object;"));
    code.add(new JumpInsnNode(Opcodes.IFNULL, skip));
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, OWNER_FIELD, "Ljava/lang/Object;"));
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, ROLE_FIELD, "I"));
    if (target.sourceLocal() >= 0) {
      code.add(new VarInsnNode(Opcodes.ALOAD, target.sourceLocal()));
    } else {
      code.add(new InsnNode(Opcodes.ACONST_NULL));
    }
    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, HOOK_NAME, HOOK_DESC, false));
    code.add(skip);
    code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
    method.instructions.insertBefore(first, code);
  }

  private static void requireCommodityTemporalBaseDirtyPrologue(ClassNode node, MethodNode method,
      TemporalDirtyMethod target) {
    requireCount(target.name() + target.desc() + " temporal dirty hook",
        countCalls(method, Opcodes.INVOKESTATIC, HOOKS, HOOK_NAME, HOOK_DESC), 1);
    List<AbstractInsnNode> code = meaningfulInstructions(method);
    if (code.size() < 10) throw mismatch(target.name() + target.desc() + " dirty prologue short");
    requireVar(code.get(0), Opcodes.ALOAD, 0, "dirty owner receiver");
    requireField(code.get(1), Opcodes.GETFIELD, node.name, OWNER_FIELD, "Ljava/lang/Object;", "dirty owner guard");
    if (!(code.get(2) instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFNULL) {
      throw mismatch(target.name() + target.desc() + " dirty null guard changed");
    }
    requireVar(code.get(3), Opcodes.ALOAD, 0, "dirty owner receiver 2");
    requireField(code.get(4), Opcodes.GETFIELD, node.name, OWNER_FIELD, "Ljava/lang/Object;", "dirty owner value");
    requireVar(code.get(5), Opcodes.ALOAD, 0, "dirty role receiver");
    requireField(code.get(6), Opcodes.GETFIELD, node.name, ROLE_FIELD, "I", "dirty role value");
    if (target.sourceLocal() >= 0) {
      requireVar(code.get(7), Opcodes.ALOAD, target.sourceLocal(), "dirty source");
    } else if (code.get(7).getOpcode() != Opcodes.ACONST_NULL) {
      throw mismatch(target.name() + target.desc() + " null dirty source changed");
    }
    requireCall(asMethod(code.get(8), "commodity temporal dirty call"), Opcodes.INVOKESTATIC, HOOKS, HOOK_NAME,
        HOOK_DESC, "commodity temporal dirty call");
    if (nextMeaningful(jump.label) != code.get(9)) {
      throw mismatch(target.name() + target.desc() + " dirty guard target changed");
    }
  }
}
