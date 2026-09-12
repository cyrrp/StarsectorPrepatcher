package com.starsector.prepatcher.agent.hyperspace;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Markers.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;

/**
 * Replaces the per-frame {@code new ArrayList} scratch collection in the starfield renderer with a
 * borrowed list that the removeAll call site releases back to the pool. The use scan is
 * deliberately tolerant: the borrowed local may be read by add/isEmpty/size/iterator calls and by
 * any non-call consumer, but it must see exactly one removeAll(Collection) call and no read of
 * the slot may follow it. A candidate that fails that shape is skipped, not fatal.
 */
public final class StarfieldCleanupBuffers implements StructuredPatch<StarfieldCleanupBuffers.Plan> {

  /** Every proven borrow/release pair: allocation triple plus its removeAll call. */
  record Plan(List<Candidate> candidates) {
  }

  private record Candidate(MethodNode method, TypeInsnNode allocation, InsnNode dup, MethodInsnNode init,
      MethodInsnNode removeAll) {
  }

  private record Shape(TypeInsnNode allocation, InsnNode dup, MethodInsnNode init, int slot) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    if (hasPatchMarker(node, "starfieldCleanupBuffers")) {
      requireCount("cleanup postcondition sites", countPatched(node), 1);
      return Match.alreadyApplied("structural marker and complete postcondition are present");
    }
    List<Candidate> candidates = new ArrayList<>();
    for (MethodNode method : node.methods) {
      List<Shape> shapes = new ArrayList<>();
      for (AbstractInsnNode n = method.instructions.getFirst(); n != null; n = n.getNext()) {
        if (!(n instanceof TypeInsnNode type) || type.getOpcode() != Opcodes.NEW
            || !type.desc.equals("java/util/ArrayList"))
          continue;
        AbstractInsnNode dup = nextMeaningful(type);
        if (dup == null || dup.getOpcode() != Opcodes.DUP) continue;
        AbstractInsnNode init = nextMeaningful(dup);
        if (!(init instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
            || !call.owner.equals("java/util/ArrayList") || !call.name.equals("<init>") || !call.desc.equals("()V"))
          continue;
        AbstractInsnNode store = nextMeaningful(init);
        if (store instanceof VarInsnNode local && local.getOpcode() == Opcodes.ASTORE)
          shapes.add(new Shape(type, (InsnNode) dup, call, local.var));
      }
      for (Shape shape : shapes) {
        MethodInsnNode removeAll = removableListSite(method, shape);
        if (removeAll != null)
          candidates.add(new Candidate(method, shape.allocation(), shape.dup(), shape.init(), removeAll));
      }
    }
    requireCount("cleanup structural sites", candidates.size(), 1);
    return Match.plan(new Plan(candidates));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    for (Candidate candidate : plan.candidates()) {
      candidate.method().instructions.remove(candidate.allocation());
      candidate.method().instructions.remove(candidate.dup());
      candidate.method().instructions.set(candidate.init(), new MethodInsnNode(Opcodes.INVOKESTATIC,
          HyperspacePatches.HOOK, "borrowArrayList", "()Ljava/util/ArrayList;", false));
      candidate.method().instructions.set(candidate.removeAll(), new MethodInsnNode(Opcodes.INVOKESTATIC,
          HyperspacePatches.HOOK, "removeAllAndRelease", "(Ljava/util/Collection;Ljava/util/Collection;)Z", false));
    }
    PatchReport report = new PatchReport();
    report.add("cleanup", plan.candidates().size());
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireCount("cleanup postcondition sites", countPatched(node), 1);
  }

  /**
   * The use scan for one allocation shape: reads of the stored slot may feed add/isEmpty/size/
   * iterator calls or non-call consumers; the first removeAll(Collection)Z call ends the scan and
   * no later read of the slot is permitted. Returns the removeAll call, or null when the shape is
   * disqualified.
   */
  private static MethodInsnNode removableListSite(MethodNode method, Shape shape) {
    MethodInsnNode remove = null;
    boolean bad = false;
    for (AbstractInsnNode n = shape.init().getNext(); n != null; n = n.getNext()) {
      if (n instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == shape.slot()) {
        AbstractInsnNode nx = nextMeaningful(n);
        if (nx instanceof MethodInsnNode call) {
          if (call.name.equals("removeAll") && call.desc.equals("(Ljava/util/Collection;)Z")) {
            remove = call;
          } else if (!(call.name.equals("add") || call.name.equals("isEmpty") || call.name.equals("size")
              || call.name.equals("iterator"))) {
                bad = true;
                break;
              }
        }
      }
      if (remove != null) {
        for (AbstractInsnNode q = remove.getNext(); q != null; q = q.getNext())
          if (q instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == shape.slot()) {
            bad = true;
            break;
          }
        break;
      }
    }
    return remove == null || bad ? null : remove;
  }

  private static int countPatched(ClassNode node) {
    return HyperspacePatches.countStaticCalls(node, "borrowArrayList") == 1
        && HyperspacePatches.countStaticCalls(node, "removeAllAndRelease") == 1 ? 1 : 0;
  }
}
