package com.fs.starfarer.api;

import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.campaign.CampaignEngine;
import com.fs.starfarer.campaign.JumpPoint;
import com.starsector.prepatcher.agent.PrepatcherConfig;
import java.util.Collections;
import java.util.List;

/** Strategic jump hook entry points and runtime capability negotiation. */
final class StarsectorPrepatcherStrategicJumpHooks {

  static final int STRATEGIC_JUMP_CAPABILITY_UNKNOWN = 0;
  static final int STRATEGIC_JUMP_CAPABILITY_READY = 1;
  static final int STRATEGIC_JUMP_CAPABILITY_PENDING = 2;
  static final int STRATEGIC_JUMP_CAPABILITY_FAILED = -1;
  static volatile int strategicJumpRuntimeCapability;
  static final String STRATEGIC_JUMP_MAINTENANCE_STATUS =
      "starsector.prepatcher.patchStatus.com.fs.starfarer.campaign.CampaignEngine." + "campaignCacheLifecycle";
  static final String STRATEGIC_JUMP_PLAN_STATUS =
      "starsector.prepatcher.patchStatus.com.fs.starfarer.campaign.ai.StrategicModule."
          + "strategicJumpDestinationPlan";
  static final String STRATEGIC_JUMP_POINT_STATUS =
      "starsector.prepatcher.patchStatus.com.fs.starfarer.campaign.JumpPoint." + "strategicJumpDestinationIndex";
  static final String STRATEGIC_JUMP_LOCATION_STATUS =
      "starsector.prepatcher.patchStatus.com.fs.starfarer.campaign.BaseLocation." + "strategicJumpDestinationIndex";
  static final String STRATEGIC_JUMP_DESTINATION_STATUS = "starsector.prepatcher.patchStatus."
      + "com.fs.starfarer.api.campaign.JumpPointAPI$JumpDestination." + "strategicJumpDestinationIndex";
  // Strategic fleet jump-point index.
  //
  // StrategicModule keeps its original destination, safety, distance and
  // tie-breaking code. The hook changes only the ordered source list. A
  // location-agnostic state machine builds that exact source incrementally
  // under one wall-clock token bucket shared by every location. Until READY,
  // an immutable empty list defers this route rebuild instead of re-entering
  // the original full scan.

  @SuppressWarnings({"rawtypes", "unchecked"})
  public static List strategicJumpPointsForDestination(LocationAPI current, Class jumpPointType,
      LocationAPI requested) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (current == null) return null;
    if (c == null || !c.strategicJumpDestinationIndex) { return strategicJumpVanillaSource(current, jumpPointType); }

