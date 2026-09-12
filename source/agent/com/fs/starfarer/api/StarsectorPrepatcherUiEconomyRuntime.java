package com.fs.starfarer.api;

import static com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge.*;
import static com.fs.starfarer.api.StarsectorPrepatcherAotdContract.AOTD_UI_CARGO_LIVE_MARKET;
import static com.fs.starfarer.api.StarsectorPrepatcherAotdContract.AOTD_UI_CARGO_SYNTHETIC;
import static com.fs.starfarer.api.StarsectorPrepatcherAotdContract.AOTD_UI_ECONOMY_ACTION_CARGO;
import static com.fs.starfarer.api.StarsectorPrepatcherAotdContract.AOTD_UI_ECONOMY_ACTION_MARKET_MUTATION;
import static com.fs.starfarer.api.StarsectorPrepatcherAotdContract.AOTD_UI_ECONOMY_ACTION_MARKET_OPEN;

import com.starsector.prepatcher.agent.PrepatcherConfig;
import com.starsector.prepatcher.agent.PrepatcherLog;

import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.campaign.econ.CommodityOnMarket;
import com.fs.starfarer.campaign.econ.Economy;
import com.fs.starfarer.campaign.econ.Market;
import com.fs.starfarer.campaign.econ.contract.iter.MultiFrameTask;
import com.fs.starfarer.campaign.econ.reach.CommodityMarketData;
import com.fs.starfarer.campaign.econ.reach.FinishEconomyUpdateTask;
import com.fs.starfarer.campaign.econ.reach.ImmigrationTask;
import com.fs.starfarer.campaign.econ.reach.ReachEconomy;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.LongAdder;

/** Exact vanilla/AoTD UI economy-step interception and local refresh machinery. */
public final class StarsectorPrepatcherUiEconomyRuntime {
  private static final String VANILLA_ECONOMY_CLASS = "com.fs.starfarer.campaign.econ.Economy";
  private static final String VANILLA_REACH_ECONOMY_CLASS = "com.fs.starfarer.campaign.econ.reach.ReachEconomy";

  private static final LongAdder DETACHED_CARGO_VANILLA_STEPS_SKIPPED = new LongAdder();
  private static final LongAdder LOOT_TRANSFER_VANILLA_STEPS_SKIPPED = new LongAdder();
  private static final LongAdder DETACHED_CARGO_AOTD_DISPATCHES = new LongAdder();
  private static final LongAdder LOOT_TRANSFER_AOTD_DISPATCHES = new LongAdder();
  private static final LongAdder SYNTHETIC_UI_UNKNOWN_MODE_FALLBACKS = new LongAdder();
  private static final LongAdder DETACHED_CARGO_UNKNOWN_ECONOMY_FALLBACKS = new LongAdder();
  private static final LongAdder CONDITION_ONLY_MARKET_OPENS_DETECTED = new LongAdder();
  private static final LongAdder CONDITION_ONLY_VANILLA_STEPS_SKIPPED = new LongAdder();
  private static final LongAdder CONDITION_ONLY_MARKETS_FOUND_IN_ECONOMY = new LongAdder();
  private static final LongAdder CONDITION_ONLY_UNKNOWN_ECONOMY_FALLBACKS = new LongAdder();

  // One-shot coalescing token for the immediate Cargo-panel tripleStep after
  // a successful exact-vanilla live-market localization. The weak references
  // prevent campaign/classloader retention across load boundaries.
  private static final Object VANILLA_MARKET_OPEN_LOCK = new Object();
  private static WeakReference<Object> vanillaMarketOpenEconomy = new WeakReference<>(null);
  private static WeakReference<Object> vanillaMarketOpenMarket = new WeakReference<>(null);
  private static long vanillaMarketOpenFingerprint;
  private static long vanillaMarketOpenThreadId;
  private static final LongAdder VANILLA_MARKET_OPENS_DETECTED = new LongAdder();
  private static final LongAdder VANILLA_MARKET_OPENS_LOCALIZED = new LongAdder();
  private static final LongAdder VANILLA_MARKET_OPEN_LOCALIZATION_FAILURES = new LongAdder();
  private static final LongAdder VANILLA_MARKET_OPEN_UNKNOWN_FALLBACKS = new LongAdder();
  private static final LongAdder VANILLA_MARKET_OPEN_CARGO_STEPS_COALESCED = new LongAdder();
  private static final LongAdder VANILLA_MARKET_OPEN_CARGO_FINGERPRINT_MISMATCHES = new LongAdder();

  // One-shot UI market-mutation context. Weak market identity prevents retention if a
  // third-party UI aborts before recreateWithEconUpdate().
  // Layout: market weak ref, reason, scope, campaign epoch, economy epoch,
  // immutable sorted commodity IDs.
  private static final ThreadLocal<Object[]> UI_MARKET_MUTATION_CONTEXT = new ThreadLocal<>();
  // Monotonic for one setter batch: a failed/unknown diagnostic read forces
  // the next shared economy guard to keep its original global invocation.
  private static final ThreadLocal<Boolean> UI_MARKET_MUTATION_POISONED = new ThreadLocal<>();
  // Trade state is committed by twConfirm() before confirmTransaction()
  // reaches its economy step. The exact transformer therefore establishes
  // this isolated weak preparation token immediately before twConfirm(), so
  // scheduler debt is replayed against the pre-transaction state. A stale
  // token cannot affect another mutation type and is overwritten by the next
  // exact trade preparation.
  // Layout: market weak ref, campaign epoch, economy epoch.
  private static final ThreadLocal<Object[]> TRADE_MUTATION_PREPARATION = new ThreadLocal<>();
  private static final LongAdder UI_MARKET_MUTATIONS_RECORDED = new LongAdder();
  private static final LongAdder UI_MARKET_MUTATIONS_LOCALIZED = new LongAdder();
  private static final LongAdder UI_MARKET_MUTATION_GLOBAL_FALLBACKS = new LongAdder();
  private static final LongAdder UI_MARKET_MUTATION_IDENTITY_MISMATCHES = new LongAdder();
  private static final LongAdder UI_MARKET_MUTATION_AOTD_CONSUMES = new LongAdder();
  private static final LongAdder AFFECTED_COMMODITY_REFRESHES = new LongAdder();
  private static final LongAdder AFFECTED_COMMODITY_GLOBAL_FALLBACKS = new LongAdder();
  private static final LongAdder TRADE_AFFECTED_COMMODITY_REFRESHES = new LongAdder();
  private static final LongAdder FREE_PORT_AFFECTED_COMMODITY_REFRESHES = new LongAdder();
  private static final LongAdder INDUSTRY_AFFECTED_COMMODITY_REFRESHES = new LongAdder();
  private static final LongAdder INDUSTRY_LOCAL_COMMITS = new LongAdder();

  private static volatile boolean aotdUiEconomyDispatchConfigured;
  private static volatile boolean aotdUiEconomyDispatchOperational;
  private static volatile boolean detachedCargoSkipConfigured;
  private static volatile boolean lootTransferSkipConfigured;
  private static volatile boolean conditionOnlyMarketOpenSkipConfigured;
  private static volatile boolean vanillaMarketOpenLocalizationConfigured;
  private static volatile boolean uiMarketMutationRefreshConfigured;
  private static volatile boolean vanillaDetachedCargoEconomyContractOperational;
  private static volatile boolean vanillaMarketOpenEconomyContractOperational;
  private static volatile boolean vanillaMarketOpenReachContractOperational;
  private static volatile boolean commodityMarketDataContractOperational;

  private StarsectorPrepatcherUiEconomyRuntime() {}

  static void configure(PrepatcherConfig config) {
    detachedCargoSkipConfigured = config.campaignCargoNoGlobalEconomyStep;
    lootTransferSkipConfigured = config.lootTransferNoGlobalEconomyStep;
    conditionOnlyMarketOpenSkipConfigured = config.planetConditionMarketOpenNoGlobalEconomyStep;
    vanillaMarketOpenLocalizationConfigured = config.vanillaMarketOpenLocalization;
    uiMarketMutationRefreshConfigured = config.uiMarketMutationRefresh;
    aotdUiEconomyDispatchConfigured = detachedCargoSkipConfigured || lootTransferSkipConfigured
        || conditionOnlyMarketOpenSkipConfigured || uiMarketMutationRefreshConfigured;
    // Capability bit 9 describes the bridge ABI, not whether a particular
    // optional UI optimization is enabled in this profile.
    aotdUiEconomyDispatchOperational = true;
    vanillaDetachedCargoEconomyContractOperational = false;
    vanillaMarketOpenEconomyContractOperational = false;
    vanillaMarketOpenReachContractOperational = false;
    commodityMarketDataContractOperational = false;
    clearVanillaMarketOpenCoalescingToken();
    clearUiMarketMutationBatch();
    clearPreparedTradeMutation();
  }

