package com.starsector.prepatcher.agent.hyperspace;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Markers.*;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;

/**
 * Wraps each getActiveLayers return in HyperspaceTerrainPlugin with the layer filter hook so a
 * no-op terrain layer is skipped instead of rendered. Methods whose return type is neither
 * EnumSet nor Set are ignored, and only ARETURN sites are instrumented.
 */
public final class NoOpTerrainLayerSkip implements StructuredPatch<NoOpTerrainLayerSkip.Plan> {

  /** Every proven ARETURN site plus the filter variant its method's return type selects. */
  record Plan(List<Site> sites) {
  }

  private record Site(MethodNode method, AbstractInsnNode ret, boolean enumSet) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    if (hasPatchMarker(node, "skipNoOpTerrainLayer")) {
      requireCount("noOpLayer postcondition sites", countPatched(node), 1);
      return Match.alreadyApplied("structural marker and complete postcondition are present");
    }
    List<Site> sites = new ArrayList<>();
    for (MethodNode method : node.methods) {
      if (!method.name.equals("getActiveLayers")) continue;
      boolean enumSet = method.desc.endsWith(")Ljava/util/EnumSet;");
      boolean set = method.desc.endsWith(")Ljava/util/Set;");
      if (!enumSet && !set) continue;
      for (AbstractInsnNode n = method.instructions.getFirst(); n != null; n = n.getNext()) {
        if (n.getOpcode() == Opcodes.ARETURN) sites.add(new Site(method, n, enumSet));
      }
    }
    requireCount("noOpLayer structural sites", sites.size(), 1);
    return Match.plan(new Plan(sites));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    for (Site site : plan.sites()) {
      InsnList filter = new InsnList();
      filter.add(new VarInsnNode(Opcodes.ALOAD, 0));
      filter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HyperspacePatches.HOOK,
          site.enumSet() ? "filterLayers" : "filterLayerSet",
          site.enumSet() ? "(Ljava/util/EnumSet;Ljava/lang/Object;)Ljava/util/EnumSet;"
              : "(Ljava/util/Set;Ljava/lang/Object;)Ljava/util/Set;",
          false));
      site.method().instructions.insertBefore(site.ret(), filter);
    }
    PatchReport report = new PatchReport();
    report.add("noOpLayer", plan.sites().size());
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireCount("noOpLayer postcondition sites", countPatched(node), 1);
  }

  private static int countPatched(ClassNode node) {
    return HyperspacePatches.countStaticCalls(node, "filterLayers")
        + HyperspacePatches.countStaticCalls(node, "filterLayerSet");
  }
}
