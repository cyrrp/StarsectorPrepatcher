package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.List;


import static com.starsector.prepatcher.agent.Allocations.*;
import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Flow.*;
import static com.starsector.prepatcher.agent.Mutations.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.Queries.*;

/**
 * Replaces the linear LinkedHashMap.values() scan in H.getTextAlignmentFor with the
 * nearbyLabelIcons spatial-candidate hook, inside the scratch scope the hook borrows
 * from. A foreign hook without the scope is rejected as an incomplete state.
 */
final class NearbyLabelIconLookup implements StructuredPatch<NearbyLabelIconLookup.Plan> {
  private static final String HOOK_DESC =
      "(Ljava/util/LinkedHashMap;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Collection;";
  private static final String SCOPE = "H.getTextAlignmentFor";

  /** The proven method, the values() site, and the label argument local. */
  record Plan(String owner, MethodNode method, MethodInsnNode values, int targetLocal) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode method = requireMethod(node, "getTextAlignmentFor",
        "(Lcom/fs/starfarer/coreui/A/ooOO;)Lcom/fs/starfarer/api/ui/Alignment;");
    // The original flow installed the scope here; running the install guards in match
    // keeps the refusal order identical without mutating.
    PatchState scopeState = scratchScopeState(node.name, method, SCOPE);
    if (scopeState == PatchState.ORIGINAL) {
      requireScratchScopeInstallable(method, SCOPE);
    }
    List<MethodInsnNode> original =
        calls(method, Opcodes.INVOKEVIRTUAL, "java/util/LinkedHashMap", "values", "()Ljava/util/Collection;");
    int hooks = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "nearbyLabelIcons", HOOK_DESC);
    if (scopeState == PatchState.ORIGINAL && original.isEmpty() && hooks == 1) {
      return Match.incompatible("H.getTextAlignmentFor label hook exists without its required scratch scope");
    }
    requireUnpatchedOrAlready("H.getTextAlignmentFor values", original.size(), 1, hooks, 1);

    MethodInsnNode values = only(original, "H.getTextAlignmentFor values");
    Frame<SourceValue>[] frames = sourceFrames(node.name, method);
    FieldInsnNode iconsField = requireSourceField(receiverSource(method, values, frames), Opcodes.GETFIELD, node.name,
        LINKED_MAP_DESC, "label icons receiver");
    requireIconsGetter(node, iconsField.name);
    MethodInsnNode iterator = asMethod(nextMeaningful(values), "label values iterator");
    requireCall(iterator, Opcodes.INVOKEINTERFACE, "java/util/Collection", "iterator", "()Ljava/util/Iterator;",
        "label values iterator");
    if (countCalls(method, -1, "com/fs/starfarer/coreui/A/ooOO", "int", "()" + ENTITY_TOKEN_DESC) < 1) {
      return Match.incompatible("label icon SectorEntityToken accessor is absent");
    }
    return Match.plan(new Plan(node.name, method, values, argumentLocal(method, 0)));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    ensureScratchScope(plan.owner(), plan.method(), SCOPE);
    plan.method().instructions.insertBefore(plan.values(), new VarInsnNode(Opcodes.ALOAD, 0));
    plan.method().instructions.insertBefore(plan.values(), new VarInsnNode(Opcodes.ALOAD, plan.targetLocal()));
    makeStatic(plan.values(), HOOKS, "nearbyLabelIcons", HOOK_DESC);

    PatchReport report = new PatchReport();
    report.add("label spatial candidates", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireCount("H.getTextAlignmentFor label hook postcondition",
        countCalls(plan.method(), Opcodes.INVOKESTATIC, HOOKS, "nearbyLabelIcons", HOOK_DESC), 1);
    if (scratchScopeState(plan.owner(), plan.method(), SCOPE) != PatchState.PATCHED) {
      throw mismatch(SCOPE + " scratch-scope installation postcondition failed");
    }
  }
}
