package com.starsector.prepatcher.agent.mapintel;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.List;


import static com.starsector.prepatcher.agent.bytecode.Allocations.*;
import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Flow.*;
import static com.starsector.prepatcher.agent.bytecode.Mutations.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.H;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.patch.PatchState;
import com.starsector.prepatcher.agent.patch.StructuredPatch;

/**
 * Atomically installs the EventsPanel reconciliation surface: the missing-plugin
 * membership accelerator, direct existing-icon candidate lookup, and the single
 * reentrant scratch scope required by fastContains(). Partial or foreign hook-shaped
 * states reject the whole group.
 */
public final class IntelMissingIconReconciliation implements StructuredPatch<IntelMissingIconReconciliation.Plan> {
  private static final String CONTAINS_DESC = "(Ljava/util/Collection;Ljava/lang/Object;)Z";
  private static final String CANDIDATES_DESC = "(Ljava/util/LinkedHashMap;Ljava/lang/Object;)Ljava/util/Collection;";
  private static final String SCOPE = "EventsPanel.addMissingIconsAndRows";

  /** The proven method, both original call sites, and the entity local feeding the lookup. */
  record Plan(String owner, MethodNode method, MethodInsnNode contains, MethodInsnNode values, int entityLocal) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode method = requireMethod(node, "addMissingIconsAndRows", "()V");
    PatchState scopeState = scratchScopeState(node.name, method, SCOPE);

    List<MethodInsnNode> originalContains =
        calls(method, Opcodes.INVOKEINTERFACE, "java/util/List", "contains", "(Ljava/lang/Object;)Z");
    List<MethodInsnNode> containsHooks = calls(method, Opcodes.INVOKESTATIC, HOOKS, "fastContains", CONTAINS_DESC);
    List<MethodInsnNode> originalValues =
        calls(method, Opcodes.INVOKEVIRTUAL, "java/util/LinkedHashMap", "values", "()Ljava/util/Collection;");
    List<MethodInsnNode> candidateHooks =
        calls(method, Opcodes.INVOKESTATIC, HOOKS, "existingIntelIconCandidates", CANDIDATES_DESC);

    boolean originalState = originalContains.size() == 1 && containsHooks.isEmpty() && originalValues.size() == 1
        && candidateHooks.isEmpty() && scopeState == PatchState.ORIGINAL;
    boolean patchedState = originalContains.isEmpty() && containsHooks.size() == 1 && originalValues.isEmpty()
        && candidateHooks.size() == 1 && scopeState == PatchState.PATCHED;

    if (patchedState) {
      validateEventsPanelContainsSite(node, method, containsHooks.get(0), true);
      validateEventsPanelExistingIconSite(node, method, candidateHooks.get(0), true);
      return Match.alreadyApplied("EventsPanel reconciliation hooks and scratch scope are already present");
    }
    if (!originalState) {
      return Match.incompatible("EventsPanel reconciliation has mixed, missing, ambiguous, or foreign "
          + "contains/icon-candidate/scope state");
    }

    MethodInsnNode contains = originalContains.get(0);
    MethodInsnNode values = originalValues.get(0);
    validateEventsPanelContainsSite(node, method, contains, false);
    int entityLocal = validateEventsPanelExistingIconSite(node, method, values, false);
    return Match.plan(new Plan(node.name, method, contains, values, entityLocal));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    installScratchScope(plan.owner(), plan.method(), SCOPE);
    makeStatic(plan.contains(), HOOKS, "fastContains", CONTAINS_DESC);
    plan.method().instructions.insertBefore(plan.values(), new VarInsnNode(Opcodes.ALOAD, plan.entityLocal()));
    makeStatic(plan.values(), HOOKS, "existingIntelIconCandidates", CANDIDATES_DESC);

    PatchReport report = new PatchReport();
    report.add("Intel missing-list hash membership", 1);
    report.add("Intel direct existing-icon candidates", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireCount("EventsPanel fastContains postcondition",
        countCalls(plan.method(), Opcodes.INVOKESTATIC, HOOKS, "fastContains", CONTAINS_DESC), 1);
    requireCount("EventsPanel icon-candidate postcondition",
        countCalls(plan.method(), Opcodes.INVOKESTATIC, HOOKS, "existingIntelIconCandidates", CANDIDATES_DESC), 1);
    if (scratchScopeState(plan.owner(), plan.method(), SCOPE) != PatchState.PATCHED) {
      throw mismatch(SCOPE + " scratch-scope installation postcondition failed");
    }
  }

  private static void validateEventsPanelContainsSite(ClassNode node, MethodNode method, MethodInsnNode call,
      boolean patched) {
    Frame<SourceValue>[] frames = sourceFrames(node.name, method);
    SourceValue collectionSource =
        patched ? argumentSource(method, call, frames, 0) : receiverSource(method, call, frames);
    SourceValue pluginSource =
        patched ? argumentSource(method, call, frames, 1) : argumentSource(method, call, frames, 0);
    int listLocal = requireLoadedLocal(collectionSource, Opcodes.ALOAD, "missing Intel list");
    int pluginLocal = requireLoadedLocal(pluginSource, Opcodes.ALOAD, "current Intel plugin");
    requireLocalConstructor(method, listLocal, "java/util/ArrayList", "()V", "missing Intel list");
    requireStoredCallResult(method, pluginLocal, -1, null, "getInfo", "()" + INTEL_DESC, "row Intel plugin");
    if (hasMutatorAfter(node.name, method, call, listLocal)) {
      throw mismatch("missing Intel list is mutated after the reconciliation membership site");
    }
  }

  private static int validateEventsPanelExistingIconSite(ClassNode node, MethodNode method, MethodInsnNode call,
      boolean patched) {
    Frame<SourceValue>[] frames = sourceFrames(node.name, method);
    SourceValue mapSource = patched ? argumentSource(method, call, frames, 0) : receiverSource(method, call, frames);
    requireSourceMethod(mapSource, Opcodes.INVOKEVIRTUAL, H, "getIcons", "()Ljava/util/LinkedHashMap;",
        "existing icon map receiver");
    MethodInsnNode entityLookup = only(
        calls(method, Opcodes.INVOKEVIRTUAL, H, "getIntelIconEntity",
            "(" + INTEL_DESC + ")Lcom/fs/starfarer/campaign/CustomCampaignEntity;"),
        "H.getIntelIconEntity in addMissingIconsAndRows");
    AbstractInsnNode storeInsn = nextMeaningful(entityLookup);
    if (!(storeInsn instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) {
      throw mismatch("Intel entity lookup result is not stored in a local");
    }
    int entityLocal = store.var;
    if (!hasNullGuard(method, store, call, entityLocal)) {
      throw mismatch("Intel entity candidate scan is not guarded by an entity null check");
    }
    if (patched) {
      int hookEntityLocal =
          requireLoadedLocal(argumentSource(method, call, frames, 1), Opcodes.ALOAD, "existing icon entity argument");
      if (hookEntityLocal != entityLocal) { throw mismatch("existing icon hook is wired to the wrong entity local"); }
    }
    int comparisons = countIdentityComparisons(method, frames, call, entityLocal, "com/fs/starfarer/coreui/A/ooOO",
        "int", "()" + ENTITY_TOKEN_DESC);
    requireCount("existing icon entity identity comparison", comparisons, 1);
    return entityLocal;
  }
}
