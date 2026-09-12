package com.fs.starfarer.api;

import com.fs.starfarer.api.StarsectorPrepatcherStrategicJumpIndexData.PointRecord;
import com.fs.starfarer.api.StarsectorPrepatcherStrategicJumpIndexData.WeakIdentityOwnerCache;
import com.fs.starfarer.api.StarsectorPrepatcherStrategicJumpIndexLocation.LocationState;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.starsector.prepatcher.agent.PrepatcherConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * Demand-driven, campaign-thread strategic jump index.
 *
 * <p>No location is classified by type or size. A lookup only admits a bounded
 * amount of state and returns an empty immutable source while the exact ordered
 * index is being built. All scans, audits and point refreshes are split into
 * small work units and consume one process-wide token bucket, so fast-forward
 * simulation loops cannot multiply the admitted work without wall-clock refill.
 */
final class StarsectorPrepatcherStrategicJumpIndex {
  private static final Object LOCK = new Object();
  private static final long REFILL_PERIOD_NANOS = 16_666_667L;
  static final int MAX_PENDING_POINT_DELTAS = 256;
  static final List<?> DEFERRED = Collections.emptyList();

  /** Strong values are deliberately bounded by LRU admission and campaign reset. */
  private static IdentityHashMap<LocationAPI, LocationState> states = new IdentityHashMap<>();
  private static ArrayDeque<LocationState> workQueue = new ArrayDeque<>();
  /** Indexed binary heap: no process-wide LRU scan is needed to find a due retry. */
  private static ArrayList<LocationState> retryHeap = new ArrayList<>();
  /** Fixed-size best-effort accelerator; audit remains the correctness fallback. */
  static final WeakIdentityOwnerCache destinationOwners = new WeakIdentityOwnerCache(8_192);

  private static LocationState lruHead;
  private static LocationState lruTail;
  /** Intrusive READY-state round-robin queue; selection and removal are O(1). */
  private static LocationState auditHead;
  private static LocationState auditTail;
  /** READY/FAILED states ordered by access recency and therefore safe to evict. */
  private static LocationState evictableHead;
  private static LocationState evictableTail;
  private static int auditQueueSize;
  private static int evictableCount;
  private static long generation = Long.MIN_VALUE;
  private static int stateMapCapacity;
  static long lastNowNanos;
  private static long lastRefillNanos;
  private static long budgetTokensNanos;
  private static long tokenRemainder;
  private static long admissionRemainder;
  private static int admissionsAvailable;
  private static long schedulerSequence;
  private static long retrySequence;
  private static long admissionDeferredSinceNanos;

  private static final LongAdder LOOKUPS = new LongAdder();
  private static final LongAdder HITS = new LongAdder();
  private static final LongAdder NEGATIVE_HITS = new LongAdder();
  private static final LongAdder DEFERRED_LOOKUPS = new LongAdder();
  private static final LongAdder DEFERRED_PLANS = new LongAdder();
  private static final LongAdder DEFERRED_ADMISSIONS = new LongAdder();
  private static final LongAdder PROTECTED_CAPACITY_DEFERRALS = new LongAdder();
  private static final LongAdder ADMISSIONS = new LongAdder();
  private static final LongAdder EVICTIONS = new LongAdder();
  static final LongAdder BUILDS_STARTED = new LongAdder();
  static final LongAdder BUILDS_COMPLETED = new LongAdder();
  static final LongAdder BUILD_RESTARTS = new LongAdder();
  private static final LongAdder MAINTENANCE_CALLS = new LongAdder();
  /** Full maintenance duration, including selection, queues, LRU and cleanup. */
  private static final LongAdder MAINTENANCE_NANOS = new LongAdder();
  private static final LongAdder BUDGET_EXHAUSTIONS = new LongAdder();
  private static final LongAdder WORK_UNIT_EXHAUSTIONS = new LongAdder();
  private static final LongAdder WORK_UNITS = new LongAdder();
  /** Time inside indivisible build/refresh/audit units only. */
  private static final LongAdder WORK_NANOS = new LongAdder();
  static final LongAdder POINT_REFRESHES = new LongAdder();
  private static final LongAdder AUDIT_UNITS = new LongAdder();
  static final LongAdder AUDIT_REPAIRS = new LongAdder();
  private static final LongAdder SOURCE_INVALIDATIONS = new LongAdder();
  private static final LongAdder POINT_INVALIDATIONS = new LongAdder();
  private static final LongAdder RETARGET_INVALIDATIONS = new LongAdder();
  static final LongAdder FAILURES = new LongAdder();
  static final LongAdder RETRIES = new LongAdder();
  private static final LongAdder IDLE_EVICTIONS = new LongAdder();

