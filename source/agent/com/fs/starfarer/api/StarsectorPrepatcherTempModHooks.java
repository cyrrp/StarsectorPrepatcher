package com.fs.starfarer.api;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Expiry-aware scheduler for {@code MutableStatWithTempMods}.
 *
 * <p>The transformed game class retains the exact vanilla {@code advance(float)} body as a
 * private synthetic method. While the modifier map is private, the scheduler advances one
 * absolute elapsed-days clock in O(1). It scans the map only when the nearest duration is due or
 * when an operation must expose fully materialized {@code timeRemaining} values.</p>
 *
 * <p>Before add/update, remove, public {@code getMods()} and serialization, all deferred elapsed
 * days are written back. {@code hasMod()} needs only current membership: due entries are already
 * removed synchronously by {@code advance()}, so it remains allocation-free and does not force a
 * materialization sweep. Public {@code getMods()} permanently places that stat
 * instance on the exact vanilla path because the returned live map may be retained and mutated
 * without another observable invalidation call.</p>
 */
public final class StarsectorPrepatcherTempModHooks {
  public static final String ORIGINAL_ADVANCE_NAME = "spp$originalTempModAdvance";
  private static final int COUNTER_BATCH = 256;

  private static final Map<Class<?>, Method> ORIGINAL_ADVANCE = new WeakHashMap<>();
  private static volatile Field timeRemainingField;

  private static final LongAdder ADVANCES = new LongAdder();
  private static final LongAdder FAST_SKIPS = new LongAdder();
  private static final LongAdder INITIAL_SWEEPS = new LongAdder();
  private static final LongAdder EXPIRY_SWEEPS = new LongAdder();
  private static final LongAdder SYNCHRONIZATION_SWEEPS = new LongAdder();
  private static final LongAdder MODS_SCANNED = new LongAdder();
  private static final LongAdder MODS_EXPIRED = new LongAdder();
  private static final LongAdder EXTERNAL_EXPOSURES = new LongAdder();
  private static final LongAdder EXPOSED_VANILLA_ADVANCES = new LongAdder();
  private static final LongAdder DISABLED_VANILLA_ADVANCES = new LongAdder();
  private static final LongAdder STATE_ALLOCATIONS = new LongAdder();
  private static final LongAdder REBUILDS = new LongAdder();
  private static final LongAdder SUBCLASS_FALLBACKS = new LongAdder();
  private static final LongAdder FAILURE_FALLBACKS = new LongAdder();

  private StarsectorPrepatcherTempModHooks() {}

  // Current hybrid uses direct scalar state inside MutableStatWithTempMods.
  // Only uncommon sweep/fallback boundaries update LongAdder counters; the
  // O(1) fast path performs no external call and no atomic operation.
  public static void recordHybridInitialSweep(int scanned, int expired) {
    INITIAL_SWEEPS.increment();
    if (scanned > 0) MODS_SCANNED.add(scanned);
    if (expired > 0) MODS_EXPIRED.add(expired);
  }

  public static void recordHybridExpirySweep(int scanned, int expired) {
    EXPIRY_SWEEPS.increment();
    if (scanned > 0) MODS_SCANNED.add(scanned);
    if (expired > 0) MODS_EXPIRED.add(expired);
  }

  public static void recordHybridSynchronizationSweep(int scanned, int expired) {
    SYNCHRONIZATION_SWEEPS.increment();
    if (scanned > 0) MODS_SCANNED.add(scanned);
    if (expired > 0) MODS_EXPIRED.add(expired);
  }

  public static void recordHybridScheduleRebuild(int scanned) {
    REBUILDS.increment();
    if (scanned > 0) MODS_SCANNED.add(scanned);
  }

  public static void recordHybridExternalExposure() {
    EXTERNAL_EXPOSURES.increment();
  }

  public static void recordHybridSubclassFallback() {
    SUBCLASS_FALLBACKS.increment();
  }

  public static void recordHybridFailureFallback() {
    FAILURE_FALLBACKS.increment();
  }

  /** Lazily allocated only for stats that actually contain modifiers or expose getMods(). */
  public static final class State {
    /** Elapsed game days since the modifier fields were last materialized. */
    double elapsedDays;
    /** Smallest base timeRemaining among live finite modifiers. */
    double nextDeadline = Double.POSITIVE_INFINITY;
    Object nearest;
    int knownSize = -1;
    boolean valid;
    boolean exposed;
    boolean disabled;
    boolean ownerChecked;
    boolean vanillaAdvanceRequired;
    int pendingFastAdvances;
    int pendingExposedAdvances;
    int pendingDisabledAdvances;
  }

