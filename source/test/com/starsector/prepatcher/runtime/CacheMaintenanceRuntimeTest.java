package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.StarsectorPrepatcherHooks;
import com.fs.starfarer.api.StarsectorPrepatcherHyperspaceHooks;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.starsector.prepatcher.agent.PrepatcherConfig;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/** Deterministic runtime coverage for UI-owner maintenance and delayed pool trimming. */
public final class CacheMaintenanceRuntimeTest {
    private static final long MS = 1_000_000L;
    private static final AtomicLong CLOCK = new AtomicLong(1_000L * MS);
    /** Strong owner for the engine published only weakly by production hooks. */
    private static Object activeEngineFixture;

    private CacheMaintenanceRuntimeTest() {}

    public static void main(String[] args) throws Exception {
        PrepatcherConfig config = config();
        installConfig(config);
        installClock(StarsectorPrepatcherHooks.class, CLOCK::get);
        installClock(StarsectorPrepatcherHyperspaceHooks.class, CLOCK::get);
        configureHyperspace(config);
        activateCampaign(new Object());
        try {
            testOwnerIdleBoundaries(config);
            testLruLimitUsesLastAccess(config);
            testHoverCellPruning(config);
            testHoverTtlBoundary(config);
            testScratchClearedPeak(config);
            testScratchDepthTrimming(config);
            testScratchGaugeAggregationAndReset(config);
            testScratchGraceTrimming(config);
            testStarfieldClockAndGraceTrimming(config);
            testStarfieldLifecycleResetAndStaleLease();
            testGenerationIsolation(config);
            System.out.println("CacheMaintenanceRuntimeTest PASSED");
        } finally {
            installClock(StarsectorPrepatcherHooks.class, System::nanoTime);
            installClock(StarsectorPrepatcherHyperspaceHooks.class, System::nanoTime);
            Object engine = activeEngineFixture;
            long transition = StarsectorPrepatcherHooks.beginCampaignEngineChange(null);
            StarsectorPrepatcherHooks.completeCampaignEngineChange(null, transition);
            Reference.reachabilityFence(engine);
            activeEngineFixture = null;
        }
    }

    private static void testOwnerIdleBoundaries(PrepatcherConfig config) throws Exception {
        CLOCK.set(10_000L * MS);
        IdentityHashMap<Object, Object> labels = ownerMap("LABEL_INDEXES");
        IdentityHashMap<Object, Object> intel = ownerMap("INTEL_ENTITY_INDEXES");
        IdentityHashMap<Object, Object> hits = ownerMap("HIT_CACHES");

        BoundaryCase label = populateBoundary(labels, "label", config.labelOwnerIdleTtlMs);
        BoundaryCase entity = populateBoundary(intel, "intel", config.intelEntityIndexOwnerIdleTtlMs);
        BoundaryCase hit = populateBoundary(hits, "hit", config.hoverOwnerIdleTtlMs);

        long labelIdleBefore = metric("LABEL_OWNER_IDLE_EVICTIONS");
        long intelIdleBefore = metric("INTEL_ENTITY_OWNER_IDLE_EVICTIONS");
        long hitIdleBefore = metric("HIT_OWNER_IDLE_EVICTIONS");
        StarsectorPrepatcherHooks.runCacheMaintenance(true);

        assertBoundary(labels, label, "label");
        assertBoundary(intel, entity, "intel");
        assertBoundary(hits, hit, "hit");
        require(metric("LABEL_OWNER_IDLE_EVICTIONS") - labelIdleBefore == 2L,
                "label idle eviction counter mismatch");
        require(metric("INTEL_ENTITY_OWNER_IDLE_EVICTIONS") - intelIdleBefore == 2L,
                "intel idle eviction counter mismatch");
        require(metric("HIT_OWNER_IDLE_EVICTIONS") - hitIdleBefore == 2L,
                "hit idle eviction counter mismatch");

        // Removal only detaches the outer root. A current call holding the value
        // remains valid and a later call can install a rebuilt value.
        require(readLong(label.exactValue, "lastAccessNanos") > 0L,
                "evicted owner value was destructively cleared");
        Object rebuilt = newOwnerValue("label", CLOCK.get());
        labels.put(label.exactOwner, rebuilt);
        require(labels.get(label.exactOwner) == rebuilt,
                "label cache could not be rebuilt after idle eviction");
        writeLong(rebuilt, "lastAccessNanos",
                CLOCK.get() - config.labelOwnerIdleTtlMs * MS - 1L);
        writeInt(rebuilt, "activeUsers", 1);
        StarsectorPrepatcherHooks.runCacheMaintenance(true);
        require(labels.get(label.exactOwner) == rebuilt,
                "idle maintenance removed an owner used by the current call");
        writeInt(rebuilt, "activeUsers", 0);
    }

