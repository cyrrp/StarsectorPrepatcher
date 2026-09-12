package com.fs.starfarer.api;

import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Strings;
import com.fs.starfarer.api.impl.campaign.submarkets.LocalResourcesSubmarketPlugin;
import com.fs.starfarer.campaign.econ.CommodityOnMarket;
import com.fs.starfarer.campaign.econ.reach.CommodityMarketData;
import com.fs.starfarer.campaign.econ.reach.ReachEconomy;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.starsector.prepatcher.agent.PrepatcherConfig;
import com.starsector.prepatcher.agent.PrepatcherLog;
import java.lang.ref.WeakReference;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Economy location maps, persistent snapshots, group index, and local-resources helpers. */
final class StarsectorPrepatcherEconomyRuntime {

  static volatile Map<ReachEconomy, EconomyLocationState> ECONOMY_LOCATION_STATES =
      Collections.synchronizedMap(new WeakHashMap<>());
  static final int ECONOMY_GROUP_INDEX_COMPONENT_REACH_ECONOMY = 1;
  static final int ECONOMY_GROUP_INDEX_COMPONENT_MARKET = 2;
  static final int ECONOMY_GROUP_INDEX_COMPONENT_ALL =
      ECONOMY_GROUP_INDEX_COMPONENT_REACH_ECONOMY | ECONOMY_GROUP_INDEX_COMPONENT_MARKET;
  static final AtomicInteger ECONOMY_GROUP_INDEX_COMPONENTS = new AtomicInteger();
  static final AtomicLong ECONOMY_GROUP_STRUCTURE_EPOCH = new AtomicLong();
  static volatile boolean economyGroupIndexReady;
  static volatile VarHandle commodityMarketDataHandle;
  static volatile boolean commodityMarketDataHandleResolved;
  // Exp8: economy, combat scratch, particles and hyperspace comm relays

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
  public static void updateEconomyLocationMapIfNeededPersistent(ReachEconomy economy, Object persistentState) {
    updateEconomyLocationMapIfNeeded0(economy, persistentState, true);
  }
  static void updateEconomyLocationMapIfNeeded0(ReachEconomy economy, Object persistentState,
      boolean persistentRequested) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    long generation = StarsectorPrepatcherCampaignCaches.campaignCacheGeneration;
    if (economy == null || c == null || !c.economyLocationCache
        || !StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation, economy)) {
      if (economy != null) {
        economy.setLocationCacheNeedsUpdate(true);
        economy.updateLocationMap();
      }
      return;
    }

    PersistentSnapshotState ownerState = persistentRequested && c.economyPersistentSnapshots
        && persistentState instanceof PersistentSnapshotState candidate && candidate.acceptKind(0) ? candidate : null;
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
        boolean audit = previous == null || epochChanged || sourceChanged || auditNs <= 0L
            || now >= ownerState.nextLocationAuditNanos;
        if (!audit) {
          forceDirty = false;
          StarsectorPrepatcherMetrics.ECONOMY_LOCATION_EPOCH_HITS.increment();
        } else {
          StarsectorPrepatcherMetrics.ECONOMY_LOCATION_AUDITS.increment();
          int mismatch = previous == null ? EconomyLocationState.SEQUENCE_MISMATCH : previous.mismatchKind(markets);
          // A direct replacement/reorder/add/remove that bypassed Economy mutators
          // must also invalidate the persistent market snapshot. Location/id-only
          // changes rebuild ReachEconomy indexes but do not require a new list snapshot.
          boolean sequenceChanged = sourceChanged || mismatch == EconomyLocationState.SEQUENCE_MISMATCH;
          if (!epochChanged && sequenceChanged) {
            ownerState.epoch++;
            observedEpoch = ownerState.epoch;
            persistentEpoch(ownerState.kind);
            persistentMismatch(0);
            StarsectorPrepatcherMetrics.ECONOMY_LOCATION_SEQUENCE_MISMATCHES.increment();
          }
          forceDirty = previous == null || epochChanged || sourceChanged || mismatch != EconomyLocationState.MATCH;
          if (!forceDirty) {
            ownerState.locationEpoch = observedEpoch;
            ownerState.nextLocationAuditNanos = nextAuditNanos(now, auditNs);
          }
        }
      } else {
        Map<ReachEconomy, EconomyLocationState> states = ECONOMY_LOCATION_STATES;
        synchronized (states) {
          if (states == ECONOMY_LOCATION_STATES
              && StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation, economy)) {
            previous = states.get(economy);
          }
        }
        forceDirty = previous == null || previous.mismatchKind(markets) != EconomyLocationState.MATCH;
      }
      StarsectorPrepatcherMetrics.ECONOMY_LOCATION_CHECKS.increment();
      if (forceDirty) StarsectorPrepatcherMetrics.ECONOMY_LOCATION_DIRTY.increment();
      else StarsectorPrepatcherMetrics.ECONOMY_LOCATION_SKIPS.increment();
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
              && StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation, economy)) {
            states.remove(economy);
          }
        }
      }
    }

    if (forceDirty) economy.setLocationCacheNeedsUpdate(true);
    // Explicit mod dirtiness stays authoritative: the original updater is always
    // invoked even when the owner-local epoch/fingerprint says nothing changed.
    economy.updateLocationMap();

    if (forceDirty && markets != null && StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation, economy)) {
      try {
        if (ownerState != null) {
          ownerState.locationState = EconomyLocationState.capture(markets, ownerState.epoch, now);
          ownerState.locationSourceIdentity = markets;
          ownerState.locationEpoch = ownerState.epoch;
          ownerState.nextLocationAuditNanos = nextAuditNanos(now, auditNs);
        } else {
          EconomyLocationState captured = EconomyLocationState.capture(markets, Long.MIN_VALUE, now);
          Map<ReachEconomy, EconomyLocationState> states = ECONOMY_LOCATION_STATES;
          synchronized (states) {
            if (states == ECONOMY_LOCATION_STATES
                && StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation, economy)) {
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
                && StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation, economy)) {
              states.remove(economy);
            }
          }
        }
      }
    }
  }
  static long nextAuditNanos(long now, long interval) {
    if (interval <= 0L) return now;
    long next = now + interval;
    return next < now ? Long.MAX_VALUE : next;
  }
  public static void registerEconomyGroupIndexComponent(int component) {
    if (component != ECONOMY_GROUP_INDEX_COMPONENT_REACH_ECONOMY && component != ECONOMY_GROUP_INDEX_COMPONENT_MARKET) {
      throw new IllegalArgumentException("Unknown economy-group index component: " + component);
    }
    int current;
    int updated;
    do {
      current = ECONOMY_GROUP_INDEX_COMPONENTS.get();
      updated = current | component;
      if (current == updated) return;
    } while (!ECONOMY_GROUP_INDEX_COMPONENTS.compareAndSet(current, updated));
    System.setProperty("starsector.prepatcher.economyGroupIndexCapabilityMask", Integer.toString(updated));
    if (updated == ECONOMY_GROUP_INDEX_COMPONENT_ALL && !economyGroupIndexReady) {
      economyGroupIndexReady = true;
      PrepatcherLog
          .info("economy-group index READY: ReachEconomy getter/mutators" + " + Market.setEconGroup mutation barrier");
    }
  }
  public static void markEconomyGroupStructureChanged() {
    ECONOMY_GROUP_STRUCTURE_EPOCH.incrementAndGet();
  }
  public static void markEconomyGroupStructureChanged(Object rawState) {
    ECONOMY_GROUP_STRUCTURE_EPOCH.incrementAndGet();
    try {
      if (rawState instanceof PersistentSnapshotState state) {
        state.invalidateGroupIndex();
      }
    } catch (Throwable ignored) {
      // The primitive epoch still invalidates every indexed owner lazily.
    }
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
    return rawState instanceof PersistentSnapshotState state ? state.epoch : Long.MIN_VALUE;
  }
  /** Persistent point-in-time snapshot with a wall-clock audit cadence. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static ArrayList borrowPersistentSnapshotTimed(Object rawState, Collection source, int auditMs, int kind) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c == null || !c.economyPersistentSnapshots || !(rawState instanceof PersistentSnapshotState state)
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
  public static ArrayList borrowPersistentSnapshotFrames(Object rawState, Collection source, int auditFrames,
      int kind) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c == null || !c.economyPersistentSnapshots || !(rawState instanceof PersistentSnapshotState state)
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
  // Economy hot paths: Local Resources and econ groups

  /**
   * Exact warm-path replacement for LocalResourcesSubmarketPlugin's legality
   * test. The exact vanilla class derives a cold result from its committed
   * local fields. The maintained AoTD fork instead peeks its authoritative
   * committed raw supply/demand object without invoking either lazy
   * CommodityMarketData construction or AoTD's local materializer.
   *
   * <p>Unknown implementations and changed owned-fork surfaces fail closed to
   * the preserved virtual getter.</p>
   */
  public static boolean localResourcesShouldHaveCommodity(MarketAPI market, CommodityOnMarketAPI commodity) {
    if (!market.isIllegal(commodity)) return true;

    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c == null || !c.localResourcesNoColdMarketData || !(commodity instanceof CommodityOnMarket)) {
      return localResourcesVanillaFallback(market, commodity);
    }

    VarHandle handle = commodityMarketDataHandle();
    if (handle == null) return localResourcesVanillaFallback(market, commodity);

    Object raw;
    try {
      raw = handle.get(commodity);
    } catch (Throwable ignored) {
      return localResourcesVanillaFallback(market, commodity);
    }

    if (StarsectorPrepatcherEconomyHotpathRuntime.isExpectedWarmMarketData(commodity, raw)) {
      StarsectorPrepatcherMetrics.LOCAL_RESOURCES_WARM_READS.increment();
      return !((CommodityMarketData) raw).getMarketShareData(market).isSourceIsIllegal();
    }

    if (StarsectorPrepatcherEconomyHotpathRuntime.isVanillaCommodity(commodity)) {
      if (raw != null) return localResourcesVanillaFallback(market, commodity);
      StarsectorPrepatcherMetrics.LOCAL_RESOURCES_COLD_AVOIDED.increment();
      return (commodity.isDemandLegal() && commodity.getMaxDemand() > 0)
          || (commodity.isSupplyLegal() && commodity.getMaxSupply() > 0);
    }

    if (StarsectorPrepatcherEconomyHotpathRuntime.isAoTDCommodity(commodity)) {
      long maxima = StarsectorPrepatcherEconomyHotpathRuntime.peekAoTDConvertedMaxima(commodity);
      if (maxima != StarsectorPrepatcherEconomyHotpathRuntime.AOTD_RAW_TOTALS_UNAVAILABLE) {
        int maxSupply = StarsectorPrepatcherEconomyHotpathRuntime.unpackAoTDMaxSupply(maxima);
        int maxDemand = StarsectorPrepatcherEconomyHotpathRuntime.unpackAoTDMaxDemand(maxima);
        StarsectorPrepatcherMetrics.LOCAL_RESOURCES_COLD_AVOIDED.increment();
        StarsectorPrepatcherMetrics.LOCAL_RESOURCES_AOTD_COLD_AVOIDED.increment();
        return (commodity.isDemandLegal() && maxDemand > 0) || (commodity.isSupplyLegal() && maxSupply > 0);
      }
    }

    return localResourcesVanillaFallback(market, commodity);
  }
  static boolean localResourcesVanillaFallback(MarketAPI market, CommodityOnMarketAPI commodity) {
    StarsectorPrepatcherMetrics.LOCAL_RESOURCES_PEEK_FALLBACKS.increment();
    return !commodity.getCommodityMarketData().getMarketShareData(market).isSourceIsIllegal();
  }
  /**
   * Renders a point-in-time Local Resources tooltip for the exact vanilla and
   * Nexerelin plugins. Unknown subclasses return false and execute the
   * preserved original method.
   */
  public static boolean renderLocalResourcesTooltipSnapshot(LocalResourcesSubmarketPlugin plugin,
      TooltipMakerAPI tooltip, boolean expanded) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c == null || !c.localResourcesTooltipSnapshot || plugin == null || tooltip == null) { return false; }
    String className = plugin.getClass().getName();
    if (!"com.fs.starfarer.api.impl.campaign.submarkets.LocalResourcesSubmarketPlugin".equals(className)
        && !"exerelin.campaign.submarkets.Nex_LocalResourcesSubmarketPlugin".equals(className)) {
      return false;
    }
    MarketAPI market = plugin.getMarket();
    if (market == null) return false;

    List<CommodityOnMarketAPI> commodities = new ArrayList<>(market.getAllCommodities());
    List<Object[]> rows = new ArrayList<>(commodities.size());
    for (CommodityOnMarketAPI commodity : commodities) {
      rows.add(new Object[]{commodity, Integer.valueOf(plugin.getStockpileLimit(commodity))});
    }
    rows.sort((left, right) -> Integer.compare(((Integer) right[1]).intValue(), ((Integer) left[1]).intValue()));

    float opad = 10f;
    tooltip.beginGridFlipped(400f, 1, 70f, opad);
    int shown = 0;
    for (Object[] row : rows) {
      CommodityOnMarketAPI commodity = (CommodityOnMarketAPI) row[0];
      if (commodity.isNonEcon()) continue;
      if (commodity.getCommodity().isMeta()) continue;
      if (!plugin.shouldHaveCommodity(commodity)) continue;

      int rawLimit = ((Integer) row[1]).intValue();
      int displayLimit = Math.round(rawLimit * plugin.getStockpilingAddRateMult(commodity));
      if (displayLimit <= 0) continue;
      tooltip.addToGrid(0, shown++, commodity.getCommodity().getName(), Misc.getWithDGS(displayLimit));
    }

    tooltip.addPara(
        "A portion of the resources produced by the colony will be made available here. "
            + "These resources can be extracted from the colony's economy for a cost equal to %s of their base value. "
            + "This cost will be deducted at the end of the month.",
        opad, Misc.getHighlightColor(),
        "" + (int) Math.round(LocalResourcesSubmarketPlugin.STOCKPILE_COST_MULT * 100f) + "%");
    tooltip.addPara(
        "These resources can also be used to counter temporary shortages, for a "
            + "cost equal to %s of their base value. If additional resources are placed here, they "
            + "will be used as well, at no cost.",
        opad, Misc.getHighlightColor(),
        "" + (int) Math.round(LocalResourcesSubmarketPlugin.STOCKPILE_SHORTAGE_COST_MULT * 100f) + "%");
    tooltip.addSectionHeading("Stockpiled per month", market.getFaction().getBaseUIColor(),
        market.getFaction().getDarkUIColor(), Alignment.MID, opad);
    if (shown > 0) {
      tooltip.addGrid(opad);
      tooltip.addPara("Stockpiles are limited to %s the monthly rate.", opad, Misc.getHighlightColor(),
          "" + (int) LocalResourcesSubmarketPlugin.STOCKPILE_MAX_MONTHS + Strings.X);
    } else {
      tooltip.addPara("No stockpiling.", opad);
    }
    return true;
  }
  static VarHandle commodityMarketDataHandle() {
    if (!commodityMarketDataHandleResolved) {
      synchronized (StarsectorPrepatcherHooks.class) {
        if (!commodityMarketDataHandleResolved) {
          try {
            MethodHandles.Lookup lookup =
                MethodHandles.privateLookupIn(CommodityOnMarket.class, MethodHandles.lookup());
            commodityMarketDataHandle =
                lookup.findVarHandle(CommodityOnMarket.class, "commodityMarketData", CommodityMarketData.class);
          } catch (Throwable ignored) {
            commodityMarketDataHandle = null;
          }
          commodityMarketDataHandleResolved = true;
        }
      }
    }
    return commodityMarketDataHandle;
  }
  /**
   * Returns a new mutable list matching ReachEconomy's vanilla contract, or
   * null when the capability/lifecycle/fork contract is not currently safe.
   * The transformed wrapper interprets null as a preserved raw-method call.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static List borrowMarketsInGroupIndexed(ReachEconomy economy, Object rawState, String group) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    long generation = StarsectorPrepatcherCampaignCaches.campaignCacheGeneration;
    if (c == null || !c.economyGroupIndex || !economyGroupIndexReady
        || !StarsectorPrepatcherEconomyHotpathRuntime.isReachEconomyEligible(economy)
        || !(rawState instanceof PersistentSnapshotState state)
        || !StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation, economy)) {
      StarsectorPrepatcherMetrics.ECONOMY_GROUP_INDEX_FALLBACKS.increment();
      return null;
    }

    try {
      List<MarketAPI> source = economy.getMarkets();
      if (source == null) {
        StarsectorPrepatcherMetrics.ECONOMY_GROUP_INDEX_FALLBACKS.increment();
        return null;
      }
      long structureEpoch = ECONOMY_GROUP_STRUCTURE_EPOCH.get();
      List result =
          state.borrowMarketsInGroup(source, group, generation, structureEpoch, Math.max(0, c.economyStructureAuditMs));
      if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation, economy)
          || structureEpoch != ECONOMY_GROUP_STRUCTURE_EPOCH.get()) {
        state.invalidateGroupIndex();
        StarsectorPrepatcherMetrics.ECONOMY_GROUP_INDEX_FALLBACKS.increment();
        return null;
      }
      return result;
    } catch (Throwable ignored) {
      state.invalidateGroupIndex();
      StarsectorPrepatcherMetrics.ECONOMY_GROUP_INDEX_FALLBACKS.increment();
      return null;
    }
  }
  /** Exact ordered fingerprint used only to decide whether Economy.advance()
   * needs to set ReachEconomy's private dirty flag automatically. */
  static final class EconomyLocationState {
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

    EconomyLocationState(Object sourceIdentity, WeakReference<MarketAPI>[] markets,
        WeakReference<LocationAPI>[] locations, String[] marketIds, String[] locationIds, long structureEpoch,
        long auditedAtNanos) {
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
    static EconomyLocationState capture(List<MarketAPI> live, long structureEpoch, long auditedAtNanos) {
      int size = live.size();
      WeakReference<MarketAPI>[] markets = (WeakReference<MarketAPI>[]) new WeakReference<?>[size];
      WeakReference<LocationAPI>[] locations = (WeakReference<LocationAPI>[]) new WeakReference<?>[size];
      String[] marketIds = new String[size];
      String[] locationIds = new String[size];
      for (int i = 0; i < size; i++) {
        MarketAPI market = live.get(i);
        if (market == null) { throw new IllegalStateException("Null market in ReachEconomy market list"); }
        LocationAPI location = market.getContainingLocation();
        markets[i] = new WeakReference<>(market);
        locations[i] = new WeakReference<>(location);
        marketIds[i] = market.getId();
        locationIds[i] = location == null ? null : location.getId();
      }
      return new EconomyLocationState(live, markets, locations, marketIds, locationIds, structureEpoch, auditedAtNanos);
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
          if (location != locations[i].get() || !Objects.equals(market == null ? null : market.getId(), marketIds[i])
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
  static void persistentHit(int kind) {
    switch (kind) {
      case 0 -> StarsectorPrepatcherMetrics.ECONOMY_MARKET_PERSISTENT_HITS.increment();
      case 1 -> StarsectorPrepatcherMetrics.MARKET_CONDITION_PERSISTENT_HITS.increment();
      case 2 -> StarsectorPrepatcherMetrics.MARKET_INDUSTRY_PERSISTENT_HITS.increment();
      default -> {
      }
    }
  }
  static void persistentAudit(int kind) {
    StarsectorPrepatcherMetrics.ECONOMY_PERSISTENT_SNAPSHOT_AUDITS.increment();
    switch (kind) {
      case 0 -> StarsectorPrepatcherMetrics.ECONOMY_MARKET_PERSISTENT_AUDITS.increment();
      case 1 -> StarsectorPrepatcherMetrics.MARKET_CONDITION_PERSISTENT_AUDITS.increment();
      case 2 -> StarsectorPrepatcherMetrics.MARKET_INDUSTRY_PERSISTENT_AUDITS.increment();
      default -> {
      }
    }
  }
  static void persistentMismatch(int kind) {
    StarsectorPrepatcherMetrics.ECONOMY_PERSISTENT_SNAPSHOT_MISMATCHES.increment();
    switch (kind) {
      case 0 -> StarsectorPrepatcherMetrics.ECONOMY_MARKET_PERSISTENT_MISMATCHES.increment();
      case 1 -> StarsectorPrepatcherMetrics.MARKET_CONDITION_PERSISTENT_MISMATCHES.increment();
      case 2 -> StarsectorPrepatcherMetrics.MARKET_INDUSTRY_PERSISTENT_MISMATCHES.increment();
      default -> {
      }
    }
  }
  static void persistentRebuild(int kind, int elements) {
    StarsectorPrepatcherMetrics.ECONOMY_PERSISTENT_SNAPSHOT_REBUILDS.increment();
    StarsectorPrepatcherMetrics.ECONOMY_PERSISTENT_SNAPSHOT_ELEMENTS.add(elements);
    switch (kind) {
      case 0 -> {
        StarsectorPrepatcherMetrics.ECONOMY_MARKET_PERSISTENT_REBUILDS.increment();
        StarsectorPrepatcherMetrics.ECONOMY_MARKET_PERSISTENT_ELEMENTS.add(elements);
      }
      case 1 -> {
        StarsectorPrepatcherMetrics.MARKET_CONDITION_PERSISTENT_REBUILDS.increment();
        StarsectorPrepatcherMetrics.MARKET_CONDITION_PERSISTENT_ELEMENTS.add(elements);
      }
      case 2 -> {
        StarsectorPrepatcherMetrics.MARKET_INDUSTRY_PERSISTENT_REBUILDS.increment();
        StarsectorPrepatcherMetrics.MARKET_INDUSTRY_PERSISTENT_ELEMENTS.add(elements);
      }
      default -> {
      }
    }
  }
  static void persistentEpoch(int kind) {
    StarsectorPrepatcherMetrics.PERSISTENT_STRUCTURE_EPOCHS.increment();
  }
  /**
   * Owner-local copy-on-write snapshot state. Snapshots are never mutated after
   * publication, so a nested/re-entrant advance may rebuild the field without
   * invalidating an iterator held by the outer call. The Economy instance also
   * stores its ReachEconomy location fingerprint here, removing the synchronized
   * WeakHashMap lookup from the persistent hot path.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  static final class PersistentSnapshotState {
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

    long groupIndexGeneration = Long.MIN_VALUE;
    long groupIndexStructureEpoch = Long.MIN_VALUE;
    Object groupIndexSourceIdentity;
    MarketAPI[] groupIndexMarkets;
    String[] groupIndexGroups;
    Map<String, int[]> groupIndexByGroup;
    long nextGroupIndexAuditNanos;

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

    private ArrayList rebuild(Collection source, long observedEpoch, int audit, boolean timed, int requestedKind) {
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

    List borrowMarketsInGroup(List<MarketAPI> source, String group, long generation, long structureEpoch, int auditMs) {
      boolean rebuild = groupIndexMarkets == null || groupIndexByGroup == null || groupIndexGeneration != generation
          || groupIndexStructureEpoch != structureEpoch || groupIndexSourceIdentity != source
          || groupIndexMarkets.length != source.size();
      long now = System.nanoTime();
      long interval = (long) auditMs * 1_000_000L;

      if (!rebuild && (interval == 0L || now >= nextGroupIndexAuditNanos)) {
        StarsectorPrepatcherMetrics.ECONOMY_GROUP_INDEX_AUDITS.increment();
        if (!groupIndexMatches(source)) {
          StarsectorPrepatcherMetrics.ECONOMY_GROUP_INDEX_MISMATCHES.increment();
          rebuild = true;
        } else {
          nextGroupIndexAuditNanos = nextAuditNanos(now, interval);
        }
      }
      if (rebuild) {
        rebuildGroupIndex(source, generation, structureEpoch, now, interval);
      }

      int[] indices = groupIndexByGroup.get(group);
      if (indices == null) {
        StarsectorPrepatcherMetrics.ECONOMY_GROUP_INDEX_HITS.increment();
        return new ArrayList();
      }
      ArrayList result = new ArrayList(indices.length);
      for (int index : indices) result.add(groupIndexMarkets[index]);
      StarsectorPrepatcherMetrics.ECONOMY_GROUP_INDEX_HITS.increment();
      return result;
    }

    private void rebuildGroupIndex(List<MarketAPI> source, long generation, long structureEpoch, long now,
        long interval) {
      int size = source.size();
      MarketAPI[] markets = new MarketAPI[size];
      String[] groups = new String[size];
      LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
      for (int i = 0; i < size; i++) {
        MarketAPI market = source.get(i);
        String group = market.getEconGroup();
        markets[i] = market;
        groups[i] = group;
        counts.put(group, counts.getOrDefault(group, 0) + 1);
      }

      LinkedHashMap<String, int[]> byGroup = new LinkedHashMap<>();
      HashMap<String, Integer> positions = new HashMap<>();
      for (Map.Entry<String, Integer> entry : counts.entrySet()) {
        byGroup.put(entry.getKey(), new int[entry.getValue()]);
        positions.put(entry.getKey(), 0);
      }
      for (int i = 0; i < size; i++) {
        String group = groups[i];
        int position = positions.get(group);
        byGroup.get(group)[position] = i;
        positions.put(group, position + 1);
      }

      // Publish only after every market/group read and allocation succeeds.
      groupIndexGeneration = generation;
      groupIndexStructureEpoch = structureEpoch;
      groupIndexSourceIdentity = source;
      groupIndexMarkets = markets;
      groupIndexGroups = groups;
      groupIndexByGroup = byGroup;
      nextGroupIndexAuditNanos = nextAuditNanos(now, interval);
      StarsectorPrepatcherMetrics.ECONOMY_GROUP_INDEX_BUILDS.increment();
      StarsectorPrepatcherMetrics.ECONOMY_GROUP_INDEX_ELEMENTS.add(size);
    }

    private boolean groupIndexMatches(List<MarketAPI> source) {
      try {
        if (groupIndexMarkets == null || groupIndexGroups == null || groupIndexMarkets.length != source.size())
          return false;
        for (int i = 0; i < groupIndexMarkets.length; i++) {
          MarketAPI market = source.get(i);
          if (groupIndexMarkets[i] != market || !Objects.equals(groupIndexGroups[i], market.getEconGroup())) {
            return false;
          }
        }
        return true;
      } catch (Throwable ignored) {
        return false;
      }
    }

    void invalidateGroupIndex() {
      groupIndexGeneration = Long.MIN_VALUE;
      groupIndexStructureEpoch = Long.MIN_VALUE;
      groupIndexSourceIdentity = null;
      groupIndexMarkets = null;
      groupIndexGroups = null;
      groupIndexByGroup = null;
      nextGroupIndexAuditNanos = 0L;
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
}
