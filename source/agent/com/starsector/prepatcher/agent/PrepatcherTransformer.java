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

import static com.starsector.prepatcher.agent.bytecode.Allocations.*;
import static com.starsector.prepatcher.agent.bytecode.ClassIO.*;
import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.economy.EconomyGroupIndex.*;
import static com.starsector.prepatcher.agent.bytecode.Flow.*;
import static com.starsector.prepatcher.agent.bytecode.Markers.*;
import static com.starsector.prepatcher.agent.economy.MarketAdvance.*;
import static com.starsector.prepatcher.agent.economy.MarketScheduler.*;
import static com.starsector.prepatcher.agent.bytecode.Mutations.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.bytecode.OwnedTemplates.*;
import static com.starsector.prepatcher.agent.economy.PersistentState.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.hyperspace.AutomatonBufferReuse;
import com.starsector.prepatcher.agent.hyperspace.AutomatonInternalReads;
import com.starsector.prepatcher.agent.economy.BaseIndustryCleanDeficitPath;
import com.starsector.prepatcher.agent.economy.BaseIndustryConstructionBarriers;
import com.starsector.prepatcher.agent.economy.BaseIndustryDormantFastPath;
import com.starsector.prepatcher.agent.campaign.CacheMaintenanceBeforeSave;
import com.starsector.prepatcher.agent.campaign.CampaignCacheLifecycle;
import com.starsector.prepatcher.agent.campaign.CampaignEngineMarketSchedulerTick;
import com.starsector.prepatcher.agent.campaign.CampaignListenerPlan;
import com.starsector.prepatcher.agent.campaign.CampaignSnapshotReuse;
import com.starsector.prepatcher.agent.campaign.CampaignStateRenderBatchProtocol;
import com.starsector.prepatcher.agent.mapintel.CommRelaySystemIndex;
import com.starsector.prepatcher.agent.economy.CommodityEventModDirtyCache;
import com.starsector.prepatcher.agent.economy.CommodityTemporalBaseBinding;
import com.starsector.prepatcher.agent.economy.ConstructionQueueMutationBarriers;
import com.starsector.prepatcher.agent.economy.CoreMarketEventSynchronization;
import com.starsector.prepatcher.agent.campaign.CoreWorldsExtentCache;
import com.starsector.prepatcher.agent.campaign.CoreWorldsTagHooks;
import com.starsector.prepatcher.agent.campaign.CoreWorldsTopologyHooks;
import com.starsector.prepatcher.agent.economy.DirectMarketObservationEntry;
import com.starsector.prepatcher.agent.combat.DynamicParticleCleanup;
import com.starsector.prepatcher.agent.economy.EconomyAdvancePlan;
import com.starsector.prepatcher.agent.campaign.EmptyMemoryAdvanceFastPath;
import com.starsector.prepatcher.agent.economy.EntityMarketSchedulerSource;
import com.starsector.prepatcher.agent.campaign.EntityScriptSnapshotReuse;
import com.starsector.prepatcher.agent.mapintel.GridLineCap;
import com.starsector.prepatcher.agent.hyperspace.HyperspacePatches;
import com.starsector.prepatcher.agent.hyperspace.HyperspaceViewportBounds;
import com.starsector.prepatcher.agent.mapintel.IntelArrowRendering;
import com.starsector.prepatcher.agent.mapintel.IntelIconEntityIndex;
import com.starsector.prepatcher.agent.mapintel.IntelMapLocationCache;
import com.starsector.prepatcher.agent.mapintel.IntelMissingIconReconciliation;
import com.starsector.prepatcher.agent.startup.LoadingTextReader;
import com.starsector.prepatcher.agent.startup.LoadingUtilsStartupLogs;
import com.starsector.prepatcher.agent.economy.LocalResourcesColdMarketDataElision;
import com.starsector.prepatcher.agent.economy.LocalResourcesTooltipSnapshot;
import com.starsector.prepatcher.agent.mapintel.MapInteractionPlan;
import com.starsector.prepatcher.agent.mapintel.MapRenderReconciliation;
import com.starsector.prepatcher.agent.economy.MarketAdvancePlan;
import com.starsector.prepatcher.agent.economy.MarketComponentStepReplay;
import com.starsector.prepatcher.agent.economy.MarketEconGroupMutationBarrier;
import com.starsector.prepatcher.agent.economy.MarketShareDataPutElision;
import com.starsector.prepatcher.agent.economy.MarketShareLinearAggregation;
import com.starsector.prepatcher.agent.mapintel.NearbyLabelIconLookup;
import com.starsector.prepatcher.agent.hyperspace.NoOpTerrainLayerSkip;
import com.starsector.prepatcher.agent.presentation.PresentationStructuralContract;
import com.starsector.prepatcher.agent.economy.PunitivePlayerShareLocalCache;
import com.starsector.prepatcher.agent.economy.ReachEconomyGroupIndex;
import com.starsector.prepatcher.agent.mapintel.RouteJumpPointIndex;
import com.starsector.prepatcher.agent.startup.RulesLiteralParser;
import com.starsector.prepatcher.agent.mapintel.SampleCacheClearThrottle;
import com.starsector.prepatcher.agent.startup.SaveLoadProgressThrottle;
import com.starsector.prepatcher.agent.campaign.SaveMethodPlan;
import com.starsector.prepatcher.agent.startup.SaveOutputBufferDedup;
import com.starsector.prepatcher.agent.combat.ShipAdvanceScratch;
import com.starsector.prepatcher.agent.startup.SpecStoreStartupLogs;
import com.starsector.prepatcher.agent.hyperspace.StarfieldCleanupBuffers;
import com.starsector.prepatcher.agent.startup.StartupInfoBlockSuppression;
import com.starsector.prepatcher.agent.strategicjump.StrategicJumpDestinationInvalidation;
import com.starsector.prepatcher.agent.strategicjump.StrategicJumpDestinationPlan;
import com.starsector.prepatcher.agent.strategicjump.StrategicJumpDestinationRetarget;
import com.starsector.prepatcher.agent.strategicjump.StrategicJumpLocationMutation;
import com.starsector.prepatcher.agent.mapintel.SystemNebulaUpdateCache;
import com.starsector.prepatcher.agent.combat.TempModExpiryScheduler;
import com.starsector.prepatcher.agent.hyperspace.TerrainRandomReuse;

