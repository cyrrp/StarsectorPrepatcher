package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import java.util.HashSet;
import java.util.Set;

import static com.starsector.prepatcher.agent.Contract.*;

final class ClassIO {
  static ClassNode readClassExpanded(byte[] bytes) {
    ClassNode node = new ClassNode(Opcodes.ASM8);
    new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
    return node;
  }

  static Set<String> publicApi(ClassNode node) {
    Set<String> api = new HashSet<>();
    for (FieldNode field : node.fields) {
      if ((field.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0) {
        api.add("F:" + field.name + ":" + field.desc + ":" + (field.access & apiAccessMask()));
      }
    }
    for (MethodNode method : node.methods) {
      if ((method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0) {
        api.add("M:" + method.name + method.desc + ":" + (method.access & apiAccessMask()));
      }
    }
    return api;
  }

  private static int apiAccessMask() {
    return Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ABSTRACT
        | Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_NATIVE | Opcodes.ACC_VARARGS;
  }

  static ClassNode readClass(byte[] bytes) {
    ClassNode node = new ClassNode(Opcodes.ASM8);
    new ClassReader(bytes).accept(node, 0);
    return node;
  }

  static byte[] writeClass(ClassNode node) {
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    node.accept(writer);
    return writer.toByteArray();
  }

  static void verifyClass(byte[] bytes) {
    ClassNode node = readClass(bytes);
    for (MethodNode method : node.methods) {
      if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
      try {
        new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
      } catch (AnalyzerException ex) {
        throw mismatch(
            "BasicVerifier rejected " + node.name + "." + method.name + method.desc + ": " + ex.getMessage());
      }
    }
  }

}
