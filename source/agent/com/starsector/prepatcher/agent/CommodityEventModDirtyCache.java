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
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import java.util.List;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.*;
import static com.starsector.prepatcher.agent.Queries.*;

/**
 * CommodityOnMarket.reapplyEventMod() normally calls unmodifyFlat("eMod")
 * for every commodity every campaign frame, including the overwhelmingly
 * common case where the combined recent-trade quantity is zero and the
 * modifier was already removed on a previous frame.
 *
 * Only that proven-absent zero path is cached. The nonzero path retains the
 * complete vanilla remove -> calculate -> conditional add sequence and its
 * original float behavior. The private flag is transient, is set only after
 * a successful removal, and is cleared before every nonzero update so loads,
 * transitions, and exceptional exits all fail open.
 */
final class CommodityEventModDirtyCache implements StructuredPatch<CommodityEventModDirtyCache.Plan> {
  private static final String KNOWN_ABSENT_FIELD = "smo$commodityEventModKnownAbsent";
  private static final int STATE_ACCESS = Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC;

  record Plan(ClassNode node, MethodNode method, String availableField) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode method = requireMethod(node, "reapplyEventMod", "()V");

    FieldNode knownAbsent = null;
    for (FieldNode field : node.fields) {
      if (!field.name.equals(KNOWN_ABSENT_FIELD)) continue;
      if (knownAbsent != null) { return Match.incompatible("duplicate commodity event-mod known-absent field"); }
      knownAbsent = field;
    }
    if (knownAbsent != null) {
      if (!knownAbsent.desc.equals("Z") || knownAbsent.access != STATE_ACCESS || knownAbsent.signature != null
          || knownAbsent.value != null) {
        return Match.incompatible("commodity event-mod known-absent field is foreign");
      }
      requireOptimizedCommodityEventModShape(node, method, KNOWN_ABSENT_FIELD);
      return Match.alreadyApplied("commodity event-mod zero-path cache postcondition matches");
    }

    String availableField = requireOriginalCommodityEventModShape(node, method);
    return Match.plan(new Plan(node, method, availableField));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    plan.node().fields.add(new FieldNode(Opcodes.ASM8, STATE_ACCESS, KNOWN_ABSENT_FIELD, "Z", null, null));
    rewriteCommodityEventMod(plan.method(), plan.node().name, plan.availableField(), KNOWN_ABSENT_FIELD);

    PatchReport report = new PatchReport();
    report.add("proven-absent commodity event-mod removals", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireOptimizedCommodityEventModShape(node, plan.method(), KNOWN_ABSENT_FIELD);
  }

