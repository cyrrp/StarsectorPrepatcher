package com.starsector.prepatcher.agent.mapintel;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.Allocations.*;
import static com.starsector.prepatcher.agent.bytecode.ClassIO.*;
import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.economy.EconomyGroupIndex.*;
import static com.starsector.prepatcher.agent.bytecode.Flow.*;
import static com.starsector.prepatcher.agent.bytecode.Markers.*;
import static com.starsector.prepatcher.agent.economy.MarketAdvance.*;
import static com.starsector.prepatcher.agent.economy.MarketScheduler.*;
import static com.starsector.prepatcher.agent.bytecode.Mutations.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.bytecode.OwnedTemplates.*;
import static com.starsector.prepatcher.agent.economy.PersistentState.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;


/**
 * Replaces the fixed 2000f global-grid constants with a scale-aware hook, so that a large
 * sector no longer draws its grid at the density chosen for a small one.
 */
public final class GridLineCap implements StructuredPatch<GridLineCap.Plan> {
  /** The five proven constants, in the method that owns them. */
  record Plan(MethodNode method, List<LdcInsnNode> constants) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode method = requireMethod(node, "null", "(F)V");
    int hooks = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "gridSpacing", "()F");
    List<LdcInsnNode> bounds = new ArrayList<>();
    List<LdcInsnNode> step = new ArrayList<>();
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!(insn instanceof LdcInsnNode ldc) || !isFloat(ldc, 2000f)) continue;
      AbstractInsnNode n1 = nextMeaningful(ldc);
      AbstractInsnNode n2 = nextMeaningful(n1);
      AbstractInsnNode n3 = nextMeaningful(n2);
      AbstractInsnNode n4 = nextMeaningful(n3);
      AbstractInsnNode prev = previousMeaningful(ldc);
      if (prev != null && prev.getOpcode() == Opcodes.FLOAD && n1 != null && n1.getOpcode() == Opcodes.FDIV
          && n2 != null && n2.getOpcode() == Opcodes.F2I && n3 != null && n3.getOpcode() == Opcodes.ISTORE) {
        bounds.add(ldc);
      } else if (n1 instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 0
          && n2 instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD && field.owner.equals(node.name)
          && field.desc.equals("F") && n3 != null && n3.getOpcode() == Opcodes.FMUL && n4 != null
          && n4.getOpcode() == Opcodes.FSTORE) {
            step.add(ldc);
          }
    }
    if (bounds.isEmpty() && step.isEmpty() && hooks == 5) {
      return Match.alreadyApplied("five structurally verified gridSpacing hooks are already present");
    }
    if (hooks != 0) { return Match.incompatible("mixed/foreign gridSpacing hook state: " + hooks); }
    requireCount("global-grid bound constants", bounds.size(), 4);
    requireCount("global-grid step constant", step.size(), 1);
    List<MethodInsnNode> starscape = calls(method, Opcodes.INVOKEVIRTUAL, node.name, "isStarscapeMode", "()Z");
    requireCount("grid starscape guard", starscape.size(), 1);
    int guardIndex = method.instructions.indexOf(starscape.get(0));
    List<LdcInsnNode> constants = concat(bounds, step);
    for (LdcInsnNode ldc : constants) {
      if (method.instructions.indexOf(ldc) <= guardIndex) {
        return Match.incompatible("a selected grid constant is not dominated by the starscape branch marker");
      }
    }
    return Match.plan(new Plan(method, constants));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    for (LdcInsnNode ldc : plan.constants()) {
      plan.method().instructions.set(ldc, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "gridSpacing", "()F", false));
    }
    PatchReport report = new PatchReport();
    report.add("large-sector grid line cap", 5);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireCount("gridSpacing postcondition",
        countCalls(plan.method(), Opcodes.INVOKESTATIC, HOOKS, "gridSpacing", "()F"), 5);
  }
}

// ---------------------------------------------------------------------
// Campaign and route hot paths
// ---------------------------------------------------------------------


// ---------------------------------------------------------------------
// StrategicModule: destination-first safe jump-point filtering
// ---------------------------------------------------------------------


// ---------------------------------------------------------------------
// StrategicModule: destination-location jump-point index
// ---------------------------------------------------------------------


// ---------------------------------------------------------------------
// Topology mutation hooks for the incremental strategic jump index
// ---------------------------------------------------------------------