    long generation = StarsectorPrepatcherCampaignCaches.campaignCacheGeneration;
    if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) return Collections.emptyList();

    int capability = strategicJumpIndexCapability();
    if (capability == STRATEGIC_JUMP_CAPABILITY_PENDING) { return Collections.emptyList(); }
    if (capability != STRATEGIC_JUMP_CAPABILITY_READY) { return strategicJumpVanillaSource(current, jumpPointType); }

    LocationAPI hyperspace;
    try {
      CampaignEngine engine = CampaignEngine.getInstance();
      hyperspace = engine == null ? null : engine.getHyperspace();
    } catch (Throwable ignored) {
      hyperspace = null;
    }
    if (hyperspace == null) return Collections.emptyList();

    try {
      return StarsectorPrepatcherStrategicJumpIndex.lookup(current, jumpPointType, requested, hyperspace, c,
          generation);
    } catch (Throwable failure) {
      // A runtime implementation failure must not make campaign routes
      // disappear permanently. Disable only this optimization and retain
      // the vanilla compatibility path.
      strategicJumpRuntimeCapability = STRATEGIC_JUMP_CAPABILITY_FAILED;
      return strategicJumpVanillaSource(current, jumpPointType);
    }
  }
  @SuppressWarnings({"rawtypes", "unchecked"})
  static List strategicJumpPointsForDestination(LocationAPI current, Class jumpPointType, LocationAPI requested,
      LocationAPI hyperspace) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (current == null) return null;
    if (c == null || !c.strategicJumpDestinationIndex) { return strategicJumpVanillaSource(current, jumpPointType); }
    long generation = StarsectorPrepatcherCampaignCaches.campaignCacheGeneration;
    if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) return Collections.emptyList();
    int capability = strategicJumpIndexCapability();
    if (capability == STRATEGIC_JUMP_CAPABILITY_PENDING) { return Collections.emptyList(); }
    if (capability != STRATEGIC_JUMP_CAPABILITY_READY) { return strategicJumpVanillaSource(current, jumpPointType); }
    if (hyperspace == null) return Collections.emptyList();
    try {
      return StarsectorPrepatcherStrategicJumpIndex.lookup(current, jumpPointType, requested, hyperspace, c,
          generation);
    } catch (Throwable failure) {
      strategicJumpRuntimeCapability = STRATEGIC_JUMP_CAPABILITY_FAILED;
      return strategicJumpVanillaSource(current, jumpPointType);
    }
  }
  /**
   * Called only from the expired-plan branch in StrategicModule. The prior
   * valid plan remains in place until the exact incremental index is READY.
   */
  public static boolean strategicJumpDeferExpiredPlan(LocationAPI current, LocationAPI requested) {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    if (current == null || c == null || !c.strategicJumpDestinationIndex) { return false; }
    long generation = StarsectorPrepatcherCampaignCaches.campaignCacheGeneration;
    if (!StarsectorPrepatcherCampaignCaches.campaignCachesReady(generation)) return true;
    int capability = strategicJumpIndexCapability();
    if (capability == STRATEGIC_JUMP_CAPABILITY_PENDING) return true;
    if (capability != STRATEGIC_JUMP_CAPABILITY_READY) return false;
    try {
      return StarsectorPrepatcherStrategicJumpIndex.deferExpiredPlan(current, JumpPoint.class, c, generation);
    } catch (Throwable failure) {
      strategicJumpRuntimeCapability = STRATEGIC_JUMP_CAPABILITY_FAILED;
      return false;
    }
  }
  @SuppressWarnings({"rawtypes", "unchecked"})
  static List strategicJumpVanillaSource(LocationAPI current, Class jumpPointType) {
    try {
      return current == null ? null : current.getEntities(jumpPointType);
    } catch (Throwable ignored) {
      return Collections.emptyList();
    }
  }
  public static void strategicJumpDestinationsChanged(JumpPointAPI jumpPoint) {
    try {
      PrepatcherConfig c = StarsectorPrepatcherHooks.config;
      if (c != null && c.strategicJumpDestinationIndex) {
        StarsectorPrepatcherStrategicJumpIndex.destinationsChanged(jumpPoint);
      }
    } catch (Throwable ignored) {
      // Mutation semantics never depend on this optional index.
    }
  }
  public static void strategicJumpDestinationAdded(JumpPointAPI jumpPoint, JumpPointAPI.JumpDestination destination) {
    try {
      PrepatcherConfig c = StarsectorPrepatcherHooks.config;
      if (c != null && c.strategicJumpDestinationIndex) {
        StarsectorPrepatcherStrategicJumpIndex.destinationAdded(jumpPoint, destination);
      }
    } catch (Throwable ignored) {
      // Mutation semantics never depend on this optional index.
    }
  }
  public static void strategicJumpDestinationRetargeted(JumpPointAPI.JumpDestination destination) {
    try {
      PrepatcherConfig c = StarsectorPrepatcherHooks.config;
      if (c != null && c.strategicJumpDestinationIndex) {
        StarsectorPrepatcherStrategicJumpIndex.destinationRetargeted(destination);
      }
    } catch (Throwable ignored) {
      // Mutation semantics never depend on this optional index.
    }
  }
  public static void strategicJumpLocationEntityChanged(LocationAPI location, Object entity) {
    try {
      PrepatcherConfig c = StarsectorPrepatcherHooks.config;
      if (c != null && c.strategicJumpDestinationIndex) {
        StarsectorPrepatcherStrategicJumpIndex.locationEntityChanged(location, entity);
      }
    } catch (Throwable ignored) {
      // Location mutation semantics never depend on this optional index.
    }
  }
  static int strategicJumpIndexCapability() {
    int cached = strategicJumpRuntimeCapability;
    if (cached == STRATEGIC_JUMP_CAPABILITY_READY || cached == STRATEGIC_JUMP_CAPABILITY_FAILED) return cached;

    String maintenance = System.getProperty(STRATEGIC_JUMP_MAINTENANCE_STATUS);
    String plan = System.getProperty(STRATEGIC_JUMP_PLAN_STATUS);
    String point = System.getProperty(STRATEGIC_JUMP_POINT_STATUS);
    String location = System.getProperty(STRATEGIC_JUMP_LOCATION_STATUS);
    String destination = System.getProperty(STRATEGIC_JUMP_DESTINATION_STATUS);
    if (patchApplied(maintenance) && patchApplied(plan) && patchApplied(point) && patchApplied(location)
        && patchApplied(destination)) {
      strategicJumpRuntimeCapability = STRATEGIC_JUMP_CAPABILITY_READY;
      return STRATEGIC_JUMP_CAPABILITY_READY;
    }
    if (patchFailed(maintenance) || patchFailed(plan) || patchFailed(point) || patchFailed(location)
        || patchFailed(destination)) {
      strategicJumpRuntimeCapability = STRATEGIC_JUMP_CAPABILITY_FAILED;
      return STRATEGIC_JUMP_CAPABILITY_FAILED;
    }
    return STRATEGIC_JUMP_CAPABILITY_PENDING;
  }
  static boolean patchApplied(String status) {
    return "APPLIED".equals(status) || "ALREADY_APPLIED".equals(status);
  }
  static boolean patchFailed(String status) {
    return status != null && !patchApplied(status);
  }
}
