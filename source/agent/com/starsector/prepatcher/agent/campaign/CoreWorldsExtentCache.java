package com.starsector.prepatcher.agent.campaign;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Mutations.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;

/**
 * Replaces the terminal vanilla core-worlds recomputation only after the
 * SectorAPI local and adjacent RouteManager/RETURN protocol are proven.
 */
public final class CoreWorldsExtentCache implements StructuredPatch<CoreWorldsExtentCache.Plan> {
  record Plan(MethodNode advance, int sectorLocal, MethodInsnNode original) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode advance = requireMethod(node, "advance", "(F)V");
    String sectorDesc = "Lcom/fs/starfarer/api/campaign/SectorAPI;";
    MethodInsnNode getSector =
        only(calls(advance, Opcodes.INVOKESTATIC, "com/fs/starfarer/api/Global", "getSector", "()" + sectorDesc),
            "CoreScript.advance Global.getSector");
    AbstractInsnNode stored = nextMeaningful(getSector);
    if (!(stored instanceof VarInsnNode sectorStore) || sectorStore.getOpcode() != Opcodes.ASTORE) {
      throw mismatch("CoreScript.advance sector result is not stored in an object local");
    }
    int sectorLocal = sectorStore.var;
    requireCount("CoreScript.advance sector local stores", countStores(advance, sectorLocal), 1);
    requireSectorReceiver(advance, sectorLocal, "isPaused", "()Z");
    requireSectorReceiver(advance, sectorLocal, "getClock", "()Lcom/fs/starfarer/api/campaign/CampaignClockAPI;");

    List<MethodInsnNode> originals =
        calls(advance, Opcodes.INVOKESTATIC, "com/fs/starfarer/api/util/Misc", "computeCoreWorldsExtent", "()V");
    List<MethodInsnNode> hooks =
        calls(advance, Opcodes.INVOKESTATIC, CORE_WORLDS_RUNTIME, "update", "(" + sectorDesc + ")V");

    if (originals.isEmpty() && hooks.size() == 1) {
      validateCoreWorldsHookShape(advance, hooks.get(0), sectorLocal);
      return Match.alreadyApplied("CoreScript core-worlds hook postcondition matches");
    }
    if (originals.size() != 1 || !hooks.isEmpty()) {
      return Match.incompatible("CoreScript core-worlds call state: expected original=1, hook=0; " + "found original="
          + originals.size() + ", hook=" + hooks.size());
    }

    MethodInsnNode original = originals.get(0);
    MethodInsnNode routeAdvance = asMethod(previousMeaningful(original), "CoreScript core-worlds predecessor");
    requireCall(routeAdvance, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/api/impl/campaign/fleets/RouteManager",
        "advance", "(F)V", "CoreScript RouteManager.advance predecessor");
    AbstractInsnNode after = nextMeaningful(original);
    if (after == null || after.getOpcode() != Opcodes.RETURN) {
      throw mismatch("CoreScript core-worlds call is not the terminal action");
    }
    return Match.plan(new Plan(advance, sectorLocal, original));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    String sectorDesc = "Lcom/fs/starfarer/api/campaign/SectorAPI;";
    plan.advance().instructions.insertBefore(plan.original(), new VarInsnNode(Opcodes.ALOAD, plan.sectorLocal()));
    makeStatic(plan.original(), CORE_WORLDS_RUNTIME, "update", "(" + sectorDesc + ")V");
    PatchReport report = new PatchReport();
    report.add("coreWorldsExtentCache", 1);
    return report;
  }

  private static void requireSectorReceiver(MethodNode method, int sectorLocal, String name, String desc) {
    MethodInsnNode call =
        only(calls(method, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/SectorAPI", name, desc),
            "CoreScript.advance SectorAPI." + name);
    AbstractInsnNode receiver = previousMeaningful(call);
    if (!(receiver instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != sectorLocal) {
      throw mismatch("CoreScript.advance SectorAPI." + name + " does not use the stored sector local");
    }
  }

  private static void validateCoreWorldsHookShape(MethodNode method, MethodInsnNode hook, int sectorLocal) {
    AbstractInsnNode receiver = previousMeaningful(hook);
    if (!(receiver instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != sectorLocal) {
      throw mismatch("CoreScript core-worlds hook does not load the proven sector local");
    }
    MethodInsnNode routeAdvance = asMethod(previousMeaningful(receiver), "CoreScript core-worlds hook predecessor");
    requireCall(routeAdvance, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/api/impl/campaign/fleets/RouteManager",
        "advance", "(F)V", "CoreScript RouteManager.advance before hook");
    AbstractInsnNode after = nextMeaningful(hook);
    if (after == null || after.getOpcode() != Opcodes.RETURN) {
      throw mismatch("CoreScript core-worlds hook is not the terminal action");
    }
    requireCount("CoreScript vanilla core-worlds calls after patch",
        countCalls(method, Opcodes.INVOKESTATIC, "com/fs/starfarer/api/util/Misc", "computeCoreWorldsExtent", "()V"),
        0);
  }
}
