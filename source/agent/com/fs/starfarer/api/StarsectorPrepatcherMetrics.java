package com.fs.starfarer.api;

import com.starsector.prepatcher.agent.PrepatcherConfig;
import com.starsector.prepatcher.agent.PrepatcherLog;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/** Telemetry counters and the stats reporting loop. */
final class StarsectorPrepatcherMetrics {

  static final LongAdder RETAIN_CALLS = new LongAdder();
  static final LongAdder RETAIN_UPPER_BOUND = new LongAdder();
  static final LongAdder RETAIN_KEYS = new LongAdder();
  static final LongAdder RETAIN_REMOVED = new LongAdder();
  static final LongAdder RETAIN_EQUALITY_FALLBACKS = new LongAdder();
  static final LongAdder HOVER_HITS = new LongAdder();
  static final LongAdder HOVER_MISSES = new LongAdder();
  static final LongAdder MAP_LOCATION_HITS = new LongAdder();
  static final LongAdder MAP_LOCATION_MISSES = new LongAdder();
  static final LongAdder ARROW_HITS = new LongAdder();
  static final LongAdder ARROW_MISSES = new LongAdder();
  static final LongAdder INTEL_INDEX_HITS = new LongAdder();
  static final LongAdder INTEL_INDEX_BUILDS = new LongAdder();
  static final LongAdder LABEL_TOTAL = new LongAdder();
  static final LongAdder LABEL_CANDIDATES = new LongAdder();
  static final LongAdder NEBULA_HITS = new LongAdder();
  static final LongAdder NEBULA_MISSES = new LongAdder();
  static final LongAdder SAMPLE_CLEAR_SKIPS = new LongAdder();
  static final LongAdder CAMPAIGN_LISTENER_RUNS = new LongAdder();
  static final LongAdder CAMPAIGN_LISTENER_SKIPS = new LongAdder();
  static final LongAdder ROUTE_JUMP_INDEX_HITS = new LongAdder();
  static final LongAdder ROUTE_JUMP_INDEX_BUILDS = new LongAdder();
  static final LongAdder ROUTE_JUMP_INDEX_FALLBACKS = new LongAdder();
  static final LongAdder ROUTE_SYSTEM_INDEX_HITS = new LongAdder();
  static final LongAdder ROUTE_SYSTEM_INDEX_BUILDS = new LongAdder();
  static final LongAdder ROUTE_SYSTEM_INDEX_FALLBACKS = new LongAdder();
  static final LongAdder CAMPAIGN_CACHE_RESETS = new LongAdder();
  static final LongAdder LABEL_OWNER_IDLE_EVICTIONS = new LongAdder();
  static final LongAdder LABEL_OWNER_LIMIT_EVICTIONS = new LongAdder();
  static final LongAdder INTEL_ENTITY_OWNER_IDLE_EVICTIONS = new LongAdder();
  static final LongAdder INTEL_ENTITY_OWNER_LIMIT_EVICTIONS = new LongAdder();
  static final LongAdder HIT_OWNER_IDLE_EVICTIONS = new LongAdder();
  static final LongAdder HIT_OWNER_LIMIT_EVICTIONS = new LongAdder();
  static final LongAdder HIT_EXPIRED_CELL_EVICTIONS = new LongAdder();
  static final LongAdder SCRATCH_LIST_TRIMS = new LongAdder();
  static final LongAdder SCRATCH_SET_TRIMS = new LongAdder();
  static final LongAdder SCRATCH_IDENTITY_TRIMS = new LongAdder();
  static final LongAdder SCRATCH_FRAME_REPLACEMENTS = new LongAdder();
  static final LongAdder ECONOMY_LOCATION_CHECKS = new LongAdder();
  static final LongAdder ECONOMY_LOCATION_DIRTY = new LongAdder();
  static final LongAdder ECONOMY_LOCATION_SKIPS = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_SIMULATION_TICKS = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_RENDER_BATCHES = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_FAST_FORWARD_TICKS = new LongAdder();
  static final AtomicLong MARKET_SCHEDULER_MAX_TICKS_PER_BATCH = new AtomicLong();
  static final LongAdder MARKET_SCHEDULER_CALLS = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_ECONOMY_CALLS = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_PLANET_CONDITION_CALLS = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_FULL_ADVANCES = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_ACCUMULATED_CALLS = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_HOT_ADVANCES = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_HIDDEN_ADVANCES = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_PER_SIMULATION_TICK_CALLS = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_PER_SIMULATION_TICK_FLAG_CHANGES = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_SAVE_FLUSHES = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_NOT_READY_TICKS = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_NOT_READY_CALLS = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_NESTED_TICKS = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_OUTSIDE_TICK_CALLS = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_LIFECYCLE_INACTIVE_CALLS = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_FRAME_CAPTURE_FAILURES = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_INVALID_SOURCE_CALLS = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_INVALID_AMOUNT_CALLS = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_AMOUNT_OVERFLOW_SPLITS = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_STATE_FAILURES = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_REENTRANT_CALLS = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_MULTIPLE_SOURCE_TICK_CALLS = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_POLICY_FAILURES = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_POLICY_AUDITS = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_POLICY_CACHE_HITS = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_DUE_CHECKS = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_DUE_ENTRIES = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_NOT_DUE_SKIPS = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_INITIAL_PHASE_DEFERRALS = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_BATCH_TOUCHED_ENTRIES = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_ADVANCE_FAILURES = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_DISABLED_MARKET_CALLS = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_SYNCHRONIZED_ADVANCES = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_DIRECT_SYNCHRONIZATIONS = new LongAdder();
  static final LongAdder MARKET_SCHEDULER_SAVE_FLUSH_FAILURES = new LongAdder();
  static final AtomicLong PENDING_MARKET_STEPS_HIGH_WATER = new AtomicLong();
  static final AtomicLong PENDING_MARKET_RUNS_HIGH_WATER = new AtomicLong();
  static final LongAdder PENDING_RUN_OVERFLOW_FLUSHES = new LongAdder();
  static final LongAdder COALESCED_MARKET_FLUSHES = new LongAdder();
  static final DoubleAdder COALESCED_MARKET_AMOUNT = new DoubleAdder();
  static final LongAdder MARKET_BATCH_CONTEXTS_CREATED = new LongAdder();
  static final LongAdder MILITARY_BASE_REPLAYED_STEPS = new LongAdder();
  static final LongAdder LIONS_GUARD_REPLAYED_STEPS = new LongAdder();
  static final LongAdder RECENT_UNREST_REPLAYED_STEPS = new LongAdder();
  static final LongAdder CONSTRUCTION_FULL_RATE_CALLS = new LongAdder();
  static final LongAdder CONSTRUCTION_MODE_ENTRIES = new LongAdder();
  static final LongAdder CONSTRUCTION_MODE_EXITS = new LongAdder();
  static final LongAdder CONSTRUCTION_BOUNDARY_EXACT_REPLAYS = new LongAdder();
  static final LongAdder CONSTRUCTION_SCANS = new LongAdder();
  static final LongAdder CONSTRUCTION_DIRTY_SCANS = new LongAdder();
  static final LongAdder CONSTRUCTION_SAFETY_AUDIT_SCANS = new LongAdder();
  static final LongAdder CONSTRUCTION_FORCED_SCANS = new LongAdder();
  static final LongAdder CONSTRUCTION_CACHED_DECISIONS = new LongAdder();
  static final LongAdder CONSTRUCTION_DETECTED_QUEUE_NON_EMPTY = new LongAdder();
  static final LongAdder CONSTRUCTION_DETECTED_INDUSTRY_BUILDING = new LongAdder();
  static final LongAdder CONSTRUCTION_DETECTED_INDUSTRY_UPGRADING = new LongAdder();
  static final LongAdder CONSTRUCTION_DETECTED_REPORTED_BUILDING_WITHOUT_RAW = new LongAdder();
  static final LongAdder CONSTRUCTION_DETECTED_MULTIPLE_REASONS = new LongAdder();
  static final LongAdder CONSTRUCTION_DETECTED_PROBE_FAILURE = new LongAdder();
  static final LongAdder CONSTRUCTION_QUEUE_NULL_SCANS = new LongAdder();
  static final LongAdder CONSTRUCTION_QUEUE_ITEMS_NULL_SCANS = new LongAdder();
  static final LongAdder CONSTRUCTION_INDUSTRIES_NULL_SCANS = new LongAdder();
  static final LongAdder CONSTRUCTION_QUEUE_ITEMS_OBSERVED = new LongAdder();
  static final LongAdder CONSTRUCTION_INDUSTRIES_SCANNED = new LongAdder();
  static final AtomicLong CONSTRUCTION_MAX_QUEUE_ITEMS = new AtomicLong();
  static final AtomicLong CONSTRUCTION_MAX_INDUSTRIES_SCANNED = new AtomicLong();
  static final LongAdder CONSTRUCTION_AUDIT_STATE_CHANGES = new LongAdder();
  static final LongAdder CONSTRUCTION_AUDIT_FALSE_TO_TRUE = new LongAdder();
  static final LongAdder CONSTRUCTION_AUDIT_TRUE_TO_FALSE = new LongAdder();
  static final LongAdder CONSTRUCTION_REASON_CHANGES = new LongAdder();
  static final LongAdder CONSTRUCTION_MUTATION_RACES = new LongAdder();
  static final LongAdder CONSTRUCTION_DIAGNOSTIC_SAMPLES_DROPPED = new LongAdder();
  static final LongAdder SAVE_COALESCED_MARKETS = new LongAdder();
  static final LongAdder SAVE_EXACT_REPLAY_MARKETS = new LongAdder();
  static final LongAdder SAVE_EXACT_REPLAY_STEPS = new LongAdder();
  static final LongAdder SAVE_FLUSH_DURATION_NANOS = new LongAdder();
  // Aggregate persistent-snapshot counters are retained for backward-compatible logs.
  static final LongAdder ECONOMY_PERSISTENT_SNAPSHOT_REBUILDS = new LongAdder();
  static final LongAdder ECONOMY_PERSISTENT_SNAPSHOT_AUDITS = new LongAdder();
  static final LongAdder ECONOMY_PERSISTENT_SNAPSHOT_MISMATCHES = new LongAdder();
  static final LongAdder ECONOMY_PERSISTENT_SNAPSHOT_ELEMENTS = new LongAdder();
  // Split counters make it possible to tell whether markets, conditions, or industries
  // still dominate after the owner-local snapshot path is enabled.
  static final LongAdder ECONOMY_MARKET_PERSISTENT_HITS = new LongAdder();
  static final LongAdder ECONOMY_MARKET_PERSISTENT_REBUILDS = new LongAdder();
  static final LongAdder ECONOMY_MARKET_PERSISTENT_AUDITS = new LongAdder();
  static final LongAdder ECONOMY_MARKET_PERSISTENT_MISMATCHES = new LongAdder();
  static final LongAdder ECONOMY_MARKET_PERSISTENT_ELEMENTS = new LongAdder();
  static final LongAdder MARKET_CONDITION_PERSISTENT_HITS = new LongAdder();
  static final LongAdder MARKET_CONDITION_PERSISTENT_REBUILDS = new LongAdder();
  static final LongAdder MARKET_CONDITION_PERSISTENT_AUDITS = new LongAdder();
  static final LongAdder MARKET_CONDITION_PERSISTENT_MISMATCHES = new LongAdder();
  static final LongAdder MARKET_CONDITION_PERSISTENT_ELEMENTS = new LongAdder();
  static final LongAdder MARKET_INDUSTRY_PERSISTENT_HITS = new LongAdder();
  static final LongAdder MARKET_INDUSTRY_PERSISTENT_REBUILDS = new LongAdder();
  static final LongAdder MARKET_INDUSTRY_PERSISTENT_AUDITS = new LongAdder();
  static final LongAdder MARKET_INDUSTRY_PERSISTENT_MISMATCHES = new LongAdder();
  static final LongAdder MARKET_INDUSTRY_PERSISTENT_ELEMENTS = new LongAdder();
  static final LongAdder LOCAL_RESOURCES_COLD_AVOIDED = new LongAdder();
  static final LongAdder LOCAL_RESOURCES_AOTD_COLD_AVOIDED = new LongAdder();
  static final LongAdder LOCAL_RESOURCES_WARM_READS = new LongAdder();
  static final LongAdder LOCAL_RESOURCES_PEEK_FALLBACKS = new LongAdder();
  static final LongAdder ECONOMY_GROUP_INDEX_HITS = new LongAdder();
  static final LongAdder ECONOMY_GROUP_INDEX_BUILDS = new LongAdder();
  static final LongAdder ECONOMY_GROUP_INDEX_FALLBACKS = new LongAdder();
  static final LongAdder ECONOMY_GROUP_INDEX_AUDITS = new LongAdder();
  static final LongAdder ECONOMY_GROUP_INDEX_MISMATCHES = new LongAdder();
  static final LongAdder ECONOMY_GROUP_INDEX_ELEMENTS = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_MARKET_CALLS = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_ENTRIES = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_ACTIVE_ADVANCES = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_INACTIVE_SKIPS = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_REAPPLIES = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_AVAILABLE_REAPPLIES = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_DIRTY_SIGNALS = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_EXPOSURES = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_AUDITS = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_REBUILDS = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_WAKEUP_ENQUEUES = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_WAKEUP_DRAINS = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_WAKEUP_INSERTS = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_FALLBACKS = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_EXACT_VANILLA_ENTRIES = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_AOTD_NATIVE_ENTRIES = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_VANILLA_ONLY_ENTRIES = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_EXACT_VANILLA_ACTIVE = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_AOTD_NATIVE_ACTIVE = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_VANILLA_ONLY_ACTIVE = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_ACTIVE_AVAILABLE = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_ACTIVE_AOTD_EXCESS = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_ACTIVE_AOTD_DEFICIT = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_ACTIVE_TRADE = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_ACTIVE_TRADE_PLUS = new LongAdder();
  static final LongAdder COMMODITY_TEMPORAL_ACTIVE_TRADE_MINUS = new LongAdder();
  static final LongAdder PERSISTENT_STRUCTURE_EPOCHS = new LongAdder();
  static final LongAdder ECONOMY_LOCATION_EPOCH_HITS = new LongAdder();
  static final LongAdder ECONOMY_LOCATION_AUDITS = new LongAdder();
  static final LongAdder ECONOMY_LOCATION_SEQUENCE_MISMATCHES = new LongAdder();
  static final LongAdder SHIP_SCRATCH_LISTS = new LongAdder();
  static final LongAdder SHIP_SCRATCH_SETS = new LongAdder();
  static final LongAdder PARTICLE_GROUPS = new LongAdder();
  static final LongAdder PARTICLE_EXPIRED = new LongAdder();
  static final LongAdder PARTICLE_LINEAR_REMOVALS = new LongAdder();
  static final LongAdder COMM_RELAY_INDEX_HITS = new LongAdder();
  static final LongAdder COMM_RELAY_INDEX_BUILDS = new LongAdder();
  static final LongAdder COMM_RELAY_INDEX_FALLBACKS = new LongAdder();
  static final LongAdder COMM_RELAY_VALIDATION_SCANS = new LongAdder();
  static final LongAdder COMM_RELAY_SYSTEMS_VALIDATED = new LongAdder();
  static final LongAdder COMM_RELAY_SYSTEMS_TOTAL = new LongAdder();
  static final LongAdder COMM_RELAY_SYSTEMS_CANDIDATE = new LongAdder();
  static final LongAdder TEXT_READ_CALLS = new LongAdder();
  static final LongAdder TEXT_READ_CHARS = new LongAdder();
  static final LongAdder TEXT_READ_CR_REMOVED = new LongAdder();
  static final LongAdder STARTUP_LOG_JSON = new LongAdder();
  static final LongAdder STARTUP_LOG_CSV = new LongAdder();
  static final LongAdder STARTUP_LOG_SCRIPT = new LongAdder();
  static final LongAdder STARTUP_LOG_RULE = new LongAdder();
  static final LongAdder STARTUP_LOG_SPEC = new LongAdder();
  static final LongAdder STARTUP_LOG_TEXTURE = new LongAdder();
  static final LongAdder STARTUP_LOG_SOUND = new LongAdder();
  static final LongAdder STARTUP_LOG_OTHER = new LongAdder();
  static CacheGaugeSnapshot cacheGaugeSnapshot() {
    int labelCurrent = 0;
    long labelIcons = 0L;
    long labelBuckets = 0L;
    IdentityHashMap<LinkedHashMap<?, ?>, StarsectorPrepatcherIntelRuntime.LabelIndex> labels =
        StarsectorPrepatcherIntelRuntime.LABEL_INDEXES;
    synchronized (labels) {
      if (labels == StarsectorPrepatcherIntelRuntime.LABEL_INDEXES) {
        labelCurrent = labels.size();
        for (StarsectorPrepatcherIntelRuntime.LabelIndex index : labels.values()) {
          labelIcons += Math.max(0, index.size);
          labelBuckets += index.buckets.size();
        }
      }
    }

    int intelCurrent = 0;
    long intelEntries = 0L;
    IdentityHashMap<List<?>, StarsectorPrepatcherIntelRuntime.IntelEntityIndex> intel =
        StarsectorPrepatcherIntelRuntime.INTEL_ENTITY_INDEXES;
    synchronized (intel) {
      if (intel == StarsectorPrepatcherIntelRuntime.INTEL_ENTITY_INDEXES) {
        intelCurrent = intel.size();
        for (StarsectorPrepatcherIntelRuntime.IntelEntityIndex index : intel.values()) {
          intelEntries += index.byPlugin.size();
        }
      }
    }

    int hitCurrent = 0;
    long hitCells = 0L;
    int hitMax = 0;
    IdentityHashMap<Object, StarsectorPrepatcherMapRuntime.HitCache> hits = StarsectorPrepatcherMapRuntime.HIT_CACHES;
    synchronized (hits) {
      if (hits == StarsectorPrepatcherMapRuntime.HIT_CACHES) {
        hitCurrent = hits.size();
        for (StarsectorPrepatcherMapRuntime.HitCache cache : hits.values()) {
          synchronized (cache) {
            int cells = cache.cells.size();
            hitCells += cells;
            if (cells > hitMax) hitMax = cells;
          }
        }
      }
    }
    return new CacheGaugeSnapshot(labelCurrent, labelIcons, labelBuckets, intelCurrent, intelEntries, hitCurrent,
        hitCells, hitMax);
  }
  record CacheGaugeSnapshot(int labelIndexesCurrent, long labelIndexesTotalIcons, long labelIndexesTotalBuckets,
      int intelEntityIndexesCurrent, long intelEntityIndexesTotalEntries, int hitCachesCurrent,
      long hitCachesTotalCells, int hitCachesMaxCellsPerOwner) {
  }
  static void statsLoop() {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    int seconds = c == null ? 0 : c.statsLogIntervalSeconds;
    if (seconds <= 0) return;
    while (true) {
      try {
        Thread.sleep(seconds * 1000L);
        CacheGaugeSnapshot gauges = cacheGaugeSnapshot();
        StarsectorPrepatcherScratch.ScratchGaugeSnapshot scratchGauges = StarsectorPrepatcherScratch.SCRATCH_GAUGES;
        StarsectorPrepatcherMarketScheduler.MarketSchedulerGaugeSnapshot marketGauges =
            StarsectorPrepatcherMarketScheduler.marketSchedulerGaugeSnapshot();
        PrepatcherLog.info("stats: retainCalls=" + RETAIN_CALLS.sumThenReset() + ", avoidedContainsUpperBound="
            + RETAIN_UPPER_BOUND.sumThenReset() + ", retainKeysScanned=" + RETAIN_KEYS.sumThenReset()
            + ", retainKeysRemoved=" + RETAIN_REMOVED.sumThenReset() + ", retainEqualityFallbacks="
            + RETAIN_EQUALITY_FALLBACKS.sumThenReset() + ", hoverHits=" + HOVER_HITS.sumThenReset() + ", hoverMisses="
            + HOVER_MISSES.sumThenReset() + ", labelIndexesCurrent=" + gauges.labelIndexesCurrent()
            + ", labelIndexesTotalIcons=" + gauges.labelIndexesTotalIcons() + ", labelIndexesTotalBuckets="
            + gauges.labelIndexesTotalBuckets() + ", intelEntityIndexesCurrent=" + gauges.intelEntityIndexesCurrent()
            + ", intelEntityIndexesTotalEntries=" + gauges.intelEntityIndexesTotalEntries() + ", hitCachesCurrent="
            + gauges.hitCachesCurrent() + ", hitCachesTotalCells=" + gauges.hitCachesTotalCells()
            + ", hitCachesMaxCellsPerOwner=" + gauges.hitCachesMaxCellsPerOwner() + ", labelOwnerIdleEvictions="
            + LABEL_OWNER_IDLE_EVICTIONS.sum() + ", labelOwnerLimitEvictions=" + LABEL_OWNER_LIMIT_EVICTIONS.sum()
            + ", intelEntityOwnerIdleEvictions=" + INTEL_ENTITY_OWNER_IDLE_EVICTIONS.sum()
            + ", intelEntityOwnerLimitEvictions=" + INTEL_ENTITY_OWNER_LIMIT_EVICTIONS.sum()
            + ", hitOwnerIdleEvictions=" + HIT_OWNER_IDLE_EVICTIONS.sum() + ", hitOwnerLimitEvictions="
            + HIT_OWNER_LIMIT_EVICTIONS.sum() + ", hitExpiredCellEvictions=" + HIT_EXPIRED_CELL_EVICTIONS.sum()
            + ", scratchFrameCount=" + scratchGauges.frameCount() + ", scratchPooledListCount="
            + scratchGauges.pooledListCount() + ", scratchPooledSetCount=" + scratchGauges.pooledSetCount()
            + ", scratchRetainedListCapacity=" + scratchGauges.retainedListCapacity() + ", scratchRetainedSetCapacity="
            + scratchGauges.retainedSetCapacity() + ", scratchRetainedIdentityCapacity="
            + scratchGauges.retainedIdentityCapacity() + ", scratchListTrims=" + SCRATCH_LIST_TRIMS.sum()
            + ", scratchSetTrims=" + SCRATCH_SET_TRIMS.sum() + ", scratchIdentityTrims=" + SCRATCH_IDENTITY_TRIMS.sum()
            + ", scratchFrameReplacements=" + SCRATCH_FRAME_REPLACEMENTS.sum() + ", mapLocationHits="
            + MAP_LOCATION_HITS.sumThenReset() + ", mapLocationMisses=" + MAP_LOCATION_MISSES.sumThenReset()
            + ", arrowHits=" + ARROW_HITS.sumThenReset() + ", arrowMisses=" + ARROW_MISSES.sumThenReset()
            + ", intelIndexHits=" + INTEL_INDEX_HITS.sumThenReset() + ", intelIndexBuilds="
            + INTEL_INDEX_BUILDS.sumThenReset() + ", labelCandidates=" + LABEL_CANDIDATES.sumThenReset() + "/"
            + LABEL_TOTAL.sumThenReset() + ", nebulaHits=" + NEBULA_HITS.sumThenReset() + ", nebulaMisses="
            + NEBULA_MISSES.sumThenReset() + ", sampleClearSkips=" + SAMPLE_CLEAR_SKIPS.sumThenReset()
            + ", campaignListenerRuns=" + CAMPAIGN_LISTENER_RUNS.sumThenReset() + ", campaignListenerSkips="
            + CAMPAIGN_LISTENER_SKIPS.sumThenReset() + ", routeJumpIndexHits=" + ROUTE_JUMP_INDEX_HITS.sumThenReset()
            + ", routeJumpIndexBuilds=" + ROUTE_JUMP_INDEX_BUILDS.sumThenReset() + ", routeJumpIndexFallbacks="
            + ROUTE_JUMP_INDEX_FALLBACKS.sumThenReset() + ", routeSystemIndexHits="
            + ROUTE_SYSTEM_INDEX_HITS.sumThenReset() + ", routeSystemIndexBuilds="
            + ROUTE_SYSTEM_INDEX_BUILDS.sumThenReset() + ", routeSystemIndexFallbacks="
            + ROUTE_SYSTEM_INDEX_FALLBACKS.sumThenReset() + StarsectorPrepatcherStrategicJumpIndex.statsAndReset()
            + ", economyLocationChecks=" + ECONOMY_LOCATION_CHECKS.sumThenReset() + ", economyLocationDirty="
            + ECONOMY_LOCATION_DIRTY.sumThenReset() + ", economyLocationSkips=" + ECONOMY_LOCATION_SKIPS.sumThenReset()
            + ", marketSchedulerSimulationTicks=" + MARKET_SCHEDULER_SIMULATION_TICKS.sumThenReset()
            + ", marketSchedulerRenderBatches=" + MARKET_SCHEDULER_RENDER_BATCHES.sumThenReset()
            + ", marketSchedulerFastForwardTicks=" + MARKET_SCHEDULER_FAST_FORWARD_TICKS.sumThenReset()
            + ", marketSchedulerMaxTicksPerBatch=" + MARKET_SCHEDULER_MAX_TICKS_PER_BATCH.getAndSet(0L)
            + ", marketSchedulerCalls=" + MARKET_SCHEDULER_CALLS.sumThenReset() + ", marketSchedulerEconomyCalls="
            + MARKET_SCHEDULER_ECONOMY_CALLS.sumThenReset() + ", marketSchedulerPlanetConditionCalls="
            + MARKET_SCHEDULER_PLANET_CONDITION_CALLS.sumThenReset() + ", marketSchedulerFullAdvances="
            + MARKET_SCHEDULER_FULL_ADVANCES.sumThenReset() + ", marketSchedulerAccumulatedCalls="
            + MARKET_SCHEDULER_ACCUMULATED_CALLS.sumThenReset() + ", marketSchedulerHotAdvances="
            + MARKET_SCHEDULER_HOT_ADVANCES.sumThenReset() + ", marketSchedulerHiddenAdvances="
            + MARKET_SCHEDULER_HIDDEN_ADVANCES.sumThenReset() + ", marketSchedulerPerSimulationTickMarkets="
            + StarsectorPrepatcherMarketScheduler.countPerSimulationTickMarkets()
            + ", marketSchedulerPerSimulationTickCalls=" + MARKET_SCHEDULER_PER_SIMULATION_TICK_CALLS.sumThenReset()
            + ", marketSchedulerPerSimulationTickFlagChanges="
            + MARKET_SCHEDULER_PER_SIMULATION_TICK_FLAG_CHANGES.sumThenReset() + ", marketSchedulerSaveFlushes="
            + MARKET_SCHEDULER_SAVE_FLUSHES.sumThenReset() + ", marketSchedulerNotReadyTicks="
            + MARKET_SCHEDULER_NOT_READY_TICKS.sumThenReset() + ", marketSchedulerNotReadyCalls="
            + MARKET_SCHEDULER_NOT_READY_CALLS.sumThenReset() + ", marketSchedulerNestedTicks="
            + MARKET_SCHEDULER_NESTED_TICKS.sumThenReset() + ", marketSchedulerOutsideTickCalls="
            + MARKET_SCHEDULER_OUTSIDE_TICK_CALLS.sumThenReset() + ", marketSchedulerLifecycleInactiveCalls="
            + MARKET_SCHEDULER_LIFECYCLE_INACTIVE_CALLS.sumThenReset() + ", marketSchedulerFrameCaptureFailures="
            + MARKET_SCHEDULER_FRAME_CAPTURE_FAILURES.sumThenReset() + ", marketSchedulerInvalidSourceCalls="
            + MARKET_SCHEDULER_INVALID_SOURCE_CALLS.sumThenReset() + ", marketSchedulerInvalidAmountCalls="
            + MARKET_SCHEDULER_INVALID_AMOUNT_CALLS.sumThenReset() + ", marketSchedulerAmountOverflowSplits="
            + MARKET_SCHEDULER_AMOUNT_OVERFLOW_SPLITS.sumThenReset() + ", marketSchedulerStateFailures="
            + MARKET_SCHEDULER_STATE_FAILURES.sumThenReset() + ", marketSchedulerReentrantCalls="
            + MARKET_SCHEDULER_REENTRANT_CALLS.sumThenReset() + ", marketSchedulerMultipleSourceTickCalls="
            + MARKET_SCHEDULER_MULTIPLE_SOURCE_TICK_CALLS.sumThenReset() + ", marketSchedulerPolicyFailures="
            + MARKET_SCHEDULER_POLICY_FAILURES.sumThenReset() + ", marketSchedulerPolicyAudits="
            + MARKET_SCHEDULER_POLICY_AUDITS.sumThenReset() + ", marketSchedulerPolicyCacheHits="
            + MARKET_SCHEDULER_POLICY_CACHE_HITS.sumThenReset() + ", marketSchedulerDueChecks="
            + MARKET_SCHEDULER_DUE_CHECKS.sumThenReset() + ", marketSchedulerDueEntries="
            + MARKET_SCHEDULER_DUE_ENTRIES.sumThenReset() + ", marketSchedulerNotDueSkips="
            + MARKET_SCHEDULER_NOT_DUE_SKIPS.sumThenReset() + ", marketSchedulerInitialPhaseDeferrals="
            + MARKET_SCHEDULER_INITIAL_PHASE_DEFERRALS.sumThenReset() + ", marketSchedulerBatchTouchedEntries="
            + MARKET_SCHEDULER_BATCH_TOUCHED_ENTRIES.sumThenReset() + ", marketSchedulerAdvanceFailures="
            + MARKET_SCHEDULER_ADVANCE_FAILURES.sumThenReset() + ", marketSchedulerDisabledMarketCalls="
            + MARKET_SCHEDULER_DISABLED_MARKET_CALLS.sumThenReset() + ", marketSchedulerSynchronizedAdvances="
            + MARKET_SCHEDULER_SYNCHRONIZED_ADVANCES.sumThenReset() + ", marketSchedulerDirectSynchronizations="
            + MARKET_SCHEDULER_DIRECT_SYNCHRONIZATIONS.sumThenReset() + ", marketSchedulerSaveFlushFailures="
            + MARKET_SCHEDULER_SAVE_FLUSH_FAILURES.sumThenReset() + ", pendingMarketStepsCurrent="
            + marketGauges.pendingMarketStepsCurrent() + ", pendingMarketRunsCurrent="
            + marketGauges.pendingMarketRunsCurrent() + ", pendingMarketStepsHighWater="
            + PENDING_MARKET_STEPS_HIGH_WATER.get() + ", pendingMarketRunsHighWater="
            + PENDING_MARKET_RUNS_HIGH_WATER.get() + ", pendingRunOverflowFlushes="
            + PENDING_RUN_OVERFLOW_FLUSHES.sumThenReset() + ", coalescedMarketFlushes="
            + COALESCED_MARKET_FLUSHES.sumThenReset() + ", coalescedMarketAmount="
            + COALESCED_MARKET_AMOUNT.sumThenReset() + ", marketBatchContextsCreated="
            + MARKET_BATCH_CONTEXTS_CREATED.sumThenReset() + ", militaryBaseReplayedSteps="
            + MILITARY_BASE_REPLAYED_STEPS.sumThenReset() + ", lionsGuardReplayedSteps="
            + LIONS_GUARD_REPLAYED_STEPS.sumThenReset() + ", recentUnrestReplayedSteps="
            + RECENT_UNREST_REPLAYED_STEPS.sumThenReset() + ", constructionFullRateCalls="
            + CONSTRUCTION_FULL_RATE_CALLS.sumThenReset() + ", constructionFullRateMarkets="
            + marketGauges.constructionFullRateMarkets() + ", constructionMarketsQueueNonEmpty="
            + marketGauges.constructionMarketsQueueNonEmpty() + ", constructionMarketsIndustryBuilding="
            + marketGauges.constructionMarketsIndustryBuilding() + ", constructionMarketsIndustryUpgrading="
            + marketGauges.constructionMarketsIndustryUpgrading() + ", constructionMarketsReportedBuildingWithoutRaw="
            + marketGauges.constructionMarketsReportedBuildingWithoutRaw() + ", constructionMarketsUncertain="
            + marketGauges.constructionMarketsUncertain() + ", constructionMarketsMultipleReasons="
            + marketGauges.constructionMarketsMultipleReasons() + ", constructionModeEntries="
            + CONSTRUCTION_MODE_ENTRIES.sumThenReset() + ", constructionModeExits="
            + CONSTRUCTION_MODE_EXITS.sumThenReset() + ", constructionBoundaryExactReplays="
            + CONSTRUCTION_BOUNDARY_EXACT_REPLAYS.sumThenReset() + ", constructionScans="
            + CONSTRUCTION_SCANS.sumThenReset() + ", constructionDirtyScans=" + CONSTRUCTION_DIRTY_SCANS.sumThenReset()
            + ", constructionSafetyAuditScans=" + CONSTRUCTION_SAFETY_AUDIT_SCANS.sumThenReset()
            + ", constructionForcedScans=" + CONSTRUCTION_FORCED_SCANS.sumThenReset() + ", constructionCachedDecisions="
            + CONSTRUCTION_CACHED_DECISIONS.sumThenReset() + ", constructionDetectedQueueNonEmpty="
            + CONSTRUCTION_DETECTED_QUEUE_NON_EMPTY.sumThenReset() + ", constructionDetectedIndustryBuilding="
            + CONSTRUCTION_DETECTED_INDUSTRY_BUILDING.sumThenReset() + ", constructionDetectedIndustryUpgrading="
            + CONSTRUCTION_DETECTED_INDUSTRY_UPGRADING.sumThenReset()
            + ", constructionDetectedReportedBuildingWithoutRaw="
            + CONSTRUCTION_DETECTED_REPORTED_BUILDING_WITHOUT_RAW.sumThenReset()
            + ", constructionDetectedMultipleReasons=" + CONSTRUCTION_DETECTED_MULTIPLE_REASONS.sumThenReset()
            + ", constructionDetectedProbeFailure=" + CONSTRUCTION_DETECTED_PROBE_FAILURE.sumThenReset()
            + ", constructionQueueNullScans=" + CONSTRUCTION_QUEUE_NULL_SCANS.sumThenReset()
            + ", constructionQueueItemsNullScans=" + CONSTRUCTION_QUEUE_ITEMS_NULL_SCANS.sumThenReset()
            + ", constructionIndustriesNullScans=" + CONSTRUCTION_INDUSTRIES_NULL_SCANS.sumThenReset()
            + ", constructionQueueItemsObserved=" + CONSTRUCTION_QUEUE_ITEMS_OBSERVED.sumThenReset()
            + ", constructionIndustriesScanned=" + CONSTRUCTION_INDUSTRIES_SCANNED.sumThenReset()
            + ", constructionMaxQueueItems=" + CONSTRUCTION_MAX_QUEUE_ITEMS.get()
            + ", constructionMaxIndustriesScanned=" + CONSTRUCTION_MAX_INDUSTRIES_SCANNED.get()
            + ", constructionAuditStateChanges=" + CONSTRUCTION_AUDIT_STATE_CHANGES.sumThenReset()
            + ", constructionAuditFalseToTrue=" + CONSTRUCTION_AUDIT_FALSE_TO_TRUE.sumThenReset()
            + ", constructionAuditTrueToFalse=" + CONSTRUCTION_AUDIT_TRUE_TO_FALSE.sumThenReset()
            + ", constructionReasonChanges=" + CONSTRUCTION_REASON_CHANGES.sumThenReset()
            + ", constructionMutationRaces=" + CONSTRUCTION_MUTATION_RACES.sumThenReset()
            + ", constructionDiagnosticSamplesDropped=" + CONSTRUCTION_DIAGNOSTIC_SAMPLES_DROPPED.sumThenReset()
            + ", saveCoalescedMarkets=" + SAVE_COALESCED_MARKETS.sumThenReset() + ", saveExactReplayMarkets="
            + SAVE_EXACT_REPLAY_MARKETS.sumThenReset() + ", saveExactReplaySteps="
            + SAVE_EXACT_REPLAY_STEPS.sumThenReset() + ", saveFlushDurationNanos="
            + SAVE_FLUSH_DURATION_NANOS.sumThenReset() + ", localResourcesColdAvoided="
            + LOCAL_RESOURCES_COLD_AVOIDED.sumThenReset() + ", localResourcesAoTDColdAvoided="
            + LOCAL_RESOURCES_AOTD_COLD_AVOIDED.sumThenReset() + ", localResourcesWarmReads="
            + LOCAL_RESOURCES_WARM_READS.sumThenReset() + ", localResourcesPeekFallbacks="
            + LOCAL_RESOURCES_PEEK_FALLBACKS.sumThenReset() + ", economyGroupIndexHits="
            + ECONOMY_GROUP_INDEX_HITS.sumThenReset() + ", economyGroupIndexBuilds="
            + ECONOMY_GROUP_INDEX_BUILDS.sumThenReset() + ", economyGroupIndexFallbacks="
            + ECONOMY_GROUP_INDEX_FALLBACKS.sumThenReset() + ", economyGroupIndexAudits="
            + ECONOMY_GROUP_INDEX_AUDITS.sumThenReset() + ", economyGroupIndexMismatches="
            + ECONOMY_GROUP_INDEX_MISMATCHES.sumThenReset() + ", economyGroupIndexElements="
            + ECONOMY_GROUP_INDEX_ELEMENTS.sumThenReset() + ", economyPersistentRebuilds="
            + ECONOMY_PERSISTENT_SNAPSHOT_REBUILDS.sumThenReset() + ", economyPersistentAudits="
            + ECONOMY_PERSISTENT_SNAPSHOT_AUDITS.sumThenReset() + ", economyPersistentMismatches="
            + ECONOMY_PERSISTENT_SNAPSHOT_MISMATCHES.sumThenReset() + ", economyPersistentElements="
            + ECONOMY_PERSISTENT_SNAPSHOT_ELEMENTS.sumThenReset() + ", economyMarketPersistentHits="
            + ECONOMY_MARKET_PERSISTENT_HITS.sumThenReset() + ", economyMarketPersistentRebuilds="
            + ECONOMY_MARKET_PERSISTENT_REBUILDS.sumThenReset() + ", economyMarketPersistentAudits="
            + ECONOMY_MARKET_PERSISTENT_AUDITS.sumThenReset() + ", economyMarketPersistentMismatches="
            + ECONOMY_MARKET_PERSISTENT_MISMATCHES.sumThenReset() + ", economyMarketPersistentElements="
            + ECONOMY_MARKET_PERSISTENT_ELEMENTS.sumThenReset() + ", marketConditionPersistentHits="
            + MARKET_CONDITION_PERSISTENT_HITS.sumThenReset() + ", marketConditionPersistentRebuilds="
            + MARKET_CONDITION_PERSISTENT_REBUILDS.sumThenReset() + ", marketConditionPersistentAudits="
            + MARKET_CONDITION_PERSISTENT_AUDITS.sumThenReset() + ", marketConditionPersistentMismatches="
            + MARKET_CONDITION_PERSISTENT_MISMATCHES.sumThenReset() + ", marketConditionPersistentElements="
            + MARKET_CONDITION_PERSISTENT_ELEMENTS.sumThenReset() + ", marketIndustryPersistentHits="
            + MARKET_INDUSTRY_PERSISTENT_HITS.sumThenReset() + ", marketIndustryPersistentRebuilds="
            + MARKET_INDUSTRY_PERSISTENT_REBUILDS.sumThenReset() + ", marketIndustryPersistentAudits="
            + MARKET_INDUSTRY_PERSISTENT_AUDITS.sumThenReset() + ", marketIndustryPersistentMismatches="
            + MARKET_INDUSTRY_PERSISTENT_MISMATCHES.sumThenReset() + ", marketIndustryPersistentElements="
            + MARKET_INDUSTRY_PERSISTENT_ELEMENTS.sumThenReset() + ", commodityTemporalMarkets="
            + COMMODITY_TEMPORAL_MARKET_CALLS.sumThenReset() + ", commodityTemporalEntries="
            + COMMODITY_TEMPORAL_ENTRIES.sumThenReset() + ", commodityTemporalActive="
            + COMMODITY_TEMPORAL_ACTIVE_ADVANCES.sumThenReset() + ", commodityTemporalInactiveSkips="
            + COMMODITY_TEMPORAL_INACTIVE_SKIPS.sumThenReset() + ", commodityTemporalReapplies="
            + COMMODITY_TEMPORAL_REAPPLIES.sumThenReset() + ", commodityTemporalAvailableReapplies="
            + COMMODITY_TEMPORAL_AVAILABLE_REAPPLIES.sumThenReset() + ", commodityTemporalDirtySignals="
            + COMMODITY_TEMPORAL_DIRTY_SIGNALS.sumThenReset() + ", commodityTemporalExposures="
            + COMMODITY_TEMPORAL_EXPOSURES.sumThenReset() + ", commodityTemporalAudits="
            + COMMODITY_TEMPORAL_AUDITS.sumThenReset() + ", commodityTemporalRebuilds="
            + COMMODITY_TEMPORAL_REBUILDS.sumThenReset() + ", commodityTemporalWakeupEnqueues="
            + COMMODITY_TEMPORAL_WAKEUP_ENQUEUES.sumThenReset() + ", commodityTemporalWakeupDrains="
            + COMMODITY_TEMPORAL_WAKEUP_DRAINS.sumThenReset() + ", commodityTemporalWakeupInserts="
            + COMMODITY_TEMPORAL_WAKEUP_INSERTS.sumThenReset() + ", commodityTemporalFallbacks="
            + COMMODITY_TEMPORAL_FALLBACKS.sumThenReset() + ", commodityTemporalExactVanillaEntries="
            + COMMODITY_TEMPORAL_EXACT_VANILLA_ENTRIES.sumThenReset() + ", commodityTemporalAoTDNativeEntries="
            + COMMODITY_TEMPORAL_AOTD_NATIVE_ENTRIES.sumThenReset() + ", commodityTemporalVanillaOnlyEntries="
            + COMMODITY_TEMPORAL_VANILLA_ONLY_ENTRIES.sumThenReset() + ", commodityTemporalExactVanillaActive="
            + COMMODITY_TEMPORAL_EXACT_VANILLA_ACTIVE.sumThenReset() + ", commodityTemporalAoTDNativeActive="
            + COMMODITY_TEMPORAL_AOTD_NATIVE_ACTIVE.sumThenReset() + ", commodityTemporalVanillaOnlyActive="
            + COMMODITY_TEMPORAL_VANILLA_ONLY_ACTIVE.sumThenReset() + ", commodityTemporalActiveAvailable="
            + COMMODITY_TEMPORAL_ACTIVE_AVAILABLE.sumThenReset() + ", commodityTemporalActiveAoTDExcess="
            + COMMODITY_TEMPORAL_ACTIVE_AOTD_EXCESS.sumThenReset() + ", commodityTemporalActiveAoTDDeficit="
            + COMMODITY_TEMPORAL_ACTIVE_AOTD_DEFICIT.sumThenReset() + ", commodityTemporalActiveTrade="
            + COMMODITY_TEMPORAL_ACTIVE_TRADE.sumThenReset() + ", commodityTemporalActiveTradePlus="
            + COMMODITY_TEMPORAL_ACTIVE_TRADE_PLUS.sumThenReset() + ", commodityTemporalActiveTradeMinus="
            + COMMODITY_TEMPORAL_ACTIVE_TRADE_MINUS.sumThenReset() + ", persistentStructureEpochs="
            + PERSISTENT_STRUCTURE_EPOCHS.sumThenReset() + ", economyLocationEpochHits="
            + ECONOMY_LOCATION_EPOCH_HITS.sumThenReset() + ", economyLocationAudits="
            + ECONOMY_LOCATION_AUDITS.sumThenReset() + ", economyLocationSequenceMismatches="
            + ECONOMY_LOCATION_SEQUENCE_MISMATCHES.sumThenReset() + ", commRelayIndexHits="
            + COMM_RELAY_INDEX_HITS.sumThenReset() + ", commRelayIndexBuilds=" + COMM_RELAY_INDEX_BUILDS.sumThenReset()
            + ", commRelayIndexFallbacks=" + COMM_RELAY_INDEX_FALLBACKS.sumThenReset() + ", commRelayValidationScans="
            + COMM_RELAY_VALIDATION_SCANS.sumThenReset() + ", commRelaySystemsValidated="
            + COMM_RELAY_SYSTEMS_VALIDATED.sumThenReset() + ", commRelaySystems="
            + COMM_RELAY_SYSTEMS_CANDIDATE.sumThenReset() + "/" + COMM_RELAY_SYSTEMS_TOTAL.sumThenReset()
            + ", shipScratchLists=" + SHIP_SCRATCH_LISTS.sumThenReset() + ", shipScratchSets="
            + SHIP_SCRATCH_SETS.sumThenReset() + ", particleGroups=" + PARTICLE_GROUPS.sumThenReset()
            + ", particleExpired=" + PARTICLE_EXPIRED.sumThenReset() + ", particleLinearRemovals="
            + PARTICLE_LINEAR_REMOVALS.sumThenReset() + ", campaignCacheResets=" + CAMPAIGN_CACHE_RESETS.sumThenReset()
            + ", textReads=" + TEXT_READ_CALLS.sumThenReset() + ", textChars=" + TEXT_READ_CHARS.sumThenReset()
            + ", textCRRemoved=" + TEXT_READ_CR_REMOVED.sumThenReset() + ", startupLogsSuppressed="
            + (STARTUP_LOG_JSON.sumThenReset() + STARTUP_LOG_CSV.sumThenReset() + STARTUP_LOG_SCRIPT.sumThenReset()
                + STARTUP_LOG_RULE.sumThenReset() + STARTUP_LOG_SPEC.sumThenReset() + STARTUP_LOG_TEXTURE.sumThenReset()
                + STARTUP_LOG_SOUND.sumThenReset() + STARTUP_LOG_OTHER.sumThenReset()));
        PrepatcherLog.info("stats: " + StarsectorPrepatcherTempModHooks.statsAndReset()
            + StarsectorPrepatcherCoreWorldsRuntime.statsAndResetFragment());
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return;
      } catch (Throwable ex) {
        PrepatcherLog.error("Stats logger failed", ex);
      }
    }
  }
}