  static boolean isUiMarketMutationRefreshOperational() {
    return uiMarketMutationRefreshConfigured && aotdUiEconomyDispatchOperational;
  }

  static boolean isUiEconomyDispatchOperational() { return aotdUiEconomyDispatchOperational; }

  static long detachedCargoAoTDDispatches() {
    return DETACHED_CARGO_AOTD_DISPATCHES.sum();
  }

  static long lootTransferAoTDDispatches() {
    return LOOT_TRANSFER_AOTD_DISPATCHES.sum();
  }

  static void disableUiEconomyDispatch() {
    aotdUiEconomyDispatchOperational = false;
    vanillaMarketOpenLocalizationConfigured = false;
    vanillaDetachedCargoEconomyContractOperational = false;
    vanillaMarketOpenEconomyContractOperational = false;
    vanillaMarketOpenReachContractOperational = false;
    commodityMarketDataContractOperational = false;
    clearVanillaMarketOpenCoalescingToken();
    clearUiMarketMutationBatch();
  }

  private static final int SYNTHETIC_UI_NONE = 0;
  private static final int SYNTHETIC_UI_DETACHED_CARGO = 1;
  private static final int SYNTHETIC_UI_LOOT_TRANSFER = 2;

  private static int classifySyntheticNonMarketUi(boolean syntheticMarket, Object mode, Object outpost,
      Object otherCargo) {
    if (!aotdUiEconomyDispatchOperational || !syntheticMarket || outpost != null
        || !(mode instanceof Enum<?> enumMode)) {
      return SYNTHETIC_UI_NONE;
    }
    String name = enumMode.name();
    if (detachedCargoSkipConfigured && "CARGO".equals(name) && otherCargo == null) {
      return SYNTHETIC_UI_DETACHED_CARGO;
    }
    if (lootTransferSkipConfigured && "LOOT".equals(name) && isCargoData(otherCargo)) {
      return SYNTHETIC_UI_LOOT_TRANSFER;
    }
    if (("CARGO".equals(name) || "LOOT".equals(name)) && (detachedCargoSkipConfigured || lootTransferSkipConfigured)) {
      SYNTHETIC_UI_UNKNOWN_MODE_FALLBACKS.increment();
    }
    return SYNTHETIC_UI_NONE;
  }

