package com.fs.starfarer.api;

import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.starsector.prepatcher.agent.PrepatcherConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Runtime regression for the budgeted, location-agnostic strategic jump index. */
public final class StrategicJumpDestinationIndexRuntimeTest {
    private static final long FRAME_NANOS = 16_666_667L;
    private static final int MAX_UNITS = 4;
    private static final IdentityHashMap<LocationAPI, List<Object>> ENTITIES =
            new IdentityHashMap<>();
    private static final AtomicLong DESTINATION_LIST_READS = new AtomicLong();

    private StrategicJumpDestinationIndexRuntimeTest() {}

    public static void main(String[] args) throws Exception {
        Path configFile = Files.createTempFile("strategic-jump-index", ".properties");
        Files.writeString(configFile,
                "patch.strategicJumpDestinationFirst=true\n"
                        + "patch.strategicJumpDestinationIndex=true\n"
                        + "strategicJump.indexBudgetMicros=1000\n"
                        + "strategicJump.indexMaxWorkUnits=" + MAX_UNITS + "\n"
                        + "strategicJump.indexAdmissionBurst=8\n"
                        + "strategicJump.indexMaxLocations=32\n"
                        + "strategicJump.indexIdleTtlMs=60000\n"
                        + "strategicJump.indexFailureRetryMs=5000\n");
        PrepatcherConfig config = PrepatcherConfig.load(configFile);
        try {
            testSinglePointColdLookupIsDeferred(config);
            testWallClockBudgetAllowsOnlyOneAtomicOvershoot();
            testIncrementalBuildOrderingRefreshAndAudit(config);
            testProtectedBuildsAndBoundedLocationLru(config);
            testMutationDuringBuild(config);
            testAuditFairnessDuringLargeBuild(config);
            testFailureCooldown(config);
            testRetryHeapDeadlineOrderAndGenerationReset(config);
            testGenerationReset(config);
            testCapabilityRequiresMaintenanceSurface();
        } finally {
            StarsectorPrepatcherStrategicJumpIndex.reset();
            ENTITIES.clear();
            Files.deleteIfExists(configFile);
        }
        System.out.println("OK strategic jump index location-agnostic cold-deferral/bounded-build/"
                + "order/negative-cache/delta-refresh/mid-build-coalescing/audit-fairness/"
                + "protected-build-lru/retry-heap/retry-order/audit-queue/debt-diagnostics/"
                + "failure-cooldown/generation-reset/capability-gate");
    }

    private static void testSinglePointColdLookupIsDeferred(PrepatcherConfig config) {
        StarsectorPrepatcherStrategicJumpIndex.reset();
        ENTITIES.clear();
        StepClock clock = new StepClock();
        long generation = 7L;
        prime(clock, config, generation);

        LocationAPI current = location("single-current");
        LocationAPI target = location("single-target");
        JumpFixture only = jumpPoint(current, target);
        List<Object> live = new ArrayList<>(List.of(only.point));
        ENTITIES.put(current, live);

        DESTINATION_LIST_READS.set(0L);
        List<?> cold = lookup(current, target, current, config, generation);
        require(cold.isEmpty() && cold != live,
                "single-point cold lookup used a size-based vanilla fallback");
        require(DESTINATION_LIST_READS.get() == 0L,
                "single-point cold lookup synchronously read destinations");
        long deferredPlansBefore = diagnostics().deferredPlans();
        require(StarsectorPrepatcherStrategicJumpIndex.deferExpiredPlan(
                        current, JumpPointAPI.class, config, generation),
                "single-point cold state did not defer an expired plan");
        require(diagnostics().deferredPlans() == deferredPlansBefore + 1,
                "expired-plan deferral was not counted");
        runFrame(clock, config, generation);
        var debt = diagnostics();
        require(debt.oldestPendingNanos() > 0L,
                "pending build age was not exposed to diagnostics");
        require(debt.oldestDeferredPlanNanos() > 0L,
                "deferred-plan age was not exposed to diagnostics");

        awaitReady(current, clock, config, generation, 20);
        List<?> ready = lookup(current, target, current, config, generation);
        require(ready.size() == 1 && ready.get(0) == only.point,
                "single-point index did not publish the exact candidate");
    }

