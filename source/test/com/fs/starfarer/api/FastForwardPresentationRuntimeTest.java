package com.fs.starfarer.api;

import com.starsector.prepatcher.agent.PrepatcherConfig;

import java.lang.reflect.Constructor;
import java.util.Properties;

/**
 * In-process scheduler and selection-bridge regression tests. The obfuscated
 * selection-indicator class requires {@code -noverify}, as does the game launcher.
 */
public final class FastForwardPresentationRuntimeTest {
  private static final float AMOUNT = 0.025f;

  private FastForwardPresentationRuntimeTest() {}

  public static void main(String[] args) throws Exception {
    // This must be set before the hooks class is initialized: test-mode is
    // intentionally immutable in a production game process.
    System.setProperty("starsector.prepatcher.presentation.testMode", "true");

    verifyRealtimeScheduler();
    verifySimulationScheduler();
    verifyMismatchFailOpenAndReset();
    verifyAbandonedFrameRecovery();
    verifyDisabledFailOpen();
    verifySelectionBridge();

    StarsectorPrepatcherPresentationHooks.endOuterFrame();
    System.out.println(
        "OK fast-forward presentation runtime scheduler" + " single-step intermediate-final realtime simulation"
            + " mismatch-latch reset abandoned-frame-recovery" + " disabled selection-bridge");
  }

  private static void verifyRealtimeScheduler() throws Exception {
    StarsectorPrepatcherPresentationHooks.configure(config(true, false));
    StarsectorPrepatcherPresentationHooks.endOuterFrame();
    require(StarsectorPrepatcherPresentationHooks.testShouldRunPresentation(),
        "reset state must fail open outside an outer frame");

    StarsectorPrepatcherPresentationHooks.beginOuterFrame(1);
    StarsectorPrepatcherPresentationHooks.testSetFastForwardFlag(false);
    StarsectorPrepatcherPresentationHooks.beforeSubstep(0, 1);
    require(StarsectorPrepatcherPresentationHooks.testShouldRunPresentation(),
        "single-step frame must run presentation");
    require(bits(StarsectorPrepatcherPresentationHooks.testEffectiveAmount(AMOUNT)) == bits(AMOUNT),
        "single-step realtime amount changed");
    StarsectorPrepatcherPresentationHooks.endOuterFrame();

    StarsectorPrepatcherPresentationHooks.beginOuterFrame(3);
    StarsectorPrepatcherPresentationHooks.testSetFastForwardFlag(false);
    StarsectorPrepatcherPresentationHooks.beforeSubstep(0, 3);
    require(!StarsectorPrepatcherPresentationHooks.testShouldRunPresentation(),
        "first of three substeps was not coalesced");
    require(bits(StarsectorPrepatcherPresentationHooks.testEffectiveAmount(AMOUNT)) == bits(AMOUNT),
        "intermediate realtime amount changed");

    StarsectorPrepatcherPresentationHooks.testSetFastForwardFlag(true);
    StarsectorPrepatcherPresentationHooks.beforeSubstep(1, 3);
    require(!StarsectorPrepatcherPresentationHooks.testShouldRunPresentation(), "middle substep was not coalesced");

    StarsectorPrepatcherPresentationHooks.testSetFastForwardFlag(true);
    StarsectorPrepatcherPresentationHooks.beforeSubstep(2, 3);
    require(StarsectorPrepatcherPresentationHooks.testShouldRunPresentation(),
        "final substep did not run presentation");
    require(bits(StarsectorPrepatcherPresentationHooks.testEffectiveAmount(AMOUNT)) == bits(AMOUNT),
        "final realtime amount changed");
    StarsectorPrepatcherPresentationHooks.endOuterFrame();
  }

