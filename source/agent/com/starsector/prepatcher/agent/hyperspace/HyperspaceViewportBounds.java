package com.starsector.prepatcher.agent.hyperspace;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Markers.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;

/**
 * Atomically corrects the two coupled defects in BaseTiledTerrain's vertical viewport bounds:
 * yEnd must use visible height, and the yEnd clamp must use the inner tile-array dimension.
 *
 * <p>The two changes share one semantic expression and therefore one transformation surface. All
 * expected methods are analyzed before either instruction is changed. A partial or ambiguous input
 * is refused by {@link #match} and leaves the ClassNode untouched.
 */
public final class HyperspaceViewportBounds implements StructuredPatch<HyperspaceViewportBounds.Plan> {

  /** The two proven candidates: the load to retarget and the clamp length to replace, per method. */
  record Plan(List<Candidate> candidates) {
  }

  private record Candidate(MethodNode method, int heightLocal, VarInsnNode verticalRangeLoad,
      InsnNode verticalClampLength) {
  }

  private record ViewportLocals(int widthLocal, int heightLocal, AbstractInsnNode afterHeight) {
  }

  private record ClampShape(List<InsnNode> direct, int nested) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    if (hasPatchMarker(node, "hyperspaceViewportBounds")) {
      requireCount("viewportBounds postcondition sites", countPatchedViewportBounds(node), 2);
      return Match.alreadyApplied("structural marker and complete postcondition are present");
    }
    List<Candidate> candidates = new ArrayList<>();
    for (MethodNode method : node.methods) {
      Candidate candidate = originalCandidate(method);
      if (candidate != null) candidates.add(candidate);
    }
    // The surface is atomic: a partial or ambiguous shape collapses to "found 0" rather than
    // reporting a partial count, matching the refusal the mutating scanner produced.
    requireCount("viewportBounds structural sites", candidates.size() == 2 ? 2 : 0, 2);
    return Match.plan(new Plan(candidates));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    for (Candidate candidate : plan.candidates()) {
      candidate.verticalRangeLoad().var = candidate.heightLocal();
      InsnList replacement = new InsnList();
      replacement.add(new InsnNode(Opcodes.ICONST_0));
      replacement.add(new InsnNode(Opcodes.AALOAD));
      replacement.add(new InsnNode(Opcodes.ARRAYLENGTH));
      candidate.method().instructions.insertBefore(candidate.verticalClampLength(), replacement);
      candidate.method().instructions.remove(candidate.verticalClampLength());
    }
    PatchReport report = new PatchReport();
    report.add("viewportBounds", plan.candidates().size());
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireCount("viewportBounds postcondition sites", countPatchedViewportBounds(node), 2);
  }

  public static int countPatchedViewportBounds(ClassNode node) {
    int total = 0;
    for (MethodNode method : node.methods) if (hasPatchedViewportBounds(method)) total++;
    return total;
  }

  private static Candidate originalCandidate(MethodNode method) {
    ViewportLocals locals = viewportLocals(method);
    if (locals == null) return null;

    List<VarInsnNode> widthLoads = new ArrayList<>();
    int heightLoads = 0;
    for (AbstractInsnNode n = locals.afterHeight().getNext(); n != null; n = n.getNext()) {
      if (!(n instanceof VarInsnNode load) || load.getOpcode() != Opcodes.FLOAD) continue;
      if (load.var == locals.widthLocal()) widthLoads.add(load);
      else if (load.var == locals.heightLocal()) heightLoads++;
    }
    if (widthLoads.size() != 3 || heightLoads != 1) return null;

    ClampShape clamp = clampShape(method);
    if (clamp.direct().size() != 2 || clamp.nested() != 0) return null;
    return new Candidate(method, locals.heightLocal(), widthLoads.get(2), clamp.direct().get(1));
  }

  private static boolean hasPatchedViewportBounds(MethodNode method) {
    ViewportLocals locals = viewportLocals(method);
    if (locals == null) return false;
    int widthLoads = 0, heightLoads = 0;
    for (AbstractInsnNode n = locals.afterHeight().getNext(); n != null; n = n.getNext()) {
      if (!(n instanceof VarInsnNode load) || load.getOpcode() != Opcodes.FLOAD) continue;
      if (load.var == locals.widthLocal()) widthLoads++;
      else if (load.var == locals.heightLocal()) heightLoads++;
    }
    if (widthLoads != 2 || heightLoads != 2) return false;
    ClampShape clamp = clampShape(method);
    return clamp.direct().size() == 1 && clamp.nested() == 1;
  }

  private static ViewportLocals viewportLocals(MethodNode method) {
    int width = -1, height = -1;
    AbstractInsnNode afterHeight = null;
    int widthCalls = 0, heightCalls = 0;
    for (AbstractInsnNode n = method.instructions.getFirst(); n != null; n = n.getNext()) {
      if (!(n instanceof MethodInsnNode call)) continue;
      if (!call.desc.equals("()F")) continue;
      AbstractInsnNode store = nextMeaningful(call);
      if (!(store instanceof VarInsnNode local) || local.getOpcode() != Opcodes.FSTORE) continue;
      if (call.name.equals("getVisibleWidth")) {
        widthCalls++;
        width = local.var;
      } else if (call.name.equals("getVisibleHeight")) {
        heightCalls++;
        height = local.var;
        afterHeight = store;
      }
    }
    if (widthCalls != 1 || heightCalls != 1 || width < 0 || height < 0 || afterHeight == null) return null;
    return new ViewportLocals(width, height, afterHeight);
  }

  private static ClampShape clampShape(MethodNode method) {
    List<InsnNode> direct = new ArrayList<>();
    int nested = 0;
    for (AbstractInsnNode n = method.instructions.getFirst(); n != null; n = n.getNext()) {
      if (!(n instanceof InsnNode length) || length.getOpcode() != Opcodes.ARRAYLENGTH) continue;
      AbstractInsnNode next1 = nextMeaningful(length);
      AbstractInsnNode next2 = next1 == null ? null : nextMeaningful(next1);
      AbstractInsnNode next3 = next2 == null ? null : nextMeaningful(next2);
      if (next1 == null || next1.getOpcode() != Opcodes.I2F || next2 == null || next2.getOpcode() != Opcodes.FCMPL
          || !(next3 instanceof JumpInsnNode))
        continue;
      AbstractInsnNode previous = previousMeaningful(length);
      if (previous instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
          && field.desc.startsWith("[[")) {
        direct.add(length);
        continue;
      }
      if (previous == null || previous.getOpcode() != Opcodes.AALOAD) continue;
      AbstractInsnNode zero = previousMeaningful(previous), fieldInsn = previousMeaningful(zero);
      if (zero != null && zero.getOpcode() == Opcodes.ICONST_0 && fieldInsn instanceof FieldInsnNode field
          && field.getOpcode() == Opcodes.GETFIELD && field.desc.startsWith("[["))
        nested++;
    }
    return new ClampShape(direct, nested);
  }
}
