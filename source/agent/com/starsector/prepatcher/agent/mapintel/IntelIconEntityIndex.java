package com.starsector.prepatcher.agent.mapintel;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


import static com.starsector.prepatcher.agent.bytecode.Allocations.*;
import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Flow.*;
import static com.starsector.prepatcher.agent.bytecode.Mutations.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.patch.StructuredPatch;

/**
 * Wraps H.getIntelIconEntity so each lookup is answered by the entity index hook, and
 * preserves the original body as the private synthetic fallback. The wrapper is built
 * only after the original is proven to be a pure scan of the one iterated List field
 * keyed by INTEL_ICON_DATA_KEY with exactly one plugin identity comparison.
 */
public final class IntelIconEntityIndex implements StructuredPatch<IntelIconEntityIndex.Plan> {
  private static final String DESC = "(" + INTEL_DESC + ")Lcom/fs/starfarer/campaign/CustomCampaignEntity;";
  private static final String RENAMED = "smo$originalGetIntelIconEntity";
  private static final String HOOK_DESC = "(Ljava/lang/Object;Ljava/util/List;" + INTEL_DESC + ")Ljava/lang/Object;";

  /** The proven original body, the iterated List field, and the class's method list. */
  record Plan(String owner, List<MethodNode> classMethods, MethodNode original, String listField) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    boolean wrapperAlready = methods(node, RENAMED, DESC).size() == 1;
    MethodNode original =
        requireWrapperSource(node, "getIntelIconEntity", DESC, RENAMED, "getIntelIconEntityIndexed", HOOK_DESC);

    if (containsWrite(original)) {
      return Match.incompatible("getIntelIconEntity has field/static writes and is not a pure lookup");
    }
    requireCount("getIntelIconEntity semantic call count", totalMethodCalls(original), 5);
    Frame<SourceValue>[] frames = sourceFrames(node.name, original);
    Set<String> listFields = new LinkedHashSet<>();
    for (MethodInsnNode iterator : calls(original, Opcodes.INVOKEINTERFACE, "java/util/List", "iterator",
        "()Ljava/util/Iterator;")) {
      FieldInsnNode field =
          optionalSourceField(receiverSource(original, iterator, frames), Opcodes.GETFIELD, node.name, LIST_DESC);
      if (field != null) listFields.add(field.name);
    }
    if (listFields.size() != 1) {
      return Match.incompatible("expected one iterated Intel-data List field, found " + listFields);
    }
    String listField = listFields.iterator().next();
    requireCount("CustomCampaignEntity.getCustomData", countCalls(original, Opcodes.INVOKEVIRTUAL,
        "com/fs/starfarer/campaign/CustomCampaignEntity", "getCustomData", "()Ljava/util/Map;"), 1);
    requireCount("Intel custom-data Map.get",
        countCalls(original, Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;"),
        1);
    requireCount("INTEL_ICON_DATA_KEY reads",
        countFields(original, Opcodes.GETSTATIC, node.name, "INTEL_ICON_DATA_KEY", "Ljava/lang/String;"), 1);

    int pluginArg = argumentLocal(original, 0);
    int identityComparisons = 0;
    for (AbstractInsnNode insn : original.instructions.toArray()) {
      if (!(insn instanceof JumpInsnNode jump)
          || (jump.getOpcode() != Opcodes.IF_ACMPEQ && jump.getOpcode() != Opcodes.IF_ACMPNE))
        continue;
      Frame<SourceValue> frame = frameBefore(original, jump, frames);
      if (frame.getStackSize() < 2) continue;
      SourceValue left = frame.getStack(frame.getStackSize() - 2);
      SourceValue right = frame.getStack(frame.getStackSize() - 1);
      boolean match = (sourceIsFieldDesc(left, INTEL_DESC) && sourceIsLocal(right, Opcodes.ALOAD, pluginArg))
          || (sourceIsFieldDesc(right, INTEL_DESC) && sourceIsLocal(left, Opcodes.ALOAD, pluginArg));
      if (match) identityComparisons++;
    }
    requireCount("Intel plugin identity comparison", identityComparisons, 1);
    requireNullReturn(original, "getIntelIconEntity");
    if (wrapperAlready) {
      requireIntelEntityWrapper(node, DESC, listField, HOOK_DESC);
      return Match.alreadyApplied("getIntelIconEntity wrapper, original semantics, and field wiring match");
    }
    return Match.plan(new Plan(node.name, node.methods, original, listField));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    MethodNode original = plan.original();
    int originalAccess = original.access;
    String signature = original.signature;
    String[] exceptions = exceptions(original);
    original.name = RENAMED;
    makePrivateSynthetic(original);

    MethodNode wrapper =
        new MethodNode(Opcodes.ASM8, originalAccess, "getIntelIconEntity", DESC, signature, exceptions);
    moveWrapperMetadata(original, wrapper, "getIntelIconEntity");
    wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
    wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
    wrapper.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, plan.owner(), plan.listField(), LIST_DESC));
    wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, argumentLocal(wrapper, 0)));
    wrapper.instructions
        .add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "getIntelIconEntityIndexed", HOOK_DESC, false));
    wrapper.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "com/fs/starfarer/campaign/CustomCampaignEntity"));
    wrapper.instructions.add(new InsnNode(Opcodes.ARETURN));
    plan.classMethods().add(wrapper);

    PatchReport report = new PatchReport();
    report.add("Intel entity identity index", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireWrapperPostcondition(node, "getIntelIconEntity", DESC, RENAMED, "getIntelIconEntityIndexed", HOOK_DESC);
    requireIntelEntityWrapper(node, DESC, plan.listField(), HOOK_DESC);
  }

  private static void requireIntelEntityWrapper(ClassNode node, String desc, String listField, String hookDesc) {
    MethodNode wrapper = requireMethod(node, "getIntelIconEntity", desc);
    List<AbstractInsnNode> code = meaningfulInstructions(wrapper);
    requireCount("getIntelIconEntity wrapper instruction count", code.size(), 7);
    requireVar(code.get(0), Opcodes.ALOAD, 0, "Intel wrapper owner");
    requireVar(code.get(1), Opcodes.ALOAD, 0, "Intel wrapper field owner");
    requireField(code.get(2), Opcodes.GETFIELD, node.name, listField, LIST_DESC, "Intel wrapper List field");
    requireVar(code.get(3), Opcodes.ALOAD, argumentLocal(wrapper, 0), "Intel wrapper plugin");
    requireCall(asMethod(code.get(4), "Intel wrapper hook"), Opcodes.INVOKESTATIC, HOOKS, "getIntelIconEntityIndexed",
        hookDesc, "Intel wrapper hook");
    if (!(code.get(5) instanceof TypeInsnNode cast) || cast.getOpcode() != Opcodes.CHECKCAST
        || !cast.desc.equals("com/fs/starfarer/campaign/CustomCampaignEntity")) {
      throw mismatch("Intel wrapper result cast changed");
    }
    if (code.get(6).getOpcode() != Opcodes.ARETURN) throw mismatch("Intel wrapper return changed");
  }
}
