package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Flow.*;
import static com.starsector.prepatcher.agent.Mutations.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.Queries.*;

final class Allocations {
  static int replaceStoredSimpleAllocations(String owner, MethodNode method, String type, String constructorDesc,
      String hookName, String hookDesc, int expected, boolean scopeInstalled, String label) {
    List<MethodInsnNode> constructors = calls(method, Opcodes.INVOKESPECIAL, type, "<init>", constructorDesc);
    List<MethodInsnNode> hookCalls = calls(method, Opcodes.INVOKESTATIC, HOOKS, hookName, hookDesc);
    boolean original = constructors.size() == expected && hookCalls.isEmpty();
    boolean patched = constructors.isEmpty() && hookCalls.size() == expected;
    if (patched) {
      if (scopeInstalled) throw mismatch(label + " hooks exist without their scratch scope");
      for (MethodInsnNode hook : hookCalls) {
        AbstractInsnNode stored = nextMeaningful(hook);
        if (!(stored instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) {
          throw mismatch(label + " patched result is not stored directly in a local");
        }
      }
      throw already(label + " hooks are already present");
    }
    if (!original) throw mismatch(label + " has mixed, missing, or ambiguous allocations");
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    for (MethodInsnNode constructor : constructors) {
      AbstractInsnNode stored = nextMeaningful(constructor);
      if (!(stored instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) {
        throw mismatch(label + " allocation result is not stored directly in a local");
      }
      AllocationPair pair = constructorDesc.equals("()V") ? immediateNoArgAllocationPair(constructor, type, label)
          : allocationPair(method, constructor, frames, type, label);
      method.instructions.remove(pair.allocation());
      method.instructions.remove(pair.duplicate());
      makeStatic(constructor, HOOKS, hookName, hookDesc);
    }
    return expected;
  }

  static PatchState storedSimpleAllocationState(MethodNode method, String type, String constructorDesc, String hookName,
      String hookDesc, int expected, String label) {
    List<MethodInsnNode> constructors = calls(method, Opcodes.INVOKESPECIAL, type, "<init>", constructorDesc);
    List<MethodInsnNode> hooks = calls(method, Opcodes.INVOKESTATIC, HOOKS, hookName, hookDesc);
    boolean original = constructors.size() == expected && hooks.isEmpty();
    boolean patched = constructors.isEmpty() && hooks.size() == expected;
    if (!original && !patched) { throw mismatch(label + " has mixed, missing, or ambiguous allocations"); }

    for (MethodInsnNode result : original ? constructors : hooks) {
      AbstractInsnNode stored = nextMeaningful(result);
      if (!(stored instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) {
        throw mismatch(label + " result is not stored directly in a local");
      }
      if (original && constructorDesc.equals("()V")) {
        immediateNoArgAllocationPair(result, type, label);
      }
    }
    return original ? PatchState.ORIGINAL : PatchState.PATCHED;
  }

  private static AllocationPair immediateNoArgAllocationPair(MethodInsnNode constructor, String type, String label) {
    AbstractInsnNode duplicate = previousMeaningful(constructor);
    AbstractInsnNode allocation = previousMeaningful(duplicate);
    if (duplicate == null || duplicate.getOpcode() != Opcodes.DUP || !(allocation instanceof TypeInsnNode typed)
        || typed.getOpcode() != Opcodes.NEW || !typed.desc.equals(type)) {
      throw mismatch(label + " no-arg allocation is not NEW/DUP/<init>");
    }
    return new AllocationPair(typed, duplicate);
  }

  static AllocationPair allocationPair(MethodNode method, MethodInsnNode constructor, Frame<SourceValue>[] frames,
      String type, String label) {
    SourceValue receiver = receiverSource(method, constructor, frames);
    TypeInsnNode allocation = null;
    AbstractInsnNode duplicate = null;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!(insn instanceof TypeInsnNode candidate) || candidate.getOpcode() != Opcodes.NEW
          || !candidate.desc.equals(type))
        continue;
      AbstractInsnNode dup = nextMeaningful(candidate);
      if (dup == null || dup.getOpcode() != Opcodes.DUP
          || (!sourceContains(receiver, dup) && !sourceContains(receiver, candidate)))
        continue;
      if (allocation != null) throw mismatch(label + " has ambiguous allocation ownership");
      allocation = candidate;
      duplicate = dup;
    }
    if (allocation == null) throw mismatch(label + " allocation owner was not found");
    return new AllocationPair(allocation, duplicate);
  }

  static boolean ensureScratchScope(String owner, MethodNode method, String label) {
    if (scratchScopeState(owner, method, label) == PatchState.ORIGINAL) {
      installScratchScope(owner, method, label);
      return true;
    }
    return false;
  }

  static PatchState scratchScopeState(String owner, MethodNode method, String label) {
    String beginDesc = "()V";
    int begins = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "beginScratchScope", beginDesc);
    int ends = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "endScratchScope", beginDesc);
    if (begins == 0 && ends == 0) { return PatchState.ORIGINAL; }
    requireCount(label + " scratch-scope begin hooks", begins, 1);
    int returns = countReturnOpcodes(method);
    requireCount(label + " scratch-scope end hooks", ends, returns + 1);

