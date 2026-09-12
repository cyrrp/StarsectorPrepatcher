package com.starsector.prepatcher.agent.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Flow.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;

public final class Mutations {
  public static void removeRange(MethodNode method, AbstractInsnNode start, AbstractInsnNode end) {
    AbstractInsnNode after = end.getNext();
    for (AbstractInsnNode cursor = start; cursor != after;) {
      AbstractInsnNode next = cursor.getNext();
      method.instructions.remove(cursor);
      cursor = next;
    }
  }

  public static AbstractInsnNode pushInt(int value) {
    if (value >= -1 && value <= 5) { return new InsnNode(value == -1 ? Opcodes.ICONST_M1 : Opcodes.ICONST_0 + value); }
    if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) { return new IntInsnNode(Opcodes.BIPUSH, value); }
    if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) { return new IntInsnNode(Opcodes.SIPUSH, value); }
    return new LdcInsnNode(value);
  }

  public static void removeInstructionRange(MethodNode method, AbstractInsnNode start, AbstractInsnNode end,
      String label) {
    Set<AbstractInsnNode> removed = new HashSet<>();
    AbstractInsnNode cursor = start;
    while (cursor != null) {
      removed.add(cursor);
      if (cursor == end) break;
      cursor = cursor.getNext();
    }
    if (cursor == null) throw mismatch(label + " end is not reachable from start");
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (removed.contains(insn)) continue;
      if (insn instanceof JumpInsnNode jump && removed.contains(jump.label)) {
        throw mismatch(label + " has an external jump into the removed range");
      }
    }
    for (TryCatchBlockNode block : method.tryCatchBlocks) {
      if (removed.contains(block.start) || removed.contains(block.end) || removed.contains(block.handler)) {
        throw mismatch(label + " crosses an exception-handler boundary");
      }
    }
    cursor = start;
    while (cursor != null) {
      AbstractInsnNode next = cursor.getNext();
      method.instructions.remove(cursor);
      if (cursor == end) break;
      cursor = next;
    }
  }

  public static void appendInitializedLocalToExpandedFrames(MethodNode method, int localSlot, String localType) {
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!(insn instanceof FrameNode frame)) continue;
      if (frame.type != Opcodes.F_NEW) { throw mismatch("punitive frame expansion did not produce full frames"); }
      if (frame.local == null) frame.local = new ArrayList<>();
      int slots = frameLocalSlots(frame.local);
      if (slots > localSlot) { throw mismatch("punitive frame already extends beyond new cache local"); }
      while (slots < localSlot) {
        frame.local.add(Opcodes.TOP);
        slots++;
      }
      frame.local.add(localType);
    }
  }

  public static MethodNode renameForPrivateOriginal(ClassNode node, MethodNode original, String rawName) {
    return renameForPrivateOriginal(node.name, node.methods, original, rawName);
  }

  public static MethodNode renameForPrivateOriginal(String owner, List<MethodNode> methods, MethodNode original,
      String rawName) {
    String wrapperName = original.name;
    MethodNode wrapper = new MethodNode(Opcodes.ASM8, original.access, wrapperName, original.desc, original.signature,
        exceptions(original));
    moveWrapperMetadata(original, wrapper, owner + "." + wrapperName + original.desc);
    original.name = rawName;
    makePrivateSynthetic(original);
    methods.add(wrapper);
    return wrapper;
  }

  public static void replaceMethodBody(MethodNode method, InsnList code, int maxLocals) {
    method.instructions.clear();
    method.instructions.add(code);
    method.tryCatchBlocks.clear();
    if (method.localVariables != null) method.localVariables.clear();
    if (method.visibleLocalVariableAnnotations != null) method.visibleLocalVariableAnnotations.clear();
    if (method.invisibleLocalVariableAnnotations != null) method.invisibleLocalVariableAnnotations.clear();
    method.maxLocals = maxLocals;
    method.maxStack = 8;
  }

  public static void moveWrapperMetadata(MethodNode original, MethodNode wrapper, String label) {
    if (original.attrs != null && !original.attrs.isEmpty()) {
      throw mismatch(label + " has unknown method attributes that cannot be moved safely");
    }
    wrapper.parameters = original.parameters;
    original.parameters = null;
    wrapper.annotationDefault = original.annotationDefault;
    original.annotationDefault = null;
    wrapper.visibleAnnotations = original.visibleAnnotations;
    original.visibleAnnotations = null;
    wrapper.invisibleAnnotations = original.invisibleAnnotations;
    original.invisibleAnnotations = null;
    wrapper.visibleTypeAnnotations = original.visibleTypeAnnotations;
    original.visibleTypeAnnotations = null;
    wrapper.invisibleTypeAnnotations = original.invisibleTypeAnnotations;
    original.invisibleTypeAnnotations = null;
    wrapper.visibleAnnotableParameterCount = original.visibleAnnotableParameterCount;
    original.visibleAnnotableParameterCount = 0;
    wrapper.visibleParameterAnnotations = original.visibleParameterAnnotations;
    original.visibleParameterAnnotations = null;
    wrapper.invisibleAnnotableParameterCount = original.invisibleAnnotableParameterCount;
    original.invisibleAnnotableParameterCount = 0;
    wrapper.invisibleParameterAnnotations = original.invisibleParameterAnnotations;
    original.invisibleParameterAnnotations = null;
  }

  public static void makePrivateSynthetic(MethodNode method) {
    method.access &= ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED);
    method.access |= Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
  }

  public static void makeStatic(MethodInsnNode call, String owner, String name, String desc) {
    call.setOpcode(Opcodes.INVOKESTATIC);
    call.owner = owner;
    call.name = name;
    call.desc = desc;
    call.itf = false;
  }

}