    private static void testWallClockBudgetAllowsOnlyOneAtomicOvershoot() throws Exception {
        Path configFile = Files.createTempFile("strategic-jump-index-budget", ".properties");
        Files.writeString(configFile,
                "patch.strategicJumpDestinationFirst=true\n"
                        + "patch.strategicJumpDestinationIndex=true\n"
                        + "strategicJump.indexBudgetMicros=25\n"
                        + "strategicJump.indexMaxWorkUnits=100\n"
                        + "strategicJump.indexAdmissionBurst=8\n"
                        + "strategicJump.indexMaxLocations=32\n"
                        + "strategicJump.indexIdleTtlMs=60000\n"
                        + "strategicJump.indexFailureRetryMs=5000\n");
        PrepatcherConfig config = PrepatcherConfig.load(configFile);
        try {
            StarsectorPrepatcherStrategicJumpIndex.reset();
            ENTITIES.clear();
            StepClock clock = new StepClock();
            long generation = 9L;
            prime(clock, config, generation);
            LocationAPI current = location("budget-current");
            LocationAPI target = location("budget-target");
            ENTITIES.put(current, new ArrayList<>(List.of(
                    jumpPoint(current, target, target, target).point)));
            lookup(current, target, current, config, generation);

            clock.advance(FRAME_NANOS);
            var before = diagnostics();
            StarsectorPrepatcherStrategicJumpIndex.maintenance(
                    config, generation, clock);
            var after = diagnostics();
            long delta = after.workUnits() - before.workUnits();
            long maintenanceDelta = after.maintenanceNanos() - before.maintenanceNanos();
            // StepClock charges 10 us for every clock sample. The scheduler now
            // checks its deadline before selecting a unit and charges the full
            // maintenance call, not just workOne()/auditOne().
            require(delta == 1,
                    "full-call wall-clock budget admitted more than one atomic unit: " + delta);
            require(maintenanceDelta >= 40_000L,
                    "scheduler/queue/final-accounting time was omitted: "
                            + maintenanceDelta);
            require(after.maintenanceMaxNanos() >= maintenanceDelta,
                    "maintenance maximum did not include the complete call");
            require(!"READY".equals(StarsectorPrepatcherStrategicJumpIndex
                            .snapshot(current).phase()),
                    "25 us budget unexpectedly completed the non-trivial index");
        } finally {
            StarsectorPrepatcherStrategicJumpIndex.reset();
            ENTITIES.clear();
            Files.deleteIfExists(configFile);
        }
    }