  private static String requireOriginalCommodityEventModShape(ClassNode node, MethodNode method) {
    final String stat = "com/fs/starfarer/api/combat/MutableStatWithTempMods";
    List<AbstractInsnNode> code = meaningfulInstructions(method);
    requireCount("CommodityOnMarket.reapplyEventMod original instruction count", code.size(), 31);
    requireVar(code.get(0), Opcodes.ALOAD, 0, "event-mod quantity owner");
    requireCall(asMethod(code.get(1), "event-mod quantity call"), Opcodes.INVOKEVIRTUAL, node.name,
        "getCombinedTradeModQuantity", "()F", "event-mod quantity call");
    requireVar(code.get(2), Opcodes.FSTORE, 1, "event-mod quantity local");
    requireVar(code.get(3), Opcodes.FLOAD, 1, "event-mod quantity test");
    if (code.get(4).getOpcode() != Opcodes.FCONST_0 || code.get(5).getOpcode() != Opcodes.FCMPL
        || !(code.get(6) instanceof JumpInsnNode zeroQuantity) || zeroQuantity.getOpcode() != Opcodes.IFEQ) {
      throw mismatch("event-mod zero-quantity branch changed");
    }

    requireVar(code.get(7), Opcodes.ALOAD, 0, "event-mod available owner");
    if (!(code.get(8) instanceof FieldInsnNode available) || available.getOpcode() != Opcodes.GETFIELD
        || !available.owner.equals(node.name) || !available.desc.equals("L" + stat + ";")) {
      throw mismatch("event-mod available-stat field changed");
    }
    requireString(code.get(9), "eMod", "event-mod removal key");
    requireCall(asMethod(code.get(10), "event-mod removal"), Opcodes.INVOKEVIRTUAL, stat, "unmodifyFlat",
        "(Ljava/lang/String;)V", "event-mod removal");
    requireVar(code.get(11), Opcodes.ALOAD, 0, "event-mod value owner");
    requireVar(code.get(12), Opcodes.FLOAD, 1, "event-mod quantity argument");
    requireCall(asMethod(code.get(13), "event-mod value call"), Opcodes.INVOKEVIRTUAL, node.name,
        "getModValueForQuantity", "(F)F", "event-mod value call");
    requireVar(code.get(14), Opcodes.FSTORE, 2, "event-mod value local");
    requireVar(code.get(15), Opcodes.FLOAD, 2, "event-mod value test");
    if (code.get(16).getOpcode() != Opcodes.FCONST_0 || code.get(17).getOpcode() != Opcodes.FCMPL
        || !(code.get(18) instanceof JumpInsnNode zeroValue) || zeroValue.getOpcode() != Opcodes.IFEQ) {
      throw mismatch("event-mod zero-value branch changed");
    }

    requireVar(code.get(19), Opcodes.ALOAD, 0, "event-mod modification owner");
    requireField(code.get(20), Opcodes.GETFIELD, node.name, available.name, available.desc,
        "event-mod modification available field");
    requireString(code.get(21), "eMod", "event-mod modification key");
    requireVar(code.get(22), Opcodes.FLOAD, 2, "event-mod modification value");
    requireString(code.get(23), "Recent trade/events", "event-mod description");
    requireCall(asMethod(code.get(24), "event-mod modification"), Opcodes.INVOKEVIRTUAL, stat, "modifyFlat",
        "(Ljava/lang/String;FLjava/lang/String;)V", "event-mod modification");
    if (!(code.get(25) instanceof JumpInsnNode done) || done.getOpcode() != Opcodes.GOTO) {
      throw mismatch("event-mod nonzero completion branch changed");
    }

    requireVar(code.get(26), Opcodes.ALOAD, 0, "zero event-mod available owner");
    requireField(code.get(27), Opcodes.GETFIELD, node.name, available.name, available.desc,
        "zero event-mod available field");
    requireString(code.get(28), "eMod", "zero event-mod removal key");
    requireCall(asMethod(code.get(29), "zero event-mod removal"), Opcodes.INVOKEVIRTUAL, stat, "unmodifyFlat",
        "(Ljava/lang/String;)V", "zero event-mod removal");
    if (code.get(30).getOpcode() != Opcodes.RETURN || nextMeaningful(zeroQuantity.label) != code.get(26)
        || nextMeaningful(zeroValue.label) != code.get(30) || nextMeaningful(done.label) != code.get(30)
        || !method.tryCatchBlocks.isEmpty()) {
      throw mismatch("event-mod original control flow changed");
    }
    return available.name;
  }