    MethodInsnNode begin =
        only(calls(method, Opcodes.INVOKESTATIC, HOOKS, "beginScratchScope", beginDesc), label + " scratch begin");
    AbstractInsnNode first = method.instructions.getFirst();
    while (first != null && first.getOpcode() < 0) first = first.getNext();
    if (first != begin) throw mismatch(label + " scratch scope does not begin at method entry");

    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!isReturnOpcode(insn.getOpcode())) continue;
      AbstractInsnNode previous = previousMeaningful(insn);
      if (!(previous instanceof MethodInsnNode call)
          || !callMatches(call, Opcodes.INVOKESTATIC, HOOKS, "endScratchScope", beginDesc)) {
        throw mismatch(label + " return is not protected by endScratchScope");
      }
    }

    int handlers = 0;
    for (TryCatchBlockNode block : method.tryCatchBlocks) {
      if (block.type != null) continue;
      AbstractInsnNode handlerStart = nextMeaningful(block.handler);
      if (!(handlerStart instanceof MethodInsnNode call)
          || !callMatches(call, Opcodes.INVOKESTATIC, HOOKS, "endScratchScope", beginDesc))
        continue;
      AbstractInsnNode rethrow = nextMeaningful(call);
      if (rethrow != null && rethrow.getOpcode() == Opcodes.ATHROW) handlers++;
    }
    requireCount(label + " scratch-scope catch-all", handlers, 1);
    return PatchState.PATCHED;
  }

  /**
   * The pre-mutation guards {@link #installScratchScope} would apply, without the mutation.
   * A patch whose original flow installed the scope before checking its call sites runs this
   * inside match so the refusal order is unchanged.
   */
  static void requireScratchScopeInstallable(MethodNode method, String label) {
    if (method.instructions == null || method.instructions.getFirst() == null) {
      throw mismatch(label + " has no code for a scratch scope");
    }
    if (method.name.equals("<init>")) throw mismatch(label + " constructor scopes are unsupported");
    if (countReturnOpcodes(method) == 0) throw mismatch(label + " has no normal return");
  }

  static void installScratchScope(String owner, MethodNode method, String label) {
    if (method.instructions == null || method.instructions.getFirst() == null) {
      throw mismatch(label + " has no code for a scratch scope");
    }
    String desc = "()V";
    if (method.name.equals("<init>")) throw mismatch(label + " constructor scopes are unsupported");
    AbstractInsnNode oldFirst = method.instructions.getFirst();
    LabelNode entry = new LabelNode();
    LabelNode start = new LabelNode();
    method.instructions.insertBefore(oldFirst, entry);
    MethodInsnNode begin = new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "beginScratchScope", desc, false);
    method.instructions.insert(entry, begin);
    method.instructions.insert(begin, start);

    int returns = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!isReturnOpcode(insn.getOpcode())) continue;
      method.instructions.insertBefore(insn,
          new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "endScratchScope", desc, false));
      returns++;
    }
    if (returns == 0) throw mismatch(label + " has no normal return");

    LabelNode end = new LabelNode();
    LabelNode handler = new LabelNode();
    method.instructions.add(end);
    method.instructions.add(handler);
    Object[] initialLocals = initialFrameLocals(owner, method);
    method.instructions.add(
        new FrameNode(Opcodes.F_FULL, initialLocals.length, initialLocals, 1, new Object[]{"java/lang/Throwable"}));
    method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "endScratchScope", desc, false));
    method.instructions.add(new InsnNode(Opcodes.ATHROW));
    method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
    ensureScratchScope(owner, method, label);
  }

  /**
   * Replaces defensive-copy allocations whose compiler locals are reused
   * later in the same large method. The ordinary scratch matcher intentionally
   * requires a single assignment per local; this variant proves ownership from
   * the SourceInterpreter value of each individual ASTORE instead.
   */
  static int replaceSnapshotAllocationGroup(String owner, MethodNode method, AllocationSpec spec,
      boolean scopeInstalled, String label) {
    List<SnapshotAllocationPlan> original = snapshotAllocationPlans(owner, method, spec);
    List<SnapshotHookPlan> patched = snapshotHookPlans(method, spec);
    boolean originalState = original.size() == spec.expected() && patched.isEmpty();
    boolean patchedState = original.isEmpty() && patched.size() == spec.expected();
    if (patchedState) {
      if (scopeInstalled) { throw mismatch(label + " hooks exist without their required scratch scope"); }
      for (int i = 0; i < patched.size(); i++) {
        requireSnapshotUseContract(owner, method, patched.get(i).store(), expectedSnapshotIterators(spec.hookName(), i),
            label);
      }
      throw already(label + " hooks and assignment-scoped non-escape contracts are already present");
    }
    if (!originalState) { throw mismatch(label + " has mixed, missing, or ambiguous allocation/hook sites"); }

    for (int i = 0; i < original.size(); i++) {
      SnapshotAllocationPlan plan = original.get(i);
      requireSnapshotUseContract(owner, method, plan.store(), expectedSnapshotIterators(spec.hookName(), i), label);
      method.instructions.remove(plan.allocation());
      method.instructions.remove(plan.duplicate());
      makeStatic(plan.constructor(), HOOKS, spec.hookName(), spec.hookDesc());
    }
    requireCount(label + " postcondition " + spec.hookName(),
        countCalls(method, Opcodes.INVOKESTATIC, HOOKS, spec.hookName(), spec.hookDesc()), spec.expected());
    return original.size();
  }

  static List<SnapshotAllocationPlan> snapshotAllocationPlans(String owner, MethodNode method, AllocationSpec spec) {
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    List<SnapshotAllocationPlan> result = new ArrayList<>();
    int rawConstructors = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
          && call.owner.equals(spec.type()) && call.name.equals("<init>") && call.desc.equals(spec.constructorDesc()))
        rawConstructors++;
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
      AbstractInsnNode stored = nextMeaningful(constructor);
      if (!(stored instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) {
        throw mismatch(spec.hookName() + " snapshot result is not stored in a local");
      }
      result.add(new SnapshotAllocationPlan(allocation, duplicate, constructor, store));
    }
    if (rawConstructors != result.size()) {
      throw mismatch(spec.hookName() + " has constructors not paired by uninitialized-value data-flow");
    }
    return result;
  }

  private static List<SnapshotHookPlan> snapshotHookPlans(MethodNode method, AllocationSpec spec) {
    List<SnapshotHookPlan> result = new ArrayList<>();
    for (MethodInsnNode hook : calls(method, Opcodes.INVOKESTATIC, HOOKS, spec.hookName(), spec.hookDesc())) {
      AbstractInsnNode stored = nextMeaningful(hook);
      if (!(stored instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) {
        throw mismatch(spec.hookName() + " result is not stored directly in a local");
      }
      result.add(new SnapshotHookPlan(hook, store));
    }
    return result;
  }

  private static int expectedSnapshotIterators(String hookName, int ordinal) {
    if (hookName.equals("borrowPausedLocationSnapshot") && ordinal == 0) return 2;
    if (hookName.equals("borrowLocationAdvanceSnapshot") || hookName.equals("borrowPausedLocationSnapshot")
        || hookName.equals("borrowEntityScriptSnapshot"))
      return 1;
    throw mismatch("no snapshot iterator contract for " + hookName);
  }

  static void requireSnapshotUseContract(String owner, MethodNode method, VarInsnNode assignment, int expectedIterators,
      String label) {
    int local = assignment.var;
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    Set<AbstractInsnNode> accountedLoads = new HashSet<>();
    List<MethodInsnNode> iterators = new ArrayList<>();
    Map<String, Integer> observed = new LinkedHashMap<>();

    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!(insn instanceof MethodInsnNode call)) continue;
      if (call.getOpcode() != Opcodes.INVOKESTATIC && recordSnapshotUse(method, receiverSource(method, call, frames),
          local, assignment, frames, accountedLoads)) {
        String key = call.getOpcode() + "|" + call.owner + "|" + call.name + "|" + call.desc + "|receiver";
        observed.merge(key, 1, Integer::sum);
        if (call.getOpcode() == Opcodes.INVOKEINTERFACE && call.owner.equals("java/util/List")
            && call.name.equals("iterator") && call.desc.equals("()Ljava/util/Iterator;")) {
          iterators.add(call);
        }
      }
      Type[] args = Type.getArgumentTypes(call.desc);
      for (int i = 0; i < args.length; i++) {
        if (recordSnapshotUse(method, argumentSource(method, call, frames, i), local, assignment, frames,
            accountedLoads)) {
          String key = call.getOpcode() + "|" + call.owner + "|" + call.name + "|" + call.desc + "|arg" + i;
          observed.merge(key, 1, Integer::sum);
        }
      }
    }

    Map<String, Integer> expected = new LinkedHashMap<>();
    expectUse(expected, Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;", -1,
        expectedIterators);
    if (!observed.equals(expected)) {
      throw mismatch(label + " assignment to local " + local + " use contract changed: expected " + expected
          + ", found " + observed);
    }

    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!(insn instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != local) continue;
      SourceValue origin = frameBefore(method, load, frames).getLocal(local);
      if (!sourceContains(origin, assignment)) continue;
      if (origin.insns.size() != 1) {
        throw mismatch(label + " local " + local + " merges its snapshot assignment with another value");
      }
      if (!accountedLoads.contains(load)) {
        throw mismatch(label + " local " + local + " has an unclassified alias/escape at instruction "
            + method.instructions.indexOf(load));
      }
    }
    for (MethodInsnNode iterator : iterators) {
      requireIteratorLifetime(owner, method, iterator, label + " assignment to local " + local);
    }
  }

  private static boolean recordSnapshotUse(MethodNode method, SourceValue source, int local, VarInsnNode assignment,
      Frame<SourceValue>[] frames, Set<AbstractInsnNode> accountedLoads) {
    if (source == null || source.insns == null || source.insns.isEmpty()) return false;
    boolean fromAssignment = false;
    for (AbstractInsnNode producer : source.insns) {
      if (!(producer instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != local) continue;
      SourceValue origin = frameBefore(method, load, frames).getLocal(local);
      if (!sourceContains(origin, assignment)) continue;
      if (origin.insns.size() != 1) {
        throw mismatch("snapshot local " + local + " merges its proven assignment with another value");
      }
      accountedLoads.add(load);
      fromAssignment = true;
    }
    return fromAssignment;
  }

  static PatchState scratchAllocationGroupState(String owner, MethodNode method, List<AllocationSpec> specs,
      String label) {
    boolean allOriginal = true;
    boolean allPatched = true;
    List<List<AllocationPlan>> plans = new ArrayList<>();
    List<List<Integer>> hookedLocals = new ArrayList<>();
    for (AllocationSpec spec : specs) {
      List<AllocationPlan> found = allocationPlans(owner, method, spec);
      List<Integer> locals = storedHookResultLocals(method, spec);
      allOriginal &= found.size() == spec.expected() && locals.isEmpty();
      allPatched &= found.isEmpty() && locals.size() == spec.expected();
      plans.add(found);
      hookedLocals.add(locals);
    }
    if (!allOriginal && !allPatched) {
      throw mismatch(label + " has mixed, missing, or ambiguous allocation/hook sites");
    }
    for (int i = 0; i < specs.size(); i++) {
      AllocationSpec spec = specs.get(i);
      if (allOriginal) {
        for (int j = 0; j < plans.get(i).size(); j++) {
          requireScratchUseContract(owner, method, plans.get(i).get(j).local(), spec, j, label);
        }
      } else {
        for (int j = 0; j < hookedLocals.get(i).size(); j++) {
          requireScratchUseContract(owner, method, hookedLocals.get(i).get(j), spec, j, label);
        }
      }
    }
    return allOriginal ? PatchState.ORIGINAL : PatchState.PATCHED;
  }

  static int replaceAllocationGroup(String owner, MethodNode method, List<AllocationSpec> specs, boolean scopeInstalled,
      String label) {
    boolean allOriginal = true;
    boolean allPatched = true;
    List<List<AllocationPlan>> plans = new ArrayList<>();
    List<List<Integer>> hookedLocals = new ArrayList<>();
    for (AllocationSpec spec : specs) {
      List<AllocationPlan> found = allocationPlans(owner, method, spec);
      List<Integer> locals = storedHookResultLocals(method, spec);
      boolean originalState = found.size() == spec.expected() && locals.isEmpty();
      boolean patchedState = found.isEmpty() && locals.size() == spec.expected();
      allOriginal &= originalState;
      allPatched &= patchedState;
      plans.add(found);
      hookedLocals.add(locals);
    }
    if (allPatched) {
      if (scopeInstalled) { throw mismatch(label + " hooks exist without their required scratch scope"); }
      for (int i = 0; i < specs.size(); i++) {
        for (int j = 0; j < hookedLocals.get(i).size(); j++) {
          requireScratchUseContract(owner, method, hookedLocals.get(i).get(j), specs.get(i), j, label);
        }
      }
      throw already(label + " hooks and non-escape contracts are already present");
    }
    if (!allOriginal) { throw mismatch(label + " has mixed, missing, or ambiguous allocation/hook sites"); }

    int changed = 0;
    for (int i = 0; i < specs.size(); i++) {
      AllocationSpec spec = specs.get(i);
      for (int j = 0; j < plans.get(i).size(); j++) {
        AllocationPlan plan = plans.get(i).get(j);
        requireScratchUseContract(owner, method, plan.local(), spec, j, label);
        method.instructions.remove(plan.allocation());
        method.instructions.remove(plan.duplicate());
        makeStatic(plan.constructor(), HOOKS, spec.hookName(), spec.hookDesc());
        changed++;
      }
    }
    for (AllocationSpec spec : specs) {
      requireCount(label + " postcondition " + spec.hookName(),
          countCalls(method, Opcodes.INVOKESTATIC, HOOKS, spec.hookName(), spec.hookDesc()), spec.expected());
    }
    return changed;
  }

  private static List<AllocationPlan> allocationPlans(String owner, MethodNode method, AllocationSpec spec) {
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    List<AllocationPlan> result = new ArrayList<>();
    int rawConstructors = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
          && call.owner.equals(spec.type()) && call.name.equals("<init>") && call.desc.equals(spec.constructorDesc()))
        rawConstructors++;
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
      if (!(storeInsn instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) {
        throw mismatch(spec.hookName() + " allocation result is not stored in a local");
      }
      if (countStores(method, store.var) != 1) {
        throw mismatch(spec.hookName() + " scratch local " + store.var + " is assigned more than once");
      }
      result.add(new AllocationPlan(allocation, duplicate, constructor, store.var));
    }
    if (rawConstructors != result.size()) {
      throw mismatch(spec.hookName() + " has constructor calls not paired by uninitialized-value data-flow");
    }
    return result;
  }

  static List<Integer> storedHookResultLocals(MethodNode method, AllocationSpec spec) {
    List<Integer> result = new ArrayList<>();
    for (MethodInsnNode hook : calls(method, Opcodes.INVOKESTATIC, HOOKS, spec.hookName(), spec.hookDesc())) {
      AbstractInsnNode next = nextMeaningful(hook);
      if (!(next instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) {
        throw mismatch(spec.hookName() + " result is not stored directly in one local");
      }
      if (countStores(method, store.var) != 1) {
        throw mismatch(spec.hookName() + " result local " + store.var + " is reassigned");
      }
      result.add(store.var);
    }
    return result;
  }

  /**
   * Returns the immediate stores for scratch-borrow hooks without requiring the JVM local
   * slot to remain single-assignment for the entire method. Large vanilla methods such as
   * Ship.advance reuse local slots after the scratch collection's lifetime. Mutation hooks
   * therefore use {@link #sourceIsActiveHookLocal} to prove that a call is still inside the
   * borrow-to-reassignment lease instead of rejecting safe slot reuse.
   */
  static List<VarInsnNode> storedHookResultStoresRelaxed(MethodNode method, AllocationSpec spec) {
    List<MethodInsnNode> hooks = calls(method, Opcodes.INVOKESTATIC, HOOKS, spec.hookName(), spec.hookDesc());
    if (hooks.size() != spec.expected()) {
      throw mismatch(spec.hookName() + " expected " + spec.expected() + " stored results but found " + hooks.size());
    }
    List<VarInsnNode> result = new ArrayList<>();
    Set<Integer> locals = new HashSet<>();
    for (MethodInsnNode hook : hooks) {
      AbstractInsnNode next = nextMeaningful(hook);
      if (!(next instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) {
        throw mismatch(spec.hookName() + " result is not stored directly in one local");
      }
      if (!locals.add(store.var)) {
        throw mismatch(spec.hookName() + " stores multiple borrowed values in local " + store.var);
      }
      result.add(store);
    }
    return result;
  }

  static void requireScratchUseContract(String owner, MethodNode method, int local, AllocationSpec spec, int ordinal,
      String label) {
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    Map<String, Integer> observed = new LinkedHashMap<>();
    Set<AbstractInsnNode> accountedLoads = new HashSet<>();
    List<MethodInsnNode> iterators = new ArrayList<>();

    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!(insn instanceof MethodInsnNode call)) continue;
      if (call.getOpcode() != Opcodes.INVOKESTATIC) {
        SourceValue receiver = receiverSource(method, call, frames);
        if (recordScratchUse(receiver, local, scratchUseKey(spec, call, -1), observed, accountedLoads)
            && call.owner.equals("java/util/List") && call.name.equals("iterator")
            && call.desc.equals("()Ljava/util/Iterator;")) {
          iterators.add(call);
        }
      }
      Type[] args = Type.getArgumentTypes(call.desc);
      for (int i = 0; i < args.length; i++) {
        recordScratchUse(argumentSource(method, call, frames, i), local, scratchUseKey(spec, call, i), observed,
            accountedLoads);
      }
    }

    Set<AbstractInsnNode> rmwLoads = validateArrowVectorRmw(method, local, spec, ordinal);
    accountedLoads.addAll(rmwLoads);
    Map<String, Integer> expected = expectedScratchUses(owner, spec, ordinal);
    if (!scratchUsesMatch(expected, observed)) {
      throw mismatch(label + " local " + local + " use contract changed: expected " + expected + ", found " + observed);
    }

    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == local
          && !accountedLoads.contains(load)) {
        throw mismatch(label + " local " + local + " has an unclassified alias/escape at instruction "
            + method.instructions.indexOf(load));
      }
    }
    for (MethodInsnNode iterator : iterators) {
      requireIteratorLifetime(owner, method, iterator, label + " local " + local);
    }
  }

  static boolean recordScratchUse(SourceValue source, int local, String key, Map<String, Integer> uses,
      Set<AbstractInsnNode> accountedLoads) {
    Integer loaded = optionalLoadedLocal(source, Opcodes.ALOAD);
    if (loaded == null || loaded != local) return false;
    uses.merge(key, 1, Integer::sum);
    for (AbstractInsnNode producer : source.insns) {
      if (producer instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == local) {
        accountedLoads.add(load);
      }
    }
    return true;
  }

  private static String scratchUseKey(AllocationSpec spec, MethodInsnNode call, int position) {
    if (spec.hookName().equals("borrowEntityList")) {
      boolean collectionOperation = call.getOpcode() == Opcodes.INVOKEINTERFACE && call.owner.equals("java/util/Set")
          && (call.name.equals("retainAll") || call.name.equals("removeAll"))
          && call.desc.equals("(Ljava/util/Collection;)Z") && position == 0;
      boolean optimizedOperation =
          call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(HOOKS) && call.name.equals("retainAllFast")
              && call.desc.equals("(Ljava/util/Set;Ljava/util/Collection;Ljava/lang/Object;)Z") && position == 1;
      if (collectionOperation || optimizedOperation) return "safe-collection-membership-consumer";
    }
    if (call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(HOOKS) && position == 0) {
      if (call.name.equals("scratchListAdd") && call.desc.equals("(Ljava/util/List;Ljava/lang/Object;)Z")) {
        return Opcodes.INVOKEINTERFACE + "|java/util/List|add|" + "(Ljava/lang/Object;)Z|receiver";
      }
      if (call.name.equals("scratchSetAdd") && call.desc.equals("(Ljava/util/Set;Ljava/lang/Object;)Z")) {
        return Opcodes.INVOKEINTERFACE + "|java/util/Set|add|" + "(Ljava/lang/Object;)Z|receiver";
      }
      if (call.name.equals("scratchSetAddAll") && call.desc.equals("(Ljava/util/Set;Ljava/util/Collection;)Z")) {
        return Opcodes.INVOKEINTERFACE + "|java/util/Set|addAll|" + "(Ljava/util/Collection;)Z|receiver";
      }
    }
    String name = call.name;
    String vectorDesc = "(Lorg/lwjgl/util/vector/Vector2f;Lorg/lwjgl/util/vector/Vector2f;)F";
    if (spec.hookName().equals("borrowHitPoint") && call.owner.equals("com/fs/starfarer/prototype/Utils")
        && call.desc.equals(vectorDesc)) {
      name = "*";
    }
    return call.getOpcode() + "|" + call.owner + "|" + name + "|" + call.desc + "|"
        + (position < 0 ? "receiver" : "arg" + position);
  }

  private static Map<String, Integer> expectedScratchUses(String owner, AllocationSpec spec, int ordinal) {
    Map<String, Integer> expected = new LinkedHashMap<>();
    switch (spec.hookName()) {
      case "borrowEntityList" -> {
        expectUse(expected, Opcodes.INVOKEINTERFACE, "java/util/List", "addAll", "(Ljava/util/Collection;)Z", -1, 3);
        expectUse(expected, Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", -1, 1);
        expectUse(expected, Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;", -1, 1);
        expected.put("safe-collection-membership-consumer", -1);
      }
      case "borrowClassSet" -> {
        expectUse(expected, Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z", -1, 7);
        expectUse(expected, Opcodes.INVOKEINTERFACE, "java/util/Set", "contains", "(Ljava/lang/Object;)Z", -1, 1);
      }
      case "borrowHitList" -> {
        expectUse(expected, Opcodes.INVOKEINTERFACE, "java/util/List", "addAll", "(Ljava/util/Collection;)Z", -1, 1);
        expectUse(expected, Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;", -1, 1);
      }
      case "borrowLocationAdvanceSnapshot", "borrowEntityScriptSnapshot" ->
        expectUse(expected, Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;", -1, 1);
      case "borrowPausedLocationSnapshot" -> expectUse(expected, Opcodes.INVOKEINTERFACE, "java/util/List", "iterator",
          "()Ljava/util/Iterator;", -1, ordinal == 0 ? 2 : 1);
      case "borrowHitPoint" -> expectUse(expected, Opcodes.INVOKESTATIC, "com/fs/starfarer/prototype/Utils", "*",
          "(Lorg/lwjgl/util/vector/Vector2f;Lorg/lwjgl/util/vector/Vector2f;)F", 0, 2);
      case "borrowArrowVector" -> {
        int position = ordinal;
        String twoVectors = "(Lorg/lwjgl/util/vector/Vector2f;" + "Lorg/lwjgl/util/vector/Vector2f;)F";
        expectUse(expected, Opcodes.INVOKESTATIC, "com/fs/starfarer/api/util/Misc", "getDistance", twoVectors, position,
            1);
        expectUse(expected, Opcodes.INVOKESTATIC, "com/fs/starfarer/api/util/Misc", "getAngleInDegrees", twoVectors,
            position, 1);
        expectUse(expected, Opcodes.INVOKESTATIC, owner, "o00000",
            "(Lorg/lwjgl/util/vector/Vector2f;Lorg/lwjgl/util/vector/Vector2f;" + "FLjava/awt/Color;F)V", position, 1);
      }
      default -> throw mismatch("no scratch use contract for " + spec.hookName());
    }
    return expected;
  }

  private static boolean scratchUsesMatch(Map<String, Integer> expected, Map<String, Integer> observed) {
    if (!expected.keySet().equals(observed.keySet())) return false;
    for (Map.Entry<String, Integer> entry : expected.entrySet()) {
      int actual = observed.getOrDefault(entry.getKey(), 0);
      if (entry.getValue() < 0) {
        if (actual < 1) return false;
      } else if (actual != entry.getValue()) { return false; }
    }
    return true;
  }

  static void expectUse(Map<String, Integer> expected, int opcode, String owner, String name, String desc, int position,
      int count) {
    String key = opcode + "|" + owner + "|" + name + "|" + desc + "|" + (position < 0 ? "receiver" : "arg" + position);
    expected.put(key, count);
  }

  private static Set<AbstractInsnNode> validateArrowVectorRmw(MethodNode method, int local, AllocationSpec spec,
      int ordinal) {
    Set<AbstractInsnNode> starts = new HashSet<>();
    if (!spec.hookName().equals("borrowArrowVector")) return starts;
    Set<String> fields = new HashSet<>();
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!(insn instanceof FieldInsnNode put) || put.getOpcode() != Opcodes.PUTFIELD
          || !put.owner.equals("org/lwjgl/util/vector/Vector2f") || !put.desc.equals("F"))
        continue;
      AbstractInsnNode start = null;
      AbstractInsnNode get = null;
      int seen = 0;
      for (AbstractInsnNode cursor = put.getPrevious(); cursor != null && seen < 10; cursor = cursor.getPrevious()) {
        if (cursor.getOpcode() < 0) continue;
        seen++;
        if (get == null && cursor instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
            && field.owner.equals(put.owner) && field.name.equals(put.name) && field.desc.equals(put.desc)) {
          get = field;
        }
        if (cursor instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == local
            && nextMeaningful(load) != null && nextMeaningful(load).getOpcode() == Opcodes.DUP) {
          start = load;
          break;
        }
      }
      if (start != null && get != null) {
        starts.add(start);
        fields.add(put.name);
      }
    }
    if (starts.size() != 2 || !fields.equals(Set.of("x", "y"))) {
      throw mismatch("arrow vector local " + local + " (ordinal " + ordinal
          + ") does not have exactly one self-RMW chain for x and y");
    }
    return starts;
  }

}
