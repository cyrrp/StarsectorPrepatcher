package com.starsector.prepatcher.agent.uieconomy;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import static com.starsector.prepatcher.agent.bytecode.Contract.mismatch;

/** Shared admission and marker checks for the two industry-mutation surfaces. */
final class IndustryMutationContract {
  private IndustryMutationContract() {}

  /** Returns the first match; this contract does not reject duplicate method signatures. */
  static MethodNode requireMethod(ClassNode node, String name, String desc) {
    for (MethodNode method : node.methods) {
      if (name.equals(method.name) && desc.equals(method.desc)) return method;
    }
    throw mismatch("missing method " + name + desc);
  }

  static void requireConcreteInstance(MethodNode method, String name) {
    if ((method.access & Opcodes.ACC_PUBLIC) == 0
        || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
      throw mismatch(name + " access changed");
    }
  }

  /** Returns the first named field, leaving marker validation to the caller. */
  static FieldNode field(ClassNode node, String name) {
    for (FieldNode field : node.fields) if (name.equals(field.name)) return field;
    return null;
  }

  static void requireNoMarker(FieldNode marker) {
    if (marker != null) throw mismatch("owned marker already exists");
  }

  static void requireMarker(FieldNode marker, String expectedValue) {
    if (marker == null
        || marker.access != (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC)
        || !"Ljava/lang/String;".equals(marker.desc) || !expectedValue.equals(marker.value)) {
      throw mismatch("owned marker is missing or malformed");
    }
  }

  static void requireCount(String label, int actual, int expected) {
    if (actual != expected) { throw mismatch(label + " expected=" + expected + " actual=" + actual); }
  }
}
