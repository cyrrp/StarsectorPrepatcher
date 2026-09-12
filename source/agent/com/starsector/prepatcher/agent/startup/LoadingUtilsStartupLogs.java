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


public final class LoadingUtilsStartupLogs implements StructuredPatch<LoadingUtilsStartupLogs.Plan> {
  record Plan(List<StartupInfoBlocks.LogBlock> jsonBlocks, List<StartupInfoBlocks.LogBlock> csvBlocks) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    int jsonSites = countStringConstants(node, "Loading JSON from [");
    int csvSites = countStringConstants(node, "Loading CSV data from [");
    int jsonHooks = countCategoryHooks(node, "startupLogSuppressed", "(I)V", 0);
    int csvHooks = countCategoryHooks(node, "startupLogSuppressed", "(I)V", 1);
    if (jsonSites == 0 && csvSites == 0 && jsonHooks == 2 && csvHooks == 3) {
      return Match.alreadyApplied("LoadingUtils startup INFO suppression postcondition matches");
    }
    requireCount("LoadingUtils JSON INFO literals", jsonSites, 2);
    requireCount("LoadingUtils CSV INFO literals", csvSites, 3);
    requireCount("LoadingUtils JSON suppression hooks", jsonHooks, 0);
    requireCount("LoadingUtils CSV suppression hooks", csvHooks, 0);
    List<StartupInfoBlocks.LogBlock> json = StartupInfoBlocks.startupInfoBlockPlans(node, "Loading JSON from [");
    List<StartupInfoBlocks.LogBlock> csv = StartupInfoBlocks.startupInfoBlockPlans(node, "Loading CSV data from [");
    requireCount("startup INFO blocks for Loading JSON from [", json.size(), 2);
    requireCount("startup INFO blocks for Loading CSV data from [", csv.size(), 3);
    return Match.plan(new Plan(json, csv));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    StartupInfoBlocks.suppressStartupInfoBlocks(plan.jsonBlocks(), 0, true);
    StartupInfoBlocks.suppressStartupInfoBlocks(plan.csvBlocks(), 1, true);
    PatchReport report = new PatchReport();
    report.add("jsonInfoBlocks", plan.jsonBlocks().size());
    report.add("csvInfoBlocks", plan.csvBlocks().size());
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireCount("LoadingUtils JSON INFO literals", countStringConstants(node, "Loading JSON from ["), 0);
    requireCount("LoadingUtils CSV INFO literals", countStringConstants(node, "Loading CSV data from ["), 0);
    requireCount("LoadingUtils JSON suppression hooks", countCategoryHooks(node, "startupLogSuppressed", "(I)V", 0), 2);
    requireCount("LoadingUtils CSV suppression hooks", countCategoryHooks(node, "startupLogSuppressed", "(I)V", 1), 3);
  }
}

