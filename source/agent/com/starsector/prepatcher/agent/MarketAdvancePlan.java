package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import java.util.List;

import static com.starsector.prepatcher.agent.ClassIO.*;
import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Markers.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.*;
import static com.starsector.prepatcher.agent.Queries.*;

/**
 * Owns the complete Market.advance(F)V transformation surface. The four
 * independently configurable components are inspected against the same
 * incoming class, then committed in one explicit order and validated by one
 * combined postcondition. No component marker is emitted.
 */
final class MarketAdvancePlan implements StructuredPatch<MarketAdvancePlan.Plan> {
  private static final String MASK_FIELD = "smo$marketAdvancePlanMask";

  private final int requestedMask;
  private final PatchAction persistentSnapshots;
  private final PatchAction commodityTemporal;
  private final PatchAction entryObservation;
  private final PatchAction schedulerBoundary;

  MarketAdvancePlan(int requestedMask, int marketStructureAuditFrames, PatchAction entryObservation) {
    this.requestedMask = requestedMask;
    this.persistentSnapshots = structured(new MarketPersistentSnapshots(marketStructureAuditFrames));
    this.commodityTemporal = structured(new MarketCommodityTemporalFastPath());
    this.entryObservation = entryObservation;
    this.schedulerBoundary = structured(new MarketSchedulerSemanticBoundary());
  }

  record Plan(ClassNode node, int featureMask, byte[] plannedBytes, PatchReport report) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    if (requestedMask == 0) { return Match.incompatible("Market.advance patch plan has an empty feature mask"); }
    rejectLegacyMarketAdvanceMarkers(node);

    boolean owned = hasPatchMarker(node, MARKET_ADVANCE_PLAN_PATCH_ID);
    if (owned) {
      requireMarketAdvancePlanMask(node, requestedMask);
      requireMarketAdvancePlanPostcondition(node, requestedMask);
      return Match.alreadyApplied("Market.advance unified patch-plan postcondition matches mask=" + requestedMask);
    }
    if (findField(node, MASK_FIELD) != null) {
      return Match.incompatible("Market.advance plan mask exists without the ownership marker");
    }

