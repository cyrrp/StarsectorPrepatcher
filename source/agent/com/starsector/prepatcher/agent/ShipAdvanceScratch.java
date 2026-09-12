package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;
import java.util.ArrayList;
import java.util.List;

import static com.starsector.prepatcher.agent.Allocations.*;
import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Flow.*;
import static com.starsector.prepatcher.agent.Mutations.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.Queries.*;

final class ShipAdvanceScratch implements StructuredPatch<ShipAdvanceScratch.Plan> {
  record Plan(String owner, MethodNode advance) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode advance = requireMethod(node, "advance", "(F)V");
    PatchState state =
        uniformPatchState("Ship.advance scratch reuse", scratchScopeState(node.name, advance, "Ship.advance"),
            storedSimpleAllocationState(advance, "java/util/ArrayList", "()V", "borrowShipList",
                "()Ljava/util/ArrayList;", 3, "Ship.advance command/group lists"),
            storedSimpleAllocationState(advance, "java/util/HashSet", "()V", "borrowShipSet", "()Ljava/util/HashSet;",
                2, "Ship.advance command/weapon sets"),
            shipCommandSnapshotState(node.name, advance), shipScratchMutationState(advance));
    if (state == PatchState.PATCHED) {
      return Match.alreadyApplied("all Ship.advance scratch hooks and scopes satisfy their postconditions");
    }
    return Match.plan(new Plan(node.name, advance));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    installScratchScope(plan.owner(), plan.advance(), "Ship.advance");
    int changed = 0;
    changed += replaceStoredSimpleAllocations(plan.owner(), plan.advance(), "java/util/ArrayList", "()V",
        "borrowShipList", "()Ljava/util/ArrayList;", 3, true, "Ship.advance command/group lists");
    changed += replaceStoredSimpleAllocations(plan.owner(), plan.advance(), "java/util/HashSet", "()V", "borrowShipSet",
        "()Ljava/util/HashSet;", 2, false, "Ship.advance command/weapon sets");
    changed += replaceShipCommandSnapshot(plan.owner(), plan.advance());
    changed += replaceShipScratchMutations(plan.owner(), plan.advance());