  @SuppressWarnings("rawtypes")
  public static State advance(Object owner, LinkedHashMap mods, State state, float days) {
    if (mods == null || mods.isEmpty()) {
      if (state == null) return null;
      flushPendingCounters(state);
      resetSchedule(state);
      return state.exposed || state.disabled ? state : null;
    }
    if (state == null) state = newState();
    state.vanillaAdvanceRequired = false;

    if (!checkExactOwner(owner, state)) {
      requireDisabledVanillaAdvance(state);
      return state;
    }
    if (state.exposed) {
      requireExposedVanillaAdvance(state);
      return state;
    }
    if (state.disabled) {
      requireDisabledVanillaAdvance(state);
      return state;
    }

    // Negative, zero, NaN and infinite deltas are uncommon and have awkward edge semantics.
    // Materialize first, then use the exact vanilla body for that call.
    if (!(days > 0f) || !Float.isFinite(days)) {
      state = synchronize(owner, mods, state);
      if (state == null) state = newState();
      requireDisabledVanillaAdvance(state);
      state.valid = false;
      return state;
    }

    if (!state.valid) {
      if (!rebuildSchedule(mods, state)) {
        state.disabled = true;
        FAILURE_FALLBACKS.increment();
        requireDisabledVanillaAdvance(state);
        return state;
      }
    } else if (state.knownSize != mods.size()) {
      // This is impossible through the wrapped API and indicates direct/foreign mutation.
      // Flush known elapsed time, then stay on vanilla for this particular stat instance.
      state = synchronize(owner, mods, state);
      if (state == null) state = newState();
      state.disabled = true;
      FAILURE_FALLBACKS.increment();
      requireDisabledVanillaAdvance(state);
      return state;
    }

    double elapsed = state.elapsedDays + (double) days;
    state.elapsedDays = elapsed;
    if (Double.isFinite(elapsed) && (state.nextDeadline > elapsed || !Double.isFinite(state.nextDeadline))) {
      recordFastAdvance(state);
      return state;
    }

    ADVANCES.increment();
    flushPendingCounters(state);
    if (!expireDue(owner, mods, state)) {
      fallbackApplyElapsed(owner, state);
      state.disabled = true;
      FAILURE_FALLBACKS.increment();
    }
    if (mods.isEmpty() && !state.exposed && !state.disabled) return null;
    return state;
  }

  /**
   * Consumes the one-shot exact-vanilla request produced by {@link #advance}. The transformed
   * wrapper invokes its retained private original body directly with {@code invokespecial}; this
   * keeps externally exposed, subclass and fail-open instances off the reflective hot path.
   */
  public static boolean takeVanillaAdvance(State state) {
    if (state == null || !state.vanillaAdvanceRequired) return false;
    state.vanillaAdvanceRequired = false;
    return true;
  }

  /** Applies deferred days before any operation that must observe current timeRemaining. */
  @SuppressWarnings("rawtypes")
  public static State synchronize(Object owner, LinkedHashMap mods, State state) {
    if (mods == null || mods.isEmpty()) {
      if (state == null) return null;
      resetSchedule(state);
      return state.exposed || state.disabled ? state : null;
    }
    if (state == null) state = newState();
    if (!checkExactOwner(owner, state)) return state;

    if (state.exposed || state.disabled) {
      // These modes should never accumulate. Retain a defensive flush for partial transforms
      // or an exception between accumulation and a mode transition.
      if (state.elapsedDays != 0d) fallbackApplyElapsed(owner, state);
      return state;
    }

    if (state.elapsedDays != 0d) {
      flushPendingCounters(state);
      if (!materialize(owner, mods, state)) {
        fallbackApplyElapsed(owner, state);
        state.disabled = true;
        FAILURE_FALLBACKS.increment();
      }
      return state;
    }
    if (!state.valid || state.knownSize != mods.size()) {
      if (!rebuildSchedule(mods, state)) {
        state.disabled = true;
        FAILURE_FALLBACKS.increment();
      }
    }
    return state;
  }

  /**
   * Marks the live map as externally exposed. Future calls use exact vanilla sweeping because a
   * mod can retain and mutate the map without calling back into the transformed class.
   */
  public static State markExternalAccess(State state) {
    if (state == null) state = newState();
    flushPendingCounters(state);
    if (!state.exposed) EXTERNAL_EXPOSURES.increment();
    state.exposed = true;
    resetSchedule(state);
    return state;
  }