    // Build the plan from independent copies of the same incoming state. A
    // component that is already hook-shaped, partial, or structurally
    // incompatible aborts the whole plan before the candidate ClassNode is
    // modified.
    Plan plan = inspectMarketAdvancePatchPlan(node, requestedMask);
    if (plan.featureMask() != requestedMask) {
      return Match.incompatible("Market.advance plan feature mask changed during inspection");
    }
    return Match.plan(plan);
  }

  @Override
  public PatchReport mutate(Plan plan) {
    ClassNode node = plan.node();
    ClassNode planned = readClass(plan.plannedBytes());
    if (!node.name.equals(planned.name)) { throw mismatch("Market.advance plan candidate owner changed"); }
    // Components in this plan add or modify only fields and methods. Commit
    // those two class sections together; all other class metadata remains
    // exactly as supplied by the JVM.
    node.fields.clear();
    node.fields.addAll(planned.fields);
    node.methods.clear();
    node.methods.addAll(planned.methods);
    node.fields
        .add(new FieldNode(Opcodes.ASM8, marketAdvancePlanMaskAccess(), MASK_FIELD, "I", null, plan.featureMask()));
    PatchReport report = plan.report();
    report.add("unified Market.advance plan", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireMarketAdvancePlanMask(node, plan.featureMask());
    requireMarketAdvancePlanPostcondition(node, plan.featureMask());
  }

  private Plan inspectMarketAdvancePatchPlan(ClassNode incoming, int requestedMask) {
    // Every component must be in its vanilla state, including disabled
    // components. This prevents the unified owner from adopting legacy,
    // foreign, or mixed split-patch state.
    requireMarketAdvanceComponentVanilla(incoming, persistentSnapshots, "persistent snapshots");
    requireMarketAdvanceComponentVanilla(incoming, commodityTemporal, "commodity temporal loop");
    requireMarketAdvanceComponentVanilla(incoming, entryObservation, "entry observation");
    requireMarketAdvanceComponentVanilla(incoming, schedulerBoundary, "scheduler semantic boundary");

    // Build the complete candidate on an isolated ClassNode. The live
    // candidate supplied to apply() remains read-only throughout planning.
    // The resulting fields/methods are committed together only after every
    // requested component has succeeded.
    ClassNode planned = readClass(writeClass(incoming));
    PatchReport combined = new PatchReport();
    if ((requestedMask & MARKET_ADVANCE_FEATURE_PERSISTENT_SNAPSHOTS) != 0) {
      PatchReport component = persistentSnapshots.apply(planned);
      combined.add("persistent snapshots", component.total);
    }
    if ((requestedMask & MARKET_ADVANCE_FEATURE_COMMODITY_TEMPORAL) != 0) {
      PatchReport component = commodityTemporal.apply(planned);
      combined.add("commodity temporal loop", component.total);
    }
    if ((requestedMask & MARKET_ADVANCE_FEATURE_ENTRY_OBSERVATION) != 0) {
      PatchReport component = entryObservation.apply(planned);
      combined.add("entry observation", component.total);
    }
    if ((requestedMask & MARKET_ADVANCE_FEATURE_SCHEDULER_SEMANTICS) != 0) {
      PatchReport component = schedulerBoundary.apply(planned);
      combined.add("scheduler semantic boundary", component.total);
    }
    return new Plan(incoming, requestedMask, writeClass(planned), combined);
  }

  private void requireMarketAdvancePlanPostcondition(ClassNode node, int mask) {
    requireMarketAdvanceComponentState(node, (mask & MARKET_ADVANCE_FEATURE_PERSISTENT_SNAPSHOTS) != 0,
        persistentSnapshots, "persistent snapshots");
    requireMarketAdvanceComponentState(node, (mask & MARKET_ADVANCE_FEATURE_COMMODITY_TEMPORAL) != 0, commodityTemporal,
        "commodity temporal loop");
    requireMarketAdvanceComponentState(node, (mask & MARKET_ADVANCE_FEATURE_ENTRY_OBSERVATION) != 0, entryObservation,
        "entry observation");
    requireMarketAdvanceComponentState(node, (mask & MARKET_ADVANCE_FEATURE_SCHEDULER_SEMANTICS) != 0,
        schedulerBoundary, "scheduler semantic boundary");
  }

  private static void requireMarketAdvanceComponentVanilla(ClassNode incoming, PatchAction component, String label) {
    ClassNode probe = readClass(writeClass(incoming));
    try {
      PatchReport report = component.apply(probe);
      if (report.total <= 0) { throw mismatch("Market.advance " + label + " probe reported no sites"); }
    } catch (AlreadyPatchedException ex) {
      throw mismatch("Market.advance " + label + " is already hook-shaped without unified ownership");
    }
  }

  private static void requireMarketAdvanceComponentState(ClassNode node, boolean enabled, PatchAction component,
      String label) {
    ClassNode probe = readClass(writeClass(node));
    try {
      PatchReport report = component.apply(probe);
      if (enabled) {
        throw mismatch("enabled Market.advance " + label + " remains patchable after unified commit: " + report);
      }
      if (report.total <= 0) {
        throw mismatch("disabled Market.advance " + label + " vanilla probe reported no sites");
      }
    } catch (AlreadyPatchedException ex) {
      if (!enabled) { throw mismatch("disabled Market.advance " + label + " is present in the unified post-state"); }
    }
  }

  private static void rejectLegacyMarketAdvanceMarkers(ClassNode node) {
    for (String legacy : List.of("economyPersistentSnapshots", "commodityTemporalFastPath", "directMarketObservation",
        "marketScheduler")) {
      if (hasPatchMarker(node, legacy)) {
        throw mismatch("legacy split Market.advance ownership marker is present: " + legacy);
      }
    }
  }

  private static int marketAdvancePlanMaskAccess() {
    return Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
  }

  private static void requireMarketAdvancePlanMask(ClassNode node, int expectedMask) {
    FieldNode mask = findField(node, MASK_FIELD);
    if (mask == null || !"I".equals(mask.desc) || mask.access != marketAdvancePlanMaskAccess() || mask.signature != null
        || !(mask.value instanceof Integer value) || value != expectedMask) {
      throw mismatch("Market.advance plan feature mask changed; expected=" + expectedMask);
    }
  }
}