    private static BoundaryCase populateBoundary(IdentityHashMap<Object, Object> map,
                                                   String kind, int ttlMs) throws Exception {
        map.clear();
        long now = CLOCK.get();
        long ttl = ttlMs * MS;
        Object youngOwner = owner(kind, 1);
        Object exactOwner = owner(kind, 2);
        Object oldOwner = owner(kind, 3);
        Object young = newOwnerValue(kind, now - ttl + 1L);
        Object exact = newOwnerValue(kind, now - ttl);
        Object old = newOwnerValue(kind, now - ttl - 1L);
        map.put(youngOwner, young);
        map.put(exactOwner, exact);
        map.put(oldOwner, old);
        return new BoundaryCase(youngOwner, exactOwner, oldOwner, exact);
    }

    private static void assertBoundary(IdentityHashMap<Object, Object> map,
                                       BoundaryCase c, String label) {
        require(map.containsKey(c.youngOwner), label + " owner with idle < TTL was removed");
        require(!map.containsKey(c.exactOwner), label + " owner with idle = TTL was retained");
        require(!map.containsKey(c.oldOwner), label + " owner with idle > TTL was retained");
    }

    private static void testLruLimitUsesLastAccess(PrepatcherConfig config) throws Exception {
        verifyLru("label", config.labelOwnerIdleTtlMs);
        verifyLru("intel", config.intelEntityIndexOwnerIdleTtlMs);
        verifyLru("hit", config.hoverOwnerIdleTtlMs);
    }

    private static void verifyLru(String kind, int idleTtlMs) throws Exception {
        CLOCK.addAndGet(1_000L * MS);
        IdentityHashMap<Object, Object> map = new IdentityHashMap<>();
        Object first = owner(kind, 10);
        Object oldest = owner(kind, 11);
        Object newest = owner(kind, 12);
        Object inserting = owner(kind, 13);
        Object firstValue = newOwnerValue(kind, CLOCK.get() - 10L);
        Object oldestValue = newOwnerValue(kind, CLOCK.get() - 20L);
        Object newestValue = newOwnerValue(kind, CLOCK.get() - 5L);
        // Touch a formerly old owner; iterator order must not decide eviction.
        writeLong(firstValue, "lastAccessNanos", CLOCK.get() - 1L);
        map.put(first, firstValue);
        map.put(oldest, oldestValue);
        map.put(newest, newestValue);

        Method method = findMethod(StarsectorPrepatcherHooks.class,
                "evictIdleAndLruForInsert", 7);
        LongAdder idle = new LongAdder();
        LongAdder limit = new LongAdder();
        boolean accepted = (boolean) method.invoke(null, map, inserting,
                CLOCK.get(), idleTtlMs, 3, idle, limit);
        require(accepted, kind + " LRU unexpectedly rejected an evictable insertion");
        require(!map.containsKey(oldest), kind + " LRU did not evict minimum lastAccessNanos owner");
        require(map.containsKey(first) && map.containsKey(newest),
                kind + " LRU evicted a recently touched owner");
        require(limit.sum() == 1L && idle.sum() == 0L,
                kind + " LRU counters did not distinguish limit eviction");
        Object insertingValue = newOwnerValue(kind, CLOCK.get());
        map.put(inserting, insertingValue);
        require(map.size() == 3, kind + " LRU insertion did not preserve hard owner limit");

        // An owner used by an outer/reentrant call must not be displaced while
        // a different owner is inserted into the full map.
        Object protectedValue = map.get(first);
        writeLong(protectedValue, "lastAccessNanos", CLOCK.get() - 30L);
        writeInt(protectedValue, "activeUsers", 1);
        writeLong(map.get(newest), "lastAccessNanos", CLOCK.get() - 20L);
        writeLong(insertingValue, "lastAccessNanos", CLOCK.get() - 10L);
        Object another = owner(kind, 14);
        accepted = (boolean) method.invoke(null, map, another, CLOCK.get(), idleTtlMs, 3,
                new LongAdder(), new LongAdder());
        require(accepted, kind + " LRU rejected insertion despite inactive candidate");
        require(map.get(first) == protectedValue,
                kind + " LRU removed an owner active in a different current call");
        require(!map.containsKey(newest),
                kind + " LRU did not choose the oldest inactive owner");
        writeInt(protectedValue, "activeUsers", 0);
        map.put(another, newOwnerValue(kind, CLOCK.get()));

        for (Object value : map.values()) writeInt(value, "activeUsers", 1);
        Object blocked = owner(kind, 15);
        accepted = (boolean) method.invoke(null, map, blocked, CLOCK.get(), idleTtlMs, 3,
                new LongAdder(), new LongAdder());
        require(!accepted && map.size() == 3 && !map.containsKey(blocked),
                kind + " LRU exceeded the hard limit when every retained owner was active");
        for (Object value : map.values()) writeInt(value, "activeUsers", 0);
    }