/**
 * Applies each optimization only when its local bytecode contract can be proven
 * against the class bytes actually supplied by the JVM. No JAR or class digest is
 * used as a compatibility decision.
 */
public final class PrepatcherTransformer implements ClassFileTransformer {

  static final String CORE_WORLDS_PATCH_ID = "coreWorldsExtentCache";

  static final String AOTD_DEFICIT_PATCH_ID = "aotdCleanDeficitPath";


  static final String MARKET_SHARE_LINEAR_PATCH_ID = "marketShareLinearAggregation";
  static final String MARKET_SHARE_DATA_PUT_ELISION_PATCH_ID = "marketShareDataPutElision";
  static final String PUNITIVE_PLAYER_SHARE_PATCH_ID = "punitivePlayerShareLocalCache";


  public static final String H = "com/fs/starfarer/coreui/A/H";
  public static final String A = "com/fs/starfarer/coreui/A/A";
  public static final String Z = "com/fs/starfarer/coreui/A/Z";
  public static final String EVENTS = "com/fs/starfarer/campaign/comms/v2/EventsPanel";
  public static final String CAMPAIGN_STATE = "com/fs/starfarer/campaign/CampaignState";
  public static final String CAMPAIGN_ENGINE = "com/fs/starfarer/campaign/CampaignEngine";
  public static final String COURSE_WIDGET = "com/fs/starfarer/coreui/A/O0Oo";
  public static final String STRATEGIC_MODULE = "com/fs/starfarer/campaign/ai/StrategicModule";
  public static final String JUMP_POINT = "com/fs/starfarer/campaign/JumpPoint";
  public static final String JUMP_DESTINATION = "com/fs/starfarer/api/campaign/JumpPointAPI$JumpDestination";
  public static final String BASE_LOCATION = "com/fs/starfarer/campaign/BaseLocation";
  public static final String BASE_CAMPAIGN_ENTITY = "com/fs/starfarer/campaign/BaseCampaignEntity";
  public static final String MEMORY = "com/fs/starfarer/campaign/rules/Memory";
  public static final String CORE_SCRIPT = "com/fs/starfarer/api/impl/campaign/CoreScript";
  public static final String ECONOMY = "com/fs/starfarer/campaign/econ/Economy";
  public static final String MARKET = "com/fs/starfarer/campaign/econ/Market";
  public static final String BASE_INDUSTRY = "com/fs/starfarer/api/impl/campaign/econ/impl/BaseIndustry";
  public static final String COMMODITY_MARKET_DATA = "com/fs/starfarer/campaign/econ/reach/CommodityMarketData";
  public static final String PUNITIVE_EXPEDITION_MANAGER =
      "com/fs/starfarer/api/impl/campaign/intel/punitive/PunitiveExpeditionManager";
  public static final String NEX_PUNITIVE_EXPEDITION_MANAGER = "exerelin/campaign/intel/Nex_PunitiveExpeditionManager";