    private static void testIncrementalBuildOrderingRefreshAndAudit(
            PrepatcherConfig config) {
        StarsectorPrepatcherStrategicJumpIndex.reset();
        ENTITIES.clear();
        StepClock clock = new StepClock();
        long generation = 11L;
        prime(clock, config, generation);

        LocationAPI current = location("current");
        LocationAPI target = location("target");
        LocationAPI fallback = location("fallback");
        LocationAPI other = location("other");
        LocationAPI missing = location("missing");

        JumpFixture unrelatedPoint = jumpPoint(current, other);
        JumpFixture fallbackPoint = jumpPoint(current, fallback);
        JumpFixture targetPoint = jumpPoint(current, target);
        JumpFixture combinedPoint = jumpPoint(current, fallback, target);
        List<Object> live = new ArrayList<>(List.of(
                unrelatedPoint.point, fallbackPoint.point,
                targetPoint.point, combinedPoint.point));
        ENTITIES.put(current, live);

        DESTINATION_LIST_READS.set(0L);
        List<?> first = lookup(current, target, fallback, config, generation);
        require(first.isEmpty(), "first lookup must defer instead of scanning the live list");
        require(DESTINATION_LIST_READS.get() == 0L,
                "first lookup synchronously inspected jump destinations");
        require(first != live, "deferred lookup leaked the vanilla full source");
        var admitted = StarsectorPrepatcherStrategicJumpIndex.snapshot(current);
        require("BUILDING".equals(admitted.phase()), "first lookup did not admit BUILDING state");
        require(admitted.indexedPoints() == 0,
                "first lookup synchronously published a complete index");
        require(StarsectorPrepatcherStrategicJumpIndex.deferExpiredPlan(
                        current, JumpPointAPI.class, config, generation),
                "BUILDING source did not preserve the prior expired plan");
        require(DESTINATION_LIST_READS.get() == 0L,
                "expired-plan deferral synchronously inspected jump destinations");

        runFrame(clock, config, generation);
        var afterOne = StarsectorPrepatcherStrategicJumpIndex.snapshot(current);
        require(!"READY".equals(afterOne.phase()),
                "four work units unexpectedly completed a four-point index");
        awaitReady(current, clock, config, generation, 200);
        require(!StarsectorPrepatcherStrategicJumpIndex.deferExpiredPlan(
                        current, JumpPointAPI.class, config, generation),
                "READY source continued to defer expired-plan replacement");

        List<?> candidates = lookup(current, target, fallback, config, generation);
        require(candidates.size() == 3, "target+fallback union size changed");
        require(candidates.get(0) == fallbackPoint.point
                        && candidates.get(1) == targetPoint.point
                        && candidates.get(2) == combinedPoint.point,
                "candidate outer order or identity deduplication changed");

        List<?> negative = lookup(current, missing, current, config, generation);
        require(negative.isEmpty(), "verified destination miss was not cached as empty");
        require(negative != live, "verified miss fell back to the full vanilla source");

        long buildsBeforeRefresh = diagnostics().buildsStarted();
        targetPoint.destinations.clear();
        targetPoint.destinations.add(destination(token(other)));
        StarsectorPrepatcherStrategicJumpIndex.destinationsChanged(targetPoint.point);
        require(lookup(current, target, fallback, config, generation).isEmpty(),
                "point refresh exposed a partially updated index");
        awaitReady(current, clock, config, generation, 100);
        require(diagnostics().buildsStarted() == buildsBeforeRefresh,
                "single-point destination change triggered a full location rebuild");
        candidates = lookup(current, target, fallback, config, generation);
        require(candidates.size() == 2 && candidates.get(0) == fallbackPoint.point
                        && candidates.get(1) == combinedPoint.point,
                "point-level refresh did not update ordered candidates");

        JumpPointAPI.JumpDestination retargeted = fallbackPoint.destinations.get(0);
        retargeted.setDestination(token(target));
        StarsectorPrepatcherStrategicJumpIndex.destinationRetargeted(retargeted);
        awaitReady(current, clock, config, generation, 100);
        candidates = lookup(current, target, fallback, config, generation);
        require(candidates.size() == 2 && candidates.get(0) == fallbackPoint.point
                        && candidates.get(1) == combinedPoint.point,
                "JumpDestination.setDestination delta refresh changed ordering");

        JumpPointAPI.JumpDestination added = destination(token(target));
        unrelatedPoint.destinations.add(added);
        StarsectorPrepatcherStrategicJumpIndex.destinationAdded(unrelatedPoint.point, added);
        awaitReady(current, clock, config, generation, 100);
        candidates = lookup(current, target, fallback, config, generation);
        require(candidates.size() == 3 && candidates.get(0) == unrelatedPoint.point
                        && candidates.get(1) == fallbackPoint.point
                        && candidates.get(2) == combinedPoint.point,
                "addDestination delta refresh changed source order");

        JumpFixture appendedPoint = jumpPoint(current, target);
        long buildsBeforeSourceMutation = diagnostics().buildsStarted();
        live.add(appendedPoint.point);
        StarsectorPrepatcherStrategicJumpIndex.locationEntityChanged(
                current, appendedPoint.point);
        require(lookup(current, target, fallback, config, generation).isEmpty(),
                "source mutation did not defer the replacement build");
        awaitReady(current, clock, config, generation, 300);
        require(diagnostics().buildsStarted() == buildsBeforeSourceMutation + 1,
                "source mutation did not coalesce to one replacement build");
        candidates = lookup(current, target, fallback, config, generation);
        require(candidates.get(candidates.size() - 1) == appendedPoint.point,
                "source-add rebuild did not preserve appended source order");

        // No event is fired here: the continuous budgeted audit must detect the
        // changed target identity and schedule one point refresh without a full scan.
        JumpPointAPI.JumpDestination directEdit = targetPoint.destinations.get(0);
        directEdit.setDestination(token(target));
        long fullBuildsBeforeAudit = diagnostics().buildsStarted();
        awaitCandidate(current, targetPoint.point, target, fallback, clock, config,
                generation, 500);
        require(diagnostics().buildsStarted() == fullBuildsBeforeAudit,
                "direct destination edit audit triggered a full location rebuild");
    }

