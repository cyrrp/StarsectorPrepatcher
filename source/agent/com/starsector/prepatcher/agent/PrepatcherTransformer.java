package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.starsector.prepatcher.agent.Allocations.*;
import static com.starsector.prepatcher.agent.ClassIO.*;
import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.EconomyGroupIndex.*;
import static com.starsector.prepatcher.agent.Flow.*;
import static com.starsector.prepatcher.agent.Markers.*;
import static com.starsector.prepatcher.agent.MarketAdvance.*;
import static com.starsector.prepatcher.agent.MarketScheduler.*;
import static com.starsector.prepatcher.agent.Mutations.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.OwnedTemplates.*;
import static com.starsector.prepatcher.agent.PersistentState.*;
import static com.starsector.prepatcher.agent.Queries.*;

/**
 * Applies each optimization only when its local bytecode contract can be proven
 * against the class bytes actually supplied by the JVM. No JAR or class digest is
 * used as a compatibility decision.
 */
public final class PrepatcherTransformer implements ClassFileTransformer {

  private static final String CORE_WORLDS_PATCH_ID = "coreWorldsExtentCache";

  private static final String AOTD_DEFICIT_PATCH_ID = "aotdCleanDeficitPath";


  private static final String MARKET_SHARE_LINEAR_PATCH_ID = "marketShareLinearAggregation";
  private static final String MARKET_SHARE_DATA_PUT_ELISION_PATCH_ID = "marketShareDataPutElision";
  private static final String PUNITIVE_PLAYER_SHARE_PATCH_ID = "punitivePlayerShareLocalCache";


  static final String H = "com/fs/starfarer/coreui/A/H";
  static final String A = "com/fs/starfarer/coreui/A/A";
  static final String Z = "com/fs/starfarer/coreui/A/Z";
  static final String EVENTS = "com/fs/starfarer/campaign/comms/v2/EventsPanel";
  static final String CAMPAIGN_STATE = "com/fs/starfarer/campaign/CampaignState";
  static final String CAMPAIGN_ENGINE = "com/fs/starfarer/campaign/CampaignEngine";
  static final String COURSE_WIDGET = "com/fs/starfarer/coreui/A/O0Oo";
  static final String STRATEGIC_MODULE = "com/fs/starfarer/campaign/ai/StrategicModule";
  static final String JUMP_POINT = "com/fs/starfarer/campaign/JumpPoint";
  static final String JUMP_DESTINATION = "com/fs/starfarer/api/campaign/JumpPointAPI$JumpDestination";
  static final String BASE_LOCATION = "com/fs/starfarer/campaign/BaseLocation";
  static final String BASE_CAMPAIGN_ENTITY = "com/fs/starfarer/campaign/BaseCampaignEntity";
  static final String MEMORY = "com/fs/starfarer/campaign/rules/Memory";
  static final String CORE_SCRIPT = "com/fs/starfarer/api/impl/campaign/CoreScript";
  static final String ECONOMY = "com/fs/starfarer/campaign/econ/Economy";
  static final String MARKET = "com/fs/starfarer/campaign/econ/Market";
  static final String BASE_INDUSTRY = "com/fs/starfarer/api/impl/campaign/econ/impl/BaseIndustry";
  static final String COMMODITY_MARKET_DATA = "com/fs/starfarer/campaign/econ/reach/CommodityMarketData";
  static final String PUNITIVE_EXPEDITION_MANAGER =
      "com/fs/starfarer/api/impl/campaign/intel/punitive/PunitiveExpeditionManager";
  static final String NEX_PUNITIVE_EXPEDITION_MANAGER = "exerelin/campaign/intel/Nex_PunitiveExpeditionManager";


  static final String MILITARY_BASE = "com/fs/starfarer/api/impl/campaign/econ/impl/MilitaryBase";
  static final String LIONS_GUARD_HQ = "com/fs/starfarer/api/impl/campaign/econ/impl/LionsGuardHQ";
  static final String RECENT_UNREST = "com/fs/starfarer/api/impl/campaign/econ/RecentUnrest";
  static final String CONSTRUCTION_QUEUE = "com/fs/starfarer/api/impl/campaign/econ/impl/ConstructionQueue";
  static final String COMMODITY_ON_MARKET = "com/fs/starfarer/campaign/econ/CommodityOnMarket";
  static final String LOCAL_RESOURCES_SUBMARKET =
      "com/fs/starfarer/api/impl/campaign/submarkets/LocalResourcesSubmarketPlugin";
  static final String REACH_ECONOMY = "com/fs/starfarer/campaign/econ/reach/ReachEconomy";
  static final String ECONOMY_ADVANCE_PLAN_PATCH_ID = "economyAdvancePlan";
  static final int ECONOMY_ADVANCE_FEATURE_PERSISTENT_SNAPSHOTS = 1;
  static final int ECONOMY_ADVANCE_FEATURE_LOCATION_CACHE = 2;
  static final int ECONOMY_ADVANCE_FEATURE_MARKET_SCHEDULER = 4;
  static final String MARKET_ADVANCE_PLAN_PATCH_ID = "marketAdvancePlan";
  static final int MARKET_ADVANCE_FEATURE_PERSISTENT_SNAPSHOTS = 1;
  static final int MARKET_ADVANCE_FEATURE_COMMODITY_TEMPORAL = 2;
  static final int MARKET_ADVANCE_FEATURE_ENTRY_OBSERVATION = 4;
  static final int MARKET_ADVANCE_FEATURE_SCHEDULER_SEMANTICS = 8;
  static final String SAVE_METHOD_PLAN_PATCH_ID = "saveMethodPlan";
  static final String STRATEGIC_JUMP_DESTINATION_PLAN_PATCH_ID = "strategicJumpDestinationPlan";
  static final int STRATEGIC_JUMP_FEATURE_DESTINATION_FIRST = 1;
  static final int STRATEGIC_JUMP_FEATURE_DESTINATION_INDEX = 2;
  static final String CAMPAIGN_LISTENER_PLAN_PATCH_ID = "campaignListenerPlan";
  static final int CAMPAIGN_LISTENER_FEATURE_ENTITY_INDEX_REPAIR = 1;
  static final int CAMPAIGN_LISTENER_FEATURE_THROTTLE = 2;
  static final String MAP_INTERACTION_PLAN_PATCH_ID = "mapInteractionPlan";
  static final int MAP_INTERACTION_FEATURE_INTEL_CALLBACK_CACHE = 1;
  static final int MAP_INTERACTION_FEATURE_HIT_TEST = 2;
  static final int SAVE_METHOD_FEATURE_CACHE_MAINTENANCE = 1;
  static final int SAVE_METHOD_FEATURE_MARKET_SCHEDULER_FLUSH = 2;
  private static final String SAVE_OUTPUT_BUFFER_DEDUP_PATCH_ID = "saveOutputBufferDedup";
  static final String MUTABLE_STAT = "com/fs/starfarer/api/combat/MutableStat";
  static final String MUTABLE_STAT_WITH_TEMP_MODS = "com/fs/starfarer/api/combat/MutableStatWithTempMods";
  static final String INTEL_MANAGER = "com/fs/starfarer/campaign/comms/v2/IntelManager";
  static final String SHIP = "com/fs/starfarer/combat/entities/Ship";
  static final String DYNAMIC_PARTICLE_GROUP = "com/fs/graphics/particle/DynamicParticleGroup";
  static final String LOADING_UTILS = "com/fs/starfarer/loading/LoadingUtils";
  static final String SCRIPT_STORE_RUNNER = "com/fs/starfarer/loading/scripts/ScriptStore$3";
  static final String RULES = "com/fs/starfarer/campaign/rules/Rules";
  static final String SPEC_STORE = "com/fs/starfarer/loading/SpecStore";
  static final String TEXTURE_LOADER = "com/fs/graphics/TextureLoader";
  static final String SOUND = "sound/Sound";
  static final String PROGRESS_INPUT =
      "com/fs/starfarer/util/oOoOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOO";
  static final String PROGRESS_OUTPUT = "com/fs/starfarer/util/do";
  static final String CAMPAIGN_GAME_MANAGER = "com/fs/starfarer/campaign/save/CampaignGameManager";
  static final String PLANET_SURVEY_PANEL = "com/fs/starfarer/campaign/ui/marketinfo/PlanetSurveyPanel";
  static final String LUDDIC_PATH_BASE_INTEL = "com/fs/starfarer/api/impl/campaign/intel/bases/LuddicPathBaseIntel";
  static final String PIRATE_BASE_INTEL = "com/fs/starfarer/api/impl/campaign/intel/bases/PirateBaseIntel";
  static final String DECIV_TRACKER = "com/fs/starfarer/api/impl/campaign/intel/deciv/DecivTracker";
  static final String PK_CMD = "com/fs/starfarer/api/impl/campaign/rulecmd/PK_CMD";
  static final Set<String> TARGET_CLASSES = Set.of(H, A, Z, EVENTS, CAMPAIGN_STATE, CAMPAIGN_ENGINE, COURSE_WIDGET,
      STRATEGIC_MODULE, JUMP_POINT, JUMP_DESTINATION, BASE_LOCATION, BASE_CAMPAIGN_ENTITY, MEMORY, CORE_SCRIPT, ECONOMY,
      MARKET, BASE_INDUSTRY, COMMODITY_MARKET_DATA, PUNITIVE_EXPEDITION_MANAGER, MILITARY_BASE, LIONS_GUARD_HQ,
      RECENT_UNREST, CONSTRUCTION_QUEUE, COMMODITY_ON_MARKET, LOCAL_RESOURCES_SUBMARKET, REACH_ECONOMY, MUTABLE_STAT,
      MUTABLE_STAT_WITH_TEMP_MODS, INTEL_MANAGER, SHIP, DYNAMIC_PARTICLE_GROUP, LOADING_UTILS, SCRIPT_STORE_RUNNER,
      RULES, SPEC_STORE, TEXTURE_LOADER, SOUND, PROGRESS_INPUT, PROGRESS_OUTPUT, CAMPAIGN_GAME_MANAGER,
      PLANET_SURVEY_PANEL, LUDDIC_PATH_BASE_INTEL, PIRATE_BASE_INTEL, DECIV_TRACKER, PK_CMD,
      HyperspacePatches.BASE_TILED, HyperspacePatches.HYPER_TERRAIN, HyperspacePatches.AUTOMATON,
      HyperspacePatches.STARFIELD);


  static final Set<String> OPTIONAL_TARGET_CLASSES = Set.of(NEX_PUNITIVE_EXPEDITION_MANAGER);

  private final PrepatcherConfig config;
  /**
   * Loader that owns the typed runtime hooks. A null value is used only by
   * the offline structural harness, which transforms byte arrays without
   * defining them in a JVM class loader.
   */
  private final ClassLoader runtimeLoader;
  private final AtomicInteger patchedClasses = new AtomicInteger();
  private final AtomicInteger appliedPatches = new AtomicInteger();
  private final AtomicInteger skippedPatches = new AtomicInteger();
  private final AtomicInteger compositionFailures = new AtomicInteger();
  private final AtomicInteger alreadyPatched = new AtomicInteger();

  public PrepatcherTransformer(PrepatcherConfig config) {
    this(config, null);
  }

  public PrepatcherTransformer(PrepatcherConfig config, ClassLoader runtimeLoader) {
    this.config = config;
    this.runtimeLoader = runtimeLoader;
  }

