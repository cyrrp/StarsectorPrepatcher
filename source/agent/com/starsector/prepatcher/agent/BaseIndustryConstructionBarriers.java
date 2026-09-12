package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import java.util.ArrayList;
import java.util.List;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.MarketAdvance.*;
import static com.starsector.prepatcher.agent.MarketScheduler.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.*;
import static com.starsector.prepatcher.agent.Queries.*;

final class BaseIndustryConstructionBarriers implements StructuredPatch<BaseIndustryConstructionBarriers.Plan> {
  private static final String[][] METHODS =
      {{"startBuilding", "()V"}, {"startUpgrading", "()V"}, {"cancelUpgrade", "()V"}, {"downgrade", "()V"}};

  private record Site(MethodNode method, AbstractInsnNode first) {
  }

  record Plan(ClassNode node, List<Site> sites) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    PatchState registration = marketSchedulerRegistrationState(node, MARKET_SCHEDULER_COMPONENT_BASE_INDUSTRY,
        "BaseIndustry construction barriers");
    int present = 0;
    for (String[] spec : METHODS) {
      MethodNode method = requireMethod(node, spec[0], spec[1]);
      int calls = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "flushPendingMarketBeforeMutation",
          "(" + MARKET_API_DESC + ")V");
      if (calls != 0) present++;
      if (calls > 1) return Match.incompatible("duplicate BaseIndustry construction barrier " + spec[0]);
    }
    if (present != 0 || registration != PatchState.ORIGINAL) {
      requireCount("complete BaseIndustry construction barrier group", present, METHODS.length);
      requireState("BaseIndustry construction capability registration", registration, PatchState.PATCHED);
      return Match.alreadyApplied("BaseIndustry construction mutation barriers match");
    }
    List<Site> sites = new ArrayList<>();
    for (String[] spec : METHODS) {
      MethodNode method = requireMethod(node, spec[0], spec[1]);
      AbstractInsnNode first = firstMeaningful(method);
      if (first == null) return Match.incompatible("BaseIndustry." + spec[0] + " has no code");
      sites.add(new Site(method, first));
    }
    return Match.plan(new Plan(node, sites));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    for (Site site : plan.sites()) {
      InsnList barrier = new InsnList();
      barrier.add(new VarInsnNode(Opcodes.ALOAD, 0));
      barrier.add(new FieldInsnNode(Opcodes.GETFIELD, plan.node().name, "market", MARKET_API_DESC));
      barrier.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "flushPendingMarketBeforeMutation",
          "(" + MARKET_API_DESC + ")V", false));
      site.method().instructions.insertBefore(site.first(), barrier);
    }
    installMarketSchedulerRegistration(plan.node(), MARKET_SCHEDULER_COMPONENT_BASE_INDUSTRY,
        "BaseIndustry construction barriers");
    PatchReport report = new PatchReport();
    report.add("BaseIndustry construction mutation barriers", METHODS.length);
    report.add("BaseIndustry scheduler semantic capability", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireState("BaseIndustry construction capability registration", marketSchedulerRegistrationState(node,
        MARKET_SCHEDULER_COMPONENT_BASE_INDUSTRY, "BaseIndustry construction barriers"), PatchState.PATCHED);
  }
}