  private static void verifySimulationScheduler() throws Exception {
    StarsectorPrepatcherPresentationHooks.configure(config(true, true));
    StarsectorPrepatcherPresentationHooks.beginOuterFrame(3);

    StarsectorPrepatcherPresentationHooks.testSetFastForwardFlag(false);
    StarsectorPrepatcherPresentationHooks.beforeSubstep(0, 3);
    require(!StarsectorPrepatcherPresentationHooks.testShouldRunPresentation(),
        "simulation first substep was not coalesced");
    require(bits(StarsectorPrepatcherPresentationHooks.testEffectiveAmount(AMOUNT)) == bits(AMOUNT),
        "simulation time was accumulated before the final substep");

    StarsectorPrepatcherPresentationHooks.testSetFastForwardFlag(true);
    StarsectorPrepatcherPresentationHooks.beforeSubstep(1, 3);
    require(!StarsectorPrepatcherPresentationHooks.testShouldRunPresentation(),
        "simulation middle substep was not coalesced");

    StarsectorPrepatcherPresentationHooks.testSetFastForwardFlag(true);
    StarsectorPrepatcherPresentationHooks.beforeSubstep(2, 3);
    require(StarsectorPrepatcherPresentationHooks.testShouldRunPresentation(),
        "simulation final substep did not run presentation");
    require(bits(StarsectorPrepatcherPresentationHooks.testEffectiveAmount(AMOUNT)) == bits(AMOUNT * 3f),
        "simulation final amount did not cover all substeps");
    StarsectorPrepatcherPresentationHooks.endOuterFrame();
  }

  private static void verifyMismatchFailOpenAndReset() throws Exception {
    StarsectorPrepatcherPresentationHooks.configure(config(true, true));
    StarsectorPrepatcherPresentationHooks.beginOuterFrame(3);

    StarsectorPrepatcherPresentationHooks.testSetFastForwardFlag(true);
    StarsectorPrepatcherPresentationHooks.beforeSubstep(0, 3);
    require(StarsectorPrepatcherPresentationHooks.testShouldRunPresentation(), "flag mismatch did not fail open");

    StarsectorPrepatcherPresentationHooks.testSetFastForwardFlag(true);
    StarsectorPrepatcherPresentationHooks.beforeSubstep(1, 3);
    require(StarsectorPrepatcherPresentationHooks.testShouldRunPresentation(),
        "flag mismatch was not latched for the outer frame");

    StarsectorPrepatcherPresentationHooks.testSetFastForwardFlag(true);
    StarsectorPrepatcherPresentationHooks.beforeSubstep(2, 3);
    require(StarsectorPrepatcherPresentationHooks.testShouldRunPresentation(),
        "latched mismatch stopped failing open at the final substep");
    require(bits(StarsectorPrepatcherPresentationHooks.testEffectiveAmount(AMOUNT)) == bits(AMOUNT),
        "mismatched frame incorrectly accumulated simulation time");
    StarsectorPrepatcherPresentationHooks.endOuterFrame();
    require(StarsectorPrepatcherPresentationHooks.testShouldRunPresentation(),
        "endOuterFrame did not reset to fail-open state");

    StarsectorPrepatcherPresentationHooks.beginOuterFrame(2);
    StarsectorPrepatcherPresentationHooks.testSetFastForwardFlag(false);
    StarsectorPrepatcherPresentationHooks.beforeSubstep(0, 2);
    require(!StarsectorPrepatcherPresentationHooks.testShouldRunPresentation(),
        "new outer frame retained the previous mismatch latch");
    StarsectorPrepatcherPresentationHooks.endOuterFrame();

    StarsectorPrepatcherPresentationHooks.beginOuterFrame(3);
    StarsectorPrepatcherPresentationHooks.testSetFastForwardFlag(false);
    StarsectorPrepatcherPresentationHooks.beforeSubstep(0, 3);
    require(!StarsectorPrepatcherPresentationHooks.testShouldRunPresentation(),
        "valid first substep was not coalesced before a late mismatch");
    StarsectorPrepatcherPresentationHooks.testSetFastForwardFlag(false);
    StarsectorPrepatcherPresentationHooks.beforeSubstep(1, 3);
    require(StarsectorPrepatcherPresentationHooks.testShouldRunPresentation(),
        "late flag mismatch did not stop further coalescing");
    StarsectorPrepatcherPresentationHooks.testSetFastForwardFlag(true);
    StarsectorPrepatcherPresentationHooks.beforeSubstep(2, 3);
    require(StarsectorPrepatcherPresentationHooks.testShouldRunPresentation(),
        "late flag mismatch did not remain latched");
    require(bits(StarsectorPrepatcherPresentationHooks.testEffectiveAmount(AMOUNT)) == bits(AMOUNT),
        "late mismatched frame incorrectly accumulated simulation time");
    StarsectorPrepatcherPresentationHooks.endOuterFrame();

    StarsectorPrepatcherPresentationHooks.beginOuterFrame(3);
    StarsectorPrepatcherPresentationHooks.testSetFastForwardFlag(false);
    StarsectorPrepatcherPresentationHooks.beforeSubstep(0, 2);
    require(StarsectorPrepatcherPresentationHooks.testShouldRunPresentation(),
        "changed totalSteps marker contract did not fail open");
    StarsectorPrepatcherPresentationHooks.endOuterFrame();
  }

