package com.starsector.prepatcher.agent.economy;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import java.util.ArrayList;
import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.economy.MarketAdvance.*;
import static com.starsector.prepatcher.agent.economy.MarketScheduler.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.patch.PatchState;

public final class ConstructionQueueMutationBarriers
    implements StructuredPatch<ConstructionQueueMutationBarriers.Plan> {
  private static final String[][] METHODS =
      {{"setItems", "(Ljava/util/List;)V"}, {"addToEnd", "(Ljava/lang/String;I)V"}, {"moveUp", "(Ljava/lang/String;)V"},
          {"moveDown", "(Ljava/lang/String;)V"}, {"moveToFront", "(Ljava/lang/String;)V"},
          {"moveToBack", "(Ljava/lang/String;)V"}, {"removeItem", "(Ljava/lang/String;)V"}};

  private record Site(MethodNode method, AbstractInsnNode first) {
  }

  record Plan(ClassNode node, List<Site> sites) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    String desc = "(" + CONSTRUCTION_QUEUE_DESC + ")V";
    PatchState registration = marketSchedulerRegistrationState(node, MARKET_SCHEDULER_COMPONENT_CONSTRUCTION_QUEUE,
        "ConstructionQueue mutation barriers");
    int present = 0;
    for (String[] spec : METHODS) {
      MethodNode method = requireMethod(node, spec[0], spec[1]);
      int calls = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "flushPendingConstructionQueueBeforeMutation", desc);
      if (calls != 0) present++;
      if (calls > 1) return Match.incompatible("duplicate ConstructionQueue mutation barrier " + spec[0]);
    }
    if (present != 0 || registration != PatchState.ORIGINAL) {
      requireCount("complete ConstructionQueue mutation barrier group", present, METHODS.length);
      requireState("ConstructionQueue capability registration", registration, PatchState.PATCHED);
      return Match.alreadyApplied("ConstructionQueue mutation barriers match");
    }
    List<Site> sites = new ArrayList<>();
    for (String[] spec : METHODS) {
      MethodNode method = requireMethod(node, spec[0], spec[1]);
      AbstractInsnNode first = firstMeaningful(method);
      if (first == null) return Match.incompatible("ConstructionQueue." + spec[0] + " has no code");
      sites.add(new Site(method, first));
    }
    return Match.plan(new Plan(node, sites));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    String desc = "(" + CONSTRUCTION_QUEUE_DESC + ")V";
    for (Site site : plan.sites()) {
      InsnList barrier = new InsnList();
      barrier.add(new VarInsnNode(Opcodes.ALOAD, 0));
      barrier.add(
          new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "flushPendingConstructionQueueBeforeMutation", desc, false));
      site.method().instructions.insertBefore(site.first(), barrier);
    }
    installMarketSchedulerRegistration(plan.node(), MARKET_SCHEDULER_COMPONENT_CONSTRUCTION_QUEUE,
        "ConstructionQueue mutation barriers");
    PatchReport report = new PatchReport();
    report.add("ConstructionQueue mutation barriers", METHODS.length);
    report.add("ConstructionQueue scheduler semantic capability", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireState("ConstructionQueue capability registration", marketSchedulerRegistrationState(node,
        MARKET_SCHEDULER_COMPONENT_CONSTRUCTION_QUEUE, "ConstructionQueue mutation barriers"), PatchState.PATCHED);
  }
}