  /** Updates the nearest deadline after getMod() creates or refreshes an entry. */
  @SuppressWarnings("rawtypes")
  public static State afterModUpdated(LinkedHashMap mods, State state, Object mod, float duration) {
    if (mods == null || mods.isEmpty()) return state;
    if (state == null) state = newState();
    if (state.exposed || state.disabled) return state;
    if (state.elapsedDays != 0d) {
      // The transformed wrapper synchronizes before mutation. A non-zero value means a
      // foreign/partial transform; prefer exact vanilla from now on.
      state.disabled = true;
      FAILURE_FALLBACKS.increment();
      return state;
    }

    int size = mods.size();
    if (!state.valid || (state.knownSize != size && state.knownSize + 1 != size)) {
      if (!rebuildSchedule(mods, state)) state.disabled = true;
      return state;
    }

    double value = (double) duration;
    if (size == 1) {
      state.knownSize = 1;
      state.valid = true;
      if (Float.isNaN(duration)) {
        state.nearest = null;
        state.nextDeadline = Double.POSITIVE_INFINITY;
      } else {
        state.nearest = mod;
        state.nextDeadline = value;
      }
      return state;
    }

    if (state.nearest == mod) {
      if (!Float.isNaN(duration) && value <= state.nextDeadline) {
        state.nextDeadline = value;
      } else if (!rebuildSchedule(mods, state)) {
        state.disabled = true;
      }
    } else if (!Float.isNaN(duration) && value < state.nextDeadline) {
      state.nearest = mod;
      state.nextDeadline = value;
    }
    state.knownSize = size;
    return state;
  }

  /** Removal is uncommon; rebuild immediately after the synchronized vanilla removal. */
  @SuppressWarnings("rawtypes")
  public static State afterRemove(LinkedHashMap mods, State state) {
    if (state == null) return null;
    if (mods == null || mods.isEmpty()) {
      flushPendingCounters(state);
      resetSchedule(state);
      return state.exposed || state.disabled ? state : null;
    }
    if (!state.exposed && !state.disabled && !rebuildSchedule(mods, state)) {
      state.disabled = true;
      FAILURE_FALLBACKS.increment();
    }
    return state;
  }

  @SuppressWarnings("rawtypes")
  public static State afterWrite(LinkedHashMap mods, State state) {
    flushPendingCounters(state);
    if (mods == null || mods.isEmpty()) return null;
    return state;
  }

  public static String statsAndReset() {
    return "tempModAdvances=" + ADVANCES.sumThenReset() + ", tempModInitialSweeps=" + INITIAL_SWEEPS.sumThenReset()
        + ", tempModFastSkips=" + FAST_SKIPS.sumThenReset() + ", tempModExpirySweeps=" + EXPIRY_SWEEPS.sumThenReset()
        + ", tempModSyncSweeps=" + SYNCHRONIZATION_SWEEPS.sumThenReset() + ", tempModsScanned="
        + MODS_SCANNED.sumThenReset() + ", tempModsExpired=" + MODS_EXPIRED.sumThenReset()
        + ", tempModExternalExposures=" + EXTERNAL_EXPOSURES.sumThenReset() + ", tempModExposedVanillaAdvances="
        + EXPOSED_VANILLA_ADVANCES.sumThenReset() + ", tempModDisabledVanillaAdvances="
        + DISABLED_VANILLA_ADVANCES.sumThenReset() + ", tempModSubclassFallbacks=" + SUBCLASS_FALLBACKS.sumThenReset()
        + ", tempModFailureFallbacks=" + FAILURE_FALLBACKS.sumThenReset() + ", tempModStateAllocations="
        + STATE_ALLOCATIONS.sumThenReset() + ", tempModScheduleRebuilds=" + REBUILDS.sumThenReset();
  }

  private static State newState() {
    STATE_ALLOCATIONS.increment();
    return new State();
  }

  private static void requireExposedVanillaAdvance(State state) {
    state.vanillaAdvanceRequired = true;
    int pending = state.pendingExposedAdvances + 1;
    if (pending >= COUNTER_BATCH) {
      ADVANCES.add(pending);
      EXPOSED_VANILLA_ADVANCES.add(pending);
      pending = 0;
    }
    state.pendingExposedAdvances = pending;
  }

  private static void requireDisabledVanillaAdvance(State state) {
    state.vanillaAdvanceRequired = true;
    int pending = state.pendingDisabledAdvances + 1;
    if (pending >= COUNTER_BATCH) {
      ADVANCES.add(pending);
      DISABLED_VANILLA_ADVANCES.add(pending);
      pending = 0;
    }
    state.pendingDisabledAdvances = pending;
  }

