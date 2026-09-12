package com.starsector.prepatcher.agent.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;
import java.util.ArrayList;
import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;

public final class Flow {
  public static boolean sourceIsIntegerConstant(SourceValue value, int expected) {
    return value != null && value.insns != null && value.insns.size() == 1
        && isIntegerLdc(value.insns.iterator().next(), expected);
  }

  public static boolean sourceIsFloatConstant(SourceValue value, float expected) {
    return value != null && value.insns != null && value.insns.size() == 1
        && value.insns.iterator().next() instanceof LdcInsnNode ldc && isFloat(ldc, expected);
  }

  static int frameLocalSlots(List<Object> locals) {
    if (locals == null) return 0;
    int slots = 0;
    for (Object value : locals) {
      slots += (Opcodes.LONG.equals(value) || Opcodes.DOUBLE.equals(value)) ? 2 : 1;
    }
    return slots;
  }

  public static Object[] initialFrameLocals(String owner, MethodNode method) {
    List<Object> locals = new ArrayList<>();
    if ((method.access & Opcodes.ACC_STATIC) == 0) locals.add(owner);
    for (Type argument : Type.getArgumentTypes(method.desc)) {
      switch (argument.getSort()) {
        case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> locals.add(Opcodes.INTEGER);
        case Type.FLOAT -> locals.add(Opcodes.FLOAT);
        case Type.LONG -> locals.add(Opcodes.LONG);
        case Type.DOUBLE -> locals.add(Opcodes.DOUBLE);
        case Type.ARRAY -> locals.add(argument.getDescriptor());
        case Type.OBJECT -> locals.add(argument.getInternalName());
        default -> throw mismatch("unsupported scratch-scope argument type " + argument);
      }
    }
    return locals.toArray();
  }

  /**
   * Proves that the receiver comes from one of the borrowed scratch locals and that no later
   * ASTORE to that local occurs between the borrow and this call. This keeps observation hooks
   * inside the exact lifetime of the borrowed collection even when vanilla reuses the slot.
   */
  public static boolean sourceIsActiveHookLocal(MethodNode method, MethodInsnNode call, SourceValue receiver,
      List<VarInsnNode> hookStores) {
    int callIndex = method.instructions.indexOf(call);
    for (VarInsnNode store : hookStores) {
      if (!sourceIsLocal(receiver, Opcodes.ALOAD, store.var)) continue;
      int storeIndex = method.instructions.indexOf(store);
      if (storeIndex < 0 || storeIndex >= callIndex) continue;
      boolean reassigned = false;
      for (AbstractInsnNode cursor = store.getNext(); cursor != null && cursor != call; cursor = cursor.getNext()) {
        if (cursor instanceof VarInsnNode var && var.getOpcode() == Opcodes.ASTORE && var.var == store.var) {
          reassigned = true;
          break;
        }
      }
      if (!reassigned) return true;
    }
    return false;
  }

  public static int countIdentityComparisons(MethodNode method, Frame<SourceValue>[] frames, AbstractInsnNode after,
      int local, String owner, String name, String desc) {
    int count = 0;
    for (AbstractInsnNode insn = after.getNext(); insn != null; insn = insn.getNext()) {
      if (!(insn instanceof JumpInsnNode jump)
          || (jump.getOpcode() != Opcodes.IF_ACMPEQ && jump.getOpcode() != Opcodes.IF_ACMPNE))
        continue;
      Frame<SourceValue> frame = frameBefore(method, jump, frames);
      if (frame.getStackSize() < 2) continue;
      SourceValue left = frame.getStack(frame.getStackSize() - 2);
      SourceValue right = frame.getStack(frame.getStackSize() - 1);
      if ((sourceIsMethod(left, -1, owner, name, desc) && sourceIsLocal(right, Opcodes.ALOAD, local))
          || (sourceIsMethod(right, -1, owner, name, desc) && sourceIsLocal(left, Opcodes.ALOAD, local)))
        count++;
    }
    return count;
  }

