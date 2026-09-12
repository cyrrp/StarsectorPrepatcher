package com.starsector.prepatcher.agent.economy;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.Allocations.*;
import static com.starsector.prepatcher.agent.bytecode.ClassIO.*;
import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.economy.EconomyGroupIndex.*;
import static com.starsector.prepatcher.agent.bytecode.Flow.*;
import static com.starsector.prepatcher.agent.bytecode.Markers.*;
import static com.starsector.prepatcher.agent.economy.MarketAdvance.*;
import static com.starsector.prepatcher.agent.economy.MarketScheduler.*;
import static com.starsector.prepatcher.agent.bytecode.Mutations.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.bytecode.OwnedTemplates.*;
import static com.starsector.prepatcher.agent.economy.PersistentState.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.PrepatcherTransformer;


/**
 * Optimizes only the empty inherited BaseIndustry.advance() steady state.
 * MarketConditionPlugin dispatch is intentionally untouched: benchmarking
 * showed that classifying an empty condition costs more than the empty
 * virtual callback itself. The complete original BaseIndustry method is
 * retained and periodically audited.
 */
public final class BaseIndustryDormantFastPath implements StructuredPatch<BaseIndustryDormantFastPath.Plan> {
  private static final String BASE_INDUSTRY_STATE_FIELD = "spp$marketNoOpState";
  private static final String BASE_INDUSTRY_COUNTDOWN_FIELD = "spp$marketNoOpCountdown";
  private static final String BASE_INDUSTRY_RAW_ADVANCE = "spp$baseIndustryRawAdvance";
  private static final String BASE_INDUSTRY_TEMPLATE_OWNER =
      "com/starsector/prepatcher/agent/templates/BaseIndustryDormantTemplate";
  private static final String BASE_INDUSTRY_TEMPLATE_RESOURCE = "/" + BASE_INDUSTRY_TEMPLATE_OWNER + ".class";
  private static final int BASE_INDUSTRY_AUDIT_PLACEHOLDER = 0x5A17CAFE;


  private static final int FIELD_ACCESS = Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC;
  private final int auditFrames;

  public BaseIndustryDormantFastPath(int auditFrames) {
    this.auditFrames = auditFrames;
  }

  record Plan(String owner, List<FieldNode> classFields, List<MethodNode> classMethods, MethodNode advance,
      MethodNode setDisrupted, AbstractInsnNode setDisruptedFirst, ClassNode template) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    int fieldsPresent = 0;
    for (String fieldName : List.of(BASE_INDUSTRY_STATE_FIELD, BASE_INDUSTRY_COUNTDOWN_FIELD)) {
      List<FieldNode> matches = node.fields.stream().filter(field -> field.name.equals(fieldName)).toList();
      if (!matches.isEmpty()) {
        fieldsPresent++;
        requireCount("BaseIndustry dormant field " + fieldName, matches.size(), 1);
        FieldNode field = matches.get(0);
        if (!field.desc.equals("I") || field.access != FIELD_ACCESS || field.signature != null || field.value != null) {
          return Match.incompatible("foreign BaseIndustry dormant field " + fieldName);
        }
      }
    }
    if (fieldsPresent != 0) {
      requireCount("complete BaseIndustry dormant field set", fieldsPresent, 2);
      requireOptimizedBaseIndustryDormantShape(node);
      return Match.alreadyApplied("direct dormant BaseIndustry fast path postcondition matches");
    }

    requireOriginalBaseIndustryDormantShape(node);
    MethodNode advance = requireMethod(node, "advance", "(F)V");
    MethodNode setDisrupted = requireMethod(node, "setDisrupted", "(FZ)V");
    AbstractInsnNode first = firstMeaningful(setDisrupted);
    if (first == null) return Match.incompatible("BaseIndustry.setDisrupted has no code");
    ClassNode template = loadBaseIndustryDormantTemplate();
    return Match.plan(new Plan(node.name, node.fields, node.methods, advance, setDisrupted, first, template));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    plan.classFields().add(new FieldNode(Opcodes.ASM8, FIELD_ACCESS, BASE_INDUSTRY_STATE_FIELD, "I", null, null));
    plan.classFields().add(new FieldNode(Opcodes.ASM8, FIELD_ACCESS, BASE_INDUSTRY_COUNTDOWN_FIELD, "I", null, null));

    MethodNode wrapper =
        renameForPrivateOriginal(plan.owner(), plan.classMethods(), plan.advance(), BASE_INDUSTRY_RAW_ADVANCE);
    installBaseIndustryTemplateBody(plan.template(), plan.owner(), wrapper);
    replaceBaseIndustryAuditPlaceholder(wrapper, auditFrames);

