package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayList;
import java.util.List;

import com.starsector.prepatcher.agent.PrepatcherTransformer.Match;
import com.starsector.prepatcher.agent.PrepatcherTransformer.PatchReport;
import com.starsector.prepatcher.agent.PrepatcherTransformer.StructuredPatch;

import static com.starsector.prepatcher.agent.Allocations.*;
import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Flow.*;
import static com.starsector.prepatcher.agent.Mutations.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.Queries.*;

/**
 * Atomically installs the complete H.renderStuff surface: the linear map reconciliation
 * (retainAllFast), both render scratch collections borrowed from the scope pool, the seven
 * scratch-set peak hooks, and the single reentrant scratch scope. Partial or foreign
 * hook-shaped states reject the whole group.
 */
final class MapRenderReconciliation implements StructuredPatch<MapRenderReconciliation.Plan> {
  private static final String HOOK_DESC = "(Ljava/util/Set;Ljava/util/Collection;Ljava/lang/Object;)Z";
  private static final String SET_ADD_DESC = "(Ljava/util/Set;Ljava/lang/Object;)Z";
  private static final String SCOPE = "H.renderStuff";

  /** The proven method, the reconciliation call, the pre-mutation retainAll count, and the
   * per-spec allocation plans. */
  record Plan(String owner, MethodNode method, MethodInsnNode retainCall, int rawRetainAll,
      List<List<AllocationPlan>> plans) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode method = requireMethod(node, "renderStuff", "(FZ)V");
    List<AllocationSpec> specs = hRenderStuffAllocationSpecs();
    PatchState scopeState = scratchScopeState(node.name, method, SCOPE);
    PatchState mutationState = hRenderScratchMutationState(method);

    List<MethodInsnNode> rawCalls =
        calls(method, Opcodes.INVOKEINTERFACE, "java/util/Set", "retainAll", "(Ljava/util/Collection;)Z");
    Frame<SourceValue>[] frames = sourceFrames(node.name, method);
    List<MethodInsnNode> originalRetain = new ArrayList<>();
    for (MethodInsnNode candidate : rawCalls) {
      if (isMapReconciliationSite(node, method, candidate, frames)) {
        originalRetain.add(candidate);
      }
    }
    List<MethodInsnNode> retainHooks = calls(method, Opcodes.INVOKESTATIC, HOOKS, "retainAllFast", HOOK_DESC);
    boolean retainOriginal = originalRetain.size() == 1 && retainHooks.isEmpty();
    boolean retainPatched = originalRetain.isEmpty() && retainHooks.size() == 1;

    boolean allocationsOriginal = true;
    boolean allocationsPatched = true;
    List<List<AllocationPlan>> plans = new ArrayList<>();
    List<List<Integer>> hookedLocals = new ArrayList<>();
    for (AllocationSpec spec : specs) {
      List<AllocationPlan> found = hRenderStuffAllocationPlans(node.name, method, spec);
      List<Integer> locals = storedHookResultLocals(method, spec);
      allocationsOriginal &= found.size() == spec.expected() && locals.isEmpty();
      allocationsPatched &= found.isEmpty() && locals.size() == spec.expected();
      plans.add(found);
      hookedLocals.add(locals);
    }

    if (retainPatched && allocationsPatched) {
      if (scopeState != PatchState.PATCHED || mutationState != PatchState.PATCHED) {
        return Match.incompatible("H.renderStuff hooks exist without the complete scratch scope");
      }
      validatePatchedHRenderStuff(node, method, retainHooks.get(0), specs, hookedLocals);
      return Match
          .alreadyApplied("H.renderStuff map reconciliation, scratch collections, and scope " + "are already present");
    }

    if (!retainOriginal || !allocationsOriginal) {
      return Match.incompatible("H.renderStuff has mixed, missing, or ambiguous reconciliation/" + "allocation sites");
    }
    if (scopeState != PatchState.ORIGINAL || mutationState != PatchState.ORIGINAL) {
      return Match.incompatible("H.renderStuff has a scope/mutation hook without the complete owned group");
    }

    // Validate every scratch local before changing any instruction in the shared method.
    for (int i = 0; i < specs.size(); i++) {
      AllocationSpec spec = specs.get(i);
      for (int j = 0; j < plans.get(i).size(); j++) {
        requireScratchUseContract(node.name, method, plans.get(i).get(j).local(), spec, j, SCOPE);
      }
    }

