package com.starsector.prepatcher.agent.hyperspace;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Markers.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;

/**
 * Replaces {@code new Random(seed)} allocation sites with the seededRandom hook so terrain
 * builders reuse the shared generator instead of allocating. The expected site count is per
 * target class: three in BaseTiledTerrain, one in HyperspaceTerrainPlugin.
 */
public final class TerrainRandomReuse implements StructuredPatch<TerrainRandomReuse.Plan> {
  private final int expected;

  public TerrainRandomReuse(int expected) {
    this.expected = expected;
  }

  /** Every proven NEW/DUP/&lt;init&gt;(J)V triple the mutation rewrites. */
  record Plan(List<Site> sites) {
  }

  private record Site(MethodNode method, TypeInsnNode allocation, AbstractInsnNode dup, MethodInsnNode init) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    if (hasPatchMarker(node, "terrainRandomReuse")) {
      requireCount("random postcondition sites", HyperspacePatches.countStaticCalls(node, "seededRandom"), expected);
      return Match.alreadyApplied("structural marker and complete postcondition are present");
    }
    List<Site> sites = new ArrayList<>();
    for (MethodNode method : node.methods) collectSites(method, sites);
    requireCount("random structural sites", sites.size(), expected);
    return Match.plan(new Plan(sites));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    for (Site site : plan.sites()) {
      site.method().instructions.remove(site.allocation());
      site.method().instructions.remove(site.dup());
      site.method().instructions.set(site.init(), new MethodInsnNode(Opcodes.INVOKESTATIC, HyperspacePatches.HOOK,
          "seededRandom", "(J)Ljava/util/Random;", false));
    }
    PatchReport report = new PatchReport();
    report.add("random", plan.sites().size());
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireCount("random postcondition sites", HyperspacePatches.countStaticCalls(node, "seededRandom"), expected);
  }

  /**
   * Collects the sites the old mutating scanner reached, with the same tolerance and the same
   * resumption rule: a NEW Random without a DUP, a constructor that is not (J)V, and a nested NEW
   * before the constructor all leave the allocation untouched. Once a triple matched, the old
   * scan removed the NEW/DUP pair before advancing; because removal detaches the nodes, the scan
   * resumed only when the node captured as "next" was metadata still in the list — that is, only
   * when metadata separated NEW and DUP. With adjacent nodes the method scan ended there.
   */
  private static void collectSites(MethodNode method, List<Site> sites) {
    for (AbstractInsnNode n = method.instructions.getFirst(); n != null;) {
      AbstractInsnNode next = n.getNext();
      if (!(n instanceof TypeInsnNode type) || type.getOpcode() != Opcodes.NEW
          || !type.desc.equals("java/util/Random")) {
        n = next;
        continue;
      }
      AbstractInsnNode dup = nextMeaningful(type);
      if (dup == null || dup.getOpcode() != Opcodes.DUP) {
        n = next;
        continue;
      }
      Site site = null;
      for (AbstractInsnNode q = dup.getNext(); q != null; q = q.getNext()) {
        if (q instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
            && call.owner.equals("java/util/Random") && call.name.equals("<init>")) {
          if (call.desc.equals("(J)V")) site = new Site(method, type, dup, call);
          break;
        }
        if (q instanceof TypeInsnNode nested && nested.getOpcode() == Opcodes.NEW) break;
      }
      if (site == null) {
        n = next;
      } else {
        sites.add(site);
        n = next == dup ? null : nextMeaningful(dup);
      }
    }
  }
}
