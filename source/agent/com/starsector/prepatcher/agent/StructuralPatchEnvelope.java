package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
import com.starsector.prepatcher.agent.patch.AlreadyPatchedException;
import com.starsector.prepatcher.agent.patch.CompositionMismatchException;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.presentation.PresentationStructuralContract;
import com.starsector.prepatcher.agent.patch.StructuralMismatchException;
import com.starsector.prepatcher.agent.patch.StructuredPatch;


/** Application envelope for structural patches: transactional apply, probes and postconditions. */
final class StructuralPatchEnvelope {
  private final PrepatcherConfig config;
  private final AtomicInteger appliedPatches = new AtomicInteger();
  private final AtomicInteger skippedPatches = new AtomicInteger();
  private final AtomicInteger compositionFailures = new AtomicInteger();
  private final AtomicInteger alreadyPatched = new AtomicInteger();

  StructuralPatchEnvelope(PrepatcherConfig config) {
    this.config = config;
  }

  @FunctionalInterface
  interface PatchPostcondition {
    void validate(byte[] bytes);
  }

  static final class TransformState {
    final String className;
    final byte[] incomingBytes;
    byte[] bytes;
    boolean changed;
    boolean compositionFailed;
    final List<String> appliedDetails = new ArrayList<>();
    final List<AppliedPatch> applied = new ArrayList<>();
    final PresentationStructuralContract.State presentationComposition;

    TransformState(String className, byte[] bytes, PresentationStructuralContract.State presentationComposition) {
      this.className = className;
      this.incomingBytes = bytes;
      this.bytes = bytes;
      this.presentationComposition = presentationComposition;
    }
  }

  record AppliedPatch(String patchId, PatchPostcondition postcondition, String detail, boolean newlyApplied) {
  }

  <P> void apply(TransformState state, String patchId, boolean enabled, StructuredPatch<P> patch) {
    if (!enabled) return;
    boolean markerPresent = false;
    boolean actionCompleted = false;
    PatchPostcondition postcondition = postconditionFor(patch, patchId);
    try {
      ClassNode node = readClass(state.bytes);
      Set<String> publicApi = publicApi(node);
      markerPresent = hasPatchMarker(node, patchId);
      Match<P> match = patch.match(node);
      if (match.alreadyApplied) throw already(match.reason);
      if (match.plan == null) throw mismatch(match.reason);
      PatchReport report = patch.mutate(match.plan);
      patch.verify(match.plan, node);
      actionCompleted = true;
      if (report.total == 0) { throw mismatch("patch reported no changed sites"); }
      if (markerPresent) { throw mismatch("optimizer marker is present but the structural postcondition is not"); }
      addPatchMarker(node, patchId);
      if (!publicApi.equals(publicApi(node))) {
        throw mismatch("public/protected API surface changed before serialization");
      }

      byte[] candidate = writeClass(node);
      verifyClass(candidate);
      ClassNode reparsed = readClass(candidate);
      if (!publicApi.equals(publicApi(reparsed))) {
        throw mismatch("public/protected API surface changed after serialization");
      }
      postcondition.validate(candidate);
      validateEarlierPostconditions(state, candidate, patchId);

      state.bytes = candidate;
      state.changed = true;
      String detail = patchId + "{" + report + "}";
      state.appliedDetails.add(detail);
      state.applied.add(new AppliedPatch(patchId, postcondition, detail, true));
    } catch (AlreadyPatchedException ex) {
      if (!markerPresent) {
        recordStructuralSkip(state.className, patchId,
            "hook-shaped postcondition exists without this optimizer's structural marker");
      } else {
        state.applied.add(new AppliedPatch(patchId, postcondition, null, false));
        int count = alreadyPatched.incrementAndGet();
        System.setProperty("starsector.prepatcher.alreadyPatched", Integer.toString(count));
        recordStatus(state.className, patchId, "ALREADY_APPLIED");
        PrepatcherLog.info("ALREADY_APPLIED " + patchId + " in " + state.className + ": " + ex.getMessage());
      }
    } catch (CompositionMismatchException ex) {
      recordCompositionSkip(state, patchId, ex.getMessage());
    } catch (StructuralMismatchException ex) {
      if (!actionCompleted && wasApplicableBeforeEarlierPatches(state, patchId, patch)) {
        recordCompositionSkip(state, patchId,
            "the patch matched the incoming class but no longer matches after earlier " + "patches: "
                + ex.getMessage());
      } else {
        recordStructuralSkip(state.className, patchId, ex.getMessage());
      }
    } catch (Throwable ex) {
      int count = skippedPatches.incrementAndGet();
      System.setProperty("starsector.prepatcher.skippedPatches", Integer.toString(count));
      recordRejectedStatus(state.className, patchId, "SKIPPED_ERROR");
      PrepatcherLog.error(
          "SKIPPED_ERROR " + patchId + " in " + state.className + "; the preceding class bytes remain active.", ex);
    }
  }

  void recordStructuralSkip(String className, String patchId, String reason) {
    int count = skippedPatches.incrementAndGet();
    System.setProperty("starsector.prepatcher.skippedPatches", Integer.toString(count));
    recordRejectedStatus(className, patchId, "SKIPPED_STRUCTURAL");
    PrepatcherLog.warn("SKIPPED_STRUCTURAL " + patchId + " in " + className + ": " + reason);
  }

  private static PatchPostcondition postconditionFor(StructuredPatch<?> patch, String patchId) {
    return bytes -> verifyPatchPostcondition(patch, bytes, patchId);
  }

