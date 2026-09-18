package com.fs.starfarer.api;

import com.fs.starfarer.api.StarsectorPrepatcherMarketDiagnostics.ConstructionSnapshot;
import com.fs.starfarer.api.StarsectorPrepatcherMarketDiagnostics.ConstructionDiagnostics;
import com.fs.starfarer.api.StarsectorPrepatcherMarketDiagnostics.DirectMarketObserver;
import com.fs.starfarer.api.StarsectorPrepatcherMarketDiagnostics.DirectMarketCallToken;

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
import com.starsector.prepatcher.agent.PrepatcherConfig;
import com.starsector.prepatcher.agent.PrepatcherLog;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Market advance scheduler, construction-queue state machine, and market advance observation. */
final class StarsectorPrepatcherMarketScheduler {

  /** Detach campaign roots without taking cache/state monitors or clearing in-flight objects.
   * Capability registration, delivery sequence, and diagnostic context have process lifetime.
   */
  static void campaignLifecycleReset() {
    MARKET_SCHEDULER_STATES = new WeakIdentityMap<>();
    CONSTRUCTION_QUEUE_OWNERS = new WeakIdentityMap<>();
    marketSchedulerSimulationTick = 0L;
    marketSchedulerRenderBatch = 0L;
    MARKET_SCHEDULER_DEBT_SEQUENCE.set(0L);
    MARKET_SCHEDULER_TICK.remove();
    MARKET_SCHEDULER_BATCH.remove();
    MARKET_ADVANCE_BATCH_STACK.remove();
    MARKET_ADVANCE_INVOCATION.remove();
  }