  public static final String MILITARY_BASE = "com/fs/starfarer/api/impl/campaign/econ/impl/MilitaryBase";
  public static final String LIONS_GUARD_HQ = "com/fs/starfarer/api/impl/campaign/econ/impl/LionsGuardHQ";
  public static final String RECENT_UNREST = "com/fs/starfarer/api/impl/campaign/econ/RecentUnrest";
  public static final String CONSTRUCTION_QUEUE = "com/fs/starfarer/api/impl/campaign/econ/impl/ConstructionQueue";
  public static final String COMMODITY_ON_MARKET = "com/fs/starfarer/campaign/econ/CommodityOnMarket";
  public static final String LOCAL_RESOURCES_SUBMARKET =
      "com/fs/starfarer/api/impl/campaign/submarkets/LocalResourcesSubmarketPlugin";
  public static final String REACH_ECONOMY = "com/fs/starfarer/campaign/econ/reach/ReachEconomy";
  public static final String ECONOMY_ADVANCE_PLAN_PATCH_ID = "economyAdvancePlan";
  public static final int ECONOMY_ADVANCE_FEATURE_PERSISTENT_SNAPSHOTS = 1;
  public static final int ECONOMY_ADVANCE_FEATURE_LOCATION_CACHE = 2;
  public static final int ECONOMY_ADVANCE_FEATURE_MARKET_SCHEDULER = 4;
  public static final String MARKET_ADVANCE_PLAN_PATCH_ID = "marketAdvancePlan";
  public static final int MARKET_ADVANCE_FEATURE_PERSISTENT_SNAPSHOTS = 1;
  public static final int MARKET_ADVANCE_FEATURE_COMMODITY_TEMPORAL = 2;
  public static final int MARKET_ADVANCE_FEATURE_ENTRY_OBSERVATION = 4;
  public static final int MARKET_ADVANCE_FEATURE_SCHEDULER_SEMANTICS = 8;
  public static final String SAVE_METHOD_PLAN_PATCH_ID = "saveMethodPlan";
  public static final String STRATEGIC_JUMP_DESTINATION_PLAN_PATCH_ID = "strategicJumpDestinationPlan";
  public static final int STRATEGIC_JUMP_FEATURE_DESTINATION_FIRST = 1;
  public static final int STRATEGIC_JUMP_FEATURE_DESTINATION_INDEX = 2;
  public static final String CAMPAIGN_LISTENER_PLAN_PATCH_ID = "campaignListenerPlan";
  public static final int CAMPAIGN_LISTENER_FEATURE_ENTITY_INDEX_REPAIR = 1;
  public static final int CAMPAIGN_LISTENER_FEATURE_THROTTLE = 2;
  public static final String MAP_INTERACTION_PLAN_PATCH_ID = "mapInteractionPlan";
  public static final int MAP_INTERACTION_FEATURE_INTEL_CALLBACK_CACHE = 1;
  public static final int MAP_INTERACTION_FEATURE_HIT_TEST = 2;
  public static final int SAVE_METHOD_FEATURE_CACHE_MAINTENANCE = 1;
  public static final int SAVE_METHOD_FEATURE_MARKET_SCHEDULER_FLUSH = 2;
  static final String SAVE_OUTPUT_BUFFER_DEDUP_PATCH_ID = "saveOutputBufferDedup";
  public static final String MUTABLE_STAT = "com/fs/starfarer/api/combat/MutableStat";
  public static final String MUTABLE_STAT_WITH_TEMP_MODS = "com/fs/starfarer/api/combat/MutableStatWithTempMods";
  public static final String INTEL_MANAGER = "com/fs/starfarer/campaign/comms/v2/IntelManager";
  public static final String SHIP = "com/fs/starfarer/combat/entities/Ship";
  public static final String DYNAMIC_PARTICLE_GROUP = "com/fs/graphics/particle/DynamicParticleGroup";
  public static final String LOADING_UTILS = "com/fs/starfarer/loading/LoadingUtils";
  public static final String SCRIPT_STORE_RUNNER = "com/fs/starfarer/loading/scripts/ScriptStore$3";
  public static final String RULES = "com/fs/starfarer/campaign/rules/Rules";
  public static final String SPEC_STORE = "com/fs/starfarer/loading/SpecStore";
  public static final String TEXTURE_LOADER = "com/fs/graphics/TextureLoader";
  public static final String SOUND = "sound/Sound";
  public static final String PROGRESS_INPUT =
      "com/fs/starfarer/util/oOoOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOO";
  public static final String PROGRESS_OUTPUT = "com/fs/starfarer/util/do";
  public static final String CAMPAIGN_GAME_MANAGER = "com/fs/starfarer/campaign/save/CampaignGameManager";
  public static final String PLANET_SURVEY_PANEL = "com/fs/starfarer/campaign/ui/marketinfo/PlanetSurveyPanel";
  public static final String LUDDIC_PATH_BASE_INTEL =
      "com/fs/starfarer/api/impl/campaign/intel/bases/LuddicPathBaseIntel";
  public static final String PIRATE_BASE_INTEL = "com/fs/starfarer/api/impl/campaign/intel/bases/PirateBaseIntel";
  public static final String DECIV_TRACKER = "com/fs/starfarer/api/impl/campaign/intel/deciv/DecivTracker";
  public static final String PK_CMD = "com/fs/starfarer/api/impl/campaign/rulecmd/PK_CMD";
  public static final Set<String> TARGET_CLASSES = Set.of(H, A, Z, EVENTS, CAMPAIGN_STATE, CAMPAIGN_ENGINE,
      COURSE_WIDGET, STRATEGIC_MODULE, JUMP_POINT, JUMP_DESTINATION, BASE_LOCATION, BASE_CAMPAIGN_ENTITY, MEMORY,
      CORE_SCRIPT, ECONOMY, MARKET, BASE_INDUSTRY, COMMODITY_MARKET_DATA, PUNITIVE_EXPEDITION_MANAGER, MILITARY_BASE,
      LIONS_GUARD_HQ, RECENT_UNREST, CONSTRUCTION_QUEUE, COMMODITY_ON_MARKET, LOCAL_RESOURCES_SUBMARKET, REACH_ECONOMY,
      MUTABLE_STAT, MUTABLE_STAT_WITH_TEMP_MODS, INTEL_MANAGER, SHIP, DYNAMIC_PARTICLE_GROUP, LOADING_UTILS,
      SCRIPT_STORE_RUNNER, RULES, SPEC_STORE, TEXTURE_LOADER, SOUND, PROGRESS_INPUT, PROGRESS_OUTPUT,
      CAMPAIGN_GAME_MANAGER, PLANET_SURVEY_PANEL, LUDDIC_PATH_BASE_INTEL, PIRATE_BASE_INTEL, DECIV_TRACKER, PK_CMD,
      HyperspacePatches.BASE_TILED, HyperspacePatches.HYPER_TERRAIN, HyperspacePatches.AUTOMATON,
      HyperspacePatches.STARFIELD);


