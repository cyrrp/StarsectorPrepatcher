package com.fs.starfarer.api;

import com.fs.starfarer.campaign.CampaignEngine;
import com.fs.starfarer.campaign.econ.Economy;
import com.fs.starfarer.campaign.econ.Market;
import com.fs.starfarer.campaign.econ.reach.ReachEconomy;
import com.starsector.prepatcher.agent.PrepatcherConfig;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/** Campaign cache lifecycle: generation tracking, coordinated reset, and idle maintenance. */
final class StarsectorPrepatcherCampaignCaches {

  static volatile LongSupplier NANO_CLOCK = System::nanoTime;
  static final Object CAMPAIGN_CACHE_LIFECYCLE_LOCK = new Object();
  static final Object CAMPAIGN_CACHE_MAINTENANCE_LOCK = new Object();
  static volatile long cacheMaintenanceGeneration = -1L;
  static volatile long nextCacheMaintenanceNanos;
  /**
   * The engine reference must itself remain weak: lifecycle hooks are a cleanup
   * trigger, not a new process-lifetime owner of campaign state.
   */
  static volatile WeakReference<Object> activeCampaignEngine = new WeakReference<>(null);
  /**
   * ReachEconomy ownership is tracked separately because an Economy.advance()
   * that outlives an engine switch must never publish into the new generation.
   * This reference remains weak for the same reason as activeCampaignEngine.
   */
  static volatile WeakReference<ReachEconomy> activeReachEconomy = new WeakReference<>(null);
  static volatile boolean campaignEngineObserved;
  static volatile boolean campaignCacheGenerationActive;
  static volatile long campaignCacheGeneration;
  // Simulation ticks preserve engine ordering. Render batches, delimited by
  // CampaignEngine.setFastForwardIteration(false), own cadence so fast-forward
  // cannot multiply expensive Market.advance callbacks per visual frame.
  static final long NO_CAMPAIGN_TRANSITION = -1L;
  static long pendingCampaignTransition = NO_CAMPAIGN_TRANSITION;
  static WeakReference<Object> pendingCampaignEngine = new WeakReference<>(null);
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
        if (pendingCampaignTransition == NO_CAMPAIGN_TRANSITION && campaignEngineObserved && observed == engine
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
        StarsectorPrepatcherMetrics.CAMPAIGN_CACHE_RESETS.increment();
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
            && pendingCampaignTransition == NO_CAMPAIGN_TRANSITION && campaignEngineObserved
            && activeCampaignEngine.get() == engine && campaignCacheGenerationActive == (engine != null);
        if (noOpMatches) return;
        if (transition != NO_CAMPAIGN_TRANSITION && pendingCampaignTransition == transition
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
        StarsectorPrepatcherMetrics.CAMPAIGN_CACHE_RESETS.increment();
        clearMismatchedGeneration = true;
      }
    } catch (Throwable ignored) {
      // Fail closed: the generation remains inactive and hooks use vanilla.
    }
    if (clearMismatchedGeneration) clearCampaignCaches();
  }
  static void clearCampaignCaches() {
    // Detach each process-lifetime root instead of waiting for its monitor.
    // In-flight calls retain only their local old map/slot; epoch checks stop
    // them from publishing into a current generation, and the detached roots
    // become collectible when those calls end.
    StarsectorPrepatcherIntelRuntime.INTEL_CACHE = Collections.synchronizedMap(new WeakHashMap<>());
    StarsectorPrepatcherIntelRuntime.LABEL_INDEXES = new IdentityHashMap<>();
    StarsectorPrepatcherIntelRuntime.INTEL_ENTITY_INDEXES = new IdentityHashMap<>();
    StarsectorPrepatcherMapRuntime.HIT_CACHES = new IdentityHashMap<>();
    StarsectorPrepatcherMapRuntime.SAMPLE_CLEAR_TIMES = new WeakHashMap<>();
    StarsectorPrepatcherCampaignListeners.CAMPAIGN_LISTENER_STATES = Collections.synchronizedMap(new WeakHashMap<>());
    StarsectorPrepatcherRouteIndex.ROUTE_JUMP_INDEXES = Collections.synchronizedMap(new WeakHashMap<>());
    StarsectorPrepatcherRouteIndex.ROUTE_SYSTEM_INDEXES = Collections.synchronizedMap(new WeakHashMap<>());
    StarsectorPrepatcherCoreWorldsRuntime.campaignLifecycleReset();
    StarsectorPrepatcherHyperspaceHooks.campaignLifecycleReset(campaignCacheGeneration);
    StarsectorPrepatcherStrategicJumpIndex.reset();
    StarsectorPrepatcherEconomyRuntime.ECONOMY_LOCATION_STATES = Collections.synchronizedMap(new WeakHashMap<>());
    StarsectorPrepatcherMarketScheduler.MARKET_SCHEDULER_STATES =
        new StarsectorPrepatcherMarketScheduler.WeakIdentityMap<>();
    StarsectorPrepatcherMarketScheduler.CONSTRUCTION_QUEUE_OWNERS =
        new StarsectorPrepatcherMarketScheduler.WeakIdentityMap<>();
    StarsectorPrepatcherIntelRuntime.COMM_RELAY_SYSTEM_INDEXES = Collections.synchronizedMap(new WeakHashMap<>());
    StarsectorPrepatcherMarketScheduler.marketSchedulerSimulationTick = 0L;
    StarsectorPrepatcherMarketScheduler.marketSchedulerRenderBatch = 0L;
    cacheMaintenanceGeneration = -1L;
    nextCacheMaintenanceNanos = 0L;
    StarsectorPrepatcherMarketScheduler.MARKET_SCHEDULER_DEBT_SEQUENCE.set(0L);
    StarsectorPrepatcherMarketScheduler.MARKET_SCHEDULER_TICK.remove();
    StarsectorPrepatcherMarketScheduler.MARKET_SCHEDULER_BATCH.remove();
    StarsectorPrepatcherMarketScheduler.MARKET_ADVANCE_BATCH_STACK.remove();
    StarsectorPrepatcherMarketScheduler.MARKET_ADVANCE_INVOCATION.remove();
    StarsectorPrepatcherMapRuntime.nebulaCacheSlot = new StarsectorPrepatcherMapRuntime.NebulaCacheSlot();
    // A lifecycle callback may be re-entrant inside a transformed scratch
    // scope. Detach the old Scratch without clearing collections that the
    // interrupted vanilla method still owns, then mirror only its open-lease
    // depth into a fresh container. Subsequent hooks cannot append campaign
    // objects to the detached container, and the replacement removes itself
    // when the interrupted scope's generated finally hook completes.
    StarsectorPrepatcherScratch.SCRATCH_GAUGES = StarsectorPrepatcherScratch.ScratchGaugeSnapshot.EMPTY;
    StarsectorPrepatcherScratch.Scratch previous = StarsectorPrepatcherScratch.SCRATCH.get();
    int openLeases = previous.scopeDepth;
    StarsectorPrepatcherScratch.SCRATCH.remove();
    if (openLeases > 0) {
      StarsectorPrepatcherScratch.Scratch replacement = new StarsectorPrepatcherScratch.Scratch();
      replacement.scopeDepth = openLeases;
      replacement.removeWhenIdle = true;
      StarsectorPrepatcherScratch.SCRATCH.set(replacement);
    }
  }
  static boolean campaignCachesReady(long generation) {
    return campaignCacheGenerationActive && campaignCacheGeneration == generation && activeCampaignEngine.get() != null;
  }
  static boolean campaignCachesReady(long generation, Object engine) {
    return campaignCacheGenerationActive && campaignCacheGeneration == generation
        && activeCampaignEngine.get() == engine;
  }
  static boolean campaignCachesReady(long generation, ReachEconomy economy) {
    return economy != null && campaignCacheGenerationActive && campaignCacheGeneration == generation
        && activeCampaignEngine.get() != null && activeReachEconomy.get() == economy;
  }
  static ReachEconomy reachEconomyOf(Object engine) {
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
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    long generation = campaignCacheGeneration;
    if (c != null && c.strategicJumpDestinationIndex && campaignCachesReady(generation)
        && StarsectorPrepatcherStrategicJumpHooks
            .strategicJumpIndexCapability() == StarsectorPrepatcherStrategicJumpHooks.STRATEGIC_JUMP_CAPABILITY_READY) {
      StarsectorPrepatcherStrategicJumpIndex.maintenance(c, generation, StarsectorPrepatcherCampaignCaches::nanoTime);
    }
    runCacheMaintenance(false);
  }
  /** Forced pre-save entry. Idle TTLs and scratch grace periods still apply. */
  public static void runCacheMaintenance(boolean forced) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
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
      if (!forced && c.cacheMaintenanceIntervalMs > 0 && now < nextCacheMaintenanceNanos) { return; }
      nextCacheMaintenanceNanos = saturatedAdd(now, millisToNanos(c.cacheMaintenanceIntervalMs));
    }

    StarsectorPrepatcherIntelRuntime.maintainLabelIndexes(c, generation, now);
    StarsectorPrepatcherIntelRuntime.maintainIntelEntityIndexes(c, generation, now);
    StarsectorPrepatcherMapRuntime.maintainHitCaches(c, generation, now);
    StarsectorPrepatcherScratch.trimCurrentScratch(c, now);
    try {
      StarsectorPrepatcherHyperspaceHooks.runPoolMaintenance(now, forced);
    } catch (Throwable ignored) {
      // Hyperspace pooling is independent and optional.
    }
  }
  static long nanoTime() {
    LongSupplier clock = NANO_CLOCK;
    return clock == null ? System.nanoTime() : clock.getAsLong();
  }
  static long millisToNanos(int millis) {
    if (millis <= 0) return 0L;
    long value = (long) millis * 1_000_000L;
    return value < 0L ? Long.MAX_VALUE : value;
  }
  static long saturatedAdd(long value, long increment) {
    if (increment <= 0L) return value;
    long result = value + increment;
    if (((value ^ result) & (increment ^ result)) < 0L) return Long.MAX_VALUE;
    return result;
  }
  static boolean hoverCellFresh(long now, long atNanos, int ttlMs) {
    return ttlMs > 0 && now - atNanos < millisToNanos(ttlMs);
  }
  static boolean idleExpired(long now, long lastAccess, int ttlMs) {
    if (ttlMs <= 0) return false;
    return now - lastAccess >= millisToNanos(ttlMs);
  }
  static <K, V> boolean evictIdleAndLruForInsert(IdentityHashMap<K, V> map, K protectedOwner, long now, int idleTtlMs,
      int limit, LongAdder idleCounter, LongAdder limitCounter) {
    Iterator<Map.Entry<K, V>> iterator = map.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<K, V> entry = iterator.next();
      if (entry.getKey() != protectedOwner && ownerActiveUsers(entry.getValue()) == 0
          && idleExpired(now, ownerLastAccessNanos(entry.getValue()), idleTtlMs)) {
        iterator.remove();
        idleCounter.increment();
      }
    }
    if (map.containsKey(protectedOwner) || map.size() < limit) return true;
    K oldestKey = null;
    long oldestAccess = Long.MAX_VALUE;
    for (Map.Entry<K, V> entry : map.entrySet()) {
      if (entry.getKey() == protectedOwner || ownerActiveUsers(entry.getValue()) > 0) continue;
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
  static long ownerLastAccessNanos(Object value) {
    if (value instanceof StarsectorPrepatcherIntelRuntime.LabelIndex index) return index.lastAccessNanos;
    if (value instanceof StarsectorPrepatcherIntelRuntime.IntelEntityIndex index) return index.lastAccessNanos;
    if (value instanceof StarsectorPrepatcherMapRuntime.HitCache cache) return cache.lastAccessNanos;
    throw new IllegalArgumentException(
        "Unsupported owner-cache value: " + (value == null ? "null" : value.getClass().getName()));
  }
  static int ownerActiveUsers(Object value) {
    if (value instanceof StarsectorPrepatcherIntelRuntime.LabelIndex index) return index.activeUsers;
    if (value instanceof StarsectorPrepatcherIntelRuntime.IntelEntityIndex index) return index.activeUsers;
    if (value instanceof StarsectorPrepatcherMapRuntime.HitCache cache) return cache.activeUsers;
    throw new IllegalArgumentException(
        "Unsupported owner-cache value: " + (value == null ? "null" : value.getClass().getName()));
  }
}
