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

final class SaveMethodPlan implements StructuredPatch<SaveMethodPlan.Plan> {
  private static final String SAVE_METHOD_PLAN_MASK_FIELD = "smo$saveMethodPlanMask";

  private final int requestedMask;
  private final PatchAction marketSchedulerFlush;
  private final PatchAction cacheMaintenance;

  SaveMethodPlan(int requestedMask, PatchAction cacheMaintenance) {
    this.requestedMask = requestedMask;
    this.marketSchedulerFlush = structured(new MarketSchedulerSaveFlush());
    this.cacheMaintenance = cacheMaintenance;
  }

  record Plan(ClassNode node, int featureMask, byte[] plannedBytes, PatchReport report) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    if (requestedMask == 0) { return Match.incompatible("save method patch plan has an empty feature mask"); }
    rejectLegacySaveMethodMarkers(node);

    boolean owned = hasPatchMarker(node, SAVE_METHOD_PLAN_PATCH_ID);
    if (owned) {
      requireSaveMethodPlanMask(node, requestedMask);
      requireSaveMethodPlanPostcondition(node, requestedMask);
      return Match.alreadyApplied("save method barrier plan postcondition matches mask=" + requestedMask);
    }
    if (findField(node, SAVE_METHOD_PLAN_MASK_FIELD) != null) {
      return Match.incompatible("save method plan mask exists without the ownership marker");
    }

    Plan plan = inspectSaveMethodPatchPlan(node, requestedMask);
    if (plan.featureMask() != requestedMask) {
      return Match.incompatible("save method plan feature mask changed during inspection");
    }
    return Match.plan(plan);
  }

  @Override
  public PatchReport mutate(Plan plan) {
    ClassNode node = plan.node();
    ClassNode planned = readClass(plan.plannedBytes());
    if (!node.name.equals(planned.name)) { throw mismatch("save method plan candidate owner changed"); }
    node.fields.clear();
    node.fields.addAll(planned.fields);
    node.methods.clear();
    node.methods.addAll(planned.methods);
    node.fields.add(new FieldNode(Opcodes.ASM8, saveMethodPlanMaskAccess(), SAVE_METHOD_PLAN_MASK_FIELD, "I", null,
        plan.featureMask()));
    PatchReport report = plan.report();
    report.add("atomic pre-save barrier plan", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireSaveMethodPlanMask(node, plan.featureMask());
    requireSaveMethodPlanPostcondition(node, plan.featureMask());
  }

  private Plan inspectSaveMethodPatchPlan(ClassNode incoming, int requestedMask) {
    // Disabled barrier components must remain vanilla as well. The unified
    // owner never adopts a legacy split barrier, a foreign hook, or a
    // partial pre-save state. Output-buffer deduplication is not part of
    // this transaction and may already be present under its own marker.
    requireSaveMethodComponentVanilla(incoming, marketSchedulerFlush, "market scheduler pre-save barrier");
    requireSaveMethodComponentVanilla(incoming, cacheMaintenance, "cache maintenance pre-save barrier");

    ClassNode planned = readClass(writeClass(incoming));
    PatchReport combined = new PatchReport();
    // Explicit barrier order: remove already-idle cache roots, then settle
    // market debt. Optional output-chain rewriting is applied afterwards.
    if ((requestedMask & SAVE_METHOD_FEATURE_CACHE_MAINTENANCE) != 0) {
      PatchReport component = cacheMaintenance.apply(planned);
      combined.add("cache maintenance pre-save barrier", component.total);
    }
    if ((requestedMask & SAVE_METHOD_FEATURE_MARKET_SCHEDULER_FLUSH) != 0) {
      PatchReport component = marketSchedulerFlush.apply(planned);
      combined.add("market scheduler pre-save barrier", component.total);
    }
    return new Plan(incoming, requestedMask, writeClass(planned), combined);
  }

  private void requireSaveMethodPlanPostcondition(ClassNode node, int mask) {
    requireSaveMethodComponentState(node, (mask & SAVE_METHOD_FEATURE_MARKET_SCHEDULER_FLUSH) != 0,
        marketSchedulerFlush, "market scheduler pre-save barrier");
    requireSaveMethodComponentState(node, (mask & SAVE_METHOD_FEATURE_CACHE_MAINTENANCE) != 0, cacheMaintenance,
        "cache maintenance pre-save barrier");
  }

  private static void requireSaveMethodComponentVanilla(ClassNode incoming, PatchAction component, String label) {
    ClassNode probe = readClass(writeClass(incoming));
    try {
      PatchReport report = component.apply(probe);
      if (report.total <= 0) { throw mismatch("save method " + label + " probe reported no sites"); }
    } catch (AlreadyPatchedException ex) {
      throw mismatch("save method " + label + " is already hook-shaped without unified ownership");
    }
  }

  private static void requireSaveMethodComponentState(ClassNode node, boolean enabled, PatchAction component,
      String label) {
    ClassNode probe = readClass(writeClass(node));
    try {
      PatchReport report = component.apply(probe);
      if (enabled) {
        throw mismatch("enabled save method " + label + " remains patchable after unified commit: " + report);
      }
      if (report.total <= 0) { throw mismatch("disabled save method " + label + " vanilla probe reported no sites"); }
    } catch (AlreadyPatchedException ex) {
      if (!enabled) { throw mismatch("disabled save method " + label + " is present in the unified post-state"); }
    }
  }

  private static void rejectLegacySaveMethodMarkers(ClassNode node) {
    for (String legacy : List.of("marketScheduler", "remoteMarketScheduler")) {
      if (hasPatchMarker(node, legacy)) {
        throw mismatch("legacy split save-method ownership marker is present: " + legacy);
      }
    }
  }

  private static int saveMethodPlanMaskAccess() {
    return Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
  }

  private static void requireSaveMethodPlanMask(ClassNode node, int expectedMask) {
    FieldNode mask = findField(node, SAVE_METHOD_PLAN_MASK_FIELD);
    if (mask == null || !"I".equals(mask.desc) || mask.access != saveMethodPlanMaskAccess() || mask.signature != null
        || !(mask.value instanceof Integer value) || value != expectedMask) {
      throw mismatch("save method plan feature mask changed; expected=" + expectedMask);
    }
  }
}
