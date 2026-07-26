package com.fs.starfarer.api;

import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.starsector.prepatcher.agent.PrepatcherConfig;

import java.lang.ref.WeakReference;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.RandomAccess;
import java.util.TreeSet;
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
    private static final int MAX_PENDING_POINT_DELTAS = 256;
    private static final List<?> DEFERRED = Collections.emptyList();

    /** Strong values are deliberately bounded by LRU admission and campaign reset. */
    private static IdentityHashMap<LocationAPI, LocationState> states = new IdentityHashMap<>();
    private static ArrayDeque<LocationState> workQueue = new ArrayDeque<>();
    /** Indexed binary heap: no process-wide LRU scan is needed to find a due retry. */
    private static ArrayList<LocationState> retryHeap = new ArrayList<>();
    /** Fixed-size best-effort accelerator; audit remains the correctness fallback. */
    private static final WeakIdentityOwnerCache destinationOwners =
            new WeakIdentityOwnerCache(8_192);

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
    private static long lastNowNanos;
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
    private static final LongAdder BUILDS_STARTED = new LongAdder();
    private static final LongAdder BUILDS_COMPLETED = new LongAdder();
    private static final LongAdder BUILD_RESTARTS = new LongAdder();
    private static final LongAdder MAINTENANCE_CALLS = new LongAdder();
    /** Full maintenance duration, including selection, queues, LRU and cleanup. */
    private static final LongAdder MAINTENANCE_NANOS = new LongAdder();
    private static final LongAdder BUDGET_EXHAUSTIONS = new LongAdder();
    private static final LongAdder WORK_UNIT_EXHAUSTIONS = new LongAdder();
    private static final LongAdder WORK_UNITS = new LongAdder();
    /** Time inside indivisible build/refresh/audit units only. */
    private static final LongAdder WORK_NANOS = new LongAdder();
    private static final LongAdder POINT_REFRESHES = new LongAdder();
    private static final LongAdder AUDIT_UNITS = new LongAdder();
    private static final LongAdder AUDIT_REPAIRS = new LongAdder();
    private static final LongAdder SOURCE_INVALIDATIONS = new LongAdder();
    private static final LongAdder POINT_INVALIDATIONS = new LongAdder();
    private static final LongAdder RETARGET_INVALIDATIONS = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();
    private static final LongAdder RETRIES = new LongAdder();
    private static final LongAdder IDLE_EVICTIONS = new LongAdder();

    /** Interval maxima are updated under LOCK and reset by statsAndReset(). */
    private static long maintenanceMaxNanos;
    private static long maintenanceMaxOverrunNanos;
    private static long readyLatencyMaxNanos;
    private static long deferredLookupMaxNanos;
    private static long deferredPlanMaxNanos;
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
    static List lookup(LocationAPI current, Class jumpPointType,
                       LocationAPI requested, LocationAPI fallback,
                       PrepatcherConfig config, long currentGeneration) {
        LOOKUPS.increment();
        if (current == null || jumpPointType == null || config == null
                || !config.strategicJumpDestinationIndex) {
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
            LocationState state = observeOrAdmit(current, jumpPointType, live,
                    config, currentGeneration);
            if (state == null || !state.active
                    || state.phase != LocationState.READY || state.data == null) {
                DEFERRED_LOOKUPS.increment();
                if (state != null && state.active) state.noteDeferredLookup();
                return DEFERRED;
            }
            List candidates = state.data.candidates(requested, fallback,
                    acceptsFallback(current, requested, fallback));
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
    static boolean deferExpiredPlan(LocationAPI current, Class jumpPointType,
                                    PrepatcherConfig config,
                                    long currentGeneration) {
        if (current == null || jumpPointType == null || config == null
                || !config.strategicJumpDestinationIndex) return false;
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
            LocationState state = observeOrAdmit(current, jumpPointType, live,
                    config, currentGeneration);
            boolean deferred = state == null || !state.active
                    || state.phase == LocationState.BUILDING
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
    private static LocationState observeOrAdmit(LocationAPI current,
                                                 Class jumpPointType,
                                                 List live,
                                                 PrepatcherConfig config,
                                                 long currentGeneration) {
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

    static void maintenance(PrepatcherConfig config, long currentGeneration,
                            LongSupplier clock) {
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
                int auditLimit = Math.max(1,
                        config.strategicJumpIndexMaxWorkUnits >>> 4);
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
                        if (state.active && state.phase == LocationState.READY
                                && state.data != null) {
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

    static void destinationAdded(JumpPointAPI point,
                                 JumpPointAPI.JumpDestination destination) {
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
            long intervalReadyLatencyMax = Math.max(readyLatencyMaxNanos,
                    debt.oldestPendingNanos);
            long intervalDeferredLookupMax = Math.max(deferredLookupMaxNanos,
                    debt.oldestDeferredLookupNanos);
            long intervalDeferredPlanMax = Math.max(deferredPlanMaxNanos,
                    debt.oldestDeferredPlanNanos);
            long currentAdmissionAge = ageNanos(admissionDeferredSinceNanos,
                    lastNowNanos);
            long intervalAdmissionMax = Math.max(admissionDeferredMaxNanos,
                    currentAdmissionAge);
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

            return ", strategicJumpIndexLocations=" + states.size()
                    + ", strategicJumpIndexQueued=" + workQueue.size()
                    + ", strategicJumpIndexAuditQueued=" + auditQueueSize
                    + ", strategicJumpIndexRetryQueued=" + retryHeap.size()
                    + ", strategicJumpIndexEvictable=" + evictableCount
                    + ", strategicJumpIndexBuilding=" + debt.building
                    + ", strategicJumpIndexRefreshing=" + debt.refreshing
                    + ", strategicJumpIndexReady=" + debt.ready
                    + ", strategicJumpIndexFailed=" + debt.failed
                    + ", strategicJumpIndexLookups=" + LOOKUPS.sumThenReset()
                    + ", strategicJumpIndexHits=" + HITS.sumThenReset()
                    + ", strategicJumpIndexNegativeHits=" + NEGATIVE_HITS.sumThenReset()
                    + ", strategicJumpIndexDeferred=" + DEFERRED_LOOKUPS.sumThenReset()
                    + ", strategicJumpIndexDeferredPlans=" + DEFERRED_PLANS.sumThenReset()
                    + ", strategicJumpIndexAdmissionDeferred="
                    + DEFERRED_ADMISSIONS.sumThenReset()
                    + ", strategicJumpIndexProtectedCapacityDeferred="
                    + PROTECTED_CAPACITY_DEFERRALS.sumThenReset()
                    + ", strategicJumpIndexAdmissions=" + ADMISSIONS.sumThenReset()
                    + ", strategicJumpIndexEvictions=" + EVICTIONS.sumThenReset()
                    + ", strategicJumpIndexIdleEvictions=" + IDLE_EVICTIONS.sumThenReset()
                    + ", strategicJumpIndexBuildsStarted=" + BUILDS_STARTED.sumThenReset()
                    + ", strategicJumpIndexBuildsCompleted=" + BUILDS_COMPLETED.sumThenReset()
                    + ", strategicJumpIndexBuildRestarts=" + BUILD_RESTARTS.sumThenReset()
                    + ", strategicJumpIndexMaintenanceCalls="
                    + MAINTENANCE_CALLS.sumThenReset()
                    + ", strategicJumpIndexMaintenanceNanos="
                    + MAINTENANCE_NANOS.sumThenReset()
                    + ", strategicJumpIndexMaintenanceMaxNanos="
                    + intervalMaintenanceMax
                    + ", strategicJumpIndexMaintenanceMaxOverrunNanos="
                    + intervalOverrunMax
                    + ", strategicJumpIndexBudgetExhaustions="
                    + BUDGET_EXHAUSTIONS.sumThenReset()
                    + ", strategicJumpIndexWorkUnitExhaustions="
                    + WORK_UNIT_EXHAUSTIONS.sumThenReset()
                    + ", strategicJumpIndexWorkUnits=" + WORK_UNITS.sumThenReset()
                    + ", strategicJumpIndexWorkNanos=" + WORK_NANOS.sumThenReset()
                    + ", strategicJumpIndexPointRefreshes=" + POINT_REFRESHES.sumThenReset()
                    + ", strategicJumpIndexAuditUnits=" + AUDIT_UNITS.sumThenReset()
                    + ", strategicJumpIndexAuditRepairs=" + AUDIT_REPAIRS.sumThenReset()
                    + ", strategicJumpIndexSourceInvalidations="
                    + SOURCE_INVALIDATIONS.sumThenReset()
                    + ", strategicJumpIndexPointInvalidations="
                    + POINT_INVALIDATIONS.sumThenReset()
                    + ", strategicJumpIndexRetargetInvalidations="
                    + RETARGET_INVALIDATIONS.sumThenReset()
                    + ", strategicJumpIndexFailures=" + FAILURES.sumThenReset()
                    + ", strategicJumpIndexRetries=" + RETRIES.sumThenReset()
                    + ", strategicJumpIndexWorkQueueMax=" + intervalWorkQueueMax
                    + ", strategicJumpIndexAuditQueueMax=" + intervalAuditQueueMax
                    + ", strategicJumpIndexRetryQueueMax=" + intervalRetryQueueMax
                    + ", strategicJumpIndexOldestPendingMs="
                    + nanosToMillis(debt.oldestPendingNanos)
                    + ", strategicJumpIndexOldestDeferredLookupMs="
                    + nanosToMillis(debt.oldestDeferredLookupNanos)
                    + ", strategicJumpIndexOldestDeferredPlanMs="
                    + nanosToMillis(debt.oldestDeferredPlanNanos)
                    + ", strategicJumpIndexAdmissionDeferredAgeMs="
                    + nanosToMillis(currentAdmissionAge)
                    + ", strategicJumpIndexReadyLatencyMaxMs="
                    + nanosToMillis(intervalReadyLatencyMax)
                    + ", strategicJumpIndexDeferredLookupMaxMs="
                    + nanosToMillis(intervalDeferredLookupMax)
                    + ", strategicJumpIndexDeferredPlanMaxMs="
                    + nanosToMillis(intervalDeferredPlanMax)
                    + ", strategicJumpIndexAdmissionDeferredMaxMs="
                    + nanosToMillis(intervalAdmissionMax);
        }
    }

    /** Package-private diagnostics used by the runtime regression harness. */
    static Snapshot snapshot(LocationAPI location) {
        synchronized (LOCK) {
            LocationState state = states.get(location);
            return state == null
                    ? new Snapshot(states.size(), workQueue.size(), "ABSENT",
                    0, 0, 0, 0)
                    : new Snapshot(states.size(), workQueue.size(), state.phaseName(),
                    state.data == null ? 0 : state.data.size,
                    state.dirtySet.size(),
                    state.build == null ? 0 : state.build.sourceIndex,
                    state.build == null || state.build.capture == null
                            ? 0 : state.build.capture.cursor);
        }
    }

    static Diagnostics diagnostics() {
        synchronized (LOCK) {
            DebtGauges debt = debtGauges(lastNowNanos);
            return new Diagnostics(WORK_UNITS.sum(), MAINTENANCE_NANOS.sum(),
                    maintenanceMaxNanos, BUILDS_STARTED.sum(), BUILDS_COMPLETED.sum(),
                    POINT_REFRESHES.sum(), FAILURES.sum(), RETRIES.sum(),
                    DEFERRED_PLANS.sum(), PROTECTED_CAPACITY_DEFERRALS.sum(),
                    states.size(), workQueue.size(), auditQueueSize, retryHeap.size(),
                    evictableCount, debt.oldestPendingNanos,
                    debt.oldestDeferredLookupNanos, debt.oldestDeferredPlanNanos);
        }
    }

    record Snapshot(int locations, int queued, String phase, int indexedPoints,
                    int dirtyPoints, int buildPointCursor,
                    int buildDestinationCursor) {}

    record Diagnostics(long workUnits, long maintenanceNanos,
                       long maintenanceMaxNanos, long buildsStarted,
                       long buildsCompleted, long pointRefreshes, long failures,
                       long retries, long deferredPlans,
                       long protectedCapacityDeferrals, int locations, int queued,
                       int auditQueued, int retryQueued, int evictable,
                       long oldestPendingNanos, long oldestDeferredLookupNanos,
                       long oldestDeferredPlanNanos) {}

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
                default -> { }
            }
            oldestPending = Math.max(oldestPending,
                    ageNanos(state.pendingSinceNanos, now));
            oldestDeferredLookup = Math.max(oldestDeferredLookup,
                    ageNanos(state.deferredLookupSinceNanos, now));
            oldestDeferredPlan = Math.max(oldestDeferredPlan,
                    ageNanos(state.deferredPlanSinceNanos, now));
        }
        return new DebtGauges(building, refreshing, ready, failed,
                oldestPending, oldestDeferredLookup, oldestDeferredPlan);
    }

    private static long ageNanos(long since, long now) {
        return since > 0L && now > since ? now - since : 0L;
    }

    private static long nanosToMillis(long nanos) {
        return nanos <= 0L ? 0L : nanos / 1_000_000L;
    }

    private record DebtGauges(int building, int refreshing, int ready, int failed,
                              long oldestPendingNanos,
                              long oldestDeferredLookupNanos,
                              long oldestDeferredPlanNanos) {}

    private static boolean acceptsFallback(LocationAPI current, LocationAPI requested,
                                           LocationAPI fallback) {
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
        if (!states.isEmpty() || stateMapCapacity == config.strategicJumpIndexMaxLocations) {
            return;
        }
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
        admissionsAvailable = Math.min(admissionCapacity,
                admissionsAvailable + admissionAdd);
    }

    private static Object safeGet(List<?> values, int index) {
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

    private static void enqueue(LocationState state) {
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

    private static void scheduleAudit(LocationState state) {
        if (!state.active || state.phase != LocationState.READY || state.data == null
                || state.auditLinked) return;
        state.auditPrevious = auditTail;
        state.auditNext = null;
        if (auditTail == null) auditHead = state;
        else auditTail.auditNext = state;
        auditTail = state;
        state.auditLinked = true;
        auditQueueSize++;
        if (auditQueueSize > auditQueueMax) auditQueueMax = auditQueueSize;
    }

    private static void unlinkAudit(LocationState state) {
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
            if (state.active && state.phase == LocationState.READY && state.data != null) {
                return state;
            }
        }
        return null;
    }

    private static void scheduleRetry(LocationState state) {
        if (!state.active || state.phase != LocationState.FAILED) return;
        removeRetry(state);
        state.retryOrder = ++retrySequence;
        int index = retryHeap.size();
        retryHeap.add(state);
        state.retryHeapIndex = index;
        siftRetryUp(index);
        if (retryHeap.size() > retryQueueMax) retryQueueMax = retryHeap.size();
    }

    private static void removeRetry(LocationState state) {
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
        if (first.retryAtNanos != second.retryAtNanos) {
            return first.retryAtNanos < second.retryAtNanos;
        }
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

    private static void linkEvictableTail(LocationState state) {
        if (state.evictableLinked || !state.active
                || (state.phase != LocationState.READY
                && state.phase != LocationState.FAILED)) return;
        state.evictablePrevious = evictableTail;
        state.evictableNext = null;
        if (evictableTail == null) evictableHead = state;
        else evictableTail.evictableNext = state;
        evictableTail = state;
        state.evictableLinked = true;
        evictableCount++;
    }

    private static void unlinkEvictable(LocationState state) {
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
        if (now < lruHead.lastAccessNanos
                || now - lruHead.lastAccessNanos < ttl) return false;
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
        long age = elapsedNanos(admissionDeferredSinceNanos,
                Math.max(admissionDeferredSinceNanos, lastNowNanos));
        if (age > admissionDeferredMaxNanos) admissionDeferredMaxNanos = age;
        admissionDeferredSinceNanos = 0L;
    }

    private static final class LocationState {
        static final int BUILDING = 1;
        static final int REFRESHING = 2;
        static final int READY = 3;
        static final int FAILED = 4;

        final LocationAPI location;
        Class<?> jumpPointType;
        boolean active = true;
        boolean queued;
        boolean auditLinked;
        boolean evictableLinked;
        LocationState previous;
        LocationState next;
        LocationState auditPrevious;
        LocationState auditNext;
        LocationState evictablePrevious;
        LocationState evictableNext;
        int retryHeapIndex = -1;
        long retryOrder;
        long lastAccessNanos;
        long sourceEpoch;
        long pendingSinceNanos;
        long deferredLookupSinceNanos;
        long deferredPlanSinceNanos;
        List<?> observedSource;
        int observedSize;
        Object observedFirst;
        Object observedLast;
        boolean observedRandomAccess;
        boolean fullBuildRequested = true;
        int phase = BUILDING;
        long retryAtNanos;
        int consecutiveFailures;
        IndexData data;
        BuildTask build;
        PointRefreshTask refresh;
        final ArrayDeque<JumpPointAPI> dirtyQueue = new ArrayDeque<>();
        final IdentityHashMap<JumpPointAPI, Boolean> dirtySet = new IdentityHashMap<>();
        AuditTask audit;

        LocationState(LocationAPI location, Class<?> jumpPointType) {
            this.location = location;
            this.jumpPointType = jumpPointType;
        }

        String phaseName() {
            return switch (phase) {
                case BUILDING -> "BUILDING";
                case REFRESHING -> "REFRESHING";
                case READY -> "READY";
                case FAILED -> "FAILED_COOLDOWN";
                default -> "UNKNOWN";
            };
        }

        void noteDeferredLookup() {
            if (deferredLookupSinceNanos == 0L && lastNowNanos > 0L) {
                deferredLookupSinceNanos = lastNowNanos;
            }
        }

        void noteDeferredPlan() {
            if (deferredPlanSinceNanos == 0L && lastNowNanos > 0L) {
                deferredPlanSinceNanos = lastNowNanos;
            }
        }

        private void beginPending() {
            if (pendingSinceNanos == 0L && lastNowNanos > 0L) {
                pendingSinceNanos = lastNowNanos;
            }
        }

        void resolveDebt(long now) {
            if (pendingSinceNanos > 0L && now > pendingSinceNanos) {
                readyLatencyMaxNanos = Math.max(readyLatencyMaxNanos,
                        now - pendingSinceNanos);
            }
            if (deferredLookupSinceNanos > 0L && now > deferredLookupSinceNanos) {
                deferredLookupMaxNanos = Math.max(deferredLookupMaxNanos,
                        now - deferredLookupSinceNanos);
            }
            if (deferredPlanSinceNanos > 0L && now > deferredPlanSinceNanos) {
                deferredPlanMaxNanos = Math.max(deferredPlanMaxNanos,
                        now - deferredPlanSinceNanos);
            }
            pendingSinceNanos = 0L;
            deferredLookupSinceNanos = 0L;
            deferredPlanSinceNanos = 0L;
        }

        void observeSource(List<?> live) {
            observedSource = live;
            observedSize = live.size();
            observedRandomAccess = live instanceof RandomAccess;
            observedFirst = observedSize == 0 ? null : safeGet(live, 0);
            observedLast = observedSize == 0 ? null
                    : (observedRandomAccess ? safeGet(live, observedSize - 1) : null);
        }

        boolean fastSourceMatches(List<?> live) {
            if (observedSource != live || observedSize != live.size()) return false;
            if (observedSize == 0) return true;
            if (safeGet(live, 0) != observedFirst) return false;
            return !observedRandomAccess || safeGet(live, observedSize - 1) == observedLast;
        }

        void requestFullBuild() {
            unlinkAudit(this);
            unlinkEvictable(this);
            removeRetry(this);
            data = null;
            build = null;
            refresh = null;
            audit = null;
            dirtyQueue.clear();
            dirtySet.clear();
            fullBuildRequested = true;
            phase = BUILDING;
            retryAtNanos = 0L;
            beginPending();
            enqueue(this);
        }

        private void enterRefreshing() {
            unlinkAudit(this);
            unlinkEvictable(this);
            removeRetry(this);
            phase = REFRESHING;
            beginPending();
        }

        private void enterReady(long now) {
            removeRetry(this);
            unlinkAudit(this);
            unlinkEvictable(this);
            phase = READY;
            resolveDebt(now);
            scheduleAudit(this);
            linkEvictableTail(this);
        }

        void invalidateSource() {
            sourceEpoch++;
            observedSource = null;
            observedSize = -1;
            observedFirst = null;
            observedLast = null;
            observedRandomAccess = false;
            requestFullBuild();
        }

        void markPointDirty(JumpPointAPI point) {
            if (phase == FAILED) {
                sourceEpoch++;
                requestFullBuild();
                return;
            }
            if (dirtySet.put(point, Boolean.TRUE) == null) dirtyQueue.addLast(point);
            if (dirtySet.size() > MAX_PENDING_POINT_DELTAS) {
                // A post-load topology burst is cheaper and more compact as one
                // replacement snapshot than as an unbounded queue of point deltas.
                sourceEpoch++;
                requestFullBuild();
                return;
            }
            if (phase == READY) enterRefreshing();
            retryAtNanos = 0L;
            enqueue(this);
        }

        PointRecord recordFor(JumpPointAPI point) {
            if (refresh != null && refresh.oldRecord.point == point) return refresh.oldRecord;
            if (build != null) {
                PointRecord record = build.data.byPoint.get(point);
                if (record != null) return record;
            }
            return data == null ? null : data.byPoint.get(point);
        }

        boolean hasWork() {
            if (!active || phase == FAILED) return false;
            return fullBuildRequested || build != null || refresh != null || !dirtyQueue.isEmpty();
        }

        boolean workOne(PrepatcherConfig config, long now) {
            if (!active) return false;
            if (phase == FAILED) {
                if (now < retryAtNanos) {
                    scheduleRetry(this);
                    return false;
                }
                RETRIES.increment();
                unlinkEvictable(this);
                phase = BUILDING;
                fullBuildRequested = true;
                beginPending();
            }
            if (fullBuildRequested || build != null) {
                return buildOne(config, now);
            }
            if (refresh != null || !dirtyQueue.isEmpty()) {
                return refreshOne(config, now);
            }
            if (data != null) enterReady(now);
            return false;
        }

        private boolean buildOne(PrepatcherConfig config, long now) {
            if (build == null) {
                List<?> live = observedSource;
                if (live == null) {
                    try {
                        live = location.getEntities(jumpPointType);
                    } catch (Throwable failure) {
                        fail(config, now);
                        return false;
                    }
                    if (live == null) {
                        fail(config, now);
                        return false;
                    }
                    observeSource(live);
                }
                build = new BuildTask(this, live, sourceEpoch);
                fullBuildRequested = false;
                phase = BUILDING;
                BUILDS_STARTED.increment();
            }

            BuildResult result = build.step();
            if (result == BuildResult.MORE) return true;
            if (result == BuildResult.FAILED) {
                fail(config, now);
                return false;
            }
            if (result == BuildResult.RESTART
                    || build.sourceEpoch != sourceEpoch
                    || !fastSourceMatches(build.source)) {
                invalidateSource();
                BUILD_RESTARTS.increment();
                return true;
            }

            data = build.data;
            build = null;
            audit = null;
            consecutiveFailures = 0;
            BUILDS_COMPLETED.increment();
            if (dirtyQueue.isEmpty()) {
                enterReady(now);
                return false;
            }
            enterRefreshing();
            return true;
        }

        private boolean refreshOne(PrepatcherConfig config, long now) {
            if (data == null) {
                requestFullBuild();
                return true;
            }
            if (refresh == null) {
                JumpPointAPI point = null;
                while (!dirtyQueue.isEmpty() && point == null) {
                    JumpPointAPI candidate = dirtyQueue.removeFirst();
                    if (dirtySet.remove(candidate) != null) point = candidate;
                }
                if (point == null) {
                    enterReady(now);
                    return false;
                }
                PointRecord old = data.byPoint.get(point);
                if (old == null) {
                    invalidateSource();
                    return true;
                }
                refresh = new PointRefreshTask(this, data, old);
            }

            RefreshResult result = refresh.step();
            if (result == RefreshResult.MORE) return true;
            if (result == RefreshResult.FAILED) {
                refresh = null;
                fail(config, now);
                return false;
            }
            refresh = null;
            audit = null;
            POINT_REFRESHES.increment();
            if (!dirtyQueue.isEmpty()) return true;
            enterReady(now);
            return false;
        }

        boolean auditOne() {
            if (!active || phase != READY || data == null) return false;
            if (audit == null) audit = new AuditTask(this, data);
            AuditResult result = audit.step();
            if (result == AuditResult.CYCLE_COMPLETE) {
                audit = null;
                return false;
            }
            if (result == AuditResult.SOURCE_CHANGED) {
                audit = null;
                invalidateSource();
                AUDIT_REPAIRS.increment();
                return false;
            }
            if (result == AuditResult.POINT_CHANGED) {
                JumpPointAPI point = audit.changedPoint;
                audit = null;
                markPointDirty(point);
                AUDIT_REPAIRS.increment();
                return false;
            }
            return true;
        }

        void fail(PrepatcherConfig config, long now) {
            unlinkAudit(this);
            unlinkEvictable(this);
            removeRetry(this);
            data = null;
            build = null;
            refresh = null;
            audit = null;
            fullBuildRequested = false;
            dirtyQueue.clear();
            dirtySet.clear();
            phase = FAILED;
            beginPending();
            consecutiveFailures = Math.min(16, consecutiveFailures + 1);
            long delay = (long) config.strategicJumpIndexFailureRetryMs * 1_000_000L;
            int shift = Math.min(4, consecutiveFailures - 1);
            delay = Math.min(delay << shift, 300_000_000_000L);
            retryAtNanos = now + Math.max(1L, delay);
            linkEvictableTail(this);
            scheduleRetry(this);
            FAILURES.increment();
        }
    }

    private enum BuildResult { MORE, COMPLETE, RESTART, FAILED }
    private enum RefreshResult { MORE, COMPLETE, FAILED }
    private enum AuditResult { MORE, CYCLE_COMPLETE, SOURCE_CHANGED, POINT_CHANGED }

    private static final class BuildTask {
        final LocationState state;
        final List<?> source;
        final int size;
        final boolean randomAccess;
        final Object first;
        final Object last;
        final long sourceEpoch;
        final IndexData data;
        Iterator<?> sourceIterator;
        int sourceIndex;
        PointCapture capture;
        PointRecord pendingRecord;
        int contributionIndex;
        int captureRestarts;

        BuildTask(LocationState state, List<?> source, long sourceEpoch) {
            this.state = state;
            this.source = source;
            this.size = source.size();
            this.randomAccess = source instanceof RandomAccess;
            this.first = size == 0 ? null : safeGet(source, 0);
            this.last = size == 0 || !randomAccess ? null : safeGet(source, size - 1);
            this.sourceEpoch = sourceEpoch;
            this.data = new IndexData(source, size, first, last, randomAccess);
            if (!randomAccess) sourceIterator = source.iterator();
        }

        BuildResult step() {
            try {
                if (pendingRecord != null) {
                    if (contributionIndex < pendingRecord.locationCount) {
                        data.addContribution(pendingRecord,
                                pendingRecord.locations[contributionIndex++]);
                        return BuildResult.MORE;
                    }
                    pendingRecord = null;
                    sourceIndex++;
                    return sourceIndex >= size ? BuildResult.COMPLETE : BuildResult.MORE;
                }
                if (capture != null) {
                    if (!capture.complete()) {
                        if (!capture.captureOne()) {
                            capture = null;
                            if (++captureRestarts > 16) return BuildResult.FAILED;
                        }
                        return BuildResult.MORE;
                    }
                    if (!capture.stable()) {
                        capture = null;
                        if (++captureRestarts > 16) return BuildResult.FAILED;
                        return BuildResult.MORE;
                    }
                    PointRecord record = capture.finish();
                    capture = null;
                    captureRestarts = 0;
                    if (data.byPoint.put(record.point, record) != null) {
                        return BuildResult.FAILED;
                    }
                    data.records.set(record.ordinal, record);
                    pendingRecord = record;
                    contributionIndex = 0;
                    return BuildResult.MORE;
                }
                if (sourceIndex >= size) return BuildResult.COMPLETE;
                if (source.size() != size) return BuildResult.RESTART;
                Object raw = randomAccess ? source.get(sourceIndex) : sourceIterator.next();
                if (!(raw instanceof JumpPointAPI point)) return BuildResult.FAILED;
                capture = new PointCapture(state, point, sourceIndex);
                return BuildResult.MORE;
            } catch (Throwable failure) {
                return BuildResult.FAILED;
            }
        }
    }

    private static final class PointRefreshTask {
        private static final int CAPTURE = 1;
        private static final int REMOVE = 2;
        private static final int ADD = 3;
        private static final int COMMIT = 4;

        final LocationState state;
        final IndexData data;
        final PointRecord oldRecord;
        PointCapture capture;
        PointRecord newRecord;
        int phase = CAPTURE;
        int index;
        int captureRestarts;

        PointRefreshTask(LocationState state, IndexData data, PointRecord oldRecord) {
            this.state = state;
            this.data = data;
            this.oldRecord = oldRecord;
        }

        RefreshResult step() {
            try {
                if (phase == CAPTURE) {
                    if (capture == null) {
                        capture = new PointCapture(state, oldRecord.point, oldRecord.ordinal);
                        return RefreshResult.MORE;
                    }
                    if (!capture.complete()) {
                        if (!capture.captureOne()) {
                            capture = null;
                            if (++captureRestarts > 16) return RefreshResult.FAILED;
                        }
                        return RefreshResult.MORE;
                    }
                    if (!capture.stable()) {
                        capture = null;
                        if (++captureRestarts > 16) return RefreshResult.FAILED;
                        return RefreshResult.MORE;
                    }
                    newRecord = capture.finish();
                    capture = null;
                    captureRestarts = 0;
                    phase = REMOVE;
                    index = 0;
                    return RefreshResult.MORE;
                }
                if (phase == REMOVE) {
                    if (index < oldRecord.locationCount) {
                        data.removeContribution(oldRecord, oldRecord.locations[index++]);
                        return RefreshResult.MORE;
                    }
                    phase = ADD;
                    index = 0;
                    return RefreshResult.MORE;
                }
                if (phase == ADD) {
                    if (index < newRecord.locationCount) {
                        data.addContribution(newRecord, newRecord.locations[index++]);
                        return RefreshResult.MORE;
                    }
                    phase = COMMIT;
                    return RefreshResult.MORE;
                }
                data.byPoint.put(newRecord.point, newRecord);
                data.records.set(newRecord.ordinal, newRecord);
                data.clearMergeCache();
                return RefreshResult.COMPLETE;
            } catch (Throwable failure) {
                return RefreshResult.FAILED;
            }
        }
    }

    private static final class AuditTask {
        final LocationState state;
        final IndexData data;
        Iterator<?> sourceIterator;
        int ordinal;
        int destinationIndex = -1;
        Iterator<?> destinationIterator;
        PointRecord current;
        JumpPointAPI changedPoint;

        AuditTask(LocationState state, IndexData data) {
            this.state = state;
            this.data = data;
            if (!data.randomAccess) sourceIterator = data.source.iterator();
        }

        AuditResult step() {
            try {
                if (data.source != state.observedSource || data.source.size() != data.size) {
                    return AuditResult.SOURCE_CHANGED;
                }
                if (ordinal >= data.size) return AuditResult.CYCLE_COMPLETE;
                current = data.records.get(ordinal);
                if (current == null) return AuditResult.SOURCE_CHANGED;

                if (destinationIndex < 0) {
                    Object raw = data.randomAccess ? data.source.get(ordinal)
                            : sourceIterator.next();
                    if (raw != current.point) return AuditResult.SOURCE_CHANGED;
                    List<JumpPointAPI.JumpDestination> live = current.point.getDestinations();
                    if (live == null || live.size() != current.destinationCount) {
                        changedPoint = current.point;
                        return AuditResult.POINT_CHANGED;
                    }
                    destinationIterator = live instanceof RandomAccess ? null : live.iterator();
                    destinationIndex = 0;
                    if (current.destinationCount == 0) {
                        ordinal++;
                        destinationIndex = -1;
                    }
                    return AuditResult.MORE;
                }

                Object liveDestination;
                List<JumpPointAPI.JumpDestination> live = current.point.getDestinations();
                if (live == null || live.size() != current.destinationCount) {
                    changedPoint = current.point;
                    return AuditResult.POINT_CHANGED;
                }
                liveDestination = live instanceof RandomAccess
                        ? live.get(destinationIndex) : destinationIterator.next();
                if (!current.matches(destinationIndex, liveDestination)) {
                    changedPoint = current.point;
                    return AuditResult.POINT_CHANGED;
                }
                destinationIndex++;
                if (destinationIndex >= current.destinationCount) {
                    ordinal++;
                    destinationIndex = -1;
                    destinationIterator = null;
                }
                return AuditResult.MORE;
            } catch (Throwable failure) {
                if (destinationIndex >= 0 && current != null) changedPoint = current.point;
                return destinationIndex < 0
                        ? AuditResult.SOURCE_CHANGED : AuditResult.POINT_CHANGED;
            }
        }
    }

    private static final class PointCapture {
        final LocationState state;
        final JumpPointAPI point;
        final int ordinal;
        final List<JumpPointAPI.JumpDestination> live;
        final boolean randomAccess;
        final int size;
        final Object[] snapshot;
        final LocationAPI[] locations;
        final SegmentedIdentityMap<LocationAPI, Boolean> seen;
        final PointRecord provisional;
        Iterator<JumpPointAPI.JumpDestination> iterator;
        int cursor;
        int locationCount;

        PointCapture(LocationState state, JumpPointAPI point, int ordinal) {
            this.state = state;
            this.point = point;
            this.ordinal = ordinal;
            this.live = point.getDestinations();
            if (live == null) throw new IllegalStateException("JumpPoint destination list is null");
            this.randomAccess = live instanceof RandomAccess;
            this.size = live.size();
            this.snapshot = size == 0 ? PointRecord.EMPTY_OBJECTS : new Object[size * 3];
            this.locations = size == 0 ? PointRecord.EMPTY_LOCATIONS : new LocationAPI[size];
            this.seen = size > 8 ? new SegmentedIdentityMap<>(size) : null;
            this.provisional = new PointRecord(state, point, ordinal, size,
                    snapshot, locations, 0);
            if (!randomAccess) iterator = live.iterator();
        }

        boolean complete() {
            return cursor >= size;
        }

        boolean captureOne() {
            if (live.size() != size) return false;
            JumpPointAPI.JumpDestination destination;
            try {
                destination = randomAccess ? live.get(cursor) : iterator.next();
            } catch (IndexOutOfBoundsException | NoSuchElementException
                     | java.util.ConcurrentModificationException changed) {
                return false;
            }
            if (destination == null) throw new IllegalStateException("Null JumpDestination");
            SectorEntityToken target = destination.getDestination();
            LocationAPI location = target == null ? null : target.getContainingLocation();
            int offset = cursor * 3;
            snapshot[offset] = destination;
            snapshot[offset + 1] = target;
            snapshot[offset + 2] = location;
            destinationOwners.put(destination, provisional);
            if (target != null && addUnique(location)) locations[locationCount++] = location;
            cursor++;
            return true;
        }

        boolean stable() {
            try {
                return live.size() == size;
            } catch (Throwable failure) {
                return false;
            }
        }

        PointRecord finish() {
            provisional.locationCount = locationCount;
            return provisional;
        }

        private boolean addUnique(LocationAPI location) {
            if (seen != null) return seen.put(location, Boolean.TRUE) == null;
            for (int i = 0; i < locationCount; i++) {
                if (locations[i] == location) return false;
            }
            return true;
        }
    }

    private static final class PointRecord {
        static final Object[] EMPTY_OBJECTS = new Object[0];
        static final LocationAPI[] EMPTY_LOCATIONS = new LocationAPI[0];

        final LocationState state;
        final JumpPointAPI point;
        final int ordinal;
        final int destinationCount;
        final Object[] snapshot;
        final LocationAPI[] locations;
        int locationCount;

        PointRecord(LocationState state, JumpPointAPI point, int ordinal,
                    int destinationCount, Object[] snapshot,
                    LocationAPI[] locations, int locationCount) {
            this.state = state;
            this.point = point;
            this.ordinal = ordinal;
            this.destinationCount = destinationCount;
            this.snapshot = snapshot;
            this.locations = locations;
            this.locationCount = locationCount;
        }

        boolean matches(int index, Object rawDestination) {
            if (!(rawDestination instanceof JumpPointAPI.JumpDestination destination)) return false;
            int offset = index * 3;
            if (snapshot[offset] != destination) return false;
            SectorEntityToken target = destination.getDestination();
            if (snapshot[offset + 1] != target) return false;
            LocationAPI location = target == null ? null : target.getContainingLocation();
            return snapshot[offset + 2] == location;
        }
    }

    private static final class IndexData {
        private static final int MERGE_CACHE_SIZE = 8;

        final List<?> source;
        final int size;
        final Object first;
        final Object last;
        final boolean randomAccess;
        final PointRecordTable records;
        final SegmentedIdentityMap<JumpPointAPI, PointRecord> byPoint;
        final SegmentedIdentityMap<LocationAPI, CandidateBucket> byDestination;
        final LocationAPI[] mergeDirectKeys = new LocationAPI[MERGE_CACHE_SIZE];
        final LocationAPI[] mergeFallbackKeys = new LocationAPI[MERGE_CACHE_SIZE];
        final List<?>[] mergeValues = new List<?>[MERGE_CACHE_SIZE];
        int mergeCursor;

        IndexData(List<?> source, int size, Object first, Object last,
                  boolean randomAccess) {
            this.source = source;
            this.size = size;
            this.first = first;
            this.last = last;
            this.randomAccess = randomAccess;
            this.records = new PointRecordTable();
            this.byPoint = new SegmentedIdentityMap<>(size);
            this.byDestination = new SegmentedIdentityMap<>(size);
        }

        void addContribution(PointRecord record, LocationAPI location) {
            CandidateBucket bucket = byDestination.get(location);
            if (bucket == null) {
                bucket = new CandidateBucket();
                byDestination.put(location, bucket);
            }
            bucket.add(record);
        }

        void removeContribution(PointRecord record, LocationAPI location) {
            CandidateBucket bucket = byDestination.get(location);
            if (bucket == null) return;
            bucket.remove(record);
            if (bucket.isEmpty()) byDestination.remove(location);
        }

        @SuppressWarnings("rawtypes")
        List candidates(LocationAPI requested, LocationAPI fallback,
                        boolean includeFallback) {
            CandidateBucket direct = byDestination.get(requested);
            if (!includeFallback || requested == fallback) {
                return direct == null ? DEFERRED : direct;
            }
            CandidateBucket fallbackBucket = byDestination.get(fallback);
            if (direct == null) return fallbackBucket == null ? DEFERRED : fallbackBucket;
            if (fallbackBucket == null || fallbackBucket == direct) return direct;
            for (int i = 0; i < MERGE_CACHE_SIZE; i++) {
                if (mergeDirectKeys[i] == requested && mergeFallbackKeys[i] == fallback) {
                    return (List) mergeValues[i];
                }
            }
            MergedCandidateList merged = new MergedCandidateList(direct, fallbackBucket);
            int slot = mergeCursor++ & (MERGE_CACHE_SIZE - 1);
            mergeDirectKeys[slot] = requested;
            mergeFallbackKeys[slot] = fallback;
            mergeValues[slot] = merged;
            return merged;
        }

        void clearMergeCache() {
            for (int i = 0; i < MERGE_CACHE_SIZE; i++) {
                mergeDirectKeys[i] = null;
                mergeFallbackKeys[i] = null;
                mergeValues[i] = null;
            }
            mergeCursor = 0;
        }
    }

    private static final class CandidateBucket extends AbstractList<SectorEntityToken>
            implements RandomAccess {
        private static final Comparator<PointRecord> BY_ORDINAL =
                Comparator.comparingInt(record -> record.ordinal);
        final TreeSet<PointRecord> records = new TreeSet<>(BY_ORDINAL);

        void add(PointRecord record) {
            if (!records.add(record)) {
                PointRecord existing = records.ceiling(record);
                if (existing == null || existing.ordinal != record.ordinal
                        || existing.point != record.point) {
                    throw new IllegalStateException("Duplicate strategic jump ordinal");
                }
            }
        }

        void remove(PointRecord record) {
            records.remove(record);
        }

        @Override
        public SectorEntityToken get(int index) {
            if (index < 0 || index >= records.size()) throw new IndexOutOfBoundsException(index);
            int current = 0;
            for (PointRecord record : records) {
                if (current++ == index) return (SectorEntityToken) record.point;
            }
            throw new IndexOutOfBoundsException(index);
        }

        @Override
        public int size() {
            return records.size();
        }

        @Override
        public Iterator<SectorEntityToken> iterator() {
            Iterator<PointRecord> delegate = records.iterator();
            return new Iterator<>() {
                @Override public boolean hasNext() { return delegate.hasNext(); }
                @Override public SectorEntityToken next() {
                    return (SectorEntityToken) delegate.next().point;
                }
            };
        }
    }

    private static final class MergedCandidateList extends AbstractList<SectorEntityToken>
            implements RandomAccess {
        final CandidateBucket first;
        final CandidateBucket second;
        int cachedSize = -1;

        MergedCandidateList(CandidateBucket first, CandidateBucket second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public SectorEntityToken get(int index) {
            if (index < 0 || index >= size()) throw new IndexOutOfBoundsException(index);
            Iterator<SectorEntityToken> iterator = iterator();
            for (int i = 0; i < index; i++) iterator.next();
            return iterator.next();
        }

        @Override
        public int size() {
            int result = cachedSize;
            if (result < 0) {
                result = mergedSize(first.records.iterator(), second.records.iterator());
                cachedSize = result;
            }
            return result;
        }

        @Override
        public Iterator<SectorEntityToken> iterator() {
            return new MergeIterator(first.records.iterator(), second.records.iterator());
        }

        private static int mergedSize(Iterator<PointRecord> first,
                                      Iterator<PointRecord> second) {
            PointRecord a = first.hasNext() ? first.next() : null;
            PointRecord b = second.hasNext() ? second.next() : null;
            int count = 0;
            while (a != null || b != null) {
                count++;
                if (b == null || (a != null && a.ordinal < b.ordinal)) {
                    a = first.hasNext() ? first.next() : null;
                } else if (a == null || b.ordinal < a.ordinal) {
                    b = second.hasNext() ? second.next() : null;
                } else {
                    a = first.hasNext() ? first.next() : null;
                    b = second.hasNext() ? second.next() : null;
                }
            }
            return count;
        }
    }

    private static final class MergeIterator implements Iterator<SectorEntityToken> {
        final Iterator<PointRecord> first;
        final Iterator<PointRecord> second;
        PointRecord a;
        PointRecord b;

        MergeIterator(Iterator<PointRecord> first, Iterator<PointRecord> second) {
            this.first = first;
            this.second = second;
            a = first.hasNext() ? first.next() : null;
            b = second.hasNext() ? second.next() : null;
        }

        @Override
        public boolean hasNext() {
            return a != null || b != null;
        }

        @Override
        public SectorEntityToken next() {
            if (!hasNext()) throw new NoSuchElementException();
            PointRecord result;
            if (b == null || (a != null && a.ordinal < b.ordinal)) {
                result = a;
                a = first.hasNext() ? first.next() : null;
            } else if (a == null || b.ordinal < a.ordinal) {
                result = b;
                b = second.hasNext() ? second.next() : null;
            } else {
                result = a;
                a = first.hasNext() ? first.next() : null;
                b = second.hasNext() ? second.next() : null;
            }
            return (SectorEntityToken) result.point;
        }
    }

    private static final class PointRecordTable {
        private static final int SHIFT = 6;
        private static final int CHUNK = 1 << SHIFT;
        private final ArrayList<PointRecord[]> chunks = new ArrayList<>();

        void set(int index, PointRecord record) {
            int chunkIndex = index >> SHIFT;
            while (chunks.size() <= chunkIndex) chunks.add(new PointRecord[CHUNK]);
            chunks.get(chunkIndex)[index & (CHUNK - 1)] = record;
        }

        PointRecord get(int index) {
            int chunkIndex = index >> SHIFT;
            if (chunkIndex >= chunks.size()) return null;
            return chunks.get(chunkIndex)[index & (CHUNK - 1)];
        }
    }

    /** Identity map sharded so no resize ever copies the complete location index. */
    private static final class SegmentedIdentityMap<K, V> {
        private final IdentityHashMap<K, V>[] segments;
        private final int mask;

        @SuppressWarnings("unchecked")
        SegmentedIdentityMap(int expectedSize) {
            int count = 4;
            while (count < 128 && count * 16 < Math.max(1, expectedSize)) count <<= 1;
            segments = (IdentityHashMap<K, V>[]) new IdentityHashMap<?, ?>[count];
            mask = count - 1;
        }

        V get(K key) {
            IdentityHashMap<K, V> segment = segments[index(key)];
            return segment == null ? null : segment.get(key);
        }

        V put(K key, V value) {
            int index = index(key);
            IdentityHashMap<K, V> segment = segments[index];
            if (segment == null) segments[index] = segment = new IdentityHashMap<>(8);
            return segment.put(key, value);
        }

        V remove(K key) {
            IdentityHashMap<K, V> segment = segments[index(key)];
            return segment == null ? null : segment.remove(key);
        }

        private int index(K key) {
            int hash = System.identityHashCode(key);
            hash ^= hash >>> 16;
            return hash & mask;
        }
    }

    /**
     * Four-way set-associative weak identity cache. It is intentionally bounded:
     * missed setter events are repaired by the incremental audit, while entries
     * from evicted locations cannot accumulate for the lifetime of the campaign.
     */
    private static final class WeakIdentityOwnerCache {
        private static final int WAYS = 4;
        private final WeakReference<JumpPointAPI.JumpDestination>[] keys;
        private final WeakReference<PointRecord>[] values;
        private final int[] stamps;
        private final byte[] nextWay;
        private final int setMask;
        private int epoch = 1;

        @SuppressWarnings("unchecked")
        WeakIdentityOwnerCache(int requestedCapacity) {
            int sets = 1;
            int requestedSets = Math.max(1, requestedCapacity / WAYS);
            while (sets < requestedSets) sets <<= 1;
            keys = (WeakReference<JumpPointAPI.JumpDestination>[]) new WeakReference<?>[sets * WAYS];
            values = (WeakReference<PointRecord>[]) new WeakReference<?>[sets * WAYS];
            stamps = new int[sets * WAYS];
            nextWay = new byte[sets];
            setMask = sets - 1;
        }

        PointRecord get(JumpPointAPI.JumpDestination key) {
            if (key == null) return null;
            int base = setBase(key);
            for (int way = 0; way < WAYS; way++) {
                int slot = base + way;
                if (stamps[slot] != epoch) continue;
                WeakReference<JumpPointAPI.JumpDestination> keyReference = keys[slot];
                if (keyReference == null || keyReference.get() != key) continue;
                WeakReference<PointRecord> valueReference = values[slot];
                return valueReference == null ? null : valueReference.get();
            }
            return null;
        }

        void put(JumpPointAPI.JumpDestination key, PointRecord value) {
            if (key == null || value == null) return;
            int base = setBase(key);
            int selected = -1;
            for (int way = 0; way < WAYS; way++) {
                int slot = base + way;
                if (stamps[slot] != epoch) {
                    if (selected < 0) selected = slot;
                    continue;
                }
                WeakReference<JumpPointAPI.JumpDestination> keyReference = keys[slot];
                JumpPointAPI.JumpDestination existing =
                        keyReference == null ? null : keyReference.get();
                WeakReference<PointRecord> valueReference = values[slot];
                if (existing == key) {
                    values[slot] = new WeakReference<>(value);
                    return;
                }
                if (selected < 0 && (existing == null
                        || valueReference == null || valueReference.get() == null)) {
                    selected = slot;
                }
            }
            if (selected < 0) {
                int set = base / WAYS;
                selected = base + (nextWay[set]++ & (WAYS - 1));
            }
            keys[selected] = new WeakReference<>(key);
            values[selected] = new WeakReference<>(value);
            stamps[selected] = epoch;
        }

        void clear() {
            epoch++;
            if (epoch == 0) {
                Arrays.fill(stamps, 0);
                epoch = 1;
            }
        }

        private int setBase(Object key) {
            int hash = System.identityHashCode(key);
            hash ^= hash >>> 16;
            return (hash & setMask) * WAYS;
        }
    }
}
