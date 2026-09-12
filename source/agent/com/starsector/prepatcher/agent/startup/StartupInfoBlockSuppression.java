package com.starsector.prepatcher.agent.startup;

import org.objectweb.asm.tree.ClassNode;

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

public final class StartupInfoBlockSuppression implements StructuredPatch<StartupInfoBlockSuppression.Plan> {
  private final String literal;
  private final int expected;
  private final int category;
  private final boolean emitRuntimeCounter;
  private final String reportLabel;

  public StartupInfoBlockSuppression(String literal, int expected, int category, boolean emitRuntimeCounter,
      String reportLabel) {
    this.literal = literal;
    this.expected = expected;
    this.category = category;
    this.emitRuntimeCounter = emitRuntimeCounter;
    this.reportLabel = reportLabel;
  }

  record Plan(List<StartupInfoBlocks.LogBlock> blocks) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    List<StartupInfoBlocks.LogBlock> blocks = StartupInfoBlocks.startupInfoBlockPlans(node, literal);
    int hooks = countCategoryHooks(node, "startupLogSuppressed", "(I)V", category);
    int expectedHooks = emitRuntimeCounter ? expected : 0;
    if (blocks.isEmpty() && hooks == expectedHooks) {
      return Match.alreadyApplied("startup INFO suppression postcondition matches for " + literal);
    }
    requireCount("startup INFO blocks for " + literal, blocks.size(), expected);
    requireCount("startup suppression hooks for category " + category, hooks, 0);
    return Match.plan(new Plan(blocks));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    StartupInfoBlocks.suppressStartupInfoBlocks(plan.blocks(), category, emitRuntimeCounter);
    PatchReport report = new PatchReport();
    report.add(reportLabel, plan.blocks().size());
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    int expectedHooks = emitRuntimeCounter ? expected : 0;
    requireCount("startup INFO blocks for " + literal, countStringConstants(node, literal), 0);
    requireCount("startup suppression hooks for category " + category,
        countCategoryHooks(node, "startupLogSuppressed", "(I)V", category), expectedHooks);
  }
}
