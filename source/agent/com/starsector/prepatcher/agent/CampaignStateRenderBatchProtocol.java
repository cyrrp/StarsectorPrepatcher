package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;
import java.util.List;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Flow.*;
import static com.starsector.prepatcher.agent.MarketScheduler.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.*;
import static com.starsector.prepatcher.agent.Queries.*;

final class CampaignStateRenderBatchProtocol implements StructuredPatch<CampaignStateRenderBatchProtocol.Plan> {
  record Plan(ClassNode node) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode advance = requireMethod(node, "advance", "(FLcom/fs/starfarer/util/A/new;)V");
    List<MethodInsnNode> setters =
        calls(advance, Opcodes.INVOKEVIRTUAL, CAMPAIGN_ENGINE, "setFastForwardIteration", "(Z)V");
    List<MethodInsnNode> engineAdvances =
        calls(advance, Opcodes.INVOKEVIRTUAL, CAMPAIGN_ENGINE, "advance", "(FLcom/fs/starfarer/util/A/new;)V");
    requireCount("CampaignState render-batch setter calls", setters.size(), 3);
    requireCount("CampaignState simulation advance calls", engineAdvances.size(), 1);

    MethodInsnNode initialFalse = setters.get(0);
    MethodInsnNode continuationTrue = setters.get(1);
    MethodInsnNode finalFalse = setters.get(2);
    MethodInsnNode engineAdvance = engineAdvances.get(0);
    Frame<SourceValue>[] frames = sourceFrames(node.name, advance);
    if (!sourceIsIntegerConstant(argumentSource(advance, initialFalse, frames, 0), 0)
        || !sourceIsIntegerConstant(argumentSource(advance, continuationTrue, frames, 0), 1)
        || !sourceIsIntegerConstant(argumentSource(advance, finalFalse, frames, 0), 0)) {
      return Match.incompatible("CampaignState render-batch boolean protocol changed");
    }

    int initialIndex = advance.instructions.indexOf(initialFalse);
    int advanceIndex = advance.instructions.indexOf(engineAdvance);
    int continuationIndex = advance.instructions.indexOf(continuationTrue);
    int finalIndex = advance.instructions.indexOf(finalFalse);
    if (!(initialIndex < advanceIndex && advanceIndex < continuationIndex && continuationIndex < finalIndex)) {
      return Match.incompatible("CampaignState render-batch call order changed");
    }

    boolean loopBack = false;
    for (AbstractInsnNode insn : advance.instructions.toArray()) {
      if (!(insn instanceof JumpInsnNode jump)) continue;
      int sourceIndex = advance.instructions.indexOf(jump);
      int targetIndex = advance.instructions.indexOf(jump.label);
      if (sourceIndex > continuationIndex && sourceIndex < finalIndex && targetIndex > initialIndex
          && targetIndex <= advanceIndex) {
        loopBack = true;
        break;
      }
    }
    if (!loopBack) {
      return Match.incompatible(
          "CampaignState simulation advance is no longer enclosed by the" + " false/true render-batch loop protocol");
    }

    PatchState registration = marketSchedulerRegistrationState(node, MARKET_SCHEDULER_COMPONENT_BATCH_PROTOCOL,
        "CampaignState market scheduler batch protocol");
    if (registration == PatchState.PATCHED) {
      return Match.alreadyApplied("CampaignState render-batch protocol and component registration match");
    }
    return Match.plan(new Plan(node));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    installMarketSchedulerRegistration(plan.node(), MARKET_SCHEDULER_COMPONENT_BATCH_PROTOCOL,
        "CampaignState market scheduler batch protocol");
    PatchReport report = new PatchReport();
    report.add("validated CampaignState false/tick-true/final-false batch protocol", 1);
    report.add("market scheduler batch-protocol component registration", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    if (marketSchedulerRegistrationState(node, MARKET_SCHEDULER_COMPONENT_BATCH_PROTOCOL,
        "CampaignState market scheduler batch protocol") != PatchState.PATCHED) {
      throw mismatch("CampaignState market scheduler protocol registration is absent");
    }
  }
}
