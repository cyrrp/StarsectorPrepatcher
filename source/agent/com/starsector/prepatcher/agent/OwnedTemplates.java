package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static com.starsector.prepatcher.agent.ClassIO.*;
import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Queries.*;

final class OwnedTemplates {
  static ClassNode loadOwnedTemplate(String owner, String resource, String label) {
    try (InputStream input = PrepatcherTransformer.class.getResourceAsStream(resource)) {
      if (input == null) throw mismatch("missing " + label + " template resource");
      ClassNode template = readClass(input.readAllBytes());
      if (!owner.equals(template.name)) { throw mismatch(label + " template owner changed: " + template.name); }
      return template;
    } catch (IOException ex) {
      throw mismatch("unable to read " + label + " template: " + ex.getMessage());
    }
  }

  static MethodNode cloneAndRemapOwnedTemplateMethod(MethodNode source, String templateOwner, String targetOwner,
      String label) {
    MethodNode copy =
        new MethodNode(Opcodes.ASM8, source.access, source.name, source.desc, source.signature, exceptions(source));
    source.accept(copy);
    remapOwnedTemplateMethod(copy, templateOwner, targetOwner, label);
    return copy;
  }

  private static void remapOwnedTemplateMethod(MethodNode method, String templateOwner, String targetOwner,
      String label) {
    method.desc = remapOwnedTemplateText(method.desc, templateOwner, targetOwner);
    method.signature = remapOwnedTemplateText(method.signature, templateOwner, targetOwner);
    if (method.exceptions != null) {
      for (int i = 0; i < method.exceptions.size(); i++) {
        method.exceptions.set(i, remapOwnedTemplateText(method.exceptions.get(i), templateOwner, targetOwner));
      }
    }
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof FieldInsnNode field) {
        field.owner = remapOwnedTemplateText(field.owner, templateOwner, targetOwner);
        field.desc = remapOwnedTemplateText(field.desc, templateOwner, targetOwner);
      } else if (insn instanceof MethodInsnNode call) {
        call.owner = remapOwnedTemplateText(call.owner, templateOwner, targetOwner);
        call.desc = remapOwnedTemplateText(call.desc, templateOwner, targetOwner);
      } else if (insn instanceof TypeInsnNode type) {
        type.desc = remapOwnedTemplateText(type.desc, templateOwner, targetOwner);
      } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type) {
        ldc.cst = Type.getType(remapOwnedTemplateText(type.getDescriptor(), templateOwner, targetOwner));
      } else if (insn instanceof MultiANewArrayInsnNode array) {
        array.desc = remapOwnedTemplateText(array.desc, templateOwner, targetOwner);
      } else if (insn instanceof FrameNode frame) {
        remapOwnedTemplateFrameValues(frame.local, templateOwner, targetOwner);
        remapOwnedTemplateFrameValues(frame.stack, templateOwner, targetOwner);
      } else if (insn instanceof InvokeDynamicInsnNode) {
        throw mismatch("unexpected invokedynamic in " + label + " template");
      }
    }
    for (TryCatchBlockNode block : method.tryCatchBlocks) {
      block.type = remapOwnedTemplateText(block.type, templateOwner, targetOwner);
    }
    if (method.localVariables != null) {
      for (LocalVariableNode local : method.localVariables) {
        local.desc = remapOwnedTemplateText(local.desc, templateOwner, targetOwner);
        local.signature = remapOwnedTemplateText(local.signature, templateOwner, targetOwner);
      }
    }
  }

  private static void remapOwnedTemplateFrameValues(List<Object> values, String templateOwner, String targetOwner) {
    if (values == null) return;
    for (int i = 0; i < values.size(); i++) {
      Object value = values.get(i);
      if (value instanceof String text) {
        values.set(i, remapOwnedTemplateText(text, templateOwner, targetOwner));
      }
    }
  }

  private static String remapOwnedTemplateText(String value, String templateOwner, String targetOwner) {
    return value == null ? null : value.replace(templateOwner, targetOwner);
  }

  static void requireNoOwnedTemplateReferences(ClassNode node, String templateOwner, String label) {
    for (FieldNode field : node.fields) {
      if (containsTemplateOwner(field.desc, templateOwner) || containsTemplateOwner(field.signature, templateOwner)) {
        throw mismatch(label + " template field reference leaked");
      }
    }
    for (MethodNode method : node.methods) {
      if (containsTemplateOwner(method.desc, templateOwner) || containsTemplateOwner(method.signature, templateOwner)) {
        throw mismatch(label + " template method signature leaked");
      }
      for (AbstractInsnNode insn : method.instructions.toArray()) {
        if (insn instanceof FieldInsnNode field && (containsTemplateOwner(field.owner, templateOwner)
            || containsTemplateOwner(field.desc, templateOwner))) {
          throw mismatch(label + " template field instruction leaked");
        }
        if (insn instanceof MethodInsnNode call
            && (containsTemplateOwner(call.owner, templateOwner) || containsTemplateOwner(call.desc, templateOwner))) {
          throw mismatch(label + " template method instruction leaked");
        }
        if (insn instanceof TypeInsnNode type && containsTemplateOwner(type.desc, templateOwner)) {
          throw mismatch(label + " template type instruction leaked");
        }
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type
            && containsTemplateOwner(type.getDescriptor(), templateOwner)) {
          throw mismatch(label + " template class literal leaked");
        }
      }
    }
  }

  private static boolean containsTemplateOwner(String value, String templateOwner) {
    return value != null && value.contains(templateOwner);
  }

}
