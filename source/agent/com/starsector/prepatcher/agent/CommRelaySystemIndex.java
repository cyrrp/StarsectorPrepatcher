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

import com.starsector.prepatcher.agent.PrepatcherTransformer.Match;
import com.starsector.prepatcher.agent.PrepatcherTransformer.PatchReport;
import com.starsector.prepatcher.agent.PrepatcherTransformer.StructuredPatch;

import static com.starsector.prepatcher.agent.Allocations.*;
import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Flow.*;
import static com.starsector.prepatcher.agent.Mutations.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.CAMPAIGN_ENGINE;
import static com.starsector.prepatcher.agent.Queries.*;

/**
 * Replaces the linear CampaignEngine.getStarSystems scan in
 * IntelManager.findNearestCommRelayToReceive with the ordered spatial-index hook, and
 * wraps the method in the reentrant scratch scope the index borrow requires.
 */
final class CommRelaySystemIndex implements StructuredPatch<CommRelaySystemIndex.Plan> {
  private static final String HOOK_DESC =
      "(L" + CAMPAIGN_ENGINE + ";Lorg/lwjgl/util/vector/Vector2f;FF)Ljava/util/List;";
  private static final String SCOPE = "IntelManager.findNearestCommRelayToReceive";

  /** The proven method and the getStarSystems scan site. */
  record Plan(String owner, MethodNode method, MethodInsnNode getSystems) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    String desc = "(" + ENTITY_TOKEN_DESC + ")" + ENTITY_TOKEN_DESC;
    MethodNode method = requireMethod(node, "findNearestCommRelayToReceive", desc);
    // The original flow installed the scope here; running the install guards in match
    // keeps the refusal order identical without mutating.
    PatchState scopeState = scratchScopeState(node.name, method, SCOPE);
    if (scopeState == PatchState.ORIGINAL) {
      requireScratchScopeInstallable(method, SCOPE);
    }
    List<MethodInsnNode> raw =
        calls(method, Opcodes.INVOKEVIRTUAL, CAMPAIGN_ENGINE, "getStarSystems", "()Ljava/util/List;");
    int hooks = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "commRelayCandidateSystems", HOOK_DESC);
    boolean original = raw.size() == 1 && hooks == 0;
    boolean patched = raw.isEmpty() && hooks == 1;
    if (patched) {
      if (scopeState == PatchState.ORIGINAL) {
        return Match.incompatible("comm relay hook exists without its scratch scope");
      }
      return Match.alreadyApplied("comm relay system-index hook is already present");
    }
    if (!original) return Match.incompatible("IntelManager comm-relay system scan changed structurally");

    MethodInsnNode getSystems = raw.get(0);
    Frame<SourceValue>[] frames = sourceFrames(node.name, method);
    requireSourceMethod(receiverSource(method, getSystems, frames), Opcodes.INVOKESTATIC, CAMPAIGN_ENGINE,
        "getInstance", "()L" + CAMPAIGN_ENGINE + ";", "comm-relay CampaignEngine source");
    requireStoredLocalProducer(method, 2, Opcodes.ASTORE, Opcodes.INVOKEINTERFACE,
        "com/fs/starfarer/api/campaign/SectorEntityToken", "getLocationInHyperspace",
        "()Lorg/lwjgl/util/vector/Vector2f;", getSystems, "comm-relay player hyperspace location");
    requireFloatLocalProducer(method, 3, getSystems, "comm-relay hyperspace units-per-light-year");
    requireCount("MAX_RANGE_AROUND_SYSTEM field",
        countFields(method, Opcodes.GETSTATIC, node.name, "MAX_RANGE_AROUND_SYSTEM", "F"), 2);
    return Match.plan(new Plan(node.name, method, getSystems));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    ensureScratchScope(plan.owner(), plan.method(), SCOPE);
    plan.method().instructions.insertBefore(plan.getSystems(), new VarInsnNode(Opcodes.ALOAD, 2));
    plan.method().instructions.insertBefore(plan.getSystems(), new VarInsnNode(Opcodes.FLOAD, 3));
    plan.method().instructions.insertBefore(plan.getSystems(),
        new FieldInsnNode(Opcodes.GETSTATIC, plan.owner(), "MAX_RANGE_AROUND_SYSTEM", "F"));
    makeStatic(plan.getSystems(), HOOKS, "commRelayCandidateSystems", HOOK_DESC);

    PatchReport report = new PatchReport();
    report.add("ordered hyperspace comm-relay system spatial index", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireCount("comm relay system-index postcondition",
        countCalls(plan.method(), Opcodes.INVOKESTATIC, HOOKS, "commRelayCandidateSystems", HOOK_DESC), 1);
    if (scratchScopeState(plan.owner(), plan.method(), SCOPE) != PatchState.PATCHED) {
      throw mismatch(SCOPE + " scratch-scope installation postcondition failed");
    }
  }
}
