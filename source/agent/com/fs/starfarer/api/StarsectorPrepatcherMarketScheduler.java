package com.fs.starfarer.api;

import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.ConstructionQueue;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.campaign.CampaignEngine;
import com.fs.starfarer.campaign.econ.Economy;
import com.fs.starfarer.campaign.econ.Market;
import com.starsector.prepatcher.agent.PrepatcherConfig;
import com.starsector.prepatcher.agent.PrepatcherLog;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/** Market advance scheduler, construction-queue state machine, and market advance observation. */
final class StarsectorPrepatcherMarketScheduler {

  static final int MARKET_ORIGIN_NONE = 0;
  static final int MARKET_ORIGIN_SCHEDULED = 1;
  static final int MARKET_ORIGIN_SCHEDULER_FALLBACK = 2;
  static final int MARKET_ORIGIN_SAVE_FLUSH = 3;
  static final int MARKET_ORIGIN_DIRECT_CALL_SITE = 4;
  static final int MARKET_ORIGIN_PLANET_CONDITION_SCHEDULED = 5;
  static final int MARKET_ORIGIN_PLANET_CONDITION_FALLBACK = 6;
  static final ThreadLocal<MarketAdvanceObservationContext> MARKET_ADVANCE_CONTEXT =
      ThreadLocal.withInitial(MarketAdvanceObservationContext::new);
  static volatile DirectMarketObserver DIRECT_MARKET_OBSERVER;
  static final int MARKET_SCHEDULER_COMPONENT_ENGINE = 1;
  static final int MARKET_SCHEDULER_COMPONENT_ECONOMY = 2;
  static final int MARKET_SCHEDULER_COMPONENT_ENTITY = 4;
  static final int MARKET_SCHEDULER_COMPONENT_SAVE = 8;
  static final int MARKET_SCHEDULER_COMPONENT_BATCH_PROTOCOL = 16;
  static final int MARKET_SCHEDULER_COMPONENT_MARKET_SEMANTICS = 32;
  static final int MARKET_SCHEDULER_COMPONENT_MILITARY_BASE = 64;
  static final int MARKET_SCHEDULER_COMPONENT_LIONS_GUARD = 128;
  static final int MARKET_SCHEDULER_COMPONENT_RECENT_UNREST = 256;
  static final int MARKET_SCHEDULER_COMPONENT_BASE_INDUSTRY = 512;
  static final int MARKET_SCHEDULER_COMPONENT_CONSTRUCTION_QUEUE = 1024;
  static final int CONSTRUCTION_REASON_QUEUE_NON_EMPTY = 1;
  static final int CONSTRUCTION_REASON_INDUSTRY_BUILDING = 2;
  static final int CONSTRUCTION_REASON_INDUSTRY_UPGRADING = 4;
  static final int CONSTRUCTION_REASON_QUEUE_ITEMS_UNKNOWN = 8;
  static final int CONSTRUCTION_REASON_INDUSTRIES_UNKNOWN = 16;
  static final int CONSTRUCTION_REASON_PROBE_FAILURE = 32;
  static final int CONSTRUCTION_REASON_MUTATION_RACE = 64;
  /**
   * A virtual Industry.isBuilding() signal from BaseIndustry while the actual
   * BaseIndustry.building field is false. PopulationAndInfrastructure uses
   * this pattern for market-size growth, so it is telemetry-only.
   */
  static final int CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW = 128;
  /** Reasons observed by the construction probe, including diagnostic-only signals. */
  static final int CONSTRUCTION_REASON_OBSERVED_MASK =
      CONSTRUCTION_REASON_QUEUE_NON_EMPTY | CONSTRUCTION_REASON_INDUSTRY_BUILDING
          | CONSTRUCTION_REASON_INDUSTRY_UPGRADING | CONSTRUCTION_REASON_QUEUE_ITEMS_UNKNOWN
          | CONSTRUCTION_REASON_INDUSTRIES_UNKNOWN | CONSTRUCTION_REASON_PROBE_FAILURE
          | CONSTRUCTION_REASON_MUTATION_RACE | CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW;
  /**
   * Reasons that require exact full-rate market advancement. isUpgrading() and
   * a BaseIndustry virtual-building signal without its raw building field are
   * intentionally diagnostic-only. Vanilla PopulationAndInfrastructure uses
   * both signals for market-size growth, not Industry construction.
   */
  static final int CONSTRUCTION_REASON_ACTIVE_MASK = CONSTRUCTION_REASON_QUEUE_NON_EMPTY
      | CONSTRUCTION_REASON_INDUSTRY_BUILDING | CONSTRUCTION_REASON_QUEUE_ITEMS_UNKNOWN
      | CONSTRUCTION_REASON_INDUSTRIES_UNKNOWN | CONSTRUCTION_REASON_PROBE_FAILURE | CONSTRUCTION_REASON_MUTATION_RACE;
  static final int MARKET_SCHEDULER_COMPONENT_ALL =
      MARKET_SCHEDULER_COMPONENT_ENGINE | MARKET_SCHEDULER_COMPONENT_ECONOMY | MARKET_SCHEDULER_COMPONENT_ENTITY
          | MARKET_SCHEDULER_COMPONENT_SAVE | MARKET_SCHEDULER_COMPONENT_BATCH_PROTOCOL
          | MARKET_SCHEDULER_COMPONENT_MARKET_SEMANTICS | MARKET_SCHEDULER_COMPONENT_MILITARY_BASE
          | MARKET_SCHEDULER_COMPONENT_LIONS_GUARD | MARKET_SCHEDULER_COMPONENT_RECENT_UNREST
          | MARKET_SCHEDULER_COMPONENT_BASE_INDUSTRY | MARKET_SCHEDULER_COMPONENT_CONSTRUCTION_QUEUE;
  static final AtomicInteger MARKET_SCHEDULER_COMPONENTS = new AtomicInteger();
  static volatile boolean marketSchedulerReady;
  static final ThreadLocal<MarketSchedulerTick> MARKET_SCHEDULER_TICK =
      ThreadLocal.withInitial(MarketSchedulerTick::new);
  static final ThreadLocal<MarketSchedulerBatch> MARKET_SCHEDULER_BATCH =
      ThreadLocal.withInitial(MarketSchedulerBatch::new);
  static final ThreadLocal<MarketAdvanceBatchStack> MARKET_ADVANCE_BATCH_STACK =
      ThreadLocal.withInitial(MarketAdvanceBatchStack::new);
  static final ThreadLocal<MarketAdvanceInvocationContext> MARKET_ADVANCE_INVOCATION =
      ThreadLocal.withInitial(MarketAdvanceInvocationContext::new);
  static final ThreadLocal<ConstructionProbe> CONSTRUCTION_PROBE = ThreadLocal.withInitial(ConstructionProbe::new);
  static volatile VarHandle baseIndustryBuildingHandle;
  static volatile boolean baseIndustryBuildingHandleResolved;
  static volatile WeakIdentityMap<MarketAPI, MarketScheduleState> MARKET_SCHEDULER_STATES = new WeakIdentityMap<>();
  static volatile WeakIdentityMap<ConstructionQueue, WeakReference<MarketAPI>> CONSTRUCTION_QUEUE_OWNERS =
      new WeakIdentityMap<>();
  static volatile ConstructionDiagnostics CONSTRUCTION_DIAGNOSTICS;
  static volatile long marketSchedulerSimulationTick;
  static volatile long marketSchedulerRenderBatch;
  static final AtomicLong MARKET_SCHEDULER_DEBT_SEQUENCE = new AtomicLong();
  static final AtomicLong MARKET_DELIVERY_SEQUENCE = new AtomicLong();
  /**
   * One-time per-runtime-class eligibility check for the direct dormant
   * BaseIndustry fast path. The per-frame wrapper never reflects or touches a
   * global identity map. Subclasses that override advance(), isDisrupted(), or
   * getDisruptedKey() retain the exact vanilla callback path.
   */
  static final ClassValue<Boolean> BASE_INDUSTRY_DORMANT_ELIGIBLE = new ClassValue<>() {
    @Override
    protected Boolean computeValue(Class<?> type) {
      try {
        if (!BaseIndustry.class.isAssignableFrom(type)) return Boolean.FALSE;
        return type.getMethod("advance", float.class).getDeclaringClass() == BaseIndustry.class
            && type.getMethod("isDisrupted").getDeclaringClass() == BaseIndustry.class
            && type.getMethod("getDisruptedKey").getDeclaringClass() == BaseIndustry.class;
      } catch (ReflectiveOperationException | SecurityException ex) {
        return Boolean.FALSE;
      }
    }
  };
  static void configureDiagnostics(PrepatcherConfig optimizerConfig, Path root) {
    CONSTRUCTION_DIAGNOSTICS = null;
    if (optimizerConfig.marketConstructionDiagnostics) {
      try {
        ConstructionDiagnostics diagnostics = new ConstructionDiagnostics(optimizerConfig, root);
        CONSTRUCTION_DIAGNOSTICS = diagnostics;
        diagnostics.start();
      } catch (Throwable failure) {
        CONSTRUCTION_DIAGNOSTICS = null;
        PrepatcherLog.error("Market construction diagnostics initialization failed; "
            + "scheduler behavior is unchanged and CSV sampling is disabled.", failure);
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
  }
  /** Called only on the first full BaseIndustry.advance() for an instance. */
  public static boolean isBaseIndustryDormantFastPathEligible(Object industry) {
    return industry != null && BASE_INDUSTRY_DORMANT_ELIGIBLE.get(industry.getClass());
  }
  // ---------------------------------------------------------------------
  // Exact Market.advance batch history
  // ---------------------------------------------------------------------

  /** Binds the top deferred-history frame to one concrete Market.advance invocation. */
  public static void beginMarketAdvanceInvocation(MarketAPI market) {
    MarketAdvanceInvocationContext invocation = MARKET_ADVANCE_INVOCATION.get();
    invocation.depth++;
    MarketAdvanceBatchFrame frame = MARKET_ADVANCE_BATCH_STACK.get().current();
    if (frame != null && frame.visible && frame.market == market && frame.boundInvocationDepth == 0) {
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
      throw new IndexOutOfBoundsException("runIndex=" + runIndex + ", runCount=" + frame.runCount);
    }
    return frame.runAmounts[runIndex];
  }
  public static int currentMarketAdvanceBatchRunRepeats(MarketAPI market, int runIndex) {
    MarketAdvanceBatchFrame frame = requireCurrentMarketAdvanceBatch(market);
    if (runIndex < 0 || runIndex >= frame.runCount) {
      throw new IndexOutOfBoundsException("runIndex=" + runIndex + ", runCount=" + frame.runCount);
    }
    return frame.runRepeats[runIndex];
  }
  static MarketAdvanceBatchFrame requireCurrentMarketAdvanceBatch(MarketAPI market) {
    if (!hasCurrentMarketAdvanceBatch(market)) {
      throw new IllegalStateException("No Market.advance batch for the current invocation");
    }
    return MARKET_ADVANCE_BATCH_STACK.get().current();
  }
  /** Called by the three transformed vanilla component wrappers. */
  public static void recordMarketComponentReplayedStep(int component) {
    switch (component) {
      case 1 -> StarsectorPrepatcherMetrics.MILITARY_BASE_REPLAYED_STEPS.increment();
      case 2 -> StarsectorPrepatcherMetrics.LIONS_GUARD_REPLAYED_STEPS.increment();
      case 3 -> StarsectorPrepatcherMetrics.RECENT_UNREST_REPLAYED_STEPS.increment();
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
      StarsectorPrepatcherHooks.throwUnchecked(failure);
      return false;
    }
  }
  /** Associates a ConstructionQueue with its weak Market owner without changing serialization. */
  public static ConstructionQueue registerConstructionQueueOwner(ConstructionQueue queue, MarketAPI market) {
    if (queue == null || market == null) return queue;
    try {
      WeakReference<MarketAPI> existing = CONSTRUCTION_QUEUE_OWNERS.get(queue);
      if (existing != null && existing.get() == market) return queue;
      CONSTRUCTION_QUEUE_OWNERS.put(queue, new WeakReference<>(market));
    } catch (Throwable ignored) {
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_STATE_FAILURES.increment();
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
      StarsectorPrepatcherHooks.throwUnchecked(failure);
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
  /**
   * Exact pending replay barrier before a synchronous localized UI refresh.
   * Unlike a mutation barrier, this does not dirty construction-state caches.
   */
  public static void flushPendingMarketBeforeUiRefresh(MarketAPI market) {
    if (market == null || !marketSchedulerReady) return;
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c == null || !c.marketScheduler) return;
    MarketScheduleState state;
    try {
      state = MARKET_SCHEDULER_STATES.get(market);
    } catch (Throwable ignored) {
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_STATE_FAILURES.increment();
      return;
    }
    if (state == null) return;
    boolean pending;
    synchronized (state) {
      if (state.inAdvance || state.disabled) return;
      pending = state.pendingSteps > 0;
    }
    if (pending) {
      deliverPendingExact(market, state, MARKET_ORIGIN_SCHEDULER_FALLBACK, false);
    }
  }
  /** Exact pending replay barrier inserted before construction-structure mutation. */
  public static void flushPendingMarketBeforeMutation(MarketAPI market) {
    if (market == null || !marketSchedulerReady) return;
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c == null || !c.marketScheduler) return;
    MarketScheduleState state;
    try {
      state = MARKET_SCHEDULER_STATES.get(market);
    } catch (Throwable ignored) {
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_STATE_FAILURES.increment();
      return;
    }
    if (state == null) return;
    boolean pending;
    synchronized (state) {
      if (state.inAdvance || state.disabled) return;
      pending = state.pendingSteps > 0;
    }
    if (pending) {
      StarsectorPrepatcherMetrics.CONSTRUCTION_BOUNDARY_EXACT_REPLAYS.increment();
      deliverPendingExact(market, state, MARKET_ORIGIN_SCHEDULER_FALLBACK, false);
    }
    // The hook runs immediately before the mutation. Leave the state dirty
    // so the next scheduler input scans the post-mutation structure once.
    markConstructionStateDirty(state);
  }
  static void markConstructionStateDirty(MarketScheduleState state) {
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
  public static void observeDirectMarketAdvance(MarketAPI market, float amount, long siteId, String metadata) {
    DirectMarketObserver observer = DIRECT_MARKET_OBSERVER;
    MarketAdvanceObservationContext context = MARKET_ADVANCE_CONTEXT.get();
    boolean recursive = context.depth > 0;
    DirectMarketCallToken token = null;
    if (observer != null) {
      try {
        token = observer.beginDirectCall(context, market, amount, siteId, metadata, recursive);
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
      advanceMarketImmediatelyWithPending(market, amount, MARKET_ORIGIN_DIRECT_CALL_SITE);
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
  static void invokeObservedMarketAdvance(MarketAPI market, float amount, int origin) {
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
    context.siteId = origin == MARKET_ORIGIN_DIRECT_CALL_SITE ? previousSiteId : 0L;
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
  static void recordDeliveredMarketAdvance(MarketAPI market, float amount, int origin) {
    // Publish only from this post-success path. The runtime owns a separate
    // weak identity generation table, so immediate/fallback callbacks are
    // visible even before a scheduler state exists for the market.
    if (market != null) {
      boolean publishToAoTD = true;
      try {
        if (!market.isInEconomy()) {
          boolean conditionOnly = market.isPlanetConditionMarketOnly();
          StarsectorPrepatcherAotdContract.recordAoTDNonEconomyDelivery(conditionOnly);
          publishToAoTD = false;
        }
      } catch (Throwable ignored) {
        // Classification is fail-open; an unexpected MarketAPI
        // implementation remains visible to the AoTD diagnostics.
      }
      if (publishToAoTD) {
        StarsectorPrepatcherAotdContract.publishAoTDMarketTimeDelivered(market, amount, origin);
      }
    }
    if (market == null) return;
    MarketScheduleState state;
    try {
      state = MARKET_SCHEDULER_STATES.get(market);
    } catch (Throwable ignored) {
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_STATE_FAILURES.increment();
      return;
    }
    if (state == null) return;
    synchronized (state) {
      state.deliveredCallbacks++;
      if (Float.isFinite(amount)) state.deliveredAmount += amount;
      state.lastDeliveredSequence = MARKET_DELIVERY_SEQUENCE.incrementAndGet();
      state.lastDeliveredOrigin = origin;
      state.lastDeliveredNanos = StarsectorPrepatcherCampaignCaches.NANO_CLOCK.getAsLong();
    }
  }
  /** Immediate path shared by scheduler fail-open and transformed core event call sites. */
  public static void advanceMarketSynchronized(MarketAPI market, float amount, int source) {
    if (market == null) throw new NullPointerException("market");
    int origin = source == 1 ? MARKET_ORIGIN_PLANET_CONDITION_FALLBACK : MARKET_ORIGIN_SCHEDULER_FALLBACK;
    advanceMarketImmediatelyWithPending(market, amount, origin);
  }
  static void advanceMarketImmediatelyWithPending(MarketAPI market, float amount, int origin) {
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
          hasPending = !state.disabled && !state.inAdvance && state.pendingSteps > 0;
        }
      }
    } catch (Throwable ignored) {
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_STATE_FAILURES.increment();
      state = null;
    }

    if (reentrant) {
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_REENTRANT_CALLS.increment();
      invokeObservedMarketAdvance(market, amount, origin);
      return;
    }

    if (hasPending && state != null) {
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_SYNCHRONIZED_ADVANCES.increment();
      if (origin == MARKET_ORIGIN_DIRECT_CALL_SITE) {
        StarsectorPrepatcherMetrics.MARKET_SCHEDULER_DIRECT_SYNCHRONIZATIONS.increment();
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
  static boolean updateConstructionMode(MarketAPI market, MarketScheduleState state) {
    return updateConstructionMode(market, state, false);
  }
  /**
   * Construction policy is mutation-driven. Structural barriers increment an
   * epoch; the expensive queue/industry scan runs only for a new epoch or a
   * rare safety audit. Save uses forceAudit=true.
   */
  static boolean updateConstructionMode(MarketAPI market, MarketScheduleState state, boolean forceAudit) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    long auditBatch = marketSchedulerRenderBatch;
    long observedEpoch;
    boolean dirtyScan;
    boolean safetyAudit;
    synchronized (state) {
      observedEpoch = state.constructionMutationEpoch;
      long last = state.lastConstructionAuditBatch;
      boolean auditDue = last == Long.MIN_VALUE || auditBatch < last
          || auditBatch - last >= (c == null ? 60 : c.marketSchedulerConstructionAuditBatches);
      dirtyScan = state.constructionAuditedEpoch != observedEpoch;
      safetyAudit = !forceAudit && !dirtyScan && auditDue;
      if (!(forceAudit || dirtyScan || auditDue)) {
        StarsectorPrepatcherMetrics.CONSTRUCTION_CACHED_DECISIONS.increment();
        return state.constructionFullRate;
      }
      state.lastConstructionAuditBatch = auditBatch;
    }

    StarsectorPrepatcherMetrics.CONSTRUCTION_SCANS.increment();
    if (forceAudit) StarsectorPrepatcherMetrics.CONSTRUCTION_FORCED_SCANS.increment();
    else if (dirtyScan) StarsectorPrepatcherMetrics.CONSTRUCTION_DIRTY_SCANS.increment();
    else if (safetyAudit) StarsectorPrepatcherMetrics.CONSTRUCTION_SAFETY_AUDIT_SCANS.increment();

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
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_POLICY_FAILURES.increment();
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
        StarsectorPrepatcherMetrics.CONSTRUCTION_MUTATION_RACES.increment();
        finalReasonMask = reasonMask | CONSTRUCTION_REASON_MUTATION_RACE;
        active = true;
      } else {
        finalReasonMask = reasonMask;
        state.constructionAuditedEpoch = observedEpoch;
      }
      state.constructionReasonMask = finalReasonMask;
      if (oldReasonMask != finalReasonMask) StarsectorPrepatcherMetrics.CONSTRUCTION_REASON_CHANGES.increment();
      if (oldActive != active) {
        state.constructionFullRate = active;
        StarsectorPrepatcherMetrics.CONSTRUCTION_AUDIT_STATE_CHANGES.increment();
        if (active) {
          StarsectorPrepatcherMetrics.CONSTRUCTION_MODE_ENTRIES.increment();
          StarsectorPrepatcherMetrics.CONSTRUCTION_AUDIT_FALSE_TO_TRUE.increment();
        } else {
          StarsectorPrepatcherMetrics.CONSTRUCTION_MODE_EXITS.increment();
          StarsectorPrepatcherMetrics.CONSTRUCTION_AUDIT_TRUE_TO_FALSE.increment();
        }
      }
      auditedEpoch = state.constructionAuditedEpoch;
      lastAuditBatch = state.lastConstructionAuditBatch;
    }

    if (diagnostics != null) {
      diagnostics.observe(market, probe, forceAudit ? "FORCED" : (dirtyScan ? "DIRTY" : "SAFETY_AUDIT"), observedEpoch,
          auditedEpoch, lastAuditBatch, oldActive, active, oldReasonMask, finalReasonMask);
    }
    return active;
  }
  static int scanConstructionState(MarketAPI market, ConstructionProbe probe) {
    int reasonMask = 0;
    ConstructionQueue queue = null;
    try {
      // Market.getConstructionQueue() already performs weak owner registration.
      // Do not repeat that synchronized weak-map write in the policy scanner.
      queue = market.getConstructionQueue();
      if (probe != null) probe.queueClass = queue == null ? "" : queue.getClass().getName();
      if (queue == null) {
        StarsectorPrepatcherMetrics.CONSTRUCTION_QUEUE_NULL_SCANS.increment();
      } else {
        List<?> items = queue.getItems();
        if (items == null) {
          StarsectorPrepatcherMetrics.CONSTRUCTION_QUEUE_ITEMS_NULL_SCANS.increment();
          reasonMask |= CONSTRUCTION_REASON_QUEUE_ITEMS_UNKNOWN;
          if (probe != null) probe.queueSize = -1;
        } else {
          int size = items.size();
          StarsectorPrepatcherMetrics.CONSTRUCTION_QUEUE_ITEMS_OBSERVED.add(size);
          updateMax(StarsectorPrepatcherMetrics.CONSTRUCTION_MAX_QUEUE_ITEMS, size);
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
        StarsectorPrepatcherMetrics.CONSTRUCTION_INDUSTRIES_NULL_SCANS.increment();
        reasonMask |= CONSTRUCTION_REASON_INDUSTRIES_UNKNOWN;
        if (probe != null) probe.industryCount = -1;
      } else {
        int size = industries.size();
        StarsectorPrepatcherMetrics.CONSTRUCTION_INDUSTRIES_SCANNED.add(size);
        updateMax(StarsectorPrepatcherMetrics.CONSTRUCTION_MAX_INDUSTRIES_SCANNED, size);
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
          int buildingReason = constructionBuildingReason(reportedBuilding, rawBuilding);
          boolean effectiveBuilding = (buildingReason & CONSTRUCTION_REASON_INDUSTRY_BUILDING) != 0;
          boolean reportedBuildingWithoutRaw =
              (buildingReason & CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW) != 0;
          if (effectiveBuilding) {
            reasonMask |= CONSTRUCTION_REASON_INDUSTRY_BUILDING;
            if (probe != null) {
              probe.captureBuildingIndustry(industry, i, rawBuilding);
            }
          }
          if (reportedBuildingWithoutRaw) {
            reasonMask |= CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW;
            if (probe != null) {
              probe.captureReportedBuildingWithoutRawIndustry(industry, i, rawBuilding);
            }
          }
          if (upgrading) {
            reasonMask |= CONSTRUCTION_REASON_INDUSTRY_UPGRADING;
            if (probe != null) {
              probe.captureUpgradingIndustry(industry, i, rawBuilding);
            }
          }
          if (probe != null && (reportedBuilding || effectiveBuilding || upgrading) && probe.industryIndex < 0) {
            probe.captureIndustry(industry, i, reportedBuilding, effectiveBuilding, upgrading);
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
  static int constructionBuildingReason(boolean reportedBuilding, Boolean rawBuilding) {
    if (rawBuilding == null) { return reportedBuilding ? CONSTRUCTION_REASON_INDUSTRY_BUILDING : 0; }
    if (rawBuilding.booleanValue()) { return CONSTRUCTION_REASON_INDUSTRY_BUILDING; }
    return reportedBuilding ? CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW : 0;
  }
  static VarHandle baseIndustryBuildingHandle() {
    if (!baseIndustryBuildingHandleResolved) {
      synchronized (StarsectorPrepatcherHooks.class) {
        if (!baseIndustryBuildingHandleResolved) {
          try {
            baseIndustryBuildingHandle = StarsectorPrepatcherHooks.privateVarHandle(BaseIndustry.class, "building");
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
  static Boolean rawBaseIndustryBuilding(Industry industry) {
    if (!(industry instanceof BaseIndustry)) return null;
    VarHandle handle = baseIndustryBuildingHandle();
    if (handle == null) { throw new IllegalStateException("BaseIndustry.building field is unavailable"); }
    return (boolean) handle.get(industry);
  }
  static void recordConstructionReasonCounters(int reasonMask) {
    int semanticReasons = 0;
    if ((reasonMask & CONSTRUCTION_REASON_QUEUE_NON_EMPTY) != 0) {
      StarsectorPrepatcherMetrics.CONSTRUCTION_DETECTED_QUEUE_NON_EMPTY.increment();
      semanticReasons++;
    }
    if ((reasonMask & CONSTRUCTION_REASON_INDUSTRY_BUILDING) != 0) {
      StarsectorPrepatcherMetrics.CONSTRUCTION_DETECTED_INDUSTRY_BUILDING.increment();
      semanticReasons++;
    }
    if ((reasonMask & CONSTRUCTION_REASON_INDUSTRY_UPGRADING) != 0) {
      StarsectorPrepatcherMetrics.CONSTRUCTION_DETECTED_INDUSTRY_UPGRADING.increment();
      semanticReasons++;
    }
    if ((reasonMask & CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW) != 0) {
      StarsectorPrepatcherMetrics.CONSTRUCTION_DETECTED_REPORTED_BUILDING_WITHOUT_RAW.increment();
      semanticReasons++;
    }
    if ((reasonMask & CONSTRUCTION_REASON_PROBE_FAILURE) != 0) {
      StarsectorPrepatcherMetrics.CONSTRUCTION_DETECTED_PROBE_FAILURE.increment();
      semanticReasons++;
    }
    if ((reasonMask & CONSTRUCTION_REASON_QUEUE_ITEMS_UNKNOWN) != 0) semanticReasons++;
    if ((reasonMask & CONSTRUCTION_REASON_INDUSTRIES_UNKNOWN) != 0) semanticReasons++;
    if (semanticReasons > 1) StarsectorPrepatcherMetrics.CONSTRUCTION_DETECTED_MULTIPLE_REASONS.increment();
  }
  static void updateMax(AtomicLong target, long value) {
    long current;
    while (value > (current = target.get()) && !target.compareAndSet(current, value)) {
      // Retry only when another observation raised the high-water concurrently.
    }
  }
  static boolean wouldCreateNewPendingRun(MarketScheduleState state, float amount) {
    PendingStepRun last = state.pendingRuns == null ? null : state.pendingRuns.peekLast();
    return last == null || Float.floatToRawIntBits(last.amount) != Float.floatToRawIntBits(amount);
  }
  static boolean appendPendingStepLocked(MarketScheduleState state, float amount) {
    float total = state.pendingAmount + amount;
    if (!Float.isFinite(total) || total < 0f || state.pendingSteps == Integer.MAX_VALUE) { return false; }
    if (state.pendingRuns == null) state.pendingRuns = new ArrayDeque<>();
    PendingStepRun last = state.pendingRuns.peekLast();
    if (last != null && Float.floatToRawIntBits(last.amount) == Float.floatToRawIntBits(amount)) {
      if (last.count == Integer.MAX_VALUE) return false;
      last.count++;
    } else {
      state.pendingRuns.addLast(new PendingStepRun(amount));
    }
    state.pendingAmount = total;
    state.pendingSteps++;
    updateMaximum(StarsectorPrepatcherMetrics.PENDING_MARKET_STEPS_HIGH_WATER, state.pendingSteps);
    updateMaximum(StarsectorPrepatcherMetrics.PENDING_MARKET_RUNS_HIGH_WATER, state.pendingRuns.size());
    return true;
  }
  static void clearPendingLocked(MarketScheduleState state) {
    state.pendingAmount = 0f;
    state.pendingSteps = 0;
    if (state.pendingRuns != null) state.pendingRuns.clear();
    state.debtSequence = 0L;
    state.sourceMask = 0;
  }
  static MarketAdvanceBatchFrame detachPendingLocked(MarketAPI market, MarketScheduleState state, boolean visible) {
    if (state.pendingSteps <= 0) return null;
    MarketAdvanceBatchStack stack = MARKET_ADVANCE_BATCH_STACK.get();
    MarketAdvanceBatchFrame frame = stack.push(market, state, visible);
    clearPendingLocked(state);
    state.inAdvance = true;
    if (visible) StarsectorPrepatcherMetrics.MARKET_BATCH_CONTEXTS_CREATED.increment();
    return frame;
  }
  static int originForStateLocked(MarketScheduleState state, int fallbackOrigin) {
    if ((state.sourceMask & (1 << 1)) != 0) { return MARKET_ORIGIN_PLANET_CONDITION_SCHEDULED; }
    if ((state.sourceMask & 1) != 0) return MARKET_ORIGIN_SCHEDULED;
    return fallbackOrigin;
  }
  static int deliverPendingCoalesced(MarketAPI market, MarketScheduleState state, int fallbackOrigin,
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
      StarsectorPrepatcherMetrics.COALESCED_MARKET_FLUSHES.increment();
      StarsectorPrepatcherMetrics.COALESCED_MARKET_AMOUNT.add(frame.totalAmount);
      if (countFullAdvance) StarsectorPrepatcherMetrics.MARKET_SCHEDULER_FULL_ADVANCES.increment();
      if (saveFlush) {
        StarsectorPrepatcherMetrics.SAVE_COALESCED_MARKETS.increment();
        StarsectorPrepatcherMetrics.MARKET_SCHEDULER_SAVE_FLUSHES.increment();
      }
      return frame.totalSteps;
    } catch (Throwable failure) {
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_ADVANCE_FAILURES.increment();
      synchronized (state) {
        state.disabled = true;
        clearPendingLocked(state);
      }
      StarsectorPrepatcherHooks.throwUnchecked(failure);
      return 0;
    } finally {
      synchronized (state) {
        state.inAdvance = false;
      }
      MARKET_ADVANCE_BATCH_STACK.get().pop(frame);
      if (!success && saveFlush) StarsectorPrepatcherMetrics.MARKET_SCHEDULER_SAVE_FLUSH_FAILURES.increment();
    }
  }
  static int deliverPendingExact(MarketAPI market, MarketScheduleState state, int fallbackOrigin, boolean saveFlush) {
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
        StarsectorPrepatcherMetrics.SAVE_EXACT_REPLAY_MARKETS.increment();
        StarsectorPrepatcherMetrics.SAVE_EXACT_REPLAY_STEPS.add(delivered);
        StarsectorPrepatcherMetrics.MARKET_SCHEDULER_SAVE_FLUSHES.increment();
      }
      return delivered;
    } catch (Throwable failure) {
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_ADVANCE_FAILURES.increment();
      synchronized (state) {
        state.disabled = true;
        clearPendingLocked(state);
      }
      StarsectorPrepatcherHooks.throwUnchecked(failure);
      return delivered;
    } finally {
      synchronized (state) {
        state.inAdvance = false;
      }
      MARKET_ADVANCE_BATCH_STACK.get().pop(frame);
      if (!success && saveFlush) StarsectorPrepatcherMetrics.MARKET_SCHEDULER_SAVE_FLUSH_FAILURES.increment();
    }
  }
  static int deliverPendingForSave(MarketAPI market, MarketScheduleState state, boolean exact) {
    MarketAdvanceBatchFrame frame;
    int origin;
    synchronized (state) {
      if (state.inAdvance) { throw new IllegalStateException("Cannot save while a market callback is active"); }
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
            invokeObservedMarketAdvance(market, frame.runAmounts[run], origin);
            delivered++;
          }
        }
        StarsectorPrepatcherMetrics.SAVE_EXACT_REPLAY_MARKETS.increment();
        StarsectorPrepatcherMetrics.SAVE_EXACT_REPLAY_STEPS.add(delivered);
      } else {
        invokeObservedMarketAdvance(market, frame.totalAmount, origin);
        delivered = frame.totalSteps;
        StarsectorPrepatcherMetrics.COALESCED_MARKET_FLUSHES.increment();
        StarsectorPrepatcherMetrics.COALESCED_MARKET_AMOUNT.add(frame.totalAmount);
        StarsectorPrepatcherMetrics.SAVE_COALESCED_MARKETS.increment();
      }
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_SAVE_FLUSHES.increment();
      return delivered;
    } catch (Throwable failure) {
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_SAVE_FLUSH_FAILURES.increment();
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
      StarsectorPrepatcherHooks.throwUnchecked(failure);
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
    if (component != MARKET_SCHEDULER_COMPONENT_ENGINE && component != MARKET_SCHEDULER_COMPONENT_ECONOMY
        && component != MARKET_SCHEDULER_COMPONENT_ENTITY && component != MARKET_SCHEDULER_COMPONENT_SAVE
        && component != MARKET_SCHEDULER_COMPONENT_BATCH_PROTOCOL
        && component != MARKET_SCHEDULER_COMPONENT_MARKET_SEMANTICS
        && component != MARKET_SCHEDULER_COMPONENT_MILITARY_BASE && component != MARKET_SCHEDULER_COMPONENT_LIONS_GUARD
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
    System.setProperty("starsector.prepatcher.marketSchedulerCapabilityMask", Integer.toString(updated));
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
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_NESTED_TICKS.increment();
      return;
    }
    tick.clearContext();

    if (!marketSchedulerReady) {
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_NOT_READY_TICKS.increment();
      return;
    }
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    long generation = StarsectorPrepatcherCampaignCaches.campaignCacheGeneration;
    if (c == null || !c.marketScheduler || !StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) return;

    MarketSchedulerBatch batch = MARKET_SCHEDULER_BATCH.get();
    if (batch.finishing) {
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_NESTED_TICKS.increment();
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
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_SIMULATION_TICKS.increment();
      if (batch.fastForwardIteration) StarsectorPrepatcherMetrics.MARKET_SCHEDULER_FAST_FORWARD_TICKS.increment();
    } catch (Throwable ignored) {
      tick.clearContext();
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_FRAME_CAPTURE_FAILURES.increment();
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
    if (!fastForwardIteration && batch.open && batch.simulationTicks > 0 && MARKET_SCHEDULER_TICK.get().depth == 0) {
      finishMarketSchedulerBatch(batch);
    }
  }
  static long nextMarketSchedulerRenderBatch() {
    long next = marketSchedulerRenderBatch + 1L;
    if (next <= 0L) next = 1L;
    marketSchedulerRenderBatch = next;
    return next;
  }
  /** Common accumulator for every transformed engine-owned MarketAPI.advance call. */
  public static void advanceMarketScheduled(MarketAPI market, float amount, int source) {
    if (market == null) throw new NullPointerException("market");
    StarsectorPrepatcherMetrics.MARKET_SCHEDULER_CALLS.increment();
    if (source == 0) StarsectorPrepatcherMetrics.MARKET_SCHEDULER_ECONOMY_CALLS.increment();
    else if (source == 1) StarsectorPrepatcherMetrics.MARKET_SCHEDULER_PLANET_CONDITION_CALLS.increment();
    else {
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_INVALID_SOURCE_CALLS.increment();
      advanceMarketImmediatelyWithPending(market, amount, MARKET_ORIGIN_SCHEDULER_FALLBACK);
      return;
    }

    int fallbackOrigin = source == 1 ? MARKET_ORIGIN_PLANET_CONDITION_FALLBACK : MARKET_ORIGIN_SCHEDULER_FALLBACK;
    int scheduledOrigin = source == 1 ? MARKET_ORIGIN_PLANET_CONDITION_SCHEDULED : MARKET_ORIGIN_SCHEDULED;

    if (!Float.isFinite(amount) || amount < 0f) {
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_INVALID_AMOUNT_CALLS.increment();
      invokeObservedMarketAdvance(market, amount, fallbackOrigin);
      return;
    }
    if (!marketSchedulerReady) {
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_NOT_READY_CALLS.increment();
      advanceMarketImmediatelyWithPending(market, amount, fallbackOrigin);
      return;
    }

    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    long generation = StarsectorPrepatcherCampaignCaches.campaignCacheGeneration;
    MarketSchedulerTick tick = MARKET_SCHEDULER_TICK.get();
    MarketSchedulerBatch batch = MARKET_SCHEDULER_BATCH.get();
    if (c == null || !c.marketScheduler) {
      advanceMarketImmediatelyWithPending(market, amount, fallbackOrigin);
      return;
    }
    if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) {
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_LIFECYCLE_INACTIVE_CALLS.increment();
      advanceMarketImmediatelyWithPending(market, amount, fallbackOrigin);
      return;
    }
    if (tick.depth != 1 || !tick.active || tick.generation != generation || !batch.open
        || batch.generation != generation || tick.batch != batch.id) {
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_OUTSIDE_TICK_CALLS.increment();
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
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_STATE_FAILURES.increment();
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
      auditMemory = !disabled && !reentrant && !c.marketSchedulerPerSimulationTickMemoryKey.isEmpty()
          && (state.lastPolicyAuditBatch == Long.MIN_VALUE
              || batch.id - state.lastPolicyAuditBatch >= c.marketSchedulerPolicyAuditBatches);
      if (auditMemory) state.lastPolicyAuditBatch = batch.id;
      perSimulationTick = state.perSimulationTick;
    }
    if (disabled) {
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_DISABLED_MARKET_CALLS.increment();
      advanceMarketImmediatelyWithPending(market, amount, fallbackOrigin);
      return;
    }
    if (reentrant) {
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_REENTRANT_CALLS.increment();
      invokeObservedMarketAdvance(market, amount, fallbackOrigin);
      return;
    }

    if (auditMemory) {
      try {
        MemoryAPI memory = market.getMemoryWithoutUpdate();
        boolean updated = memory != null && memory.getBoolean(c.marketSchedulerPerSimulationTickMemoryKey);
        synchronized (state) {
          if (state.perSimulationTick != updated) {
            state.perSimulationTick = updated;
            StarsectorPrepatcherMetrics.MARKET_SCHEDULER_PER_SIMULATION_TICK_FLAG_CHANGES.increment();
          }
          perSimulationTick = state.perSimulationTick;
        }
      } catch (Throwable ignored) {
        StarsectorPrepatcherMetrics.MARKET_SCHEDULER_POLICY_FAILURES.increment();
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
      StarsectorPrepatcherMetrics.CONSTRUCTION_FULL_RATE_CALLS.increment();
      invokeObservedMarketAdvance(market, amount, scheduledOrigin);
      return;
    }

    if (perSimulationTick) {
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_PER_SIMULATION_TICK_CALLS.increment();
      advanceMarketImmediatelyWithPending(market, amount, scheduledOrigin);
      return;
    }

    boolean overflowRuns;
    synchronized (state) {
      overflowRuns = state.pendingSteps > 0 && wouldCreateNewPendingRun(state, amount) && state.pendingRuns != null
          && state.pendingRuns.size() >= c.marketSchedulerMaxPendingRuns;
    }
    if (overflowRuns) {
      StarsectorPrepatcherMetrics.PENDING_RUN_OVERFLOW_FLUSHES.increment();
      deliverPendingCoalesced(market, state, scheduledOrigin, true, false);
    }

    boolean evaluateDue = false;
    boolean invalidTotal = false;
    synchronized (state) {
      if (state.lastSourceSimulationTick == tick.simulationTick && state.lastSource != source) {
        StarsectorPrepatcherMetrics.MARKET_SCHEDULER_MULTIPLE_SOURCE_TICK_CALLS.increment();
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
        evaluateDue = state.lastDueCheckBatch != batch.id || (source == 0 && previousSourceMask != state.sourceMask);
        if (evaluateDue) state.lastDueCheckBatch = batch.id;
      }
    }
    if (invalidTotal) {
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_INVALID_AMOUNT_CALLS.increment();
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_AMOUNT_OVERFLOW_SPLITS.increment();
      deliverPendingCoalesced(market, state, scheduledOrigin, true, false);
      invokeObservedMarketAdvance(market, amount, fallbackOrigin);
      return;
    }
    if (evaluateDue && prepareMarketForBatch(batch, market, state, c)) {
      batch.touched.add(new WeakIdentityEntry<>(market, state));
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_BATCH_TOUCHED_ENTRIES.increment();
    }
    StarsectorPrepatcherMetrics.MARKET_SCHEDULER_ACCUMULATED_CALLS.increment();
  }
  static void finishMarketSchedulerBatch(MarketSchedulerBatch batch) {
    if (!batch.open || batch.finishing) return;
    batch.open = false;
    batch.finishing = true;
    StarsectorPrepatcherMetrics.MARKET_SCHEDULER_RENDER_BATCHES.increment();
    updateMaximum(StarsectorPrepatcherMetrics.MARKET_SCHEDULER_MAX_TICKS_PER_BATCH, batch.simulationTicks);
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
  static boolean prepareMarketForBatch(MarketSchedulerBatch batch, MarketAPI market, MarketScheduleState state,
      PrepatcherConfig c) {
    if (market == null || state == null || c == null || !c.marketScheduler) return false;
    StarsectorPrepatcherMetrics.MARKET_SCHEDULER_DUE_CHECKS.increment();

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
        StarsectorPrepatcherMetrics.MARKET_SCHEDULER_POLICY_FAILURES.increment();
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
          audit = !state.schedulePolicyCached || state.lastSchedulePolicyAuditBatch == Long.MIN_VALUE
              || batch.id - state.lastSchedulePolicyAuditBatch >= c.marketSchedulerPolicyAuditBatches;
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
          StarsectorPrepatcherMetrics.MARKET_SCHEDULER_POLICY_AUDITS.increment();
        } else {
          StarsectorPrepatcherMetrics.MARKET_SCHEDULER_POLICY_CACHE_HITS.increment();
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
        StarsectorPrepatcherMetrics.MARKET_SCHEDULER_POLICY_AUDITS.increment();
      }
      if (c.marketSchedulerHotCurrentLocation && location != null && location == batch.currentLocation) hot = true;
      if (c.marketSchedulerHotPlayerOwned && playerOwned) hot = true;
      if (c.marketSchedulerHotInteraction && market == batch.interactionMarket) hot = true;
      interval = hot ? 1 : (hidden ? c.marketSchedulerHiddenBatches : c.marketSchedulerBatches);
      phase = stableMarketPhase(market, interval);
    } catch (Throwable ignored) {
      hot = true;
      interval = 1;
      phase = 0;
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_POLICY_FAILURES.increment();
    }

    synchronized (state) {
      if (state.disabled || state.inAdvance || state.pendingSteps <= 0) return false;
      if (state.nextDueBatch == Long.MIN_VALUE) {
        if (conditionOnlySource && interval > 1) {
          state.nextDueBatch = firstStableDueBatch(batch.id, phase, interval);
          if (state.nextDueBatch > batch.id) {
            StarsectorPrepatcherMetrics.MARKET_SCHEDULER_INITIAL_PHASE_DEFERRALS.increment();
          }
        } else {
          state.nextDueBatch = batch.id;
        }
      }
      boolean due = interval <= 1 || batch.id >= state.nextDueBatch;
      if (!due) {
        StarsectorPrepatcherMetrics.MARKET_SCHEDULER_NOT_DUE_SKIPS.increment();
        return false;
      }
      state.dueDecisionBatch = batch.id;
      state.dueHot = hot;
      state.dueHidden = hidden;
      state.dueInterval = interval;
      state.duePhase = phase;
      if (state.lastTouchedBatch == batch.id) return false;
      state.lastTouchedBatch = batch.id;
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_DUE_ENTRIES.increment();
      return true;
    }
  }
  static void deliverMarketAtBatchEnd(MarketSchedulerBatch batch, MarketAPI market, MarketScheduleState state) {
    if (market == null || state == null) return;
    boolean hot;
    boolean hidden;
    int interval;
    int phase;
    synchronized (state) {
      if (state.disabled || state.inAdvance || state.pendingSteps <= 0 || state.dueDecisionBatch != batch.id) return;
      hot = state.dueHot;
      hidden = state.dueHidden;
      interval = state.dueInterval;
      phase = state.duePhase;
    }

    int delivered = deliverPendingCoalesced(market, state, MARKET_ORIGIN_SCHEDULED, true, false);
    if (delivered <= 0) return;
    if (hot) StarsectorPrepatcherMetrics.MARKET_SCHEDULER_HOT_ADVANCES.increment();
    if (hidden) StarsectorPrepatcherMetrics.MARKET_SCHEDULER_HIDDEN_ADVANCES.increment();
    synchronized (state) {
      state.nextDueBatch = nextStableDueBatch(batch.id, phase, interval);
    }
  }
  static long firstStableDueBatch(long currentBatch, int phase, int interval) {
    if (interval <= 1) return currentBatch;
    return currentBatch + Math.floorMod((long) phase - currentBatch, interval);
  }
  static long nextStableDueBatch(long currentBatch, int phase, int interval) {
    if (interval <= 1) return currentBatch + 1L;
    long next = currentBatch + 1L;
    return next + Math.floorMod((long) phase - next, interval);
  }
  static void updateMaximum(AtomicLong maximum, long value) {
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
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    long generation = StarsectorPrepatcherCampaignCaches.campaignCacheGeneration;
    if (c == null || !c.marketScheduler || !StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) return;

    long started = System.nanoTime();
    dumpMarketSchedulerBaseline("before-save-flush");
    try {
      List<WeakIdentityEntry<MarketAPI, MarketScheduleState>> entries;
      try {
        entries = MARKET_SCHEDULER_STATES.entriesSnapshot();
        entries.sort((left, right) -> Long.compare(debtSequence(left.value), debtSequence(right.value)));
      } catch (Throwable failure) {
        StarsectorPrepatcherMetrics.MARKET_SCHEDULER_SAVE_FLUSH_FAILURES.increment();
        throw new IllegalStateException("Unable to enumerate market scheduler debt before save", failure);
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
      if (elapsed > 0L) StarsectorPrepatcherMetrics.SAVE_FLUSH_DURATION_NANOS.add(elapsed);
    }
  }
  static long debtSequence(MarketScheduleState state) {
    if (state == null) return Long.MAX_VALUE;
    synchronized (state) {
      return state.debtSequence == 0L ? Long.MAX_VALUE : state.debtSequence;
    }
  }
  static int countPerSimulationTickMarkets() {
    int count = 0;
    try {
      for (WeakIdentityEntry<MarketAPI, MarketScheduleState> entry : MARKET_SCHEDULER_STATES.entriesSnapshot()) {
        MarketScheduleState state = entry.value;
        if (state == null) continue;
        synchronized (state) {
          if (state.perSimulationTick) count++;
        }
      }
    } catch (Throwable ignored) {
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_STATE_FAILURES.increment();
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
    Path root = StarsectorPrepatcherHooks.modRoot;
    if (root == null) return "";
    String safeReason = reason == null || reason.isBlank() ? "manual" : reason.replaceAll("[^A-Za-z0-9._-]+", "_");
    String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(Instant.now());
    Path directory = root.resolve("logs").resolve("market-scheduler-baseline");
    Path csvPath = directory.resolve("baseline-" + stamp + '-' + safeReason + ".csv");
    Path jsonPath = directory.resolve("baseline-" + stamp + '-' + safeReason + ".json");

    ArrayList<String[]> rows = new ArrayList<>();
    long pendingStepsTotal = 0L;
    long pendingRunsTotal = 0L;
    long deliveredCallbacksTotal = 0L;
    double deliveredAmountTotal = 0d;
    try {
      for (WeakIdentityEntry<MarketAPI, MarketScheduleState> entry : MARKET_SCHEDULER_STATES.entriesSnapshot()) {
        MarketAPI market = entry.key;
        MarketScheduleState state = entry.value;
        if (market == null || state == null) continue;
        String marketId;
        String marketName;
        try {
          marketId = Objects.toString(market.getId(), "");
        } catch (Throwable ignored) {
          marketId = "";
        }
        try {
          marketName = Objects.toString(market.getName(), "");
        } catch (Throwable ignored) {
          marketName = "";
        }
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
        rows.add(new String[]{marketId, marketName, Integer.toString(System.identityHashCode(market)),
            Integer.toString(pendingSteps), Float.toString(pendingAmount), Integer.toString(pendingRuns),
            pendingHistory, Long.toString(deliveredCallbacks), Double.toString(deliveredAmount),
            Long.toString(lastDeliveredSequence), Integer.toString(lastDeliveredOrigin),
            Long.toString(lastDeliveredNanos), Long.toString(debtSequence), Integer.toString(lastSource),
            Integer.toString(sourceMask), Boolean.toString(perSimulationTick), Boolean.toString(constructionFullRate),
            Integer.toString(constructionReasonMask), Boolean.toString(inAdvance), Boolean.toString(disabled),
            Long.toString(nextDueBatch), Long.toString(lastTouchedBatch)});
      }
    } catch (Throwable failure) {
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_STATE_FAILURES.increment();
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
    String json = "{\n" + "  \"schemaVersion\": 1,\n" + "  \"capturedUtc\": \"" + baselineJson(Instant.now().toString())
        + "\",\n" + "  \"reason\": \"" + baselineJson(reason == null ? "" : reason) + "\",\n" + "  \"schedulerReady\": "
        + marketSchedulerReady + ",\n" + "  \"markets\": " + rows.size() + ",\n" + "  \"pendingSteps\": "
        + pendingStepsTotal + ",\n" + "  \"pendingRuns\": " + pendingRunsTotal + ",\n" + "  \"deliveredCallbacks\": "
        + deliveredCallbacksTotal + ",\n" + "  \"deliveredAmount\": " + deliveredAmountTotal + "\n" + "}\n";
    try {
      Files.createDirectories(directory);
      Files.writeString(csvPath, csv, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
      Files.writeString(jsonPath, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
      return csvPath.toAbsolutePath().normalize().toString();
    } catch (Throwable failure) {
      PrepatcherLog.warn("Could not write market scheduler baseline: " + failure);
      return "";
    }
  }
  static String baselineCsv(String value) {
    String text = value == null ? "" : value;
    return '"' + text.replace("\"", "\"\"") + '"';
  }
  static String baselineJson(String value) {
    if (value == null) return "";
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
  }
  static MarketSchedulerGaugeSnapshot marketSchedulerGaugeSnapshot() {
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
      for (WeakIdentityEntry<MarketAPI, MarketScheduleState> entry : MARKET_SCHEDULER_STATES.entriesSnapshot()) {
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
          if ((reasons & (CONSTRUCTION_REASON_QUEUE_ITEMS_UNKNOWN | CONSTRUCTION_REASON_INDUSTRIES_UNKNOWN
              | CONSTRUCTION_REASON_PROBE_FAILURE | CONSTRUCTION_REASON_MUTATION_RACE)) != 0) {
            constructionUncertainMarkets++;
          }
          if (Integer.bitCount(reasons & CONSTRUCTION_REASON_OBSERVED_MASK) > 1) {
            constructionMultipleReasonMarkets++;
          }
        }
      }
    } catch (Throwable ignored) {
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_STATE_FAILURES.increment();
    }
    return new MarketSchedulerGaugeSnapshot(pendingSteps, pendingRuns, constructionMarkets, constructionQueueMarkets,
        constructionBuildingMarkets, constructionUpgradingMarkets, constructionReportedBuildingWithoutRawMarkets,
        constructionUncertainMarkets, constructionMultipleReasonMarkets);
  }
  record MarketSchedulerGaugeSnapshot(long pendingMarketStepsCurrent, long pendingMarketRunsCurrent,
      int constructionFullRateMarkets, int constructionMarketsQueueNonEmpty, int constructionMarketsIndustryBuilding,
      int constructionMarketsIndustryUpgrading, int constructionMarketsReportedBuildingWithoutRaw,
      int constructionMarketsUncertain, int constructionMarketsMultipleReasons) {
  }
  static int stableMarketPhase(MarketAPI market, int interval) {
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
  /** Per-simulation-tick context owned by the unified CampaignEngine scheduler. */
  static final class MarketSchedulerTick {
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
  static final class MarketSchedulerBatch {
    boolean open;
    boolean finishing;
    boolean fastForwardIteration;
    long generation = -1L;
    long id;
    int simulationTicks;
    LocationAPI currentLocation;
    MarketAPI interactionMarket;
    final ArrayList<WeakIdentityEntry<MarketAPI, MarketScheduleState>> touched = new ArrayList<>();

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
  static final class MarketScheduleState {
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
  static final class ConstructionProbe {
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

    void captureIndustry(Industry industry, int index, boolean reportedBuilding, boolean effectiveBuilding,
        boolean upgrading) {
      industryIndex = index;
      industryClass = ((Object) industry).getClass().getName();
      industryBuilding = reportedBuilding;
      industryEffectiveBuilding = effectiveBuilding;
      industryUpgrading = upgrading;
      industryId = safeIndustryId(industry);
    }

    void captureBuildingIndustry(Industry industry, int index, Boolean rawBuilding) {
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

    void captureReportedBuildingWithoutRawIndustry(Industry industry, int index, Boolean rawBuilding) {
      if (reportedBuildingWithoutRawIndustryIndex >= 0) return;
      reportedBuildingWithoutRawIndustryIndex = index;
      reportedBuildingWithoutRawIndustryClass = ((Object) industry).getClass().getName();
      reportedBuildingWithoutRawIndustryId = safeIndustryId(industry);
      String[] details = baseIndustryDetails(industry, rawBuilding);
      reportedBuildingWithoutRawRawBuildingField = details[0];
      reportedBuildingWithoutRawBuildProgress = details[1];
      reportedBuildingWithoutRawBuildTime = details[2];
      reportedBuildingWithoutRawUpgradeId = details[3];
      reportedBuildingWithoutRawIsFunctional = details[4];
    }

    void captureUpgradingIndustry(Industry industry, int index, Boolean rawBuilding) {
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
    private static String[] baseIndustryDetails(Industry industry, Boolean rawBuilding) {
      String[] result = {"", "", "", "", ""};
      if (!(industry instanceof BaseIndustry)) return result;
      result[0] = rawBuilding == null ? readField(industry, "building") : String.valueOf(rawBuilding.booleanValue());
      result[1] = readMethodOrField(industry, "getBuildProgress", "buildProgress");
      result[2] = readMethodOrField(industry, "getBuildTime", "buildTime");
      result[3] = firstAvailable(readMethod(industry, "getUpgradeId"), readField(industry, "upgradeId"),
          readField(industry, "upgrade"));
      result[4] = readMethod(industry, "isFunctional");
      return result;
    }

    private static String readMethodOrField(Object target, String methodName, String fieldName) {
      return firstAvailable(readMethod(target, methodName), readField(target, fieldName));
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
      if (value instanceof Number || value instanceof Boolean || value instanceof Character
          || value instanceof String) {
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
  static final class ConstructionDiagnostics {
    private static final DateTimeFormatter SESSION_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    private static final String HEADER = "timestamp_utc,first_seen_utc,last_seen_utc,occurrences,"
        + "market_identity_hash,market_id,market_name,trigger,sample_bucket,transition,"
        + "reason_mask,reasons,queue_class,queue_size,industry_count,"
        + "industry_index,industry_id,industry_class,is_building,"
        + "effective_is_building,is_upgrading,reported_building_without_raw,"
        + "building_industry_index,building_industry_id,building_industry_class,"
        + "building_raw_building_field,building_build_progress,building_build_time,"
        + "building_upgrade_id,building_is_functional," + "reported_building_without_raw_industry_index,"
        + "reported_building_without_raw_industry_id," + "reported_building_without_raw_industry_class,"
        + "reported_building_without_raw_raw_building_field," + "reported_building_without_raw_build_progress,"
        + "reported_building_without_raw_build_time," + "reported_building_without_raw_upgrade_id,"
        + "reported_building_without_raw_is_functional,"
        + "upgrading_industry_index,upgrading_industry_id,upgrading_industry_class,"
        + "upgrading_raw_building_field,upgrading_build_progress,upgrading_build_time,"
        + "upgrading_upgrade_id,upgrading_is_functional,"
        + "mutation_epoch,audited_epoch,last_audit_batch,old_active,new_active,"
        + "old_reason_mask,new_reason_mask,exception_class,exception_message\n";

    private final int maxSamplesPerReason;
    private final int reportIntervalSeconds;
    private final Path output;
    private final ConcurrentHashMap<String, ConstructionDiagnosticSample> samples = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> samplesPerReason = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    ConstructionDiagnostics(PrepatcherConfig configuration, Path root) throws IOException {
      maxSamplesPerReason = configuration.marketConstructionDiagnosticsMaxSamplesPerReason;
      reportIntervalSeconds = configuration.statsLogIntervalSeconds > 0 ? configuration.statsLogIntervalSeconds : 30;
      String sessionName = "session-" + SESSION_FORMAT.format(Instant.now()) + "-pid" + ProcessHandle.current().pid();
      Path directory = root.resolve("logs").resolve("market-construction-diagnostics").resolve(sessionName);
      Files.createDirectories(directory);
      output = directory.resolve("samples.csv");
      Files.writeString(output, HEADER, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
      System.setProperty("starsector.prepatcher.marketConstructionDiagnosticsDir",
          directory.toAbsolutePath().toString());
      PrepatcherLog.info("Market construction diagnostics session: " + directory.toAbsolutePath());
    }

    void start() {
      Thread reporter = new Thread(this::reportLoop, "StarsectorPrepatcher-ConstructionDiagnostics");
      reporter.setDaemon(true);
      reporter.setPriority(Thread.MIN_PRIORITY);
      reporter.start();
    }

    void observe(MarketAPI market, ConstructionProbe probe, String trigger, long mutationEpoch, long auditedEpoch,
        long lastAuditBatch, boolean oldActive, boolean newActive, int oldReasonMask, int newReasonMask) {
      if (newReasonMask == 0 && oldReasonMask == 0 && oldActive == newActive) return;
      int identityHash = market == null ? 0 : System.identityHashCode(market);
      String marketId = safeMarketText(market, true);
      String marketName = safeMarketText(market, false);
      String reasonBucket = constructionSampleBucket(oldReasonMask, newReasonMask);
      String industryClass = probe == null ? "" : probe.industryClass;
      String industryId = probe == null ? "" : probe.industryId;
      String buildingClass = probe == null ? "" : probe.buildingIndustryClass;
      String buildingId = probe == null ? "" : probe.buildingIndustryId;
      String reportedWithoutRawClass = probe == null ? "" : probe.reportedBuildingWithoutRawIndustryClass;
      String reportedWithoutRawId = probe == null ? "" : probe.reportedBuildingWithoutRawIndustryId;
      String upgradingClass = probe == null ? "" : probe.upgradingIndustryClass;
      String upgradingId = probe == null ? "" : probe.upgradingIndustryId;
      String key = reasonBucket + '\u001f' + identityHash + '\u001f' + marketId + '\u001f' + oldReasonMask + '\u001f'
          + newReasonMask + '\u001f' + industryClass + '\u001f' + industryId + '\u001f' + buildingClass + '\u001f'
          + buildingId + '\u001f' + reportedWithoutRawClass + '\u001f' + reportedWithoutRawId + '\u001f'
          + upgradingClass + '\u001f' + upgradingId + '\u001f' + trigger;
      ConstructionDiagnosticSample existing = samples.get(key);
      if (existing == null) {
        AtomicInteger count = samplesPerReason.computeIfAbsent(reasonBucket, ignored -> new AtomicInteger());
        int slot = count.incrementAndGet();
        if (slot > maxSamplesPerReason) {
          count.decrementAndGet();
          StarsectorPrepatcherMetrics.CONSTRUCTION_DIAGNOSTIC_SAMPLES_DROPPED.increment();
          return;
        }
        ConstructionDiagnosticSample candidate =
            new ConstructionDiagnosticSample(identityHash, marketId, marketName, trigger, reasonBucket, mutationEpoch,
                auditedEpoch, lastAuditBatch, oldActive, newActive, oldReasonMask, newReasonMask, probe);
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
          try {
            writePending();
          } catch (Throwable ignored) {}
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
        Files.writeString(output, rows, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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
  static final class ConstructionDiagnosticSample {
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

    ConstructionDiagnosticSample(int marketIdentityHash, String marketId, String marketName, String trigger,
        String sampleBucket, long mutationEpoch, long auditedEpoch, long lastAuditBatch, boolean oldActive,
        boolean newActive, int oldReasonMask, int newReasonMask, ConstructionProbe probe) {
      this.marketIdentityHash = marketIdentityHash;
      this.marketId = marketId;
      this.marketName = marketName;
      this.trigger = trigger;
      this.sampleBucket = sampleBucket;
      this.transition = oldReasonMask == newReasonMask ? "" : oldReasonMask + "->" + newReasonMask;
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
      reportedBuildingWithoutRaw = probe != null && probe.reportedBuildingWithoutRawIndustryIndex >= 0;
      buildingIndustryIndex = probe == null ? -1 : probe.buildingIndustryIndex;
      buildingIndustryId = probe == null ? "" : probe.buildingIndustryId;
      buildingIndustryClass = probe == null ? "" : probe.buildingIndustryClass;
      buildingRawBuildingField = probe == null ? "" : probe.buildingRawBuildingField;
      buildingBuildProgress = probe == null ? "" : probe.buildingBuildProgress;
      buildingBuildTime = probe == null ? "" : probe.buildingBuildTime;
      buildingUpgradeId = probe == null ? "" : probe.buildingUpgradeId;
      buildingIsFunctional = probe == null ? "" : probe.buildingIsFunctional;
      reportedBuildingWithoutRawIndustryIndex = probe == null ? -1 : probe.reportedBuildingWithoutRawIndustryIndex;
      reportedBuildingWithoutRawIndustryId = probe == null ? "" : probe.reportedBuildingWithoutRawIndustryId;
      reportedBuildingWithoutRawIndustryClass = probe == null ? "" : probe.reportedBuildingWithoutRawIndustryClass;
      reportedBuildingWithoutRawRawBuildingField =
          probe == null ? "" : probe.reportedBuildingWithoutRawRawBuildingField;
      reportedBuildingWithoutRawBuildProgress = probe == null ? "" : probe.reportedBuildingWithoutRawBuildProgress;
      reportedBuildingWithoutRawBuildTime = probe == null ? "" : probe.reportedBuildingWithoutRawBuildTime;
      reportedBuildingWithoutRawUpgradeId = probe == null ? "" : probe.reportedBuildingWithoutRawUpgradeId;
      reportedBuildingWithoutRawIsFunctional = probe == null ? "" : probe.reportedBuildingWithoutRawIsFunctional;
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
          .append(DirectMarketObserver.csv(Instant.ofEpochMilli(firstSeenMillis).toString())).append(',')
          .append(DirectMarketObserver.csv(Instant.ofEpochMilli(lastSeenMillis.get()).toString())).append(',')
          .append(occurrences).append(',').append(marketIdentityHash).append(',')
          .append(DirectMarketObserver.csv(marketId)).append(',').append(DirectMarketObserver.csv(marketName))
          .append(',').append(DirectMarketObserver.csv(trigger)).append(',')
          .append(DirectMarketObserver.csv(sampleBucket)).append(',').append(DirectMarketObserver.csv(transition))
          .append(',').append(newReasonMask).append(',')
          .append(DirectMarketObserver.csv(constructionReasonText(newReasonMask))).append(',')
          .append(DirectMarketObserver.csv(queueClass)).append(',').append(queueSize).append(',').append(industryCount)
          .append(',').append(industryIndex).append(',').append(DirectMarketObserver.csv(industryId)).append(',')
          .append(DirectMarketObserver.csv(industryClass)).append(',').append(building).append(',')
          .append(effectiveBuilding).append(',').append(upgrading).append(',').append(reportedBuildingWithoutRaw)
          .append(',').append(buildingIndustryIndex).append(',').append(DirectMarketObserver.csv(buildingIndustryId))
          .append(',').append(DirectMarketObserver.csv(buildingIndustryClass)).append(',')
          .append(DirectMarketObserver.csv(buildingRawBuildingField)).append(',')
          .append(DirectMarketObserver.csv(buildingBuildProgress)).append(',')
          .append(DirectMarketObserver.csv(buildingBuildTime)).append(',')
          .append(DirectMarketObserver.csv(buildingUpgradeId)).append(',')
          .append(DirectMarketObserver.csv(buildingIsFunctional)).append(',')
          .append(reportedBuildingWithoutRawIndustryIndex).append(',')
          .append(DirectMarketObserver.csv(reportedBuildingWithoutRawIndustryId)).append(',')
          .append(DirectMarketObserver.csv(reportedBuildingWithoutRawIndustryClass)).append(',')
          .append(DirectMarketObserver.csv(reportedBuildingWithoutRawRawBuildingField)).append(',')
          .append(DirectMarketObserver.csv(reportedBuildingWithoutRawBuildProgress)).append(',')
          .append(DirectMarketObserver.csv(reportedBuildingWithoutRawBuildTime)).append(',')
          .append(DirectMarketObserver.csv(reportedBuildingWithoutRawUpgradeId)).append(',')
          .append(DirectMarketObserver.csv(reportedBuildingWithoutRawIsFunctional)).append(',')
          .append(upgradingIndustryIndex).append(',').append(DirectMarketObserver.csv(upgradingIndustryId)).append(',')
          .append(DirectMarketObserver.csv(upgradingIndustryClass)).append(',')
          .append(DirectMarketObserver.csv(upgradingRawBuildingField)).append(',')
          .append(DirectMarketObserver.csv(upgradingBuildProgress)).append(',')
          .append(DirectMarketObserver.csv(upgradingBuildTime)).append(',')
          .append(DirectMarketObserver.csv(upgradingUpgradeId)).append(',')
          .append(DirectMarketObserver.csv(upgradingIsFunctional)).append(',').append(mutationEpoch).append(',')
          .append(auditedEpoch).append(',').append(lastAuditBatch).append(',').append(oldActive).append(',')
          .append(newActive).append(',').append(oldReasonMask).append(',').append(newReasonMask).append(',')
          .append(DirectMarketObserver.csv(exceptionClass)).append(',')
          .append(DirectMarketObserver.csv(exceptionMessage)).append('\n');
    }
  }
  static String constructionSampleBucket(int oldReasonMask, int newReasonMask) {
    if (oldReasonMask != 0 && oldReasonMask != newReasonMask) {
      // Keep the number of quota categories fixed. The exact masks remain
      // available in the transition/old_reason_mask/new_reason_mask columns.
      if (oldReasonMask == CONSTRUCTION_REASON_INDUSTRY_UPGRADING
          && newReasonMask == (CONSTRUCTION_REASON_INDUSTRY_BUILDING | CONSTRUCTION_REASON_INDUSTRY_UPGRADING)) {
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
  static String constructionReasonBucket(int reasonMask) {
    int semantic = reasonMask & CONSTRUCTION_REASON_OBSERVED_MASK;
    if (semantic == CONSTRUCTION_REASON_INDUSTRY_UPGRADING) { return "INDUSTRY_UPGRADING_WITHOUT_BUILDING"; }
    if (semantic == CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW
        || semantic == (CONSTRUCTION_REASON_INDUSTRY_UPGRADING | CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW)) {
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
    if ((semantic & CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW) != 0) { return "REPORTED_BUILDING_WITHOUT_RAW"; }
    return "INACTIVE";
  }
  static String constructionReasonText(int reasonMask) {
    if (reasonMask == 0) return "NONE";
    StringBuilder result = new StringBuilder();
    appendReason(result, reasonMask, CONSTRUCTION_REASON_QUEUE_NON_EMPTY, "QUEUE_NON_EMPTY");
    appendReason(result, reasonMask, CONSTRUCTION_REASON_INDUSTRY_BUILDING, "INDUSTRY_BUILDING");
    appendReason(result, reasonMask, CONSTRUCTION_REASON_INDUSTRY_UPGRADING, "INDUSTRY_UPGRADING");
    appendReason(result, reasonMask, CONSTRUCTION_REASON_QUEUE_ITEMS_UNKNOWN, "QUEUE_ITEMS_UNKNOWN");
    appendReason(result, reasonMask, CONSTRUCTION_REASON_INDUSTRIES_UNKNOWN, "INDUSTRIES_UNKNOWN");
    appendReason(result, reasonMask, CONSTRUCTION_REASON_PROBE_FAILURE, "PROBE_FAILURE");
    appendReason(result, reasonMask, CONSTRUCTION_REASON_MUTATION_RACE, "MUTATION_RACE");
    appendReason(result, reasonMask, CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW,
        "REPORTED_BUILDING_WITHOUT_RAW");
    return result.toString();
  }
  static void appendReason(StringBuilder output, int mask, int bit, String label) {
    if ((mask & bit) == 0) return;
    if (output.length() > 0) output.append('|');
    output.append(label);
  }
  static final class PendingStepRun {
    final float amount;
    int count;

    PendingStepRun(float amount) {
      this.amount = amount;
      this.count = 1;
    }
  }
  static final class MarketAdvanceInvocationContext {
    int depth;
  }
  static final class MarketAdvanceBatchFrame {
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
          if (run == null || run.count <= 0) { throw new IllegalStateException("Corrupt pending Market.advance run"); }
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
  static final class MarketAdvanceBatchStack {
    final ArrayList<MarketAdvanceBatchFrame> frames = new ArrayList<>();
    int depth;

    MarketAdvanceBatchFrame push(MarketAPI market, MarketScheduleState state, boolean visible) {
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
  static final class WeakIdentityEntry<K, V> {
    final K key;
    final V value;

    WeakIdentityEntry(K key, V value) {
      this.key = key;
      this.value = value;
    }
  }
  /** Weak-key map with identity, rather than equals(), key semantics. */
  static final class WeakIdentityMap<K, V> {
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
  static final class IdentityWeakReference<T> extends WeakReference<T> {
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
  static final class MarketAdvanceObservationContext {
    int origin = MARKET_ORIGIN_NONE;
    int depth;
    long siteId;
    final long[] observerSiteIds = new long[8];
    final String[] observerMetadata = new String[8];
    final DirectMarketSiteStats[] observerStats = new DirectMarketSiteStats[8];
  }
  static final class DirectMarketCallToken {
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
  static final class DirectMarketObserver {
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
    private static final String STACK_HEADER = "timestamp_utc,site_id,samples,stack\n";
    private static final String SUMMARY_HEADER =
        "timestamp_utc,interval_seconds,scheduled_entries,scheduler_fallback_entries,"
            + "save_flush_entries,planet_condition_scheduled_entries,"
            + "planet_condition_fallback_entries,direct_callsite_entries,unknown_entries,"
            + "direct_wrapper_calls,site_count,site_overflow_calls,metadata_collisions\n";
    private static final String UNKNOWN_HEADER = "timestamp_utc,samples,stack\n";

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
    private final ConcurrentHashMap<Long, DirectMarketSiteStats> sites = new ConcurrentHashMap<>();
    private final DirectMarketSiteStats overflowSite = DirectMarketSiteStats.overflow();
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
      sessionOrigin = sanitizeSessionOrigin(System.getProperty("starsector.prepatcher.sessionOrigin", "game"));

      String originSegment = "game".equals(sessionOrigin) ? "" : sessionOrigin + "-";
      String sessionName =
          "session-" + originSegment + SESSION_FORMAT.format(Instant.now()) + "-pid" + ProcessHandle.current().pid();
      Path base = root.resolve("logs").resolve("direct-market-observe");
      sessionDir = base.resolve(sessionName);
      Files.createDirectories(sessionDir);
      sitesFile = sessionDir.resolve("call-sites.csv");
      observationsFile = sessionDir.resolve("observations.csv");
      stacksFile = sessionDir.resolve("stacks.csv");
      summaryFile = sessionDir.resolve("summary.csv");
      unknownFile = sessionDir.resolve("unknown-stacks.csv");

      Files.writeString(sitesFile, SITE_HEADER, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
      Files.writeString(observationsFile, OBSERVATION_HEADER, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
      Files.writeString(stacksFile, STACK_HEADER, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
      Files.writeString(summaryFile, SUMMARY_HEADER, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
      Files.writeString(unknownFile, UNKNOWN_HEADER, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
      Files.writeString(sessionDir.resolve("session.json"), sessionJson(), StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW);
      System.setProperty("starsector.prepatcher.directMarketObservationDir", sessionDir.toAbsolutePath().toString());
      PrepatcherLog.info("Direct Market.advance observation session: " + sessionDir.toAbsolutePath());
    }

    void start() {
      Thread reporter = new Thread(this::reportLoop, "StarsectorPrepatcher-DirectMarketObserver");
      reporter.setDaemon(true);
      reporter.setPriority(Thread.MIN_PRIORITY);
      reporter.start();
    }

    void registerSite(long siteId, String metadata) {
      DirectMarketSiteStats stats = site(null, siteId, metadata);
      if (stats == overflowSite) return;
      writeMetadataIfNeeded(stats);
    }

    DirectMarketCallToken beginDirectCall(MarketAdvanceObservationContext context, MarketAPI market, float amount,
        long siteId, String metadata, boolean recursive) {
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

      boolean stackSample =
          maxStacksPerSite > 0 && (callNumber == 1L || (stackSampleEvery > 0 && callNumber % stackSampleEvery == 0L));
      if (stackSample) stats.recordStack(captureDirectStack(), maxStacksPerSite);

      boolean timed = timingSampleEvery > 0 && Math.floorMod(callNumber + stats.samplePhase, timingSampleEvery) == 0L;
      return new DirectMarketCallToken(stats, timed, timed ? System.nanoTime() : 0L);
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
        case MARKET_ORIGIN_PLANET_CONDITION_SCHEDULED -> planetConditionScheduledEntries.increment();
        case MARKET_ORIGIN_PLANET_CONDITION_FALLBACK -> planetConditionFallbackEntries.increment();
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
              interval.stacks.computeIfAbsent(stack, ignored -> new LongAdder()).increment();
            }
          } finally {
            interval.releaseWriter();
          }
        }
      }
    }

    private DirectMarketSiteStats site(MarketAdvanceObservationContext context, long siteId, String metadata) {
      int slot = (int) (siteId ^ (siteId >>> 32)) & 7;
      if (context != null) {
        DirectMarketSiteStats cached = context.observerStats[slot];
        String cachedMetadata = context.observerMetadata[slot];
        if (cached != null && context.observerSiteIds[slot] == siteId
            && (cachedMetadata == metadata || Objects.equals(cachedMetadata, metadata))) {
          return cached;
        }
      }

      DirectMarketSiteStats existing = sites.get(siteId);
      if (existing == null) {
        if (sites.size() >= maxSites) {
          siteOverflowCalls.increment();
          return overflowSite;
        }
        DirectMarketSiteStats candidate = new DirectMarketSiteStats(DirectMarketMetadata.parse(siteId, metadata));
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
          PrepatcherLog.warn("Direct market call-site manifest write failed for " + unsignedHex(stats.metadata.siteId)
              + ": " + failure.getMessage());
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
          try {
            report();
          } catch (Throwable ignored) {}
          return;
        } catch (Throwable failure) {
          PrepatcherLog.error("Direct Market.advance observation report failed", failure);
        }
      }
    }

    private void report() throws IOException {
      long nowNanos = System.nanoTime();
      double elapsedSeconds = Math.max(0.001, (nowNanos - lastReportNanos) / 1_000_000_000.0);
      lastReportNanos = nowNanos;
      String timestamp = Instant.now().toString();

      ArrayList<DirectMarketIntervalSnapshot> snapshots = new ArrayList<>();
      for (DirectMarketSiteStats stats : sites.values()) {
        DirectMarketIntervalSnapshot snapshot = stats.snapshot();
        if (snapshot.calls > 0L || !stats.metadataWritten) snapshots.add(snapshot);
      }
      DirectMarketIntervalSnapshot overflow = overflowSite.snapshot();
      if (overflow.calls > 0L) snapshots.add(overflow);
      snapshots.sort((left, right) -> Long.compare(right.estimatedInclusiveNanos(), left.estimatedInclusiveNanos()));

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
      UnknownStackInterval unknownInterval = unknownStackInterval.getAndSet(new UnknownStackInterval());
      unknownInterval.sealAndAwaitWriters();
      StringBuilder unknownRows = new StringBuilder();
      for (Map.Entry<String, LongAdder> entry : unknownInterval.stacks.entrySet()) {
        long samples = entry.getValue().sum();
        if (samples <= 0L) continue;
        unknownRows.append(csv(timestamp)).append(',').append(samples).append(',').append(csv(entry.getKey()))
            .append('\n');
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
      String summary = csv(timestamp) + ',' + decimal(elapsedSeconds) + ',' + scheduled + ',' + fallback + ',' + save
          + ',' + planetScheduled + ',' + planetFallback + ',' + direct + ',' + unknown + ',' + wrappers + ','
          + sites.size() + ',' + overflowCalls + ',' + collisions + '\n';
      append(summaryFile, new StringBuilder(summary));

      if (wrappers > 0L || unknown > 0L || planetScheduled > 0L || planetFallback > 0L || overflowCalls > 0L
          || collisions > 0L) {
        PrepatcherLog.info("direct-market-observe: calls=" + wrappers + ", sites=" + sites.size()
            + ", scheduledEntries=" + scheduled + ", planetScheduledEntries=" + planetScheduled
            + ", planetFallbackEntries=" + planetFallback + ", directEntries=" + direct + ", unknownEntries=" + unknown
            + ", overflowCalls=" + overflowCalls + ", output=" + sessionDir.toAbsolutePath());
      }
    }

    private String sessionJson() {
      String version = System.getProperty("starsector.prepatcher.version", "unknown");
      return "{\n" + "  \"schema\": 3,\n" + "  \"mode\": \"observe\",\n" + "  \"createdUtc\": "
          + json(Instant.now().toString()) + ",\n" + "  \"prepatcherVersion\": " + json(version) + ",\n"
          + "  \"sessionOrigin\": " + json(sessionOrigin) + ",\n" + "  \"timingSampleEvery\": " + timingSampleEvery
          + ",\n" + "  \"stackSampleEvery\": " + stackSampleEvery + ",\n" + "  \"maxStacksPerSite\": "
          + maxStacksPerSite + ",\n" + "  \"reportIntervalSeconds\": " + reportIntervalSeconds + ",\n"
          + "  \"maxSites\": " + maxSites + ",\n" + "  \"unknownStackSamplesPerInterval\": " + unknownStackSampleLimit
          + ",\n"
          + "  \"behavior\": \"Engine-owned market calls share one scheduler. Direct mod calls remain synchronous and consume any pending scheduler debt before their own amount.\"\n"
          + "}\n";
    }

    private static String sanitizeSessionOrigin(String raw) {
      if (raw == null || raw.isBlank()) return "game";
      StringBuilder sanitized = new StringBuilder(Math.min(raw.length(), 48));
      for (int index = 0; index < raw.length() && sanitized.length() < 48; index++) {
        char value = raw.charAt(index);
        if ((value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z') || (value >= '0' && value <= '9')
            || value == '.' || value == '_' || value == '-') {
          sanitized.append(value);
        } else {
          sanitized.append('_');
        }
      }
      return sanitized.length() == 0 ? "game" : sanitized.toString();
    }

    private static void appendSiteRow(StringBuilder output, DirectMarketMetadata metadata) {
      output.append(csv(unsignedHex(metadata.siteId))).append(',').append(csv(metadata.modId)).append(',')
          .append(csv(metadata.modName)).append(',').append(csv(metadata.modDirectory)).append(',')
          .append(csv(metadata.jarName)).append(',').append(csv(metadata.source)).append(',')
          .append(csv(metadata.className)).append(',').append(csv(metadata.method)).append(',')
          .append(csv(metadata.descriptor)).append(',').append(metadata.line).append(',').append(metadata.ordinal)
          .append(',').append(csv(metadata.amountSource)).append(',').append(csv(metadata.callOwner)).append(',')
          .append(metadata.opcode).append('\n');
    }

    private static void appendObservationRow(StringBuilder output, String timestamp, double elapsedSeconds,
        DirectMarketIntervalSnapshot snapshot) {
      DirectMarketMetadata metadata = snapshot.stats.metadata;
      long estimated = snapshot.estimatedInclusiveNanos();
      double average = snapshot.sampledCalls == 0L ? 0.0 : (double) snapshot.sampledNanos / snapshot.sampledCalls;
      output.append(csv(timestamp)).append(',').append(decimal(elapsedSeconds)).append(',')
          .append(csv(unsignedHex(metadata.siteId))).append(',').append(csv(metadata.modId)).append(',')
          .append(csv(metadata.modName)).append(',').append(csv(metadata.modDirectory)).append(',')
          .append(csv(metadata.jarName)).append(',').append(csv(metadata.source)).append(',')
          .append(csv(metadata.className)).append(',').append(csv(metadata.method)).append(',')
          .append(csv(metadata.descriptor)).append(',').append(metadata.line).append(',').append(metadata.ordinal)
          .append(',').append(csv(metadata.amountSource)).append(',').append(csv(metadata.callOwner)).append(',')
          .append(metadata.opcode).append(',').append(snapshot.calls).append(',').append(snapshot.cumulativeCalls)
          .append(',').append(decimal(snapshot.calls / elapsedSeconds)).append(',').append(snapshot.sampledCalls)
          .append(',').append(snapshot.sampledNanos).append(',').append(estimated).append(',').append(decimal(average))
          .append(',').append(snapshot.exceptions).append(',').append(snapshot.recursiveCalls).append(',')
          .append(snapshot.finiteAmounts).append(',').append(decimal(snapshot.amountSum)).append(',')
          .append(decimal(snapshot.finiteAmounts == 0L ? 0.0 : snapshot.amountSum / snapshot.finiteAmounts)).append(',')
          .append(snapshot.zeroAmounts).append(',').append(snapshot.negativeAmounts).append(',')
          .append(snapshot.nanAmounts).append(',').append(snapshot.nullMarkets).append(',')
          .append(snapshot.approxUniqueMarkets).append('\n');
    }

    private static void append(Path file, StringBuilder content) throws IOException {
      Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String captureDirectStack() {
      try {
        return StackWalker.getInstance()
            .walk(stream -> stream.filter(frame -> !isObserverFrame(frame.getClassName())).limit(16)
                .map(frame -> frame.getClassName() + "#" + frame.getMethodName() + ':' + frame.getLineNumber())
                .collect(Collectors.joining(" <- ")));
      } catch (Throwable failure) {
        return "<stack unavailable: " + failure.getClass().getName() + ">";
      }
    }

    private static boolean isObserverFrame(String className) {
      return className.equals(StarsectorPrepatcherHooks.class.getName())
          || className.startsWith(StarsectorPrepatcherHooks.class.getName() + '$')
          || className.equals(StarsectorPrepatcherMarketScheduler.class.getName())
          || className.startsWith(StarsectorPrepatcherMarketScheduler.class.getName() + '$')
          || className.equals("com.fs.starfarer.campaign.econ.Market") || className.startsWith("java.lang.reflect.")
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
  static final class UnknownStackInterval {
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
  static final class DirectMarketSiteStats {
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
    final AtomicLong[] uniqueWords = {new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()};
    final ConcurrentHashMap<String, LongAdder> stackSamples = new ConcurrentHashMap<>();
    final LongAdder overflowStackSamples = new LongAdder();
    volatile boolean metadataWritten;

    DirectMarketSiteStats(DirectMarketMetadata metadata) {
      this.metadata = metadata;
      this.samplePhase = metadata.siteId ^ (metadata.siteId >>> 32);
    }

    static DirectMarketSiteStats overflow() {
      DirectMarketSiteStats result = new DirectMarketSiteStats(
          new DirectMarketMetadata(0L, "<site-overflow>", "<site-overflow>", "<site-overflow>", "", "<site-overflow>",
              "<site-overflow>", "<site-overflow>", "", -1, -1, "UNKNOWN", "", -1, "<site-overflow>"));
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
      return new DirectMarketIntervalSnapshot(this, calls, totalCalls.get(), sampled, sampledNanos, errors, recursive,
          finite, amountTotal, zero, negative, nan, nulls, approximateUniqueMarkets());
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
            .append(DirectMarketObserver.csv(DirectMarketObserver.unsignedHex(metadata.siteId))).append(',')
            .append(samples).append(',').append(DirectMarketObserver.csv(entry.getKey())).append('\n');
      }
      long overflow = overflowStackSamples.sumThenReset();
      if (overflow > 0L) {
        output.append(DirectMarketObserver.csv(timestamp)).append(',')
            .append(DirectMarketObserver.csv(DirectMarketObserver.unsignedHex(metadata.siteId))).append(',')
            .append(overflow).append(',').append(DirectMarketObserver.csv("<additional stack signatures>"))
            .append('\n');
      }
    }
  }
  static final class DirectMarketMetadata {
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

    DirectMarketMetadata(long siteId, String modId, String modName, String modDirectory, String jarName, String source,
        String className, String method, String descriptor, int line, int ordinal, String amountSource,
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
        return new DirectMarketMetadata(siteId, fields[2], fields[3], fields[4], fields[5], fields[1], fields[6],
            fields[7], fields[8], parseInt(fields[9], -1), parseInt(fields[10], -1), fields[11], fields[12],
            parseInt(fields[13], -1), raw);
      }
      if (fields.length == 10 && "1".equals(fields[0])) {
        String modId = modIdFromSource(fields[1]);
        return new DirectMarketMetadata(siteId, modId, modId, modId, jarNameFromSource(fields[1]), fields[1], fields[2],
            fields[3], fields[4], parseInt(fields[5], -1), parseInt(fields[6], -1), fields[7], fields[8],
            parseInt(fields[9], -1), raw);
      }
      return new DirectMarketMetadata(siteId, "<malformed>", "<malformed>", "<malformed>", "", "<malformed>",
          "<malformed>", "<malformed>", "", -1, -1, "UNKNOWN", "", -1, raw);
    }

    private static String modIdFromSource(String source) {
      String normalized = source == null ? "" : source.replace('\\', '/');
      int marker = normalized.indexOf("/mods/");
      int start = marker >= 0 ? marker + 6 : (normalized.startsWith("mods/") ? 5 : -1);
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
      try {
        return Integer.parseInt(value);
      } catch (RuntimeException ignored) {
        return fallback;
      }
    }
  }
  static final class DirectMarketIntervalSnapshot {
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

    DirectMarketIntervalSnapshot(DirectMarketSiteStats stats, long calls, long cumulativeCalls, long sampledCalls,
        long sampledNanos, long exceptions, long recursiveCalls, long finiteAmounts, double amountSum, long zeroAmounts,
        long negativeAmounts, long nanAmounts, long nullMarkets, int approxUniqueMarkets) {
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
}
