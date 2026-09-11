package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;
import java.util.ArrayList;
import java.util.List;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Flow.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.Queries.*;

final class PersistentState {
  static final String PERSISTENT_STATE_DESC = "Ljava/lang/Object;";

  static int persistentStateAccess() {
    return Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC;
  }

  static boolean hasExactSyntheticField(ClassNode node, String name, String desc, int access) {
    FieldNode found = null;
    for (FieldNode field : node.fields) {
      if (!field.name.equals(name)) continue;
      if (found != null) throw mismatch("duplicate synthetic field " + node.name + "." + name);
      found = field;
    }
    if (found == null) return false;
    if (!found.desc.equals(desc) || found.access != access || found.signature != null || found.value != null) {
      throw mismatch("foreign or malformed synthetic field " + node.name + "." + name);
    }
    return true;
  }

  static boolean hasExactPersistentAccessor(ClassNode node, String name, String desc, boolean timed, String stateField,
      int auditValue, int kind) {
    List<MethodNode> methods = methods(node, name, desc);
    if (methods.isEmpty()) return false;
    if (methods.size() != 1) throw mismatch("duplicate persistent accessor " + name + desc);
    MethodNode method = methods.get(0);
    int expectedAccess = Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
    if (method.access != expectedAccess || method.signature != null
        || (method.exceptions != null && !method.exceptions.isEmpty())) {
      throw mismatch("foreign persistent accessor metadata " + name + desc);
    }
    List<AbstractInsnNode> code = meaningfulInstructions(method);
    int expected = timed ? 8 : 7;
    requireCount(name + " instruction count", code.size(), expected);
    requireVar(code.get(0), Opcodes.ALOAD, 0, name + " state owner");
    requireField(code.get(1), Opcodes.GETFIELD, node.name, stateField, PERSISTENT_STATE_DESC, name + " state field");
    int cursor = 2;
    if (timed) {
      requireVar(code.get(cursor++), Opcodes.ALOAD, 0, name + " source owner");
      requireCall(asMethod(code.get(cursor++), name + " source call"), Opcodes.INVOKEVIRTUAL, node.name, "getMarkets",
          "()Ljava/util/List;", name + " source call");
    } else {
      requireVar(code.get(cursor++), Opcodes.ALOAD, 1, name + " source argument");
    }
    if (!isIntegerLdc(code.get(cursor++), auditValue) || !isIntegerLdc(code.get(cursor++), kind)) {
      throw mismatch(name + " audit/kind constants changed");
    }
    String hookName = timed ? "borrowPersistentSnapshotTimed" : "borrowPersistentSnapshotFrames";
    requireCall(asMethod(code.get(cursor++), name + " hook"), Opcodes.INVOKESTATIC, HOOKS, hookName,
        "(Ljava/lang/Object;Ljava/util/Collection;II)Ljava/util/ArrayList;", name + " hook");
    if (code.get(cursor).getOpcode() != Opcodes.ARETURN) {
      throw mismatch(name + " no longer returns the persistent snapshot");
    }
    return true;
  }

  static void initializePersistentStateAfterSuper(ClassNode node, MethodNode constructor, String stateField,
      String label) {
    List<MethodInsnNode> supers = calls(constructor, Opcodes.INVOKESPECIAL, node.superName, "<init>", "()V");
    requireCount(label + " super constructor", supers.size(), 1);
    initializePersistentStateAfterAnchor(constructor, supers.get(0), node.name, stateField);
  }

  static void initializePersistentStateAtEntry(MethodNode method, String owner, String stateField, String label) {
    AbstractInsnNode first = firstMeaningful(method);
    if (first == null) throw mismatch(label + " has no executable body");
    InsnList init = persistentStateInitializer(owner, stateField, 0);
    method.instructions.insertBefore(first, init);
  }

  static void initializePersistentStateAfterAnchor(MethodNode method, AbstractInsnNode anchor, String owner,
      String stateField) {
    method.instructions.insert(anchor, persistentStateInitializer(owner, stateField, 0));
  }

  static InsnList persistentStateInitializer(String owner, String stateField, int ownerLocal) {
    InsnList init = new InsnList();
    init.add(new VarInsnNode(Opcodes.ALOAD, ownerLocal));
    init.add(
        new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "newPersistentSnapshotState", "()Ljava/lang/Object;", false));
    init.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, stateField, PERSISTENT_STATE_DESC));
    return init;
  }

  static FieldInsnNode lastPersistentStateInitializer(MethodNode method, String owner, String stateField) {
    FieldInsnNode result = null;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD && field.owner.equals(owner)
          && field.name.equals(stateField) && field.desc.equals(PERSISTENT_STATE_DESC))
        result = field;
    }
    if (result == null) throw mismatch("missing persistent state initializer " + stateField);
    return result;
  }

  static int countPersistentStateInitializers(MethodNode method, String owner, String stateField) {
    int count = 0;
    for (MethodInsnNode call : calls(method, Opcodes.INVOKESTATIC, HOOKS, "newPersistentSnapshotState",
        "()Ljava/lang/Object;")) {
      AbstractInsnNode next = nextMeaningful(call);
      if (next instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD && field.owner.equals(owner)
          && field.name.equals(stateField) && field.desc.equals(PERSISTENT_STATE_DESC))
        count++;
    }
    return count;
  }

  static void insertPersistentMarkAfterUniqueCall(MethodNode method, int opcode, String callOwner, String callName,
      String callDesc, String owner, String stateField, String label) {
    MethodInsnNode call = only(calls(method, opcode, callOwner, callName, callDesc), label);
    AbstractInsnNode anchor = call;
    AbstractInsnNode next = nextMeaningful(call);
    if (next != null && next.getOpcode() == Opcodes.POP) anchor = next;
    insertPersistentMarkAfter(method, anchor, owner, stateField);
  }

  static void insertPersistentMarkAfter(MethodNode method, AbstractInsnNode anchor, String owner, String stateField) {
    InsnList mark = new InsnList();
    mark.add(new VarInsnNode(Opcodes.ALOAD, 0));
    mark.add(new FieldInsnNode(Opcodes.GETFIELD, owner, stateField, PERSISTENT_STATE_DESC));
    mark.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "markPersistentSnapshotStructure", "(Ljava/lang/Object;)V",
        false));
    method.instructions.insert(anchor, mark);
  }

  static int countPersistentMarks(MethodNode method, String owner, String stateField) {
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    int count = 0;
    for (MethodInsnNode call : calls(method, Opcodes.INVOKESTATIC, HOOKS, "markPersistentSnapshotStructure",
        "(Ljava/lang/Object;)V")) {
      FieldInsnNode field =
          optionalSourceField(argumentSource(method, call, frames, 0), Opcodes.GETFIELD, owner, PERSISTENT_STATE_DESC);
      if (field != null && field.name.equals(stateField)) count++;
    }
    return count;
  }

}
