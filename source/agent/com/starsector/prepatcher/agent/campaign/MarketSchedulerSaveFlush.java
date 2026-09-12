package com.starsector.prepatcher.agent.campaign;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.economy.MarketScheduler.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.patch.PatchState;

final class MarketSchedulerSaveFlush implements StructuredPatch<MarketSchedulerSaveFlush.Plan> {
  record Plan(ClassNode node, MethodNode method, MethodInsnNode maintenanceCall) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode method =
        requireMethod(node, "o00000", "(Lcom/fs/starfarer/campaign/CampaignEngine$o;JZ)Ljava/lang/String;");
    PatchState registration =
        marketSchedulerRegistrationState(node, MARKET_SCHEDULER_COMPONENT_SAVE, "CampaignGameManager market scheduler");
    int hooks = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "flushMarketSchedulerBeforeSave", "()V");
    if (hooks == 1 && registration == PatchState.PATCHED) {
      MethodInsnNode call = only(calls(method, Opcodes.INVOKESTATIC, HOOKS, "flushMarketSchedulerBeforeSave", "()V"),
          "market scheduler save flush");
      AbstractInsnNode previous = previousMeaningful(call);
      if (previous instanceof MethodInsnNode maintenance
          && callMatches(maintenance, Opcodes.INVOKESTATIC, HOOKS, "runCacheMaintenance", "(Z)V")) {
        AbstractInsnNode flag = previousMeaningful(maintenance);
        if (flag == null || flag.getOpcode() != Opcodes.ICONST_1) {
          return Match.incompatible("market scheduler save flush follows malformed maintenance barrier");
        }
      } else if (previous != null) {
        return Match.incompatible("market scheduler save flush is not the first barrier after optional maintenance");
      }
      return Match.alreadyApplied("market scheduler save component postcondition matches");
    }
    if (hooks != 0 || registration == PatchState.PATCHED) {
      return Match.incompatible("market scheduler save component is partially patched");
    }

    List<MethodInsnNode> maintenance = calls(method, Opcodes.INVOKESTATIC, HOOKS, "runCacheMaintenance", "(Z)V");
    MethodInsnNode anchor = null;
    if (!maintenance.isEmpty()) {
      requireCount("forced cache maintenance hooks before scheduler flush", maintenance.size(), 1);
      anchor = maintenance.get(0);
    }
    return Match.plan(new Plan(node, method, anchor));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    MethodInsnNode flush =
        new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "flushMarketSchedulerBeforeSave", "()V", false);
    if (plan.maintenanceCall() == null) {
      plan.method().instructions.insert(flush);
    } else {
      plan.method().instructions.insert(plan.maintenanceCall(), flush);
    }
    installMarketSchedulerRegistration(plan.node(), MARKET_SCHEDULER_COMPONENT_SAVE,
        "CampaignGameManager market scheduler");
    PatchReport report = new PatchReport();
    report.add("pending market scheduler time flushed before save", 1);
    report.add("market scheduler save component registration", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireCount("market scheduler save flush hooks",
        countCalls(plan.method(), Opcodes.INVOKESTATIC, HOOKS, "flushMarketSchedulerBeforeSave", "()V"), 1);
    if (marketSchedulerRegistrationState(node, MARKET_SCHEDULER_COMPONENT_SAVE,
        "CampaignGameManager market scheduler") != PatchState.PATCHED) {
      throw mismatch("market scheduler save component registration is absent");
    }
  }
}