  private static void rewriteCommodityEventMod(MethodNode method, String owner, String availableField,
      String knownAbsentField) {
    final String stat = "com/fs/starfarer/api/combat/MutableStatWithTempMods";
    LabelNode zeroQuantity = new LabelNode();
    LabelNode applyZeroRemoval = new LabelNode();
    LabelNode done = new LabelNode();
    InsnList code = new InsnList();

    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "getCombinedTradeModQuantity", "()F", false));
    code.add(new VarInsnNode(Opcodes.FSTORE, 1));
    code.add(new VarInsnNode(Opcodes.FLOAD, 1));
    code.add(new InsnNode(Opcodes.FCONST_0));
    code.add(new InsnNode(Opcodes.FCMPL));
    code.add(new JumpInsnNode(Opcodes.IFEQ, zeroQuantity));

    // The nonzero path is the original implementation, with only a
    // fail-open state clear inserted before the first operation that can throw.
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new InsnNode(Opcodes.ICONST_0));
    code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, knownAbsentField, "Z"));
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, availableField, "L" + stat + ";"));
    code.add(new LdcInsnNode("eMod"));
    code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, stat, "unmodifyFlat", "(Ljava/lang/String;)V", false));
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new VarInsnNode(Opcodes.FLOAD, 1));
    code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "getModValueForQuantity", "(F)F", false));
    code.add(new VarInsnNode(Opcodes.FSTORE, 2));
    code.add(new VarInsnNode(Opcodes.FLOAD, 2));
    code.add(new InsnNode(Opcodes.FCONST_0));
    code.add(new InsnNode(Opcodes.FCMPL));
    code.add(new JumpInsnNode(Opcodes.IFEQ, done));
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, availableField, "L" + stat + ";"));
    code.add(new LdcInsnNode("eMod"));
    code.add(new VarInsnNode(Opcodes.FLOAD, 2));
    code.add(new LdcInsnNode("Recent trade/events"));
    code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, stat, "modifyFlat", "(Ljava/lang/String;FLjava/lang/String;)V",
        false));
    code.add(new JumpInsnNode(Opcodes.GOTO, done));

    // Zero quantity always means that eMod must be absent. Once a successful
    // removal established that invariant, repeated absent HashMap.remove()
    // calls are unnecessary until a nonzero quantity clears the proof.
    code.add(zeroQuantity);
    code.add(new FrameNode(Opcodes.F_FULL, 2, new Object[]{owner, Opcodes.FLOAT}, 0, null));
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, knownAbsentField, "Z"));
    code.add(new JumpInsnNode(Opcodes.IFEQ, applyZeroRemoval));
    code.add(new InsnNode(Opcodes.RETURN));
    code.add(applyZeroRemoval);
    code.add(new FrameNode(Opcodes.F_FULL, 2, new Object[]{owner, Opcodes.FLOAT}, 0, null));
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, availableField, "L" + stat + ";"));
    code.add(new LdcInsnNode("eMod"));
    code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, stat, "unmodifyFlat", "(Ljava/lang/String;)V", false));
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new InsnNode(Opcodes.ICONST_1));
    code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, knownAbsentField, "Z"));
    code.add(done);
    code.add(new FrameNode(Opcodes.F_FULL, 2, new Object[]{owner, Opcodes.FLOAT}, 0, null));
    code.add(new InsnNode(Opcodes.RETURN));

    method.instructions.clear();
    method.instructions.add(code);
    method.tryCatchBlocks.clear();
    if (method.localVariables != null) method.localVariables.clear();
    if (method.visibleLocalVariableAnnotations != null) {
      method.visibleLocalVariableAnnotations.clear();
    }
    if (method.invisibleLocalVariableAnnotations != null) {
      method.invisibleLocalVariableAnnotations.clear();
    }
    method.maxLocals = 3;
    method.maxStack = 4;
  }

  private static void requireOptimizedCommodityEventModShape(ClassNode node, MethodNode method,
      String knownAbsentField) {
    final String stat = "com/fs/starfarer/api/combat/MutableStatWithTempMods";
    List<AbstractInsnNode> code = meaningfulInstructions(method);
    requireCount("CommodityOnMarket.reapplyEventMod optimized instruction count", code.size(), 41);
    requireVar(code.get(0), Opcodes.ALOAD, 0, "cached event-mod quantity owner");
    requireCall(asMethod(code.get(1), "cached event-mod quantity call"), Opcodes.INVOKEVIRTUAL, node.name,
        "getCombinedTradeModQuantity", "()F", "cached event-mod quantity call");
    requireVar(code.get(2), Opcodes.FSTORE, 1, "cached event-mod quantity local");
    requireVar(code.get(3), Opcodes.FLOAD, 1, "cached event-mod quantity test");
    if (code.get(4).getOpcode() != Opcodes.FCONST_0 || code.get(5).getOpcode() != Opcodes.FCMPL
        || !(code.get(6) instanceof JumpInsnNode zeroQuantity) || zeroQuantity.getOpcode() != Opcodes.IFEQ) {
      throw mismatch("cached event-mod zero-quantity branch changed");
    }

    requireVar(code.get(7), Opcodes.ALOAD, 0, "nonzero cache-state owner");
    if (code.get(8).getOpcode() != Opcodes.ICONST_0) { throw mismatch("nonzero event-mod cache clear value changed"); }
    requireField(code.get(9), Opcodes.PUTFIELD, node.name, knownAbsentField, "Z", "nonzero event-mod cache clear");
    requireVar(code.get(10), Opcodes.ALOAD, 0, "cached event-mod available owner");
    if (!(code.get(11) instanceof FieldInsnNode available) || available.getOpcode() != Opcodes.GETFIELD
        || !available.owner.equals(node.name) || !available.desc.equals("L" + stat + ";")) {
      throw mismatch("cached event-mod available-stat field changed");
    }
    requireString(code.get(12), "eMod", "cached event-mod removal key");
    requireCall(asMethod(code.get(13), "cached event-mod nonzero removal"), Opcodes.INVOKEVIRTUAL, stat, "unmodifyFlat",
        "(Ljava/lang/String;)V", "cached event-mod nonzero removal");
    requireVar(code.get(14), Opcodes.ALOAD, 0, "cached event-mod value owner");
    requireVar(code.get(15), Opcodes.FLOAD, 1, "cached event-mod value argument");
    requireCall(asMethod(code.get(16), "cached event-mod value call"), Opcodes.INVOKEVIRTUAL, node.name,
        "getModValueForQuantity", "(F)F", "cached event-mod value call");
    requireVar(code.get(17), Opcodes.FSTORE, 2, "cached event-mod value local");
    requireVar(code.get(18), Opcodes.FLOAD, 2, "cached event-mod zero test");
    if (code.get(19).getOpcode() != Opcodes.FCONST_0 || code.get(20).getOpcode() != Opcodes.FCMPL
        || !(code.get(21) instanceof JumpInsnNode zeroValue) || zeroValue.getOpcode() != Opcodes.IFEQ) {
      throw mismatch("cached event-mod zero-value branch changed");
    }
    requireVar(code.get(22), Opcodes.ALOAD, 0, "cached event-mod modification owner");
    requireField(code.get(23), Opcodes.GETFIELD, node.name, available.name, available.desc,
        "cached event-mod modification available field");
    requireString(code.get(24), "eMod", "cached event-mod modification key");
    requireVar(code.get(25), Opcodes.FLOAD, 2, "cached event-mod modification value");
    requireString(code.get(26), "Recent trade/events", "cached event-mod description");
    requireCall(asMethod(code.get(27), "cached event-mod modification"), Opcodes.INVOKEVIRTUAL, stat, "modifyFlat",
        "(Ljava/lang/String;FLjava/lang/String;)V", "cached event-mod modification");
    if (!(code.get(28) instanceof JumpInsnNode nonzeroDone) || nonzeroDone.getOpcode() != Opcodes.GOTO) {
      throw mismatch("cached event-mod nonzero completion branch changed");
    }

    requireVar(code.get(29), Opcodes.ALOAD, 0, "zero event-mod cache owner");
    requireField(code.get(30), Opcodes.GETFIELD, node.name, knownAbsentField, "Z", "zero event-mod cache read");
    if (!(code.get(31) instanceof JumpInsnNode unknown) || unknown.getOpcode() != Opcodes.IFEQ
        || code.get(32).getOpcode() != Opcodes.RETURN) {
      throw mismatch("zero event-mod cache guard changed");
    }
    requireVar(code.get(33), Opcodes.ALOAD, 0, "zero event-mod removal owner");
    requireField(code.get(34), Opcodes.GETFIELD, node.name, available.name, available.desc,
        "zero event-mod removal available field");
    requireString(code.get(35), "eMod", "zero event-mod removal key");
    requireCall(asMethod(code.get(36), "zero event-mod removal"), Opcodes.INVOKEVIRTUAL, stat, "unmodifyFlat",
        "(Ljava/lang/String;)V", "zero event-mod removal");
    requireVar(code.get(37), Opcodes.ALOAD, 0, "zero event-mod proof owner");
    if (code.get(38).getOpcode() != Opcodes.ICONST_1) { throw mismatch("zero event-mod proof value changed"); }
    requireField(code.get(39), Opcodes.PUTFIELD, node.name, knownAbsentField, "Z", "zero event-mod proof write");
    if (code.get(40).getOpcode() != Opcodes.RETURN || nextMeaningful(zeroQuantity.label) != code.get(29)
        || nextMeaningful(unknown.label) != code.get(33) || nextMeaningful(zeroValue.label) != code.get(40)
        || nextMeaningful(nonzeroDone.label) != code.get(40) || !method.tryCatchBlocks.isEmpty()) {
      throw mismatch("cached event-mod optimized control flow changed");
    }
  }
}