    InsnList wake = new InsnList();
    wake.add(new VarInsnNode(Opcodes.ALOAD, 0));
    wake.add(new InsnNode(Opcodes.ICONST_0));
    wake.add(new FieldInsnNode(Opcodes.PUTFIELD, plan.owner(), BASE_INDUSTRY_STATE_FIELD, "I"));
    wake.add(new VarInsnNode(Opcodes.ALOAD, 0));
    wake.add(new InsnNode(Opcodes.ICONST_0));
    wake.add(new FieldInsnNode(Opcodes.PUTFIELD, plan.owner(), BASE_INDUSTRY_COUNTDOWN_FIELD, "I"));
    plan.setDisrupted().instructions.insertBefore(plan.setDisruptedFirst(), wake);

    PatchReport report = new PatchReport();
    report.add("direct dormant inherited BaseIndustry fast path", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireOptimizedBaseIndustryDormantShape(node);
  }

  private static void requireOriginalBaseIndustryDormantShape(ClassNode node) {
    for (FieldNode field : node.fields) {
      if (field.name.startsWith("spp$marketNoOp")) {
        throw mismatch("foreign/partial BaseIndustry dormant field " + field.name);
      }
    }
    requireCount("foreign BaseIndustry raw advance method", (int) node.methods.stream()
        .filter(method -> method.name.equals(BASE_INDUSTRY_RAW_ADVANCE) && method.desc.equals("(F)V")).count(), 0);
    MethodNode advance = requireMethod(node, "advance", "(F)V");
    requireCount("BaseIndustry.advance isDisrupted",
        countCalls(advance, Opcodes.INVOKEVIRTUAL, node.name, "isDisrupted", "()Z"), 1);
    requireCount("BaseIndustry.advance disruptionFinished",
        countCalls(advance, Opcodes.INVOKEVIRTUAL, node.name, "disruptionFinished", "()V"), 1);
    requireCount("BaseIndustry.advance building reads",
        countFields(advance, Opcodes.GETFIELD, node.name, "building", "Z"), 1);
    requireCount("BaseIndustry.advance wasDisrupted reads",
        countFields(advance, Opcodes.GETFIELD, node.name, "wasDisrupted", "Z"), 1);
    requireCount("BaseIndustry.advance wasDisrupted writes",
        countFields(advance, Opcodes.PUTFIELD, node.name, "wasDisrupted", "Z"), 1);

    MethodNode setDisrupted = requireMethod(node, "setDisrupted", "(FZ)V");
    requireCount("BaseIndustry.setDisrupted canBeDisrupted",
        countCalls(setDisrupted, Opcodes.INVOKEVIRTUAL, node.name, "canBeDisrupted", "()Z"), 1);
    requireCount("BaseIndustry.setDisrupted dormant state writes",
        countFields(setDisrupted, Opcodes.PUTFIELD, node.name, BASE_INDUSTRY_STATE_FIELD, "I"), 0);
    requireCount("BaseIndustry.setDisrupted dormant countdown writes",
        countFields(setDisrupted, Opcodes.PUTFIELD, node.name, BASE_INDUSTRY_COUNTDOWN_FIELD, "I"), 0);
  }

  private static void requireOptimizedBaseIndustryDormantShape(ClassNode node) {
    final int fieldAccess = Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC;
    FieldNode state = requireField(node, BASE_INDUSTRY_STATE_FIELD, "I");
    FieldNode countdown = requireField(node, BASE_INDUSTRY_COUNTDOWN_FIELD, "I");
    if (state.access != fieldAccess || state.signature != null || state.value != null || countdown.access != fieldAccess
        || countdown.signature != null || countdown.value != null) {
      throw mismatch("BaseIndustry dormant field metadata changed");
    }

    MethodNode wrapper = requireMethod(node, "advance", "(F)V");
    MethodNode raw = requireMethod(node, BASE_INDUSTRY_RAW_ADVANCE, "(F)V");
    int rawAccess = Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
    if ((raw.access
        & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC)) != rawAccess) {
      throw mismatch("BaseIndustry raw advance access changed");
    }
    requireCount("BaseIndustry wrapper raw call", countCalls(wrapper, -1, node.name, BASE_INDUSTRY_RAW_ADVANCE, "(F)V"),
        1);
    requireCount("BaseIndustry wrapper eligibility call", countCalls(wrapper, Opcodes.INVOKESTATIC, HOOKS,
        "isBaseIndustryDormantFastPathEligible", "(Ljava/lang/Object;)Z"), 1);
    requireCount("BaseIndustry raw isDisrupted",
        countCalls(raw, Opcodes.INVOKEVIRTUAL, node.name, "isDisrupted", "()Z"), 1);
    requireCount("BaseIndustry raw disruptionFinished",
        countCalls(raw, Opcodes.INVOKEVIRTUAL, node.name, "disruptionFinished", "()V"), 1);

