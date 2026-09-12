package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.StarsectorPrepatcherHooks;
import com.fs.starfarer.api.combat.MutableStatWithTempMods;
import com.fs.starfarer.campaign.econ.CommodityOnMarket;
import com.fs.starfarer.campaign.econ.Market;
import sun.misc.Unsafe;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Actual-javaagent smoke for the merged Market active set and direct temp-mod hybrid. */
public final class CommodityTemporalAgentSmokeTest {
  private static final Unsafe U = unsafe();

  public static void main(String[] args) throws Exception {
    require(hasField(Market.class, "smo$commodityTemporalState"), "Market commodity active-set field missing");
    require(
        hasField(MutableStatWithTempMods.class, "spp$commodityTemporalOwner")
            && hasField(MutableStatWithTempMods.class, "spp$commodityTemporalRole"),
        "MutableStat owner/role binding missing");
    require(
        hasField(MutableStatWithTempMods.class, "spp$tempModHybridDeferredDays")
            && hasField(MutableStatWithTempMods.class, "spp$tempModHybridTimeToNext")
            && hasField(MutableStatWithTempMods.class, "spp$tempModHybridFlags"),
        "direct temp-mod hybrid fields missing");

    Object engine = new Object();
    long token = StarsectorPrepatcherHooks.beginCampaignEngineChange(engine);
    require(token >= 0L, "campaign lifecycle did not open");
    StarsectorPrepatcherHooks.completeCampaignEngineChange(engine, token);

    Market market = (Market) U.allocateInstance(Market.class);
    ArrayList<CommodityOnMarket> commodities = new ArrayList<>();
    set(market, "commodities", commodities);
    CommodityOnMarket first = commodity(market, "first");
    CommodityOnMarket second = commodity(market, "second");
    commodities.add(first);
    commodities.add(second);

    Object state = StarsectorPrepatcherHooks.advanceMarketCommodityTemporalState(market, null, 0.1f);
    require(state != null, "initial active-set state was not created");
    require(activeSize(state) == 0, "stable commodities did not leave active set");

    state = StarsectorPrepatcherHooks.advanceMarketCommodityTemporalState(market, state, 0.1f);
    require(activeSize(state) == 0, "stable active set unexpectedly repopulated");

    MutableStatWithTempMods available = first.getAvailableStat();
    available.addTemporaryModFlat(1f, "smoke", "smoke", 2f);
    require(activeSize(state) == 0, "dirty signal mutated active list reentrantly during mod code");
    state = StarsectorPrepatcherHooks.advanceMarketCommodityTemporalState(market, state, 0.25f);
    require(activeSize(state) == 1, "temporary modifier did not activate commodity");
    require(close(rawRemaining(available, "smoke"), 1f), "direct hybrid touched backing duration before deadline");
    require(close(floatField(available, "spp$tempModHybridDeferredDays"), 0.25f),
        "direct hybrid did not accumulate deferred days");

    state = StarsectorPrepatcherHooks.advanceMarketCommodityTemporalState(market, state, 0.75f);
    require(!rawMods(available).containsKey("smoke"), "temporary modifier did not expire on exact deadline");
    require(activeSize(state) == 0, "expired commodity remained active");

    long reapplyBefore = counter("COMMODITY_TEMPORAL_REAPPLIES");
    long availableBefore = counter("COMMODITY_TEMPORAL_AVAILABLE_REAPPLIES");
    available.modifyFlat("permanent", 3f, "permanent");
    require(activeSize(state) == 0, "permanent dirty signal mutated active list reentrantly");
    state = StarsectorPrepatcherHooks.advanceMarketCommodityTemporalState(market, state, 0f);
    require(counter("COMMODITY_TEMPORAL_REAPPLIES") > reapplyBefore,
        "permanent availability mutation did not recalculate event mod");
    require(counter("COMMODITY_TEMPORAL_AVAILABLE_REAPPLIES") > availableBefore,
        "availability dirty role was not propagated");
    require(activeSize(state) == 0, "permanent-only commodity did not return to inactive set");

    // Public getMods() permanently chooses the exact vanilla scheduler for this
    // stat, but the market active set still has to recover later mutations made
    // through the retained live map at its bounded audit boundary.
    @SuppressWarnings("unchecked")
    Map<String, MutableStatWithTempMods.TemporaryStatMod> retained = available.getMods();
    state = StarsectorPrepatcherHooks.advanceMarketCommodityTemporalState(market, state, 0f);
    require(activeSize(state) == 0, "read-only map exposure kept commodity active");
    setInt(state, "auditCountdown", 2);
    MutableStatWithTempMods.TemporaryStatMod direct = new MutableStatWithTempMods.TemporaryStatMod(1f, "direct");
    retained.put("direct", direct);
    state = StarsectorPrepatcherHooks.advanceMarketCommodityTemporalState(market, state, 0.25f);
    require(close(rawTimeRemaining(direct), 1f), "retained-map mutation was processed before audit boundary");
    state = StarsectorPrepatcherHooks.advanceMarketCommodityTemporalState(market, state, 0.25f);
    require(close(rawTimeRemaining(direct), 0.75f), "bounded market audit did not recover retained-map mutation");
    require(activeSize(state) == 1, "audited direct mutation was not kept active");

    System.out.println("OK commodity-temporal-agent-smoke stable-skip dirty-wakeup"
        + " exact-expiry permanent-dirty retained-map-audit" + " marketCalls="
        + counter("COMMODITY_TEMPORAL_MARKET_CALLS") + " active=" + counter("COMMODITY_TEMPORAL_ACTIVE_ADVANCES")
        + " skipped=" + counter("COMMODITY_TEMPORAL_INACTIVE_SKIPS") + " audits="
        + counter("COMMODITY_TEMPORAL_AUDITS"));
  }