    @SuppressWarnings("unchecked")
    private static void testHoverCellPruning(PrepatcherConfig config) throws Exception {
        CLOCK.set(30_000L * MS);
        IdentityHashMap<Object, Object> hits = ownerMap("HIT_CACHES");
        hits.clear();
        Object owner = new Object();
        Object cache = newOwnerValue("hit", CLOCK.get());
        Map<Long, Object> cells = (Map<Long, Object>) read(cache, "cells");
        long oldAt = CLOCK.get() - config.hoverCellTtlMs * MS;
        long freshAt = CLOCK.get() + (config.hoverCellPruneIntervalMs - 5L) * MS;
        cells.put(1L, newCell(oldAt));
        cells.put(2L, newCell(freshAt));
        writeLong(cache, "lastCellPruneNanos", CLOCK.get());
        writeBoolean(cache, "hasLast", true);
        writeLong(cache, "lastAtNanos", CLOCK.get());
        hits.put(owner, cache);

        long before = metric("HIT_EXPIRED_CELL_EVICTIONS");
        CLOCK.addAndGet((config.hoverCellPruneIntervalMs - 1L) * MS);
        StarsectorPrepatcherHooks.runCacheMaintenance(true);
        require(cells.size() == 2, "hover cells were scanned before prune interval");

        CLOCK.addAndGet(MS);
        StarsectorPrepatcherHooks.runCacheMaintenance(true);
        require(!cells.containsKey(1L) && cells.containsKey(2L),
                "hover pruning did not remove only expired cells");
        require(readBoolean(cache, "hasLast"),
                "hover pruning cleared independently valid lastResult state");
        require(metric("HIT_EXPIRED_CELL_EVICTIONS") - before == 1L,
                "expired hover-cell metric mismatch");
    }

