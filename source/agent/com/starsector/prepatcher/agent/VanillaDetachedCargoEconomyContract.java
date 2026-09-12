package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

import static com.starsector.prepatcher.agent.Contract.*;

/**
 * Proves that the current vanilla Economy tripleStep is only three nextStep
 * calls and that getEconomy returns the exact ReachEconomy field. No bytecode
 * is changed; the result gates the detached-Cargo vanilla skip at runtime.
 */
final class VanillaDetachedCargoEconomyContract implements StructuredPatch<VanillaDetachedCargoEconomyContract.Plan> {
  static final String TARGET = "com/fs/starfarer/campaign/econ/Economy";
  private static final String REACH_DESC = "Lcom/fs/starfarer/campaign/econ/reach/ReachEconomy;";

  record Plan() {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    validate(node);
    return Match.plan(new Plan());
  }

  @Override
  public PatchReport mutate(Plan plan) {
    return new PatchReport();
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    validate(node);
  }

  private static void validate(ClassNode node) {
    if (!TARGET.equals(node.name)) { throw mismatch("unexpected owner " + node.name); }
    FieldNode economyField = null;
    for (FieldNode field : node.fields) {
      if (REACH_DESC.equals(field.desc)) {
        if (economyField != null) { throw mismatch("multiple ReachEconomy fields"); }
        economyField = field;
      }
    }
    if (economyField == null || (economyField.access & Opcodes.ACC_STATIC) != 0) {
      throw mismatch("exact ReachEconomy instance field missing");
    }

    MethodNode getter = requireMethod(node, "getEconomy", "()" + REACH_DESC);
    requirePublicInstance(getter, "getEconomy");
    List<AbstractInsnNode> getterInstructions = meaningful(getter);
    if (getterInstructions.size() != 3) { throw mismatch("getEconomy instruction count changed"); }
    requireLoadThis(getterInstructions.get(0), "getEconomy receiver");
    if (!(getterInstructions.get(1) instanceof FieldInsnNode fieldRead) || fieldRead.getOpcode() != Opcodes.GETFIELD
        || !node.name.equals(fieldRead.owner) || !economyField.name.equals(fieldRead.name)
        || !REACH_DESC.equals(fieldRead.desc) || getterInstructions.get(2).getOpcode() != Opcodes.ARETURN) {
      throw mismatch("getEconomy no longer returns the exact field");
    }

    MethodNode triple = requireMethod(node, "tripleStep", "()V");
    requirePublicInstance(triple, "tripleStep");
    if (triple.tryCatchBlocks != null && !triple.tryCatchBlocks.isEmpty()) {
      throw mismatch("tripleStep gained exception regions");
    }
    List<AbstractInsnNode> instructions = meaningful(triple);
    if (instructions.size() != 7) { throw mismatch("tripleStep instruction count changed"); }
    for (int i = 0; i < 3; i++) {
      requireLoadThis(instructions.get(i * 2), "tripleStep receiver " + i);
      AbstractInsnNode candidate = instructions.get(i * 2 + 1);
      if (!(candidate instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEVIRTUAL
          || !node.name.equals(call.owner) || !"nextStep".equals(call.name) || !"()V".equals(call.desc)) {
        throw mismatch("tripleStep call " + i + " is not exact nextStep()V");
      }
    }
    if (instructions.get(6).getOpcode() != Opcodes.RETURN) { throw mismatch("tripleStep no longer ends with RETURN"); }
  }

  private static void requirePublicInstance(MethodNode method, String label) {
    if ((method.access & Opcodes.ACC_PUBLIC) == 0
        || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
      throw mismatch(label + " access changed");
    }
  }

  private static void requireLoadThis(AbstractInsnNode instruction, String label) {
    if (!(instruction instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0) {
      throw mismatch(label + " changed");
    }
  }

  private static MethodNode requireMethod(ClassNode node, String name, String desc) {
    MethodNode result = null;
    for (MethodNode method : node.methods) {
      if (name.equals(method.name) && desc.equals(method.desc)) {
        if (result != null) { throw mismatch("duplicate method " + name + desc); }
        result = method;
      }
    }
    if (result == null) throw mismatch("missing method " + name + desc);
    return result;
  }

  private static List<AbstractInsnNode> meaningful(MethodNode method) {
    List<AbstractInsnNode> result = new ArrayList<>();
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction.getOpcode() >= 0) result.add(instruction);
    }
    return result;
  }
}