  private static void verifyPatchPostcondition(StructuredPatch<?> patch, byte[] bytes, String patchId) {
    ClassNode validationNode = readClass(bytes);
    if (!hasPatchMarker(validationNode, patchId)) {
      throw mismatch(patchId + " structural marker is missing from the candidate class");
    }
    try {
      Match<?> match = patch.match(validationNode);
      if (match.plan != null) { throw mismatch(patchId + " remains patchable after serialization"); }
      if (!match.alreadyApplied) { throw mismatch(match.reason); }
    } catch (AlreadyPatchedException expected) {
      // Match proves the contract without mutating, so the throwaway ClassNode is
      // needed only as match's input: it cannot corrupt the candidate bytes.
      // Composite plans may still surface the boundary signal from a component
      // probe; both refusal shapes mean the postcondition holds.
    }
  }

  private void validateEarlierPostconditions(TransformState state, byte[] candidate, String currentPatchId) {
    try {
      state.presentationComposition.validate(candidate, config);
    } catch (Throwable ex) {
      throw composition("applying " + currentPatchId
          + " invalidated the earlier fast-forward presentation transformation: " + failureMessage(ex));
    }
    for (AppliedPatch previous : state.applied) {
      try {
        previous.postcondition.validate(candidate);
      } catch (Throwable ex) {
        throw composition("applying " + currentPatchId + " invalidated earlier patch " + previous.patchId + ": "
            + failureMessage(ex));
      }
    }
  }

  boolean validateFinalComposition(TransformState state) {
    try {
      state.presentationComposition.validate(state.bytes, config);
      for (AppliedPatch patch : state.applied) {
        patch.postcondition.validate(state.bytes);
      }
      if (!state.applied.isEmpty() && !state.compositionFailed) {
        recordStatus(state.className, "composition", "PASSED");
      }
      if (state.presentationComposition.present() && !state.compositionFailed) {
        recordStatus(state.className, "presentationStructuralComposition", "PASSED");
      }
      return true;
    } catch (Throwable ex) {
      recordStatus(state.className, "composition", "FAILED");
      for (AppliedPatch patch : state.applied) {
        if (patch.newlyApplied) {
          recordStatus(state.className, patch.patchId, "ROLLED_BACK_COMPOSITION");
        }
      }
      int count = compositionFailures.incrementAndGet();
      System.setProperty("starsector.prepatcher.compositionFailures", Integer.toString(count));
      PrepatcherLog.error("COMPOSITION_FAILED final validation for " + state.className
          + "; all structural changes made by this transformer invocation are rolled "
          + "back to the incoming class bytes.", ex);
      return false;
    }
  }

  void finalizeAppliedPatches(TransformState state) {
    for (AppliedPatch patch : state.applied) {
      if (!patch.newlyApplied) continue;
      TransformDiagnostics.onCommit(() -> {
        int count = appliedPatches.incrementAndGet();
        System.setProperty("starsector.prepatcher.appliedPatches", Integer.toString(count));
      });
      recordStatus(state.className, patch.patchId, "APPLIED");
      PrepatcherLog.info("APPLIED " + patch.patchId + " to " + state.className + ": " + patch.detail);
    }
  }

  private static boolean wasApplicableBeforeEarlierPatches(TransformState state, String patchId,
      StructuredPatch<?> patch) {
    if (!state.changed || state.applied.isEmpty()) return false;
    try {
      ClassNode baseline = readClass(state.incomingBytes);
      if (hasPatchMarker(baseline, patchId)) return false;
      return patch.match(baseline).plan != null;
    } catch (Throwable ex) {
      return false;
    }
  }

  void recordCompositionSkip(TransformState state, String patchId, String reason) {
    state.compositionFailed = true;
    int skipped = skippedPatches.incrementAndGet();
    System.setProperty("starsector.prepatcher.skippedPatches", Integer.toString(skipped));
    int failures = compositionFailures.incrementAndGet();
    System.setProperty("starsector.prepatcher.compositionFailures", Integer.toString(failures));
    recordRejectedStatus(state.className, patchId, "SKIPPED_COMPOSITION");
    recordStatus(state.className, "composition", "FAILED");
    PrepatcherLog.warn("SKIPPED_COMPOSITION " + patchId + " in " + state.className + ": " + reason
        + "; the preceding class bytes remain active.");
  }

  private static String failureMessage(Throwable ex) {
    String message = ex.getMessage();
    return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
  }

  void recordPresentationCompositionSkip(String className, Throwable failure) {
    int skipped = skippedPatches.incrementAndGet();
    System.setProperty("starsector.prepatcher.skippedPatches", Integer.toString(skipped));
    int failures = compositionFailures.incrementAndGet();
    System.setProperty("starsector.prepatcher.compositionFailures", Integer.toString(failures));
    recordRejectedStatus(className, "presentationStructuralComposition", "SKIPPED_COMPOSITION");
    recordStatus(className, "composition", "FAILED");
    PrepatcherLog.warn("SKIPPED_COMPOSITION presentation/structural contract in " + className + ": "
        + failureMessage(failure) + "; incoming class bytes remain active.");
  }

  static void recordRejectedStatus(String className, String patchId, String status) {
    TransformDiagnostics.reject("starsector.prepatcher.patchStatus." + className.replace('/', '.') + "." + patchId,
        status);
  }

  static void recordStatus(String className, String patchId, String status) {
    TransformDiagnostics.status("starsector.prepatcher.patchStatus." + className.replace('/', '.') + "." + patchId,
        status);
  }
}
