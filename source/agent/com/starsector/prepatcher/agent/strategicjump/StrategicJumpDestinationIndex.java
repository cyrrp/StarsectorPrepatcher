package com.starsector.prepatcher.agent.strategicjump;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Markers.*;
import static com.starsector.prepatcher.agent.bytecode.Mutations.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import static com.starsector.prepatcher.agent.strategicjump.StrategicJump.*;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.PrepatcherTransformer;
import com.starsector.prepatcher.agent.patch.StructuredPatch;

/**
 * Transformation surface: the initial candidate-source expression in
 * StrategicModule.findNearestSafeJumpPoint and the expired-plan clear branch in
 * updateJumpPlanTo. This member runs inside StrategicJumpDestinationPlan: it
 * consumes the destination-first post-state proven on the same plan candidate,
 * replaces LocationAPI.getEntities(JumpPoint.class) with the indexed runtime
 * hook, and defers only an expired replacement while the index is cold.
 */
final class StrategicJumpDestinationIndex implements StructuredPatch<StrategicJumpDestinationIndex.Plan> {
  private static final String STRATEGIC_JUMP_INDEX_HOOK = "strategicJumpPointsForDestination";
  private static final String STRATEGIC_JUMP_INDEX_HOOK_DESC = "(Lcom/fs/starfarer/api/campaign/LocationAPI;"
      + "Ljava/lang/Class;" + "Lcom/fs/starfarer/api/campaign/LocationAPI;)Ljava/util/List;";
  private static final String STRATEGIC_JUMP_DEFER_HOOK = "strategicJumpDeferExpiredPlan";
  private static final String STRATEGIC_JUMP_DEFER_HOOK_DESC =
      "(Lcom/fs/starfarer/api/campaign/LocationAPI;" + "Lcom/fs/starfarer/api/campaign/LocationAPI;)Z";

  record Plan(String owner, MethodNode method, MethodNode update, StrategicJumpExpiry expiry,
      StrategicJumpSource original) {
  }

  private record StrategicJumpSource(MethodInsnNode call, AbstractInsnNode argument) {
  }

  private record StrategicJumpExpiry(JumpInsnNode expiryJump, VarInsnNode clearStart, FieldInsnNode clearWrite,
      MethodInsnNode findCall) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode method = requireMethod(node, "findNearestSafeJumpPoint", STRATEGIC_JUMP_METHOD_DESC);
    MethodNode update = requireMethod(node, "updateJumpPlanTo", STRATEGIC_JUMP_UPDATE_METHOD_DESC);
    StrategicJumpCommon common = inspectStrategicJumpCommon(node.name, method);
    if (inspectStrategicJumpOriginal(common, false) != null || inspectStrategicJumpLazy(common, false) == null) {
      return Match.incompatible("destination-first postcondition changed before index composition");
    }

    StrategicJumpExpiry expiry = inspectStrategicJumpExpiry(node.name, update, true);
    boolean deferred = strategicJumpExpiryDeferralState(node.name, update, expiry, false);
    boolean owned = hasPatchMarker(node, STRATEGIC_JUMP_DESTINATION_INDEX_PATCH_ID);
    StrategicJumpSource original = inspectStrategicJumpOriginalSource(method, false);
    StrategicJumpSource indexed = inspectStrategicJumpIndexedSource(method, false);
    if (owned) {
      if (indexed == null || original != null || !deferred) {
        return Match.incompatible("owned strategic destination index has a mixed post-state");
      }
      return Match.alreadyApplied("strategic jump source and expired-plan deferral are already installed");
    }
    if (indexed != null || deferred) {
      return Match.alreadyApplied("strategic destination-index bytecode is present without this marker");
    }
    if (original == null) { return Match.incompatible("vanilla StrategicModule jump-point source was not found"); }
    return Match.plan(new Plan(node.name, method, update, expiry, original));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    plan.method().instructions.insertBefore(plan.original().call(),
        new VarInsnNode(Opcodes.ALOAD, argumentLocal(plan.method(), 1)));
    makeStatic(plan.original().call(), HOOKS, STRATEGIC_JUMP_INDEX_HOOK, STRATEGIC_JUMP_INDEX_HOOK_DESC);
    installStrategicJumpExpiryDeferral(plan.owner(), plan.update(), plan.expiry());

