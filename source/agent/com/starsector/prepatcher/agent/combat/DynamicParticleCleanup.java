package com.starsector.prepatcher.agent.combat;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.List;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import org.objectweb.asm.tree.*;
import static com.starsector.prepatcher.agent.bytecode.Markers.hasPatchMarker;
import static com.starsector.prepatcher.agent.bytecode.OwnedTemplates.*;

import static com.starsector.prepatcher.agent.bytecode.Allocations.installScratchScope;
import static com.starsector.prepatcher.agent.bytecode.Allocations.replaceStoredSimpleAllocations;
import static com.starsector.prepatcher.agent.bytecode.Allocations.scratchScopeState;
import static com.starsector.prepatcher.agent.bytecode.Allocations.storedAllocationResultStoresRelaxed;
import static com.starsector.prepatcher.agent.bytecode.Allocations.storedSimpleAllocationState;
import static com.starsector.prepatcher.agent.bytecode.Contract.mismatch;
import static com.starsector.prepatcher.agent.bytecode.Contract.requireMethod;
import static com.starsector.prepatcher.agent.bytecode.Contract.uniformPatchState;
import static com.starsector.prepatcher.agent.bytecode.Flow.receiverSource;
import static com.starsector.prepatcher.agent.bytecode.Flow.sourceFrames;
import static com.starsector.prepatcher.agent.bytecode.Flow.sourceIsActiveHookLocal;
import static com.starsector.prepatcher.agent.bytecode.Mutations.makeStatic;
import static com.starsector.prepatcher.agent.bytecode.Names.HOOKS;
import static com.starsector.prepatcher.agent.bytecode.Queries.calls;
import static com.starsector.prepatcher.agent.bytecode.Queries.countCalls;
import static com.starsector.prepatcher.agent.bytecode.Queries.nextMeaningful;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.patch.PatchState;
import com.starsector.prepatcher.agent.bytecode.AllocationSpec;

