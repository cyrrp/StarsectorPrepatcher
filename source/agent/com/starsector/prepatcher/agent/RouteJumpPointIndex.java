package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


import static com.starsector.prepatcher.agent.Allocations.*;
import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Flow.*;
import static com.starsector.prepatcher.agent.Mutations.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.CAMPAIGN_ENGINE;
import static com.starsector.prepatcher.agent.Queries.*;

/**
 * Replaces the linear LocationAPI.getJumpPoints scans in the route-following helpers
 * getNextStep (twice) and getLastLegDistance (once) with per-system index lookups, and
 * replaces the getStarSystems scan in getNextStep with the hyperspace-anchor index hook.
 * Each rewritten call receives the already-resolved system/anchor local it compared
 * against, so the index hook answers the same question the scan did.
 */
final class RouteJumpPointIndex implements StructuredPatch<RouteJumpPointIndex.Plan> {
  private static final String JUMP_HOOK_DESC = "(" + LOCATION_DESC + "Ljava/lang/Object;)Ljava/util/List;";
  private static final String SYSTEMS_HOOK_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;";

  /** The proven scan calls plus the compared system/anchor local each hook consumes. */
  record Plan(MethodNode next, List<MethodInsnNode> nextJumps, int[] nextSystemLocals, MethodNode distance,
      MethodInsnNode distanceCall, int distanceSystemLocal, MethodInsnNode systemsCall, int anchorLocal) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode next = requireMethod(node, "getNextStep", "(" + ENTITY_TOKEN_DESC + ")" + ENTITY_TOKEN_DESC);
    MethodNode distance = requireMethod(node, "getLastLegDistance", "(" + ENTITY_TOKEN_DESC + ")F");

    List<MethodInsnNode> nextJumps = calls(next, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/LocationAPI",
        "getJumpPoints", "()Ljava/util/List;");
    List<MethodInsnNode> distanceJumps = calls(distance, Opcodes.INVOKEINTERFACE,
        "com/fs/starfarer/api/campaign/LocationAPI", "getJumpPoints", "()Ljava/util/List;");
    int jumpHooks = countCalls(next, Opcodes.INVOKESTATIC, HOOKS, "routeJumpPointsForSystem", JUMP_HOOK_DESC)
        + countCalls(distance, Opcodes.INVOKESTATIC, HOOKS, "routeJumpPointsForSystem", JUMP_HOOK_DESC);
    List<MethodInsnNode> systems =
        calls(next, Opcodes.INVOKEVIRTUAL, CAMPAIGN_ENGINE, "getStarSystems", "()Ljava/util/List;");
    int systemsHooks = countCalls(next, Opcodes.INVOKESTATIC, HOOKS, "routeSystemsForAnchor", SYSTEMS_HOOK_DESC);

    boolean fullyPatched =
        nextJumps.isEmpty() && distanceJumps.isEmpty() && jumpHooks == 3 && systems.isEmpty() && systemsHooks == 1;
    if (fullyPatched) { return Match.alreadyApplied("three jump hooks and one system hook are already present"); }
    if (jumpHooks != 0 || systemsHooks != 0) {
      return Match.incompatible("mixed/foreign route hook state: jumps=" + jumpHooks + ", systems=" + systemsHooks);
    }
    requireCount("getNextStep jump scans", nextJumps.size(), 2);
    requireCount("getLastLegDistance jump scans", distanceJumps.size(), 1);
    requireCount("getNextStep system scan", systems.size(), 1);

