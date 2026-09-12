package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;


import static com.starsector.prepatcher.agent.Allocations.*;
import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Mutations.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.Queries.*;

/**
 * Replaces every IntelInfoPlugin.getMapLocation call in the target class with the cached
 * map-location hook. The expected site count is part of the contract: the A panel owns a
 * single callback, EventsPanel owns seven.
 */
final class IntelMapLocationCache implements StructuredPatch<IntelMapLocationCache.Plan> {
  private static final String HOOK_DESC = "(" + INTEL_DESC + MAP_API_DESC + ")" + ENTITY_TOKEN_DESC;
  private final int expected;

  IntelMapLocationCache(int expected) {
    this.expected = expected;
  }

  /** The proven original call sites. */
  record Plan(List<MethodInsnNode> originals) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    String originalDesc = "(" + MAP_API_DESC + ")" + ENTITY_TOKEN_DESC;
    List<MethodInsnNode> originals = new ArrayList<>();
    int hooks = 0;
    for (MethodNode method : node.methods) {
      originals.addAll(calls(method, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/comm/IntelInfoPlugin",
          "getMapLocation", originalDesc));
      hooks += countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "getMapLocationCached", HOOK_DESC);
    }
    requireUnpatchedOrAlready(node.name + " getMapLocation", originals.size(), expected, hooks, expected);
    for (MethodInsnNode call : originals) {
      if (nextMeaningful(call) != null && nextMeaningful(call).getOpcode() == Opcodes.POP) {
        return Match.incompatible("getMapLocation result is discarded at a candidate site");
      }
    }
    return Match.plan(new Plan(originals));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    for (MethodInsnNode call : plan.originals()) {
      makeStatic(call, HOOKS, "getMapLocationCached", HOOK_DESC);
    }
    PatchReport report = new PatchReport();
    report.add("Intel map-location callback cache", plan.originals().size());
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    int hooks = 0;
    for (MethodNode method : node.methods) {
      hooks += countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "getMapLocationCached", HOOK_DESC);
    }
    requireCount(node.name + " getMapLocation hook postcondition", hooks, expected);
  }
}
