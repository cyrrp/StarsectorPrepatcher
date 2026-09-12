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

final class EconomyAdvancePlan implements StructuredPatch<EconomyAdvancePlan.Plan> {
  private static final String MASK_FIELD = "smo$economyAdvancePlanMask";

  private final int requestedMask;
  private final PatchAction persistentSnapshots;
  private final PatchAction locationCache;
  private final PatchAction schedulerSource;

  EconomyAdvancePlan(int requestedMask, int economyStructureAuditMs) {
    this.requestedMask = requestedMask;
    this.persistentSnapshots = structured(new EconomyPersistentSnapshots(economyStructureAuditMs));
    this.locationCache = structured(new EconomyLocationMapInvalidation());
    this.schedulerSource = structured(new EconomyMarketSchedulerSource());
  }

  record Plan(ClassNode node, int featureMask, byte[] plannedBytes, PatchReport report) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    if (requestedMask == 0) { return Match.incompatible("Economy.advance patch plan has an empty feature mask"); }
    rejectLegacyEconomyAdvanceMarkers(node);

    boolean owned = hasPatchMarker(node, ECONOMY_ADVANCE_PLAN_PATCH_ID);
    if (owned) {
      requireEconomyAdvancePlanMask(node, requestedMask);
      requireEconomyAdvancePlanPostcondition(node, requestedMask);
      return Match.alreadyApplied("Economy.advance unified plan postcondition matches mask=" + requestedMask);
    }
    if (findField(node, MASK_FIELD) != null) {
      return Match.incompatible("Economy.advance plan mask exists without the ownership marker");
    }

    Plan plan = inspectEconomyAdvancePatchPlan(node, requestedMask);
    if (plan.featureMask() != requestedMask) {
      return Match.incompatible("Economy.advance plan feature mask changed during inspection");
    }
    return Match.plan(plan);
  }

  @Override
  public PatchReport mutate(Plan plan) {
    ClassNode node = plan.node();
    ClassNode planned = readClass(plan.plannedBytes());
    if (!node.name.equals(planned.name)) { throw mismatch("Economy.advance plan candidate owner changed"); }
    node.fields.clear();
    node.fields.addAll(planned.fields);
    node.methods.clear();
    node.methods.addAll(planned.methods);
    node.fields
        .add(new FieldNode(Opcodes.ASM8, economyAdvancePlanMaskAccess(), MASK_FIELD, "I", null, plan.featureMask()));
    PatchReport report = plan.report();
    report.add("unified Economy.advance plan", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireEconomyAdvancePlanMask(node, plan.featureMask());
    requireEconomyAdvancePlanPostcondition(node, plan.featureMask());
  }

  private Plan inspectEconomyAdvancePatchPlan(ClassNode incoming, int requestedMask) {
    // Disabled components must also remain vanilla. This prevents the new
    // owner from adopting old split markers, foreign hooks, or a partial
    // combination left by another transformer.
    requireEconomyAdvanceComponentVanilla(incoming, persistentSnapshots, "persistent snapshots");
    requireEconomyAdvanceComponentVanilla(incoming, locationCache, "location cache");
    requireEconomyAdvanceComponentVanilla(incoming, schedulerSource, "market scheduler source");

    ClassNode planned = readClass(writeClass(incoming));
    PatchReport combined = new PatchReport();
    // Explicit dependency order: location-cache selection observes whether
    // the persistent snapshot state was installed, and the scheduler source
    // is applied last to the final advance-method data flow.
    if ((requestedMask & ECONOMY_ADVANCE_FEATURE_PERSISTENT_SNAPSHOTS) != 0) {
      PatchReport component = persistentSnapshots.apply(planned);
      combined.add("persistent snapshots", component.total);
    }
    if ((requestedMask & ECONOMY_ADVANCE_FEATURE_LOCATION_CACHE) != 0) {
      PatchReport component = locationCache.apply(planned);
      combined.add("location cache", component.total);
    }
    if ((requestedMask & ECONOMY_ADVANCE_FEATURE_MARKET_SCHEDULER) != 0) {
      PatchReport component = schedulerSource.apply(planned);
      combined.add("market scheduler source", component.total);
    }
    return new Plan(incoming, requestedMask, writeClass(planned), combined);
  }

  private void requireEconomyAdvancePlanPostcondition(ClassNode node, int mask) {
    requireEconomyAdvanceComponentState(node, (mask & ECONOMY_ADVANCE_FEATURE_PERSISTENT_SNAPSHOTS) != 0,
        persistentSnapshots, "persistent snapshots");
    requireEconomyAdvanceComponentState(node, (mask & ECONOMY_ADVANCE_FEATURE_LOCATION_CACHE) != 0, locationCache,
        "location cache");
    requireEconomyAdvanceComponentState(node, (mask & ECONOMY_ADVANCE_FEATURE_MARKET_SCHEDULER) != 0, schedulerSource,
        "market scheduler source");
  }

  private static void requireEconomyAdvanceComponentVanilla(ClassNode incoming, PatchAction component, String label) {
    ClassNode probe = readClass(writeClass(incoming));
    try {
      PatchReport report = component.apply(probe);
      if (report.total <= 0) { throw mismatch("Economy.advance " + label + " probe reported no sites"); }
    } catch (AlreadyPatchedException ex) {
      throw mismatch("Economy.advance " + label + " is already hook-shaped without unified ownership");
    }
  }

  private static void requireEconomyAdvanceComponentState(ClassNode node, boolean enabled, PatchAction component,
      String label) {
    ClassNode probe = readClass(writeClass(node));
    try {
      PatchReport report = component.apply(probe);
      if (enabled) {
        throw mismatch("enabled Economy.advance " + label + " remains patchable after unified commit: " + report);
      }
      if (report.total <= 0) {
        throw mismatch("disabled Economy.advance " + label + " vanilla probe reported no sites");
      }
    } catch (AlreadyPatchedException ex) {
      if (!enabled) { throw mismatch("disabled Economy.advance " + label + " is present in the unified post-state"); }
    }
  }

  private static void rejectLegacyEconomyAdvanceMarkers(ClassNode node) {
    for (String legacy : List.of("economyPersistentSnapshots", "economyLocationCache", "marketScheduler")) {
      if (hasPatchMarker(node, legacy)) {
        throw mismatch("legacy split Economy.advance ownership marker is present: " + legacy);
      }
    }
  }

  private static int economyAdvancePlanMaskAccess() {
    return Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
  }

  private static void requireEconomyAdvancePlanMask(ClassNode node, int expectedMask) {
    FieldNode mask = findField(node, MASK_FIELD);
    if (mask == null || !"I".equals(mask.desc) || mask.access != economyAdvancePlanMaskAccess()
        || mask.signature != null || !(mask.value instanceof Integer value) || value != expectedMask) {
      throw mismatch("Economy.advance plan feature mask changed; expected=" + expectedMask);
    }
  }
}