  public static final Set<String> OPTIONAL_TARGET_CLASSES = Set.of(NEX_PUNITIVE_EXPEDITION_MANAGER);

  private final PrepatcherConfig config;
  /**
   * Loader that owns the typed runtime hooks. A null value is used only by
   * the offline structural harness, which transforms byte arrays without
   * defining them in a JVM class loader.
   */
  private final ClassLoader runtimeLoader;
  private final AtomicInteger patchedClasses = new AtomicInteger();
  private final StructuralPatchEnvelope envelope;

  public PrepatcherTransformer(PrepatcherConfig config) {
    this(config, null);
  }

  public PrepatcherTransformer(PrepatcherConfig config, ClassLoader runtimeLoader) {
    this.config = config;
    this.runtimeLoader = runtimeLoader;
    this.envelope = new StructuralPatchEnvelope(config);
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
      boolean logged = "SKIPPED_LOADER".equals(System.getProperty(key));
      TransformDiagnostics.reject(key, "SKIPPED_LOADER");
      if (!logged) {
        PrepatcherLog.warn("SKIPPED_LOADER " + className + ": target loader=" + loaderName(loader) + ", runtime loader="
            + loaderName(runtimeLoader));
      }
      return null;
    }

    PresentationStructuralContract.State presentationComposition;
    try {
      presentationComposition = PresentationStructuralContract.inspect(className, classfileBuffer, config);
      if (presentationComposition.present()) {
        StructuralPatchEnvelope.recordStatus(className, "presentationStructuralComposition", "INPUT_VALIDATED");
      }
    } catch (Throwable failure) {
      envelope.recordPresentationCompositionSkip(className, failure);
      return null;
    }