    PatchReport report = new PatchReport();
    report.add("reusable Ship.advance command/group collections", changed);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    uniformPatchState("Ship.advance scratch reuse", scratchScopeState(plan.owner(), plan.advance(), "Ship.advance"),
        storedSimpleAllocationState(plan.advance(), "java/util/ArrayList", "()V", "borrowShipList",
            "()Ljava/util/ArrayList;", 3, "Ship.advance command/group lists"),
        storedSimpleAllocationState(plan.advance(), "java/util/HashSet", "()V", "borrowShipSet",
            "()Ljava/util/HashSet;", 2, "Ship.advance command/weapon sets"),
        shipCommandSnapshotState(plan.owner(), plan.advance()), shipScratchMutationState(plan.advance()));
  }

  private static PatchState shipScratchMutationState(MethodNode method) {
    int rawList = countCalls(method, Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z");
    int rawSet = countCalls(method, Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z");
    int rawSetAll = countCalls(method, Opcodes.INVOKEINTERFACE, "java/util/Set", "addAll", "(Ljava/util/Collection;)Z");
    int listHooks =
        countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "scratchListAdd", "(Ljava/util/List;Ljava/lang/Object;)Z");
    int setHooks =
        countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "scratchSetAdd", "(Ljava/util/Set;Ljava/lang/Object;)Z");
    int setAllHooks =
        countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "scratchSetAddAll", "(Ljava/util/Set;Ljava/util/Collection;)Z");
    boolean original =
        rawList == 4 && rawSet == 1 && rawSetAll == 1 && listHooks == 0 && setHooks == 0 && setAllHooks == 0;
    boolean patched =
        rawList == 1 && rawSet == 0 && rawSetAll == 0 && listHooks == 3 && setHooks == 1 && setAllHooks == 1;
    if (!original && !patched) { throw mismatch("Ship.advance scratch mutation hooks are partial or changed"); }
    return original ? PatchState.ORIGINAL : PatchState.PATCHED;
  }

  private static int replaceShipScratchMutations(String owner, MethodNode method) {
    AllocationSpec listSpec =
        new AllocationSpec("java/util/ArrayList", "()V", "borrowShipList", "()Ljava/util/ArrayList;", 3);
    AllocationSpec setSpec =
        new AllocationSpec("java/util/HashSet", "()V", "borrowShipSet", "()Ljava/util/HashSet;", 2);
    List<VarInsnNode> listStores = storedHookResultStoresRelaxed(method, listSpec);
    List<VarInsnNode> setStores = storedHookResultStoresRelaxed(method, setSpec);
    if (listStores.size() != 3 || setStores.size() != 2) {
      throw mismatch("Ship.advance scratch mutation locals are ambiguous");
    }
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    int changed = 0;
    for (MethodInsnNode call : new ArrayList<>(
        calls(method, Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z"))) {
      SourceValue receiver = receiverSource(method, call, frames);
      if (!sourceIsActiveHookLocal(method, call, receiver, listStores)) continue;
      makeStatic(call, HOOKS, "scratchListAdd", "(Ljava/util/List;Ljava/lang/Object;)Z");
      changed++;
    }
    for (MethodInsnNode call : new ArrayList<>(
        calls(method, Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z"))) {
      SourceValue receiver = receiverSource(method, call, frames);
      if (!sourceIsActiveHookLocal(method, call, receiver, setStores)) continue;
      makeStatic(call, HOOKS, "scratchSetAdd", "(Ljava/util/Set;Ljava/lang/Object;)Z");
      changed++;
    }
    for (MethodInsnNode call : new ArrayList<>(
        calls(method, Opcodes.INVOKEINTERFACE, "java/util/Set", "addAll", "(Ljava/util/Collection;)Z"))) {
      SourceValue receiver = receiverSource(method, call, frames);
      if (!sourceIsActiveHookLocal(method, call, receiver, setStores)) continue;
      makeStatic(call, HOOKS, "scratchSetAddAll", "(Ljava/util/Set;Ljava/util/Collection;)Z");
      changed++;
    }
    if (changed != 5 || shipScratchMutationState(method) != PatchState.PATCHED) {
      throw mismatch("Ship.advance scratch mutation hook postcondition failed");
    }
    return changed;
  }

  private static int replaceShipCommandSnapshot(String owner, MethodNode method) {
    String hookDesc = "(Ljava/util/Collection;)Ljava/util/ArrayList;";
    List<MethodInsnNode> raw =
        calls(method, Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "(Ljava/util/Collection;)V");
    List<MethodInsnNode> hooks = calls(method, Opcodes.INVOKESTATIC, HOOKS, "borrowShipListSnapshot", hookDesc);
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    List<MethodInsnNode> directFieldSnapshots = new ArrayList<>();
    List<MethodInsnNode> listenerSnapshots = new ArrayList<>();
    for (MethodInsnNode constructor : raw) {
      SourceValue argument = argumentSource(method, constructor, frames, 0);
      FieldInsnNode field = optionalSourceField(argument, Opcodes.GETFIELD, owner, LIST_DESC);
      if (field != null) {
        MethodInsnNode iterator = asMethod(nextMeaningful(constructor), "Ship.advance command snapshot iterator");
        requireCall(iterator, Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "iterator", "()Ljava/util/Iterator;",
            "Ship.advance command snapshot iterator");
        directFieldSnapshots.add(constructor);
        continue;
      }
      if (sourceIsMethod(argument, Opcodes.INVOKEINTERFACE,
          "com/fs/starfarer/api/combat/listeners/CombatListenerManagerAPI", "getListeners",
          "(Ljava/lang/Class;)Ljava/util/List;")) {
        listenerSnapshots.add(constructor);
      }
    }
    boolean original =
        directFieldSnapshots.size() == 1 && listenerSnapshots.size() == 1 && raw.size() == 2 && hooks.isEmpty();
    boolean patched =
        directFieldSnapshots.isEmpty() && listenerSnapshots.size() == 1 && raw.size() == 1 && hooks.size() == 1;
    if (patched) throw already("Ship.advance command snapshot hook is already present");
    if (!original) { throw mismatch("Ship.advance listener/command snapshot ownership changed structurally"); }
    MethodInsnNode constructor = directFieldSnapshots.get(0);
    AllocationPair pair =
        allocationPair(method, constructor, frames, "java/util/ArrayList", "Ship.advance command snapshot");
    method.instructions.remove(pair.allocation());
    method.instructions.remove(pair.duplicate());
    makeStatic(constructor, HOOKS, "borrowShipListSnapshot", hookDesc);
    return 1;
  }

  private static PatchState shipCommandSnapshotState(String owner, MethodNode method) {
    String label = "Ship.advance command snapshot";
    String hookDesc = "(Ljava/util/Collection;)Ljava/util/ArrayList;";
    List<MethodInsnNode> raw =
        calls(method, Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "(Ljava/util/Collection;)V");
    List<MethodInsnNode> hooks = calls(method, Opcodes.INVOKESTATIC, HOOKS, "borrowShipListSnapshot", hookDesc);
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    List<MethodInsnNode> directFieldSnapshots = new ArrayList<>();
    List<MethodInsnNode> listenerSnapshots = new ArrayList<>();
    for (MethodInsnNode constructor : raw) {
      SourceValue argument = argumentSource(method, constructor, frames, 0);
      FieldInsnNode field = optionalSourceField(argument, Opcodes.GETFIELD, owner, LIST_DESC);
      if (field != null) {
        requireThisFieldReceiver(field, label);
        MethodInsnNode iterator = asMethod(nextMeaningful(constructor), label + " iterator");
        requireCall(iterator, Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "iterator", "()Ljava/util/Iterator;",
            label + " iterator");
        directFieldSnapshots.add(constructor);
      } else if (sourceIsMethod(argument, Opcodes.INVOKEINTERFACE,
          "com/fs/starfarer/api/combat/listeners/CombatListenerManagerAPI", "getListeners",
          "(Ljava/lang/Class;)Ljava/util/List;")) {
            listenerSnapshots.add(constructor);
          }
    }
    for (MethodInsnNode hook : hooks) {
      FieldInsnNode field =
          optionalSourceField(argumentSource(method, hook, frames, 0), Opcodes.GETFIELD, owner, LIST_DESC);
      if (field == null) throw mismatch(label + " hook input is not the owned command field");
      requireThisFieldReceiver(field, label + " hook");
      MethodInsnNode iterator = asMethod(nextMeaningful(hook), label + " patched iterator");
      requireCall(iterator, Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "iterator", "()Ljava/util/Iterator;",
          label + " patched iterator");
    }

    boolean original =
        directFieldSnapshots.size() == 1 && listenerSnapshots.size() == 1 && raw.size() == 2 && hooks.isEmpty();
    boolean patched =
        directFieldSnapshots.isEmpty() && listenerSnapshots.size() == 1 && raw.size() == 1 && hooks.size() == 1;
    if (!original && !patched) {
      throw mismatch("Ship.advance listener/command snapshot ownership changed structurally");
    }
    return original ? PatchState.ORIGINAL : PatchState.PATCHED;
  }
}