    private static void testProtectedBuildsAndBoundedLocationLru(
            PrepatcherConfig config) {
        StarsectorPrepatcherStrategicJumpIndex.reset();
        ENTITIES.clear();
        StepClock clock = new StepClock();
        long generation = 19L;
        prime(clock, config, generation);

        ArrayList<LocationAPI> admitted = new ArrayList<>();
        LocationAPI target = location("lru-target");
        for (int i = 0; i < config.strategicJumpIndexMaxLocations; i++) {
            LocationAPI current = location("lru-" + i);
            admitted.add(current);
            LocationAPI[] slowDestinations = new LocationAPI[64];
            java.util.Arrays.fill(slowDestinations, target);
            ENTITIES.put(current, new ArrayList<>(List.of(
                    jumpPoint(current, slowDestinations).point)));
            if (i > 0 && i % config.strategicJumpIndexAdmissionBurst == 0) {
                runFrame(clock, config, generation);
            }
            require(lookup(current, target, current, config, generation).isEmpty(),
                    "cold LRU admission unexpectedly exposed a partial index");
        }

        LocationAPI blocked = location("lru-blocked");
        ENTITIES.put(blocked, new ArrayList<>(List.of(jumpPoint(blocked, target).point)));
        runFrame(clock, config, generation); // replenish admission tokens
        long protectedBefore = diagnostics().protectedCapacityDeferrals();
        require(lookup(blocked, target, blocked, config, generation).isEmpty(),
                "capacity-blocked lookup exposed a source");
        require("ABSENT".equals(StarsectorPrepatcherStrategicJumpIndex
                        .snapshot(blocked).phase()),
                "new location was admitted by evicting unfinished work");
        require(!"ABSENT".equals(StarsectorPrepatcherStrategicJumpIndex
                        .snapshot(admitted.get(0)).phase()),
                "oldest unfinished location was evicted");
        require(diagnostics().protectedCapacityDeferrals() > protectedBefore,
                "protected-capacity deferral was not diagnosed");

        // Once one existing state reaches READY it becomes an O(1) evictable
        // candidate. The blocked location can then be admitted without losing
        // any BUILDING/REFRESHING progress.
        for (int frame = 0; frame < 1_000 && diagnostics().evictable() == 0; frame++) {
            runFrame(clock, config, generation);
        }
        require(diagnostics().evictable() > 0,
                "fixture never produced an evictable READY state");
        runFrame(clock, config, generation); // replenish admission tokens
        lookup(blocked, target, blocked, config, generation);
        var admittedBlocked = StarsectorPrepatcherStrategicJumpIndex.snapshot(blocked);
        require(!"ABSENT".equals(admittedBlocked.phase()),
                "blocked location was not admitted after a safe victim became available");
        require(admittedBlocked.locations() == config.strategicJumpIndexMaxLocations,
                "location LRU exceeded its configured bound: " + admittedBlocked);
        require(admittedBlocked.queued() <= config.strategicJumpIndexMaxLocations,
                "work queue retained evicted location states: " + admittedBlocked);
        require(diagnostics().auditQueued() <= config.strategicJumpIndexMaxLocations,
                "audit queue exceeded active location bound");
        require(diagnostics().retryQueued() <= config.strategicJumpIndexMaxLocations,
                "retry heap exceeded active location bound");
    }

    private static void testMutationDuringBuild(PrepatcherConfig config) {
        StarsectorPrepatcherStrategicJumpIndex.reset();
        ENTITIES.clear();
        StepClock clock = new StepClock();
        long generation = 19L;
        prime(clock, config, generation);

        LocationAPI current = location("mutating-build-current");
        LocationAPI target = location("mutating-build-target");
        LocationAPI other = location("mutating-build-other");
        JumpFixture point = jumpPoint(current,
                other, other, other, other, other, other, other, other, other, other);
        ENTITIES.put(current, new ArrayList<>(List.of(point.point)));

        lookup(current, target, current, config, generation);
        runFrame(clock, config, generation);
        var partial = StarsectorPrepatcherStrategicJumpIndex.snapshot(current);
        require("BUILDING".equals(partial.phase())
                        && partial.buildDestinationCursor() > 0,
                "fixture did not enter a partial destination capture");
        long failuresBefore = diagnostics().failures();

        JumpPointAPI.JumpDestination added = destination(token(target));
        point.destinations.add(added);
        StarsectorPrepatcherStrategicJumpIndex.destinationAdded(point.point, added);
        awaitReady(current, clock, config, generation, 200);

        require(diagnostics().failures() == failuresBefore,
                "normal destination mutation during build entered failure cooldown");
        List<?> candidates = lookup(current, target, current, config, generation);
        require(candidates.size() == 1 && candidates.get(0) == point.point,
                "restarted point capture did not publish the mutated destination");
    }

