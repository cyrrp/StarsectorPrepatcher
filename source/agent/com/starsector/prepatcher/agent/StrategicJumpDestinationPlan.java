package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import java.util.List;

import static com.starsector.prepatcher.agent.ClassIO.*;
import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Markers.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.*;
import static com.starsector.prepatcher.agent.Queries.*;

/**
 * Owns the complete StrategicModule destination-selection surface: the lazy
 * hostile-market score in findNearestSafeJumpPoint, the indexed candidate
 * source in the same method, and the expired-plan deferral in
 * updateJumpPlanTo. The two independently configurable members are inspected
 * against the same incoming class, then committed in one explicit order and
 * validated by one combined postcondition. No member marker is emitted.
 *
 * <p>The index member's contract is defined over the destination-first
 * post-state, so the inspection order is mandatory: the destination-first
 * surface is proven first, and the index surface is then proven against that
 * post-state. Inside the plan this order is an implementation detail of one
 * transaction; nothing outside it can observe or depend on it.
 */
final class StrategicJumpDestinationPlan implements StructuredPatch<StrategicJumpDestinationPlan.Plan> {
  private static final String MASK_FIELD = "smo$strategicJumpDestinationPlanMask";

  private final int requestedMask;
  private final PatchAction destinationFirst;
  private final PatchAction destinationIndex;

  StrategicJumpDestinationPlan(int requestedMask) {
    this.requestedMask = requestedMask;
    this.destinationFirst = structured(new StrategicJumpDestinationFirst());
    this.destinationIndex = structured(new StrategicJumpDestinationIndex());
  }

  record Plan(ClassNode node, int featureMask, byte[] plannedBytes, PatchReport report) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    if (requestedMask == 0) { return Match.incompatible("StrategicModule destination plan has an empty feature mask"); }
    if ((requestedMask & STRATEGIC_JUMP_FEATURE_DESTINATION_INDEX) != 0
        && (requestedMask & STRATEGIC_JUMP_FEATURE_DESTINATION_FIRST) == 0) {
      return Match.incompatible("StrategicModule destination index member requires the destination-first member");
    }
    rejectLegacyStrategicJumpMarkers(node);

    boolean owned = hasPatchMarker(node, STRATEGIC_JUMP_DESTINATION_PLAN_PATCH_ID);
    if (owned) {
      requireStrategicJumpPlanMask(node, requestedMask);
      requireStrategicJumpPlanPostcondition(node, requestedMask);
      return Match
          .alreadyApplied("StrategicModule unified destination plan postcondition matches mask=" + requestedMask);
    }
    if (findField(node, MASK_FIELD) != null) {
      return Match.incompatible("StrategicModule destination plan mask exists without the ownership marker");
    }