public final class DynamicParticleCleanup implements StructuredPatch<DynamicParticleCleanup.Plan> {
  record Plan(String owner, MethodNode advance, MethodInsnNode removeAll, MethodInsnNode add) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode advance = requireMethod(node, "advance", "(F)V");
    if (!hasPatchMarker(node, "particleCleanup") && matchesArrayCleanup(node, advance)) {
      return Match.notNeeded("particle advance already uses allocation-free array compaction without a removal list or removeAll");
    }
    PatchState state = uniformPatchState("DynamicParticleGroup cleanup",
        scratchScopeState(node.name, advance, "DynamicParticleGroup.advance"),
        storedSimpleAllocationState(advance, "java/util/ArrayList", "()V", "borrowParticleRemovalList",
            "()Ljava/util/ArrayList;", 1, "DynamicParticleGroup expired list"),
        particleCleanupState(advance), particleScratchMutationState(advance));
    if (state == PatchState.PATCHED) {
      return Match.alreadyApplied("all DynamicParticleGroup cleanup hooks and scope postconditions match");
    }
    MethodInsnNode removeAll =
        calls(advance, Opcodes.INVOKEVIRTUAL, "java/util/LinkedList", "removeAll", "(Ljava/util/Collection;)Z").get(0);
    return Match.plan(new Plan(node.name, advance, removeAll, matchParticleScratchMutation(node.name, advance)));
  }

  private static boolean matchesArrayCleanup(ClassNode node, MethodNode advance) {
    String particleArray = "[Lcom/fs/graphics/particle/BaseParticle;";
    boolean array = node.fields.stream().anyMatch(f -> f.name.equals("particlesArray") && f.desc.equals(particleArray)
        && (f.access & Opcodes.ACC_STATIC) == 0);
    boolean size = node.fields.stream().anyMatch(f -> f.name.equals("particlesNum") && f.desc.equals("I")
        && (f.access & Opcodes.ACC_STATIC) == 0);
    if (!array || !size || (advance.access & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED)) != 0) return false;
    String templateOwner = "com/starsector/prepatcher/agent/templates/ParticleArrayCleanupTemplate";
    ClassNode template = loadOwnedTemplate(templateOwner, "/" + templateOwner + ".class", "particle array cleanup");
    MethodNode expected = cloneAndRemapOwnedTemplateMethod(requireMethod(template, "advance", "(F)V"), templateOwner, node.name, "particle array cleanup");
    return advance.tryCatchBlocks.isEmpty() && instructionShape(advance).equals(instructionShape(expected));
  }

  /** Every opcode, operand and branch target is checked; debug entries and frame encoding are not semantic. */
  private static List<String> instructionShape(MethodNode method) {
    IdentityHashMap<LabelNode, Integer> labels = new IdentityHashMap<>();
    int position = 0;
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof LabelNode label) labels.put(label, position);
      if (instruction.getOpcode() >= 0) position++;
    }
    List<String> shape = new ArrayList<>();
    for (AbstractInsnNode instruction : method.instructions) {
      int opcode = instruction.getOpcode();
      if (opcode < 0) continue;
      String key = Integer.toString(opcode);
      if (instruction instanceof VarInsnNode var) key += ":" + var.var;
      else if (instruction instanceof FieldInsnNode field) key += ":" + field.owner + ":" + field.name + ":" + field.desc;
      else if (instruction instanceof MethodInsnNode call) key += ":" + call.owner + ":" + call.name + ":" + call.desc + ":" + call.itf;
      else if (instruction instanceof JumpInsnNode jump) key += ":" + labels.get(jump.label);
      else if (instruction instanceof IincInsnNode increment) key += ":" + increment.var + ":" + increment.incr;
      else if (!(instruction instanceof InsnNode)) return List.of("unsupported instruction");
      shape.add(key);
    }
    return shape;
  }

  @Override
  public PatchReport mutate(Plan plan) {
    installScratchScope(plan.owner(), plan.advance(), "DynamicParticleGroup.advance");
    int changed = replaceStoredSimpleAllocations(plan.owner(), plan.advance(), "java/util/ArrayList", "()V",
        "borrowParticleRemovalList", "()Ljava/util/ArrayList;", 1, true, "DynamicParticleGroup expired list");
    makeStatic(plan.add(), HOOKS, "scratchListAdd", "(Ljava/util/List;Ljava/lang/Object;)Z");
    if (particleScratchMutationState(plan.advance()) != PatchState.PATCHED) {
      throw mismatch("particle scratch peak hook postcondition failed");
    }
    changed++;
    makeStatic(plan.removeAll(), HOOKS, "removeExpiredParticles", "(Ljava/util/LinkedList;Ljava/util/Collection;)Z");
    changed++;

    PatchReport report = new PatchReport();
    report.add("reusable particle expiry buffer and linear cleanup", changed);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    uniformPatchState("DynamicParticleGroup cleanup",
        scratchScopeState(plan.owner(), plan.advance(), "DynamicParticleGroup.advance"),
        storedSimpleAllocationState(plan.advance(), "java/util/ArrayList", "()V", "borrowParticleRemovalList",
            "()Ljava/util/ArrayList;", 1, "DynamicParticleGroup expired list"),
        particleCleanupState(plan.advance()), particleScratchMutationState(plan.advance()));
  }

  private static PatchState particleScratchMutationState(MethodNode method) {
    int raw = countCalls(method, Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z");
    int hooks =
        countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "scratchListAdd", "(Ljava/util/List;Ljava/lang/Object;)Z");
    boolean original = raw == 1 && hooks == 0;
    boolean patched = raw == 0 && hooks == 1;
    if (!original && !patched) { throw mismatch("DynamicParticleGroup scratch mutation hook is partial or changed"); }
    return original ? PatchState.ORIGINAL : PatchState.PATCHED;
  }

  private static MethodInsnNode matchParticleScratchMutation(String owner, MethodNode method) {
    AllocationSpec spec =
        new AllocationSpec("java/util/ArrayList", "()V", "borrowParticleRemovalList", "()Ljava/util/ArrayList;", 1);
    List<VarInsnNode> stores = storedAllocationResultStoresRelaxed(method, spec);
    if (stores.size() != 1) throw mismatch("particle scratch local is ambiguous");
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    List<MethodInsnNode> raw = calls(method, Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z");
    if (raw.size() != 1
        || !sourceIsActiveHookLocal(method, raw.get(0), receiverSource(method, raw.get(0), frames), stores)) {
      throw mismatch("particle scratch List.add receiver changed");
    }
    return raw.get(0);
  }

  private static PatchState particleCleanupState(MethodNode method) {
    String label = "DynamicParticleGroup removeAll cleanup";
    String hookDesc = "(Ljava/util/LinkedList;Ljava/util/Collection;)Z";
    List<MethodInsnNode> raw =
        calls(method, Opcodes.INVOKEVIRTUAL, "java/util/LinkedList", "removeAll", "(Ljava/util/Collection;)Z");
    List<MethodInsnNode> hooks = calls(method, Opcodes.INVOKESTATIC, HOOKS, "removeExpiredParticles", hookDesc);
    boolean original = raw.size() == 1 && hooks.isEmpty();
    boolean patched = raw.isEmpty() && hooks.size() == 1;
    if (!original && !patched) { throw mismatch(label + " is missing, duplicated, or partially patched"); }
    MethodInsnNode cleanup = original ? raw.get(0) : hooks.get(0);
    AbstractInsnNode discarded = nextMeaningful(cleanup);
    if (discarded == null || discarded.getOpcode() != Opcodes.POP) {
      throw mismatch(label + " result is not discarded");
    }
    return original ? PatchState.ORIGINAL : PatchState.PATCHED;
  }

}