    // The original applyHRetainAll installed the scope at this point; running the install
    // guards read-only here keeps the refusal order identical.
    requireScratchScopeInstallable(method, SCOPE);
    MethodInsnNode call = originalRetain.get(0);
    int entityListLocal =
        requireLoadedLocal(argumentSource(method, call, frames, 0), Opcodes.ALOAD, "retainAll keep collection");
    AbstractInsnNode beforeCall = previousMeaningful(call);
    if (!(beforeCall instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != entityListLocal) {
      return Match.incompatible("retainAll keep collection is not loaded directly from its proven local");
    }
    return Match.plan(new Plan(node.name, method, call, rawCalls.size(), plans));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    MethodNode method = plan.method();
    ensureScratchScope(plan.owner(), method, SCOPE);
    method.instructions.insertBefore(plan.retainCall(), new VarInsnNode(Opcodes.ALOAD, 0));
    makeStatic(plan.retainCall(), HOOKS, "retainAllFast", HOOK_DESC);
    requireCount("retainAllFast postcondition",
        countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "retainAllFast", HOOK_DESC), 1);
    requireCount("non-target retainAll preservation",
        calls(method, Opcodes.INVOKEINTERFACE, "java/util/Set", "retainAll", "(Ljava/util/Collection;)Z").size(),
        plan.rawRetainAll() - 1);

    applyHRenderScratchCollections(method, hRenderStuffAllocationSpecs(), plan.plans());
    int observedMutations = replaceHRenderScratchMutations(plan.owner(), method);