  /** Interval maxima are updated under LOCK and reset by statsAndReset(). */
  private static long maintenanceMaxNanos;
  private static long maintenanceMaxOverrunNanos;
  static long readyLatencyMaxNanos;
  static long deferredLookupMaxNanos;
  static long deferredPlanMaxNanos;
  private static long admissionDeferredMaxNanos;
  private static int workQueueMax;
  private static int auditQueueMax;
  private static int retryQueueMax;

  private StarsectorPrepatcherStrategicJumpIndex() {}

  static void reset() {
    synchronized (LOCK) {
      resolveAllDebtBeforeClear();
      for (LocationState state : states.values()) state.active = false;
      states = new IdentityHashMap<>();
      workQueue = new ArrayDeque<>();
      retryHeap = new ArrayList<>();
      lruHead = null;
      lruTail = null;
      auditHead = null;
      auditTail = null;
      evictableHead = null;
      evictableTail = null;
      auditQueueSize = 0;
      evictableCount = 0;
      generation = Long.MIN_VALUE;
      stateMapCapacity = 0;
      lastNowNanos = 0L;
      lastRefillNanos = 0L;
      budgetTokensNanos = 0L;
      tokenRemainder = 0L;
      admissionRemainder = 0L;
      admissionsAvailable = 0;
      schedulerSequence = 0L;
      retrySequence = 0L;
      admissionDeferredSinceNanos = 0L;
      destinationOwners.clear();
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  static List lookup(LocationAPI current, Class jumpPointType, LocationAPI requested, LocationAPI fallback,
      PrepatcherConfig config, long currentGeneration) {
    LOOKUPS.increment();
    if (current == null || jumpPointType == null || config == null || !config.strategicJumpDestinationIndex) {
      return DEFERRED;
    }

    List live;
    try {
      live = current.getEntities(jumpPointType);
    } catch (Throwable failure) {
      FAILURES.increment();
      return DEFERRED;
    }
    if (live == null) return DEFERRED;

    synchronized (LOCK) {
      ensureGeneration(currentGeneration);
      LocationState state = observeOrAdmit(current, jumpPointType, live, config, currentGeneration);
      if (state == null || !state.active || state.phase != LocationState.READY || state.data == null) {
        DEFERRED_LOOKUPS.increment();
        if (state != null && state.active) state.noteDeferredLookup();
        return DEFERRED;
      }
      List candidates = state.data.candidates(requested, fallback, acceptsFallback(current, requested, fallback));
      HITS.increment();
      if (candidates.isEmpty()) NEGATIVE_HITS.increment();
      return candidates;
    }
  }

  /**
   * Keeps an already valid JumpPlan in place while its exact replacement
   * source is still being built. This admission path is location-agnostic and
   * performs no indexing work; all scans remain in {@link #maintenance}.
   */
  @SuppressWarnings("rawtypes")
  static boolean deferExpiredPlan(LocationAPI current, Class jumpPointType, PrepatcherConfig config,
      long currentGeneration) {
    if (current == null || jumpPointType == null || config == null || !config.strategicJumpDestinationIndex)
      return false;
    List live;
    try {
      live = current.getEntities(jumpPointType);
    } catch (Throwable failure) {
      FAILURES.increment();
      return false;
    }
    if (live == null) return false;
    synchronized (LOCK) {
      ensureGeneration(currentGeneration);
      LocationState state = observeOrAdmit(current, jumpPointType, live, config, currentGeneration);
      boolean deferred = state == null || !state.active || state.phase == LocationState.BUILDING
          || state.phase == LocationState.REFRESHING;
      // A cold or actively refreshing exact snapshot may retain the prior
      // plan briefly. Persistent build failures must not keep a stale plan
      // alive throughout the cooldown; the fleet retries without a plan.
      if (deferred) {
        DEFERRED_PLANS.increment();
        if (state != null && state.active) state.noteDeferredPlan();
      }
      return deferred;
    }
  }

  @SuppressWarnings("rawtypes")
  private static LocationState observeOrAdmit(LocationAPI current, Class jumpPointType, List live,
      PrepatcherConfig config, long currentGeneration) {
    LocationState state = states.get(current);
    if (state == null) {
      if (admissionsAvailable <= 0) {
        DEFERRED_ADMISSIONS.increment();
        noteAdmissionDeferred();
        return null;
      }
      while (states.size() >= config.strategicJumpIndexMaxLocations) {
        LocationState victim = evictableHead;
        if (victim == null) {
          // BUILDING/REFRESHING states keep their completed work. New
          // locations wait instead of forcing LRU restart thrashing.
          DEFERRED_ADMISSIONS.increment();
          PROTECTED_CAPACITY_DEFERRALS.increment();
          noteAdmissionDeferred();
          return null;
        }
        evict(victim, false);
      }
      admissionsAvailable--;
      state = new LocationState(current, jumpPointType);
      states.put(current, state);
      linkTail(state);
      state.observeSource(live);
      state.requestFullBuild();
      resolveAdmissionDeferred();
      ADMISSIONS.increment();
    } else {
      touch(state);
      if (state.jumpPointType != jumpPointType) {
        state.jumpPointType = jumpPointType;
        state.observeSource(live);
        state.sourceEpoch++;
        state.requestFullBuild();
      } else if (!state.fastSourceMatches(live)) {
        state.observeSource(live);
        state.sourceEpoch++;
        state.requestFullBuild();
      }
    }
    state.lastAccessNanos = lastNowNanos;
    return state;
  }

  static void maintenance(PrepatcherConfig config, long currentGeneration, LongSupplier clock) {
    if (config == null || !config.strategicJumpDestinationIndex || clock == null) return;
    // Capture before acquiring LOCK so any unexpected contention is also
    // charged to this maintenance period rather than hidden from the budget.
    long started = safeNow(clock);
    synchronized (LOCK) {
      long available = 0L;
      int units = 0;
      int auditUnits = 0;
      boolean deadlineReached = false;
      try {
        ensureGeneration(currentGeneration);
        prepareCapacity(config);
        lastNowNanos = started;
        refill(config, started);
        available = Math.max(0L, budgetTokensNanos);
        MAINTENANCE_CALLS.increment();
        if (available <= 0L) return;

        long deadline = saturatedAdd(started, available);
        int auditLimit = Math.max(1, config.strategicJumpIndexMaxWorkUnits >>> 4);
        while (units < config.strategicJumpIndexMaxWorkUnits) {
          long now = safeNow(clock);
          lastNowNanos = now;
          if (now >= deadline) {
            deadlineReached = true;
            break;
          }

          long turn = schedulerSequence++;
          if ((turn & 31L) == 31L && evictIdleOne(config, now)) {
            units++;
            WORK_UNITS.increment();
            continue;
          }

          boolean audit = false;
          LocationState state = null;

          // One sixteenth of the deterministic work-unit ceiling is
          // reserved for continuous correctness audit. In an idle
          // steady state this prevents audit from burning the entire
          // wall-clock bucket every campaign frame.
          if (auditUnits < auditLimit && (turn & 15L) == 15L) {
            state = pollAudit();
            audit = state != null;
          }
          if (state == null && (turn & 7L) == 7L) {
            state = pollDueRetry(now);
          }
          if (state == null) state = pollWork();
          if (state == null) state = pollDueRetry(now);
          if (state == null && auditUnits < auditLimit) {
            state = pollAudit();
            audit = state != null;
          }
          if (state == null) {
            if (evictIdleOne(config, now)) {
              units++;
              WORK_UNITS.increment();
              continue;
            }
            break;
          }

          // Selection, heap/queue operations and all cleanup are
          // covered by the outer started/deadline/final accounting.
          // These two samples retain the existing useful-unit metric.
          long beforeWork = safeNow(clock);
          boolean more = false;
          try {
            if (audit) {
              state.auditOne();
            } else {
              more = state.workOne(config, now);
            }
          } catch (Throwable failure) {
            state.fail(config, now);
          }
          long afterWork = safeNow(clock);
          WORK_NANOS.add(elapsedNanos(beforeWork, afterWork));

          units++;
          WORK_UNITS.increment();
          if (audit) {
            auditUnits++;
            AUDIT_UNITS.increment();
            if (state.active && state.phase == LocationState.READY && state.data != null) {
              scheduleAudit(state);
            }
          } else if (more && state.active) {
            enqueue(state);
          }
        }
        if (units >= config.strategicJumpIndexMaxWorkUnits) {
          WORK_UNIT_EXHAUSTIONS.increment();
        }
      } finally {
        long ended = safeNow(clock);
        lastNowNanos = Math.max(lastNowNanos, ended);
        long fullCost = elapsedNanos(started, ended);
        long overrun = Math.max(0L, fullCost - available);
        budgetTokensNanos = fullCost >= available ? 0L : available - fullCost;
        MAINTENANCE_NANOS.add(fullCost);
        if (fullCost > maintenanceMaxNanos) maintenanceMaxNanos = fullCost;
        if (overrun > maintenanceMaxOverrunNanos) {
          maintenanceMaxOverrunNanos = overrun;
        }
        if (available > 0L && (deadlineReached || fullCost >= available)) {
          BUDGET_EXHAUSTIONS.increment();
        }
      }
    }
  }

  static void destinationsChanged(JumpPointAPI point) {
    if (point == null) return;
    synchronized (LOCK) {
      LocationState state = stateFor(point);
      if (state == null) return;
      state.markPointDirty(point);
      POINT_INVALIDATIONS.increment();
    }
  }

  static void destinationAdded(JumpPointAPI point, JumpPointAPI.JumpDestination destination) {
    if (point == null) return;
    synchronized (LOCK) {
      LocationState state = stateFor(point);
      if (state == null) return;
      PointRecord record = state.recordFor(point);
      if (destination != null && record != null) {
        destinationOwners.put(destination, record);
      }
      state.markPointDirty(point);
      POINT_INVALIDATIONS.increment();
    }
  }

  static void destinationRetargeted(JumpPointAPI.JumpDestination destination) {
    if (destination == null) return;
    synchronized (LOCK) {
      PointRecord record = destinationOwners.get(destination);
      if (record == null || !record.state.active) return;
      record.state.markPointDirty(record.point);
      RETARGET_INVALIDATIONS.increment();
    }
  }

  static void locationEntityChanged(LocationAPI location, Object entity) {
    if (location == null || !(entity instanceof JumpPointAPI)) return;
    synchronized (LOCK) {
      LocationState state = states.get(location);
      if (state == null || !state.active) return;
      state.invalidateSource();
      SOURCE_INVALIDATIONS.increment();
    }
  }

  static String statsAndReset() {
    synchronized (LOCK) {
      DebtGauges debt = debtGauges(lastNowNanos);
      long intervalMaintenanceMax = maintenanceMaxNanos;
      long intervalOverrunMax = maintenanceMaxOverrunNanos;
      long intervalReadyLatencyMax = Math.max(readyLatencyMaxNanos, debt.oldestPendingNanos);
      long intervalDeferredLookupMax = Math.max(deferredLookupMaxNanos, debt.oldestDeferredLookupNanos);
      long intervalDeferredPlanMax = Math.max(deferredPlanMaxNanos, debt.oldestDeferredPlanNanos);
      long currentAdmissionAge = ageNanos(admissionDeferredSinceNanos, lastNowNanos);
      long intervalAdmissionMax = Math.max(admissionDeferredMaxNanos, currentAdmissionAge);
      int intervalWorkQueueMax = Math.max(workQueueMax, workQueue.size());
      int intervalAuditQueueMax = Math.max(auditQueueMax, auditQueueSize);
      int intervalRetryQueueMax = Math.max(retryQueueMax, retryHeap.size());

      maintenanceMaxNanos = 0L;
      maintenanceMaxOverrunNanos = 0L;
      readyLatencyMaxNanos = 0L;
      deferredLookupMaxNanos = 0L;
      deferredPlanMaxNanos = 0L;
      admissionDeferredMaxNanos = 0L;
      workQueueMax = workQueue.size();
      auditQueueMax = auditQueueSize;
      retryQueueMax = retryHeap.size();

      return ", strategicJumpIndexLocations=" + states.size() + ", strategicJumpIndexQueued=" + workQueue.size()
          + ", strategicJumpIndexAuditQueued=" + auditQueueSize + ", strategicJumpIndexRetryQueued=" + retryHeap.size()
          + ", strategicJumpIndexEvictable=" + evictableCount + ", strategicJumpIndexBuilding=" + debt.building
          + ", strategicJumpIndexRefreshing=" + debt.refreshing + ", strategicJumpIndexReady=" + debt.ready
          + ", strategicJumpIndexFailed=" + debt.failed + ", strategicJumpIndexLookups=" + LOOKUPS.sumThenReset()
          + ", strategicJumpIndexHits=" + HITS.sumThenReset() + ", strategicJumpIndexNegativeHits="
          + NEGATIVE_HITS.sumThenReset() + ", strategicJumpIndexDeferred=" + DEFERRED_LOOKUPS.sumThenReset()
          + ", strategicJumpIndexDeferredPlans=" + DEFERRED_PLANS.sumThenReset()
          + ", strategicJumpIndexAdmissionDeferred=" + DEFERRED_ADMISSIONS.sumThenReset()
          + ", strategicJumpIndexProtectedCapacityDeferred=" + PROTECTED_CAPACITY_DEFERRALS.sumThenReset()
          + ", strategicJumpIndexAdmissions=" + ADMISSIONS.sumThenReset() + ", strategicJumpIndexEvictions="
          + EVICTIONS.sumThenReset() + ", strategicJumpIndexIdleEvictions=" + IDLE_EVICTIONS.sumThenReset()
          + ", strategicJumpIndexBuildsStarted=" + BUILDS_STARTED.sumThenReset()
          + ", strategicJumpIndexBuildsCompleted=" + BUILDS_COMPLETED.sumThenReset()
          + ", strategicJumpIndexBuildRestarts=" + BUILD_RESTARTS.sumThenReset()
          + ", strategicJumpIndexMaintenanceCalls=" + MAINTENANCE_CALLS.sumThenReset()
          + ", strategicJumpIndexMaintenanceNanos=" + MAINTENANCE_NANOS.sumThenReset()
          + ", strategicJumpIndexMaintenanceMaxNanos=" + intervalMaintenanceMax
          + ", strategicJumpIndexMaintenanceMaxOverrunNanos=" + intervalOverrunMax
          + ", strategicJumpIndexBudgetExhaustions=" + BUDGET_EXHAUSTIONS.sumThenReset()
          + ", strategicJumpIndexWorkUnitExhaustions=" + WORK_UNIT_EXHAUSTIONS.sumThenReset()
          + ", strategicJumpIndexWorkUnits=" + WORK_UNITS.sumThenReset() + ", strategicJumpIndexWorkNanos="
          + WORK_NANOS.sumThenReset() + ", strategicJumpIndexPointRefreshes=" + POINT_REFRESHES.sumThenReset()
          + ", strategicJumpIndexAuditUnits=" + AUDIT_UNITS.sumThenReset() + ", strategicJumpIndexAuditRepairs="
          + AUDIT_REPAIRS.sumThenReset() + ", strategicJumpIndexSourceInvalidations="
          + SOURCE_INVALIDATIONS.sumThenReset() + ", strategicJumpIndexPointInvalidations="
          + POINT_INVALIDATIONS.sumThenReset() + ", strategicJumpIndexRetargetInvalidations="
          + RETARGET_INVALIDATIONS.sumThenReset() + ", strategicJumpIndexFailures=" + FAILURES.sumThenReset()
          + ", strategicJumpIndexRetries=" + RETRIES.sumThenReset() + ", strategicJumpIndexWorkQueueMax="
          + intervalWorkQueueMax + ", strategicJumpIndexAuditQueueMax=" + intervalAuditQueueMax
          + ", strategicJumpIndexRetryQueueMax=" + intervalRetryQueueMax + ", strategicJumpIndexOldestPendingMs="
          + nanosToMillis(debt.oldestPendingNanos) + ", strategicJumpIndexOldestDeferredLookupMs="
          + nanosToMillis(debt.oldestDeferredLookupNanos) + ", strategicJumpIndexOldestDeferredPlanMs="
          + nanosToMillis(debt.oldestDeferredPlanNanos) + ", strategicJumpIndexAdmissionDeferredAgeMs="
          + nanosToMillis(currentAdmissionAge) + ", strategicJumpIndexReadyLatencyMaxMs="
          + nanosToMillis(intervalReadyLatencyMax) + ", strategicJumpIndexDeferredLookupMaxMs="
          + nanosToMillis(intervalDeferredLookupMax) + ", strategicJumpIndexDeferredPlanMaxMs="
          + nanosToMillis(intervalDeferredPlanMax) + ", strategicJumpIndexAdmissionDeferredMaxMs="
          + nanosToMillis(intervalAdmissionMax);
    }
  }

  /** Package-private diagnostics used by the runtime regression harness. */
  static Snapshot snapshot(LocationAPI location) {
    synchronized (LOCK) {
      LocationState state = states.get(location);
      return state == null ? new Snapshot(states.size(), workQueue.size(), "ABSENT", 0, 0, 0, 0)
          : new Snapshot(states.size(), workQueue.size(), state.phaseName(), state.data == null ? 0 : state.data.size,
              state.dirtySet.size(), state.build == null ? 0 : state.build.sourceIndex,
              state.build == null || state.build.capture == null ? 0 : state.build.capture.cursor);
    }
  }

  static Diagnostics diagnostics() {
    synchronized (LOCK) {
      DebtGauges debt = debtGauges(lastNowNanos);
      return new Diagnostics(WORK_UNITS.sum(), MAINTENANCE_NANOS.sum(), maintenanceMaxNanos, BUILDS_STARTED.sum(),
          BUILDS_COMPLETED.sum(), POINT_REFRESHES.sum(), FAILURES.sum(), RETRIES.sum(), DEFERRED_PLANS.sum(),
          PROTECTED_CAPACITY_DEFERRALS.sum(), states.size(), workQueue.size(), auditQueueSize, retryHeap.size(),
          evictableCount, debt.oldestPendingNanos, debt.oldestDeferredLookupNanos, debt.oldestDeferredPlanNanos);
    }
  }

  record Snapshot(int locations, int queued, String phase, int indexedPoints, int dirtyPoints, int buildPointCursor,
      int buildDestinationCursor) {
  }

  record Diagnostics(long workUnits, long maintenanceNanos, long maintenanceMaxNanos, long buildsStarted,
      long buildsCompleted, long pointRefreshes, long failures, long retries, long deferredPlans,
      long protectedCapacityDeferrals, int locations, int queued, int auditQueued, int retryQueued, int evictable,
      long oldestPendingNanos, long oldestDeferredLookupNanos, long oldestDeferredPlanNanos) {
  }

  private static DebtGauges debtGauges(long now) {
    int building = 0;
    int refreshing = 0;
    int ready = 0;
    int failed = 0;
    long oldestPending = 0L;
    long oldestDeferredLookup = 0L;
    long oldestDeferredPlan = 0L;
    for (LocationState state : states.values()) {
      if (!state.active) continue;
      switch (state.phase) {
        case LocationState.BUILDING -> building++;
        case LocationState.REFRESHING -> refreshing++;
        case LocationState.READY -> ready++;
        case LocationState.FAILED -> failed++;
        default -> {
        }
      }
      oldestPending = Math.max(oldestPending, ageNanos(state.pendingSinceNanos, now));
      oldestDeferredLookup = Math.max(oldestDeferredLookup, ageNanos(state.deferredLookupSinceNanos, now));
      oldestDeferredPlan = Math.max(oldestDeferredPlan, ageNanos(state.deferredPlanSinceNanos, now));
    }
    return new DebtGauges(building, refreshing, ready, failed, oldestPending, oldestDeferredLookup, oldestDeferredPlan);
  }

  private static long ageNanos(long since, long now) {
    return since > 0L && now > since ? now - since : 0L;
  }

  private static long nanosToMillis(long nanos) {
    return nanos <= 0L ? 0L : nanos / 1_000_000L;
  }

  private record DebtGauges(int building, int refreshing, int ready, int failed, long oldestPendingNanos,
      long oldestDeferredLookupNanos, long oldestDeferredPlanNanos) {
  }

  private static boolean acceptsFallback(LocationAPI current, LocationAPI requested, LocationAPI fallback) {
    if (fallback == null) return false;
    if (requested == null) return true;
    return fallback != requested && fallback != current;
  }

  private static void ensureGeneration(long currentGeneration) {
    if (generation == currentGeneration) return;
    resolveAllDebtBeforeClear();
    for (LocationState state : states.values()) state.active = false;
    states = new IdentityHashMap<>();
    workQueue = new ArrayDeque<>();
    retryHeap = new ArrayList<>();
    lruHead = null;
    lruTail = null;
    auditHead = null;
    auditTail = null;
    evictableHead = null;
    evictableTail = null;
    auditQueueSize = 0;
    evictableCount = 0;
    generation = currentGeneration;
    stateMapCapacity = 0;
    lastNowNanos = 0L;
    lastRefillNanos = 0L;
    budgetTokensNanos = 0L;
    tokenRemainder = 0L;
    admissionRemainder = 0L;
    admissionsAvailable = 0;
    schedulerSequence = 0L;
    retrySequence = 0L;
    admissionDeferredSinceNanos = 0L;
    destinationOwners.clear();
  }

  private static void resolveAllDebtBeforeClear() {
    long now = lastNowNanos;
    for (LocationState state : states.values()) state.resolveDebt(now);
    resolveAdmissionDeferred();
  }

  private static void prepareCapacity(PrepatcherConfig config) {
    if (!states.isEmpty() || stateMapCapacity == config.strategicJumpIndexMaxLocations) { return; }
    states = new IdentityHashMap<>(config.strategicJumpIndexMaxLocations);
    workQueue = new ArrayDeque<>(config.strategicJumpIndexMaxLocations);
    retryHeap = new ArrayList<>(config.strategicJumpIndexMaxLocations);
    stateMapCapacity = config.strategicJumpIndexMaxLocations;
  }

  private static void refill(PrepatcherConfig config, long now) {
    long capacity = Math.max(1L, (long) config.strategicJumpIndexBudgetMicros * 1_000L);
    int admissionCapacity = Math.max(1, config.strategicJumpIndexAdmissionBurst);
    if (lastRefillNanos == 0L || now < lastRefillNanos) {
      lastRefillNanos = now;
      budgetTokensNanos = capacity;
      admissionsAvailable = admissionCapacity;
      tokenRemainder = 0L;
      admissionRemainder = 0L;
      return;
    }
    long elapsed = now - lastRefillNanos;
    lastRefillNanos = now;
    if (elapsed >= REFILL_PERIOD_NANOS) {
      budgetTokensNanos = capacity;
      admissionsAvailable = admissionCapacity;
      tokenRemainder = 0L;
      admissionRemainder = 0L;
      return;
    }
    if (elapsed <= 0L) return;

    long tokenScaled = elapsed * capacity + tokenRemainder;
    long tokenAdd = tokenScaled / REFILL_PERIOD_NANOS;
    tokenRemainder = tokenScaled % REFILL_PERIOD_NANOS;
    budgetTokensNanos = Math.min(capacity, budgetTokensNanos + tokenAdd);

    long admissionScaled = elapsed * admissionCapacity + admissionRemainder;
    int admissionAdd = (int) (admissionScaled / REFILL_PERIOD_NANOS);
    admissionRemainder = admissionScaled % REFILL_PERIOD_NANOS;
    admissionsAvailable = Math.min(admissionCapacity, admissionsAvailable + admissionAdd);
  }

  static Object safeGet(List<?> values, int index) {
    try {
      return values.get(index);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static long safeNow(LongSupplier clock) {
    try {
      return clock.getAsLong();
    } catch (Throwable ignored) {
      return System.nanoTime();
    }
  }

  private static long elapsedNanos(long started, long ended) {
    return ended > started ? ended - started : 1L;
  }

  private static long saturatedAdd(long value, long increment) {
    if (increment <= 0L) return value;
    long result = value + increment;
    return result < value ? Long.MAX_VALUE : result;
  }

  private static LocationState stateFor(JumpPointAPI point) {
    try {
      LocationAPI location = point.getContainingLocation();
      LocationState state = location == null ? null : states.get(location);
      return state != null && state.active ? state : null;
    } catch (Throwable ignored) {
      return null;
    }
  }

  static void enqueue(LocationState state) {
    if (!state.active || state.queued || state.phase == LocationState.FAILED) return;
    state.queued = true;
    workQueue.addLast(state);
    if (workQueue.size() > workQueueMax) workQueueMax = workQueue.size();
  }

  private static LocationState pollWork() {
    while (!workQueue.isEmpty()) {
      LocationState state = workQueue.removeFirst();
      state.queued = false;
      if (state.active && state.hasWork()) return state;
    }
    return null;
  }

  static void scheduleAudit(LocationState state) {
    if (!state.active || state.phase != LocationState.READY || state.data == null || state.auditLinked) return;
    state.auditPrevious = auditTail;
    state.auditNext = null;
    if (auditTail == null) auditHead = state;
    else auditTail.auditNext = state;
    auditTail = state;
    state.auditLinked = true;
    auditQueueSize++;
    if (auditQueueSize > auditQueueMax) auditQueueMax = auditQueueSize;
  }

  static void unlinkAudit(LocationState state) {
    if (!state.auditLinked) return;
    if (state.auditPrevious == null) auditHead = state.auditNext;
    else state.auditPrevious.auditNext = state.auditNext;
    if (state.auditNext == null) auditTail = state.auditPrevious;
    else state.auditNext.auditPrevious = state.auditPrevious;
    state.auditPrevious = null;
    state.auditNext = null;
    state.auditLinked = false;
    auditQueueSize--;
  }

  private static LocationState pollAudit() {
    while (auditHead != null) {
      LocationState state = auditHead;
      unlinkAudit(state);
      if (state.active && state.phase == LocationState.READY && state.data != null) { return state; }
    }
    return null;
  }

  static void scheduleRetry(LocationState state) {
    if (!state.active || state.phase != LocationState.FAILED) return;
    removeRetry(state);
    state.retryOrder = ++retrySequence;
    int index = retryHeap.size();
    retryHeap.add(state);
    state.retryHeapIndex = index;
    siftRetryUp(index);
    if (retryHeap.size() > retryQueueMax) retryQueueMax = retryHeap.size();
  }

  static void removeRetry(LocationState state) {
    int index = state.retryHeapIndex;
    if (index < 0 || index >= retryHeap.size() || retryHeap.get(index) != state) {
      state.retryHeapIndex = -1;
      return;
    }
    removeRetryAt(index);
  }

  private static LocationState pollDueRetry(long now) {
    while (!retryHeap.isEmpty()) {
      LocationState state = retryHeap.get(0);
      if (!state.active || state.phase != LocationState.FAILED) {
        removeRetryAt(0);
        continue;
      }
      if (state.retryAtNanos > now) return null;
      removeRetryAt(0);
      return state;
    }
    return null;
  }

  private static void removeRetryAt(int index) {
    int lastIndex = retryHeap.size() - 1;
    LocationState removed = retryHeap.get(index);
    LocationState moved = retryHeap.remove(lastIndex);
    removed.retryHeapIndex = -1;
    if (index == lastIndex) return;
    retryHeap.set(index, moved);
    moved.retryHeapIndex = index;
    int parent = (index - 1) >>> 1;
    if (index > 0 && retryBefore(moved, retryHeap.get(parent))) {
      siftRetryUp(index);
    } else {
      siftRetryDown(index);
    }
  }

  private static void siftRetryUp(int index) {
    LocationState state = retryHeap.get(index);
    while (index > 0) {
      int parent = (index - 1) >>> 1;
      LocationState parentState = retryHeap.get(parent);
      if (!retryBefore(state, parentState)) break;
      retryHeap.set(index, parentState);
      parentState.retryHeapIndex = index;
      index = parent;
    }
    retryHeap.set(index, state);
    state.retryHeapIndex = index;
  }

  private static void siftRetryDown(int index) {
    int size = retryHeap.size();
    LocationState state = retryHeap.get(index);
    int half = size >>> 1;
    while (index < half) {
      int child = (index << 1) + 1;
      LocationState selected = retryHeap.get(child);
      int right = child + 1;
      if (right < size && retryBefore(retryHeap.get(right), selected)) {
        child = right;
        selected = retryHeap.get(right);
      }
      if (!retryBefore(selected, state)) break;
      retryHeap.set(index, selected);
      selected.retryHeapIndex = index;
      index = child;
    }
    retryHeap.set(index, state);
    state.retryHeapIndex = index;
  }

  private static boolean retryBefore(LocationState first, LocationState second) {
    if (first.retryAtNanos != second.retryAtNanos) { return first.retryAtNanos < second.retryAtNanos; }
    return first.retryOrder < second.retryOrder;
  }

  private static void touch(LocationState state) {
    if (state != lruTail) {
      unlink(state);
      linkTail(state);
    }
    if (state.evictableLinked && state != evictableTail) {
      unlinkEvictable(state);
      linkEvictableTail(state);
    }
  }

  private static void linkTail(LocationState state) {
    state.previous = lruTail;
    state.next = null;
    if (lruTail == null) lruHead = state;
    else lruTail.next = state;
    lruTail = state;
  }

  private static void unlink(LocationState state) {
    if (state.previous == null) lruHead = state.next;
    else state.previous.next = state.next;
    if (state.next == null) lruTail = state.previous;
    else state.next.previous = state.previous;
    state.previous = null;
    state.next = null;
  }

  static void linkEvictableTail(LocationState state) {
    if (state.evictableLinked || !state.active
        || (state.phase != LocationState.READY && state.phase != LocationState.FAILED))
      return;
    state.evictablePrevious = evictableTail;
    state.evictableNext = null;
    if (evictableTail == null) evictableHead = state;
    else evictableTail.evictableNext = state;
    evictableTail = state;
    state.evictableLinked = true;
    evictableCount++;
  }

  static void unlinkEvictable(LocationState state) {
    if (!state.evictableLinked) return;
    if (state.evictablePrevious == null) evictableHead = state.evictableNext;
    else state.evictablePrevious.evictableNext = state.evictableNext;
    if (state.evictableNext == null) evictableTail = state.evictablePrevious;
    else state.evictableNext.evictablePrevious = state.evictablePrevious;
    state.evictablePrevious = null;
    state.evictableNext = null;
    state.evictableLinked = false;
    evictableCount--;
  }

  private static void evict(LocationState state, boolean idle) {
    if (!state.active) return;
    state.resolveDebt(lastNowNanos);
    states.remove(state.location);
    unlink(state);
    unlinkAudit(state);
    unlinkEvictable(state);
    removeRetry(state);
    state.active = false;
    if (state.queued) {
      // BUILDING/REFRESHING states are protected from capacity eviction,
      // but idle TTL and campaign teardown may still remove one safely.
      workQueue.remove(state);
      state.queued = false;
    }
    EVICTIONS.increment();
    if (idle) IDLE_EVICTIONS.increment();
  }

  private static boolean evictIdleOne(PrepatcherConfig config, long now) {
    if (config.strategicJumpIndexIdleTtlMs <= 0 || lruHead == null) return false;
    long ttl = (long) config.strategicJumpIndexIdleTtlMs * 1_000_000L;
    if (now < lruHead.lastAccessNanos || now - lruHead.lastAccessNanos < ttl) return false;
    evict(lruHead, true);
    return true;
  }

  private static void noteAdmissionDeferred() {
    if (admissionDeferredSinceNanos == 0L && lastNowNanos > 0L) {
      admissionDeferredSinceNanos = lastNowNanos;
    }
  }

  private static void resolveAdmissionDeferred() {
    if (admissionDeferredSinceNanos == 0L) return;
    long age = elapsedNanos(admissionDeferredSinceNanos, Math.max(admissionDeferredSinceNanos, lastNowNanos));
    if (age > admissionDeferredMaxNanos) admissionDeferredMaxNanos = age;
    admissionDeferredSinceNanos = 0L;
  }

}