  static final int MARKET_ORIGIN_NONE = 0;
  static final int MARKET_ORIGIN_SCHEDULED = 1;
  static final int MARKET_ORIGIN_SCHEDULER_FALLBACK = 2;
  static final int MARKET_ORIGIN_SAVE_FLUSH = 3;
  static final int MARKET_ORIGIN_DIRECT_CALL_SITE = 4;
  static final int MARKET_ORIGIN_PLANET_CONDITION_SCHEDULED = 5;
  static final int MARKET_ORIGIN_PLANET_CONDITION_FALLBACK = 6;
  private static final ThreadLocal<MarketAdvanceObservationContext> MARKET_ADVANCE_CONTEXT =
      ThreadLocal.withInitial(MarketAdvanceObservationContext::new);
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
  private static volatile WeakIdentityMap<ConstructionQueue, WeakReference<MarketAPI>> CONSTRUCTION_QUEUE_OWNERS =
      new WeakIdentityMap<>();
  // Simulation ticks preserve engine ordering. Render batches, delimited by
  // CampaignEngine.setFastForwardIteration(false), own cadence so fast-forward
  // cannot multiply expensive Market.advance callbacks per visual frame.
  private static volatile long marketSchedulerSimulationTick;
  private static volatile long marketSchedulerRenderBatch;
  private static final AtomicLong MARKET_SCHEDULER_DEBT_SEQUENCE = new AtomicLong();
  private static final AtomicLong MARKET_DELIVERY_SEQUENCE = new AtomicLong();
  /**
   * One-time per-runtime-class eligibility check for the direct dormant
   * BaseIndustry fast path. The per-frame wrapper never reflects or touches a
   * global identity map. Subclasses that override advance(), isDisrupted(), or
   * getDisruptedKey() retain the exact vanilla callback path.
   */
  private static final ClassValue<Boolean> BASE_INDUSTRY_DORMANT_ELIGIBLE = new ClassValue<>() {
    @Override
    protected Boolean computeValue(Class<?> type) {
      try {
        if (!BaseIndustry.class.isAssignableFrom(type)) return Boolean.FALSE;
        return type.getMethod("advance", float.class).getDeclaringClass() == BaseIndustry.class
            && type.getMethod("isDisrupted").getDeclaringClass() == BaseIndustry.class
            && type.getMethod("getDisruptedKey").getDeclaringClass() == BaseIndustry.class;
      } catch (ReflectiveOperationException | SecurityException | LinkageError ex) {
        return Boolean.FALSE;
      }
    }
  };
  /** Called only on the first full BaseIndustry.advance() for an instance. */
  public static boolean isBaseIndustryDormantFastPathEligible(Object industry) {
    return industry != null && BASE_INDUSTRY_DORMANT_ELIGIBLE.get(industry.getClass());
  }
  // Exact Market.advance batch history

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
  private static MarketAdvanceBatchFrame requireCurrentMarketAdvanceBatch(MarketAPI market) {
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
  private static void markConstructionStateDirty(MarketScheduleState state) {
    synchronized (state) {
      long next = state.constructionMutationEpoch + 1L;
      state.constructionMutationEpoch = next <= 0L ? 1L : next;
    }
  }
  // Direct Market.advance observation

  /** Registers a transformed direct-call site before its first execution. */
  public static void registerDirectMarketCallSite(long siteId, String metadata) {
    DirectMarketObserver observer = StarsectorPrepatcherMarketDiagnostics.directObserver();
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
    DirectMarketObserver observer = StarsectorPrepatcherMarketDiagnostics.directObserver();
    MarketAdvanceObservationContext context = MARKET_ADVANCE_CONTEXT.get();
    boolean recursive = context.depth > 0;
    DirectMarketCallToken token = null;
    if (observer != null) {
      try {
        token = observer.beginDirectCall(market == null, System.identityHashCode(market), amount, siteId, metadata,
            recursive);
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
    DirectMarketObserver observer = StarsectorPrepatcherMarketDiagnostics.directObserver();
    if (observer == null) return;
    try {
      MarketAdvanceObservationContext context = MARKET_ADVANCE_CONTEXT.get();
      observer.observeMarketEntry(context.origin);
    } catch (Throwable ignored) {
      // Observation is strictly fail-open.
    }
  }
  private static void invokeObservedMarketAdvance(MarketAPI market, float amount, int origin) {
    DirectMarketObserver observer = StarsectorPrepatcherMarketDiagnostics.directObserver();
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
  private static void recordDeliveredMarketAdvance(MarketAPI market, float amount, int origin) {
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
  private static void advanceMarketImmediatelyWithPending(MarketAPI market, float amount, int origin) {
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
  private static boolean updateConstructionMode(MarketAPI market, MarketScheduleState state) {
    return updateConstructionMode(market, state, false);
  }
  /**
   * Construction policy is mutation-driven. Structural barriers increment an
   * epoch; the expensive queue/industry scan runs only for a new epoch or a
   * rare safety audit. Save uses forceAudit=true.
   */
  private static boolean updateConstructionMode(MarketAPI market, MarketScheduleState state, boolean forceAudit) {
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

    ConstructionDiagnostics diagnostics = StarsectorPrepatcherMarketDiagnostics.constructionDiagnostics();
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

    if (diagnostics != null && (finalReasonMask != 0 || oldReasonMask != 0 || oldActive != active)) {
      diagnostics.observe(constructionSnapshot(market, probe),
          forceAudit ? "FORCED" : (dirtyScan ? "DIRTY" : "SAFETY_AUDIT"), observedEpoch, auditedEpoch, lastAuditBatch,
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
  private static int constructionBuildingReason(boolean reportedBuilding, Boolean rawBuilding) {
    if (rawBuilding == null) { return reportedBuilding ? CONSTRUCTION_REASON_INDUSTRY_BUILDING : 0; }
    if (rawBuilding.booleanValue()) { return CONSTRUCTION_REASON_INDUSTRY_BUILDING; }
    return reportedBuilding ? CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW : 0;
  }
  private static VarHandle baseIndustryBuildingHandle() {
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
  private static Boolean rawBaseIndustryBuilding(Industry industry) {
    if (!(industry instanceof BaseIndustry)) return null;
    VarHandle handle = baseIndustryBuildingHandle();
    if (handle == null) { throw new IllegalStateException("BaseIndustry.building field is unavailable"); }
    return (boolean) handle.get(industry);
  }
  private static void recordConstructionReasonCounters(int reasonMask) {
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
  private static void updateMax(AtomicLong target, long value) {
    long current;
    while (value > (current = target.get()) && !target.compareAndSet(current, value)) {
      // Retry only when another observation raised the high-water concurrently.
    }
  }
  private static boolean wouldCreateNewPendingRun(MarketScheduleState state, float amount) {
    PendingStepRun last = state.pendingRuns == null ? null : state.pendingRuns.peekLast();
    return last == null || Float.floatToRawIntBits(last.amount) != Float.floatToRawIntBits(amount);
  }
  private static boolean appendPendingStepLocked(MarketScheduleState state, float amount) {
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
  private static void clearPendingLocked(MarketScheduleState state) {
    state.pendingAmount = 0f;
    state.pendingSteps = 0;
    if (state.pendingRuns != null) state.pendingRuns.clear();
    state.debtSequence = 0L;
    state.sourceMask = 0;
  }
  private static MarketAdvanceBatchFrame detachPendingLocked(MarketAPI market, MarketScheduleState state,
      boolean visible) {
    if (state.pendingSteps <= 0) return null;
    MarketAdvanceBatchStack stack = MARKET_ADVANCE_BATCH_STACK.get();
    MarketAdvanceBatchFrame frame = stack.push(market, state, visible);
    clearPendingLocked(state);
    state.inAdvance = true;
    if (visible) StarsectorPrepatcherMetrics.MARKET_BATCH_CONTEXTS_CREATED.increment();
    return frame;
  }
  private static int originForStateLocked(MarketScheduleState state, int fallbackOrigin) {
    if ((state.sourceMask & (1 << 1)) != 0) { return MARKET_ORIGIN_PLANET_CONDITION_SCHEDULED; }
    if ((state.sourceMask & 1) != 0) return MARKET_ORIGIN_SCHEDULED;
    return fallbackOrigin;
  }
  private static int deliverPendingCoalesced(MarketAPI market, MarketScheduleState state, int fallbackOrigin,
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
  private static int deliverPendingExact(MarketAPI market, MarketScheduleState state, int fallbackOrigin,
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
  private static int deliverPendingForSave(MarketAPI market, MarketScheduleState state, boolean exact) {
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
  private static long nextMarketSchedulerRenderBatch() {
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
  private static void finishMarketSchedulerBatch(MarketSchedulerBatch batch) {
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
  private static boolean prepareMarketForBatch(MarketSchedulerBatch batch, MarketAPI market, MarketScheduleState state,
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
  private static void deliverMarketAtBatchEnd(MarketSchedulerBatch batch, MarketAPI market, MarketScheduleState state) {
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
  private static long debtSequence(MarketScheduleState state) {
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
  /** Copies under each state's existing monitor; no mutable state or game object escapes. */
  static SchedulerSnapshot schedulerSnapshot() {
    ArrayList<MarketSnapshot> rows = new ArrayList<>();
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
        rows.add(new MarketSnapshot(marketId, marketName, System.identityHashCode(market), pendingSteps, pendingAmount,
            pendingRuns, pendingHistory, deliveredCallbacks, deliveredAmount, lastDeliveredSequence,
            lastDeliveredOrigin, lastDeliveredNanos, debtSequence, lastSource, sourceMask, perSimulationTick,
            constructionFullRate, constructionReasonMask, inAdvance, disabled, nextDueBatch, lastTouchedBatch));
      }
    } catch (Throwable failure) {
      StarsectorPrepatcherMetrics.MARKET_SCHEDULER_STATE_FAILURES.increment();
      PrepatcherLog.warn("Could not capture market scheduler baseline: " + failure);
    }
    return new SchedulerSnapshot(List.copyOf(rows), marketSchedulerReady);
  }
  record SchedulerSnapshot(List<MarketSnapshot> markets, boolean ready) {
  }
  record MarketSnapshot(String marketId, String marketName, int identityHash, int pendingSteps, float pendingAmount,
      int pendingRuns, String pendingHistory, long deliveredCallbacks, double deliveredAmount,
      long lastDeliveredSequence, int lastDeliveredOrigin, long lastDeliveredNanos, long debtSequence, int lastSource,
      int sourceMask, boolean perSimulationTick, boolean constructionFullRate, int constructionReasonMask,
      boolean inAdvance, boolean disabled, long nextDueBatch, long lastTouchedBatch) {
  }

  public static String dumpMarketSchedulerBaseline(String reason) {
    return StarsectorPrepatcherMarketDiagnostics.dumpMarketSchedulerBaseline(reason);
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
  private static ConstructionSnapshot constructionSnapshot(MarketAPI market, ConstructionProbe probe) {
    String marketId = safeMarketText(market, true);
    String marketName = safeMarketText(market, false);
    return new ConstructionSnapshot(System.identityHashCode(market), marketId, marketName, probe.queueClass,
        probe.queueSize, probe.industryCount, probe.industryIndex, probe.industryId, probe.industryClass,
        probe.industryBuilding, probe.industryEffectiveBuilding, probe.industryUpgrading, probe.buildingIndustryIndex,
        probe.buildingIndustryId, probe.buildingIndustryClass, probe.buildingRawBuildingField,
        probe.buildingBuildProgress, probe.buildingBuildTime, probe.buildingUpgradeId, probe.buildingIsFunctional,
        probe.reportedBuildingWithoutRawIndustryIndex, probe.reportedBuildingWithoutRawIndustryId,
        probe.reportedBuildingWithoutRawIndustryClass, probe.reportedBuildingWithoutRawRawBuildingField,
        probe.reportedBuildingWithoutRawBuildProgress, probe.reportedBuildingWithoutRawBuildTime,
        probe.reportedBuildingWithoutRawUpgradeId, probe.reportedBuildingWithoutRawIsFunctional,
        probe.upgradingIndustryIndex, probe.upgradingIndustryId, probe.upgradingIndustryClass,
        probe.upgradingRawBuildingField, probe.upgradingBuildProgress, probe.upgradingBuildTime,
        probe.upgradingUpgradeId, probe.upgradingIsFunctional, probe.exceptionClass, probe.exceptionMessage);
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

  }
}
