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
 * Owns the A map-panel interaction surface: the IntelInfoPlugin.getMapLocation
 * callback cache scans every method of the class, and the hit-test member
 * mutates that same method inventory by renaming OO0000 and adding its
 * wrapper. The two independently configurable members are inspected against
 * the same incoming class, applied to an isolated candidate in one explicit
 * order, and validated by one combined postcondition. No member marker is
 * emitted.
 *
 * <p>The explicit order keeps the member contract the old dispatch order
 * enforced by convention: the call-site member runs against the intact method
 * inventory, then the hit-test member renames the original body and installs
 * the wrapper. Both member matchers accept the other's post-state, so the
 * order is an implementation detail of one transaction; nothing outside it
 * can observe or depend on it.
 */
final class MapInteractionPlan implements StructuredPatch<MapInteractionPlan.Plan> {
  private static final String MASK_FIELD = "smo$mapInteractionPlanMask";

  private final int requestedMask;
  private final PatchAction intelCallbackCache;
  private final PatchAction hitTest;

  MapInteractionPlan(int requestedMask) {
    this.requestedMask = requestedMask;
    this.intelCallbackCache = structured(new IntelMapLocationCache(1));
    this.hitTest = structured(new MapHitTest());
  }

  record Plan(ClassNode node, int featureMask, byte[] plannedBytes, PatchReport report) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    if (requestedMask == 0) { return Match.incompatible("A map-interaction plan has an empty feature mask"); }
    rejectLegacyMapInteractionMarkers(node);

    boolean owned = hasPatchMarker(node, MAP_INTERACTION_PLAN_PATCH_ID);
    if (owned) {
      requireMapInteractionPlanMask(node, requestedMask);
      requireMapInteractionPlanPostcondition(node, requestedMask);
      return Match.alreadyApplied("A unified map-interaction plan postcondition matches mask=" + requestedMask);
    }
    if (findField(node, MASK_FIELD) != null) {
      return Match.incompatible("A map-interaction plan mask exists without the ownership marker");
    }

    // Build the plan from independent copies of the same incoming state. A
    // member that is already hook-shaped, partial, or structurally
    // incompatible aborts the whole plan before the candidate ClassNode is
    // modified.
    Plan plan = inspectMapInteractionPlan(node, requestedMask);
    if (plan.featureMask() != requestedMask) {
      return Match.incompatible("A map-interaction plan feature mask changed during inspection");
    }
    return Match.plan(plan);
  }

  @Override
  public PatchReport mutate(Plan plan) {
    ClassNode node = plan.node();
    ClassNode planned = readClass(plan.plannedBytes());
    if (!node.name.equals(planned.name)) { throw mismatch("A map-interaction plan candidate owner changed"); }
    // Members in this plan add methods and modify method bodies only. Commit
    // fields and methods together; all other class metadata remains exactly as
    // supplied by the JVM.
    node.fields.clear();
    node.fields.addAll(planned.fields);
    node.methods.clear();
    node.methods.addAll(planned.methods);
    node.fields
        .add(new FieldNode(Opcodes.ASM8, mapInteractionPlanMaskAccess(), MASK_FIELD, "I", null, plan.featureMask()));
    PatchReport report = plan.report();
    report.add("unified A map-interaction plan", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireMapInteractionPlanMask(node, plan.featureMask());
    requireMapInteractionPlanPostcondition(node, plan.featureMask());
  }

  private Plan inspectMapInteractionPlan(ClassNode incoming, int requestedMask) {
    // Every member surface must be in its vanilla state, including disabled
    // members. This prevents the unified owner from adopting legacy, foreign,
    // or mixed split-patch state.
    requireMapInteractionComponentVanilla(incoming, intelCallbackCache, "Intel callback cache");
    requireMapInteractionComponentVanilla(incoming, hitTest, "hit test");

    // Build the complete candidate on an isolated ClassNode. The call-site
    // member runs first so it rewrites the preserved original body before the
    // hit-test member renames it and installs the wrapper. The resulting
    // fields/methods are committed together only after every requested member
    // has succeeded.
    ClassNode planned = readClass(writeClass(incoming));
    PatchReport combined = new PatchReport();
    if ((requestedMask & MAP_INTERACTION_FEATURE_INTEL_CALLBACK_CACHE) != 0) {
      PatchReport component = intelCallbackCache.apply(planned);
      combined.add("Intel map-location callback cache", component.total);
    }
    if ((requestedMask & MAP_INTERACTION_FEATURE_HIT_TEST) != 0) {
      PatchReport component = hitTest.apply(planned);
      combined.add("map hit test", component.total);
    }
    return new Plan(incoming, requestedMask, writeClass(planned), combined);
  }

  private void requireMapInteractionPlanPostcondition(ClassNode node, int mask) {
    requireMapInteractionComponentState(node, (mask & MAP_INTERACTION_FEATURE_INTEL_CALLBACK_CACHE) != 0,
        intelCallbackCache, "Intel callback cache");
    requireMapInteractionComponentState(node, (mask & MAP_INTERACTION_FEATURE_HIT_TEST) != 0, hitTest, "hit test");
  }

  private static void requireMapInteractionComponentVanilla(ClassNode incoming, PatchAction component, String label) {
    ClassNode probe = readClass(writeClass(incoming));
    try {
      PatchReport report = component.apply(probe);
      if (report.total <= 0) { throw mismatch("A " + label + " probe reported no sites"); }
    } catch (AlreadyPatchedException ex) {
      throw mismatch("A " + label + " is already hook-shaped without unified ownership");
    }
  }

  private static void requireMapInteractionComponentState(ClassNode node, boolean enabled, PatchAction component,
      String label) {
    ClassNode probe = readClass(writeClass(node));
    try {
      PatchReport report = component.apply(probe);
      if (enabled) { throw mismatch("enabled A " + label + " remains patchable after unified commit: " + report); }
      if (report.total <= 0) { throw mismatch("disabled A " + label + " vanilla probe reported no sites"); }
    } catch (AlreadyPatchedException ex) {
      if (!enabled) { throw mismatch("disabled A " + label + " is present in the unified post-state"); }
    }
  }

  private static void rejectLegacyMapInteractionMarkers(ClassNode node) {
    for (String legacy : List.of("intelCallbackCache", "mapHitTest")) {
      if (hasPatchMarker(node, legacy)) {
        throw mismatch("legacy split A map-interaction marker is present: " + legacy);
      }
    }
  }

  private static int mapInteractionPlanMaskAccess() {
    return Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
  }

  private static void requireMapInteractionPlanMask(ClassNode node, int expectedMask) {
    FieldNode mask = findField(node, MASK_FIELD);
    if (mask == null || !"I".equals(mask.desc) || mask.access != mapInteractionPlanMaskAccess()
        || mask.signature != null || !(mask.value instanceof Integer value) || value != expectedMask) {
      throw mismatch("A map-interaction plan feature mask changed; expected=" + expectedMask);
    }
  }
}
