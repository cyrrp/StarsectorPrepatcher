package com.starsector.prepatcher.agent.startup;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

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
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.patch.StructuredPatch;

public final class SaveLoadProgressThrottle implements StructuredPatch<SaveLoadProgressThrottle.Plan> {
  private final String methodName;

  public SaveLoadProgressThrottle(String methodName) {
    this.methodName = methodName;
  }

  record Plan(MethodNode method, LdcInsnNode constant) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode method = requireMethod(node, methodName, "(Z)V");
    List<LdcInsnNode> originals = new ArrayList<>();
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof LdcInsnNode ldc && isFloat(ldc, 1f / 60f)) originals.add(ldc);
    }
    int hooks = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "saveLoadProgressMinIntervalSeconds", "()F");
    if (originals.isEmpty() && hooks == 1) {
      return Match.alreadyApplied("save/load progress throttle postcondition matches");
    }
    requireCount("save/load progress 60 Hz constants", originals.size(), 1);
    requireCount("save/load progress hooks", hooks, 0);
    return Match.plan(new Plan(method, originals.get(0)));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    plan.method().instructions.set(plan.constant(),
        new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "saveLoadProgressMinIntervalSeconds", "()F", false));
    PatchReport report = new PatchReport();
    report.add("progressInterval", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireCount("save/load progress hooks",
        countCalls(plan.method(), Opcodes.INVOKESTATIC, HOOKS, "saveLoadProgressMinIntervalSeconds", "()F"), 1);
  }
}