  public static Frame<SourceValue>[] sourceFrames(String owner, MethodNode method) {
    try {
      return new Analyzer<SourceValue>(new SourceInterpreter()).analyze(owner, method);
    } catch (AnalyzerException ex) {
      throw mismatch(
          "data-flow analysis failed for " + owner + "." + method.name + method.desc + ": " + ex.getMessage());
    }
  }

  public static Frame<SourceValue> frameBefore(MethodNode method, AbstractInsnNode insn, Frame<SourceValue>[] frames) {
    int index = method.instructions.indexOf(insn);
    if (index < 0 || index >= frames.length || frames[index] == null) {
      throw mismatch("no reachable data-flow frame at " + method.name + method.desc);
    }
    return frames[index];
  }

  public static SourceValue receiverSource(MethodNode method, MethodInsnNode call, Frame<SourceValue>[] frames) {
    if (call.getOpcode() == Opcodes.INVOKESTATIC) throw mismatch("static call has no receiver: " + call.name);
    Frame<SourceValue> frame = frameBefore(method, call, frames);
    int arguments = Type.getArgumentTypes(call.desc).length;
    int index = frame.getStackSize() - arguments - 1;
    if (index < 0) throw mismatch("invalid receiver stack for " + call.owner + "." + call.name + call.desc);
    return frame.getStack(index);
  }

  public static SourceValue argumentSource(MethodNode method, MethodInsnNode call, Frame<SourceValue>[] frames,
      int argumentIndex) {
    Type[] args = Type.getArgumentTypes(call.desc);
    if (argumentIndex < 0 || argumentIndex >= args.length) throw mismatch("invalid call argument index");
    Frame<SourceValue> frame = frameBefore(method, call, frames);
    int index = frame.getStackSize() - args.length + argumentIndex;
    if (index < 0) throw mismatch("invalid argument stack for " + call.owner + "." + call.name + call.desc);
    return frame.getStack(index);
  }

  public static FieldInsnNode optionalSourceField(SourceValue value, int opcode, String owner, String desc) {
    if (value == null || value.insns == null || value.insns.size() != 1) return null;
    AbstractInsnNode source = value.insns.iterator().next();
    if (source instanceof FieldInsnNode field && field.getOpcode() == opcode && field.owner.equals(owner)
        && field.desc.equals(desc))
      return field;
    return null;
  }

  public static Integer optionalLoadedLocal(SourceValue value, int opcode) {
    if (value == null || value.insns == null || value.insns.isEmpty()) return null;
    Integer local = null;
    for (AbstractInsnNode source : value.insns) {
      if (!(source instanceof VarInsnNode var) || var.getOpcode() != opcode) return null;
      if (local != null && local != var.var) return null;
      local = var.var;
    }
    return local;
  }

  public static boolean sourceIsLocal(SourceValue value, int opcode, int local) {
    Integer found = optionalLoadedLocal(value, opcode);
    return found != null && found == local;
  }

  public static boolean sourceIsOpcode(SourceValue value, int opcode) {
    if (value == null || value.insns == null || value.insns.size() != 1) return false;
    return value.insns.iterator().next().getOpcode() == opcode;
  }

  public static boolean sourceIsFieldDesc(SourceValue value, String desc) {
    if (value == null || value.insns == null || value.insns.size() != 1) return false;
    AbstractInsnNode source = value.insns.iterator().next();
    return source instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD && field.desc.equals(desc);
  }

  public static boolean sourceIsMethod(SourceValue value, int opcode, String owner, String name, String desc) {
    if (value == null || value.insns == null || value.insns.size() != 1) return false;
    AbstractInsnNode source = value.insns.iterator().next();
    return source instanceof MethodInsnNode call && callMatches(call, opcode, owner, name, desc);
  }

  public static boolean sourceContains(SourceValue value, AbstractInsnNode source) {
    return value != null && value.insns != null && value.insns.contains(source);
  }

}
