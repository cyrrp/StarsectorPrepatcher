package com.starsector.prepatcher.agent.campaign;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;
import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Flow.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;

/**
 * Notifies the core-worlds index after all normal BaseLocation tag API exits.
 * Direct edits through the live getTags() collection remain covered by the
 * bounded rotating non-core audit in the runtime.
 */
public final class CoreWorldsTagHooks implements StructuredPatch<CoreWorldsTagHooks.Plan> {
  record Plan(MethodNode add, MethodNode remove, MethodNode clear, List<AbstractInsnNode> addReturns,
      List<AbstractInsnNode> removeReturns, List<AbstractInsnNode> clearReturns, String tagHookDesc,
      String clearHookDesc) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    String locationDesc = "Lcom/fs/starfarer/api/campaign/LocationAPI;";
    String tagHookDesc = "(" + locationDesc + "Ljava/lang/String;)V";
    String clearHookDesc = "(" + locationDesc + ")V";
    MethodNode add = requireMethod(node, "addTag", "(Ljava/lang/String;)V");
    MethodNode remove = requireMethod(node, "removeTag", "(Ljava/lang/String;)V");
    MethodNode clear = requireMethod(node, "clearTags", "()V");
    if ((add.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
        || (remove.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
        || (clear.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
      return Match.incompatible("BaseLocation core-worlds tag methods have no code");
    }

    requireCoreWorldsTagMutationShape(node.name, add, "add", "(Ljava/lang/Object;)Z", true);
    requireCoreWorldsTagMutationShape(node.name, remove, "remove", "(Ljava/lang/Object;)Z", true);
    requireCoreWorldsTagMutationShape(node.name, clear, "clear", "()V", false);
    List<AbstractInsnNode> addReturns = campaignReturns(add);
    List<AbstractInsnNode> removeReturns = campaignReturns(remove);
    List<AbstractInsnNode> clearReturns = campaignReturns(clear);
    requireCount("BaseLocation.addTag normal returns", addReturns.size(), 1);
    requireCount("BaseLocation.removeTag normal returns", removeReturns.size(), 2);
    requireCount("BaseLocation.clearTags normal returns", clearReturns.size(), 1);

    int addHooks = countCalls(add, Opcodes.INVOKESTATIC, CORE_WORLDS_RUNTIME, "locationTagAdded", tagHookDesc);
    int removeHooks = countCalls(remove, Opcodes.INVOKESTATIC, CORE_WORLDS_RUNTIME, "locationTagRemoved", tagHookDesc);
    int clearHooks = countCalls(clear, Opcodes.INVOKESTATIC, CORE_WORLDS_RUNTIME, "locationTagsCleared", clearHookDesc);
    if (addHooks == addReturns.size() && removeHooks == removeReturns.size() && clearHooks == clearReturns.size()) {
      validateCoreWorldsTagHookReturns(add, addReturns, "locationTagAdded", tagHookDesc, true);
      validateCoreWorldsTagHookReturns(remove, removeReturns, "locationTagRemoved", tagHookDesc, true);
      validateCoreWorldsTagHookReturns(clear, clearReturns, "locationTagsCleared", clearHookDesc, false);
      return Match.alreadyApplied("BaseLocation core-worlds tag hooks match");
    }
    if (addHooks != 0 || removeHooks != 0 || clearHooks != 0) {
      return Match.incompatible(
          "BaseLocation core-worlds tag hook state is partial: add=" + addHooks + "/" + addReturns.size() + ", remove="
              + removeHooks + "/" + removeReturns.size() + ", clear=" + clearHooks + "/" + clearReturns.size());
    }
    return Match
        .plan(new Plan(add, remove, clear, addReturns, removeReturns, clearReturns, tagHookDesc, clearHookDesc));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    insertCoreWorldsTagHooks(plan.add(), plan.addReturns(), "locationTagAdded", plan.tagHookDesc(), true);
    insertCoreWorldsTagHooks(plan.remove(), plan.removeReturns(), "locationTagRemoved", plan.tagHookDesc(), true);
    insertCoreWorldsTagHooks(plan.clear(), plan.clearReturns(), "locationTagsCleared", plan.clearHookDesc(), false);

    PatchReport report = new PatchReport();
    report.add("BaseLocation core-tag mutation epilogues",
        plan.addReturns().size() + plan.removeReturns().size() + plan.clearReturns().size());
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    validateCoreWorldsTagHookReturns(plan.add(), plan.addReturns(), "locationTagAdded", plan.tagHookDesc(), true);
    validateCoreWorldsTagHookReturns(plan.remove(), plan.removeReturns(), "locationTagRemoved", plan.tagHookDesc(),
        true);
    validateCoreWorldsTagHookReturns(plan.clear(), plan.clearReturns(), "locationTagsCleared", plan.clearHookDesc(),
        false);
  }

  private static void requireCoreWorldsTagMutationShape(String owner, MethodNode method, String operation,
      String descriptor, boolean stringArgument) {
    MethodInsnNode mutation = only(calls(method, Opcodes.INVOKEVIRTUAL, "java/util/HashSet", operation, descriptor),
        "BaseLocation." + method.name + " HashSet." + operation);
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    if (!sourceIsFieldDesc(receiverSource(method, mutation, frames), "Ljava/util/HashSet;")) {
      throw mismatch("BaseLocation." + method.name + " mutation receiver is not the tag HashSet field");
    }
    if (stringArgument) {
      int loaded = requireLoadedLocal(argumentSource(method, mutation, frames, 0), Opcodes.ALOAD,
          "BaseLocation." + method.name + " tag argument");
      if (loaded != argumentLocal(method, 0)) {
        throw mismatch("BaseLocation." + method.name + " mutates a different tag argument local");
      }
    }
  }

  private static void insertCoreWorldsTagHooks(MethodNode method, List<AbstractInsnNode> returns, String hookName,
      String hookDesc, boolean stringArgument) {
    int tagLocal = stringArgument ? argumentLocal(method, 0) : -1;
    for (AbstractInsnNode returnInsn : returns) {
      InsnList hook = new InsnList();
      hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
      if (stringArgument) hook.add(new VarInsnNode(Opcodes.ALOAD, tagLocal));
      hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CORE_WORLDS_RUNTIME, hookName, hookDesc, false));
      method.instructions.insertBefore(returnInsn, hook);
    }
  }

  private static void validateCoreWorldsTagHookReturns(MethodNode method, List<AbstractInsnNode> returns,
      String hookName, String hookDesc, boolean stringArgument) {
    int tagLocal = stringArgument ? argumentLocal(method, 0) : -1;
    for (AbstractInsnNode returnInsn : returns) {
      MethodInsnNode hook = asMethod(previousMeaningful(returnInsn), method.name + " core-worlds tag hook");
      requireCall(hook, Opcodes.INVOKESTATIC, CORE_WORLDS_RUNTIME, hookName, hookDesc,
          method.name + " core-worlds tag hook");
      AbstractInsnNode locationLoad;
      if (stringArgument) {
        AbstractInsnNode tag = previousMeaningful(hook);
        requireVar(tag, Opcodes.ALOAD, tagLocal, method.name + " core-worlds tag argument");
        locationLoad = previousMeaningful(tag);
      } else {
        locationLoad = previousMeaningful(hook);
      }
      requireVar(locationLoad, Opcodes.ALOAD, 0, method.name + " core-worlds location argument");
    }
  }
}