    StructuralPatchEnvelope.TransformState state =
        new StructuralPatchEnvelope.TransformState(className, classfileBuffer, presentationComposition);
    switch (className) {
      case H -> {
        envelope.apply(state, "mapRenderStuff", config.mapRenderStuff, new MapRenderReconciliation());
        envelope.apply(state, "labelSpatialCandidates", config.labelSpatialCandidates, new NearbyLabelIconLookup());
        envelope.apply(state, "intelEntityIndex", config.intelEntityIndex, new IntelIconEntityIndex());
        envelope.apply(state, "systemNebulaCache", config.systemNebulaCache, new SystemNebulaUpdateCache());
        envelope.apply(state, "sampleCacheClearThrottle", config.sampleCacheClearThrottle,
            new SampleCacheClearThrottle());
        envelope.apply(state, "gridLineCap", config.gridLineCap, new GridLineCap());
      }
      case A -> envelope.apply(state, MAP_INTERACTION_PLAN_PATCH_ID, mapInteractionPlanEnabled(),
          new MapInteractionPlan(requestedMapInteractionFeatureMask()));
      case Z -> envelope.apply(state, "intelArrowRendering", config.intelArrowRendering, new IntelArrowRendering());
      case EVENTS -> {
        envelope.apply(state, "intelCallbackCache", config.intelCallbackCache, new IntelMapLocationCache(7));
        envelope.apply(state, "intelReconciliation", config.intelReconciliation, new IntelMissingIconReconciliation());
      }
      case CAMPAIGN_STATE ->
        envelope.apply(state, "marketScheduler", config.marketScheduler, new CampaignStateRenderBatchProtocol());
      case CAMPAIGN_ENGINE -> {
        // The incremental core-worlds contract spans three independent classes.
        // This CampaignEngine surface owns authoritative system add/remove events.
        envelope.apply(state, CORE_WORLDS_PATCH_ID, config.coreWorldsExtentCache, new CoreWorldsTopologyHooks());
        envelope.apply(state, "campaignCacheLifecycle", campaignCacheLifecycleEnabled(), new CampaignCacheLifecycle());
        envelope.apply(state, "marketScheduler", config.marketScheduler, new CampaignEngineMarketSchedulerTick());
        envelope.apply(state, CAMPAIGN_LISTENER_PLAN_PATCH_ID, campaignListenerPlanEnabled(),
            new CampaignListenerPlan(requestedCampaignListenerFeatureMask()));
      }
      case COURSE_WIDGET ->
        envelope.apply(state, "routeJumpPointIndex", config.routeJumpPointIndex, new RouteJumpPointIndex());
      case STRATEGIC_MODULE ->
        envelope.apply(state, STRATEGIC_JUMP_DESTINATION_PLAN_PATCH_ID, strategicJumpDestinationPlanEnabled(),
            new StrategicJumpDestinationPlan(requestedStrategicJumpDestinationFeatureMask()));
      case JUMP_POINT -> envelope.apply(state, STRATEGIC_JUMP_DESTINATION_INDEX_PATCH_ID,
          config.strategicJumpDestinationIndex, new StrategicJumpDestinationInvalidation());
      case JUMP_DESTINATION -> envelope.apply(state, STRATEGIC_JUMP_DESTINATION_INDEX_PATCH_ID,
          config.strategicJumpDestinationIndex, new StrategicJumpDestinationRetarget());
      case BASE_LOCATION -> {
        // BaseLocation owns authoritative addTag/removeTag/clearTags events.
        envelope.apply(state, CORE_WORLDS_PATCH_ID, config.coreWorldsExtentCache, new CoreWorldsTagHooks());
        envelope.apply(state, "campaignSnapshotReuse", config.campaignSnapshotReuse, new CampaignSnapshotReuse());
        envelope.apply(state, STRATEGIC_JUMP_DESTINATION_INDEX_PATCH_ID, config.strategicJumpDestinationIndex,
            new StrategicJumpLocationMutation());
      }
      case BASE_CAMPAIGN_ENTITY -> {
        envelope.apply(state, "entityScriptSnapshotReuse", config.entityScriptSnapshotReuse,
            new EntityScriptSnapshotReuse());
        envelope.apply(state, "marketScheduler", config.marketScheduler, new EntityMarketSchedulerSource());
      }
      case MEMORY -> envelope.apply(state, "emptyMemoryAdvanceFastPath", config.emptyMemoryAdvanceFastPath,
          new EmptyMemoryAdvanceFastPath());
      case CORE_SCRIPT ->
        envelope.apply(state, CORE_WORLDS_PATCH_ID, config.coreWorldsExtentCache, new CoreWorldsExtentCache());
      case ECONOMY -> envelope.apply(state, ECONOMY_ADVANCE_PLAN_PATCH_ID, economyAdvancePlanEnabled(),
          new EconomyAdvancePlan(requestedEconomyAdvanceFeatureMask(), config.economyStructureAuditMs));
      case MARKET -> {
        envelope.apply(state, MARKET_ADVANCE_PLAN_PATCH_ID, marketAdvancePlanEnabled(),
            new MarketAdvancePlan(requestedMarketAdvanceFeatureMask(), config.marketStructureAuditFrames,
                new DirectMarketObservationEntry()));
        envelope.apply(state, "economyGroupIndexMarketMutation", config.economyGroupIndex,
            new MarketEconGroupMutationBarrier());
      }
      case LOCAL_RESOURCES_SUBMARKET -> {
        envelope.apply(state, "localResourcesNoColdMarketData", config.localResourcesNoColdMarketData,
            new LocalResourcesColdMarketDataElision());
        envelope.apply(state, "localResourcesTooltipSnapshot", config.localResourcesTooltipSnapshot,
            new LocalResourcesTooltipSnapshot());
      }
      case REACH_ECONOMY ->
        envelope.apply(state, "economyGroupIndexReachEconomy", config.economyGroupIndex, new ReachEconomyGroupIndex());
      case COMMODITY_MARKET_DATA -> {
        // Both patches touch the same class. The linear wrapper is installed first;
        // put-elision then consumes that current state and composition validation
        // rechecks the wrapper before the class is committed.
        envelope.apply(state, MARKET_SHARE_LINEAR_PATCH_ID, config.marketShareLinearAggregation,
            new MarketShareLinearAggregation());
        envelope.apply(state, MARKET_SHARE_DATA_PUT_ELISION_PATCH_ID, config.marketShareDataPutElision,
            new MarketShareDataPutElision());
      }
      case PUNITIVE_EXPEDITION_MANAGER -> envelope.apply(state, PUNITIVE_PLAYER_SHARE_PATCH_ID,
          config.punitivePlayerShareLocalCache, new PunitivePlayerShareLocalCache());
      case NEX_PUNITIVE_EXPEDITION_MANAGER -> envelope.apply(state, PUNITIVE_PLAYER_SHARE_PATCH_ID,
          config.nexPunitivePlayerShareLocalCache, new PunitivePlayerShareLocalCache());
      case BASE_INDUSTRY -> {
        envelope.apply(state, AOTD_DEFICIT_PATCH_ID, config.aotdCleanDeficitPath, new BaseIndustryCleanDeficitPath());
        envelope.apply(state, "marketNoOpCallbacks",
            config.marketNoOpCallbacks && config.marketNoOpIndustryAuditFrames > 0,
            new BaseIndustryDormantFastPath(config.marketNoOpIndustryAuditFrames));
        envelope.apply(state, "marketConstructionMutationBarriers", config.marketScheduler,
            new BaseIndustryConstructionBarriers());
      }
      case MILITARY_BASE ->
        envelope.apply(state, "marketStepReplayMilitaryBase", config.marketScheduler, new MarketComponentStepReplay(1));
      case LIONS_GUARD_HQ ->
        envelope.apply(state, "marketStepReplayLionsGuardHQ", config.marketScheduler, new MarketComponentStepReplay(2));
      case RECENT_UNREST ->
        envelope.apply(state, "marketStepReplayRecentUnrest", config.marketScheduler, new MarketComponentStepReplay(3));
      case CONSTRUCTION_QUEUE -> envelope.apply(state, "marketConstructionMutationBarriers", config.marketScheduler,
          new ConstructionQueueMutationBarriers());
      case COMMODITY_ON_MARKET -> envelope.apply(state, "commodityEventModDirtyCache",
          config.commodityEventModDirtyCache, new CommodityEventModDirtyCache());
      case MUTABLE_STAT -> envelope.apply(state, "commodityTemporalBinding", config.commodityTemporalFastPath,
          new CommodityTemporalBaseBinding());
      case MUTABLE_STAT_WITH_TEMP_MODS -> envelope.apply(state, "tempModExpiryScheduler",
          config.tempModExpiryScheduler || config.commodityTemporalFastPath, new TempModExpiryScheduler());
      case INTEL_MANAGER ->
        envelope.apply(state, "commRelaySystemIndex", config.commRelaySystemIndex, new CommRelaySystemIndex());
      case SHIP -> envelope.apply(state, "shipAdvanceScratch", config.shipAdvanceScratch, new ShipAdvanceScratch());
      case DYNAMIC_PARTICLE_GROUP ->
        envelope.apply(state, "particleCleanup", config.particleCleanup, new DynamicParticleCleanup());
      case LOADING_UTILS -> {
        envelope.apply(state, "loadingTextReader", config.loadingTextReader, new LoadingTextReader());
        envelope.apply(state, "startupLogAggregation", config.startupLogAggregation, new LoadingUtilsStartupLogs());
      }
      case SCRIPT_STORE_RUNNER ->
        envelope.apply(state, "startupLogAggregation", config.startupLogAggregation, new StartupInfoBlockSuppression(
            "] already loaded (perhaps from jar file, or due to a reference from another class), skipping compilation.",
            1, 2, true, "alreadyLoadedScriptInfo"));
      case RULES -> {
        envelope.apply(state, "startupLogAggregation", config.startupLogAggregation,
            new StartupInfoBlockSuppression("Loading rule: ", 1, 3, true, "perRuleInfo"));
        envelope.apply(state, "rulesLiteralParser", config.rulesLiteralParser, new RulesLiteralParser());
      }
      case SPEC_STORE ->
        envelope.apply(state, "startupLogAggregation", config.startupLogAggregation, new SpecStoreStartupLogs());
      case TEXTURE_LOADER ->
        envelope.apply(state, "startupLogAggregation", config.startupLogAggregation, new StartupInfoBlockSuppression(
            "Cleaned buffer for texture %s (using reflection)", 1, 5, true, "textureCleanupInfo"));
      case SOUND -> {
        // sound.Sound is parent-loaded by Faster Rendering and cannot resolve
        // the typed runtime installed in FR's child system loader. Removing
        // this pure INFO block inline preserves the optimization without a
        // cross-loader helper call. The structural marker remains the source
        // of ownership/idempotency truth; only the optional runtime counter is
        // intentionally absent for this one parent-owned target.
        envelope.apply(state, "startupLogAggregation", config.startupLogAggregation,
            new StartupInfoBlockSuppression("Loading sound [", 1, 6, false, "soundLoadInfo"));
      }
      case PROGRESS_INPUT -> envelope.apply(state, "saveLoadProgressThrottle", config.saveLoadProgressThrottle,
          new SaveLoadProgressThrottle("super"));
      case PROGRESS_OUTPUT -> envelope.apply(state, "saveLoadProgressThrottle", config.saveLoadProgressThrottle,
          new SaveLoadProgressThrottle("o00000"));
      case CAMPAIGN_GAME_MANAGER -> {
        envelope.apply(state, SAVE_METHOD_PLAN_PATCH_ID, saveMethodPlanEnabled(),
            new SaveMethodPlan(requestedSaveMethodFeatureMask(), new CacheMaintenanceBeforeSave()));
        // The output-chain allocation rewrite is optional and deliberately
        // ordered after the correctness-critical pre-save barrier. A layout
        // mismatch may skip deduplication without disabling scheduler readiness.
        envelope.apply(state, SAVE_OUTPUT_BUFFER_DEDUP_PATCH_ID, config.saveOutputBufferDedup,
            new SaveOutputBufferDedup());
      }
      case PLANET_SURVEY_PANEL, LUDDIC_PATH_BASE_INTEL, PIRATE_BASE_INTEL, DECIV_TRACKER, PK_CMD ->
        envelope.apply(state, "marketScheduler", config.marketScheduler, new CoreMarketEventSynchronization());
      case HyperspacePatches.BASE_TILED -> {
        envelope.apply(state, "hyperspaceViewportBounds", config.hyperspaceViewportBounds,
            new HyperspaceViewportBounds());
        envelope.apply(state, "terrainRandomReuse", config.terrainRandomReuse, new TerrainRandomReuse(3));
      }
      case HyperspacePatches.HYPER_TERRAIN -> {
        envelope.apply(state, "skipNoOpTerrainLayer", config.skipNoOpTerrainLayer, new NoOpTerrainLayerSkip());
        envelope.apply(state, "terrainRandomReuse", config.terrainRandomReuse, new TerrainRandomReuse(1));
        envelope.apply(state, "automatonInternalReads", config.automatonBufferReuse, new AutomatonInternalReads());
      }
      case HyperspacePatches.AUTOMATON ->
        envelope.apply(state, "automatonBufferReuse", config.automatonBufferReuse, new AutomatonBufferReuse());
      case HyperspacePatches.STARFIELD ->
        envelope.apply(state, "starfieldCleanupBuffers", config.starfieldCleanupBuffers, new StarfieldCleanupBuffers());
      default -> {
        return null;
      }
    }

    if (!envelope.validateFinalComposition(state)) { return null; }
    envelope.finalizeAppliedPatches(state);

    if (!state.changed) {
      PrepatcherLog.info("Structural scan completed for " + className + "; no new compatible patch was applied.");
      return null;
    }

    TransformDiagnostics.onCommit(() -> {
      int count = patchedClasses.incrementAndGet();
      System.setProperty("starsector.prepatcher.patchedClasses", Integer.toString(count));
    });
    PrepatcherLog.info("Patched " + className + " structurally: " + String.join(", ", state.appliedDetails) + ", bytes "
        + classfileBuffer.length + " -> " + state.bytes.length);

    return state.bytes;
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


  private static String loaderName(ClassLoader loader) {
    return loader == null ? "<bootstrap>"
        : loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
  }


}