    PatchReport report = new PatchReport();
    report.add("linear map reconciliation", 1);
    report.add("render scratch collections", 2);
    report.add("render scratch peak observations", observedMutations);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    List<AllocationSpec> specs = hRenderStuffAllocationSpecs();
    List<MethodInsnNode> retainHooks = calls(plan.method(), Opcodes.INVOKESTATIC, HOOKS, "retainAllFast", HOOK_DESC);
    requireCount("retainAllFast postcondition", retainHooks.size(), 1);
    List<List<Integer>> hookedLocals = new ArrayList<>();
    for (AllocationSpec spec : specs) {
      hookedLocals.add(storedHookResultLocals(plan.method(), spec));
    }
    if (scratchScopeState(plan.owner(), plan.method(), SCOPE) != PatchState.PATCHED
        || hRenderScratchMutationState(plan.method()) != PatchState.PATCHED) {
      throw mismatch("H.renderStuff hooks exist without the complete scratch scope");
    }
    validatePatchedHRenderStuff(node, plan.method(), retainHooks.get(0), specs, hookedLocals);
  }

  private static PatchState hRenderScratchMutationState(MethodNode method) {
    int raw = countCalls(method, Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z");
    int hooks = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "scratchSetAdd", SET_ADD_DESC);
    boolean original = raw == 7 && hooks == 0;
    boolean patched = raw == 0 && hooks == 7;
    if (!original && !patched) { throw mismatch("H.renderStuff scratch-set mutation sites are partial or changed"); }
    return original ? PatchState.ORIGINAL : PatchState.PATCHED;
  }

  private static int replaceHRenderScratchMutations(String owner, MethodNode method) {
    AllocationSpec setSpec = hRenderStuffAllocationSpecs().get(1);
    List<Integer> locals = storedHookResultLocals(method, setSpec);
    if (locals.size() != 1) throw mismatch("H.renderStuff scratch-set local is ambiguous");
    int local = locals.get(0);
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    int changed = 0;
    for (MethodInsnNode call : new ArrayList<>(
        calls(method, Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z"))) {
      if (!sourceIsLocal(receiverSource(method, call, frames), Opcodes.ALOAD, local)) {
        throw mismatch("H.renderStuff Set.add receiver escaped the owned scratch local");
      }
      makeStatic(call, HOOKS, "scratchSetAdd", SET_ADD_DESC);
      changed++;
    }
    if (changed != 7 || hRenderScratchMutationState(method) != PatchState.PATCHED) {
      throw mismatch("H.renderStuff scratch-set peak hooks failed postcondition");
    }
    return changed;
  }

  private static List<AllocationSpec> hRenderStuffAllocationSpecs() {
    return List.of(
        new AllocationSpec("java/util/ArrayList", "(Ljava/util/Collection;)V", "borrowEntityList",
            "(Ljava/util/Collection;)Ljava/util/ArrayList;", 1),
        new AllocationSpec("java/util/HashSet", "()V", "borrowClassSet", "()Ljava/util/HashSet;", 1));
  }

  private static List<AllocationPlan> hRenderStuffAllocationPlans(String owner, MethodNode method,
      AllocationSpec spec) {
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    List<AllocationPlan> result = new ArrayList<>();
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!(insn instanceof TypeInsnNode allocation) || allocation.getOpcode() != Opcodes.NEW
          || !allocation.desc.equals(spec.type()))
        continue;
      AbstractInsnNode duplicate = nextMeaningful(allocation);
      if (duplicate == null || duplicate.getOpcode() != Opcodes.DUP) continue;

      MethodInsnNode constructor = null;
      for (AbstractInsnNode cursor = duplicate.getNext(); cursor != null; cursor = cursor.getNext()) {
        if (cursor instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
            && call.owner.equals(spec.type()) && call.name.equals("<init>")
            && call.desc.equals(spec.constructorDesc())) {
          SourceValue receiver = receiverSource(method, call, frames);
          if (sourceContains(receiver, duplicate)) constructor = call;
          break;
        }
        if (cursor.getOpcode() == Opcodes.NEW && cursor != allocation) break;
      }
      if (constructor == null) continue;
      AbstractInsnNode storeInsn = nextMeaningful(constructor);
      if (!(storeInsn instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE
          || countStores(method, store.var) != 1) {
        continue;
      }
      try {
        requireScratchUseContract(owner, method, store.var, spec, result.size(), "H.renderStuff candidate");
        result.add(new AllocationPlan(allocation, duplicate, constructor, store.var));
      } catch (StructuralMismatchException ignored) {
        // A same-type allocation with a different use contract is unrelated to this surface.
      }
    }
    return result;
  }

  private static void applyHRenderScratchCollections(MethodNode method, List<AllocationSpec> specs,
      List<List<AllocationPlan>> plans) {
    for (int i = 0; i < specs.size(); i++) {
      AllocationSpec spec = specs.get(i);
      for (AllocationPlan plan : plans.get(i)) {
        method.instructions.remove(plan.allocation());
        method.instructions.remove(plan.duplicate());
        makeStatic(plan.constructor(), HOOKS, spec.hookName(), spec.hookDesc());
      }
      requireCount("H.renderStuff postcondition " + spec.hookName(),
          countCalls(method, Opcodes.INVOKESTATIC, HOOKS, spec.hookName(), spec.hookDesc()), spec.expected());
    }
  }

  private static void validatePatchedHRenderStuff(ClassNode node, MethodNode method, MethodInsnNode retainHook,
      List<AllocationSpec> specs, List<List<Integer>> hookedLocals) {
    Frame<SourceValue>[] frames = sourceFrames(node.name, method);
    MethodInsnNode keySet = requireSourceMethod(argumentSource(method, retainHook, frames, 0), Opcodes.INVOKEVIRTUAL,
        "java/util/LinkedHashMap", "keySet", "()Ljava/util/Set;", "retainAllFast set argument");
    FieldInsnNode iconsField = requireSourceField(receiverSource(method, keySet, frames), Opcodes.GETFIELD, node.name,
        LINKED_MAP_DESC, "icons map receiver");
    requireIconsGetter(node, iconsField.name);

    int entityListLocal = requireLoadedLocal(argumentSource(method, retainHook, frames, 1), Opcodes.ALOAD,
        "retainAllFast keep collection");
    if (hookedLocals.get(0).size() != 1 || hookedLocals.get(0).get(0) != entityListLocal) {
      throw mismatch("retainAllFast does not consume the owned borrowEntityList local");
    }
    int ownerLocal =
        requireLoadedLocal(argumentSource(method, retainHook, frames, 2), Opcodes.ALOAD, "retainAllFast owner");
    if (ownerLocal != 0) { throw mismatch("retainAllFast owner is not H.this"); }
    if (nextMeaningful(retainHook) == null || nextMeaningful(retainHook).getOpcode() != Opcodes.POP) {
      throw mismatch("retainAllFast boolean result is not discarded as expected");
    }

    for (int i = 0; i < specs.size(); i++) {
      AllocationSpec spec = specs.get(i);
      for (int j = 0; j < hookedLocals.get(i).size(); j++) {
        requireScratchUseContract(node.name, method, hookedLocals.get(i).get(j), spec, j, SCOPE);
      }
    }
  }

  private static boolean isMapReconciliationSite(ClassNode node, MethodNode method, MethodInsnNode call,
      Frame<SourceValue>[] frames) {
    try {
      MethodInsnNode keySet = requireSourceMethod(receiverSource(method, call, frames), Opcodes.INVOKEVIRTUAL,
          "java/util/LinkedHashMap", "keySet", "()Ljava/util/Set;", "retainAll receiver");
      FieldInsnNode iconsField = requireSourceField(receiverSource(method, keySet, frames), Opcodes.GETFIELD, node.name,
          LINKED_MAP_DESC, "icons map receiver");
      requireIconsGetter(node, iconsField.name);
      int entityListLocal =
          requireLoadedLocal(argumentSource(method, call, frames, 0), Opcodes.ALOAD, "retainAll keep collection");
      requireLocalConstructor(method, entityListLocal, "java/util/ArrayList", "(Ljava/util/Collection;)V",
          "render entity list");
      return nextMeaningful(call) != null && nextMeaningful(call).getOpcode() == Opcodes.POP;
    } catch (StructuralMismatchException ex) {
      return false;
    }
  }
}
