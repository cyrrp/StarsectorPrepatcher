package com.starsector.prepatcher.agent.economy;

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
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.ClassIO.*;
import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;

/**
 * Installs the AoTD deficit-resolver wrapper on BaseIndustry.getMaxDeficit,
 * keeping the original method as a private synthetic fallback.
 */
public final class BaseIndustryCleanDeficitPath implements StructuredPatch<BaseIndustryCleanDeficitPath.Plan> {
  private static final String RAW_METHOD = "spp$baseIndustryRawGetMaxDeficit";
  private static final String DESC = "([Ljava/lang/String;)Lcom/fs/starfarer/api/util/Pair;";
  private static final String TEMPLATE_OWNER = "com/starsector/prepatcher/agent/templates/BaseIndustryDeficitTemplate";
  private static final String TEMPLATE_RESOURCE = "/" + TEMPLATE_OWNER + ".class";
  private static final String RUNTIME_BRIDGE = "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge";

  record Plan(List<MethodNode> classMethods, MethodNode wrapper, MethodNode installed, int originalAccess,
      String originalSignature, String[] originalExceptions) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    List<MethodNode> rawMethods = methods(node, RAW_METHOD, DESC);
    MethodNode wrapper = requireMethod(node, "getMaxDeficit", DESC);
    int resolverCalls = countCalls(wrapper, Opcodes.INVOKESTATIC, RUNTIME_BRIDGE, "resolveAoTDMaxDeficit",
        "(Ljava/lang/Object;[Ljava/lang/String;)Ljava/lang/Object;");
    if (!rawMethods.isEmpty()) {
      requireCount("AoTD clean deficit raw method", rawMethods.size(), 1);
      MethodNode raw = rawMethods.get(0);
      if ((raw.access & Opcodes.ACC_PRIVATE) == 0 || (raw.access & Opcodes.ACC_SYNTHETIC) == 0) {
        throw mismatch("AoTD clean deficit raw method metadata changed");
      }
      requireCount("AoTD clean deficit resolver call", resolverCalls, 1);
      requireCount("AoTD clean deficit raw fallback call", countCalls(wrapper, -1, node.name, RAW_METHOD, DESC), 1);
      requireNoDeficitTemplateReferences(node);
      return Match.alreadyApplied("clean BaseIndustry deficit wrapper postcondition matches");
    }

