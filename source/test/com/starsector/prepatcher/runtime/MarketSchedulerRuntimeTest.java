package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.StarsectorPrepatcherHooks;
import com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.impl.campaign.econ.impl.ConstructionQueue;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.starsector.prepatcher.agent.PrepatcherConfig;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.LongAdder;

/** Runtime semantics checks for the unified engine-owned market scheduler. */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class MarketSchedulerRuntimeTest {
    private MarketSchedulerRuntimeTest() {}

    public static void main(String[] args) throws Exception {
        PrepatcherConfig config = config(true);
        installConfig(config);
        Object engine = new Object();
        activateCampaign(engine);

        MutableLocation currentLocation = new MutableLocation("current");
        MutableLocation remoteLocation = new MutableLocation("remote");
        SectorContext sector = new SectorContext(currentLocation.proxy, 100f);
        Global.setSector(sector.proxy);

        try {
            assertCapabilityGate(remoteLocation);
            assertIdentityStateMaps();
            assertStaggeringAndAmountConservation(sector, currentLocation, remoteLocation);
            assertHotPolicies(sector, currentLocation, remoteLocation);
            assertMemoryOptOut(sector, remoteLocation);
            assertPolicyFailureStaysConservativeWithinBatch(sector, remoteLocation);
            assertFastForwardBatchNormalization(sector, currentLocation, remoteLocation);
            assertHiddenCadenceAndSaveFlush(sector, remoteLocation);
            assertEntitySourceScheduling(sector, currentLocation, remoteLocation);
            assertOutsideTickAndLifecycleMetrics(remoteLocation);
            assertNestedTickFallbackPreservesOuterTick(sector, remoteLocation);
            assertReentrantFallback(sector, remoteLocation);
            assertDuplicateFrameMetrics(sector, remoteLocation);
            assertDeferredDuplicateOrdering(sector, remoteLocation);
            assertAdvanceFailureDisablesScheduling(sector, remoteLocation);
            assertSaveFlushFailureAborts(sector, remoteLocation);
            assertDirectCallSynchronizesDebt(sector, remoteLocation);
            assertFiniteOverflowUsesSplitDelivery(sector, remoteLocation);
            assertExactPendingHistoryAndBatchContext(sector, remoteLocation);
            assertDifferentialBatchSequences(sector, remoteLocation);
            assertPendingRunOverflowFlush(sector, remoteLocation);
            assertConstructionModeAndMutationBoundary(sector, remoteLocation);
            assertConstructionPolicyDirtyAudit(sector, remoteLocation);
            assertConstructionReasonCounters(sector, remoteLocation);
            assertConstructionDiagnosticsCsv(sector, remoteLocation);
            assertSaveExactReplayMode(sector, remoteLocation);
            assertSchedulerBaselineDump(remoteLocation);
            assertWeakStateDoesNotRetainMarket(sector, remoteLocation);
            assertDisabledFallback(sector, remoteLocation);
        } finally {
            Global.setSector(null);
            StarsectorPrepatcherHooks.beginCampaignEngineChange(null);
            installConfig(null);
        }

        System.out.println("OK market-scheduler identity-state"
                + " stable-phase/staggering amount-conservation"
                + " current/player/interaction/memory hot policies"
                + " conservative policy-failure mode"
                + " render-batch normalization x1/x4/x10/x100 per-simulation-tick-cost"
                + " capability-gate hidden cadence save-flush"
                + " shared entity-source/staggering/current-location/memory/save-flush/reentrant"
                + " outside-tick/lifecycle/nested/reentrant/duplicate ordering"
                + " advance-failure/save-abort/direct-sync/finite-overflow-split"
                + " exact-RLE/batch-context/differential-sequences/run-overflow"
                + " construction-full-rate/mutation-boundary/reason-counters/diagnostic-csv/save-exact"
                + " pending-vs-delivered-baseline-dump"
                + " weak-key/disabled metrics");
    }


    private static void assertCapabilityGate(MutableLocation remoteLocation) throws Exception {
        MutableMarket market = new MutableMarket("capability-gate", remoteLocation.proxy,
                false, false);
        long notReadyTicks = metric("MARKET_SCHEDULER_NOT_READY_TICKS");
        long notReadyCalls = metric("MARKET_SCHEDULER_NOT_READY_CALLS");
        long frames = metric("MARKET_SCHEDULER_SIMULATION_TICKS");
        int calls = 0;
        float total = 0f;

        int[] incompleteComponents = {1, 2, 4, 8, 16, 32, 64, 128, 256, 512};
        for (int component : incompleteComponents) {
            beginSyntheticSchedulerTick();
            try {
                StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.125f, 0);
            } finally {
                endSyntheticSchedulerTick();
            }
            calls++;
            total += 0.125f;
            require(market.calls == calls && close(market.totalAmount, total),
                    "incomplete scheduler did not fail open before component " + component);
            require(metric("MARKET_SCHEDULER_SIMULATION_TICKS") == frames,
                    "scheduler activated with incomplete semantic capability mask before "
                            + component);
            StarsectorPrepatcherHooks.registerMarketSchedulerComponent(component);
        }
        require(metric("MARKET_SCHEDULER_NOT_READY_TICKS")
                        == notReadyTicks + incompleteComponents.length,
                "incomplete scheduler ticks were not reported");
        require(metric("MARKET_SCHEDULER_NOT_READY_CALLS")
                        == notReadyCalls + incompleteComponents.length,
                "incomplete scheduler calls were not reported");

        // ConstructionQueue is the final mandatory semantic component. Until it
        // registers, the core cadence/source/save components must not activate.
        beginSyntheticSchedulerTick();
        try {
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.25f, 0);
        } finally {
            endSyntheticSchedulerTick();
        }
        calls++;
        total += 0.25f;
        require(market.calls == calls && close(market.totalAmount, total),
                "scheduler activated while ConstructionQueue barrier capability was missing");
        require(metric("MARKET_SCHEDULER_SIMULATION_TICKS") == frames,
                "missing ConstructionQueue capability still published a scheduler tick");

        StarsectorPrepatcherHooks.registerMarketSchedulerComponent(1024);
        beginSyntheticSchedulerTick();
        try {
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 1.0f, 0);
        } finally {
            endSyntheticSchedulerTick();
        }
        require(metric("MARKET_SCHEDULER_SIMULATION_TICKS") == frames + 1L,
                "complete scheduler did not publish a tick");
        require(market.calls == calls + 1 && close(market.totalAmount, total + 1.0f),
                "complete scheduler did not execute its first market call");
        require("2047".equals(System.getProperty(
                        "starsector.prepatcher.marketSchedulerCapabilityMask")),
                "semantic capability mask is incomplete");
    }

    private static void assertIdentityStateMaps() throws Exception {
        Field field = StarsectorPrepatcherHooks.class.getDeclaredField("MARKET_SCHEDULER_STATES");
        field.setAccessible(true);
        Object map = field.get(null);
        require(map.getClass().getSimpleName().equals("WeakIdentityMap"),
                "market scheduler state is not weak-identity keyed: " + map.getClass());
    }

    private static void assertStaggeringAndAmountConservation(
            SectorContext sector, MutableLocation current, MutableLocation remote) throws Exception {
        resetSchedulerGeneration();
        sector.currentLocation = current.proxy;
        sector.interactionMarket = null;

        ArrayList<MutableMarket> markets = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            markets.add(new MutableMarket("remote-" + i, remote.proxy, false, false));
        }

        Method stablePhase = StarsectorPrepatcherHooks.class.getDeclaredMethod(
                "stableMarketPhase", MarketAPI.class, int.class);
        stablePhase.setAccessible(true);

        int frames = 13;
        for (int frame = 1; frame <= frames; frame++) {
            beginSyntheticSchedulerTick();
            try {
                for (MutableMarket market : markets) {
                    StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 1f, 0);
                }
            } finally {
                endSyntheticSchedulerTick();
            }
        }

        boolean sawDeferred = false;
        boolean sawDifferentCadence = false;
        int firstCalls = markets.get(0).advanceFrames.size();
        for (MutableMarket market : markets) {
            int phase = (Integer) stablePhase.invoke(null, market.proxy, 4);
            ArrayList<Integer> expected = new ArrayList<>();
            expected.add(1); // Newly observed markets always initialize immediately.
            for (int frame = 2; frame <= frames; frame++) {
                if (Math.floorMod(frame, 4) == phase) expected.add(frame);
            }
            require(market.advanceFrames.equals(expected),
                    "remote market cadence diverged for " + market.id
                            + ": expected=" + expected + " actual=" + market.advanceFrames);
            if (market.calls < frames) sawDeferred = true;
            if (market.calls != firstCalls) sawDifferentCadence = true;
        }
        require(sawDeferred, "remote markets were not staggered");
        // Different phase counts are not guaranteed for every short interval,
        // but the per-market frame assertions above prove stable phase behavior.
        if (!sawDifferentCadence) {
            require(markets.stream().map(m -> m.advanceFrames).distinct().count() > 1,
                    "all remote markets unexpectedly used one phase");
        }

        StarsectorPrepatcherHooks.flushMarketSchedulerBeforeSave();
        for (MutableMarket market : markets) {
            require(close(market.totalAmount, frames),
                    "deferred amount was lost for " + market.id + ": " + market.totalAmount);
        }
    }

    private static void assertHotPolicies(
            SectorContext sector, MutableLocation current, MutableLocation remote) throws Exception {
        resetSchedulerGeneration();

        MutableMarket local = new MutableMarket("local", current.proxy, false, false);
        MutableMarket playerOwned = new MutableMarket("player", remote.proxy, true, false);
        MutableMarket interaction = new MutableMarket("interaction", remote.proxy, false, false);
        sector.currentLocation = current.proxy;
        sector.interactionMarket = interaction.proxy;

        for (int frame = 0; frame < 9; frame++) {
            beginSyntheticSchedulerTick();
            try {
                StarsectorPrepatcherHooks.advanceMarketScheduled(local.proxy, 0.25f, 0);
                StarsectorPrepatcherHooks.advanceMarketScheduled(playerOwned.proxy, 0.25f, 0);
                StarsectorPrepatcherHooks.advanceMarketScheduled(interaction.proxy, 0.25f, 0);
            } finally {
                endSyntheticSchedulerTick();
            }
        }

        require(local.calls == 9 && playerOwned.calls == 9 && interaction.calls == 9,
                "one of the full-rate hot-market policies was throttled");
        require(close(local.totalAmount, 2.25f)
                        && close(playerOwned.totalAmount, 2.25f)
                        && close(interaction.totalAmount, 2.25f),
                "hot-market amount changed");
    }

    private static void assertMemoryOptOut(
            SectorContext sector, MutableLocation remote) throws Exception {
        resetSchedulerGeneration();
        sector.currentLocation = null;
        sector.interactionMarket = null;
        MutableMarket market = new MutableMarket("memory-hot", remote.proxy, false, false);
        market.memory.fullRate = true;

        for (int frame = 0; frame < 7; frame++) {
            beginSyntheticSchedulerTick();
            try {
                StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 1f, 0);
            } finally {
                endSyntheticSchedulerTick();
            }
        }
        require(market.calls == 7 && close(market.totalAmount, 7f),
                "per-market memory opt-out did not keep full cadence");
    }

    private static void assertPolicyFailureStaysConservativeWithinBatch(
            SectorContext sector, MutableLocation remote) throws Exception {
        resetSchedulerGeneration();
        sector.currentLocation = null;
        sector.interactionMarket = null;
        MutableMarket market = new MutableMarket(
                "policy-failure-conservative", remote.proxy, false, false);
        market.memory.failuresRemaining = 1;
        long failuresBefore = metric("MARKET_SCHEDULER_POLICY_FAILURES");
        long perTickBefore = metric("MARKET_SCHEDULER_PER_SIMULATION_TICK_CALLS");

        runRenderBatch(market, 4, 0.25f, 0);
        require(market.calls == 4 && close(market.totalAmount, 1f),
                "policy lookup failure did not keep the market conservative for the whole batch");
        require(metric("MARKET_SCHEDULER_POLICY_FAILURES") == failuresBefore + 1L,
                "policy lookup failure was not counted exactly once");
        require(metric("MARKET_SCHEDULER_PER_SIMULATION_TICK_CALLS") == perTickBefore + 4L,
                "conservative policy failure did not retain per-simulation-tick delivery");

        int before = market.calls;
        runRenderBatch(market, 4, 0.25f, 0);
        require(market.calls - before == 1,
                "successful policy re-audit did not return the market to batch aggregation");
    }

    private static void assertFastForwardBatchNormalization(
            SectorContext sector, MutableLocation current, MutableLocation remote) throws Exception {
        resetSchedulerGeneration();
        sector.currentLocation = current.proxy;
        sector.interactionMarket = null;

        MutableMarket hot = new MutableMarket("fast-forward-hot", current.proxy, false, false);
        int[] speeds = {1, 4, 10, 100};
        int batchesPerSpeed = 3;
        int expectedCallbacks = 0;
        float expectedAmount = 0f;
        for (int speed : speeds) {
            int before = hot.calls;
            for (int batch = 0; batch < batchesPerSpeed; batch++) {
                runRenderBatch(hot, speed, 0.25f, 0);
                expectedCallbacks++;
                expectedAmount += speed * 0.25f;
            }
            require(hot.calls - before == batchesPerSpeed,
                    "hot market callback count scaled with speed x" + speed);
        }
        require(hot.calls == expectedCallbacks && close(hot.totalAmount, expectedAmount),
                "hot render-batch aggregation lost callbacks or amount");

        resetSchedulerGeneration();
        sector.currentLocation = null;
        MutableMarket cold = new MutableMarket("fast-forward-cold", remote.proxy, false, false);
        int coldCallsBefore;
        float coldExpected = 0f;
        for (int speed : speeds) {
            coldCallsBefore = cold.calls;
            for (int batch = 0; batch < 8; batch++) {
                runRenderBatch(cold, speed, 0.125f, 0);
                coldExpected += speed * 0.125f;
            }
            int callbacks = cold.calls - coldCallsBefore;
            require(callbacks >= 1 && callbacks <= 3,
                    "cold cadence scaled with simulation ticks at speed x" + speed
                            + ": callbacks=" + callbacks);
        }
        StarsectorPrepatcherHooks.flushMarketSchedulerBeforeSave();
        require(close(cold.totalAmount, coldExpected),
                "cold fast-forward aggregation lost amount");

        resetSchedulerGeneration();
        MutableMarket perTick = new MutableMarket(
                "per-simulation-tick", remote.proxy, false, false);
        perTick.memory.fullRate = true;
        long callsBefore = metric("MARKET_SCHEDULER_PER_SIMULATION_TICK_CALLS");
        runRenderBatch(perTick, 1, 0.5f, 0);
        int normalCallbacks = perTick.calls;
        runRenderBatch(perTick, 10, 0.5f, 0);
        int fastCallbacks = perTick.calls - normalCallbacks;
        require(normalCallbacks == 1 && fastCallbacks == 10,
                "per-simulation-tick opt-out did not expose its exact callback cost");
        require(metric("MARKET_SCHEDULER_PER_SIMULATION_TICK_CALLS")
                        == callsBefore + 11L,
                "per-simulation-tick callbacks were not counted");
        Method count = StarsectorPrepatcherHooks.class.getDeclaredMethod(
                "countPerSimulationTickMarkets");
        count.setAccessible(true);
        require((Integer) count.invoke(null) == 1,
                "known per-simulation-tick market count is incorrect");
    }

    private static void runRenderBatch(
            MutableMarket market, int simulationTicks, float amount, int source) {
        StarsectorPrepatcherHooks.marketSchedulerFastForwardIterationChanged(false);
        for (int tick = 0; tick < simulationTicks; tick++) {
            StarsectorPrepatcherHooks.beginMarketSchedulerTick();
            try {
                StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, amount, source);
            } finally {
                StarsectorPrepatcherHooks.endMarketSchedulerTick();
                StarsectorPrepatcherHooks.marketSchedulerFastForwardIterationChanged(true);
            }
        }
        StarsectorPrepatcherHooks.marketSchedulerFastForwardIterationChanged(false);
    }

    private static void assertHiddenCadenceAndSaveFlush(
            SectorContext sector, MutableLocation remote) throws Exception {
        resetSchedulerGeneration();
        sector.currentLocation = null;
        sector.interactionMarket = null;
        MutableMarket hidden = new MutableMarket("hidden", remote.proxy, false, true);

        Method stablePhase = StarsectorPrepatcherHooks.class.getDeclaredMethod(
                "stableMarketPhase", MarketAPI.class, int.class);
        stablePhase.setAccessible(true);
        int phase = (Integer) stablePhase.invoke(null, hidden.proxy, 8);

        int frames = 11;
        for (int frame = 1; frame <= frames; frame++) {
            beginSyntheticSchedulerTick();
            try {
                StarsectorPrepatcherHooks.advanceMarketScheduled(hidden.proxy, 2f, 0);
            } finally {
                endSyntheticSchedulerTick();
            }
        }
        ArrayList<Integer> expected = new ArrayList<>();
        expected.add(1);
        for (int frame = 2; frame <= frames; frame++) {
            if (Math.floorMod(frame, 8) == phase) expected.add(frame);
        }
        require(hidden.advanceFrames.equals(expected),
                "hidden market did not use hidden cadence: expected=" + expected
                        + " actual=" + hidden.advanceFrames);

        float before = hidden.totalAmount;
        StarsectorPrepatcherHooks.flushMarketSchedulerBeforeSave();
        require(close(hidden.totalAmount, frames * 2f),
                "save flush lost hidden-market time: before=" + before
                        + " after=" + hidden.totalAmount);
        int callsAfterFirstFlush = hidden.calls;
        StarsectorPrepatcherHooks.flushMarketSchedulerBeforeSave();
        require(hidden.calls == callsAfterFirstFlush,
                "save flush replayed already-applied pending time");
    }

    private static void assertEntitySourceScheduling(
            SectorContext sector, MutableLocation current, MutableLocation remote) throws Exception {
        resetSchedulerGeneration();
        sector.currentLocation = current.proxy;
        sector.interactionMarket = null;

        Method stablePhase = StarsectorPrepatcherHooks.class.getDeclaredMethod(
                "stableMarketPhase", MarketAPI.class, int.class);
        stablePhase.setAccessible(true);

        ArrayList<MutableMarket> remoteMarkets = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            remoteMarkets.add(new MutableMarket("condition-remote-" + i,
                    remote.proxy, false, false));
        }
        MutableMarket local = new MutableMarket(
                "condition-local", current.proxy, false, false);
        MutableMarket memoryHot = new MutableMarket(
                "condition-memory-hot", remote.proxy, false, false);
        memoryHot.memory.fullRate = true;
        MutableMarket economyViaConditionSource = new MutableMarket(
                "economy-condition-source", remote.proxy, true, false);
        economyViaConditionSource.inEconomy = true;

        int frames = 13;
        for (int frame = 1; frame <= frames; frame++) {
            beginSyntheticSchedulerTick();
            try {
                for (MutableMarket market : remoteMarkets) {
                    StarsectorPrepatcherHooks.advanceMarketScheduled(
                            market.proxy, 1f, 1);
                }
                StarsectorPrepatcherHooks.advanceMarketScheduled(
                        local.proxy, 0.5f, 1);
                StarsectorPrepatcherHooks.advanceMarketScheduled(
                        memoryHot.proxy, 0.25f, 1);
                StarsectorPrepatcherHooks.advanceMarketScheduled(
                        economyViaConditionSource.proxy, 0.125f, 1);
            } finally {
                endSyntheticSchedulerTick();
            }
        }

        for (MutableMarket market : remoteMarkets) {
            int phase = (Integer) stablePhase.invoke(null, market.proxy, 4);
            ArrayList<Integer> expected = new ArrayList<>();
            for (int frame = 1; frame <= frames; frame++) {
                if (Math.floorMod(frame, 4) == phase) expected.add(frame);
            }
            require(market.advanceFrames.equals(expected),
                    "planet-condition cadence diverged for " + market.id
                            + ": expected=" + expected + " actual=" + market.advanceFrames);
        }
        require(local.calls == frames && close(local.totalAmount, frames * 0.5f),
                "current-location planet-condition market was throttled");
        require(memoryHot.calls == frames && close(memoryHot.totalAmount, frames * 0.25f),
                "planet-condition memory opt-out was throttled");
        require(economyViaConditionSource.calls == frames
                        && close(economyViaConditionSource.totalAmount, frames * 0.125f),
                "economy market reached through condition source lost player hot policy");

        StarsectorPrepatcherHooks.flushMarketSchedulerBeforeSave();
        for (MutableMarket market : remoteMarkets) {
            require(close(market.totalAmount, frames),
                    "planet-condition save flush lost amount for " + market.id);
        }

        resetSchedulerGeneration();
        sector.currentLocation = null;
        long reentrantMetricBefore = metric("MARKET_SCHEDULER_REENTRANT_CALLS");
        MutableMarket reentrant = new MutableMarket(
                "condition-reentrant", remote.proxy, false, false);
        reentrant.reenterPlanetOnce = true;
        int scheduledFrames = 0;
        while (reentrant.calls == 0 && scheduledFrames < 4) {
            beginSyntheticSchedulerTick();
            try {
                StarsectorPrepatcherHooks.advanceMarketScheduled(
                        reentrant.proxy, 1f, 1);
                scheduledFrames++;
            } finally {
                endSyntheticSchedulerTick();
            }
        }
        require(reentrant.calls == 2
                        && close(reentrant.totalAmount, scheduledFrames + 0.5f),
                "planet-condition reentrant call was lost or coalesced");
        require(metric("MARKET_SCHEDULER_REENTRANT_CALLS")
                        == reentrantMetricBefore + 1L,
                "planet-condition reentrant metric did not identify the recursive call");
    }

    private static void assertNestedTickFallbackPreservesOuterTick(
            SectorContext sector, MutableLocation remote) throws Exception {
        resetSchedulerGeneration();
        sector.currentLocation = null;
        sector.interactionMarket = null;
        MutableMarket nested = new MutableMarket("nested-economy", remote.proxy, false, false);
        long nestedTicksBefore = metric("MARKET_SCHEDULER_NESTED_TICKS");
        long outsideTickBefore = metric("MARKET_SCHEDULER_OUTSIDE_TICK_CALLS");

        beginSyntheticSchedulerTick();
        try {
            long outerFrame = schedulerFrame();
            StarsectorPrepatcherHooks.beginMarketSchedulerTick();
            try {
                require(schedulerFrame() == outerFrame,
                        "nested CampaignEngine tick overwrote the outer scheduler context");
                StarsectorPrepatcherHooks.advanceMarketScheduled(nested.proxy, 0.5f, 0);
            } finally {
                StarsectorPrepatcherHooks.endMarketSchedulerTick();
            }

            require(schedulerFrame() == outerFrame,
                    "nested tick fallback did not preserve the outer frame");
            StarsectorPrepatcherHooks.advanceMarketScheduled(nested.proxy, 1f, 0);
        } finally {
            endSyntheticSchedulerTick();
        }

        require(nested.calls == 2 && close(nested.totalAmount, 1.5f),
                "nested tick fallback changed callback multiplicity or amount");
        require(metric("MARKET_SCHEDULER_NESTED_TICKS")
                        == nestedTicksBefore + 1L,
                "nested CampaignEngine tick metric was not incremented exactly once");
        require(metric("MARKET_SCHEDULER_OUTSIDE_TICK_CALLS")
                        == outsideTickBefore + 1L,
                "nested market call was not classified as a outside-tick fallback");
    }

    private static void assertReentrantFallback(
            SectorContext sector, MutableLocation remote) throws Exception {
        resetSchedulerGeneration();
        sector.currentLocation = null;
        sector.interactionMarket = null;
        long reentrantMetricBefore = metric("MARKET_SCHEDULER_REENTRANT_CALLS");
        MutableMarket market = new MutableMarket("reentrant", remote.proxy, false, false);
        market.reenterOnce = true;

        beginSyntheticSchedulerTick();
        try {
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 1f, 0);
        } finally {
            endSyntheticSchedulerTick();
        }
        require(market.calls == 2 && close(market.totalAmount, 1.5f),
                "reentrant market callback deadlocked, disappeared, or coalesced unexpectedly");
        require(metric("MARKET_SCHEDULER_REENTRANT_CALLS")
                        == reentrantMetricBefore + 1L,
                "remote-market reentrant metric did not identify the recursive call");
    }

    private static void assertDuplicateFrameMetrics(
            SectorContext sector, MutableLocation remote) throws Exception {
        resetSchedulerGeneration();
        sector.currentLocation = null;
        sector.interactionMarket = null;

        long multipleBefore = metric("MARKET_SCHEDULER_MULTIPLE_SOURCE_TICK_CALLS");
        MutableMarket sameSource = new MutableMarket(
                "same-source-batch", remote.proxy, false, false);
        beginSyntheticSchedulerTick();
        try {
            StarsectorPrepatcherHooks.advanceMarketScheduled(sameSource.proxy, 1f, 0);
            StarsectorPrepatcherHooks.advanceMarketScheduled(sameSource.proxy, 0.25f, 0);
        } finally {
            endSyntheticSchedulerTick();
        }
        require(sameSource.calls == 1 && close(sameSource.totalAmount, 1.25f),
                "same-source calls were not coalesced at render-batch end");
        require(metric("MARKET_SCHEDULER_MULTIPLE_SOURCE_TICK_CALLS") == multipleBefore,
                "same-source calls were misclassified as multiple-source");

        resetSchedulerGeneration();
        long crossSourceBefore = metric("MARKET_SCHEDULER_MULTIPLE_SOURCE_TICK_CALLS");
        MutableMarket crossSource = new MutableMarket(
                "cross-source-batch", remote.proxy, false, false);
        beginSyntheticSchedulerTick();
        try {
            StarsectorPrepatcherHooks.advanceMarketScheduled(crossSource.proxy, 1f, 0);
            StarsectorPrepatcherHooks.advanceMarketScheduled(crossSource.proxy, 0.25f, 1);
        } finally {
            endSyntheticSchedulerTick();
        }
        require(crossSource.calls == 1 && close(crossSource.totalAmount, 1.25f),
                "cross-source calls were not coalesced at render-batch end");
        require(metric("MARKET_SCHEDULER_MULTIPLE_SOURCE_TICK_CALLS")
                        == crossSourceBefore + 1L,
                "multiple-source simulation tick was not counted");
    }


    private static void assertOutsideTickAndLifecycleMetrics(
            MutableLocation remote) throws Exception {
        resetSchedulerGeneration();
        MutableMarket outside = new MutableMarket("outside-tick", remote.proxy, false, false);
        long outsideBefore = metric("MARKET_SCHEDULER_OUTSIDE_TICK_CALLS");
        StarsectorPrepatcherHooks.advanceMarketScheduled(outside.proxy, 0.5f, 1);
        require(outside.calls == 1 && close(outside.totalAmount, 0.5f),
                "outside-tick entity source did not fail open");
        require(metric("MARKET_SCHEDULER_OUTSIDE_TICK_CALLS") == outsideBefore + 1L,
                "outside-tick entity source was not classified explicitly");

        StarsectorPrepatcherHooks.beginCampaignEngineChange(null);
        MutableMarket inactive = new MutableMarket("lifecycle-inactive", remote.proxy, false, false);
        long inactiveBefore = metric("MARKET_SCHEDULER_LIFECYCLE_INACTIVE_CALLS");
        beginSyntheticSchedulerTick();
        try {
            StarsectorPrepatcherHooks.advanceMarketScheduled(inactive.proxy, 0.25f, 0);
        } finally {
            endSyntheticSchedulerTick();
        }
        require(inactive.calls == 1 && close(inactive.totalAmount, 0.25f),
                "inactive lifecycle did not fail open");
        require(metric("MARKET_SCHEDULER_LIFECYCLE_INACTIVE_CALLS") == inactiveBefore + 1L,
                "inactive lifecycle was not classified explicitly");
        activateCampaign(new Object());
    }

    private static void assertDeferredDuplicateOrdering(
            SectorContext sector, MutableLocation remote) throws Exception {
        resetSchedulerGeneration();
        sector.currentLocation = null;
        sector.interactionMarket = null;
        MutableMarket market = new MutableMarket("deferred-multiple-source", remote.proxy, false, false);

        beginSyntheticSchedulerTick();
        try {
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.5f, 0);
        } finally {
            endSyntheticSchedulerTick();
        }
        beginNonDueTick(market.proxy, 4);
        long multipleBefore = metric("MARKET_SCHEDULER_MULTIPLE_SOURCE_TICK_CALLS");
        int callsBefore = market.calls;
        try {
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 1f, 0);
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.25f, 1);
        } finally {
            endSyntheticSchedulerTick();
        }
        require(market.calls == callsBefore,
                "multiple-source cold market was forced to full rate");
        require(close(pendingAmount(market.proxy), 1.25f),
                "multiple-source debt was not coalesced in order");
        require(metric("MARKET_SCHEDULER_MULTIPLE_SOURCE_TICK_CALLS")
                        == multipleBefore + 1L,
                "multiple-source deferred call was not counted");

        StarsectorPrepatcherHooks.observeDirectMarketAdvance(
                market.proxy, 0f, 0x66L, "deferred-order-sync");
        require(market.calls == callsBefore + 2 && close(market.totalAmount, 1.75f),
                "synchronous event did not consume deferred multi-source debt before its own step");
        require(close(pendingAmount(market.proxy), 0f),
                "synchronous event left deferred multi-source debt pending");
    }


    private static void assertAdvanceFailureDisablesScheduling(
            SectorContext sector, MutableLocation remote) throws Exception {
        resetSchedulerGeneration();
        sector.currentLocation = null;
        sector.interactionMarket = null;
        MutableMarket market = new MutableMarket("advance-failure", remote.proxy, false, false);
        market.throwAfterApplyOnce = true;
        long failuresBefore = metric("MARKET_SCHEDULER_ADVANCE_FAILURES");
        boolean threw = false;
        beginSyntheticSchedulerTick();
        StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 1f, 0);
        try {
            endSyntheticSchedulerTick();
        } catch (IllegalStateException expected) {
            threw = true;
        }
        require(threw, "market callback exception was swallowed");
        require(metric("MARKET_SCHEDULER_ADVANCE_FAILURES") == failuresBefore + 1L,
                "market callback exception was not counted");
        require(close(pendingAmount(market.proxy), 0f),
                "ambiguous debt was retained after partial callback failure");

        long disabledBefore = metric("MARKET_SCHEDULER_DISABLED_MARKET_CALLS");
        beginSyntheticSchedulerTick();
        try {
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.5f, 0);
        } finally {
            endSyntheticSchedulerTick();
        }
        require(market.calls == 2 && close(market.totalAmount, 1.5f),
                "failed market was not switched to exact immediate execution");
        require(metric("MARKET_SCHEDULER_DISABLED_MARKET_CALLS") == disabledBefore + 1L,
                "disabled-market path was not counted");
    }

    private static void assertSaveFlushFailureAborts(
            SectorContext sector, MutableLocation remote) throws Exception {
        resetSchedulerGeneration();
        sector.currentLocation = null;
        sector.interactionMarket = null;
        MutableMarket market = new MutableMarket("save-failure", remote.proxy, false, false);
        warmAndDefer(market, 1f, 0);
        require(close(pendingAmount(market.proxy), 1f),
                "save test did not create pending debt");
        market.throwBeforeApplyOnce = true;
        long failuresBefore = metric("MARKET_SCHEDULER_SAVE_FLUSH_FAILURES");
        boolean threw = false;
        try {
            StarsectorPrepatcherHooks.flushMarketSchedulerBeforeSave();
        } catch (IllegalStateException expected) {
            threw = true;
        }
        require(threw, "failed save flush did not abort serialization");
        require(metric("MARKET_SCHEDULER_SAVE_FLUSH_FAILURES") == failuresBefore + 1L,
                "failed save flush was not counted");
        require(close(pendingAmount(market.proxy), 0f),
                "failed save flush retained ambiguous detached debt");

        // The callback may have partially mutated the market before throwing.
        // Automatic retry is forbidden; the market is permanently failed open.
        StarsectorPrepatcherHooks.flushMarketSchedulerBeforeSave();
        beginSyntheticSchedulerTick();
        try {
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.25f, 0);
        } finally {
            endSyntheticSchedulerTick();
        }
        require(close(market.totalAmount, 0.75f) && close(pendingAmount(market.proxy), 0f),
                "failed save market was not switched to immediate execution");
    }

    private static void assertDirectCallSynchronizesDebt(
            SectorContext sector, MutableLocation remote) throws Exception {
        resetSchedulerGeneration();
        sector.currentLocation = null;
        sector.interactionMarket = null;
        MutableMarket market = new MutableMarket("direct-sync", remote.proxy, false, false);
        warmAndDefer(market, 1f, 0);
        require(close(pendingAmount(market.proxy), 1f),
                "direct-call test did not create pending debt");
        long syncBefore = metric("MARKET_SCHEDULER_DIRECT_SYNCHRONIZATIONS");
        int callsBefore = market.calls;
        StarsectorPrepatcherHooks.observeDirectMarketAdvance(
                market.proxy, 0.25f, 0x55L, "runtime-test");
        require(market.calls == callsBefore + 2 && close(market.totalAmount, 1.75f),
                "direct call did not consume scheduler debt before its own amount");
        require(close(pendingAmount(market.proxy), 0f),
                "direct call left synchronized debt pending");
        require(metric("MARKET_SCHEDULER_DIRECT_SYNCHRONIZATIONS") == syncBefore + 1L,
                "direct synchronization was not counted");
    }

    private static void assertFiniteOverflowUsesSplitDelivery(
            SectorContext sector, MutableLocation remote) throws Exception {
        resetSchedulerGeneration();
        sector.currentLocation = null;
        sector.interactionMarket = null;
        float huge = 3.0e38f;
        long splitsBefore = metric("MARKET_SCHEDULER_AMOUNT_OVERFLOW_SPLITS");

        MutableMarket synchronizedMarket = new MutableMarket(
                "overflow-synchronized", remote.proxy, false, false);
        warmAndDefer(synchronizedMarket, huge, 0);
        int before = synchronizedMarket.deliveredAmounts.size();
        StarsectorPrepatcherHooks.advanceMarketSynchronized(
                synchronizedMarket.proxy, huge, 2);
        require(synchronizedMarket.deliveredAmounts.size() == before + 2,
                "synchronized overflow did not split into two finite callbacks");
        requireFiniteTail(synchronizedMarket, huge, "synchronized overflow");
        require(close(pendingAmount(synchronizedMarket.proxy), 0f),
                "synchronized overflow left scheduler debt behind");

        MutableMarket directMarket = new MutableMarket(
                "overflow-direct", remote.proxy, false, false);
        warmAndDefer(directMarket, huge, 0);
        before = directMarket.deliveredAmounts.size();
        StarsectorPrepatcherHooks.observeDirectMarketAdvance(
                directMarket.proxy, huge, 123L, "overflow-direct-site");
        require(directMarket.deliveredAmounts.size() == before + 2,
                "direct overflow did not split into two finite callbacks");
        requireFiniteTail(directMarket, huge, "direct overflow");
        require(close(pendingAmount(directMarket.proxy), 0f),
                "direct overflow left scheduler debt behind");
        require(metric("MARKET_SCHEDULER_AMOUNT_OVERFLOW_SPLITS") == splitsBefore,
                "synchronous step barriers should not report arithmetic overflow splits");
    }

    private static void requireFiniteTail(MutableMarket market, float expected, String label) {
        int size = market.deliveredAmounts.size();
        float first = market.deliveredAmounts.get(size - 2);
        float second = market.deliveredAmounts.get(size - 1);
        require(Float.isFinite(first) && Float.isFinite(second),
                label + " passed a non-finite amount");
        require(close(first, expected) && close(second, expected),
                label + " changed the finite operands: " + first + ", " + second);
    }


    private static void assertExactPendingHistoryAndBatchContext(
            SectorContext sector, MutableLocation remote) throws Exception {
        installConfig(config(true, 32, false));
        resetSchedulerGeneration();
        sector.currentLocation = null;
        sector.interactionMarket = null;

        MutableMarket market = new MutableMarket("exact-history", remote.proxy, false, false);
        market.emulateMarketWrapper = true;
        runRenderBatch(market, 1, 0.5f, 0); // initialize cadence
        market.clearDeliveries();

        MutableMarket nested = new MutableMarket("nested-context", remote.proxy, false, false);
        nested.emulateMarketWrapper = true;
        market.nestedDirectMarket = nested;

        float[] steps = {0.016f, 0.016f, 0.020f, 0.020f, 0.020f};
        StarsectorPrepatcherHooks.marketSchedulerFastForwardIterationChanged(false);
        beginSyntheticSchedulerTick();
        try {
            for (float step : steps) {
                StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, step, 0);
            }
        } finally {
            endSyntheticFastForwardTick();
        }

        Object state = schedulerState(market.proxy);
        require(intField(state, "pendingSteps") == 5,
                "exact pending history lost step count");
        Object runs = field(state, "pendingRuns");
        require(runs instanceof java.util.ArrayDeque<?> deque && deque.size() == 2,
                "exact pending history was not run-length encoded");
        Object[] encoded = ((java.util.ArrayDeque<?>) runs).toArray();
        require(Float.floatToRawIntBits(floatField(encoded[0], "amount"))
                        == Float.floatToRawIntBits(0.016f)
                        && intField(encoded[0], "count") == 2,
                "first pending run is incorrect");
        require(Float.floatToRawIntBits(floatField(encoded[1], "amount"))
                        == Float.floatToRawIntBits(0.020f)
                        && intField(encoded[1], "count") == 3,
                "second pending run is incorrect");

        StarsectorPrepatcherHooks.flushMarketSchedulerBeforeSave();
        require(market.calls == 1, "coalesced save did not call Market.advance once");
        require(market.batchObservations.size() == 1,
                "coalesced save did not publish one batch context");
        BatchObservation observation = market.batchObservations.get(0);
        require(observation.totalSteps == 5 && observation.amounts.size() == 2,
                "batch context did not preserve RLE history");
        require(rawEquals(observation.amounts.get(0), 0.016f)
                        && observation.repeats.get(0) == 2
                        && rawEquals(observation.amounts.get(1), 0.020f)
                        && observation.repeats.get(1) == 3,
                "batch context changed run values or order");
        require(market.outerContextRestored,
                "nested advance did not restore the outer market batch context");
        require(nested.batchVisibility.size() == 1 && !nested.batchVisibility.get(0),
                "direct nested market inherited another market's batch context");
        require(intField(state, "pendingSteps") == 0
                        && ((java.util.ArrayDeque<?>) field(state, "pendingRuns")).isEmpty(),
                "flush did not clear pending history references");
        StarsectorPrepatcherHooks.marketSchedulerFastForwardIterationChanged(false);
    }


    private static void assertDifferentialBatchSequences(
            SectorContext sector, MutableLocation remote) throws Exception {
        ArrayList<float[]> cases = new ArrayList<>();
        cases.add(repeatedSteps(0.016f, 100));
        cases.add(repeatedSteps(0.016f, 400));
        cases.add(concat(repeatedSteps(0.016f, 20), repeatedSteps(0.020f, 10)));
        cases.add(concat(repeatedSteps(0.016f, 10), new float[] {0.100f},
                repeatedSteps(0.016f, 10)));
        float[] distinct = new float[48];
        for (int i = 0; i < distinct.length; i++) distinct[i] = 0.010f + i * 0.0001f;
        cases.add(distinct);

        installConfig(config(true, 32, false));
        sector.currentLocation = null;
        for (int caseIndex = 0; caseIndex < cases.size(); caseIndex++) {
            resetSchedulerGeneration();
            MutableMarket market = new MutableMarket(
                    "differential-" + caseIndex, remote.proxy, false, false);
            market.emulateMarketWrapper = true;
            runRenderBatch(market, 1, 0.5f, 0);
            market.clearDeliveries();

            StarsectorPrepatcherHooks.marketSchedulerFastForwardIterationChanged(false);
            beginSyntheticSchedulerTick();
            try {
                for (float step : cases.get(caseIndex)) {
                    StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, step, 0);
                }
            } finally {
                endSyntheticFastForwardTick();
            }
            StarsectorPrepatcherHooks.flushMarketSchedulerBeforeSave();
            StarsectorPrepatcherHooks.marketSchedulerFastForwardIterationChanged(false);

            ArrayList<Float> replayed = new ArrayList<>();
            for (BatchObservation observation : market.batchObservations) {
                for (int run = 0; run < observation.amounts.size(); run++) {
                    for (int repeat = 0; repeat < observation.repeats.get(run); repeat++) {
                        replayed.add(observation.amounts.get(run));
                    }
                }
            }
            float[] expected = cases.get(caseIndex);
            require(replayed.size() == expected.length,
                    "differential replay step count changed for case " + caseIndex
                            + ": expected=" + expected.length + " actual=" + replayed.size());
            for (int i = 0; i < expected.length; i++) {
                require(rawEquals(replayed.get(i), expected[i]),
                        "differential replay changed step " + i + " for case " + caseIndex);
            }
        }
    }

    private static float[] repeatedSteps(float amount, int count) {
        float[] result = new float[count];
        java.util.Arrays.fill(result, amount);
        return result;
    }

    private static float[] concat(float[]... parts) {
        int length = 0;
        for (float[] part : parts) length += part.length;
        float[] result = new float[length];
        int offset = 0;
        for (float[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    private static void assertPendingRunOverflowFlush(
            SectorContext sector, MutableLocation remote) throws Exception {
        PrepatcherConfig original = config(true);
        installConfig(config(true, 2, false));
        resetSchedulerGeneration();
        sector.currentLocation = null;
        MutableMarket market = new MutableMarket("run-overflow", remote.proxy, false, false);
        market.emulateMarketWrapper = true;
        runRenderBatch(market, 1, 0.5f, 0);
        market.clearDeliveries();
        long before = metric("PENDING_RUN_OVERFLOW_FLUSHES");

        StarsectorPrepatcherHooks.marketSchedulerFastForwardIterationChanged(false);
        beginSyntheticSchedulerTick();
        try {
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.011f, 0);
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.022f, 0);
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.033f, 0);
        } finally {
            endSyntheticFastForwardTick();
        }

        require(metric("PENDING_RUN_OVERFLOW_FLUSHES") == before + 1L,
                "pending-run limit did not flush the existing batch");
        require(market.calls == 1 && market.batchObservations.size() == 1,
                "run overflow did not use one coalesced batch delivery");
        BatchObservation first = market.batchObservations.get(0);
        require(first.totalSteps == 2 && first.amounts.size() == 2
                        && rawEquals(first.amounts.get(0), 0.011f)
                        && rawEquals(first.amounts.get(1), 0.022f),
                "run overflow lost or averaged exact history");
        Object state = schedulerState(market.proxy);
        require(intField(state, "pendingSteps") == 1,
                "current step was not retained after run-overflow flush");
        Object[] remaining = ((java.util.ArrayDeque<?>) field(state, "pendingRuns")).toArray();
        require(remaining.length == 1
                        && rawEquals(floatField(remaining[0], "amount"), 0.033f),
                "run-overflow remainder is incorrect");
        StarsectorPrepatcherHooks.flushMarketSchedulerBeforeSave();
        StarsectorPrepatcherHooks.marketSchedulerFastForwardIterationChanged(false);
        installConfig(original);
    }

    private static void assertConstructionModeAndMutationBoundary(
            SectorContext sector, MutableLocation remote) throws Exception {
        installConfig(config(true, 32, false));
        resetSchedulerGeneration();
        sector.currentLocation = null;
        MutableMarket market = new MutableMarket("construction-mode", remote.proxy, false, false);
        market.emulateMarketWrapper = true;
        runRenderBatch(market, 1, 0.5f, 0);
        market.clearDeliveries();

        StarsectorPrepatcherHooks.marketSchedulerFastForwardIterationChanged(false);
        beginSyntheticSchedulerTick();
        try {
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.10f, 0);
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.20f, 0);
        } finally {
            endSyntheticFastForwardTick();
        }
        MutableIndustry industry = new MutableIndustry();
        StarsectorPrepatcherHooks.flushPendingMarketBeforeMutation(market.proxy);
        industry.setRawAndReportedBuilding(true);
        market.industries.add(industry);

        long entriesBefore = metric("CONSTRUCTION_MODE_ENTRIES");
        long fullRateBefore = metric("CONSTRUCTION_FULL_RATE_CALLS");
        beginSyntheticSchedulerTick();
        try {
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.30f, 0);
        } finally {
            endSyntheticFastForwardTick();
        }
        require(market.deliveredAmounts.size() == 3
                        && rawEquals(market.deliveredAmounts.get(0), 0.10f)
                        && rawEquals(market.deliveredAmounts.get(1), 0.20f)
                        && rawEquals(market.deliveredAmounts.get(2), 0.30f),
                "construction transition did not exact-replay old time before current step");
        require(market.batchVisibility.stream().noneMatch(Boolean::booleanValue),
                "construction exact replay incorrectly exposed coalesced context");
        require(metric("CONSTRUCTION_MODE_ENTRIES") == entriesBefore + 1L
                        && metric("CONSTRUCTION_FULL_RATE_CALLS") == fullRateBefore + 1L,
                "construction mode metrics are incorrect");

        market.clearDeliveries();
        beginSyntheticSchedulerTick();
        try {
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.40f, 0);
        } finally {
            endSyntheticFastForwardTick();
        }
        require(market.calls == 1 && rawEquals(market.deliveredAmounts.get(0), 0.40f),
                "active construction was not kept full-rate");

        StarsectorPrepatcherHooks.flushPendingMarketBeforeMutation(market.proxy);
        industry.setRawAndReportedBuilding(false);
        long exitsBefore = metric("CONSTRUCTION_MODE_EXITS");
        beginSyntheticSchedulerTick();
        try {
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.50f, 0);
        } finally {
            endSyntheticFastForwardTick();
        }
        require(metric("CONSTRUCTION_MODE_EXITS") == exitsBefore + 1L,
                "market did not leave construction mode");

        // Exact mutation boundary through the queue owner registry.
        resetSchedulerGeneration();
        market = new MutableMarket("construction-boundary", remote.proxy, false, false);
        market.emulateMarketWrapper = true;
        runRenderBatch(market, 1, 0.5f, 0);
        market.clearDeliveries();
        StarsectorPrepatcherHooks.marketSchedulerFastForwardIterationChanged(false);
        beginSyntheticSchedulerTick();
        try {
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.06f, 0);
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.07f, 0);
        } finally {
            endSyntheticFastForwardTick();
        }
        StarsectorPrepatcherHooks.registerConstructionQueueOwner(
                market.constructionQueue, market.proxy);
        long barriersBefore = metric("CONSTRUCTION_BOUNDARY_EXACT_REPLAYS");
        StarsectorPrepatcherHooks.flushPendingConstructionQueueBeforeMutation(
                market.constructionQueue);
        require(market.deliveredAmounts.size() == 2
                        && rawEquals(market.deliveredAmounts.get(0), 0.06f)
                        && rawEquals(market.deliveredAmounts.get(1), 0.07f),
                "construction mutation boundary did not exact-replay pending steps");
        require(metric("CONSTRUCTION_BOUNDARY_EXACT_REPLAYS") == barriersBefore + 1L,
                "construction mutation boundary was not counted");
        StarsectorPrepatcherHooks.marketSchedulerFastForwardIterationChanged(false);
    }

    private static void assertConstructionPolicyDirtyAudit(
            SectorContext sector, MutableLocation remote) throws Exception {
        installConfig(config(true, 32, false));
        resetSchedulerGeneration();
        sector.currentLocation = null;
        MutableMarket market = new MutableMarket(
                "construction-dirty-audit", remote.proxy, false, false);
        market.emulateMarketWrapper = true;

        beginSyntheticSchedulerTick();
        try {
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.01f, 0);
            for (int i = 0; i < 50; i++) {
                StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.01f, 0);
            }
        } finally {
            endSyntheticFastForwardTick();
        }
        require(market.constructionQueueReads == 1 && market.industryListReads == 1,
                "construction detector scanned on every simulation input: queue="
                        + market.constructionQueueReads + " industries="
                        + market.industryListReads);

        StarsectorPrepatcherHooks.flushPendingMarketBeforeMutation(market.proxy);
        int queueBefore = market.constructionQueueReads;
        int industriesBefore = market.industryListReads;
        beginSyntheticSchedulerTick();
        try {
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.02f, 0);
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.02f, 0);
        } finally {
            endSyntheticFastForwardTick();
        }
        require(market.constructionQueueReads == queueBefore + 1
                        && market.industryListReads == industriesBefore + 1,
                "dirty construction state was not audited exactly once");
        StarsectorPrepatcherHooks.marketSchedulerFastForwardIterationChanged(false);
    }

    private static void assertConstructionReasonCounters(
            SectorContext sector, MutableLocation remote) throws Exception {
        installConfig(config(true, 32, false));
        resetSchedulerGeneration();
        sector.currentLocation = null;

        long queueBefore = metric("CONSTRUCTION_DETECTED_QUEUE_NON_EMPTY");
        long buildingBefore = metric("CONSTRUCTION_DETECTED_INDUSTRY_BUILDING");
        long upgradingBefore = metric("CONSTRUCTION_DETECTED_INDUSTRY_UPGRADING");
        long reportedWithoutRawBefore = metric(
                "CONSTRUCTION_DETECTED_REPORTED_BUILDING_WITHOUT_RAW");
        long multipleBefore = metric("CONSTRUCTION_DETECTED_MULTIPLE_REASONS");
        long failureBefore = metric("CONSTRUCTION_DETECTED_PROBE_FAILURE");
        long scansBefore = metric("CONSTRUCTION_SCANS");
        assertConstructionBuildingReasonClassification();

        MutableMarket queueMarket = new MutableMarket(
                "construction-reason-queue", remote.proxy, false, false);
        queueMarket.constructionQueue.addToEnd("queued_industry", 1);

        MutableMarket buildingMarket = new MutableMarket(
                "construction-reason-building", remote.proxy, false, false);
        MutableIndustry building = new MutableIndustry();
        building.setRawAndReportedBuilding(true);
        buildingMarket.industries.add(building);

        MutableMarket upgradingMarket = new MutableMarket(
                "construction-reason-upgrading", remote.proxy, false, false);
        MutableIndustry upgrading = new MutableIndustry();
        upgrading.upgrading = true;
        upgradingMarket.industries.add(upgrading);

        MutableMarket reportedWithoutRawMarket = new MutableMarket(
                "construction-reason-reported-without-raw",
                remote.proxy, false, false);
        MutableIndustry reportedWithoutRaw = new MutableIndustry();
        reportedWithoutRaw.setReportedBuilding(true);
        reportedWithoutRawMarket.industries.add(reportedWithoutRaw);

        MutableMarket multipleMarket = new MutableMarket(
                "construction-reason-multiple", remote.proxy, false, false);
        multipleMarket.constructionQueue.addToEnd("queued_multiple", 1);
        MutableIndustry both = new MutableIndustry();
        both.setRawAndReportedBuilding(true);
        both.upgrading = true;
        multipleMarket.industries.add(both);

        MutableMarket failureMarket = new MutableMarket(
                "construction-reason-failure", remote.proxy, false, false);
        failureMarket.throwConstructionProbe = true;

        MutableMarket[] markets = {
                queueMarket, buildingMarket, upgradingMarket, reportedWithoutRawMarket,
                multipleMarket, failureMarket
        };
        for (MutableMarket market : markets) {
            beginSyntheticSchedulerTick();
            try {
                StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.01f, 0);
            } finally {
                endSyntheticSchedulerTick();
            }
        }

        Object upgradingState = schedulerState(upgradingMarket.proxy);
        require(!(Boolean) field(upgradingState, "constructionFullRate")
                        && intField(upgradingState, "constructionReasonMask") == 4,
                "upgrade-only diagnostic signal incorrectly enabled full-rate mode");
        Object reportedWithoutRawState = schedulerState(reportedWithoutRawMarket.proxy);
        require(!(Boolean) field(reportedWithoutRawState, "constructionFullRate")
                        && intField(reportedWithoutRawState, "constructionReasonMask") == 128,
                "reported BaseIndustry building without raw state enabled full-rate mode");
        Object multipleState = schedulerState(multipleMarket.proxy);
        require((Boolean) field(multipleState, "constructionFullRate")
                        && intField(multipleState, "constructionReasonMask") == 7,
                "active construction reasons were lost when upgrading became diagnostic-only");

        require(metric("CONSTRUCTION_SCANS") >= scansBefore + markets.length,
                "construction reason scenarios did not run one scan per market");
        require(metric("CONSTRUCTION_DETECTED_QUEUE_NON_EMPTY") >= queueBefore + 2L,
                "queue reason counter did not include queue-only and multi-reason markets");
        require(metric("CONSTRUCTION_DETECTED_INDUSTRY_BUILDING") >= buildingBefore + 2L,
                "building reason counter did not include building and multi-reason markets");
        require(metric("CONSTRUCTION_DETECTED_INDUSTRY_UPGRADING") >= upgradingBefore + 2L,
                "upgrading reason counter did not include upgrading and multi-reason markets");
        require(metric("CONSTRUCTION_DETECTED_REPORTED_BUILDING_WITHOUT_RAW")
                        >= reportedWithoutRawBefore + 1L,
                "reported-building-without-raw reason was not counted");
        require(metric("CONSTRUCTION_DETECTED_MULTIPLE_REASONS") >= multipleBefore + 1L,
                "multiple construction reasons were not counted");
        require(metric("CONSTRUCTION_DETECTED_PROBE_FAILURE") >= failureBefore + 1L,
                "construction probe failure was not counted");

        Object gauges = invokePrivateStatic("marketSchedulerGaugeSnapshot");
        require(recordInt(gauges, "constructionMarketsQueueNonEmpty") >= 2,
                "queue reason gauge is incomplete");
        require(recordInt(gauges, "constructionMarketsIndustryBuilding") >= 2,
                "building reason gauge is incomplete");
        require(recordInt(gauges, "constructionMarketsIndustryUpgrading") >= 2,
                "upgrading reason gauge is incomplete");
        require(recordInt(gauges,
                        "constructionMarketsReportedBuildingWithoutRaw") >= 1,
                "reported-building-without-raw gauge is incomplete");
        require(recordInt(gauges, "constructionMarketsMultipleReasons") >= 1,
                "multiple-reason gauge is incomplete");
        require(recordInt(gauges, "constructionMarketsUncertain") >= 1,
                "probe-failure market was not included in uncertain gauge");
    }

    private static void assertConstructionBuildingReasonClassification() throws Exception {
        Method method = StarsectorPrepatcherHooks.class.getDeclaredMethod(
                "constructionBuildingReason", boolean.class, Boolean.class);
        method.setAccessible(true);
        require((Integer) method.invoke(null, true, null) == 2,
                "non-BaseIndustry isBuilding fallback was not preserved");
        require((Integer) method.invoke(null, false, null) == 0,
                "inactive non-BaseIndustry was classified as building");
        require((Integer) method.invoke(null, true, Boolean.FALSE) == 128,
                "virtual BaseIndustry building was not classified as diagnostic-only");
        require((Integer) method.invoke(null, false, Boolean.TRUE) == 2,
                "raw BaseIndustry building state was not authoritative");
    }

    private static void assertConstructionDiagnosticsCsv(
            SectorContext sector, MutableLocation remote) throws Exception {
        Path root = Files.createTempDirectory("prepatcher-construction-diagnostics-");
        PrepatcherConfig original = config(true, 32, false);
        try {
            PrepatcherConfig diagnosticsConfig = config(true, 32, false, true, 1);
            StarsectorPrepatcherRuntimeBridge.configure(diagnosticsConfig, root);
            resetSchedulerGeneration();
            sector.currentLocation = null;

            long droppedBefore = metric("CONSTRUCTION_DIAGNOSTIC_SAMPLES_DROPPED");
            for (int i = 0; i < 2; i++) {
                MutableMarket market = new MutableMarket(
                        "diagnostic-building-" + i, remote.proxy, false, false);
                MutableIndustry industry = new MutableIndustry();
                industry.setRawAndReportedBuilding(true);
                market.industries.add(industry);
                beginSyntheticSchedulerTick();
                try {
                    StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.01f, 0);
                } finally {
                    endSyntheticSchedulerTick();
                }
            }

            MutableMarket transitionMarket = new MutableMarket(
                    "diagnostic-transition", remote.proxy, false, false);
            MutableIndustry transitionIndustry = new MutableIndustry();
            transitionIndustry.upgrading = true;
            transitionMarket.industries.add(transitionIndustry);
            beginSyntheticSchedulerTick();
            try {
                StarsectorPrepatcherHooks.advanceMarketScheduled(
                        transitionMarket.proxy, 0.01f, 0);
            } finally {
                endSyntheticSchedulerTick();
            }
            require(!(Boolean) field(schedulerState(transitionMarket.proxy),
                            "constructionFullRate"),
                    "diagnostic upgrade-only market entered full-rate mode");
            StarsectorPrepatcherHooks.flushPendingMarketBeforeMutation(
                    transitionMarket.proxy);
            transitionIndustry.setReportedBuilding(true);
            beginSyntheticSchedulerTick();
            try {
                StarsectorPrepatcherHooks.advanceMarketScheduled(
                        transitionMarket.proxy, 0.01f, 0);
            } finally {
                endSyntheticSchedulerTick();
            }
            require(!(Boolean) field(schedulerState(transitionMarket.proxy),
                            "constructionFullRate"),
                    "reported-building-without-raw transition entered full-rate mode");

            MutableMarket rawTransitionMarket = new MutableMarket(
                    "diagnostic-raw-transition", remote.proxy, false, false);
            MutableIndustry rawTransitionIndustry = new MutableIndustry();
            rawTransitionIndustry.upgrading = true;
            rawTransitionMarket.industries.add(rawTransitionIndustry);
            beginSyntheticSchedulerTick();
            try {
                StarsectorPrepatcherHooks.advanceMarketScheduled(
                        rawTransitionMarket.proxy, 0.01f, 0);
            } finally {
                endSyntheticSchedulerTick();
            }
            StarsectorPrepatcherHooks.flushPendingMarketBeforeMutation(
                    rawTransitionMarket.proxy);
            rawTransitionIndustry.setRawAndReportedBuilding(true);
            beginSyntheticSchedulerTick();
            try {
                StarsectorPrepatcherHooks.advanceMarketScheduled(
                        rawTransitionMarket.proxy, 0.01f, 0);
            } finally {
                endSyntheticSchedulerTick();
            }
            require((Boolean) field(schedulerState(rawTransitionMarket.proxy),
                            "constructionFullRate"),
                    "raw BaseIndustry building transition did not enter full-rate mode");

            Object diagnostics = staticField("CONSTRUCTION_DIAGNOSTICS");
            require(diagnostics != null, "construction diagnostics were not initialized");
            Method writePending = diagnostics.getClass().getDeclaredMethod("writePending");
            writePending.setAccessible(true);
            writePending.invoke(diagnostics);

            Path directory = Path.of(System.getProperty(
                    "starsector.prepatcher.marketConstructionDiagnosticsDir"));
            String csv = Files.readString(directory.resolve("samples.csv"),
                    StandardCharsets.UTF_8);
            require(csv.contains("market_identity_hash,market_id,market_name,trigger")
                            && csv.contains("sample_bucket,transition,reason_mask,reasons")
                            && csv.contains("building_industry_index,building_industry_id")
                            && csv.contains("upgrading_industry_index,upgrading_industry_id")
                            && csv.contains("effective_is_building")
                            && csv.contains("reported_building_without_raw_industry_index")
                            && csv.contains("building_raw_building_field,building_build_progress")
                            && csv.contains("INDUSTRY_BUILDING")
                            && csv.contains("INDUSTRY_UPGRADING_WITHOUT_BUILDING")
                            && csv.contains("REPORTED_BUILDING_WITHOUT_RAW")
                            && csv.contains("TRANSITION_4_TO_132")
                            && csv.contains("4->132")
                            && csv.contains("TRANSITION_4_TO_6")
                            && csv.contains("4->6")
                            && csv.contains("diagnostic-building-")
                            && csv.contains("diagnostic-transition")
                            && csv.contains("diagnostic-raw-transition"),
                    "construction diagnostics CSV is incomplete: " + csv);
            require(dataRows(csv) == 4,
                    "per-bucket sample limit did not bound diagnostic CSV: " + csv);
            require(metric("CONSTRUCTION_DIAGNOSTIC_SAMPLES_DROPPED")
                            >= droppedBefore + 2L,
                    "diagnostic sample overflow was not counted");
        } finally {
            installConfig(original);
            setStaticField("CONSTRUCTION_DIAGNOSTICS", null);
            deleteRecursively(root);
        }
    }

    private static void assertSaveExactReplayMode(
            SectorContext sector, MutableLocation remote) throws Exception {
        installConfig(config(true, 32, true));
        resetSchedulerGeneration();
        sector.currentLocation = null;
        MutableMarket market = new MutableMarket("save-exact", remote.proxy, false, false);
        market.emulateMarketWrapper = true;
        runRenderBatch(market, 1, 0.5f, 0);
        market.clearDeliveries();

        StarsectorPrepatcherHooks.marketSchedulerFastForwardIterationChanged(false);
        beginSyntheticSchedulerTick();
        try {
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.016f, 0);
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.016f, 0);
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.100f, 0);
        } finally {
            endSyntheticFastForwardTick();
        }
        long exactBefore = metric("SAVE_EXACT_REPLAY_MARKETS");
        long stepsBefore = metric("SAVE_EXACT_REPLAY_STEPS");
        StarsectorPrepatcherHooks.flushMarketSchedulerBeforeSave();
        require(market.deliveredAmounts.size() == 3
                        && rawEquals(market.deliveredAmounts.get(0), 0.016f)
                        && rawEquals(market.deliveredAmounts.get(1), 0.016f)
                        && rawEquals(market.deliveredAmounts.get(2), 0.100f),
                "exact save mode did not preserve step sequence");
        require(market.batchVisibility.stream().noneMatch(Boolean::booleanValue),
                "exact save replay exposed a coalesced batch context");
        require(metric("SAVE_EXACT_REPLAY_MARKETS") == exactBefore + 1L
                        && metric("SAVE_EXACT_REPLAY_STEPS") == stepsBefore + 3L,
                "exact save replay metrics are incorrect");
        StarsectorPrepatcherHooks.marketSchedulerFastForwardIterationChanged(false);
        installConfig(config(true));
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static int intField(Object owner, String name) throws Exception {
        return (Integer) field(owner, name);
    }

    private static float floatField(Object owner, String name) throws Exception {
        return (Float) field(owner, name);
    }

    private static boolean rawEquals(float left, float right) {
        return Float.floatToRawIntBits(left) == Float.floatToRawIntBits(right);
    }

    private static void assertSchedulerBaselineDump(MutableLocation remote) throws Exception {
        resetSchedulerGeneration();
        Path root = Files.createTempDirectory("prepatcher-market-baseline-");
        Field modRootField = StarsectorPrepatcherHooks.class.getDeclaredField("modRoot");
        modRootField.setAccessible(true);
        Object previousRoot = modRootField.get(null);
        modRootField.set(null, root);
        try {
            MutableMarket market = new MutableMarket("baseline-dump-market", remote.proxy,
                    false, false);

            beginSyntheticSchedulerTick();
            try {
                StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.5f, 0);
            } finally {
                endSyntheticSchedulerTick();
            }
            require(market.calls == 1, "baseline setup did not deliver initial callback");

            beginNonDueTick(market.proxy, 4);
            try {
                StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.25f, 0);
            } finally {
                endSyntheticSchedulerTick();
            }
            require(pendingAmount(market.proxy) > 0f,
                    "baseline setup did not retain pending scheduler debt");

            String output = StarsectorPrepatcherRuntimeBridge
                    .dumpMarketSchedulerBaseline("runtime-test");
            require(output != null && !output.isBlank(),
                    "scheduler baseline dump returned no output path");
            Path csv = Path.of(output);
            require(Files.isRegularFile(csv), "scheduler baseline CSV was not written");
            String content = Files.readString(csv, StandardCharsets.UTF_8);
            require(content.contains("baseline-dump-market"),
                    "scheduler baseline omitted test market");
            require(content.contains("delivered_callbacks"),
                    "scheduler baseline omitted delivered callback column");
            require(content.contains("pending_steps"),
                    "scheduler baseline omitted pending debt column");
            Path json = csv.resolveSibling(csv.getFileName().toString().replace(".csv", ".json"));
            require(Files.isRegularFile(json), "scheduler baseline summary JSON was not written");
        } finally {
            modRootField.set(null, previousRoot);
        }
    }

    private static void assertWeakStateDoesNotRetainMarket(
            SectorContext sector, MutableLocation remote) throws Exception {
        resetSchedulerGeneration();
        sector.currentLocation = null;
        sector.interactionMarket = null;
        WeakReference<MarketAPI> reference = createTemporaryScheduledMarket(remote);
        require(schedulerStateSize() == 1,
                "temporary market did not create one scheduler state");
        for (int i = 0; i < 30 && reference.get() != null; i++) {
            System.gc();
            System.runFinalization();
            byte[] pressure = new byte[256 * 1024];
            pressure[0] = 1;
            Thread.sleep(10L);
        }
        require(reference.get() == null,
                "scheduler or test retained the temporary market strongly");
        require(schedulerStateSize() == 0,
                "weak scheduler map did not expunge the collected market");
    }

    private static WeakReference<MarketAPI> createTemporaryScheduledMarket(
            MutableLocation remote) throws Exception {
        MutableMarket market = new MutableMarket("temporary", remote.proxy, false, false);
        beginSyntheticSchedulerTick();
        try {
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.5f, 0);
        } finally {
            endSyntheticSchedulerTick();
        }
        return new WeakReference<>(market.proxy);
    }

    private static void warmAndDefer(MutableMarket market, float deferred, int source)
            throws Exception {
        beginSyntheticSchedulerTick();
        try {
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 0.5f, source);
        } finally {
            endSyntheticSchedulerTick();
        }
        beginNonDueTick(market.proxy, 4);
        try {
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, deferred, source);
        } finally {
            endSyntheticSchedulerTick();
        }
    }

    /** Opens, but does not close, a deterministic non-due render batch. */
    private static void beginNonDueTick(MarketAPI market, int interval) throws Exception {
        Object state = schedulerState(market);
        require(state != null, "test market has no scheduler state");
        Field nextDue = state.getClass().getDeclaredField("nextDueBatch");
        nextDue.setAccessible(true);
        nextDue.setLong(state, schedulerFrame() + Math.max(2, interval));
        beginSyntheticSchedulerTick();
        require(schedulerFrame() < nextDue.getLong(state),
                "test failed to open a non-due render batch");
    }


    private static Object schedulerState(MarketAPI market) throws Exception {
        Field field = StarsectorPrepatcherHooks.class.getDeclaredField("MARKET_SCHEDULER_STATES");
        field.setAccessible(true);
        Object map = field.get(null);
        Method get = map.getClass().getDeclaredMethod("get", Object.class);
        get.setAccessible(true);
        return get.invoke(map, market);
    }

    private static float pendingAmount(MarketAPI market) throws Exception {
        Object state = schedulerState(market);
        if (state == null) return 0f;
        Field field = state.getClass().getDeclaredField("pendingAmount");
        field.setAccessible(true);
        return field.getFloat(state);
    }

    private static int schedulerStateSize() throws Exception {
        Field field = StarsectorPrepatcherHooks.class.getDeclaredField("MARKET_SCHEDULER_STATES");
        field.setAccessible(true);
        Object map = field.get(null);
        Method size = map.getClass().getDeclaredMethod("size");
        size.setAccessible(true);
        return (Integer) size.invoke(map);
    }

    private static void assertDisabledFallback(
            SectorContext sector, MutableLocation remote) throws Exception {
        installConfig(config(false));
        MutableMarket market = new MutableMarket("disabled", remote.proxy, false, false);
        for (int i = 0; i < 5; i++) {
            beginSyntheticSchedulerTick();
            try {
                StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 1f, 0);
            } finally {
                endSyntheticSchedulerTick();
            }
        }
        require(market.calls == 5 && close(market.totalAmount, 5f),
                "disabled scheduler did not execute exact immediate fallback");

        MutableMarket condition = new MutableMarket(
                "condition-disabled", remote.proxy, false, false);
        for (int i = 0; i < 5; i++) {
            beginSyntheticSchedulerTick();
            try {
                StarsectorPrepatcherHooks.advanceMarketScheduled(
                        condition.proxy, 0.5f, 1);
            } finally {
                endSyntheticSchedulerTick();
            }
        }
        require(condition.calls == 5 && close(condition.totalAmount, 2.5f),
                "disabled unified scheduler entity source did not execute immediate fallback");
        installConfig(config(true));
        resetSchedulerGeneration();
    }

    private static void beginSyntheticSchedulerTick() {
        StarsectorPrepatcherHooks.beginMarketSchedulerTick();
    }

    private static void endSyntheticSchedulerTick() {
        StarsectorPrepatcherHooks.endMarketSchedulerTick();
        StarsectorPrepatcherHooks.marketSchedulerFastForwardIterationChanged(false);
    }

    private static void endSyntheticFastForwardTick() {
        StarsectorPrepatcherHooks.endMarketSchedulerTick();
        StarsectorPrepatcherHooks.marketSchedulerFastForwardIterationChanged(true);
    }

    private static PrepatcherConfig config(boolean enabled) throws Exception {
        return config(enabled, 32, false);
    }

    private static PrepatcherConfig config(
            boolean enabled, int maxPendingRuns, boolean exactReplayBeforeSave) throws Exception {
        return config(enabled, maxPendingRuns, exactReplayBeforeSave, false, 32);
    }

    private static PrepatcherConfig config(
            boolean enabled, int maxPendingRuns, boolean exactReplayBeforeSave,
            boolean constructionDiagnostics, int maxSamplesPerReason) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("patch.marketScheduler", Boolean.toString(enabled));
        properties.setProperty("market.scheduler.batches", "4");
        properties.setProperty("market.scheduler.hiddenBatches", "8");
        properties.setProperty("market.scheduler.hot.currentLocation", "true");
        properties.setProperty("market.scheduler.hot.playerOwned", "true");
        properties.setProperty("market.scheduler.hot.interaction", "true");
        properties.setProperty("market.scheduler.policyAuditBatches", "1");
        properties.setProperty("market.scheduler.perSimulationTickMemoryKey",
                "$starsectorPrepatcher_perSimulationTickMarket");
        properties.setProperty("market.remote.maxPendingRuns", Integer.toString(maxPendingRuns));
        properties.setProperty("market.remote.exactReplayBeforeSave",
                Boolean.toString(exactReplayBeforeSave));
        properties.setProperty("observer.marketConstructionDiagnostics",
                Boolean.toString(constructionDiagnostics));
        properties.setProperty("observer.marketConstructionDiagnosticsMaxSamplesPerReason",
                Integer.toString(maxSamplesPerReason));
        properties.setProperty("logging.statsIntervalSeconds", "0");
        Constructor<PrepatcherConfig> constructor =
                PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);
        PrepatcherConfig config = constructor.newInstance(properties);
        require(config.marketScheduler == enabled
                        && config.marketSchedulerBatches == 4
                        && config.marketSchedulerHiddenBatches == 8
                        && config.marketSchedulerMaxPendingRuns == maxPendingRuns
                        && config.marketSchedulerExactReplayBeforeSave == exactReplayBeforeSave
                        && config.marketConstructionDiagnostics == constructionDiagnostics
                        && config.marketConstructionDiagnosticsMaxSamplesPerReason
                        == maxSamplesPerReason,
                "scheduler test configuration was not parsed correctly");
        return config;
    }

    private static Object invokePrivateStatic(String methodName) throws Exception {
        Method method = StarsectorPrepatcherHooks.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(null);
    }

    private static int recordInt(Object record, String accessor) throws Exception {
        Method method = record.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return (Integer) method.invoke(record);
    }

    private static Object staticField(String fieldName) throws Exception {
        Field field = StarsectorPrepatcherHooks.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void setStaticField(String fieldName, Object value) throws Exception {
        Field field = StarsectorPrepatcherHooks.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static int dataRows(String csv) {
        int rows = 0;
        for (String line : csv.split("\\R")) {
            if (!line.isBlank()) rows++;
        }
        return Math.max(0, rows - 1);
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted((left, right) -> right.compareTo(left))
                    .forEach(path -> {
                        try { Files.deleteIfExists(path); }
                        catch (Exception ignored) {}
                    });
        }
    }

    private static long metric(String fieldName) throws Exception {
        Field field = StarsectorPrepatcherHooks.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return ((LongAdder) field.get(null)).sum();
    }

    private static void installConfig(PrepatcherConfig config) throws Exception {
        Field field = StarsectorPrepatcherHooks.class.getDeclaredField("config");
        field.setAccessible(true);
        field.set(null, config);
    }

    private static void activateCampaign(Object engine) {
        long transition = StarsectorPrepatcherHooks.beginCampaignEngineChange(engine);
        require(transition >= 0L, "campaign lifecycle did not open scheduler generation");
        StarsectorPrepatcherHooks.completeCampaignEngineChange(engine, transition);
    }

    private static void resetSchedulerGeneration() {
        Object engine = new Object();
        long transition = StarsectorPrepatcherHooks.beginCampaignEngineChange(engine);
        require(transition >= 0L, "scheduler reset did not open new generation");
        StarsectorPrepatcherHooks.completeCampaignEngineChange(engine, transition);
    }

    private static long schedulerFrame() throws Exception {
        Field field = StarsectorPrepatcherHooks.class.getDeclaredField("marketSchedulerRenderBatch");
        field.setAccessible(true);
        return field.getLong(null);
    }

    private static boolean close(float a, float b) {
        return Math.abs(a - b) <= 0.0001f * Math.max(1f, Math.max(Math.abs(a), Math.abs(b)));
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args, String label) {
        return switch (method.getName()) {
            case "toString" -> label;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> null;
        };
    }

    private static Object primitiveDefault(Class<?> type) {
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

    private static final class MutableLocation implements InvocationHandler {
        final String id;
        final LocationAPI proxy;

        MutableLocation(String id) {
            this.id = id;
            this.proxy = (LocationAPI) Proxy.newProxyInstance(
                    LocationAPI.class.getClassLoader(), new Class<?>[] {LocationAPI.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args, "Location[" + id + "]");
            }
            if (method.getName().equals("getId")) return id;
            return primitiveDefault(method.getReturnType());
        }
    }

    private static final class MutableMemory implements InvocationHandler {
        boolean fullRate;
        int failuresRemaining;
        final MemoryAPI proxy;

        MutableMemory() {
            this.proxy = (MemoryAPI) Proxy.newProxyInstance(
                    MemoryAPI.class.getClassLoader(), new Class<?>[] {MemoryAPI.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args, "Memory");
            }
            if (method.getName().equals("getBoolean")) {
                if (failuresRemaining > 0) {
                    failuresRemaining--;
                    throw new IllegalStateException("synthetic memory policy failure");
                }
                return fullRate;
            }
            return primitiveDefault(method.getReturnType());
        }
    }

    private static final class BatchObservation {
        final ArrayList<Float> amounts = new ArrayList<>();
        final ArrayList<Integer> repeats = new ArrayList<>();
        int totalSteps;
    }

    private static final class MutableIndustry extends BaseIndustry {
        boolean reportedBuilding;
        boolean upgrading;

        void setRawAndReportedBuilding(boolean value) {
            super.building = value;
            reportedBuilding = value;
        }

        void setReportedBuilding(boolean value) {
            reportedBuilding = value;
        }

        @Override
        public void apply() {}

        @Override
        public void unapply() {}

        @Override
        public boolean isBuilding() {
            return reportedBuilding;
        }

        @Override
        public boolean isUpgrading() {
            return upgrading;
        }
    }

    private static final class MutableMarket implements InvocationHandler {
        final String id;
        final LocationAPI location;
        final boolean playerOwned;
        final boolean hidden;
        boolean inEconomy;
        final MutableMemory memory = new MutableMemory();
        final ConstructionQueue constructionQueue = new ConstructionQueue();
        final ArrayList<Industry> industries = new ArrayList<>();
        final MarketAPI proxy;
        final ArrayList<Integer> advanceFrames = new ArrayList<>();
        final ArrayList<Float> deliveredAmounts = new ArrayList<>();
        final ArrayList<Boolean> batchVisibility = new ArrayList<>();
        final ArrayList<BatchObservation> batchObservations = new ArrayList<>();
        MutableMarket nestedDirectMarket;
        boolean outerContextRestored;
        boolean emulateMarketWrapper;
        int calls;
        float totalAmount;
        boolean reenterOnce;
        boolean reenterPlanetOnce;
        boolean throwBeforeApplyOnce;
        boolean throwAfterApplyOnce;
        boolean throwConstructionProbe;
        boolean inHandler;
        int constructionQueueReads;
        int industryListReads;

        MutableMarket(String id, LocationAPI location, boolean playerOwned, boolean hidden) {
            this.id = id;
            this.location = location;
            this.playerOwned = playerOwned;
            this.hidden = hidden;
            this.proxy = (MarketAPI) Proxy.newProxyInstance(
                    MarketAPI.class.getClassLoader(), new Class<?>[] {MarketAPI.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args, "Market[" + id + "]");
            }
            return switch (method.getName()) {
                case "getId" -> id;
                case "getName" -> id + " name";
                case "getContainingLocation" -> location;
                case "isPlayerOwned" -> playerOwned;
                case "isHidden" -> hidden;
                case "isInEconomy" -> inEconomy;
                case "getMemoryWithoutUpdate" -> memory.proxy;
                case "getConstructionQueue" -> {
                    constructionQueueReads++;
                    if (throwConstructionProbe) {
                        throw new IllegalStateException("synthetic construction probe failure");
                    }
                    yield constructionQueue;
                }
                case "getIndustries" -> {
                    industryListReads++;
                    yield industries;
                }
                case "advance" -> {
                    float amount = (Float) args[0];
                    if (emulateMarketWrapper) {
                        StarsectorPrepatcherHooks.beginMarketAdvanceInvocation(this.proxy);
                    }
                    try {
                        boolean visible = StarsectorPrepatcherHooks.hasCurrentMarketAdvanceBatch(
                                this.proxy);
                        batchVisibility.add(visible);
                        if (visible) {
                            BatchObservation observation = new BatchObservation();
                            int runCount = StarsectorPrepatcherHooks
                                    .currentMarketAdvanceBatchRunCount(this.proxy);
                            for (int run = 0; run < runCount; run++) {
                                float runAmount = StarsectorPrepatcherHooks
                                        .currentMarketAdvanceBatchRunAmount(this.proxy, run);
                                int repeats = StarsectorPrepatcherHooks
                                        .currentMarketAdvanceBatchRunRepeats(this.proxy, run);
                                observation.amounts.add(runAmount);
                                observation.repeats.add(repeats);
                                observation.totalSteps += repeats;
                            }
                            batchObservations.add(observation);
                        }
                        calls++;
                        advanceFrames.add(currentSchedulerFrame());
                        deliveredAmounts.add(amount);
                        if (throwBeforeApplyOnce) {
                            throwBeforeApplyOnce = false;
                            throw new IllegalStateException("synthetic failure before apply");
                        }
                        totalAmount += amount;
                        if (throwAfterApplyOnce) {
                            throwAfterApplyOnce = false;
                            throw new IllegalStateException("synthetic failure after apply");
                        }
                        if (nestedDirectMarket != null && !inHandler) {
                            inHandler = true;
                            try {
                                boolean before = StarsectorPrepatcherHooks
                                        .hasCurrentMarketAdvanceBatch(this.proxy);
                                nestedDirectMarket.proxy.advance(0.031f);
                                boolean after = StarsectorPrepatcherHooks
                                        .hasCurrentMarketAdvanceBatch(this.proxy);
                                outerContextRestored = before && after;
                            } finally {
                                inHandler = false;
                            }
                        }
                        if ((reenterOnce || reenterPlanetOnce) && !inHandler) {
                            boolean planet = reenterPlanetOnce;
                            reenterOnce = false;
                            reenterPlanetOnce = false;
                            inHandler = true;
                            try {
                                StarsectorPrepatcherHooks.advanceMarketScheduled(
                                        this.proxy, 0.5f, planet ? 1 : 0);
                            } finally {
                                inHandler = false;
                            }
                        }
                        yield null;
                    } finally {
                        if (emulateMarketWrapper) {
                            StarsectorPrepatcherHooks.endMarketAdvanceInvocation();
                        }
                    }
                }
                default -> primitiveDefault(method.getReturnType());
            };
        }

        void clearDeliveries() {
            calls = 0;
            totalAmount = 0f;
            advanceFrames.clear();
            deliveredAmounts.clear();
            batchVisibility.clear();
            batchObservations.clear();
            outerContextRestored = false;
            nestedDirectMarket = null;
        }

        private int currentSchedulerFrame() {
            try {
                Field field = StarsectorPrepatcherHooks.class.getDeclaredField("marketSchedulerRenderBatch");
                field.setAccessible(true);
                return (int) field.getLong(null);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static final class SectorContext implements InvocationHandler {
        LocationAPI currentLocation;
        MarketAPI interactionMarket;
        final float secondsPerDay;
        final SectorAPI proxy;
        final CampaignClockAPI clock;
        final CampaignUIAPI ui;

        SectorContext(LocationAPI currentLocation, float secondsPerDay) {
            this.currentLocation = currentLocation;
            this.secondsPerDay = secondsPerDay;
            this.clock = (CampaignClockAPI) Proxy.newProxyInstance(
                    CampaignClockAPI.class.getClassLoader(),
                    new Class<?>[] {CampaignClockAPI.class}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return objectMethod(proxy, method, args, "Clock");
                        }
                        if (method.getName().equals("getSecondsPerDay")) return this.secondsPerDay;
                        return primitiveDefault(method.getReturnType());
                    });
            this.ui = (CampaignUIAPI) Proxy.newProxyInstance(
                    CampaignUIAPI.class.getClassLoader(),
                    new Class<?>[] {CampaignUIAPI.class}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return objectMethod(proxy, method, args, "CampaignUI");
                        }
                        if (method.getName().equals("getCurrentInteractionDialog")) {
                            if (interactionMarket == null) return null;
                            SectorEntityToken token = (SectorEntityToken) Proxy.newProxyInstance(
                                    SectorEntityToken.class.getClassLoader(),
                                    new Class<?>[] {SectorEntityToken.class},
                                    (tokenProxy, tokenMethod, tokenArgs) -> {
                                        if (tokenMethod.getDeclaringClass() == Object.class) {
                                            return objectMethod(tokenProxy, tokenMethod, tokenArgs,
                                                    "InteractionTarget");
                                        }
                                        if (tokenMethod.getName().equals("getMarket")) {
                                            return interactionMarket;
                                        }
                                        return primitiveDefault(tokenMethod.getReturnType());
                                    });
                            return Proxy.newProxyInstance(
                                    InteractionDialogAPI.class.getClassLoader(),
                                    new Class<?>[] {InteractionDialogAPI.class},
                                    (dialogProxy, dialogMethod, dialogArgs) -> {
                                        if (dialogMethod.getDeclaringClass() == Object.class) {
                                            return objectMethod(dialogProxy, dialogMethod, dialogArgs,
                                                    "InteractionDialog");
                                        }
                                        if (dialogMethod.getName().equals("getInteractionTarget")) {
                                            return token;
                                        }
                                        return primitiveDefault(dialogMethod.getReturnType());
                                    });
                        }
                        return primitiveDefault(method.getReturnType());
                    });
            this.proxy = (SectorAPI) Proxy.newProxyInstance(
                    SectorAPI.class.getClassLoader(), new Class<?>[] {SectorAPI.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args, "Sector");
            }
            return switch (method.getName()) {
                case "getCurrentLocation" -> currentLocation;
                case "getCampaignUI" -> ui;
                case "getClock" -> clock;
                default -> primitiveDefault(method.getReturnType());
            };
        }
    }
}