    private static void testHoverTtlBoundary(PrepatcherConfig config) throws Exception {
        Method fresh = findMethod(StarsectorPrepatcherHooks.class, "hoverCellFresh", 3);
        long now = 35_000L * MS;
        long ttl = config.hoverCellTtlMs * MS;
        require((boolean) fresh.invoke(null, now, now - ttl + 1L, config.hoverCellTtlMs),
                "hover cell with age < TTL was rejected");
        require(!(boolean) fresh.invoke(null, now, now - ttl, config.hoverCellTtlMs),
                "hover cell with age = TTL remained valid");
        require(!(boolean) fresh.invoke(null, now, now - ttl - 1L, config.hoverCellTtlMs),
                "hover cell with age > TTL remained valid");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void testScratchClearedPeak(PrepatcherConfig config) throws Exception {
        removeThreadLocal(StarsectorPrepatcherHooks.class, "SCRATCH");
        CLOCK.set(36_000L * MS);
        StarsectorPrepatcherHooks.beginScratchScope();
        ArrayList pooled = StarsectorPrepatcherHooks.borrowShipList();
        for (int i = 0; i < 64; i++) {
            StarsectorPrepatcherHooks.scratchListAdd(pooled, new Object());
        }
        pooled.clear();
        StarsectorPrepatcherHooks.endScratchScope();

        Object scratch = threadLocalValue("SCRATCH");
        Object frame = ((List<?>) read(scratch, "frames")).get(0);
        int retainedPeak = ((List<Integer>) read(frame, "pooledListHighWaters")).get(0);
        require(retainedPeak >= 64,
                "scratch list did not retain a peak observed before caller clear()");
        require(readLong(frame, "oversizedLastUseNanos") == CLOCK.get(),
                "scratch oversized timestamp was not recorded at scope close");

        Object original = frame;
        CLOCK.addAndGet((config.scratchTrimGraceMs - 1L) * MS);
        StarsectorPrepatcherHooks.runCacheMaintenance(true);
        require(((List<?>) read(scratch, "frames")).get(0) == original,
                "cleared-peak scratch frame trimmed before grace period");
        CLOCK.addAndGet(MS);
        StarsectorPrepatcherHooks.runCacheMaintenance(true);
        require(((List<?>) read(scratch, "frames")).get(0) != original,
                "cleared-peak scratch frame was not trimmed after grace period");
    }

    private static void testScratchDepthTrimming(PrepatcherConfig config) throws Exception {
        removeThreadLocal(StarsectorPrepatcherHooks.class, "SCRATCH");
        CLOCK.set(38_000L * MS);
        for (int i = 0; i < 64; i++) StarsectorPrepatcherHooks.beginScratchScope();
        for (int i = 0; i < 64; i++) StarsectorPrepatcherHooks.endScratchScope();
        Object scratch = threadLocalValue("SCRATCH");
        List<?> frames = (List<?>) read(scratch, "frames");
        require(frames.size() == 64, "deep scratch setup did not allocate 64 frames");

        CLOCK.addAndGet((config.scratchTrimGraceMs - 1L) * MS);
        StarsectorPrepatcherHooks.runCacheMaintenance(true);
        require(((List<?>) read(scratch, "frames")).size() == 64,
                "inactive trailing scratch frames trimmed before grace period");
        CLOCK.addAndGet(MS);
        StarsectorPrepatcherHooks.runCacheMaintenance(true);
        require(((List<?>) read(scratch, "frames")).size() == 4,
                "inactive trailing scratch frames were not reduced to retained floor");
    }

    private static void testScratchGaugeAggregationAndReset(PrepatcherConfig config)
            throws Exception {
        removeThreadLocal(StarsectorPrepatcherHooks.class, "SCRATCH");
        CLOCK.set(39_000L * MS);
        StarsectorPrepatcherHooks.beginScratchScope();
        StarsectorPrepatcherHooks.endScratchScope();
        StarsectorPrepatcherHooks.runCacheMaintenance(true);

        Object scratch = threadLocalValue("SCRATCH");
        Object frame = ((List<?>) read(scratch, "frames")).get(0);
        long expectedLists = readLong(frame, "entityListHighWater")
                + readLong(frame, "hitListHighWater")
                + readLong(frame, "labelCandidatesHighWater");
        long expectedSets = readLong(frame, "classSetHighWater")
                + readLong(frame, "equalityMembershipHighWater")
                + readLong(frame, "containsSetHighWater");
        Object gauges = staticField(StarsectorPrepatcherHooks.class, "SCRATCH_GAUGES");
        require(readLong(gauges, "retainedListCapacity") >= expectedLists,
                "scratch list gauge reports one maximum instead of per-list sum");
        require(readLong(gauges, "retainedSetCapacity") >= expectedSets,
                "scratch set gauge reports one maximum instead of per-set sum");

        activateCampaign(new Object());
        Object reset = staticField(StarsectorPrepatcherHooks.class, "SCRATCH_GAUGES");
        require(readLong(reset, "retainedListCapacity") == 0L
                        && readLong(reset, "retainedSetCapacity") == 0L
                        && readLong(reset, "retainedIdentityCapacity") == 0L
                        && ((Number) read(reset, "frameCount")).intValue() == 0,
                "scratch gauges retained detached generation capacity");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void testStarfieldClockAndGraceTrimming(PrepatcherConfig config)
            throws Exception {
        removeThreadLocal(StarsectorPrepatcherHyperspaceHooks.class, "LISTS");
        CLOCK.set(60_000L * MS);
        AtomicLong clockCalls = new AtomicLong();
        installClock(StarsectorPrepatcherHyperspaceHooks.class, () -> {
            clockCalls.incrementAndGet();
            return CLOCK.get();
        });
        ArrayList list = StarsectorPrepatcherHyperspaceHooks.borrowArrayList();
        for (int i = 0; i < 1000; i++) list.add(new Object());
        require(clockCalls.get() == 0L,
                "starfield pooled list read nanoTime during add hot path");
        StarsectorPrepatcherHyperspaceHooks.removeAllAndRelease(new ArrayList(), list);
        require(clockCalls.get() == 1L,
                "starfield pooled list did not read clock exactly once at release");
        installClock(StarsectorPrepatcherHyperspaceHooks.class, CLOCK::get);

        Object pool = threadLocalValue(StarsectorPrepatcherHyperspaceHooks.class, "LISTS");
        require(((java.util.Deque<?>) read(pool, "free")).size() == 1,
                "ordinary starfield release did not return list to pool");
        CLOCK.addAndGet((config.starfieldPoolOversizedGraceMs - 1L) * MS);
        requireCampaignFixtureActive();
        StarsectorPrepatcherHooks.runCacheMaintenance(true);
        require(((java.util.Deque<?>) read(pool, "free")).size() == 1,
                "oversized starfield list dropped before grace period");
        CLOCK.addAndGet(MS);
        requireCampaignFixtureActive();
        StarsectorPrepatcherHooks.runCacheMaintenance(true);
        require(((java.util.Deque<?>) read(pool, "free")).isEmpty(),
                "oversized starfield backing storage retained after grace period");

        List<ArrayList> borrowed = new ArrayList<>();
        for (int i = 0; i < 40; i++) borrowed.add(
                StarsectorPrepatcherHyperspaceHooks.borrowArrayList());
        for (ArrayList retained : borrowed) {
            StarsectorPrepatcherHyperspaceHooks.removeAllAndRelease(new ArrayList(), retained);
        }
        require(((java.util.Deque<?>) read(pool, "free")).size() <= 32,
                "starfield retained-list count exceeded hard pool limit");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void testStarfieldLifecycleResetAndStaleLease() throws Exception {
        removeThreadLocal(StarsectorPrepatcherHyperspaceHooks.class, "LISTS");
        CLOCK.set(65_000L * MS);

        ArrayList staleLease = StarsectorPrepatcherHyperspaceHooks.borrowArrayList();
        for (int i = 0; i < 1000; i++) staleLease.add(new Object());

        ArrayList retained = StarsectorPrepatcherHyperspaceHooks.borrowArrayList();
        for (int i = 0; i < 1000; i++) retained.add(new Object());
        StarsectorPrepatcherHyperspaceHooks.removeAllAndRelease(
                new ArrayList(), retained);

        Object oldPool = threadLocalValue(
                StarsectorPrepatcherHyperspaceHooks.class, "LISTS");
        require(((java.util.Deque<?>) read(oldPool, "free")).size() == 1,
                "starfield lifecycle setup did not retain a backing array");

        long resetsBefore = hyperspaceMetric("starfieldLifecyclePoolResets");
        long staleBefore = hyperspaceMetric("starfieldStaleLeaseDrops");
        activateCampaign(new Object());

        require(((java.util.Deque<?>) read(oldPool, "free")).isEmpty(),
                "campaign lifecycle reset retained old starfield backing arrays");
        require(hyperspaceMetric("starfieldLifecyclePoolResets")
                        == resetsBefore + 1L,
                "starfield lifecycle reset counter mismatch");
        require(((Number) staticField(StarsectorPrepatcherHyperspaceHooks.class,
                        "starfieldFreeListCount")).intValue() == 0,
                "starfield free-list gauge survived campaign reset");
        require(((Number) staticField(StarsectorPrepatcherHyperspaceHooks.class,
                        "starfieldRetainedCapacity")).longValue() == 0L,
                "starfield retained-capacity gauge survived campaign reset");

        Object newPool = threadLocalValue(
                StarsectorPrepatcherHyperspaceHooks.class, "LISTS");
        require(newPool != oldPool,
                "campaign lifecycle reset did not detach the ThreadLocal pool");
        require(((java.util.Deque<?>) read(newPool, "free")).isEmpty(),
                "new campaign inherited old starfield free lists");

        StarsectorPrepatcherHyperspaceHooks.removeAllAndRelease(
                new ArrayList(), staleLease);
        require(staleLease.isEmpty(),
                "stale starfield lease retained campaign objects after release");
        require(((java.util.Deque<?>) read(newPool, "free")).isEmpty(),
                "stale old-generation lease repopulated the new campaign pool");
        require(hyperspaceMetric("starfieldStaleLeaseDrops") == staleBefore + 1L,
                "stale starfield lease drop counter mismatch");
    }

    private static void testGenerationIsolation(PrepatcherConfig config) throws Exception {
        CLOCK.set(40_000L * MS);
        IdentityHashMap<Object, Object> oldLabels = ownerMap("LABEL_INDEXES");
        oldLabels.clear();
        Object oldOwner = owner("label", 40);
        oldLabels.put(oldOwner, newOwnerValue("label",
                CLOCK.get() - config.labelOwnerIdleTtlMs * MS - 1L));

        activateCampaign(new Object());
        IdentityHashMap<Object, Object> current = ownerMap("LABEL_INDEXES");
        require(current != oldLabels, "campaign generation did not replace owner cache map");
        Object currentOwner = owner("label", 41);
        current.put(currentOwner, newOwnerValue("label", CLOCK.get()));
        StarsectorPrepatcherHooks.runCacheMaintenance(true);
        require(current.containsKey(currentOwner),
                "maintenance from stale generation affected current owner map");
        require(oldLabels.containsKey(oldOwner),
                "detached old-generation map was unexpectedly traversed");
    }

    private static void testScratchGraceTrimming(PrepatcherConfig config) throws Exception {
        CLOCK.set(50_000L * MS);
        StarsectorPrepatcherHooks.beginScratchScope();
        try {
            List<Object> large = new ArrayList<>(Collections.nCopies(32, new Object()));
            StarsectorPrepatcherHooks.borrowEntityList(large);
            java.util.Set<Object> target = Collections.newSetFromMap(new IdentityHashMap<>());
            target.addAll(large);
            StarsectorPrepatcherHooks.retainAllFast(target, large, new Object());
            StarsectorPrepatcherHooks.fastContains(large, large.get(0));
            java.util.HashSet pooledSet = StarsectorPrepatcherHooks.borrowShipSet();
            pooledSet.addAll(large);
            for (int i = 0; i < 5; i++) {
                StarsectorPrepatcherHooks.borrowShipList().add(new Object());
            }
        } finally {
            StarsectorPrepatcherHooks.endScratchScope();
        }
        Object scratch = threadLocalValue("SCRATCH");
        List<?> frames = (List<?>) read(scratch, "frames");
        Object oversized = frames.get(0);
        require(((List<?>) read(oversized, "pooledLists")).size()
                        <= config.scratchTrimMaxPooledCollections,
                "scratch pooled-list count exceeded configured limit");
        require(((List<?>) read(oversized, "pooledSets")).size()
                        <= config.scratchTrimMaxPooledCollections,
                "scratch pooled-set count exceeded configured limit");

        CLOCK.addAndGet((config.scratchTrimGraceMs - 1L) * MS);
        StarsectorPrepatcherHooks.runCacheMaintenance(true);
        require(((List<?>) read(scratch, "frames")).get(0) == oversized,
                "scratch frame trimmed before grace period");

        CLOCK.addAndGet(MS);
        StarsectorPrepatcherHooks.runCacheMaintenance(true);
        Object trimmed = ((List<?>) read(scratch, "frames")).get(0);
        require(trimmed != oversized, "scratch frame was not replaced after grace period");

        StarsectorPrepatcherHooks.beginScratchScope();
        Object active = ((List<?>) read(scratch, "frames")).get(0);
        StarsectorPrepatcherHooks.borrowEntityList(Collections.nCopies(32, new Object()));
        CLOCK.addAndGet(config.scratchTrimGraceMs * MS);
        StarsectorPrepatcherHooks.runCacheMaintenance(true);
        require(((List<?>) read(scratch, "frames")).get(0) == active,
                "active scratch frame was trimmed inside scope");
        StarsectorPrepatcherHooks.endScratchScope();
        CLOCK.addAndGet(config.scratchTrimGraceMs * MS);
        StarsectorPrepatcherHooks.runCacheMaintenance(true);
        require(((List<?>) read(scratch, "frames")).get(0) != active,
                "closed oversized scratch frame was not eventually trimmed");

        StarsectorPrepatcherHooks.beginScratchScope();
        StarsectorPrepatcherHooks.beginScratchScope();
        try {
            StarsectorPrepatcherHooks.borrowEntityList(Collections.nCopies(32, new Object()));
            throw new TestException();
        } catch (TestException expected) {
            // Generated transformed methods use the same finally-style close.
        } finally {
            StarsectorPrepatcherHooks.endScratchScope();
            StarsectorPrepatcherHooks.endScratchScope();
        }
        require((int) read(scratch, "scopeDepth") == 0,
                "nested/exceptional scratch scopes did not fully close");
    }

    private static PrepatcherConfig config() throws Exception {
        Properties p = new Properties();
        p.setProperty("patch.mapRenderStuff", "true");
        p.setProperty("patch.mapHitTest", "true");
        p.setProperty("patch.labelSpatialCandidates", "true");
        p.setProperty("patch.intelEntityIndex", "true");
        p.setProperty("patch.intelReconciliation", "true");
        p.setProperty("patch.shipAdvanceScratch", "true");
        p.setProperty("patch.campaignSnapshotReuse", "true");
        p.setProperty("patch.starfieldCleanupBuffers", "true");
        p.setProperty("cache.maintenanceIntervalMs", "30");
        p.setProperty("hover.ownerIdleTtlMs", "50");
        p.setProperty("label.ownerIdleTtlMs", "100");
        p.setProperty("intel.entityIndexOwnerIdleTtlMs", "200");
        p.setProperty("hover.cellTtlMs", "10");
        p.setProperty("hover.cellPruneIntervalMs", "30");
        p.setProperty("hover.maxCells", "64");
        p.setProperty("scratch.trim.enabled", "true");
        p.setProperty("scratch.trimGraceMs", "100");
        p.setProperty("scratch.trim.maxListCapacity", "16");
        p.setProperty("scratch.trim.maxIdentityEntries", "16");
        p.setProperty("scratch.trim.maxSetEntries", "16");
        p.setProperty("scratch.trim.maxPooledCollections", "2");
        p.setProperty("starfield.pool.maxRetainedCapacity", "16");
        p.setProperty("starfield.pool.oversizedGraceMs", "100");
        p.setProperty("logging.statsIntervalSeconds", "0");
        Constructor<PrepatcherConfig> constructor =
                PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);
        return constructor.newInstance(p);
    }

    private static void installConfig(PrepatcherConfig config) throws Exception {
        Field field = StarsectorPrepatcherHooks.class.getDeclaredField("config");
        field.setAccessible(true);
        field.set(null, config);
    }

    private static void configureHyperspace(PrepatcherConfig config) throws Exception {
        Method method = StarsectorPrepatcherHyperspaceHooks.class
                .getDeclaredMethod("configure", PrepatcherConfig.class);
        method.setAccessible(true);
        method.invoke(null, config);
    }

    private static void installClock(Class<?> owner, LongSupplier clock) throws Exception {
        Field field = owner.getDeclaredField("NANO_CLOCK");
        field.setAccessible(true);
        field.set(null, clock);
    }

    private static void activateCampaign(Object engine) {
        Object previous = activeEngineFixture;
        activeEngineFixture = engine;
        long transition = StarsectorPrepatcherHooks.beginCampaignEngineChange(engine);
        require(transition >= 0L, "campaign maintenance lifecycle did not open generation");
        StarsectorPrepatcherHooks.completeCampaignEngineChange(engine, transition);
        requireCampaignFixtureActive();
        Reference.reachabilityFence(previous);
        Reference.reachabilityFence(engine);
    }

    private static void requireCampaignFixtureActive() {
        try {
            Field field = StarsectorPrepatcherHooks.class
                    .getDeclaredField("activeCampaignEngine");
            field.setAccessible(true);
            WeakReference<?> active = (WeakReference<?>) field.get(null);
            require(active.get() == activeEngineFixture,
                    "test campaign fixture is no longer strongly reachable");
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("unable to verify campaign fixture", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static IdentityHashMap<Object, Object> ownerMap(String name) throws Exception {
        Field field = StarsectorPrepatcherHooks.class.getDeclaredField(name);
        field.setAccessible(true);
        return (IdentityHashMap<Object, Object>) field.get(null);
    }

    private static Object owner(String kind, int id) {
        return switch (kind) {
            case "label" -> new LinkedHashMap<>(Map.of("id", id));
            case "intel" -> new ArrayList<>(List.of(id));
            default -> new Owner(id);
        };
    }

    private static Object newOwnerValue(String kind, long access) throws Exception {
        Class<?> type = Class.forName("com.fs.starfarer.api.StarsectorPrepatcherHooks$"
                + switch (kind) {
                    case "label" -> "LabelIndex";
                    case "intel" -> "IntelEntityIndex";
                    case "hit" -> "HitCache";
                    default -> throw new IllegalArgumentException(kind);
                });
        Object value;
        if (kind.equals("label")) {
            Constructor<?> c = type.getDeclaredConstructor(int.class, long.class,
                    Map.class, List.class, boolean.class);
            c.setAccessible(true);
            value = c.newInstance(1, access, new java.util.HashMap<>(),
                    new ArrayList<>(), false);
        } else if (kind.equals("intel")) {
            Constructor<?> c = type.getDeclaredConstructor(int.class, Object.class,
                    Object.class, long.class, IdentityHashMap.class, boolean.class);
            c.setAccessible(true);
            value = c.newInstance(1, null, null, access, new IdentityHashMap<>(), false);
        } else {
            Constructor<?> c = type.getDeclaredConstructor(int.class, long.class);
            c.setAccessible(true);
            value = c.newInstance(64, access);
        }
        writeLong(value, "lastAccessNanos", access);
        return value;
    }

    private static Object newCell(long atNanos) throws Exception {
        Class<?> type = Class.forName(
                "com.fs.starfarer.api.StarsectorPrepatcherHooks$CellHit");
        Constructor<?> c = type.getDeclaredConstructor(SectorEntityToken.class, long.class);
        c.setAccessible(true);
        return c.newInstance(null, atNanos);
    }

    private static Method findMethod(Class<?> owner, String name, int parameterCount) {
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterCount() == parameterCount) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new AssertionError("Missing method " + name);
    }

    private static Object threadLocalValue(String fieldName) throws Exception {
        return threadLocalValue(StarsectorPrepatcherHooks.class, fieldName);
    }

    private static Object threadLocalValue(Class<?> owner, String fieldName) throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        return ((ThreadLocal<?>) field.get(null)).get();
    }

    private static void removeThreadLocal(Class<?> owner, String fieldName) throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        ((ThreadLocal<?>) field.get(null)).remove();
    }

    private static Object staticField(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static Object read(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static long readLong(Object owner, String name) throws Exception {
        return ((Number) read(owner, name)).longValue();
    }

    private static boolean readBoolean(Object owner, String name) throws Exception {
        return (boolean) read(owner, name);
    }

    private static void writeLong(Object owner, String name, long value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(owner, value);
    }

    private static void writeBoolean(Object owner, String name, boolean value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(owner, value);
    }

    private static void writeInt(Object owner, String name, int value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(owner, value);
    }

    private static long metric(String name) throws Exception {
        Field field = StarsectorPrepatcherHooks.class.getDeclaredField(name);
        field.setAccessible(true);
        return ((LongAdder) field.get(null)).sum();
    }

    private static long hyperspaceMetric(String name) throws Exception {
        Field field = StarsectorPrepatcherHyperspaceHooks.class.getDeclaredField(name);
        field.setAccessible(true);
        return ((LongAdder) field.get(null)).sum();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record BoundaryCase(Object youngOwner, Object exactOwner,
                                Object oldOwner, Object exactValue) {}
    private record Owner(int id) {}
    private static final class TestException extends RuntimeException {}
}
