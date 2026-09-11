package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.StarsectorPrepatcherTempModHooks;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;

/** Runtime semantics checks for the expiry-aware MutableStatWithTempMods scheduler. */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class TempModExpiryRuntimeRegressionTest {
  private TempModExpiryRuntimeRegressionTest() {}

  public static void main(String[] args) throws Exception {
    assertNearestExpiryAndDeferredSurvivors();
    assertRandomGameDayDeadlines();
    assertSynchronizationBeforeMutation();
    assertExternalExposureFallsBackToVanilla();
    assertSubclassFallsBackToVanilla();
    assertEmptyStateIsReleased();
    System.out
        .println("OK temp-mod-expiry nearest-deadline/game-day-expiry" + " synchronization-before-add-remove-read-save"
            + " live-map-exposure/subclass-vanilla-fallback empty-state-release");
  }

  private static void assertNearestExpiryAndDeferredSurvivors() {
    FakeOwner owner = new FakeOwner();
    owner.add("first", 1f);
    owner.add("second", 3f);
    StarsectorPrepatcherTempModHooks.State state = null;

    state = scheduledAdvance(owner, owner.mods, state, 0.25f);
    state = scheduledAdvance(owner, owner.mods, state, 0.25f);
    state = scheduledAdvance(owner, owner.mods, state, 0.25f);
    require(owner.rawAdvanceCalls == 0, "scheduler swept before the nearest expiry");
    require(bits(owner.mods.get("second").timeRemaining) == bits(3f),
        "survivor field was materialized during the O(1) path");

    state = scheduledAdvance(owner, owner.mods, state, 0.25f);
    require(owner.rawAdvanceCalls == 1 && owner.rawAdvanceAmounts.equals(List.of(0f)),
        "nearest expiry did not use the retained vanilla zero-delta removal body");
    require(!owner.mods.containsKey("first") && owner.mods.containsKey("second"),
        "nearest expiry removed the wrong entries");
    require(owner.unmodified.equals(List.of("first")), "nearest expiry changed unmodify order/source");
    require(bits(owner.mods.get("second").timeRemaining) == bits(3f),
        "expiry sweep materialized a survivor instead of retaining the absolute baseline");

    state = StarsectorPrepatcherTempModHooks.synchronize(owner, owner.mods, state);
    require(close(owner.mods.get("second").timeRemaining, 2f, 2e-6f),
        "synchronization did not materialize deferred survivor time");
  }

  private static void assertRandomGameDayDeadlines() {
    Random random = new Random(0x5A17E57L);
    for (int run = 0; run < 300; run++) {
      FakeOwner owner = new FakeOwner();
      LinkedHashMap<String, Double> deadlines = new LinkedHashMap<>();
      int count = 1 + random.nextInt(12);
      for (int i = 0; i < count; i++) {
        float duration = 0.01f + random.nextFloat() * 20f;
        String source = "m" + i;
        owner.add(source, duration);
        deadlines.put(source, (double) duration);
      }
      StarsectorPrepatcherTempModHooks.State state = null;
      double elapsed = 0d;
      ArrayList<String> expectedOrder = new ArrayList<>();
      for (int frame = 0; frame < 500 && !owner.mods.isEmpty(); frame++) {
        float days = 0.0001f + random.nextFloat() * 0.12f;
        elapsed += (double) days;
        for (var entry : deadlines.entrySet()) {
          if (entry.getValue() <= elapsed && owner.mods.containsKey(entry.getKey())
              && !expectedOrder.contains(entry.getKey())) {
            expectedOrder.add(entry.getKey());
          }
        }
        state = scheduledAdvance(owner, owner.mods, state, days);
        for (String source : deadlines.keySet()) {
          boolean expectedLive = deadlines.get(source) > elapsed;
          require(owner.mods.containsKey(source) == expectedLive,
              "modifier expiry frame diverged from accumulated game days: run=" + run + " frame=" + frame + " source="
                  + source);
        }
        require(owner.unmodified.equals(expectedOrder), "expiry order diverged from LinkedHashMap/vanilla order");
      }
    }
  }

  private static void assertSynchronizationBeforeMutation() {
    FakeOwner owner = new FakeOwner();
    owner.add("a", 5f);
    owner.add("b", 9f);
    StarsectorPrepatcherTempModHooks.State state = null;
    state = scheduledAdvance(owner, owner.mods, state, 1.25f);
    state = scheduledAdvance(owner, owner.mods, state, 0.75f);
    require(bits(owner.mods.get("a").timeRemaining) == bits(5f), "fast path unexpectedly materialized before mutation");

    // Equivalent of the transformed getMod wrapper: synchronize, mutate, update schedule.
    state = StarsectorPrepatcherTempModHooks.synchronize(owner, owner.mods, state);
    require(close(owner.mods.get("a").timeRemaining, 3f, 2e-6f) && close(owner.mods.get("b").timeRemaining, 7f, 2e-6f),
        "add/update synchronization did not flush elapsed days");
    FakeMod refreshed = owner.add("b", 1.5f);
    state = StarsectorPrepatcherTempModHooks.afterModUpdated(owner.mods, state, refreshed, 1.5f);
    state = scheduledAdvance(owner, owner.mods, state, 1.5f);
    require(!owner.mods.containsKey("b") && owner.mods.containsKey("a"),
        "refreshed modifier did not become the next exact deadline");

    // Equivalent of removeTemporaryMod wrapper.
    state = StarsectorPrepatcherTempModHooks.synchronize(owner, owner.mods, state);
    FakeMod removed = owner.mods.remove("a");
    if (removed != null) owner.unmodify(removed.source);
    state = StarsectorPrepatcherTempModHooks.afterRemove(owner.mods, state);
    require(owner.mods.isEmpty() && state == null, "remove synchronization/post-hook retained empty scheduler state");

    // Equivalent of writeReplace: all values must be materialized before serialization.
    owner.add("save", 6f);
    state = StarsectorPrepatcherTempModHooks.afterModUpdated(owner.mods, state, owner.mods.get("save"), 6f);
    state = scheduledAdvance(owner, owner.mods, state, 2.25f);
    state = StarsectorPrepatcherTempModHooks.synchronize(owner, owner.mods, state);
    state = StarsectorPrepatcherTempModHooks.afterWrite(owner.mods, state);
    require(close(owner.mods.get("save").timeRemaining, 3.75f, 2e-6f),
        "save synchronization did not materialize timeRemaining");
  }

  private static void assertExternalExposureFallsBackToVanilla() {
    FakeOwner owner = new FakeOwner();
    owner.add("a", 4f);
    StarsectorPrepatcherTempModHooks.State state = null;
    state = scheduledAdvance(owner, owner.mods, state, 1f);
    state = StarsectorPrepatcherTempModHooks.synchronize(owner, owner.mods, state);
    state = StarsectorPrepatcherTempModHooks.markExternalAccess(state);
    require(close(owner.mods.get("a").timeRemaining, 3f, 1e-6f),
        "external getMods exposure was not synchronized first");

    // A retained live map can be mutated without another accessor call.
    owner.add("external", 0.5f);
    int before = owner.rawAdvanceCalls;
    state = scheduledAdvance(owner, owner.mods, state, 0.25f);
    state = scheduledAdvance(owner, owner.mods, state, 0.25f);
    require(owner.rawAdvanceCalls == before + 2,
        "externally exposed stat did not remain on exact vanilla per-frame advance");
    require(!owner.mods.containsKey("external"), "live-map mutation was not observed by exposed vanilla fallback");
  }

  private static void assertSubclassFallsBackToVanilla() {
    FakeOwnerSubclass owner = new FakeOwnerSubclass();
    owner.add("a", 1f);
    StarsectorPrepatcherTempModHooks.State state = null;
    state = scheduledAdvance(owner, owner.mods, state, 0.25f);
    state = scheduledAdvance(owner, owner.mods, state, 0.25f);
    require(owner.rawAdvanceCalls == 2, "subclass owner did not fall back to exact vanilla cadence");
    require(close(owner.mods.get("a").timeRemaining, 0.5f, 1e-6f), "subclass vanilla fallback changed remaining time");
  }

  private static void assertEmptyStateIsReleased() {
    FakeOwner owner = new FakeOwner();
    owner.add("a", 0.1f);
    StarsectorPrepatcherTempModHooks.State state = null;
    state = scheduledAdvance(owner, owner.mods, state, 0.1f);
    require(owner.mods.isEmpty() && state == null, "empty non-exposed stat retained synthetic state");
  }

  private static StarsectorPrepatcherTempModHooks.State scheduledAdvance(FakeOwner owner, LinkedHashMap mods,
      StarsectorPrepatcherTempModHooks.State state, float days) {
    state = StarsectorPrepatcherTempModHooks.advance(owner, mods, state, days);
    if (StarsectorPrepatcherTempModHooks.takeVanillaAdvance(state)) {
      owner.spp$originalTempModAdvance(days);
    }
    return state;
  }

  private static int bits(float value) {
    return Float.floatToIntBits(value);
  }

  private static boolean close(float actual, float expected, float tolerance) {
    return Math.abs(actual - expected) <= tolerance;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  public static class FakeOwner {
    final LinkedHashMap<String, FakeMod> mods = new LinkedHashMap<>();
    final ArrayList<String> unmodified = new ArrayList<>();
    final ArrayList<Float> rawAdvanceAmounts = new ArrayList<>();
    int rawAdvanceCalls;

    FakeMod add(String source, float duration) {
      FakeMod mod = mods.get(source);
      if (mod == null) {
        mod = new FakeMod(duration, source);
        mods.put(source, mod);
      }
      mod.timeRemaining = duration;
      return mod;
    }

    void unmodify(String source) {
      unmodified.add(source);
    }

    @SuppressWarnings("unused")
    private void spp$originalTempModAdvance(float days) {
      rawAdvanceCalls++;
      rawAdvanceAmounts.add(days);
      Iterator<FakeMod> iterator = mods.values().iterator();
      while (iterator.hasNext()) {
        FakeMod mod = iterator.next();
        mod.timeRemaining -= days;
        if (mod.timeRemaining <= 0f) {
          iterator.remove();
          unmodify(mod.source);
        }
      }
    }
  }

  public static final class FakeOwnerSubclass extends FakeOwner {}

  public static final class FakeMod {
    float timeRemaining;
    final String source;

    FakeMod(float timeRemaining, String source) {
      this.timeRemaining = timeRemaining;
      this.source = source;
    }
  }
}
