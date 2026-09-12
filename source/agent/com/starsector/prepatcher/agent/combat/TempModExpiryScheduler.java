package com.starsector.prepatcher.agent.combat;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import java.util.ArrayList;
import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Mutations.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.bytecode.OwnedTemplates.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;

/**
 * Keeps the unified transform/guard/fail-open architecture, but moves the hot scheduler state
 * directly into the game class. The nearest expiry is driven by repeated float subtraction,
 * matching vanilla's frame of expiry, while all surviving modifiers are materialized only at
 * a deadline or an externally observable mutation/read/save boundary.
 */
public final class TempModExpiryScheduler implements StructuredPatch<TempModExpiryScheduler.Plan> {
  private static final String TEMP_MOD_HOOKS = "com/fs/starfarer/api/StarsectorPrepatcherTempModHooks";
  private static final String TEMP_MOD_LEGACY_STATE_FIELD = "spp$tempModExpiryState";
  private static final String TEMP_MOD_TEMPLATE_OWNER =
      "com/fs/starfarer/api/combat/StarsectorPrepatcherTempModHybridTemplate";
  private static final String TEMP_MOD_TEMPLATE_RESOURCE = "/" + TEMP_MOD_TEMPLATE_OWNER + ".class";
  private static final String TEMP_MOD_RAW_ADVANCE = "spp$originalTempModAdvance";
  private static final String TEMP_MOD_RAW_GET_MODS = "spp$rawGetMods";
  private static final String TEMP_MOD_RAW_GET_MOD = "spp$rawGetMod";
  private static final String TEMP_MOD_RAW_REMOVE = "spp$rawRemoveTemporaryMod";
  private static final String TEMP_MOD_RAW_HAS = "spp$rawHasMod";
  private static final String TEMP_MOD_RAW_WRITE = "spp$rawWriteReplace";

  private static final String[][] TEMP_MOD_HYBRID_FIELDS =
      {{"spp$tempModHybridDeferredDays", "F"}, {"spp$tempModHybridTimeToNext", "F"},
          {"spp$tempModHybridScheduledMin", "F"}, {"spp$tempModHybridScheduledMinCount", "I"},
          {"spp$tempModHybridKnownSize", "I"}, {"spp$tempModHybridFlags", "I"}};

  private static final String[][] TEMP_MOD_HYBRID_HELPERS = {{"spp$tempModHybridUseVanilla", "()Z"},
      {"spp$tempModHybridSynchronize", "()V"}, {"spp$tempModHybridAuditForCommodity", "()V"},
      {"spp$tempModHybridDisableAndFlush", "()V"}, {"spp$tempModHybridApplyElapsed", "(FZZ)V"},
      {"spp$tempModHybridRecomputeSchedule", "()V"}, {"spp$tempModHybridAfterSet", "(ZFF)V"},
      {"spp$tempModHybridAfterRemoval", "(F)V"}, {"spp$tempModHybridSetSoleMinimum", "(F)V"},
      {"spp$tempModHybridResetForEmpty", "()V"}, {"spp$tempModHybridTrackable", "(F)Z"}};

  private static final int FIELD_ACCESS = Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC;

  record Plan(ClassNode node, MethodNode advance, MethodNode getMods, MethodNode getMod, MethodNode remove,
      MethodNode has, MethodNode write, List<MethodInsnNode> rawGetModsCalls) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    for (FieldNode field : node.fields) {
      if (field.name.equals(TEMP_MOD_LEGACY_STATE_FIELD)) {
        return Match.incompatible("legacy 0.9.1 temp-mod state field is already present");
      }
    }

    int hybridFieldsPresent = 0;
    for (String[] spec : TEMP_MOD_HYBRID_FIELDS) {
      List<FieldNode> matches = new ArrayList<>();
      for (FieldNode field : node.fields) {
        if (field.name.equals(spec[0])) matches.add(field);
      }
      if (!matches.isEmpty()) {
        hybridFieldsPresent++;
        requireCount("temp-mod hybrid field " + spec[0], matches.size(), 1);
        FieldNode field = matches.get(0);
        if (!field.desc.equals(spec[1]) || field.access != FIELD_ACCESS || field.signature != null
            || field.value != null) {
          return Match.incompatible("foreign temp-mod hybrid field " + spec[0]);
        }
      }
    }
    if (hybridFieldsPresent != 0) {
      requireCount("complete temp-mod hybrid field set", hybridFieldsPresent, TEMP_MOD_HYBRID_FIELDS.length);
      requireOptimizedTempModSchedulerShape(node);
      return Match.alreadyApplied("direct temp-mod hybrid scheduler postcondition matches");
    }