    private static void testAuditFairnessDuringLargeBuild(PrepatcherConfig config) {
        StarsectorPrepatcherStrategicJumpIndex.reset();
        ENTITIES.clear();
        StepClock clock = new StepClock();
        long generation = 21L;
        prime(clock, config, generation);

        LocationAPI readyLocation = location("audit-ready-location");
        LocationAPI oldTarget = location("audit-old-target");
        LocationAPI newTarget = location("audit-new-target");
        JumpFixture readyPoint = jumpPoint(readyLocation, oldTarget);
        ENTITIES.put(readyLocation, new ArrayList<>(List.of(readyPoint.point)));
        lookup(readyLocation, oldTarget, readyLocation, config, generation);
        awaitReady(readyLocation, clock, config, generation, 100);

        LocationAPI largeLocation = location("audit-large-location");
        LocationAPI unrelated = location("audit-unrelated");
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 160; i++) {
            many.add(jumpPoint(largeLocation, unrelated).point);
        }
        ENTITIES.put(largeLocation, many);
        lookup(largeLocation, unrelated, largeLocation, config, generation);

        // No explicit invalidation: this models direct mutable-state changes or
        // a missed setter owner-cache entry. The READY audit must still progress
        // while the unrelated large build queue remains non-empty.
        readyPoint.destinations.get(0).setDestination(token(newTarget));
        boolean repairedBeforeLargeBuildCompleted = false;
        for (int frame = 0; frame < 120; frame++) {
            List<?> candidates = lookup(readyLocation, newTarget, readyLocation,
                    config, generation);
            if (containsIdentity(candidates, readyPoint.point)) {
                repairedBeforeLargeBuildCompleted = !"READY".equals(
                        StarsectorPrepatcherStrategicJumpIndex
                                .snapshot(largeLocation).phase());
                break;
            }
            runFrame(clock, config, generation);
        }
        require(repairedBeforeLargeBuildCompleted,
                "READY-state audit starved behind an unrelated large build");
    }

    private static void testFailureCooldown(PrepatcherConfig config) {
        StarsectorPrepatcherStrategicJumpIndex.reset();
        ENTITIES.clear();
        StepClock clock = new StepClock();
        long generation = 23L;
        prime(clock, config, generation);

        LocationAPI current = location("failing-current");
        LocationAPI target = location("failing-target");
        JumpPointAPI broken = (JumpPointAPI) Proxy.newProxyInstance(
                StrategicJumpDestinationIndexRuntimeTest.class.getClassLoader(),
                new Class<?>[]{JumpPointAPI.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getDestinations" -> throw new IllegalStateException("fixture failure");
                    case "getContainingLocation" -> current;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        ENTITIES.put(current, new ArrayList<>(List.of(
                broken, jumpPoint(current, target).point, jumpPoint(current, target).point)));
        lookup(current, target, current, config, generation);
        awaitPhase(current, "FAILED_COOLDOWN", clock, config, generation, 100);
        require(!StarsectorPrepatcherStrategicJumpIndex.deferExpiredPlan(
                        current, JumpPointAPI.class, config, generation),
                "FAILED_COOLDOWN retained a stale expired plan");
        var failed = diagnostics();
        require(failed.retryQueued() == 1,
                "failed state was not represented by one indexed retry-heap entry");

        for (int i = 0; i < 30; i++) {
            require(lookup(current, target, current, config, generation).isEmpty(),
                    "failed index did not retain negative/deferred behavior");
            runFrame(clock, config, generation);
        }
        var beforeRetry = diagnostics();
        require(beforeRetry.buildsStarted() == failed.buildsStarted(),
                "failed build retried on each lookup before cooldown elapsed");
        require(beforeRetry.retries() == failed.retries(),
                "failure retry counter advanced before cooldown elapsed");
        require(beforeRetry.retryQueued() == 1,
                "cooldown polling duplicated or lost the retry-heap entry");

        clock.advance(5_100_000_000L);
        lookup(current, target, current, config, generation);
        runFrame(clock, config, generation);
        var afterRetry = diagnostics();
        require(afterRetry.retries() == failed.retries() + 1,
                "failed state did not perform one controlled retry after cooldown");
        require(afterRetry.buildsStarted() == failed.buildsStarted() + 1,
                "controlled retry did not start exactly one replacement build");
        require(afterRetry.retryQueued() == 1,
                "failed controlled retry did not replace its heap entry exactly once");
    }

    private static void testRetryHeapDeadlineOrderAndGenerationReset(
            PrepatcherConfig config) {
        StarsectorPrepatcherStrategicJumpIndex.reset();
        ENTITIES.clear();
        StepClock clock = new StepClock();
        long generation = 29L;
        prime(clock, config, generation);

        LocationAPI target = location("retry-order-target");
        List<LocationAPI> early = List.of(
                location("retry-order-early-a"),
                location("retry-order-early-b"));
        List<LocationAPI> late = List.of(
                location("retry-order-late-a"),
                location("retry-order-late-b"));
        for (LocationAPI current : early) {
            ENTITIES.put(current, new ArrayList<>(List.of(failingJumpPoint(current))));
            lookup(current, target, current, config, generation);
        }
        awaitRetryHeapSize(early.size(), clock, config, generation, 100);

        clock.advance(1_000_000_000L);
        for (LocationAPI current : late) {
            ENTITIES.put(current, new ArrayList<>(List.of(failingJumpPoint(current))));
            lookup(current, target, current, config, generation);
        }
        int total = early.size() + late.size();
        awaitRetryHeapSize(total, clock, config, generation, 100);
        long retriesBefore = diagnostics().retries();

        // Only the first deadline cohort is due. Each failed retry must replace
        // its one heap node without allowing the later cohort to jump the queue.
        clock.advance(4_100_000_000L);
        awaitRetryCountAndHeapSize(retriesBefore + early.size(), total,
                clock, config, generation, 100);
        require(diagnostics().retries() == retriesBefore + early.size(),
                "retry heap serviced a later deadline before it became due");

        clock.advance(1_100_000_000L);
        awaitRetryCountAndHeapSize(retriesBefore + total, total,
                clock, config, generation, 100);
        require(diagnostics().retries() == retriesBefore + total,
                "retry heap did not service the second deadline cohort exactly once");

        clock.advance(FRAME_NANOS);
        StarsectorPrepatcherStrategicJumpIndex.maintenance(
                config, generation + 1L, clock);
        var cleared = diagnostics();
        require(cleared.locations() == 0 && cleared.queued() == 0
                        && cleared.auditQueued() == 0 && cleared.retryQueued() == 0
                        && cleared.evictable() == 0,
                "generation reset retained an intrusive queue or retry-heap node: "
                        + cleared);
    }

    private static void testGenerationReset(PrepatcherConfig config) {
        StarsectorPrepatcherStrategicJumpIndex.reset();
        ENTITIES.clear();
        StepClock clock = new StepClock();
        LocationAPI first = location("generation-one");
        LocationAPI second = location("generation-two");
        LocationAPI target = location("generation-target");
        ENTITIES.put(first, new ArrayList<>(List.of(jumpPoint(first, target).point)));
        ENTITIES.put(second, new ArrayList<>(List.of(jumpPoint(second, target).point)));

        prime(clock, config, 31L);
        lookup(first, target, first, config, 31L);
        require(StarsectorPrepatcherStrategicJumpIndex.snapshot(first).locations() == 1,
                "first generation state was not admitted");

        clock.advance(FRAME_NANOS);
        StarsectorPrepatcherStrategicJumpIndex.maintenance(config, 32L, clock);
        require("ABSENT".equals(
                        StarsectorPrepatcherStrategicJumpIndex.snapshot(first).phase()),
                "generation transition retained old campaign location state");
        lookup(second, target, second, config, 32L);
        require(StarsectorPrepatcherStrategicJumpIndex.snapshot(second).locations() == 1,
                "new generation did not admit a fresh location state");
    }

    private static void testCapabilityRequiresMaintenanceSurface() throws Exception {
        String prefix = "starsector.prepatcher.patchStatus.";
        String maintenance = prefix
                + "com.fs.starfarer.campaign.CampaignEngine.campaignCacheLifecycle";
        String first = prefix
                + "com.fs.starfarer.campaign.ai.StrategicModule."
                + "strategicJumpDestinationFirst";
        String source = prefix
                + "com.fs.starfarer.campaign.ai.StrategicModule."
                + "strategicJumpDestinationIndex";
        String point = prefix
                + "com.fs.starfarer.campaign.JumpPoint.strategicJumpDestinationIndex";
        String location = prefix
                + "com.fs.starfarer.campaign.BaseLocation.strategicJumpDestinationIndex";
        String destination = prefix
                + "com.fs.starfarer.api.campaign.JumpPointAPI$JumpDestination."
                + "strategicJumpDestinationIndex";
        String[] keys = {maintenance, first, source, point, location, destination};
        String[] previous = new String[keys.length];
        Field cached = StarsectorPrepatcherHooks.class
                .getDeclaredField("strategicJumpRuntimeCapability");
        cached.setAccessible(true);
        Method capability = StarsectorPrepatcherHooks.class
                .getDeclaredMethod("strategicJumpIndexCapability");
        capability.setAccessible(true);
        try {
            for (int i = 0; i < keys.length; i++) previous[i] = System.getProperty(keys[i]);
            for (String key : keys) System.setProperty(key, "APPLIED");
            System.setProperty(maintenance, "SKIPPED_STRUCTURAL");
            cached.setInt(null, 0);
            require((Integer) capability.invoke(null) == -1,
                    "missing maintenance surface did not fail the index capability");

            System.setProperty(maintenance, "APPLIED");
            cached.setInt(null, 0);
            require((Integer) capability.invoke(null) == 1,
                    "complete strategic index surfaces did not negotiate READY");

            System.setProperty(source, "SKIPPED_STRUCTURAL");
            cached.setInt(null, 0);
            require((Integer) capability.invoke(null) == -1,
                    "missing StrategicModule source surface did not fail capability");
            System.setProperty(source, "APPLIED");

            System.clearProperty(destination);
            cached.setInt(null, 0);
            require((Integer) capability.invoke(null) == 2,
                    "unloaded mutation surface did not keep capability PENDING");
        } finally {
            for (int i = 0; i < keys.length; i++) {
                if (previous[i] == null) System.clearProperty(keys[i]);
                else System.setProperty(keys[i], previous[i]);
            }
            cached.setInt(null, 0);
        }
    }

    private static List<?> lookup(LocationAPI current, LocationAPI requested,
                                  LocationAPI fallback, PrepatcherConfig config,
                                  long generation) {
        return StarsectorPrepatcherStrategicJumpIndex.lookup(current, JumpPointAPI.class,
                requested, fallback, config, generation);
    }

    private static void prime(StepClock clock, PrepatcherConfig config, long generation) {
        StarsectorPrepatcherStrategicJumpIndex.maintenance(config, generation, clock);
    }

    private static void runFrame(StepClock clock, PrepatcherConfig config,
                                 long generation) {
        clock.advance(FRAME_NANOS);
        long before = diagnostics().workUnits();
        StarsectorPrepatcherStrategicJumpIndex.maintenance(config, generation, clock);
        long delta = diagnostics().workUnits() - before;
        require(delta >= 0 && delta <= MAX_UNITS,
                "maintenance exceeded configured work-unit bound: " + delta);
    }

    private static void awaitReady(LocationAPI location, StepClock clock,
                                   PrepatcherConfig config, long generation,
                                   int maxFrames) {
        awaitPhase(location, "READY", clock, config, generation, maxFrames);
    }

    private static void awaitPhase(LocationAPI location, String phase, StepClock clock,
                                   PrepatcherConfig config, long generation,
                                   int maxFrames) {
        for (int i = 0; i < maxFrames; i++) {
            if (phase.equals(StarsectorPrepatcherStrategicJumpIndex
                    .snapshot(location).phase())) return;
            runFrame(clock, config, generation);
        }
        throw new AssertionError("state did not reach " + phase + ": "
                + StarsectorPrepatcherStrategicJumpIndex.snapshot(location));
    }

    private static void awaitCandidate(LocationAPI current, Object expected,
                                       LocationAPI requested, LocationAPI fallback,
                                       StepClock clock, PrepatcherConfig config,
                                       long generation, int maxFrames) {
        for (int i = 0; i < maxFrames; i++) {
            List<?> candidates = lookup(current, requested, fallback, config, generation);
            if (containsIdentity(candidates, expected)) return;
            runFrame(clock, config, generation);
        }
        throw new AssertionError("audit did not publish expected candidate");
    }

    private static void awaitRetryHeapSize(int expected, StepClock clock,
                                           PrepatcherConfig config,
                                           long generation, int maxFrames) {
        for (int i = 0; i < maxFrames; i++) {
            if (diagnostics().retryQueued() == expected) return;
            runFrame(clock, config, generation);
        }
        throw new AssertionError("retry heap did not reach size " + expected
                + ": " + diagnostics());
    }

    private static void awaitRetryCountAndHeapSize(long expectedRetries,
                                                    int expectedHeapSize,
                                                    StepClock clock,
                                                    PrepatcherConfig config,
                                                    long generation,
                                                    int maxFrames) {
        for (int i = 0; i < maxFrames; i++) {
            var current = diagnostics();
            if (current.retries() == expectedRetries
                    && current.retryQueued() == expectedHeapSize) return;
            require(current.retries() <= expectedRetries,
                    "retry heap serviced more states than expected: " + current);
            runFrame(clock, config, generation);
        }
        throw new AssertionError("retry heap did not settle at retries="
                + expectedRetries + ", size=" + expectedHeapSize + ": "
                + diagnostics());
    }

    private static boolean containsIdentity(List<?> values, Object expected) {
        for (Object value : values) if (value == expected) return true;
        return false;
    }

    private static StarsectorPrepatcherStrategicJumpIndex.Diagnostics diagnostics() {
        return StarsectorPrepatcherStrategicJumpIndex.diagnostics();
    }

    private static LocationAPI location(String id) {
        return (LocationAPI) Proxy.newProxyInstance(
                StrategicJumpDestinationIndexRuntimeTest.class.getClassLoader(),
                new Class<?>[]{LocationAPI.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getEntities" -> ENTITIES.getOrDefault(proxy, List.of());
                    case "getId", "getName" -> id;
                    case "toString" -> id;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static JumpFixture jumpPoint(LocationAPI owner, LocationAPI... locations) {
        List<JumpPointAPI.JumpDestination> destinations = new ArrayList<>();
        for (LocationAPI location : locations) destinations.add(destination(token(location)));
        JumpPointAPI point = (JumpPointAPI) Proxy.newProxyInstance(
                StrategicJumpDestinationIndexRuntimeTest.class.getClassLoader(),
                new Class<?>[]{JumpPointAPI.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getDestinations" -> {
                        DESTINATION_LIST_READS.incrementAndGet();
                        yield destinations;
                    }
                    case "getContainingLocation" -> owner;
                    case "toString" -> "jump@" + System.identityHashCode(proxy);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        return new JumpFixture(point, destinations);
    }

    private static JumpPointAPI failingJumpPoint(LocationAPI owner) {
        return (JumpPointAPI) Proxy.newProxyInstance(
                StrategicJumpDestinationIndexRuntimeTest.class.getClassLoader(),
                new Class<?>[]{JumpPointAPI.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getDestinations" -> throw new IllegalStateException("fixture failure");
                    case "getContainingLocation" -> owner;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static SectorEntityToken token(LocationAPI owner) {
        return (SectorEntityToken) Proxy.newProxyInstance(
                StrategicJumpDestinationIndexRuntimeTest.class.getClassLoader(),
                new Class<?>[]{SectorEntityToken.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getContainingLocation" -> owner;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static JumpPointAPI.JumpDestination destination(SectorEntityToken token) {
        return new JumpPointAPI.JumpDestination(token, "test");
    }

    private record JumpFixture(JumpPointAPI point,
                               List<JumpPointAPI.JumpDestination> destinations) {}

    private static final class StepClock implements java.util.function.LongSupplier {
        private final AtomicLong now = new AtomicLong(1_000_000_000L);

        @Override
        public long getAsLong() {
            return now.getAndAdd(10_000L);
        }

        void advance(long nanos) {
            now.addAndGet(nanos);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return '\0';
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