    PatchReport report = new PatchReport();
    report.add("strategic destination-location jump-point source", 1);
    report.add("strategic expired-plan incremental-index deferrals", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    MethodNode method = plan.method();
    MethodNode update = plan.update();
    StrategicJumpSource patched = inspectStrategicJumpIndexedSource(method, true);
    if (patched == null || inspectStrategicJumpOriginalSource(method, false) != null) {
      throw mismatch("strategic destination-index mutation did not reach its post-state");
    }
    StrategicJumpExpiry patchedExpiry = inspectStrategicJumpExpiry(plan.owner(), update, true);
    if (!strategicJumpExpiryDeferralState(plan.owner(), update, patchedExpiry, true)) {
      throw mismatch("strategic expired-plan deferral did not reach its post-state");
    }
    StrategicJumpCommon recomposed = inspectStrategicJumpCommon(plan.owner(), method);
    if (inspectStrategicJumpOriginal(recomposed, false) != null
        || inspectStrategicJumpLazy(recomposed, false) == null) {
      throw mismatch("strategic destination index damaged destination-first semantics");
    }
  }

  private static void installStrategicJumpExpiryDeferral(String owner, MethodNode method, StrategicJumpExpiry expiry) {
    LabelNode continueClear = new LabelNode();
    InsnList hook = new InsnList();
    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
    hook.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "fleet", "Lcom/fs/starfarer/campaign/fleet/CampaignFleet;"));
    hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/campaign/fleet/CampaignFleet",
        "getContainingLocation", "()Lcom/fs/starfarer/campaign/BaseLocation;", false));
    hook.add(new VarInsnNode(Opcodes.ALOAD, argumentLocal(method, 0)));
    hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, STRATEGIC_JUMP_DEFER_HOOK, STRATEGIC_JUMP_DEFER_HOOK_DESC,
        false));
    hook.add(new JumpInsnNode(Opcodes.IFEQ, continueClear));
    hook.add(new InsnNode(Opcodes.RETURN));
    hook.add(continueClear);
    hook.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
    method.instructions.insertBefore(expiry.clearStart(), hook);
  }

  private static StrategicJumpExpiry inspectStrategicJumpExpiry(String owner, MethodNode method, boolean required) {
    List<MethodInsnNode> findCalls =
        calls(method, Opcodes.INVOKESTATIC, owner, "findNearestSafeJumpPoint", STRATEGIC_JUMP_METHOD_DESC);
    if (findCalls.size() != 1) {
      if (required) throw mismatch("StrategicModule update findNearest call count changed");
      return null;
    }
    FieldInsnNode comparedTime = null;
    JumpInsnNode expiryJump = null;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
          || !field.owner.equals("com/fs/starfarer/campaign/ai/CampaignFleetAI$JumpPlan")
          || !field.name.equals("timeLeft") || !field.desc.equals("F")) {
        continue;
      }
      AbstractInsnNode zero = nextMeaningful(field);
      AbstractInsnNode compare = nextMeaningful(zero);
      AbstractInsnNode jump = nextMeaningful(compare);
      if (zero == null || zero.getOpcode() != Opcodes.FCONST_0 || compare == null
          || compare.getOpcode() != Opcodes.FCMPG || !(jump instanceof JumpInsnNode candidate)
          || candidate.getOpcode() != Opcodes.IFGT)
        continue;
      if (comparedTime != null) {
        if (required) throw mismatch("StrategicModule has ambiguous plan-expiry checks");
        return null;
      }
      comparedTime = field;
      expiryJump = candidate;
    }
    if (comparedTime == null || expiryJump == null) {
      if (required) throw mismatch("StrategicModule plan-expiry comparison was not found");
      return null;
    }

    int branchIndex = method.instructions.indexOf(expiryJump);
    int targetIndex = method.instructions.indexOf(expiryJump.label);
    FieldInsnNode clearWrite = null;
    VarInsnNode clearStart = null;
    for (AbstractInsnNode cursor = nextMeaningful(expiryJump); cursor != null
        && method.instructions.indexOf(cursor) < targetIndex; cursor = nextMeaningful(cursor)) {
      if (!(cursor instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
          || !field.owner.equals(owner) || !field.name.equals("currJumpPlan")
          || !field.desc.equals("Lcom/fs/starfarer/campaign/ai/CampaignFleetAI$JumpPlan;"))
        continue;
      AbstractInsnNode nullValue = previousMeaningful(field);
      AbstractInsnNode receiver = previousMeaningful(nullValue);
      if (nullValue == null || nullValue.getOpcode() != Opcodes.ACONST_NULL || !(receiver instanceof VarInsnNode load)
          || load.getOpcode() != Opcodes.ALOAD || load.var != 0)
        continue;
      if (clearWrite != null) {
        if (required) throw mismatch("StrategicModule expiry branch clears multiple plans");
        return null;
      }
      clearWrite = field;
      clearStart = load;
    }
    if (clearWrite == null || clearStart == null || branchIndex >= method.instructions.indexOf(clearStart)
        || method.instructions.indexOf(clearWrite) >= targetIndex
        || method.instructions.indexOf(findCalls.get(0)) <= targetIndex) {
      if (required) throw mismatch("StrategicModule expired-plan clear shape changed");
      return null;
    }
    return new StrategicJumpExpiry(expiryJump, clearStart, clearWrite, findCalls.get(0));
  }

  private static boolean strategicJumpExpiryDeferralState(String owner, MethodNode method, StrategicJumpExpiry expiry,
      boolean required) {
    List<MethodInsnNode> hooks =
        calls(method, Opcodes.INVOKESTATIC, HOOKS, STRATEGIC_JUMP_DEFER_HOOK, STRATEGIC_JUMP_DEFER_HOOK_DESC);
    if (hooks.size() != 1) {
      if (required) throw mismatch("StrategicModule expired-plan deferral count changed");
      return false;
    }
    MethodInsnNode hook = hooks.get(0);
    VarInsnNode requested =
        requireVar(previousMeaningful(hook), Opcodes.ALOAD, "StrategicModule deferral requested-location argument");
    MethodInsnNode containing =
        asMethod(previousMeaningful(requested), "StrategicModule deferral current-location getter");
    AbstractInsnNode fleetInsn = previousMeaningful(containing);
    if (!(fleetInsn instanceof FieldInsnNode fleet) || fleet.getOpcode() != Opcodes.GETFIELD) {
      if (required) throw mismatch("StrategicModule deferral fleet field changed");
      return false;
    }
    VarInsnNode receiver = requireVar(previousMeaningful(fleet), Opcodes.ALOAD, "StrategicModule deferral receiver");
    JumpInsnNode continueJump =
        requireJump(nextMeaningful(hook), Opcodes.IFEQ, "StrategicModule deferral continuation");
    AbstractInsnNode earlyReturn = nextMeaningful(continueJump);
    if (requested.var != argumentLocal(method, 0) || receiver.var != 0 || !fleet.owner.equals(owner)
        || !fleet.name.equals("fleet") || !fleet.desc.equals("Lcom/fs/starfarer/campaign/fleet/CampaignFleet;")
        || !callMatches(containing, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/campaign/fleet/CampaignFleet",
            "getContainingLocation", "()Lcom/fs/starfarer/campaign/BaseLocation;")
        || earlyReturn == null || earlyReturn.getOpcode() != Opcodes.RETURN
        || method.instructions.indexOf(hook) <= method.instructions.indexOf(expiry.expiryJump())
        || method.instructions.indexOf(earlyReturn) >= method.instructions.indexOf(expiry.clearStart())
        || method.instructions.indexOf(continueJump.label) >= method.instructions.indexOf(expiry.clearStart())
        || nextMeaningful(continueJump.label) != expiry.clearStart()) {
      if (required) throw mismatch("StrategicModule expired-plan deferral shape changed");
      return false;
    }
    return true;
  }

  private static StrategicJumpSource inspectStrategicJumpOriginalSource(MethodNode method, boolean required) {
    List<MethodInsnNode> originalCalls = calls(method, Opcodes.INVOKEINTERFACE,
        "com/fs/starfarer/api/campaign/LocationAPI", "getEntities", "(Ljava/lang/Class;)Ljava/util/List;");
    int indexedCalls =
        countCalls(method, Opcodes.INVOKESTATIC, HOOKS, STRATEGIC_JUMP_INDEX_HOOK, STRATEGIC_JUMP_INDEX_HOOK_DESC);
    if (originalCalls.size() != 1 || indexedCalls != 0) {
      if (required) throw mismatch("StrategicModule original jump-point source count changed");
      return null;
    }
    MethodInsnNode call = originalCalls.get(0);
    AbstractInsnNode argument = previousMeaningful(call);
    if (!(argument instanceof LdcInsnNode ldc) || !(ldc.cst instanceof Type type)
        || !type.getInternalName().equals(PrepatcherTransformer.JUMP_POINT)) {
      if (required) throw mismatch("StrategicModule getEntities class argument changed");
      return null;
    }
    MethodInsnNode location =
        asMethod(previousMeaningful(argument), "StrategicModule source containing-location getter");
    VarInsnNode fleet =
        requireVar(previousMeaningful(location), Opcodes.ALOAD, "StrategicModule source fleet argument");
    if (fleet.var != argumentLocal(method, 0)
        || !callMatches(location, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/CampaignFleetAPI",
            "getContainingLocation", "()Lcom/fs/starfarer/api/campaign/LocationAPI;")) {
      if (required) throw mismatch("StrategicModule source receiver changed");
      return null;
    }
    requireVar(nextMeaningful(call), Opcodes.ASTORE, "StrategicModule source result local");
    return new StrategicJumpSource(call, argument);
  }

  private static StrategicJumpSource inspectStrategicJumpIndexedSource(MethodNode method, boolean required) {
    List<MethodInsnNode> indexedCalls =
        calls(method, Opcodes.INVOKESTATIC, HOOKS, STRATEGIC_JUMP_INDEX_HOOK, STRATEGIC_JUMP_INDEX_HOOK_DESC);
    int originalCalls = countCalls(method, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/LocationAPI",
        "getEntities", "(Ljava/lang/Class;)Ljava/util/List;");
    if (indexedCalls.size() != 1 || originalCalls != 0) {
      if (required) throw mismatch("StrategicModule indexed jump-point source count changed");
      return null;
    }
    MethodInsnNode call = indexedCalls.get(0);
    VarInsnNode target =
        requireVar(previousMeaningful(call), Opcodes.ALOAD, "StrategicModule indexed destination argument");
    AbstractInsnNode typeArgument = previousMeaningful(target);
    if (!(typeArgument instanceof LdcInsnNode ldc) || !(ldc.cst instanceof Type type)
        || !type.getInternalName().equals(PrepatcherTransformer.JUMP_POINT)) {
      if (required) throw mismatch("StrategicModule indexed class argument changed");
      return null;
    }
    MethodInsnNode location =
        asMethod(previousMeaningful(typeArgument), "StrategicModule indexed containing-location getter");
    VarInsnNode fleet =
        requireVar(previousMeaningful(location), Opcodes.ALOAD, "StrategicModule indexed fleet argument");
    if (target.var != argumentLocal(method, 1) || fleet.var != argumentLocal(method, 0)
        || !callMatches(location, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/CampaignFleetAPI",
            "getContainingLocation", "()Lcom/fs/starfarer/api/campaign/LocationAPI;")) {
      if (required) throw mismatch("StrategicModule indexed source arguments changed");
      return null;
    }
    requireVar(nextMeaningful(call), Opcodes.ASTORE, "StrategicModule indexed source result local");
    return new StrategicJumpSource(call, typeArgument);
  }
}