    for (AbstractInsnNode insn : wrapper.instructions.toArray()) {
      if (insn instanceof LdcInsnNode ldc && Integer.valueOf(BASE_INDUSTRY_AUDIT_PLACEHOLDER).equals(ldc.cst)) {
        throw mismatch("BaseIndustry audit placeholder leaked into target");
      }
    }

    MethodNode setDisrupted = requireMethod(node, "setDisrupted", "(FZ)V");
    List<AbstractInsnNode> code = meaningfulInstructions(setDisrupted);
    if (code.size() < 6) throw mismatch("BaseIndustry.setDisrupted wake prologue is short");
    requireVar(code.get(0), Opcodes.ALOAD, 0, "BaseIndustry wake receiver 1");
    if (code.get(1).getOpcode() != Opcodes.ICONST_0) { throw mismatch("BaseIndustry wake state constant changed"); }
    requireField(code.get(2), Opcodes.PUTFIELD, node.name, BASE_INDUSTRY_STATE_FIELD, "I", "BaseIndustry wake state");
    requireVar(code.get(3), Opcodes.ALOAD, 0, "BaseIndustry wake receiver 2");
    if (code.get(4).getOpcode() != Opcodes.ICONST_0) { throw mismatch("BaseIndustry wake countdown constant changed"); }
    requireField(code.get(5), Opcodes.PUTFIELD, node.name, BASE_INDUSTRY_COUNTDOWN_FIELD, "I",
        "BaseIndustry wake countdown");
    requireNoBaseIndustryTemplateReferences(node);
  }

  private static ClassNode loadBaseIndustryDormantTemplate() {
    try (InputStream input = PrepatcherTransformer.class.getResourceAsStream(BASE_INDUSTRY_TEMPLATE_RESOURCE)) {
      if (input == null) throw mismatch("missing BaseIndustry dormant template resource");
      ClassNode template = new ClassNode(Opcodes.ASM8);
      new ClassReader(input).accept(template, 0);
      if (!template.name.equals(BASE_INDUSTRY_TEMPLATE_OWNER)) {
        throw mismatch("BaseIndustry dormant template owner changed: " + template.name);
      }
      return template;
    } catch (IOException ex) {
      throw mismatch("unable to read BaseIndustry dormant template: " + ex.getMessage());
    }
  }

  private static void installBaseIndustryTemplateBody(ClassNode template, String targetOwner, MethodNode target) {
    MethodNode source = requireMethod(template, "advance", "(F)V");
    MethodNode copy = cloneAndRemapBaseIndustryTemplateMethod(source, targetOwner);
    target.instructions.clear();
    target.instructions.add(copy.instructions);
    target.tryCatchBlocks.clear();
    target.tryCatchBlocks.addAll(copy.tryCatchBlocks);
    target.localVariables = copy.localVariables;
    target.visibleLocalVariableAnnotations = copy.visibleLocalVariableAnnotations;
    target.invisibleLocalVariableAnnotations = copy.invisibleLocalVariableAnnotations;
    target.maxLocals = copy.maxLocals;
    target.maxStack = copy.maxStack;
  }

  private static MethodNode cloneAndRemapBaseIndustryTemplateMethod(MethodNode source, String targetOwner) {
    MethodNode copy =
        new MethodNode(Opcodes.ASM8, source.access, source.name, source.desc, source.signature, exceptions(source));
    source.accept(copy);
    remapBaseIndustryTemplateMethod(copy, targetOwner);
    return copy;
  }

  private static void remapBaseIndustryTemplateMethod(MethodNode method, String targetOwner) {
    method.desc = remapBaseIndustryTemplateText(method.desc, targetOwner);
    method.signature = remapBaseIndustryTemplateText(method.signature, targetOwner);
    if (method.exceptions != null) {
      for (int i = 0; i < method.exceptions.size(); i++) {
        method.exceptions.set(i, remapBaseIndustryTemplateText(method.exceptions.get(i), targetOwner));
      }
    }
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof FieldInsnNode field) {
        field.owner = remapBaseIndustryTemplateText(field.owner, targetOwner);
        field.desc = remapBaseIndustryTemplateText(field.desc, targetOwner);
      } else if (insn instanceof MethodInsnNode call) {
        call.owner = remapBaseIndustryTemplateText(call.owner, targetOwner);
        call.desc = remapBaseIndustryTemplateText(call.desc, targetOwner);
      } else if (insn instanceof TypeInsnNode type) {
        type.desc = remapBaseIndustryTemplateText(type.desc, targetOwner);
      } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type) {
        ldc.cst = Type.getType(remapBaseIndustryTemplateText(type.getDescriptor(), targetOwner));
      } else if (insn instanceof MultiANewArrayInsnNode array) {
        array.desc = remapBaseIndustryTemplateText(array.desc, targetOwner);
      } else if (insn instanceof FrameNode frame) {
        remapBaseIndustryFrameValues(frame.local, targetOwner);
        remapBaseIndustryFrameValues(frame.stack, targetOwner);
      } else if (insn instanceof InvokeDynamicInsnNode) {
        throw mismatch("unexpected invokedynamic in BaseIndustry dormant template");
      }
    }
    for (TryCatchBlockNode block : method.tryCatchBlocks) {
      block.type = remapBaseIndustryTemplateText(block.type, targetOwner);
    }
    if (method.localVariables != null) {
      for (LocalVariableNode local : method.localVariables) {
        local.desc = remapBaseIndustryTemplateText(local.desc, targetOwner);
        local.signature = remapBaseIndustryTemplateText(local.signature, targetOwner);
      }
    }
  }

  private static void remapBaseIndustryFrameValues(List<Object> values, String targetOwner) {
    if (values == null) return;
    for (int i = 0; i < values.size(); i++) {
      Object value = values.get(i);
      if (value instanceof String text) {
        values.set(i, remapBaseIndustryTemplateText(text, targetOwner));
      }
    }
  }

  private static String remapBaseIndustryTemplateText(String value, String targetOwner) {
    if (value == null) return null;
    return value.replace(BASE_INDUSTRY_TEMPLATE_OWNER, targetOwner);
  }

  private static void replaceBaseIndustryAuditPlaceholder(MethodNode method, int auditFrames) {
    int replacements = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof LdcInsnNode ldc && Integer.valueOf(BASE_INDUSTRY_AUDIT_PLACEHOLDER).equals(ldc.cst)) {
        ldc.cst = Integer.valueOf(auditFrames);
        replacements++;
      }
    }
    requireCount("BaseIndustry audit-frame placeholder", replacements, 1);
  }

  private static void requireNoBaseIndustryTemplateReferences(ClassNode node) {
    for (FieldNode field : node.fields) {
      if ((field.desc != null && field.desc.contains(BASE_INDUSTRY_TEMPLATE_OWNER))
          || (field.signature != null && field.signature.contains(BASE_INDUSTRY_TEMPLATE_OWNER))) {
        throw mismatch("BaseIndustry template field reference leaked into target");
      }
    }
    for (MethodNode method : node.methods) {
      if ((method.desc != null && method.desc.contains(BASE_INDUSTRY_TEMPLATE_OWNER))
          || (method.signature != null && method.signature.contains(BASE_INDUSTRY_TEMPLATE_OWNER))) {
        throw mismatch("BaseIndustry template method signature leaked into target");
      }
      for (AbstractInsnNode insn : method.instructions.toArray()) {
        if (insn instanceof FieldInsnNode field && (field.owner.contains(BASE_INDUSTRY_TEMPLATE_OWNER)
            || field.desc.contains(BASE_INDUSTRY_TEMPLATE_OWNER))) {
          throw mismatch("BaseIndustry template field instruction leaked into target");
        }
        if (insn instanceof MethodInsnNode call && (call.owner.contains(BASE_INDUSTRY_TEMPLATE_OWNER)
            || call.desc.contains(BASE_INDUSTRY_TEMPLATE_OWNER))) {
          throw mismatch("BaseIndustry template method instruction leaked into target");
        }
        if (insn instanceof TypeInsnNode type && type.desc.contains(BASE_INDUSTRY_TEMPLATE_OWNER)) {
          throw mismatch("BaseIndustry template type instruction leaked into target");
        }
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type
            && type.getDescriptor().contains(BASE_INDUSTRY_TEMPLATE_OWNER)) {
          throw mismatch("BaseIndustry template class literal leaked into target");
        }
      }
    }
  }
}

