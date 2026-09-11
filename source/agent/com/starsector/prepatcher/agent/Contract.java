package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.starsector.prepatcher.agent.Allocations.*;
import static com.starsector.prepatcher.agent.Flow.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.Queries.*;

final class Contract {
  static void requireNoControlBoundary(AbstractInsnNode start, AbstractInsnNode end, String label) {
    for (AbstractInsnNode cursor = start; cursor != null; cursor = cursor.getNext()) {
      if (cursor instanceof LabelNode || cursor instanceof FrameNode || cursor instanceof JumpInsnNode) {
        throw mismatch(label + " contains a control-flow boundary");
      }
      if (cursor == end) return;
    }
    throw mismatch(label + " end is not reachable from start");
  }

  static AbstractInsnNode requireSingleReturn(MethodNode method, String label) {
    AbstractInsnNode result = null;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn.getOpcode() != Opcodes.RETURN) continue;
      if (result != null) throw mismatch(label + " has multiple normal returns");
      result = insn;
    }
    if (result == null) throw mismatch(label + " has no normal return");
    return result;
  }

  static VarInsnNode requireVar(AbstractInsnNode insn, int opcode, String label) {
    if (!(insn instanceof VarInsnNode var) || var.getOpcode() != opcode) {
      throw mismatch(label + " has unexpected instruction");
    }
    return var;
  }

  static JumpInsnNode requireJump(AbstractInsnNode insn, int opcode, String label) {
    if (!(insn instanceof JumpInsnNode jump) || jump.getOpcode() != opcode) {
      throw mismatch(label + " has unexpected instruction");
    }
    return jump;
  }

  static AbstractInsnNode onlyInsn(List<AbstractInsnNode> values, String label) {
    if (values.size() != 1) { throw mismatch(label + " expected exactly 1 site but found " + values.size()); }
    return values.get(0);
  }

  static FieldInsnNode onlyField(MethodNode method, int opcode, String owner, String name, String desc, String label) {
    List<FieldInsnNode> result = new ArrayList<>();
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof FieldInsnNode field && field.getOpcode() == opcode && field.owner.equals(owner)
          && field.name.equals(name) && field.desc.equals(desc))
        result.add(field);
    }
    if (result.size() != 1) { throw mismatch(label + " expected once, found " + result.size()); }
    return result.get(0);
  }

  static FieldNode uniqueField(ClassNode node, String name) {
    FieldNode result = null;
    for (FieldNode field : node.fields) {
      if (!field.name.equals(name)) continue;
      if (result != null) throw mismatch("duplicate field " + name);
      result = field;
    }
    return result;
  }

  static void requirePrivateSyntheticField(FieldNode field, String name, String desc, int access) {
    if (field == null || !field.desc.equals(desc) || field.access != access || field.signature != null
        || field.value != null) {
      throw mismatch("foreign or incomplete field " + name);
    }
  }

  static void requireString(AbstractInsnNode insn, String expected, String label) {
    if (!(insn instanceof LdcInsnNode ldc) || !expected.equals(ldc.cst)) { throw mismatch(label + " changed"); }
  }

  static void requireThisFieldReceiver(FieldInsnNode field, String label) {
    AbstractInsnNode receiver = previousMeaningful(field);
    if (!(receiver instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0) {
      throw mismatch(label + " field receiver is not this");
    }
  }

  static PatchState uniformPatchState(String label, PatchState... states) {
    boolean original = true;
    boolean patched = true;
    StringBuilder observed = new StringBuilder();
    for (PatchState state : states) {
      original &= state == PatchState.ORIGINAL;
      patched &= state == PatchState.PATCHED;
      if (observed.length() > 0) observed.append(',');
      observed.append(state);
    }
    if (original) return PatchState.ORIGINAL;
    if (patched) return PatchState.PATCHED;
    throw mismatch(label + " has mixed original/patched postconditions: " + observed);
  }

  static void requireStoredLocalProducer(MethodNode method, int local, int storeOpcode, int callOpcode,
      String callOwner, String callName, String callDesc, AbstractInsnNode before, String label) {
    int matches = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!(insn instanceof VarInsnNode store) || store.getOpcode() != storeOpcode || store.var != local
          || method.instructions.indexOf(store) >= method.instructions.indexOf(before))
        continue;
      AbstractInsnNode producer = previousMeaningful(store);
      if (producer instanceof MethodInsnNode call && callMatches(call, callOpcode, callOwner, callName, callDesc))
        matches++;
    }
    requireCount(label, matches, 1);
  }

  static void requireFloatLocalProducer(MethodNode method, int local, AbstractInsnNode before, String label) {
    int matches = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!(insn instanceof VarInsnNode store) || store.getOpcode() != Opcodes.FSTORE || store.var != local
          || method.instructions.indexOf(store) >= method.instructions.indexOf(before))
        continue;
      AbstractInsnNode producer = previousMeaningful(store);
      if (producer instanceof MethodInsnNode call && Type.getReturnType(call.desc).getSort() == Type.FLOAT) matches++;
    }
    requireCount(label, matches, 1);
  }

  static void requireIteratorLifetime(String owner, MethodNode method, MethodInsnNode iteratorCall, String label) {
    AbstractInsnNode stored = nextMeaningful(iteratorCall);
    if (!(stored instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) {
      throw mismatch(label + " iterator is not stored directly in a local");
    }
    AbstractInsnNode boundary = null;
    for (AbstractInsnNode cursor = store.getNext(); cursor != null; cursor = cursor.getNext()) {
      if (cursor instanceof VarInsnNode nextStore && nextStore.getOpcode() == Opcodes.ASTORE
          && nextStore.var == store.var) {
        boundary = cursor;
        break;
      }
    }
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    Map<String, Integer> observed = new LinkedHashMap<>();
    Set<AbstractInsnNode> accounted = new HashSet<>();
    for (AbstractInsnNode insn = store.getNext(); insn != null && insn != boundary; insn = insn.getNext()) {
      if (!(insn instanceof MethodInsnNode call)) continue;
      if (call.getOpcode() != Opcodes.INVOKESTATIC) {
        recordScratchUse(receiverSource(method, call, frames), store.var,
            call.getOpcode() + "|" + call.owner + "|" + call.name + "|" + call.desc + "|receiver", observed, accounted);
      }
      Type[] args = Type.getArgumentTypes(call.desc);
      for (int i = 0; i < args.length; i++) {
        recordScratchUse(argumentSource(method, call, frames, i), store.var,
            call.getOpcode() + "|" + call.owner + "|" + call.name + "|" + call.desc + "|arg" + i, observed, accounted);
      }
    }
    Map<String, Integer> expected = new LinkedHashMap<>();
    expectUse(expected, Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", -1, 1);
    expectUse(expected, Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", -1, 1);
    if (!observed.equals(expected)) {
      throw mismatch(label + " iterator use contract changed: expected " + expected + ", found " + observed);
    }
    for (AbstractInsnNode insn = store.getNext(); insn != null && insn != boundary; insn = insn.getNext()) {
      if (insn instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == store.var
          && !accounted.contains(load)) {
        throw mismatch(label + " iterator local has an unclassified escape");
      }
    }
  }

  static void requireLocalConstructor(MethodNode method, int local, String type, String desc, String label) {
    int matches = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
          || !call.owner.equals(type) || !call.name.equals("<init>") || !call.desc.equals(desc))
        continue;
      AbstractInsnNode store = nextMeaningful(call);
      if (store instanceof VarInsnNode var && var.getOpcode() == Opcodes.ASTORE && var.var == local) matches++;
    }
    requireCount(label + " constructor assignment", matches, 1);
  }

  static MethodNode requireWrapperSource(ClassNode node, String publicName, String desc, String renamedName,
      String hookName, String hookDesc) {
    List<MethodNode> publicMethods = methods(node, publicName, desc);
    List<MethodNode> renamedMethods = methods(node, renamedName, desc);
    int hookCount = countCalls(node, Opcodes.INVOKESTATIC, HOOKS, hookName, hookDesc);
    if (publicMethods.size() == 1 && renamedMethods.size() == 1 && hookCount == 1) {
      MethodNode wrapper = publicMethods.get(0);
      MethodNode original = renamedMethods.get(0);
      int expectedOriginalAccess = wrapper.access;
      expectedOriginalAccess &= ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED);
      expectedOriginalAccess |= Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
      if (countCalls(wrapper, Opcodes.INVOKESTATIC, HOOKS, hookName, hookDesc) == 1
          && original.access == expectedOriginalAccess) {
        requireStraightWrapper(wrapper, hookName, hookDesc, publicName);
        return original;
      }
      throw mismatch(publicName + " has an invalid partial wrapper state");
    }
    if (publicMethods.size() != 1 || !renamedMethods.isEmpty() || hookCount != 0) {
      throw mismatch(publicName + " wrapper collision/mixed state: public=" + publicMethods.size() + ", renamed="
          + renamedMethods.size() + ", hooks=" + hookCount);
    }
    return publicMethods.get(0);
  }

  static void requireWrapperPostcondition(ClassNode node, String publicName, String desc, String renamedName,
      String hookName, String hookDesc) {
    requireCount(publicName + " wrapper count", methods(node, publicName, desc).size(), 1);
    List<MethodNode> renamed = methods(node, renamedName, desc);
    requireCount(renamedName + " original count", renamed.size(), 1);
    if ((renamed.get(0).access & Opcodes.ACC_PRIVATE) == 0) { throw mismatch(renamedName + " is not private"); }
    requireCount(publicName + " wrapper hook count",
        countCalls(methods(node, publicName, desc).get(0), Opcodes.INVOKESTATIC, HOOKS, hookName, hookDesc), 1);
    requireStraightWrapper(methods(node, publicName, desc).get(0), hookName, hookDesc, publicName);
  }

  private static void requireStraightWrapper(MethodNode wrapper, String hookName, String hookDesc, String label) {
    if (wrapper.tryCatchBlocks != null && !wrapper.tryCatchBlocks.isEmpty()) {
      throw mismatch(label + " wrapper unexpectedly contains exception handlers");
    }
    int returns = 0;
    for (AbstractInsnNode insn : wrapper.instructions.toArray()) {
      int opcode = insn.getOpcode();
      if (opcode < 0) continue;
      if (insn instanceof MethodInsnNode call) {
        if (!callMatches(call, Opcodes.INVOKESTATIC, HOOKS, hookName, hookDesc)) {
          throw mismatch(label + " wrapper contains a foreign method call");
        }
        continue;
      }
      if (opcode == Opcodes.ARETURN || opcode == Opcodes.RETURN) {
        returns++;
        continue;
      }
      if (opcode != Opcodes.ALOAD && opcode != Opcodes.FLOAD && opcode != Opcodes.GETFIELD
          && opcode != Opcodes.CHECKCAST) {
        throw mismatch(label + " wrapper contains unexpected opcode " + opcode);
      }
    }
    requireCount(label + " wrapper returns", returns, 1);
    AbstractInsnNode tail = wrapper.instructions.getLast();
    while (tail != null && tail.getOpcode() < 0) tail = tail.getPrevious();
    if (tail == null || (tail.getOpcode() != Opcodes.ARETURN && tail.getOpcode() != Opcodes.RETURN)) {
      throw mismatch(label + " wrapper does not end in its only return");
    }
  }

  static void requireVar(AbstractInsnNode insn, int opcode, int local, String label) {
    if (!(insn instanceof VarInsnNode var) || var.getOpcode() != opcode || var.var != local) {
      throw mismatch(label + " changed");
    }
  }

  static void requireField(AbstractInsnNode insn, int opcode, String owner, String name, String desc, String label) {
    if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != opcode || !field.owner.equals(owner)
        || !field.name.equals(name) || !field.desc.equals(desc)) {
      throw mismatch(label + " changed");
    }
  }

  static FieldNode requireField(ClassNode node, String name, String desc) {
    FieldNode match = null;
    for (FieldNode field : node.fields) {
      if (!field.name.equals(name) || !field.desc.equals(desc)) continue;
      if (match != null) throw mismatch("duplicate field " + node.name + "." + name + desc);
      match = field;
    }
    if (match == null) throw mismatch("missing field " + node.name + "." + name + desc);
    return match;
  }

  static int requireStoredCallResultLocal(MethodNode method, int opcode, String owner, String name, String desc,
      String label) {
    MethodInsnNode call = only(calls(method, opcode, owner, name, desc), label);
    AbstractInsnNode next = nextMeaningful(call);
    if (!(next instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) {
      throw mismatch(label + " is not stored in a local");
    }
    return store.var;
  }

  static void requireStoredCallResult(MethodNode method, int local, int opcode, String owner, String name, String desc,
      String label) {
    int matches = 0;
    for (MethodInsnNode call : calls(method, opcode, owner, name, desc)) {
      AbstractInsnNode next = nextMeaningful(call);
      if (next instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE && store.var == local) matches++;
    }
    requireCount(label + " assignment", matches, 1);
  }

  static String uniqueRole(String current, String candidate, String label) {
    if (current != null && !current.equals(candidate)) throw mismatch("multiple fields match " + label);
    return candidate;
  }

  static void requireIconsGetter(ClassNode node, String fieldName) {
    MethodNode getter = requireMethod(node, "getIcons", "()Ljava/util/LinkedHashMap;");
    requireCount("getIcons backing field", countFields(getter, Opcodes.GETFIELD, node.name, fieldName, LINKED_MAP_DESC),
        1);
    requireCount("getIcons ARETURN", countOpcode(getter, Opcodes.ARETURN), 1);
  }

  static void requireNullReturn(MethodNode method, String label) {
    int count = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn.getOpcode() == Opcodes.ARETURN && previousMeaningful(insn) != null
          && previousMeaningful(insn).getOpcode() == Opcodes.ACONST_NULL)
        count++;
    }
    requireCount(label + " null return", count, 1);
  }

  static MethodInsnNode requireSourceMethod(SourceValue value, int opcode, String owner, String name, String desc,
      String label) {
    if (value == null || value.insns == null || value.insns.size() != 1) {
      throw mismatch(label + " has ambiguous producer set");
    }
    AbstractInsnNode source = value.insns.iterator().next();
    if (!(source instanceof MethodInsnNode call) || !callMatches(call, opcode, owner, name, desc)) {
      throw mismatch(label + " is not produced by " + owner + "." + name + desc);
    }
    return call;
  }

  static FieldInsnNode requireSourceField(SourceValue value, int opcode, String owner, String desc, String label) {
    FieldInsnNode field = optionalSourceField(value, opcode, owner, desc);
    if (field == null) throw mismatch(label + " is not produced by the expected field read");
    return field;
  }

  static int requireLoadedLocal(SourceValue value, int opcode, String label) {
    Integer local = optionalLoadedLocal(value, opcode);
    if (local == null) throw mismatch(label + " is not loaded unambiguously from one local");
    return local;
  }

  static MethodNode requireMethod(ClassNode node, String name, String desc) {
    List<MethodNode> found = methods(node, name, desc);
    if (found.size() != 1) {
      throw mismatch("expected one method " + node.name + "." + name + desc + ", found " + found.size());
    }
    return found.get(0);
  }

  static void requireCall(MethodInsnNode call, int opcode, String owner, String name, String desc, String label) {
    if (!callMatches(call, opcode, owner, name, desc)) {
      throw mismatch(label + " does not match " + owner + "." + name + desc);
    }
  }

  static <T> T only(List<T> values, String label) {
    if (values.size() != 1) throw mismatch(label + ": expected one site, found " + values.size());
    return values.get(0);
  }

  static void requireUnpatchedOrAlready(String label, int originals, int expectedOriginals, int hooks,
      int expectedHooks) {
    if (originals == 0 && hooks == expectedHooks) throw already(label + " hook postcondition matches");
    if (originals != expectedOriginals || hooks != 0) {
      throw mismatch(label + ": expected original=" + expectedOriginals + ", hooks=0; found original=" + originals
          + ", hooks=" + hooks);
    }
  }

  static void requireCount(String label, int actual, int expected) {
    if (actual != expected) throw mismatch(label + ": expected " + expected + ", found " + actual);
  }

  static void requireState(String label, PatchState actual, PatchState expected) {
    if (actual != expected) { throw mismatch(label + ": expected " + expected + ", found " + actual); }
  }

  static void requireCountAtLeast(String label, int actual, int minimum) {
    if (actual < minimum) throw mismatch(label + ": expected at least " + minimum + ", found " + actual);
  }

  static StructuralMismatchException mismatch(String message) {
    return new StructuralMismatchException(message);
  }

  static AlreadyPatchedException already(String message) {
    return new AlreadyPatchedException(message);
  }

  static CompositionMismatchException composition(String message) {
    return new CompositionMismatchException(message);
  }

}
