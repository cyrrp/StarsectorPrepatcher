package com.fs.starfarer.api;

import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.ConstructionQueue;
import com.fs.starfarer.api.impl.campaign.submarkets.LocalResourcesSubmarketPlugin;
import com.fs.starfarer.campaign.CampaignEngine;
import com.fs.starfarer.campaign.econ.reach.ReachEconomy;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.starsector.prepatcher.agent.PrepatcherConfig;
import org.lwjgl.util.vector.Vector2f;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Public hook surface for the Starsector prepatcher runtime. Patched bytecode calls these
 * methods directly; each one delegates to a package-private implementation class.
 */
public final class StarsectorPrepatcherHooks {

  static volatile PrepatcherConfig config;
  static volatile Path modRoot;
  private StarsectorPrepatcherHooks() {}

  static void configure(PrepatcherConfig optimizerConfig, Path root) {
    config = optimizerConfig;
    modRoot = root;
    StarsectorPrepatcherStrategicJumpHooks.strategicJumpRuntimeCapability =
        StarsectorPrepatcherStrategicJumpHooks.STRATEGIC_JUMP_CAPABILITY_UNKNOWN;
    StarsectorPrepatcherStrategicJumpIndex.reset();
    StarsectorPrepatcherCoreWorldsRuntime.campaignLifecycleReset();
    StarsectorPrepatcherMarketDiagnostics.configureDiagnostics(optimizerConfig, root);
    if (optimizerConfig.statsLogIntervalSeconds > 0) {
      Thread stats = new Thread(StarsectorPrepatcherMetrics::statsLoop, "StarsectorPrepatcher-Stats");
      stats.setDaemon(true);
      stats.setPriority(Thread.MIN_PRIORITY);
      stats.start();
    }
  }
  static VarHandle privateVarHandle(Class<?> type, String name) throws Exception {
    Field field = findField(type, name);
    field.setAccessible(true);
    MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(field.getDeclaringClass(), MethodHandles.lookup());
    return lookup.unreflectVarHandle(field);
  }
  static Method findDeclaredMethod(Class<?> type, String name, Class<?>... parameterTypes)
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
  static Field findUniqueFieldByTypeName(Class<?> type, String fieldTypeName, String label)
      throws NoSuchFieldException {
    Field result = null;
    Class<?> current = type;
    while (current != null) {
      for (Field field : current.getDeclaredFields()) {
        if (!field.getType().getName().equals(fieldTypeName)) continue;
        if (result != null) { throw new NoSuchFieldException("Ambiguous " + label + " in " + type.getName()); }
        result = field;
      }
      current = current.getSuperclass();
    }
    if (result == null) throw new NoSuchFieldException(label + " in " + type.getName());
    return result;
  }
  static Field findField(Class<?> type, String name) throws NoSuchFieldException {
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
  static void throwUnchecked(Throwable cause) {
    if (cause instanceof RuntimeException runtime) throw runtime;
    if (cause instanceof Error error) throw error;
    throw new RuntimeException(cause);
  }
  // Spatial cell helpers

  static int floorCell(float value, float cellSize) {
    return safeFloorToInt(value / cellSize);
  }
  static int safeFloorToInt(float value) {
    if (value >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
    if (value <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
    return (int) Math.floor(value);
  }
  static long cellKey(int x, int y) {
    return ((long) x << 32) ^ (y & 0xffffffffL);
  }

  // StarsectorPrepatcherMarketScheduler

  public static boolean isBaseIndustryDormantFastPathEligible(Object industry) {
    return StarsectorPrepatcherMarketScheduler.isBaseIndustryDormantFastPathEligible(industry);
  }
  public static void beginMarketAdvanceInvocation(MarketAPI market) {
    StarsectorPrepatcherMarketScheduler.beginMarketAdvanceInvocation(market);
  }
  public static void endMarketAdvanceInvocation() {
    StarsectorPrepatcherMarketScheduler.endMarketAdvanceInvocation();
  }
  public static boolean hasCurrentMarketAdvanceBatch(MarketAPI market) {
    return StarsectorPrepatcherMarketScheduler.hasCurrentMarketAdvanceBatch(market);
  }
  public static int currentMarketAdvanceBatchRunCount(MarketAPI market) {
    return StarsectorPrepatcherMarketScheduler.currentMarketAdvanceBatchRunCount(market);
  }
  public static float currentMarketAdvanceBatchRunAmount(MarketAPI market, int runIndex) {
    return StarsectorPrepatcherMarketScheduler.currentMarketAdvanceBatchRunAmount(market, runIndex);
  }
  public static int currentMarketAdvanceBatchRunRepeats(MarketAPI market, int runIndex) {
    return StarsectorPrepatcherMarketScheduler.currentMarketAdvanceBatchRunRepeats(market, runIndex);
  }
  public static void recordMarketComponentReplayedStep(int component) {
    StarsectorPrepatcherMarketScheduler.recordMarketComponentReplayedStep(component);
  }
  public static boolean shouldContinueRecentUnrestReplay(MarketAPI market) {
    return StarsectorPrepatcherMarketScheduler.shouldContinueRecentUnrestReplay(market);
  }
  public static ConstructionQueue registerConstructionQueueOwner(ConstructionQueue queue, MarketAPI market) {
    return StarsectorPrepatcherMarketScheduler.registerConstructionQueueOwner(queue, market);
  }
  public static void flushPendingConstructionQueueBeforeMutation(ConstructionQueue queue) {
    StarsectorPrepatcherMarketScheduler.flushPendingConstructionQueueBeforeMutation(queue);
  }
  public static void flushPendingMarketBeforeAoTDMutation(Object rawMarket) {
    StarsectorPrepatcherMarketScheduler.flushPendingMarketBeforeAoTDMutation(rawMarket);
  }
  public static void flushPendingMarketBeforeUiRefresh(MarketAPI market) {
    StarsectorPrepatcherMarketScheduler.flushPendingMarketBeforeUiRefresh(market);
  }
  public static void flushPendingMarketBeforeMutation(MarketAPI market) {
    StarsectorPrepatcherMarketScheduler.flushPendingMarketBeforeMutation(market);
  }
  public static void registerDirectMarketCallSite(long siteId, String metadata) {
    StarsectorPrepatcherMarketScheduler.registerDirectMarketCallSite(siteId, metadata);
  }
  public static void observeDirectMarketAdvance(MarketAPI market, float amount, long siteId, String metadata) {
    StarsectorPrepatcherMarketScheduler.observeDirectMarketAdvance(market, amount, siteId, metadata);
  }
  public static void observeMarketAdvanceEntry(MarketAPI market, float amount) {
    StarsectorPrepatcherMarketScheduler.observeMarketAdvanceEntry(market, amount);
  }
  public static void advanceMarketSynchronized(MarketAPI market, float amount, int source) {
    StarsectorPrepatcherMarketScheduler.advanceMarketSynchronized(market, amount, source);
  }
  public static void registerMarketSchedulerComponent(int component) {
    StarsectorPrepatcherMarketScheduler.registerMarketSchedulerComponent(component);
  }
  public static void beginMarketSchedulerTick() {
    StarsectorPrepatcherMarketScheduler.beginMarketSchedulerTick();
  }
  public static void endMarketSchedulerTick() {
    StarsectorPrepatcherMarketScheduler.endMarketSchedulerTick();
  }
  public static void marketSchedulerFastForwardIterationChanged(boolean fastForwardIteration) {
    StarsectorPrepatcherMarketScheduler.marketSchedulerFastForwardIterationChanged(fastForwardIteration);
  }
  public static void advanceMarketScheduled(MarketAPI market, float amount, int source) {
    StarsectorPrepatcherMarketScheduler.advanceMarketScheduled(market, amount, source);
  }
  public static void flushMarketSchedulerBeforeAoTDGlobalBoundary() {
    StarsectorPrepatcherMarketScheduler.flushMarketSchedulerBeforeAoTDGlobalBoundary();
  }
  public static void flushMarketSchedulerBeforeSave() {
    StarsectorPrepatcherMarketScheduler.flushMarketSchedulerBeforeSave();
  }
  public static String dumpMarketSchedulerBaseline(String reason) {
    return StarsectorPrepatcherMarketScheduler.dumpMarketSchedulerBaseline(reason);
  }

  // StarsectorPrepatcherScratch

  public static void beginScratchScope() {
    StarsectorPrepatcherScratch.beginScratchScope();
  }
  public static void endScratchScope() {
    StarsectorPrepatcherScratch.endScratchScope();
  }
  public static boolean retainAllFast(Set target, Collection keep, Object mapOwner) {
    return StarsectorPrepatcherScratch.retainAllFast(target, keep, mapOwner);
  }
  public static ArrayList borrowEntityList(Collection source) {
    return StarsectorPrepatcherScratch.borrowEntityList(source);
  }
  public static List borrowLocationAdvanceSnapshot(Collection source) {
    return StarsectorPrepatcherScratch.borrowLocationAdvanceSnapshot(source);
  }
  public static List borrowPausedLocationSnapshot(Collection source) {
    return StarsectorPrepatcherScratch.borrowPausedLocationSnapshot(source);
  }
  public static List borrowEntityScriptSnapshot(Collection source) {
    return StarsectorPrepatcherScratch.borrowEntityScriptSnapshot(source);
  }
  public static Iterator memoryExpireIterator(List source) {
    return StarsectorPrepatcherScratch.memoryExpireIterator(source);
  }
  public static Iterator memoryRequireIterator(Collection source) {
    return StarsectorPrepatcherScratch.memoryRequireIterator(source);
  }
  public static boolean scratchListAdd(List receiver, Object value) {
    return StarsectorPrepatcherScratch.scratchListAdd(receiver, value);
  }
  public static boolean scratchSetAdd(Set receiver, Object value) {
    return StarsectorPrepatcherScratch.scratchSetAdd(receiver, value);
  }
  public static boolean scratchSetAddAll(Set receiver, Collection values) {
    return StarsectorPrepatcherScratch.scratchSetAddAll(receiver, values);
  }
  public static ArrayList borrowShipList() {
    return StarsectorPrepatcherScratch.borrowShipList();
  }
  public static ArrayList borrowShipListSnapshot(Collection source) {
    return StarsectorPrepatcherScratch.borrowShipListSnapshot(source);
  }
  public static HashSet borrowShipSet() {
    return StarsectorPrepatcherScratch.borrowShipSet();
  }
  public static ArrayList borrowParticleRemovalList() {
    return StarsectorPrepatcherScratch.borrowParticleRemovalList();
  }
  public static boolean removeExpiredParticles(LinkedList particles, Collection expired) {
    return StarsectorPrepatcherScratch.removeExpiredParticles(particles, expired);
  }
  public static HashSet borrowClassSet() {
    return StarsectorPrepatcherScratch.borrowClassSet();
  }
  public static ArrayList borrowHitList(Collection source) {
    return StarsectorPrepatcherScratch.borrowHitList(source);
  }
  public static Vector2f borrowHitPoint(float x, float y) {
    return StarsectorPrepatcherScratch.borrowHitPoint(x, y);
  }
  public static Vector2f borrowArrowVector(float x, float y) {
    return StarsectorPrepatcherScratch.borrowArrowVector(x, y);
  }

  // StarsectorPrepatcherCampaignCaches

  public static long beginCampaignEngineChange(Object engine) {
    return StarsectorPrepatcherCampaignCaches.beginCampaignEngineChange(engine);
  }
  public static void completeCampaignEngineChange(Object engine, long transition) {
    StarsectorPrepatcherCampaignCaches.completeCampaignEngineChange(engine, transition);
  }
  public static void campaignCacheMaintenanceTick() {
    StarsectorPrepatcherCampaignCaches.campaignCacheMaintenanceTick();
  }
  public static void runCacheMaintenance(boolean forced) {
    StarsectorPrepatcherCampaignCaches.runCacheMaintenance(forced);
  }

  // StarsectorPrepatcherStartupRuntime

  public static String readUtf8Normalized(InputStream input) throws IOException {
    return StarsectorPrepatcherStartupRuntime.readUtf8Normalized(input);
  }
  public static void startupLogSuppressed(int category) {
    StarsectorPrepatcherStartupRuntime.startupLogSuppressed(category);
  }
  public static void suppressStartupInfo(Object logger, Object message, int category) {
    StarsectorPrepatcherStartupRuntime.suppressStartupInfo(logger, message, category);
  }
  public static String removeCarriageReturns(String value) {
    return StarsectorPrepatcherStartupRuntime.removeCarriageReturns(value);
  }
  public static String replaceNewlinesWithSpace(String value) {
    return StarsectorPrepatcherStartupRuntime.replaceNewlinesWithSpace(value);
  }
  public static String[] splitNewlines(String value) {
    return StarsectorPrepatcherStartupRuntime.splitNewlines(value);
  }
  public static String[] splitOrBlocks(String value) {
    return StarsectorPrepatcherStartupRuntime.splitOrBlocks(value);
  }
  public static String[] splitColon(String value) {
    return StarsectorPrepatcherStartupRuntime.splitColon(value);
  }
  public static float saveLoadProgressMinIntervalSeconds() {
    return StarsectorPrepatcherStartupRuntime.saveLoadProgressMinIntervalSeconds();
  }

  // StarsectorPrepatcherIntelRuntime

  public static List commRelayCandidateSystems(CampaignEngine engine, Vector2f playerLocation, float unitsPerLy,
      float maxRangeLy) {
    return StarsectorPrepatcherIntelRuntime.commRelayCandidateSystems(engine, playerLocation, unitsPerLy, maxRangeLy);
  }
  public static Collection nearbyLabelIcons(LinkedHashMap icons, Object mapOwner, Object targetIcon) {
    return StarsectorPrepatcherIntelRuntime.nearbyLabelIcons(icons, mapOwner, targetIcon);
  }
  public static SectorEntityToken getMapLocationCached(IntelInfoPlugin plugin, SectorMapAPI map) {
    return StarsectorPrepatcherIntelRuntime.getMapLocationCached(plugin, map);
  }
  public static List getArrowDataCached(IntelInfoPlugin plugin, SectorMapAPI map) {
    return StarsectorPrepatcherIntelRuntime.getArrowDataCached(plugin, map);
  }
  public static boolean fastContains(Collection collection, Object value) {
    return StarsectorPrepatcherIntelRuntime.fastContains(collection, value);
  }
  public static Collection existingIntelIconCandidates(LinkedHashMap icons, Object entity) {
    return StarsectorPrepatcherIntelRuntime.existingIntelIconCandidates(icons, entity);
  }
  public static Object getIntelIconEntityIndexed(Object owner, List intelEntities, IntelInfoPlugin plugin) {
    return StarsectorPrepatcherIntelRuntime.getIntelIconEntityIndexed(owner, intelEntities, plugin);
  }

  // StarsectorPrepatcherMapRuntime

  public static SectorEntityToken hitTestCached(Object handler, float x, float y, float radius) {
    return StarsectorPrepatcherMapRuntime.hitTestCached(handler, x, y, radius);
  }
  public static void updateSystemNebulasCached(Object owner, List systemNebulas, List constellationLabels,
      List nebulaStars) {
    StarsectorPrepatcherMapRuntime.updateSystemNebulasCached(owner, systemNebulas, constellationLabels, nebulaStars);
  }
  public static void forceClearSampleCacheThrottled(Object plugin) {
    StarsectorPrepatcherMapRuntime.forceClearSampleCacheThrottled(plugin);
  }
  public static float gridSpacing() {
    return StarsectorPrepatcherMapRuntime.gridSpacing();
  }

  // StarsectorPrepatcherCampaignListeners

  public static void readdChangeListenersIfNeeded(Object engine, List<?> systems, Object hyperspace) {
    StarsectorPrepatcherCampaignListeners.readdChangeListenersIfNeeded(engine, systems, hyperspace);
  }

  // StarsectorPrepatcherRouteIndex

  public static List routeJumpPointsForSystem(LocationAPI hyperspace, Object system) {
    return StarsectorPrepatcherRouteIndex.routeJumpPointsForSystem(hyperspace, system);
  }
  public static List routeSystemsForAnchor(Object engine, Object anchor) {
    return StarsectorPrepatcherRouteIndex.routeSystemsForAnchor(engine, anchor);
  }

  // StarsectorPrepatcherStrategicJumpHooks

  public static List strategicJumpPointsForDestination(LocationAPI current, Class jumpPointType,
      LocationAPI requested) {
    return StarsectorPrepatcherStrategicJumpHooks.strategicJumpPointsForDestination(current, jumpPointType, requested);
  }
  static List strategicJumpPointsForDestination(LocationAPI current, Class jumpPointType, LocationAPI requested,
      LocationAPI hyperspace) {
    return StarsectorPrepatcherStrategicJumpHooks.strategicJumpPointsForDestination(current, jumpPointType, requested,
        hyperspace);
  }
  public static boolean strategicJumpDeferExpiredPlan(LocationAPI current, LocationAPI requested) {
    return StarsectorPrepatcherStrategicJumpHooks.strategicJumpDeferExpiredPlan(current, requested);
  }
  public static void strategicJumpDestinationsChanged(JumpPointAPI jumpPoint) {
    StarsectorPrepatcherStrategicJumpHooks.strategicJumpDestinationsChanged(jumpPoint);
  }
  public static void strategicJumpDestinationAdded(JumpPointAPI jumpPoint, JumpPointAPI.JumpDestination destination) {
    StarsectorPrepatcherStrategicJumpHooks.strategicJumpDestinationAdded(jumpPoint, destination);
  }
  public static void strategicJumpDestinationRetargeted(JumpPointAPI.JumpDestination destination) {
    StarsectorPrepatcherStrategicJumpHooks.strategicJumpDestinationRetargeted(destination);
  }
  public static void strategicJumpLocationEntityChanged(LocationAPI location, Object entity) {
    StarsectorPrepatcherStrategicJumpHooks.strategicJumpLocationEntityChanged(location, entity);
  }

  // StarsectorPrepatcherEconomyRuntime

  public static void updateEconomyLocationMapIfNeeded(ReachEconomy economy) {
    StarsectorPrepatcherEconomyRuntime.updateEconomyLocationMapIfNeeded(economy);
  }
  public static void updateEconomyLocationMapIfNeededPersistent(ReachEconomy economy, Object persistentState) {
    StarsectorPrepatcherEconomyRuntime.updateEconomyLocationMapIfNeededPersistent(economy, persistentState);
  }
  public static void registerEconomyGroupIndexComponent(int component) {
    StarsectorPrepatcherEconomyRuntime.registerEconomyGroupIndexComponent(component);
  }
  public static void markEconomyGroupStructureChanged() {
    StarsectorPrepatcherEconomyRuntime.markEconomyGroupStructureChanged();
  }
  public static void markEconomyGroupStructureChanged(Object rawState) {
    StarsectorPrepatcherEconomyRuntime.markEconomyGroupStructureChanged(rawState);
  }
  public static Object newPersistentSnapshotState() {
    return StarsectorPrepatcherEconomyRuntime.newPersistentSnapshotState();
  }
  public static void markPersistentSnapshotStructure(Object rawState) {
    StarsectorPrepatcherEconomyRuntime.markPersistentSnapshotStructure(rawState);
  }
  public static long persistentSnapshotEpoch(Object rawState) {
    return StarsectorPrepatcherEconomyRuntime.persistentSnapshotEpoch(rawState);
  }
  public static ArrayList borrowPersistentSnapshotTimed(Object rawState, Collection source, int auditMs, int kind) {
    return StarsectorPrepatcherEconomyRuntime.borrowPersistentSnapshotTimed(rawState, source, auditMs, kind);
  }
  public static ArrayList borrowPersistentSnapshotFrames(Object rawState, Collection source, int auditFrames,
      int kind) {
    return StarsectorPrepatcherEconomyRuntime.borrowPersistentSnapshotFrames(rawState, source, auditFrames, kind);
  }
  public static boolean localResourcesShouldHaveCommodity(MarketAPI market, CommodityOnMarketAPI commodity) {
    return StarsectorPrepatcherEconomyRuntime.localResourcesShouldHaveCommodity(market, commodity);
  }
  public static boolean renderLocalResourcesTooltipSnapshot(LocalResourcesSubmarketPlugin plugin,
      TooltipMakerAPI tooltip, boolean expanded) {
    return StarsectorPrepatcherEconomyRuntime.renderLocalResourcesTooltipSnapshot(plugin, tooltip, expanded);
  }
  public static List borrowMarketsInGroupIndexed(ReachEconomy economy, Object rawState, String group) {
    return StarsectorPrepatcherEconomyRuntime.borrowMarketsInGroupIndexed(economy, rawState, group);
  }

  // StarsectorPrepatcherCommodityTemporal

  public static void markCommodityTemporalOwnerDirty(Object rawOwner, int role, String source) {
    StarsectorPrepatcherCommodityTemporal.markCommodityTemporalOwnerDirty(rawOwner, role, source);
  }
  public static void markCommodityTemporalStatExposed(Object rawStat) {
    StarsectorPrepatcherCommodityTemporal.markCommodityTemporalStatExposed(rawStat);
  }
  public static Object advanceMarketCommodityTemporalState(Object rawMarket, Object rawState, float days) {
    return StarsectorPrepatcherCommodityTemporal.advanceMarketCommodityTemporalState(rawMarket, rawState, days);
  }
}