  private static void verifyDisabledFailOpen() throws Exception {
    StarsectorPrepatcherPresentationHooks.configure(config(false, false));
    StarsectorPrepatcherPresentationHooks.beginOuterFrame(2);
    StarsectorPrepatcherPresentationHooks.testSetFastForwardFlag(false);
    StarsectorPrepatcherPresentationHooks.beforeSubstep(0, 2);
    require(StarsectorPrepatcherPresentationHooks.testShouldRunPresentation(),
        "disabled runtime suppressed presentation");
    require(bits(StarsectorPrepatcherPresentationHooks.testEffectiveAmount(AMOUNT)) == bits(AMOUNT),
        "disabled runtime changed presentation time");
    StarsectorPrepatcherPresentationHooks.endOuterFrame();
  }

  private static void verifyAbandonedFrameRecovery() throws Exception {
    StarsectorPrepatcherPresentationHooks.configure(config(true, true));
    StarsectorPrepatcherPresentationHooks.beginOuterFrame(3);
    StarsectorPrepatcherPresentationHooks.testSetFastForwardFlag(true);
    StarsectorPrepatcherPresentationHooks.beforeSubstep(0, 3);
    require(StarsectorPrepatcherPresentationHooks.testShouldRunPresentation(),
        "abandoned-frame fixture did not latch a mismatch");

    // Simulate an exception escaping CampaignState.advance before its final
    // marker. The next begin marker is the unwind/recovery boundary.
    StarsectorPrepatcherPresentationHooks.beginOuterFrame(2);
    StarsectorPrepatcherPresentationHooks.testSetFastForwardFlag(false);
    StarsectorPrepatcherPresentationHooks.beforeSubstep(0, 2);
    require(!StarsectorPrepatcherPresentationHooks.testShouldRunPresentation(),
        "new outer frame retained abandoned active/mismatch state");
    require(bits(StarsectorPrepatcherPresentationHooks.testEffectiveAmount(AMOUNT)) == bits(AMOUNT),
        "new outer frame retained abandoned accumulated time");

    StarsectorPrepatcherPresentationHooks.testSetFastForwardFlag(true);
    StarsectorPrepatcherPresentationHooks.beforeSubstep(1, 2);
    require(StarsectorPrepatcherPresentationHooks.testShouldRunPresentation(),
        "recovered frame did not run its final presentation substep");
    require(bits(StarsectorPrepatcherPresentationHooks.testEffectiveAmount(AMOUNT)) == bits(AMOUNT * 2f),
        "recovered frame retained the abandoned totalSteps value");
    StarsectorPrepatcherPresentationHooks.endOuterFrame();
  }

  private static void verifySelectionBridge() throws Exception {
    StarsectorPrepatcherPresentationHooks.configure(config(true, false));
    require(StarsectorPrepatcherPresentationHooks.testResolveSelectionIndicatorBridge(),
        "selection-indicator MethodHandle was not resolved");
  }

  private static PrepatcherConfig config(boolean enabled, boolean simulation) throws Exception {
    Properties properties = new Properties();
    properties.setProperty("patch.fastForwardPresentation", Boolean.toString(enabled));
    properties.setProperty("patch.fastForwardFrameMarker", "true");
    properties.setProperty("fastForward.metrics", "false");
    properties.setProperty("fastForward.visualTime", simulation ? "simulation" : "realtime");
    Constructor<PrepatcherConfig> constructor = PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
    constructor.setAccessible(true);
    return constructor.newInstance(properties);
  }

  private static int bits(float value) {
    return Float.floatToIntBits(value);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
