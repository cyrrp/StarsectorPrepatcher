package com.starsector.prepatcher.agent.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Flow.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;

public final class Queries {
  public static int countStringConstants(MethodNode method, String value) {
    int count = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (isStringLdc(insn, value)) count++;
    }
    return count;
  }

  public static int countMethodInstructions(MethodNode method) {
    int count = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof MethodInsnNode) count++;
    }
    return count;
  }

  public static int countFieldInstructions(MethodNode method) {
    int count = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof FieldInsnNode) count++;
    }
    return count;
  }

  public static int countStringConstants(ClassNode node, String value) {
    int count = 0;
    for (MethodNode method : node.methods) {
      for (AbstractInsnNode insn : method.instructions.toArray()) {
        if (isStringLdc(insn, value)) count++;
      }
    }
    return count;
  }

  public static int countCategoryHooks(ClassNode node, String name, String desc, int category) {
    int count = 0;
    for (MethodNode method : node.methods) {
      for (MethodInsnNode call : calls(method, Opcodes.INVOKESTATIC, HOOKS, name, desc)) {
        if (isIntegerLdc(previousMeaningful(call), category)) count++;
      }
    }
    return count;
  }

  public static boolean isStringLdc(AbstractInsnNode insn, String value) {
    return insn instanceof LdcInsnNode ldc && value.equals(ldc.cst);
  }

  public static boolean isIntegerLdc(AbstractInsnNode insn, int value) {
    if (insn instanceof LdcInsnNode ldc) return Integer.valueOf(value).equals(ldc.cst);
    int opcode = insn == null ? -1 : insn.getOpcode();
    return (value == -1 && opcode == Opcodes.ICONST_M1)
        || (value >= 0 && value <= 5 && opcode == Opcodes.ICONST_0 + value)
        || (insn instanceof IntInsnNode integer && integer.operand == value
            && (integer.getOpcode() == Opcodes.BIPUSH || integer.getOpcode() == Opcodes.SIPUSH));
  }

  public static List<AbstractInsnNode> campaignReturns(MethodNode method) {
    ArrayList<AbstractInsnNode> returns = new ArrayList<>();
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn.getOpcode() == Opcodes.RETURN) returns.add(insn);
    }
    if (returns.isEmpty()) throw mismatch(method.name + " has no normal return");
    return returns;
  }

  public static List<AbstractInsnNode> opcodeReturns(MethodNode method, int opcode, String label) {
    ArrayList<AbstractInsnNode> returns = new ArrayList<>();
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn.getOpcode() == opcode) returns.add(insn);
    }
    if (returns.isEmpty()) throw mismatch(label + " has no expected return");
    return returns;
  }

  public static FieldNode findField(ClassNode node, String name) {
    FieldNode found = null;
    for (FieldNode field : node.fields) {
      if (!field.name.equals(name)) continue;
      if (found != null) throw mismatch("duplicate field " + node.name + "." + name);
      found = field;
    }
    return found;
  }

  public static int countImmediateArrayListIteratorSnapshots(MethodNode method) {
    int count = 0;
    for (MethodInsnNode constructor : calls(method, Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>",
        "(Ljava/util/Collection;)V")) {
      AbstractInsnNode next = nextMeaningful(constructor);
      if (next instanceof MethodInsnNode iterator
          && callMatches(iterator, Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "iterator", "()Ljava/util/Iterator;"))
        count++;
    }
    return count;
  }

  public static AbstractInsnNode firstExecutable(MethodNode method) {
    AbstractInsnNode first = method.instructions.getFirst();
    while (first != null && first.getOpcode() < 0) first = first.getNext();
    return first;
  }

  public static int countTypeInstructions(MethodNode method, int opcode, String desc) {
    int count = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof TypeInsnNode type && type.getOpcode() == opcode && type.desc.equals(desc)) count++;
    }
    return count;
  }

  public static int countClassLiterals(MethodNode method, String internalName) {
    int count = 0;
    String descriptor = "L" + internalName + ";";
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type && type.getDescriptor().equals(descriptor))
        count++;
    }
    return count;
  }

  public static int countCallsInClass(ClassNode node, String owner, String name, String desc) {
    int count = 0;
    for (MethodNode method : node.methods) {
      count += countCalls(method, -1, owner, name, desc);
    }
    return count;
  }

  public static int countNew(MethodNode method, String type) {
    int count = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
          && allocation.desc.equals(type))
        count++;
    }
    return count;
  }

  public static boolean hasExternalControlFlowEntry(MethodNode method, AbstractInsnNode start, AbstractInsnNode end) {
    Set<AbstractInsnNode> guarded = Collections.newSetFromMap(new IdentityHashMap<>());
    AbstractInsnNode cursor = start;
    while (cursor != null) {
      guarded.add(cursor);
      if (cursor == end) break;
      cursor = nextMeaningful(cursor);
    }
    if (cursor == null) return true;

    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (guarded.contains(insn)) continue;
      if (insn instanceof JumpInsnNode jump) {
        AbstractInsnNode entry = nextMeaningful(jump.label);
        if (entry != start && guarded.contains(entry)) return true;
      } else if (insn instanceof LookupSwitchInsnNode lookup) {
        AbstractInsnNode defaultEntry = nextMeaningful(lookup.dflt);
        if (defaultEntry != start && guarded.contains(defaultEntry)) return true;
        for (LabelNode label : lookup.labels) {
          AbstractInsnNode entry = nextMeaningful(label);
          if (entry != start && guarded.contains(entry)) return true;
        }
      } else if (insn instanceof TableSwitchInsnNode table) {
        AbstractInsnNode defaultEntry = nextMeaningful(table.dflt);
        if (defaultEntry != start && guarded.contains(defaultEntry)) return true;
        for (LabelNode label : table.labels) {
          AbstractInsnNode entry = nextMeaningful(label);
          if (entry != start && guarded.contains(entry)) return true;
        }
      }
    }
    for (TryCatchBlockNode block : method.tryCatchBlocks) {
      AbstractInsnNode entry = nextMeaningful(block.handler);
      if (entry != start && guarded.contains(entry)) return true;
    }
    return false;
  }

  public static int countReturnOpcodes(MethodNode method) {
    int count = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (isReturnOpcode(insn.getOpcode())) count++;
    }
    return count;
  }

  public static boolean isReturnOpcode(int opcode) {
    return opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN;
  }

  public static List<AbstractInsnNode> meaningfulInstructions(MethodNode method) {
    List<AbstractInsnNode> result = new ArrayList<>();
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn.getOpcode() >= 0) result.add(insn);
    }
    return result;
  }

  public static boolean hasMutatorAfter(String owner, MethodNode method, AbstractInsnNode after, int receiverLocal) {
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    Set<String> mutators = Set.of("add", "addAll", "clear", "remove", "removeAll", "retainAll", "set");
    for (AbstractInsnNode insn = after.getNext(); insn != null; insn = insn.getNext()) {
      if (!(insn instanceof MethodInsnNode call) || !mutators.contains(call.name)) continue;
      if (call.getOpcode() == Opcodes.INVOKESTATIC) continue;
      if (sourceIsLocal(receiverSource(method, call, frames), Opcodes.ALOAD, receiverLocal)) return true;
    }
    return false;
  }

  public static boolean hasNullGuard(MethodNode method, AbstractInsnNode after, AbstractInsnNode before, int local) {
    for (AbstractInsnNode insn = after.getNext(); insn != null && insn != before; insn = insn.getNext()) {
      if (!(insn instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != local) continue;
      AbstractInsnNode next = nextMeaningful(load);
      if (next != null && (next.getOpcode() == Opcodes.IFNULL || next.getOpcode() == Opcodes.IFNONNULL)) return true;
    }
    return false;
  }

  public static boolean hasCallImmediatelyBefore(AbstractInsnNode target, int opcode, String owner, String name,
      String desc, int maxMeaningful) {
    int seen = 0;
    for (AbstractInsnNode insn = target.getPrevious(); insn != null && seen < maxMeaningful; insn =
        insn.getPrevious()) {
      if (insn.getOpcode() < 0) continue;
      seen++;
      if (insn instanceof MethodInsnNode call && callMatches(call, opcode, owner, name, desc)) return true;
    }
    return false;
  }

  public static int countCallsWithFieldReceiver(String owner, MethodNode method, Frame<SourceValue>[] frames,
      int opcode, String callOwner, String name, String desc, String fieldName, String fieldDesc) {
    int count = 0;
    for (MethodInsnNode call : calls(method, opcode, callOwner, name, desc)) {
      FieldInsnNode field =
          optionalSourceField(receiverSource(method, call, frames), Opcodes.GETFIELD, owner, fieldDesc);
      if (field != null && field.name.equals(fieldName)) count++;
    }
    return count;
  }

  public static boolean containsWrite(MethodNode method) {
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (isStateWriteOpcode(insn.getOpcode())) return true;
    }
    return false;
  }

  public static boolean containsWriteOrMonitor(MethodNode method) {
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      int opcode = insn.getOpcode();
      if (isStateWriteOpcode(opcode) || opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT
          || opcode == Opcodes.INVOKEDYNAMIC)
        return true;
    }
    return false;
  }

  private static boolean isStateWriteOpcode(int opcode) {
    return opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC
        || (opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE);
  }

  public static int totalMethodCalls(MethodNode method) {
    int count = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof MethodInsnNode) count++;
    }
    return count;
  }

  public static int argumentLocal(MethodNode method, int argumentIndex) {
    Type[] args = Type.getArgumentTypes(method.desc);
    if (argumentIndex < 0 || argumentIndex >= args.length) throw mismatch("invalid argument index " + argumentIndex);
    int local = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
    for (int i = 0; i < argumentIndex; i++) local += args[i].getSize();
    return local;
  }

  public static MethodNode optionalMethod(ClassNode node, String name, String desc) {
    MethodNode found = null;
    for (MethodNode method : node.methods) {
      if (!method.name.equals(name) || !method.desc.equals(desc)) continue;
      if (found != null) throw mismatch("duplicate method " + name + desc);
      found = method;
    }
    return found;
  }

  public static List<MethodNode> methods(ClassNode node, String name, String desc) {
    List<MethodNode> result = new ArrayList<>();
    for (MethodNode method : node.methods) {
      if (method.name.equals(name) && method.desc.equals(desc)) result.add(method);
    }
    return result;
  }

  public static List<FieldNode> fields(ClassNode node, String desc) {
    List<FieldNode> result = new ArrayList<>();
    for (FieldNode field : node.fields) if (field.desc.equals(desc)) result.add(field);
    return result;
  }

  public static List<MethodInsnNode> calls(MethodNode method, int opcode, String owner, String name, String desc) {
    List<MethodInsnNode> result = new ArrayList<>();
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof MethodInsnNode call && callMatches(call, opcode, owner, name, desc)) result.add(call);
    }
    return result;
  }

  public static boolean callMatches(MethodInsnNode call, int opcode, String owner, String name, String desc) {
    return (opcode < 0 || call.getOpcode() == opcode) && (owner == null || call.owner.equals(owner))
        && (name == null || call.name.equals(name)) && (desc == null || call.desc.equals(desc));
  }

  public static int countCalls(MethodNode method, int opcode, String owner, String name, String desc) {
    return calls(method, opcode, owner, name, desc).size();
  }

  public static int countCalls(ClassNode node, int opcode, String owner, String name, String desc) {
    int count = 0;
    for (MethodNode method : node.methods) count += countCalls(method, opcode, owner, name, desc);
    return count;
  }

  public static int countFields(MethodNode method, int opcode, String owner, String name, String desc) {
    int count = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof FieldInsnNode field && (opcode < 0 || field.getOpcode() == opcode)
          && (owner == null || field.owner.equals(owner)) && (name == null || field.name.equals(name))
          && (desc == null || field.desc.equals(desc)))
        count++;
    }
    return count;
  }

  public static List<FieldInsnNode> fields(MethodNode method, int opcode, String owner, String name, String desc) {
    List<FieldInsnNode> result = new ArrayList<>();
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof FieldInsnNode field && (opcode < 0 || field.getOpcode() == opcode)
          && (owner == null || field.owner.equals(owner)) && (name == null || field.name.equals(name))
          && (desc == null || field.desc.equals(desc))) {
        result.add(field);
      }
    }
    return result;
  }

  public static int countOpcode(MethodNode method, int opcode) {
    int count = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) if (insn.getOpcode() == opcode) count++;
    return count;
  }

  public static int countStores(MethodNode method, int local) {
    int count = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof VarInsnNode var && var.getOpcode() == Opcodes.ASTORE && var.var == local) count++;
    }
    return count;
  }

  public static MethodInsnNode asMethod(AbstractInsnNode insn, String label) {
    if (insn instanceof MethodInsnNode method) return method;
    throw mismatch(label + " is not a method invocation");
  }

  public static AbstractInsnNode nextMeaningful(AbstractInsnNode node) {
    if (node == null) return null;
    AbstractInsnNode current = node.getNext();
    while (current != null && current.getOpcode() < 0) current = current.getNext();
    return current;
  }

  /** Raw instruction index by identity, including metadata nodes; -1 if absent. */
  public static int instructionIndex(MethodNode method, AbstractInsnNode target) {
    int index = 0;
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction == target) return index;
      index++;
    }
    return -1;
  }

  /** First invocation with this signature, irrespective of opcode; -1 if absent. */
  public static int instructionIndex(MethodNode method, String owner, String name, String desc) {
    int index = 0;
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof MethodInsnNode call && owner.equals(call.owner) && name.equals(call.name)
          && desc.equals(call.desc))
        return index;
      index++;
    }
    return -1;
  }

  public static AbstractInsnNode firstMeaningful(MethodNode method) {
    AbstractInsnNode current = method.instructions.getFirst();
    while (current != null && current.getOpcode() < 0) current = current.getNext();
    return current;
  }

  public static AbstractInsnNode previousMeaningful(AbstractInsnNode node) {
    if (node == null) return null;
    AbstractInsnNode current = node.getPrevious();
    while (current != null && current.getOpcode() < 0) current = current.getPrevious();
    return current;
  }

  public static boolean isFloat(LdcInsnNode ldc, float expected) {
    return ldc.cst instanceof Float value && Float.floatToIntBits(value) == Float.floatToIntBits(expected);
  }

  public static <T> List<T> concat(List<T> first, List<T> second) {
    List<T> result = new ArrayList<>(first.size() + second.size());
    result.addAll(first);
    result.addAll(second);
    return result;
  }

  public static String[] exceptions(MethodNode method) {
    if (method.exceptions == null) return null;
    return method.exceptions.toArray(new String[0]);
  }

}
