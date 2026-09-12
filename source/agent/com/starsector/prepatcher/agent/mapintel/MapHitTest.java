package com.starsector.prepatcher.agent.mapintel;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.Allocations.*;
import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Mutations.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.bytecode.AllocationSpec;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.patch.PatchState;
import com.starsector.prepatcher.agent.PrepatcherTransformer;
import com.starsector.prepatcher.agent.patch.StructuredPatch;

/**
 * Owns the complete A.OO0000 hit-test method: the original body is preserved as
 * a private synthetic method with a scratch scope and reusable hit list/point
 * allocations, and the public name becomes a thin wrapper over the bounded
 * exact-result hover cache. This member runs inside MapInteractionPlan so
 * call-site members are applied to the preserved original body before the
 * wrapper split.
 */
public final class MapHitTest implements StructuredPatch<MapHitTest.Plan> {
  private static final String PUBLIC_NAME = "OO0000";
  private static final String RENAMED = "smo$originalHitTest";
  private static final String HOOK = "hitTestCached";
  private static final String SCOPE = "A.OO0000";
  private static final String SCRATCH = "A.OO0000 hit-test scratch";

  /** The proven original body plus the descriptors and scratch specs it was proven against. */
  record Plan(String owner, List<MethodNode> classMethods, MethodNode original, String desc, String hookDesc,
      List<AllocationSpec> specs) {
  }

  private static String desc() {
    return "(FFF)" + ENTITY_TOKEN_DESC;
  }

  private static String hookDesc() {
    return "(Ljava/lang/Object;FFF)" + ENTITY_TOKEN_DESC;
  }

