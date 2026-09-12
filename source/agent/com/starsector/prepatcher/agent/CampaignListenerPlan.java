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
 * Owns the CampaignEngine.advance post-listener boundary: the one-shot
 * post-load entity-index repair and the incremental repository-listener
 * refresh share the readdChangeListeners() call site. The two independently
 * configurable members are inspected against the same incoming class, applied
 * to an isolated candidate in one explicit order, and validated by one
 * combined postcondition. No member marker is emitted.
 *
 * <p>The members compose at the boundary: the repair anchors its guarded
 * rebuild immediately after the listener call, and the throttle rewrites that
 * call in place. Both member matchers recognize the other's post-state, so the
 * plan order is an implementation detail of one transaction; nothing outside
 * it can observe or depend on it.
 */
final class CampaignListenerPlan implements StructuredPatch<CampaignListenerPlan.Plan> {
  private static final String MASK_FIELD = "smo$campaignListenerPlanMask";

  private final int requestedMask;
  private final PatchAction entityIndexRepair;
  private final PatchAction listenerThrottle;

  CampaignListenerPlan(int requestedMask) {
    this.requestedMask = requestedMask;
    this.entityIndexRepair = structured(new EntityLookupIndexRepair());
    this.listenerThrottle = structured(new CampaignListenerThrottle());
  }

  record Plan(ClassNode node, int featureMask, byte[] plannedBytes, PatchReport report) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    if (requestedMask == 0) { return Match.incompatible("CampaignEngine listener plan has an empty feature mask"); }
    rejectLegacyCampaignListenerMarkers(node);

    boolean owned = hasPatchMarker(node, CAMPAIGN_LISTENER_PLAN_PATCH_ID);
    if (owned) {
      requireCampaignListenerPlanMask(node, requestedMask);
      requireCampaignListenerPlanPostcondition(node, requestedMask);
      return Match.alreadyApplied("CampaignEngine unified listener plan postcondition matches mask=" + requestedMask);
    }
    if (findField(node, MASK_FIELD) != null) {
      return Match.incompatible("CampaignEngine listener plan mask exists without the ownership marker");
    }

    // Build the plan from independent copies of the same incoming state. A
    // member that is already hook-shaped, partial, or structurally
    // incompatible aborts the whole plan before the candidate ClassNode is
    // modified.
    Plan plan = inspectCampaignListenerPlan(node, requestedMask);
    if (plan.featureMask() != requestedMask) {
      return Match.incompatible("CampaignEngine listener plan feature mask changed during inspection");
    }
    return Match.plan(plan);
  }

  @Override
  public PatchReport mutate(Plan plan) {
    ClassNode node = plan.node();
    ClassNode planned = readClass(plan.plannedBytes());
    if (!node.name.equals(planned.name)) { throw mismatch("CampaignEngine listener plan candidate owner changed"); }
    // Members in this plan add fields and modify method bodies only. Commit
    // fields and methods together; all other class metadata remains exactly as
    // supplied by the JVM.
    node.fields.clear();
    node.fields.addAll(planned.fields);
    node.methods.clear();
    node.methods.addAll(planned.methods);
    node.fields
        .add(new FieldNode(Opcodes.ASM8, campaignListenerPlanMaskAccess(), MASK_FIELD, "I", null, plan.featureMask()));
    PatchReport report = plan.report();
    report.add("unified CampaignEngine listener plan", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireCampaignListenerPlanMask(node, plan.featureMask());
    requireCampaignListenerPlanPostcondition(node, plan.featureMask());
  }

  private Plan inspectCampaignListenerPlan(ClassNode incoming, int requestedMask) {
    // Every member surface must be in its vanilla state, including disabled
    // members. This prevents the unified owner from adopting legacy, foreign,
    // or mixed split-patch state.
    requireCampaignListenerComponentVanilla(incoming, entityIndexRepair, "entity-index repair");
    requireCampaignListenerComponentVanilla(incoming, listenerThrottle, "listener throttle");

    // Build the complete candidate on an isolated ClassNode. The live
    // candidate supplied to apply() remains read-only throughout planning.
    // The resulting fields/methods are committed together only after every
    // requested member has succeeded.
    ClassNode planned = readClass(writeClass(incoming));
    PatchReport combined = new PatchReport();
    if ((requestedMask & CAMPAIGN_LISTENER_FEATURE_ENTITY_INDEX_REPAIR) != 0) {
      PatchReport component = entityIndexRepair.apply(planned);
      combined.add("entity-index repair", component.total);
    }
    if ((requestedMask & CAMPAIGN_LISTENER_FEATURE_THROTTLE) != 0) {
      PatchReport component = listenerThrottle.apply(planned);
      combined.add("listener throttle", component.total);
    }
    return new Plan(incoming, requestedMask, writeClass(planned), combined);
  }

  private void requireCampaignListenerPlanPostcondition(ClassNode node, int mask) {
    requireCampaignListenerComponentState(node, (mask & CAMPAIGN_LISTENER_FEATURE_ENTITY_INDEX_REPAIR) != 0,
        entityIndexRepair, "entity-index repair");
    requireCampaignListenerComponentState(node, (mask & CAMPAIGN_LISTENER_FEATURE_THROTTLE) != 0, listenerThrottle,
        "listener throttle");
  }

  private static void requireCampaignListenerComponentVanilla(ClassNode incoming, PatchAction component, String label) {
    ClassNode probe = readClass(writeClass(incoming));
    try {
      PatchReport report = component.apply(probe);
      if (report.total <= 0) { throw mismatch("CampaignEngine " + label + " probe reported no sites"); }
    } catch (AlreadyPatchedException ex) {
      throw mismatch("CampaignEngine " + label + " is already hook-shaped without unified ownership");
    }
  }

  private static void requireCampaignListenerComponentState(ClassNode node, boolean enabled, PatchAction component,
      String label) {
    ClassNode probe = readClass(writeClass(node));
    try {
      PatchReport report = component.apply(probe);
      if (enabled) {
        throw mismatch("enabled CampaignEngine " + label + " remains patchable after unified commit: " + report);
      }
      if (report.total <= 0) {
        throw mismatch("disabled CampaignEngine " + label + " vanilla probe reported no sites");
      }
    } catch (AlreadyPatchedException ex) {
      if (!enabled) { throw mismatch("disabled CampaignEngine " + label + " is present in the unified post-state"); }
    }
  }

  private static void rejectLegacyCampaignListenerMarkers(ClassNode node) {
    for (String legacy : List.of("entityLookupIndexRepair", "campaignListenerThrottle")) {
      if (hasPatchMarker(node, legacy)) {
        throw mismatch("legacy split CampaignEngine listener marker is present: " + legacy);
      }
    }
  }

  private static int campaignListenerPlanMaskAccess() {
    return Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
  }

  private static void requireCampaignListenerPlanMask(ClassNode node, int expectedMask) {
    FieldNode mask = findField(node, MASK_FIELD);
    if (mask == null || !"I".equals(mask.desc) || mask.access != campaignListenerPlanMaskAccess()
        || mask.signature != null || !(mask.value instanceof Integer value) || value != expectedMask) {
      throw mismatch("CampaignEngine listener plan feature mask changed; expected=" + expectedMask);
    }
  }
}