  private static void recordFastAdvance(State state) {
    int pending = state.pendingFastAdvances + 1;
    if (pending >= COUNTER_BATCH) {
      ADVANCES.add(pending);
      FAST_SKIPS.add(pending);
      pending = 0;
    }
    state.pendingFastAdvances = pending;
  }

  /** Flushes diagnostic counters only at an uncommon synchronization/sweep boundary. */
  private static void flushPendingCounters(State state) {
    if (state == null) return;
    int fast = state.pendingFastAdvances;
    if (fast != 0) {
      ADVANCES.add(fast);
      FAST_SKIPS.add(fast);
      state.pendingFastAdvances = 0;
    }
    int exposed = state.pendingExposedAdvances;
    if (exposed != 0) {
      ADVANCES.add(exposed);
      EXPOSED_VANILLA_ADVANCES.add(exposed);
      state.pendingExposedAdvances = 0;
    }
    int disabled = state.pendingDisabledAdvances;
    if (disabled != 0) {
      ADVANCES.add(disabled);
      DISABLED_VANILLA_ADVANCES.add(disabled);
      state.pendingDisabledAdvances = 0;
    }
  }

  private static boolean checkExactOwner(Object owner, State state) {
    if (state.ownerChecked) return !state.disabled;
    state.ownerChecked = true;
    try {
      Method raw = originalAdvance(owner.getClass());
      if (raw.getDeclaringClass() != owner.getClass()) {
        state.disabled = true;
        SUBCLASS_FALLBACKS.increment();
        return false;
      }
      return true;
    } catch (Throwable ignored) {
      state.disabled = true;
      FAILURE_FALLBACKS.increment();
      return false;
    }
  }

  private static void resetSchedule(State state) {
    state.elapsedDays = 0d;
    state.nextDeadline = Double.POSITIVE_INFINITY;
    state.nearest = null;
    state.knownSize = -1;
    state.valid = false;
  }

  /**
   * Removes due entries without materializing survivors. Their fields remain at the last sync
   * baseline, while elapsedDays remains an absolute offset from that baseline.
   */
  @SuppressWarnings("rawtypes")
  private static boolean expireDue(Object owner, LinkedHashMap mods, State state) {
    try {
      Field remaining = timeRemainingField(mods);
      double min = Double.POSITIVE_INFINITY;
      Object nearest = null;
      long scanned = 0L;
      int marked = 0;
      double elapsed = state.elapsedDays;
      for (Object mod : mods.values()) {
        if (mod == null) return false;
        float base = remaining.getFloat(mod);
        scanned++;
        if (!Float.isNaN(base) && (double) base <= elapsed) {
          // The exact vanilla body will remove it and call unmodify(source) in order.
          remaining.setFloat(mod, 0f);
          marked++;
        } else if (!Float.isNaN(base) && (double) base < min) {
          min = (double) base;
          nearest = mod;
        }
      }
      MODS_SCANNED.add(scanned);
      if (marked > 0) {
        int before = mods.size();
        invokeOriginalAdvance(owner, 0f);
        EXPIRY_SWEEPS.increment();
        int expired = before - mods.size();
        if (expired > 0) MODS_EXPIRED.add(expired);
        if (expired != marked) return false;
      }
      state.nextDeadline = nearest == null ? Double.POSITIVE_INFINITY : min;
      state.nearest = nearest;
      state.knownSize = mods.size();
      state.valid = true;
      return marked > 0 || state.nextDeadline > state.elapsedDays || !Double.isFinite(state.nextDeadline);
    } catch (Throwable ignored) {
      state.valid = false;
      return false;
    }
  }

  /** Materializes deferred elapsed time and resets the baseline. */
  @SuppressWarnings("rawtypes")
  private static boolean materialize(Object owner, LinkedHashMap mods, State state) {
    try {
      Field remaining = timeRemainingField(mods);
      double elapsed = state.elapsedDays;
      long scanned = 0L;
      int marked = 0;
      for (Object mod : mods.values()) {
        if (mod == null) return false;
        float base = remaining.getFloat(mod);
        scanned++;
        if (!Float.isNaN(base) && (double) base <= elapsed) {
          remaining.setFloat(mod, 0f);
          marked++;
          continue;
        }
        double effectiveDouble = (double) base - elapsed;
        float effective = (float) effectiveDouble;
        if (effectiveDouble > 0d && !(effective > 0f)) effective = Float.MIN_VALUE;
        remaining.setFloat(mod, effective);
      }
      MODS_SCANNED.add(scanned);
      int before = mods.size();
      if (marked > 0) invokeOriginalAdvance(owner, 0f);
      int expired = before - mods.size();
      if (expired > 0) MODS_EXPIRED.add(expired);
      if (expired != marked) return false;
      state.elapsedDays = 0d;
      SYNCHRONIZATION_SWEEPS.increment();
      return rebuildSchedule(mods, state);
    } catch (Throwable ignored) {
      state.valid = false;
      return false;
    }
  }