    // Build the plan from independent copies of the same incoming state. A
    // member that is already hook-shaped, partial, or structurally
    // incompatible aborts the whole plan before the candidate ClassNode is
    // modified.
    Plan plan = inspectStrategicJumpPlan(node, requestedMask);
    if (plan.featureMask() != requestedMask) {
      return Match.incompatible("StrategicModule destination plan feature mask changed during inspection");
    }
    return Match.plan(plan);
  }

  @Override
  public PatchReport mutate(Plan plan) {
    ClassNode node = plan.node();
    ClassNode planned = readClass(plan.plannedBytes());
    if (!node.name.equals(planned.name)) { throw mismatch("StrategicModule destination plan candidate owner changed"); }
    // Members in this plan modify method bodies only. Commit fields and
    // methods together; all other class metadata remains exactly as supplied
    // by the JVM.
    node.fields.clear();
    node.fields.addAll(planned.fields);
    node.methods.clear();
    node.methods.addAll(planned.methods);
    node.fields
        .add(new FieldNode(Opcodes.ASM8, strategicJumpPlanMaskAccess(), MASK_FIELD, "I", null, plan.featureMask()));
    PatchReport report = plan.report();
    report.add("unified StrategicModule destination plan", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireStrategicJumpPlanMask(node, plan.featureMask());
    requireStrategicJumpPlanPostcondition(node, plan.featureMask());
  }

  private Plan inspectStrategicJumpPlan(ClassNode incoming, int requestedMask) {
    // Every member surface must be in its vanilla state, including disabled
    // members. The probe encodes the member contract directly: destination-first
    // applies to the vanilla surface, then the index member applies to the
    // destination-first post-state produced inside the same probe.
    ClassNode probe = readClass(writeClass(incoming));
    requireStrategicJumpComponentApplicable(probe, destinationFirst, "destination-first");
    requireStrategicJumpComponentApplicable(probe, destinationIndex, "destination index");

    // Build the complete candidate on an isolated ClassNode. The live
    // candidate supplied to apply() remains read-only throughout planning.
    // The resulting fields/methods are committed together only after every
    // requested member has succeeded.
    ClassNode planned = readClass(writeClass(incoming));
    PatchReport combined = new PatchReport();
    if ((requestedMask & STRATEGIC_JUMP_FEATURE_DESTINATION_FIRST) != 0) {
      PatchReport component = destinationFirst.apply(planned);
      combined.add("destination-first hostile-market guard", component.total);
    }
    if ((requestedMask & STRATEGIC_JUMP_FEATURE_DESTINATION_INDEX) != 0) {
      PatchReport component = destinationIndex.apply(planned);
      combined.add("strategic destination index", component.total);
    }
    return new Plan(incoming, requestedMask, writeClass(planned), combined);
  }

  private void requireStrategicJumpPlanPostcondition(ClassNode node, int mask) {
    requireStrategicJumpComponentState(node, (mask & STRATEGIC_JUMP_FEATURE_DESTINATION_FIRST) != 0, destinationFirst,
        "destination-first");
    requireStrategicJumpComponentState(node, (mask & STRATEGIC_JUMP_FEATURE_DESTINATION_INDEX) != 0, destinationIndex,
        "destination index");
  }

  private static void requireStrategicJumpComponentApplicable(ClassNode probe, PatchAction component, String label) {
    try {
      PatchReport report = component.apply(probe);
      if (report.total <= 0) { throw mismatch("StrategicModule " + label + " probe reported no sites"); }
    } catch (AlreadyPatchedException ex) {
      throw mismatch("StrategicModule " + label + " is already hook-shaped without unified ownership");
    }
  }

  private static void requireStrategicJumpComponentState(ClassNode node, boolean enabled, PatchAction component,
      String label) {
    ClassNode probe = readClass(writeClass(node));
    try {
      PatchReport report = component.apply(probe);
      if (enabled) {
        throw mismatch("enabled StrategicModule " + label + " remains patchable after unified commit: " + report);
      }
      if (report.total <= 0) {
        throw mismatch("disabled StrategicModule " + label + " vanilla probe reported no sites");
      }
    } catch (AlreadyPatchedException ex) {
      if (!enabled) { throw mismatch("disabled StrategicModule " + label + " is present in the unified post-state"); }
    }
  }

  private static void rejectLegacyStrategicJumpMarkers(ClassNode node) {
    for (String legacy : List.of(STRATEGIC_JUMP_DESTINATION_FIRST_PATCH_ID,
        STRATEGIC_JUMP_DESTINATION_INDEX_PATCH_ID)) {
      if (hasPatchMarker(node, legacy)) {
        throw mismatch("legacy split StrategicModule destination marker is present: " + legacy);
      }
    }
  }

  private static int strategicJumpPlanMaskAccess() {
    return Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
  }

  private static void requireStrategicJumpPlanMask(ClassNode node, int expectedMask) {
    FieldNode mask = findField(node, MASK_FIELD);
    if (mask == null || !"I".equals(mask.desc) || mask.access != strategicJumpPlanMaskAccess() || mask.signature != null
        || !(mask.value instanceof Integer value) || value != expectedMask) {
      throw mismatch("StrategicModule destination plan feature mask changed; expected=" + expectedMask);
    }
  }
}
