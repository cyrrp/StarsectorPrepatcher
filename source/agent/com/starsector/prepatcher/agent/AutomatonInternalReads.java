package com.starsector.prepatcher.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Markers.*;

/**
 * HyperspaceTerrainPlugin is the engine-internal consumer of the automaton array. Redirect its
 * two getCells() calls to a direct field hook so they do not mark the public array as escaped.
 * Public/mod calls still go through getCells() and therefore retain vanilla alias safety.
 */
final class AutomatonInternalReads implements StructuredPatch<AutomatonInternalReads.Plan> {
  private static final String OWNER = "com/fs/starfarer/api/impl/campaign/terrain/HyperspaceAutomaton";

  /** The proven getCells() call sites: the first exact match per method, as the old scan found. */
  record Plan(List<Site> sites) {
  }

  private record Site(MethodNode method, MethodInsnNode call) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    if (hasPatchMarker(node, "automatonInternalReads")) {
      requireCount("internalCells postcondition sites", countPatched(node), 2);
      return Match.alreadyApplied("structural marker and complete postcondition are present");
    }
    List<Site> sites = new ArrayList<>();
    for (MethodNode method : node.methods) {
      for (AbstractInsnNode n = method.instructions.getFirst(); n != null; n = n.getNext()) {
        if (!(n instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEVIRTUAL
            || !call.owner.equals(OWNER) || !call.name.equals("getCells") || !call.desc.equals("()[[I"))
          continue;
        // Replacing the call detached it, so the old scan stopped after the first match in a
        // method; matching that, only the first exact site per method enters the plan.
        sites.add(new Site(method, call));
        break;
      }
    }
    requireCount("internalCells structural sites", sites.size(), 2);
    return Match.plan(new Plan(sites));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    for (Site site : plan.sites()) {
      site.method().instructions.insertBefore(site.call(), new LdcInsnNode(OWNER.replace('/', '.')));
      site.method().instructions.set(site.call(), new MethodInsnNode(Opcodes.INVOKESTATIC, HyperspacePatches.HOOK,
          "automatonCellsInternal", "(Ljava/lang/Object;Ljava/lang/String;)[[I", false));
    }
    PatchReport report = new PatchReport();
    report.add("internalCells", plan.sites().size());
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireCount("internalCells postcondition sites", countPatched(node), 2);
  }

  private static int countPatched(ClassNode node) {
    return HyperspacePatches.countStaticCalls(node, "automatonCellsInternal");
  }
}