  /** Applies deferred time through vanilla as a last-resort fail-open path. */
  private static void fallbackApplyElapsed(Object owner, State state) {
    double elapsed = state.elapsedDays;
    state.elapsedDays = 0d;
    if (elapsed == 0d) return;
    float amount;
    if (elapsed >= Float.MAX_VALUE) amount = Float.POSITIVE_INFINITY;
    else amount = (float) elapsed;
    invokeOriginalAdvance(owner, amount);
    SYNCHRONIZATION_SWEEPS.increment();
  }

  @SuppressWarnings("rawtypes")
  private static boolean rebuildSchedule(LinkedHashMap mods, State state) {
    if (mods == null || mods.isEmpty()) {
      resetSchedule(state);
      state.knownSize = 0;
      state.valid = true;
      return true;
    }
    try {
      Field remaining = timeRemainingField(mods);
      double min = Double.POSITIVE_INFINITY;
      Object nearest = null;
      long scanned = 0L;
      for (Object mod : mods.values()) {
        if (mod == null) return false;
        float value = remaining.getFloat(mod);
        scanned++;
        if (!Float.isNaN(value) && (nearest == null || (double) value < min)) {
          min = (double) value;
          nearest = mod;
        }
      }
      MODS_SCANNED.add(scanned);
      REBUILDS.increment();
      state.elapsedDays = 0d;
      state.nextDeadline = nearest == null ? Double.POSITIVE_INFINITY : min;
      state.nearest = nearest;
      state.knownSize = mods.size();
      state.valid = true;
      return true;
    } catch (Throwable ignored) {
      state.valid = false;
      return false;
    }
  }

  @SuppressWarnings("rawtypes")
  private static Field timeRemainingField(LinkedHashMap mods) throws ReflectiveOperationException {
    Object sample = null;
    for (Object value : mods.values()) {
      if (value != null) {
        sample = value;
        break;
      }
    }
    if (sample == null) throw new NoSuchFieldException("temporary-mod map contains no value");
    Field cached = timeRemainingField;
    if (cached != null && cached.getDeclaringClass().isAssignableFrom(sample.getClass())) { return cached; }
    synchronized (StarsectorPrepatcherTempModHooks.class) {
      cached = timeRemainingField;
      if (cached != null && cached.getDeclaringClass().isAssignableFrom(sample.getClass())) { return cached; }
      Class<?> type = sample.getClass();
      Field uniqueFloat = null;
      for (Class<?> current = type; current != null; current = current.getSuperclass()) {
        for (Field field : current.getDeclaredFields()) {
          if (field.getType() != float.class) continue;
          if ("timeRemaining".equals(field.getName())) {
            field.setAccessible(true);
            timeRemainingField = field;
            return field;
          }
          if (uniqueFloat != null) { throw new NoSuchFieldException("ambiguous float fields in " + type.getName()); }
          uniqueFloat = field;
        }
      }
      if (uniqueFloat == null) throw new NoSuchFieldException("no float field in " + type.getName());
      uniqueFloat.setAccessible(true);
      timeRemainingField = uniqueFloat;
      return uniqueFloat;
    }
  }

  private static void invokeOriginalAdvance(Object owner, float days) {
    Method method = originalAdvance(owner.getClass());
    try {
      method.invoke(owner, days);
    } catch (InvocationTargetException ex) {
      Throwable cause = ex.getCause();
      if (cause instanceof RuntimeException runtime) throw runtime;
      if (cause instanceof Error error) throw error;
      throw new RuntimeException(cause);
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException("Unable to invoke original MutableStatWithTempMods.advance", ex);
    }
  }

  private static Method originalAdvance(Class<?> type) {
    synchronized (ORIGINAL_ADVANCE) {
      Method cached = ORIGINAL_ADVANCE.get(type);
      if (cached != null) return cached;
      Class<?> current = type;
      while (current != null) {
        try {
          Method method = current.getDeclaredMethod(ORIGINAL_ADVANCE_NAME, float.class);
          method.setAccessible(true);
          ORIGINAL_ADVANCE.put(type, method);
          return method;
        } catch (NoSuchMethodException ignored) {
          current = current.getSuperclass();
        }
      }
    }
    throw new IllegalStateException("Missing transformed method " + ORIGINAL_ADVANCE_NAME + " on " + type.getName());
  }
}