    requireCount("AoTD clean deficit raw method", rawMethods.size(), 0);
    requireCount("AoTD clean deficit resolver call", resolverCalls, 0);
    if ((wrapper.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || wrapper.instructions == null
        || wrapper.instructions.size() == 0) {
      return Match.incompatible("BaseIndustry.getMaxDeficit has no concrete code");
    }

    ClassNode template = loadDeficitTemplate();
    MethodNode templateWrapper = requireMethod(template, "getMaxDeficit", DESC);
    MethodNode installed = cloneAndRemapDeficitTemplateMethod(templateWrapper, node.name);
    return Match
        .plan(new Plan(node.methods, wrapper, installed, wrapper.access, wrapper.signature, exceptions(wrapper)));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    MethodNode wrapper = plan.wrapper();
    wrapper.name = RAW_METHOD;
    wrapper.access =
        (wrapper.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;

    MethodNode installed = plan.installed();
    installed.access = plan.originalAccess();
    installed.signature = plan.originalSignature();
    installed.exceptions =
        plan.originalExceptions() == null ? null : new ArrayList<>(List.of(plan.originalExceptions()));
    plan.classMethods().add(installed);

    PatchReport report = new PatchReport();
    report.add("clean BaseIndustry deficit wrapper", 1);
    report.add("preserved vanilla deficit method", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireCount("AoTD clean deficit resolver call", countCalls(plan.installed(), Opcodes.INVOKESTATIC, RUNTIME_BRIDGE,
        "resolveAoTDMaxDeficit", "(Ljava/lang/Object;[Ljava/lang/String;)Ljava/lang/Object;"), 1);
    requireCount("AoTD clean deficit raw fallback call", countCalls(plan.installed(), -1, node.name, RAW_METHOD, DESC),
        1);
    requireNoDeficitTemplateReferences(node);
  }

  private static ClassNode loadDeficitTemplate() {
    try (InputStream input = BaseIndustryCleanDeficitPath.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
      if (input == null) throw mismatch("missing BaseIndustry deficit template resource");
      ClassNode template = readClass(input.readAllBytes());
      if (!TEMPLATE_OWNER.equals(template.name)) {
        throw mismatch("BaseIndustry deficit template owner changed: " + template.name);
      }
      return template;
    } catch (IOException ex) {
      throw mismatch("unable to read BaseIndustry deficit template: " + ex.getMessage());
    }
  }

  private static MethodNode cloneAndRemapDeficitTemplateMethod(MethodNode source, String targetOwner) {
    MethodNode copy =
        new MethodNode(Opcodes.ASM8, source.access, source.name, source.desc, source.signature, exceptions(source));
    source.accept(copy);
    remapDeficitTemplateMethod(copy, targetOwner);
    return copy;
  }

  private static void remapDeficitTemplateMethod(MethodNode method, String targetOwner) {
    method.desc = remapDeficitTemplateText(method.desc, targetOwner);
    method.signature = remapDeficitTemplateText(method.signature, targetOwner);
    if (method.exceptions != null) {
      for (int i = 0; i < method.exceptions.size(); i++) {
        method.exceptions.set(i, remapDeficitTemplateText(method.exceptions.get(i), targetOwner));
      }
    }
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof FieldInsnNode field) {
        field.owner = remapDeficitTemplateText(field.owner, targetOwner);
        field.desc = remapDeficitTemplateText(field.desc, targetOwner);
      } else if (insn instanceof MethodInsnNode call) {
        call.owner = remapDeficitTemplateText(call.owner, targetOwner);
        call.desc = remapDeficitTemplateText(call.desc, targetOwner);
      } else if (insn instanceof TypeInsnNode type) {
        type.desc = remapDeficitTemplateText(type.desc, targetOwner);
      } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type) {
        ldc.cst = Type.getType(remapDeficitTemplateText(type.getDescriptor(), targetOwner));
      } else if (insn instanceof MultiANewArrayInsnNode array) {
        array.desc = remapDeficitTemplateText(array.desc, targetOwner);
      } else if (insn instanceof FrameNode frame) {
        remapDeficitTemplateFrameValues(frame.local, targetOwner);
        remapDeficitTemplateFrameValues(frame.stack, targetOwner);
      } else if (insn instanceof InvokeDynamicInsnNode) {
        throw mismatch("unexpected invokedynamic in BaseIndustry deficit template");
      }
    }
    for (TryCatchBlockNode block : method.tryCatchBlocks) {
      block.type = remapDeficitTemplateText(block.type, targetOwner);
    }
    if (method.localVariables != null) {
      for (LocalVariableNode local : method.localVariables) {
        local.desc = remapDeficitTemplateText(local.desc, targetOwner);
        local.signature = remapDeficitTemplateText(local.signature, targetOwner);
      }
    }
  }

  private static void remapDeficitTemplateFrameValues(List<Object> values, String targetOwner) {
    if (values == null) return;
    for (int i = 0; i < values.size(); i++) {
      Object value = values.get(i);
      if (value instanceof String text) {
        values.set(i, remapDeficitTemplateText(text, targetOwner));
      }
    }
  }

  private static String remapDeficitTemplateText(String value, String targetOwner) {
    return value == null ? null : value.replace(TEMPLATE_OWNER, targetOwner);
  }

  private static void requireNoDeficitTemplateReferences(ClassNode node) {
    for (FieldNode field : node.fields) {
      if ((field.desc != null && field.desc.contains(TEMPLATE_OWNER))
          || (field.signature != null && field.signature.contains(TEMPLATE_OWNER))) {
        throw mismatch("BaseIndustry deficit template field reference leaked");
      }
    }
    for (MethodNode method : node.methods) {
      if ((method.desc != null && method.desc.contains(TEMPLATE_OWNER))
          || (method.signature != null && method.signature.contains(TEMPLATE_OWNER))) {
        throw mismatch("BaseIndustry deficit template method signature leaked");
      }
      for (AbstractInsnNode insn : method.instructions.toArray()) {
        if (insn instanceof FieldInsnNode field && field.owner.equals(TEMPLATE_OWNER)) {
          throw mismatch("BaseIndustry deficit template field instruction leaked");
        }
        if (insn instanceof MethodInsnNode call && call.owner.equals(TEMPLATE_OWNER)) {
          throw mismatch("BaseIndustry deficit template method instruction leaked");
        }
        if (insn instanceof TypeInsnNode type && type.desc.equals(TEMPLATE_OWNER)) {
          throw mismatch("BaseIndustry deficit template type instruction leaked");
        }
      }
    }
  }
}