  private static boolean isCargoData(Object value) {
    if (value == null) return false;
    for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
      if ("com.fs.starfarer.campaign.fleet.CargoData".equals(type.getName())) { return true; }
    }
    return false;
  }

  private static boolean isPlanetConditionMarketOnly(Object market) {
    if (market == null) return false;
    try {
      Method method = market.getClass().getMethod("isPlanetConditionMarketOnly");
      Object result = method.invoke(market);
      return Boolean.TRUE.equals(result);
    } catch (ReflectiveOperationException | LinkageError | SecurityException failure) {
      return false;
    }
  }

  /**
   * Returns true only for the proven detached-Cargo call when both the outer
   * economy and its reach implementation are the exact vanilla classes.
   * Subclasses and replacement economies preserve their original tripleStep().
   */
  public static boolean shouldSkipVanillaDetachedCargoEconomyStep(Object economy, boolean syntheticMarket, Object mode,
      Object outpost, Object otherCargo) {
    return shouldSkipVanillaCargoEconomyStep(economy, null, syntheticMarket, mode, outpost, otherCargo);
  }

  /**
   * Suppresses either a proven synthetic Cargo/LOOT step or the immediate
   * duplicate Cargo tripleStep following an exact localized live-market open.
   */
  public static boolean shouldSkipVanillaCargoEconomyStep(Object economy, Object market, boolean syntheticMarket,
      Object mode, Object outpost, Object otherCargo) {
    try {
      int kind = classifySyntheticNonMarketUi(syntheticMarket, mode, outpost, otherCargo);
      if (kind != SYNTHETIC_UI_NONE) {
        if (vanillaDetachedCargoEconomyContractOperational && isExactVanillaEconomy(economy)) {
          if (kind == SYNTHETIC_UI_LOOT_TRANSFER) {
            incrementBestEffort(LOOT_TRANSFER_VANILLA_STEPS_SKIPPED);
          } else {
            incrementBestEffort(DETACHED_CARGO_VANILLA_STEPS_SKIPPED);
          }
          return true;
        }
        if (StarsectorPrepatcherAotdContract.isExactAoTDEconomy(economy) && StarsectorPrepatcherAotdContract
            .invokeAoTDUiEconomyAction(economy, null, AOTD_UI_ECONOMY_ACTION_CARGO, AOTD_UI_CARGO_SYNTHETIC, null)) {
          if (kind == SYNTHETIC_UI_LOOT_TRANSFER) {
            incrementBestEffort(LOOT_TRANSFER_AOTD_DISPATCHES);
          } else {
            incrementBestEffort(DETACHED_CARGO_AOTD_DISPATCHES);
          }
          return true;
        }
        if ((StarsectorPrepatcherAotdContract.getAoTDNegotiatedCapabilities()
            & AOTD_CAPABILITY_UI_ECONOMY_DISPATCH) == 0L) {
          incrementBestEffort(DETACHED_CARGO_UNKNOWN_ECONOMY_FALLBACKS);
        }
        return false;
      }

      if (!vanillaMarketOpenLocalizationConfigured || market == null) { return false; }
      if (StarsectorPrepatcherAotdContract.isExactAoTDEconomy(economy)) {
        if (StarsectorPrepatcherAotdContract.invokeAoTDUiEconomyAction(economy, market, AOTD_UI_ECONOMY_ACTION_CARGO,
            AOTD_UI_CARGO_LIVE_MARKET, null)) {
          incrementBestEffort(VANILLA_MARKET_OPEN_CARGO_STEPS_COALESCED);
          return true;
        }
        return false;
      }
      if (!isVanillaMarketOpenLocalizationContractOperational()) return false;
      Economy exactEconomy = exactStockVanillaEconomy(economy);
      Market exactMarket = exactVanillaMarket(market, exactEconomy);
      if (exactEconomy == null || exactMarket == null) return false;
      long fingerprint = vanillaMarketUiFingerprint(exactMarket);
      if (consumeVanillaMarketOpenRefresh(exactEconomy, exactMarket, fingerprint)) {
        incrementBestEffort(VANILLA_MARKET_OPEN_CARGO_STEPS_COALESCED);
        return true;
      }
      return false;
    } catch (Throwable failure) {
      logFailOpen("Cargo UI economy-step inspection failed open; " + "the original Economy.tripleStep will run.",
          failure);
      return false;
    }
  }

  private static boolean isExactVanillaEconomy(Object economy) {
    return exactVanillaReachEconomy(economy) != null;
  }

  /**
   * Name/loader based proof retained for the read-only UI removals. Keeping
   * this reflection-only avoids linking those guards to concrete game types
   * and preserves their synthetic child-loader regression coverage.
   */
  private static Object exactVanillaReachEconomy(Object economy) {
    if (economy == null || !VANILLA_ECONOMY_CLASS.equals(economy.getClass().getName())) { return null; }
    try {
      Method getter = economy.getClass().getMethod("getEconomy");
      if (getter.getParameterCount() != 0 || getter.getDeclaringClass() != economy.getClass()
          || !VANILLA_REACH_ECONOMY_CLASS.equals(getter.getReturnType().getName())) {
        return null;
      }
      Object reach = getter.invoke(economy);
      if (reach == null || !VANILLA_REACH_ECONOMY_CLASS.equals(reach.getClass().getName())
          || reach.getClass().getClassLoader() != economy.getClass().getClassLoader()) {
        return null;
      }
      return reach;
    } catch (ReflectiveOperationException | LinkageError | SecurityException failure) {
      return null;
    }
  }

  /** Market-open localization requires concrete stock classes because it executes their internals. */
  private static Economy exactStockVanillaEconomy(Object economy) {
    if (economy == null || economy.getClass() != Economy.class) return null;
    ReachEconomy reach = ((Economy) economy).getEconomy();
    if (reach == null || reach.getClass() != ReachEconomy.class
        || reach.getClass().getClassLoader() != economy.getClass().getClassLoader()) {
      return null;
    }
    return (Economy) economy;
  }

  private static Market exactVanillaMarket(Object market, Economy economy) {
    if (economy == null || market == null || market.getClass() != Market.class
        || market.getClass().getClassLoader() != economy.getClass().getClassLoader()) {
      return null;
    }
    Market exact = (Market) market;
    return exact.getEconomy() == economy ? exact : null;
  }

  /**
   * Suppresses only the global vanilla step caused by opening a condition-only
   * planet market that is not a member of the exact current ReachEconomy.
   */
  public static boolean shouldSkipConditionOnlyMarketOpenEconomyStep(Object economy, Object market) {
    try {
      if (!conditionOnlyMarketOpenSkipConfigured || !isPlanetConditionMarketOnly(market)) { return false; }
      incrementBestEffort(CONDITION_ONLY_MARKET_OPENS_DETECTED);
      Object reach = exactVanillaReachEconomy(economy);
      if (reach == null) {
        incrementBestEffort(CONDITION_ONLY_UNKNOWN_ECONOMY_FALLBACKS);
        return false;
      }
      Method getMarkets = reach.getClass().getMethod("getMarkets");
      Object rawMarkets = getMarkets.invoke(reach);
      if (rawMarkets instanceof Iterable<?> markets) {
        for (Object candidate : markets) {
          if (candidate == market) {
            incrementBestEffort(CONDITION_ONLY_MARKETS_FOUND_IN_ECONOMY);
            return false;
          }
        }
      } else {
        incrementBestEffort(CONDITION_ONLY_UNKNOWN_ECONOMY_FALLBACKS);
        return false;
      }
      incrementBestEffort(CONDITION_ONLY_VANILLA_STEPS_SKIPPED);
      return true;
    } catch (Throwable failure) {
      incrementBestEffort(CONDITION_ONLY_UNKNOWN_ECONOMY_FALLBACKS);
      logFailOpen("Condition-only market-open inspection failed open; " + "the original Economy.nextStep will run.",
          failure);
      return false;
    }
  }

  /** Exact call-site wrapper for a free-port write. It snapshots the
   * materialized local commodity vector and records only changed IDs. */
  public static void applyVanillaFreePortMutation(MarketAPI market, boolean value) {
    boolean changed = true;
    boolean barrierReady = false;
    Map<String, Long> before = Collections.emptyMap();
    try {
      changed = market != null && market.isFreePort() != value;
      barrierReady = prepareUiMarketMutation(market);
      if (changed && barrierReady) before = snapshotLocalCommodityVector(market);
    } catch (Throwable failure) {
      logFailOpen(
          "Free-port mutation preparation failed open; the original " + "setter and global economy step remain active.",
          failure);
      barrierReady = false;
    }
    market.setFreePort(value);
    if (!changed) return;

    String[] affected = new String[0];
    try {
      if (uiMarketMutationRefreshConfigured && barrierReady) {
        market.reapplyConditions();
        market.reapplyIndustries();
        affected = changedCommodityIds(before, snapshotLocalCommodityVector(market));
        if (affected.length == 0) {
          affected = sortedCommodityIds(market.getAllCommodities());
        }
      }
      recordUiMarketMutation(market, MUTATION_REASON_FREE_PORT,
          REFRESH_SCOPE_LOCAL_STATS | REFRESH_SCOPE_LOCAL_COMMODITIES | REFRESH_SCOPE_LOCAL_PRICE_STOCKPILE
              | REFRESH_SCOPE_IMMIGRATION | REFRESH_SCOPE_ACCESSIBILITY | REFRESH_SCOPE_LISTENER_BOUNDARY
              | REFRESH_SCOPE_AFFECTED_GLOBAL_COMMODITIES
              | (barrierReady && affected.length > 0 ? 0 : REFRESH_SCOPE_GLOBAL_TOPOLOGY),
          affected);
    } catch (Throwable failure) {
      poisonUiMarketMutationContextBestEffort(market, MUTATION_REASON_FREE_PORT,
          REFRESH_SCOPE_LOCAL_STATS | REFRESH_SCOPE_LOCAL_COMMODITIES | REFRESH_SCOPE_LOCAL_PRICE_STOCKPILE
              | REFRESH_SCOPE_IMMIGRATION | REFRESH_SCOPE_ACCESSIBILITY | REFRESH_SCOPE_LISTENER_BOUNDARY
              | REFRESH_SCOPE_AFFECTED_GLOBAL_COMMODITIES);
      logFailOpen(
          "Free-port affected-commodity capture failed open; " + "the original global economy step will remain active.",
          failure);
    }
  }

  public static void applyVanillaImmigrationClosedMutation(MarketAPI market, boolean value) {
    boolean barrierReady = prepareUiMarketMutation(market);
    market.setImmigrationClosed(value);
    recordUiMarketMutationFailOpen(market, MUTATION_REASON_IMMIGRATION_POLICY,
        REFRESH_SCOPE_LOCAL_STATS | REFRESH_SCOPE_IMMIGRATION | REFRESH_SCOPE_LISTENER_BOUNDARY
            | (barrierReady ? 0 : REFRESH_SCOPE_GLOBAL_TOPOLOGY));
  }

  public static void applyVanillaImmigrationIncentivesMutation(MarketAPI market, Boolean value) {
    boolean barrierReady = prepareUiMarketMutation(market);
    market.setImmigrationIncentivesOn(value);
    recordUiMarketMutationFailOpen(market, MUTATION_REASON_IMMIGRATION_POLICY,
        REFRESH_SCOPE_LOCAL_STATS | REFRESH_SCOPE_IMMIGRATION | REFRESH_SCOPE_LISTENER_BOUNDARY
            | (barrierReady ? 0 : REFRESH_SCOPE_GLOBAL_TOPOLOGY));
  }

  public static void applyVanillaStockpilePolicyMutation(MarketAPI market, boolean value) {
    boolean barrierReady = prepareUiMarketMutation(market);
    market.setUseStockpilesForShortages(value);
    recordUiMarketMutationFailOpen(market, MUTATION_REASON_STOCKPILE_POLICY,
        REFRESH_SCOPE_LOCAL_COMMODITIES | REFRESH_SCOPE_LOCAL_PRICE_STOCKPILE | REFRESH_SCOPE_LISTENER_BOUNDARY
            | (barrierReady ? 0 : REFRESH_SCOPE_GLOBAL_TOPOLOGY));
  }

  /** Exact vanilla IndustryConfigDialog branch wrapper. */
  public static void applyVanillaIndustryStartUpgrading(Industry industry) {
    if (!isIndustryMarketMutationPatchOperational()) {
      industry.startUpgrading();
      return;
    }
    MarketAPI market = safeIndustryMarket(industry);
    Object[] preparation = prepareIndustryMutation(market);
    industry.startUpgrading();
    recordVanillaIndustryMutation(market, preparation, MUTATION_REASON_INDUSTRY_QUEUE);
  }

  /** Exact vanilla IndustryConfigDialog branch wrapper. */
  public static void applyVanillaIndustryDowngrade(Industry industry) {
    if (!isIndustryMarketMutationPatchOperational()) {
      industry.downgrade();
      return;
    }
    MarketAPI market = safeIndustryMarket(industry);
    Object[] preparation = prepareIndustryMutation(market);
    industry.downgrade();
    recordVanillaIndustryMutation(market, preparation, MUTATION_REASON_INDUSTRY_STRUCTURE);
  }

  /** Exact vanilla IndustryConfigDialog branch wrapper. */
  public static void applyVanillaIndustryCancelUpgrade(Industry industry) {
    if (!isIndustryMarketMutationPatchOperational()) {
      industry.cancelUpgrade();
      return;
    }
    MarketAPI market = safeIndustryMarket(industry);
    Object[] preparation = prepareIndustryMutation(market);
    industry.cancelUpgrade();
    recordVanillaIndustryMutation(market, preparation, MUTATION_REASON_INDUSTRY_QUEUE);
  }

  /** Exact vanilla IndustryConfigDialog remove/shut-down branch wrapper. */
  public static void applyVanillaIndustryRemoval(MarketAPI market, String industryId,
      MarketAPI.MarketInteractionMode mode, boolean notify) {
    if (!isIndustryMarketMutationPatchOperational()) {
      market.removeIndustry(industryId, mode, notify);
      return;
    }
    Object[] preparation = prepareIndustryMutation(market);
    market.removeIndustry(industryId, mode, notify);
    recordVanillaIndustryMutation(market, preparation, MUTATION_REASON_INDUSTRY_STRUCTURE);
  }

  private static boolean isIndustryMarketMutationPatchOperational() {
    try {
      return uiMarketMutationRefreshConfigured
          && "ready".equals(System.getProperty("starsector.prepatcher.industryMarketMutationPatchGroup"));
    } catch (Throwable failure) {
      logFailOpen("Industry mutation patch-state inspection failed open; the original " + "mutation remains active.",
          failure);
      return false;
    }
  }

  private static MarketAPI safeIndustryMarket(Industry industry) {
    try {
      return industry.getMarket();
    } catch (Throwable failure) {
      logFailOpen("Industry market lookup failed open; the original mutation " + "will still run.", failure);
      return null;
    }
  }

  private static Object[] prepareIndustryMutation(MarketAPI market) {
    boolean barrierReady = false;
    Map<String, Long> before = Collections.emptyMap();
    try {
      barrierReady = prepareUiMarketMutation(market);
      if (barrierReady) before = snapshotLocalCommodityVector(market);
    } catch (Throwable failure) {
      logFailOpen("Industry mutation preparation failed open; the original "
          + "mutation and global economy step remain active.", failure);
      barrierReady = false;
    }
    return new Object[]{before, Boolean.valueOf(barrierReady)};
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Long> castCommoditySnapshot(Object value) {
    return (Map<String, Long>) value;
  }

  private static void recordVanillaIndustryMutation(MarketAPI market, Object[] preparation, int reasonMask) {
    try {
      Map<String, Long> before = castCommoditySnapshot(preparation[0]);
      boolean barrierReady = ((Boolean) preparation[1]).booleanValue();
      String[] affected = new String[0];
      if (barrierReady && market != null && uiMarketMutationRefreshConfigured) {
        market.reapplyConditions();
        market.reapplyIndustries();
        affected = changedCommodityIds(before, snapshotLocalCommodityVector(market));
      }
      int scope = REFRESH_SCOPE_LOCAL_STATS | REFRESH_SCOPE_LOCAL_COMMODITIES | REFRESH_SCOPE_INDUSTRY_STATE
          | REFRESH_SCOPE_LISTENER_BOUNDARY;
      if (affected.length > 0) {
        scope |= REFRESH_SCOPE_AFFECTED_GLOBAL_COMMODITIES;
      }
      if (!barrierReady) {
        scope |= REFRESH_SCOPE_GLOBAL_TOPOLOGY;
      }
      recordUiMarketMutation(market, reasonMask, scope, affected);
    } catch (Throwable failure) {
      poisonUiMarketMutationContextBestEffort(market, reasonMask, REFRESH_SCOPE_LOCAL_STATS
          | REFRESH_SCOPE_LOCAL_COMMODITIES | REFRESH_SCOPE_INDUSTRY_STATE | REFRESH_SCOPE_LISTENER_BOUNDARY);
      logFailOpen(
          "Industry affected-commodity capture failed open; " + "the original global economy step will remain active.",
          failure);
    }
  }

  private static boolean prepareUiMarketMutation(MarketAPI market) {
    try {
      clearPreparedTradeMutation();
      if (!uiMarketMutationRefreshConfigured || market == null) { return false; }
      StarsectorPrepatcherHooks.flushPendingMarketBeforeUiRefresh(market);
      return true;
    } catch (Throwable failure) {
      clearPreparedTradeMutationBestEffort();
      logFailOpen(
          "UI market-mutation debt barrier failed open; " + "the original global economy step will remain active.",
          failure);
      return false;
    }
  }

  private static void recordUiMarketMutation(Object market, int reasonMask, int scopeMask) {
    recordUiMarketMutation(market, reasonMask, scopeMask, new String[0]);
  }

  private static void recordUiMarketMutation(Object market, int reasonMask, int scopeMask, String[] commodityIds) {
    if (!uiMarketMutationRefreshConfigured || market == null || reasonMask == 0 || scopeMask == 0) {
      throw new IllegalArgumentException("invalid UI market-mutation context");
    }
    String[] normalized = normalizeCommodityIds(commodityIds);
    Object[] existing = UI_MARKET_MUTATION_CONTEXT.get();
    if (contextMatches(existing, market)) {
      reasonMask |= ((Integer) existing[1]).intValue();
      scopeMask |= ((Integer) existing[2]).intValue();
      normalized = mergeCommodityIds((String[]) existing[5], normalized);
    }
    UI_MARKET_MUTATION_CONTEXT.set(new Object[]{new WeakReference<>(market), Integer.valueOf(reasonMask),
        Integer.valueOf(scopeMask), Long.valueOf(StarsectorPrepatcherAotdRuntimeState.getAoTDCampaignEpoch()),
        Long.valueOf(StarsectorPrepatcherAotdRuntimeState.getAoTDEconomyEpoch()), normalized});
    UI_MARKET_MUTATIONS_RECORDED.increment();
  }

  private static void recordUiMarketMutationFailOpen(Object market, int reasonMask, int scopeMask) {
    try {
      recordUiMarketMutation(market, reasonMask, scopeMask);
    } catch (Throwable failure) {
      poisonUiMarketMutationContextBestEffort(market, reasonMask, scopeMask);
      logFailOpen("UI market-mutation context publication failed open; "
          + "the original global economy step will remain active.", failure);
    }
  }

  /**
   * Publishes a sticky same-market fallback marker. A later setter may add
   * detail, but cannot remove GLOBAL_TOPOLOGY before the shared helper consumes it.
   */
  private static void poisonUiMarketMutationContextBestEffort(Object market, int reasonMask, int scopeMask) {
    try {
      if (!uiMarketMutationRefreshConfigured) {
        clearUiMarketMutationBatch();
        return;
      }
      UI_MARKET_MUTATION_POISONED.set(Boolean.TRUE);
      if (market == null) return;
      String[] commodityIds = new String[0];
      Object[] existing = UI_MARKET_MUTATION_CONTEXT.get();
      if (contextMatches(existing, market)) {
        reasonMask |= ((Integer) existing[1]).intValue();
        scopeMask |= ((Integer) existing[2]).intValue();
        commodityIds = ((String[]) existing[5]).clone();
      }
      UI_MARKET_MUTATION_CONTEXT.set(new Object[]{new WeakReference<>(market), Integer.valueOf(reasonMask),
          Integer.valueOf(scopeMask | REFRESH_SCOPE_GLOBAL_TOPOLOGY),
          Long.valueOf(StarsectorPrepatcherAotdRuntimeState.getAoTDCampaignEpoch()),
          Long.valueOf(StarsectorPrepatcherAotdRuntimeState.getAoTDEconomyEpoch()), commodityIds});
      incrementBestEffort(UI_MARKET_MUTATIONS_RECORDED);
    } catch (Throwable ignored) {
      clearUiMarketMutationContextBestEffort();
      try {
        UI_MARKET_MUTATION_POISONED.set(Boolean.TRUE);
      } catch (Throwable alsoIgnored) {
        // No stronger recovery is possible if ThreadLocal itself is unavailable.
      }
    }
  }

  private static boolean contextMatches(Object[] context, Object market) {
    if (context == null || context.length != 6 || !(context[0] instanceof WeakReference<?> reference)
        || !(context[1] instanceof Integer) || !(context[2] instanceof Integer)
        || !(context[3] instanceof Long campaignEpoch) || !(context[4] instanceof Long economyEpoch)
        || !(context[5] instanceof String[])) {
      return false;
    }
    return reference.get() == market
        && campaignEpoch.longValue() == StarsectorPrepatcherAotdRuntimeState.getAoTDCampaignEpoch()
        && economyEpoch.longValue() == StarsectorPrepatcherAotdRuntimeState.getAoTDEconomyEpoch();
  }

  private static Object[] takeUiMarketMutationContext(Object market) {
    Object[] context = UI_MARKET_MUTATION_CONTEXT.get();
    UI_MARKET_MUTATION_CONTEXT.remove();
    if (!contextMatches(context, market)) {
      if (context != null) UI_MARKET_MUTATION_IDENTITY_MISMATCHES.increment();
      return null;
    }
    return context;
  }

  private static void clearUiMarketMutationContext() {
    Object[] context = UI_MARKET_MUTATION_CONTEXT.get();
    if (context != null) {
      for (int i = 0; i < context.length; i++) context[i] = null;
    }
    UI_MARKET_MUTATION_CONTEXT.remove();
  }

  private static void clearUiMarketMutationContextBestEffort() {
    try {
      clearUiMarketMutationContext();
    } catch (Throwable ignored) {
      // Optimization state must never own caller control flow.
    }
  }

  static void clearUiMarketMutationBatch() {
    clearUiMarketMutationContext();
    UI_MARKET_MUTATION_POISONED.remove();
  }

  private static void clearUiMarketMutationBatchBestEffort() {
    try {
      clearUiMarketMutationBatch();
    } catch (Throwable ignored) {
      // Optimization state must never own caller control flow.
    }
  }

  private static boolean takeUiMarketMutationPoison() {
    boolean poisoned = Boolean.TRUE.equals(UI_MARKET_MUTATION_POISONED.get());
    UI_MARKET_MUTATION_POISONED.remove();
    return poisoned;
  }

  /** Exact pre-twConfirm trade barrier inserted by the trade transformer. */
  public static void prepareTradeMarketMutation(MarketAPI market) {
    try {
      clearUiMarketMutationBatch();
      clearPreparedTradeMutation();
      if (!uiMarketMutationRefreshConfigured || market == null) return;
      StarsectorPrepatcherHooks.flushPendingMarketBeforeUiRefresh(market);
      TRADE_MUTATION_PREPARATION.set(new Object[]{new WeakReference<>(market),
          Long.valueOf(StarsectorPrepatcherAotdRuntimeState.getAoTDCampaignEpoch()),
          Long.valueOf(StarsectorPrepatcherAotdRuntimeState.getAoTDEconomyEpoch())});
    } catch (Throwable failure) {
      clearUiMarketMutationBatchBestEffort();
      clearPreparedTradeMutationBestEffort();
      logFailOpen(
          "Trade pre-mutation debt barrier failed open; " + "the original global economy step will remain active.",
          failure);
    }
  }

  private static boolean takePreparedTradeMutation(Object market) {
    Object[] prepared = TRADE_MUTATION_PREPARATION.get();
    TRADE_MUTATION_PREPARATION.remove();
    if (prepared == null || prepared.length != 3 || !(prepared[0] instanceof WeakReference<?> reference)
        || !(prepared[1] instanceof Long campaignEpoch) || !(prepared[2] instanceof Long economyEpoch)) {
      return false;
    }
    return reference.get() == market
        && campaignEpoch.longValue() == StarsectorPrepatcherAotdRuntimeState.getAoTDCampaignEpoch()
        && economyEpoch.longValue() == StarsectorPrepatcherAotdRuntimeState.getAoTDEconomyEpoch();
  }

  private static void clearPreparedTradeMutation() {
    Object[] prepared = TRADE_MUTATION_PREPARATION.get();
    if (prepared != null) {
      for (int i = 0; i < prepared.length; i++) prepared[i] = null;
    }
    TRADE_MUTATION_PREPARATION.remove();
  }

  private static void clearPreparedTradeMutationBestEffort() {
    try {
      clearPreparedTradeMutation();
    } catch (Throwable ignored) {
      // Optimization state must never own caller control flow.
    }
  }

  private static void incrementBestEffort(LongAdder counter) {
    try {
      counter.increment();
    } catch (Throwable ignored) {
      // Counters are diagnostics, not part of semantic completion.
    }
  }

  static void logFailOpen(String message, Throwable failure) {
    try {
      PrepatcherLog.error(message, failure);
    } catch (Throwable ignored) {
      // Logging must not suppress the preserved original call.
    }
  }

  /**
   * Consumes a one-shot exact mutation context for the shared vanilla market
   * overview refresh helper. Unsafe/global scopes deliberately retain the
   * original economy step.
   */
  public static boolean shouldHandleVanillaUiMutationEconomyStep(Object economy, Object market) {
    if (!uiMarketMutationRefreshConfigured) return false;
    try {
      if (takeUiMarketMutationPoison()) {
        clearUiMarketMutationContextBestEffort();
        incrementBestEffort(UI_MARKET_MUTATION_GLOBAL_FALLBACKS);
        return false;
      }
      Object[] context = UI_MARKET_MUTATION_CONTEXT.get();
      if (!contextMatches(context, market)) {
        if (context != null) {
          incrementBestEffort(UI_MARKET_MUTATION_IDENTITY_MISMATCHES);
          clearUiMarketMutationBatchBestEffort();
        }
        return false;
      }

      // A subclass receives no exact-fork semantics and cannot retain a stale
      // payload. The exact current fork transfers ownership to the dispatcher.
      if (StarsectorPrepatcherAotdContract.isOwnedAoTDEconomyHierarchy(economy)) {
        if (!StarsectorPrepatcherAotdContract.isExactAoTDEconomy(economy)) {
          clearUiMarketMutationBatchBestEffort();
          incrementBestEffort(UI_MARKET_MUTATION_GLOBAL_FALLBACKS);
          return false;
        }
      }

      Object[] consumed = takeUiMarketMutationContext(market);
      if (consumed == null) return false;
      int reason = ((Integer) consumed[1]).intValue();
      int scope = ((Integer) consumed[2]).intValue();
      boolean globalTopology = (scope & REFRESH_SCOPE_GLOBAL_TOPOLOGY) != 0;
      boolean targeted = (scope & REFRESH_SCOPE_AFFECTED_GLOBAL_COMMODITIES) != 0;
      if (globalTopology) {
        incrementBestEffort(UI_MARKET_MUTATION_GLOBAL_FALLBACKS);
        return false;
      }

      if (StarsectorPrepatcherAotdContract.isExactAoTDEconomy(economy)) {
        String[] ids = (String[]) consumed[5];
        if (StarsectorPrepatcherAotdContract.invokeAoTDUiEconomyAction(economy, market,
            AOTD_UI_ECONOMY_ACTION_MARKET_MUTATION,
            StarsectorPrepatcherAotdContract.packAoTDUiMutationDetail(reason, scope), ids)) {
          incrementBestEffort(UI_MARKET_MUTATIONS_LOCALIZED);
          incrementBestEffort(UI_MARKET_MUTATION_AOTD_CONSUMES);
          if (targeted) {
            incrementBestEffort(AFFECTED_COMMODITY_REFRESHES);
            if ((reason & MUTATION_REASON_FREE_PORT) != 0) {
              incrementBestEffort(FREE_PORT_AFFECTED_COMMODITY_REFRESHES);
            }
            if ((reason & (MUTATION_REASON_INDUSTRY_QUEUE | MUTATION_REASON_INDUSTRY_STRUCTURE
                | MUTATION_REASON_INDUSTRY_MODIFIER)) != 0) {
              incrementBestEffort(INDUSTRY_AFFECTED_COMMODITY_REFRESHES);
            }
          }
          return true;
        }
        incrementBestEffort(UI_MARKET_MUTATION_GLOBAL_FALLBACKS);
        return false;
      }

      Economy exactEconomy = exactStockVanillaEconomy(economy);
      Market exactMarket = exactVanillaMarket(market, exactEconomy);
      if (exactEconomy == null || exactMarket == null || Global.getSector() == null
          || Global.getSector().getEconomy() != exactEconomy
          || !containsMarketIdentity(exactEconomy.getEconomy(), exactMarket)) {
        incrementBestEffort(UI_MARKET_MUTATION_GLOBAL_FALLBACKS);
        return false;
      }
      if (targeted) {
        String[] commodityIds = (String[]) consumed[5];
        if (!isCommodityMarketDataContractOperational() || commodityIds.length == 0) {
          incrementBestEffort(UI_MARKET_MUTATION_GLOBAL_FALLBACKS);
          return false;
        }
        refreshVanillaAffectedCommodities(exactEconomy, exactMarket, commodityIds, scope);
        incrementBestEffort(AFFECTED_COMMODITY_REFRESHES);
        int consumedReason = ((Integer) consumed[1]).intValue();
        if ((consumedReason & MUTATION_REASON_FREE_PORT) != 0) {
          incrementBestEffort(FREE_PORT_AFFECTED_COMMODITY_REFRESHES);
        }
        if ((consumedReason & (MUTATION_REASON_INDUSTRY_QUEUE | MUTATION_REASON_INDUSTRY_STRUCTURE
            | MUTATION_REASON_INDUSTRY_MODIFIER)) != 0) {
          incrementBestEffort(INDUSTRY_AFFECTED_COMMODITY_REFRESHES);
        }
      } else {
        if (!isVanillaMarketOpenLocalizationContractOperational()) {
          incrementBestEffort(UI_MARKET_MUTATION_GLOBAL_FALLBACKS);
          return false;
        }
        refreshVanillaUiMarket(exactEconomy, exactMarket);
        int consumedReason = ((Integer) consumed[1]).intValue();
        if ((consumedReason & (MUTATION_REASON_INDUSTRY_QUEUE | MUTATION_REASON_INDUSTRY_STRUCTURE
            | MUTATION_REASON_INDUSTRY_MODIFIER)) != 0) {
          incrementBestEffort(INDUSTRY_LOCAL_COMMITS);
        }
      }
      incrementBestEffort(UI_MARKET_MUTATIONS_LOCALIZED);
      return true;
    } catch (Throwable failure) {
      clearUiMarketMutationBatchBestEffort();
      incrementBestEffort(UI_MARKET_MUTATION_GLOBAL_FALLBACKS);
      incrementBestEffort(AFFECTED_COMMODITY_GLOBAL_FALLBACKS);
      logFailOpen("UI mutation inspection/refresh failed open; " + "the original global Economy.tripleStep will run.",
          failure);
      return false;
    }
  }

  public static long getUiMarketMutationsRecorded() { return UI_MARKET_MUTATIONS_RECORDED.sum(); }

  public static long getUiMarketMutationsLocalized() { return UI_MARKET_MUTATIONS_LOCALIZED.sum(); }

  public static long getUiMarketMutationGlobalFallbacks() { return UI_MARKET_MUTATION_GLOBAL_FALLBACKS.sum(); }

  /**
   * Exact vanilla trade-call guard. Returns true only after a complete local
   * commit; every inspection or refresh failure returns false to the preserved
   * EconomyAPI.doubleStep invocation in the transformed caller.
   */
  public static boolean shouldHandleTradeMarketMutationEconomyStep(EconomyAPI economy, MarketAPI market,
      PlayerMarketTransaction transaction) {
    boolean handled = false;
    try {
      if (!uiMarketMutationRefreshConfigured || economy == null || market == null || transaction == null
          || transaction.getMarket() != market) {
        return false;
      }
      String[] affected = collectTransactionCommodityIds(transaction);
      boolean barrierReady = affected.length > 0 && takePreparedTradeMutation(market);
      int scope = REFRESH_SCOPE_LOCAL_COMMODITIES | REFRESH_SCOPE_LOCAL_PRICE_STOCKPILE
          | REFRESH_SCOPE_LISTENER_BOUNDARY | REFRESH_SCOPE_AFFECTED_GLOBAL_COMMODITIES;
      if (!barrierReady) return false;

      if (StarsectorPrepatcherAotdContract.isOwnedAoTDEconomyHierarchy(economy)) {
        if (!StarsectorPrepatcherAotdContract.isExactAoTDEconomy(economy)) return false;
        if (StarsectorPrepatcherAotdContract.invokeAoTDUiEconomyAction(economy, market,
            AOTD_UI_ECONOMY_ACTION_MARKET_MUTATION,
            StarsectorPrepatcherAotdContract.packAoTDUiMutationDetail(MUTATION_REASON_TRADE_TRANSACTION, scope),
            affected)) {
          handled = true;
          incrementBestEffort(UI_MARKET_MUTATIONS_LOCALIZED);
          incrementBestEffort(UI_MARKET_MUTATION_AOTD_CONSUMES);
          incrementBestEffort(AFFECTED_COMMODITY_REFRESHES);
          incrementBestEffort(TRADE_AFFECTED_COMMODITY_REFRESHES);
          return true;
        }
        return false;
      }

      if (!isCommodityMarketDataContractOperational()) return false;

      Economy exactEconomy = exactStockVanillaEconomy(economy);
      Market exactMarket = exactVanillaMarket(market, exactEconomy);
      if (exactEconomy == null || exactMarket == null || Global.getSector() == null
          || Global.getSector().getEconomy() != exactEconomy
          || !containsMarketIdentity(exactEconomy.getEconomy(), exactMarket)) {
        return false;
      }
      refreshVanillaAffectedCommodities(exactEconomy, exactMarket, affected, scope);
      handled = true;
      incrementBestEffort(AFFECTED_COMMODITY_REFRESHES);
      incrementBestEffort(TRADE_AFFECTED_COMMODITY_REFRESHES);
      return true;
    } catch (Throwable failure) {
      logFailOpen("Trade mutation inspection/refresh failed open; " + "the original Economy.doubleStep will run.",
          failure);
      return false;
    } finally {
      clearUiMarketMutationBatchBestEffort();
      clearPreparedTradeMutationBestEffort();
      if (!handled) incrementBestEffort(AFFECTED_COMMODITY_GLOBAL_FALLBACKS);
    }
  }

  private static String[] collectTransactionCommodityIds(PlayerMarketTransaction transaction) {
    TreeSet<String> ids = new TreeSet<>();
    collectCargoCommodityIds(transaction.getBought(), ids);
    collectCargoCommodityIds(transaction.getSold(), ids);
    return ids.toArray(new String[0]);
  }

  private static void collectCargoCommodityIds(CargoAPI cargo, Set<String> ids) {
    if (cargo == null) return;
    for (CargoStackAPI stack : cargo.getStacksCopy()) {
      if (stack == null || !stack.isCommodityStack() || stack.getSize() == 0f) continue;
      String id = stack.getCommodityId();
      if (id != null && !id.isBlank()) ids.add(id);
    }
  }

  /**
   * Replaces CampaignEngine's exact vanilla live-market nextStep with one
   * synchronous local refresh. Unknown implementations and structural drift
   * return false so the original global step executes unchanged.
   */
  public static boolean shouldHandleVanillaMarketOpenEconomyStep(Object economy, Object market) {
    try {
      boolean conditionOnly = isPlanetConditionMarketOnly(market);
      if (StarsectorPrepatcherAotdContract.isExactAoTDEconomy(economy)) {
        if (conditionOnly && !conditionOnlyMarketOpenSkipConfigured) return false;
        if (!conditionOnly && !vanillaMarketOpenLocalizationConfigured) return false;
        if (conditionOnly) {
          incrementBestEffort(CONDITION_ONLY_MARKET_OPENS_DETECTED);
        } else {
          incrementBestEffort(VANILLA_MARKET_OPENS_DETECTED);
        }
        if (StarsectorPrepatcherAotdContract.invokeAoTDUiEconomyAction(economy, market,
            AOTD_UI_ECONOMY_ACTION_MARKET_OPEN, 0L, null)) {
          if (conditionOnly) {
            incrementBestEffort(CONDITION_ONLY_VANILLA_STEPS_SKIPPED);
          } else {
            incrementBestEffort(VANILLA_MARKET_OPENS_LOCALIZED);
          }
          return true;
        }
        if (conditionOnly) {
          incrementBestEffort(CONDITION_ONLY_UNKNOWN_ECONOMY_FALLBACKS);
        } else {
          incrementBestEffort(VANILLA_MARKET_OPEN_UNKNOWN_FALLBACKS);
        }
        return false;
      }
      if (conditionOnly) { return shouldSkipConditionOnlyMarketOpenEconomyStep(economy, market); }
      if (!vanillaMarketOpenLocalizationConfigured) return false;
      incrementBestEffort(VANILLA_MARKET_OPENS_DETECTED);
      if (!isVanillaMarketOpenLocalizationContractOperational()) {
        incrementBestEffort(VANILLA_MARKET_OPEN_UNKNOWN_FALLBACKS);
        return false;
      }

      Economy exactEconomy = exactStockVanillaEconomy(economy);
      Market exactMarket = exactVanillaMarket(market, exactEconomy);
      if (exactEconomy == null || exactMarket == null || Global.getSector() == null
          || Global.getSector().getEconomy() != exactEconomy
          || !containsMarketIdentity(exactEconomy.getEconomy(), exactMarket)) {
        incrementBestEffort(VANILLA_MARKET_OPEN_UNKNOWN_FALLBACKS);
        return false;
      }

      refreshVanillaUiMarket(exactEconomy, exactMarket);
      long fingerprint = vanillaMarketUiFingerprint(exactMarket);
      recordVanillaMarketOpenRefresh(exactEconomy, exactMarket, fingerprint);
      incrementBestEffort(VANILLA_MARKET_OPENS_LOCALIZED);
      return true;
    } catch (Throwable failure) {
      try {
        clearVanillaMarketOpenCoalescingToken();
      } catch (Throwable ignored) {
        // The preserved virtual call remains the authority.
      }
      incrementBestEffort(VANILLA_MARKET_OPEN_LOCALIZATION_FAILURES);
      logFailOpen(
          "Market-open inspection/localization failed open; " + "the original global Economy.nextStep will run.",
          failure);
      return false;
    }
  }

  private static boolean containsMarketIdentity(ReachEconomy reach, Market market) {
    if (reach == null || market == null) return false;
    for (MarketAPI candidate : reach.getMarkets()) {
      if (candidate == market) return true;
    }
    return false;
  }

  private static void refreshVanillaUiMarket(Economy economy, Market market) {
    StarsectorPrepatcherHooks.flushPendingMarketBeforeUiRefresh(market);
    market.updatePrevStability();

    PersonAPI admin = market.getAdmin();
    if (admin != null) {
      admin.getStats().refreshCharacterStatsEffects();
      admin.getStats().refreshGovernedOutpostEffects(market);
    }

    reapplyLocalMarket(market);
    updateLocalCommodityVector(market);

    economy.forceStockpileUpdate(market);
    notifyVanillaCommodityListeners(economy, economy.getAllCommodityIds());
    reapplyLocalMarket(market);
    drainVanillaTask(new ImmigrationTask(Collections.<MarketAPI>singletonList(market), economy.getEconomy(), true));
    drainVanillaTask(new FinishEconomyUpdateTask(economy));
  }

  private static void refreshVanillaAffectedCommodities(Economy economy, Market market, String[] commodityIds,
      int scope) {
    String[] normalized = normalizeCommodityIds(commodityIds);
    if (normalized.length == 0) { throw new IllegalArgumentException("empty affected commodity set"); }
    StarsectorPrepatcherHooks.flushPendingMarketBeforeUiRefresh(market);
    market.updatePrevStability();
    PersonAPI admin = market.getAdmin();
    if (admin != null) {
      admin.getStats().refreshCharacterStatsEffects();
      admin.getStats().refreshGovernedOutpostEffects(market);
    }
    reapplyLocalMarket(market);
    updateLocalCommodityVector(market);

    TreeSet<String> econGroups = new TreeSet<>();
    for (MarketAPI candidate : economy.getEconomy().getMarkets()) {
      if (candidate == null) continue;
      String group = candidate.getEconGroup();
      if (group != null && !group.isBlank()) econGroups.add(group);
    }
    for (String commodityId : normalized) {
      new CommodityMarketData(commodityId, null);
      for (String econGroup : econGroups) {
        new CommodityMarketData(commodityId, econGroup);
      }
    }
    if ((scope & REFRESH_SCOPE_LOCAL_PRICE_STOCKPILE) != 0) {
      economy.forceStockpileUpdate(market);
    }
    if ((scope & REFRESH_SCOPE_LISTENER_BOUNDARY) != 0) {
      notifyVanillaCommodityListeners(economy, java.util.Arrays.asList(normalized));
    }
    reapplyLocalMarket(market);
    if ((scope & REFRESH_SCOPE_IMMIGRATION) != 0) {
      drainVanillaTask(new ImmigrationTask(Collections.<MarketAPI>singletonList(market), economy.getEconomy(), true));
    }
    drainVanillaTask(new FinishEconomyUpdateTask(economy));
  }

  private static void updateLocalCommodityVector(Market market) {
    ArrayList<CommodityOnMarket> commodities = new ArrayList<>(market.getCommodities());
    for (CommodityOnMarket commodity : commodities) {
      if (commodity != null) commodity.updateMaxSupplyAndDemand();
    }
    for (CommodityOnMarket primary : commodities) {
      if (primary == null || primary.getCommodity() == null || !primary.getCommodity().isPrimary()) continue;
      for (CommodityOnMarket sibling : market.getCommoditiesWithClass(primary.getDemandClass())) {
        if (sibling == null || sibling == primary) continue;
        sibling.setMaxDemand(primary.getMaxDemand());
        sibling.setDemandLegal(primary.isDemandLegal());
      }
    }
  }

  private static void reapplyLocalMarket(Market market) {
    market.reapplyConditions();
    market.reapplyIndustries();
  }

  private static void notifyVanillaCommodityListeners(Economy economy, List<String> commodityIds) {
    for (String commodityId : commodityIds) {
      ArrayList<EconomyAPI.EconomyUpdateListener> listeners = new ArrayList<>(economy.getUpdateListeners());
      for (EconomyAPI.EconomyUpdateListener listener : listeners) {
        if (listener == null) continue;
        if (listener.isEconomyListenerExpired()) {
          economy.removeUpdateListener(listener);
        } else {
          listener.commodityUpdated(commodityId);
        }
      }
    }
  }

  private static void drainVanillaTask(MultiFrameTask task) {
    while (!task.isDone()) task.doNextBatch();
  }

  private static long vanillaMarketUiFingerprint(Market market) {
    long hash = 0xcbf29ce484222325L;
    hash = mixFingerprint(hash, System.identityHashCode(market));
    hash = mixFingerprint(hash, market.getSize());
    hash = mixFingerprint(hash, Float.floatToIntBits(market.getStabilityValue()));
    hash = mixFingerprint(hash, System.identityHashCode(market.getAdmin()));
    List<CommodityOnMarket> commodities = market.getCommodities();
    hash = mixFingerprint(hash, commodities.size());
    for (CommodityOnMarket commodity : commodities) {
      if (commodity == null) {
        hash = mixFingerprint(hash, 0);
        continue;
      }
      hash = mixFingerprint(hash, System.identityHashCode(commodity));
      String id = commodity.getId();
      hash = mixFingerprint(hash, id == null ? 0 : id.hashCode());
      hash = mixFingerprint(hash, commodity.getMaxSupply());
      hash = mixFingerprint(hash, commodity.getMaxDemand());
      hash = mixFingerprint(hash, commodity.getAvailable());
      hash = mixFingerprint(hash, Float.floatToIntBits(commodity.getStockpile()));
      hash = mixFingerprint(hash, commodity.isDemandLegal() ? 1 : 0);
      hash = mixFingerprint(hash, commodity.isSupplyLegal() ? 1 : 0);
      hash = mixFingerprint(hash, System.identityHashCode(commodity.getCommodityMarketData()));
    }
    return hash;
  }

  private static Map<String, Long> snapshotLocalCommodityVector(MarketAPI market) {
    TreeMap<String, Long> result = new TreeMap<>();
    if (market == null) return result;
    for (CommodityOnMarketAPI commodity : market.getAllCommodities()) {
      if (commodity == null || commodity.getId() == null) continue;
      long hash = 0xcbf29ce484222325L;
      hash = mixFingerprint(hash, commodity.getMaxSupply());
      hash = mixFingerprint(hash, commodity.getMaxDemand());
      hash = mixFingerprint(hash, commodity.getAvailable());
      hash = mixFingerprint(hash, commodity.isDemandLegal() ? 1 : 0);
      hash = mixFingerprint(hash, commodity.isSupplyLegal() ? 1 : 0);
      result.put(commodity.getId(), Long.valueOf(hash));
    }
    return result;
  }

  private static String[] changedCommodityIds(Map<String, Long> before, Map<String, Long> after) {
    TreeSet<String> ids = new TreeSet<>();
    ids.addAll(before.keySet());
    ids.addAll(after.keySet());
    ids.removeIf(id -> java.util.Objects.equals(before.get(id), after.get(id)));
    return ids.toArray(new String[0]);
  }

  private static String[] sortedCommodityIds(List<? extends CommodityOnMarketAPI> commodities) {
    TreeSet<String> ids = new TreeSet<>();
    if (commodities != null) {
      for (CommodityOnMarketAPI commodity : commodities) {
        if (commodity == null) continue;
        String id = commodity.getId();
        if (id != null && !id.isBlank()) ids.add(id);
      }
    }
    return ids.toArray(new String[0]);
  }

  private static String[] normalizeCommodityIds(String[] ids) {
    TreeSet<String> normalized = new TreeSet<>();
    if (ids != null) {
      for (String id : ids) {
        if (id != null && !id.isBlank()) normalized.add(id);
      }
    }
    return normalized.toArray(new String[0]);
  }

  private static String[] mergeCommodityIds(String[] left, String[] right) {
    TreeSet<String> merged = new TreeSet<>();
    Collections.addAll(merged, normalizeCommodityIds(left));
    Collections.addAll(merged, normalizeCommodityIds(right));
    return merged.toArray(new String[0]);
  }

  private static long mixFingerprint(long hash, long value) {
    hash ^= value;
    return hash * 0x100000001b3L;
  }

  static void recordVanillaMarketOpenRefresh(Object economy, Object market, long fingerprint) {
    synchronized (VANILLA_MARKET_OPEN_LOCK) {
      vanillaMarketOpenEconomy = new WeakReference<>(economy);
      vanillaMarketOpenMarket = new WeakReference<>(market);
      vanillaMarketOpenFingerprint = fingerprint;
      vanillaMarketOpenThreadId = Thread.currentThread().getId();
    }
  }

  static boolean consumeVanillaMarketOpenRefresh(Object economy, Object market, long fingerprint) {
    synchronized (VANILLA_MARKET_OPEN_LOCK) {
      boolean identityMatch = vanillaMarketOpenEconomy.get() == economy && vanillaMarketOpenMarket.get() == market
          && vanillaMarketOpenThreadId == Thread.currentThread().getId();
      boolean fingerprintMatch = identityMatch && vanillaMarketOpenFingerprint == fingerprint;
      if (identityMatch && !fingerprintMatch) {
        VANILLA_MARKET_OPEN_CARGO_FINGERPRINT_MISMATCHES.increment();
      }
      clearVanillaMarketOpenCoalescingTokenLocked();
      return fingerprintMatch;
    }
  }

  static void clearVanillaMarketOpenCoalescingToken() {
    synchronized (VANILLA_MARKET_OPEN_LOCK) {
      clearVanillaMarketOpenCoalescingTokenLocked();
    }
  }

  private static void clearVanillaMarketOpenCoalescingTokenLocked() {
    vanillaMarketOpenEconomy.clear();
    vanillaMarketOpenMarket.clear();
    vanillaMarketOpenEconomy = new WeakReference<>(null);
    vanillaMarketOpenMarket = new WeakReference<>(null);
    vanillaMarketOpenFingerprint = 0L;
    vanillaMarketOpenThreadId = 0L;
  }

  public static void setVanillaMarketOpenEconomyContract(boolean operational, String reason) {
    vanillaMarketOpenEconomyContractOperational = operational;
    publishVanillaMarketOpenContractStatus(reason);
  }

  public static void setVanillaMarketOpenReachContract(boolean operational, String reason) {
    vanillaMarketOpenReachContractOperational = operational;
    publishVanillaMarketOpenContractStatus(reason);
  }

  private static void publishVanillaMarketOpenContractStatus(String reason) {
    boolean ready = isVanillaMarketOpenLocalizationContractOperational();
    System.setProperty("starsector.prepatcher.vanillaMarketOpenLocalizationContract", ready ? "ready" : "disabled");
    if (!ready && reason != null && !reason.isBlank()) {
      PrepatcherLog.warn("Vanilla market-open localization disabled: " + reason);
    }
  }

  public static boolean isVanillaMarketOpenLocalizationContractOperational() {
    return vanillaMarketOpenLocalizationConfigured && vanillaMarketOpenEconomyContractOperational
        && vanillaMarketOpenReachContractOperational;
  }

  public static void setCommodityMarketDataContract(boolean operational, String reason) {
    commodityMarketDataContractOperational = operational;
    System.setProperty("starsector.prepatcher.commodityMarketDataContract",
        isCommodityMarketDataContractOperational() ? "ready" : "disabled");
    if (!operational && reason != null && !reason.isBlank()) {
      PrepatcherLog.warn("Vanilla affected-commodity commits disabled: " + reason);
    }
  }

  public static boolean isCommodityMarketDataContractOperational() {
    return uiMarketMutationRefreshConfigured && commodityMarketDataContractOperational
        && isVanillaMarketOpenLocalizationContractOperational();
  }

  public static long getAffectedCommodityRefreshes() { return AFFECTED_COMMODITY_REFRESHES.sum(); }

  public static long getAffectedCommodityGlobalFallbacks() { return AFFECTED_COMMODITY_GLOBAL_FALLBACKS.sum(); }

  public static long getTradeAffectedCommodityRefreshes() { return TRADE_AFFECTED_COMMODITY_REFRESHES.sum(); }

  public static long getFreePortAffectedCommodityRefreshes() { return FREE_PORT_AFFECTED_COMMODITY_REFRESHES.sum(); }

  public static long getIndustryAffectedCommodityRefreshes() { return INDUSTRY_AFFECTED_COMMODITY_REFRESHES.sum(); }

  public static long getIndustryLocalCommits() { return INDUSTRY_LOCAL_COMMITS.sum(); }

  /** Publishes the exact current-game Economy.tripleStep/getEconomy contract. */
  public static void setVanillaDetachedCargoEconomyContract(boolean operational, String reason) {
    vanillaDetachedCargoEconomyContractOperational =
        operational && aotdUiEconomyDispatchConfigured && aotdUiEconomyDispatchOperational;
    System.setProperty("starsector.prepatcher.detachedCargoVanillaEconomyContract",
        vanillaDetachedCargoEconomyContractOperational ? "ready" : "disabled");
    if (!vanillaDetachedCargoEconomyContractOperational && reason != null && !reason.isBlank()) {
      PrepatcherLog.warn("Vanilla detached-Cargo economy skip disabled: " + reason);
    }
  }

  public static boolean isVanillaDetachedCargoEconomyContractOperational() {
    return vanillaDetachedCargoEconomyContractOperational;
  }

  /** Number of exact-vanilla detached-Cargo triple steps suppressed. */
  public static long getDetachedCargoVanillaStepsSkipped() {
    return DETACHED_CARGO_VANILLA_STEPS_SKIPPED.sum();
  }

  public static long getLootTransferVanillaStepsSkipped() { return LOOT_TRANSFER_VANILLA_STEPS_SKIPPED.sum(); }

  public static long getConditionOnlyVanillaStepsSkipped() { return CONDITION_ONLY_VANILLA_STEPS_SKIPPED.sum(); }

  public static long getVanillaMarketOpensLocalized() { return VANILLA_MARKET_OPENS_LOCALIZED.sum(); }

  public static long getVanillaMarketOpenCargoStepsCoalesced() {
    return VANILLA_MARKET_OPEN_CARGO_STEPS_COALESCED.sum();
  }

  public static long getVanillaMarketOpenLocalizationFailures() {
    return VANILLA_MARKET_OPEN_LOCALIZATION_FAILURES.sum();
  }

  /** Number of non-vanilla detached-Cargo implementations preserved without AoTD ABI. */
  public static long getDetachedCargoUnknownEconomyFallbacks() {
    return DETACHED_CARGO_UNKNOWN_ECONOMY_FALLBACKS.sum();
  }
}
