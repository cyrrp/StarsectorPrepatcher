package com.starsector.prepatcher.agent.campaign;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import static com.starsector.prepatcher.agent.bytecode.Allocations.*;
import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.patch.PatchState;
import com.starsector.prepatcher.agent.bytecode.AllocationSpec;

public final class CampaignSnapshotReuse implements StructuredPatch<CampaignSnapshotReuse.Plan> {
  record Plan(String owner, MethodNode advance, PatchState advanceScope, MethodNode paused, PatchState pausedScope,
      AllocationSpec advanceSnapshots, AllocationSpec pausedSnapshots) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    String methodDesc = "(FLcom/fs/starfarer/util/A/new;)V";
    String hookDesc = "(Ljava/util/Collection;)Ljava/util/List;";
    AllocationSpec advanceSnapshots = new AllocationSpec("java/util/ArrayList", "(Ljava/util/Collection;)V",
        "borrowLocationAdvanceSnapshot", hookDesc, 3);
    AllocationSpec pausedSnapshots = new AllocationSpec("java/util/ArrayList", "(Ljava/util/Collection;)V",
        "borrowPausedLocationSnapshot", hookDesc, 2);

    MethodNode advance = requireMethod(node, "advance", methodDesc);
    PatchState advanceScope = scratchScopeState(node.name, advance, "BaseLocation.advance");
    if (snapshotAllocationGroupState(node.name, advance, advanceSnapshots, advanceScope == PatchState.ORIGINAL,
        "BaseLocation.advance defensive snapshots") == PatchState.PATCHED) {
      return Match.alreadyApplied("BaseLocation.advance defensive snapshots"
          + " hooks and assignment-scoped non-escape contracts are already present");
    }

    MethodNode paused = requireMethod(node, "advanceEvenIfPaused", methodDesc);
    PatchState pausedScope = scratchScopeState(node.name, paused, "BaseLocation.advanceEvenIfPaused");
    if (snapshotAllocationGroupState(node.name, paused, pausedSnapshots, pausedScope == PatchState.ORIGINAL,
        "BaseLocation.advanceEvenIfPaused defensive snapshots") == PatchState.PATCHED) {
      return Match.alreadyApplied("BaseLocation.advanceEvenIfPaused defensive snapshots"
          + " hooks and assignment-scoped non-escape contracts are already present");
    }
    return Match
        .plan(new Plan(node.name, advance, advanceScope, paused, pausedScope, advanceSnapshots, pausedSnapshots));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    int changed = 0;
    if (plan.advanceScope() == PatchState.ORIGINAL) {
      installScratchScope(plan.owner(), plan.advance(), "BaseLocation.advance");
    }
    changed += replaceSnapshotAllocationGroup(plan.owner(), plan.advance(), plan.advanceSnapshots(),
        plan.advanceScope() == PatchState.ORIGINAL, "BaseLocation.advance defensive snapshots");
    if (plan.pausedScope() == PatchState.ORIGINAL) {
      installScratchScope(plan.owner(), plan.paused(), "BaseLocation.advanceEvenIfPaused");
    }
    changed += replaceSnapshotAllocationGroup(plan.owner(), plan.paused(), plan.pausedSnapshots(),
        plan.pausedScope() == PatchState.ORIGINAL, "BaseLocation.advanceEvenIfPaused defensive snapshots");

    PatchReport report = new PatchReport();
    report.add("reusable BaseLocation defensive snapshots", changed);
    return report;
  }
}