    requireOriginalTempModSchedulerShape(node);

    MethodNode advance = requireMethod(node, "advance", "(F)V");
    MethodNode getMods = requireMethod(node, "getMods", "()Ljava/util/Map;");
    MethodNode getMod = requireMethod(node, "getMod",
        "(Ljava/lang/String;F)Lcom/fs/starfarer/api/combat/MutableStatWithTempMods$TemporaryStatMod;");
    MethodNode remove = requireMethod(node, "removeTemporaryMod", "(Ljava/lang/String;)V");
    MethodNode has = requireMethod(node, "hasMod", "(Ljava/lang/String;)Z");
    MethodNode write = requireMethod(node, "writeReplace", "()Ljava/lang/Object;");

    List<MethodInsnNode> rawGetModsCalls = new ArrayList<>();
    for (MethodNode raw : List.of(advance, getMod, remove, has, write)) {
      for (AbstractInsnNode insn : raw.instructions.toArray()) {
        if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(node.name) || !call.name.equals("getMods")
            || !call.desc.equals("()Ljava/util/Map;"))
          continue;
        rawGetModsCalls.add(call);
      }
    }
    requireCount("MutableStatWithTempMods raw getMods rewrites", rawGetModsCalls.size(), 7);
    return Match.plan(new Plan(node, advance, getMods, getMod, remove, has, write, rawGetModsCalls));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    ClassNode node = plan.node();
    for (String[] spec : TEMP_MOD_HYBRID_FIELDS) {
      node.fields.add(new FieldNode(Opcodes.ASM8, FIELD_ACCESS, spec[0], spec[1], null, null));
    }

    MethodNode advanceWrapper = renameForPrivateOriginal(node, plan.advance(), TEMP_MOD_RAW_ADVANCE);
    MethodNode getModsWrapper = renameForPrivateOriginal(node, plan.getMods(), TEMP_MOD_RAW_GET_MODS);
    MethodNode getModWrapper = renameForPrivateOriginal(node, plan.getMod(), TEMP_MOD_RAW_GET_MOD);
    MethodNode removeWrapper = renameForPrivateOriginal(node, plan.remove(), TEMP_MOD_RAW_REMOVE);
    MethodNode hasWrapper = renameForPrivateOriginal(node, plan.has(), TEMP_MOD_RAW_HAS);
    MethodNode writeWrapper = renameForPrivateOriginal(node, plan.write(), TEMP_MOD_RAW_WRITE);

    for (MethodInsnNode call : plan.rawGetModsCalls()) {
      call.name = TEMP_MOD_RAW_GET_MODS;
      call.setOpcode(Opcodes.INVOKESPECIAL);
      call.itf = false;
    }

    ClassNode template = loadOwnedTemplate(TEMP_MOD_TEMPLATE_OWNER, TEMP_MOD_TEMPLATE_RESOURCE, "temp-mod hybrid");
    installTempModTemplateBody(template, node.name, "advance", "(F)V", advanceWrapper);
    installTempModTemplateBody(template, node.name, "getMods", "()Ljava/util/Map;", getModsWrapper);
    installTempModTemplateBody(template, node.name, "getMod",
        "(Ljava/lang/String;F)Lcom/fs/starfarer/api/combat/MutableStatWithTempMods$TemporaryStatMod;", getModWrapper);
    installTempModTemplateBody(template, node.name, "removeTemporaryMod", "(Ljava/lang/String;)V", removeWrapper);
    installTempModTemplateBody(template, node.name, "hasMod", "(Ljava/lang/String;)Z", hasWrapper);
    installTempModTemplateBody(template, node.name, "writeReplace", "()Ljava/lang/Object;", writeWrapper);
    for (String[] helper : TEMP_MOD_HYBRID_HELPERS) {
      node.methods.add(copyTempModTemplateMethod(template, node.name, helper[0], helper[1]));
    }

    PatchReport report = new PatchReport();
    report.add("direct float-countdown MutableStatWithTempMods hybrid scheduler", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireOptimizedTempModSchedulerShape(node);
  }

  private static void requireOriginalTempModSchedulerShape(ClassNode node) {
    requireField(node, "tempMods", "Ljava/util/LinkedHashMap;");
    for (FieldNode field : node.fields) {
      if (field.name.startsWith("spp$tempModHybrid") || field.name.equals(TEMP_MOD_LEGACY_STATE_FIELD)) {
        throw mismatch("foreign/partial temp-mod scheduler field " + field.name);
      }
    }
    for (String raw : List.of(TEMP_MOD_RAW_ADVANCE, TEMP_MOD_RAW_GET_MODS, TEMP_MOD_RAW_GET_MOD, TEMP_MOD_RAW_REMOVE,
        TEMP_MOD_RAW_HAS, TEMP_MOD_RAW_WRITE)) {
      for (MethodNode method : node.methods) {
        if (method.name.equals(raw)) { throw mismatch("foreign/partial temp-mod scheduler method " + raw); }
      }
    }
    for (String[] helper : TEMP_MOD_HYBRID_HELPERS) {
      if (!methods(node, helper[0], helper[1]).isEmpty()) {
        throw mismatch("foreign/partial temp-mod hybrid helper " + helper[0]);
      }
    }

    MethodNode advance = requireMethod(node, "advance", "(F)V");
    MethodNode getMods = requireMethod(node, "getMods", "()Ljava/util/Map;");
    MethodNode getMod = requireMethod(node, "getMod",
        "(Ljava/lang/String;F)Lcom/fs/starfarer/api/combat/MutableStatWithTempMods$TemporaryStatMod;");
    MethodNode remove = requireMethod(node, "removeTemporaryMod", "(Ljava/lang/String;)V");
    MethodNode has = requireMethod(node, "hasMod", "(Ljava/lang/String;)Z");
    MethodNode write = requireMethod(node, "writeReplace", "()Ljava/lang/Object;");

    requireCount("MutableStatWithTempMods.advance getMods calls",
        countCalls(advance, -1, node.name, "getMods", "()Ljava/util/Map;"), 2);
    requireCount("MutableStatWithTempMods.advance iterator removals",
        countCalls(advance, Opcodes.INVOKEINTERFACE, "java/util/Iterator", "remove", "()V"), 1);
    requireCount("MutableStatWithTempMods.advance unmodify calls",
        countCalls(advance, Opcodes.INVOKEVIRTUAL, null, "unmodify", "(Ljava/lang/String;)V"), 1);
    requireCount("MutableStatWithTempMods.getMods map construction", countNew(getMods, "java/util/LinkedHashMap"), 1);
    requireCount("MutableStatWithTempMods.getMod getMods calls",
        countCalls(getMod, -1, node.name, "getMods", "()Ljava/util/Map;"), 2);
    requireCount("MutableStatWithTempMods.remove getMods calls",
        countCalls(remove, -1, node.name, "getMods", "()Ljava/util/Map;"), 1);
    requireCount("MutableStatWithTempMods.has getMods calls",
        countCalls(has, -1, node.name, "getMods", "()Ljava/util/Map;"), 1);
    requireCount("MutableStatWithTempMods.write getMods calls",
        countCalls(write, -1, node.name, "getMods", "()Ljava/util/Map;"), 1);
  }

  private static void requireOptimizedTempModSchedulerShape(ClassNode node) {
    for (String[] spec : TEMP_MOD_HYBRID_FIELDS) {
      FieldNode field = requireField(node, spec[0], spec[1]);
      if (field.access != FIELD_ACCESS || field.signature != null || field.value != null) {
        throw mismatch("temp-mod hybrid field shape changed: " + spec[0]);
      }
    }
    for (FieldNode field : node.fields) {
      if (field.name.equals(TEMP_MOD_LEGACY_STATE_FIELD)) {
        throw mismatch("legacy temp-mod scheduler field coexists with hybrid");
      }
    }

    requireCount("temp-mod public advance wrapper", methods(node, "advance", "(F)V").size(), 1);
    requireCount("temp-mod raw advance", methods(node, TEMP_MOD_RAW_ADVANCE, "(F)V").size(), 1);
    requireCount("temp-mod public getMods wrapper", methods(node, "getMods", "()Ljava/util/Map;").size(), 1);
    requireCount("temp-mod raw getMods", methods(node, TEMP_MOD_RAW_GET_MODS, "()Ljava/util/Map;").size(), 1);
    requireCount("temp-mod private getMod wrapper", methods(node, "getMod",
        "(Ljava/lang/String;F)Lcom/fs/starfarer/api/combat/MutableStatWithTempMods$TemporaryStatMod;").size(), 1);
    requireCount("temp-mod raw getMod", methods(node, TEMP_MOD_RAW_GET_MOD,
        "(Ljava/lang/String;F)Lcom/fs/starfarer/api/combat/MutableStatWithTempMods$TemporaryStatMod;").size(), 1);
    requireCount("temp-mod remove wrapper", methods(node, "removeTemporaryMod", "(Ljava/lang/String;)V").size(), 1);
    requireCount("temp-mod raw remove", methods(node, TEMP_MOD_RAW_REMOVE, "(Ljava/lang/String;)V").size(), 1);
    requireCount("temp-mod has wrapper", methods(node, "hasMod", "(Ljava/lang/String;)Z").size(), 1);
    requireCount("temp-mod raw has", methods(node, TEMP_MOD_RAW_HAS, "(Ljava/lang/String;)Z").size(), 1);
    requireCount("temp-mod write wrapper", methods(node, "writeReplace", "()Ljava/lang/Object;").size(), 1);
    requireCount("temp-mod raw write", methods(node, TEMP_MOD_RAW_WRITE, "()Ljava/lang/Object;").size(), 1);
    for (String[] helper : TEMP_MOD_HYBRID_HELPERS) {
      MethodNode method = requireMethod(node, helper[0], helper[1]);
      if ((method.access & Opcodes.ACC_PRIVATE) == 0 || (method.access & Opcodes.ACC_SYNTHETIC) == 0) {
        throw mismatch("temp-mod hybrid helper access changed: " + helper[0]);
      }
    }

    MethodNode advance = requireMethod(node, "advance", "(F)V");
    MethodNode getMods = requireMethod(node, "getMods", "()Ljava/util/Map;");
    MethodNode getMod = requireMethod(node, "getMod",
        "(Ljava/lang/String;F)Lcom/fs/starfarer/api/combat/MutableStatWithTempMods$TemporaryStatMod;");
    MethodNode remove = requireMethod(node, "removeTemporaryMod", "(Ljava/lang/String;)V");
    MethodNode has = requireMethod(node, "hasMod", "(Ljava/lang/String;)Z");
    MethodNode write = requireMethod(node, "writeReplace", "()Ljava/lang/Object;");
    MethodNode apply = requireMethod(node, "spp$tempModHybridApplyElapsed", "(FZZ)V");

    requireCountAtLeast("temp-mod advance raw fallback",
        countCalls(advance, -1, node.name, TEMP_MOD_RAW_ADVANCE, "(F)V"), 1);
    requireCountAtLeast("temp-mod advance direct apply",
        countCalls(advance, -1, node.name, "spp$tempModHybridApplyElapsed", "(FZZ)V"), 2);
    requireCountAtLeast("temp-mod getMods raw path",
        countCalls(getMods, -1, node.name, TEMP_MOD_RAW_GET_MODS, "()Ljava/util/Map;"), 1);
    requireCountAtLeast("temp-mod getMod raw path", countCalls(getMod, -1, node.name, TEMP_MOD_RAW_GET_MOD,
        "(Ljava/lang/String;F)Lcom/fs/starfarer/api/combat/MutableStatWithTempMods$TemporaryStatMod;"), 1);
    requireCountAtLeast("temp-mod remove raw path",
        countCalls(remove, -1, node.name, TEMP_MOD_RAW_REMOVE, "(Ljava/lang/String;)V"), 1);
    requireCount("temp-mod has direct raw path",
        countCalls(has, -1, node.name, TEMP_MOD_RAW_HAS, "(Ljava/lang/String;)Z"), 1);
    requireCountAtLeast("temp-mod write raw path",
        countCalls(write, -1, node.name, TEMP_MOD_RAW_WRITE, "()Ljava/lang/Object;"), 1);
    requireCount("temp-mod direct iterator removal",
        countCalls(apply, Opcodes.INVOKEINTERFACE, "java/util/Iterator", "remove", "()V"), 1);
    requireCount("temp-mod direct unmodify",
        countCalls(apply, Opcodes.INVOKEVIRTUAL, null, "unmodify", "(Ljava/lang/String;)V"), 1);

    requireCount("temp-mod hybrid initial telemetry",
        countCallsInClass(node, TEMP_MOD_HOOKS, "recordHybridInitialSweep", "(II)V"), 1);
    requireCount("temp-mod hybrid expiry telemetry",
        countCallsInClass(node, TEMP_MOD_HOOKS, "recordHybridExpirySweep", "(II)V"), 1);
    requireCount("temp-mod hybrid synchronization telemetry",
        countCallsInClass(node, TEMP_MOD_HOOKS, "recordHybridSynchronizationSweep", "(II)V"), 1);
    requireCount("temp-mod hybrid rebuild telemetry",
        countCallsInClass(node, TEMP_MOD_HOOKS, "recordHybridScheduleRebuild", "(I)V"), 1);
    requireCount("temp-mod hybrid exposure telemetry",
        countCallsInClass(node, TEMP_MOD_HOOKS, "recordHybridExternalExposure", "()V"), 1);
    requireCount("temp-mod hybrid subclass telemetry",
        countCallsInClass(node, TEMP_MOD_HOOKS, "recordHybridSubclassFallback", "()V"), 1);
    requireCount("temp-mod hybrid failure telemetry",
        countCallsInClass(node, TEMP_MOD_HOOKS, "recordHybridFailureFallback", "()V"), 1);
    requireCount("legacy per-frame temp-mod hook removed", countCallsInClass(node, TEMP_MOD_HOOKS, "advance", null), 0);

    MethodNode rawAdvance = requireMethod(node, TEMP_MOD_RAW_ADVANCE, "(F)V");
    MethodNode rawGetMod = requireMethod(node, TEMP_MOD_RAW_GET_MOD,
        "(Ljava/lang/String;F)Lcom/fs/starfarer/api/combat/MutableStatWithTempMods$TemporaryStatMod;");
    MethodNode rawRemove = requireMethod(node, TEMP_MOD_RAW_REMOVE, "(Ljava/lang/String;)V");
    MethodNode rawHas = requireMethod(node, TEMP_MOD_RAW_HAS, "(Ljava/lang/String;)Z");
    MethodNode rawWrite = requireMethod(node, TEMP_MOD_RAW_WRITE, "()Ljava/lang/Object;");
    int rawCalls = 0;
    for (MethodNode raw : List.of(rawAdvance, rawGetMod, rawRemove, rawHas, rawWrite)) {
      rawCalls += countCalls(raw, -1, node.name, TEMP_MOD_RAW_GET_MODS, "()Ljava/util/Map;");
      requireCount("raw temp-mod method calls public getMods",
          countCalls(raw, -1, node.name, "getMods", "()Ljava/util/Map;"), 0);
    }
    requireCount("raw temp-mod getMods call count", rawCalls, 7);
    requireNoTempModTemplateReferences(node);
  }

  private static void installTempModTemplateBody(ClassNode template, String targetOwner, String name, String desc,
      MethodNode target) {
    MethodNode source = requireMethod(template, name, desc);
    MethodNode copy = cloneAndRemapOwnedTemplateMethod(source, TEMP_MOD_TEMPLATE_OWNER, targetOwner, "temp-mod hybrid");
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

  private static MethodNode copyTempModTemplateMethod(ClassNode template, String targetOwner, String name,
      String desc) {
    MethodNode source = requireMethod(template, name, desc);
    MethodNode copy = cloneAndRemapOwnedTemplateMethod(source, TEMP_MOD_TEMPLATE_OWNER, targetOwner, "temp-mod hybrid");
    copy.access = (copy.access & Opcodes.ACC_STATIC) | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
    return copy;
  }

  private static void requireNoTempModTemplateReferences(ClassNode node) {
    for (FieldNode field : node.fields) {
      if ((field.desc != null && field.desc.contains(TEMP_MOD_TEMPLATE_OWNER))
          || (field.signature != null && field.signature.contains(TEMP_MOD_TEMPLATE_OWNER))) {
        throw mismatch("temp-mod template field reference leaked into target");
      }
    }
    for (MethodNode method : node.methods) {
      if ((method.desc != null && method.desc.contains(TEMP_MOD_TEMPLATE_OWNER))
          || (method.signature != null && method.signature.contains(TEMP_MOD_TEMPLATE_OWNER))) {
        throw mismatch("temp-mod template method signature leaked into target");
      }
      for (AbstractInsnNode insn : method.instructions.toArray()) {
        if (insn instanceof FieldInsnNode field
            && (field.owner.contains(TEMP_MOD_TEMPLATE_OWNER) || field.desc.contains(TEMP_MOD_TEMPLATE_OWNER))) {
          throw mismatch("temp-mod template field instruction leaked into target");
        }
        if (insn instanceof MethodInsnNode call
            && (call.owner.contains(TEMP_MOD_TEMPLATE_OWNER) || call.desc.contains(TEMP_MOD_TEMPLATE_OWNER))) {
          throw mismatch("temp-mod template method instruction leaked into target");
        }
        if (insn instanceof TypeInsnNode type && type.desc.contains(TEMP_MOD_TEMPLATE_OWNER)) {
          throw mismatch("temp-mod template type instruction leaked into target");
        }
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type
            && type.getDescriptor().contains(TEMP_MOD_TEMPLATE_OWNER)) {
          throw mismatch("temp-mod template class literal leaked into target");
        }
      }
    }
  }
}
