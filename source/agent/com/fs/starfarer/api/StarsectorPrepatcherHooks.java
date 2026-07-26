package com.fs.starfarer.api;

import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.ConstructionQueue;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.combat.MutableStatWithTempMods;
import com.fs.starfarer.campaign.CampaignEngine;
import com.fs.starfarer.campaign.JumpPoint;
import com.fs.starfarer.campaign.econ.CommodityOnMarket;
import com.fs.starfarer.campaign.econ.Economy;
import com.fs.starfarer.campaign.econ.Market;
import com.fs.starfarer.campaign.econ.reach.ReachEconomy;
import com.fs.starfarer.api.impl.campaign.procgen.Constellation;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.terrain.BaseTiledTerrain;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.starsector.prepatcher.agent.PrepatcherConfig;
import com.starsector.prepatcher.agent.PrepatcherLog;
import org.lwjgl.util.vector.Vector2f;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Random;
import java.util.RandomAccess;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import java.util.function.LongSupplier;

/** Runtime entry points called by transformed Starsector classes. */
public final class StarsectorPrepatcherHooks {
    private static volatile PrepatcherConfig config;
    private static volatile Path modRoot;

    private static final int MARKET_ORIGIN_NONE = 0;
    private static final int MARKET_ORIGIN_SCHEDULED = 1;
    private static final int MARKET_ORIGIN_SCHEDULER_FALLBACK = 2;
    private static final int MARKET_ORIGIN_SAVE_FLUSH = 3;
    private static final int MARKET_ORIGIN_DIRECT_CALL_SITE = 4;
    private static final int MARKET_ORIGIN_PLANET_CONDITION_SCHEDULED = 5;
    private static final int MARKET_ORIGIN_PLANET_CONDITION_FALLBACK = 6;
    private static final ThreadLocal<MarketAdvanceObservationContext> MARKET_ADVANCE_CONTEXT =
            ThreadLocal.withInitial(MarketAdvanceObservationContext::new);
    private static volatile DirectMarketObserver DIRECT_MARKET_OBSERVER;

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    private static final int SCRATCH_RETAINED_FRAME_FLOOR = 4;
    private static volatile LongSupplier NANO_CLOCK = System::nanoTime;
    private static final ThreadLocal<TextReadScratch> TEXT_READ_SCRATCH =
            ThreadLocal.withInitial(TextReadScratch::new);
    private static volatile Map<IntelInfoPlugin, IntelCache> INTEL_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile IdentityHashMap<LinkedHashMap<?, ?>, LabelIndex> LABEL_INDEXES =
            new IdentityHashMap<>();
    private static volatile IdentityHashMap<List<?>, IntelEntityIndex> INTEL_ENTITY_INDEXES =
            new IdentityHashMap<>();
    private static volatile IdentityHashMap<Object, HitCache> HIT_CACHES = new IdentityHashMap<>();
    private static volatile WeakHashMap<Object, Long> SAMPLE_CLEAR_TIMES = new WeakHashMap<>();
    private static volatile Map<Object, CampaignListenerState> CAMPAIGN_LISTENER_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile Map<LocationAPI, RouteJumpIndex> ROUTE_JUMP_INDEXES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile Map<Object, RouteSystemIndex> ROUTE_SYSTEM_INDEXES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final int STRATEGIC_JUMP_CAPABILITY_UNKNOWN = 0;
    private static final int STRATEGIC_JUMP_CAPABILITY_READY = 1;
    private static final int STRATEGIC_JUMP_CAPABILITY_PENDING = 2;
    private static final int STRATEGIC_JUMP_CAPABILITY_FAILED = -1;
    private static volatile int strategicJumpRuntimeCapability;
    private static final String STRATEGIC_JUMP_MAINTENANCE_STATUS =
            "starsector.prepatcher.patchStatus.com.fs.starfarer.campaign.CampaignEngine."
                    + "campaignCacheLifecycle";
    private static final String STRATEGIC_JUMP_FIRST_STATUS =
            "starsector.prepatcher.patchStatus.com.fs.starfarer.campaign.ai.StrategicModule."
                    + "strategicJumpDestinationFirst";
    private static final String STRATEGIC_JUMP_SOURCE_STATUS =
            "starsector.prepatcher.patchStatus.com.fs.starfarer.campaign.ai.StrategicModule."
                    + "strategicJumpDestinationIndex";
    private static final String STRATEGIC_JUMP_POINT_STATUS =
            "starsector.prepatcher.patchStatus.com.fs.starfarer.campaign.JumpPoint."
                    + "strategicJumpDestinationIndex";
    private static final String STRATEGIC_JUMP_LOCATION_STATUS =
            "starsector.prepatcher.patchStatus.com.fs.starfarer.campaign.BaseLocation."
                    + "strategicJumpDestinationIndex";
    private static final String STRATEGIC_JUMP_DESTINATION_STATUS =
            "starsector.prepatcher.patchStatus."
                    + "com.fs.starfarer.api.campaign.JumpPointAPI$JumpDestination."
                    + "strategicJumpDestinationIndex";
    private static volatile Map<ReachEconomy, EconomyLocationState> ECONOMY_LOCATION_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final int MARKET_SCHEDULER_COMPONENT_ENGINE = 1;
    private static final int MARKET_SCHEDULER_COMPONENT_ECONOMY = 2;
    private static final int MARKET_SCHEDULER_COMPONENT_ENTITY = 4;
    private static final int MARKET_SCHEDULER_COMPONENT_SAVE = 8;
    private static final int MARKET_SCHEDULER_COMPONENT_BATCH_PROTOCOL = 16;
    private static final int MARKET_SCHEDULER_COMPONENT_MARKET_SEMANTICS = 32;
    private static final int MARKET_SCHEDULER_COMPONENT_MILITARY_BASE = 64;
    private static final int MARKET_SCHEDULER_COMPONENT_LIONS_GUARD = 128;
    private static final int MARKET_SCHEDULER_COMPONENT_RECENT_UNREST = 256;
    private static final int MARKET_SCHEDULER_COMPONENT_BASE_INDUSTRY = 512;
    private static final int MARKET_SCHEDULER_COMPONENT_CONSTRUCTION_QUEUE = 1024;
    private static final int CONSTRUCTION_REASON_QUEUE_NON_EMPTY = 1;
    private static final int CONSTRUCTION_REASON_INDUSTRY_BUILDING = 2;
    private static final int CONSTRUCTION_REASON_INDUSTRY_UPGRADING = 4;
    private static final int CONSTRUCTION_REASON_QUEUE_ITEMS_UNKNOWN = 8;
    private static final int CONSTRUCTION_REASON_INDUSTRIES_UNKNOWN = 16;
    private static final int CONSTRUCTION_REASON_PROBE_FAILURE = 32;
    private static final int CONSTRUCTION_REASON_MUTATION_RACE = 64;
    /**
     * A virtual Industry.isBuilding() signal from BaseIndustry while the actual
     * BaseIndustry.building field is false. PopulationAndInfrastructure uses
     * this pattern for market-size growth, so it is telemetry-only.
     */
    private static final int CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW = 128;
    /** Reasons observed by the construction probe, including diagnostic-only signals. */
    private static final int CONSTRUCTION_REASON_OBSERVED_MASK =
            CONSTRUCTION_REASON_QUEUE_NON_EMPTY
                    | CONSTRUCTION_REASON_INDUSTRY_BUILDING
                    | CONSTRUCTION_REASON_INDUSTRY_UPGRADING
                    | CONSTRUCTION_REASON_QUEUE_ITEMS_UNKNOWN
                    | CONSTRUCTION_REASON_INDUSTRIES_UNKNOWN
                    | CONSTRUCTION_REASON_PROBE_FAILURE
                    | CONSTRUCTION_REASON_MUTATION_RACE
                    | CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW;
    /**
     * Reasons that require exact full-rate market advancement. isUpgrading() and
     * a BaseIndustry virtual-building signal without its raw building field are
     * intentionally diagnostic-only. Vanilla PopulationAndInfrastructure uses
     * both signals for market-size growth, not Industry construction.
     */
    private static final int CONSTRUCTION_REASON_ACTIVE_MASK =
            CONSTRUCTION_REASON_QUEUE_NON_EMPTY
                    | CONSTRUCTION_REASON_INDUSTRY_BUILDING
                    | CONSTRUCTION_REASON_QUEUE_ITEMS_UNKNOWN
                    | CONSTRUCTION_REASON_INDUSTRIES_UNKNOWN
                    | CONSTRUCTION_REASON_PROBE_FAILURE
                    | CONSTRUCTION_REASON_MUTATION_RACE;
    private static final int MARKET_SCHEDULER_COMPONENT_ALL =
            MARKET_SCHEDULER_COMPONENT_ENGINE
                    | MARKET_SCHEDULER_COMPONENT_ECONOMY
                    | MARKET_SCHEDULER_COMPONENT_ENTITY
                    | MARKET_SCHEDULER_COMPONENT_SAVE
                    | MARKET_SCHEDULER_COMPONENT_BATCH_PROTOCOL
                    | MARKET_SCHEDULER_COMPONENT_MARKET_SEMANTICS
                    | MARKET_SCHEDULER_COMPONENT_MILITARY_BASE
                    | MARKET_SCHEDULER_COMPONENT_LIONS_GUARD
                    | MARKET_SCHEDULER_COMPONENT_RECENT_UNREST
                    | MARKET_SCHEDULER_COMPONENT_BASE_INDUSTRY
                    | MARKET_SCHEDULER_COMPONENT_CONSTRUCTION_QUEUE;
    private static final AtomicInteger MARKET_SCHEDULER_COMPONENTS = new AtomicInteger();
    private static volatile boolean marketSchedulerReady;
    private static final ThreadLocal<MarketSchedulerTick> MARKET_SCHEDULER_TICK =
            ThreadLocal.withInitial(MarketSchedulerTick::new);
    private static final ThreadLocal<MarketSchedulerBatch> MARKET_SCHEDULER_BATCH =
            ThreadLocal.withInitial(MarketSchedulerBatch::new);
    private static final ThreadLocal<MarketAdvanceBatchStack> MARKET_ADVANCE_BATCH_STACK =
            ThreadLocal.withInitial(MarketAdvanceBatchStack::new);
    private static final ThreadLocal<MarketAdvanceInvocationContext> MARKET_ADVANCE_INVOCATION =
            ThreadLocal.withInitial(MarketAdvanceInvocationContext::new);
    private static final ThreadLocal<ConstructionProbe> CONSTRUCTION_PROBE =
            ThreadLocal.withInitial(ConstructionProbe::new);
    private static volatile VarHandle baseIndustryBuildingHandle;
    private static volatile boolean baseIndustryBuildingHandleResolved;
    private static volatile WeakIdentityMap<MarketAPI, MarketScheduleState> MARKET_SCHEDULER_STATES =
            new WeakIdentityMap<>();
    private static volatile WeakIdentityMap<ConstructionQueue, WeakReference<MarketAPI>>
            CONSTRUCTION_QUEUE_OWNERS = new WeakIdentityMap<>();
    private static volatile ConstructionDiagnostics CONSTRUCTION_DIAGNOSTICS;
    private static volatile Map<Object, CommRelaySystemIndex> COMM_RELAY_SYSTEM_INDEXES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Object CAMPAIGN_CACHE_LIFECYCLE_LOCK = new Object();
    private static final Object CAMPAIGN_CACHE_MAINTENANCE_LOCK = new Object();
    private static volatile long cacheMaintenanceGeneration = -1L;
    private static volatile long nextCacheMaintenanceNanos;

    /**
     * The engine reference must itself remain weak: lifecycle hooks are a cleanup
     * trigger, not a new process-lifetime owner of campaign state.
     */
    private static volatile WeakReference<Object> activeCampaignEngine = new WeakReference<>(null);
    /**
     * ReachEconomy ownership is tracked separately because an Economy.advance()
     * that outlives an engine switch must never publish into the new generation.
     * This reference remains weak for the same reason as activeCampaignEngine.
     */
    private static volatile WeakReference<ReachEconomy> activeReachEconomy =
            new WeakReference<>(null);
    private static volatile boolean campaignEngineObserved;
    private static volatile boolean campaignCacheGenerationActive;
    private static volatile long campaignCacheGeneration;
    private static volatile long marketSchedulerSimulationTick;
    private static volatile long marketSchedulerRenderBatch;
    private static final AtomicLong MARKET_SCHEDULER_DEBT_SEQUENCE = new AtomicLong();
    private static final AtomicLong MARKET_DELIVERY_SEQUENCE = new AtomicLong();
    // Simulation ticks preserve engine ordering. Render batches, delimited by
    // CampaignEngine.setFastForwardIteration(false), own cadence so fast-forward
    // cannot multiply expensive Market.advance callbacks per visual frame.
    private static final long NO_CAMPAIGN_TRANSITION = -1L;
    private static long pendingCampaignTransition = NO_CAMPAIGN_TRANSITION;
    private static WeakReference<Object> pendingCampaignEngine = new WeakReference<>(null);

    private static final LongAdder RETAIN_CALLS = new LongAdder();
    private static final LongAdder RETAIN_UPPER_BOUND = new LongAdder();
    private static final LongAdder RETAIN_KEYS = new LongAdder();
    private static final LongAdder RETAIN_REMOVED = new LongAdder();
    private static final LongAdder RETAIN_EQUALITY_FALLBACKS = new LongAdder();
    private static final LongAdder HOVER_HITS = new LongAdder();
    private static final LongAdder HOVER_MISSES = new LongAdder();
    private static final LongAdder MAP_LOCATION_HITS = new LongAdder();
    private static final LongAdder MAP_LOCATION_MISSES = new LongAdder();
    private static final LongAdder ARROW_HITS = new LongAdder();
    private static final LongAdder ARROW_MISSES = new LongAdder();
    private static final LongAdder INTEL_INDEX_HITS = new LongAdder();
    private static final LongAdder INTEL_INDEX_BUILDS = new LongAdder();
    private static final LongAdder LABEL_TOTAL = new LongAdder();
    private static final LongAdder LABEL_CANDIDATES = new LongAdder();
    private static final LongAdder NEBULA_HITS = new LongAdder();
    private static final LongAdder NEBULA_MISSES = new LongAdder();
    private static final LongAdder SAMPLE_CLEAR_SKIPS = new LongAdder();
    private static final LongAdder CAMPAIGN_LISTENER_RUNS = new LongAdder();
    private static final LongAdder CAMPAIGN_LISTENER_SKIPS = new LongAdder();
    private static final LongAdder ROUTE_JUMP_INDEX_HITS = new LongAdder();
    private static final LongAdder ROUTE_JUMP_INDEX_BUILDS = new LongAdder();
    private static final LongAdder ROUTE_JUMP_INDEX_FALLBACKS = new LongAdder();
    private static final LongAdder ROUTE_SYSTEM_INDEX_HITS = new LongAdder();
    private static final LongAdder ROUTE_SYSTEM_INDEX_BUILDS = new LongAdder();
    private static final LongAdder ROUTE_SYSTEM_INDEX_FALLBACKS = new LongAdder();
    private static final LongAdder CAMPAIGN_CACHE_RESETS = new LongAdder();
    private static final LongAdder LABEL_OWNER_IDLE_EVICTIONS = new LongAdder();
    private static final LongAdder LABEL_OWNER_LIMIT_EVICTIONS = new LongAdder();
    private static final LongAdder INTEL_ENTITY_OWNER_IDLE_EVICTIONS = new LongAdder();
    private static final LongAdder INTEL_ENTITY_OWNER_LIMIT_EVICTIONS = new LongAdder();
    private static final LongAdder HIT_OWNER_IDLE_EVICTIONS = new LongAdder();
    private static final LongAdder HIT_OWNER_LIMIT_EVICTIONS = new LongAdder();
    private static final LongAdder HIT_EXPIRED_CELL_EVICTIONS = new LongAdder();
    private static final LongAdder SCRATCH_LIST_TRIMS = new LongAdder();
    private static final LongAdder SCRATCH_SET_TRIMS = new LongAdder();
    private static final LongAdder SCRATCH_IDENTITY_TRIMS = new LongAdder();
    private static final LongAdder SCRATCH_FRAME_REPLACEMENTS = new LongAdder();
    private static volatile ScratchGaugeSnapshot SCRATCH_GAUGES = ScratchGaugeSnapshot.EMPTY;
    private static final LongAdder ECONOMY_LOCATION_CHECKS = new LongAdder();
    private static final LongAdder ECONOMY_LOCATION_DIRTY = new LongAdder();
    private static final LongAdder ECONOMY_LOCATION_SKIPS = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_SIMULATION_TICKS = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_RENDER_BATCHES = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_FAST_FORWARD_TICKS = new LongAdder();
    private static final AtomicLong MARKET_SCHEDULER_MAX_TICKS_PER_BATCH = new AtomicLong();
    private static final LongAdder MARKET_SCHEDULER_CALLS = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_ECONOMY_CALLS = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_PLANET_CONDITION_CALLS = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_FULL_ADVANCES = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_ACCUMULATED_CALLS = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_HOT_ADVANCES = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_HIDDEN_ADVANCES = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_PER_SIMULATION_TICK_CALLS = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_PER_SIMULATION_TICK_FLAG_CHANGES = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_SAVE_FLUSHES = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_NOT_READY_TICKS = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_NOT_READY_CALLS = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_NESTED_TICKS = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_OUTSIDE_TICK_CALLS = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_LIFECYCLE_INACTIVE_CALLS = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_FRAME_CAPTURE_FAILURES = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_INVALID_SOURCE_CALLS = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_INVALID_AMOUNT_CALLS = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_AMOUNT_OVERFLOW_SPLITS = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_STATE_FAILURES = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_REENTRANT_CALLS = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_MULTIPLE_SOURCE_TICK_CALLS = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_POLICY_FAILURES = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_POLICY_AUDITS = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_POLICY_CACHE_HITS = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_DUE_CHECKS = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_DUE_ENTRIES = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_NOT_DUE_SKIPS = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_INITIAL_PHASE_DEFERRALS = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_BATCH_TOUCHED_ENTRIES = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_ADVANCE_FAILURES = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_DISABLED_MARKET_CALLS = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_SYNCHRONIZED_ADVANCES = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_DIRECT_SYNCHRONIZATIONS = new LongAdder();
    private static final LongAdder MARKET_SCHEDULER_SAVE_FLUSH_FAILURES = new LongAdder();
    private static final AtomicLong PENDING_MARKET_STEPS_HIGH_WATER = new AtomicLong();
    private static final AtomicLong PENDING_MARKET_RUNS_HIGH_WATER = new AtomicLong();
    private static final LongAdder PENDING_RUN_OVERFLOW_FLUSHES = new LongAdder();
    private static final LongAdder COALESCED_MARKET_FLUSHES = new LongAdder();
    private static final DoubleAdder COALESCED_MARKET_AMOUNT = new DoubleAdder();
    private static final LongAdder MARKET_BATCH_CONTEXTS_CREATED = new LongAdder();
    private static final LongAdder MILITARY_BASE_REPLAYED_STEPS = new LongAdder();
    private static final LongAdder LIONS_GUARD_REPLAYED_STEPS = new LongAdder();
    private static final LongAdder RECENT_UNREST_REPLAYED_STEPS = new LongAdder();
    private static final LongAdder CONSTRUCTION_FULL_RATE_CALLS = new LongAdder();
    private static final LongAdder CONSTRUCTION_MODE_ENTRIES = new LongAdder();
    private static final LongAdder CONSTRUCTION_MODE_EXITS = new LongAdder();
    private static final LongAdder CONSTRUCTION_BOUNDARY_EXACT_REPLAYS = new LongAdder();
    private static final LongAdder CONSTRUCTION_SCANS = new LongAdder();
    private static final LongAdder CONSTRUCTION_DIRTY_SCANS = new LongAdder();
    private static final LongAdder CONSTRUCTION_SAFETY_AUDIT_SCANS = new LongAdder();
    private static final LongAdder CONSTRUCTION_FORCED_SCANS = new LongAdder();
    private static final LongAdder CONSTRUCTION_CACHED_DECISIONS = new LongAdder();
    private static final LongAdder CONSTRUCTION_DETECTED_QUEUE_NON_EMPTY = new LongAdder();
    private static final LongAdder CONSTRUCTION_DETECTED_INDUSTRY_BUILDING = new LongAdder();
    private static final LongAdder CONSTRUCTION_DETECTED_INDUSTRY_UPGRADING = new LongAdder();
    private static final LongAdder CONSTRUCTION_DETECTED_REPORTED_BUILDING_WITHOUT_RAW =
            new LongAdder();
    private static final LongAdder CONSTRUCTION_DETECTED_MULTIPLE_REASONS = new LongAdder();
    private static final LongAdder CONSTRUCTION_DETECTED_PROBE_FAILURE = new LongAdder();
    private static final LongAdder CONSTRUCTION_QUEUE_NULL_SCANS = new LongAdder();
    private static final LongAdder CONSTRUCTION_QUEUE_ITEMS_NULL_SCANS = new LongAdder();
    private static final LongAdder CONSTRUCTION_INDUSTRIES_NULL_SCANS = new LongAdder();
    private static final LongAdder CONSTRUCTION_QUEUE_ITEMS_OBSERVED = new LongAdder();
    private static final LongAdder CONSTRUCTION_INDUSTRIES_SCANNED = new LongAdder();
    private static final AtomicLong CONSTRUCTION_MAX_QUEUE_ITEMS = new AtomicLong();
    private static final AtomicLong CONSTRUCTION_MAX_INDUSTRIES_SCANNED = new AtomicLong();
    private static final LongAdder CONSTRUCTION_AUDIT_STATE_CHANGES = new LongAdder();
    private static final LongAdder CONSTRUCTION_AUDIT_FALSE_TO_TRUE = new LongAdder();
    private static final LongAdder CONSTRUCTION_AUDIT_TRUE_TO_FALSE = new LongAdder();
    private static final LongAdder CONSTRUCTION_REASON_CHANGES = new LongAdder();
    private static final LongAdder CONSTRUCTION_MUTATION_RACES = new LongAdder();
    private static final LongAdder CONSTRUCTION_DIAGNOSTIC_SAMPLES_DROPPED = new LongAdder();
    private static final LongAdder SAVE_COALESCED_MARKETS = new LongAdder();
    private static final LongAdder SAVE_EXACT_REPLAY_MARKETS = new LongAdder();
    private static final LongAdder SAVE_EXACT_REPLAY_STEPS = new LongAdder();
    private static final LongAdder SAVE_FLUSH_DURATION_NANOS = new LongAdder();
    // Aggregate persistent-snapshot counters are retained for backward-compatible logs.
    private static final LongAdder ECONOMY_PERSISTENT_SNAPSHOT_REBUILDS = new LongAdder();
    private static final LongAdder ECONOMY_PERSISTENT_SNAPSHOT_AUDITS = new LongAdder();
    private static final LongAdder ECONOMY_PERSISTENT_SNAPSHOT_MISMATCHES = new LongAdder();
    private static final LongAdder ECONOMY_PERSISTENT_SNAPSHOT_ELEMENTS = new LongAdder();
    // Split counters make it possible to tell whether markets, conditions, or industries
    // still dominate after the owner-local snapshot path is enabled.
    private static final LongAdder ECONOMY_MARKET_PERSISTENT_HITS = new LongAdder();
    private static final LongAdder ECONOMY_MARKET_PERSISTENT_REBUILDS = new LongAdder();
    private static final LongAdder ECONOMY_MARKET_PERSISTENT_AUDITS = new LongAdder();
    private static final LongAdder ECONOMY_MARKET_PERSISTENT_MISMATCHES = new LongAdder();
    private static final LongAdder ECONOMY_MARKET_PERSISTENT_ELEMENTS = new LongAdder();
    private static final LongAdder MARKET_CONDITION_PERSISTENT_HITS = new LongAdder();
    private static final LongAdder MARKET_CONDITION_PERSISTENT_REBUILDS = new LongAdder();
    private static final LongAdder MARKET_CONDITION_PERSISTENT_AUDITS = new LongAdder();
    private static final LongAdder MARKET_CONDITION_PERSISTENT_MISMATCHES = new LongAdder();
    private static final LongAdder MARKET_CONDITION_PERSISTENT_ELEMENTS = new LongAdder();
    private static final LongAdder MARKET_INDUSTRY_PERSISTENT_HITS = new LongAdder();
    private static final LongAdder MARKET_INDUSTRY_PERSISTENT_REBUILDS = new LongAdder();
    private static final LongAdder MARKET_INDUSTRY_PERSISTENT_AUDITS = new LongAdder();
    private static final LongAdder MARKET_INDUSTRY_PERSISTENT_MISMATCHES = new LongAdder();
    private static final LongAdder MARKET_INDUSTRY_PERSISTENT_ELEMENTS = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_MARKET_CALLS = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_ENTRIES = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_ACTIVE_ADVANCES = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_INACTIVE_SKIPS = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_REAPPLIES = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_AVAILABLE_REAPPLIES = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_DIRTY_SIGNALS = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_EXPOSURES = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_AUDITS = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_REBUILDS = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_WAKEUP_ENQUEUES = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_WAKEUP_DRAINS = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_WAKEUP_INSERTS = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_FALLBACKS = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_EXACT_VANILLA_ENTRIES = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_AOTD_NATIVE_ENTRIES = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_VANILLA_ONLY_ENTRIES = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_EXACT_VANILLA_ACTIVE = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_AOTD_NATIVE_ACTIVE = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_VANILLA_ONLY_ACTIVE = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_ACTIVE_AVAILABLE = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_ACTIVE_AOTD_EXCESS = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_ACTIVE_AOTD_DEFICIT = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_ACTIVE_TRADE = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_ACTIVE_TRADE_PLUS = new LongAdder();
    private static final LongAdder COMMODITY_TEMPORAL_ACTIVE_TRADE_MINUS = new LongAdder();
    // The four high-volume market counters are sampled once per 64 calls. Exact
    // counters remain only on rare state transitions/fallbacks so observability
    // cannot become a new economy hot path.
    private static final int COMMODITY_TEMPORAL_METRIC_SAMPLE_SHIFT = 6;
    private static final int COMMODITY_TEMPORAL_METRIC_SAMPLE_MASK =
            (1 << COMMODITY_TEMPORAL_METRIC_SAMPLE_SHIFT) - 1;
    private static int commodityTemporalMetricsTick;
    private static final LongAdder PERSISTENT_STRUCTURE_EPOCHS = new LongAdder();
    private static final LongAdder ECONOMY_LOCATION_EPOCH_HITS = new LongAdder();
    private static final LongAdder ECONOMY_LOCATION_AUDITS = new LongAdder();
    private static final LongAdder ECONOMY_LOCATION_SEQUENCE_MISMATCHES = new LongAdder();
    private static final LongAdder SHIP_SCRATCH_LISTS = new LongAdder();
    private static final LongAdder SHIP_SCRATCH_SETS = new LongAdder();
    private static final LongAdder PARTICLE_GROUPS = new LongAdder();
    private static final LongAdder PARTICLE_EXPIRED = new LongAdder();
    private static final LongAdder PARTICLE_LINEAR_REMOVALS = new LongAdder();
    private static final LongAdder COMM_RELAY_INDEX_HITS = new LongAdder();
    private static final LongAdder COMM_RELAY_INDEX_BUILDS = new LongAdder();
    private static final LongAdder COMM_RELAY_INDEX_FALLBACKS = new LongAdder();
    private static final LongAdder COMM_RELAY_VALIDATION_SCANS = new LongAdder();
    private static final LongAdder COMM_RELAY_SYSTEMS_VALIDATED = new LongAdder();
    private static final LongAdder COMM_RELAY_SYSTEMS_TOTAL = new LongAdder();
    private static final LongAdder COMM_RELAY_SYSTEMS_CANDIDATE = new LongAdder();
    private static final LongAdder TEXT_READ_CALLS = new LongAdder();
    private static final LongAdder TEXT_READ_CHARS = new LongAdder();
    private static final LongAdder TEXT_READ_CR_REMOVED = new LongAdder();
    private static final LongAdder STARTUP_LOG_JSON = new LongAdder();
    private static final LongAdder STARTUP_LOG_CSV = new LongAdder();
    private static final LongAdder STARTUP_LOG_SCRIPT = new LongAdder();
    private static final LongAdder STARTUP_LOG_RULE = new LongAdder();
    private static final LongAdder STARTUP_LOG_SPEC = new LongAdder();
    private static final LongAdder STARTUP_LOG_TEXTURE = new LongAdder();
    private static final LongAdder STARTUP_LOG_SOUND = new LongAdder();
    private static final LongAdder STARTUP_LOG_OTHER = new LongAdder();
    private static volatile NebulaCacheSlot nebulaCacheSlot = new NebulaCacheSlot();
    private static volatile float cachedGridSpacing = -1f;
    private static volatile float cachedGridWidth = Float.NaN;
    private static volatile float cachedGridHeight = Float.NaN;

    private StarsectorPrepatcherHooks() {}

    static void configure(PrepatcherConfig optimizerConfig, Path root) {
        config = optimizerConfig;
        modRoot = root;
        strategicJumpRuntimeCapability = STRATEGIC_JUMP_CAPABILITY_UNKNOWN;
        StarsectorPrepatcherStrategicJumpIndex.reset();
        CONSTRUCTION_DIAGNOSTICS = null;
        if (optimizerConfig.marketConstructionDiagnostics) {
            try {
                ConstructionDiagnostics diagnostics =
                        new ConstructionDiagnostics(optimizerConfig, root);
                CONSTRUCTION_DIAGNOSTICS = diagnostics;
                diagnostics.start();
            } catch (Throwable failure) {
                CONSTRUCTION_DIAGNOSTICS = null;
                PrepatcherLog.error("Market construction diagnostics initialization failed; "
                        + "scheduler behavior is unchanged and CSV sampling is disabled.",
                        failure);
            }
        }
        if (optimizerConfig.directMarketObservation) {
            try {
                DirectMarketObserver observer = new DirectMarketObserver(optimizerConfig, root);
                DIRECT_MARKET_OBSERVER = observer;
                observer.start();
            } catch (Throwable failure) {
                DIRECT_MARKET_OBSERVER = null;
                PrepatcherLog.error("Direct Market.advance observation initialization failed; "
                        + "market behavior remains unchanged and observation is disabled.", failure);
            }
        }
        if (optimizerConfig.statsLogIntervalSeconds > 0) {
            Thread stats = new Thread(StarsectorPrepatcherHooks::statsLoop, "StarsectorPrepatcher-Stats");
            stats.setDaemon(true);
            stats.setPriority(Thread.MIN_PRIORITY);
            stats.start();
        }
    }

    // ---------------------------------------------------------------------
    // Startup and save/load helpers
    // ---------------------------------------------------------------------

    /**
     * Streaming replacement for LoadingUtils' 1 MiB-per-resource byte buffer.
     * UTF-8 decoder state is preserved across chunk boundaries and carriage
     * returns are removed while copying, matching the observable vanilla result.
     */
    public static String readUtf8Normalized(InputStream input) throws IOException {
        if (input == null) throw new NullPointerException("input");
        TextReadScratch scratch = TEXT_READ_SCRATCH.get();
        char[] buffer = scratch.acquire();
        StringBuilder output = new StringBuilder(8192);
        long chars = 0L;
        long removed = 0L;
        try {
            Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
            while (true) {
                int count = reader.read(buffer, 0, buffer.length);
                if (count < 0) break;
                if (count == 0) continue;
                chars += count;
                int runStart = 0;
                for (int i = 0; i < count; i++) {
                    if (buffer[i] != '\r') continue;
                    if (i > runStart) output.append(buffer, runStart, i - runStart);
                    runStart = i + 1;
                    removed++;
                }
                if (runStart < count) output.append(buffer, runStart, count - runStart);
            }
            TEXT_READ_CALLS.increment();
            TEXT_READ_CHARS.add(chars);
            TEXT_READ_CR_REMOVED.add(removed);
            return output.toString();
        } finally {
            scratch.release();
            input.close();
        }
    }

    /** Called when the transformer removed an entire high-volume INFO block. */
    public static void startupLogSuppressed(int category) {
        startupLogCounter(category).increment();
    }

    /**
     * Drop-in replacement for Logger.info(Object) at broad SpecStore sites.
     * The already-computed message is deliberately ignored; WARN/ERROR calls are
     * untouched and the optimization can be disabled independently.
     */
    public static void suppressStartupInfo(Object logger, Object message, int category) {
        startupLogCounter(category).increment();
    }

    private static LongAdder startupLogCounter(int category) {
        return switch (category) {
            case 0 -> STARTUP_LOG_JSON;
            case 1 -> STARTUP_LOG_CSV;
            case 2 -> STARTUP_LOG_SCRIPT;
            case 3 -> STARTUP_LOG_RULE;
            case 4 -> STARTUP_LOG_SPEC;
            case 5 -> STARTUP_LOG_TEXTURE;
            case 6 -> STARTUP_LOG_SOUND;
            default -> STARTUP_LOG_OTHER;
        };
    }

    public static String removeCarriageReturns(String value) {
        return removeCharacter(value, '\r');
    }

    public static String replaceNewlinesWithSpace(String value) {
        return replaceCharacter(value, '\n', ' ');
    }

    public static String[] splitNewlines(String value) {
        return splitLiteralDiscardTrailing(value, "\n");
    }

    public static String[] splitOrBlocks(String value) {
        return splitLiteralDiscardTrailing(value, "\nOR\n");
    }

    public static String[] splitColon(String value) {
        return splitLiteralDiscardTrailing(value, ":");
    }

    /** 0 means keep vanilla's 60 Hz progress-render ceiling. */
    public static float saveLoadProgressMinIntervalSeconds() {
        PrepatcherConfig c = config;
        int hz = c == null ? 0 : c.saveLoadProgressHz;
        if (hz <= 0) return 1f / 60f;
        return 1f / hz;
    }

    private static String removeCharacter(String value, char unwanted) {
        if (value == null) throw new NullPointerException("value");
        int first = value.indexOf(unwanted);
        if (first < 0) return value;
        int length = value.length();
        char[] result = new char[length - 1];
        value.getChars(0, first, result, 0);
        int out = first;
        for (int i = first + 1; i < length; i++) {
            char c = value.charAt(i);
            if (c != unwanted) result[out++] = c;
        }
        return new String(result, 0, out);
    }

    private static String replaceCharacter(String value, char target, char replacement) {
        if (value == null) throw new NullPointerException("value");
        int first = value.indexOf(target);
        if (first < 0) return value;
        char[] result = value.toCharArray();
        result[first] = replacement;
        for (int i = first + 1; i < result.length; i++) {
            if (result[i] == target) result[i] = replacement;
        }
        return new String(result);
    }

    /** Exact positive-width literal equivalent of String.split(regex) with limit=0. */
    private static String[] splitLiteralDiscardTrailing(String value, String delimiter) {
        if (value == null) throw new NullPointerException("value");
        int first = value.indexOf(delimiter);
        if (first < 0) return new String[]{value};

        // First pass computes the exact result size after String.split(..., 0)
        // discards trailing empty elements. This avoids an ArrayList and its
        // backing array in the rules loader's high-volume parsing path.
        int delimiterLength = delimiter.length();
        int start = 0;
        int index = first;
        int segment = 0;
        int retained = 0;
        while (index >= 0) {
            segment++;
            if (index > start) retained = segment;
            start = index + delimiterLength;
            index = value.indexOf(delimiter, start);
        }
        segment++;
        if (start < value.length()) retained = segment;
        if (retained == 0) return new String[0];

        String[] result = new String[retained];
        start = 0;
        index = first;
        for (int i = 0; i < retained; i++) {
            if (index < 0) {
                result[i] = value.substring(start);
            } else {
                result[i] = value.substring(start, index);
                start = index + delimiterLength;
                index = value.indexOf(delimiter, start);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // Campaign cache lifecycle
    // ---------------------------------------------------------------------

    /**
     * Closes the current cache generation immediately before CampaignEngine
     * publishes its structurally verified singleton-field write.
     *
     * All caches below are useful only inside one campaign generation. Several
     * values intentionally contain locations, entities or UI objects that lead
     * back to ObjectRepository.listener and therefore to CampaignEngine. Weak
     * keys cannot break that value-to-key cycle, so changing the active engine is
     * the authoritative eviction boundary.
     *
     * The lifecycle monitor is deliberately released before old roots are
     * detached. Detachment swaps volatile map and nebula-slot references instead
     * of acquiring their monitors: cache builders may call mod/game code, so
     * cross-monitor clearing would permit an ABBA deadlock during a re-entrant
     * reset. This hook is identity-idempotent and deliberately noexcept.
     *
     * @return a transition token for {@link #completeCampaignEngineChange}, or a
     * negative value when the requested identity is already active
     */
    public static long beginCampaignEngineChange(Object engine) {
        long transition = NO_CAMPAIGN_TRANSITION;
        try {
            synchronized (CAMPAIGN_CACHE_LIFECYCLE_LOCK) {
                Object observed = activeCampaignEngine.get();
                if (pendingCampaignTransition == NO_CAMPAIGN_TRANSITION
                        && campaignEngineObserved && observed == engine
                        && campaignCacheGenerationActive == (engine != null)) {
                    return NO_CAMPAIGN_TRANSITION;
                }
                // Close the generation before eviction so no new cache path can
                // enter while the old state is being detached.
                campaignCacheGenerationActive = false;
                activeReachEconomy = new WeakReference<>(null);
                transition = ++campaignCacheGeneration;
                pendingCampaignTransition = transition;
                pendingCampaignEngine = new WeakReference<>(engine);
                CAMPAIGN_CACHE_RESETS.increment();
            }
            clearCampaignCaches();
            return transition;
        } catch (Throwable ignored) {
            // CampaignEngine lifecycle semantics always take precedence over an
            // optional optimization cache. Returning the no-op token leaves all
            // stateful caches disabled if cleanup could not finish.
            return NO_CAMPAIGN_TRANSITION;
        }
    }

    /**
     * Activates a successfully published campaign only after setInstance() has
     * completed its Global/Factory updates and other normal-return work. A stale,
     * mismatched or failed transition remains safely disabled.
     */
    public static void completeCampaignEngineChange(Object engine, long transition) {
        boolean clearMismatchedGeneration = false;
        try {
            synchronized (CAMPAIGN_CACHE_LIFECYCLE_LOCK) {
                boolean noOpMatches = transition == NO_CAMPAIGN_TRANSITION
                        && pendingCampaignTransition == NO_CAMPAIGN_TRANSITION
                        && campaignEngineObserved && activeCampaignEngine.get() == engine
                        && campaignCacheGenerationActive == (engine != null);
                if (noOpMatches) return;
                if (transition != NO_CAMPAIGN_TRANSITION
                        && pendingCampaignTransition == transition
                        && pendingCampaignEngine.get() == engine) {
                    activeCampaignEngine = new WeakReference<>(engine);
                    activeReachEconomy = new WeakReference<>(reachEconomyOf(engine));
                    campaignEngineObserved = true;
                    pendingCampaignTransition = NO_CAMPAIGN_TRANSITION;
                    pendingCampaignEngine = new WeakReference<>(null);
                    // resetInstance(null) intentionally leaves caches inactive until
                    // the next successfully completed non-null setInstance().
                    campaignCacheGenerationActive = engine != null;
                    return;
                }

                // Concurrent/out-of-order lifecycle calls are not expected from
                // the game, but must still fail closed: never leave cache identity
                // active for an engine that may no longer be the singleton.
                campaignCacheGenerationActive = false;
                campaignCacheGeneration++;
                activeCampaignEngine = new WeakReference<>(null);
                activeReachEconomy = new WeakReference<>(null);
                campaignEngineObserved = false;
                pendingCampaignTransition = NO_CAMPAIGN_TRANSITION;
                pendingCampaignEngine = new WeakReference<>(null);
                CAMPAIGN_CACHE_RESETS.increment();
                clearMismatchedGeneration = true;
            }
        } catch (Throwable ignored) {
            // Fail closed: the generation remains inactive and hooks use vanilla.
        }
        if (clearMismatchedGeneration) clearCampaignCaches();
    }

    private static void clearCampaignCaches() {
        // Detach each process-lifetime root instead of waiting for its monitor.
        // In-flight calls retain only their local old map/slot; epoch checks stop
        // them from publishing into a current generation, and the detached roots
        // become collectible when those calls end.
        INTEL_CACHE = Collections.synchronizedMap(new WeakHashMap<>());
        LABEL_INDEXES = new IdentityHashMap<>();
        INTEL_ENTITY_INDEXES = new IdentityHashMap<>();
        HIT_CACHES = new IdentityHashMap<>();
        SAMPLE_CLEAR_TIMES = new WeakHashMap<>();
        CAMPAIGN_LISTENER_STATES = Collections.synchronizedMap(new WeakHashMap<>());
        ROUTE_JUMP_INDEXES = Collections.synchronizedMap(new WeakHashMap<>());
        ROUTE_SYSTEM_INDEXES = Collections.synchronizedMap(new WeakHashMap<>());
        StarsectorPrepatcherStrategicJumpIndex.reset();
        ECONOMY_LOCATION_STATES = Collections.synchronizedMap(new WeakHashMap<>());
        MARKET_SCHEDULER_STATES = new WeakIdentityMap<>();
        CONSTRUCTION_QUEUE_OWNERS = new WeakIdentityMap<>();
        COMM_RELAY_SYSTEM_INDEXES = Collections.synchronizedMap(new WeakHashMap<>());
        marketSchedulerSimulationTick = 0L;
        marketSchedulerRenderBatch = 0L;
        cacheMaintenanceGeneration = -1L;
        nextCacheMaintenanceNanos = 0L;
        MARKET_SCHEDULER_DEBT_SEQUENCE.set(0L);
        MARKET_SCHEDULER_TICK.remove();
        MARKET_SCHEDULER_BATCH.remove();
        MARKET_ADVANCE_BATCH_STACK.remove();
        MARKET_ADVANCE_INVOCATION.remove();
        nebulaCacheSlot = new NebulaCacheSlot();
        // A lifecycle callback may be re-entrant inside a transformed scratch
        // scope. Detach the old Scratch without clearing collections that the
        // interrupted vanilla method still owns, then mirror only its open-lease
        // depth into a fresh container. Subsequent hooks cannot append campaign
        // objects to the detached container, and the replacement removes itself
        // when the interrupted scope's generated finally hook completes.
        SCRATCH_GAUGES = ScratchGaugeSnapshot.EMPTY;
        Scratch previous = SCRATCH.get();
        int openLeases = previous.scopeDepth;
        SCRATCH.remove();
        if (openLeases > 0) {
            Scratch replacement = new Scratch();
            replacement.scopeDepth = openLeases;
            replacement.removeWhenIdle = true;
            SCRATCH.set(replacement);
        }
    }

    private static boolean campaignCachesReady(long generation) {
        return campaignCacheGenerationActive && campaignCacheGeneration == generation
                && activeCampaignEngine.get() != null;
    }

    private static boolean campaignCachesReady(long generation, Object engine) {
        return campaignCacheGenerationActive && campaignCacheGeneration == generation
                && activeCampaignEngine.get() == engine;
    }

    private static boolean campaignCachesReady(long generation, ReachEconomy economy) {
        return economy != null && campaignCacheGenerationActive
                && campaignCacheGeneration == generation
                && activeCampaignEngine.get() != null
                && activeReachEconomy.get() == economy;
    }

    private static ReachEconomy reachEconomyOf(Object engine) {
        try {
            if (!(engine instanceof CampaignEngine campaignEngine)) return null;
            Economy campaignEconomy = campaignEngine.getEconomy();
            return campaignEconomy == null ? null : campaignEconomy.getEconomy();
        } catch (Throwable ignored) {
            // A missing/non-vanilla economy disables only this optimization.
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Campaign-thread cache maintenance and delayed scratch trimming
    // ---------------------------------------------------------------------

    /** Throttled campaign-thread maintenance entry installed in CampaignEngine.advance(). */
    public static void campaignCacheMaintenanceTick() {
        PrepatcherConfig c = config;
        long generation = campaignCacheGeneration;
        if (c != null && c.strategicJumpDestinationIndex
                && campaignCachesReady(generation)
                && strategicJumpIndexCapability() == STRATEGIC_JUMP_CAPABILITY_READY) {
            StarsectorPrepatcherStrategicJumpIndex.maintenance(
                    c, generation, StarsectorPrepatcherHooks::nanoTime);
        }
        runCacheMaintenance(false);
    }

    /** Forced pre-save entry. Idle TTLs and scratch grace periods still apply. */
    public static void runCacheMaintenance(boolean forced) {
        PrepatcherConfig c = config;
        long generation = campaignCacheGeneration;
        if (c == null || !campaignCachesReady(generation)) return;
        long now;
        try {
            now = nanoTime();
        } catch (Throwable ignored) {
            return;
        }

        synchronized (CAMPAIGN_CACHE_MAINTENANCE_LOCK) {
            if (!campaignCachesReady(generation)) return;
            if (cacheMaintenanceGeneration != generation) {
                cacheMaintenanceGeneration = generation;
                nextCacheMaintenanceNanos = 0L;
            }
            if (!forced && c.cacheMaintenanceIntervalMs > 0
                    && now < nextCacheMaintenanceNanos) {
                return;
            }
            nextCacheMaintenanceNanos = saturatedAdd(now,
                    millisToNanos(c.cacheMaintenanceIntervalMs));
        }

        maintainLabelIndexes(c, generation, now);
        maintainIntelEntityIndexes(c, generation, now);
        maintainHitCaches(c, generation, now);
        trimCurrentScratch(c, now);
        try {
            StarsectorPrepatcherHyperspaceHooks.runPoolMaintenance(now, forced);
        } catch (Throwable ignored) {
            // Hyperspace pooling is independent and optional.
        }
    }

    private static long nanoTime() {
        LongSupplier clock = NANO_CLOCK;
        return clock == null ? System.nanoTime() : clock.getAsLong();
    }

    private static long millisToNanos(int millis) {
        if (millis <= 0) return 0L;
        long value = (long) millis * 1_000_000L;
        return value < 0L ? Long.MAX_VALUE : value;
    }

    private static long saturatedAdd(long value, long increment) {
        if (increment <= 0L) return value;
        long result = value + increment;
        if (((value ^ result) & (increment ^ result)) < 0L) return Long.MAX_VALUE;
        return result;
    }

    private static boolean hoverCellFresh(long now, long atNanos, int ttlMs) {
        return ttlMs > 0 && now - atNanos < millisToNanos(ttlMs);
    }

    private static boolean idleExpired(long now, long lastAccess, int ttlMs) {
        if (ttlMs <= 0) return false;
        return now - lastAccess >= millisToNanos(ttlMs);
    }

    private static void maintainLabelIndexes(PrepatcherConfig c, long generation, long now) {
        IdentityHashMap<LinkedHashMap<?, ?>, LabelIndex> indexes = LABEL_INDEXES;
        synchronized (indexes) {
            if (indexes != LABEL_INDEXES || !campaignCachesReady(generation)) return;
            Iterator<Map.Entry<LinkedHashMap<?, ?>, LabelIndex>> iterator =
                    indexes.entrySet().iterator();
            while (iterator.hasNext()) {
                LabelIndex index = iterator.next().getValue();
                if (index.activeUsers == 0
                        && idleExpired(now, index.lastAccessNanos, c.labelOwnerIdleTtlMs)) {
                    iterator.remove();
                    LABEL_OWNER_IDLE_EVICTIONS.increment();
                }
            }
        }
    }

    private static void maintainIntelEntityIndexes(PrepatcherConfig c, long generation, long now) {
        IdentityHashMap<List<?>, IntelEntityIndex> indexes = INTEL_ENTITY_INDEXES;
        synchronized (indexes) {
            if (indexes != INTEL_ENTITY_INDEXES || !campaignCachesReady(generation)) return;
            Iterator<Map.Entry<List<?>, IntelEntityIndex>> iterator = indexes.entrySet().iterator();
            while (iterator.hasNext()) {
                IntelEntityIndex index = iterator.next().getValue();
                if (index.activeUsers == 0
                        && idleExpired(now, index.lastAccessNanos,
                        c.intelEntityIndexOwnerIdleTtlMs)) {
                    iterator.remove();
                    INTEL_ENTITY_OWNER_IDLE_EVICTIONS.increment();
                }
            }
        }
    }

    private static void maintainHitCaches(PrepatcherConfig c, long generation, long now) {
        IdentityHashMap<Object, HitCache> caches = HIT_CACHES;
        synchronized (caches) {
            if (caches != HIT_CACHES || !campaignCachesReady(generation)) return;
            Iterator<Map.Entry<Object, HitCache>> iterator = caches.entrySet().iterator();
            while (iterator.hasNext()) {
                HitCache cache = iterator.next().getValue();
                if (cache.activeUsers == 0
                        && idleExpired(now, cache.lastAccessNanos, c.hoverOwnerIdleTtlMs)) {
                    iterator.remove();
                    HIT_OWNER_IDLE_EVICTIONS.increment();
                    continue;
                }
                synchronized (cache) {
                    if (c.hoverCellPruneIntervalMs > 0
                            && now - cache.lastCellPruneNanos
                            >= millisToNanos(c.hoverCellPruneIntervalMs)) {
                        cache.lastCellPruneNanos = now;
                        long cellTtl = millisToNanos(c.hoverCellTtlMs);
                        if (cellTtl > 0L && !cache.cells.isEmpty()) {
                            Iterator<CellHit> cells = cache.cells.values().iterator();
                            while (cells.hasNext()) {
                                if (!hoverCellFresh(now, cells.next().atNanos, c.hoverCellTtlMs)) {
                                    cells.remove();
                                    HIT_EXPIRED_CELL_EVICTIONS.increment();
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void trimCurrentScratch(PrepatcherConfig c, long now) {
        if (!c.scratchTrimEnabled) {
            publishScratchGauges(SCRATCH.get());
            return;
        }
        Scratch scratch = SCRATCH.get();
        for (int i = scratch.scopeDepth; i < scratch.frames.size(); i++) {
            ScratchFrame frame = scratch.frames.get(i);
            if (frame.shouldTrim(c, now)) {
                countScratchTrimKinds(frame, c);
                scratch.frames.set(i, new ScratchFrame());
                SCRATCH_FRAME_REPLACEMENTS.increment();
            }
        }
        if (scratch.scopeDepth == 0
                && scratch.frames.size() > SCRATCH_RETAINED_FRAME_FLOOR
                && scratch.trailingFramesLastUseNanos != Long.MIN_VALUE
                && now - scratch.trailingFramesLastUseNanos
                >= millisToNanos(c.scratchTrimGraceMs)) {
            int removed = scratch.frames.size() - SCRATCH_RETAINED_FRAME_FLOOR;
            scratch.frames.subList(SCRATCH_RETAINED_FRAME_FLOOR,
                    scratch.frames.size()).clear();
            scratch.trailingFramesLastUseNanos = Long.MIN_VALUE;
            SCRATCH_FRAME_REPLACEMENTS.add(removed);
        }
        publishScratchGauges(scratch);
    }

    private static void countScratchTrimKinds(ScratchFrame frame, PrepatcherConfig c) {
        frame.refreshHighWater();
        if (frame.listHighWater > c.scratchTrimMaxListCapacity) {
            SCRATCH_LIST_TRIMS.increment();
        }
        if (frame.setHighWater > c.scratchTrimMaxSetEntries) {
            SCRATCH_SET_TRIMS.increment();
        }
        if (frame.identityHighWater > c.scratchTrimMaxIdentityEntries) {
            SCRATCH_IDENTITY_TRIMS.increment();
        }
    }

    private static void publishScratchGauges(Scratch scratch) {
        long listCapacity = 0L;
        long setCapacity = 0L;
        long identityCapacity = 0L;
        int pooledLists = 0;
        int pooledSets = 0;
        for (ScratchFrame frame : scratch.frames) {
            listCapacity += frame.retainedListCapacityEstimate();
            setCapacity += frame.retainedSetCapacityEstimate();
            identityCapacity += frame.identityHighWater;
            pooledLists += frame.pooledLists.size();
            pooledSets += frame.pooledSets.size();
        }
        SCRATCH_GAUGES = new ScratchGaugeSnapshot(scratch.frames.size(), pooledLists,
                pooledSets, listCapacity, setCapacity, identityCapacity);
    }

    private static <K, V> boolean evictIdleAndLruForInsert(
            IdentityHashMap<K, V> map, K protectedOwner, long now, int idleTtlMs,
            int limit, LongAdder idleCounter, LongAdder limitCounter) {
        Iterator<Map.Entry<K, V>> iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<K, V> entry = iterator.next();
            if (entry.getKey() != protectedOwner
                    && ownerActiveUsers(entry.getValue()) == 0
                    && idleExpired(now, ownerLastAccessNanos(entry.getValue()), idleTtlMs)) {
                iterator.remove();
                idleCounter.increment();
            }
        }
        if (map.containsKey(protectedOwner) || map.size() < limit) return true;
        K oldestKey = null;
        long oldestAccess = Long.MAX_VALUE;
        for (Map.Entry<K, V> entry : map.entrySet()) {
            if (entry.getKey() == protectedOwner
                    || ownerActiveUsers(entry.getValue()) > 0) continue;
            long access = ownerLastAccessNanos(entry.getValue());
            if (oldestKey == null || access < oldestAccess) {
                oldestKey = entry.getKey();
                oldestAccess = access;
            }
        }
        if (oldestKey != null) {
            map.remove(oldestKey);
            limitCounter.increment();
            return true;
        }
        return false;
    }

    private static long ownerLastAccessNanos(Object value) {
        if (value instanceof LabelIndex index) return index.lastAccessNanos;
        if (value instanceof IntelEntityIndex index) return index.lastAccessNanos;
        if (value instanceof HitCache cache) return cache.lastAccessNanos;
        throw new IllegalArgumentException("Unsupported owner-cache value: "
                + (value == null ? "null" : value.getClass().getName()));
    }

    private static int ownerActiveUsers(Object value) {
        if (value instanceof LabelIndex index) return index.activeUsers;
        if (value instanceof IntelEntityIndex index) return index.activeUsers;
        if (value instanceof HitCache cache) return cache.activeUsers;
        throw new IllegalArgumentException("Unsupported owner-cache value: "
                + (value == null ? "null" : value.getClass().getName()));
    }

    // ---------------------------------------------------------------------
    // Map reconciliation and scratch collection reuse
    // ---------------------------------------------------------------------

    /** Opens a re-entrancy-safe lease for pooled values used by one transformed method. */
    public static void beginScratchScope() {
        Scratch scratch = SCRATCH.get();
        int next = scratch.scopeDepth;
        scratch.frameAt(next);
        scratch.scopeDepth = next + 1;
        if (scratch.scopeDepth > scratch.maxDepthSinceIdle) {
            scratch.maxDepthSinceIdle = scratch.scopeDepth;
        }
    }

    /**
     * Closes the current lease. This hook is deliberately noexcept and idempotent:
     * it is also called by a generated catch-all handler.
     */
    public static void endScratchScope() {
        try {
            Scratch scratch = SCRATCH.get();
            if (scratch.scopeDepth <= 0) return;
            int index = --scratch.scopeDepth;
            ScratchFrame frame = scratch.frameAt(index);
            PrepatcherConfig c = config;
            long now = c != null && c.scratchTrimEnabled ? nanoTime() : 0L;
            frame.clear(c, now);
            if (c != null && c.scratchTrimEnabled && frame.shouldTrim(c, now)) {
                countScratchTrimKinds(frame, c);
                scratch.frames.set(index, new ScratchFrame());
                SCRATCH_FRAME_REPLACEMENTS.increment();
            }
            if (scratch.scopeDepth == 0) {
                if (c != null && c.scratchTrimEnabled
                        && scratch.maxDepthSinceIdle > SCRATCH_RETAINED_FRAME_FLOOR) {
                    scratch.trailingFramesLastUseNanos = now;
                }
                scratch.maxDepthSinceIdle = 0;
                if (scratch.removeWhenIdle) SCRATCH.remove();
            }
        } catch (Throwable ignored) {
            // Never mask an original return value or exception.
        }
    }

    /**
     * Replaces the exact H.renderStuff() reconciliation call.
     *
     * Vanilla invokes LinkedHashMap.keySet().retainAll(ArrayList), which scans the
     * ArrayList once for every icon key. The map keys are concrete campaign-entity
     * instances; all vanilla 0.98a-RC8 CampaignEntity implementations inherit
     * Object.equals/hashCode, so identity membership is the native domain semantic.
     *
     * A reusable IdentityHashMap avoids entity hashCode implementations for vanilla
     * entities and the explicit iterator avoids the generic AbstractCollection path.
     * Modded entity classes overriding equals/hashCode use a reusable HashSet so the
     * original Java collection semantics are retained.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean retainAllFast(Set target, Collection keep, Object mapOwner) {
        PrepatcherConfig c = config;
        if (c == null || !c.mapRenderStuff) {
            return target.retainAll(keep);
        }

        ScratchFrame scratch = SCRATCH.get().scopeFrame();
        IdentityHashMap<Object, Boolean> membership = scratch.identityMembership;
        clearIdentityMembership(membership);
        boolean customEquality = false;
        if (keep instanceof List list) {
            for (int i = 0, size = list.size(); i < size; i++) {
                Object entity = list.get(i);
                membership.put(entity, Boolean.TRUE);
                if (entity != null && CUSTOM_EQUALITY.get(entity.getClass())) customEquality = true;
            }
        } else {
            for (Object entity : keep) {
                membership.put(entity, Boolean.TRUE);
                if (entity != null && CUSTOM_EQUALITY.get(entity.getClass())) customEquality = true;
            }
        }

        scratch.observeIdentitySize(membership.size());

        if (!customEquality) {
            for (Object entity : target) {
                if (entity != null && CUSTOM_EQUALITY.get(entity.getClass())) {
                    customEquality = true;
                    break;
                }
            }
        }

        Set<Object> equalityMembership = null;
        if (customEquality) {
            // Preserve equals/hashCode semantics for modded entity classes that
            // override Object equality. Vanilla entities stay on the faster
            // identity path. LinkedHashMap keys already require a valid hashCode
            // contract, so HashSet membership matches normal Java collection rules.
            equalityMembership = scratch.equalityMembership;
            equalityMembership.clear();
            equalityMembership.addAll(keep);
            scratch.observeRetainedSet(equalityMembership);
            RETAIN_EQUALITY_FALLBACKS.increment();
        }

        RETAIN_CALLS.increment();
        RETAIN_UPPER_BOUND.add((long) target.size() * (long) keep.size());
        RETAIN_KEYS.add(target.size());

        boolean changed = false;
        long removed = 0L;
        try {
            Iterator iterator = target.iterator();
            while (iterator.hasNext()) {
                Object entity = iterator.next();
                boolean present = customEquality
                        ? equalityMembership.contains(entity)
                        : membership.containsKey(entity);
                if (!present) {
                    iterator.remove();
                    changed = true;
                    removed++;
                }
            }
            if (removed != 0L) RETAIN_REMOVED.add(removed);
            return changed;
        } finally {
            clearIdentityMembership(membership);
            if (equalityMembership != null) equalityMembership.clear();
        }
    }

    /**
     * IdentityHashMap.clear() scans its full capacity even when size is zero.
     * Scratch frames use a deliberately large membership table, so an empty clear
     * at every scope exit is far more expensive than the constant-time size check.
     */
    private static void clearIdentityMembership(IdentityHashMap<?, ?> membership) {
        if (!membership.isEmpty()) membership.clear();
    }

    private static final ClassValue<Boolean> CUSTOM_EQUALITY = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                Method equals = type.getMethod("equals", Object.class);
                Method hashCode = type.getMethod("hashCode");
                return equals.getDeclaringClass() != Object.class
                        || hashCode.getDeclaringClass() != Object.class;
            } catch (ReflectiveOperationException | SecurityException ex) {
                // Unknown/custom class shape: prefer collection semantics over the
                // identity optimization.
                return Boolean.TRUE;
            }
        }
    };


    /**
     * One-time per-runtime-class eligibility check for the direct dormant
     * BaseIndustry fast path. The per-frame wrapper never reflects or touches a
     * global identity map. Subclasses that override advance(), isDisrupted(), or
     * getDisruptedKey() retain the exact vanilla callback path.
     */
    private static final ClassValue<Boolean> BASE_INDUSTRY_DORMANT_ELIGIBLE =
            new ClassValue<>() {
                @Override
                protected Boolean computeValue(Class<?> type) {
                    try {
                        if (!BaseIndustry.class.isAssignableFrom(type)) return Boolean.FALSE;
                        return type.getMethod("advance", float.class).getDeclaringClass()
                                        == BaseIndustry.class
                                && type.getMethod("isDisrupted").getDeclaringClass()
                                        == BaseIndustry.class
                                && type.getMethod("getDisruptedKey").getDeclaringClass()
                                        == BaseIndustry.class;
                    } catch (ReflectiveOperationException | SecurityException ex) {
                        return Boolean.FALSE;
                    }
                }
            };

    /** Called only on the first full BaseIndustry.advance() for an instance. */
    public static boolean isBaseIndustryDormantFastPathEligible(Object industry) {
        return industry != null
                && BASE_INDUSTRY_DORMANT_ELIGIBLE.get(industry.getClass());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static ArrayList borrowEntityList(Collection source) {
        PrepatcherConfig c = config;
        if (c == null || !c.mapRenderStuff) return new ArrayList(source);
        ArrayList list = SCRATCH.get().scopeFrame().entityList;
        list.clear();
        list.addAll(source);
        SCRATCH.get().scopeFrame().observeRetainedList(list);
        return list;
    }

    /**
     * Reuses the defensive snapshots taken by BaseLocation.advance(). The
     * returned object is deliberately exposed only as List: transformed sites
     * are structurally proven to iterate it without mutation or escape.
     * Collection.toArray(T[]) preserves the vanilla point-in-time copy while
     * reusing the backing array after the first capacity warm-up.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List borrowLocationAdvanceSnapshot(Collection source) {
        PrepatcherConfig c = config;
        if (c == null || !c.campaignSnapshotReuse) return new ArrayList(source);
        return borrowCampaignSnapshot(source);
    }

    /** Same snapshot contract for BaseLocation.advanceEvenIfPaused(). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List borrowPausedLocationSnapshot(Collection source) {
        PrepatcherConfig c = config;
        if (c == null || !c.campaignSnapshotReuse) return new ArrayList(source);
        return borrowCampaignSnapshot(source);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List borrowCampaignSnapshot(Collection source) {
        ScratchFrame frame = SCRATCH.get().scopeFrame();
        SnapshotList snapshot = frame.campaignSnapshotAt(frame.campaignSnapshotIndex++);
        snapshot.copyFrom(source);
        frame.observeListSize(snapshot.size());
        return snapshot;
    }

    /**
     * Reuses BaseCampaignEntity.runScripts()' defensive copy. Empty script
     * lists use the JDK's allocation-free immutable empty list; non-empty lists
     * keep the same point-in-time iteration semantics as the vanilla copy.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List borrowEntityScriptSnapshot(Collection source) {
        PrepatcherConfig c = config;
        if (c == null || !c.entityScriptSnapshotReuse) return new ArrayList(source);
        if (source.isEmpty()) return Collections.emptyList();
        return borrowCampaignSnapshot(source);
    }

    /** Legacy helper; 0.7.1 uses an inline whole-method empty-work guard instead. */
    @SuppressWarnings("rawtypes")
    public static Iterator memoryExpireIterator(List source) {
        PrepatcherConfig c = config;
        if (c != null && c.emptyMemoryAdvanceFastPath && source.isEmpty()) {
            return Collections.emptyIterator();
        }
        return source.iterator();
    }

    /** Legacy helper; 0.7.1 uses an inline whole-method empty-work guard instead. */
    @SuppressWarnings("rawtypes")
    public static Iterator memoryRequireIterator(Collection source) {
        PrepatcherConfig c = config;
        if (c != null && c.emptyMemoryAdvanceFastPath && source.isEmpty()) {
            return Collections.emptyIterator();
        }
        return source.iterator();
    }


    // ---------------------------------------------------------------------
    // Exp8: economy, combat scratch, particles and hyperspace comm relays
    // ---------------------------------------------------------------------

    /**
     * Compatibility path used when persistent structure epochs are disabled.
     * It retains the original every-frame ordered fingerprint comparison.
     */
    public static void updateEconomyLocationMapIfNeeded(ReachEconomy economy) {
        updateEconomyLocationMapIfNeeded0(economy, null, false);
    }

    /**
     * Epoch-aware location-index invalidation. Explicit dirty marks made by mods
     * remain authoritative because ReachEconomy.updateLocationMap() is still
     * invoked every frame. The epoch suppresses only the optimizer's own full
     * market fingerprint scan; a periodic audit catches direct live-list, market
     * id, and containing-location mutations that bypass transformed mutators.
     */
    public static void updateEconomyLocationMapIfNeededPersistent(
            ReachEconomy economy, Object persistentState) {
        updateEconomyLocationMapIfNeeded0(economy, persistentState, true);
    }

    private static void updateEconomyLocationMapIfNeeded0(
            ReachEconomy economy, Object persistentState, boolean persistentRequested) {
        PrepatcherConfig c = config;
        long generation = campaignCacheGeneration;
        if (economy == null || c == null || !c.economyLocationCache
                || !campaignCachesReady(generation, economy)) {
            if (economy != null) {
                economy.setLocationCacheNeedsUpdate(true);
                economy.updateLocationMap();
            }
            return;
        }

        PersistentSnapshotState ownerState = persistentRequested
                && c.economyPersistentSnapshots
                && persistentState instanceof PersistentSnapshotState candidate
                && candidate.acceptKind(0)
                ? candidate : null;
        boolean forceDirty = true;
        List<MarketAPI> markets = null;
        EconomyLocationState previous = null;
        long now = System.nanoTime();
        long auditNs = Math.max(0L, c.economyStructureAuditMs) * 1_000_000L;

        try {
            markets = economy.getMarkets();
            if (ownerState != null) {
                long observedEpoch = ownerState.epoch;
                previous = ownerState.locationState;
                boolean epochChanged = ownerState.locationEpoch != observedEpoch;
                boolean sourceChanged = previous != null && ownerState.locationSourceIdentity != markets;
                boolean audit = previous == null || epochChanged || sourceChanged
                        || auditNs <= 0L || now >= ownerState.nextLocationAuditNanos;
                if (!audit) {
                    forceDirty = false;
                    ECONOMY_LOCATION_EPOCH_HITS.increment();
                } else {
                    ECONOMY_LOCATION_AUDITS.increment();
                    int mismatch = previous == null
                            ? EconomyLocationState.SEQUENCE_MISMATCH
                            : previous.mismatchKind(markets);
                    // A direct replacement/reorder/add/remove that bypassed Economy mutators
                    // must also invalidate the persistent market snapshot. Location/id-only
                    // changes rebuild ReachEconomy indexes but do not require a new list snapshot.
                    boolean sequenceChanged = sourceChanged
                            || mismatch == EconomyLocationState.SEQUENCE_MISMATCH;
                    if (!epochChanged && sequenceChanged) {
                        ownerState.epoch++;
                        observedEpoch = ownerState.epoch;
                        persistentEpoch(ownerState.kind);
                        persistentMismatch(0);
                        ECONOMY_LOCATION_SEQUENCE_MISMATCHES.increment();
                    }
                    forceDirty = previous == null || epochChanged || sourceChanged
                            || mismatch != EconomyLocationState.MATCH;
                    if (!forceDirty) {
                        ownerState.locationEpoch = observedEpoch;
                        ownerState.nextLocationAuditNanos = nextAuditNanos(now, auditNs);
                    }
                }
            } else {
                Map<ReachEconomy, EconomyLocationState> states = ECONOMY_LOCATION_STATES;
                synchronized (states) {
                    if (states == ECONOMY_LOCATION_STATES
                            && campaignCachesReady(generation, economy)) {
                        previous = states.get(economy);
                    }
                }
                forceDirty = previous == null
                        || previous.mismatchKind(markets) != EconomyLocationState.MATCH;
            }
            ECONOMY_LOCATION_CHECKS.increment();
            if (forceDirty) ECONOMY_LOCATION_DIRTY.increment();
            else ECONOMY_LOCATION_SKIPS.increment();
        } catch (Throwable ignored) {
            forceDirty = true;
            if (ownerState != null) {
                ownerState.locationState = null;
                ownerState.locationSourceIdentity = null;
                ownerState.locationEpoch = Long.MIN_VALUE;
                ownerState.nextLocationAuditNanos = 0L;
            } else {
                Map<ReachEconomy, EconomyLocationState> states = ECONOMY_LOCATION_STATES;
                synchronized (states) {
                    if (states == ECONOMY_LOCATION_STATES
                            && campaignCachesReady(generation, economy)) {
                        states.remove(economy);
                    }
                }
            }
        }

        if (forceDirty) economy.setLocationCacheNeedsUpdate(true);
        // Explicit mod dirtiness stays authoritative: the original updater is always
        // invoked even when the owner-local epoch/fingerprint says nothing changed.
        economy.updateLocationMap();

        if (forceDirty && markets != null && campaignCachesReady(generation, economy)) {
            try {
                if (ownerState != null) {
                    ownerState.locationState = EconomyLocationState.capture(markets,
                            ownerState.epoch, now);
                    ownerState.locationSourceIdentity = markets;
                    ownerState.locationEpoch = ownerState.epoch;
                    ownerState.nextLocationAuditNanos = nextAuditNanos(now, auditNs);
                } else {
                    EconomyLocationState captured = EconomyLocationState.capture(
                            markets, Long.MIN_VALUE, now);
                    Map<ReachEconomy, EconomyLocationState> states = ECONOMY_LOCATION_STATES;
                    synchronized (states) {
                        if (states == ECONOMY_LOCATION_STATES
                                && campaignCachesReady(generation, economy)) {
                            states.put(economy, captured);
                        }
                    }
                }
            } catch (Throwable ignored) {
                if (ownerState != null) {
                    ownerState.locationState = null;
                    ownerState.locationSourceIdentity = null;
                    ownerState.locationEpoch = Long.MIN_VALUE;
                    ownerState.nextLocationAuditNanos = 0L;
                } else {
                    Map<ReachEconomy, EconomyLocationState> states = ECONOMY_LOCATION_STATES;
                    synchronized (states) {
                        if (states == ECONOMY_LOCATION_STATES
                                && campaignCachesReady(generation, economy)) {
                            states.remove(economy);
                        }
                    }
                }
            }
        }
    }

    private static long nextAuditNanos(long now, long interval) {
        if (interval <= 0L) return now;
        long next = now + interval;
        return next < now ? Long.MAX_VALUE : next;
    }

    // ---------------------------------------------------------------------
    // Exact Market.advance batch history
    // ---------------------------------------------------------------------

    /** Binds the top deferred-history frame to one concrete Market.advance invocation. */
    public static void beginMarketAdvanceInvocation(MarketAPI market) {
        MarketAdvanceInvocationContext invocation = MARKET_ADVANCE_INVOCATION.get();
        invocation.depth++;
        MarketAdvanceBatchFrame frame = MARKET_ADVANCE_BATCH_STACK.get().current();
        if (frame != null && frame.visible && frame.market == market
                && frame.boundInvocationDepth == 0) {
            frame.boundInvocationDepth = invocation.depth;
        }
    }

    /** Completes the concrete Market.advance invocation opened above. */
    public static void endMarketAdvanceInvocation() {
        MarketAdvanceInvocationContext invocation = MARKET_ADVANCE_INVOCATION.get();
        if (invocation.depth <= 0) {
            MARKET_ADVANCE_INVOCATION.remove();
            return;
        }
        invocation.depth--;
        if (invocation.depth == 0) MARKET_ADVANCE_INVOCATION.remove();
    }

    public static boolean hasCurrentMarketAdvanceBatch(MarketAPI market) {
        if (market == null) return false;
        MarketAdvanceBatchFrame frame = MARKET_ADVANCE_BATCH_STACK.get().current();
        if (frame == null || !frame.visible || frame.market != market) return false;
        int invocationDepth = MARKET_ADVANCE_INVOCATION.get().depth;
        return invocationDepth > 0 && frame.boundInvocationDepth == invocationDepth;
    }

    public static int currentMarketAdvanceBatchRunCount(MarketAPI market) {
        MarketAdvanceBatchFrame frame = requireCurrentMarketAdvanceBatch(market);
        return frame.runCount;
    }

    public static float currentMarketAdvanceBatchRunAmount(MarketAPI market, int runIndex) {
        MarketAdvanceBatchFrame frame = requireCurrentMarketAdvanceBatch(market);
        if (runIndex < 0 || runIndex >= frame.runCount) {
            throw new IndexOutOfBoundsException("runIndex=" + runIndex
                    + ", runCount=" + frame.runCount);
        }
        return frame.runAmounts[runIndex];
    }

    public static int currentMarketAdvanceBatchRunRepeats(MarketAPI market, int runIndex) {
        MarketAdvanceBatchFrame frame = requireCurrentMarketAdvanceBatch(market);
        if (runIndex < 0 || runIndex >= frame.runCount) {
            throw new IndexOutOfBoundsException("runIndex=" + runIndex
                    + ", runCount=" + frame.runCount);
        }
        return frame.runRepeats[runIndex];
    }

    private static MarketAdvanceBatchFrame requireCurrentMarketAdvanceBatch(MarketAPI market) {
        if (!hasCurrentMarketAdvanceBatch(market)) {
            throw new IllegalStateException("No Market.advance batch for the current invocation");
        }
        return MARKET_ADVANCE_BATCH_STACK.get().current();
    }

    /** Called by the three transformed vanilla component wrappers. */
    public static void recordMarketComponentReplayedStep(int component) {
        switch (component) {
            case 1 -> MILITARY_BASE_REPLAYED_STEPS.increment();
            case 2 -> LIONS_GUARD_REPLAYED_STEPS.increment();
            case 3 -> RECENT_UNREST_REPLAYED_STEPS.increment();
            default -> throw new IllegalArgumentException("Unknown replay component: " + component);
        }
    }

    /** RecentUnrest is no longer invoked by vanilla after its condition removes itself. */
    public static boolean shouldContinueRecentUnrestReplay(MarketAPI market) {
        if (market == null) return false;
        try {
            return market.hasCondition(Conditions.RECENT_UNREST);
        } catch (Throwable failure) {
            // Losing the remaining replay silently is less safe than preserving
            // the original failure semantics and aborting the enclosing advance.
            throwUnchecked(failure);
            return false;
        }
    }

    /** Associates a ConstructionQueue with its weak Market owner without changing serialization. */
    public static ConstructionQueue registerConstructionQueueOwner(
            ConstructionQueue queue, MarketAPI market) {
        if (queue == null || market == null) return queue;
        try {
            WeakReference<MarketAPI> existing = CONSTRUCTION_QUEUE_OWNERS.get(queue);
            if (existing != null && existing.get() == market) return queue;
            CONSTRUCTION_QUEUE_OWNERS.put(queue, new WeakReference<>(market));
        } catch (Throwable ignored) {
            MARKET_SCHEDULER_STATE_FAILURES.increment();
        }
        return queue;
    }

    /** Exact pending replay barrier inserted at ConstructionQueue mutator entry. */
    public static void flushPendingConstructionQueueBeforeMutation(ConstructionQueue queue) {
        if (queue == null) return;
        try {
            WeakReference<MarketAPI> reference = CONSTRUCTION_QUEUE_OWNERS.get(queue);
            MarketAPI market = reference == null ? null : reference.get();
            if (market != null) flushPendingMarketBeforeMutation(market);
        } catch (Throwable failure) {
            throwUnchecked(failure);
        }
    }

    /**
     * Loader-neutral exact replay barrier used by the source-level AoTD ABI.
     * Invalid/non-market objects fail open; a valid market reuses the proven
     * construction mutation barrier and therefore delivers old pending time
     * before the new structure becomes visible.
     */
    public static void flushPendingMarketBeforeAoTDMutation(Object rawMarket) {
        if (!(rawMarket instanceof MarketAPI market)) return;
        flushPendingMarketBeforeMutation(market);
    }

    /** Exact pending replay barrier inserted before construction-structure mutation. */
    public static void flushPendingMarketBeforeMutation(MarketAPI market) {
        if (market == null || !marketSchedulerReady) return;
        PrepatcherConfig c = config;
        if (c == null || !c.marketScheduler) return;
        MarketScheduleState state;
        try {
            state = MARKET_SCHEDULER_STATES.get(market);
        } catch (Throwable ignored) {
            MARKET_SCHEDULER_STATE_FAILURES.increment();
            return;
        }
        if (state == null) return;
        boolean pending;
        synchronized (state) {
            if (state.inAdvance || state.disabled) return;
            pending = state.pendingSteps > 0;
        }
        if (pending) {
            CONSTRUCTION_BOUNDARY_EXACT_REPLAYS.increment();
            deliverPendingExact(market, state, MARKET_ORIGIN_SCHEDULER_FALLBACK, false);
        }
        // The hook runs immediately before the mutation. Leave the state dirty
        // so the next scheduler input scans the post-mutation structure once.
        markConstructionStateDirty(state);
    }

    private static void markConstructionStateDirty(MarketScheduleState state) {
        synchronized (state) {
            long next = state.constructionMutationEpoch + 1L;
            state.constructionMutationEpoch = next <= 0L ? 1L : next;
        }
    }

    // ---------------------------------------------------------------------
    // Direct Market.advance observation
    // ---------------------------------------------------------------------

    /** Registers a transformed direct-call site before its first execution. */
    public static void registerDirectMarketCallSite(long siteId, String metadata) {
        DirectMarketObserver observer = DIRECT_MARKET_OBSERVER;
        if (observer == null) return;
        try {
            observer.registerSite(siteId, metadata);
        } catch (Throwable ignored) {
            // Registration is diagnostic only and must never block class loading.
        }
    }

    /**
     * Synchronous replacement for direct mod call sites. Observation is optional.
     * With the market scheduler enabled, pending engine debt is delivered before
     * the direct amount in the same callback so a later direct call cannot overtake
     * earlier deferred time. Telemetry failures never suppress game/mod behavior.
     */
    public static void observeDirectMarketAdvance(MarketAPI market, float amount,
                                                   long siteId, String metadata) {
        DirectMarketObserver observer = DIRECT_MARKET_OBSERVER;
        MarketAdvanceObservationContext context = MARKET_ADVANCE_CONTEXT.get();
        boolean recursive = context.depth > 0;
        DirectMarketCallToken token = null;
        if (observer != null) {
            try {
                token = observer.beginDirectCall(context, market, amount,
                        siteId, metadata, recursive);
            } catch (Throwable ignored) {
                token = null;
            }
        }

        int previousOrigin = context.origin;
        int previousDepth = context.depth;
        long previousSiteId = context.siteId;
        context.origin = MARKET_ORIGIN_DIRECT_CALL_SITE;
        context.depth = previousDepth + 1;
        context.siteId = siteId;
        boolean success = false;
        try {
            advanceMarketImmediatelyWithPending(
                    market, amount, MARKET_ORIGIN_DIRECT_CALL_SITE);
            success = true;
        } finally {
            context.origin = previousOrigin;
            context.depth = previousDepth;
            context.siteId = previousSiteId;
            if (observer != null) {
                try {
                    observer.endDirectCall(token, success);
                } catch (Throwable ignored) {
                    // Never mask the original return value or exception.
                }
            }
        }
    }

    /** Entry probe inserted into the concrete vanilla Market.advance method. */
    public static void observeMarketAdvanceEntry(MarketAPI market, float amount) {
        DirectMarketObserver observer = DIRECT_MARKET_OBSERVER;
        if (observer == null) return;
        try {
            MarketAdvanceObservationContext context = MARKET_ADVANCE_CONTEXT.get();
            observer.observeMarketEntry(context.origin, context.siteId, market, amount);
        } catch (Throwable ignored) {
            // Observation is strictly fail-open.
        }
    }

    private static void invokeObservedMarketAdvance(MarketAPI market, float amount, int origin) {
        DirectMarketObserver observer = DIRECT_MARKET_OBSERVER;
        if (observer == null) {
            market.advance(amount);
            recordDeliveredMarketAdvance(market, amount, origin);
            return;
        }
        MarketAdvanceObservationContext context = MARKET_ADVANCE_CONTEXT.get();
        int previousOrigin = context.origin;
        int previousDepth = context.depth;
        long previousSiteId = context.siteId;
        context.origin = origin;
        context.depth = previousDepth + 1;
        context.siteId = origin == MARKET_ORIGIN_DIRECT_CALL_SITE
                ? previousSiteId : 0L;
        boolean success = false;
        try {
            market.advance(amount);
            success = true;
        } finally {
            context.origin = previousOrigin;
            context.depth = previousDepth;
            context.siteId = previousSiteId;
            if (success) recordDeliveredMarketAdvance(market, amount, origin);
        }
    }

    /** Records only callbacks that actually returned successfully. */
    private static void recordDeliveredMarketAdvance(
            MarketAPI market, float amount, int origin) {
        // Publish only from this post-success path. The runtime owns a separate
        // weak identity generation table, so immediate/fallback callbacks are
        // visible even before a scheduler state exists for the market.
        if (market != null) {
            boolean publishToAoTD = true;
            try {
                if (!market.isInEconomy()) {
                    boolean conditionOnly = market.isPlanetConditionMarketOnly();
                    StarsectorPrepatcherRuntimeBridge.recordAoTDNonEconomyDelivery(
                            conditionOnly);
                    publishToAoTD = false;
                }
            } catch (Throwable ignored) {
                // Classification is fail-open; an unexpected MarketAPI
                // implementation remains visible to the AoTD diagnostics.
            }
            if (publishToAoTD) {
                StarsectorPrepatcherRuntimeBridge.publishAoTDMarketTimeDelivered(
                        market, amount, origin);
            }
        }
        if (market == null) return;
        MarketScheduleState state;
        try {
            state = MARKET_SCHEDULER_STATES.get(market);
        } catch (Throwable ignored) {
            MARKET_SCHEDULER_STATE_FAILURES.increment();
            return;
        }
        if (state == null) return;
        synchronized (state) {
            state.deliveredCallbacks++;
            if (Float.isFinite(amount)) state.deliveredAmount += amount;
            state.lastDeliveredSequence = MARKET_DELIVERY_SEQUENCE.incrementAndGet();
            state.lastDeliveredOrigin = origin;
            state.lastDeliveredNanos = NANO_CLOCK.getAsLong();
        }
    }


    /** Immediate path shared by scheduler fail-open and transformed core event call sites. */
    public static void advanceMarketSynchronized(MarketAPI market, float amount, int source) {
        if (market == null) throw new NullPointerException("market");
        int origin = source == 1
                ? MARKET_ORIGIN_PLANET_CONDITION_FALLBACK
                : MARKET_ORIGIN_SCHEDULER_FALLBACK;
        advanceMarketImmediatelyWithPending(market, amount, origin);
    }

    private static void advanceMarketImmediatelyWithPending(
            MarketAPI market, float amount, int origin) {
        if (market == null) throw new NullPointerException("market");
        if (!Float.isFinite(amount) || amount < 0f) {
            invokeObservedMarketAdvance(market, amount, origin);
            return;
        }

        MarketScheduleState state = null;
        boolean reentrant = false;
        boolean hasPending = false;
        try {
            state = MARKET_SCHEDULER_STATES.get(market);
            if (state != null) {
                synchronized (state) {
                    reentrant = state.inAdvance;
                    hasPending = !state.disabled && !state.inAdvance
                            && state.pendingSteps > 0;
                }
            }
        } catch (Throwable ignored) {
            MARKET_SCHEDULER_STATE_FAILURES.increment();
            state = null;
        }

        if (reentrant) {
            MARKET_SCHEDULER_REENTRANT_CALLS.increment();
            invokeObservedMarketAdvance(market, amount, origin);
            return;
        }

        if (hasPending && state != null) {
            MARKET_SCHEDULER_SYNCHRONIZED_ADVANCES.increment();
            if (origin == MARKET_ORIGIN_DIRECT_CALL_SITE) {
                MARKET_SCHEDULER_DIRECT_SYNCHRONIZATIONS.increment();
            }
            boolean construction = updateConstructionMode(market, state);
            if (construction) {
                deliverPendingExact(market, state, origin, false);
            } else {
                deliverPendingCoalesced(market, state, origin, false, false);
            }
        }

        // A direct/fail-open/full-rate call is a distinct vanilla step. It is
        // deliberately not included in the deferred batch context.
        invokeObservedMarketAdvance(market, amount, origin);
    }

    private static boolean updateConstructionMode(
            MarketAPI market, MarketScheduleState state) {
        return updateConstructionMode(market, state, false);
    }

    /**
     * Construction policy is mutation-driven. Structural barriers increment an
     * epoch; the expensive queue/industry scan runs only for a new epoch or a
     * rare safety audit. Save uses forceAudit=true.
     */
    private static boolean updateConstructionMode(
            MarketAPI market, MarketScheduleState state, boolean forceAudit) {
        PrepatcherConfig c = config;
        long auditBatch = marketSchedulerRenderBatch;
        long observedEpoch;
        boolean dirtyScan;
        boolean safetyAudit;
        synchronized (state) {
            observedEpoch = state.constructionMutationEpoch;
            long last = state.lastConstructionAuditBatch;
            boolean auditDue = last == Long.MIN_VALUE || auditBatch < last
                    || auditBatch - last >= (c == null
                    ? 60 : c.marketSchedulerConstructionAuditBatches);
            dirtyScan = state.constructionAuditedEpoch != observedEpoch;
            safetyAudit = !forceAudit && !dirtyScan && auditDue;
            if (!(forceAudit || dirtyScan || auditDue)) {
                CONSTRUCTION_CACHED_DECISIONS.increment();
                return state.constructionFullRate;
            }
            state.lastConstructionAuditBatch = auditBatch;
        }

        CONSTRUCTION_SCANS.increment();
        if (forceAudit) CONSTRUCTION_FORCED_SCANS.increment();
        else if (dirtyScan) CONSTRUCTION_DIRTY_SCANS.increment();
        else if (safetyAudit) CONSTRUCTION_SAFETY_AUDIT_SCANS.increment();

        ConstructionDiagnostics diagnostics = CONSTRUCTION_DIAGNOSTICS;
        ConstructionProbe probe = diagnostics == null ? null : CONSTRUCTION_PROBE.get();
        if (probe != null) probe.reset();
        int reasonMask;
        try {
            reasonMask = scanConstructionState(market, probe);
        } catch (Throwable failure) {
            reasonMask = CONSTRUCTION_REASON_PROBE_FAILURE;
            if (probe != null) probe.failure(failure);
        }
        if ((reasonMask & CONSTRUCTION_REASON_PROBE_FAILURE) != 0) {
            MARKET_SCHEDULER_POLICY_FAILURES.increment();
        }
        recordConstructionReasonCounters(reasonMask);
        boolean active = (reasonMask & CONSTRUCTION_REASON_ACTIVE_MASK) != 0;

        boolean oldActive;
        int oldReasonMask;
        int finalReasonMask;
        boolean mutationRace;
        long auditedEpoch;
        long lastAuditBatch;
        synchronized (state) {
            oldActive = state.constructionFullRate;
            oldReasonMask = state.constructionReasonMask;
            mutationRace = state.constructionMutationEpoch != observedEpoch;
            if (mutationRace) {
                CONSTRUCTION_MUTATION_RACES.increment();
                finalReasonMask = reasonMask | CONSTRUCTION_REASON_MUTATION_RACE;
                active = true;
            } else {
                finalReasonMask = reasonMask;
                state.constructionAuditedEpoch = observedEpoch;
            }
            state.constructionReasonMask = finalReasonMask;
            if (oldReasonMask != finalReasonMask) CONSTRUCTION_REASON_CHANGES.increment();
            if (oldActive != active) {
                state.constructionFullRate = active;
                CONSTRUCTION_AUDIT_STATE_CHANGES.increment();
                if (active) {
                    CONSTRUCTION_MODE_ENTRIES.increment();
                    CONSTRUCTION_AUDIT_FALSE_TO_TRUE.increment();
                } else {
                    CONSTRUCTION_MODE_EXITS.increment();
                    CONSTRUCTION_AUDIT_TRUE_TO_FALSE.increment();
                }
            }
            auditedEpoch = state.constructionAuditedEpoch;
            lastAuditBatch = state.lastConstructionAuditBatch;
        }

        if (diagnostics != null) {
            diagnostics.observe(market, probe, forceAudit ? "FORCED"
                            : (dirtyScan ? "DIRTY" : "SAFETY_AUDIT"),
                    observedEpoch, auditedEpoch, lastAuditBatch,
                    oldActive, active, oldReasonMask, finalReasonMask);
        }
        return active;
    }

    private static int scanConstructionState(MarketAPI market, ConstructionProbe probe) {
        int reasonMask = 0;
        ConstructionQueue queue = null;
        try {
            // Market.getConstructionQueue() already performs weak owner registration.
            // Do not repeat that synchronized weak-map write in the policy scanner.
            queue = market.getConstructionQueue();
            if (probe != null) probe.queueClass = queue == null ? "" : queue.getClass().getName();
            if (queue == null) {
                CONSTRUCTION_QUEUE_NULL_SCANS.increment();
            } else {
                List<?> items = queue.getItems();
                if (items == null) {
                    CONSTRUCTION_QUEUE_ITEMS_NULL_SCANS.increment();
                    reasonMask |= CONSTRUCTION_REASON_QUEUE_ITEMS_UNKNOWN;
                    if (probe != null) probe.queueSize = -1;
                } else {
                    int size = items.size();
                    CONSTRUCTION_QUEUE_ITEMS_OBSERVED.add(size);
                    updateMax(CONSTRUCTION_MAX_QUEUE_ITEMS, size);
                    if (probe != null) probe.queueSize = size;
                    if (size > 0) reasonMask |= CONSTRUCTION_REASON_QUEUE_NON_EMPTY;
                }
            }
        } catch (Throwable failure) {
            reasonMask |= CONSTRUCTION_REASON_PROBE_FAILURE;
            if (probe != null) probe.failure(failure);
        }

        try {
            List<Industry> industries = market.getIndustries();
            if (industries == null) {
                CONSTRUCTION_INDUSTRIES_NULL_SCANS.increment();
                reasonMask |= CONSTRUCTION_REASON_INDUSTRIES_UNKNOWN;
                if (probe != null) probe.industryCount = -1;
            } else {
                int size = industries.size();
                CONSTRUCTION_INDUSTRIES_SCANNED.add(size);
                updateMax(CONSTRUCTION_MAX_INDUSTRIES_SCANNED, size);
                if (probe != null) probe.industryCount = size;
                for (int i = 0; i < size; i++) {
                    Industry industry = industries.get(i);
                    if (industry == null) continue;
                    boolean reportedBuilding = false;
                    boolean upgrading = false;
                    Boolean rawBuilding = null;
                    try {
                        reportedBuilding = industry.isBuilding();
                    } catch (Throwable failure) {
                        reasonMask |= CONSTRUCTION_REASON_PROBE_FAILURE;
                        if (probe != null) probe.failure(failure);
                    }
                    try {
                        upgrading = industry.isUpgrading();
                    } catch (Throwable failure) {
                        reasonMask |= CONSTRUCTION_REASON_PROBE_FAILURE;
                        if (probe != null) probe.failure(failure);
                    }
                    try {
                        rawBuilding = rawBaseIndustryBuilding(industry);
                    } catch (Throwable failure) {
                        reasonMask |= CONSTRUCTION_REASON_PROBE_FAILURE;
                        if (probe != null) probe.failure(failure);
                    }
                    int buildingReason = constructionBuildingReason(
                            reportedBuilding, rawBuilding);
                    boolean effectiveBuilding = (buildingReason
                            & CONSTRUCTION_REASON_INDUSTRY_BUILDING) != 0;
                    boolean reportedBuildingWithoutRaw = (buildingReason
                            & CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW) != 0;
                    if (effectiveBuilding) {
                        reasonMask |= CONSTRUCTION_REASON_INDUSTRY_BUILDING;
                        if (probe != null) {
                            probe.captureBuildingIndustry(industry, i, rawBuilding);
                        }
                    }
                    if (reportedBuildingWithoutRaw) {
                        reasonMask |= CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW;
                        if (probe != null) {
                            probe.captureReportedBuildingWithoutRawIndustry(
                                    industry, i, rawBuilding);
                        }
                    }
                    if (upgrading) {
                        reasonMask |= CONSTRUCTION_REASON_INDUSTRY_UPGRADING;
                        if (probe != null) {
                            probe.captureUpgradingIndustry(industry, i, rawBuilding);
                        }
                    }
                    if (probe != null
                            && (reportedBuilding || effectiveBuilding || upgrading)
                            && probe.industryIndex < 0) {
                        probe.captureIndustry(industry, i, reportedBuilding,
                                effectiveBuilding, upgrading);
                    }
                }
            }
        } catch (Throwable failure) {
            reasonMask |= CONSTRUCTION_REASON_PROBE_FAILURE;
            if (probe != null) probe.failure(failure);
        }
        return reasonMask;
    }

    /**
     * Classifies the public building signal against the authoritative raw
     * BaseIndustry field. A null raw value denotes a non-BaseIndustry
     * implementation, for which the interface signal remains authoritative.
     */
    private static int constructionBuildingReason(
            boolean reportedBuilding, Boolean rawBuilding) {
        if (rawBuilding == null) {
            return reportedBuilding ? CONSTRUCTION_REASON_INDUSTRY_BUILDING : 0;
        }
        if (rawBuilding.booleanValue()) {
            return CONSTRUCTION_REASON_INDUSTRY_BUILDING;
        }
        return reportedBuilding
                ? CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW : 0;
    }

    private static VarHandle baseIndustryBuildingHandle() {
        if (!baseIndustryBuildingHandleResolved) {
            synchronized (StarsectorPrepatcherHooks.class) {
                if (!baseIndustryBuildingHandleResolved) {
                    try {
                        baseIndustryBuildingHandle =
                                privateVarHandle(BaseIndustry.class, "building");
                    } catch (Throwable ignored) {
                        baseIndustryBuildingHandle = null;
                    }
                    baseIndustryBuildingHandleResolved = true;
                }
            }
        }
        return baseIndustryBuildingHandle;
    }

    /**
     * Returns the authoritative BaseIndustry construction flag. A null result
     * means the implementation is not a BaseIndustry and the interface method
     * remains the only available signal. Failure to access the known field is
     * treated as a probe failure so the scheduler fails open.
     */
    private static Boolean rawBaseIndustryBuilding(Industry industry) {
        if (!(industry instanceof BaseIndustry)) return null;
        VarHandle handle = baseIndustryBuildingHandle();
        if (handle == null) {
            throw new IllegalStateException(
                    "BaseIndustry.building field is unavailable");
        }
        return (boolean) handle.get(industry);
    }

    private static void recordConstructionReasonCounters(int reasonMask) {
        int semanticReasons = 0;
        if ((reasonMask & CONSTRUCTION_REASON_QUEUE_NON_EMPTY) != 0) {
            CONSTRUCTION_DETECTED_QUEUE_NON_EMPTY.increment();
            semanticReasons++;
        }
        if ((reasonMask & CONSTRUCTION_REASON_INDUSTRY_BUILDING) != 0) {
            CONSTRUCTION_DETECTED_INDUSTRY_BUILDING.increment();
            semanticReasons++;
        }
        if ((reasonMask & CONSTRUCTION_REASON_INDUSTRY_UPGRADING) != 0) {
            CONSTRUCTION_DETECTED_INDUSTRY_UPGRADING.increment();
            semanticReasons++;
        }
        if ((reasonMask & CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW) != 0) {
            CONSTRUCTION_DETECTED_REPORTED_BUILDING_WITHOUT_RAW.increment();
            semanticReasons++;
        }
        if ((reasonMask & CONSTRUCTION_REASON_PROBE_FAILURE) != 0) {
            CONSTRUCTION_DETECTED_PROBE_FAILURE.increment();
            semanticReasons++;
        }
        if ((reasonMask & CONSTRUCTION_REASON_QUEUE_ITEMS_UNKNOWN) != 0) semanticReasons++;
        if ((reasonMask & CONSTRUCTION_REASON_INDUSTRIES_UNKNOWN) != 0) semanticReasons++;
        if (semanticReasons > 1) CONSTRUCTION_DETECTED_MULTIPLE_REASONS.increment();
    }

    private static void updateMax(AtomicLong target, long value) {
        long current;
        while (value > (current = target.get()) && !target.compareAndSet(current, value)) {
            // Retry only when another observation raised the high-water concurrently.
        }
    }

    private static boolean wouldCreateNewPendingRun(
            MarketScheduleState state, float amount) {
        PendingStepRun last = state.pendingRuns == null
                ? null : state.pendingRuns.peekLast();
        return last == null || Float.floatToRawIntBits(last.amount)
                != Float.floatToRawIntBits(amount);
    }

    private static boolean appendPendingStepLocked(
            MarketScheduleState state, float amount) {
        float total = state.pendingAmount + amount;
        if (!Float.isFinite(total) || total < 0f
                || state.pendingSteps == Integer.MAX_VALUE) {
            return false;
        }
        if (state.pendingRuns == null) state.pendingRuns = new ArrayDeque<>();
        PendingStepRun last = state.pendingRuns.peekLast();
        if (last != null && Float.floatToRawIntBits(last.amount)
                == Float.floatToRawIntBits(amount)) {
            if (last.count == Integer.MAX_VALUE) return false;
            last.count++;
        } else {
            state.pendingRuns.addLast(new PendingStepRun(amount));
        }
        state.pendingAmount = total;
        state.pendingSteps++;
        updateMaximum(PENDING_MARKET_STEPS_HIGH_WATER, state.pendingSteps);
        updateMaximum(PENDING_MARKET_RUNS_HIGH_WATER, state.pendingRuns.size());
        return true;
    }

    private static void clearPendingLocked(MarketScheduleState state) {
        state.pendingAmount = 0f;
        state.pendingSteps = 0;
        if (state.pendingRuns != null) state.pendingRuns.clear();
        state.debtSequence = 0L;
        state.sourceMask = 0;
    }

    private static MarketAdvanceBatchFrame detachPendingLocked(
            MarketAPI market, MarketScheduleState state, boolean visible) {
        if (state.pendingSteps <= 0) return null;
        MarketAdvanceBatchStack stack = MARKET_ADVANCE_BATCH_STACK.get();
        MarketAdvanceBatchFrame frame = stack.push(market, state, visible);
        clearPendingLocked(state);
        state.inAdvance = true;
        if (visible) MARKET_BATCH_CONTEXTS_CREATED.increment();
        return frame;
    }

    private static int originForStateLocked(MarketScheduleState state, int fallbackOrigin) {
        if ((state.sourceMask & (1 << 1)) != 0) {
            return MARKET_ORIGIN_PLANET_CONDITION_SCHEDULED;
        }
        if ((state.sourceMask & 1) != 0) return MARKET_ORIGIN_SCHEDULED;
        return fallbackOrigin;
    }

    private static int deliverPendingCoalesced(
            MarketAPI market, MarketScheduleState state, int fallbackOrigin,
            boolean countFullAdvance, boolean saveFlush) {
        MarketAdvanceBatchFrame frame;
        int origin;
        synchronized (state) {
            if (state.disabled || state.inAdvance || state.pendingSteps <= 0) return 0;
            origin = originForStateLocked(state, fallbackOrigin);
            frame = detachPendingLocked(market, state, true);
        }
        if (frame == null) return 0;
        boolean success = false;
        try {
            invokeObservedMarketAdvance(market, frame.totalAmount, origin);
            success = true;
            COALESCED_MARKET_FLUSHES.increment();
            COALESCED_MARKET_AMOUNT.add(frame.totalAmount);
            if (countFullAdvance) MARKET_SCHEDULER_FULL_ADVANCES.increment();
            if (saveFlush) {
                SAVE_COALESCED_MARKETS.increment();
                MARKET_SCHEDULER_SAVE_FLUSHES.increment();
            }
            return frame.totalSteps;
        } catch (Throwable failure) {
            MARKET_SCHEDULER_ADVANCE_FAILURES.increment();
            synchronized (state) {
                state.disabled = true;
                clearPendingLocked(state);
            }
            throwUnchecked(failure);
            return 0;
        } finally {
            synchronized (state) {
                state.inAdvance = false;
            }
            MARKET_ADVANCE_BATCH_STACK.get().pop(frame);
            if (!success && saveFlush) MARKET_SCHEDULER_SAVE_FLUSH_FAILURES.increment();
        }
    }

    private static int deliverPendingExact(
            MarketAPI market, MarketScheduleState state, int fallbackOrigin,
            boolean saveFlush) {
        MarketAdvanceBatchFrame frame;
        int origin;
        synchronized (state) {
            if (state.disabled || state.inAdvance || state.pendingSteps <= 0) return 0;
            origin = originForStateLocked(state, fallbackOrigin);
            frame = detachPendingLocked(market, state, false);
        }
        if (frame == null) return 0;
        int delivered = 0;
        boolean success = false;
        try {
            for (int run = 0; run < frame.runCount; run++) {
                float step = frame.runAmounts[run];
                int repeats = frame.runRepeats[run];
                for (int i = 0; i < repeats; i++) {
                    invokeObservedMarketAdvance(market, step, origin);
                    delivered++;
                }
            }
            success = true;
            if (saveFlush) {
                SAVE_EXACT_REPLAY_MARKETS.increment();
                SAVE_EXACT_REPLAY_STEPS.add(delivered);
                MARKET_SCHEDULER_SAVE_FLUSHES.increment();
            }
            return delivered;
        } catch (Throwable failure) {
            MARKET_SCHEDULER_ADVANCE_FAILURES.increment();
            synchronized (state) {
                state.disabled = true;
                clearPendingLocked(state);
            }
            throwUnchecked(failure);
            return delivered;
        } finally {
            synchronized (state) {
                state.inAdvance = false;
            }
            MARKET_ADVANCE_BATCH_STACK.get().pop(frame);
            if (!success && saveFlush) MARKET_SCHEDULER_SAVE_FLUSH_FAILURES.increment();
        }
    }

    private static int deliverPendingForSave(
            MarketAPI market, MarketScheduleState state, boolean exact) {
        MarketAdvanceBatchFrame frame;
        int origin;
        synchronized (state) {
            if (state.inAdvance) {
                throw new IllegalStateException("Cannot save while a market callback is active");
            }
            if (state.disabled || state.pendingSteps <= 0) return 0;
            // Save attribution is a serialization barrier regardless of which
            // periodic source originally accumulated the debt.
            origin = MARKET_ORIGIN_SAVE_FLUSH;
            frame = detachPendingLocked(market, state, !exact);
        }
        if (frame == null) return 0;

        int delivered = 0;
        try {
            if (exact) {
                for (int run = 0; run < frame.runCount; run++) {
                    int repeats = frame.runRepeats[run];
                    for (int repeat = 0; repeat < repeats; repeat++) {
                        invokeObservedMarketAdvance(
                                market, frame.runAmounts[run], origin);
                        delivered++;
                    }
                }
                SAVE_EXACT_REPLAY_MARKETS.increment();
                SAVE_EXACT_REPLAY_STEPS.add(delivered);
            } else {
                invokeObservedMarketAdvance(market, frame.totalAmount, origin);
                delivered = frame.totalSteps;
                COALESCED_MARKET_FLUSHES.increment();
                COALESCED_MARKET_AMOUNT.add(frame.totalAmount);
                SAVE_COALESCED_MARKETS.increment();
            }
            MARKET_SCHEDULER_SAVE_FLUSHES.increment();
            return delivered;
        } catch (Throwable failure) {
            MARKET_SCHEDULER_SAVE_FLUSH_FAILURES.increment();
            synchronized (state) {
                // Once a callback has started, its side effects are ambiguous.
                // Never restore that detached history for an automatic retry:
                // discard it, disable coalescing for this market, and abort save.
                state.disabled = true;
                clearPendingLocked(state);
            }
            PrepatcherLog.warn("Market scheduler save callback failed after delivery began; "
                    + "detached debt was discarded and this market was switched to immediate "
                    + "execution to prevent duplicate side effects: " + failure);
            throwUnchecked(failure);
            return delivered;
        } finally {
            synchronized (state) {
                state.inAdvance = false;
            }
            MARKET_ADVANCE_BATCH_STACK.get().pop(frame);
        }
    }

    /**
     * Registers one successfully initialized bytecode component of the unified scheduler.
     * Scheduling remains disabled until all core cadence/source/save components and every
     * mandatory semantic boundary, local replay wrapper and construction-mutation barrier have
     * completed class initialization.
     */
    public static void registerMarketSchedulerComponent(int component) {
        if (component != MARKET_SCHEDULER_COMPONENT_ENGINE
                && component != MARKET_SCHEDULER_COMPONENT_ECONOMY
                && component != MARKET_SCHEDULER_COMPONENT_ENTITY
                && component != MARKET_SCHEDULER_COMPONENT_SAVE
                && component != MARKET_SCHEDULER_COMPONENT_BATCH_PROTOCOL
                && component != MARKET_SCHEDULER_COMPONENT_MARKET_SEMANTICS
                && component != MARKET_SCHEDULER_COMPONENT_MILITARY_BASE
                && component != MARKET_SCHEDULER_COMPONENT_LIONS_GUARD
                && component != MARKET_SCHEDULER_COMPONENT_RECENT_UNREST
                && component != MARKET_SCHEDULER_COMPONENT_BASE_INDUSTRY
                && component != MARKET_SCHEDULER_COMPONENT_CONSTRUCTION_QUEUE) {
            throw new IllegalArgumentException("Unknown market scheduler component: " + component);
        }
        int current;
        int updated;
        do {
            current = MARKET_SCHEDULER_COMPONENTS.get();
            updated = current | component;
            if (current == updated) return;
        } while (!MARKET_SCHEDULER_COMPONENTS.compareAndSet(current, updated));
        System.setProperty("starsector.prepatcher.marketSchedulerCapabilityMask",
                Integer.toString(updated));
        if (updated == MARKET_SCHEDULER_COMPONENT_ALL && !marketSchedulerReady) {
            marketSchedulerReady = true;
            PrepatcherLog.info("market scheduler READY: core cadence/source/save components"
                    + " + Market invocation context + MilitaryBase/LionsGuardHQ/RecentUnrest"
                    + " replay wrappers + BaseIndustry/ConstructionQueue mutation barriers");
        }
    }

    /** Opens one simulation tick inside the current render batch. */
    public static void beginMarketSchedulerTick() {
        MarketSchedulerTick tick = MARKET_SCHEDULER_TICK.get();
        tick.depth++;
        if (tick.depth > 1) {
            MARKET_SCHEDULER_NESTED_TICKS.increment();
            return;
        }
        tick.clearContext();

        if (!marketSchedulerReady) {
            MARKET_SCHEDULER_NOT_READY_TICKS.increment();
            return;
        }
        PrepatcherConfig c = config;
        long generation = campaignCacheGeneration;
        if (c == null || !c.marketScheduler || !campaignCachesReady(generation)) return;

        MarketSchedulerBatch batch = MARKET_SCHEDULER_BATCH.get();
        if (batch.finishing) {
            MARKET_SCHEDULER_NESTED_TICKS.increment();
            return;
        }
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) throw new IllegalStateException("Global sector is unavailable");
            LocationAPI currentLocation = sector.getCurrentLocation();
            MarketAPI interactionMarket = null;
            if (c.marketSchedulerHotInteraction) {
                CampaignUIAPI ui = sector.getCampaignUI();
                InteractionDialogAPI dialog = ui == null ? null : ui.getCurrentInteractionDialog();
                SectorEntityToken target = dialog == null ? null : dialog.getInteractionTarget();
                interactionMarket = target == null ? null : target.getMarket();
            }

            if (!batch.open || batch.generation != generation) {
                batch.open(generation, nextMarketSchedulerRenderBatch());
            }
            batch.simulationTicks++;
            batch.currentLocation = currentLocation;
            batch.interactionMarket = interactionMarket;

            long nextTick = marketSchedulerSimulationTick + 1L;
            if (nextTick <= 0L) nextTick = 1L;
            marketSchedulerSimulationTick = nextTick;

            tick.active = true;
            tick.generation = generation;
            tick.simulationTick = nextTick;
            tick.batch = batch.id;
            tick.currentLocation = currentLocation;
            tick.interactionMarket = interactionMarket;
            MARKET_SCHEDULER_SIMULATION_TICKS.increment();
            if (batch.fastForwardIteration) MARKET_SCHEDULER_FAST_FORWARD_TICKS.increment();
        } catch (Throwable ignored) {
            tick.clearContext();
            MARKET_SCHEDULER_FRAME_CAPTURE_FAILURES.increment();
        }
    }

    /** Closes only the current simulation tick; the render batch remains open. */
    public static void endMarketSchedulerTick() {
        MarketSchedulerTick tick = MARKET_SCHEDULER_TICK.get();
        if (tick.depth <= 0) {
            MARKET_SCHEDULER_TICK.remove();
            return;
        }
        if (tick.depth == 1) tick.clearContext();
        tick.depth--;
        if (tick.depth == 0) MARKET_SCHEDULER_TICK.remove();
    }

    /**
     * Called from CampaignEngine.setFastForwardIteration(boolean). Starsector writes false before
     * the iteration loop, true after every simulation tick, then false once after the loop. The
     * final false therefore closes exactly one render batch at every campaign speed.
     */
    public static void marketSchedulerFastForwardIterationChanged(boolean fastForwardIteration) {
        MarketSchedulerBatch batch = MARKET_SCHEDULER_BATCH.get();
        batch.fastForwardIteration = fastForwardIteration;
        if (!fastForwardIteration && batch.open && batch.simulationTicks > 0
                && MARKET_SCHEDULER_TICK.get().depth == 0) {
            finishMarketSchedulerBatch(batch);
        }
    }

    private static long nextMarketSchedulerRenderBatch() {
        long next = marketSchedulerRenderBatch + 1L;
        if (next <= 0L) next = 1L;
        marketSchedulerRenderBatch = next;
        return next;
    }

    /** Common accumulator for every transformed engine-owned MarketAPI.advance call. */
    public static void advanceMarketScheduled(MarketAPI market, float amount, int source) {
        if (market == null) throw new NullPointerException("market");
        MARKET_SCHEDULER_CALLS.increment();
        if (source == 0) MARKET_SCHEDULER_ECONOMY_CALLS.increment();
        else if (source == 1) MARKET_SCHEDULER_PLANET_CONDITION_CALLS.increment();
        else {
            MARKET_SCHEDULER_INVALID_SOURCE_CALLS.increment();
            advanceMarketImmediatelyWithPending(
                    market, amount, MARKET_ORIGIN_SCHEDULER_FALLBACK);
            return;
        }

        int fallbackOrigin = source == 1
                ? MARKET_ORIGIN_PLANET_CONDITION_FALLBACK
                : MARKET_ORIGIN_SCHEDULER_FALLBACK;
        int scheduledOrigin = source == 1
                ? MARKET_ORIGIN_PLANET_CONDITION_SCHEDULED
                : MARKET_ORIGIN_SCHEDULED;

        if (!Float.isFinite(amount) || amount < 0f) {
            MARKET_SCHEDULER_INVALID_AMOUNT_CALLS.increment();
            invokeObservedMarketAdvance(market, amount, fallbackOrigin);
            return;
        }
        if (!marketSchedulerReady) {
            MARKET_SCHEDULER_NOT_READY_CALLS.increment();
            advanceMarketImmediatelyWithPending(market, amount, fallbackOrigin);
            return;
        }

        PrepatcherConfig c = config;
        long generation = campaignCacheGeneration;
        MarketSchedulerTick tick = MARKET_SCHEDULER_TICK.get();
        MarketSchedulerBatch batch = MARKET_SCHEDULER_BATCH.get();
        if (c == null || !c.marketScheduler) {
            advanceMarketImmediatelyWithPending(market, amount, fallbackOrigin);
            return;
        }
        if (!campaignCachesReady(generation)) {
            MARKET_SCHEDULER_LIFECYCLE_INACTIVE_CALLS.increment();
            advanceMarketImmediatelyWithPending(market, amount, fallbackOrigin);
            return;
        }
        if (tick.depth != 1 || !tick.active || tick.generation != generation
                || !batch.open || batch.generation != generation || tick.batch != batch.id) {
            MARKET_SCHEDULER_OUTSIDE_TICK_CALLS.increment();
            advanceMarketImmediatelyWithPending(market, amount, fallbackOrigin);
            return;
        }

        MarketScheduleState state;
        try {
            WeakIdentityMap<MarketAPI, MarketScheduleState> states = MARKET_SCHEDULER_STATES;
            state = states.get(market);
            if (state == null) {
                state = new MarketScheduleState();
                MarketScheduleState existing = states.putIfAbsent(market, state);
                if (existing != null) state = existing;
            }
        } catch (Throwable ignored) {
            MARKET_SCHEDULER_STATE_FAILURES.increment();
            advanceMarketImmediatelyWithPending(market, amount, fallbackOrigin);
            return;
        }

        boolean disabled;
        boolean reentrant;
        boolean auditMemory;
        boolean perSimulationTick;
        synchronized (state) {
            disabled = state.disabled;
            reentrant = state.inAdvance;
            auditMemory = !disabled && !reentrant
                    && !c.marketSchedulerPerSimulationTickMemoryKey.isEmpty()
                    && (state.lastPolicyAuditBatch == Long.MIN_VALUE
                    || batch.id - state.lastPolicyAuditBatch
                    >= c.marketSchedulerPolicyAuditBatches);
            if (auditMemory) state.lastPolicyAuditBatch = batch.id;
            perSimulationTick = state.perSimulationTick;
        }
        if (disabled) {
            MARKET_SCHEDULER_DISABLED_MARKET_CALLS.increment();
            advanceMarketImmediatelyWithPending(market, amount, fallbackOrigin);
            return;
        }
        if (reentrant) {
            MARKET_SCHEDULER_REENTRANT_CALLS.increment();
            invokeObservedMarketAdvance(market, amount, fallbackOrigin);
            return;
        }

        if (auditMemory) {
            try {
                MemoryAPI memory = market.getMemoryWithoutUpdate();
                boolean updated = memory != null
                        && memory.getBoolean(c.marketSchedulerPerSimulationTickMemoryKey);
                synchronized (state) {
                    if (state.perSimulationTick != updated) {
                        state.perSimulationTick = updated;
                        MARKET_SCHEDULER_PER_SIMULATION_TICK_FLAG_CHANGES.increment();
                    }
                    perSimulationTick = state.perSimulationTick;
                }
            } catch (Throwable ignored) {
                MARKET_SCHEDULER_POLICY_FAILURES.increment();
                synchronized (state) {
                    state.perSimulationTick = true;
                    perSimulationTick = true;
                }
            }
        }

        // Construction sequencing spans the entire Market.advance method. Flush
        // older time exactly, then preserve the current vanilla call boundary.
        if (updateConstructionMode(market, state)) {
            deliverPendingExact(market, state, scheduledOrigin, false);
            CONSTRUCTION_FULL_RATE_CALLS.increment();
            invokeObservedMarketAdvance(market, amount, scheduledOrigin);
            return;
        }

        if (perSimulationTick) {
            MARKET_SCHEDULER_PER_SIMULATION_TICK_CALLS.increment();
            advanceMarketImmediatelyWithPending(market, amount, scheduledOrigin);
            return;
        }

        boolean overflowRuns;
        synchronized (state) {
            overflowRuns = state.pendingSteps > 0
                    && wouldCreateNewPendingRun(state, amount)
                    && state.pendingRuns != null
                    && state.pendingRuns.size() >= c.marketSchedulerMaxPendingRuns;
        }
        if (overflowRuns) {
            PENDING_RUN_OVERFLOW_FLUSHES.increment();
            deliverPendingCoalesced(market, state, scheduledOrigin, true, false);
        }

        boolean evaluateDue = false;
        boolean invalidTotal = false;
        synchronized (state) {
            if (state.lastSourceSimulationTick == tick.simulationTick
                    && state.lastSource != source) {
                MARKET_SCHEDULER_MULTIPLE_SOURCE_TICK_CALLS.increment();
            }
            state.lastSourceSimulationTick = tick.simulationTick;
            state.lastSource = source;

            if (state.pendingSteps == 0) {
                state.debtSequence = MARKET_SCHEDULER_DEBT_SEQUENCE.incrementAndGet();
            }
            if (!appendPendingStepLocked(state, amount)) {
                invalidTotal = true;
                if (state.pendingSteps == 0) state.debtSequence = 0L;
            } else {
                int previousSourceMask = state.sourceMask;
                state.sourceMask |= 1 << source;
                // One due decision per render batch is enough unless an economy
                // source joins a condition-only debt later in the same batch.
                evaluateDue = state.lastDueCheckBatch != batch.id
                        || (source == 0 && previousSourceMask != state.sourceMask);
                if (evaluateDue) state.lastDueCheckBatch = batch.id;
            }
        }
        if (invalidTotal) {
            MARKET_SCHEDULER_INVALID_AMOUNT_CALLS.increment();
            MARKET_SCHEDULER_AMOUNT_OVERFLOW_SPLITS.increment();
            deliverPendingCoalesced(market, state, scheduledOrigin, true, false);
            invokeObservedMarketAdvance(market, amount, fallbackOrigin);
            return;
        }
        if (evaluateDue && prepareMarketForBatch(batch, market, state, c)) {
            batch.touched.add(new WeakIdentityEntry<>(market, state));
            MARKET_SCHEDULER_BATCH_TOUCHED_ENTRIES.increment();
        }
        MARKET_SCHEDULER_ACCUMULATED_CALLS.increment();
    }

    private static void finishMarketSchedulerBatch(MarketSchedulerBatch batch) {
        if (!batch.open || batch.finishing) return;
        batch.open = false;
        batch.finishing = true;
        MARKET_SCHEDULER_RENDER_BATCHES.increment();
        updateMaximum(MARKET_SCHEDULER_MAX_TICKS_PER_BATCH, batch.simulationTicks);
        try {
            ArrayList<WeakIdentityEntry<MarketAPI, MarketScheduleState>> entries = batch.touched;
            for (int i = 0; i < entries.size(); i++) {
                WeakIdentityEntry<MarketAPI, MarketScheduleState> entry = entries.get(i);
                deliverMarketAtBatchEnd(batch, entry.key, entry.value);
            }
        } finally {
            batch.clearAfterFinish();
        }
    }

    /**
     * Computes one delivery decision on the first source call of a render batch.
     * Condition-source markets cache stable policy inputs and are added to the
     * batch list only when due. Economy markets retain exact per-batch policy.
     */
    private static boolean prepareMarketForBatch(
            MarketSchedulerBatch batch, MarketAPI market,
            MarketScheduleState state, PrepatcherConfig c) {
        if (market == null || state == null || c == null || !c.marketScheduler) return false;
        MARKET_SCHEDULER_DUE_CHECKS.increment();

        int sourceMask;
        synchronized (state) {
            if (state.disabled || state.inAdvance || state.pendingSteps <= 0) return false;
            sourceMask = state.sourceMask;
        }
        boolean conditionOnlySource = false;
        if (sourceMask == (1 << 1)) {
            try {
                // Source 1 is also used by real economy markets whose primary
                // entity owns planet conditions. Cache/stagger only genuinely
                // non-economy markets; economy markets keep exact hot policy.
                conditionOnlySource = !market.isInEconomy();
            } catch (Throwable failure) {
                MARKET_SCHEDULER_POLICY_FAILURES.increment();
                conditionOnlySource = false;
            }
        }
        boolean hot = false;
        boolean hidden = false;
        int interval = 1;
        int phase = 0;
        try {
            LocationAPI location;
            boolean playerOwned;
            if (conditionOnlySource) {
                boolean audit;
                synchronized (state) {
                    audit = !state.schedulePolicyCached
                            || state.lastSchedulePolicyAuditBatch == Long.MIN_VALUE
                            || batch.id - state.lastSchedulePolicyAuditBatch
                            >= c.marketSchedulerPolicyAuditBatches;
                    if (audit) state.lastSchedulePolicyAuditBatch = batch.id;
                    location = state.cachedScheduleLocation;
                    hidden = state.cachedScheduleHidden;
                }
                if (audit) {
                    location = market.getContainingLocation();
                    if (location == null) {
                        SectorEntityToken primary = market.getPrimaryEntity();
                        if (primary != null) location = primary.getContainingLocation();
                    }
                    hidden = market.isHidden();
                    synchronized (state) {
                        state.cachedScheduleLocation = location;
                        state.cachedScheduleHidden = hidden;
                        state.schedulePolicyCached = true;
                    }
                    MARKET_SCHEDULER_POLICY_AUDITS.increment();
                } else {
                    MARKET_SCHEDULER_POLICY_CACHE_HITS.increment();
                }
                playerOwned = false;
            } else {
                location = market.getContainingLocation();
                if (location == null) {
                    SectorEntityToken primary = market.getPrimaryEntity();
                    if (primary != null) location = primary.getContainingLocation();
                }
                playerOwned = market.isPlayerOwned();
                hidden = market.isHidden();
                MARKET_SCHEDULER_POLICY_AUDITS.increment();
            }
            if (c.marketSchedulerHotCurrentLocation
                    && location != null && location == batch.currentLocation) hot = true;
            if (c.marketSchedulerHotPlayerOwned && playerOwned) hot = true;
            if (c.marketSchedulerHotInteraction && market == batch.interactionMarket) hot = true;
            interval = hot ? 1 : (hidden
                    ? c.marketSchedulerHiddenBatches : c.marketSchedulerBatches);
            phase = stableMarketPhase(market, interval);
        } catch (Throwable ignored) {
            hot = true;
            interval = 1;
            phase = 0;
            MARKET_SCHEDULER_POLICY_FAILURES.increment();
        }

        synchronized (state) {
            if (state.disabled || state.inAdvance || state.pendingSteps <= 0) return false;
            if (state.nextDueBatch == Long.MIN_VALUE) {
                if (conditionOnlySource && interval > 1) {
                    state.nextDueBatch = firstStableDueBatch(batch.id, phase, interval);
                    if (state.nextDueBatch > batch.id) {
                        MARKET_SCHEDULER_INITIAL_PHASE_DEFERRALS.increment();
                    }
                } else {
                    state.nextDueBatch = batch.id;
                }
            }
            boolean due = interval <= 1 || batch.id >= state.nextDueBatch;
            if (!due) {
                MARKET_SCHEDULER_NOT_DUE_SKIPS.increment();
                return false;
            }
            state.dueDecisionBatch = batch.id;
            state.dueHot = hot;
            state.dueHidden = hidden;
            state.dueInterval = interval;
            state.duePhase = phase;
            if (state.lastTouchedBatch == batch.id) return false;
            state.lastTouchedBatch = batch.id;
            MARKET_SCHEDULER_DUE_ENTRIES.increment();
            return true;
        }
    }

    private static void deliverMarketAtBatchEnd(
            MarketSchedulerBatch batch, MarketAPI market, MarketScheduleState state) {
        if (market == null || state == null) return;
        boolean hot;
        boolean hidden;
        int interval;
        int phase;
        synchronized (state) {
            if (state.disabled || state.inAdvance || state.pendingSteps <= 0
                    || state.dueDecisionBatch != batch.id) return;
            hot = state.dueHot;
            hidden = state.dueHidden;
            interval = state.dueInterval;
            phase = state.duePhase;
        }

        int delivered = deliverPendingCoalesced(
                market, state, MARKET_ORIGIN_SCHEDULED, true, false);
        if (delivered <= 0) return;
        if (hot) MARKET_SCHEDULER_HOT_ADVANCES.increment();
        if (hidden) MARKET_SCHEDULER_HIDDEN_ADVANCES.increment();
        synchronized (state) {
            state.nextDueBatch = nextStableDueBatch(batch.id, phase, interval);
        }
    }

    private static long firstStableDueBatch(long currentBatch, int phase, int interval) {
        if (interval <= 1) return currentBatch;
        return currentBatch + Math.floorMod((long) phase - currentBatch, interval);
    }

    private static long nextStableDueBatch(long currentBatch, int phase, int interval) {
        if (interval <= 1) return currentBatch + 1L;
        long next = currentBatch + 1L;
        return next + Math.floorMod((long) phase - next, interval);
    }

    private static void updateMaximum(AtomicLong maximum, long value) {
        long current = maximum.get();
        while (value > current && !maximum.compareAndSet(current, value)) {
            current = maximum.get();
        }
    }

    /** Flushes the one shared scheduler debt map before save serialization. */
    /** Hard global boundary requested by the native AoTD fork. */
    public static void flushMarketSchedulerBeforeAoTDGlobalBoundary() {
        flushMarketSchedulerBeforeSave();
    }

    public static void flushMarketSchedulerBeforeSave() {
        if (!marketSchedulerReady) return;
        PrepatcherConfig c = config;
        long generation = campaignCacheGeneration;
        if (c == null || !c.marketScheduler || !campaignCachesReady(generation)) return;

        long started = System.nanoTime();
        dumpMarketSchedulerBaseline("before-save-flush");
        try {
            List<WeakIdentityEntry<MarketAPI, MarketScheduleState>> entries;
            try {
                entries = MARKET_SCHEDULER_STATES.entriesSnapshot();
                entries.sort((left, right) -> Long.compare(
                        debtSequence(left.value), debtSequence(right.value)));
            } catch (Throwable failure) {
                MARKET_SCHEDULER_SAVE_FLUSH_FAILURES.increment();
                throw new IllegalStateException(
                        "Unable to enumerate market scheduler debt before save", failure);
            }

            for (WeakIdentityEntry<MarketAPI, MarketScheduleState> entry : entries) {
                MarketAPI market = entry.key;
                MarketScheduleState state = entry.value;
                if (market == null || state == null) continue;
                boolean exact = c.marketSchedulerExactReplayBeforeSave;
                if (!exact) exact = updateConstructionMode(market, state, true);
                deliverPendingForSave(market, state, exact);
            }
        } finally {
            dumpMarketSchedulerBaseline("after-save-flush");
            long elapsed = System.nanoTime() - started;
            if (elapsed > 0L) SAVE_FLUSH_DURATION_NANOS.add(elapsed);
        }
    }

    private static long debtSequence(MarketScheduleState state) {
        if (state == null) return Long.MAX_VALUE;
        synchronized (state) {
            return state.debtSequence == 0L ? Long.MAX_VALUE : state.debtSequence;
        }
    }


    private static int countPerSimulationTickMarkets() {
        int count = 0;
        try {
            for (WeakIdentityEntry<MarketAPI, MarketScheduleState> entry
                    : MARKET_SCHEDULER_STATES.entriesSnapshot()) {
                MarketScheduleState state = entry.value;
                if (state == null) continue;
                synchronized (state) {
                    if (state.perSimulationTick) count++;
                }
            }
        } catch (Throwable ignored) {
            MARKET_SCHEDULER_STATE_FAILURES.increment();
        }
        return count;
    }

    /**
     * Writes a source-of-truth baseline of pending scheduler debt and callbacks
     * already delivered to live Market objects. This is diagnostic only and
     * intentionally does not flush pending debt.
     *
     * @return absolute CSV path, or an empty string when no mod root is configured
     */
    public static String dumpMarketSchedulerBaseline(String reason) {
        Path root = modRoot;
        if (root == null) return "";
        String safeReason = reason == null || reason.isBlank()
                ? "manual" : reason.replaceAll("[^A-Za-z0-9._-]+", "_");
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        Path directory = root.resolve("logs").resolve("market-scheduler-baseline");
        Path csvPath = directory.resolve("baseline-" + stamp + '-' + safeReason + ".csv");
        Path jsonPath = directory.resolve("baseline-" + stamp + '-' + safeReason + ".json");

        ArrayList<String[]> rows = new ArrayList<>();
        long pendingStepsTotal = 0L;
        long pendingRunsTotal = 0L;
        long deliveredCallbacksTotal = 0L;
        double deliveredAmountTotal = 0d;
        try {
            for (WeakIdentityEntry<MarketAPI, MarketScheduleState> entry
                    : MARKET_SCHEDULER_STATES.entriesSnapshot()) {
                MarketAPI market = entry.key;
                MarketScheduleState state = entry.value;
                if (market == null || state == null) continue;
                String marketId;
                String marketName;
                try { marketId = Objects.toString(market.getId(), ""); }
                catch (Throwable ignored) { marketId = ""; }
                try { marketName = Objects.toString(market.getName(), ""); }
                catch (Throwable ignored) { marketName = ""; }
                String pendingHistory;
                int pendingSteps;
                int pendingRuns;
                float pendingAmount;
                long deliveredCallbacks;
                double deliveredAmount;
                long lastDeliveredSequence;
                int lastDeliveredOrigin;
                long lastDeliveredNanos;
                long debtSequence;
                int lastSource;
                int sourceMask;
                boolean perSimulationTick;
                boolean constructionFullRate;
                int constructionReasonMask;
                boolean inAdvance;
                boolean disabled;
                long nextDueBatch;
                long lastTouchedBatch;
                synchronized (state) {
                    pendingSteps = state.pendingSteps;
                    pendingRuns = state.pendingRuns == null ? 0 : state.pendingRuns.size();
                    pendingAmount = state.pendingAmount;
                    StringBuilder history = new StringBuilder();
                    if (state.pendingRuns != null) {
                        for (PendingStepRun run : state.pendingRuns) {
                            if (history.length() > 0) history.append(';');
                            history.append(Float.toString(run.amount)).append('x').append(run.count);
                        }
                    }
                    pendingHistory = history.toString();
                    deliveredCallbacks = state.deliveredCallbacks;
                    deliveredAmount = state.deliveredAmount;
                    lastDeliveredSequence = state.lastDeliveredSequence;
                    lastDeliveredOrigin = state.lastDeliveredOrigin;
                    lastDeliveredNanos = state.lastDeliveredNanos;
                    debtSequence = state.debtSequence;
                    lastSource = state.lastSource;
                    sourceMask = state.sourceMask;
                    perSimulationTick = state.perSimulationTick;
                    constructionFullRate = state.constructionFullRate;
                    constructionReasonMask = state.constructionReasonMask;
                    inAdvance = state.inAdvance;
                    disabled = state.disabled;
                    nextDueBatch = state.nextDueBatch;
                    lastTouchedBatch = state.lastTouchedBatch;
                }
                pendingStepsTotal += pendingSteps;
                pendingRunsTotal += pendingRuns;
                deliveredCallbacksTotal += deliveredCallbacks;
                deliveredAmountTotal += deliveredAmount;
                rows.add(new String[] {
                        marketId, marketName, Integer.toString(System.identityHashCode(market)),
                        Integer.toString(pendingSteps), Float.toString(pendingAmount),
                        Integer.toString(pendingRuns), pendingHistory,
                        Long.toString(deliveredCallbacks), Double.toString(deliveredAmount),
                        Long.toString(lastDeliveredSequence), Integer.toString(lastDeliveredOrigin),
                        Long.toString(lastDeliveredNanos), Long.toString(debtSequence),
                        Integer.toString(lastSource), Integer.toString(sourceMask),
                        Boolean.toString(perSimulationTick), Boolean.toString(constructionFullRate),
                        Integer.toString(constructionReasonMask), Boolean.toString(inAdvance),
                        Boolean.toString(disabled), Long.toString(nextDueBatch),
                        Long.toString(lastTouchedBatch)
                });
            }
        } catch (Throwable failure) {
            MARKET_SCHEDULER_STATE_FAILURES.increment();
            PrepatcherLog.warn("Could not capture market scheduler baseline: " + failure);
        }
        rows.sort((left, right) -> {
            int byId = left[0].compareTo(right[0]);
            return byId != 0 ? byId : left[2].compareTo(right[2]);
        });

        StringBuilder csv = new StringBuilder(
                "market_id,market_name,identity_hash,pending_steps,pending_amount,pending_runs,pending_history,delivered_callbacks,delivered_amount,last_delivered_sequence,last_delivered_origin,last_delivered_nanos,debt_sequence,last_source,source_mask,per_simulation_tick,construction_full_rate,construction_reason_mask,in_advance,disabled,next_due_batch,last_touched_batch\n");
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) csv.append(',');
                csv.append(baselineCsv(row[i]));
            }
            csv.append('\n');
        }
        String json = "{\n"
                + "  \"schemaVersion\": 1,\n"
                + "  \"capturedUtc\": \"" + baselineJson(Instant.now().toString()) + "\",\n"
                + "  \"reason\": \"" + baselineJson(reason == null ? "" : reason) + "\",\n"
                + "  \"schedulerReady\": " + marketSchedulerReady + ",\n"
                + "  \"markets\": " + rows.size() + ",\n"
                + "  \"pendingSteps\": " + pendingStepsTotal + ",\n"
                + "  \"pendingRuns\": " + pendingRunsTotal + ",\n"
                + "  \"deliveredCallbacks\": " + deliveredCallbacksTotal + ",\n"
                + "  \"deliveredAmount\": " + deliveredAmountTotal + "\n"
                + "}\n";
        try {
            Files.createDirectories(directory);
            Files.writeString(csvPath, csv, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            Files.writeString(jsonPath, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            return csvPath.toAbsolutePath().normalize().toString();
        } catch (Throwable failure) {
            PrepatcherLog.warn("Could not write market scheduler baseline: " + failure);
            return "";
        }
    }

    private static String baselineCsv(String value) {
        String text = value == null ? "" : value;
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private static String baselineJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static MarketSchedulerGaugeSnapshot marketSchedulerGaugeSnapshot() {
        long pendingSteps = 0L;
        long pendingRuns = 0L;
        int constructionMarkets = 0;
        int constructionQueueMarkets = 0;
        int constructionBuildingMarkets = 0;
        int constructionUpgradingMarkets = 0;
        int constructionReportedBuildingWithoutRawMarkets = 0;
        int constructionUncertainMarkets = 0;
        int constructionMultipleReasonMarkets = 0;
        try {
            for (WeakIdentityEntry<MarketAPI, MarketScheduleState> entry
                    : MARKET_SCHEDULER_STATES.entriesSnapshot()) {
                MarketScheduleState state = entry.value;
                if (state == null) continue;
                synchronized (state) {
                    pendingSteps += state.pendingSteps;
                    pendingRuns += state.pendingRuns == null ? 0 : state.pendingRuns.size();
                    if (state.constructionFullRate) constructionMarkets++;
                    int reasons = state.constructionReasonMask;
                    if ((reasons & CONSTRUCTION_REASON_QUEUE_NON_EMPTY) != 0) {
                        constructionQueueMarkets++;
                    }
                    if ((reasons & CONSTRUCTION_REASON_INDUSTRY_BUILDING) != 0) {
                        constructionBuildingMarkets++;
                    }
                    if ((reasons & CONSTRUCTION_REASON_INDUSTRY_UPGRADING) != 0) {
                        constructionUpgradingMarkets++;
                    }
                    if ((reasons & CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW) != 0) {
                        constructionReportedBuildingWithoutRawMarkets++;
                    }
                    if ((reasons & (CONSTRUCTION_REASON_QUEUE_ITEMS_UNKNOWN
                            | CONSTRUCTION_REASON_INDUSTRIES_UNKNOWN
                            | CONSTRUCTION_REASON_PROBE_FAILURE
                            | CONSTRUCTION_REASON_MUTATION_RACE)) != 0) {
                        constructionUncertainMarkets++;
                    }
                    if (Integer.bitCount(reasons & CONSTRUCTION_REASON_OBSERVED_MASK) > 1) {
                        constructionMultipleReasonMarkets++;
                    }
                }
            }
        } catch (Throwable ignored) {
            MARKET_SCHEDULER_STATE_FAILURES.increment();
        }
        return new MarketSchedulerGaugeSnapshot(pendingSteps, pendingRuns, constructionMarkets,
                constructionQueueMarkets, constructionBuildingMarkets,
                constructionUpgradingMarkets, constructionReportedBuildingWithoutRawMarkets,
                constructionUncertainMarkets, constructionMultipleReasonMarkets);
    }

    private record MarketSchedulerGaugeSnapshot(
            long pendingMarketStepsCurrent,
            long pendingMarketRunsCurrent,
            int constructionFullRateMarkets,
            int constructionMarketsQueueNonEmpty,
            int constructionMarketsIndustryBuilding,
            int constructionMarketsIndustryUpgrading,
            int constructionMarketsReportedBuildingWithoutRaw,
            int constructionMarketsUncertain,
            int constructionMarketsMultipleReasons) {}

    private static int stableMarketPhase(MarketAPI market, int interval) {
        if (interval <= 1) return 0;
        int hash = System.identityHashCode(market);
        try {
            String id = market.getId();
            if (id != null) hash = 31 * hash + id.hashCode();
        } catch (Throwable ignored) {
            // Identity still provides a stable phase for this process.
        }
        hash ^= hash >>> 16;
        return Math.floorMod(hash, interval);
    }

    /** Allocates owner-local transient state used by persistent economy snapshots. */
    public static Object newPersistentSnapshotState() {
        return new PersistentSnapshotState();
    }

    /** Marks a transformed Economy/Market structure as changed. Deliberately noexcept. */
    public static void markPersistentSnapshotStructure(Object rawState) {
        try {
            if (rawState instanceof PersistentSnapshotState state) {
                state.epoch++;
                persistentEpoch(state.kind);
            }
        } catch (Throwable ignored) {
            // A missing/foreign state only degrades to the audit/fallback path.
        }
    }

    /** Exposed only to transformed Economy bytecode, tests and the location-cache hook. */
    public static long persistentSnapshotEpoch(Object rawState) {
        return rawState instanceof PersistentSnapshotState state
                ? state.epoch : Long.MIN_VALUE;
    }

    /** Persistent point-in-time snapshot with a wall-clock audit cadence. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static ArrayList borrowPersistentSnapshotTimed(
            Object rawState, Collection source, int auditMs, int kind) {
        PrepatcherConfig c = config;
        if (c == null || !c.economyPersistentSnapshots
                || !(rawState instanceof PersistentSnapshotState state)
                || !state.acceptKind(kind)) {
            return new ArrayList(source);
        }
        try {
            return state.borrowTimed(source, Math.max(0, auditMs), kind);
        } catch (Throwable ignored) {
            state.invalidateSnapshot();
            return new ArrayList(source);
        }
    }

    /** Persistent point-in-time snapshot with a call/frame-count audit cadence. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static ArrayList borrowPersistentSnapshotFrames(
            Object rawState, Collection source, int auditFrames, int kind) {
        PrepatcherConfig c = config;
        if (c == null || !c.economyPersistentSnapshots
                || !(rawState instanceof PersistentSnapshotState state)
                || !state.acceptKind(kind)) {
            return new ArrayList(source);
        }
        try {
            return state.borrowFrames(source, Math.max(1, auditFrames), kind);
        } catch (Throwable ignored) {
            state.invalidateSnapshot();
            return new ArrayList(source);
        }
    }


    // ---------------------------------------------------------------------
    // Aggressive economy: ordered commodity active set
    // ---------------------------------------------------------------------

    private static final int COMMODITY_STAT_AVAILABLE = 1;
    private static final int COMMODITY_STAT_AOTD_EXCESS = 1 << 1;
    private static final int COMMODITY_STAT_AOTD_DEFICIT = 1 << 2;
    private static final int COMMODITY_STAT_TRADE = 1 << 3;
    private static final int COMMODITY_STAT_TRADE_PLUS = 1 << 4;
    private static final int COMMODITY_STAT_TRADE_MINUS = 1 << 5;
    private static final int COMMODITY_MASK_AVAILABILITY =
            COMMODITY_STAT_AVAILABLE
                    | COMMODITY_STAT_AOTD_EXCESS
                    | COMMODITY_STAT_AOTD_DEFICIT;
    private static final int COMMODITY_MASK_TRADE =
            COMMODITY_STAT_TRADE
                    | COMMODITY_STAT_TRADE_PLUS
                    | COMMODITY_STAT_TRADE_MINUS;
    private static final int COMMODITY_MASK_EXACT_VANILLA =
            COMMODITY_STAT_AVAILABLE | COMMODITY_MASK_TRADE;
    private static final int COMMODITY_MASK_AOTD_NATIVE =
            COMMODITY_MASK_AVAILABILITY | COMMODITY_MASK_TRADE;
    private static final int COMMODITY_MODE_VANILLA_ONLY = 0;
    private static final int COMMODITY_MODE_EXACT_VANILLA = 1;
    private static final int COMMODITY_MODE_AOTD_NATIVE = 2;
    private static final String AOTD_COMMODITY_CLASS =
            "data.kaysaar.aotd.tot.scripts.commoditydata.AoTDCommodityOnMarket";
    private static final String AOTD_AVAILABLE_STAT_CLASS =
            "data.kaysaar.aotd.tot.scripts.commoditydata.AoTDAvailableStat";
    private static final MethodHandle[] AOTD_TEMPORAL_ACCESS_UNSUPPORTED =
            new MethodHandle[0];
    private static final ClassValue<MethodHandle[]> AOTD_TEMPORAL_ACCESS =
            new ClassValue<>() {
                @Override
                protected MethodHandle[] computeValue(Class<?> type) {
                    return createAoTDTemporalAccess(type);
                }
            };

    /**
     * Called only after transformed MutableStat bytecode observed a non-null
     * private owner binding. Unbound stats never enter this hook.
     */
    public static void markCommodityTemporalOwnerDirty(
            Object rawOwner, int role, String source) {
        PrepatcherConfig c = config;
        if (c == null || !c.commodityTemporalFastPath
                || !(rawOwner instanceof CommodityTemporalEntry entry)) return;
        // CommodityOnMarket.reapplyEventMod() owns this internal availability key.
        // Waking the entry for its own remove/add cycle would keep it permanently hot.
        if (role == COMMODITY_STAT_AVAILABLE && "eMod".equals(source)) return;
        entry.markDirty(role);
        COMMODITY_TEMPORAL_DIRTY_SIGNALS.increment();
    }

    /** Wakes a bound commodity when public getMods() exposes a live mutable map. */
    public static void markCommodityTemporalStatExposed(Object rawStat) {
        PrepatcherConfig c = config;
        if (c == null || !c.commodityTemporalFastPath
                || !(rawStat instanceof MutableStatWithTempMods stat)) return;
        // The transformed binding fields are declared in MutableStatWithTempMods
        // itself and therefore also exist on AoTDAvailableStat instances. Unknown
        // subclasses remain harmless because they never receive an owner binding.
        CommodityTemporalStatAccess access = commodityTemporalBaseStatAccess();
        if (!access.supported) return;
        access.markExposure(stat);
    }

    /** Whole-loop replacement for Market.advance() commodity maintenance. */
    @SuppressWarnings("unchecked")
    public static Object advanceMarketCommodityTemporalState(
            Object rawMarket, Object rawState, float days) {
        PrepatcherConfig c = config;
        if (!(rawMarket instanceof Market market)) return rawState;
        if (c == null || !c.commodityTemporalFastPath
                || market.getClass() != Market.class) {
            vanillaAdvanceMarketCommodities(market, days);
            COMMODITY_TEMPORAL_FALLBACKS.increment();
            return rawState;
        }
        long generation = campaignCacheGeneration;
        if (!campaignCachesReady(generation)) {
            vanillaAdvanceMarketCommodities(market, days);
            COMMODITY_TEMPORAL_FALLBACKS.increment();
            return rawState;
        }

        List<CommodityOnMarket> commodities;
        try {
            commodities = market.getCommodities();
        } catch (Throwable ex) {
            vanillaAdvanceMarketCommodities(market, days);
            COMMODITY_TEMPORAL_FALLBACKS.increment();
            return null;
        }
        if (commodities == null) {
            vanillaAdvanceMarketCommodities(market, days);
            COMMODITY_TEMPORAL_FALLBACKS.increment();
            return null;
        }

        CommodityTemporalMarketState state;
        if (rawState instanceof CommodityTemporalMarketState existing
                && existing.owner == market && existing.generation == generation) {
            state = existing;
        } else {
            if (rawState instanceof CommodityTemporalMarketState existing) {
                existing.unbindAll();
            }
            state = new CommodityTemporalMarketState(market, generation);
        }
        if (!state.prepare(commodities, c.commodityTemporalAuditFrames)) {
            state.unbindAll();
            vanillaAdvanceMarketCommodities(market, days);
            COMMODITY_TEMPORAL_FALLBACKS.increment();
            return null;
        }

        // Once an original stat/commodity method has executed, replaying the whole
        // vanilla loop on failure would double-advance earlier entries. Propagate.
        sampleCommodityTemporalMarketMetrics(state);
        state.advancePrepared(days);
        return state;
    }

    private static void sampleCommodityTemporalMarketMetrics(
            CommodityTemporalMarketState state) {
        int tick = ++commodityTemporalMetricsTick;
        if ((tick & COMMODITY_TEMPORAL_METRIC_SAMPLE_MASK) != 0) return;
        long scale = 1L << COMMODITY_TEMPORAL_METRIC_SAMPLE_SHIFT;
        int active = state.active.size();
        int exactVanillaActive = 0;
        int aotdNativeActive = 0;
        int vanillaOnlyActive = 0;
        int availableActive = 0;
        int excessActive = 0;
        int deficitActive = 0;
        int tradeActive = 0;
        int tradePlusActive = 0;
        int tradeMinusActive = 0;
        for (int i = 0; i < active; i++) {
            CommodityTemporalEntry entry = state.active.get(i);
            if (entry.vanillaOnly) {
                vanillaOnlyActive++;
                continue;
            }
            if (entry.mode == COMMODITY_MODE_AOTD_NATIVE) aotdNativeActive++;
            else exactVanillaActive++;
            int mask = entry.activeMask;
            if ((mask & COMMODITY_STAT_AVAILABLE) != 0) availableActive++;
            if ((mask & COMMODITY_STAT_AOTD_EXCESS) != 0) excessActive++;
            if ((mask & COMMODITY_STAT_AOTD_DEFICIT) != 0) deficitActive++;
            if ((mask & COMMODITY_STAT_TRADE) != 0) tradeActive++;
            if ((mask & COMMODITY_STAT_TRADE_PLUS) != 0) tradePlusActive++;
            if ((mask & COMMODITY_STAT_TRADE_MINUS) != 0) tradeMinusActive++;
        }
        COMMODITY_TEMPORAL_MARKET_CALLS.add(scale);
        COMMODITY_TEMPORAL_ENTRIES.add((long) state.entries.size() * scale);
        COMMODITY_TEMPORAL_ACTIVE_ADVANCES.add((long) active * scale);
        COMMODITY_TEMPORAL_INACTIVE_SKIPS.add(
                (long) Math.max(0, state.entries.size() - active) * scale);
        COMMODITY_TEMPORAL_EXACT_VANILLA_ENTRIES.add(
                (long) state.exactVanillaEntries * scale);
        COMMODITY_TEMPORAL_AOTD_NATIVE_ENTRIES.add(
                (long) state.aotdNativeEntries * scale);
        COMMODITY_TEMPORAL_VANILLA_ONLY_ENTRIES.add(
                (long) state.vanillaOnlyEntries * scale);
        COMMODITY_TEMPORAL_EXACT_VANILLA_ACTIVE.add(
                (long) exactVanillaActive * scale);
        COMMODITY_TEMPORAL_AOTD_NATIVE_ACTIVE.add(
                (long) aotdNativeActive * scale);
        COMMODITY_TEMPORAL_VANILLA_ONLY_ACTIVE.add(
                (long) vanillaOnlyActive * scale);
        COMMODITY_TEMPORAL_ACTIVE_AVAILABLE.add((long) availableActive * scale);
        COMMODITY_TEMPORAL_ACTIVE_AOTD_EXCESS.add((long) excessActive * scale);
        COMMODITY_TEMPORAL_ACTIVE_AOTD_DEFICIT.add((long) deficitActive * scale);
        COMMODITY_TEMPORAL_ACTIVE_TRADE.add((long) tradeActive * scale);
        COMMODITY_TEMPORAL_ACTIVE_TRADE_PLUS.add((long) tradePlusActive * scale);
        COMMODITY_TEMPORAL_ACTIVE_TRADE_MINUS.add((long) tradeMinusActive * scale);
    }

    private static void vanillaAdvanceMarketCommodities(Market market, float days) {
        Iterator<?> iterator = market.getCommodities().iterator();
        while (iterator.hasNext()) {
            CommodityOnMarket commodity = (CommodityOnMarket) iterator.next();
            vanillaAdvanceCommodity(commodity, days);
        }
    }

    private static void vanillaAdvanceCommodity(CommodityOnMarket commodity, float days) {
        commodity.getAvailableStat().advance(days);
        commodity.getTradeMod().advance(days);
        commodity.getTradeModPlus().advance(days);
        commodity.getTradeModMinus().advance(days);
        commodity.reapplyEventMod();
    }

    private static CommodityTemporalStatAccess commodityTemporalBaseStatAccess() {
        return COMMODITY_TEMPORAL_STAT_ACCESS.get(MutableStatWithTempMods.class);
    }

    private static MethodHandle[] aotdTemporalAccess(Class<?> commodityType) {
        if (commodityType == null
                || !AOTD_COMMODITY_CLASS.equals(commodityType.getName())) {
            return AOTD_TEMPORAL_ACCESS_UNSUPPORTED;
        }
        return AOTD_TEMPORAL_ACCESS.get(commodityType);
    }

    private static MethodHandle[] createAoTDTemporalAccess(Class<?> commodityType) {
        try {
            Method getDataMethod = commodityType.getMethod("getExcDefData");
            Class<?> dataType = getDataMethod.getReturnType();
            Field excessField = dataType.getField("excess");
            Field deficitField = dataType.getField("deficit");
            if (!MutableStatWithTempMods.class.isAssignableFrom(excessField.getType())
                    || !MutableStatWithTempMods.class.isAssignableFrom(
                    deficitField.getType())) {
                return AOTD_TEMPORAL_ACCESS_UNSUPPORTED;
            }
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            return new MethodHandle[] {
                    lookup.unreflect(getDataMethod),
                    lookup.unreflectGetter(excessField),
                    lookup.unreflectGetter(deficitField)
            };
        } catch (Throwable ignored) {
            return AOTD_TEMPORAL_ACCESS_UNSUPPORTED;
        }
    }

    private static MutableStatWithTempMods[] captureAoTDExtraStats(
            CommodityOnMarket commodity) {
        MethodHandle[] access = aotdTemporalAccess(commodity.getClass());
        if (access.length != 3) return null;
        try {
            Object data = access[0].invoke(commodity);
            Object excess = access[1].invoke(data);
            Object deficit = access[2].invoke(data);
            if (!(excess instanceof MutableStatWithTempMods excessStat)
                    || !(deficit instanceof MutableStatWithTempMods deficitStat)) {
                return null;
            }
            return new MutableStatWithTempMods[] {excessStat, deficitStat};
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final ClassValue<CommodityTemporalStatAccess>
            COMMODITY_TEMPORAL_STAT_ACCESS = new ClassValue<>() {
        @Override
        protected CommodityTemporalStatAccess computeValue(Class<?> type) {
            if (type != MutableStatWithTempMods.class) {
                return CommodityTemporalStatAccess.unsupported();
            }
            try {
                return CommodityTemporalStatAccess.create(type);
            } catch (Throwable ignored) {
                return CommodityTemporalStatAccess.unsupported();
            }
        }
    };

    private static final class CommodityTemporalStatAccess {
        final VarHandle tempMods;
        final VarHandle owner;
        final VarHandle role;
        final MethodHandle auditForCommodity;
        final boolean supported;

        private CommodityTemporalStatAccess(
                VarHandle tempMods, VarHandle owner, VarHandle role,
                MethodHandle auditForCommodity, boolean supported) {
            this.tempMods = tempMods;
            this.owner = owner;
            this.role = role;
            this.auditForCommodity = auditForCommodity;
            this.supported = supported;
        }

        static CommodityTemporalStatAccess create(Class<?> type) throws Exception {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                    type, MethodHandles.lookup());
            MethodHandle audit = lookup.findVirtual(type,
                    "spp$tempModHybridAuditForCommodity",
                    MethodType.methodType(void.class));
            return new CommodityTemporalStatAccess(
                    privateVarHandle(type, "tempMods"),
                    privateVarHandle(type, "spp$commodityTemporalOwner"),
                    privateVarHandle(type, "spp$commodityTemporalRole"),
                    audit, true);
        }

        static CommodityTemporalStatAccess unsupported() {
            return new CommodityTemporalStatAccess(null, null, null, null, false);
        }

        @SuppressWarnings("unchecked")
        LinkedHashMap<?, ?> map(MutableStatWithTempMods stat) {
            return (LinkedHashMap<?, ?>) tempMods.get(stat);
        }

        boolean hasMods(MutableStatWithTempMods stat) {
            LinkedHashMap<?, ?> map = map(stat);
            return map != null && !map.isEmpty();
        }

        boolean bind(MutableStatWithTempMods stat, Object entry, int entryRole) {
            if (!supported || stat == null) return false;
            Object existing = owner.get(stat);
            if (existing != null && existing != entry) {
                if (existing instanceof CommodityTemporalEntry other) {
                    other.promoteToVanillaOnly();
                }
                return false;
            }
            owner.set(stat, entry);
            role.set(stat, entryRole);
            return owner.get(stat) == entry && (int) role.get(stat) == entryRole;
        }

        void unbind(MutableStatWithTempMods stat, Object expected) {
            if (!supported || stat == null) return;
            if (owner.get(stat) == expected) {
                owner.set(stat, null);
                role.set(stat, 0);
            }
        }

        void markExposure(MutableStatWithTempMods stat) {
            Object raw = owner.get(stat);
            if (raw instanceof CommodityTemporalEntry entry) {
                entry.markExposed((int) role.get(stat));
            }
        }

        boolean audit(MutableStatWithTempMods stat) {
            if (!supported || auditForCommodity == null || stat == null) return false;
            try {
                auditForCommodity.invokeExact(stat);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    private static VarHandle privateVarHandle(Class<?> type, String name) throws Exception {
        Field field = findField(type, name);
        field.setAccessible(true);
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                field.getDeclaringClass(), MethodHandles.lookup());
        return lookup.unreflectVarHandle(field);
    }

    private static final class CommodityTemporalEntry {
        final CommodityTemporalMarketState owner;
        final CommodityOnMarket commodity;
        final MutableStatWithTempMods available;
        final MutableStatWithTempMods excess;
        final MutableStatWithTempMods deficit;
        final MutableStatWithTempMods trade;
        final MutableStatWithTempMods tradePlus;
        final MutableStatWithTempMods tradeMinus;
        final int mode;
        final int supportedMask;
        int ordinal;
        int activeMask;
        int dirtyMask;
        boolean active = true;
        boolean forceAudit = true;
        boolean vanillaOnly;
        boolean listedActive;
        boolean queuedWakeup;

        CommodityTemporalEntry(CommodityTemporalMarketState owner,
                               CommodityOnMarket commodity) {
            this.owner = owner;
            this.commodity = commodity;

            MutableStatWithTempMods a = null;
            MutableStatWithTempMods x = null;
            MutableStatWithTempMods d = null;
            MutableStatWithTempMods t = null;
            MutableStatWithTempMods p = null;
            MutableStatWithTempMods m = null;
            int selectedMode = COMMODITY_MODE_VANILLA_ONLY;
            boolean unsupported = commodity == null;

            if (!unsupported) {
                Class<?> commodityType = commodity.getClass();
                if (commodityType == CommodityOnMarket.class) {
                    selectedMode = COMMODITY_MODE_EXACT_VANILLA;
                } else if (AOTD_COMMODITY_CLASS.equals(commodityType.getName())) {
                    selectedMode = COMMODITY_MODE_AOTD_NATIVE;
                } else {
                    unsupported = true;
                }
            }

            if (!unsupported) {
                try {
                    a = commodity.getAvailableStat();
                    t = commodity.getTradeMod();
                    p = commodity.getTradeModPlus();
                    m = commodity.getTradeModMinus();
                    if (selectedMode == COMMODITY_MODE_AOTD_NATIVE) {
                        MutableStatWithTempMods[] extra =
                                captureAoTDExtraStats(commodity);
                        if (extra == null) {
                            unsupported = true;
                        } else {
                            x = extra[0];
                            d = extra[1];
                        }
                    }
                } catch (Throwable ex) {
                    unsupported = true;
                }
            }

            if (!unsupported) {
                if (selectedMode == COMMODITY_MODE_EXACT_VANILLA) {
                    unsupported = !isSupportedBaseStat(a)
                            || !isSupportedBaseStat(t)
                            || !isSupportedBaseStat(p)
                            || !isSupportedBaseStat(m);
                } else {
                    unsupported = !isSupportedAoTDAvailableStat(a)
                            || !isSupportedBaseStat(x)
                            || !isSupportedBaseStat(d)
                            || !isSupportedBaseStat(t)
                            || !isSupportedBaseStat(p)
                            || !isSupportedBaseStat(m);
                }
            }

            available = a;
            excess = x;
            deficit = d;
            trade = t;
            tradePlus = p;
            tradeMinus = m;
            mode = unsupported ? COMMODITY_MODE_VANILLA_ONLY : selectedMode;
            supportedMask = mode == COMMODITY_MODE_AOTD_NATIVE
                    ? COMMODITY_MASK_AOTD_NATIVE
                    : mode == COMMODITY_MODE_EXACT_VANILLA
                    ? COMMODITY_MASK_EXACT_VANILLA : 0;
            activeMask = supportedMask;
            dirtyMask = supportedMask;
            vanillaOnly = unsupported;
        }

        private static boolean isSupportedBaseStat(MutableStatWithTempMods stat) {
            return stat != null && stat.getClass() == MutableStatWithTempMods.class
                    && commodityTemporalBaseStatAccess().supported;
        }

        private static boolean isSupportedAoTDAvailableStat(
                MutableStatWithTempMods stat) {
            return stat != null
                    && AOTD_AVAILABLE_STAT_CLASS.equals(stat.getClass().getName())
                    && commodityTemporalBaseStatAccess().supported;
        }

        void markDirty(int role) {
            int maskedRole = role & supportedMask;
            if (maskedRole == 0) return;
            dirtyMask |= maskedRole;
            activeMask |= maskedRole;
            active = true;
            owner.enqueueWakeup(this);
        }

        void requestFullAudit() {
            if (!vanillaOnly) {
                dirtyMask |= supportedMask;
                activeMask |= supportedMask;
                forceAudit = true;
            }
            active = true;
            owner.enqueueWakeup(this);
        }

        void promoteToVanillaOnly() {
            if (!vanillaOnly) {
                vanillaOnly = true;
                unbind();
            }
            active = true;
            owner.enqueueWakeup(this);
        }

        void markExposed(int role) {
            forceAudit = true;
            markDirty(role);
            COMMODITY_TEMPORAL_EXPOSURES.increment();
        }

        void bind() {
            if (vanillaOnly) return;
            CommodityTemporalStatAccess access = commodityTemporalBaseStatAccess();
            boolean ok = access.bind(available, this, COMMODITY_STAT_AVAILABLE)
                    && (mode != COMMODITY_MODE_AOTD_NATIVE
                    || (access.bind(excess, this, COMMODITY_STAT_AOTD_EXCESS)
                    && access.bind(deficit, this, COMMODITY_STAT_AOTD_DEFICIT)))
                    && access.bind(trade, this, COMMODITY_STAT_TRADE)
                    && access.bind(tradePlus, this, COMMODITY_STAT_TRADE_PLUS)
                    && access.bind(tradeMinus, this, COMMODITY_STAT_TRADE_MINUS);
            if (!ok) {
                promoteToVanillaOnly();
            }
        }

        void unbind() {
            CommodityTemporalStatAccess access = commodityTemporalBaseStatAccess();
            access.unbind(available, this);
            access.unbind(excess, this);
            access.unbind(deficit, this);
            access.unbind(trade, this);
            access.unbind(tradePlus, this);
            access.unbind(tradeMinus, this);
        }

        private boolean auditAll(CommodityTemporalStatAccess access) {
            return access.audit(available)
                    && (mode != COMMODITY_MODE_AOTD_NATIVE
                    || (access.audit(excess) && access.audit(deficit)))
                    && access.audit(trade)
                    && access.audit(tradePlus)
                    && access.audit(tradeMinus);
        }

        private int liveMask(CommodityTemporalStatAccess access, int mask) {
            int result = 0;
            if ((mask & COMMODITY_STAT_AVAILABLE) != 0 && access.hasMods(available)) {
                result |= COMMODITY_STAT_AVAILABLE;
            }
            if ((mask & COMMODITY_STAT_AOTD_EXCESS) != 0
                    && access.hasMods(excess)) {
                result |= COMMODITY_STAT_AOTD_EXCESS;
            }
            if ((mask & COMMODITY_STAT_AOTD_DEFICIT) != 0
                    && access.hasMods(deficit)) {
                result |= COMMODITY_STAT_AOTD_DEFICIT;
            }
            if ((mask & COMMODITY_STAT_TRADE) != 0 && access.hasMods(trade)) {
                result |= COMMODITY_STAT_TRADE;
            }
            if ((mask & COMMODITY_STAT_TRADE_PLUS) != 0
                    && access.hasMods(tradePlus)) {
                result |= COMMODITY_STAT_TRADE_PLUS;
            }
            if ((mask & COMMODITY_STAT_TRADE_MINUS) != 0
                    && access.hasMods(tradeMinus)) {
                result |= COMMODITY_STAT_TRADE_MINUS;
            }
            return result;
        }

        private void refreshMask(CommodityTemporalStatAccess access, int mask) {
            int supported = mask & supportedMask;
            activeMask = (activeMask & ~supported) | liveMask(access, supported);
        }

        boolean process(float days) {
            if (vanillaOnly) {
                vanillaAdvanceCommodity(commodity, days);
                active = true;
                return true;
            }
            CommodityTemporalStatAccess access = commodityTemporalBaseStatAccess();

            int changed = dirtyMask;
            int candidates = (activeMask | dirtyMask) & supportedMask;
            dirtyMask = 0;
            if (forceAudit) {
                forceAudit = false;
                if (!auditAll(access)) {
                    unbind();
                    vanillaOnly = true;
                    vanillaAdvanceCommodity(commodity, days);
                    active = true;
                    return true;
                }
                // Preserve the existing conservative audit semantics: direct live-map
                // mutation is treated as potentially affecting every supported stat.
                changed |= supportedMask;
                activeMask = liveMask(access, supportedMask);
            } else {
                refreshMask(access, candidates);
            }

            int workMask = activeMask;
            int availabilityWork = workMask & COMMODITY_MASK_AVAILABILITY;
            if (availabilityWork != 0) {
                // AoTDAvailableStat advances available/excess/deficit in one ordered
                // group. Exact vanilla has only the AVAILABLE bit in this group.
                available.advance(days);
            }
            if ((workMask & COMMODITY_STAT_TRADE) != 0) trade.advance(days);
            if ((workMask & COMMODITY_STAT_TRADE_PLUS) != 0) tradePlus.advance(days);
            if ((workMask & COMMODITY_STAT_TRADE_MINUS) != 0) tradeMinus.advance(days);

            // Expiry invokes MutableStat.unmodify(), whose transformed prologue
            // marks the exact stat bit dirty. Refresh only processed/changed bits.
            int duringAdvanceDirty = dirtyMask;
            dirtyMask = 0;
            changed |= duringAdvanceDirty;
            int refresh = workMask | duringAdvanceDirty;
            if ((refresh & COMMODITY_MASK_AVAILABILITY) != 0) {
                refresh |= supportedMask & COMMODITY_MASK_AVAILABILITY;
            }
            refreshMask(access, refresh);

            if (mode == COMMODITY_MODE_EXACT_VANILLA
                    && (changed & supportedMask) != 0) {
                commodity.reapplyEventMod();
                COMMODITY_TEMPORAL_REAPPLIES.increment();
                if ((changed & COMMODITY_STAT_AVAILABLE) != 0) {
                    COMMODITY_TEMPORAL_AVAILABLE_REAPPLIES.increment();
                }
            }
            // AoTDCommodityOnMarket.reapplyEventMod() is explicitly a no-op.

            // A reapply callback may have mutated another bound stat. markDirty()
            // already added its exact role to activeMask and retained dirtyMask for
            // changed-semantics on the next delivery.
            active = activeMask != 0 || dirtyMask != 0 || forceAudit;
            return active;
        }
    }

    private static final class CommodityTemporalMarketState {
        final Market owner;
        final long generation;
        final ArrayList<CommodityTemporalEntry> entries = new ArrayList<>(32);
        final ArrayList<CommodityTemporalEntry> active = new ArrayList<>(8);
        final ArrayList<CommodityTemporalEntry> pendingWakeups = new ArrayList<>(8);
        List<?> source;
        int sourceSize = -1;
        Object first;
        Object last;
        int auditCountdown;
        int exactVanillaEntries;
        int aotdNativeEntries;
        int vanillaOnlyEntries;

        CommodityTemporalMarketState(Market owner, long generation) {
            this.owner = owner;
            this.generation = generation;
        }

        boolean prepare(List<CommodityOnMarket> live, int auditFrames) {
            try {
                boolean rebuild = source != live || sourceSize != live.size();
                if (!rebuild && sourceSize > 0) {
                    rebuild = first != live.get(0) || last != live.get(sourceSize - 1);
                }
                if (rebuild) {
                    if (!rebuild(live, auditFrames)) return false;
                } else if (--auditCountdown <= 0) {
                    COMMODITY_TEMPORAL_AUDITS.increment();
                    if (!matches(live)) {
                        if (!rebuild(live, auditFrames)) return false;
                    } else {
                        auditCountdown = normalizedCommodityAuditFrames(auditFrames);
                        // The audit already walks every entry. Queue only entries that
                        // are currently sleeping; do not perform a second full-market
                        // entries scan after setting their audit state.
                        for (CommodityTemporalEntry entry : entries) {
                            entry.requestFullAudit();
                        }
                    }
                }
                drainWakeups();
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private boolean matches(List<CommodityOnMarket> live) {
            if (live.size() != entries.size()) return false;
            for (int i = 0; i < entries.size(); i++) {
                if (live.get(i) != entries.get(i).commodity) return false;
            }
            return true;
        }

        private boolean rebuild(List<CommodityOnMarket> live, int auditFrames) {
            unbindAll();
            entries.clear();
            exactVanillaEntries = 0;
            aotdNativeEntries = 0;
            vanillaOnlyEntries = 0;
            IdentityHashMap<MutableStatWithTempMods, CommodityTemporalEntry> owners =
                    new IdentityHashMap<>();
            for (int i = 0; i < live.size(); i++) {
                CommodityOnMarket commodity = live.get(i);
                CommodityTemporalEntry entry = new CommodityTemporalEntry(this, commodity);
                entry.ordinal = i;
                entries.add(entry);
                if (entry.mode == COMMODITY_MODE_AOTD_NATIVE) aotdNativeEntries++;
                else if (entry.mode == COMMODITY_MODE_EXACT_VANILLA) exactVanillaEntries++;
                else vanillaOnlyEntries++;
                if (!entry.vanillaOnly) registerStats(entry, owners);
            }
            for (CommodityTemporalEntry entry : entries) entry.bind();
            // A shared-stat bind failure may have converted an otherwise native
            // entry to vanilla-only. Recount the final operational modes.
            exactVanillaEntries = 0;
            aotdNativeEntries = 0;
            vanillaOnlyEntries = 0;
            for (CommodityTemporalEntry entry : entries) {
                if (entry.vanillaOnly) vanillaOnlyEntries++;
                else if (entry.mode == COMMODITY_MODE_AOTD_NATIVE) aotdNativeEntries++;
                else exactVanillaEntries++;
            }
            source = live;
            sourceSize = live.size();
            first = sourceSize == 0 ? null : live.get(0);
            last = sourceSize == 0 ? null : live.get(sourceSize - 1);
            auditCountdown = initialCommodityAuditCountdown(
                    owner, generation, auditFrames);
            initializeActive();
            COMMODITY_TEMPORAL_REBUILDS.increment();
            return true;
        }

        private static void registerStats(
                CommodityTemporalEntry entry,
                IdentityHashMap<MutableStatWithTempMods, CommodityTemporalEntry> owners) {
            registerStat(entry.available, entry, owners);
            if (entry.mode == COMMODITY_MODE_AOTD_NATIVE) {
                registerStat(entry.excess, entry, owners);
                registerStat(entry.deficit, entry, owners);
            }
            registerStat(entry.trade, entry, owners);
            registerStat(entry.tradePlus, entry, owners);
            registerStat(entry.tradeMinus, entry, owners);
        }

        private static void registerStat(
                MutableStatWithTempMods stat, CommodityTemporalEntry entry,
                IdentityHashMap<MutableStatWithTempMods, CommodityTemporalEntry> owners) {
            if (stat == null) {
                entry.vanillaOnly = true;
                return;
            }
            CommodityTemporalEntry previous = owners.put(stat, entry);
            if (previous != null && previous != entry) {
                previous.vanillaOnly = true;
                entry.vanillaOnly = true;
            } else if (previous == entry) {
                entry.vanillaOnly = true;
            }
        }

        void enqueueWakeup(CommodityTemporalEntry entry) {
            if (entry == null || entry.owner != this || entry.listedActive
                    || entry.queuedWakeup) return;
            entry.queuedWakeup = true;
            pendingWakeups.add(entry);
            COMMODITY_TEMPORAL_WAKEUP_ENQUEUES.increment();
        }

        private void initializeActive() {
            active.clear();
            pendingWakeups.clear();
            for (CommodityTemporalEntry entry : entries) {
                entry.queuedWakeup = false;
                entry.listedActive = false;
                if (entry.vanillaOnly || entry.active || entry.activeMask != 0
                        || entry.dirtyMask != 0 || entry.forceAudit) {
                    entry.listedActive = true;
                    active.add(entry);
                }
            }
        }

        private void drainWakeups() {
            int count = pendingWakeups.size();
            if (count == 0) return;
            COMMODITY_TEMPORAL_WAKEUP_DRAINS.add(count);

            // Mutations may arrive in callback order, but commodity maintenance is
            // order-sensitive. Sort the usually tiny queue by source ordinal before
            // merging it into the already ordered active list.
            for (int i = 1; i < count; i++) {
                CommodityTemporalEntry value = pendingWakeups.get(i);
                int j = i - 1;
                while (j >= 0 && pendingWakeups.get(j).ordinal > value.ordinal) {
                    pendingWakeups.set(j + 1, pendingWakeups.get(j));
                    j--;
                }
                pendingWakeups.set(j + 1, value);
            }

            for (int i = 0; i < count; i++) {
                CommodityTemporalEntry entry = pendingWakeups.get(i);
                entry.queuedWakeup = false;
                if (!entry.listedActive
                        && (entry.vanillaOnly || entry.active || entry.activeMask != 0
                        || entry.dirtyMask != 0 || entry.forceAudit)) {
                    insertActiveOrdered(entry);
                    COMMODITY_TEMPORAL_WAKEUP_INSERTS.increment();
                }
            }
            pendingWakeups.clear();
        }

        private void insertActiveOrdered(CommodityTemporalEntry entry) {
            int low = 0;
            int high = active.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (active.get(mid).ordinal < entry.ordinal) low = mid + 1;
                else high = mid;
            }
            if (low < active.size() && active.get(low) == entry) {
                entry.listedActive = true;
                return;
            }
            active.add(low, entry);
            entry.listedActive = true;
        }

        void advancePrepared(float days) {
            int write = 0;
            int size = active.size();
            for (int read = 0; read < size; read++) {
                CommodityTemporalEntry entry = active.get(read);
                if (entry.process(days)) {
                    entry.listedActive = true;
                    if (write != read) active.set(write, entry);
                    write++;
                } else {
                    // Clear membership immediately. A later callback in this same
                    // delivery can now enqueue the already-processed sleeping entry
                    // for the next prepare() without requiring a full entries scan.
                    entry.listedActive = false;
                }
            }
            while (active.size() > write) active.remove(active.size() - 1);
        }

        void unbindAll() {
            pendingWakeups.clear();
            for (CommodityTemporalEntry entry : entries) {
                entry.queuedWakeup = false;
                entry.listedActive = false;
                entry.unbind();
            }
            active.clear();
        }
    }

    private static int normalizedCommodityAuditFrames(int frames) {
        return Math.max(1, frames);
    }

    private static int initialCommodityAuditCountdown(
            Object owner, long generation, int frames) {
        int interval = normalizedCommodityAuditFrames(frames);
        if (interval == 1) return 1;
        int hash = System.identityHashCode(owner);
        hash ^= (int) generation;
        hash ^= (int) (generation >>> 32);
        hash ^= hash >>> 16;
        return 1 + Math.floorMod(hash, interval);
    }

    /**
     * Exact List.add replacement used only at structurally proven scratch-local
     * mutation sites. The vanilla concrete collection type and boolean result are
     * preserved; peak observation is allocation-free and does not read the clock.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean scratchListAdd(List receiver, Object value) {
        boolean changed = receiver.add(value);
        if (changed) observeScratchListPeak(receiver);
        return changed;
    }

    /** Exact Set.add replacement for structurally proven scratch-local sites. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean scratchSetAdd(Set receiver, Object value) {
        boolean changed = receiver.add(value);
        if (changed) observeScratchSetPeak(receiver);
        return changed;
    }

    /** Exact Set.addAll replacement for structurally proven scratch-local sites. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean scratchSetAddAll(Set receiver, Collection values) {
        boolean changed = receiver.addAll(values);
        if (changed) observeScratchSetPeak(receiver);
        return changed;
    }

    private static void observeScratchListPeak(Collection<?> receiver) {
        try {
            Scratch scratch = SCRATCH.get();
            if (scratch.scopeDepth > 0) scratch.scopeFrame().observeRetainedList(receiver);
        } catch (Throwable ignored) {
            // Telemetry/trimming must never alter the original collection operation.
        }
    }

    private static void observeScratchSetPeak(Collection<?> receiver) {
        try {
            Scratch scratch = SCRATCH.get();
            if (scratch.scopeDepth > 0) scratch.scopeFrame().observeRetainedSet(receiver);
        } catch (Throwable ignored) {
            // Telemetry/trimming must never alter the original collection operation.
        }
    }

    /** Three frame-local command/group lists in Ship.advance(). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static ArrayList borrowShipList() {
        PrepatcherConfig c = config;
        if (c == null || !c.shipAdvanceScratch) return new ArrayList();
        SHIP_SCRATCH_LISTS.increment();
        return SCRATCH.get().scopeFrame().pooledList();
    }

    /** The eager command snapshot in Ship.advance(); listener snapshot remains vanilla. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static ArrayList borrowShipListSnapshot(Collection source) {
        PrepatcherConfig c = config;
        if (c == null || !c.shipAdvanceScratch) return new ArrayList(source);
        ScratchFrame frame = SCRATCH.get().scopeFrame();
        ArrayList result = frame.pooledList();
        result.addAll(source);
        frame.observeRetainedList(result);
        SHIP_SCRATCH_LISTS.increment();
        return result;
    }

    /** Two frame-local sets in Ship.advance(). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static HashSet borrowShipSet() {
        PrepatcherConfig c = config;
        if (c == null || !c.shipAdvanceScratch) return new HashSet();
        SHIP_SCRATCH_SETS.increment();
        return SCRATCH.get().scopeFrame().pooledSet();
    }

    /** Reuses DynamicParticleGroup.advance()'s deferred-removal list. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static ArrayList borrowParticleRemovalList() {
        PrepatcherConfig c = config;
        if (c == null || !c.particleCleanup) return new ArrayList();
        PARTICLE_GROUPS.increment();
        return SCRATCH.get().scopeFrame().pooledList();
    }

    /**
     * Linear identity cleanup for ordinary particle classes, with exact vanilla
     * removeAll fallback for any class that overrides equals/hashCode. The first
     * advance pass remains complete before any removal, preserving callback
     * visibility and survivor order.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean removeExpiredParticles(LinkedList particles, Collection expired) {
        PrepatcherConfig c = config;
        if (c == null || !c.particleCleanup || expired.size() < 4) {
            return particles.removeAll(expired);
        }
        PARTICLE_EXPIRED.add(expired.size());

        // All compatibility checks complete before the first mutation. If a
        // custom equals/hashCode implementation or malformed collection throws,
        // the untouched vanilla list still receives the exact removeAll call.
        try {
            for (Object value : expired) {
                if (value != null && CUSTOM_EQUALITY.get(value.getClass())) {
                    return particles.removeAll(expired);
                }
            }
            for (Object value : particles) {
                if (value != null && CUSTOM_EQUALITY.get(value.getClass())) {
                    return particles.removeAll(expired);
                }
            }
        } catch (Throwable ignored) {
            return particles.removeAll(expired);
        }

        IdentityHashMap<Object, Boolean> membership =
                SCRATCH.get().scopeFrame().identityMembership;
        clearIdentityMembership(membership);
        try {
            for (Object value : expired) membership.put(value, Boolean.TRUE);
            SCRATCH.get().scopeFrame().observeIdentitySize(membership.size());
            boolean changed = false;
            Iterator iterator = particles.iterator();
            while (iterator.hasNext()) {
                if (membership.containsKey(iterator.next())) {
                    iterator.remove();
                    changed = true;
                }
            }
            PARTICLE_LINEAR_REMOVALS.increment();
            return changed;
        } finally {
            clearIdentityMembership(membership);
        }
    }

    /**
     * Returns only spatially plausible systems, in the exact original
     * getStarSystems() order. The original IntelManager loop still performs all
     * distance, relay tag, memory and Random selection logic. Index misses and
     * malformed/modded structures return the full vanilla list.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List commRelayCandidateSystems(CampaignEngine engine, Vector2f playerLocation,
                                                  float unitsPerLy, float maxRangeLy) {
        PrepatcherConfig c = config;
        long generation = campaignCacheGeneration;
        if (engine == null) return Collections.emptyList();
        if (c == null || !c.commRelaySystemIndex || c.commRelayIndexTtlMs <= 0
                || playerLocation == null || !campaignCachesReady(generation, engine)) {
            COMM_RELAY_INDEX_FALLBACKS.increment();
            return engine.getStarSystems();
        }
        float radius = unitsPerLy * maxRangeLy;
        if (!(radius > 0f) || !Float.isFinite(radius)
                || !Float.isFinite(playerLocation.x) || !Float.isFinite(playerLocation.y)) {
            COMM_RELAY_INDEX_FALLBACKS.increment();
            return engine.getStarSystems();
        }

        try {
            // Reuse the same structurally-discovered backing list as route
            // indexing. Candidate results are still copied into the current
            // scratch scope, so no live engine collection escapes to Intel code.
            List systems = CampaignRouteAccess.ACCESS.get(engine.getClass()).backingSystems(engine);
            COMM_RELAY_SYSTEMS_TOTAL.add(systems.size());
            if (systems.size() < 96) {
                COMM_RELAY_INDEX_FALLBACKS.increment();
                return engine.getStarSystems();
            }

            long now = System.nanoTime();
            CommRelaySystemIndex index;
            Map<Object, CommRelaySystemIndex> indexes = COMM_RELAY_SYSTEM_INDEXES;
            synchronized (indexes) {
                if (!campaignCachesReady(generation, engine)) {
                    COMM_RELAY_INDEX_FALLBACKS.increment();
                    return engine.getStarSystems();
                }
                index = indexes.get(engine);
                if (index == null || !index.matches(systems, now,
                        c.commRelayIndexTtlMs, radius)) {
                    index = CommRelaySystemIndex.build(systems, now, radius);
                    if (campaignCachesReady(generation, engine)) {
                        indexes.put(engine, index);
                        if (!index.failed) COMM_RELAY_INDEX_BUILDS.increment();
                    }
                }
            }
            if (index.failed) {
                COMM_RELAY_INDEX_FALLBACKS.increment();
                return engine.getStarSystems();
            }

            Scratch scratch = SCRATCH.get();
            if (scratch.scopeDepth <= 0) {
                COMM_RELAY_INDEX_FALLBACKS.increment();
                return engine.getStarSystems();
            }
            ScratchFrame frame = scratch.scopeFrame();
            ArrayList entryScratch = frame.pooledList();
            ArrayList result = frame.pooledList();
            index.collect(playerLocation.x, playerLocation.y, radius, entryScratch);
            entryScratch.sort(COMM_RELAY_ENTRY_ORDER);
            for (Object raw : entryScratch) {
                result.add(((CommRelaySystemEntry) raw).system);
            }
            if (result.size() >= systems.size() * 3 / 4) {
                COMM_RELAY_INDEX_FALLBACKS.increment();
                return engine.getStarSystems();
            }
            COMM_RELAY_INDEX_HITS.increment();
            COMM_RELAY_SYSTEMS_CANDIDATE.add(result.size());
            return result;
        } catch (Throwable ignored) {
            COMM_RELAY_INDEX_FALLBACKS.increment();
            return engine.getStarSystems();
        }
    }

    private static final java.util.Comparator<Object> COMM_RELAY_ENTRY_ORDER =
            (left, right) -> Integer.compare(((CommRelaySystemEntry) left).ordinal,
                    ((CommRelaySystemEntry) right).ordinal);

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static HashSet borrowClassSet() {
        PrepatcherConfig c = config;
        if (c == null || !c.mapRenderStuff) return new HashSet();
        HashSet set = SCRATCH.get().scopeFrame().classSet;
        set.clear();
        return set;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static ArrayList borrowHitList(Collection source) {
        PrepatcherConfig c = config;
        if (c == null || !c.mapHitTest) return new ArrayList(source);
        ScratchFrame frame = SCRATCH.get().scopeFrame();
        ArrayList list = frame.hitList;
        list.clear();
        list.addAll(source);
        frame.observeRetainedList(list);
        return list;
    }

    public static Vector2f borrowHitPoint(float x, float y) {
        PrepatcherConfig c = config;
        if (c == null || !c.mapHitTest) return new Vector2f(x, y);
        Vector2f vector = SCRATCH.get().scopeFrame().hitPoint;
        vector.set(x, y);
        return vector;
    }

    public static Vector2f borrowArrowVector(float x, float y) {
        PrepatcherConfig c = config;
        if (c == null || !c.intelArrowRendering) return new Vector2f(x, y);
        ScratchFrame scratch = SCRATCH.get().scopeFrame();
        Vector2f vector = scratch.arrowVectors[scratch.arrowVectorIndex++ & (scratch.arrowVectors.length - 1)];
        vector.set(x, y);
        return vector;
    }

    // ---------------------------------------------------------------------
    // Label candidate spatial index. Original method still performs exact
    // visibility and distance checks; this only narrows the candidate set.
    // ---------------------------------------------------------------------

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Collection nearbyLabelIcons(LinkedHashMap icons, Object mapOwner, Object targetIcon) {
        PrepatcherConfig c = config;
        long generation = campaignCacheGeneration;
        if (c == null || !c.labelSpatialCandidates || !campaignCachesReady(generation)
                || icons.size() < 64) {
            return icons.values();
        }
        LabelIndex leasedIndex = null;
        try {
            LabelIndex index;
            long now = nanoTime();
            IdentityHashMap<LinkedHashMap<?, ?>, LabelIndex> labelIndexes = LABEL_INDEXES;
            synchronized (labelIndexes) {
                if (!campaignCachesReady(generation)) return icons.values();
                index = labelIndexes.get(icons);
                long ttlNs = c.labelIndexTtlMs * 1_000_000L;
                if (index == null || index.size != icons.size()
                        || (ttlNs > 0L && now - index.builtAtNanos > ttlNs)) {
                    index = LabelIndex.build(icons, now);
                    if (!campaignCachesReady(generation)) return icons.values();
                    if (evictIdleAndLruForInsert(labelIndexes, icons, now,
                            c.labelOwnerIdleTtlMs, 32,
                            LABEL_OWNER_IDLE_EVICTIONS, LABEL_OWNER_LIMIT_EVICTIONS)) {
                        labelIndexes.put(icons, index);
                    }
                }
                index.lastAccessNanos = now;
                index.activeUsers++;
                leasedIndex = index;
                if (!campaignCachesReady(generation)) {
                    labelIndexes.clear();
                    return icons.values();
                }
            }
            SectorEntityToken token = iconToken(targetIcon);
            if (token == null || token.getLocation() == null || index.failed) return icons.values();
            Vector2f location = token.getLocation();
            int cx = floorCell(location.x, LabelIndex.CELL_X);
            int cy = floorCell(location.y, LabelIndex.CELL_Y);
            ArrayList<Object> result = SCRATCH.get().scopeFrame().labelCandidates;
            result.clear();
            result.addAll(index.fallback);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    List<Object> bucket = index.buckets.get(cellKey(cx + dx, cy + dy));
                    if (bucket != null) result.addAll(bucket);
                }
            }
            SCRATCH.get().scopeFrame().observeRetainedList(result);
            LABEL_TOTAL.add(icons.size());
            LABEL_CANDIDATES.add(result.size());
            return result;
        } catch (Throwable ex) {
            return icons.values();
        } finally {
            if (leasedIndex != null) leasedIndex.activeUsers--;
        }
    }

    private static SectorEntityToken iconToken(Object icon) throws Exception {
        Method method = IconAccess.TOKEN_METHODS.get(icon.getClass());
        return (SectorEntityToken) method.invoke(icon);
    }

    private static final class IconAccess {
        private static final ClassValue<Method> TOKEN_METHODS = new ClassValue<>() {
            @Override
            protected Method computeValue(Class<?> type) {
                Class<?> current = type;
                while (current != null) {
                    try {
                        Method method = current.getDeclaredMethod("int");
                        method.setAccessible(true);
                        return method;
                    } catch (NoSuchMethodException ignored) {
                        current = current.getSuperclass();
                    }
                }
                throw new IllegalStateException("Unable to find map-icon token accessor for " + type.getName());
            }
        };
    }

    private static final class LabelIndex {
        static final float CELL_X = 10_000f;
        static final float CELL_Y = 3_000f;
        final int size;
        final long builtAtNanos;
        volatile long lastAccessNanos;
        volatile int activeUsers;
        final Map<Long, List<Object>> buckets;
        final List<Object> fallback;
        final boolean failed;

        private LabelIndex(int size, long builtAtNanos, Map<Long, List<Object>> buckets,
                           List<Object> fallback, boolean failed) {
            this.size = size;
            this.builtAtNanos = builtAtNanos;
            this.lastAccessNanos = builtAtNanos;
            this.buckets = buckets;
            this.fallback = fallback;
            this.failed = failed;
        }


        static LabelIndex build(LinkedHashMap<?, ?> icons, long now) {
            Map<Long, List<Object>> buckets = new HashMap<>();
            List<Object> fallback = new ArrayList<>();
            try {
                for (Object icon : icons.values()) {
                    try {
                        SectorEntityToken token = iconToken(icon);
                        Vector2f location = token == null ? null : token.getLocation();
                        if (location == null || !Float.isFinite(location.x) || !Float.isFinite(location.y)) {
                            fallback.add(icon);
                            continue;
                        }
                        int cx = floorCell(location.x, CELL_X);
                        int cy = floorCell(location.y, CELL_Y);
                        buckets.computeIfAbsent(cellKey(cx, cy), ignored -> new ArrayList<>()).add(icon);
                    } catch (Throwable ex) {
                        fallback.add(icon);
                    }
                }
                return new LabelIndex(icons.size(), now, buckets, fallback, false);
            } catch (ConcurrentModificationException ex) {
                return new LabelIndex(icons.size(), now, Collections.emptyMap(), new ArrayList<>(), true);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Intel callbacks
    // ---------------------------------------------------------------------

    public static SectorEntityToken getMapLocationCached(IntelInfoPlugin plugin, SectorMapAPI map) {
        PrepatcherConfig c = config;
        long generation = campaignCacheGeneration;
        if (c == null || !c.intelCallbackCache || !campaignCachesReady(generation)
                || c.intelMapLocationCacheMs <= 0) {
            return plugin.getMapLocation(map);
        }
        long now = System.nanoTime();
        long ttl = c.intelMapLocationCacheMs * 1_000_000L;
        IntelCache entry;
        Map<IntelInfoPlugin, IntelCache> intelCache = INTEL_CACHE;
        synchronized (intelCache) {
            if (!campaignCachesReady(generation)) {
                entry = null;
            } else {
                entry = intelCache.computeIfAbsent(plugin, ignored -> new IntelCache());
                // WeakHashMap key operations may invoke user-defined hashCode.
                // A re-entrant lifecycle change must not leave that late insert.
                if (!campaignCachesReady(generation)) {
                    intelCache.clear();
                    entry = null;
                }
            }
            if (entry != null && entry.mapForLocation == map && entry.locationValid
                    && now - entry.locationAtNanos <= ttl) {
                MAP_LOCATION_HITS.increment();
                return entry.location;
            }
        }
        if (entry == null) return plugin.getMapLocation(map);
        SectorEntityToken value = plugin.getMapLocation(map);
        synchronized (intelCache) {
            entry.mapForLocation = map;
            entry.location = value;
            entry.locationAtNanos = now;
            entry.locationValid = true;
        }
        MAP_LOCATION_MISSES.increment();
        return value;
    }

    @SuppressWarnings("rawtypes")
    public static List getArrowDataCached(IntelInfoPlugin plugin, SectorMapAPI map) {
        PrepatcherConfig c = config;
        long generation = campaignCacheGeneration;
        if (c == null || !c.intelArrowRendering || !campaignCachesReady(generation)
                || c.intelArrowDataCacheMs <= 0) {
            return plugin.getArrowData(map);
        }
        long now = System.nanoTime();
        long ttl = c.intelArrowDataCacheMs * 1_000_000L;
        IntelCache entry;
        Map<IntelInfoPlugin, IntelCache> intelCache = INTEL_CACHE;
        synchronized (intelCache) {
            if (!campaignCachesReady(generation)) {
                entry = null;
            } else {
                entry = intelCache.computeIfAbsent(plugin, ignored -> new IntelCache());
                if (!campaignCachesReady(generation)) {
                    intelCache.clear();
                    entry = null;
                }
            }
            if (entry != null && entry.mapForArrows == map && entry.arrowsValid
                    && now - entry.arrowsAtNanos <= ttl) {
                ARROW_HITS.increment();
                return entry.arrows;
            }
        }
        if (entry == null) return plugin.getArrowData(map);
        List value = plugin.getArrowData(map);
        synchronized (intelCache) {
            entry.mapForArrows = map;
            entry.arrows = value;
            entry.arrowsAtNanos = now;
            entry.arrowsValid = true;
        }
        ARROW_MISSES.increment();
        return value;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean fastContains(Collection collection, Object value) {
        PrepatcherConfig c = config;
        if (c == null || !c.intelReconciliation || collection.size() < 16) {
            return collection.contains(value);
        }
        ScratchFrame scratch = SCRATCH.get().scopeFrame();
        if (scratch.containsSource != collection || scratch.containsSourceSize != collection.size()) {
            scratch.containsSet.clear();
            scratch.containsSet.addAll(collection);
            scratch.observeRetainedSet(scratch.containsSet);
            scratch.containsSource = collection;
            scratch.containsSourceSize = collection.size();
        }
        return scratch.containsSet.contains(value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Collection existingIntelIconCandidates(LinkedHashMap icons, Object entity) {
        Object direct = icons.get(entity);
        if (direct != null) return Collections.singletonList(direct);
        return icons.values();
    }

    /** Identity index for H.getIntelIconEntity(), avoiding an O(M) scan per Intel item. */
    @SuppressWarnings("rawtypes")
    public static Object getIntelIconEntityIndexed(Object owner, List intelEntities, IntelInfoPlugin plugin) {
        PrepatcherConfig c = config;
        long generation = campaignCacheGeneration;
        if (c == null || !c.intelEntityIndex || !campaignCachesReady(generation)
                || intelEntities == null || plugin == null) {
            return invokeOriginalIntelIconLookup(owner, plugin);
        }
        IntelEntityIndex leasedIndex = null;
        try {
            long now = nanoTime();
            Object first = intelEntities.isEmpty() ? null : intelEntities.get(0);
            Object last = intelEntities.isEmpty() ? null : intelEntities.get(intelEntities.size() - 1);
            IntelEntityIndex index;
            IdentityHashMap<List<?>, IntelEntityIndex> intelEntityIndexes = INTEL_ENTITY_INDEXES;
            synchronized (intelEntityIndexes) {
                if (!campaignCachesReady(generation)) {
                    index = null;
                } else {
                    index = intelEntityIndexes.get(intelEntities);
                    long ttlNs = c.intelEntityIndexTtlMs * 1_000_000L;
                    if (index == null || index.size != intelEntities.size()
                            || index.first != first || index.last != last
                            || (ttlNs > 0L && now - index.builtAtNanos > ttlNs)) {
                        index = IntelEntityIndex.build(intelEntities, first, last, now);
                        if (!campaignCachesReady(generation)) {
                            index = null;
                        } else {
                            if (evictIdleAndLruForInsert(intelEntityIndexes, intelEntities, now,
                                    c.intelEntityIndexOwnerIdleTtlMs, 32,
                                    INTEL_ENTITY_OWNER_IDLE_EVICTIONS,
                                    INTEL_ENTITY_OWNER_LIMIT_EVICTIONS)) {
                                intelEntityIndexes.put(intelEntities, index);
                            }
                            INTEL_INDEX_BUILDS.increment();
                        }
                    }
                    if (index != null) {
                        index.lastAccessNanos = now;
                        if (!index.failed) {
                            index.activeUsers++;
                            leasedIndex = index;
                        }
                    }
                    if (!campaignCachesReady(generation)) {
                        intelEntityIndexes.clear();
                        index = null;
                    }
                }
            }
            if (index != null && !index.failed) {
                INTEL_INDEX_HITS.increment();
                return index.byPlugin.get(plugin);
            }
        } catch (Throwable ignored) {
            // Fall through to the original method. Keep that invocation outside
            // this catch so an exception from game/mod code is never retried.
        } finally {
            if (leasedIndex != null) leasedIndex.activeUsers--;
        }
        return invokeOriginalIntelIconLookup(owner, plugin);
    }

    private static Object invokeOriginalIntelIconLookup(Object owner, IntelInfoPlugin plugin) {
        if (owner == null) return null;
        try {
            return IntelEntityAccess.ORIGINAL.get(owner.getClass()).invoke(owner, plugin);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException("Unable to invoke original Starsector Intel entity lookup", ex);
        }
    }

    private static final class IntelEntityAccess {
        static final ClassValue<Method> ORIGINAL = new ClassValue<>() {
            @Override
            protected Method computeValue(Class<?> type) {
                try {
                    Method method = findDeclaredMethod(type,
                            "smo$originalGetIntelIconEntity", IntelInfoPlugin.class);
                    method.setAccessible(true);
                    return method;
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        };

        static final ClassValue<Field> PLUGIN_FIELD = new ClassValue<>() {
            @Override
            protected Field computeValue(Class<?> type) {
                Field result = null;
                Class<?> current = type;
                while (current != null) {
                    for (Field field : current.getDeclaredFields()) {
                        if (IntelInfoPlugin.class.isAssignableFrom(field.getType())) {
                            if (result != null) {
                                throw new IllegalStateException("Ambiguous IntelInfoPlugin fields in "
                                        + type.getName());
                            }
                            result = field;
                        }
                    }
                    current = current.getSuperclass();
                }
                if (result == null) {
                    throw new IllegalStateException("No IntelInfoPlugin field in " + type.getName());
                }
                result.setAccessible(true);
                return result;
            }
        };
    }

    private static final class IntelEntityIndex {
        final int size;
        final Object first;
        final Object last;
        final long builtAtNanos;
        volatile long lastAccessNanos;
        volatile int activeUsers;
        final IdentityHashMap<IntelInfoPlugin, Object> byPlugin;
        final boolean failed;

        IntelEntityIndex(int size, Object first, Object last, long builtAtNanos,
                         IdentityHashMap<IntelInfoPlugin, Object> byPlugin, boolean failed) {
            this.size = size;
            this.first = first;
            this.last = last;
            this.builtAtNanos = builtAtNanos;
            this.lastAccessNanos = builtAtNanos;
            this.byPlugin = byPlugin;
            this.failed = failed;
        }


        @SuppressWarnings("rawtypes")
        static IntelEntityIndex build(List entities, Object first, Object last, long now) {
            IdentityHashMap<IntelInfoPlugin, Object> byPlugin = new IdentityHashMap<>();
            try {
                for (Object entity : entities) {
                    if (!(entity instanceof SectorEntityToken token)) continue;
                    Object data = token.getCustomData().get("intelIconData");
                    if (data == null) continue;
                    Field pluginField = IntelEntityAccess.PLUGIN_FIELD.get(data.getClass());
                    Object value = pluginField.get(data);
                    if (value instanceof IntelInfoPlugin plugin) byPlugin.put(plugin, entity);
                }
                return new IntelEntityIndex(entities.size(), first, last, now, byPlugin, false);
            } catch (ConcurrentModificationException ex) {
                return new IntelEntityIndex(entities.size(), first, last, now,
                        new IdentityHashMap<>(), true);
            } catch (Throwable ex) {
                return new IntelEntityIndex(entities.size(), first, last, now,
                        new IdentityHashMap<>(), true);
            }
        }
    }

    private static final class IntelCache {
        SectorMapAPI mapForLocation;
        SectorEntityToken location;
        long locationAtNanos;
        boolean locationValid;
        SectorMapAPI mapForArrows;
        List<?> arrows;
        long arrowsAtNanos;
        boolean arrowsValid;
    }

    // ---------------------------------------------------------------------
    // Hover / hit-test cache. The exact vanilla hit-test remains the source
    // of truth on misses; results are reused for a short time/cell.
    // ---------------------------------------------------------------------

    public static SectorEntityToken hitTestCached(Object handler, float x, float y, float radius) {
        PrepatcherConfig c = config;
        long generation = campaignCacheGeneration;
        if (c == null || !c.mapHitTest || !campaignCachesReady(generation)) {
            return invokeOriginalHitTest(handler, x, y, radius);
        }

        HitCache cache = null;
        HitCache leasedCache = null;
        long now = 0L;
        long cellKey = 0L;
        boolean cacheCell = false;
        try {
            try {
                HitAccess access = HitAccess.ACCESS.get(handler.getClass());
                Object map = access.mapField.get(handler);
                float factor = ((Number) access.getFactor.invoke(map)).floatValue();
                Object icons = access.getIcons.invoke(map);
                int iconCount = icons instanceof Map ? ((Map<?, ?>) icons).size() : -1;
                Object location = access.getLocation.invoke(map);
                now = nanoTime();
                IdentityHashMap<Object, HitCache> hitCaches = HIT_CACHES;
                synchronized (hitCaches) {
                    if (campaignCachesReady(generation)) {
                        cache = hitCaches.get(handler);
                        if (cache == null) {
                            boolean retain = evictIdleAndLruForInsert(hitCaches, handler, now,
                                    c.hoverOwnerIdleTtlMs, 64,
                                    HIT_OWNER_IDLE_EVICTIONS, HIT_OWNER_LIMIT_EVICTIONS);
                            cache = new HitCache(c.hoverMaxCells, now);
                            if (retain) hitCaches.put(handler, cache);
                        }
                        cache.lastAccessNanos = now;
                    }
                    if (!campaignCachesReady(generation)) {
                        hitCaches.clear();
                        cache = null;
                    } else if (cache != null) {
                        cache.activeUsers++;
                        leasedCache = cache;
                    }
                }
                if (cache != null) {
                    synchronized (cache) {
                        if (cache.location != location || cache.iconCount != iconCount
                                || Float.floatToIntBits(cache.factor) != Float.floatToIntBits(factor)) {
                            cache.reset(location, iconCount, factor);
                        }
                        long minInterval = c.hoverMaxHz <= 0 ? 0L : 1_000_000_000L / c.hoverMaxHz;
                        if (cache.hasLast && minInterval > 0L && now - cache.lastAtNanos < minInterval) {
                            HOVER_HITS.increment();
                            return cache.lastResult;
                        }
                        if (c.hoverCellPixels > 0f && c.hoverCellTtlMs > 0 && c.hoverMaxCells > 0
                                && Float.isFinite(factor) && factor > 0f) {
                            int cellX = safeFloorToInt((x * factor) / c.hoverCellPixels);
                            int cellY = safeFloorToInt((y * factor) / c.hoverCellPixels);
                            cellKey = cellKey(cellX, cellY)
                                    ^ ((long) Float.floatToIntBits(radius) * 0x9E3779B97F4A7C15L);
                            CellHit hit = cache.cells.get(cellKey);
                            if (hit != null && hoverCellFresh(now, hit.atNanos, c.hoverCellTtlMs)) {
                                cache.setLast(hit.value, now);
                                HOVER_HITS.increment();
                                return hit.value;
                            }
                            cacheCell = true;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Cache setup is optional. Invoke the original once, below, outside
                // the catch boundary so its exception/side effects stay exact.
                cache = null;
                cacheCell = false;
            }

            SectorEntityToken value = invokeOriginalHitTest(handler, x, y, radius);
            if (cache != null) {
                try {
                    synchronized (cache) {
                        if (cacheCell) cache.cells.put(cellKey, new CellHit(value, now));
                        cache.setLast(value, now);
                    }
                    HOVER_MISSES.increment();
                } catch (Throwable ignored) {
                    // Returning the already-computed original result is always safe.
                }
            }
            return value;
        } finally {
            if (leasedCache != null) leasedCache.activeUsers--;
        }
    }

    private static SectorEntityToken invokeOriginalHitTest(Object handler, float x, float y, float radius) {
        try {
            Method original = HitOriginalAccess.ORIGINAL.get(handler.getClass());
            return (SectorEntityToken) original.invoke(handler, x, y, radius);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException("Unable to invoke original Starsector map hit-test", ex);
        }
    }

    private static final class HitOriginalAccess {
        static final ClassValue<Method> ORIGINAL = new ClassValue<>() {
            @Override
            protected Method computeValue(Class<?> type) {
                try {
                    Method original = findDeclaredMethod(type,
                            "smo$originalHitTest", float.class, float.class, float.class);
                    original.setAccessible(true);
                    return original;
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        };
    }

    private static final class HitAccess {
        static final ClassValue<HitAccess> ACCESS = new ClassValue<>() {
            @Override
            protected HitAccess computeValue(Class<?> type) {
                try {
                    Field mapField = findUniqueFieldByTypeName(type,
                            "com.fs.starfarer.coreui.A.H", "H map field");
                    mapField.setAccessible(true);
                    Class<?> mapType = mapField.getType();
                    Method getFactor = mapType.getMethod("getFactor");
                    Method getIcons = mapType.getMethod("getIcons");
                    Method getLocation = mapType.getMethod("getLocation");
                    return new HitAccess(mapField, getFactor, getIcons, getLocation);
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        };

        final Field mapField;
        final Method getFactor;
        final Method getIcons;
        final Method getLocation;

        HitAccess(Field mapField, Method getFactor, Method getIcons, Method getLocation) {
            this.mapField = mapField;
            this.getFactor = getFactor;
            this.getIcons = getIcons;
            this.getLocation = getLocation;
        }
    }

    private static final class HitCache {
        Object location;
        int iconCount = Integer.MIN_VALUE;
        float factor = Float.NaN;
        boolean hasLast;
        SectorEntityToken lastResult;
        long lastAtNanos;
        volatile long lastAccessNanos;
        volatile int activeUsers;
        long lastCellPruneNanos;
        final LinkedHashMap<Long, CellHit> cells;

        HitCache(int maxEntries, long now) {
            lastAccessNanos = now;
            lastCellPruneNanos = now;
            final int limit = Math.max(1, maxEntries);
            cells = new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, CellHit> eldest) {
                    return size() > limit;
                }
            };
        }


        void reset(Object newLocation, int newIconCount, float newFactor) {
            location = newLocation;
            iconCount = newIconCount;
            factor = newFactor;
            hasLast = false;
            lastResult = null;
            lastAtNanos = 0L;
            cells.clear();
        }

        void setLast(SectorEntityToken result, long now) {
            hasLast = true;
            lastResult = result;
            lastAtNanos = now;
        }
    }

    private static final class CellHit {
        final SectorEntityToken value;
        final long atNanos;
        CellHit(SectorEntityToken value, long atNanos) {
            this.value = value;
            this.atNanos = atNanos;
        }
    }

    // ---------------------------------------------------------------------
    // Map-open preprocessing caches
    // ---------------------------------------------------------------------

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void updateSystemNebulasCached(Object owner, List systemNebulas,
                                                  List constellationLabels, List nebulaStars) {
        PrepatcherConfig c = config;
        long generation = campaignCacheGeneration;
        if (c == null || !c.systemNebulaCache || !campaignCachesReady(generation)) {
            invokeOriginalSystemNebulaBuilder(owner);
            return;
        }
        systemNebulas.clear();
        constellationLabels.clear();
        nebulaStars.clear();
        Throwable cacheFailure = null;
        try {
            SectorAPI sector = Global.getSector();
            if (sector != null) {
                NebulaCache cache = getOrBuildNebulaCache(sector, c, generation);
                if (cache != null) {
                    SystemNebulaAccess access = SystemNebulaAccess.ACCESS.get(owner.getClass());
                    for (int i = 0; i < cache.systemNebulaSystems.size(); i++) {
                        StarSystemAPI system = cache.systemNebulaSystems.get(i);
                        Object terrain = access.createNebula.invoke(null, system);
                        if (!(terrain instanceof SectorEntityToken token)) {
                            throw new IllegalStateException("createNebula returned " + terrain);
                        }
                        token.getCustomData().put("system", system);
                        token.getCustomData().put("seed", Long.valueOf(cache.systemNebulaSeeds[i]));
                        systemNebulas.add(terrain);
                    }
                    for (Constellation constellation : cache.constellations) {
                        Object label = access.createConstellationLabel.invoke(null, constellation);
                        if (!(label instanceof SectorEntityToken token)) {
                            throw new IllegalStateException("createConstellationLabel returned " + label);
                        }
                        token.getCustomData().put("constellationLabel", Boolean.TRUE);
                        token.getCustomData().put("constellation", constellation);
                        constellationLabels.add(label);
                    }
                    nebulaStars.addAll(cache.nebulaStars);
                    NEBULA_HITS.increment();
                    return;
                }
            }
        } catch (Throwable ex) {
            cacheFailure = ex;
        }

        systemNebulas.clear();
        constellationLabels.clear();
        nebulaStars.clear();
        if (cacheFailure != null) {
            PrepatcherLog.warn("System-nebula metadata cache failed; using vanilla builder: "
                    + cacheFailure.getClass().getSimpleName() + ": " + cacheFailure.getMessage());
        }
        // Never place the original call inside the compatibility catch: if game
        // or mod code throws, it must be observed after exactly one invocation.
        invokeOriginalSystemNebulaBuilder(owner);
    }

    private static NebulaCache getOrBuildNebulaCache(SectorAPI sector, PrepatcherConfig c,
                                                      long generation) {
        int systems = sector.getStarSystems().size();
        long now = System.nanoTime();
        NebulaCacheSlot slot = nebulaCacheSlot;
        NebulaCache current = slot.value;
        if (campaignCachesReady(generation)
                && nebulaCacheSlot == slot
                && current != null && current.sector.get() == sector && current.systemCount == systems
                && (c.systemNebulaMaxAgeMs <= 0
                    || now - current.createdAtNanos <= c.systemNebulaMaxAgeMs * 1_000_000L)) {
            return current;
        }
        synchronized (slot) {
            if (nebulaCacheSlot != slot || !campaignCachesReady(generation)) return null;
            current = slot.value;
            if (current != null && current.sector.get() == sector && current.systemCount == systems
                    && (c.systemNebulaMaxAgeMs <= 0
                        || now - current.createdAtNanos <= c.systemNebulaMaxAgeMs * 1_000_000L)) {
                return current;
            }
            NebulaCache built = buildNebulaMetadata(sector, now);
            if (nebulaCacheSlot != slot || !campaignCachesReady(generation)) return null;
            slot.value = built;
            // A lifecycle transition may run between the pre-publication check
            // and assignment. It swaps the static slot without waiting here, so
            // this write can then reach only the detached slot. Clear it eagerly
            // and refuse the stale result if either identity check changed.
            if (nebulaCacheSlot != slot || !campaignCachesReady(generation)) {
                slot.value = null;
                return null;
            }
            NEBULA_MISSES.increment();
            return built;
        }
    }

    private static NebulaCache buildNebulaMetadata(SectorAPI sector, long now) {
        long baseSeed = 0L;
        String seedString = sector.getSeedString();
        if (seedString != null && seedString.length() > 3) {
            baseSeed = Long.parseLong(seedString.substring(3));
        }
        Random random = Misc.getRandom(baseSeed, 10);
        ArrayList<StarSystemAPI> nebulaSystems = new ArrayList<>();
        ArrayList<Long> seeds = new ArrayList<>();
        HashSet<Constellation> constellationSet = new HashSet<>();
        ArrayList<SectorEntityToken> nebulaStars = new ArrayList<>();
        for (StarSystemAPI system : sector.getStarSystems()) {
            if (Boolean.TRUE.equals(system.hasSystemwideNebula()) && system.getAge() != null) {
                nebulaSystems.add(system);
                seeds.add(random.nextLong());
            }
            if (system.isInConstellation()) {
                Constellation constellation = system.getConstellation();
                if (constellation != null) constellationSet.add(constellation);
            }
            if (system.getType() == StarSystemGenerator.StarSystemType.NEBULA
                    && system.getStar() != null
                    && system.getStar().getSpec().isNebulaCenter()) {
                nebulaStars.add(system.getStar());
            }
        }
        long[] seedArray = new long[seeds.size()];
        for (int i = 0; i < seeds.size(); i++) seedArray[i] = seeds.get(i);
        return new NebulaCache(new WeakReference<>(sector), sector.getStarSystems().size(), now,
                nebulaSystems, seedArray, new ArrayList<>(constellationSet), nebulaStars);
    }

    private static void invokeOriginalSystemNebulaBuilder(Object owner) {
        try {
            SystemNebulaOriginalAccess.ORIGINAL.get(owner.getClass()).invoke(owner);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException("Unable to invoke original Starsector system-nebula builder", ex);
        }
    }

    private static final class SystemNebulaOriginalAccess {
        static final ClassValue<Method> ORIGINAL = new ClassValue<>() {
            @Override
            protected Method computeValue(Class<?> type) {
                try {
                    Method original = findDeclaredMethod(type, "smo$originalUpdateSystemNebulas");
                    original.setAccessible(true);
                    return original;
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        };
    }

    private static final class SystemNebulaAccess {
        static final ClassValue<SystemNebulaAccess> ACCESS = new ClassValue<>() {
            @Override
            protected SystemNebulaAccess computeValue(Class<?> type) {
                try {
                    Method createNebula = type.getMethod("createNebula", StarSystemAPI.class);
                    Method createConstellationLabel = type.getMethod("createConstellationLabel", Constellation.class);
                    return new SystemNebulaAccess(createNebula, createConstellationLabel);
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        };

        final Method createNebula;
        final Method createConstellationLabel;

        SystemNebulaAccess(Method createNebula, Method createConstellationLabel) {
            this.createNebula = createNebula;
            this.createConstellationLabel = createConstellationLabel;
        }
    }

    public static void forceClearSampleCacheThrottled(Object plugin) {
        if (plugin == null) return;
        PrepatcherConfig c = config;
        long generation = campaignCacheGeneration;
        if (c == null || !c.sampleCacheClearThrottle || !campaignCachesReady(generation)
                || c.sampleCacheClearMinIntervalMs <= 0) {
            ((BaseTiledTerrain) plugin).forceClearSampleCache();
            return;
        }
        long now = System.nanoTime();
        long interval = c.sampleCacheClearMinIntervalMs * 1_000_000L;
        WeakHashMap<Object, Long> sampleClearTimes = SAMPLE_CLEAR_TIMES;
        synchronized (sampleClearTimes) {
            if (!campaignCachesReady(generation)) {
                // Fall through to the exact vanilla clear below.
            } else {
                Long previous = sampleClearTimes.get(plugin);
                if (campaignCachesReady(generation)
                        && previous != null && now - previous < interval) {
                    SAMPLE_CLEAR_SKIPS.increment();
                    return;
                }
                if (campaignCachesReady(generation)) {
                    sampleClearTimes.put(plugin, now);
                    if (!campaignCachesReady(generation)) {
                        // Protect against a re-entrant reset from custom key code.
                        sampleClearTimes.clear();
                    }
                }
            }
        }
        // A generation change only disables this optional throttle. The exact
        // vanilla clear still runs, preserving all behavior and side effects.
        ((BaseTiledTerrain) plugin).forceClearSampleCache();
    }

    public static float gridSpacing() {
        PrepatcherConfig c = config;
        if (c == null || !c.gridLineCap || c.gridMaxLinesPerAxis <= 0) {
            return c == null ? 2000f : c.gridBaseSpacing;
        }
        try {
            float width = Math.abs(Global.getSettings().getFloat("sectorWidth"));
            float height = Math.abs(Global.getSettings().getFloat("sectorHeight"));
            if (Float.floatToIntBits(width) == Float.floatToIntBits(cachedGridWidth)
                    && Float.floatToIntBits(height) == Float.floatToIntBits(cachedGridHeight)
                    && cachedGridSpacing > 0f) {
                return cachedGridSpacing;
            }
            float base = c.gridBaseSpacing;
            float maxDimension = Math.max(width, height);
            float lines = maxDimension / base;
            int multiplier = Math.max(1, (int) Math.ceil(lines / c.gridMaxLinesPerAxis));
            float spacing = base * multiplier;
            cachedGridWidth = width;
            cachedGridHeight = height;
            cachedGridSpacing = spacing;
            if (spacing > base) {
                PrepatcherLog.info("Map grid LOD: sector=" + width + "x" + height
                        + ", spacing=" + spacing + " (base=" + base + ")");
            }
            return spacing;
        } catch (Throwable ex) {
            return c.gridBaseSpacing;
        }
    }

    private static final class NebulaCacheSlot {
        volatile NebulaCache value;
    }

    private static final class NebulaCache {
        final WeakReference<SectorAPI> sector;
        final int systemCount;
        final long createdAtNanos;
        final List<StarSystemAPI> systemNebulaSystems;
        final long[] systemNebulaSeeds;
        final List<Constellation> constellations;
        final List<SectorEntityToken> nebulaStars;

        NebulaCache(WeakReference<SectorAPI> sector, int systemCount, long createdAtNanos,
                    List<StarSystemAPI> systemNebulaSystems, long[] systemNebulaSeeds,
                    List<Constellation> constellations, List<SectorEntityToken> nebulaStars) {
            this.sector = sector;
            this.systemCount = systemCount;
            this.createdAtNanos = createdAtNanos;
            this.systemNebulaSystems = systemNebulaSystems;
            this.systemNebulaSeeds = systemNebulaSeeds;
            this.constellations = constellations;
            this.nebulaStars = nebulaStars;
        }
    }

    // ---------------------------------------------------------------------
    // Closed-map campaign bookkeeping.
    //
    // CampaignEngine.advance() calls readdChangeListeners() every frame. The
    // vanilla method walks hyperspace and every star system and only writes the
    // same ObjectRepository listener reference again. Creation/removal APIs
    // already update listeners immediately, so retain the public vanilla method
    // unchanged and throttle only the call site in advance(). Structural changes
    // trigger an immediate refresh; a periodic audit covers direct list mutation
    // by mods.
    // ---------------------------------------------------------------------

    public static void readdChangeListenersIfNeeded(Object engine, List<?> systems, Object hyperspace) {
        if (engine == null) return;
        PrepatcherConfig c = config;
        long generation = campaignCacheGeneration;
        if (c == null || !c.campaignListenerThrottle
                || !campaignCachesReady(generation, engine)) {
            invokeReaddChangeListeners(engine);
            return;
        }

        int size = -1;
        Object first = null;
        Object last = null;
        long now = 0L;
        boolean refresh = true;
        boolean compatibilityFallback = false;
        Map<Object, CampaignListenerState> listenerStates = CAMPAIGN_LISTENER_STATES;
        try {
            size = systems == null ? -1 : systems.size();
            first = size > 0 ? systems.get(0) : null;
            last = size > 0 ? systems.get(size - 1) : null;
            now = System.nanoTime();
            long auditNs = c.campaignListenerAuditMs * 1_000_000L;

            synchronized (listenerStates) {
                if (!campaignCachesReady(generation, engine)) {
                    compatibilityFallback = true;
                } else {
                    CampaignListenerState state = listenerStates.get(engine);
                    if (!campaignCachesReady(generation, engine)) {
                        compatibilityFallback = true;
                    } else {
                        refresh = state == null
                                || state.systems != systems
                                || state.hyperspace != hyperspace
                                || state.systemCount != size
                                || state.firstSystem != first
                                || state.lastSystem != last
                                || (auditNs > 0L && now - state.refreshedAtNanos >= auditNs);
                    }
                }
            }
        } catch (Throwable ex) {
            compatibilityFallback = true;
        }

        if (compatibilityFallback) {
            // Keep the original call outside the catch boundary so an exception
            // from game/mod code cannot cause a retry.
            invokeReaddChangeListeners(engine);
            return;
        }

        if (!refresh && campaignCachesReady(generation, engine)) {
            CAMPAIGN_LISTENER_SKIPS.increment();
            return;
        }

        // Keep exceptions from the vanilla method exact: do not catch and retry it.
        invokeReaddChangeListeners(engine);
        try {
            synchronized (listenerStates) {
                if (!campaignCachesReady(generation, engine)) return;
                CampaignListenerState state = listenerStates.computeIfAbsent(
                        engine, ignored -> new CampaignListenerState());
                if (!campaignCachesReady(generation, engine)) {
                    listenerStates.clear();
                } else {
                    state.systems = systems;
                    state.hyperspace = hyperspace;
                    state.systemCount = size;
                    state.firstSystem = first;
                    state.lastSystem = last;
                    state.refreshedAtNanos = now;
                }
            }
        } catch (Throwable ignored) {
            // A failed bookkeeping update merely causes another vanilla refresh on
            // the next frame; it must not affect campaign execution.
        }
        CAMPAIGN_LISTENER_RUNS.increment();
    }

    private static void invokeReaddChangeListeners(Object engine) {
        try {
            CampaignListenerAccess.ACCESS.get(engine.getClass()).readdChangeListeners.invoke(engine);
        } catch (InvocationTargetException ex) {
            throwUnchecked(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException("Unable to invoke CampaignEngine.readdChangeListeners()", ex);
        }
    }

    private static final class CampaignListenerAccess {
        static final ClassValue<CampaignListenerAccess> ACCESS = new ClassValue<>() {
            @Override
            protected CampaignListenerAccess computeValue(Class<?> type) {
                try {
                    Method refresh = type.getMethod("readdChangeListeners");
                    refresh.setAccessible(true);
                    return new CampaignListenerAccess(refresh);
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException("Unexpected CampaignEngine layout: " + type.getName(), ex);
                }
            }
        };

        final Method readdChangeListeners;

        CampaignListenerAccess(Method readdChangeListeners) {
            this.readdChangeListeners = readdChangeListeners;
        }
    }


    /** Exact ordered fingerprint used only to decide whether Economy.advance()
     * needs to set ReachEconomy's private dirty flag automatically. */
    private static final class EconomyLocationState {
        static final int MATCH = 0;
        static final int SEQUENCE_MISMATCH = 1;
        static final int DETAILS_MISMATCH = 2;

        final WeakReference<Object> sourceIdentity;
        final WeakReference<MarketAPI>[] markets;
        final WeakReference<LocationAPI>[] locations;
        final String[] marketIds;
        final String[] locationIds;
        final long structureEpoch;
        volatile long auditedAtNanos;

        EconomyLocationState(Object sourceIdentity,
                             WeakReference<MarketAPI>[] markets,
                             WeakReference<LocationAPI>[] locations,
                             String[] marketIds, String[] locationIds,
                             long structureEpoch, long auditedAtNanos) {
            this.sourceIdentity = new WeakReference<>(sourceIdentity);
            this.markets = markets;
            this.locations = locations;
            this.marketIds = marketIds;
            this.locationIds = locationIds;
            this.structureEpoch = structureEpoch;
            this.auditedAtNanos = auditedAtNanos;
        }

        static EconomyLocationState capture(List<MarketAPI> live) {
            return capture(live, Long.MIN_VALUE, System.nanoTime());
        }

        @SuppressWarnings("unchecked")
        static EconomyLocationState capture(List<MarketAPI> live,
                                            long structureEpoch,
                                            long auditedAtNanos) {
            int size = live.size();
            WeakReference<MarketAPI>[] markets =
                    (WeakReference<MarketAPI>[]) new WeakReference<?>[size];
            WeakReference<LocationAPI>[] locations =
                    (WeakReference<LocationAPI>[]) new WeakReference<?>[size];
            String[] marketIds = new String[size];
            String[] locationIds = new String[size];
            for (int i = 0; i < size; i++) {
                MarketAPI market = live.get(i);
                if (market == null) {
                    throw new IllegalStateException("Null market in ReachEconomy market list");
                }
                LocationAPI location = market.getContainingLocation();
                markets[i] = new WeakReference<>(market);
                locations[i] = new WeakReference<>(location);
                marketIds[i] = market.getId();
                locationIds[i] = location == null ? null : location.getId();
            }
            return new EconomyLocationState(live, markets, locations, marketIds, locationIds,
                    structureEpoch, auditedAtNanos);
        }

        int mismatchKind(List<MarketAPI> live) {
            try {
                int size = live.size();
                if (sourceIdentity.get() != live || markets.length != size) return SEQUENCE_MISMATCH;
                boolean detailsMismatch = false;
                for (int i = 0; i < size; i++) {
                    MarketAPI market = live.get(i);
                    if (market != markets[i].get()) return SEQUENCE_MISMATCH;
                    LocationAPI location = market == null ? null : market.getContainingLocation();
                    if (location != locations[i].get()
                            || !Objects.equals(market == null ? null : market.getId(), marketIds[i])
                            || !Objects.equals(location == null ? null : location.getId(), locationIds[i])) {
                        detailsMismatch = true;
                    }
                }
                return detailsMismatch ? DETAILS_MISMATCH : MATCH;
            } catch (Throwable ignored) {
                return SEQUENCE_MISMATCH;
            }
        }

        boolean matches(List<MarketAPI> live) {
            return mismatchKind(live) == MATCH;
        }
    }

    private static void persistentHit(int kind) {
        switch (kind) {
            case 0 -> ECONOMY_MARKET_PERSISTENT_HITS.increment();
            case 1 -> MARKET_CONDITION_PERSISTENT_HITS.increment();
            case 2 -> MARKET_INDUSTRY_PERSISTENT_HITS.increment();
            default -> { }
        }
    }

    private static void persistentAudit(int kind) {
        ECONOMY_PERSISTENT_SNAPSHOT_AUDITS.increment();
        switch (kind) {
            case 0 -> ECONOMY_MARKET_PERSISTENT_AUDITS.increment();
            case 1 -> MARKET_CONDITION_PERSISTENT_AUDITS.increment();
            case 2 -> MARKET_INDUSTRY_PERSISTENT_AUDITS.increment();
            default -> { }
        }
    }

    private static void persistentMismatch(int kind) {
        ECONOMY_PERSISTENT_SNAPSHOT_MISMATCHES.increment();
        switch (kind) {
            case 0 -> ECONOMY_MARKET_PERSISTENT_MISMATCHES.increment();
            case 1 -> MARKET_CONDITION_PERSISTENT_MISMATCHES.increment();
            case 2 -> MARKET_INDUSTRY_PERSISTENT_MISMATCHES.increment();
            default -> { }
        }
    }

    private static void persistentRebuild(int kind, int elements) {
        ECONOMY_PERSISTENT_SNAPSHOT_REBUILDS.increment();
        ECONOMY_PERSISTENT_SNAPSHOT_ELEMENTS.add(elements);
        switch (kind) {
            case 0 -> {
                ECONOMY_MARKET_PERSISTENT_REBUILDS.increment();
                ECONOMY_MARKET_PERSISTENT_ELEMENTS.add(elements);
            }
            case 1 -> {
                MARKET_CONDITION_PERSISTENT_REBUILDS.increment();
                MARKET_CONDITION_PERSISTENT_ELEMENTS.add(elements);
            }
            case 2 -> {
                MARKET_INDUSTRY_PERSISTENT_REBUILDS.increment();
                MARKET_INDUSTRY_PERSISTENT_ELEMENTS.add(elements);
            }
            default -> { }
        }
    }

    private static void persistentEpoch(int kind) {
        PERSISTENT_STRUCTURE_EPOCHS.increment();
    }

    /**
     * Owner-local copy-on-write snapshot state. Snapshots are never mutated after
     * publication, so a nested/re-entrant advance may rebuild the field without
     * invalidating an iterator held by the outer call. The Economy instance also
     * stores its ReachEconomy location fingerprint here, removing the synchronized
     * WeakHashMap lookup from the persistent hot path.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final class PersistentSnapshotState {
        long epoch;
        long snapshotEpoch = Long.MIN_VALUE;
        ArrayList snapshot;
        Object sourceIdentity;
        int auditCountdown;
        long nextAuditNanos;
        int kind = -1;

        EconomyLocationState locationState;
        Object locationSourceIdentity;
        long locationEpoch = Long.MIN_VALUE;
        long nextLocationAuditNanos;

        boolean acceptKind(int requestedKind) {
            if (requestedKind < 0 || requestedKind > 2) return false;
            int current = kind;
            if (current == -1) {
                kind = requestedKind;
                return true;
            }
            return current == requestedKind;
        }

        ArrayList borrowTimed(Collection source, int auditMs, int requestedKind) {
            ArrayList current = snapshot;
            long observedEpoch = epoch;
            if (current == null || snapshotEpoch != observedEpoch) {
                return rebuild(source, observedEpoch, auditMs, true, requestedKind);
            }
            if (sourceIdentity != source) {
                persistentMismatch(requestedKind);
                long changedEpoch = ++epoch;
                persistentEpoch(requestedKind);
                return rebuild(source, changedEpoch, auditMs, true, requestedKind);
            }
            long now = System.nanoTime();
            long interval = (long) auditMs * 1_000_000L;
            if (interval > 0L && now < nextAuditNanos) {
                persistentHit(requestedKind);
                return current;
            }
            persistentAudit(requestedKind);
            if (identityMatches(current, source)) {
                nextAuditNanos = nextAuditNanos(now, interval);
                persistentHit(requestedKind);
                return current;
            }
            persistentMismatch(requestedKind);
            long changedEpoch = ++epoch;
            persistentEpoch(requestedKind);
            return rebuild(source, changedEpoch, auditMs, true, requestedKind);
        }

        ArrayList borrowFrames(Collection source, int auditFrames, int requestedKind) {
            ArrayList current = snapshot;
            long observedEpoch = epoch;
            if (current == null || snapshotEpoch != observedEpoch) {
                return rebuild(source, observedEpoch, auditFrames, false, requestedKind);
            }
            if (sourceIdentity != source) {
                persistentMismatch(requestedKind);
                long changedEpoch = ++epoch;
                persistentEpoch(requestedKind);
                return rebuild(source, changedEpoch, auditFrames, false, requestedKind);
            }
            int remaining = auditCountdown - 1;
            auditCountdown = remaining;
            if (remaining > 0) {
                persistentHit(requestedKind);
                return current;
            }
            persistentAudit(requestedKind);
            if (identityMatches(current, source)) {
                auditCountdown = Math.max(1, auditFrames);
                persistentHit(requestedKind);
                return current;
            }
            persistentMismatch(requestedKind);
            long changedEpoch = ++epoch;
            persistentEpoch(requestedKind);
            return rebuild(source, changedEpoch, auditFrames, false, requestedKind);
        }

        private ArrayList rebuild(Collection source, long observedEpoch,
                                  int audit, boolean timed, int requestedKind) {
            // Copy-on-write is essential: never clear a snapshot that an outer
            // re-entrant callback may still be iterating.
            ArrayList fresh = new ArrayList(source);
            snapshot = fresh;
            sourceIdentity = source;
            snapshotEpoch = observedEpoch;
            if (timed) {
                long now = System.nanoTime();
                long interval = (long) Math.max(0, audit) * 1_000_000L;
                nextAuditNanos = nextAuditNanos(now, interval);
            } else {
                auditCountdown = Math.max(1, audit);
            }
            persistentRebuild(requestedKind, fresh.size());
            return fresh;
        }

        void invalidateSnapshot() {
            snapshot = null;
            sourceIdentity = null;
            snapshotEpoch = Long.MIN_VALUE;
            auditCountdown = 0;
            nextAuditNanos = 0L;
        }

        private static boolean identityMatches(List snapshot, Collection source) {
            try {
                if (snapshot.size() != source.size()) return false;
                if (source instanceof List list && source instanceof RandomAccess) {
                    for (int i = 0; i < snapshot.size(); i++) {
                        if (snapshot.get(i) != list.get(i)) return false;
                    }
                    return true;
                }
                Iterator iterator = source.iterator();
                for (int i = 0; i < snapshot.size(); i++) {
                    if (!iterator.hasNext() || snapshot.get(i) != iterator.next()) return false;
                }
                return !iterator.hasNext();
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    /** Per-simulation-tick context owned by the unified CampaignEngine scheduler. */
    private static final class MarketSchedulerTick {
        int depth;
        boolean active;
        long generation = -1L;
        long simulationTick;
        long batch;
        LocationAPI currentLocation;
        MarketAPI interactionMarket;

        void clearContext() {
            active = false;
            generation = -1L;
            simulationTick = 0L;
            batch = 0L;
            currentLocation = null;
            interactionMarket = null;
        }
    }

    /** One visual-frame batch containing one or more CampaignEngine.advance() calls. */
    private static final class MarketSchedulerBatch {
        boolean open;
        boolean finishing;
        boolean fastForwardIteration;
        long generation = -1L;
        long id;
        int simulationTicks;
        LocationAPI currentLocation;
        MarketAPI interactionMarket;
        final ArrayList<WeakIdentityEntry<MarketAPI, MarketScheduleState>> touched =
                new ArrayList<>();

        void open(long generation, long id) {
            this.open = true;
            this.finishing = false;
            this.fastForwardIteration = false;
            this.generation = generation;
            this.id = id;
            this.simulationTicks = 0;
            this.currentLocation = null;
            this.interactionMarket = null;
            this.touched.clear();
        }

        void clearAfterFinish() {
            open = false;
            finishing = false;
            fastForwardIteration = false;
            generation = -1L;
            id = 0L;
            simulationTicks = 0;
            currentLocation = null;
            interactionMarket = null;
            touched.clear();
        }
    }

    private static final class MarketScheduleState {
        float pendingAmount;
        int pendingSteps;
        ArrayDeque<PendingStepRun> pendingRuns;
        long nextDueBatch = Long.MIN_VALUE;
        long lastTouchedBatch = Long.MIN_VALUE;
        long lastDueCheckBatch = Long.MIN_VALUE;
        long dueDecisionBatch = Long.MIN_VALUE;
        boolean dueHot;
        boolean dueHidden;
        int dueInterval = 1;
        int duePhase;
        long lastPolicyAuditBatch = Long.MIN_VALUE;
        long lastSchedulePolicyAuditBatch = Long.MIN_VALUE;
        LocationAPI cachedScheduleLocation;
        boolean cachedScheduleHidden;
        boolean schedulePolicyCached;
        long lastSourceSimulationTick = Long.MIN_VALUE;
        long debtSequence;
        long deliveredCallbacks;
        double deliveredAmount;
        long lastDeliveredSequence;
        int lastDeliveredOrigin = MARKET_ORIGIN_NONE;
        long lastDeliveredNanos;
        int lastSource = -1;
        int sourceMask;
        boolean perSimulationTick;
        boolean constructionFullRate;
        int constructionReasonMask;
        long constructionMutationEpoch;
        long constructionAuditedEpoch = Long.MIN_VALUE;
        long lastConstructionAuditBatch = Long.MIN_VALUE;
        boolean inAdvance;
        boolean disabled;
    }

    private static final class ConstructionProbe {
        String queueClass = "";
        int queueSize = -2;
        int industryCount = -2;

        // Legacy first-positive fields are retained for existing CSV consumers.
        int industryIndex = -1;
        String industryId = "";
        String industryClass = "";
        boolean industryBuilding;
        boolean industryEffectiveBuilding;
        boolean industryUpgrading;

        int buildingIndustryIndex = -1;
        String buildingIndustryId = "";
        String buildingIndustryClass = "";
        String buildingRawBuildingField = "";
        String buildingBuildProgress = "";
        String buildingBuildTime = "";
        String buildingUpgradeId = "";
        String buildingIsFunctional = "";

        int reportedBuildingWithoutRawIndustryIndex = -1;
        String reportedBuildingWithoutRawIndustryId = "";
        String reportedBuildingWithoutRawIndustryClass = "";
        String reportedBuildingWithoutRawRawBuildingField = "";
        String reportedBuildingWithoutRawBuildProgress = "";
        String reportedBuildingWithoutRawBuildTime = "";
        String reportedBuildingWithoutRawUpgradeId = "";
        String reportedBuildingWithoutRawIsFunctional = "";

        int upgradingIndustryIndex = -1;
        String upgradingIndustryId = "";
        String upgradingIndustryClass = "";
        String upgradingRawBuildingField = "";
        String upgradingBuildProgress = "";
        String upgradingBuildTime = "";
        String upgradingUpgradeId = "";
        String upgradingIsFunctional = "";

        String exceptionClass = "";
        String exceptionMessage = "";

        void reset() {
            queueClass = "";
            queueSize = -2;
            industryCount = -2;
            industryIndex = -1;
            industryId = "";
            industryClass = "";
            industryBuilding = false;
            industryEffectiveBuilding = false;
            industryUpgrading = false;
            buildingIndustryIndex = -1;
            buildingIndustryId = "";
            buildingIndustryClass = "";
            buildingRawBuildingField = "";
            buildingBuildProgress = "";
            buildingBuildTime = "";
            buildingUpgradeId = "";
            buildingIsFunctional = "";
            reportedBuildingWithoutRawIndustryIndex = -1;
            reportedBuildingWithoutRawIndustryId = "";
            reportedBuildingWithoutRawIndustryClass = "";
            reportedBuildingWithoutRawRawBuildingField = "";
            reportedBuildingWithoutRawBuildProgress = "";
            reportedBuildingWithoutRawBuildTime = "";
            reportedBuildingWithoutRawUpgradeId = "";
            reportedBuildingWithoutRawIsFunctional = "";
            upgradingIndustryIndex = -1;
            upgradingIndustryId = "";
            upgradingIndustryClass = "";
            upgradingRawBuildingField = "";
            upgradingBuildProgress = "";
            upgradingBuildTime = "";
            upgradingUpgradeId = "";
            upgradingIsFunctional = "";
            exceptionClass = "";
            exceptionMessage = "";
        }

        void captureIndustry(Industry industry, int index,
                             boolean reportedBuilding, boolean effectiveBuilding,
                             boolean upgrading) {
            industryIndex = index;
            industryClass = ((Object) industry).getClass().getName();
            industryBuilding = reportedBuilding;
            industryEffectiveBuilding = effectiveBuilding;
            industryUpgrading = upgrading;
            industryId = safeIndustryId(industry);
        }

        void captureBuildingIndustry(
                Industry industry, int index, Boolean rawBuilding) {
            if (buildingIndustryIndex >= 0) return;
            buildingIndustryIndex = index;
            buildingIndustryClass = ((Object) industry).getClass().getName();
            buildingIndustryId = safeIndustryId(industry);
            String[] details = baseIndustryDetails(industry, rawBuilding);
            buildingRawBuildingField = details[0];
            buildingBuildProgress = details[1];
            buildingBuildTime = details[2];
            buildingUpgradeId = details[3];
            buildingIsFunctional = details[4];
        }

        void captureReportedBuildingWithoutRawIndustry(
                Industry industry, int index, Boolean rawBuilding) {
            if (reportedBuildingWithoutRawIndustryIndex >= 0) return;
            reportedBuildingWithoutRawIndustryIndex = index;
            reportedBuildingWithoutRawIndustryClass =
                    ((Object) industry).getClass().getName();
            reportedBuildingWithoutRawIndustryId = safeIndustryId(industry);
            String[] details = baseIndustryDetails(industry, rawBuilding);
            reportedBuildingWithoutRawRawBuildingField = details[0];
            reportedBuildingWithoutRawBuildProgress = details[1];
            reportedBuildingWithoutRawBuildTime = details[2];
            reportedBuildingWithoutRawUpgradeId = details[3];
            reportedBuildingWithoutRawIsFunctional = details[4];
        }

        void captureUpgradingIndustry(
                Industry industry, int index, Boolean rawBuilding) {
            if (upgradingIndustryIndex >= 0) return;
            upgradingIndustryIndex = index;
            upgradingIndustryClass = ((Object) industry).getClass().getName();
            upgradingIndustryId = safeIndustryId(industry);
            String[] details = baseIndustryDetails(industry, rawBuilding);
            upgradingRawBuildingField = details[0];
            upgradingBuildProgress = details[1];
            upgradingBuildTime = details[2];
            upgradingUpgradeId = details[3];
            upgradingIsFunctional = details[4];
        }

        private static String safeIndustryId(Industry industry) {
            try {
                String id = industry.getId();
                return id == null ? "" : id;
            } catch (Throwable ignored) {
                return "<unavailable>";
            }
        }

        /**
         * Snapshot BaseIndustry internals as scalar text only. Reflection keeps
         * this diagnostics path compatible with API variations and avoids
         * retaining strong references to the industry object.
         */
        private static String[] baseIndustryDetails(
                Industry industry, Boolean rawBuilding) {
            String[] result = {"", "", "", "", ""};
            if (!(industry instanceof BaseIndustry)) return result;
            result[0] = rawBuilding == null
                    ? readField(industry, "building")
                    : String.valueOf(rawBuilding.booleanValue());
            result[1] = readMethodOrField(industry, "getBuildProgress", "buildProgress");
            result[2] = readMethodOrField(industry, "getBuildTime", "buildTime");
            result[3] = firstAvailable(
                    readMethod(industry, "getUpgradeId"),
                    readField(industry, "upgradeId"),
                    readField(industry, "upgrade"));
            result[4] = readMethod(industry, "isFunctional");
            return result;
        }

        private static String readMethodOrField(
                Object target, String methodName, String fieldName) {
            return firstAvailable(readMethod(target, methodName),
                    readField(target, fieldName));
        }

        private static String readMethod(Object target, String name) {
            if (target == null) return "";
            Class<?> type = target.getClass();
            while (type != null) {
                try {
                    Method method = type.getDeclaredMethod(name);
                    method.setAccessible(true);
                    return diagnosticScalar(method.invoke(target));
                } catch (NoSuchMethodException ignored) {
                    type = type.getSuperclass();
                } catch (Throwable ignored) {
                    return "<unavailable>";
                }
            }
            return "";
        }

        private static String readField(Object target, String name) {
            if (target == null) return "";
            Class<?> type = target.getClass();
            while (type != null) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return diagnosticScalar(field.get(target));
                } catch (NoSuchFieldException ignored) {
                    type = type.getSuperclass();
                } catch (Throwable ignored) {
                    return "<unavailable>";
                }
            }
            return "";
        }

        private static String diagnosticScalar(Object value) {
            if (value == null) return "<null>";
            if (value instanceof Number || value instanceof Boolean
                    || value instanceof Character || value instanceof String) {
                return String.valueOf(value);
            }
            return value.getClass().getName() + ':' + String.valueOf(value);
        }

        private static String firstAvailable(String... values) {
            for (String value : values) {
                if (value != null && !value.isEmpty()) return value;
            }
            return "";
        }

        void failure(Throwable failure) {
            if (failure == null || !exceptionClass.isEmpty()) return;
            exceptionClass = failure.getClass().getName();
            String message = failure.getMessage();
            exceptionMessage = message == null ? "" : message;
        }
    }

    private static final class ConstructionDiagnostics {
        private static final DateTimeFormatter SESSION_FORMAT =
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                        .withZone(ZoneOffset.UTC);
        private static final String HEADER =
                "timestamp_utc,first_seen_utc,last_seen_utc,occurrences,"
                        + "market_identity_hash,market_id,market_name,trigger,sample_bucket,transition,"
                        + "reason_mask,reasons,queue_class,queue_size,industry_count,"
                        + "industry_index,industry_id,industry_class,is_building,"
                        + "effective_is_building,is_upgrading,reported_building_without_raw,"
                        + "building_industry_index,building_industry_id,building_industry_class,"
                        + "building_raw_building_field,building_build_progress,building_build_time,"
                        + "building_upgrade_id,building_is_functional,"
                        + "reported_building_without_raw_industry_index,"
                        + "reported_building_without_raw_industry_id,"
                        + "reported_building_without_raw_industry_class,"
                        + "reported_building_without_raw_raw_building_field,"
                        + "reported_building_without_raw_build_progress,"
                        + "reported_building_without_raw_build_time,"
                        + "reported_building_without_raw_upgrade_id,"
                        + "reported_building_without_raw_is_functional,"
                        + "upgrading_industry_index,upgrading_industry_id,upgrading_industry_class,"
                        + "upgrading_raw_building_field,upgrading_build_progress,upgrading_build_time,"
                        + "upgrading_upgrade_id,upgrading_is_functional,"
                        + "mutation_epoch,audited_epoch,last_audit_batch,old_active,new_active,"
                        + "old_reason_mask,new_reason_mask,exception_class,exception_message\n";

        private final int maxSamplesPerReason;
        private final int reportIntervalSeconds;
        private final Path output;
        private final ConcurrentHashMap<String, ConstructionDiagnosticSample> samples =
                new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, AtomicInteger> samplesPerReason =
                new ConcurrentHashMap<>();
        private final Object writeLock = new Object();

        ConstructionDiagnostics(PrepatcherConfig configuration, Path root) throws IOException {
            maxSamplesPerReason = configuration
                    .marketConstructionDiagnosticsMaxSamplesPerReason;
            reportIntervalSeconds = configuration.statsLogIntervalSeconds > 0
                    ? configuration.statsLogIntervalSeconds : 30;
            String sessionName = "session-" + SESSION_FORMAT.format(Instant.now())
                    + "-pid" + ProcessHandle.current().pid();
            Path directory = root.resolve("logs")
                    .resolve("market-construction-diagnostics").resolve(sessionName);
            Files.createDirectories(directory);
            output = directory.resolve("samples.csv");
            Files.writeString(output, HEADER, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            System.setProperty("starsector.prepatcher.marketConstructionDiagnosticsDir",
                    directory.toAbsolutePath().toString());
            PrepatcherLog.info("Market construction diagnostics session: "
                    + directory.toAbsolutePath());
        }

        void start() {
            Thread reporter = new Thread(this::reportLoop,
                    "StarsectorPrepatcher-ConstructionDiagnostics");
            reporter.setDaemon(true);
            reporter.setPriority(Thread.MIN_PRIORITY);
            reporter.start();
        }

        void observe(MarketAPI market, ConstructionProbe probe, String trigger,
                     long mutationEpoch, long auditedEpoch, long lastAuditBatch,
                     boolean oldActive, boolean newActive,
                     int oldReasonMask, int newReasonMask) {
            if (newReasonMask == 0 && oldReasonMask == 0 && oldActive == newActive) return;
            int identityHash = market == null ? 0 : System.identityHashCode(market);
            String marketId = safeMarketText(market, true);
            String marketName = safeMarketText(market, false);
            String reasonBucket = constructionSampleBucket(oldReasonMask, newReasonMask);
            String industryClass = probe == null ? "" : probe.industryClass;
            String industryId = probe == null ? "" : probe.industryId;
            String buildingClass = probe == null ? "" : probe.buildingIndustryClass;
            String buildingId = probe == null ? "" : probe.buildingIndustryId;
            String reportedWithoutRawClass = probe == null
                    ? "" : probe.reportedBuildingWithoutRawIndustryClass;
            String reportedWithoutRawId = probe == null
                    ? "" : probe.reportedBuildingWithoutRawIndustryId;
            String upgradingClass = probe == null ? "" : probe.upgradingIndustryClass;
            String upgradingId = probe == null ? "" : probe.upgradingIndustryId;
            String key = reasonBucket + '\u001f' + identityHash + '\u001f' + marketId
                    + '\u001f' + oldReasonMask + '\u001f' + newReasonMask
                    + '\u001f' + industryClass + '\u001f' + industryId
                    + '\u001f' + buildingClass + '\u001f' + buildingId
                    + '\u001f' + reportedWithoutRawClass
                    + '\u001f' + reportedWithoutRawId
                    + '\u001f' + upgradingClass + '\u001f' + upgradingId
                    + '\u001f' + trigger;
            ConstructionDiagnosticSample existing = samples.get(key);
            if (existing == null) {
                AtomicInteger count = samplesPerReason.computeIfAbsent(
                        reasonBucket, ignored -> new AtomicInteger());
                int slot = count.incrementAndGet();
                if (slot > maxSamplesPerReason) {
                    count.decrementAndGet();
                    CONSTRUCTION_DIAGNOSTIC_SAMPLES_DROPPED.increment();
                    return;
                }
                ConstructionDiagnosticSample candidate = new ConstructionDiagnosticSample(
                        identityHash, marketId, marketName, trigger, reasonBucket,
                        mutationEpoch, auditedEpoch, lastAuditBatch,
                        oldActive, newActive, oldReasonMask, newReasonMask, probe);
                ConstructionDiagnosticSample raced = samples.putIfAbsent(key, candidate);
                existing = raced == null ? candidate : raced;
                if (raced != null) count.decrementAndGet();
            }
            existing.observe();
        }

        private void reportLoop() {
            while (true) {
                try {
                    Thread.sleep(reportIntervalSeconds * 1000L);
                    writePending();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    try { writePending(); } catch (Throwable ignored) {}
                    return;
                } catch (Throwable failure) {
                    PrepatcherLog.error("Market construction diagnostics report failed", failure);
                }
            }
        }

        private void writePending() throws IOException {
            String timestamp = Instant.now().toString();
            StringBuilder rows = new StringBuilder();
            for (ConstructionDiagnosticSample sample : samples.values()) {
                long occurrences = sample.pendingOccurrences.sumThenReset();
                if (occurrences <= 0L) continue;
                sample.append(rows, timestamp, occurrences);
            }
            if (rows.length() == 0) return;
            synchronized (writeLock) {
                Files.writeString(output, rows, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        }

        private static String safeMarketText(MarketAPI market, boolean id) {
            if (market == null) return "<null>";
            try {
                String value = id ? market.getId() : market.getName();
                return value == null ? "" : value;
            } catch (Throwable failure) {
                return "<" + failure.getClass().getSimpleName() + ">";
            }
        }
    }

    private static final class ConstructionDiagnosticSample {
        final int marketIdentityHash;
        final String marketId;
        final String marketName;
        final String trigger;
        final String sampleBucket;
        final String transition;
        final long mutationEpoch;
        final long auditedEpoch;
        final long lastAuditBatch;
        final boolean oldActive;
        final boolean newActive;
        final int oldReasonMask;
        final int newReasonMask;
        final String queueClass;
        final int queueSize;
        final int industryCount;
        final int industryIndex;
        final String industryId;
        final String industryClass;
        final boolean building;
        final boolean effectiveBuilding;
        final boolean upgrading;
        final boolean reportedBuildingWithoutRaw;
        final int buildingIndustryIndex;
        final String buildingIndustryId;
        final String buildingIndustryClass;
        final String buildingRawBuildingField;
        final String buildingBuildProgress;
        final String buildingBuildTime;
        final String buildingUpgradeId;
        final String buildingIsFunctional;
        final int reportedBuildingWithoutRawIndustryIndex;
        final String reportedBuildingWithoutRawIndustryId;
        final String reportedBuildingWithoutRawIndustryClass;
        final String reportedBuildingWithoutRawRawBuildingField;
        final String reportedBuildingWithoutRawBuildProgress;
        final String reportedBuildingWithoutRawBuildTime;
        final String reportedBuildingWithoutRawUpgradeId;
        final String reportedBuildingWithoutRawIsFunctional;
        final int upgradingIndustryIndex;
        final String upgradingIndustryId;
        final String upgradingIndustryClass;
        final String upgradingRawBuildingField;
        final String upgradingBuildProgress;
        final String upgradingBuildTime;
        final String upgradingUpgradeId;
        final String upgradingIsFunctional;
        final String exceptionClass;
        final String exceptionMessage;
        final long firstSeenMillis = System.currentTimeMillis();
        final AtomicLong lastSeenMillis = new AtomicLong(firstSeenMillis);
        final LongAdder pendingOccurrences = new LongAdder();

        ConstructionDiagnosticSample(int marketIdentityHash,
                                     String marketId, String marketName, String trigger,
                                     String sampleBucket,
                                     long mutationEpoch, long auditedEpoch, long lastAuditBatch,
                                     boolean oldActive, boolean newActive,
                                     int oldReasonMask, int newReasonMask,
                                     ConstructionProbe probe) {
            this.marketIdentityHash = marketIdentityHash;
            this.marketId = marketId;
            this.marketName = marketName;
            this.trigger = trigger;
            this.sampleBucket = sampleBucket;
            this.transition = oldReasonMask == newReasonMask
                    ? "" : oldReasonMask + "->" + newReasonMask;
            this.mutationEpoch = mutationEpoch;
            this.auditedEpoch = auditedEpoch;
            this.lastAuditBatch = lastAuditBatch;
            this.oldActive = oldActive;
            this.newActive = newActive;
            this.oldReasonMask = oldReasonMask;
            this.newReasonMask = newReasonMask;
            queueClass = probe == null ? "" : probe.queueClass;
            queueSize = probe == null ? -2 : probe.queueSize;
            industryCount = probe == null ? -2 : probe.industryCount;
            industryIndex = probe == null ? -1 : probe.industryIndex;
            industryId = probe == null ? "" : probe.industryId;
            industryClass = probe == null ? "" : probe.industryClass;
            building = probe != null && probe.industryBuilding;
            effectiveBuilding = probe != null && probe.industryEffectiveBuilding;
            upgrading = probe != null && probe.industryUpgrading;
            reportedBuildingWithoutRaw = probe != null
                    && probe.reportedBuildingWithoutRawIndustryIndex >= 0;
            buildingIndustryIndex = probe == null ? -1 : probe.buildingIndustryIndex;
            buildingIndustryId = probe == null ? "" : probe.buildingIndustryId;
            buildingIndustryClass = probe == null ? "" : probe.buildingIndustryClass;
            buildingRawBuildingField = probe == null ? "" : probe.buildingRawBuildingField;
            buildingBuildProgress = probe == null ? "" : probe.buildingBuildProgress;
            buildingBuildTime = probe == null ? "" : probe.buildingBuildTime;
            buildingUpgradeId = probe == null ? "" : probe.buildingUpgradeId;
            buildingIsFunctional = probe == null ? "" : probe.buildingIsFunctional;
            reportedBuildingWithoutRawIndustryIndex = probe == null
                    ? -1 : probe.reportedBuildingWithoutRawIndustryIndex;
            reportedBuildingWithoutRawIndustryId = probe == null
                    ? "" : probe.reportedBuildingWithoutRawIndustryId;
            reportedBuildingWithoutRawIndustryClass = probe == null
                    ? "" : probe.reportedBuildingWithoutRawIndustryClass;
            reportedBuildingWithoutRawRawBuildingField = probe == null
                    ? "" : probe.reportedBuildingWithoutRawRawBuildingField;
            reportedBuildingWithoutRawBuildProgress = probe == null
                    ? "" : probe.reportedBuildingWithoutRawBuildProgress;
            reportedBuildingWithoutRawBuildTime = probe == null
                    ? "" : probe.reportedBuildingWithoutRawBuildTime;
            reportedBuildingWithoutRawUpgradeId = probe == null
                    ? "" : probe.reportedBuildingWithoutRawUpgradeId;
            reportedBuildingWithoutRawIsFunctional = probe == null
                    ? "" : probe.reportedBuildingWithoutRawIsFunctional;
            upgradingIndustryIndex = probe == null ? -1 : probe.upgradingIndustryIndex;
            upgradingIndustryId = probe == null ? "" : probe.upgradingIndustryId;
            upgradingIndustryClass = probe == null ? "" : probe.upgradingIndustryClass;
            upgradingRawBuildingField = probe == null ? "" : probe.upgradingRawBuildingField;
            upgradingBuildProgress = probe == null ? "" : probe.upgradingBuildProgress;
            upgradingBuildTime = probe == null ? "" : probe.upgradingBuildTime;
            upgradingUpgradeId = probe == null ? "" : probe.upgradingUpgradeId;
            upgradingIsFunctional = probe == null ? "" : probe.upgradingIsFunctional;
            exceptionClass = probe == null ? "" : probe.exceptionClass;
            exceptionMessage = probe == null ? "" : probe.exceptionMessage;
        }

        void observe() {
            lastSeenMillis.set(System.currentTimeMillis());
            pendingOccurrences.increment();
        }

        void append(StringBuilder output, String timestamp, long occurrences) {
            output.append(DirectMarketObserver.csv(timestamp)).append(',')
                    .append(DirectMarketObserver.csv(
                            Instant.ofEpochMilli(firstSeenMillis).toString())).append(',')
                    .append(DirectMarketObserver.csv(
                            Instant.ofEpochMilli(lastSeenMillis.get()).toString())).append(',')
                    .append(occurrences).append(',')
                    .append(marketIdentityHash).append(',')
                    .append(DirectMarketObserver.csv(marketId)).append(',')
                    .append(DirectMarketObserver.csv(marketName)).append(',')
                    .append(DirectMarketObserver.csv(trigger)).append(',')
                    .append(DirectMarketObserver.csv(sampleBucket)).append(',')
                    .append(DirectMarketObserver.csv(transition)).append(',')
                    .append(newReasonMask).append(',')
                    .append(DirectMarketObserver.csv(
                            constructionReasonText(newReasonMask))).append(',')
                    .append(DirectMarketObserver.csv(queueClass)).append(',')
                    .append(queueSize).append(',')
                    .append(industryCount).append(',')
                    .append(industryIndex).append(',')
                    .append(DirectMarketObserver.csv(industryId)).append(',')
                    .append(DirectMarketObserver.csv(industryClass)).append(',')
                    .append(building).append(',')
                    .append(effectiveBuilding).append(',')
                    .append(upgrading).append(',')
                    .append(reportedBuildingWithoutRaw).append(',')
                    .append(buildingIndustryIndex).append(',')
                    .append(DirectMarketObserver.csv(buildingIndustryId)).append(',')
                    .append(DirectMarketObserver.csv(buildingIndustryClass)).append(',')
                    .append(DirectMarketObserver.csv(buildingRawBuildingField)).append(',')
                    .append(DirectMarketObserver.csv(buildingBuildProgress)).append(',')
                    .append(DirectMarketObserver.csv(buildingBuildTime)).append(',')
                    .append(DirectMarketObserver.csv(buildingUpgradeId)).append(',')
                    .append(DirectMarketObserver.csv(buildingIsFunctional)).append(',')
                    .append(reportedBuildingWithoutRawIndustryIndex).append(',')
                    .append(DirectMarketObserver.csv(
                            reportedBuildingWithoutRawIndustryId)).append(',')
                    .append(DirectMarketObserver.csv(
                            reportedBuildingWithoutRawIndustryClass)).append(',')
                    .append(DirectMarketObserver.csv(
                            reportedBuildingWithoutRawRawBuildingField)).append(',')
                    .append(DirectMarketObserver.csv(
                            reportedBuildingWithoutRawBuildProgress)).append(',')
                    .append(DirectMarketObserver.csv(
                            reportedBuildingWithoutRawBuildTime)).append(',')
                    .append(DirectMarketObserver.csv(
                            reportedBuildingWithoutRawUpgradeId)).append(',')
                    .append(DirectMarketObserver.csv(
                            reportedBuildingWithoutRawIsFunctional)).append(',')
                    .append(upgradingIndustryIndex).append(',')
                    .append(DirectMarketObserver.csv(upgradingIndustryId)).append(',')
                    .append(DirectMarketObserver.csv(upgradingIndustryClass)).append(',')
                    .append(DirectMarketObserver.csv(upgradingRawBuildingField)).append(',')
                    .append(DirectMarketObserver.csv(upgradingBuildProgress)).append(',')
                    .append(DirectMarketObserver.csv(upgradingBuildTime)).append(',')
                    .append(DirectMarketObserver.csv(upgradingUpgradeId)).append(',')
                    .append(DirectMarketObserver.csv(upgradingIsFunctional)).append(',')
                    .append(mutationEpoch).append(',')
                    .append(auditedEpoch).append(',')
                    .append(lastAuditBatch).append(',')
                    .append(oldActive).append(',')
                    .append(newActive).append(',')
                    .append(oldReasonMask).append(',')
                    .append(newReasonMask).append(',')
                    .append(DirectMarketObserver.csv(exceptionClass)).append(',')
                    .append(DirectMarketObserver.csv(exceptionMessage)).append('\n');
        }
    }

    private static String constructionSampleBucket(
            int oldReasonMask, int newReasonMask) {
        if (oldReasonMask != 0 && oldReasonMask != newReasonMask) {
            // Keep the number of quota categories fixed. The exact masks remain
            // available in the transition/old_reason_mask/new_reason_mask columns.
            if (oldReasonMask == CONSTRUCTION_REASON_INDUSTRY_UPGRADING
                    && newReasonMask == (CONSTRUCTION_REASON_INDUSTRY_BUILDING
                    | CONSTRUCTION_REASON_INDUSTRY_UPGRADING)) {
                return "TRANSITION_4_TO_6";
            }
            if (oldReasonMask == CONSTRUCTION_REASON_INDUSTRY_UPGRADING
                    && newReasonMask == (CONSTRUCTION_REASON_INDUSTRY_UPGRADING
                    | CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW)) {
                return "TRANSITION_4_TO_132";
            }
            return "TRANSITION_OTHER";
        }
        return constructionReasonBucket(newReasonMask);
    }

    private static String constructionReasonBucket(int reasonMask) {
        int semantic = reasonMask & CONSTRUCTION_REASON_OBSERVED_MASK;
        if (semantic == CONSTRUCTION_REASON_INDUSTRY_UPGRADING) {
            return "INDUSTRY_UPGRADING_WITHOUT_BUILDING";
        }
        if (semantic == CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW
                || semantic == (CONSTRUCTION_REASON_INDUSTRY_UPGRADING
                | CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW)) {
            return "REPORTED_BUILDING_WITHOUT_RAW";
        }
        if (Integer.bitCount(semantic) > 1) return "MULTIPLE";
        if ((semantic & CONSTRUCTION_REASON_QUEUE_NON_EMPTY) != 0) return "QUEUE_NON_EMPTY";
        if ((semantic & CONSTRUCTION_REASON_INDUSTRY_BUILDING) != 0) return "INDUSTRY_BUILDING";
        if ((semantic & CONSTRUCTION_REASON_INDUSTRY_UPGRADING) != 0) return "INDUSTRY_UPGRADING";
        if ((semantic & CONSTRUCTION_REASON_QUEUE_ITEMS_UNKNOWN) != 0) return "QUEUE_ITEMS_UNKNOWN";
        if ((semantic & CONSTRUCTION_REASON_INDUSTRIES_UNKNOWN) != 0) return "INDUSTRIES_UNKNOWN";
        if ((semantic & CONSTRUCTION_REASON_PROBE_FAILURE) != 0) return "PROBE_FAILURE";
        if ((semantic & CONSTRUCTION_REASON_MUTATION_RACE) != 0) return "MUTATION_RACE";
        if ((semantic & CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW) != 0) {
            return "REPORTED_BUILDING_WITHOUT_RAW";
        }
        return "INACTIVE";
    }

    private static String constructionReasonText(int reasonMask) {
        if (reasonMask == 0) return "NONE";
        StringBuilder result = new StringBuilder();
        appendReason(result, reasonMask, CONSTRUCTION_REASON_QUEUE_NON_EMPTY, "QUEUE_NON_EMPTY");
        appendReason(result, reasonMask, CONSTRUCTION_REASON_INDUSTRY_BUILDING, "INDUSTRY_BUILDING");
        appendReason(result, reasonMask, CONSTRUCTION_REASON_INDUSTRY_UPGRADING, "INDUSTRY_UPGRADING");
        appendReason(result, reasonMask, CONSTRUCTION_REASON_QUEUE_ITEMS_UNKNOWN, "QUEUE_ITEMS_UNKNOWN");
        appendReason(result, reasonMask, CONSTRUCTION_REASON_INDUSTRIES_UNKNOWN, "INDUSTRIES_UNKNOWN");
        appendReason(result, reasonMask, CONSTRUCTION_REASON_PROBE_FAILURE, "PROBE_FAILURE");
        appendReason(result, reasonMask, CONSTRUCTION_REASON_MUTATION_RACE, "MUTATION_RACE");
        appendReason(result, reasonMask,
                CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW,
                "REPORTED_BUILDING_WITHOUT_RAW");
        return result.toString();
    }

    private static void appendReason(
            StringBuilder output, int mask, int bit, String label) {
        if ((mask & bit) == 0) return;
        if (output.length() > 0) output.append('|');
        output.append(label);
    }

    private static final class PendingStepRun {
        final float amount;
        int count;

        PendingStepRun(float amount) {
            this.amount = amount;
            this.count = 1;
        }
    }

    private static final class MarketAdvanceInvocationContext {
        int depth;
    }

    private static final class MarketAdvanceBatchFrame {
        MarketAPI market;
        float[] runAmounts = new float[4];
        int[] runRepeats = new int[4];
        int runCount;
        int totalSteps;
        float totalAmount;
        boolean visible;
        int boundInvocationDepth;

        void load(MarketAPI market, MarketScheduleState state, boolean visible) {
            int runs = state.pendingRuns == null ? 0 : state.pendingRuns.size();
            ensureCapacity(runs);
            int index = 0;
            int steps = 0;
            if (state.pendingRuns != null) {
                for (PendingStepRun run : state.pendingRuns) {
                    if (run == null || run.count <= 0) {
                        throw new IllegalStateException("Corrupt pending Market.advance run");
                    }
                    runAmounts[index] = run.amount;
                    runRepeats[index] = run.count;
                    steps += run.count;
                    index++;
                }
            }
            if (steps != state.pendingSteps || index != runs) {
                throw new IllegalStateException("Pending Market.advance history invariant failed");
            }
            this.market = market;
            this.runCount = runs;
            this.totalSteps = state.pendingSteps;
            this.totalAmount = state.pendingAmount;
            this.visible = visible;
            this.boundInvocationDepth = 0;
        }

        void ensureCapacity(int wanted) {
            if (wanted <= runAmounts.length) return;
            int capacity = runAmounts.length;
            while (capacity < wanted) capacity = Math.max(capacity << 1, 4);
            runAmounts = Arrays.copyOf(runAmounts, capacity);
            runRepeats = Arrays.copyOf(runRepeats, capacity);
        }

        void clear() {
            market = null;
            Arrays.fill(runAmounts, 0, runCount, 0f);
            Arrays.fill(runRepeats, 0, runCount, 0);
            runCount = 0;
            totalSteps = 0;
            totalAmount = 0f;
            visible = false;
            boundInvocationDepth = 0;
        }
    }

    private static final class MarketAdvanceBatchStack {
        final ArrayList<MarketAdvanceBatchFrame> frames = new ArrayList<>();
        int depth;

        MarketAdvanceBatchFrame push(
                MarketAPI market, MarketScheduleState state, boolean visible) {
            MarketAdvanceBatchFrame frame;
            if (depth < frames.size()) frame = frames.get(depth);
            else {
                frame = new MarketAdvanceBatchFrame();
                frames.add(frame);
            }
            depth++;
            try {
                frame.load(market, state, visible);
                return frame;
            } catch (Throwable failure) {
                depth--;
                frame.clear();
                throw failure;
            }
        }

        MarketAdvanceBatchFrame current() {
            return depth <= 0 ? null : frames.get(depth - 1);
        }

        void pop(MarketAdvanceBatchFrame expected) {
            MarketAdvanceBatchFrame current = current();
            if (current != expected) {
                depth = 0;
                for (MarketAdvanceBatchFrame frame : frames) frame.clear();
                throw new IllegalStateException("Market.advance batch stack order changed");
            }
            current.clear();
            depth--;
            if (depth == 0 && frames.size() > 8) {
                while (frames.size() > 8) frames.remove(frames.size() - 1);
            }
        }
    }

    private static final class WeakIdentityEntry<K, V> {
        final K key;
        final V value;

        WeakIdentityEntry(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    /** Weak-key map with identity, rather than equals(), key semantics. */
    private static final class WeakIdentityMap<K, V> {
        private final ReferenceQueue<K> queue = new ReferenceQueue<>();
        private final HashMap<IdentityWeakReference<K>, V> values = new HashMap<>();

        synchronized V get(K key) {
            if (key == null) return null;
            expungeStaleEntries();
            return values.get(new IdentityWeakReference<>(key));
        }

        synchronized V putIfAbsent(K key, V value) {
            if (key == null) throw new NullPointerException("key");
            expungeStaleEntries();
            IdentityWeakReference<K> lookup = new IdentityWeakReference<>(key);
            V existing = values.get(lookup);
            if (existing != null) return existing;
            values.put(new IdentityWeakReference<>(key, queue), value);
            return null;
        }

        synchronized void put(K key, V value) {
            if (key == null) throw new NullPointerException("key");
            expungeStaleEntries();
            values.remove(new IdentityWeakReference<>(key));
            values.put(new IdentityWeakReference<>(key, queue), value);
        }

        synchronized List<WeakIdentityEntry<K, V>> entriesSnapshot() {
            expungeStaleEntries();
            ArrayList<WeakIdentityEntry<K, V>> result = new ArrayList<>(values.size());
            for (Map.Entry<IdentityWeakReference<K>, V> entry : values.entrySet()) {
                K key = entry.getKey().get();
                if (key != null) result.add(new WeakIdentityEntry<>(key, entry.getValue()));
            }
            return result;
        }

        synchronized int size() {
            expungeStaleEntries();
            return values.size();
        }

        @SuppressWarnings("unchecked")
        private void expungeStaleEntries() {
            IdentityWeakReference<K> stale;
            while ((stale = (IdentityWeakReference<K>) queue.poll()) != null) {
                values.remove(stale);
            }
        }
    }

    private static final class IdentityWeakReference<T> extends WeakReference<T> {
        private final int identityHash;

        IdentityWeakReference(T referent) {
            super(referent);
            identityHash = System.identityHashCode(referent);
        }

        IdentityWeakReference(T referent, ReferenceQueue<T> queue) {
            super(referent, queue);
            identityHash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityWeakReference<?> reference)) return false;
            Object left = get();
            return left != null && left == reference.get();
        }
    }

    private static final class CommRelaySystemEntry {
        final int ordinal;
        final StarSystemAPI system;

        CommRelaySystemEntry(int ordinal, StarSystemAPI system) {
            this.ordinal = ordinal;
            this.system = system;
        }
    }

    /** Spatial index whose output is sorted back into CampaignEngine.getStarSystems()
     * order before the original IntelManager loop observes it. */
    private static final class CommRelaySystemIndex {
        final int size;
        final Object first;
        final Object last;
        final int radiusBits;
        final float cellSize;
        long validatedAtNanos;
        final Object[] entries;
        final int[] xBits;
        final int[] yBits;
        final HashMap<Long, ArrayList<CommRelaySystemEntry>> cells;
        final boolean failed;

        CommRelaySystemIndex(int size, Object first, Object last, int radiusBits,
                             float cellSize, long validatedAtNanos, Object[] entries,
                             int[] xBits, int[] yBits,
                             HashMap<Long, ArrayList<CommRelaySystemEntry>> cells,
                             boolean failed) {
            this.size = size;
            this.first = first;
            this.last = last;
            this.radiusBits = radiusBits;
            this.cellSize = cellSize;
            this.validatedAtNanos = validatedAtNanos;
            this.entries = entries;
            this.xBits = xBits;
            this.yBits = yBits;
            this.cells = cells;
            this.failed = failed;
        }

        boolean matches(List<?> live, long now, int ttlMs, float radius) {
            int currentSize = live.size();
            if (size != currentSize || radiusBits != Float.floatToIntBits(radius)) return false;
            long ttlNs = ttlMs * 1_000_000L;
            if (ttlNs <= 0L) return false;
            // A failed build is a safe vanilla fallback. Remember it only for a
            // bounded interval so a transient mod/list failure does not allocate
            // and rebuild on every IntelManager call.
            if (failed) {
                long elapsed = now - validatedAtNanos;
                return elapsed >= 0L && elapsed < ttlNs;
            }
            Object currentFirst = currentSize == 0 ? null : live.get(0);
            Object currentLast = currentSize == 0 ? null : live.get(currentSize - 1);
            if (first != currentFirst || last != currentLast) return false;
            long elapsed = now - validatedAtNanos;
            if (elapsed >= 0L && elapsed < ttlNs) return true;

            // System hyperspace coordinates are mutable, but rebuilding/validating
            // the full 2k+ system relation on every IntelManager frame defeats the
            // spatial index itself. Perform the exact identity/coordinate audit at
            // the configured interval; ordinary frames remain O(1) before the
            // spatial candidate query. A rare mod teleport can therefore remain
            // stale for at most commRelay.indexTtlMs, while all live relay/tag/
            // memory checks are still executed by the original method every frame.
            try {
                COMM_RELAY_VALIDATION_SCANS.increment();
                COMM_RELAY_SYSTEMS_VALIDATED.add(currentSize);
                for (int i = 0; i < currentSize; i++) {
                    Object raw = live.get(i);
                    if (raw != entries[i] || !(raw instanceof StarSystemAPI system)) return false;
                    Vector2f location = system.getLocation();
                    if (location == null || Float.floatToIntBits(location.x) != xBits[i]
                            || Float.floatToIntBits(location.y) != yBits[i]) return false;
                }
            } catch (Throwable ignored) {
                return false;
            }
            validatedAtNanos = now;
            return true;
        }

        @SuppressWarnings("rawtypes")
        static CommRelaySystemIndex build(List systems, long now, float radius) {
            int size = systems.size();
            Object first = size == 0 ? null : systems.get(0);
            Object last = size == 0 ? null : systems.get(size - 1);
            Object[] entries = new Object[size];
            int[] xBits = new int[size];
            int[] yBits = new int[size];
            HashMap<Long, ArrayList<CommRelaySystemEntry>> cells =
                    new HashMap<>(Math.max(32, size * 2));
            float cellSize = Math.max(radius, 1f);
            try {
                for (int i = 0; i < size; i++) {
                    Object raw = systems.get(i);
                    entries[i] = raw;
                    if (!(raw instanceof StarSystemAPI system)) {
                        throw new IllegalStateException("Non-system entry in CampaignEngine star-system list");
                    }
                    Vector2f location = system.getLocation();
                    if (location == null || !Float.isFinite(location.x) || !Float.isFinite(location.y)) {
                        throw new IllegalStateException("Invalid star-system hyperspace location");
                    }
                    xBits[i] = Float.floatToIntBits(location.x);
                    yBits[i] = Float.floatToIntBits(location.y);
                    int cellX = safeFloorToInt(location.x / cellSize);
                    int cellY = safeFloorToInt(location.y / cellSize);
                    cells.computeIfAbsent(cellKey(cellX, cellY), ignored -> new ArrayList<>(4))
                            .add(new CommRelaySystemEntry(i, system));
                }
                return new CommRelaySystemIndex(size, first, last,
                        Float.floatToIntBits(radius), cellSize, now,
                        entries, xBits, yBits, cells, false);
            } catch (Throwable ignored) {
                // Failed entries intentionally contain no campaign references;
                // the weak engine key must not be made strong through the value.
                return new CommRelaySystemIndex(size, null, null,
                        Float.floatToIntBits(radius), cellSize, now,
                        new Object[0], new int[0], new int[0], new HashMap<>(0), true);
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        void collect(float x, float y, float radius, List output) {
            int minX = safeFloorToInt((x - radius) / cellSize);
            int maxX = safeFloorToInt((x + radius) / cellSize);
            int minY = safeFloorToInt((y - radius) / cellSize);
            int maxY = safeFloorToInt((y + radius) / cellSize);
            for (int cellX = minX; cellX <= maxX; cellX++) {
                for (int cellY = minY; cellY <= maxY; cellY++) {
                    List<CommRelaySystemEntry> bucket = cells.get(cellKey(cellX, cellY));
                    if (bucket != null) output.addAll(bucket);
                    if (cellY == Integer.MAX_VALUE) break;
                }
                if (cellX == Integer.MAX_VALUE) break;
            }
        }
    }

    private static final class CampaignListenerState {
        Object systems;
        Object hyperspace;
        Object firstSystem;
        Object lastSystem;
        int systemCount;
        long refreshedAtNanos;
    }

    // ---------------------------------------------------------------------
    // Strategic fleet jump-point index.
    //
    // StrategicModule keeps its original destination, safety, distance and
    // tie-breaking code. The hook changes only the ordered source list. A
    // location-agnostic state machine builds that exact source incrementally
    // under one wall-clock token bucket shared by every location. Until READY,
    // an immutable empty list defers this route rebuild instead of re-entering
    // the original full scan.
    // ---------------------------------------------------------------------

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List strategicJumpPointsForDestination(LocationAPI current,
                                                          Class jumpPointType,
                                                          LocationAPI requested) {
        PrepatcherConfig c = config;
        if (current == null) return null;
        if (c == null || !c.strategicJumpDestinationIndex) {
            return strategicJumpVanillaSource(current, jumpPointType);
        }

        long generation = campaignCacheGeneration;
        if (!campaignCachesReady(generation)) return Collections.emptyList();

        int capability = strategicJumpIndexCapability();
        if (capability == STRATEGIC_JUMP_CAPABILITY_PENDING) {
            return Collections.emptyList();
        }
        if (capability != STRATEGIC_JUMP_CAPABILITY_READY) {
            return strategicJumpVanillaSource(current, jumpPointType);
        }

        LocationAPI hyperspace;
        try {
            CampaignEngine engine = CampaignEngine.getInstance();
            hyperspace = engine == null ? null : engine.getHyperspace();
        } catch (Throwable ignored) {
            hyperspace = null;
        }
        if (hyperspace == null) return Collections.emptyList();

        try {
            return StarsectorPrepatcherStrategicJumpIndex.lookup(
                    current, jumpPointType, requested, hyperspace, c, generation);
        } catch (Throwable failure) {
            // A runtime implementation failure must not make campaign routes
            // disappear permanently. Disable only this optimization and retain
            // the vanilla compatibility path.
            strategicJumpRuntimeCapability = STRATEGIC_JUMP_CAPABILITY_FAILED;
            return strategicJumpVanillaSource(current, jumpPointType);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static List strategicJumpPointsForDestination(LocationAPI current,
                                                   Class jumpPointType,
                                                   LocationAPI requested,
                                                   LocationAPI hyperspace) {
        PrepatcherConfig c = config;
        if (current == null) return null;
        if (c == null || !c.strategicJumpDestinationIndex) {
            return strategicJumpVanillaSource(current, jumpPointType);
        }
        long generation = campaignCacheGeneration;
        if (!campaignCachesReady(generation)) return Collections.emptyList();
        int capability = strategicJumpIndexCapability();
        if (capability == STRATEGIC_JUMP_CAPABILITY_PENDING) {
            return Collections.emptyList();
        }
        if (capability != STRATEGIC_JUMP_CAPABILITY_READY) {
            return strategicJumpVanillaSource(current, jumpPointType);
        }
        if (hyperspace == null) return Collections.emptyList();
        try {
            return StarsectorPrepatcherStrategicJumpIndex.lookup(
                    current, jumpPointType, requested, hyperspace, c, generation);
        } catch (Throwable failure) {
            strategicJumpRuntimeCapability = STRATEGIC_JUMP_CAPABILITY_FAILED;
            return strategicJumpVanillaSource(current, jumpPointType);
        }
    }

    /**
     * Called only from the expired-plan branch in StrategicModule. The prior
     * valid plan remains in place until the exact incremental index is READY.
     */
    public static boolean strategicJumpDeferExpiredPlan(LocationAPI current,
                                                         LocationAPI requested) {
        PrepatcherConfig c = config;
        if (current == null || c == null || !c.strategicJumpDestinationIndex) {
            return false;
        }
        long generation = campaignCacheGeneration;
        if (!campaignCachesReady(generation)) return true;
        int capability = strategicJumpIndexCapability();
        if (capability == STRATEGIC_JUMP_CAPABILITY_PENDING) return true;
        if (capability != STRATEGIC_JUMP_CAPABILITY_READY) return false;
        try {
            return StarsectorPrepatcherStrategicJumpIndex.deferExpiredPlan(
                    current, JumpPoint.class, c, generation);
        } catch (Throwable failure) {
            strategicJumpRuntimeCapability = STRATEGIC_JUMP_CAPABILITY_FAILED;
            return false;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List strategicJumpVanillaSource(LocationAPI current, Class jumpPointType) {
        try {
            return current == null ? null : current.getEntities(jumpPointType);
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    public static void strategicJumpDestinationsChanged(JumpPointAPI jumpPoint) {
        try {
            PrepatcherConfig c = config;
            if (c != null && c.strategicJumpDestinationIndex) {
                StarsectorPrepatcherStrategicJumpIndex.destinationsChanged(jumpPoint);
            }
        } catch (Throwable ignored) {
            // Mutation semantics never depend on this optional index.
        }
    }

    public static void strategicJumpDestinationAdded(
            JumpPointAPI jumpPoint, JumpPointAPI.JumpDestination destination) {
        try {
            PrepatcherConfig c = config;
            if (c != null && c.strategicJumpDestinationIndex) {
                StarsectorPrepatcherStrategicJumpIndex.destinationAdded(
                        jumpPoint, destination);
            }
        } catch (Throwable ignored) {
            // Mutation semantics never depend on this optional index.
        }
    }

    public static void strategicJumpDestinationRetargeted(
            JumpPointAPI.JumpDestination destination) {
        try {
            PrepatcherConfig c = config;
            if (c != null && c.strategicJumpDestinationIndex) {
                StarsectorPrepatcherStrategicJumpIndex.destinationRetargeted(destination);
            }
        } catch (Throwable ignored) {
            // Mutation semantics never depend on this optional index.
        }
    }

    public static void strategicJumpLocationEntityChanged(LocationAPI location,
                                                           Object entity) {
        try {
            PrepatcherConfig c = config;
            if (c != null && c.strategicJumpDestinationIndex) {
                StarsectorPrepatcherStrategicJumpIndex.locationEntityChanged(
                        location, entity);
            }
        } catch (Throwable ignored) {
            // Location mutation semantics never depend on this optional index.
        }
    }

    private static int strategicJumpIndexCapability() {
        int cached = strategicJumpRuntimeCapability;
        if (cached == STRATEGIC_JUMP_CAPABILITY_READY
                || cached == STRATEGIC_JUMP_CAPABILITY_FAILED) return cached;

        String maintenance = System.getProperty(STRATEGIC_JUMP_MAINTENANCE_STATUS);
        String first = System.getProperty(STRATEGIC_JUMP_FIRST_STATUS);
        String source = System.getProperty(STRATEGIC_JUMP_SOURCE_STATUS);
        String point = System.getProperty(STRATEGIC_JUMP_POINT_STATUS);
        String location = System.getProperty(STRATEGIC_JUMP_LOCATION_STATUS);
        String destination = System.getProperty(STRATEGIC_JUMP_DESTINATION_STATUS);
        if (patchApplied(maintenance) && patchApplied(first) && patchApplied(source)
                && patchApplied(point)
                && patchApplied(location) && patchApplied(destination)) {
            strategicJumpRuntimeCapability = STRATEGIC_JUMP_CAPABILITY_READY;
            return STRATEGIC_JUMP_CAPABILITY_READY;
        }
        if (patchFailed(maintenance) || patchFailed(first) || patchFailed(source)
                || patchFailed(point)
                || patchFailed(location) || patchFailed(destination)) {
            strategicJumpRuntimeCapability = STRATEGIC_JUMP_CAPABILITY_FAILED;
            return STRATEGIC_JUMP_CAPABILITY_FAILED;
        }
        return STRATEGIC_JUMP_CAPABILITY_PENDING;
    }

    private static boolean patchApplied(String status) {
        return "APPLIED".equals(status) || "ALREADY_APPLIED".equals(status);
    }

    private static boolean patchFailed(String status) {
        return status != null && !patchApplied(status);
    }

    // ---------------------------------------------------------------------
    // Route/pathfinding indexes.
    //
    // O0Oo.getNextStep() and getLastLegDistance() preserve all of their original
    // filtering, distance and tie-breaking code. These hooks only replace the
    // candidate source: an O(all hyperspace jump points) list becomes the same
    // ordered subset whose first destination belongs to the requested system.
    // Cache misses and malformed/custom data fall back to the vanilla full list.
    // ---------------------------------------------------------------------

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List routeJumpPointsForSystem(LocationAPI hyperspace, Object system) {
        if (hyperspace == null) return null;
        List live = hyperspace.getJumpPoints();
        PrepatcherConfig c = config;
        long generation = campaignCacheGeneration;
        if (c == null || !c.routeJumpPointIndex || !campaignCachesReady(generation)
                || c.routeIndexTtlMs <= 0
                || system == null || live == null || !(live instanceof RandomAccess)
                || live.size() < 32) {
            ROUTE_JUMP_INDEX_FALLBACKS.increment();
            return live;
        }

        try {
            long now = System.nanoTime();
            RouteJumpIndex index;
            Map<LocationAPI, RouteJumpIndex> routeJumpIndexes = ROUTE_JUMP_INDEXES;
            synchronized (routeJumpIndexes) {
                if (!campaignCachesReady(generation)) {
                    index = null;
                } else {
                    index = routeJumpIndexes.get(hyperspace);
                    if (!campaignCachesReady(generation)) {
                        index = null;
                    } else if (index == null || !index.matches(live, now, c.routeIndexTtlMs)) {
                        index = RouteJumpIndex.build(live, now);
                        if (!campaignCachesReady(generation)) {
                            index = null;
                        } else {
                            routeJumpIndexes.put(hyperspace, index);
                            if (!campaignCachesReady(generation)) {
                                routeJumpIndexes.clear();
                                index = null;
                            } else {
                                ROUTE_JUMP_INDEX_BUILDS.increment();
                            }
                        }
                    }
                    if (!campaignCachesReady(generation)) {
                        routeJumpIndexes.clear();
                        index = null;
                    }
                }
            }
            if (index == null) {
                ROUTE_JUMP_INDEX_FALLBACKS.increment();
                return live;
            }
            if (index.failed) {
                ROUTE_JUMP_INDEX_FALLBACKS.increment();
                return live;
            }
            List candidates = index.bySystem.get(system);
            if (candidates == null) {
                // A full-list fallback on a miss prevents stale indexes from ever
                // hiding a newly retargeted modded jump point.
                ROUTE_JUMP_INDEX_FALLBACKS.increment();
                return live;
            }
            ROUTE_JUMP_INDEX_HITS.increment();
            return candidates;
        } catch (Throwable ex) {
            ROUTE_JUMP_INDEX_FALLBACKS.increment();
            return live;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List routeSystemsForAnchor(Object engine, Object anchor) {
        if (engine == null || anchor == null) return vanillaStarSystems(engine);
        PrepatcherConfig c = config;
        long generation = campaignCacheGeneration;
        if (c == null || !c.routeJumpPointIndex
                || !campaignCachesReady(generation, engine)
                || c.routeIndexTtlMs <= 0) {
            return vanillaStarSystems(engine);
        }

        try {
            CampaignRouteAccess access = CampaignRouteAccess.ACCESS.get(engine.getClass());
            List systems = access.backingSystems(engine);
            if (systems instanceof RandomAccess && systems.size() >= 32) {
                long now = System.nanoTime();
                RouteSystemIndex index;
                Map<Object, RouteSystemIndex> routeSystemIndexes = ROUTE_SYSTEM_INDEXES;
                synchronized (routeSystemIndexes) {
                    if (!campaignCachesReady(generation, engine)) {
                        index = null;
                    } else {
                        index = routeSystemIndexes.get(engine);
                        if (!campaignCachesReady(generation, engine)) {
                            index = null;
                        } else if (index == null || !index.matches(systems, now, c.routeIndexTtlMs)) {
                            index = RouteSystemIndex.build(systems, now);
                            if (!campaignCachesReady(generation, engine)) {
                                index = null;
                            } else {
                                routeSystemIndexes.put(engine, index);
                                if (!campaignCachesReady(generation, engine)) {
                                    routeSystemIndexes.clear();
                                    index = null;
                                } else {
                                    ROUTE_SYSTEM_INDEX_BUILDS.increment();
                                }
                            }
                        }
                    }
                    if (!campaignCachesReady(generation, engine)) {
                        routeSystemIndexes.clear();
                        index = null;
                    }
                }
                if (index != null && !index.failed) {
                    List candidates = index.byAnchor.get(anchor);
                    if (candidates != null) {
                        ROUTE_SYSTEM_INDEX_HITS.increment();
                        return candidates;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Fall through. The public getter below is deliberately independent
            // from backing-field discovery, so compatibility fallback stays live.
        }
        ROUTE_SYSTEM_INDEX_FALLBACKS.increment();
        return vanillaStarSystems(engine);
    }

    @SuppressWarnings("rawtypes")
    private static List vanillaStarSystems(Object engine) {
        if (engine == null) return Collections.emptyList();
        try {
            return (List) CampaignRouteGetter.GETTER.get(engine.getClass()).invoke(engine);
        } catch (InvocationTargetException ex) {
            throwUnchecked(ex.getCause());
            return Collections.emptyList(); // unreachable
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException("Unable to invoke CampaignEngine.getStarSystems()", ex);
        }
    }

    private static final class CampaignRouteGetter {
        static final ClassValue<Method> GETTER = new ClassValue<>() {
            @Override
            protected Method computeValue(Class<?> type) {
                try {
                    Method getter = type.getMethod("getStarSystems");
                    getter.setAccessible(true);
                    return getter;
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException("Unexpected CampaignEngine route layout: "
                            + type.getName(), ex);
                }
            }
        };
    }

    private static final class CampaignRouteAccess {
        static final ClassValue<CampaignRouteAccess> ACCESS = new ClassValue<>() {
            @Override
            protected CampaignRouteAccess computeValue(Class<?> type) {
                Method getter = CampaignRouteGetter.GETTER.get(type);
                ArrayList<Field> candidates = new ArrayList<>();
                Class<?> current = type;
                while (current != null) {
                    for (Field field : current.getDeclaredFields()) {
                        if (List.class.isAssignableFrom(field.getType())) {
                            field.setAccessible(true);
                            candidates.add(field);
                        }
                    }
                    current = current.getSuperclass();
                }
                return new CampaignRouteAccess(candidates, getter);
            }
        };

        final List<Field> listFields;
        final Method getStarSystems;
        volatile Field resolvedStarSystems;

        CampaignRouteAccess(List<Field> listFields, Method getStarSystems) {
            this.listFields = listFields;
            this.getStarSystems = getStarSystems;
        }

        @SuppressWarnings("rawtypes")
        List backingSystems(Object engine) throws ReflectiveOperationException {
            Field resolved = resolvedStarSystems;
            if (resolved != null) return (List) resolved.get(engine);
            synchronized (this) {
                resolved = resolvedStarSystems;
                if (resolved != null) return (List) resolved.get(engine);
                List visible = (List) getStarSystems.invoke(engine);
                Field match = null;
                for (Field candidate : listFields) {
                    Object value = candidate.get(engine);
                    if (!(value instanceof List list) || !sameIdentityContents(list, visible)) continue;
                    if (match != null) {
                        throw new IllegalStateException("Ambiguous CampaignEngine star-system List fields");
                    }
                    match = candidate;
                }
                if (match == null) {
                    throw new IllegalStateException("CampaignEngine backing star-system List was not found");
                }
                resolvedStarSystems = match;
                return (List) match.get(engine);
            }
        }

        @SuppressWarnings("rawtypes")
        private static boolean sameIdentityContents(List candidate, List visible) {
            if (candidate == null || visible == null || candidate.size() != visible.size()) return false;
            for (int i = 0; i < candidate.size(); i++) {
                if (candidate.get(i) != visible.get(i)) return false;
            }
            return true;
        }
    }

    private static final class RouteJumpIndex {
        final int size;
        final Object first;
        final Object last;
        long validatedAtNanos;
        final IdentityHashMap<Object, List<SectorEntityToken>> bySystem;
        final Object[] entries;
        final Object[] destinationSystems;
        final boolean failed;

        RouteJumpIndex(int size, Object first, Object last, long builtAtNanos,
                       IdentityHashMap<Object, List<SectorEntityToken>> bySystem,
                       Object[] entries, Object[] destinationSystems, boolean failed) {
            this.size = size;
            this.first = first;
            this.last = last;
            this.validatedAtNanos = builtAtNanos;
            this.bySystem = bySystem;
            this.entries = entries;
            this.destinationSystems = destinationSystems;
            this.failed = failed;
        }

        boolean matches(List<?> live, long now, int ttlMs) {
            int currentSize = live.size();
            Object currentFirst = currentSize > 0 ? live.get(0) : null;
            Object currentLast = currentSize > 0 ? live.get(currentSize - 1) : null;
            long ttlNs = ttlMs * 1_000_000L;
            if (size != currentSize || first != currentFirst || last != currentLast) return false;
            if (ttlNs <= 0L) return false;
            if (now - validatedAtNanos < ttlNs) return true;
            if (failed) return false;

            // Size/edge checks do not catch middle replacement or a mod retargeting
            // an existing jump point. Periodically validate the full relation while
            // keeping ordinary cache hits O(1); a mismatch rebuilds the index.
            try {
                for (int i = 0; i < currentSize; i++) {
                    Object raw = live.get(i);
                    if (raw != entries[i]
                            || destinationSystem(raw) != destinationSystems[i]) return false;
                }
            } catch (Throwable ignored) {
                return false;
            }
            validatedAtNanos = now;
            return true;
        }

        @SuppressWarnings("rawtypes")
        static RouteJumpIndex build(List live, long now) {
            int size = live.size();
            Object first = size > 0 ? live.get(0) : null;
            Object last = size > 0 ? live.get(size - 1) : null;
            IdentityHashMap<Object, List<SectorEntityToken>> bySystem = new IdentityHashMap<>();
            Object[] entries = new Object[size];
            Object[] destinationSystems = new Object[size];
            try {
                for (int i = 0; i < size; i++) {
                    Object raw = live.get(i);
                    entries[i] = raw;
                    Object location = destinationSystem(raw);
                    destinationSystems[i] = location;
                    if (location == null) continue;
                    bySystem.computeIfAbsent(location, ignored -> new ArrayList<>(2))
                            .add((SectorEntityToken) raw);
                }
                return new RouteJumpIndex(size, first, last, now, bySystem,
                        entries, destinationSystems, false);
            } catch (Throwable ex) {
                return new RouteJumpIndex(size, first, last, now, bySystem,
                        entries, destinationSystems, true);
            }
        }

        private static Object destinationSystem(Object raw) {
            if (!(raw instanceof JumpPointAPI jumpPoint)) {
                throw new IllegalStateException("Non-jump-point entry in hyperspace jump list");
            }
            List<JumpPointAPI.JumpDestination> destinations = jumpPoint.getDestinations();
            if (destinations == null || destinations.isEmpty()) return null;
            JumpPointAPI.JumpDestination firstDestination = destinations.get(0);
            if (firstDestination == null || firstDestination.getDestination() == null) {
                throw new IllegalStateException("Malformed jump-point destination");
            }
            return firstDestination.getDestination().getContainingLocation();
        }
    }

    private static final class RouteSystemIndex {
        final int size;
        final Object first;
        final Object last;
        long validatedAtNanos;
        final IdentityHashMap<Object, List<Object>> byAnchor;
        final Object[] entries;
        final Object[] anchors;
        final boolean failed;

        RouteSystemIndex(int size, Object first, Object last, long builtAtNanos,
                         IdentityHashMap<Object, List<Object>> byAnchor,
                         Object[] entries, Object[] anchors, boolean failed) {
            this.size = size;
            this.first = first;
            this.last = last;
            this.validatedAtNanos = builtAtNanos;
            this.byAnchor = byAnchor;
            this.entries = entries;
            this.anchors = anchors;
            this.failed = failed;
        }

        boolean matches(List<?> live, long now, int ttlMs) {
            int currentSize = live.size();
            Object currentFirst = currentSize > 0 ? live.get(0) : null;
            Object currentLast = currentSize > 0 ? live.get(currentSize - 1) : null;
            long ttlNs = ttlMs * 1_000_000L;
            if (size != currentSize || first != currentFirst || last != currentLast) return false;
            if (ttlNs <= 0L) return false;
            if (now - validatedAtNanos < ttlNs) return true;
            if (failed) return false;

            try {
                for (int i = 0; i < currentSize; i++) {
                    Object raw = live.get(i);
                    if (raw != entries[i] || !(raw instanceof StarSystemAPI system)
                            || system.getHyperspaceAnchor() != anchors[i]) return false;
                }
            } catch (Throwable ignored) {
                return false;
            }
            validatedAtNanos = now;
            return true;
        }

        @SuppressWarnings("rawtypes")
        static RouteSystemIndex build(List systems, long now) {
            int size = systems.size();
            Object first = size > 0 ? systems.get(0) : null;
            Object last = size > 0 ? systems.get(size - 1) : null;
            IdentityHashMap<Object, List<Object>> byAnchor = new IdentityHashMap<>(Math.max(32, size * 2));
            Object[] entries = new Object[size];
            Object[] anchors = new Object[size];
            try {
                for (int i = 0; i < size; i++) {
                    Object raw = systems.get(i);
                    entries[i] = raw;
                    if (!(raw instanceof StarSystemAPI system)) {
                        throw new IllegalStateException("Non-system entry in CampaignEngine star-system list");
                    }
                    SectorEntityToken anchor = system.getHyperspaceAnchor();
                    anchors[i] = anchor;
                    if (anchor != null) {
                        byAnchor.computeIfAbsent(anchor, ignored -> new ArrayList<>(1)).add(raw);
                    }
                }
                return new RouteSystemIndex(size, first, last, now, byAnchor, entries, anchors, false);
            } catch (Throwable ex) {
                return new RouteSystemIndex(size, first, last, now, byAnchor, entries, anchors, true);
            }
        }
    }

    private static Method findDeclaredMethod(Class<?> type, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static Field findUniqueFieldByTypeName(Class<?> type, String fieldTypeName, String label)
            throws NoSuchFieldException {
        Field result = null;
        Class<?> current = type;
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (!field.getType().getName().equals(fieldTypeName)) continue;
                if (result != null) {
                    throw new NoSuchFieldException("Ambiguous " + label + " in " + type.getName());
                }
                result = field;
            }
            current = current.getSuperclass();
        }
        if (result == null) throw new NoSuchFieldException(label + " in " + type.getName());
        return result;
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static void throwUnchecked(Throwable cause) {
        if (cause instanceof RuntimeException runtime) throw runtime;
        if (cause instanceof Error error) throw error;
        throw new RuntimeException(cause);
    }

    // ---------------------------------------------------------------------
    // Helpers and stats
    // ---------------------------------------------------------------------

    private static int floorCell(float value, float cellSize) {
        return safeFloorToInt(value / cellSize);
    }

    private static int safeFloorToInt(float value) {
        if (value >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (value <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) Math.floor(value);
    }

    private static long cellKey(int x, int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }

    private static CacheGaugeSnapshot cacheGaugeSnapshot() {
        int labelCurrent = 0;
        long labelIcons = 0L;
        long labelBuckets = 0L;
        IdentityHashMap<LinkedHashMap<?, ?>, LabelIndex> labels = LABEL_INDEXES;
        synchronized (labels) {
            if (labels == LABEL_INDEXES) {
                labelCurrent = labels.size();
                for (LabelIndex index : labels.values()) {
                    labelIcons += Math.max(0, index.size);
                    labelBuckets += index.buckets.size();
                }
            }
        }

        int intelCurrent = 0;
        long intelEntries = 0L;
        IdentityHashMap<List<?>, IntelEntityIndex> intel = INTEL_ENTITY_INDEXES;
        synchronized (intel) {
            if (intel == INTEL_ENTITY_INDEXES) {
                intelCurrent = intel.size();
                for (IntelEntityIndex index : intel.values()) {
                    intelEntries += index.byPlugin.size();
                }
            }
        }

        int hitCurrent = 0;
        long hitCells = 0L;
        int hitMax = 0;
        IdentityHashMap<Object, HitCache> hits = HIT_CACHES;
        synchronized (hits) {
            if (hits == HIT_CACHES) {
                hitCurrent = hits.size();
                for (HitCache cache : hits.values()) {
                    synchronized (cache) {
                        int cells = cache.cells.size();
                        hitCells += cells;
                        if (cells > hitMax) hitMax = cells;
                    }
                }
            }
        }
        return new CacheGaugeSnapshot(labelCurrent, labelIcons, labelBuckets,
                intelCurrent, intelEntries, hitCurrent, hitCells, hitMax);
    }

    private record CacheGaugeSnapshot(
            int labelIndexesCurrent,
            long labelIndexesTotalIcons,
            long labelIndexesTotalBuckets,
            int intelEntityIndexesCurrent,
            long intelEntityIndexesTotalEntries,
            int hitCachesCurrent,
            long hitCachesTotalCells,
            int hitCachesMaxCellsPerOwner) {}

    private static void statsLoop() {
        PrepatcherConfig c = config;
        int seconds = c == null ? 0 : c.statsLogIntervalSeconds;
        if (seconds <= 0) return;
        while (true) {
            try {
                Thread.sleep(seconds * 1000L);
                CacheGaugeSnapshot gauges = cacheGaugeSnapshot();
                ScratchGaugeSnapshot scratchGauges = SCRATCH_GAUGES;
                MarketSchedulerGaugeSnapshot marketGauges = marketSchedulerGaugeSnapshot();
                PrepatcherLog.info("stats: retainCalls=" + RETAIN_CALLS.sumThenReset()
                        + ", avoidedContainsUpperBound=" + RETAIN_UPPER_BOUND.sumThenReset()
                        + ", retainKeysScanned=" + RETAIN_KEYS.sumThenReset()
                        + ", retainKeysRemoved=" + RETAIN_REMOVED.sumThenReset()
                        + ", retainEqualityFallbacks=" + RETAIN_EQUALITY_FALLBACKS.sumThenReset()
                        + ", hoverHits=" + HOVER_HITS.sumThenReset()
                        + ", hoverMisses=" + HOVER_MISSES.sumThenReset()
                        + ", labelIndexesCurrent=" + gauges.labelIndexesCurrent()
                        + ", labelIndexesTotalIcons=" + gauges.labelIndexesTotalIcons()
                        + ", labelIndexesTotalBuckets=" + gauges.labelIndexesTotalBuckets()
                        + ", intelEntityIndexesCurrent=" + gauges.intelEntityIndexesCurrent()
                        + ", intelEntityIndexesTotalEntries=" + gauges.intelEntityIndexesTotalEntries()
                        + ", hitCachesCurrent=" + gauges.hitCachesCurrent()
                        + ", hitCachesTotalCells=" + gauges.hitCachesTotalCells()
                        + ", hitCachesMaxCellsPerOwner=" + gauges.hitCachesMaxCellsPerOwner()
                        + ", labelOwnerIdleEvictions=" + LABEL_OWNER_IDLE_EVICTIONS.sum()
                        + ", labelOwnerLimitEvictions=" + LABEL_OWNER_LIMIT_EVICTIONS.sum()
                        + ", intelEntityOwnerIdleEvictions=" + INTEL_ENTITY_OWNER_IDLE_EVICTIONS.sum()
                        + ", intelEntityOwnerLimitEvictions=" + INTEL_ENTITY_OWNER_LIMIT_EVICTIONS.sum()
                        + ", hitOwnerIdleEvictions=" + HIT_OWNER_IDLE_EVICTIONS.sum()
                        + ", hitOwnerLimitEvictions=" + HIT_OWNER_LIMIT_EVICTIONS.sum()
                        + ", hitExpiredCellEvictions=" + HIT_EXPIRED_CELL_EVICTIONS.sum()
                        + ", scratchFrameCount=" + scratchGauges.frameCount()
                        + ", scratchPooledListCount=" + scratchGauges.pooledListCount()
                        + ", scratchPooledSetCount=" + scratchGauges.pooledSetCount()
                        + ", scratchRetainedListCapacity=" + scratchGauges.retainedListCapacity()
                        + ", scratchRetainedSetCapacity=" + scratchGauges.retainedSetCapacity()
                        + ", scratchRetainedIdentityCapacity=" + scratchGauges.retainedIdentityCapacity()
                        + ", scratchListTrims=" + SCRATCH_LIST_TRIMS.sum()
                        + ", scratchSetTrims=" + SCRATCH_SET_TRIMS.sum()
                        + ", scratchIdentityTrims=" + SCRATCH_IDENTITY_TRIMS.sum()
                        + ", scratchFrameReplacements=" + SCRATCH_FRAME_REPLACEMENTS.sum()
                        + ", mapLocationHits=" + MAP_LOCATION_HITS.sumThenReset()
                        + ", mapLocationMisses=" + MAP_LOCATION_MISSES.sumThenReset()
                        + ", arrowHits=" + ARROW_HITS.sumThenReset()
                        + ", arrowMisses=" + ARROW_MISSES.sumThenReset()
                        + ", intelIndexHits=" + INTEL_INDEX_HITS.sumThenReset()
                        + ", intelIndexBuilds=" + INTEL_INDEX_BUILDS.sumThenReset()
                        + ", labelCandidates=" + LABEL_CANDIDATES.sumThenReset()
                        + "/" + LABEL_TOTAL.sumThenReset()
                        + ", nebulaHits=" + NEBULA_HITS.sumThenReset()
                        + ", nebulaMisses=" + NEBULA_MISSES.sumThenReset()
                        + ", sampleClearSkips=" + SAMPLE_CLEAR_SKIPS.sumThenReset()
                        + ", campaignListenerRuns=" + CAMPAIGN_LISTENER_RUNS.sumThenReset()
                        + ", campaignListenerSkips=" + CAMPAIGN_LISTENER_SKIPS.sumThenReset()
                        + ", routeJumpIndexHits=" + ROUTE_JUMP_INDEX_HITS.sumThenReset()
                        + ", routeJumpIndexBuilds=" + ROUTE_JUMP_INDEX_BUILDS.sumThenReset()
                        + ", routeJumpIndexFallbacks=" + ROUTE_JUMP_INDEX_FALLBACKS.sumThenReset()
                        + ", routeSystemIndexHits=" + ROUTE_SYSTEM_INDEX_HITS.sumThenReset()
                        + ", routeSystemIndexBuilds=" + ROUTE_SYSTEM_INDEX_BUILDS.sumThenReset()
                        + ", routeSystemIndexFallbacks=" + ROUTE_SYSTEM_INDEX_FALLBACKS.sumThenReset()
                        + StarsectorPrepatcherStrategicJumpIndex.statsAndReset()
                        + ", economyLocationChecks=" + ECONOMY_LOCATION_CHECKS.sumThenReset()
                        + ", economyLocationDirty=" + ECONOMY_LOCATION_DIRTY.sumThenReset()
                        + ", economyLocationSkips=" + ECONOMY_LOCATION_SKIPS.sumThenReset()
                        + ", marketSchedulerSimulationTicks="
                        + MARKET_SCHEDULER_SIMULATION_TICKS.sumThenReset()
                        + ", marketSchedulerRenderBatches="
                        + MARKET_SCHEDULER_RENDER_BATCHES.sumThenReset()
                        + ", marketSchedulerFastForwardTicks="
                        + MARKET_SCHEDULER_FAST_FORWARD_TICKS.sumThenReset()
                        + ", marketSchedulerMaxTicksPerBatch="
                        + MARKET_SCHEDULER_MAX_TICKS_PER_BATCH.getAndSet(0L)
                        + ", marketSchedulerCalls=" + MARKET_SCHEDULER_CALLS.sumThenReset()
                        + ", marketSchedulerEconomyCalls="
                        + MARKET_SCHEDULER_ECONOMY_CALLS.sumThenReset()
                        + ", marketSchedulerPlanetConditionCalls="
                        + MARKET_SCHEDULER_PLANET_CONDITION_CALLS.sumThenReset()
                        + ", marketSchedulerFullAdvances="
                        + MARKET_SCHEDULER_FULL_ADVANCES.sumThenReset()
                        + ", marketSchedulerAccumulatedCalls="
                        + MARKET_SCHEDULER_ACCUMULATED_CALLS.sumThenReset()
                        + ", marketSchedulerHotAdvances="
                        + MARKET_SCHEDULER_HOT_ADVANCES.sumThenReset()
                        + ", marketSchedulerHiddenAdvances="
                        + MARKET_SCHEDULER_HIDDEN_ADVANCES.sumThenReset()
                        + ", marketSchedulerPerSimulationTickMarkets="
                        + countPerSimulationTickMarkets()
                        + ", marketSchedulerPerSimulationTickCalls="
                        + MARKET_SCHEDULER_PER_SIMULATION_TICK_CALLS.sumThenReset()
                        + ", marketSchedulerPerSimulationTickFlagChanges="
                        + MARKET_SCHEDULER_PER_SIMULATION_TICK_FLAG_CHANGES.sumThenReset()
                        + ", marketSchedulerSaveFlushes="
                        + MARKET_SCHEDULER_SAVE_FLUSHES.sumThenReset()
                        + ", marketSchedulerNotReadyTicks="
                        + MARKET_SCHEDULER_NOT_READY_TICKS.sumThenReset()
                        + ", marketSchedulerNotReadyCalls="
                        + MARKET_SCHEDULER_NOT_READY_CALLS.sumThenReset()
                        + ", marketSchedulerNestedTicks="
                        + MARKET_SCHEDULER_NESTED_TICKS.sumThenReset()
                        + ", marketSchedulerOutsideTickCalls="
                        + MARKET_SCHEDULER_OUTSIDE_TICK_CALLS.sumThenReset()
                        + ", marketSchedulerLifecycleInactiveCalls="
                        + MARKET_SCHEDULER_LIFECYCLE_INACTIVE_CALLS.sumThenReset()
                        + ", marketSchedulerFrameCaptureFailures="
                        + MARKET_SCHEDULER_FRAME_CAPTURE_FAILURES.sumThenReset()
                        + ", marketSchedulerInvalidSourceCalls="
                        + MARKET_SCHEDULER_INVALID_SOURCE_CALLS.sumThenReset()
                        + ", marketSchedulerInvalidAmountCalls="
                        + MARKET_SCHEDULER_INVALID_AMOUNT_CALLS.sumThenReset()
                        + ", marketSchedulerAmountOverflowSplits="
                        + MARKET_SCHEDULER_AMOUNT_OVERFLOW_SPLITS.sumThenReset()
                        + ", marketSchedulerStateFailures="
                        + MARKET_SCHEDULER_STATE_FAILURES.sumThenReset()
                        + ", marketSchedulerReentrantCalls="
                        + MARKET_SCHEDULER_REENTRANT_CALLS.sumThenReset()
                        + ", marketSchedulerMultipleSourceTickCalls="
                        + MARKET_SCHEDULER_MULTIPLE_SOURCE_TICK_CALLS.sumThenReset()
                        + ", marketSchedulerPolicyFailures="
                        + MARKET_SCHEDULER_POLICY_FAILURES.sumThenReset()
                        + ", marketSchedulerPolicyAudits="
                        + MARKET_SCHEDULER_POLICY_AUDITS.sumThenReset()
                        + ", marketSchedulerPolicyCacheHits="
                        + MARKET_SCHEDULER_POLICY_CACHE_HITS.sumThenReset()
                        + ", marketSchedulerDueChecks="
                        + MARKET_SCHEDULER_DUE_CHECKS.sumThenReset()
                        + ", marketSchedulerDueEntries="
                        + MARKET_SCHEDULER_DUE_ENTRIES.sumThenReset()
                        + ", marketSchedulerNotDueSkips="
                        + MARKET_SCHEDULER_NOT_DUE_SKIPS.sumThenReset()
                        + ", marketSchedulerInitialPhaseDeferrals="
                        + MARKET_SCHEDULER_INITIAL_PHASE_DEFERRALS.sumThenReset()
                        + ", marketSchedulerBatchTouchedEntries="
                        + MARKET_SCHEDULER_BATCH_TOUCHED_ENTRIES.sumThenReset()
                        + ", marketSchedulerAdvanceFailures="
                        + MARKET_SCHEDULER_ADVANCE_FAILURES.sumThenReset()
                        + ", marketSchedulerDisabledMarketCalls="
                        + MARKET_SCHEDULER_DISABLED_MARKET_CALLS.sumThenReset()
                        + ", marketSchedulerSynchronizedAdvances="
                        + MARKET_SCHEDULER_SYNCHRONIZED_ADVANCES.sumThenReset()
                        + ", marketSchedulerDirectSynchronizations="
                        + MARKET_SCHEDULER_DIRECT_SYNCHRONIZATIONS.sumThenReset()
                        + ", marketSchedulerSaveFlushFailures="
                        + MARKET_SCHEDULER_SAVE_FLUSH_FAILURES.sumThenReset()
                        + ", pendingMarketStepsCurrent="
                        + marketGauges.pendingMarketStepsCurrent()
                        + ", pendingMarketRunsCurrent="
                        + marketGauges.pendingMarketRunsCurrent()
                        + ", pendingMarketStepsHighWater="
                        + PENDING_MARKET_STEPS_HIGH_WATER.get()
                        + ", pendingMarketRunsHighWater="
                        + PENDING_MARKET_RUNS_HIGH_WATER.get()
                        + ", pendingRunOverflowFlushes="
                        + PENDING_RUN_OVERFLOW_FLUSHES.sumThenReset()
                        + ", coalescedMarketFlushes="
                        + COALESCED_MARKET_FLUSHES.sumThenReset()
                        + ", coalescedMarketAmount="
                        + COALESCED_MARKET_AMOUNT.sumThenReset()
                        + ", marketBatchContextsCreated="
                        + MARKET_BATCH_CONTEXTS_CREATED.sumThenReset()
                        + ", militaryBaseReplayedSteps="
                        + MILITARY_BASE_REPLAYED_STEPS.sumThenReset()
                        + ", lionsGuardReplayedSteps="
                        + LIONS_GUARD_REPLAYED_STEPS.sumThenReset()
                        + ", recentUnrestReplayedSteps="
                        + RECENT_UNREST_REPLAYED_STEPS.sumThenReset()
                        + ", constructionFullRateCalls="
                        + CONSTRUCTION_FULL_RATE_CALLS.sumThenReset()
                        + ", constructionFullRateMarkets="
                        + marketGauges.constructionFullRateMarkets()
                        + ", constructionMarketsQueueNonEmpty="
                        + marketGauges.constructionMarketsQueueNonEmpty()
                        + ", constructionMarketsIndustryBuilding="
                        + marketGauges.constructionMarketsIndustryBuilding()
                        + ", constructionMarketsIndustryUpgrading="
                        + marketGauges.constructionMarketsIndustryUpgrading()
                        + ", constructionMarketsReportedBuildingWithoutRaw="
                        + marketGauges.constructionMarketsReportedBuildingWithoutRaw()
                        + ", constructionMarketsUncertain="
                        + marketGauges.constructionMarketsUncertain()
                        + ", constructionMarketsMultipleReasons="
                        + marketGauges.constructionMarketsMultipleReasons()
                        + ", constructionModeEntries="
                        + CONSTRUCTION_MODE_ENTRIES.sumThenReset()
                        + ", constructionModeExits="
                        + CONSTRUCTION_MODE_EXITS.sumThenReset()
                        + ", constructionBoundaryExactReplays="
                        + CONSTRUCTION_BOUNDARY_EXACT_REPLAYS.sumThenReset()
                        + ", constructionScans="
                        + CONSTRUCTION_SCANS.sumThenReset()
                        + ", constructionDirtyScans="
                        + CONSTRUCTION_DIRTY_SCANS.sumThenReset()
                        + ", constructionSafetyAuditScans="
                        + CONSTRUCTION_SAFETY_AUDIT_SCANS.sumThenReset()
                        + ", constructionForcedScans="
                        + CONSTRUCTION_FORCED_SCANS.sumThenReset()
                        + ", constructionCachedDecisions="
                        + CONSTRUCTION_CACHED_DECISIONS.sumThenReset()
                        + ", constructionDetectedQueueNonEmpty="
                        + CONSTRUCTION_DETECTED_QUEUE_NON_EMPTY.sumThenReset()
                        + ", constructionDetectedIndustryBuilding="
                        + CONSTRUCTION_DETECTED_INDUSTRY_BUILDING.sumThenReset()
                        + ", constructionDetectedIndustryUpgrading="
                        + CONSTRUCTION_DETECTED_INDUSTRY_UPGRADING.sumThenReset()
                        + ", constructionDetectedReportedBuildingWithoutRaw="
                        + CONSTRUCTION_DETECTED_REPORTED_BUILDING_WITHOUT_RAW.sumThenReset()
                        + ", constructionDetectedMultipleReasons="
                        + CONSTRUCTION_DETECTED_MULTIPLE_REASONS.sumThenReset()
                        + ", constructionDetectedProbeFailure="
                        + CONSTRUCTION_DETECTED_PROBE_FAILURE.sumThenReset()
                        + ", constructionQueueNullScans="
                        + CONSTRUCTION_QUEUE_NULL_SCANS.sumThenReset()
                        + ", constructionQueueItemsNullScans="
                        + CONSTRUCTION_QUEUE_ITEMS_NULL_SCANS.sumThenReset()
                        + ", constructionIndustriesNullScans="
                        + CONSTRUCTION_INDUSTRIES_NULL_SCANS.sumThenReset()
                        + ", constructionQueueItemsObserved="
                        + CONSTRUCTION_QUEUE_ITEMS_OBSERVED.sumThenReset()
                        + ", constructionIndustriesScanned="
                        + CONSTRUCTION_INDUSTRIES_SCANNED.sumThenReset()
                        + ", constructionMaxQueueItems="
                        + CONSTRUCTION_MAX_QUEUE_ITEMS.get()
                        + ", constructionMaxIndustriesScanned="
                        + CONSTRUCTION_MAX_INDUSTRIES_SCANNED.get()
                        + ", constructionAuditStateChanges="
                        + CONSTRUCTION_AUDIT_STATE_CHANGES.sumThenReset()
                        + ", constructionAuditFalseToTrue="
                        + CONSTRUCTION_AUDIT_FALSE_TO_TRUE.sumThenReset()
                        + ", constructionAuditTrueToFalse="
                        + CONSTRUCTION_AUDIT_TRUE_TO_FALSE.sumThenReset()
                        + ", constructionReasonChanges="
                        + CONSTRUCTION_REASON_CHANGES.sumThenReset()
                        + ", constructionMutationRaces="
                        + CONSTRUCTION_MUTATION_RACES.sumThenReset()
                        + ", constructionDiagnosticSamplesDropped="
                        + CONSTRUCTION_DIAGNOSTIC_SAMPLES_DROPPED.sumThenReset()
                        + ", saveCoalescedMarkets="
                        + SAVE_COALESCED_MARKETS.sumThenReset()
                        + ", saveExactReplayMarkets="
                        + SAVE_EXACT_REPLAY_MARKETS.sumThenReset()
                        + ", saveExactReplaySteps="
                        + SAVE_EXACT_REPLAY_STEPS.sumThenReset()
                        + ", saveFlushDurationNanos="
                        + SAVE_FLUSH_DURATION_NANOS.sumThenReset()
                        + ", economyPersistentRebuilds=" + ECONOMY_PERSISTENT_SNAPSHOT_REBUILDS.sumThenReset()
                        + ", economyPersistentAudits=" + ECONOMY_PERSISTENT_SNAPSHOT_AUDITS.sumThenReset()
                        + ", economyPersistentMismatches=" + ECONOMY_PERSISTENT_SNAPSHOT_MISMATCHES.sumThenReset()
                        + ", economyPersistentElements=" + ECONOMY_PERSISTENT_SNAPSHOT_ELEMENTS.sumThenReset()
                        + ", economyMarketPersistentHits=" + ECONOMY_MARKET_PERSISTENT_HITS.sumThenReset()
                        + ", economyMarketPersistentRebuilds=" + ECONOMY_MARKET_PERSISTENT_REBUILDS.sumThenReset()
                        + ", economyMarketPersistentAudits=" + ECONOMY_MARKET_PERSISTENT_AUDITS.sumThenReset()
                        + ", economyMarketPersistentMismatches=" + ECONOMY_MARKET_PERSISTENT_MISMATCHES.sumThenReset()
                        + ", economyMarketPersistentElements=" + ECONOMY_MARKET_PERSISTENT_ELEMENTS.sumThenReset()
                        + ", marketConditionPersistentHits=" + MARKET_CONDITION_PERSISTENT_HITS.sumThenReset()
                        + ", marketConditionPersistentRebuilds=" + MARKET_CONDITION_PERSISTENT_REBUILDS.sumThenReset()
                        + ", marketConditionPersistentAudits=" + MARKET_CONDITION_PERSISTENT_AUDITS.sumThenReset()
                        + ", marketConditionPersistentMismatches=" + MARKET_CONDITION_PERSISTENT_MISMATCHES.sumThenReset()
                        + ", marketConditionPersistentElements=" + MARKET_CONDITION_PERSISTENT_ELEMENTS.sumThenReset()
                        + ", marketIndustryPersistentHits=" + MARKET_INDUSTRY_PERSISTENT_HITS.sumThenReset()
                        + ", marketIndustryPersistentRebuilds=" + MARKET_INDUSTRY_PERSISTENT_REBUILDS.sumThenReset()
                        + ", marketIndustryPersistentAudits=" + MARKET_INDUSTRY_PERSISTENT_AUDITS.sumThenReset()
                        + ", marketIndustryPersistentMismatches=" + MARKET_INDUSTRY_PERSISTENT_MISMATCHES.sumThenReset()
                        + ", marketIndustryPersistentElements=" + MARKET_INDUSTRY_PERSISTENT_ELEMENTS.sumThenReset()
                        + ", commodityTemporalMarkets=" + COMMODITY_TEMPORAL_MARKET_CALLS.sumThenReset()
                        + ", commodityTemporalEntries=" + COMMODITY_TEMPORAL_ENTRIES.sumThenReset()
                        + ", commodityTemporalActive=" + COMMODITY_TEMPORAL_ACTIVE_ADVANCES.sumThenReset()
                        + ", commodityTemporalInactiveSkips=" + COMMODITY_TEMPORAL_INACTIVE_SKIPS.sumThenReset()
                        + ", commodityTemporalReapplies=" + COMMODITY_TEMPORAL_REAPPLIES.sumThenReset()
                        + ", commodityTemporalAvailableReapplies=" + COMMODITY_TEMPORAL_AVAILABLE_REAPPLIES.sumThenReset()
                        + ", commodityTemporalDirtySignals=" + COMMODITY_TEMPORAL_DIRTY_SIGNALS.sumThenReset()
                        + ", commodityTemporalExposures=" + COMMODITY_TEMPORAL_EXPOSURES.sumThenReset()
                        + ", commodityTemporalAudits=" + COMMODITY_TEMPORAL_AUDITS.sumThenReset()
                        + ", commodityTemporalRebuilds=" + COMMODITY_TEMPORAL_REBUILDS.sumThenReset()
                        + ", commodityTemporalWakeupEnqueues="
                        + COMMODITY_TEMPORAL_WAKEUP_ENQUEUES.sumThenReset()
                        + ", commodityTemporalWakeupDrains="
                        + COMMODITY_TEMPORAL_WAKEUP_DRAINS.sumThenReset()
                        + ", commodityTemporalWakeupInserts="
                        + COMMODITY_TEMPORAL_WAKEUP_INSERTS.sumThenReset()
                        + ", commodityTemporalFallbacks=" + COMMODITY_TEMPORAL_FALLBACKS.sumThenReset()
                        + ", commodityTemporalExactVanillaEntries="
                        + COMMODITY_TEMPORAL_EXACT_VANILLA_ENTRIES.sumThenReset()
                        + ", commodityTemporalAoTDNativeEntries="
                        + COMMODITY_TEMPORAL_AOTD_NATIVE_ENTRIES.sumThenReset()
                        + ", commodityTemporalVanillaOnlyEntries="
                        + COMMODITY_TEMPORAL_VANILLA_ONLY_ENTRIES.sumThenReset()
                        + ", commodityTemporalExactVanillaActive="
                        + COMMODITY_TEMPORAL_EXACT_VANILLA_ACTIVE.sumThenReset()
                        + ", commodityTemporalAoTDNativeActive="
                        + COMMODITY_TEMPORAL_AOTD_NATIVE_ACTIVE.sumThenReset()
                        + ", commodityTemporalVanillaOnlyActive="
                        + COMMODITY_TEMPORAL_VANILLA_ONLY_ACTIVE.sumThenReset()
                        + ", commodityTemporalActiveAvailable="
                        + COMMODITY_TEMPORAL_ACTIVE_AVAILABLE.sumThenReset()
                        + ", commodityTemporalActiveAoTDExcess="
                        + COMMODITY_TEMPORAL_ACTIVE_AOTD_EXCESS.sumThenReset()
                        + ", commodityTemporalActiveAoTDDeficit="
                        + COMMODITY_TEMPORAL_ACTIVE_AOTD_DEFICIT.sumThenReset()
                        + ", commodityTemporalActiveTrade="
                        + COMMODITY_TEMPORAL_ACTIVE_TRADE.sumThenReset()
                        + ", commodityTemporalActiveTradePlus="
                        + COMMODITY_TEMPORAL_ACTIVE_TRADE_PLUS.sumThenReset()
                        + ", commodityTemporalActiveTradeMinus="
                        + COMMODITY_TEMPORAL_ACTIVE_TRADE_MINUS.sumThenReset()
                        + ", persistentStructureEpochs=" + PERSISTENT_STRUCTURE_EPOCHS.sumThenReset()
                        + ", economyLocationEpochHits=" + ECONOMY_LOCATION_EPOCH_HITS.sumThenReset()
                        + ", economyLocationAudits=" + ECONOMY_LOCATION_AUDITS.sumThenReset()
                        + ", economyLocationSequenceMismatches=" + ECONOMY_LOCATION_SEQUENCE_MISMATCHES.sumThenReset()
                        + ", commRelayIndexHits=" + COMM_RELAY_INDEX_HITS.sumThenReset()
                        + ", commRelayIndexBuilds=" + COMM_RELAY_INDEX_BUILDS.sumThenReset()
                        + ", commRelayIndexFallbacks=" + COMM_RELAY_INDEX_FALLBACKS.sumThenReset()
                        + ", commRelayValidationScans=" + COMM_RELAY_VALIDATION_SCANS.sumThenReset()
                        + ", commRelaySystemsValidated=" + COMM_RELAY_SYSTEMS_VALIDATED.sumThenReset()
                        + ", commRelaySystems=" + COMM_RELAY_SYSTEMS_CANDIDATE.sumThenReset()
                        + "/" + COMM_RELAY_SYSTEMS_TOTAL.sumThenReset()
                        + ", shipScratchLists=" + SHIP_SCRATCH_LISTS.sumThenReset()
                        + ", shipScratchSets=" + SHIP_SCRATCH_SETS.sumThenReset()
                        + ", particleGroups=" + PARTICLE_GROUPS.sumThenReset()
                        + ", particleExpired=" + PARTICLE_EXPIRED.sumThenReset()
                        + ", particleLinearRemovals=" + PARTICLE_LINEAR_REMOVALS.sumThenReset()
                        + ", campaignCacheResets=" + CAMPAIGN_CACHE_RESETS.sumThenReset()
                        + ", textReads=" + TEXT_READ_CALLS.sumThenReset()
                        + ", textChars=" + TEXT_READ_CHARS.sumThenReset()
                        + ", textCRRemoved=" + TEXT_READ_CR_REMOVED.sumThenReset()
                        + ", startupLogsSuppressed="
                        + (STARTUP_LOG_JSON.sumThenReset() + STARTUP_LOG_CSV.sumThenReset()
                        + STARTUP_LOG_SCRIPT.sumThenReset() + STARTUP_LOG_RULE.sumThenReset()
                        + STARTUP_LOG_SPEC.sumThenReset() + STARTUP_LOG_TEXTURE.sumThenReset()
                        + STARTUP_LOG_SOUND.sumThenReset() + STARTUP_LOG_OTHER.sumThenReset()));
                PrepatcherLog.info("stats: "
                        + StarsectorPrepatcherTempModHooks.statsAndReset()
                        + StarsectorPrepatcherCoreWorldsRuntime.statsAndResetFragment());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable ex) {
                PrepatcherLog.error("Stats logger failed", ex);
            }
        }
    }

    private static final class MarketAdvanceObservationContext {
        int origin = MARKET_ORIGIN_NONE;
        int depth;
        long siteId;
        final long[] observerSiteIds = new long[8];
        final String[] observerMetadata = new String[8];
        final DirectMarketSiteStats[] observerStats = new DirectMarketSiteStats[8];
    }

    private static final class DirectMarketCallToken {
        final DirectMarketSiteStats stats;
        final boolean timed;
        final long startNanos;

        DirectMarketCallToken(DirectMarketSiteStats stats, boolean timed, long startNanos) {
            this.stats = stats;
            this.timed = timed;
            this.startNanos = startNanos;
        }
    }

    /**
     * Low-overhead aggregate observer for direct Market.advance call sites.
     * All maps retain only strings/numbers and never MarketAPI instances.
     */
    private static final class DirectMarketObserver {
        private static final DateTimeFormatter SESSION_FORMAT =
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
        private static final String SITE_HEADER =
                "site_id,mod_id,mod_name,mod_directory,jar_name,source,class,method,descriptor,line,ordinal,amount_source,call_owner,opcode\n";
        private static final String OBSERVATION_HEADER =
                "timestamp_utc,interval_seconds,site_id,mod_id,mod_name,mod_directory,jar_name,source,class,method,descriptor,line,ordinal,"
                        + "amount_source,call_owner,opcode,interval_calls,cumulative_calls,calls_per_second,"
                        + "sampled_calls,sampled_inclusive_ns,estimated_inclusive_ns,avg_sample_ns,"
                        + "exceptions,recursive_calls,finite_amounts,amount_sum,amount_avg,"
                        + "zero_amount,negative_amount,nan_amount,null_market,approx_unique_markets_sampled\n";
        private static final String STACK_HEADER =
                "timestamp_utc,site_id,samples,stack\n";
        private static final String SUMMARY_HEADER =
                "timestamp_utc,interval_seconds,scheduled_entries,scheduler_fallback_entries,"
                        + "save_flush_entries,planet_condition_scheduled_entries,"
                        + "planet_condition_fallback_entries,direct_callsite_entries,unknown_entries,"
                        + "direct_wrapper_calls,site_count,site_overflow_calls,metadata_collisions\n";
        private static final String UNKNOWN_HEADER =
                "timestamp_utc,samples,stack\n";

        private final int timingSampleEvery;
        private final int stackSampleEvery;
        private final int maxStacksPerSite;
        private final int reportIntervalSeconds;
        private final int maxSites;
        private final int unknownStackSampleLimit;
        private final Path sessionDir;
        private final Path sitesFile;
        private final Path observationsFile;
        private final Path stacksFile;
        private final Path summaryFile;
        private final Path unknownFile;
        private final ConcurrentHashMap<Long, DirectMarketSiteStats> sites =
                new ConcurrentHashMap<>();
        private final DirectMarketSiteStats overflowSite =
                DirectMarketSiteStats.overflow();
        private final AtomicReference<UnknownStackInterval> unknownStackInterval =
                new AtomicReference<>(new UnknownStackInterval());
        private final LongAdder scheduledEntries = new LongAdder();
        private final LongAdder schedulerFallbackEntries = new LongAdder();
        private final LongAdder saveFlushEntries = new LongAdder();
        private final LongAdder planetConditionScheduledEntries = new LongAdder();
        private final LongAdder planetConditionFallbackEntries = new LongAdder();
        private final LongAdder directCallsiteEntries = new LongAdder();
        private final LongAdder unknownEntries = new LongAdder();
        private final LongAdder directWrapperCalls = new LongAdder();
        private final LongAdder siteOverflowCalls = new LongAdder();
        private final LongAdder metadataCollisions = new LongAdder();
        private final Object metadataWriteLock = new Object();
        private volatile long lastReportNanos = System.nanoTime();
        private final String sessionOrigin;

        DirectMarketObserver(PrepatcherConfig configuration, Path root) throws IOException {
            timingSampleEvery = configuration.directMarketTimingSampleEvery;
            stackSampleEvery = configuration.directMarketStackSampleEvery;
            maxStacksPerSite = configuration.directMarketMaxStacksPerSite;
            reportIntervalSeconds = configuration.directMarketReportIntervalSeconds;
            maxSites = configuration.directMarketMaxSites;
            unknownStackSampleLimit = configuration.directMarketUnknownStackSamples;
            sessionOrigin = sanitizeSessionOrigin(System.getProperty(
                    "starsector.prepatcher.sessionOrigin", "game"));

            String originSegment = "game".equals(sessionOrigin) ? "" : sessionOrigin + "-";
            String sessionName = "session-" + originSegment + SESSION_FORMAT.format(Instant.now())
                    + "-pid" + ProcessHandle.current().pid();
            Path base = root.resolve("logs").resolve("direct-market-observe");
            sessionDir = base.resolve(sessionName);
            Files.createDirectories(sessionDir);
            sitesFile = sessionDir.resolve("call-sites.csv");
            observationsFile = sessionDir.resolve("observations.csv");
            stacksFile = sessionDir.resolve("stacks.csv");
            summaryFile = sessionDir.resolve("summary.csv");
            unknownFile = sessionDir.resolve("unknown-stacks.csv");

            Files.writeString(sitesFile, SITE_HEADER, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            Files.writeString(observationsFile, OBSERVATION_HEADER, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            Files.writeString(stacksFile, STACK_HEADER, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            Files.writeString(summaryFile, SUMMARY_HEADER, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            Files.writeString(unknownFile, UNKNOWN_HEADER, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            Files.writeString(sessionDir.resolve("session.json"), sessionJson(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            System.setProperty("starsector.prepatcher.directMarketObservationDir",
                    sessionDir.toAbsolutePath().toString());
            PrepatcherLog.info("Direct Market.advance observation session: "
                    + sessionDir.toAbsolutePath());
        }

        void start() {
            Thread reporter = new Thread(this::reportLoop,
                    "StarsectorPrepatcher-DirectMarketObserver");
            reporter.setDaemon(true);
            reporter.setPriority(Thread.MIN_PRIORITY);
            reporter.start();
        }

        void registerSite(long siteId, String metadata) {
            DirectMarketSiteStats stats = site(null, siteId, metadata);
            if (stats == overflowSite) return;
            writeMetadataIfNeeded(stats);
        }

        DirectMarketCallToken beginDirectCall(MarketAdvanceObservationContext context,
                                              MarketAPI market, float amount,
                                              long siteId, String metadata,
                                              boolean recursive) {
            DirectMarketSiteStats stats = site(context, siteId, metadata);
            long callNumber = stats.totalCalls.incrementAndGet();
            stats.intervalCalls.increment();
            directWrapperCalls.increment();
            if (Float.isFinite(amount)) {
                stats.finiteAmounts.increment();
                stats.amountSum.add(amount);
            } else if (Float.isNaN(amount)) {
                stats.nanAmounts.increment();
            }
            if (amount < 0f) stats.negativeAmounts.increment();
            else if (amount == 0f) stats.zeroAmounts.increment();
            if (market == null) stats.nullMarkets.increment();
            if (recursive) stats.recursiveCalls.increment();
            if (market != null && (callNumber == 1L || (callNumber & 7L) == 0L)) {
                stats.observeMarketIdentity(System.identityHashCode(market));
            }

            boolean stackSample = maxStacksPerSite > 0
                    && (callNumber == 1L
                    || (stackSampleEvery > 0 && callNumber % stackSampleEvery == 0L));
            if (stackSample) stats.recordStack(captureDirectStack(), maxStacksPerSite);

            boolean timed = timingSampleEvery > 0
                    && Math.floorMod(callNumber + stats.samplePhase, timingSampleEvery) == 0L;
            return new DirectMarketCallToken(stats, timed,
                    timed ? System.nanoTime() : 0L);
        }

        void endDirectCall(DirectMarketCallToken token, boolean success) {
            if (token == null || token.stats == null) return;
            if (!success) token.stats.exceptions.increment();
            if (token.timed) {
                long elapsed = System.nanoTime() - token.startNanos;
                if (elapsed < 0L) elapsed = 0L;
                token.stats.sampledCalls.increment();
                token.stats.sampledInclusiveNanos.add(elapsed);
            }
        }

        void observeMarketEntry(int origin, long siteId, MarketAPI market, float amount) {
            switch (origin) {
                case MARKET_ORIGIN_SCHEDULED -> scheduledEntries.increment();
                case MARKET_ORIGIN_SCHEDULER_FALLBACK -> schedulerFallbackEntries.increment();
                case MARKET_ORIGIN_SAVE_FLUSH -> saveFlushEntries.increment();
                case MARKET_ORIGIN_PLANET_CONDITION_SCHEDULED ->
                        planetConditionScheduledEntries.increment();
                case MARKET_ORIGIN_PLANET_CONDITION_FALLBACK ->
                        planetConditionFallbackEntries.increment();
                case MARKET_ORIGIN_DIRECT_CALL_SITE -> directCallsiteEntries.increment();
                default -> {
                    unknownEntries.increment();
                    UnknownStackInterval interval;
                    while (true) {
                        interval = unknownStackInterval.get();
                        if (interval.tryAcquireWriter()) break;
                    }
                    try {
                        int sample = interval.samples.getAndIncrement();
                        if (sample < unknownStackSampleLimit) {
                            String stack = captureDirectStack();
                            interval.stacks.computeIfAbsent(
                                    stack, ignored -> new LongAdder()).increment();
                        }
                    } finally {
                        interval.releaseWriter();
                    }
                }
            }
        }

        private DirectMarketSiteStats site(MarketAdvanceObservationContext context,
                                             long siteId, String metadata) {
            int slot = (int) (siteId ^ (siteId >>> 32)) & 7;
            if (context != null) {
                DirectMarketSiteStats cached = context.observerStats[slot];
                String cachedMetadata = context.observerMetadata[slot];
                if (cached != null && context.observerSiteIds[slot] == siteId
                        && (cachedMetadata == metadata
                        || Objects.equals(cachedMetadata, metadata))) {
                    return cached;
                }
            }

            DirectMarketSiteStats existing = sites.get(siteId);
            if (existing == null) {
                if (sites.size() >= maxSites) {
                    siteOverflowCalls.increment();
                    return overflowSite;
                }
                DirectMarketSiteStats candidate = new DirectMarketSiteStats(
                        DirectMarketMetadata.parse(siteId, metadata));
                DirectMarketSiteStats raced = sites.putIfAbsent(siteId, candidate);
                existing = raced == null ? candidate : raced;
            }
            if (!existing.metadata.raw.equals(metadata)) metadataCollisions.increment();
            if (context != null) {
                context.observerSiteIds[slot] = siteId;
                context.observerMetadata[slot] = metadata;
                context.observerStats[slot] = existing;
            }
            return existing;
        }

        private void writeMetadataIfNeeded(DirectMarketSiteStats stats) {
            if (stats == null || stats == overflowSite || stats.metadataWritten) return;
            synchronized (metadataWriteLock) {
                if (stats.metadataWritten) return;
                StringBuilder row = new StringBuilder();
                appendSiteRow(row, stats.metadata);
                try {
                    append(sitesFile, row);
                    stats.metadataWritten = true;
                } catch (IOException failure) {
                    PrepatcherLog.warn("Direct market call-site manifest write failed for "
                            + unsignedHex(stats.metadata.siteId) + ": "
                            + failure.getMessage());
                }
            }
        }

        private void reportLoop() {
            while (true) {
                try {
                    Thread.sleep(reportIntervalSeconds * 1000L);
                    report();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    try { report(); } catch (Throwable ignored) {}
                    return;
                } catch (Throwable failure) {
                    PrepatcherLog.error("Direct Market.advance observation report failed", failure);
                }
            }
        }

        private void report() throws IOException {
            long nowNanos = System.nanoTime();
            double elapsedSeconds = Math.max(0.001,
                    (nowNanos - lastReportNanos) / 1_000_000_000.0);
            lastReportNanos = nowNanos;
            String timestamp = Instant.now().toString();

            ArrayList<DirectMarketIntervalSnapshot> snapshots = new ArrayList<>();
            for (DirectMarketSiteStats stats : sites.values()) {
                DirectMarketIntervalSnapshot snapshot = stats.snapshot();
                if (snapshot.calls > 0L || !stats.metadataWritten) snapshots.add(snapshot);
            }
            DirectMarketIntervalSnapshot overflow = overflowSite.snapshot();
            if (overflow.calls > 0L) snapshots.add(overflow);
            snapshots.sort((left, right) -> Long.compare(
                    right.estimatedInclusiveNanos(), left.estimatedInclusiveNanos()));

            StringBuilder observationRows = new StringBuilder();
            StringBuilder stackRows = new StringBuilder();
            for (DirectMarketIntervalSnapshot snapshot : snapshots) {
                DirectMarketSiteStats stats = snapshot.stats;
                writeMetadataIfNeeded(stats);
                if (snapshot.calls > 0L) {
                    appendObservationRow(observationRows, timestamp, elapsedSeconds, snapshot);
                }
                stats.appendStackRows(stackRows, timestamp);
            }
            if (observationRows.length() > 0) append(observationsFile, observationRows);
            if (stackRows.length() > 0) append(stacksFile, stackRows);

            // Atomically detach the interval so stack signatures do not accumulate
            // for the process lifetime and calls racing the report go to one of the
            // two complete intervals rather than sharing a reset counter.
            UnknownStackInterval unknownInterval = unknownStackInterval.getAndSet(
                    new UnknownStackInterval());
            unknownInterval.sealAndAwaitWriters();
            StringBuilder unknownRows = new StringBuilder();
            for (Map.Entry<String, LongAdder> entry : unknownInterval.stacks.entrySet()) {
                long samples = entry.getValue().sum();
                if (samples <= 0L) continue;
                unknownRows.append(csv(timestamp)).append(',')
                        .append(samples).append(',')
                        .append(csv(entry.getKey())).append('\n');
            }
            if (unknownRows.length() > 0) append(unknownFile, unknownRows);

            long scheduled = scheduledEntries.sumThenReset();
            long fallback = schedulerFallbackEntries.sumThenReset();
            long save = saveFlushEntries.sumThenReset();
            long planetScheduled = planetConditionScheduledEntries.sumThenReset();
            long planetFallback = planetConditionFallbackEntries.sumThenReset();
            long direct = directCallsiteEntries.sumThenReset();
            long unknown = unknownEntries.sumThenReset();
            long wrappers = directWrapperCalls.sumThenReset();
            long overflowCalls = siteOverflowCalls.sumThenReset();
            long collisions = metadataCollisions.sumThenReset();
            String summary = csv(timestamp) + ',' + decimal(elapsedSeconds) + ','
                    + scheduled + ',' + fallback + ',' + save + ','
                    + planetScheduled + ',' + planetFallback + ','
                    + direct + ',' + unknown + ',' + wrappers + ','
                    + sites.size() + ',' + overflowCalls + ',' + collisions + '\n';
            append(summaryFile, new StringBuilder(summary));

            if (wrappers > 0L || unknown > 0L || planetScheduled > 0L
                    || planetFallback > 0L
                    || overflowCalls > 0L || collisions > 0L) {
                PrepatcherLog.info("direct-market-observe: calls=" + wrappers
                        + ", sites=" + sites.size()
                        + ", scheduledEntries=" + scheduled
                        + ", planetScheduledEntries=" + planetScheduled
                        + ", planetFallbackEntries=" + planetFallback
                        + ", directEntries=" + direct
                        + ", unknownEntries=" + unknown
                        + ", overflowCalls=" + overflowCalls
                        + ", output=" + sessionDir.toAbsolutePath());
            }
        }

        private String sessionJson() {
            String version = System.getProperty("starsector.prepatcher.version", "unknown");
            return "{\n"
                    + "  \"schema\": 3,\n"
                    + "  \"mode\": \"observe\",\n"
                    + "  \"createdUtc\": " + json(Instant.now().toString()) + ",\n"
                    + "  \"prepatcherVersion\": " + json(version) + ",\n"
                    + "  \"sessionOrigin\": " + json(sessionOrigin) + ",\n"
                    + "  \"timingSampleEvery\": " + timingSampleEvery + ",\n"
                    + "  \"stackSampleEvery\": " + stackSampleEvery + ",\n"
                    + "  \"maxStacksPerSite\": " + maxStacksPerSite + ",\n"
                    + "  \"reportIntervalSeconds\": " + reportIntervalSeconds + ",\n"
                    + "  \"maxSites\": " + maxSites + ",\n"
                    + "  \"unknownStackSamplesPerInterval\": "
                    + unknownStackSampleLimit + ",\n"
                    + "  \"behavior\": \"Engine-owned market calls share one scheduler. Direct mod calls remain synchronous and consume any pending scheduler debt before their own amount.\"\n"
                    + "}\n";
        }

        private static String sanitizeSessionOrigin(String raw) {
            if (raw == null || raw.isBlank()) return "game";
            StringBuilder sanitized = new StringBuilder(Math.min(raw.length(), 48));
            for (int index = 0; index < raw.length() && sanitized.length() < 48; index++) {
                char value = raw.charAt(index);
                if ((value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z')
                        || (value >= '0' && value <= '9') || value == '.' || value == '_'
                        || value == '-') {
                    sanitized.append(value);
                } else {
                    sanitized.append('_');
                }
            }
            return sanitized.length() == 0 ? "game" : sanitized.toString();
        }

        private static void appendSiteRow(StringBuilder output, DirectMarketMetadata metadata) {
            output.append(csv(unsignedHex(metadata.siteId))).append(',')
                    .append(csv(metadata.modId)).append(',')
                    .append(csv(metadata.modName)).append(',')
                    .append(csv(metadata.modDirectory)).append(',')
                    .append(csv(metadata.jarName)).append(',')
                    .append(csv(metadata.source)).append(',')
                    .append(csv(metadata.className)).append(',')
                    .append(csv(metadata.method)).append(',')
                    .append(csv(metadata.descriptor)).append(',')
                    .append(metadata.line).append(',')
                    .append(metadata.ordinal).append(',')
                    .append(csv(metadata.amountSource)).append(',')
                    .append(csv(metadata.callOwner)).append(',')
                    .append(metadata.opcode).append('\n');
        }

        private static void appendObservationRow(StringBuilder output, String timestamp,
                                                 double elapsedSeconds,
                                                 DirectMarketIntervalSnapshot snapshot) {
            DirectMarketMetadata metadata = snapshot.stats.metadata;
            long estimated = snapshot.estimatedInclusiveNanos();
            double average = snapshot.sampledCalls == 0L ? 0.0
                    : (double) snapshot.sampledNanos / snapshot.sampledCalls;
            output.append(csv(timestamp)).append(',')
                    .append(decimal(elapsedSeconds)).append(',')
                    .append(csv(unsignedHex(metadata.siteId))).append(',')
                    .append(csv(metadata.modId)).append(',')
                    .append(csv(metadata.modName)).append(',')
                    .append(csv(metadata.modDirectory)).append(',')
                    .append(csv(metadata.jarName)).append(',')
                    .append(csv(metadata.source)).append(',')
                    .append(csv(metadata.className)).append(',')
                    .append(csv(metadata.method)).append(',')
                    .append(csv(metadata.descriptor)).append(',')
                    .append(metadata.line).append(',')
                    .append(metadata.ordinal).append(',')
                    .append(csv(metadata.amountSource)).append(',')
                    .append(csv(metadata.callOwner)).append(',')
                    .append(metadata.opcode).append(',')
                    .append(snapshot.calls).append(',')
                    .append(snapshot.cumulativeCalls).append(',')
                    .append(decimal(snapshot.calls / elapsedSeconds)).append(',')
                    .append(snapshot.sampledCalls).append(',')
                    .append(snapshot.sampledNanos).append(',')
                    .append(estimated).append(',')
                    .append(decimal(average)).append(',')
                    .append(snapshot.exceptions).append(',')
                    .append(snapshot.recursiveCalls).append(',')
                    .append(snapshot.finiteAmounts).append(',')
                    .append(decimal(snapshot.amountSum)).append(',')
                    .append(decimal(snapshot.finiteAmounts == 0L ? 0.0
                            : snapshot.amountSum / snapshot.finiteAmounts)).append(',')
                    .append(snapshot.zeroAmounts).append(',')
                    .append(snapshot.negativeAmounts).append(',')
                    .append(snapshot.nanAmounts).append(',')
                    .append(snapshot.nullMarkets).append(',')
                    .append(snapshot.approxUniqueMarkets).append('\n');
        }

        private static void append(Path file, StringBuilder content) throws IOException {
            Files.writeString(file, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        private static String captureDirectStack() {
            try {
                return StackWalker.getInstance().walk(stream -> stream
                        .filter(frame -> !isObserverFrame(frame.getClassName()))
                        .limit(16)
                        .map(frame -> frame.getClassName() + "#" + frame.getMethodName()
                                + ':' + frame.getLineNumber())
                        .collect(Collectors.joining(" <- ")));
            } catch (Throwable failure) {
                return "<stack unavailable: " + failure.getClass().getName() + ">";
            }
        }

        private static boolean isObserverFrame(String className) {
            return className.equals(StarsectorPrepatcherHooks.class.getName())
                    || className.startsWith(StarsectorPrepatcherHooks.class.getName() + '$')
                    || className.equals("com.fs.starfarer.campaign.econ.Market")
                    || className.startsWith("java.lang.reflect.")
                    || className.startsWith("jdk.internal.reflect.");
        }

        private static String csv(String value) {
            if (value == null) return "";
            boolean quote = false;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == ',' || c == '"' || c == '\r' || c == '\n') {
                    quote = true;
                    break;
                }
            }
            if (!quote) return value;
            return '"' + value.replace("\"", "\"\"") + '"';
        }

        private static String json(String value) {
            if (value == null) return "null";
            StringBuilder out = new StringBuilder(value.length() + 16).append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                        else out.append(c);
                    }
                }
            }
            return out.append('"').toString();
        }

        private static String decimal(double value) {
            if (!Double.isFinite(value)) return "0";
            return String.format(java.util.Locale.ROOT, "%.3f", value);
        }

        private static String unsignedHex(long value) {
            return "0x" + Long.toUnsignedString(value, 16);
        }
    }

    private static final class UnknownStackInterval {
        final AtomicInteger samples = new AtomicInteger();
        final AtomicInteger activeWriters = new AtomicInteger();
        final ConcurrentHashMap<String, LongAdder> stacks = new ConcurrentHashMap<>();
        volatile boolean sealed;

        boolean tryAcquireWriter() {
            activeWriters.incrementAndGet();
            if (!sealed) return true;
            activeWriters.decrementAndGet();
            return false;
        }

        void releaseWriter() {
            activeWriters.decrementAndGet();
        }

        void sealAndAwaitWriters() {
            sealed = true;
            int spins = 0;
            while (activeWriters.get() != 0) {
                if (spins++ < 256) Thread.onSpinWait();
                else Thread.yield();
            }
        }
    }

    private static final class DirectMarketSiteStats {
        final DirectMarketMetadata metadata;
        final long samplePhase;
        final AtomicLong totalCalls = new AtomicLong();
        final LongAdder intervalCalls = new LongAdder();
        final LongAdder finiteAmounts = new LongAdder();
        final DoubleAdder amountSum = new DoubleAdder();
        final LongAdder sampledCalls = new LongAdder();
        final LongAdder sampledInclusiveNanos = new LongAdder();
        final LongAdder exceptions = new LongAdder();
        final LongAdder recursiveCalls = new LongAdder();
        final LongAdder zeroAmounts = new LongAdder();
        final LongAdder negativeAmounts = new LongAdder();
        final LongAdder nanAmounts = new LongAdder();
        final LongAdder nullMarkets = new LongAdder();
        final AtomicLong[] uniqueWords = {
                new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()
        };
        final ConcurrentHashMap<String, LongAdder> stackSamples = new ConcurrentHashMap<>();
        final LongAdder overflowStackSamples = new LongAdder();
        volatile boolean metadataWritten;

        DirectMarketSiteStats(DirectMarketMetadata metadata) {
            this.metadata = metadata;
            this.samplePhase = metadata.siteId ^ (metadata.siteId >>> 32);
        }

        static DirectMarketSiteStats overflow() {
            DirectMarketSiteStats result = new DirectMarketSiteStats(
                    new DirectMarketMetadata(0L,
                            "<site-overflow>", "<site-overflow>",
                            "<site-overflow>", "", "<site-overflow>",
                            "<site-overflow>", "<site-overflow>", "",
                            -1, -1, "UNKNOWN", "", -1, "<site-overflow>"));
            result.metadataWritten = true;
            return result;
        }

        void observeMarketIdentity(int identityHash) {
            int mixed = identityHash;
            mixed ^= mixed >>> 16;
            mixed *= 0x7feb352d;
            mixed ^= mixed >>> 15;
            int bucket = mixed & 255;
            AtomicLong word = uniqueWords[bucket >>> 6];
            long bit = 1L << (bucket & 63);
            long current;
            do {
                current = word.get();
                if ((current & bit) != 0L) return;
            } while (!word.compareAndSet(current, current | bit));
        }

        void recordStack(String stack, int maxStacks) {
            LongAdder existing = stackSamples.get(stack);
            if (existing != null) {
                existing.increment();
                return;
            }
            if (stackSamples.size() >= maxStacks) {
                overflowStackSamples.increment();
                return;
            }
            LongAdder candidate = new LongAdder();
            LongAdder raced = stackSamples.putIfAbsent(stack, candidate);
            (raced == null ? candidate : raced).increment();
        }

        DirectMarketIntervalSnapshot snapshot() {
            long calls = intervalCalls.sumThenReset();
            long sampled = sampledCalls.sumThenReset();
            long sampledNanos = sampledInclusiveNanos.sumThenReset();
            long errors = exceptions.sumThenReset();
            long recursive = recursiveCalls.sumThenReset();
            long finite = finiteAmounts.sumThenReset();
            double amountTotal = amountSum.sumThenReset();
            if (finite == 0L) amountTotal = 0.0;
            long zero = zeroAmounts.sumThenReset();
            long negative = negativeAmounts.sumThenReset();
            long nan = nanAmounts.sumThenReset();
            long nulls = nullMarkets.sumThenReset();
            return new DirectMarketIntervalSnapshot(this, calls, totalCalls.get(), sampled,
                    sampledNanos, errors, recursive, finite, amountTotal,
                    zero, negative, nan, nulls, approximateUniqueMarkets());
        }

        int approximateUniqueMarkets() {
            int bits = 0;
            for (AtomicLong word : uniqueWords) bits += Long.bitCount(word.get());
            if (bits <= 0) return 0;
            if (bits >= 256) return 256;
            return (int) Math.round(-256.0 * Math.log((256.0 - bits) / 256.0));
        }

        void appendStackRows(StringBuilder output, String timestamp) {
            for (Map.Entry<String, LongAdder> entry : stackSamples.entrySet()) {
                long samples = entry.getValue().sumThenReset();
                if (samples <= 0L) continue;
                output.append(DirectMarketObserver.csv(timestamp)).append(',')
                        .append(DirectMarketObserver.csv(
                                DirectMarketObserver.unsignedHex(metadata.siteId))).append(',')
                        .append(samples).append(',')
                        .append(DirectMarketObserver.csv(entry.getKey())).append('\n');
            }
            long overflow = overflowStackSamples.sumThenReset();
            if (overflow > 0L) {
                output.append(DirectMarketObserver.csv(timestamp)).append(',')
                        .append(DirectMarketObserver.csv(
                                DirectMarketObserver.unsignedHex(metadata.siteId))).append(',')
                        .append(overflow).append(',')
                        .append(DirectMarketObserver.csv("<additional stack signatures>"))
                        .append('\n');
            }
        }
    }

    private static final class DirectMarketMetadata {
        final long siteId;
        final String modId;
        final String modName;
        final String modDirectory;
        final String jarName;
        final String source;
        final String className;
        final String method;
        final String descriptor;
        final int line;
        final int ordinal;
        final String amountSource;
        final String callOwner;
        final int opcode;
        final String raw;

        DirectMarketMetadata(long siteId, String modId, String modName,
                             String modDirectory, String jarName,
                             String source, String className, String method,
                             String descriptor, int line, int ordinal, String amountSource,
                             String callOwner, int opcode, String raw) {
            this.siteId = siteId;
            this.modId = modId;
            this.modName = modName;
            this.modDirectory = modDirectory;
            this.jarName = jarName;
            this.source = source;
            this.className = className;
            this.method = method;
            this.descriptor = descriptor;
            this.line = line;
            this.ordinal = ordinal;
            this.amountSource = amountSource;
            this.callOwner = callOwner;
            this.opcode = opcode;
            this.raw = raw;
        }

        static DirectMarketMetadata parse(long siteId, String metadata) {
            String raw = metadata == null ? "" : metadata;
            String[] fields = raw.split("\u001f", -1);
            if (fields.length == 14 && "2".equals(fields[0])) {
                return new DirectMarketMetadata(siteId,
                        fields[2], fields[3], fields[4], fields[5], fields[1],
                        fields[6], fields[7], fields[8], parseInt(fields[9], -1),
                        parseInt(fields[10], -1), fields[11], fields[12],
                        parseInt(fields[13], -1), raw);
            }
            if (fields.length == 10 && "1".equals(fields[0])) {
                String modId = modIdFromSource(fields[1]);
                return new DirectMarketMetadata(siteId, modId, modId, modId,
                        jarNameFromSource(fields[1]), fields[1], fields[2], fields[3], fields[4],
                        parseInt(fields[5], -1), parseInt(fields[6], -1), fields[7], fields[8],
                        parseInt(fields[9], -1), raw);
            }
            return new DirectMarketMetadata(siteId,
                    "<malformed>", "<malformed>", "<malformed>", "",
                    "<malformed>", "<malformed>", "<malformed>", "",
                    -1, -1, "UNKNOWN", "", -1, raw);
        }

        private static String modIdFromSource(String source) {
            String normalized = source == null ? "" : source.replace('\\', '/');
            int marker = normalized.indexOf("/mods/");
            int start = marker >= 0 ? marker + 6
                    : (normalized.startsWith("mods/") ? 5 : -1);
            if (start < 0) return "unknown";
            int end = normalized.indexOf('/', start);
            return end < 0 ? normalized.substring(start) : normalized.substring(start, end);
        }

        private static String jarNameFromSource(String source) {
            String normalized = source == null ? "" : source.replace('\\', '/');
            int slash = normalized.lastIndexOf('/');
            String tail = slash < 0 ? normalized : normalized.substring(slash + 1);
            return tail.toLowerCase(java.util.Locale.ROOT).endsWith(".jar") ? tail : "";
        }

        private static int parseInt(String value, int fallback) {
            try { return Integer.parseInt(value); }
            catch (RuntimeException ignored) { return fallback; }
        }
    }

    private static final class DirectMarketIntervalSnapshot {
        final DirectMarketSiteStats stats;
        final long calls;
        final long cumulativeCalls;
        final long sampledCalls;
        final long sampledNanos;
        final long exceptions;
        final long recursiveCalls;
        final long finiteAmounts;
        final double amountSum;
        final long zeroAmounts;
        final long negativeAmounts;
        final long nanAmounts;
        final long nullMarkets;
        final int approxUniqueMarkets;

        DirectMarketIntervalSnapshot(DirectMarketSiteStats stats, long calls,
                                     long cumulativeCalls, long sampledCalls,
                                     long sampledNanos, long exceptions,
                                     long recursiveCalls, long finiteAmounts,
                                     double amountSum, long zeroAmounts,
                                     long negativeAmounts, long nanAmounts,
                                     long nullMarkets, int approxUniqueMarkets) {
            this.stats = stats;
            this.calls = calls;
            this.cumulativeCalls = cumulativeCalls;
            this.sampledCalls = sampledCalls;
            this.sampledNanos = sampledNanos;
            this.exceptions = exceptions;
            this.recursiveCalls = recursiveCalls;
            this.finiteAmounts = finiteAmounts;
            this.amountSum = amountSum;
            this.zeroAmounts = zeroAmounts;
            this.negativeAmounts = negativeAmounts;
            this.nanAmounts = nanAmounts;
            this.nullMarkets = nullMarkets;
            this.approxUniqueMarkets = approxUniqueMarkets;
        }

        long estimatedInclusiveNanos() {
            if (sampledCalls <= 0L || calls <= 0L || sampledNanos <= 0L) return 0L;
            double estimate = ((double) sampledNanos * calls) / sampledCalls;
            if (estimate >= Long.MAX_VALUE) return Long.MAX_VALUE;
            return Math.max(0L, Math.round(estimate));
        }
    }


    private static final class TextReadScratch {
        private static final int BUFFER_SIZE = 64 * 1024;
        private static final int RING_SIZE = 4;
        final char[][] buffers = new char[RING_SIZE][];
        int depth;

        char[] acquire() {
            int slot = depth++;
            if (slot >= RING_SIZE) return new char[BUFFER_SIZE];
            char[] buffer = buffers[slot];
            if (buffer == null) buffers[slot] = buffer = new char[BUFFER_SIZE];
            return buffer;
        }

        void release() {
            if (depth > 0) depth--;
        }
    }

    private static final class Scratch {
        final ArrayList<ScratchFrame> frames = new ArrayList<>(2);
        int scopeDepth;
        int maxDepthSinceIdle;
        long trailingFramesLastUseNanos = Long.MIN_VALUE;
        boolean removeWhenIdle;

        ScratchFrame frameAt(int index) {
            while (frames.size() <= index) frames.add(new ScratchFrame());
            return frames.get(index);
        }

        ScratchFrame scopeFrame() {
            return frameAt(Math.max(0, scopeDepth - 1));
        }
    }

    private static final class ScratchFrame {
        ArrayList<Object> entityList = new ArrayList<>(1024);
        int entityListHighWater = 1024;
        ArrayList<Object> hitList = new ArrayList<>(1024);
        int hitListHighWater = 1024;
        HashSet<Object> classSet = new HashSet<>(16);
        int classSetHighWater = 16;
        IdentityHashMap<Object, Boolean> identityMembership = new IdentityHashMap<>(2048);
        int identityMembershipHighWater = 2048;
        HashSet<Object> equalityMembership = new HashSet<>(2048);
        int equalityMembershipHighWater = 2048;
        HashSet<Object> containsSet = new HashSet<>(512);
        int containsSetHighWater = 512;
        Collection<?> containsSource;
        int containsSourceSize = -1;
        ArrayList<Object> labelCandidates = new ArrayList<>(256);
        int labelCandidatesHighWater = 256;
        final Vector2f hitPoint = new Vector2f();
        final Vector2f[] arrowVectors = new Vector2f[] {
                new Vector2f(), new Vector2f(), new Vector2f(), new Vector2f(),
                new Vector2f(), new Vector2f(), new Vector2f(), new Vector2f()
        };
        int arrowVectorIndex;
        ArrayList<SnapshotList> campaignSnapshots = new ArrayList<>(3);
        int campaignSnapshotIndex;
        ArrayList<ArrayList<Object>> pooledLists = new ArrayList<>(8);
        ArrayList<Integer> pooledListHighWaters = new ArrayList<>(8);
        int pooledListIndex;
        ArrayList<HashSet<Object>> pooledSets = new ArrayList<>(4);
        ArrayList<Integer> pooledSetHighWaters = new ArrayList<>(4);
        int pooledSetIndex;
        int listHighWater = 1024;
        int setHighWater = 2048;
        int identityHighWater = 2048;
        boolean listOversizedTouched;
        boolean setOversizedTouched;
        boolean identityOversizedTouched;
        long oversizedLastUseNanos = Long.MIN_VALUE;

        ArrayList<Object> pooledList() {
            PrepatcherConfig c = config;
            int limit = c == null ? 32 : c.scratchTrimMaxPooledCollections;
            if (pooledListIndex >= limit) return new ArrayList<>();
            while (pooledLists.size() <= pooledListIndex) {
                pooledLists.add(new ArrayList<>());
                pooledListHighWaters.add(0);
            }
            ArrayList<Object> result = pooledLists.get(pooledListIndex++);
            if (!result.isEmpty()) result.clear();
            return result;
        }

        HashSet<Object> pooledSet() {
            PrepatcherConfig c = config;
            int limit = c == null ? 32 : c.scratchTrimMaxPooledCollections;
            if (pooledSetIndex >= limit) return new HashSet<>();
            while (pooledSets.size() <= pooledSetIndex) {
                pooledSets.add(new HashSet<>());
                pooledSetHighWaters.add(0);
            }
            HashSet<Object> result = pooledSets.get(pooledSetIndex++);
            if (!result.isEmpty()) result.clear();
            return result;
        }

        SnapshotList campaignSnapshotAt(int index) {
            while (campaignSnapshots.size() <= index) {
                campaignSnapshots.add(new SnapshotList());
            }
            return campaignSnapshots.get(index);
        }

        void observeRetainedList(Collection<?> list) {
            if (list == null) return;
            int size = list.size();
            if (list == entityList) entityListHighWater = Math.max(entityListHighWater, size);
            else if (list == hitList) hitListHighWater = Math.max(hitListHighWater, size);
            else if (list == labelCandidates) {
                labelCandidatesHighWater = Math.max(labelCandidatesHighWater, size);
            } else {
                for (int i = 0; i < pooledLists.size(); i++) {
                    if (list == pooledLists.get(i)) {
                        pooledListHighWaters.set(i,
                                Math.max(pooledListHighWaters.get(i), size));
                        break;
                    }
                }
            }
            observeListSize(size);
        }

        void observeRetainedSet(Collection<?> set) {
            if (set == null) return;
            int size = set.size();
            if (set == classSet) classSetHighWater = Math.max(classSetHighWater, size);
            else if (set == equalityMembership) {
                equalityMembershipHighWater = Math.max(equalityMembershipHighWater, size);
            } else if (set == containsSet) {
                containsSetHighWater = Math.max(containsSetHighWater, size);
            } else {
                for (int i = 0; i < pooledSets.size(); i++) {
                    if (set == pooledSets.get(i)) {
                        pooledSetHighWaters.set(i,
                                Math.max(pooledSetHighWaters.get(i), size));
                        break;
                    }
                }
            }
            observeSetSize(size);
        }

        void observeListSize(int size) {
            if (size > listHighWater) listHighWater = size;
            PrepatcherConfig c = config;
            if (c != null && size > c.scratchTrimMaxListCapacity) {
                listOversizedTouched = true;
            }
        }

        void observeSetSize(int size) {
            if (size > setHighWater) setHighWater = size;
            PrepatcherConfig c = config;
            if (c != null && size > c.scratchTrimMaxSetEntries) {
                setOversizedTouched = true;
            }
        }

        void observeIdentitySize(int size) {
            identityMembershipHighWater = Math.max(identityMembershipHighWater, size);
            if (size > identityHighWater) identityHighWater = size;
            PrepatcherConfig c = config;
            if (c != null && size > c.scratchTrimMaxIdentityEntries) {
                identityOversizedTouched = true;
            }
        }

        void refreshHighWater() {
            listHighWater = Math.max(entityListHighWater, hitListHighWater);
            listHighWater = Math.max(listHighWater, labelCandidatesHighWater);
            for (SnapshotList snapshot : campaignSnapshots) {
                listHighWater = Math.max(listHighWater, snapshot.retainedCapacity());
            }
            for (Integer peak : pooledListHighWaters) {
                listHighWater = Math.max(listHighWater, peak);
            }
            setHighWater = Math.max(classSetHighWater, equalityMembershipHighWater);
            setHighWater = Math.max(setHighWater, containsSetHighWater);
            for (Integer peak : pooledSetHighWaters) {
                setHighWater = Math.max(setHighWater, peak);
            }
            identityHighWater = Math.max(identityHighWater, identityMembershipHighWater);
        }

        private boolean consumeOversizedUse() {
            boolean touched = listOversizedTouched || setOversizedTouched
                    || identityOversizedTouched;
            listOversizedTouched = false;
            setOversizedTouched = false;
            identityOversizedTouched = false;
            for (SnapshotList snapshot : campaignSnapshots) {
                touched |= snapshot.consumeOversizedTouch();
            }
            return touched;
        }

        boolean shouldTrim(PrepatcherConfig c, long now) {
            refreshHighWater();
            boolean oversized = listHighWater > c.scratchTrimMaxListCapacity
                    || setHighWater > c.scratchTrimMaxSetEntries
                    || identityHighWater > c.scratchTrimMaxIdentityEntries
                    || pooledLists.size() > c.scratchTrimMaxPooledCollections
                    || pooledSets.size() > c.scratchTrimMaxPooledCollections;
            if (!oversized || oversizedLastUseNanos == Long.MIN_VALUE) return false;
            return now - oversizedLastUseNanos >= millisToNanos(c.scratchTrimGraceMs);
        }

        long retainedListCapacityEstimate() {
            long total = entityListHighWater + hitListHighWater + labelCandidatesHighWater;
            total += 3L + 8L + 4L; // known initial capacities of retained list containers
            for (SnapshotList snapshot : campaignSnapshots) total += snapshot.retainedCapacity();
            for (Integer peak : pooledListHighWaters) total += peak;
            return total;
        }

        long retainedSetCapacityEstimate() {
            long total = classSetHighWater + equalityMembershipHighWater + containsSetHighWater;
            for (Integer peak : pooledSetHighWaters) total += peak;
            return total;
        }

        void clear(PrepatcherConfig c, long now) {
            refreshHighWater();
            if (c != null && c.scratchTrimEnabled && consumeOversizedUse()) {
                oversizedLastUseNanos = now;
            }
            if (!entityList.isEmpty()) entityList.clear();
            if (!hitList.isEmpty()) hitList.clear();
            if (!classSet.isEmpty()) classSet.clear();
            clearIdentityMembership(identityMembership);
            if (!equalityMembership.isEmpty()) equalityMembership.clear();
            if (!containsSet.isEmpty()) containsSet.clear();
            containsSource = null;
            containsSourceSize = -1;
            if (!labelCandidates.isEmpty()) labelCandidates.clear();
            arrowVectorIndex = 0;
            for (int i = 0; i < campaignSnapshotIndex; i++) {
                campaignSnapshots.get(i).clearSnapshot();
            }
            campaignSnapshotIndex = 0;
            for (int i = 0; i < pooledListIndex && i < pooledLists.size(); i++) {
                ArrayList<Object> list = pooledLists.get(i);
                if (!list.isEmpty()) list.clear();
            }
            pooledListIndex = 0;
            for (int i = 0; i < pooledSetIndex && i < pooledSets.size(); i++) {
                HashSet<Object> set = pooledSets.get(i);
                if (!set.isEmpty()) set.clear();
            }
            pooledSetIndex = 0;
        }
    }

    private record ScratchGaugeSnapshot(
            int frameCount,
            int pooledListCount,
            int pooledSetCount,
            long retainedListCapacity,
            long retainedSetCapacity,
            long retainedIdentityCapacity) {
        static final ScratchGaugeSnapshot EMPTY =
                new ScratchGaugeSnapshot(0, 0, 0, 0L, 0L, 0L);
    }

    /** Array-backed, reusable read-only snapshot used only by verified loops. */
    private static final class SnapshotList extends AbstractList<Object> implements RandomAccess {
        private Object[] elements = new Object[0];
        private int size;
        private final ArrayList<SnapshotIterator> iterators = new ArrayList<>(2);
        private int iteratorIndex;
        private boolean oversizedTouched;

        void copyFrom(Collection<?> source) {
            int oldSize = size;
            try {
                int newSize = source.size();
                Object[] current = elements;
                Object[] copied = source.toArray(current);
                boolean reused = copied == current;
                int copiedSize = reused ? newSize : copied.length;
                if (reused && newSize < oldSize) {
                    Arrays.fill(current, newSize, oldSize, null);
                }
                elements = copied;
                size = copiedSize;
                PrepatcherConfig c = config;
                if (c != null && copiedSize > c.scratchTrimMaxListCapacity) {
                    // Snapshot peak is observed before scope close even if later cleared.
                    oversizedTouched = true;
                }
                iteratorIndex = 0;
                modCount++;
            } catch (Throwable ex) {
                // A custom Collection may fail after partially populating the
                // supplied array. Never let that exceptional path pin campaign
                // objects in the process-lifetime ThreadLocal.
                if (elements != null) Arrays.fill(elements, null);
                size = 0;
                iteratorIndex = 0;
                modCount++;
                throw propagate(ex);
            }
        }

        void clearSnapshot() {
            if (size > 0) Arrays.fill(elements, 0, size, null);
            size = 0;
            iteratorIndex = 0;
            modCount++;
        }

        int retainedCapacity() {
            return elements == null ? 0 : elements.length;
        }

        boolean consumeOversizedTouch() {
            boolean result = oversizedTouched;
            oversizedTouched = false;
            return result;
        }

        @Override
        public Object get(int index) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
            return elements[index];
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public Iterator<Object> iterator() {
            while (iterators.size() <= iteratorIndex) {
                iterators.add(new SnapshotIterator());
            }
            SnapshotIterator result = iterators.get(iteratorIndex++);
            result.reset();
            return result;
        }

        private final class SnapshotIterator implements Iterator<Object> {
            private int cursor;
            private int expectedModCount;

            void reset() {
                cursor = 0;
                expectedModCount = modCount;
            }

            @Override
            public boolean hasNext() {
                return cursor != size;
            }

            @Override
            public Object next() {
                if (expectedModCount != modCount) throw new ConcurrentModificationException();
                if (cursor >= size) throw new NoSuchElementException();
                return elements[cursor++];
            }
        }

        @SuppressWarnings("unchecked")
        private static <T extends Throwable> RuntimeException propagate(Throwable ex) throws T {
            throw (T) ex;
        }
    }
}