    int[] nextSystemLocals = new int[2];
    for (int i = 0; i < nextJumps.size(); i++) {
      AbstractInsnNode boundary = i + 1 < nextJumps.size() ? nextJumps.get(i + 1) : null;
      nextSystemLocals[i] = findComparedLocationLocal(node.name, next, nextJumps.get(i), boundary);
      requireSystemLocalProducer(next, nextSystemLocals[i], nextJumps.get(i), "getNextStep jump scan " + (i + 1));
    }
    int distanceSystemLocal = findComparedLocationLocal(node.name, distance, distanceJumps.get(0), null);
    requireSystemLocalProducer(distance, distanceSystemLocal, distanceJumps.get(0), "getLastLegDistance jump scan");
    int anchorLocal = findAnchorComparisonLocal(node.name, next, systems.get(0));
    int expectedAnchor = argumentLocal(next, 0);
    if (anchorLocal != expectedAnchor) {
      return Match.incompatible("route anchor local is not the SectorEntityToken argument: found " + anchorLocal
          + ", expected " + expectedAnchor);
    }
    return Match.plan(new Plan(next, nextJumps, nextSystemLocals, distance, distanceJumps.get(0), distanceSystemLocal,
        systems.get(0), anchorLocal));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    for (int i = 0; i < plan.nextJumps().size(); i++) {
      MethodInsnNode call = plan.nextJumps().get(i);
      plan.next().instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, plan.nextSystemLocals()[i]));
      makeStatic(call, HOOKS, "routeJumpPointsForSystem", JUMP_HOOK_DESC);
    }
    plan.distance().instructions.insertBefore(plan.distanceCall(),
        new VarInsnNode(Opcodes.ALOAD, plan.distanceSystemLocal()));
    makeStatic(plan.distanceCall(), HOOKS, "routeJumpPointsForSystem", JUMP_HOOK_DESC);

    plan.next().instructions.insertBefore(plan.systemsCall(), new VarInsnNode(Opcodes.ALOAD, plan.anchorLocal()));
    makeStatic(plan.systemsCall(), HOOKS, "routeSystemsForAnchor", SYSTEMS_HOOK_DESC);

    PatchReport report = new PatchReport();
    report.add("route jump-point system index", 3);
    report.add("route hyperspace-anchor system index", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    int jumpHooks = countCalls(plan.next(), Opcodes.INVOKESTATIC, HOOKS, "routeJumpPointsForSystem", JUMP_HOOK_DESC)
        + countCalls(plan.distance(), Opcodes.INVOKESTATIC, HOOKS, "routeJumpPointsForSystem", JUMP_HOOK_DESC);
    requireCount("route jump-point index postcondition", jumpHooks, 3);
    requireCount("route system-index postcondition",
        countCalls(plan.next(), Opcodes.INVOKESTATIC, HOOKS, "routeSystemsForAnchor", SYSTEMS_HOOK_DESC), 1);
  }

  private static int findComparedLocationLocal(String owner, MethodNode method, MethodInsnNode from,
      AbstractInsnNode boundary) {
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    Set<Integer> locals = new LinkedHashSet<>();
    for (AbstractInsnNode insn = from.getNext(); insn != null && insn != boundary; insn = insn.getNext()) {
      if (!(insn instanceof JumpInsnNode jump)
          || (jump.getOpcode() != Opcodes.IF_ACMPEQ && jump.getOpcode() != Opcodes.IF_ACMPNE))
        continue;
      Frame<SourceValue> frame = frameBefore(method, jump, frames);
      if (frame.getStackSize() < 2) continue;
      SourceValue left = frame.getStack(frame.getStackSize() - 2);
      SourceValue right = frame.getStack(frame.getStackSize() - 1);
      Integer local = null;
      if (sourceIsMethod(left, -1, "com/fs/starfarer/api/campaign/SectorEntityToken", "getContainingLocation",
          "()" + LOCATION_DESC)) {
        local = optionalLoadedLocal(right, Opcodes.ALOAD);
      } else if (sourceIsMethod(right, -1, "com/fs/starfarer/api/campaign/SectorEntityToken", "getContainingLocation",
          "()" + LOCATION_DESC)) {
            local = optionalLoadedLocal(left, Opcodes.ALOAD);
          }
      if (local != null) locals.add(local);
    }
    if (locals.size() != 1) {
      throw mismatch("expected one destination-containing-location comparison local, found " + locals);
    }
    return locals.iterator().next();
  }

  private static int findAnchorComparisonLocal(String owner, MethodNode method, MethodInsnNode from) {
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    Set<Integer> locals = new LinkedHashSet<>();
    int meaningful = 0;
    for (AbstractInsnNode insn = from.getNext(); insn != null && meaningful < 100; insn = insn.getNext()) {
      if (insn.getOpcode() >= 0) meaningful++;
      if (!(insn instanceof JumpInsnNode jump)
          || (jump.getOpcode() != Opcodes.IF_ACMPEQ && jump.getOpcode() != Opcodes.IF_ACMPNE))
        continue;
      Frame<SourceValue> frame = frameBefore(method, jump, frames);
      if (frame.getStackSize() < 2) continue;
      SourceValue left = frame.getStack(frame.getStackSize() - 2);
      SourceValue right = frame.getStack(frame.getStackSize() - 1);
      Integer local = null;
      if (sourceIsMethod(left, -1, "com/fs/starfarer/api/campaign/StarSystemAPI", "getHyperspaceAnchor",
          "()" + ENTITY_TOKEN_DESC)) {
        local = optionalLoadedLocal(right, Opcodes.ALOAD);
      } else if (sourceIsMethod(right, -1, "com/fs/starfarer/api/campaign/StarSystemAPI", "getHyperspaceAnchor",
          "()" + ENTITY_TOKEN_DESC)) {
            local = optionalLoadedLocal(left, Opcodes.ALOAD);
          }
      if (local != null) locals.add(local);
    }
    if (locals.size() != 1) throw mismatch("expected one hyperspace-anchor comparison local, found " + locals);
    return locals.iterator().next();
  }

  private static void requireSystemLocalProducer(MethodNode method, int local, AbstractInsnNode before, String label) {
    int valid = 0;
    for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null && insn != before; insn =
        insn.getNext()) {
      if (!(insn instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE || store.var != local) continue;
      AbstractInsnNode producer = previousMeaningful(store);
      if (producer instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST
          && (cast.desc.equals("com/fs/starfarer/campaign/StarSystem")
              || cast.desc.equals("com/fs/starfarer/api/campaign/StarSystemAPI"))) {
        valid++;
      } else if (producer instanceof MethodInsnNode call
          && Type.getReturnType(call.desc).getDescriptor().equals(STAR_SYSTEM_DESC)) {
            valid++;
          }
    }
    requireCountAtLeast(label + " system-local producers", valid, 1);
  }
}