  private static CommodityOnMarket commodity(Market market, String id) throws Exception {
    CommodityOnMarket commodity = (CommodityOnMarket) U.allocateInstance(CommodityOnMarket.class);
    setVar(CommodityOnMarket.class, commodity, "market", Market.class, market);
    setVar(CommodityOnMarket.class, commodity, "commodityId", String.class, id);
    setVar(CommodityOnMarket.class, commodity, "available", MutableStatWithTempMods.class,
        new MutableStatWithTempMods(0f));
    setVar(CommodityOnMarket.class, commodity, "tradeMod", MutableStatWithTempMods.class,
        new MutableStatWithTempMods(0f));
    setVar(CommodityOnMarket.class, commodity, "tradeModPlus", MutableStatWithTempMods.class,
        new MutableStatWithTempMods(0f));
    setVar(CommodityOnMarket.class, commodity, "tradeModMinus", MutableStatWithTempMods.class,
        new MutableStatWithTempMods(0f));
    return commodity;
  }

  private static int activeSize(Object state) throws Exception {
    return ((List<?>) field(state.getClass(), "active").get(state)).size();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, MutableStatWithTempMods.TemporaryStatMod> rawMods(MutableStatWithTempMods stat)
      throws Exception {
    return (Map<String, MutableStatWithTempMods.TemporaryStatMod>) field(MutableStatWithTempMods.class, "tempMods")
        .get(stat);
  }

  private static float rawRemaining(MutableStatWithTempMods stat, String id) throws Exception {
    Object mod = rawMods(stat).get(id);
    require(mod != null, "temporary modifier missing: " + id);
    return rawTimeRemaining(mod);
  }

  private static float rawTimeRemaining(Object mod) throws Exception {
    return field(mod.getClass(), "timeRemaining").getFloat(mod);
  }

  private static float floatField(Object target, String name) throws Exception {
    return field(target.getClass(), name).getFloat(target);
  }

  private static long counter(String name) throws Exception {
    Field f = field(Class.forName("com.fs.starfarer.api.StarsectorPrepatcherMetrics"), name);
    Object adder = f.get(null);
    return (long) adder.getClass().getMethod("sum").invoke(adder);
  }

  private static void setVar(Class<?> owner, Object target, String name, Class<?> type, Object value) throws Exception {
    MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
    VarHandle handle = lookup.findVarHandle(owner, name, type);
    handle.set(target, value);
  }

  private static void set(Object target, String name, Object value) throws Exception {
    field(target.getClass(), name).set(target, value);
  }

  private static void setInt(Object target, String name, int value) throws Exception {
    field(target.getClass(), name).setInt(target, value);
  }

  private static Field field(Class<?> type, String name) throws Exception {
    for (Class<?> c = type; c != null; c = c.getSuperclass()) {
      try {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
      } catch (NoSuchFieldException ignored) {}
    }
    throw new NoSuchFieldException(type.getName() + "." + name);
  }

  private static boolean hasField(Class<?> type, String name) {
    try {
      field(type, name);
      return true;
    } catch (Exception ignored) {
      return false;
    }
  }

  private static boolean close(float a, float b) {
    return Math.abs(a - b) < 0.00001f;
  }

  private static Unsafe unsafe() {
    try {
      Field f = Unsafe.class.getDeclaredField("theUnsafe");
      f.setAccessible(true);
      return (Unsafe) f.get(null);
    } catch (Exception ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
