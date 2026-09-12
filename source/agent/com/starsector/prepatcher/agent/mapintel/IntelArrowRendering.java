package com.starsector.prepatcher.agent.mapintel;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;


import static com.starsector.prepatcher.agent.bytecode.Allocations.*;
import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Mutations.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.patch.PatchState;
import com.starsector.prepatcher.agent.bytecode.AllocationSpec;

/**
 * Atomically installs the complete Intel-arrow rendering surface in Z.o00000: the
 * getArrowData callback cache, both temporary Vector2f scratch allocations, and the
 * single reentrant scratch scope. Partial or foreign hook-shaped states reject the
 * whole group.
 */
public final class IntelArrowRendering implements StructuredPatch<IntelArrowRendering.Plan> {
  private static final String HOOK_DESC = "(" + INTEL_DESC + MAP_API_DESC + ")Ljava/util/List;";
  private static final List<AllocationSpec> SPECS = List.of(new AllocationSpec("org/lwjgl/util/vector/Vector2f",
      "(FF)V", "borrowArrowVector", "(FF)Lorg/lwjgl/util/vector/Vector2f;", 2));

  /** The proven method and the original getArrowData callback site. */
  record Plan(String owner, MethodNode method, MethodInsnNode callback) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode method = requireMethod(node, "o00000", "(FF)V");
    String originalDesc = "(" + MAP_API_DESC + ")Ljava/util/List;";

    PatchState scopeState = scratchScopeState(node.name, method, "Z.o00000");
    PatchState allocationState = scratchAllocationGroupState(node.name, method, SPECS, "Z arrow vectors");
    List<MethodInsnNode> originalCallbacks = calls(method, Opcodes.INVOKEINTERFACE,
        "com/fs/starfarer/api/campaign/comm/IntelInfoPlugin", "getArrowData", originalDesc);
    List<MethodInsnNode> callbackHooks = calls(method, Opcodes.INVOKESTATIC, HOOKS, "getArrowDataCached", HOOK_DESC);

    boolean originalState = originalCallbacks.size() == 1 && callbackHooks.isEmpty()
        && allocationState == PatchState.ORIGINAL && scopeState == PatchState.ORIGINAL;
    boolean patchedState = originalCallbacks.isEmpty() && callbackHooks.size() == 1
        && allocationState == PatchState.PATCHED && scopeState == PatchState.PATCHED;

    if (patchedState) {
      validateArrowDataCallback(callbackHooks.get(0));
      return Match.alreadyApplied("Z Intel-arrow callback, vectors, and scratch scope are already present");
    }
    if (!originalState) {
      return Match.incompatible(
          "Z.o00000 Intel-arrow rendering has mixed, missing, ambiguous, or " + "foreign callback/vector/scope state");
    }

    MethodInsnNode callback = originalCallbacks.get(0);
    validateArrowDataCallback(callback);
    return Match.plan(new Plan(node.name, method, callback));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    installScratchScope(plan.owner(), plan.method(), "Z.o00000");
    makeStatic(plan.callback(), HOOKS, "getArrowDataCached", HOOK_DESC);
    int vectors = replaceAllocationGroup(plan.owner(), plan.method(), SPECS, true, "Z arrow vectors");

    PatchReport report = new PatchReport();
    report.add("Intel arrow-data callback cache", 1);
    report.add("Intel arrow vector pool", vectors);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireCount("Z arrow-data callback postcondition",
        countCalls(plan.method(), Opcodes.INVOKESTATIC, HOOKS, "getArrowDataCached", HOOK_DESC), 1);
    requireCount("Z arrow-vector postcondition", countCalls(plan.method(), Opcodes.INVOKESTATIC, HOOKS,
        "borrowArrowVector", "(FF)Lorg/lwjgl/util/vector/Vector2f;"), 2);
    if (scratchScopeState(plan.owner(), plan.method(), "Z.o00000") != PatchState.PATCHED) {
      throw mismatch("Z.o00000 scratch-scope installation postcondition failed");
    }
  }

  private static void validateArrowDataCallback(MethodInsnNode call) {
    if (nextMeaningful(call) != null && nextMeaningful(call).getOpcode() == Opcodes.POP) {
      throw mismatch("getArrowData result is discarded");
    }
  }
}