  @Override
  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain, byte[] classfileBuffer) {
    if ((!TARGET_CLASSES.contains(className) && !OPTIONAL_TARGET_CLASSES.contains(className))
        || !isTargetEnabled(className))
      return null;
    // FR child-loads com.fs.* while keeping javaagents in a sibling loader.
    // Never emit a typed hook call unless caller and hook are owned by the
    // exact same loader; a mismatch would otherwise become a late
    // loader-constraint LinkageError at the first invocation.
    if (runtimeLoader != null && className.startsWith("com/fs/") && loader != runtimeLoader) {
      String key = "starsector.prepatcher.patchStatus." + className.replace('/', '.') + ".classLoader";
      if (!"SKIPPED_LOADER".equals(System.getProperty(key))) {
        System.setProperty(key, "SKIPPED_LOADER");
        PrepatcherLog.warn("SKIPPED_LOADER " + className + ": target loader=" + loaderName(loader) + ", runtime loader="
            + loaderName(runtimeLoader));
      }
      return null;
    }

    PresentationStructuralContract.State presentationComposition;
    try {
      presentationComposition = PresentationStructuralContract.inspect(className, classfileBuffer, config);
      if (presentationComposition.present()) {
        recordStatus(className, "presentationStructuralComposition", "INPUT_VALIDATED");
      }
    } catch (Throwable failure) {
      recordPresentationCompositionSkip(className, failure);
      return null;
    }

    TransformState state = new TransformState(className, classfileBuffer, presentationComposition);
    switch (className) {
      case H -> {
        apply(state, "mapRenderStuff", config.mapRenderStuff, structured(new MapRenderReconciliation()));
        apply(state, "labelSpatialCandidates", config.labelSpatialCandidates, structured(new NearbyLabelIconLookup()));
        apply(state, "intelEntityIndex", config.intelEntityIndex, structured(new IntelIconEntityIndex()));
        apply(state, "systemNebulaCache", config.systemNebulaCache, structured(new SystemNebulaUpdateCache()));
        apply(state, "sampleCacheClearThrottle", config.sampleCacheClearThrottle,
            structured(new SampleCacheClearThrottle()));
        apply(state, "gridLineCap", config.gridLineCap, structured(new GridLineCap()));
      }
      case A -> apply(state, MAP_INTERACTION_PLAN_PATCH_ID, mapInteractionPlanEnabled(),
          structured(new MapInteractionPlan(requestedMapInteractionFeatureMask())));
      case Z -> apply(state, "intelArrowRendering", config.intelArrowRendering, structured(new IntelArrowRendering()));
      case EVENTS -> {
        apply(state, "intelCallbackCache", config.intelCallbackCache, structured(new IntelMapLocationCache(7)));
        apply(state, "intelReconciliation", config.intelReconciliation,
            structured(new IntelMissingIconReconciliation()));
      }
      case CAMPAIGN_STATE ->
        apply(state, "marketScheduler", config.marketScheduler, structured(new CampaignStateRenderBatchProtocol()));
      case CAMPAIGN_ENGINE -> {
        // The incremental core-worlds contract spans three independent classes.
        // This CampaignEngine surface owns authoritative system add/remove events.
        apply(state, CORE_WORLDS_PATCH_ID, config.coreWorldsExtentCache, structured(new CoreWorldsTopologyHooks()));
        apply(state, "campaignCacheLifecycle", campaignCacheLifecycleEnabled(),
            structured(new CampaignCacheLifecycle()));
        apply(state, "marketScheduler", config.marketScheduler, structured(new CampaignEngineMarketSchedulerTick()));
        apply(state, CAMPAIGN_LISTENER_PLAN_PATCH_ID, campaignListenerPlanEnabled(),
            structured(new CampaignListenerPlan(requestedCampaignListenerFeatureMask())));
      }
      case COURSE_WIDGET ->
        apply(state, "routeJumpPointIndex", config.routeJumpPointIndex, structured(new RouteJumpPointIndex()));
      case STRATEGIC_MODULE ->
        apply(state, STRATEGIC_JUMP_DESTINATION_PLAN_PATCH_ID, strategicJumpDestinationPlanEnabled(),
            structured(new StrategicJumpDestinationPlan(requestedStrategicJumpDestinationFeatureMask())));
      case JUMP_POINT -> apply(state, STRATEGIC_JUMP_DESTINATION_INDEX_PATCH_ID, config.strategicJumpDestinationIndex,
          structured(new StrategicJumpDestinationInvalidation()));
      case JUMP_DESTINATION -> apply(state, STRATEGIC_JUMP_DESTINATION_INDEX_PATCH_ID,
          config.strategicJumpDestinationIndex, structured(new StrategicJumpDestinationRetarget()));
      case BASE_LOCATION -> {
        // BaseLocation owns authoritative addTag/removeTag/clearTags events.
        apply(state, CORE_WORLDS_PATCH_ID, config.coreWorldsExtentCache, structured(new CoreWorldsTagHooks()));
        apply(state, "campaignSnapshotReuse", config.campaignSnapshotReuse, structured(new CampaignSnapshotReuse()));
        apply(state, STRATEGIC_JUMP_DESTINATION_INDEX_PATCH_ID, config.strategicJumpDestinationIndex,
            structured(new StrategicJumpLocationMutation()));
      }
      case BASE_CAMPAIGN_ENTITY -> {
        apply(state, "entityScriptSnapshotReuse", config.entityScriptSnapshotReuse,
            structured(new EntityScriptSnapshotReuse()));
        apply(state, "marketScheduler", config.marketScheduler, structured(new EntityMarketSchedulerSource()));
      }
      case MEMORY -> apply(state, "emptyMemoryAdvanceFastPath", config.emptyMemoryAdvanceFastPath,
          structured(new EmptyMemoryAdvanceFastPath()));
      case CORE_SCRIPT ->
        apply(state, CORE_WORLDS_PATCH_ID, config.coreWorldsExtentCache, structured(new CoreWorldsExtentCache()));
      case ECONOMY -> apply(state, ECONOMY_ADVANCE_PLAN_PATCH_ID, economyAdvancePlanEnabled(),
          structured(new EconomyAdvancePlan(requestedEconomyAdvanceFeatureMask(), config.economyStructureAuditMs)));
      case MARKET -> {
        apply(state, MARKET_ADVANCE_PLAN_PATCH_ID, marketAdvancePlanEnabled(),
            structured(new MarketAdvancePlan(requestedMarketAdvanceFeatureMask(), config.marketStructureAuditFrames,
                this::patchDirectMarketObservationEntry)));
        apply(state, "economyGroupIndexMarketMutation", config.economyGroupIndex,
            structured(new MarketEconGroupMutationBarrier()));
      }
      case LOCAL_RESOURCES_SUBMARKET -> {
        apply(state, "localResourcesNoColdMarketData", config.localResourcesNoColdMarketData,
            structured(new LocalResourcesColdMarketDataElision()));
        apply(state, "localResourcesTooltipSnapshot", config.localResourcesTooltipSnapshot,
            structured(new LocalResourcesTooltipSnapshot()));
      }
      case REACH_ECONOMY -> apply(state, "economyGroupIndexReachEconomy", config.economyGroupIndex,
          structured(new ReachEconomyGroupIndex()));
      case COMMODITY_MARKET_DATA -> {
        // Both patches touch the same class. The linear wrapper is installed first;
        // put-elision then consumes that current state and composition validation
        // rechecks the wrapper before the class is committed.
        apply(state, MARKET_SHARE_LINEAR_PATCH_ID, config.marketShareLinearAggregation,
            structured(new MarketShareLinearAggregation()));
        apply(state, MARKET_SHARE_DATA_PUT_ELISION_PATCH_ID, config.marketShareDataPutElision,
            structured(new MarketShareDataPutElision()));
      }
      case PUNITIVE_EXPEDITION_MANAGER -> apply(state, PUNITIVE_PLAYER_SHARE_PATCH_ID,
          config.punitivePlayerShareLocalCache, structured(new PunitivePlayerShareLocalCache()));
      case NEX_PUNITIVE_EXPEDITION_MANAGER -> apply(state, PUNITIVE_PLAYER_SHARE_PATCH_ID,
          config.nexPunitivePlayerShareLocalCache, structured(new PunitivePlayerShareLocalCache()));
      case BASE_INDUSTRY -> {
        apply(state, AOTD_DEFICIT_PATCH_ID, config.aotdCleanDeficitPath,
            structured(new BaseIndustryCleanDeficitPath()));
        apply(state, "marketNoOpCallbacks", config.marketNoOpCallbacks && config.marketNoOpIndustryAuditFrames > 0,
            structured(new BaseIndustryDormantFastPath(config.marketNoOpIndustryAuditFrames)));
        apply(state, "marketConstructionMutationBarriers", config.marketScheduler,
            structured(new BaseIndustryConstructionBarriers()));
      }
      case MILITARY_BASE -> apply(state, "marketStepReplayMilitaryBase", config.marketScheduler,
          structured(new MarketComponentStepReplay(1)));
      case LIONS_GUARD_HQ -> apply(state, "marketStepReplayLionsGuardHQ", config.marketScheduler,
          structured(new MarketComponentStepReplay(2)));
      case RECENT_UNREST -> apply(state, "marketStepReplayRecentUnrest", config.marketScheduler,
          structured(new MarketComponentStepReplay(3)));
      case CONSTRUCTION_QUEUE -> apply(state, "marketConstructionMutationBarriers", config.marketScheduler,
          structured(new ConstructionQueueMutationBarriers()));
      case COMMODITY_ON_MARKET -> apply(state, "commodityEventModDirtyCache", config.commodityEventModDirtyCache,
          structured(new CommodityEventModDirtyCache()));
      case MUTABLE_STAT -> apply(state, "commodityTemporalBinding", config.commodityTemporalFastPath,
          structured(new CommodityTemporalBaseBinding()));
      case MUTABLE_STAT_WITH_TEMP_MODS -> apply(state, "tempModExpiryScheduler",
          config.tempModExpiryScheduler || config.commodityTemporalFastPath, structured(new TempModExpiryScheduler()));
      case INTEL_MANAGER ->
        apply(state, "commRelaySystemIndex", config.commRelaySystemIndex, structured(new CommRelaySystemIndex()));
      case SHIP -> apply(state, "shipAdvanceScratch", config.shipAdvanceScratch, structured(new ShipAdvanceScratch()));
      case DYNAMIC_PARTICLE_GROUP ->
        apply(state, "particleCleanup", config.particleCleanup, structured(new DynamicParticleCleanup()));
      case LOADING_UTILS -> {
        apply(state, "loadingTextReader", config.loadingTextReader, structured(new LoadingTextReader()));
        apply(state, "startupLogAggregation", config.startupLogAggregation, structured(new LoadingUtilsStartupLogs()));
      }
      case SCRIPT_STORE_RUNNER ->
        apply(state, "startupLogAggregation", config.startupLogAggregation, structured(new StartupInfoBlockSuppression(
            "] already loaded (perhaps from jar file, or due to a reference from another class), skipping compilation.",
            1, 2, true, "alreadyLoadedScriptInfo")));
      case RULES -> {
        apply(state, "startupLogAggregation", config.startupLogAggregation,
            structured(new StartupInfoBlockSuppression("Loading rule: ", 1, 3, true, "perRuleInfo")));
        apply(state, "rulesLiteralParser", config.rulesLiteralParser, structured(new RulesLiteralParser()));
      }
      case SPEC_STORE ->
        apply(state, "startupLogAggregation", config.startupLogAggregation, structured(new SpecStoreStartupLogs()));
      case TEXTURE_LOADER -> apply(state, "startupLogAggregation", config.startupLogAggregation,
          structured(new StartupInfoBlockSuppression("Cleaned buffer for texture %s (using reflection)", 1, 5, true,
              "textureCleanupInfo")));
      case SOUND -> {
        // sound.Sound is parent-loaded by Faster Rendering and cannot resolve
        // the typed runtime installed in FR's child system loader. Removing
        // this pure INFO block inline preserves the optimization without a
        // cross-loader helper call. The structural marker remains the source
        // of ownership/idempotency truth; only the optional runtime counter is
        // intentionally absent for this one parent-owned target.
        apply(state, "startupLogAggregation", config.startupLogAggregation,
            structured(new StartupInfoBlockSuppression("Loading sound [", 1, 6, false, "soundLoadInfo")));
      }
      case PROGRESS_INPUT -> apply(state, "saveLoadProgressThrottle", config.saveLoadProgressThrottle,
          structured(new SaveLoadProgressThrottle("super")));
      case PROGRESS_OUTPUT -> apply(state, "saveLoadProgressThrottle", config.saveLoadProgressThrottle,
          structured(new SaveLoadProgressThrottle("o00000")));
      case CAMPAIGN_GAME_MANAGER -> {
        apply(state, SAVE_METHOD_PLAN_PATCH_ID, saveMethodPlanEnabled(),
            structured(new SaveMethodPlan(requestedSaveMethodFeatureMask(), this::patchCacheMaintenanceBeforeSave)));
        // The output-chain allocation rewrite is optional and deliberately
        // ordered after the correctness-critical pre-save barrier. A layout
        // mismatch may skip deduplication without disabling scheduler readiness.
        apply(state, SAVE_OUTPUT_BUFFER_DEDUP_PATCH_ID, config.saveOutputBufferDedup,
            structured(new SaveOutputBufferDedup()));
      }
      case PLANET_SURVEY_PANEL, LUDDIC_PATH_BASE_INTEL, PIRATE_BASE_INTEL, DECIV_TRACKER, PK_CMD ->
        apply(state, "marketScheduler", config.marketScheduler, structured(new CoreMarketEventSynchronization()));
      case HyperspacePatches.BASE_TILED -> {
        apply(state, "hyperspaceViewportBounds", config.hyperspaceViewportBounds,
            structured(new HyperspaceViewportBounds()));
        apply(state, "terrainRandomReuse", config.terrainRandomReuse, structured(new TerrainRandomReuse(3)));
      }
      case HyperspacePatches.HYPER_TERRAIN -> {
        apply(state, "skipNoOpTerrainLayer", config.skipNoOpTerrainLayer, structured(new NoOpTerrainLayerSkip()));
        apply(state, "terrainRandomReuse", config.terrainRandomReuse, structured(new TerrainRandomReuse(1)));
        apply(state, "automatonInternalReads", config.automatonBufferReuse, structured(new AutomatonInternalReads()));
      }
      case HyperspacePatches.AUTOMATON ->
        apply(state, "automatonBufferReuse", config.automatonBufferReuse, structured(new AutomatonBufferReuse()));
      case HyperspacePatches.STARFIELD -> apply(state, "starfieldCleanupBuffers", config.starfieldCleanupBuffers,
          structured(new StarfieldCleanupBuffers()));
      default -> {
        return null;
      }
    }

    if (!validateFinalComposition(state)) { return null; }
    finalizeAppliedPatches(state);

    if (!state.changed) {
      PrepatcherLog.info("Structural scan completed for " + className + "; no new compatible patch was applied.");
      return null;
    }

    int count = patchedClasses.incrementAndGet();
    System.setProperty("starsector.prepatcher.patchedClasses", Integer.toString(count));
    PrepatcherLog.info("Patched " + className + " structurally: " + String.join(", ", state.appliedDetails) + ", bytes "
        + classfileBuffer.length + " -> " + state.bytes.length);

    return state.bytes;
  }

  private void apply(TransformState state, String patchId, boolean enabled, PatchAction action) {
    if (!enabled) return;
    boolean markerPresent = false;
    boolean matchCompleted = false;
    PatchPostcondition postcondition = postconditionFor(action, patchId);
    try {
      ClassNode node = readClass(state.bytes);
      Set<String> publicApi = publicApi(node);
      markerPresent = hasPatchMarker(node, patchId);
      PatchReport report = action.apply(node);
      matchCompleted = true;
      if (report.total == 0) { throw mismatch("patch reported no changed sites"); }
      if (markerPresent) { throw mismatch("optimizer marker is present but the structural postcondition is not"); }
      addPatchMarker(node, patchId);
      if (!publicApi.equals(publicApi(node))) {
        throw mismatch("public/protected API surface changed before serialization");
      }

      byte[] candidate = writeClass(node);
      verifyClass(candidate);
      ClassNode reparsed = readClass(candidate);
      if (!publicApi.equals(publicApi(reparsed))) {
        throw mismatch("public/protected API surface changed after serialization");
      }
      postcondition.validate(candidate);
      validateEarlierPostconditions(state, candidate, patchId);

      state.bytes = candidate;
      state.changed = true;
      String detail = patchId + "{" + report + "}";
      state.appliedDetails.add(detail);
      state.applied.add(new AppliedPatch(patchId, postcondition, detail, true));
    } catch (AlreadyPatchedException ex) {
      if (!markerPresent) {
        recordStructuralSkip(state.className, patchId,
            "hook-shaped postcondition exists without this optimizer's structural marker");
      } else {
        state.applied.add(new AppliedPatch(patchId, postcondition, null, false));
        int count = alreadyPatched.incrementAndGet();
        System.setProperty("starsector.prepatcher.alreadyPatched", Integer.toString(count));
        recordStatus(state.className, patchId, "ALREADY_APPLIED");
        PrepatcherLog.info("ALREADY_APPLIED " + patchId + " in " + state.className + ": " + ex.getMessage());
      }
    } catch (CompositionMismatchException ex) {
      recordCompositionSkip(state, patchId, ex.getMessage());
    } catch (StructuralMismatchException ex) {
      if (!matchCompleted && wasApplicableBeforeEarlierPatches(state, patchId, action)) {
        recordCompositionSkip(state, patchId,
            "the patch matched the incoming class but no longer matches after earlier " + "patches: "
                + ex.getMessage());
      } else {
        recordStructuralSkip(state.className, patchId, ex.getMessage());
      }
    } catch (Throwable ex) {
      int count = skippedPatches.incrementAndGet();
      System.setProperty("starsector.prepatcher.skippedPatches", Integer.toString(count));
      recordStatus(state.className, patchId, "SKIPPED_ERROR");
      PrepatcherLog.error(
          "SKIPPED_ERROR " + patchId + " in " + state.className + "; the preceding class bytes remain active.", ex);
    }
  }

  private void recordStructuralSkip(String className, String patchId, String reason) {
    int count = skippedPatches.incrementAndGet();
    System.setProperty("starsector.prepatcher.skippedPatches", Integer.toString(count));
    recordStatus(className, patchId, "SKIPPED_STRUCTURAL");
    PrepatcherLog.warn("SKIPPED_STRUCTURAL " + patchId + " in " + className + ": " + reason);
  }

  private static PatchPostcondition postconditionFor(PatchAction action, String patchId) {
    return bytes -> verifyPatchPostcondition(action, bytes, patchId);
  }

  private static void verifyPatchPostcondition(PatchAction action, byte[] bytes, String patchId) {
    ClassNode validationNode = readClass(bytes);
    if (!hasPatchMarker(validationNode, patchId)) {
      throw mismatch(patchId + " structural marker is missing from the candidate class");
    }
    try {
      PatchReport repeated = action.apply(validationNode);
      throw mismatch(patchId + " remains patchable after serialization: " + repeated);
    } catch (AlreadyPatchedException expected) {
      // Validation runs on a separate ClassNode. Any accidental mutation by a matcher
      // cannot affect the candidate bytes or another patch's validation.
    }
  }

  private void validateEarlierPostconditions(TransformState state, byte[] candidate, String currentPatchId) {
    try {
      state.presentationComposition.validate(candidate, config);
    } catch (Throwable ex) {
      throw composition("applying " + currentPatchId
          + " invalidated the earlier fast-forward presentation transformation: " + failureMessage(ex));
    }
    for (AppliedPatch previous : state.applied) {
      try {
        previous.postcondition.validate(candidate);
      } catch (Throwable ex) {
        throw composition("applying " + currentPatchId + " invalidated earlier patch " + previous.patchId + ": "
            + failureMessage(ex));
      }
    }
  }

  private boolean validateFinalComposition(TransformState state) {
    try {
      state.presentationComposition.validate(state.bytes, config);
      for (AppliedPatch patch : state.applied) {
        patch.postcondition.validate(state.bytes);
      }
      if (!state.applied.isEmpty() && !state.compositionFailed) {
        recordStatus(state.className, "composition", "PASSED");
      }
      if (state.presentationComposition.present() && !state.compositionFailed) {
        recordStatus(state.className, "presentationStructuralComposition", "PASSED");
      }
      return true;
    } catch (Throwable ex) {
      recordStatus(state.className, "composition", "FAILED");
      for (AppliedPatch patch : state.applied) {
        if (patch.newlyApplied) {
          recordStatus(state.className, patch.patchId, "ROLLED_BACK_COMPOSITION");
        }
      }
      int count = compositionFailures.incrementAndGet();
      System.setProperty("starsector.prepatcher.compositionFailures", Integer.toString(count));
      PrepatcherLog.error("COMPOSITION_FAILED final validation for " + state.className
          + "; all structural changes made by this transformer invocation are rolled "
          + "back to the incoming class bytes.", ex);
      return false;
    }
  }

  private void finalizeAppliedPatches(TransformState state) {
    for (AppliedPatch patch : state.applied) {
      if (!patch.newlyApplied) continue;
      int count = appliedPatches.incrementAndGet();
      System.setProperty("starsector.prepatcher.appliedPatches", Integer.toString(count));
      recordStatus(state.className, patch.patchId, "APPLIED");
      PrepatcherLog.info("APPLIED " + patch.patchId + " to " + state.className + ": " + patch.detail);
    }
  }

  private static boolean wasApplicableBeforeEarlierPatches(TransformState state, String patchId, PatchAction action) {
    if (!state.changed || state.applied.isEmpty()) return false;
    try {
      ClassNode baseline = readClass(state.incomingBytes);
      if (hasPatchMarker(baseline, patchId)) return false;
      PatchReport report = action.apply(baseline);
      return report.total > 0;
    } catch (AlreadyPatchedException | StructuralMismatchException ex) {
      return false;
    } catch (Throwable ex) {
      return false;
    }
  }

  private void recordCompositionSkip(TransformState state, String patchId, String reason) {
    state.compositionFailed = true;
    int skipped = skippedPatches.incrementAndGet();
    System.setProperty("starsector.prepatcher.skippedPatches", Integer.toString(skipped));
    int failures = compositionFailures.incrementAndGet();
    System.setProperty("starsector.prepatcher.compositionFailures", Integer.toString(failures));
    recordStatus(state.className, patchId, "SKIPPED_COMPOSITION");
    recordStatus(state.className, "composition", "FAILED");
    PrepatcherLog.warn("SKIPPED_COMPOSITION " + patchId + " in " + state.className + ": " + reason
        + "; the preceding class bytes remain active.");
  }

  private static String failureMessage(Throwable ex) {
    String message = ex.getMessage();
    return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
  }

  private void recordPresentationCompositionSkip(String className, Throwable failure) {
    int skipped = skippedPatches.incrementAndGet();
    System.setProperty("starsector.prepatcher.skippedPatches", Integer.toString(skipped));
    int failures = compositionFailures.incrementAndGet();
    System.setProperty("starsector.prepatcher.compositionFailures", Integer.toString(failures));
    recordStatus(className, "presentationStructuralComposition", "SKIPPED_COMPOSITION");
    recordStatus(className, "composition", "FAILED");
    PrepatcherLog.warn("SKIPPED_COMPOSITION presentation/structural contract in " + className + ": "
        + failureMessage(failure) + "; incoming class bytes remain active.");
  }

  private static void recordStatus(String className, String patchId, String status) {
    System.setProperty("starsector.prepatcher.patchStatus." + className.replace('/', '.') + "." + patchId, status);
  }

  boolean isTargetEnabled(String className) {
    return switch (className) {
      case H -> config.mapRenderStuff || config.labelSpatialCandidates || config.intelEntityIndex
          || config.systemNebulaCache || config.sampleCacheClearThrottle || config.gridLineCap;
      case A -> mapInteractionPlanEnabled();
      case Z -> config.intelArrowRendering;
      case EVENTS -> config.intelCallbackCache || config.intelReconciliation;
      case CAMPAIGN_STATE -> config.marketScheduler;
      case CAMPAIGN_ENGINE ->
        config.coreWorldsExtentCache || campaignCacheLifecycleEnabled() || campaignListenerPlanEnabled();
      case COURSE_WIDGET -> config.routeJumpPointIndex;
      case STRATEGIC_MODULE -> strategicJumpDestinationPlanEnabled();
      case JUMP_POINT, JUMP_DESTINATION -> config.strategicJumpDestinationIndex;
      case BASE_LOCATION ->
        config.coreWorldsExtentCache || config.campaignSnapshotReuse || config.strategicJumpDestinationIndex;
      case BASE_CAMPAIGN_ENTITY -> config.entityScriptSnapshotReuse || config.marketScheduler;
      case MEMORY -> config.emptyMemoryAdvanceFastPath;
      case CORE_SCRIPT -> config.coreWorldsExtentCache;
      case ECONOMY -> config.economyLocationCache || config.economyPersistentSnapshots || config.marketScheduler;
      case MARKET -> config.economyPersistentSnapshots || config.commodityTemporalFastPath
          || config.directMarketObservation || config.marketScheduler || config.economyGroupIndex;
      case LOCAL_RESOURCES_SUBMARKET -> config.localResourcesNoColdMarketData || config.localResourcesTooltipSnapshot;
      case REACH_ECONOMY -> config.economyGroupIndex;
      case COMMODITY_MARKET_DATA -> config.marketShareLinearAggregation || config.marketShareDataPutElision;
      case PUNITIVE_EXPEDITION_MANAGER -> config.punitivePlayerShareLocalCache;
      case NEX_PUNITIVE_EXPEDITION_MANAGER -> config.nexPunitivePlayerShareLocalCache;
      case BASE_INDUSTRY -> config.aotdCleanDeficitPath
          || (config.marketNoOpCallbacks && config.marketNoOpIndustryAuditFrames > 0) || config.marketScheduler;
      case MILITARY_BASE, LIONS_GUARD_HQ, RECENT_UNREST, CONSTRUCTION_QUEUE -> config.marketScheduler;
      case COMMODITY_ON_MARKET -> config.commodityEventModDirtyCache;
      case MUTABLE_STAT -> config.commodityTemporalFastPath;
      case MUTABLE_STAT_WITH_TEMP_MODS -> config.tempModExpiryScheduler || config.commodityTemporalFastPath;
      case INTEL_MANAGER -> config.commRelaySystemIndex;
      case SHIP -> config.shipAdvanceScratch;
      case DYNAMIC_PARTICLE_GROUP -> config.particleCleanup;
      case LOADING_UTILS -> config.loadingTextReader || config.startupLogAggregation;
      case SCRIPT_STORE_RUNNER, SPEC_STORE, TEXTURE_LOADER, SOUND -> config.startupLogAggregation;
      case RULES -> config.startupLogAggregation || config.rulesLiteralParser;
      case PROGRESS_INPUT, PROGRESS_OUTPUT -> config.saveLoadProgressThrottle;
      case CAMPAIGN_GAME_MANAGER -> saveMethodPlanEnabled() || config.saveOutputBufferDedup;
      case PLANET_SURVEY_PANEL, LUDDIC_PATH_BASE_INTEL, PIRATE_BASE_INTEL, DECIV_TRACKER, PK_CMD ->
        config.marketScheduler;
      case HyperspacePatches.BASE_TILED -> config.hyperspaceViewportBounds || config.terrainRandomReuse;
      case HyperspacePatches.HYPER_TERRAIN ->
        config.skipNoOpTerrainLayer || config.terrainRandomReuse || config.automatonBufferReuse;
      case HyperspacePatches.AUTOMATON -> config.automatonBufferReuse;
      case HyperspacePatches.STARFIELD -> config.starfieldCleanupBuffers;
      default -> false;
    };
  }

  private boolean economyAdvancePlanEnabled() {
    return config.economyPersistentSnapshots || config.economyLocationCache || config.marketScheduler;
  }

  private int requestedEconomyAdvanceFeatureMask() {
    int mask = 0;
    if (config.economyPersistentSnapshots) {
      mask |= ECONOMY_ADVANCE_FEATURE_PERSISTENT_SNAPSHOTS;
    }
    if (config.economyLocationCache) {
      mask |= ECONOMY_ADVANCE_FEATURE_LOCATION_CACHE;
    }
    if (config.marketScheduler) {
      mask |= ECONOMY_ADVANCE_FEATURE_MARKET_SCHEDULER;
    }
    return mask;
  }

  private boolean marketAdvancePlanEnabled() {
    return config.economyPersistentSnapshots || config.commodityTemporalFastPath || config.directMarketObservation
        || config.marketScheduler;
  }

  private boolean saveMethodPlanEnabled() {
    return config.marketScheduler || campaignCacheLifecycleEnabled();
  }

  private int requestedSaveMethodFeatureMask() {
    int mask = 0;
    if (campaignCacheLifecycleEnabled()) mask |= SAVE_METHOD_FEATURE_CACHE_MAINTENANCE;
    if (config.marketScheduler) mask |= SAVE_METHOD_FEATURE_MARKET_SCHEDULER_FLUSH;
    return mask;
  }

  private int requestedMarketAdvanceFeatureMask() {
    int mask = 0;
    if (config.economyPersistentSnapshots) {
      mask |= MARKET_ADVANCE_FEATURE_PERSISTENT_SNAPSHOTS;
    }
    if (config.commodityTemporalFastPath) {
      mask |= MARKET_ADVANCE_FEATURE_COMMODITY_TEMPORAL;
    }
    if (config.directMarketObservation) {
      mask |= MARKET_ADVANCE_FEATURE_ENTRY_OBSERVATION;
    }
    if (config.marketScheduler) {
      mask |= MARKET_ADVANCE_FEATURE_SCHEDULER_SEMANTICS;
    }
    return mask;
  }

  private boolean strategicJumpDestinationPlanEnabled() {
    return config.strategicJumpDestinationFirst || config.strategicJumpDestinationIndex;
  }

  private int requestedStrategicJumpDestinationFeatureMask() {
    int mask = 0;
    if (config.strategicJumpDestinationFirst) {
      mask |= STRATEGIC_JUMP_FEATURE_DESTINATION_FIRST;
    }
    if (config.strategicJumpDestinationIndex) {
      mask |= STRATEGIC_JUMP_FEATURE_DESTINATION_INDEX;
    }
    return mask;
  }

  private boolean campaignListenerPlanEnabled() {
    return config.entityLookupIndexRepair || config.campaignListenerThrottle;
  }

  private int requestedCampaignListenerFeatureMask() {
    int mask = 0;
    if (config.entityLookupIndexRepair) {
      mask |= CAMPAIGN_LISTENER_FEATURE_ENTITY_INDEX_REPAIR;
    }
    if (config.campaignListenerThrottle) {
      mask |= CAMPAIGN_LISTENER_FEATURE_THROTTLE;
    }
    return mask;
  }

  private boolean mapInteractionPlanEnabled() {
    return config.intelCallbackCache || config.mapHitTest;
  }

  private int requestedMapInteractionFeatureMask() {
    int mask = 0;
    if (config.intelCallbackCache) {
      mask |= MAP_INTERACTION_FEATURE_INTEL_CALLBACK_CACHE;
    }
    if (config.mapHitTest) {
      mask |= MAP_INTERACTION_FEATURE_HIT_TEST;
    }
    return mask;
  }

  private boolean campaignCacheLifecycleEnabled() {
    return config.labelSpatialCandidates || config.intelCallbackCache || config.intelArrowRendering
        || config.intelEntityIndex || config.mapHitTest || config.systemNebulaCache || config.sampleCacheClearThrottle
        || config.campaignListenerThrottle || config.routeJumpPointIndex || config.strategicJumpDestinationIndex
        || config.economyLocationCache || config.marketScheduler || config.economyGroupIndex
        || config.commRelaySystemIndex || config.commodityTemporalFastPath || config.scratchTrimEnabled
        || config.starfieldCleanupBuffers;
  }


  // ---------------------------------------------------------------------
  // Startup loading and save/load
  // ---------------------------------------------------------------------

  private static final class LoadingTextReader implements StructuredPatch<LoadingTextReader.Plan> {
    private record Plan(MethodNode method) {
    }

    @Override
    public Match<Plan> match(ClassNode node) {
      MethodNode method = requireMethod(node, "super", "(Ljava/io/InputStream;)Ljava/lang/String;");
      int hooks = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "readUtf8Normalized",
          "(Ljava/io/InputStream;)Ljava/lang/String;");
      int buffers = 0;
      int oneMiB = 0;
      for (AbstractInsnNode insn : method.instructions.toArray()) {
        if (insn instanceof IntInsnNode array && array.getOpcode() == Opcodes.NEWARRAY
            && array.operand == Opcodes.T_BYTE)
          buffers++;
        if (insn instanceof LdcInsnNode ldc && Integer.valueOf(1_048_576).equals(ldc.cst)) oneMiB++;
      }
      int stringBuffers = countCalls(method, Opcodes.INVOKESPECIAL, "java/lang/StringBuffer", "<init>", "()V");
      int replaceAll = countCalls(method, Opcodes.INVOKEVIRTUAL, "java/lang/String", "replaceAll",
          "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
      if (hooks == 1 && buffers == 0 && oneMiB == 0 && stringBuffers == 0 && replaceAll == 0) {
        return Match.alreadyApplied("streaming UTF-8 text reader postcondition matches");
      }
      requireCount("text reader hook", hooks, 0);
      requireCount("text reader byte-array allocation", buffers, 1);
      requireCount("text reader 1 MiB constant", oneMiB, 1);
      requireCount("text reader StringBuffer", stringBuffers, 1);
      requireCount("text reader CR regex", replaceAll, 1);
      requireOriginalLoadingTextReaderShape(method);
      return Match.plan(new Plan(method));
    }

    @Override
    public PatchReport mutate(Plan plan) {
      MethodNode method = plan.method();
      method.instructions.clear();
      method.tryCatchBlocks.clear();
      if (method.localVariables != null) method.localVariables.clear();
      if (method.visibleLocalVariableAnnotations != null) method.visibleLocalVariableAnnotations.clear();
      if (method.invisibleLocalVariableAnnotations != null) method.invisibleLocalVariableAnnotations.clear();
      method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
      method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "readUtf8Normalized",
          "(Ljava/io/InputStream;)Ljava/lang/String;", false));
      method.instructions.add(new InsnNode(Opcodes.ARETURN));
      method.maxLocals = 1;
      method.maxStack = 1;

      PatchReport report = new PatchReport();
      report.add("streamingUtf8Reader", 1);
      return report;
    }

    @Override
    public void verify(Plan plan, ClassNode node) {
      requireCount("text reader hook", countCalls(plan.method(), Opcodes.INVOKESTATIC, HOOKS, "readUtf8Normalized",
          "(Ljava/io/InputStream;)Ljava/lang/String;"), 1);
    }
  }

  private static final class LoadingUtilsStartupLogs implements StructuredPatch<LoadingUtilsStartupLogs.Plan> {
    private record Plan(List<LogBlock> jsonBlocks, List<LogBlock> csvBlocks) {
    }

    @Override
    public Match<Plan> match(ClassNode node) {
      int jsonSites = countStringConstants(node, "Loading JSON from [");
      int csvSites = countStringConstants(node, "Loading CSV data from [");
      int jsonHooks = countCategoryHooks(node, "startupLogSuppressed", "(I)V", 0);
      int csvHooks = countCategoryHooks(node, "startupLogSuppressed", "(I)V", 1);
      if (jsonSites == 0 && csvSites == 0 && jsonHooks == 2 && csvHooks == 3) {
        return Match.alreadyApplied("LoadingUtils startup INFO suppression postcondition matches");
      }
      requireCount("LoadingUtils JSON INFO literals", jsonSites, 2);
      requireCount("LoadingUtils CSV INFO literals", csvSites, 3);
      requireCount("LoadingUtils JSON suppression hooks", jsonHooks, 0);
      requireCount("LoadingUtils CSV suppression hooks", csvHooks, 0);
      List<LogBlock> json = startupInfoBlockPlans(node, "Loading JSON from [");
      List<LogBlock> csv = startupInfoBlockPlans(node, "Loading CSV data from [");
      requireCount("startup INFO blocks for Loading JSON from [", json.size(), 2);
      requireCount("startup INFO blocks for Loading CSV data from [", csv.size(), 3);
      return Match.plan(new Plan(json, csv));
    }

    @Override
    public PatchReport mutate(Plan plan) {
      suppressStartupInfoBlocks(plan.jsonBlocks(), 0, true);
      suppressStartupInfoBlocks(plan.csvBlocks(), 1, true);
      PatchReport report = new PatchReport();
      report.add("jsonInfoBlocks", plan.jsonBlocks().size());
      report.add("csvInfoBlocks", plan.csvBlocks().size());
      return report;
    }

    @Override
    public void verify(Plan plan, ClassNode node) {
      requireCount("LoadingUtils JSON INFO literals", countStringConstants(node, "Loading JSON from ["), 0);
      requireCount("LoadingUtils CSV INFO literals", countStringConstants(node, "Loading CSV data from ["), 0);
      requireCount("LoadingUtils JSON suppression hooks", countCategoryHooks(node, "startupLogSuppressed", "(I)V", 0),
          2);
      requireCount("LoadingUtils CSV suppression hooks", countCategoryHooks(node, "startupLogSuppressed", "(I)V", 1),
          3);
    }
  }

  private static final class StartupInfoBlockSuppression implements StructuredPatch<StartupInfoBlockSuppression.Plan> {
    private final String literal;
    private final int expected;
    private final int category;
    private final boolean emitRuntimeCounter;
    private final String reportLabel;

    StartupInfoBlockSuppression(String literal, int expected, int category, boolean emitRuntimeCounter,
        String reportLabel) {
      this.literal = literal;
      this.expected = expected;
      this.category = category;
      this.emitRuntimeCounter = emitRuntimeCounter;
      this.reportLabel = reportLabel;
    }

    private record Plan(List<LogBlock> blocks) {
    }

    @Override
    public Match<Plan> match(ClassNode node) {
      List<LogBlock> blocks = startupInfoBlockPlans(node, literal);
      int hooks = countCategoryHooks(node, "startupLogSuppressed", "(I)V", category);
      int expectedHooks = emitRuntimeCounter ? expected : 0;
      if (blocks.isEmpty() && hooks == expectedHooks) {
        return Match.alreadyApplied("startup INFO suppression postcondition matches for " + literal);
      }
      requireCount("startup INFO blocks for " + literal, blocks.size(), expected);
      requireCount("startup suppression hooks for category " + category, hooks, 0);
      return Match.plan(new Plan(blocks));
    }

    @Override
    public PatchReport mutate(Plan plan) {
      suppressStartupInfoBlocks(plan.blocks(), category, emitRuntimeCounter);
      PatchReport report = new PatchReport();
      report.add(reportLabel, plan.blocks().size());
      return report;
    }

    @Override
    public void verify(Plan plan, ClassNode node) {
      int expectedHooks = emitRuntimeCounter ? expected : 0;
      requireCount("startup INFO blocks for " + literal, countStringConstants(node, literal), 0);
      requireCount("startup suppression hooks for category " + category,
          countCategoryHooks(node, "startupLogSuppressed", "(I)V", category), expectedHooks);
    }
  }

  private static final class SpecStoreStartupLogs implements StructuredPatch<SpecStoreStartupLogs.Plan> {
    private record Site(MethodNode method, MethodInsnNode call) {
    }

    private record Plan(List<Site> sites) {
    }

    @Override
    public Match<Plan> match(ClassNode node) {
      List<Site> sites = new ArrayList<>();
      for (MethodNode method : node.methods) {
        for (MethodInsnNode call : calls(method, Opcodes.INVOKEVIRTUAL, "org/apache/log4j/Logger", "info",
            "(Ljava/lang/Object;)V")) {
          sites.add(new Site(method, call));
        }
      }
      int hooks = countCategoryHooks(node, "suppressStartupInfo", "(Ljava/lang/Object;Ljava/lang/Object;I)V", 4);
      if (sites.isEmpty() && hooks == 53) {
        return Match.alreadyApplied("SpecStore INFO suppression postcondition matches");
      }
      requireCount("SpecStore Logger.info sites", sites.size(), 53);
      requireCount("SpecStore suppression hooks", hooks, 0);
      return Match.plan(new Plan(sites));
    }

    @Override
    public PatchReport mutate(Plan plan) {
      for (Site site : plan.sites()) {
        // The logger receiver and already-built message remain on the stack.
        // Add only the category argument immediately before the replacement call.
        site.method().instructions.insertBefore(site.call(), new LdcInsnNode(4));
        site.call().owner = HOOKS;
        site.call().name = "suppressStartupInfo";
        site.call().desc = "(Ljava/lang/Object;Ljava/lang/Object;I)V";
        site.call().setOpcode(Opcodes.INVOKESTATIC);
        site.call().itf = false;
      }
      PatchReport report = new PatchReport();
      report.add("specInfoCalls", plan.sites().size());
      return report;
    }

    @Override
    public void verify(Plan plan, ClassNode node) {
      requireCount("SpecStore suppression hooks",
          countCategoryHooks(node, "suppressStartupInfo", "(Ljava/lang/Object;Ljava/lang/Object;I)V", 4), 53);
    }
  }


  private static final class SaveLoadProgressThrottle implements StructuredPatch<SaveLoadProgressThrottle.Plan> {
    private final String methodName;

    SaveLoadProgressThrottle(String methodName) {
      this.methodName = methodName;
    }

    private record Plan(MethodNode method, LdcInsnNode constant) {
    }

    @Override
    public Match<Plan> match(ClassNode node) {
      MethodNode method = requireMethod(node, methodName, "(Z)V");
      List<LdcInsnNode> originals = new ArrayList<>();
      for (AbstractInsnNode insn : method.instructions.toArray()) {
        if (insn instanceof LdcInsnNode ldc && isFloat(ldc, 1f / 60f)) originals.add(ldc);
      }
      int hooks = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "saveLoadProgressMinIntervalSeconds", "()F");
      if (originals.isEmpty() && hooks == 1) {
        return Match.alreadyApplied("save/load progress throttle postcondition matches");
      }
      requireCount("save/load progress 60 Hz constants", originals.size(), 1);
      requireCount("save/load progress hooks", hooks, 0);
      return Match.plan(new Plan(method, originals.get(0)));
    }

    @Override
    public PatchReport mutate(Plan plan) {
      plan.method().instructions.set(plan.constant(),
          new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "saveLoadProgressMinIntervalSeconds", "()F", false));
      PatchReport report = new PatchReport();
      report.add("progressInterval", 1);
      return report;
    }

    @Override
    public void verify(Plan plan, ClassNode node) {
      requireCount("save/load progress hooks",
          countCalls(plan.method(), Opcodes.INVOKESTATIC, HOOKS, "saveLoadProgressMinIntervalSeconds", "()F"), 1);
    }
  }


  private PatchReport patchCacheMaintenanceBeforeSave(ClassNode node) {
    MethodNode method =
        requireMethod(node, "o00000", "(Lcom/fs/starfarer/campaign/CampaignEngine$o;JZ)Ljava/lang/String;");
    int hooks = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "runCacheMaintenance", "(Z)V");
    if (hooks == 1) {
      AbstractInsnNode first = firstMeaningful(method);
      if (first == null || first.getOpcode() != Opcodes.ICONST_1) {
        throw mismatch("forced cache maintenance true argument is not first");
      }
      MethodInsnNode call = asMethod(nextMeaningful(first), "forced cache maintenance hook");
      requireCall(call, Opcodes.INVOKESTATIC, HOOKS, "runCacheMaintenance", "(Z)V", "forced cache maintenance hook");
      throw already("cache maintenance pre-save barrier postcondition matches");
    }
    requireCount("forced cache maintenance hooks", hooks, 0);
    InsnList prefix = new InsnList();
    prefix.add(new InsnNode(Opcodes.ICONST_1));
    prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "runCacheMaintenance", "(Z)V", false));
    method.instructions.insert(prefix);
    PatchReport report = new PatchReport();
    report.add("idle cache and scratch maintenance before save", 1);
    return report;
  }


  private static List<LogBlock> startupInfoBlockPlans(ClassNode node, String literal) {
    List<LogBlock> plans = new ArrayList<>();
    for (MethodNode method : node.methods) {
      for (AbstractInsnNode insn : method.instructions.toArray()) {
        if (!isStringLdc(insn, literal)) continue;
        MethodInsnNode info = null;
        int meaningful = 0;
        for (AbstractInsnNode cursor = insn; cursor != null && meaningful <= 40; cursor = cursor.getNext()) {
          if (cursor.getOpcode() >= 0) meaningful++;
          if (cursor instanceof MethodInsnNode call
              && callMatches(call, Opcodes.INVOKEVIRTUAL, "org/apache/log4j/Logger", "info", "(Ljava/lang/Object;)V")) {
            info = call;
            break;
          }
        }
        if (info == null) throw mismatch("no Logger.info after startup literal " + literal);
        FieldInsnNode logger = null;
        meaningful = 0;
        for (AbstractInsnNode cursor = insn; cursor != null && meaningful <= 40; cursor = cursor.getPrevious()) {
          if (cursor.getOpcode() >= 0) meaningful++;
          if (cursor instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
              && field.desc.equals("Lorg/apache/log4j/Logger;")) {
            logger = field;
            break;
          }
        }
        if (logger == null) throw mismatch("no logger field before startup literal " + literal);
        requireNoControlBoundary(logger, info, "startup INFO block " + literal);
        requirePureStartupInfoBlock(logger, info, literal);
        plans.add(new LogBlock(method, logger, info));
      }
    }
    return plans;
  }

  private static void suppressStartupInfoBlocks(List<LogBlock> plans, int category, boolean emitRuntimeCounter) {
    for (LogBlock plan : plans) {
      if (emitRuntimeCounter) {
        InsnList replacement = new InsnList();
        replacement.add(new LdcInsnNode(category));
        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "startupLogSuppressed", "(I)V", false));
        plan.method.instructions.insertBefore(plan.start, replacement);
      }
      removeRange(plan.method, plan.start, plan.end);
    }
  }

  private static String loaderName(ClassLoader loader) {
    return loader == null ? "<bootstrap>"
        : loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
  }

  private static void requireOriginalLoadingTextReaderShape(MethodNode method) {
    requireCount("text reader String(byte[],int,int,String) constructor",
        countCalls(method, Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([BIILjava/lang/String;)V"), 1);
    requireCount("text reader StringBuffer append", countCalls(method, Opcodes.INVOKEVIRTUAL, "java/lang/StringBuffer",
        "append", "(Ljava/lang/String;)Ljava/lang/StringBuffer;"), 1);
    requireCount("text reader InputStream.read",
        countCalls(method, Opcodes.INVOKEVIRTUAL, "java/io/InputStream", "read", "([B)I"), 1);
    requireCount("text reader InputStream.close",
        countCalls(method, Opcodes.INVOKEVIRTUAL, "java/io/InputStream", "close", "()V"), 3);
    requireCount("text reader StringBuffer.toString",
        countCalls(method, Opcodes.INVOKEVIRTUAL, "java/lang/StringBuffer", "toString", "()Ljava/lang/String;"), 1);
    requireCount("text reader total method calls", countMethodInstructions(method), 9);
    requireCount("text reader field accesses", countFieldInstructions(method), 0);
    requireCount("text reader invokedynamic sites", countOpcode(method, Opcodes.INVOKEDYNAMIC), 0);
    requireCount("text reader try/catch regions", method.tryCatchBlocks.size(), 2);
    requireCount("text reader UTF-8 literals", countStringConstants(method, "UTF-8"), 1);
    requireCount("text reader CR literals", countStringConstants(method, "\\r"), 1);
    requireCount("text reader empty literals", countStringConstants(method, ""), 1);

    int newString = 0;
    int newStringBuffer = 0;
    int otherNew = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!(insn instanceof TypeInsnNode type) || type.getOpcode() != Opcodes.NEW) continue;
      if (type.desc.equals("java/lang/String")) newString++;
      else if (type.desc.equals("java/lang/StringBuffer")) newStringBuffer++;
      else otherNew++;
    }
    requireCount("text reader new String", newString, 1);
    requireCount("text reader new StringBuffer", newStringBuffer, 1);
    requireCount("text reader other object allocations", otherNew, 0);
  }

  /**
   * Whole-block INFO suppression is permitted only for compiler-generated message
   * construction. This makes the patch fail open if another javaagent inserts a
   * getter, callback, probe, or other potentially observable call into the block.
   */
  private static void requirePureStartupInfoBlock(AbstractInsnNode start, AbstractInsnNode end, String literal) {
    int infoCalls = 0;
    for (AbstractInsnNode cursor = start; cursor != null; cursor = cursor.getNext()) {
      if (cursor instanceof MethodInsnNode call) {
        boolean allowed;
        if (callMatches(call, Opcodes.INVOKEVIRTUAL, "org/apache/log4j/Logger", "info", "(Ljava/lang/Object;)V")) {
          infoCalls++;
          allowed = true;
        } else if (call.owner.equals("java/lang/StringBuilder")) {
          allowed = (call.getOpcode() == Opcodes.INVOKESPECIAL && call.name.equals("<init>"))
              || (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                  && (call.name.equals("append") || call.name.equals("toString")));
        } else if (callMatches(call, Opcodes.INVOKESTATIC, "java/lang/String", "format",
            "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;")) {
              allowed = true;
            } else
          if (callMatches(call, Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
              "(Ljava/lang/Object;)Ljava/lang/String;")) {
                allowed = true;
              } else {
                allowed = false;
              }
        if (!allowed) {
          throw mismatch("startup INFO block " + literal + " contains an observable call " + call.owner + "."
              + call.name + call.desc);
        }
      } else if (cursor instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD) {
        throw mismatch("startup INFO block " + literal + " writes field " + field.owner + "." + field.name);
      } else if (cursor instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC) {
        throw mismatch("startup INFO block " + literal + " writes static field " + field.owner + "." + field.name);
      } else if (cursor.getOpcode() == Opcodes.INVOKEDYNAMIC || cursor.getOpcode() == Opcodes.MONITORENTER
          || cursor.getOpcode() == Opcodes.MONITOREXIT) {
            throw mismatch("startup INFO block " + literal + " contains unsupported opcode " + cursor.getOpcode());
          }
      if (cursor == end) break;
    }
    requireCount("Logger.info calls in startup block " + literal, infoCalls, 1);
  }


  // ---------------------------------------------------------------------
  // H: core map renderer
  // ---------------------------------------------------------------------

  /**
   * Replaces the fixed 2000f global-grid constants with a scale-aware hook, so that a large
   * sector no longer draws its grid at the density chosen for a small one.
   */
  private static final class GridLineCap implements StructuredPatch<GridLineCap.Plan> {
    /** The five proven constants, in the method that owns them. */
    private record Plan(MethodNode method, List<LdcInsnNode> constants) {
    }

    @Override
    public Match<Plan> match(ClassNode node) {
      MethodNode method = requireMethod(node, "null", "(F)V");
      int hooks = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "gridSpacing", "()F");
      List<LdcInsnNode> bounds = new ArrayList<>();
      List<LdcInsnNode> step = new ArrayList<>();
      for (AbstractInsnNode insn : method.instructions.toArray()) {
        if (!(insn instanceof LdcInsnNode ldc) || !isFloat(ldc, 2000f)) continue;
        AbstractInsnNode n1 = nextMeaningful(ldc);
        AbstractInsnNode n2 = nextMeaningful(n1);
        AbstractInsnNode n3 = nextMeaningful(n2);
        AbstractInsnNode n4 = nextMeaningful(n3);
        AbstractInsnNode prev = previousMeaningful(ldc);
        if (prev != null && prev.getOpcode() == Opcodes.FLOAD && n1 != null && n1.getOpcode() == Opcodes.FDIV
            && n2 != null && n2.getOpcode() == Opcodes.F2I && n3 != null && n3.getOpcode() == Opcodes.ISTORE) {
          bounds.add(ldc);
        } else if (n1 instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 0
            && n2 instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
            && field.owner.equals(node.name) && field.desc.equals("F") && n3 != null && n3.getOpcode() == Opcodes.FMUL
            && n4 != null && n4.getOpcode() == Opcodes.FSTORE) {
              step.add(ldc);
            }
      }
      if (bounds.isEmpty() && step.isEmpty() && hooks == 5) {
        return Match.alreadyApplied("five structurally verified gridSpacing hooks are already present");
      }
      if (hooks != 0) { return Match.incompatible("mixed/foreign gridSpacing hook state: " + hooks); }
      requireCount("global-grid bound constants", bounds.size(), 4);
      requireCount("global-grid step constant", step.size(), 1);
      List<MethodInsnNode> starscape = calls(method, Opcodes.INVOKEVIRTUAL, node.name, "isStarscapeMode", "()Z");
      requireCount("grid starscape guard", starscape.size(), 1);
      int guardIndex = method.instructions.indexOf(starscape.get(0));
      List<LdcInsnNode> constants = concat(bounds, step);
      for (LdcInsnNode ldc : constants) {
        if (method.instructions.indexOf(ldc) <= guardIndex) {
          return Match.incompatible("a selected grid constant is not dominated by the starscape branch marker");
        }
      }
      return Match.plan(new Plan(method, constants));
    }

    @Override
    public PatchReport mutate(Plan plan) {
      for (LdcInsnNode ldc : plan.constants()) {
        plan.method().instructions.set(ldc,
            new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "gridSpacing", "()F", false));
      }
      PatchReport report = new PatchReport();
      report.add("large-sector grid line cap", 5);
      return report;
    }

    @Override
    public void verify(Plan plan, ClassNode node) {
      requireCount("gridSpacing postcondition",
          countCalls(plan.method(), Opcodes.INVOKESTATIC, HOOKS, "gridSpacing", "()F"), 5);
    }
  }

  // ---------------------------------------------------------------------
  // Campaign and route hot paths
  // ---------------------------------------------------------------------


  // ---------------------------------------------------------------------
  // StrategicModule: destination-first safe jump-point filtering
  // ---------------------------------------------------------------------


  // ---------------------------------------------------------------------
  // StrategicModule: destination-location jump-point index
  // ---------------------------------------------------------------------


  // ---------------------------------------------------------------------
  // Topology mutation hooks for the incremental strategic jump index
  // ---------------------------------------------------------------------


  private static final class EntityScriptSnapshotReuse implements StructuredPatch<EntityScriptSnapshotReuse.Plan> {
    private record Plan(String owner, MethodNode runScripts, SnapshotAllocationPlan snapshot, String scriptsField) {
    }

    @Override
    public Match<Plan> match(ClassNode node) {
      MethodNode runScripts = requireMethod(node, "runScripts", "(F)V");
      List<MethodInsnNode> empties = calls(runScripts, Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z");
      AllocationSpec spec = new AllocationSpec("java/util/ArrayList", "(Ljava/util/Collection;)V",
          "borrowEntityScriptSnapshot", "(Ljava/util/Collection;)Ljava/util/List;", 1);
      List<SnapshotAllocationPlan> plans = snapshotAllocationPlans(node.name, runScripts, spec);
      requireCount("BaseCampaignEntity.runScripts defensive snapshot", plans.size(), 1);
      requireCount("BaseCampaignEntity.runScripts retired snapshot hook",
          countCalls(runScripts, Opcodes.INVOKESTATIC, HOOKS, spec.hookName(), spec.hookDesc()), 0);

      SnapshotAllocationPlan snapshot = plans.get(0);
      MethodInsnNode constructor = snapshot.constructor();
      Frame<SourceValue>[] frames = sourceFrames(node.name, runScripts);
      FieldInsnNode scriptsField = requireSourceField(argumentSource(runScripts, constructor, frames, 0),
          Opcodes.GETFIELD, node.name, LIST_DESC, "BaseCampaignEntity.runScripts scripts field");
      if (!scriptsField.name.equals("scripts")) {
        return Match.incompatible("BaseCampaignEntity.runScripts snapshot no longer reads scripts");
      }
      requireSnapshotUseContract(node.name, runScripts, snapshot.store(), 1,
          "BaseCampaignEntity.runScripts defensive snapshot");

      boolean guarded = empties.size() == 1 && hasEntityScriptEmptyGuard(node.name, runScripts, scriptsField.name);
      if (!empties.isEmpty() && !guarded) {
        return Match.incompatible("BaseCampaignEntity.runScripts has a foreign/partial empty guard");
      }
      requireEntityScriptEmptyPathShape(node.name, runScripts, snapshot, scriptsField.name, guarded);
      if (guarded) { return Match.alreadyApplied("BaseCampaignEntity.runScripts empty fast path is already present"); }
      return Match.plan(new Plan(node.name, runScripts, snapshot, scriptsField.name));
    }

    @Override
    public PatchReport mutate(Plan plan) {
      // Most campaign entities have no scripts. The previous reusable-snapshot
      // implementation still opened and cleared a large generic scratch frame for
      // every one of those entities. Return before any scope/allocation instead;
      // the uncommon non-empty path is left byte-for-byte vanilla and therefore
      // keeps a fresh mutation-safe ArrayList snapshot.
      MethodNode runScripts = plan.runScripts();
      AbstractInsnNode oldFirst = runScripts.instructions.getFirst();
      LabelNode nonEmpty = new LabelNode();
      InsnList guard = new InsnList();
      guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
      guard.add(new FieldInsnNode(Opcodes.GETFIELD, plan.owner(), plan.scriptsField(), LIST_DESC));
      guard.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z", true));
      guard.add(new JumpInsnNode(Opcodes.IFEQ, nonEmpty));
      guard.add(new InsnNode(Opcodes.RETURN));
      guard.add(nonEmpty);
      guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
      runScripts.instructions.insertBefore(oldFirst, guard);

      PatchReport report = new PatchReport();
      report.add("empty entity-script fast path", 1);
      return report;
    }

    @Override
    public void verify(Plan plan, ClassNode node) {
      requireCount("BaseCampaignEntity.runScripts empty guard",
          countCalls(plan.runScripts(), Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z"), 1);
      if (!hasEntityScriptEmptyGuard(plan.owner(), plan.runScripts(), plan.scriptsField())) {
        throw mismatch("BaseCampaignEntity.runScripts empty guard postcondition failed");
      }
      requireEntityScriptEmptyPathShape(plan.owner(), plan.runScripts(), plan.snapshot(), plan.scriptsField(), true);
    }
  }

  private static boolean hasEntityScriptEmptyGuard(String owner, MethodNode method, String fieldName) {
    AbstractInsnNode first = method.instructions.getFirst();
    while (first != null && first.getOpcode() < 0) first = first.getNext();
    if (!(first instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0) return false;
    AbstractInsnNode fieldInsn = nextMeaningful(load);
    if (!(fieldInsn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
        || !field.owner.equals(owner) || !field.name.equals(fieldName) || !field.desc.equals(LIST_DESC))
      return false;
    AbstractInsnNode emptyInsn = nextMeaningful(field);
    if (!(emptyInsn instanceof MethodInsnNode empty)
        || !callMatches(empty, Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z"))
      return false;
    AbstractInsnNode jumpInsn = nextMeaningful(empty);
    if (!(jumpInsn instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFEQ) { return false; }
    AbstractInsnNode emptyReturn = nextMeaningful(jump);
    if (emptyReturn == null || emptyReturn.getOpcode() != Opcodes.RETURN) return false;
    return nextMeaningful(jump.label) == nextMeaningful(emptyReturn);
  }

  private static void requireEntityScriptEmptyPathShape(String owner, MethodNode method, SnapshotAllocationPlan plan,
      String scriptsFieldName, boolean guarded) {
    if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
      throw mismatch("BaseCampaignEntity.runScripts gained exception handlers");
    }
    requireCount("BaseCampaignEntity.runScripts returns", countOpcode(method, Opcodes.RETURN), guarded ? 2 : 1);

    AbstractInsnNode entry = firstMeaningful(method);
    AbstractInsnNode bodyStart = entry;
    if (guarded) {
      if (!(entry instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0) {
        throw mismatch("BaseCampaignEntity.runScripts guard is not at method entry");
      }
      AbstractInsnNode fieldInsn = nextMeaningful(load);
      AbstractInsnNode emptyInsn = nextMeaningful(fieldInsn);
      AbstractInsnNode jumpInsn = nextMeaningful(emptyInsn);
      AbstractInsnNode returnInsn = nextMeaningful(jumpInsn);
      if (!(fieldInsn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
          || !field.owner.equals(owner) || !field.name.equals(scriptsFieldName) || !field.desc.equals(LIST_DESC)
          || !(emptyInsn instanceof MethodInsnNode empty)
          || !callMatches(empty, Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z")
          || !(jumpInsn instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFEQ || returnInsn == null
          || returnInsn.getOpcode() != Opcodes.RETURN) {
        throw mismatch("BaseCampaignEntity.runScripts entry guard changed structurally");
      }
      bodyStart = nextMeaningful(returnInsn);
      if (nextMeaningful(jump.label) != bodyStart) {
        throw mismatch("BaseCampaignEntity.runScripts guard no longer rejoins vanilla entry");
      }
    }

    if (bodyStart != plan.allocation() || nextMeaningful(plan.allocation()) != plan.duplicate()) {
      throw mismatch("BaseCampaignEntity.runScripts has a foreign entry prologue");
    }
    AbstractInsnNode loadThis = nextMeaningful(plan.duplicate());
    AbstractInsnNode fieldInsn = nextMeaningful(loadThis);
    if (!(loadThis instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0
        || !(fieldInsn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
        || !field.owner.equals(owner) || !field.name.equals(scriptsFieldName) || !field.desc.equals(LIST_DESC)
        || nextMeaningful(fieldInsn) != plan.constructor() || nextMeaningful(plan.constructor()) != plan.store()) {
      throw mismatch("BaseCampaignEntity.runScripts snapshot entry sequence changed");
    }
    AbstractInsnNode snapshotLoad = nextMeaningful(plan.store());
    AbstractInsnNode iteratorInsn = nextMeaningful(snapshotLoad);
    AbstractInsnNode iteratorStoreInsn = nextMeaningful(iteratorInsn);
    AbstractInsnNode initialJumpInsn = nextMeaningful(iteratorStoreInsn);
    if (!(snapshotLoad instanceof VarInsnNode snapshotVar) || snapshotVar.getOpcode() != Opcodes.ALOAD
        || snapshotVar.var != plan.store().var || !(iteratorInsn instanceof MethodInsnNode iterator)
        || !callMatches(iterator, Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;")
        || !(iteratorStoreInsn instanceof VarInsnNode iteratorStore) || iteratorStore.getOpcode() != Opcodes.ASTORE
        || !(initialJumpInsn instanceof JumpInsnNode initialJump) || initialJump.getOpcode() != Opcodes.GOTO) {
      throw mismatch("BaseCampaignEntity.runScripts snapshot loop setup changed");
    }
    AbstractInsnNode body = nextMeaningful(initialJump);
    AbstractInsnNode testLoadInsn = nextMeaningful(initialJump.label);
    AbstractInsnNode hasNextInsn = nextMeaningful(testLoadInsn);
    AbstractInsnNode loopJumpInsn = nextMeaningful(hasNextInsn);
    AbstractInsnNode terminalReturn = nextMeaningful(loopJumpInsn);
    if (!(testLoadInsn instanceof VarInsnNode testLoad) || testLoad.getOpcode() != Opcodes.ALOAD
        || testLoad.var != iteratorStore.var || !(hasNextInsn instanceof MethodInsnNode hasNext)
        || !callMatches(hasNext, Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z")
        || !(loopJumpInsn instanceof JumpInsnNode loopJump) || loopJump.getOpcode() != Opcodes.IFNE
        || nextMeaningful(loopJump.label) != body || terminalReturn == null
        || terminalReturn.getOpcode() != Opcodes.RETURN || nextMeaningful(terminalReturn) != null) {
      throw mismatch("BaseCampaignEntity.runScripts empty-path control flow changed");
    }
  }


  private PatchReport patchDirectMarketObservationEntry(ClassNode node) {
    MethodNode method = marketAdvanceSemanticBody(node);
    String descriptor = "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;F)V";
    int existing = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "observeMarketAdvanceEntry", descriptor);
    if (existing == 1) {
      AbstractInsnNode expected = firstMeaningful(method);
      if (expected instanceof MethodInsnNode scratch
          && callMatches(scratch, Opcodes.INVOKESTATIC, HOOKS, "beginScratchScope", "()V")) {
        expected = nextMeaningful(expected);
      }
      if (!(expected instanceof VarInsnNode owner) || owner.getOpcode() != Opcodes.ALOAD || owner.var != 0
          || !(nextMeaningful(expected) instanceof VarInsnNode amount) || amount.getOpcode() != Opcodes.FLOAD
          || amount.var != 1 || !(nextMeaningful(amount) instanceof MethodInsnNode hook)
          || !callMatches(hook, Opcodes.INVOKESTATIC, HOOKS, "observeMarketAdvanceEntry", descriptor)) {
        throw mismatch("Market.advance observation entry hook is not at the verified entry point");
      }
      throw already("Market.advance observation entry hook is present");
    }
    requireCount("Market.advance observation entry hook", existing, 0);

    InsnList prologue = new InsnList();
    prologue.add(new VarInsnNode(Opcodes.ALOAD, 0));
    prologue.add(new VarInsnNode(Opcodes.FLOAD, 1));
    prologue.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "observeMarketAdvanceEntry", descriptor, false));
    AbstractInsnNode entry = firstMeaningful(method);
    if (entry instanceof MethodInsnNode scratch
        && callMatches(scratch, Opcodes.INVOKESTATIC, HOOKS, "beginScratchScope", "()V")) {
      method.instructions.insert(entry, prologue);
    } else {
      method.instructions.insertBefore(entry, prologue);
    }

    PatchReport report = new PatchReport();
    report.add("marketAdvanceEntry", 1);
    return report;
  }


  // ---------------------------------------------------------------------
  // Market-share aggregation and punitive-expedition callers
  // ---------------------------------------------------------------------


  // ---------------------------------------------------------------------
  // Clean AoTD BaseIndustry deficit wrapper
  // ---------------------------------------------------------------------


  // ---------------------------------------------------------------------
  // Dormant inherited BaseIndustry.advance() fast path
  // ---------------------------------------------------------------------

  private static final String BASE_INDUSTRY_STATE_FIELD = "spp$marketNoOpState";
  private static final String BASE_INDUSTRY_COUNTDOWN_FIELD = "spp$marketNoOpCountdown";
  private static final String BASE_INDUSTRY_RAW_ADVANCE = "spp$baseIndustryRawAdvance";
  private static final String BASE_INDUSTRY_TEMPLATE_OWNER =
      "com/starsector/prepatcher/agent/templates/BaseIndustryDormantTemplate";
  private static final String BASE_INDUSTRY_TEMPLATE_RESOURCE = "/" + BASE_INDUSTRY_TEMPLATE_OWNER + ".class";
  private static final int BASE_INDUSTRY_AUDIT_PLACEHOLDER = 0x5A17CAFE;

  /**
   * Optimizes only the empty inherited BaseIndustry.advance() steady state.
   * MarketConditionPlugin dispatch is intentionally untouched: benchmarking
   * showed that classifying an empty condition costs more than the empty
   * virtual callback itself. The complete original BaseIndustry method is
   * retained and periodically audited.
   */
  private static final class BaseIndustryDormantFastPath implements StructuredPatch<BaseIndustryDormantFastPath.Plan> {
    private static final int FIELD_ACCESS = Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC;
    private final int auditFrames;

    BaseIndustryDormantFastPath(int auditFrames) {
      this.auditFrames = auditFrames;
    }

    private record Plan(String owner, List<FieldNode> classFields, List<MethodNode> classMethods, MethodNode advance,
        MethodNode setDisrupted, AbstractInsnNode setDisruptedFirst, ClassNode template) {
    }

    @Override
    public Match<Plan> match(ClassNode node) {
      int fieldsPresent = 0;
      for (String fieldName : List.of(BASE_INDUSTRY_STATE_FIELD, BASE_INDUSTRY_COUNTDOWN_FIELD)) {
        List<FieldNode> matches = node.fields.stream().filter(field -> field.name.equals(fieldName)).toList();
        if (!matches.isEmpty()) {
          fieldsPresent++;
          requireCount("BaseIndustry dormant field " + fieldName, matches.size(), 1);
          FieldNode field = matches.get(0);
          if (!field.desc.equals("I") || field.access != FIELD_ACCESS || field.signature != null
              || field.value != null) {
            return Match.incompatible("foreign BaseIndustry dormant field " + fieldName);
          }
        }
      }
      if (fieldsPresent != 0) {
        requireCount("complete BaseIndustry dormant field set", fieldsPresent, 2);
        requireOptimizedBaseIndustryDormantShape(node);
        return Match.alreadyApplied("direct dormant BaseIndustry fast path postcondition matches");
      }

      requireOriginalBaseIndustryDormantShape(node);
      MethodNode advance = requireMethod(node, "advance", "(F)V");
      MethodNode setDisrupted = requireMethod(node, "setDisrupted", "(FZ)V");
      AbstractInsnNode first = firstMeaningful(setDisrupted);
      if (first == null) return Match.incompatible("BaseIndustry.setDisrupted has no code");
      ClassNode template = loadBaseIndustryDormantTemplate();
      return Match.plan(new Plan(node.name, node.fields, node.methods, advance, setDisrupted, first, template));
    }

    @Override
    public PatchReport mutate(Plan plan) {
      plan.classFields().add(new FieldNode(Opcodes.ASM8, FIELD_ACCESS, BASE_INDUSTRY_STATE_FIELD, "I", null, null));
      plan.classFields().add(new FieldNode(Opcodes.ASM8, FIELD_ACCESS, BASE_INDUSTRY_COUNTDOWN_FIELD, "I", null, null));

      MethodNode wrapper =
          renameForPrivateOriginal(plan.owner(), plan.classMethods(), plan.advance(), BASE_INDUSTRY_RAW_ADVANCE);
      installBaseIndustryTemplateBody(plan.template(), plan.owner(), wrapper);
      replaceBaseIndustryAuditPlaceholder(wrapper, auditFrames);

      InsnList wake = new InsnList();
      wake.add(new VarInsnNode(Opcodes.ALOAD, 0));
      wake.add(new InsnNode(Opcodes.ICONST_0));
      wake.add(new FieldInsnNode(Opcodes.PUTFIELD, plan.owner(), BASE_INDUSTRY_STATE_FIELD, "I"));
      wake.add(new VarInsnNode(Opcodes.ALOAD, 0));
      wake.add(new InsnNode(Opcodes.ICONST_0));
      wake.add(new FieldInsnNode(Opcodes.PUTFIELD, plan.owner(), BASE_INDUSTRY_COUNTDOWN_FIELD, "I"));
      plan.setDisrupted().instructions.insertBefore(plan.setDisruptedFirst(), wake);

      PatchReport report = new PatchReport();
      report.add("direct dormant inherited BaseIndustry fast path", 1);
      return report;
    }

    @Override
    public void verify(Plan plan, ClassNode node) {
      requireOptimizedBaseIndustryDormantShape(node);
    }
  }

  private static void requireOriginalBaseIndustryDormantShape(ClassNode node) {
    for (FieldNode field : node.fields) {
      if (field.name.startsWith("spp$marketNoOp")) {
        throw mismatch("foreign/partial BaseIndustry dormant field " + field.name);
      }
    }
    requireCount("foreign BaseIndustry raw advance method", (int) node.methods.stream()
        .filter(method -> method.name.equals(BASE_INDUSTRY_RAW_ADVANCE) && method.desc.equals("(F)V")).count(), 0);
    MethodNode advance = requireMethod(node, "advance", "(F)V");
    requireCount("BaseIndustry.advance isDisrupted",
        countCalls(advance, Opcodes.INVOKEVIRTUAL, node.name, "isDisrupted", "()Z"), 1);
    requireCount("BaseIndustry.advance disruptionFinished",
        countCalls(advance, Opcodes.INVOKEVIRTUAL, node.name, "disruptionFinished", "()V"), 1);
    requireCount("BaseIndustry.advance building reads",
        countFields(advance, Opcodes.GETFIELD, node.name, "building", "Z"), 1);
    requireCount("BaseIndustry.advance wasDisrupted reads",
        countFields(advance, Opcodes.GETFIELD, node.name, "wasDisrupted", "Z"), 1);
    requireCount("BaseIndustry.advance wasDisrupted writes",
        countFields(advance, Opcodes.PUTFIELD, node.name, "wasDisrupted", "Z"), 1);

    MethodNode setDisrupted = requireMethod(node, "setDisrupted", "(FZ)V");
    requireCount("BaseIndustry.setDisrupted canBeDisrupted",
        countCalls(setDisrupted, Opcodes.INVOKEVIRTUAL, node.name, "canBeDisrupted", "()Z"), 1);
    requireCount("BaseIndustry.setDisrupted dormant state writes",
        countFields(setDisrupted, Opcodes.PUTFIELD, node.name, BASE_INDUSTRY_STATE_FIELD, "I"), 0);
    requireCount("BaseIndustry.setDisrupted dormant countdown writes",
        countFields(setDisrupted, Opcodes.PUTFIELD, node.name, BASE_INDUSTRY_COUNTDOWN_FIELD, "I"), 0);
  }

  private static void requireOptimizedBaseIndustryDormantShape(ClassNode node) {
    final int fieldAccess = Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC;
    FieldNode state = requireField(node, BASE_INDUSTRY_STATE_FIELD, "I");
    FieldNode countdown = requireField(node, BASE_INDUSTRY_COUNTDOWN_FIELD, "I");
    if (state.access != fieldAccess || state.signature != null || state.value != null || countdown.access != fieldAccess
        || countdown.signature != null || countdown.value != null) {
      throw mismatch("BaseIndustry dormant field metadata changed");
    }

    MethodNode wrapper = requireMethod(node, "advance", "(F)V");
    MethodNode raw = requireMethod(node, BASE_INDUSTRY_RAW_ADVANCE, "(F)V");
    int rawAccess = Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
    if ((raw.access
        & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC)) != rawAccess) {
      throw mismatch("BaseIndustry raw advance access changed");
    }
    requireCount("BaseIndustry wrapper raw call", countCalls(wrapper, -1, node.name, BASE_INDUSTRY_RAW_ADVANCE, "(F)V"),
        1);
    requireCount("BaseIndustry wrapper eligibility call", countCalls(wrapper, Opcodes.INVOKESTATIC, HOOKS,
        "isBaseIndustryDormantFastPathEligible", "(Ljava/lang/Object;)Z"), 1);
    requireCount("BaseIndustry raw isDisrupted",
        countCalls(raw, Opcodes.INVOKEVIRTUAL, node.name, "isDisrupted", "()Z"), 1);
    requireCount("BaseIndustry raw disruptionFinished",
        countCalls(raw, Opcodes.INVOKEVIRTUAL, node.name, "disruptionFinished", "()V"), 1);

    for (AbstractInsnNode insn : wrapper.instructions.toArray()) {
      if (insn instanceof LdcInsnNode ldc && Integer.valueOf(BASE_INDUSTRY_AUDIT_PLACEHOLDER).equals(ldc.cst)) {
        throw mismatch("BaseIndustry audit placeholder leaked into target");
      }
    }

    MethodNode setDisrupted = requireMethod(node, "setDisrupted", "(FZ)V");
    List<AbstractInsnNode> code = meaningfulInstructions(setDisrupted);
    if (code.size() < 6) throw mismatch("BaseIndustry.setDisrupted wake prologue is short");
    requireVar(code.get(0), Opcodes.ALOAD, 0, "BaseIndustry wake receiver 1");
    if (code.get(1).getOpcode() != Opcodes.ICONST_0) { throw mismatch("BaseIndustry wake state constant changed"); }
    requireField(code.get(2), Opcodes.PUTFIELD, node.name, BASE_INDUSTRY_STATE_FIELD, "I", "BaseIndustry wake state");
    requireVar(code.get(3), Opcodes.ALOAD, 0, "BaseIndustry wake receiver 2");
    if (code.get(4).getOpcode() != Opcodes.ICONST_0) { throw mismatch("BaseIndustry wake countdown constant changed"); }
    requireField(code.get(5), Opcodes.PUTFIELD, node.name, BASE_INDUSTRY_COUNTDOWN_FIELD, "I",
        "BaseIndustry wake countdown");
    requireNoBaseIndustryTemplateReferences(node);
  }

  private static ClassNode loadBaseIndustryDormantTemplate() {
    try (InputStream input = PrepatcherTransformer.class.getResourceAsStream(BASE_INDUSTRY_TEMPLATE_RESOURCE)) {
      if (input == null) throw mismatch("missing BaseIndustry dormant template resource");
      ClassNode template = new ClassNode(Opcodes.ASM8);
      new ClassReader(input).accept(template, 0);
      if (!template.name.equals(BASE_INDUSTRY_TEMPLATE_OWNER)) {
        throw mismatch("BaseIndustry dormant template owner changed: " + template.name);
      }
      return template;
    } catch (IOException ex) {
      throw mismatch("unable to read BaseIndustry dormant template: " + ex.getMessage());
    }
  }

  private static void installBaseIndustryTemplateBody(ClassNode template, String targetOwner, MethodNode target) {
    MethodNode source = requireMethod(template, "advance", "(F)V");
    MethodNode copy = cloneAndRemapBaseIndustryTemplateMethod(source, targetOwner);
    target.instructions.clear();
    target.instructions.add(copy.instructions);
    target.tryCatchBlocks.clear();
    target.tryCatchBlocks.addAll(copy.tryCatchBlocks);
    target.localVariables = copy.localVariables;
    target.visibleLocalVariableAnnotations = copy.visibleLocalVariableAnnotations;
    target.invisibleLocalVariableAnnotations = copy.invisibleLocalVariableAnnotations;
    target.maxLocals = copy.maxLocals;
    target.maxStack = copy.maxStack;
  }

  private static MethodNode cloneAndRemapBaseIndustryTemplateMethod(MethodNode source, String targetOwner) {
    MethodNode copy =
        new MethodNode(Opcodes.ASM8, source.access, source.name, source.desc, source.signature, exceptions(source));
    source.accept(copy);
    remapBaseIndustryTemplateMethod(copy, targetOwner);
    return copy;
  }

  private static void remapBaseIndustryTemplateMethod(MethodNode method, String targetOwner) {
    method.desc = remapBaseIndustryTemplateText(method.desc, targetOwner);
    method.signature = remapBaseIndustryTemplateText(method.signature, targetOwner);
    if (method.exceptions != null) {
      for (int i = 0; i < method.exceptions.size(); i++) {
        method.exceptions.set(i, remapBaseIndustryTemplateText(method.exceptions.get(i), targetOwner));
      }
    }
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof FieldInsnNode field) {
        field.owner = remapBaseIndustryTemplateText(field.owner, targetOwner);
        field.desc = remapBaseIndustryTemplateText(field.desc, targetOwner);
      } else if (insn instanceof MethodInsnNode call) {
        call.owner = remapBaseIndustryTemplateText(call.owner, targetOwner);
        call.desc = remapBaseIndustryTemplateText(call.desc, targetOwner);
      } else if (insn instanceof TypeInsnNode type) {
        type.desc = remapBaseIndustryTemplateText(type.desc, targetOwner);
      } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type) {
        ldc.cst = Type.getType(remapBaseIndustryTemplateText(type.getDescriptor(), targetOwner));
      } else if (insn instanceof MultiANewArrayInsnNode array) {
        array.desc = remapBaseIndustryTemplateText(array.desc, targetOwner);
      } else if (insn instanceof FrameNode frame) {
        remapBaseIndustryFrameValues(frame.local, targetOwner);
        remapBaseIndustryFrameValues(frame.stack, targetOwner);
      } else if (insn instanceof InvokeDynamicInsnNode) {
        throw mismatch("unexpected invokedynamic in BaseIndustry dormant template");
      }
    }
    for (TryCatchBlockNode block : method.tryCatchBlocks) {
      block.type = remapBaseIndustryTemplateText(block.type, targetOwner);
    }
    if (method.localVariables != null) {
      for (LocalVariableNode local : method.localVariables) {
        local.desc = remapBaseIndustryTemplateText(local.desc, targetOwner);
        local.signature = remapBaseIndustryTemplateText(local.signature, targetOwner);
      }
    }
  }

  private static void remapBaseIndustryFrameValues(List<Object> values, String targetOwner) {
    if (values == null) return;
    for (int i = 0; i < values.size(); i++) {
      Object value = values.get(i);
      if (value instanceof String text) {
        values.set(i, remapBaseIndustryTemplateText(text, targetOwner));
      }
    }
  }

  private static String remapBaseIndustryTemplateText(String value, String targetOwner) {
    if (value == null) return null;
    return value.replace(BASE_INDUSTRY_TEMPLATE_OWNER, targetOwner);
  }

  private static void replaceBaseIndustryAuditPlaceholder(MethodNode method, int auditFrames) {
    int replacements = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof LdcInsnNode ldc && Integer.valueOf(BASE_INDUSTRY_AUDIT_PLACEHOLDER).equals(ldc.cst)) {
        ldc.cst = Integer.valueOf(auditFrames);
        replacements++;
      }
    }
    requireCount("BaseIndustry audit-frame placeholder", replacements, 1);
  }

  private static void requireNoBaseIndustryTemplateReferences(ClassNode node) {
    for (FieldNode field : node.fields) {
      if ((field.desc != null && field.desc.contains(BASE_INDUSTRY_TEMPLATE_OWNER))
          || (field.signature != null && field.signature.contains(BASE_INDUSTRY_TEMPLATE_OWNER))) {
        throw mismatch("BaseIndustry template field reference leaked into target");
      }
    }
    for (MethodNode method : node.methods) {
      if ((method.desc != null && method.desc.contains(BASE_INDUSTRY_TEMPLATE_OWNER))
          || (method.signature != null && method.signature.contains(BASE_INDUSTRY_TEMPLATE_OWNER))) {
        throw mismatch("BaseIndustry template method signature leaked into target");
      }
      for (AbstractInsnNode insn : method.instructions.toArray()) {
        if (insn instanceof FieldInsnNode field && (field.owner.contains(BASE_INDUSTRY_TEMPLATE_OWNER)
            || field.desc.contains(BASE_INDUSTRY_TEMPLATE_OWNER))) {
          throw mismatch("BaseIndustry template field instruction leaked into target");
        }
        if (insn instanceof MethodInsnNode call && (call.owner.contains(BASE_INDUSTRY_TEMPLATE_OWNER)
            || call.desc.contains(BASE_INDUSTRY_TEMPLATE_OWNER))) {
          throw mismatch("BaseIndustry template method instruction leaked into target");
        }
        if (insn instanceof TypeInsnNode type && type.desc.contains(BASE_INDUSTRY_TEMPLATE_OWNER)) {
          throw mismatch("BaseIndustry template type instruction leaked into target");
        }
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type
            && type.getDescriptor().contains(BASE_INDUSTRY_TEMPLATE_OWNER)) {
          throw mismatch("BaseIndustry template class literal leaked into target");
        }
      }
    }
  }


  private static final class DynamicParticleCleanup implements StructuredPatch<DynamicParticleCleanup.Plan> {
    private record Plan(String owner, MethodNode advance, MethodInsnNode removeAll, MethodInsnNode add) {
    }

    @Override
    public Match<Plan> match(ClassNode node) {
      MethodNode advance = requireMethod(node, "advance", "(F)V");
      PatchState state = uniformPatchState("DynamicParticleGroup cleanup",
          scratchScopeState(node.name, advance, "DynamicParticleGroup.advance"),
          storedSimpleAllocationState(advance, "java/util/ArrayList", "()V", "borrowParticleRemovalList",
              "()Ljava/util/ArrayList;", 1, "DynamicParticleGroup expired list"),
          particleCleanupState(advance), particleScratchMutationState(advance));
      if (state == PatchState.PATCHED) {
        return Match.alreadyApplied("all DynamicParticleGroup cleanup hooks and scope postconditions match");
      }
      MethodInsnNode removeAll =
          calls(advance, Opcodes.INVOKEVIRTUAL, "java/util/LinkedList", "removeAll", "(Ljava/util/Collection;)Z")
              .get(0);
      return Match.plan(new Plan(node.name, advance, removeAll, matchParticleScratchMutation(node.name, advance)));
    }

    @Override
    public PatchReport mutate(Plan plan) {
      installScratchScope(plan.owner(), plan.advance(), "DynamicParticleGroup.advance");
      int changed = replaceStoredSimpleAllocations(plan.owner(), plan.advance(), "java/util/ArrayList", "()V",
          "borrowParticleRemovalList", "()Ljava/util/ArrayList;", 1, true, "DynamicParticleGroup expired list");
      makeStatic(plan.add(), HOOKS, "scratchListAdd", "(Ljava/util/List;Ljava/lang/Object;)Z");
      if (particleScratchMutationState(plan.advance()) != PatchState.PATCHED) {
        throw mismatch("particle scratch peak hook postcondition failed");
      }
      changed++;
      makeStatic(plan.removeAll(), HOOKS, "removeExpiredParticles", "(Ljava/util/LinkedList;Ljava/util/Collection;)Z");
      changed++;

      PatchReport report = new PatchReport();
      report.add("reusable particle expiry buffer and linear cleanup", changed);
      return report;
    }

    @Override
    public void verify(Plan plan, ClassNode node) {
      uniformPatchState("DynamicParticleGroup cleanup",
          scratchScopeState(plan.owner(), plan.advance(), "DynamicParticleGroup.advance"),
          storedSimpleAllocationState(plan.advance(), "java/util/ArrayList", "()V", "borrowParticleRemovalList",
              "()Ljava/util/ArrayList;", 1, "DynamicParticleGroup expired list"),
          particleCleanupState(plan.advance()), particleScratchMutationState(plan.advance()));
    }
  }

  private static PatchState particleScratchMutationState(MethodNode method) {
    int raw = countCalls(method, Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z");
    int hooks =
        countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "scratchListAdd", "(Ljava/util/List;Ljava/lang/Object;)Z");
    boolean original = raw == 1 && hooks == 0;
    boolean patched = raw == 0 && hooks == 1;
    if (!original && !patched) { throw mismatch("DynamicParticleGroup scratch mutation hook is partial or changed"); }
    return original ? PatchState.ORIGINAL : PatchState.PATCHED;
  }

  private static MethodInsnNode matchParticleScratchMutation(String owner, MethodNode method) {
    AllocationSpec spec =
        new AllocationSpec("java/util/ArrayList", "()V", "borrowParticleRemovalList", "()Ljava/util/ArrayList;", 1);
    List<VarInsnNode> stores = storedAllocationResultStoresRelaxed(method, spec);
    if (stores.size() != 1) throw mismatch("particle scratch local is ambiguous");
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    List<MethodInsnNode> raw = calls(method, Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z");
    if (raw.size() != 1
        || !sourceIsActiveHookLocal(method, raw.get(0), receiverSource(method, raw.get(0), frames), stores)) {
      throw mismatch("particle scratch List.add receiver changed");
    }
    return raw.get(0);
  }


  private static PatchState particleCleanupState(MethodNode method) {
    String label = "DynamicParticleGroup removeAll cleanup";
    String hookDesc = "(Ljava/util/LinkedList;Ljava/util/Collection;)Z";
    List<MethodInsnNode> raw =
        calls(method, Opcodes.INVOKEVIRTUAL, "java/util/LinkedList", "removeAll", "(Ljava/util/Collection;)Z");
    List<MethodInsnNode> hooks = calls(method, Opcodes.INVOKESTATIC, HOOKS, "removeExpiredParticles", hookDesc);
    boolean original = raw.size() == 1 && hooks.isEmpty();
    boolean patched = raw.isEmpty() && hooks.size() == 1;
    if (!original && !patched) { throw mismatch(label + " is missing, duplicated, or partially patched"); }
    MethodInsnNode cleanup = original ? raw.get(0) : hooks.get(0);
    AbstractInsnNode discarded = nextMeaningful(cleanup);
    if (discarded == null || discarded.getOpcode() != Opcodes.POP) {
      throw mismatch(label + " result is not discarded");
    }
    return original ? PatchState.ORIGINAL : PatchState.PATCHED;
  }


  // ---------------------------------------------------------------------
  // Structural matcher and verifier helpers
  // ---------------------------------------------------------------------


  @FunctionalInterface
  interface PatchAction {
    PatchReport apply(ClassNode node);
  }


  /**
   * Adapts a two-phase patch to the single-call contract the envelope expects, keeping refusal
   * signalling on the boundary where every other patch already raises it.
   */
  static <P> PatchAction structured(StructuredPatch<P> patch) {
    return node -> {
      Match<P> match = patch.match(node);
      if (match.alreadyApplied) throw already(match.reason);
      if (match.plan == null) throw mismatch(match.reason);
      PatchReport report = patch.mutate(match.plan);
      patch.verify(match.plan, node);
      return report;
    };
  }

  @FunctionalInterface
  private interface PatchPostcondition {
    void validate(byte[] bytes);
  }

  private static final class TransformState {
    final String className;
    final byte[] incomingBytes;
    byte[] bytes;
    boolean changed;
    boolean compositionFailed;
    final List<String> appliedDetails = new ArrayList<>();
    final List<AppliedPatch> applied = new ArrayList<>();
    final PresentationStructuralContract.State presentationComposition;

    TransformState(String className, byte[] bytes, PresentationStructuralContract.State presentationComposition) {
      this.className = className;
      this.incomingBytes = bytes;
      this.bytes = bytes;
      this.presentationComposition = presentationComposition;
    }
  }

  private record AppliedPatch(String patchId, PatchPostcondition postcondition, String detail, boolean newlyApplied) {
  }

  private record LogBlock(MethodNode method, AbstractInsnNode start, AbstractInsnNode end) {
  }


}