  private static List<AllocationSpec> specs() {
    return List.of(
        new AllocationSpec("java/util/ArrayList", "(Ljava/util/Collection;)V", "borrowHitList",
            "(Ljava/util/Collection;)Ljava/util/ArrayList;", 1),
        new AllocationSpec("org/lwjgl/util/vector/Vector2f", "(FF)V", "borrowHitPoint",
            "(FF)Lorg/lwjgl/util/vector/Vector2f;", 1));
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    String desc = desc();
    String hookDesc = hookDesc();
    List<AllocationSpec> specs = specs();

    boolean wrapperAlready = methods(node, RENAMED, desc).size() == 1;
    MethodNode original = requireWrapperSource(node, PUBLIC_NAME, desc, RENAMED, HOOK, hookDesc);
    PatchState scopeState = scratchScopeState(node.name, original, SCOPE);
    PatchState allocationState = scratchAllocationGroupState(node.name, original, specs, SCRATCH);

    if (containsWriteOrMonitor(original)) {
      return Match.incompatible("hit-test method writes state or uses monitor/invokedynamic operations");
    }
    requireCount("hit-test semantic call count", totalMethodCalls(original),
        scopeState == PatchState.PATCHED ? 54 : 50);
    List<FieldNode> mapFields = fields(node, "L" + PrepatcherTransformer.H + ";");
    requireCount("A map-handler fields", mapFields.size(), 1);
    requireCountAtLeast("hit-test H.getLocation", countCalls(original, Opcodes.INVOKEVIRTUAL, PrepatcherTransformer.H,
        "getLocation", "()Lcom/fs/starfarer/campaign/BaseLocation;"), 1);
    requireCountAtLeast("hit-test H.getFactor",
        countCalls(original, Opcodes.INVOKEVIRTUAL, PrepatcherTransformer.H, "getFactor", "()F"), 1);
    requireCountAtLeast("hit-test H.getIcons",
        countCalls(original, Opcodes.INVOKEVIRTUAL, PrepatcherTransformer.H, "getIcons", "()Ljava/util/LinkedHashMap;"),
        1);

    if (wrapperAlready) {
      if (scopeState != PatchState.PATCHED || allocationState != PatchState.PATCHED) {
        return Match.incompatible("hit-test wrapper exists without the complete scratch allocation group");
      }
      requireWrapperPostcondition(node, PUBLIC_NAME, desc, RENAMED, HOOK, hookDesc);
      requireHitTestWrapper(node, desc, hookDesc);
      return Match.alreadyApplied("hit-test wrapper, scratch scope, allocation hooks and semantic contract match");
    }
    if (scopeState != PatchState.ORIGINAL || allocationState != PatchState.ORIGINAL) {
      return Match.incompatible("hit-test method has a mixed wrapper/scope/allocation state");
    }
    return Match.plan(new Plan(node.name, node.methods, original, desc, hookDesc, specs));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    MethodNode original = plan.original();
    installScratchScope(plan.owner(), original, SCOPE);
    int changed = replaceAllocationGroup(plan.owner(), original, plan.specs(), true, SCRATCH);

    int originalAccess = original.access;
    String signature = original.signature;
    String[] exceptions = exceptions(original);
    original.name = RENAMED;
    makePrivateSynthetic(original);
    MethodNode wrapper = new MethodNode(Opcodes.ASM8, originalAccess, PUBLIC_NAME, plan.desc(), signature, exceptions);
    moveWrapperMetadata(original, wrapper, PUBLIC_NAME);
    wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
    wrapper.instructions.add(new VarInsnNode(Opcodes.FLOAD, argumentLocal(wrapper, 0)));
    wrapper.instructions.add(new VarInsnNode(Opcodes.FLOAD, argumentLocal(wrapper, 1)));
    wrapper.instructions.add(new VarInsnNode(Opcodes.FLOAD, argumentLocal(wrapper, 2)));
    wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, HOOK, plan.hookDesc(), false));
    wrapper.instructions.add(new InsnNode(Opcodes.ARETURN));
    plan.classMethods().add(wrapper);

    PatchReport report = new PatchReport();
    report.add("hit-test scratch allocations", changed);
    report.add("bounded exact-result hover cache", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    MethodNode original = plan.original();
    requireWrapperPostcondition(node, PUBLIC_NAME, plan.desc(), RENAMED, HOOK, plan.hookDesc());
    requireHitTestWrapper(node, plan.desc(), plan.hookDesc());
    requireCount("hit-test scratch scope",
        countCalls(original, Opcodes.INVOKESTATIC, HOOKS, "beginScratchScope", "()V"), 1);
    requireCount("hit-test list hook", countCalls(original, Opcodes.INVOKESTATIC, HOOKS, "borrowHitList",
        "(Ljava/util/Collection;)Ljava/util/ArrayList;"), 1);
    requireCount("hit-test point hook",
        countCalls(original, Opcodes.INVOKESTATIC, HOOKS, "borrowHitPoint", "(FF)Lorg/lwjgl/util/vector/Vector2f;"), 1);
  }

  private static void requireHitTestWrapper(ClassNode node, String desc, String hookDesc) {
    MethodNode wrapper = requireMethod(node, "OO0000", desc);
    List<AbstractInsnNode> code = meaningfulInstructions(wrapper);
    requireCount("OO0000 wrapper instruction count", code.size(), 6);
    requireVar(code.get(0), Opcodes.ALOAD, 0, "hit-test wrapper owner");
    requireVar(code.get(1), Opcodes.FLOAD, argumentLocal(wrapper, 0), "hit-test wrapper x");
    requireVar(code.get(2), Opcodes.FLOAD, argumentLocal(wrapper, 1), "hit-test wrapper y");
    requireVar(code.get(3), Opcodes.FLOAD, argumentLocal(wrapper, 2), "hit-test wrapper radius");
    requireCall(asMethod(code.get(4), "hit-test wrapper hook"), Opcodes.INVOKESTATIC, HOOKS, "hitTestCached", hookDesc,
        "hit-test wrapper hook");
    if (code.get(5).getOpcode() != Opcodes.ARETURN) throw mismatch("hit-test wrapper return changed");
  }
}
