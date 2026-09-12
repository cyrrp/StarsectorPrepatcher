package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.List;


import static com.starsector.prepatcher.agent.Allocations.*;
import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Flow.*;
import static com.starsector.prepatcher.agent.Mutations.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.Queries.*;

/**
 * Replaces the forceClearSampleCache call in the H constructor with the throttled hook.
 * The receiver must be the Misc.getHyperspaceTerrainPlugin result, stored in a local and
 * null-guarded, or the site is not provably the owned call.
 */
final class SampleCacheClearThrottle implements StructuredPatch<SampleCacheClearThrottle.Plan> {
  private static final String TERRAIN = "com/fs/starfarer/api/impl/campaign/terrain/HyperspaceTerrainPlugin";
  private static final String HOOK_DESC = "(Ljava/lang/Object;)V";

  /** The proven forceClearSampleCache call site. */
  record Plan(MethodInsnNode call) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode method = requireMethod(node, "<init>", "(Z)V");
    List<MethodInsnNode> original = calls(method, Opcodes.INVOKEVIRTUAL, TERRAIN, "forceClearSampleCache", "()V");
    int hooks = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "forceClearSampleCacheThrottled", HOOK_DESC);
    requireUnpatchedOrAlready("H constructor forceClearSampleCache", original.size(), 1, hooks, 1);

    MethodInsnNode call = only(original, "forceClearSampleCache");
    Frame<SourceValue>[] frames = sourceFrames(node.name, method);
    int pluginLocal =
        requireLoadedLocal(receiverSource(method, call, frames), Opcodes.ALOAD, "hyperspace terrain plugin");
    MethodInsnNode getter = only(calls(method, Opcodes.INVOKESTATIC, "com/fs/starfarer/api/util/Misc",
        "getHyperspaceTerrainPlugin", "()L" + TERRAIN + ";"), "getHyperspaceTerrainPlugin");
    AbstractInsnNode stored = nextMeaningful(getter);
    if (!(stored instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE || store.var != pluginLocal) {
      return Match.incompatible("terrain plugin receiver is not the value returned by Misc.getHyperspaceTerrainPlugin");
    }
    if (!hasNullGuard(method, store, call, pluginLocal)) {
      return Match.incompatible("terrain plugin call is not protected by the expected null guard");
    }
    return Match.plan(new Plan(call));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    makeStatic(plan.call(), HOOKS, "forceClearSampleCacheThrottled", HOOK_DESC);

    PatchReport report = new PatchReport();
    report.add("hyperspace sample-cache clear throttle", 1);
    return report;
  }
}
