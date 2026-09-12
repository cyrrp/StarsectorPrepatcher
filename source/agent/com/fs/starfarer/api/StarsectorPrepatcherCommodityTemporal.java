package com.fs.starfarer.api;

import com.fs.starfarer.api.combat.MutableStatWithTempMods;
import com.fs.starfarer.campaign.econ.CommodityOnMarket;
import com.fs.starfarer.campaign.econ.Market;
import com.starsector.prepatcher.agent.PrepatcherConfig;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

/** Ordered commodity active set, AoTD stat compatibility, and per-market temporal state. */
final class StarsectorPrepatcherCommodityTemporal {

  // The four high-volume market counters are sampled once per 64 calls. Exact
  // counters remain only on rare state transitions/fallbacks so observability
  // cannot become a new economy hot path.
  static final int COMMODITY_TEMPORAL_METRIC_SAMPLE_SHIFT = 6;
  static final int COMMODITY_TEMPORAL_METRIC_SAMPLE_MASK = (1 << COMMODITY_TEMPORAL_METRIC_SAMPLE_SHIFT) - 1;
  static int commodityTemporalMetricsTick;
  // Aggressive economy: ordered commodity active set

  static final int COMMODITY_STAT_AVAILABLE = 1;
  static final int COMMODITY_STAT_AOTD_EXCESS = 1 << 1;
  static final int COMMODITY_STAT_AOTD_DEFICIT = 1 << 2;
  static final int COMMODITY_STAT_TRADE = 1 << 3;
  static final int COMMODITY_STAT_TRADE_PLUS = 1 << 4;
  static final int COMMODITY_STAT_TRADE_MINUS = 1 << 5;
  static final int COMMODITY_MASK_AVAILABILITY =
      COMMODITY_STAT_AVAILABLE | COMMODITY_STAT_AOTD_EXCESS | COMMODITY_STAT_AOTD_DEFICIT;
  static final int COMMODITY_MASK_TRADE = COMMODITY_STAT_TRADE | COMMODITY_STAT_TRADE_PLUS | COMMODITY_STAT_TRADE_MINUS;
  static final int COMMODITY_MASK_EXACT_VANILLA = COMMODITY_STAT_AVAILABLE | COMMODITY_MASK_TRADE;
  static final int COMMODITY_MASK_AOTD_NATIVE = COMMODITY_MASK_AVAILABILITY | COMMODITY_MASK_TRADE;
  static final int COMMODITY_MODE_VANILLA_ONLY = 0;
  static final int COMMODITY_MODE_EXACT_VANILLA = 1;
  static final int COMMODITY_MODE_AOTD_NATIVE = 2;
  static final String AOTD_COMMODITY_CLASS = "data.kaysaar.aotd.tot.scripts.commoditydata.AoTDCommodityOnMarket";
  static final String AOTD_AVAILABLE_STAT_CLASS = "data.kaysaar.aotd.tot.scripts.commoditydata.AoTDAvailableStat";
  static final MethodHandle[] AOTD_TEMPORAL_ACCESS_UNSUPPORTED = new MethodHandle[0];
  static final ClassValue<MethodHandle[]> AOTD_TEMPORAL_ACCESS = new ClassValue<>() {
    @Override
    protected MethodHandle[] computeValue(Class<?> type) {
      return createAoTDTemporalAccess(type);
    }
  };
  /**
   * Called only after transformed MutableStat bytecode observed a non-null
   * private owner binding. Unbound stats never enter this hook.
   */
  public static void markCommodityTemporalOwnerDirty(Object rawOwner, int role, String source) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c == null || !c.commodityTemporalFastPath || !(rawOwner instanceof CommodityTemporalEntry entry)) return;
    // CommodityOnMarket.reapplyEventMod() owns this internal availability key.
    // Waking the entry for its own remove/add cycle would keep it permanently hot.
    if (role == COMMODITY_STAT_AVAILABLE && "eMod".equals(source)) return;
    entry.markDirty(role);
    StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_DIRTY_SIGNALS.increment();
  }
  /** Wakes a bound commodity when public getMods() exposes a live mutable map. */
  public static void markCommodityTemporalStatExposed(Object rawStat) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (c == null || !c.commodityTemporalFastPath || !(rawStat instanceof MutableStatWithTempMods stat)) return;
    // The transformed binding fields are declared in MutableStatWithTempMods
    // itself and therefore also exist on AoTDAvailableStat instances. Unknown
    // subclasses remain harmless because they never receive an owner binding.
    CommodityTemporalStatAccess access = commodityTemporalBaseStatAccess();
    if (!access.supported) return;
    access.markExposure(stat);
  }
  /** Whole-loop replacement for Market.advance() commodity maintenance. */
  @SuppressWarnings("unchecked")
  public static Object advanceMarketCommodityTemporalState(Object rawMarket, Object rawState, float days) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (!(rawMarket instanceof Market market)) return rawState;
    if (c == null || !c.commodityTemporalFastPath || market.getClass() != Market.class) {
      vanillaAdvanceMarketCommodities(market, days);
      StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_FALLBACKS.increment();
      return rawState;
    }
    long generation = StarsectorPrepatcherCampaignCaches.campaignCacheGeneration;
    if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) {
      vanillaAdvanceMarketCommodities(market, days);
      StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_FALLBACKS.increment();
      return rawState;
    }

    List<CommodityOnMarket> commodities;
    try {
      commodities = market.getCommodities();
    } catch (Throwable ex) {
      vanillaAdvanceMarketCommodities(market, days);
      StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_FALLBACKS.increment();
      return null;
    }
    if (commodities == null) {
      vanillaAdvanceMarketCommodities(market, days);
      StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_FALLBACKS.increment();
      return null;
    }

    CommodityTemporalMarketState state;
    if (rawState instanceof CommodityTemporalMarketState existing && existing.owner == market
        && existing.generation == generation) {
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
      StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_FALLBACKS.increment();
      return null;
    }

    // Once an original stat/commodity method has executed, replaying the whole
    // vanilla loop on failure would double-advance earlier entries. Propagate.
    sampleCommodityTemporalMarketMetrics(state);
    state.advancePrepared(days);
    return state;
  }
  static void sampleCommodityTemporalMarketMetrics(CommodityTemporalMarketState state) {
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
    StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_MARKET_CALLS.add(scale);
    StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_ENTRIES.add((long) state.entries.size() * scale);
    StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_ACTIVE_ADVANCES.add((long) active * scale);
    StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_INACTIVE_SKIPS
        .add((long) Math.max(0, state.entries.size() - active) * scale);
    StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_EXACT_VANILLA_ENTRIES.add((long) state.exactVanillaEntries * scale);
    StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_AOTD_NATIVE_ENTRIES.add((long) state.aotdNativeEntries * scale);
    StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_VANILLA_ONLY_ENTRIES.add((long) state.vanillaOnlyEntries * scale);
    StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_EXACT_VANILLA_ACTIVE.add((long) exactVanillaActive * scale);
    StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_AOTD_NATIVE_ACTIVE.add((long) aotdNativeActive * scale);
    StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_VANILLA_ONLY_ACTIVE.add((long) vanillaOnlyActive * scale);
    StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_ACTIVE_AVAILABLE.add((long) availableActive * scale);
    StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_ACTIVE_AOTD_EXCESS.add((long) excessActive * scale);
    StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_ACTIVE_AOTD_DEFICIT.add((long) deficitActive * scale);
    StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_ACTIVE_TRADE.add((long) tradeActive * scale);
    StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_ACTIVE_TRADE_PLUS.add((long) tradePlusActive * scale);
    StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_ACTIVE_TRADE_MINUS.add((long) tradeMinusActive * scale);
  }
  static void vanillaAdvanceMarketCommodities(Market market, float days) {
    Iterator<?> iterator = market.getCommodities().iterator();
    while (iterator.hasNext()) {
      CommodityOnMarket commodity = (CommodityOnMarket) iterator.next();
      vanillaAdvanceCommodity(commodity, days);
    }
  }
  static void vanillaAdvanceCommodity(CommodityOnMarket commodity, float days) {
    commodity.getAvailableStat().advance(days);
    commodity.getTradeMod().advance(days);
    commodity.getTradeModPlus().advance(days);
    commodity.getTradeModMinus().advance(days);
    commodity.reapplyEventMod();
  }
  static CommodityTemporalStatAccess commodityTemporalBaseStatAccess() {
    return COMMODITY_TEMPORAL_STAT_ACCESS.get(MutableStatWithTempMods.class);
  }
  static MethodHandle[] aotdTemporalAccess(Class<?> commodityType) {
    if (commodityType == null || !AOTD_COMMODITY_CLASS.equals(commodityType.getName())) {
      return AOTD_TEMPORAL_ACCESS_UNSUPPORTED;
    }
    return AOTD_TEMPORAL_ACCESS.get(commodityType);
  }
  static MethodHandle[] createAoTDTemporalAccess(Class<?> commodityType) {
    try {
      Method getDataMethod = commodityType.getMethod("getExcDefData");
      Class<?> dataType = getDataMethod.getReturnType();
      Field excessField = dataType.getField("excess");
      Field deficitField = dataType.getField("deficit");
      if (!MutableStatWithTempMods.class.isAssignableFrom(excessField.getType())
          || !MutableStatWithTempMods.class.isAssignableFrom(deficitField.getType())) {
        return AOTD_TEMPORAL_ACCESS_UNSUPPORTED;
      }
      MethodHandles.Lookup lookup = MethodHandles.publicLookup();
      return new MethodHandle[]{lookup.unreflect(getDataMethod), lookup.unreflectGetter(excessField),
          lookup.unreflectGetter(deficitField)};
    } catch (Throwable ignored) {
      return AOTD_TEMPORAL_ACCESS_UNSUPPORTED;
    }
  }
  static MutableStatWithTempMods[] captureAoTDExtraStats(CommodityOnMarket commodity) {
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
      return new MutableStatWithTempMods[]{excessStat, deficitStat};
    } catch (Throwable ignored) {
      return null;
    }
  }
  static final ClassValue<CommodityTemporalStatAccess> COMMODITY_TEMPORAL_STAT_ACCESS = new ClassValue<>() {
    @Override
    protected CommodityTemporalStatAccess computeValue(Class<?> type) {
      if (type != MutableStatWithTempMods.class) { return CommodityTemporalStatAccess.unsupported(); }
      try {
        return CommodityTemporalStatAccess.create(type);
      } catch (Throwable ignored) {
        return CommodityTemporalStatAccess.unsupported();
      }
    }
  };
  static final class CommodityTemporalStatAccess {
    final VarHandle tempMods;
    final VarHandle owner;
    final VarHandle role;
    final MethodHandle auditForCommodity;
    final boolean supported;

    private CommodityTemporalStatAccess(VarHandle tempMods, VarHandle owner, VarHandle role,
        MethodHandle auditForCommodity, boolean supported) {
      this.tempMods = tempMods;
      this.owner = owner;
      this.role = role;
      this.auditForCommodity = auditForCommodity;
      this.supported = supported;
    }

    static CommodityTemporalStatAccess create(Class<?> type) throws Exception {
      MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
      MethodHandle audit =
          lookup.findVirtual(type, "spp$tempModHybridAuditForCommodity", MethodType.methodType(void.class));
      return new CommodityTemporalStatAccess(StarsectorPrepatcherHooks.privateVarHandle(type, "tempMods"),
          StarsectorPrepatcherHooks.privateVarHandle(type, "spp$commodityTemporalOwner"),
          StarsectorPrepatcherHooks.privateVarHandle(type, "spp$commodityTemporalRole"), audit, true);
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
  static final class CommodityTemporalEntry {
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

    CommodityTemporalEntry(CommodityTemporalMarketState owner, CommodityOnMarket commodity) {
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
            MutableStatWithTempMods[] extra = captureAoTDExtraStats(commodity);
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
          unsupported =
              !isSupportedBaseStat(a) || !isSupportedBaseStat(t) || !isSupportedBaseStat(p) || !isSupportedBaseStat(m);
        } else {
          unsupported = !isSupportedAoTDAvailableStat(a) || !isSupportedBaseStat(x) || !isSupportedBaseStat(d)
              || !isSupportedBaseStat(t) || !isSupportedBaseStat(p) || !isSupportedBaseStat(m);
        }
      }

      available = a;
      excess = x;
      deficit = d;
      trade = t;
      tradePlus = p;
      tradeMinus = m;
      mode = unsupported ? COMMODITY_MODE_VANILLA_ONLY : selectedMode;
      supportedMask = mode == COMMODITY_MODE_AOTD_NATIVE ? COMMODITY_MASK_AOTD_NATIVE
          : mode == COMMODITY_MODE_EXACT_VANILLA ? COMMODITY_MASK_EXACT_VANILLA : 0;
      activeMask = supportedMask;
      dirtyMask = supportedMask;
      vanillaOnly = unsupported;
    }

    private static boolean isSupportedBaseStat(MutableStatWithTempMods stat) {
      return stat != null && stat.getClass() == MutableStatWithTempMods.class
          && commodityTemporalBaseStatAccess().supported;
    }

    private static boolean isSupportedAoTDAvailableStat(MutableStatWithTempMods stat) {
      return stat != null && AOTD_AVAILABLE_STAT_CLASS.equals(stat.getClass().getName())
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
      StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_EXPOSURES.increment();
    }

    void bind() {
      if (vanillaOnly) return;
      CommodityTemporalStatAccess access = commodityTemporalBaseStatAccess();
      boolean ok = access.bind(available, this, COMMODITY_STAT_AVAILABLE)
          && (mode != COMMODITY_MODE_AOTD_NATIVE || (access.bind(excess, this, COMMODITY_STAT_AOTD_EXCESS)
              && access.bind(deficit, this, COMMODITY_STAT_AOTD_DEFICIT)))
          && access.bind(trade, this, COMMODITY_STAT_TRADE) && access.bind(tradePlus, this, COMMODITY_STAT_TRADE_PLUS)
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
          && (mode != COMMODITY_MODE_AOTD_NATIVE || (access.audit(excess) && access.audit(deficit)))
          && access.audit(trade) && access.audit(tradePlus) && access.audit(tradeMinus);
    }

    private int liveMask(CommodityTemporalStatAccess access, int mask) {
      int result = 0;
      if ((mask & COMMODITY_STAT_AVAILABLE) != 0 && access.hasMods(available)) {
        result |= COMMODITY_STAT_AVAILABLE;
      }
      if ((mask & COMMODITY_STAT_AOTD_EXCESS) != 0 && access.hasMods(excess)) {
        result |= COMMODITY_STAT_AOTD_EXCESS;
      }
      if ((mask & COMMODITY_STAT_AOTD_DEFICIT) != 0 && access.hasMods(deficit)) {
        result |= COMMODITY_STAT_AOTD_DEFICIT;
      }
      if ((mask & COMMODITY_STAT_TRADE) != 0 && access.hasMods(trade)) {
        result |= COMMODITY_STAT_TRADE;
      }
      if ((mask & COMMODITY_STAT_TRADE_PLUS) != 0 && access.hasMods(tradePlus)) {
        result |= COMMODITY_STAT_TRADE_PLUS;
      }
      if ((mask & COMMODITY_STAT_TRADE_MINUS) != 0 && access.hasMods(tradeMinus)) {
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

      if (mode == COMMODITY_MODE_EXACT_VANILLA && (changed & supportedMask) != 0) {
        commodity.reapplyEventMod();
        StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_REAPPLIES.increment();
        if ((changed & COMMODITY_STAT_AVAILABLE) != 0) {
          StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_AVAILABLE_REAPPLIES.increment();
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
  static final class CommodityTemporalMarketState {
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
          StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_AUDITS.increment();
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
      IdentityHashMap<MutableStatWithTempMods, CommodityTemporalEntry> owners = new IdentityHashMap<>();
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
      auditCountdown = initialCommodityAuditCountdown(owner, generation, auditFrames);
      initializeActive();
      StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_REBUILDS.increment();
      return true;
    }

    private static void registerStats(CommodityTemporalEntry entry,
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

    private static void registerStat(MutableStatWithTempMods stat, CommodityTemporalEntry entry,
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
      if (entry == null || entry.owner != this || entry.listedActive || entry.queuedWakeup) return;
      entry.queuedWakeup = true;
      pendingWakeups.add(entry);
      StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_WAKEUP_ENQUEUES.increment();
    }

    private void initializeActive() {
      active.clear();
      pendingWakeups.clear();
      for (CommodityTemporalEntry entry : entries) {
        entry.queuedWakeup = false;
        entry.listedActive = false;
        if (entry.vanillaOnly || entry.active || entry.activeMask != 0 || entry.dirtyMask != 0 || entry.forceAudit) {
          entry.listedActive = true;
          active.add(entry);
        }
      }
    }

    private void drainWakeups() {
      int count = pendingWakeups.size();
      if (count == 0) return;
      StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_WAKEUP_DRAINS.add(count);

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
        if (!entry.listedActive && (entry.vanillaOnly || entry.active || entry.activeMask != 0 || entry.dirtyMask != 0
            || entry.forceAudit)) {
          insertActiveOrdered(entry);
          StarsectorPrepatcherMetrics.COMMODITY_TEMPORAL_WAKEUP_INSERTS.increment();
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
  static int normalizedCommodityAuditFrames(int frames) {
    return Math.max(1, frames);
  }
  static int initialCommodityAuditCountdown(Object owner, long generation, int frames) {
    int interval = normalizedCommodityAuditFrames(frames);
    if (interval == 1) return 1;
    int hash = System.identityHashCode(owner);
    hash ^= (int) generation;
    hash ^= (int) (generation >>> 32);
    hash ^= hash >>> 16;
    return 1 + Math.floorMod(hash, interval);
  }
}
