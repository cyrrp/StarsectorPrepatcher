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
  private static final String CORE_WORLDS_RUNTIME = "com/fs/starfarer/api/StarsectorPrepatcherCoreWorldsRuntime";
  private static final String CORE_WORLDS_PATCH_ID = "coreWorldsExtentCache";
  private static final String ENTITY_LOOKUP_INDEX_REPAIR_FIELD = "spp$entityLookupIndexReady";
  private static final int MARKET_SCHEDULER_COMPONENT_ENGINE = 1;
  private static final int MARKET_SCHEDULER_COMPONENT_ECONOMY = 2;
  private static final int MARKET_SCHEDULER_COMPONENT_ENTITY = 4;
  private static final int MARKET_SCHEDULER_COMPONENT_SAVE = 8;
  private static final int MARKET_SCHEDULER_COMPONENT_BATCH_PROTOCOL = 16;
  private static final int MARKET_SCHEDULER_COMPONENT_MARKET_SEMANTICS = 32;
  private static final int MARKET_SCHEDULER_COMPONENT_MILITARY_BASE = 64;
  private static final int MARKET_SCHEDULER_COMPONENT_LIONS_GUARD = 128;
  private static final int MARKET_SCHEDULER_COMPONENT_RECENT_UNREST = 256;
  private static final int MARKET_SCHEDULER_COMPONENT_BASE_INDUSTRY = 512;
  private static final int MARKET_SCHEDULER_COMPONENT_CONSTRUCTION_QUEUE = 1024;
  private static final String AOTD_DEFICIT_PATCH_ID = "aotdCleanDeficitPath";
  private static final String AOTD_DEFICIT_RAW_METHOD = "spp$baseIndustryRawGetMaxDeficit";
  private static final String AOTD_DEFICIT_DESC = "([Ljava/lang/String;)Lcom/fs/starfarer/api/util/Pair;";
  private static final String AOTD_DEFICIT_TEMPLATE_OWNER =
      "com/starsector/prepatcher/agent/templates/BaseIndustryDeficitTemplate";
  private static final String AOTD_DEFICIT_TEMPLATE_RESOURCE = "/" + AOTD_DEFICIT_TEMPLATE_OWNER + ".class";
  private static final String MARKET_SHARE_LINEAR_PATCH_ID = "marketShareLinearAggregation";
  private static final String MARKET_SHARE_DATA_PUT_ELISION_PATCH_ID = "marketShareDataPutElision";
  private static final String PUNITIVE_PLAYER_SHARE_PATCH_ID = "punitivePlayerShareLocalCache";
  private static final String MARKET_SHARE_RAW_METHOD = "spp$commodityMarketDataRawMarketSharePerFaction";
  private static final String MARKET_SHARE_METHOD_DESC = "()Ljava/util/Map;";
  private static final String MARKET_SHARE_TEMPLATE_OWNER =
      "com/starsector/prepatcher/agent/templates/CommodityMarketShareTemplate";
  private static final String MARKET_SHARE_TEMPLATE_RESOURCE = "/" + MARKET_SHARE_TEMPLATE_OWNER + ".class";
  private static final String MARKET_SHARE_RUNTIME = "com/fs/starfarer/api/StarsectorPrepatcherMarketShareRuntime";
  private static final String MARKET_SHARE_ELIGIBILITY_METHOD = "isEligible";
  private static final String MARKET_SHARE_ELIGIBILITY_DESC = "(Ljava/lang/Object;)Z";
  private static final String PUNITIVE_TEMPLATE_OWNER =
      "com/starsector/prepatcher/agent/templates/PunitivePlayerShareTemplate";
  private static final String PUNITIVE_TEMPLATE_RESOURCE = "/" + PUNITIVE_TEMPLATE_OWNER + ".class";
  private static final String PUNITIVE_HELPER_METHOD = "spp$punitiveCachedPlayerShare";
  private static final String PUNITIVE_HELPER_DESC = "(Lcom/fs/starfarer/api/campaign/econ/CommodityMarketDataAPI;"
      + "Lcom/fs/starfarer/api/campaign/FactionAPI;Ljava/util/Map;" + "Ljava/util/IdentityHashMap;)I";
  private static final String RUNTIME_BRIDGE = "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge";
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
  private static final String COMMODITY_MARKET_DATA_API = "com/fs/starfarer/api/campaign/econ/CommodityMarketDataAPI";
  private static final String FACTION_API = "com/fs/starfarer/api/campaign/FactionAPI";
  private static final String SECTOR_API = "com/fs/starfarer/api/campaign/SectorAPI";
  private static final String MARKET_SHARE_DATA = "com/fs/starfarer/campaign/econ/reach/MarketShareData";
  private static final String PUNITIVE_REASONS_DESC =
      "(Lcom/fs/starfarer/api/impl/campaign/intel/punitive/" + "PunitiveExpeditionManager$PunExData;)Ljava/util/List;";
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
  private static final String STRATEGIC_JUMP_DESTINATION_FIRST_PATCH_ID = "strategicJumpDestinationFirst";
  private static final String STRATEGIC_JUMP_DESTINATION_INDEX_PATCH_ID = "strategicJumpDestinationIndex";
  private static final String STRATEGIC_JUMP_INDEX_HOOK = "strategicJumpPointsForDestination";
  private static final String STRATEGIC_JUMP_INDEX_HOOK_DESC = "(Lcom/fs/starfarer/api/campaign/LocationAPI;"
      + "Ljava/lang/Class;" + "Lcom/fs/starfarer/api/campaign/LocationAPI;)Ljava/util/List;";
  private static final String STRATEGIC_JUMP_INVALIDATE_HOOK = "strategicJumpDestinationsChanged";
  private static final String STRATEGIC_JUMP_INVALIDATE_HOOK_DESC = "(Lcom/fs/starfarer/api/campaign/JumpPointAPI;)V";
  private static final String STRATEGIC_JUMP_ADDED_HOOK = "strategicJumpDestinationAdded";
  private static final String STRATEGIC_JUMP_ADDED_HOOK_DESC = "(Lcom/fs/starfarer/api/campaign/JumpPointAPI;"
      + "Lcom/fs/starfarer/api/campaign/JumpPointAPI$JumpDestination;)V";
  private static final String STRATEGIC_JUMP_RETARGET_HOOK = "strategicJumpDestinationRetargeted";
  private static final String STRATEGIC_JUMP_RETARGET_HOOK_DESC =
      "(Lcom/fs/starfarer/api/campaign/JumpPointAPI$JumpDestination;)V";
  private static final String STRATEGIC_JUMP_LOCATION_HOOK = "strategicJumpLocationEntityChanged";
  private static final String STRATEGIC_JUMP_LOCATION_HOOK_DESC =
      "(Lcom/fs/starfarer/api/campaign/LocationAPI;Ljava/lang/Object;)V";
  private static final String STRATEGIC_JUMP_DEFER_HOOK = "strategicJumpDeferExpiredPlan";
  private static final String STRATEGIC_JUMP_DEFER_HOOK_DESC =
      "(Lcom/fs/starfarer/api/campaign/LocationAPI;" + "Lcom/fs/starfarer/api/campaign/LocationAPI;)Z";
  private static final String STRATEGIC_JUMP_UPDATE_METHOD_DESC = "(Lcom/fs/starfarer/api/campaign/LocationAPI;F)V";
  private static final String STRATEGIC_JUMP_METHOD_DESC = "(Lcom/fs/starfarer/api/campaign/CampaignFleetAPI;"
      + "Lcom/fs/starfarer/api/campaign/LocationAPI;)" + "Lcom/fs/starfarer/campaign/ai/StrategicModule$Oo;";
  private static final String HOSTILE_MARKETS_DESC =
      "(Lcom/fs/starfarer/api/campaign/FactionAPI;" + "Lcom/fs/starfarer/api/campaign/SectorEntityToken;F)I";
  private static final String UNSAFE_FLEETS_DESC =
      "(Lcom/fs/starfarer/api/campaign/CampaignFleetAPI;" + "Lcom/fs/starfarer/api/campaign/SectorEntityToken;F)I";
  private static final int STRATEGIC_JUMP_UNCOMPUTED = Integer.MIN_VALUE;
  static final Set<String> OPTIONAL_TARGET_CLASSES = Set.of(NEX_PUNITIVE_EXPEDITION_MANAGER);
  private static final String ENTITY_TOKEN_DESC = "Lcom/fs/starfarer/api/campaign/SectorEntityToken;";
  private static final String MAP_API_DESC = "Lcom/fs/starfarer/api/ui/SectorMapAPI;";
  private static final String INTEL_DESC = "Lcom/fs/starfarer/api/campaign/comm/IntelInfoPlugin;";
  private static final String LIST_DESC = "Ljava/util/List;";
  private static final String STAR_SYSTEM_DESC = "Lcom/fs/starfarer/api/campaign/StarSystemAPI;";
  private static final String LOCATION_DESC = "Lcom/fs/starfarer/api/campaign/LocationAPI;";
  private static final String HYPERSPACE_DESC = "Lcom/fs/starfarer/campaign/Hyperspace;";
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
        apply(state, "mapRenderStuff", config.mapRenderStuff, this::patchHRenderStuff);
        apply(state, "labelSpatialCandidates", config.labelSpatialCandidates, this::patchLabelCandidates);
        apply(state, "intelEntityIndex", config.intelEntityIndex, this::patchIntelEntityIndex);
        apply(state, "systemNebulaCache", config.systemNebulaCache, this::patchSystemNebulaCache);
        apply(state, "sampleCacheClearThrottle", config.sampleCacheClearThrottle, this::patchSampleCacheClearThrottle);
        apply(state, "gridLineCap", config.gridLineCap, structured(new GridLineCap()));
      }
      case A -> {
        apply(state, "intelCallbackCache", config.intelCallbackCache, node -> patchIntelMapLocationCalls(node, 1));
        // The complete hit-test method is one transformation surface. Keep the wrapper last
        // so independent call-site patches are applied to the preserved original body.
        apply(state, "mapHitTest", config.mapHitTest, structured(new MapHitTest()));
      }
      case Z -> apply(state, "intelArrowRendering", config.intelArrowRendering, this::patchIntelArrowRendering);
      case EVENTS -> {
        apply(state, "intelCallbackCache", config.intelCallbackCache, node -> patchIntelMapLocationCalls(node, 7));
        apply(state, "intelReconciliation", config.intelReconciliation, this::patchIntelReconciliation);
      }
      case CAMPAIGN_STATE ->
        apply(state, "marketScheduler", config.marketScheduler, structured(new CampaignStateRenderBatchProtocol()));
      case CAMPAIGN_ENGINE -> {
        // The incremental core-worlds contract spans three independent classes.
        // This CampaignEngine surface owns authoritative system add/remove events.
        apply(state, CORE_WORLDS_PATCH_ID, config.coreWorldsExtentCache, this::patchCoreWorldsTopologyHooks);
        apply(state, "campaignCacheLifecycle", campaignCacheLifecycleEnabled(), this::patchCampaignCacheLifecycle);
        apply(state, "marketScheduler", config.marketScheduler, structured(new CampaignEngineMarketSchedulerTick()));
        // Explicit overlapping-surface order: the entity-index repair owns the
        // post-listener boundary first; the throttle then replaces that boundary and
        // the generic composition gate revalidates the repair against the new call.
        apply(state, "entityLookupIndexRepair", config.entityLookupIndexRepair, this::patchEntityLookupIndexRepair);
        apply(state, "campaignListenerThrottle", config.campaignListenerThrottle, this::patchCampaignListenerThrottle);
      }
      case COURSE_WIDGET ->
        apply(state, "routeJumpPointIndex", config.routeJumpPointIndex, this::patchRouteJumpPointIndex);
      case STRATEGIC_MODULE -> {
        // Explicit dependency order: the index matcher consumes and revalidates
        // the destination-first post-state produced by the preceding patch.
        apply(state, STRATEGIC_JUMP_DESTINATION_FIRST_PATCH_ID, config.strategicJumpDestinationFirst,
            this::patchStrategicJumpDestinationFirst);
        apply(state, STRATEGIC_JUMP_DESTINATION_INDEX_PATCH_ID, config.strategicJumpDestinationIndex,
            this::patchStrategicJumpDestinationIndex);
      }
      case JUMP_POINT -> apply(state, STRATEGIC_JUMP_DESTINATION_INDEX_PATCH_ID, config.strategicJumpDestinationIndex,
          this::patchStrategicJumpDestinationInvalidation);
      case JUMP_DESTINATION -> apply(state, STRATEGIC_JUMP_DESTINATION_INDEX_PATCH_ID,
          config.strategicJumpDestinationIndex, this::patchStrategicJumpDestinationRetarget);
      case BASE_LOCATION -> {
        // BaseLocation owns authoritative addTag/removeTag/clearTags events.
        apply(state, CORE_WORLDS_PATCH_ID, config.coreWorldsExtentCache, this::patchCoreWorldsTagHooks);
        apply(state, "campaignSnapshotReuse", config.campaignSnapshotReuse, this::patchCampaignSnapshotReuse);
        apply(state, STRATEGIC_JUMP_DESTINATION_INDEX_PATCH_ID, config.strategicJumpDestinationIndex,
            this::patchStrategicJumpLocationMutation);
      }
      case BASE_CAMPAIGN_ENTITY -> {
        apply(state, "entityScriptSnapshotReuse", config.entityScriptSnapshotReuse,
            structured(new EntityScriptSnapshotReuse()));
        apply(state, "marketScheduler", config.marketScheduler, structured(new EntityMarketSchedulerSource()));
      }
      case MEMORY -> apply(state, "emptyMemoryAdvanceFastPath", config.emptyMemoryAdvanceFastPath,
          this::patchEmptyMemoryAdvanceFastPath);
      case CORE_SCRIPT ->
        apply(state, CORE_WORLDS_PATCH_ID, config.coreWorldsExtentCache, this::patchCoreWorldsExtentCache);
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
            this::patchMarketShareLinearAggregation);
        apply(state, MARKET_SHARE_DATA_PUT_ELISION_PATCH_ID, config.marketShareDataPutElision,
            this::patchMarketShareDataPutElision);
      }
      case PUNITIVE_EXPEDITION_MANAGER -> apply(state, PUNITIVE_PLAYER_SHARE_PATCH_ID,
          config.punitivePlayerShareLocalCache, this::patchPunitivePlayerShareLocalCache);
      case NEX_PUNITIVE_EXPEDITION_MANAGER -> apply(state, PUNITIVE_PLAYER_SHARE_PATCH_ID,
          config.nexPunitivePlayerShareLocalCache, this::patchPunitivePlayerShareLocalCache);
      case BASE_INDUSTRY -> {
        apply(state, AOTD_DEFICIT_PATCH_ID, config.aotdCleanDeficitPath, this::patchAoTDCleanDeficitPath);
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
        apply(state, "commRelaySystemIndex", config.commRelaySystemIndex, this::patchCommRelaySystemIndex);
      case SHIP -> apply(state, "shipAdvanceScratch", config.shipAdvanceScratch, this::patchShipAdvanceScratch);
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
        apply(state, "rulesLiteralParser", config.rulesLiteralParser, this::patchRulesLiteralParser);
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
        apply(state, SAVE_OUTPUT_BUFFER_DEDUP_PATCH_ID, config.saveOutputBufferDedup, this::patchSaveOutputBufferDedup);
      }
      case PLANET_SURVEY_PANEL, LUDDIC_PATH_BASE_INTEL, PIRATE_BASE_INTEL, DECIV_TRACKER, PK_CMD ->
        apply(state, "marketScheduler", config.marketScheduler, structured(new CoreMarketEventSynchronization()));
      case HyperspacePatches.BASE_TILED -> {
        applyHyperspace(state, "hyperspaceViewportBounds", config.hyperspaceViewportBounds, "viewportBounds", 2,
            HyperspacePatches::patchViewportBounds, HyperspacePatches::countPatchedViewportBounds);
        applyHyperspace(state, "terrainRandomReuse", config.terrainRandomReuse, "random", 3,
            HyperspacePatches::patchRandom, HyperspacePatches::countPatchedRandom);
      }
      case HyperspacePatches.HYPER_TERRAIN -> {
        applyHyperspace(state, "skipNoOpTerrainLayer", config.skipNoOpTerrainLayer, "noOpLayer", 1,
            HyperspacePatches::patchLayers, HyperspacePatches::countPatchedLayers);
        applyHyperspace(state, "terrainRandomReuse", config.terrainRandomReuse, "random", 1,
            HyperspacePatches::patchRandom, HyperspacePatches::countPatchedRandom);
        applyHyperspace(state, "automatonInternalReads", config.automatonBufferReuse, "internalCells", 2,
            HyperspacePatches::patchAutomatonInternalReads, HyperspacePatches::countPatchedAutomatonInternalReads);
      }
      case HyperspacePatches.AUTOMATON -> applyHyperspace(state, "automatonBufferReuse", config.automatonBufferReuse,
          "automatonBuffers", 1, HyperspacePatches::patchAutomaton, HyperspacePatches::countPatchedAutomaton);
      case HyperspacePatches.STARFIELD ->
        applyHyperspace(state, "starfieldCleanupBuffers", config.starfieldCleanupBuffers, "cleanup", 1,
            HyperspacePatches::patchCleanup, HyperspacePatches::countPatchedCleanup);
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

  private void applyHyperspace(TransformState state, String patchId, boolean enabled, String detail, int expected,
      CountPatchAction action, CountPatchAction postcondition) {
    apply(state, patchId, enabled, node -> {
      if (hasPatchMarker(node, patchId)) {
        int actual = postcondition.apply(node);
        requireCount(detail + " postcondition sites", actual, expected);
        throw already("structural marker and complete postcondition are present");
      }
      int actual = action.apply(node);
      requireCount(detail + " structural sites", actual, expected);
      PatchReport report = new PatchReport();
      report.add(detail, actual);
      return report;
    });
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
      case A -> config.mapHitTest || config.intelCallbackCache;
      case Z -> config.intelArrowRendering;
      case EVENTS -> config.intelCallbackCache || config.intelReconciliation;
      case CAMPAIGN_STATE -> config.marketScheduler;
      case CAMPAIGN_ENGINE -> config.coreWorldsExtentCache || campaignCacheLifecycleEnabled()
          || config.campaignListenerThrottle || config.entityLookupIndexRepair;
      case COURSE_WIDGET -> config.routeJumpPointIndex;
      case STRATEGIC_MODULE -> config.strategicJumpDestinationFirst || config.strategicJumpDestinationIndex;
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

  private PatchReport patchRulesLiteralParser(ClassNode node) {
    MethodNode method = requireMethod(node, "o00000", "(Lcom/fs/starfarer/loading/ResourceLoaderState;)V");
    List<MethodInsnNode> carriageReturns = new ArrayList<>();
    List<MethodInsnNode> newlineToSpace = new ArrayList<>();
    List<MethodInsnNode> newlineSplits = new ArrayList<>();
    List<MethodInsnNode> orSplits = new ArrayList<>();
    List<MethodInsnNode> colonSplits = new ArrayList<>();

    for (MethodInsnNode call : calls(method, Opcodes.INVOKEVIRTUAL, "java/lang/String", "replaceAll",
        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")) {
      AbstractInsnNode replacement = previousMeaningful(call);
      AbstractInsnNode pattern = previousMeaningful(replacement);
      if (isStringLdc(pattern, "\\r") && isStringLdc(replacement, "")) {
        carriageReturns.add(call);
      } else if (isStringLdc(pattern, "\\n") && isStringLdc(replacement, " ")) {
        newlineToSpace.add(call);
      }
    }
    for (MethodInsnNode call : calls(method, Opcodes.INVOKEVIRTUAL, "java/lang/String", "split",
        "(Ljava/lang/String;)[Ljava/lang/String;")) {
      AbstractInsnNode delimiter = previousMeaningful(call);
      if (isStringLdc(delimiter, "\\n")) {
        newlineSplits.add(call);
      } else if (isStringLdc(delimiter, "\nOR\n")) {
        orSplits.add(call);
      } else if (delimiter instanceof MethodInsnNode quote && callMatches(quote, Opcodes.INVOKESTATIC,
          "java/util/regex/Pattern", "quote", "(Ljava/lang/String;)Ljava/lang/String;")
          && isStringLdc(previousMeaningful(quote), ":")) {
            colonSplits.add(call);
          }
    }

    int crHooks = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "removeCarriageReturns",
        "(Ljava/lang/String;)Ljava/lang/String;");
    int spaceHooks = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "replaceNewlinesWithSpace",
        "(Ljava/lang/String;)Ljava/lang/String;");
    int newlineHooks =
        countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "splitNewlines", "(Ljava/lang/String;)[Ljava/lang/String;");
    int orHooks =
        countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "splitOrBlocks", "(Ljava/lang/String;)[Ljava/lang/String;");
    int colonHooks =
        countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "splitColon", "(Ljava/lang/String;)[Ljava/lang/String;");
    if (carriageReturns.isEmpty() && newlineToSpace.isEmpty() && newlineSplits.isEmpty() && orSplits.isEmpty()
        && colonSplits.isEmpty() && crHooks == 3 && spaceHooks == 1 && newlineHooks == 3 && orHooks == 1
        && colonHooks == 1) {
      throw already("literal rules parser postcondition matches");
    }
    requireCount("rules CR replacements", carriageReturns.size(), 3);
    requireCount("rules newline-to-space replacements", newlineToSpace.size(), 1);
    requireCount("rules newline splits", newlineSplits.size(), 3);
    requireCount("rules OR splits", orSplits.size(), 1);
    requireCount("rules colon splits", colonSplits.size(), 1);
    requireCount("rules CR hooks", crHooks, 0);
    requireCount("rules newline-to-space hooks", spaceHooks, 0);
    requireCount("rules newline hooks", newlineHooks, 0);
    requireCount("rules OR hooks", orHooks, 0);
    requireCount("rules colon hooks", colonHooks, 0);

    for (MethodInsnNode call : carriageReturns) {
      AbstractInsnNode replacement = previousMeaningful(call);
      AbstractInsnNode pattern = previousMeaningful(replacement);
      method.instructions.remove(pattern);
      method.instructions.remove(replacement);
      makeStatic(call, HOOKS, "removeCarriageReturns", "(Ljava/lang/String;)Ljava/lang/String;");
    }
    for (MethodInsnNode call : newlineToSpace) {
      AbstractInsnNode replacement = previousMeaningful(call);
      AbstractInsnNode pattern = previousMeaningful(replacement);
      method.instructions.remove(pattern);
      method.instructions.remove(replacement);
      makeStatic(call, HOOKS, "replaceNewlinesWithSpace", "(Ljava/lang/String;)Ljava/lang/String;");
    }
    for (MethodInsnNode call : newlineSplits) {
      method.instructions.remove(previousMeaningful(call));
      makeStatic(call, HOOKS, "splitNewlines", "(Ljava/lang/String;)[Ljava/lang/String;");
    }
    for (MethodInsnNode call : orSplits) {
      method.instructions.remove(previousMeaningful(call));
      makeStatic(call, HOOKS, "splitOrBlocks", "(Ljava/lang/String;)[Ljava/lang/String;");
    }
    for (MethodInsnNode call : colonSplits) {
      AbstractInsnNode quote = previousMeaningful(call);
      AbstractInsnNode colon = previousMeaningful(quote);
      method.instructions.remove(colon);
      method.instructions.remove(quote);
      makeStatic(call, HOOKS, "splitColon", "(Ljava/lang/String;)[Ljava/lang/String;");
    }

    PatchReport report = new PatchReport();
    report.add("removeCR", carriageReturns.size());
    report.add("newlineToSpace", newlineToSpace.size());
    report.add("splitNewline", newlineSplits.size());
    report.add("splitOR", orSplits.size());
    report.add("splitColon", colonSplits.size());
    return report;
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


  private PatchReport patchSaveOutputBufferDedup(ClassNode node) {
    MethodNode method =
        requireMethod(node, "o00000", "(Lcom/fs/starfarer/campaign/CampaignEngine$o;JZ)Ljava/lang/String;");
    List<BufferConstruction> plans = new ArrayList<>();
    for (MethodInsnNode call : calls(method, Opcodes.INVOKESPECIAL, "java/io/BufferedOutputStream", "<init>",
        "(Ljava/io/OutputStream;I)V")) {
      AbstractInsnNode size = previousMeaningful(call);
      AbstractInsnNode stream = previousMeaningful(size);
      AbstractInsnNode duplicate = previousMeaningful(stream);
      AbstractInsnNode allocation = previousMeaningful(duplicate);
      AbstractInsnNode store = nextMeaningful(call);
      if (!(size instanceof LdcInsnNode ldc) || !Integer.valueOf(1_048_576).equals(ldc.cst)) continue;
      if (!(stream instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD) continue;
      if (duplicate == null || duplicate.getOpcode() != Opcodes.DUP) continue;
      if (!(allocation instanceof TypeInsnNode type) || type.getOpcode() != Opcodes.NEW
          || !type.desc.equals("java/io/BufferedOutputStream"))
        continue;
      if (!(store instanceof VarInsnNode target) || target.getOpcode() != Opcodes.ASTORE || target.var != load.var)
        continue;
      requireNoControlBoundary(allocation, store, "save output buffer construction");
      plans.add(new BufferConstruction(allocation, store));
    }
    if (plans.isEmpty()) throw already("outer save buffer is already absent");
    requireCount("redundant outer save buffers", plans.size(), 1);
    removeRange(method, plans.get(0).start, plans.get(0).end);
    PatchReport report = new PatchReport();
    report.add("outer1MiBBufferRemoved", 1);
    return report;
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


  private static MethodNode findMethod(ClassNode node, AbstractInsnNode target) {
    for (MethodNode method : node.methods) {
      if (method.instructions.indexOf(target) >= 0) return method;
    }
    throw mismatch("instruction is not owned by the target class");
  }


  // ---------------------------------------------------------------------
  // H: core map renderer
  // ---------------------------------------------------------------------

  /**
   * Atomically installs the complete renderStuff optimization surface: the semantic map
   * reconciliation hook, both reusable collection hooks, and their single reentrant scratch
   * scope. A mixed or partial state rejects the whole group so one sub-optimization can never
   * silently survive without the other two.
   */
  private PatchReport patchHRenderStuff(ClassNode node) {
    MethodNode method = requireMethod(node, "renderStuff", "(FZ)V");
    String hookDesc = "(Ljava/util/Set;Ljava/util/Collection;Ljava/lang/Object;)Z";
    List<AllocationSpec> specs = hRenderStuffAllocationSpecs();
    PatchState scopeState = scratchScopeState(node.name, method, "H.renderStuff");
    PatchState mutationState = hRenderScratchMutationState(method);

    List<MethodInsnNode> rawCalls =
        calls(method, Opcodes.INVOKEINTERFACE, "java/util/Set", "retainAll", "(Ljava/util/Collection;)Z");
    Frame<SourceValue>[] frames = sourceFrames(node.name, method);
    List<MethodInsnNode> originalRetain = new ArrayList<>();
    for (MethodInsnNode candidate : rawCalls) {
      if (isMapReconciliationSite(node, method, candidate, frames)) {
        originalRetain.add(candidate);
      }
    }
    List<MethodInsnNode> retainHooks = calls(method, Opcodes.INVOKESTATIC, HOOKS, "retainAllFast", hookDesc);
    boolean retainOriginal = originalRetain.size() == 1 && retainHooks.isEmpty();
    boolean retainPatched = originalRetain.isEmpty() && retainHooks.size() == 1;

    boolean allocationsOriginal = true;
    boolean allocationsPatched = true;
    List<List<AllocationPlan>> plans = new ArrayList<>();
    List<List<Integer>> hookedLocals = new ArrayList<>();
    for (AllocationSpec spec : specs) {
      List<AllocationPlan> found = hRenderStuffAllocationPlans(node.name, method, spec);
      List<Integer> locals = storedHookResultLocals(method, spec);
      allocationsOriginal &= found.size() == spec.expected() && locals.isEmpty();
      allocationsPatched &= found.isEmpty() && locals.size() == spec.expected();
      plans.add(found);
      hookedLocals.add(locals);
    }

    if (retainPatched && allocationsPatched) {
      if (scopeState != PatchState.PATCHED || mutationState != PatchState.PATCHED) {
        throw mismatch("H.renderStuff hooks exist without the complete scratch scope");
      }
      validatePatchedHRenderStuff(node, method, retainHooks.get(0), specs, hookedLocals);
      throw already("H.renderStuff map reconciliation, scratch collections, and scope " + "are already present");
    }

    if (!retainOriginal || !allocationsOriginal) {
      throw mismatch("H.renderStuff has mixed, missing, or ambiguous reconciliation/" + "allocation sites");
    }
    if (scopeState != PatchState.ORIGINAL || mutationState != PatchState.ORIGINAL) {
      throw mismatch("H.renderStuff has a scope/mutation hook without the complete owned group");
    }

    // Validate every scratch local before changing any instruction in the shared method.
    for (int i = 0; i < specs.size(); i++) {
      AllocationSpec spec = specs.get(i);
      for (int j = 0; j < plans.get(i).size(); j++) {
        requireScratchUseContract(node.name, method, plans.get(i).get(j).local(), spec, j, "H.renderStuff");
      }
    }

    applyHRetainAll(node);
    applyHRenderScratchCollections(method, specs, plans);
    int observedMutations = replaceHRenderScratchMutations(method);

    PatchReport report = new PatchReport();
    report.add("linear map reconciliation", 1);
    report.add("render scratch collections", 2);
    report.add("render scratch peak observations", observedMutations);
    return report;
  }

  private static PatchState hRenderScratchMutationState(MethodNode method) {
    int raw = countCalls(method, Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z");
    int hooks =
        countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "scratchSetAdd", "(Ljava/util/Set;Ljava/lang/Object;)Z");
    boolean original = raw == 7 && hooks == 0;
    boolean patched = raw == 0 && hooks == 7;
    if (!original && !patched) { throw mismatch("H.renderStuff scratch-set mutation sites are partial or changed"); }
    return original ? PatchState.ORIGINAL : PatchState.PATCHED;
  }

  private static int replaceHRenderScratchMutations(MethodNode method) {
    AllocationSpec setSpec = hRenderStuffAllocationSpecs().get(1);
    List<Integer> locals = storedHookResultLocals(method, setSpec);
    if (locals.size() != 1) throw mismatch("H.renderStuff scratch-set local is ambiguous");
    int local = locals.get(0);
    Frame<SourceValue>[] frames = sourceFrames(H, method);
    int changed = 0;
    for (MethodInsnNode call : new ArrayList<>(
        calls(method, Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z"))) {
      if (!sourceIsLocal(receiverSource(method, call, frames), Opcodes.ALOAD, local)) {
        throw mismatch("H.renderStuff Set.add receiver escaped the owned scratch local");
      }
      makeStatic(call, HOOKS, "scratchSetAdd", "(Ljava/util/Set;Ljava/lang/Object;)Z");
      changed++;
    }
    if (changed != 7 || hRenderScratchMutationState(method) != PatchState.PATCHED) {
      throw mismatch("H.renderStuff scratch-set peak hooks failed postcondition");
    }
    return changed;
  }

  private static List<AllocationSpec> hRenderStuffAllocationSpecs() {
    return List.of(
        new AllocationSpec("java/util/ArrayList", "(Ljava/util/Collection;)V", "borrowEntityList",
            "(Ljava/util/Collection;)Ljava/util/ArrayList;", 1),
        new AllocationSpec("java/util/HashSet", "()V", "borrowClassSet", "()Ljava/util/HashSet;", 1));
  }

  private static List<AllocationPlan> hRenderStuffAllocationPlans(String owner, MethodNode method,
      AllocationSpec spec) {
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    List<AllocationPlan> result = new ArrayList<>();
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!(insn instanceof TypeInsnNode allocation) || allocation.getOpcode() != Opcodes.NEW
          || !allocation.desc.equals(spec.type()))
        continue;
      AbstractInsnNode duplicate = nextMeaningful(allocation);
      if (duplicate == null || duplicate.getOpcode() != Opcodes.DUP) continue;

      MethodInsnNode constructor = null;
      for (AbstractInsnNode cursor = duplicate.getNext(); cursor != null; cursor = cursor.getNext()) {
        if (cursor instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
            && call.owner.equals(spec.type()) && call.name.equals("<init>")
            && call.desc.equals(spec.constructorDesc())) {
          SourceValue receiver = receiverSource(method, call, frames);
          if (sourceContains(receiver, duplicate)) constructor = call;
          break;
        }
        if (cursor.getOpcode() == Opcodes.NEW && cursor != allocation) break;
      }
      if (constructor == null) continue;
      AbstractInsnNode storeInsn = nextMeaningful(constructor);
      if (!(storeInsn instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE
          || countStores(method, store.var) != 1) {
        continue;
      }
      try {
        requireScratchUseContract(owner, method, store.var, spec, result.size(), "H.renderStuff candidate");
        result.add(new AllocationPlan(allocation, duplicate, constructor, store.var));
      } catch (StructuralMismatchException ignored) {
        // A same-type allocation with a different use contract is unrelated to this surface.
      }
    }
    return result;
  }

  private static void applyHRenderScratchCollections(MethodNode method, List<AllocationSpec> specs,
      List<List<AllocationPlan>> plans) {
    for (int i = 0; i < specs.size(); i++) {
      AllocationSpec spec = specs.get(i);
      for (AllocationPlan plan : plans.get(i)) {
        method.instructions.remove(plan.allocation());
        method.instructions.remove(plan.duplicate());
        makeStatic(plan.constructor(), HOOKS, spec.hookName(), spec.hookDesc());
      }
      requireCount("H.renderStuff postcondition " + spec.hookName(),
          countCalls(method, Opcodes.INVOKESTATIC, HOOKS, spec.hookName(), spec.hookDesc()), spec.expected());
    }
  }

  private static void validatePatchedHRenderStuff(ClassNode node, MethodNode method, MethodInsnNode retainHook,
      List<AllocationSpec> specs, List<List<Integer>> hookedLocals) {
    Frame<SourceValue>[] frames = sourceFrames(node.name, method);
    MethodInsnNode keySet = requireSourceMethod(argumentSource(method, retainHook, frames, 0), Opcodes.INVOKEVIRTUAL,
        "java/util/LinkedHashMap", "keySet", "()Ljava/util/Set;", "retainAllFast set argument");
    FieldInsnNode iconsField = requireSourceField(receiverSource(method, keySet, frames), Opcodes.GETFIELD, node.name,
        LINKED_MAP_DESC, "icons map receiver");
    requireIconsGetter(node, iconsField.name);

    int entityListLocal = requireLoadedLocal(argumentSource(method, retainHook, frames, 1), Opcodes.ALOAD,
        "retainAllFast keep collection");
    if (hookedLocals.get(0).size() != 1 || hookedLocals.get(0).get(0) != entityListLocal) {
      throw mismatch("retainAllFast does not consume the owned borrowEntityList local");
    }
    int ownerLocal =
        requireLoadedLocal(argumentSource(method, retainHook, frames, 2), Opcodes.ALOAD, "retainAllFast owner");
    if (ownerLocal != 0) { throw mismatch("retainAllFast owner is not H.this"); }
    if (nextMeaningful(retainHook) == null || nextMeaningful(retainHook).getOpcode() != Opcodes.POP) {
      throw mismatch("retainAllFast boolean result is not discarded as expected");
    }

    for (int i = 0; i < specs.size(); i++) {
      AllocationSpec spec = specs.get(i);
      for (int j = 0; j < hookedLocals.get(i).size(); j++) {
        requireScratchUseContract(node.name, method, hookedLocals.get(i).get(j), spec, j, "H.renderStuff");
      }
    }
  }

  private PatchReport applyHRetainAll(ClassNode node) {
    MethodNode method = requireMethod(node, "renderStuff", "(FZ)V");
    boolean scopeInstalled = ensureScratchScope(node.name, method, "H.renderStuff");
    String hookDesc = "(Ljava/util/Set;Ljava/util/Collection;Ljava/lang/Object;)Z";
    List<MethodInsnNode> rawCalls =
        calls(method, Opcodes.INVOKEINTERFACE, "java/util/Set", "retainAll", "(Ljava/util/Collection;)Z");
    int hooks = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "retainAllFast", hookDesc);
    Frame<SourceValue>[] frames = sourceFrames(node.name, method);
    List<MethodInsnNode> original = new ArrayList<>();
    for (MethodInsnNode candidate : rawCalls) {
      if (isMapReconciliationSite(node, method, candidate, frames)) original.add(candidate);
    }
    if (scopeInstalled && original.isEmpty() && hooks == 1) {
      throw mismatch("H.renderStuff retainAll hook exists without its required scratch scope");
    }
    requireUnpatchedOrAlready("H.renderStuff semantic retainAll", original.size(), 1, hooks, 1);

    MethodInsnNode call = only(original, "H.renderStuff semantic retainAll");
    MethodInsnNode keySet = requireSourceMethod(receiverSource(method, call, frames), Opcodes.INVOKEVIRTUAL,
        "java/util/LinkedHashMap", "keySet", "()Ljava/util/Set;", "retainAll receiver");
    FieldInsnNode iconsField = requireSourceField(receiverSource(method, keySet, frames), Opcodes.GETFIELD, node.name,
        LINKED_MAP_DESC, "icons map receiver");
    requireIconsGetter(node, iconsField.name);

    int entityListLocal =
        requireLoadedLocal(argumentSource(method, call, frames, 0), Opcodes.ALOAD, "retainAll keep collection");
    AbstractInsnNode beforeCall = previousMeaningful(call);
    if (!(beforeCall instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != entityListLocal) {
      throw mismatch("retainAll keep collection is not loaded directly from its proven local");
    }
    requireLocalConstructor(method, entityListLocal, "java/util/ArrayList", "(Ljava/util/Collection;)V",
        "render entity list");
    if (nextMeaningful(call) == null || nextMeaningful(call).getOpcode() != Opcodes.POP) {
      throw mismatch("retainAll boolean result is not discarded as expected");
    }

    method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 0));
    makeStatic(call, HOOKS, "retainAllFast", hookDesc);
    requireCount("retainAllFast postcondition",
        countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "retainAllFast", hookDesc), 1);
    requireCount("non-target retainAll preservation",
        calls(method, Opcodes.INVOKEINTERFACE, "java/util/Set", "retainAll", "(Ljava/util/Collection;)Z").size(),
        rawCalls.size() - 1);

    PatchReport report = new PatchReport();
    report.add("O(K*E) retainAll -> linear reconciliation", 1);
    return report;
  }

  private static boolean isMapReconciliationSite(ClassNode node, MethodNode method, MethodInsnNode call,
      Frame<SourceValue>[] frames) {
    try {
      MethodInsnNode keySet = requireSourceMethod(receiverSource(method, call, frames), Opcodes.INVOKEVIRTUAL,
          "java/util/LinkedHashMap", "keySet", "()Ljava/util/Set;", "retainAll receiver");
      FieldInsnNode iconsField = requireSourceField(receiverSource(method, keySet, frames), Opcodes.GETFIELD, node.name,
          LINKED_MAP_DESC, "icons map receiver");
      requireIconsGetter(node, iconsField.name);
      int entityListLocal =
          requireLoadedLocal(argumentSource(method, call, frames, 0), Opcodes.ALOAD, "retainAll keep collection");
      requireLocalConstructor(method, entityListLocal, "java/util/ArrayList", "(Ljava/util/Collection;)V",
          "render entity list");
      return nextMeaningful(call) != null && nextMeaningful(call).getOpcode() == Opcodes.POP;
    } catch (StructuralMismatchException ex) {
      return false;
    }
  }

  private PatchReport patchLabelCandidates(ClassNode node) {
    MethodNode method = requireMethod(node, "getTextAlignmentFor",
        "(Lcom/fs/starfarer/coreui/A/ooOO;)Lcom/fs/starfarer/api/ui/Alignment;");
    boolean scopeInstalled = ensureScratchScope(node.name, method, "H.getTextAlignmentFor");
    String hookDesc = "(Ljava/util/LinkedHashMap;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Collection;";
    List<MethodInsnNode> original =
        calls(method, Opcodes.INVOKEVIRTUAL, "java/util/LinkedHashMap", "values", "()Ljava/util/Collection;");
    int hooks = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "nearbyLabelIcons", hookDesc);
    if (scopeInstalled && original.isEmpty() && hooks == 1) {
      throw mismatch("H.getTextAlignmentFor label hook exists without its required scratch scope");
    }
    requireUnpatchedOrAlready("H.getTextAlignmentFor values", original.size(), 1, hooks, 1);

    MethodInsnNode values = only(original, "H.getTextAlignmentFor values");
    Frame<SourceValue>[] frames = sourceFrames(node.name, method);
    FieldInsnNode iconsField = requireSourceField(receiverSource(method, values, frames), Opcodes.GETFIELD, node.name,
        LINKED_MAP_DESC, "label icons receiver");
    requireIconsGetter(node, iconsField.name);
    MethodInsnNode iterator = asMethod(nextMeaningful(values), "label values iterator");
    requireCall(iterator, Opcodes.INVOKEINTERFACE, "java/util/Collection", "iterator", "()Ljava/util/Iterator;",
        "label values iterator");
    if (countCalls(method, -1, "com/fs/starfarer/coreui/A/ooOO", "int", "()" + ENTITY_TOKEN_DESC) < 1) {
      throw mismatch("label icon SectorEntityToken accessor is absent");
    }

    int targetLocal = argumentLocal(method, 0);
    method.instructions.insertBefore(values, new VarInsnNode(Opcodes.ALOAD, 0));
    method.instructions.insertBefore(values, new VarInsnNode(Opcodes.ALOAD, targetLocal));
    makeStatic(values, HOOKS, "nearbyLabelIcons", hookDesc);

    PatchReport report = new PatchReport();
    report.add("label spatial candidates", 1);
    return report;
  }

  private PatchReport patchIntelEntityIndex(ClassNode node) {
    String desc = "(" + INTEL_DESC + ")Lcom/fs/starfarer/campaign/CustomCampaignEntity;";
    String renamed = "smo$originalGetIntelIconEntity";
    String hookDesc = "(Ljava/lang/Object;Ljava/util/List;" + INTEL_DESC + ")Ljava/lang/Object;";
    boolean wrapperAlready = methods(node, renamed, desc).size() == 1;
    MethodNode original =
        requireWrapperSource(node, "getIntelIconEntity", desc, renamed, "getIntelIconEntityIndexed", hookDesc);

    if (containsWrite(original)) {
      throw mismatch("getIntelIconEntity has field/static writes and is not a pure lookup");
    }
    requireCount("getIntelIconEntity semantic call count", totalMethodCalls(original), 5);
    Frame<SourceValue>[] frames = sourceFrames(node.name, original);
    Set<String> listFields = new LinkedHashSet<>();
    for (MethodInsnNode iterator : calls(original, Opcodes.INVOKEINTERFACE, "java/util/List", "iterator",
        "()Ljava/util/Iterator;")) {
      FieldInsnNode field =
          optionalSourceField(receiverSource(original, iterator, frames), Opcodes.GETFIELD, node.name, LIST_DESC);
      if (field != null) listFields.add(field.name);
    }
    if (listFields.size() != 1) { throw mismatch("expected one iterated Intel-data List field, found " + listFields); }
    String listField = listFields.iterator().next();
    requireCount("CustomCampaignEntity.getCustomData", countCalls(original, Opcodes.INVOKEVIRTUAL,
        "com/fs/starfarer/campaign/CustomCampaignEntity", "getCustomData", "()Ljava/util/Map;"), 1);
    requireCount("Intel custom-data Map.get",
        countCalls(original, Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;"),
        1);
    requireCount("INTEL_ICON_DATA_KEY reads",
        countFields(original, Opcodes.GETSTATIC, node.name, "INTEL_ICON_DATA_KEY", "Ljava/lang/String;"), 1);

    int pluginArg = argumentLocal(original, 0);
    int identityComparisons = 0;
    for (AbstractInsnNode insn : original.instructions.toArray()) {
      if (!(insn instanceof JumpInsnNode jump)
          || (jump.getOpcode() != Opcodes.IF_ACMPEQ && jump.getOpcode() != Opcodes.IF_ACMPNE))
        continue;
      Frame<SourceValue> frame = frameBefore(original, jump, frames);
      if (frame.getStackSize() < 2) continue;
      SourceValue left = frame.getStack(frame.getStackSize() - 2);
      SourceValue right = frame.getStack(frame.getStackSize() - 1);
      boolean match = (sourceIsFieldDesc(left, INTEL_DESC) && sourceIsLocal(right, Opcodes.ALOAD, pluginArg))
          || (sourceIsFieldDesc(right, INTEL_DESC) && sourceIsLocal(left, Opcodes.ALOAD, pluginArg));
      if (match) identityComparisons++;
    }
    requireCount("Intel plugin identity comparison", identityComparisons, 1);
    requireNullReturn(original, "getIntelIconEntity");
    if (wrapperAlready) {
      requireIntelEntityWrapper(node, desc, listField, hookDesc);
      throw already("getIntelIconEntity wrapper, original semantics, and field wiring match");
    }

    int originalAccess = original.access;
    String signature = original.signature;
    String[] exceptions = exceptions(original);
    original.name = renamed;
    makePrivateSynthetic(original);

    MethodNode wrapper =
        new MethodNode(Opcodes.ASM8, originalAccess, "getIntelIconEntity", desc, signature, exceptions);
    moveWrapperMetadata(original, wrapper, "getIntelIconEntity");
    wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
    wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
    wrapper.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, listField, LIST_DESC));
    wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, argumentLocal(wrapper, 0)));
    wrapper.instructions
        .add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "getIntelIconEntityIndexed", hookDesc, false));
    wrapper.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "com/fs/starfarer/campaign/CustomCampaignEntity"));
    wrapper.instructions.add(new InsnNode(Opcodes.ARETURN));
    node.methods.add(wrapper);
    requireWrapperPostcondition(node, "getIntelIconEntity", desc, renamed, "getIntelIconEntityIndexed", hookDesc);
    requireIntelEntityWrapper(node, desc, listField, hookDesc);

    PatchReport report = new PatchReport();
    report.add("Intel entity identity index", 1);
    return report;
  }

  private PatchReport patchSystemNebulaCache(ClassNode node) {
    String desc = "()V";
    String renamed = "smo$originalUpdateSystemNebulas";
    String hookDesc = "(Ljava/lang/Object;Ljava/util/List;Ljava/util/List;Ljava/util/List;)V";
    boolean wrapperAlready = methods(node, renamed, desc).size() == 1;
    MethodNode original =
        requireWrapperSource(node, "updateSystemNebulas", desc, renamed, "updateSystemNebulasCached", hookDesc);
    Frame<SourceValue>[] frames = sourceFrames(node.name, original);
    requireCount("updateSystemNebulas semantic call count", totalMethodCalls(original), 57);

    String createNebulaDesc = "(" + STAR_SYSTEM_DESC + ")Lcom/fs/starfarer/campaign/CampaignTerrain;";
    String createLabelDesc = "(Lcom/fs/starfarer/api/impl/campaign/procgen/Constellation;)"
        + "Lcom/fs/starfarer/campaign/CustomCampaignEntity;";
    requireMethod(node, "createNebula", createNebulaDesc);
    requireMethod(node, "createConstellationLabel", createLabelDesc);
    int nebulaLocal = requireStoredCallResultLocal(original, Opcodes.INVOKESTATIC, node.name, "createNebula",
        createNebulaDesc, "system nebula result");
    int labelLocal = requireStoredCallResultLocal(original, Opcodes.INVOKESTATIC, node.name, "createConstellationLabel",
        createLabelDesc, "constellation label result");

    String systemNebulas = null;
    String constellationLabels = null;
    String nebulaStars = null;
    List<MethodInsnNode> adds =
        calls(original, Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z");
    requireCount("updateSystemNebulas List.add sites", adds.size(), 3);
    for (MethodInsnNode add : adds) {
      FieldInsnNode field = requireSourceField(receiverSource(original, add, frames), Opcodes.GETFIELD, node.name,
          LIST_DESC, "system-nebula output list");
      SourceValue value = argumentSource(original, add, frames, 0);
      if (sourceIsLocal(value, Opcodes.ALOAD, nebulaLocal)) {
        systemNebulas = uniqueRole(systemNebulas, field.name, "system nebula list");
      } else if (sourceIsLocal(value, Opcodes.ALOAD, labelLocal)) {
        constellationLabels = uniqueRole(constellationLabels, field.name, "constellation label list");
      } else if (hasCallImmediatelyBefore(add, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/StarSystemAPI",
          "getStar", "()Lcom/fs/starfarer/api/campaign/PlanetAPI;", 4)) {
            nebulaStars = uniqueRole(nebulaStars, field.name, "nebula-star list");
          } else {
            throw mismatch("unable to assign semantic role to List.add in updateSystemNebulas");
          }
    }
    if (systemNebulas == null || constellationLabels == null || nebulaStars == null
        || Set.of(systemNebulas, constellationLabels, nebulaStars).size() != 3) {
      throw mismatch("system-nebula output lists are missing or not distinct");
    }
    for (String field : List.of(systemNebulas, constellationLabels, nebulaStars)) {
      int clears = countCallsWithFieldReceiver(node.name, original, frames, Opcodes.INVOKEINTERFACE, "java/util/List",
          "clear", "()V", field, LIST_DESC);
      requireCount("clear of system-nebula list " + field, clears, 1);
    }
    if (wrapperAlready) {
      requireSystemNebulaWrapper(node, desc, List.of(systemNebulas, constellationLabels, nebulaStars), hookDesc);
      throw already("updateSystemNebulas wrapper, original semantics, and field wiring match");
    }

    int originalAccess = original.access;
    String signature = original.signature;
    String[] exceptions = exceptions(original);
    original.name = renamed;
    makePrivateSynthetic(original);
    MethodNode wrapper =
        new MethodNode(Opcodes.ASM8, originalAccess, "updateSystemNebulas", desc, signature, exceptions);
    moveWrapperMetadata(original, wrapper, "updateSystemNebulas");
    wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
    for (String field : List.of(systemNebulas, constellationLabels, nebulaStars)) {
      wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
      wrapper.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, field, LIST_DESC));
    }
    wrapper.instructions
        .add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "updateSystemNebulasCached", hookDesc, false));
    wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
    node.methods.add(wrapper);
    requireWrapperPostcondition(node, "updateSystemNebulas", desc, renamed, "updateSystemNebulasCached", hookDesc);
    requireSystemNebulaWrapper(node, desc, List.of(systemNebulas, constellationLabels, nebulaStars), hookDesc);

    PatchReport report = new PatchReport();
    report.add("system-nebula preprocessing cache", 1);
    return report;
  }

  private PatchReport patchSampleCacheClearThrottle(ClassNode node) {
    MethodNode method = requireMethod(node, "<init>", "(Z)V");
    String terrain = "com/fs/starfarer/api/impl/campaign/terrain/HyperspaceTerrainPlugin";
    String hookDesc = "(Ljava/lang/Object;)V";
    List<MethodInsnNode> original = calls(method, Opcodes.INVOKEVIRTUAL, terrain, "forceClearSampleCache", "()V");
    int hooks = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "forceClearSampleCacheThrottled", hookDesc);
    requireUnpatchedOrAlready("H constructor forceClearSampleCache", original.size(), 1, hooks, 1);

    MethodInsnNode call = only(original, "forceClearSampleCache");
    Frame<SourceValue>[] frames = sourceFrames(node.name, method);
    int pluginLocal =
        requireLoadedLocal(receiverSource(method, call, frames), Opcodes.ALOAD, "hyperspace terrain plugin");
    MethodInsnNode getter = only(calls(method, Opcodes.INVOKESTATIC, "com/fs/starfarer/api/util/Misc",
        "getHyperspaceTerrainPlugin", "()L" + terrain + ";"), "getHyperspaceTerrainPlugin");
    AbstractInsnNode stored = nextMeaningful(getter);
    if (!(stored instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE || store.var != pluginLocal) {
      throw mismatch("terrain plugin receiver is not the value returned by Misc.getHyperspaceTerrainPlugin");
    }
    if (!hasNullGuard(method, store, call, pluginLocal)) {
      throw mismatch("terrain plugin call is not protected by the expected null guard");
    }
    makeStatic(call, HOOKS, "forceClearSampleCacheThrottled", hookDesc);

    PatchReport report = new PatchReport();
    report.add("hyperspace sample-cache clear throttle", 1);
    return report;
  }

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
  // A/Z/EventsPanel: hit tests and Intel map
  // ---------------------------------------------------------------------

  /**
   * Wraps the map hit-test so repeated hovers reuse an exact-result cache, and makes the
   * preserved original body borrow its list and point from a per-scope scratch pool instead of
   * allocating them on every test. The whole method is one transformation surface, so the wrapper
   * is built only after the scope, the allocation group and the semantic contract are all proven.
   */
  private static final class MapHitTest implements StructuredPatch<MapHitTest.Plan> {
    private static final String PUBLIC_NAME = "OO0000";
    private static final String RENAMED = "smo$originalHitTest";
    private static final String HOOK = "hitTestCached";
    private static final String SCOPE = "A.OO0000";
    private static final String SCRATCH = "A.OO0000 hit-test scratch";

    /** The proven original body plus the descriptors and scratch specs it was proven against. */
    private record Plan(String owner, List<MethodNode> classMethods, MethodNode original, String desc, String hookDesc,
        List<AllocationSpec> specs) {
    }

    private static String desc() {
      return "(FFF)" + ENTITY_TOKEN_DESC;
    }

    private static String hookDesc() {
      return "(Ljava/lang/Object;FFF)" + ENTITY_TOKEN_DESC;
    }

    private static List<AllocationSpec> specs() {
      return List.of(
          new AllocationSpec("java/util/ArrayList", "(Ljava/util/Collection;)V", "borrowHitList",
              "(Ljava/util/Collection;)Ljava/util/ArrayList;", 1),
          new AllocationSpec("org/lwjgl/util/vector/Vector2f", "(FF)V", "borrowHitPoint",
              "(FF)Lorg/lwjgl/util/vector/Vector2f;", 1));
    }

    @Override
    public Match<Plan> match(ClassNode node) {
      String desc = desc();
      String hookDesc = hookDesc();
      List<AllocationSpec> specs = specs();

      boolean wrapperAlready = methods(node, RENAMED, desc).size() == 1;
      MethodNode original = requireWrapperSource(node, PUBLIC_NAME, desc, RENAMED, HOOK, hookDesc);
      PatchState scopeState = scratchScopeState(node.name, original, SCOPE);
      PatchState allocationState = scratchAllocationGroupState(node.name, original, specs, SCRATCH);

      if (containsWriteOrMonitor(original)) {
        return Match.incompatible("hit-test method writes state or uses monitor/invokedynamic operations");
      }
      requireCount("hit-test semantic call count", totalMethodCalls(original),
          scopeState == PatchState.PATCHED ? 54 : 50);
      List<FieldNode> mapFields = fields(node, "L" + H + ";");
      requireCount("A map-handler fields", mapFields.size(), 1);
      requireCountAtLeast("hit-test H.getLocation",
          countCalls(original, Opcodes.INVOKEVIRTUAL, H, "getLocation", "()Lcom/fs/starfarer/campaign/BaseLocation;"),
          1);
      requireCountAtLeast("hit-test H.getFactor", countCalls(original, Opcodes.INVOKEVIRTUAL, H, "getFactor", "()F"),
          1);
      requireCountAtLeast("hit-test H.getIcons",
          countCalls(original, Opcodes.INVOKEVIRTUAL, H, "getIcons", "()Ljava/util/LinkedHashMap;"), 1);

      if (wrapperAlready) {
        if (scopeState != PatchState.PATCHED || allocationState != PatchState.PATCHED) {
          return Match.incompatible("hit-test wrapper exists without the complete scratch allocation group");
        }
        requireWrapperPostcondition(node, PUBLIC_NAME, desc, RENAMED, HOOK, hookDesc);
        requireHitTestWrapper(node, desc, hookDesc);
        return Match.alreadyApplied("hit-test wrapper, scratch scope, allocation hooks and semantic contract match");
      }
      if (scopeState != PatchState.ORIGINAL || allocationState != PatchState.ORIGINAL) {
        return Match.incompatible("hit-test method has a mixed wrapper/scope/allocation state");
      }
      return Match.plan(new Plan(node.name, node.methods, original, desc, hookDesc, specs));
    }

    @Override
    public PatchReport mutate(Plan plan) {
      MethodNode original = plan.original();
      installScratchScope(plan.owner(), original, SCOPE);
      int changed = replaceAllocationGroup(plan.owner(), original, plan.specs(), true, SCRATCH);

      int originalAccess = original.access;
      String signature = original.signature;
      String[] exceptions = exceptions(original);
      original.name = RENAMED;
      makePrivateSynthetic(original);
      MethodNode wrapper =
          new MethodNode(Opcodes.ASM8, originalAccess, PUBLIC_NAME, plan.desc(), signature, exceptions);
      moveWrapperMetadata(original, wrapper, PUBLIC_NAME);
      wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
      wrapper.instructions.add(new VarInsnNode(Opcodes.FLOAD, argumentLocal(wrapper, 0)));
      wrapper.instructions.add(new VarInsnNode(Opcodes.FLOAD, argumentLocal(wrapper, 1)));
      wrapper.instructions.add(new VarInsnNode(Opcodes.FLOAD, argumentLocal(wrapper, 2)));
      wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, HOOK, plan.hookDesc(), false));
      wrapper.instructions.add(new InsnNode(Opcodes.ARETURN));
      plan.classMethods().add(wrapper);

      PatchReport report = new PatchReport();
      report.add("hit-test scratch allocations", changed);
      report.add("bounded exact-result hover cache", 1);
      return report;
    }

    @Override
    public void verify(Plan plan, ClassNode node) {
      MethodNode original = plan.original();
      requireWrapperPostcondition(node, PUBLIC_NAME, plan.desc(), RENAMED, HOOK, plan.hookDesc());
      requireHitTestWrapper(node, plan.desc(), plan.hookDesc());
      requireCount("hit-test scratch scope",
          countCalls(original, Opcodes.INVOKESTATIC, HOOKS, "beginScratchScope", "()V"), 1);
      requireCount("hit-test list hook", countCalls(original, Opcodes.INVOKESTATIC, HOOKS, "borrowHitList",
          "(Ljava/util/Collection;)Ljava/util/ArrayList;"), 1);
      requireCount("hit-test point hook",
          countCalls(original, Opcodes.INVOKESTATIC, HOOKS, "borrowHitPoint", "(FF)Lorg/lwjgl/util/vector/Vector2f;"),
          1);
    }
  }

  private PatchReport patchIntelMapLocationCalls(ClassNode node, int expected) {
    String originalDesc = "(" + MAP_API_DESC + ")" + ENTITY_TOKEN_DESC;
    String hookDesc = "(" + INTEL_DESC + MAP_API_DESC + ")" + ENTITY_TOKEN_DESC;
    List<MethodInsnNode> originals = new ArrayList<>();
    int hooks = 0;
    for (MethodNode method : node.methods) {
      originals.addAll(calls(method, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/comm/IntelInfoPlugin",
          "getMapLocation", originalDesc));
      hooks += countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "getMapLocationCached", hookDesc);
    }
    requireUnpatchedOrAlready(node.name + " getMapLocation", originals.size(), expected, hooks, expected);
    for (MethodInsnNode call : originals) {
      if (nextMeaningful(call) != null && nextMeaningful(call).getOpcode() == Opcodes.POP) {
        throw mismatch("getMapLocation result is discarded at a candidate site");
      }
    }
    for (MethodInsnNode call : originals) {
      makeStatic(call, HOOKS, "getMapLocationCached", hookDesc);
    }
    PatchReport report = new PatchReport();
    report.add("Intel map-location callback cache", originals.size());
    return report;
  }

  /**
   * Atomically installs the complete Intel-arrow rendering surface in Z.o00000: the
   * getArrowData callback cache, both temporary Vector2f scratch allocations, and the
   * single reentrant scratch scope. Partial or foreign hook-shaped states reject the
   * whole group.
   */
  private PatchReport patchIntelArrowRendering(ClassNode node) {
    MethodNode method = requireMethod(node, "o00000", "(FF)V");
    String originalDesc = "(" + MAP_API_DESC + ")Ljava/util/List;";
    String hookDesc = "(" + INTEL_DESC + MAP_API_DESC + ")Ljava/util/List;";
    List<AllocationSpec> specs = List.of(new AllocationSpec("org/lwjgl/util/vector/Vector2f", "(FF)V",
        "borrowArrowVector", "(FF)Lorg/lwjgl/util/vector/Vector2f;", 2));

    PatchState scopeState = scratchScopeState(node.name, method, "Z.o00000");
    PatchState allocationState = scratchAllocationGroupState(node.name, method, specs, "Z arrow vectors");
    List<MethodInsnNode> originalCallbacks = calls(method, Opcodes.INVOKEINTERFACE,
        "com/fs/starfarer/api/campaign/comm/IntelInfoPlugin", "getArrowData", originalDesc);
    List<MethodInsnNode> callbackHooks = calls(method, Opcodes.INVOKESTATIC, HOOKS, "getArrowDataCached", hookDesc);

    boolean originalState = originalCallbacks.size() == 1 && callbackHooks.isEmpty()
        && allocationState == PatchState.ORIGINAL && scopeState == PatchState.ORIGINAL;
    boolean patchedState = originalCallbacks.isEmpty() && callbackHooks.size() == 1
        && allocationState == PatchState.PATCHED && scopeState == PatchState.PATCHED;

    if (patchedState) {
      validateArrowDataCallback(callbackHooks.get(0));
      throw already("Z Intel-arrow callback, vectors, and scratch scope are already present");
    }
    if (!originalState) {
      throw mismatch(
          "Z.o00000 Intel-arrow rendering has mixed, missing, ambiguous, or " + "foreign callback/vector/scope state");
    }

    MethodInsnNode callback = originalCallbacks.get(0);
    validateArrowDataCallback(callback);
    installScratchScope(node.name, method, "Z.o00000");
    makeStatic(callback, HOOKS, "getArrowDataCached", hookDesc);
    int vectors = replaceAllocationGroup(node.name, method, specs, true, "Z arrow vectors");

    requireCount("Z arrow-data callback postcondition",
        countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "getArrowDataCached", hookDesc), 1);
    requireCount("Z arrow-vector postcondition",
        countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "borrowArrowVector", "(FF)Lorg/lwjgl/util/vector/Vector2f;"),
        2);
    if (scratchScopeState(node.name, method, "Z.o00000") != PatchState.PATCHED) {
      throw mismatch("Z.o00000 scratch-scope installation postcondition failed");
    }

    PatchReport report = new PatchReport();
    report.add("Intel arrow-data callback cache", 1);
    report.add("Intel arrow vector pool", vectors);
    return report;
  }

  private static void validateArrowDataCallback(MethodInsnNode call) {
    if (nextMeaningful(call) != null && nextMeaningful(call).getOpcode() == Opcodes.POP) {
      throw mismatch("getArrowData result is discarded");
    }
  }

  /**
   * Atomically installs the EventsPanel reconciliation surface: the missing-plugin
   * membership accelerator, direct existing-icon candidate lookup, and the single
   * reentrant scratch scope required by fastContains(). Partial or foreign hook-shaped
   * states reject the whole group.
   */
  private PatchReport patchIntelReconciliation(ClassNode node) {
    MethodNode method = requireMethod(node, "addMissingIconsAndRows", "()V");
    String containsDesc = "(Ljava/util/Collection;Ljava/lang/Object;)Z";
    String candidatesDesc = "(Ljava/util/LinkedHashMap;Ljava/lang/Object;)Ljava/util/Collection;";
    PatchState scopeState = scratchScopeState(node.name, method, "EventsPanel.addMissingIconsAndRows");

    List<MethodInsnNode> originalContains =
        calls(method, Opcodes.INVOKEINTERFACE, "java/util/List", "contains", "(Ljava/lang/Object;)Z");
    List<MethodInsnNode> containsHooks = calls(method, Opcodes.INVOKESTATIC, HOOKS, "fastContains", containsDesc);
    List<MethodInsnNode> originalValues =
        calls(method, Opcodes.INVOKEVIRTUAL, "java/util/LinkedHashMap", "values", "()Ljava/util/Collection;");
    List<MethodInsnNode> candidateHooks =
        calls(method, Opcodes.INVOKESTATIC, HOOKS, "existingIntelIconCandidates", candidatesDesc);

    boolean originalState = originalContains.size() == 1 && containsHooks.isEmpty() && originalValues.size() == 1
        && candidateHooks.isEmpty() && scopeState == PatchState.ORIGINAL;
    boolean patchedState = originalContains.isEmpty() && containsHooks.size() == 1 && originalValues.isEmpty()
        && candidateHooks.size() == 1 && scopeState == PatchState.PATCHED;

    if (patchedState) {
      validateEventsPanelContainsSite(node, method, containsHooks.get(0), true);
      validateEventsPanelExistingIconSite(node, method, candidateHooks.get(0), true);
      throw already("EventsPanel reconciliation hooks and scratch scope are already present");
    }
    if (!originalState) {
      throw mismatch("EventsPanel reconciliation has mixed, missing, ambiguous, or foreign "
          + "contains/icon-candidate/scope state");
    }

    MethodInsnNode contains = originalContains.get(0);
    MethodInsnNode values = originalValues.get(0);
    validateEventsPanelContainsSite(node, method, contains, false);
    int entityLocal = validateEventsPanelExistingIconSite(node, method, values, false);

    installScratchScope(node.name, method, "EventsPanel.addMissingIconsAndRows");
    makeStatic(contains, HOOKS, "fastContains", containsDesc);
    method.instructions.insertBefore(values, new VarInsnNode(Opcodes.ALOAD, entityLocal));
    makeStatic(values, HOOKS, "existingIntelIconCandidates", candidatesDesc);

    PatchReport report = new PatchReport();
    report.add("Intel missing-list hash membership", 1);
    report.add("Intel direct existing-icon candidates", 1);
    return report;
  }

  private static void validateEventsPanelContainsSite(ClassNode node, MethodNode method, MethodInsnNode call,
      boolean patched) {
    Frame<SourceValue>[] frames = sourceFrames(node.name, method);
    SourceValue collectionSource =
        patched ? argumentSource(method, call, frames, 0) : receiverSource(method, call, frames);
    SourceValue pluginSource =
        patched ? argumentSource(method, call, frames, 1) : argumentSource(method, call, frames, 0);
    int listLocal = requireLoadedLocal(collectionSource, Opcodes.ALOAD, "missing Intel list");
    int pluginLocal = requireLoadedLocal(pluginSource, Opcodes.ALOAD, "current Intel plugin");
    requireLocalConstructor(method, listLocal, "java/util/ArrayList", "()V", "missing Intel list");
    requireStoredCallResult(method, pluginLocal, -1, null, "getInfo", "()" + INTEL_DESC, "row Intel plugin");
    if (hasMutatorAfter(node.name, method, call, listLocal)) {
      throw mismatch("missing Intel list is mutated after the reconciliation membership site");
    }
  }

  private static int validateEventsPanelExistingIconSite(ClassNode node, MethodNode method, MethodInsnNode call,
      boolean patched) {
    Frame<SourceValue>[] frames = sourceFrames(node.name, method);
    SourceValue mapSource = patched ? argumentSource(method, call, frames, 0) : receiverSource(method, call, frames);
    requireSourceMethod(mapSource, Opcodes.INVOKEVIRTUAL, H, "getIcons", "()Ljava/util/LinkedHashMap;",
        "existing icon map receiver");
    MethodInsnNode entityLookup = only(
        calls(method, Opcodes.INVOKEVIRTUAL, H, "getIntelIconEntity",
            "(" + INTEL_DESC + ")Lcom/fs/starfarer/campaign/CustomCampaignEntity;"),
        "H.getIntelIconEntity in addMissingIconsAndRows");
    AbstractInsnNode storeInsn = nextMeaningful(entityLookup);
    if (!(storeInsn instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) {
      throw mismatch("Intel entity lookup result is not stored in a local");
    }
    int entityLocal = store.var;
    if (!hasNullGuard(method, store, call, entityLocal)) {
      throw mismatch("Intel entity candidate scan is not guarded by an entity null check");
    }
    if (patched) {
      int hookEntityLocal =
          requireLoadedLocal(argumentSource(method, call, frames, 1), Opcodes.ALOAD, "existing icon entity argument");
      if (hookEntityLocal != entityLocal) { throw mismatch("existing icon hook is wired to the wrong entity local"); }
    }
    int comparisons = countIdentityComparisons(method, frames, call, entityLocal, "com/fs/starfarer/coreui/A/ooOO",
        "int", "()" + ENTITY_TOKEN_DESC);
    requireCount("existing icon entity identity comparison", comparisons, 1);
    return entityLocal;
  }

  // ---------------------------------------------------------------------
  // Campaign and route hot paths
  // ---------------------------------------------------------------------

  private PatchReport patchCampaignCacheLifecycle(ClassNode node) {
    String engineDesc = "L" + CAMPAIGN_ENGINE + ";";
    String beginDesc = "(Ljava/lang/Object;)J";
    String completeDesc = "(Ljava/lang/Object;J)V";
    MethodNode reset = requireMethod(node, "resetInstance", "()V");
    MethodNode set = requireMethod(node, "setInstance", "(" + engineDesc + ")V");
    MethodNode advance = requireMethod(node, "advance", "(FLcom/fs/starfarer/util/A/new;)V");
    int maintenanceHooks = countCalls(advance, Opcodes.INVOKESTATIC, HOOKS, "campaignCacheMaintenanceTick", "()V");
    if ((reset.access & Opcodes.ACC_STATIC) == 0 || (set.access & Opcodes.ACC_STATIC) == 0) {
      throw mismatch("CampaignEngine lifecycle methods are not static");
    }
    if ((reset.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
        || (set.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
      throw mismatch("CampaignEngine lifecycle methods have no patchable code");
    }

    FieldNode singleton = null;
    for (FieldNode field : node.fields) {
      if ((field.access & Opcodes.ACC_STATIC) == 0 || !field.desc.equals(engineDesc)) continue;
      if (singleton != null) { throw mismatch("CampaignEngine has multiple static self-typed fields"); }
      singleton = field;
    }
    if (singleton == null) throw mismatch("CampaignEngine singleton field was not found");
    FieldInsnNode resetWrite = requireSingletonWrite(reset, node.name, singleton.name, engineDesc, "resetInstance");
    FieldInsnNode setWrite = requireSingletonWrite(set, node.name, singleton.name, engineDesc, "setInstance");
    Frame<SourceValue> resetFrame = frameBefore(reset, resetWrite, sourceFrames(node.name, reset));
    Frame<SourceValue> setFrame = frameBefore(set, setWrite, sourceFrames(node.name, set));
    if (resetFrame.getStackSize() < 1
        || !sourceIsOpcode(resetFrame.getStack(resetFrame.getStackSize() - 1), Opcodes.ACONST_NULL)) {
      throw mismatch("resetInstance singleton write is not produced by null");
    }
    if (setFrame.getStackSize() < 1
        || !sourceIsLocal(setFrame.getStack(setFrame.getStackSize() - 1), Opcodes.ALOAD, argumentLocal(set, 0))) {
      throw mismatch("setInstance singleton write is not produced by its engine argument");
    }

    List<AbstractInsnNode> resetReturns = campaignReturns(reset);
    List<AbstractInsnNode> setReturns = campaignReturns(set);
    int resetBegins = countCalls(reset, Opcodes.INVOKESTATIC, HOOKS, "beginCampaignEngineChange", beginDesc);
    int setBegins = countCalls(set, Opcodes.INVOKESTATIC, HOOKS, "beginCampaignEngineChange", beginDesc);
    int resetCompletes = countCalls(reset, Opcodes.INVOKESTATIC, HOOKS, "completeCampaignEngineChange", completeDesc);
    int setCompletes = countCalls(set, Opcodes.INVOKESTATIC, HOOKS, "completeCampaignEngineChange", completeDesc);
    if (resetBegins == 1 && setBegins == 1 && resetCompletes == resetReturns.size() && setCompletes == setReturns.size()
        && maintenanceHooks == 1) {
      int resetToken = requireCampaignBeginPlacement(reset, resetWrite, false, beginDesc);
      int setToken = requireCampaignBeginPlacement(set, setWrite, true, beginDesc);
      requireCampaignCompletionPlacement(reset, resetReturns, false, resetToken, completeDesc);
      requireCampaignCompletionPlacement(set, setReturns, true, setToken, completeDesc);
      requireCampaignTokenInitializer(reset, resetToken);
      requireCampaignTokenInitializer(set, setToken);
      throw already("CampaignEngine lifecycle cache hooks are already present");
    }
    if (resetBegins != 0 || setBegins != 0 || resetCompletes != 0 || setCompletes != 0 || maintenanceHooks != 0) {
      throw mismatch("CampaignEngine lifecycle/maintenance hooks are partial or ambiguous: resetBegin=" + resetBegins
          + ", setBegin=" + setBegins + ", resetComplete=" + resetCompletes + ", setComplete=" + setCompletes
          + ", maintenance=" + maintenanceHooks);
    }

    advance.instructions
        .insert(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "campaignCacheMaintenanceTick", "()V", false));

    int resetToken = reset.maxLocals;
    reset.maxLocals += 2;
    InsnList resetInitializer = new InsnList();
    resetInitializer.add(new LdcInsnNode(Long.valueOf(-1L)));
    resetInitializer.add(new VarInsnNode(Opcodes.LSTORE, resetToken));
    reset.instructions.insert(resetInitializer);
    InsnList resetPrefix = new InsnList();
    resetPrefix.add(new InsnNode(Opcodes.ACONST_NULL));
    resetPrefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "beginCampaignEngineChange", beginDesc, false));
    resetPrefix.add(new VarInsnNode(Opcodes.LSTORE, resetToken));
    reset.instructions.insertBefore(resetWrite, resetPrefix);
    for (AbstractInsnNode returnInsn : resetReturns) {
      InsnList completion = new InsnList();
      completion.add(new InsnNode(Opcodes.ACONST_NULL));
      completion.add(new VarInsnNode(Opcodes.LLOAD, resetToken));
      completion
          .add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "completeCampaignEngineChange", completeDesc, false));
      reset.instructions.insertBefore(returnInsn, completion);
    }

    int setToken = set.maxLocals;
    set.maxLocals += 2;
    InsnList setInitializer = new InsnList();
    setInitializer.add(new LdcInsnNode(Long.valueOf(-1L)));
    setInitializer.add(new VarInsnNode(Opcodes.LSTORE, setToken));
    set.instructions.insert(setInitializer);
    InsnList setPrefix = new InsnList();
    setPrefix.add(new VarInsnNode(Opcodes.ALOAD, argumentLocal(set, 0)));
    setPrefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "beginCampaignEngineChange", beginDesc, false));
    setPrefix.add(new VarInsnNode(Opcodes.LSTORE, setToken));
    set.instructions.insertBefore(setWrite, setPrefix);
    for (AbstractInsnNode returnInsn : setReturns) {
      InsnList completion = new InsnList();
      completion.add(new VarInsnNode(Opcodes.ALOAD, argumentLocal(set, 0)));
      completion.add(new VarInsnNode(Opcodes.LLOAD, setToken));
      completion
          .add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "completeCampaignEngineChange", completeDesc, false));
      set.instructions.insertBefore(returnInsn, completion);
    }

    requireCampaignBeginPlacement(reset, resetWrite, false, beginDesc);
    requireCampaignBeginPlacement(set, setWrite, true, beginDesc);
    requireCampaignCompletionPlacement(reset, resetReturns, false, resetToken, completeDesc);
    requireCampaignCompletionPlacement(set, setReturns, true, setToken, completeDesc);
    requireCampaignTokenInitializer(reset, resetToken);
    requireCampaignTokenInitializer(set, setToken);
    requireCount("CampaignEngine campaign maintenance hooks",
        countCalls(advance, Opcodes.INVOKESTATIC, HOOKS, "campaignCacheMaintenanceTick", "()V"), 1);
    PatchReport report = new PatchReport();
    report.add("campaign cache two-phase boundaries", 2 + resetReturns.size() + setReturns.size());
    report.add("campaign-thread cache maintenance tick", 1);
    return report;
  }

  private static int requireCampaignBeginPlacement(MethodNode method, FieldInsnNode write, boolean engineArgument,
      String beginDesc) {
    AbstractInsnNode storeInsn = previousMeaningful(write);
    if (!(storeInsn instanceof VarInsnNode store) || store.getOpcode() != Opcodes.LSTORE) {
      throw mismatch(method.name + " lifecycle transition token is not stored before publication");
    }
    AbstractInsnNode hookInsn = previousMeaningful(store);
    MethodInsnNode hook = asMethod(hookInsn, method.name + " lifecycle hook");
    requireCall(hook, Opcodes.INVOKESTATIC, HOOKS, "beginCampaignEngineChange", beginDesc,
        method.name + " lifecycle hook");
    AbstractInsnNode argument = previousMeaningful(hook);
    if (engineArgument) {
      requireVar(argument, Opcodes.ALOAD, argumentLocal(method, 0), method.name + " lifecycle engine argument");
    } else if (argument == null || argument.getOpcode() != Opcodes.ACONST_NULL) {
      throw mismatch(method.name + " lifecycle reset argument is not null");
    }
    return store.var;
  }

  private static void requireCampaignCompletionPlacement(MethodNode method, List<AbstractInsnNode> returns,
      boolean engineArgument, int tokenLocal, String completeDesc) {
    for (AbstractInsnNode returnInsn : returns) {
      MethodInsnNode hook = asMethod(previousMeaningful(returnInsn), method.name + " lifecycle completion hook");
      requireCall(hook, Opcodes.INVOKESTATIC, HOOKS, "completeCampaignEngineChange", completeDesc,
          method.name + " lifecycle completion hook");
      AbstractInsnNode token = previousMeaningful(hook);
      requireVar(token, Opcodes.LLOAD, tokenLocal, method.name + " lifecycle completion token");
      AbstractInsnNode argument = previousMeaningful(token);
      if (engineArgument) {
        requireVar(argument, Opcodes.ALOAD, argumentLocal(method, 0),
            method.name + " lifecycle completion engine argument");
      } else if (argument == null || argument.getOpcode() != Opcodes.ACONST_NULL) {
        throw mismatch(method.name + " lifecycle completion reset argument is not null");
      }
    }
  }

  private static void requireCampaignTokenInitializer(MethodNode method, int tokenLocal) {
    AbstractInsnNode first = null;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn.getOpcode() >= 0) {
        first = insn;
        break;
      }
    }
    if (!(first instanceof LdcInsnNode ldc) || !(ldc.cst instanceof Long value) || value.longValue() != -1L) {
      throw mismatch(method.name + " lifecycle transition token initializer changed");
    }
    requireVar(nextMeaningful(first), Opcodes.LSTORE, tokenLocal,
        method.name + " lifecycle transition token initializer");
  }


  private static FieldInsnNode requireSingletonWrite(MethodNode method, String owner, String name, String desc,
      String label) {
    FieldInsnNode result = null;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC || !field.owner.equals(owner)
          || !field.name.equals(name) || !field.desc.equals(desc))
        continue;
      if (result != null) throw mismatch(label + " has multiple singleton writes");
      result = field;
    }
    if (result == null) throw mismatch(label + " singleton write was not found");
    return result;
  }


  /**
   * CampaignEngine's id-to-entity map is transient. During XStream restore a mod
   * may call getEntityById() before the star-system graph has been attached. The
   * vanilla lookup then materializes a non-null but incomplete map; the first
   * campaign tick only rebuilds null maps, so the incomplete map survives and
   * every miss scans hyperspace plus all star systems.
   *
   * Add one transient per-engine readiness bit and rebuild the exact vanilla map
   * once, immediately after the first repository-listener restoration. The bit
   * is deliberately transient so every loaded CampaignEngine receives the same
   * repair, while steady-state ticks pay one private boolean read and branch.
   */
  private PatchReport patchEntityLookupIndexRepair(ClassNode node) {
    final int stateAccess = Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC;
    EntityLookupIndexSurface surface = requireEntityLookupIndexSurface(node);
    FieldNode state = uniqueField(node, ENTITY_LOOKUP_INDEX_REPAIR_FIELD);
    if (state != null) {
      if (!state.desc.equals("Z") || state.access != stateAccess || state.signature != null || state.value != null) {
        throw mismatch("CampaignEngine entity-index readiness field is foreign");
      }
      requireEntityLookupIndexRepairBlock(node, surface);
      throw already("CampaignEngine post-load entity-index repair postcondition matches");
    }

    requireCount("CampaignEngine original advance entity-index rebuilds",
        countCalls(surface.advance(), Opcodes.INVOKEVIRTUAL, node.name, surface.rebuild().name, surface.rebuild().desc),
        1);
    node.fields.add(new FieldNode(Opcodes.ASM8, stateAccess, ENTITY_LOOKUP_INDEX_REPAIR_FIELD, "Z", null, null));

    LabelNode ready = new LabelNode();
    InsnList repair = new InsnList();
    repair.add(new VarInsnNode(Opcodes.ALOAD, 0));
    repair.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, ENTITY_LOOKUP_INDEX_REPAIR_FIELD, "Z"));
    repair.add(new JumpInsnNode(Opcodes.IFNE, ready));
    repair.add(new VarInsnNode(Opcodes.ALOAD, 0));
    repair.add(
        new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, surface.rebuild().name, surface.rebuild().desc, false));
    repair.add(new VarInsnNode(Opcodes.ALOAD, 0));
    repair.add(new InsnNode(Opcodes.ICONST_1));
    repair.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, ENTITY_LOOKUP_INDEX_REPAIR_FIELD, "Z"));
    repair.add(ready);
    surface.advance().instructions.insert(surface.listenerBoundary(), repair);

    requireEntityLookupIndexRepairBlock(node, requireEntityLookupIndexSurface(node));
    PatchReport report = new PatchReport();
    report.add("transient CampaignEngine entity-index readiness flag", 1);
    report.add("one post-load vanilla entity-index rebuild", 1);
    return report;
  }

  private static EntityLookupIndexSurface requireEntityLookupIndexSurface(ClassNode node) {
    MethodNode rebuild = requireMethod(node, "rebuildIDToEntityMap", "()V");
    if ((rebuild.access & Opcodes.ACC_PRIVATE) == 0
        || (rebuild.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
      throw mismatch("CampaignEngine entity-index rebuild method access changed");
    }

    FieldInsnNode mapWrite = null;
    int hashMapAllocations = 0;
    for (AbstractInsnNode insn : rebuild.instructions.toArray()) {
      if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD && field.owner.equals(node.name)
          && field.desc.equals("Ljava/util/Map;")) {
        if (mapWrite != null) { throw mismatch("CampaignEngine entity-index rebuild writes multiple Map fields"); }
        mapWrite = field;
      }
      if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
          && type.desc.equals("java/util/HashMap")) {
        hashMapAllocations++;
      }
    }
    if (mapWrite == null) { throw mismatch("CampaignEngine entity-index rebuild Map field was not found"); }
    requireCount("CampaignEngine entity-index HashMap allocation", hashMapAllocations, 1);
    requireCount("CampaignEngine entity-index population sites", calls(rebuild, Opcodes.INVOKEINTERFACE,
        "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;").size(), 2);

    MethodNode lookup = requireMethod(node, "getEntityById", "(Ljava/lang/String;)" + ENTITY_TOKEN_DESC);
    MethodInsnNode lookupRebuild = only(calls(lookup, Opcodes.INVOKEVIRTUAL, node.name, rebuild.name, rebuild.desc),
        "CampaignEngine.getEntityById null-map rebuild");
    Frame<SourceValue>[] lookupFrames = sourceFrames(node.name, lookup);
    if (!sourceIsLocal(receiverSource(lookup, lookupRebuild, lookupFrames), Opcodes.ALOAD, 0)) {
      throw mismatch("CampaignEngine.getEntityById rebuild receiver is not this");
    }
    int lookupMapReads = 0;
    for (AbstractInsnNode insn : lookup.instructions.toArray()) {
      if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
          || !field.owner.equals(node.name) || !field.desc.equals("Ljava/util/Map;")) {
        continue;
      }
      if (!field.name.equals(mapWrite.name)) {
        throw mismatch("CampaignEngine.getEntityById reads multiple Map fields");
      }
      lookupMapReads++;
    }
    requireCount("CampaignEngine.getEntityById entity-index reads", lookupMapReads, 5);

    MethodNode advance = requireMethod(node, "advance", "(FLcom/fs/starfarer/util/A/new;)V");
    MethodInsnNode listenerBoundary = requireEntityLookupListenerBoundary(node, advance);
    return new EntityLookupIndexSurface(rebuild, advance, listenerBoundary);
  }

  private static MethodInsnNode requireEntityLookupListenerBoundary(ClassNode node, MethodNode advance) {
    List<MethodInsnNode> raw = calls(advance, Opcodes.INVOKEVIRTUAL, node.name, "readdChangeListeners", "()V");
    String hookDesc = "(Ljava/lang/Object;Ljava/util/List;Ljava/lang/Object;)V";
    List<MethodInsnNode> throttled =
        calls(advance, Opcodes.INVOKESTATIC, HOOKS, "readdChangeListenersIfNeeded", hookDesc);
    Frame<SourceValue>[] frames = sourceFrames(node.name, advance);
    if (raw.size() == 1 && throttled.isEmpty()) {
      MethodInsnNode boundary = raw.get(0);
      if (!sourceIsLocal(receiverSource(advance, boundary, frames), Opcodes.ALOAD, 0)) {
        throw mismatch("CampaignEngine listener restoration receiver is not this");
      }
      return boundary;
    }
    if (raw.isEmpty() && throttled.size() == 1) {
      MethodInsnNode boundary = throttled.get(0);
      if (!sourceIsLocal(argumentSource(advance, boundary, frames, 0), Opcodes.ALOAD, 0)) {
        throw mismatch("CampaignEngine throttled listener restoration engine is not this");
      }
      return boundary;
    }
    throw mismatch("CampaignEngine listener restoration boundary is missing or ambiguous: raw=" + raw.size()
        + ", throttled=" + throttled.size());
  }

  private static void requireEntityLookupIndexRepairBlock(ClassNode node, EntityLookupIndexSurface surface) {
    requireCount("CampaignEngine repaired advance entity-index rebuilds",
        countCalls(surface.advance(), Opcodes.INVOKEVIRTUAL, node.name, surface.rebuild().name, surface.rebuild().desc),
        2);

    AbstractInsnNode cursor = nextMeaningful(surface.listenerBoundary());
    requireVar(cursor, Opcodes.ALOAD, 0, "entity-index readiness owner");
    cursor = nextMeaningful(cursor);
    requireField(cursor, Opcodes.GETFIELD, node.name, ENTITY_LOOKUP_INDEX_REPAIR_FIELD, "Z",
        "entity-index readiness read");
    cursor = nextMeaningful(cursor);
    if (!(cursor instanceof JumpInsnNode ready) || ready.getOpcode() != Opcodes.IFNE) {
      throw mismatch("entity-index readiness branch changed");
    }
    cursor = nextMeaningful(cursor);
    requireVar(cursor, Opcodes.ALOAD, 0, "entity-index rebuild owner");
    cursor = nextMeaningful(cursor);
    if (!(cursor instanceof MethodInsnNode rebuild)
        || !callMatches(rebuild, Opcodes.INVOKEVIRTUAL, node.name, surface.rebuild().name, surface.rebuild().desc)) {
      throw mismatch("entity-index guarded rebuild call changed");
    }
    cursor = nextMeaningful(cursor);
    requireVar(cursor, Opcodes.ALOAD, 0, "entity-index readiness write owner");
    cursor = nextMeaningful(cursor);
    if (cursor == null || cursor.getOpcode() != Opcodes.ICONST_1) {
      throw mismatch("entity-index readiness value changed");
    }
    cursor = nextMeaningful(cursor);
    requireField(cursor, Opcodes.PUTFIELD, node.name, ENTITY_LOOKUP_INDEX_REPAIR_FIELD, "Z",
        "entity-index readiness write");
    if (surface.advance().instructions.indexOf(ready.label) <= surface.advance().instructions.indexOf(cursor)
        || nextMeaningful(cursor) != nextMeaningful(ready.label)) {
      throw mismatch("entity-index readiness branch does not join after publication");
    }
  }

  private record EntityLookupIndexSurface(MethodNode rebuild, MethodNode advance, MethodInsnNode listenerBoundary) {
  }

  private PatchReport patchCampaignListenerThrottle(ClassNode node) {
    MethodNode refresh = requireMethod(node, "readdChangeListeners", "()V");
    if (containsWrite(refresh)) { throw mismatch("readdChangeListeners contains field/static writes"); }
    Frame<SourceValue>[] refreshFrames = sourceFrames(node.name, refresh);
    List<MethodInsnNode> listenerCalls =
        calls(refresh, Opcodes.INVOKEVIRTUAL, "com/fs/util/container/repo/ObjectRepository", "setListener", null);
    requireCount("readdChangeListeners setListener calls", listenerCalls.size(), 2);

    MethodInsnNode systemIterator =
        only(calls(refresh, Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;"),
            "starSystems iterator");
    FieldInsnNode systemsField = requireSourceField(receiverSource(refresh, systemIterator, refreshFrames),
        Opcodes.GETFIELD, node.name, LIST_DESC, "CampaignEngine starSystems field");
    MethodInsnNode getObjects = only(calls(refresh, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/campaign/Hyperspace",
        "getObjects", "()Lcom/fs/util/container/repo/ObjectRepository;"), "hyperspace repository access");
    FieldInsnNode hyperspaceField = requireSourceField(receiverSource(refresh, getObjects, refreshFrames),
        Opcodes.GETFIELD, node.name, HYPERSPACE_DESC, "CampaignEngine hyperspace field");

    MethodNode advance = requireMethod(node, "advance", "(FLcom/fs/starfarer/util/A/new;)V");
    String hookDesc = "(Ljava/lang/Object;Ljava/util/List;Ljava/lang/Object;)V";
    List<MethodInsnNode> original = calls(advance, Opcodes.INVOKEVIRTUAL, node.name, "readdChangeListeners", "()V");
    int hooks = countCalls(advance, Opcodes.INVOKESTATIC, HOOKS, "readdChangeListenersIfNeeded", hookDesc);
    requireUnpatchedOrAlready("CampaignEngine.advance listener refresh", original.size(), 1, hooks, 1);
    MethodInsnNode call = only(original, "CampaignEngine.advance listener refresh");
    Frame<SourceValue>[] advanceFrames = sourceFrames(node.name, advance);
    if (!sourceIsLocal(receiverSource(advance, call, advanceFrames), Opcodes.ALOAD, 0)) {
      throw mismatch("CampaignEngine.advance listener receiver is not this");
    }
    advance.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 0));
    advance.instructions.insertBefore(call,
        new FieldInsnNode(Opcodes.GETFIELD, node.name, systemsField.name, LIST_DESC));
    advance.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 0));
    advance.instructions.insertBefore(call,
        new FieldInsnNode(Opcodes.GETFIELD, node.name, hyperspaceField.name, HYPERSPACE_DESC));
    makeStatic(call, HOOKS, "readdChangeListenersIfNeeded", hookDesc);

    PatchReport report = new PatchReport();
    report.add("incremental campaign repository-listener refresh", 1);
    return report;
  }

  private PatchReport patchRouteJumpPointIndex(ClassNode node) {
    MethodNode next = requireMethod(node, "getNextStep", "(" + ENTITY_TOKEN_DESC + ")" + ENTITY_TOKEN_DESC);
    MethodNode distance = requireMethod(node, "getLastLegDistance", "(" + ENTITY_TOKEN_DESC + ")F");
    String jumpHookDesc = "(" + LOCATION_DESC + "Ljava/lang/Object;)Ljava/util/List;";
    String systemsHookDesc = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;";

    List<MethodInsnNode> nextJumps = calls(next, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/LocationAPI",
        "getJumpPoints", "()Ljava/util/List;");
    List<MethodInsnNode> distanceJumps = calls(distance, Opcodes.INVOKEINTERFACE,
        "com/fs/starfarer/api/campaign/LocationAPI", "getJumpPoints", "()Ljava/util/List;");
    int jumpHooks = countCalls(next, Opcodes.INVOKESTATIC, HOOKS, "routeJumpPointsForSystem", jumpHookDesc)
        + countCalls(distance, Opcodes.INVOKESTATIC, HOOKS, "routeJumpPointsForSystem", jumpHookDesc);
    List<MethodInsnNode> systems =
        calls(next, Opcodes.INVOKEVIRTUAL, CAMPAIGN_ENGINE, "getStarSystems", "()Ljava/util/List;");
    int systemsHooks = countCalls(next, Opcodes.INVOKESTATIC, HOOKS, "routeSystemsForAnchor", systemsHookDesc);

    boolean fullyPatched =
        nextJumps.isEmpty() && distanceJumps.isEmpty() && jumpHooks == 3 && systems.isEmpty() && systemsHooks == 1;
    if (fullyPatched) throw already("three jump hooks and one system hook are already present");
    if (jumpHooks != 0 || systemsHooks != 0) {
      throw mismatch("mixed/foreign route hook state: jumps=" + jumpHooks + ", systems=" + systemsHooks);
    }
    requireCount("getNextStep jump scans", nextJumps.size(), 2);
    requireCount("getLastLegDistance jump scans", distanceJumps.size(), 1);
    requireCount("getNextStep system scan", systems.size(), 1);

    int[] nextSystemLocals = new int[2];
    for (int i = 0; i < nextJumps.size(); i++) {
      AbstractInsnNode boundary = i + 1 < nextJumps.size() ? nextJumps.get(i + 1) : null;
      nextSystemLocals[i] = findComparedLocationLocal(node.name, next, nextJumps.get(i), boundary);
      requireSystemLocalProducer(next, nextSystemLocals[i], nextJumps.get(i), "getNextStep jump scan " + (i + 1));
    }
    int distanceSystemLocal = findComparedLocationLocal(node.name, distance, distanceJumps.get(0), null);
    requireSystemLocalProducer(distance, distanceSystemLocal, distanceJumps.get(0), "getLastLegDistance jump scan");
    int anchorLocal = findAnchorComparisonLocal(node.name, next, systems.get(0));
    int expectedAnchor = argumentLocal(next, 0);
    if (anchorLocal != expectedAnchor) {
      throw mismatch("route anchor local is not the SectorEntityToken argument: found " + anchorLocal + ", expected "
          + expectedAnchor);
    }

    for (int i = 0; i < nextJumps.size(); i++) {
      MethodInsnNode call = nextJumps.get(i);
      next.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, nextSystemLocals[i]));
      makeStatic(call, HOOKS, "routeJumpPointsForSystem", jumpHookDesc);
    }
    MethodInsnNode distanceCall = distanceJumps.get(0);
    distance.instructions.insertBefore(distanceCall, new VarInsnNode(Opcodes.ALOAD, distanceSystemLocal));
    makeStatic(distanceCall, HOOKS, "routeJumpPointsForSystem", jumpHookDesc);

    MethodInsnNode systemsCall = systems.get(0);
    next.instructions.insertBefore(systemsCall, new VarInsnNode(Opcodes.ALOAD, anchorLocal));
    makeStatic(systemsCall, HOOKS, "routeSystemsForAnchor", systemsHookDesc);

    PatchReport report = new PatchReport();
    report.add("route jump-point system index", 3);
    report.add("route hyperspace-anchor system index", 1);
    return report;
  }

  // ---------------------------------------------------------------------
  // StrategicModule: destination-first safe jump-point filtering
  // ---------------------------------------------------------------------

  /**
   * Transformation surface: StrategicModule.findNearestSafeJumpPoint and only the
   * straight-line hostile-market score feeding the accepted-destination candidate
   * path. No other patch currently targets this class or method.
   */
  private PatchReport patchStrategicJumpDestinationFirst(ClassNode node) {
    MethodNode method = requireMethod(node, "findNearestSafeJumpPoint", STRATEGIC_JUMP_METHOD_DESC);
    if ((method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) != (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) {
      throw mismatch("StrategicModule.findNearestSafeJumpPoint access changed");
    }

    StrategicJumpCommon common = inspectStrategicJumpCommon(node.name, method);
    boolean owned = hasPatchMarker(node, STRATEGIC_JUMP_DESTINATION_FIRST_PATCH_ID);
    StrategicJumpOriginal original = inspectStrategicJumpOriginal(common, false);
    StrategicJumpLazy lazy = inspectStrategicJumpLazy(common, false);

    if (owned) {
      if (lazy == null || original != null) {
        throw mismatch("owned destination-first patch has an incomplete or mixed post-state");
      }
      throw already("destination predicate precedes the guarded hostile-market scan");
    }
    if (lazy != null) { throw already("destination-first bytecode is already present without this optimizer marker"); }
    if (original == null) { throw mismatch("expected the vanilla eager hostile-market score before getDestinations"); }

    InsnList sentinel = new InsnList();
    sentinel.add(new LdcInsnNode(STRATEGIC_JUMP_UNCOMPUTED));
    sentinel.add(new VarInsnNode(Opcodes.ISTORE, common.unsafeLocal));
    method.instructions.insertBefore(original.start, sentinel);
    removeRange(method, original.start, original.end);

    LabelNode scoreReady = new LabelNode();
    InsnList lazyScore = new InsnList();
    lazyScore.add(new VarInsnNode(Opcodes.ILOAD, common.unsafeLocal));
    lazyScore.add(new LdcInsnNode(STRATEGIC_JUMP_UNCOMPUTED));
    lazyScore.add(new JumpInsnNode(Opcodes.IF_ICMPNE, scoreReady));
    lazyScore.add(new VarInsnNode(Opcodes.ALOAD, common.fleetLocal));
    lazyScore.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/CampaignFleetAPI",
        "getFaction", "()Lcom/fs/starfarer/api/campaign/FactionAPI;", true));
    lazyScore.add(new VarInsnNode(Opcodes.ALOAD, common.jumpPointLocal));
    lazyScore.add(new LdcInsnNode(Float.valueOf(4000f)));
    lazyScore.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/fs/starfarer/api/util/Misc", "getNumHostileMarkets",
        HOSTILE_MARKETS_DESC, false));
    lazyScore.add(new InsnNode(Opcodes.ICONST_2));
    lazyScore.add(new InsnNode(Opcodes.IMUL));
    lazyScore.add(new VarInsnNode(Opcodes.ISTORE, common.unsafeLocal));
    lazyScore.add(scoreReady);
    // The new join has the same locals and an empty stack as the existing
    // accepted-destination frame immediately preceding this block.
    lazyScore.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
    method.instructions.insertBefore(common.candidateStart, lazyScore);

    StrategicJumpCommon patchedCommon = inspectStrategicJumpCommon(node.name, method);
    if (inspectStrategicJumpOriginal(patchedCommon, false) != null
        || inspectStrategicJumpLazy(patchedCommon, false) == null) {
      throw mismatch("destination-first mutation did not produce the complete lazy post-state");
    }

    PatchReport report = new PatchReport();
    report.add("destination-first hostile-market guards", 1);
    return report;
  }

  // ---------------------------------------------------------------------
  // StrategicModule: destination-location jump-point index
  // ---------------------------------------------------------------------

  /**
   * Transformation surface: the initial candidate-source expression in
   * StrategicModule.findNearestSafeJumpPoint and the expired-plan clear branch in
   * updateJumpPlanTo. This patch consumes the complete destination-first post-state,
   * replaces LocationAPI.getEntities(JumpPoint.class) with the indexed runtime hook,
   * defers only an expired replacement while the index is cold, and then revalidates
   * both composed postconditions.
   */
  private PatchReport patchStrategicJumpDestinationIndex(ClassNode node) {
    MethodNode method = requireMethod(node, "findNearestSafeJumpPoint", STRATEGIC_JUMP_METHOD_DESC);
    MethodNode update = requireMethod(node, "updateJumpPlanTo", STRATEGIC_JUMP_UPDATE_METHOD_DESC);
    if (!hasPatchMarker(node, STRATEGIC_JUMP_DESTINATION_FIRST_PATCH_ID)) {
      throw mismatch("destination index requires the owned destination-first post-state");
    }
    StrategicJumpCommon common = inspectStrategicJumpCommon(node.name, method);
    if (inspectStrategicJumpOriginal(common, false) != null || inspectStrategicJumpLazy(common, false) == null) {
      throw mismatch("destination-first postcondition changed before index composition");
    }

    StrategicJumpExpiry expiry = inspectStrategicJumpExpiry(node.name, update, true);
    boolean deferred = strategicJumpExpiryDeferralState(node.name, update, expiry, false);
    boolean owned = hasPatchMarker(node, STRATEGIC_JUMP_DESTINATION_INDEX_PATCH_ID);
    StrategicJumpSource original = inspectStrategicJumpOriginalSource(method, false);
    StrategicJumpSource indexed = inspectStrategicJumpIndexedSource(method, false);
    if (owned) {
      if (indexed == null || original != null || !deferred) {
        throw mismatch("owned strategic destination index has a mixed post-state");
      }
      throw already("strategic jump source and expired-plan deferral are already installed");
    }
    if (indexed != null || deferred) {
      throw already("strategic destination-index bytecode is present without this marker");
    }
    if (original == null) { throw mismatch("vanilla StrategicModule jump-point source was not found"); }

    method.instructions.insertBefore(original.call, new VarInsnNode(Opcodes.ALOAD, argumentLocal(method, 1)));
    makeStatic(original.call, HOOKS, STRATEGIC_JUMP_INDEX_HOOK, STRATEGIC_JUMP_INDEX_HOOK_DESC);
    installStrategicJumpExpiryDeferral(node.name, update, expiry);

    StrategicJumpSource patched = inspectStrategicJumpIndexedSource(method, true);
    if (patched == null || inspectStrategicJumpOriginalSource(method, false) != null) {
      throw mismatch("strategic destination-index mutation did not reach its post-state");
    }
    StrategicJumpExpiry patchedExpiry = inspectStrategicJumpExpiry(node.name, update, true);
    if (!strategicJumpExpiryDeferralState(node.name, update, patchedExpiry, true)) {
      throw mismatch("strategic expired-plan deferral did not reach its post-state");
    }
    StrategicJumpCommon recomposed = inspectStrategicJumpCommon(node.name, method);
    if (inspectStrategicJumpOriginal(recomposed, false) != null
        || inspectStrategicJumpLazy(recomposed, false) == null) {
      throw mismatch("strategic destination index damaged destination-first semantics");
    }

    PatchReport report = new PatchReport();
    report.add("strategic destination-location jump-point source", 1);
    report.add("strategic expired-plan incremental-index deferrals", 1);
    return report;
  }

  private static void installStrategicJumpExpiryDeferral(String owner, MethodNode method, StrategicJumpExpiry expiry) {
    LabelNode continueClear = new LabelNode();
    InsnList hook = new InsnList();
    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
    hook.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "fleet", "Lcom/fs/starfarer/campaign/fleet/CampaignFleet;"));
    hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/campaign/fleet/CampaignFleet",
        "getContainingLocation", "()Lcom/fs/starfarer/campaign/BaseLocation;", false));
    hook.add(new VarInsnNode(Opcodes.ALOAD, argumentLocal(method, 0)));
    hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, STRATEGIC_JUMP_DEFER_HOOK, STRATEGIC_JUMP_DEFER_HOOK_DESC,
        false));
    hook.add(new JumpInsnNode(Opcodes.IFEQ, continueClear));
    hook.add(new InsnNode(Opcodes.RETURN));
    hook.add(continueClear);
    hook.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
    method.instructions.insertBefore(expiry.clearStart, hook);
  }

  private static StrategicJumpExpiry inspectStrategicJumpExpiry(String owner, MethodNode method, boolean required) {
    List<MethodInsnNode> findCalls =
        calls(method, Opcodes.INVOKESTATIC, owner, "findNearestSafeJumpPoint", STRATEGIC_JUMP_METHOD_DESC);
    if (findCalls.size() != 1) {
      if (required) throw mismatch("StrategicModule update findNearest call count changed");
      return null;
    }
    FieldInsnNode comparedTime = null;
    JumpInsnNode expiryJump = null;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
          || !field.owner.equals("com/fs/starfarer/campaign/ai/CampaignFleetAI$JumpPlan")
          || !field.name.equals("timeLeft") || !field.desc.equals("F")) {
        continue;
      }
      AbstractInsnNode zero = nextMeaningful(field);
      AbstractInsnNode compare = nextMeaningful(zero);
      AbstractInsnNode jump = nextMeaningful(compare);
      if (zero == null || zero.getOpcode() != Opcodes.FCONST_0 || compare == null
          || compare.getOpcode() != Opcodes.FCMPG || !(jump instanceof JumpInsnNode candidate)
          || candidate.getOpcode() != Opcodes.IFGT)
        continue;
      if (comparedTime != null) {
        if (required) throw mismatch("StrategicModule has ambiguous plan-expiry checks");
        return null;
      }
      comparedTime = field;
      expiryJump = candidate;
    }
    if (comparedTime == null || expiryJump == null) {
      if (required) throw mismatch("StrategicModule plan-expiry comparison was not found");
      return null;
    }

    int branchIndex = method.instructions.indexOf(expiryJump);
    int targetIndex = method.instructions.indexOf(expiryJump.label);
    FieldInsnNode clearWrite = null;
    VarInsnNode clearStart = null;
    for (AbstractInsnNode cursor = nextMeaningful(expiryJump); cursor != null
        && method.instructions.indexOf(cursor) < targetIndex; cursor = nextMeaningful(cursor)) {
      if (!(cursor instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
          || !field.owner.equals(owner) || !field.name.equals("currJumpPlan")
          || !field.desc.equals("Lcom/fs/starfarer/campaign/ai/CampaignFleetAI$JumpPlan;"))
        continue;
      AbstractInsnNode nullValue = previousMeaningful(field);
      AbstractInsnNode receiver = previousMeaningful(nullValue);
      if (nullValue == null || nullValue.getOpcode() != Opcodes.ACONST_NULL || !(receiver instanceof VarInsnNode load)
          || load.getOpcode() != Opcodes.ALOAD || load.var != 0)
        continue;
      if (clearWrite != null) {
        if (required) throw mismatch("StrategicModule expiry branch clears multiple plans");
        return null;
      }
      clearWrite = field;
      clearStart = load;
    }
    if (clearWrite == null || clearStart == null || branchIndex >= method.instructions.indexOf(clearStart)
        || method.instructions.indexOf(clearWrite) >= targetIndex
        || method.instructions.indexOf(findCalls.get(0)) <= targetIndex) {
      if (required) throw mismatch("StrategicModule expired-plan clear shape changed");
      return null;
    }
    return new StrategicJumpExpiry(expiryJump, clearStart, clearWrite, findCalls.get(0));
  }

  private static boolean strategicJumpExpiryDeferralState(String owner, MethodNode method, StrategicJumpExpiry expiry,
      boolean required) {
    List<MethodInsnNode> hooks =
        calls(method, Opcodes.INVOKESTATIC, HOOKS, STRATEGIC_JUMP_DEFER_HOOK, STRATEGIC_JUMP_DEFER_HOOK_DESC);
    if (hooks.size() != 1) {
      if (required) throw mismatch("StrategicModule expired-plan deferral count changed");
      return false;
    }
    MethodInsnNode hook = hooks.get(0);
    VarInsnNode requested =
        requireVar(previousMeaningful(hook), Opcodes.ALOAD, "StrategicModule deferral requested-location argument");
    MethodInsnNode containing =
        asMethod(previousMeaningful(requested), "StrategicModule deferral current-location getter");
    AbstractInsnNode fleetInsn = previousMeaningful(containing);
    if (!(fleetInsn instanceof FieldInsnNode fleet) || fleet.getOpcode() != Opcodes.GETFIELD) {
      if (required) throw mismatch("StrategicModule deferral fleet field changed");
      return false;
    }
    VarInsnNode receiver = requireVar(previousMeaningful(fleet), Opcodes.ALOAD, "StrategicModule deferral receiver");
    JumpInsnNode continueJump =
        requireJump(nextMeaningful(hook), Opcodes.IFEQ, "StrategicModule deferral continuation");
    AbstractInsnNode earlyReturn = nextMeaningful(continueJump);
    if (requested.var != argumentLocal(method, 0) || receiver.var != 0 || !fleet.owner.equals(owner)
        || !fleet.name.equals("fleet") || !fleet.desc.equals("Lcom/fs/starfarer/campaign/fleet/CampaignFleet;")
        || !callMatches(containing, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/campaign/fleet/CampaignFleet",
            "getContainingLocation", "()Lcom/fs/starfarer/campaign/BaseLocation;")
        || earlyReturn == null || earlyReturn.getOpcode() != Opcodes.RETURN
        || method.instructions.indexOf(hook) <= method.instructions.indexOf(expiry.expiryJump)
        || method.instructions.indexOf(earlyReturn) >= method.instructions.indexOf(expiry.clearStart)
        || method.instructions.indexOf(continueJump.label) >= method.instructions.indexOf(expiry.clearStart)
        || nextMeaningful(continueJump.label) != expiry.clearStart) {
      if (required) throw mismatch("StrategicModule expired-plan deferral shape changed");
      return false;
    }
    return true;
  }

  private static StrategicJumpSource inspectStrategicJumpOriginalSource(MethodNode method, boolean required) {
    List<MethodInsnNode> originalCalls = calls(method, Opcodes.INVOKEINTERFACE,
        "com/fs/starfarer/api/campaign/LocationAPI", "getEntities", "(Ljava/lang/Class;)Ljava/util/List;");
    int indexedCalls =
        countCalls(method, Opcodes.INVOKESTATIC, HOOKS, STRATEGIC_JUMP_INDEX_HOOK, STRATEGIC_JUMP_INDEX_HOOK_DESC);
    if (originalCalls.size() != 1 || indexedCalls != 0) {
      if (required) throw mismatch("StrategicModule original jump-point source count changed");
      return null;
    }
    MethodInsnNode call = originalCalls.get(0);
    AbstractInsnNode argument = previousMeaningful(call);
    if (!(argument instanceof LdcInsnNode ldc) || !(ldc.cst instanceof Type type)
        || !type.getInternalName().equals(JUMP_POINT)) {
      if (required) throw mismatch("StrategicModule getEntities class argument changed");
      return null;
    }
    MethodInsnNode location =
        asMethod(previousMeaningful(argument), "StrategicModule source containing-location getter");
    VarInsnNode fleet =
        requireVar(previousMeaningful(location), Opcodes.ALOAD, "StrategicModule source fleet argument");
    if (fleet.var != argumentLocal(method, 0)
        || !callMatches(location, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/CampaignFleetAPI",
            "getContainingLocation", "()Lcom/fs/starfarer/api/campaign/LocationAPI;")) {
      if (required) throw mismatch("StrategicModule source receiver changed");
      return null;
    }
    requireVar(nextMeaningful(call), Opcodes.ASTORE, "StrategicModule source result local");
    return new StrategicJumpSource(call, argument);
  }

  private static StrategicJumpSource inspectStrategicJumpIndexedSource(MethodNode method, boolean required) {
    List<MethodInsnNode> indexedCalls =
        calls(method, Opcodes.INVOKESTATIC, HOOKS, STRATEGIC_JUMP_INDEX_HOOK, STRATEGIC_JUMP_INDEX_HOOK_DESC);
    int originalCalls = countCalls(method, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/LocationAPI",
        "getEntities", "(Ljava/lang/Class;)Ljava/util/List;");
    if (indexedCalls.size() != 1 || originalCalls != 0) {
      if (required) throw mismatch("StrategicModule indexed jump-point source count changed");
      return null;
    }
    MethodInsnNode call = indexedCalls.get(0);
    VarInsnNode target =
        requireVar(previousMeaningful(call), Opcodes.ALOAD, "StrategicModule indexed destination argument");
    AbstractInsnNode typeArgument = previousMeaningful(target);
    if (!(typeArgument instanceof LdcInsnNode ldc) || !(ldc.cst instanceof Type type)
        || !type.getInternalName().equals(JUMP_POINT)) {
      if (required) throw mismatch("StrategicModule indexed class argument changed");
      return null;
    }
    MethodInsnNode location =
        asMethod(previousMeaningful(typeArgument), "StrategicModule indexed containing-location getter");
    VarInsnNode fleet =
        requireVar(previousMeaningful(location), Opcodes.ALOAD, "StrategicModule indexed fleet argument");
    if (target.var != argumentLocal(method, 1) || fleet.var != argumentLocal(method, 0)
        || !callMatches(location, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/CampaignFleetAPI",
            "getContainingLocation", "()Lcom/fs/starfarer/api/campaign/LocationAPI;")) {
      if (required) throw mismatch("StrategicModule indexed source arguments changed");
      return null;
    }
    requireVar(nextMeaningful(call), Opcodes.ASTORE, "StrategicModule indexed source result local");
    return new StrategicJumpSource(call, typeArgument);
  }

  // ---------------------------------------------------------------------
  // Topology mutation hooks for the incremental strategic jump index
  // ---------------------------------------------------------------------

  private PatchReport patchStrategicJumpDestinationInvalidation(ClassNode node) {
    MethodNode add =
        requireMethod(node, "addDestination", "(Lcom/fs/starfarer/api/campaign/JumpPointAPI$JumpDestination;)V");
    MethodNode clear = requireMethod(node, "clearDestinations", "()V");
    MethodNode remove =
        requireMethod(node, "removeDestination", "(Lcom/fs/starfarer/api/campaign/SectorEntityToken;)V");
    boolean owned = hasPatchMarker(node, STRATEGIC_JUMP_DESTINATION_INDEX_PATCH_ID);
    boolean addHook = strategicJumpPointHookState(add, true, false);
    boolean clearHook = strategicJumpPointHookState(clear, false, false);
    boolean removeHook = strategicJumpPointHookState(remove, false, false);
    int installed = (addHook ? 1 : 0) + (clearHook ? 1 : 0) + (removeHook ? 1 : 0);
    if (owned) {
      if (installed != 3) { throw mismatch("owned JumpPoint topology hooks are incomplete"); }
      throw already("JumpPoint topology mutations already update the strategic index");
    }
    if (installed != 0) { throw already("foreign JumpPoint topology hooks are already present"); }

    requireSingleReturn(add, "JumpPoint.addDestination");
    requireSingleReturn(clear, "JumpPoint.clearDestinations");
    requireSingleReturn(remove, "JumpPoint.removeDestination");
    requireCount("JumpPoint.addDestination List.add calls",
        countCalls(add, Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z"), 1);
    requireCount("JumpPoint.clearDestinations List.clear calls",
        countCalls(clear, Opcodes.INVOKEINTERFACE, "java/util/List", "clear", "()V"), 1);
    requireCount("JumpPoint.removeDestination List.remove calls",
        countCalls(remove, Opcodes.INVOKEINTERFACE, "java/util/List", "remove", "(Ljava/lang/Object;)Z"), 1);

    installStrategicJumpPointHook(add, true);
    installStrategicJumpPointHook(clear, false);
    installStrategicJumpPointHook(remove, false);
    strategicJumpPointHookState(add, true, true);
    strategicJumpPointHookState(clear, false, true);
    strategicJumpPointHookState(remove, false, true);

    PatchReport report = new PatchReport();
    report.add("JumpPoint incremental topology epilogues", 3);
    return report;
  }

  private static void installStrategicJumpPointHook(MethodNode method, boolean added) {
    AbstractInsnNode exit = requireSingleReturn(method, "JumpPoint." + method.name);
    InsnList hook = new InsnList();
    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
    if (added) hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
    hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
        added ? STRATEGIC_JUMP_ADDED_HOOK : STRATEGIC_JUMP_INVALIDATE_HOOK,
        added ? STRATEGIC_JUMP_ADDED_HOOK_DESC : STRATEGIC_JUMP_INVALIDATE_HOOK_DESC, false));
    method.instructions.insertBefore(exit, hook);
  }

  private static boolean strategicJumpPointHookState(MethodNode method, boolean added, boolean required) {
    String hookName = added ? STRATEGIC_JUMP_ADDED_HOOK : STRATEGIC_JUMP_INVALIDATE_HOOK;
    String hookDesc = added ? STRATEGIC_JUMP_ADDED_HOOK_DESC : STRATEGIC_JUMP_INVALIDATE_HOOK_DESC;
    List<MethodInsnNode> hooks = calls(method, Opcodes.INVOKESTATIC, HOOKS, hookName, hookDesc);
    if (hooks.size() != 1) {
      if (required) throw mismatch("JumpPoint hook count changed in " + method.name + method.desc);
      return false;
    }
    AbstractInsnNode exit = requireSingleReturn(method, "JumpPoint." + method.name);
    MethodInsnNode hook = hooks.get(0);
    if (nextMeaningful(hook) != exit) {
      if (required) throw mismatch("JumpPoint hook is not the normal epilogue in " + method.name + method.desc);
      return false;
    }
    AbstractInsnNode previous = previousMeaningful(hook);
    if (added) {
      VarInsnNode destination = requireVar(previous, Opcodes.ALOAD, "JumpPoint.addDestination hook argument");
      VarInsnNode receiver =
          requireVar(previousMeaningful(destination), Opcodes.ALOAD, "JumpPoint.addDestination hook receiver");
      if (destination.var != 1 || receiver.var != 0) {
        if (required) throw mismatch("JumpPoint.addDestination hook operands changed");
        return false;
      }
    } else {
      VarInsnNode receiver = requireVar(previous, Opcodes.ALOAD, "JumpPoint mutation hook receiver");
      if (receiver.var != 0) {
        if (required) throw mismatch("JumpPoint mutation hook receiver changed");
        return false;
      }
    }
    return true;
  }

  private PatchReport patchStrategicJumpDestinationRetarget(ClassNode node) {
    MethodNode method = requireMethod(node, "setDestination", "(Lcom/fs/starfarer/api/campaign/SectorEntityToken;)V");
    boolean owned = hasPatchMarker(node, STRATEGIC_JUMP_DESTINATION_INDEX_PATCH_ID);
    boolean installed = strategicJumpRetargetHookState(method, false);
    if (owned) {
      if (!installed) throw mismatch("owned JumpDestination retarget hook is missing");
      throw already("JumpDestination.setDestination already updates the strategic index");
    }
    if (installed) { throw already("foreign JumpDestination retarget hook is already present"); }

    AbstractInsnNode exit = requireSingleReturn(method, "JumpDestination.setDestination");
    FieldInsnNode write = null;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
          || !field.owner.equals(JUMP_DESTINATION) || !field.name.equals("destination")
          || !field.desc.equals("Lcom/fs/starfarer/api/campaign/SectorEntityToken;")) {
        continue;
      }
      if (write != null) throw mismatch("JumpDestination destination field has multiple writes");
      write = field;
    }
    if (write == null || method.instructions.indexOf(write) > method.instructions.indexOf(exit)) {
      throw mismatch("JumpDestination destination field write was not found before return");
    }
    InsnList hook = new InsnList();
    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
    hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, STRATEGIC_JUMP_RETARGET_HOOK,
        STRATEGIC_JUMP_RETARGET_HOOK_DESC, false));
    method.instructions.insertBefore(exit, hook);
    strategicJumpRetargetHookState(method, true);

    PatchReport report = new PatchReport();
    report.add("JumpDestination retarget epilogues", 1);
    return report;
  }

  private static boolean strategicJumpRetargetHookState(MethodNode method, boolean required) {
    List<MethodInsnNode> hooks =
        calls(method, Opcodes.INVOKESTATIC, HOOKS, STRATEGIC_JUMP_RETARGET_HOOK, STRATEGIC_JUMP_RETARGET_HOOK_DESC);
    if (hooks.size() != 1) {
      if (required) throw mismatch("JumpDestination retarget hook count changed");
      return false;
    }
    AbstractInsnNode exit = requireSingleReturn(method, "JumpDestination.setDestination");
    MethodInsnNode hook = hooks.get(0);
    VarInsnNode receiver =
        requireVar(previousMeaningful(hook), Opcodes.ALOAD, "JumpDestination retarget hook receiver");
    if (receiver.var != 0 || nextMeaningful(hook) != exit) {
      if (required) throw mismatch("JumpDestination retarget hook epilogue changed");
      return false;
    }
    return true;
  }

  private PatchReport patchStrategicJumpLocationMutation(ClassNode node) {
    MethodNode add = requireMethod(node, "addObjectReal", "(Ljava/lang/Object;)V");
    MethodNode remove = requireMethod(node, "removeObjectReal", "(Ljava/lang/Object;)V");
    boolean owned = hasPatchMarker(node, STRATEGIC_JUMP_DESTINATION_INDEX_PATCH_ID);
    boolean addHook = strategicJumpLocationHookState(add, false);
    boolean removeHook = strategicJumpLocationHookState(remove, false);
    if (owned) {
      if (!addHook || !removeHook) { throw mismatch("owned BaseLocation topology hooks are incomplete"); }
      throw already("BaseLocation entity mutations already update the strategic index");
    }
    if (addHook || removeHook) { throw already("foreign BaseLocation topology hook is already present"); }

    requireSingleReturn(add, "BaseLocation.addObjectReal");
    requireSingleReturn(remove, "BaseLocation.removeObjectReal");
    requireCount("BaseLocation ObjectRepository.add calls", countCalls(add, Opcodes.INVOKEVIRTUAL,
        "com/fs/util/container/repo/ObjectRepository", "add", "(Ljava/lang/Object;)Z"), 1);
    requireCount("BaseLocation ObjectRepository.remove calls", countCalls(remove, Opcodes.INVOKEVIRTUAL,
        "com/fs/util/container/repo/ObjectRepository", "remove", "(Ljava/lang/Object;)Z"), 1);
    installStrategicJumpLocationHook(add);
    installStrategicJumpLocationHook(remove);
    strategicJumpLocationHookState(add, true);
    strategicJumpLocationHookState(remove, true);

    PatchReport report = new PatchReport();
    report.add("BaseLocation jump-source mutation epilogues", 2);
    return report;
  }

  private static void installStrategicJumpLocationHook(MethodNode method) {
    AbstractInsnNode exit = requireSingleReturn(method, "BaseLocation." + method.name);
    InsnList hook = new InsnList();
    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
    hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
    hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, STRATEGIC_JUMP_LOCATION_HOOK,
        STRATEGIC_JUMP_LOCATION_HOOK_DESC, false));
    method.instructions.insertBefore(exit, hook);
  }

  private static boolean strategicJumpLocationHookState(MethodNode method, boolean required) {
    List<MethodInsnNode> hooks =
        calls(method, Opcodes.INVOKESTATIC, HOOKS, STRATEGIC_JUMP_LOCATION_HOOK, STRATEGIC_JUMP_LOCATION_HOOK_DESC);
    if (hooks.size() != 1) {
      if (required) throw mismatch("BaseLocation topology hook count changed in " + method.name + method.desc);
      return false;
    }
    AbstractInsnNode exit = requireSingleReturn(method, "BaseLocation." + method.name);
    MethodInsnNode hook = hooks.get(0);
    VarInsnNode entity = requireVar(previousMeaningful(hook), Opcodes.ALOAD, "BaseLocation topology entity");
    VarInsnNode receiver = requireVar(previousMeaningful(entity), Opcodes.ALOAD, "BaseLocation topology receiver");
    if (receiver.var != 0 || entity.var != 1 || nextMeaningful(hook) != exit) {
      if (required) throw mismatch("BaseLocation topology hook epilogue changed");
      return false;
    }
    return true;
  }


  private static StrategicJumpCommon inspectStrategicJumpCommon(String owner, MethodNode method) {
    requireCount("StrategicModule hostile-market calls", countCalls(method, Opcodes.INVOKESTATIC,
        "com/fs/starfarer/api/util/Misc", "getNumHostileMarkets", HOSTILE_MARKETS_DESC), 1);
    requireCount("StrategicModule destination list calls", countCalls(method, Opcodes.INVOKEVIRTUAL,
        "com/fs/starfarer/campaign/JumpPoint", "getDestinations", "()Ljava/util/List;"), 1);
    requireCount("StrategicModule unsafe-fleet calls",
        countCalls(method, Opcodes.INVOKESTATIC, owner, "getNumUnsafeFleetsAround", UNSAFE_FLEETS_DESC), 1);
    requireCount("StrategicModule candidate sorting", countCalls(method, Opcodes.INVOKESTATIC, "java/util/Collections",
        "sort", "(Ljava/util/List;Ljava/util/Comparator;)V"), 1);

    MethodInsnNode destinations = calls(method, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/campaign/JumpPoint",
        "getDestinations", "()Ljava/util/List;").get(0);
    VarInsnNode jumpLoad =
        requireVar(previousMeaningful(destinations), Opcodes.ALOAD, "StrategicModule jump-point receiver");
    int jumpPointLocal = jumpLoad.var;

    MethodInsnNode listIterator = asMethod(nextMeaningful(destinations), "StrategicModule destination iterator");
    requireCall(listIterator, Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;",
        "StrategicModule destination iterator");
    VarInsnNode iteratorStore =
        requireVar(nextMeaningful(listIterator), Opcodes.ASTORE, "StrategicModule destination iterator local");

    MethodInsnNode iteratorNext = null;
    for (AbstractInsnNode cursor = nextMeaningful(iteratorStore); cursor != null; cursor = nextMeaningful(cursor)) {
      if (!(cursor instanceof MethodInsnNode call)
          || !callMatches(call, Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;"))
        continue;
      VarInsnNode receiver =
          requireVar(previousMeaningful(call), Opcodes.ALOAD, "StrategicModule destination Iterator.next receiver");
      if (receiver.var == iteratorStore.var) {
        iteratorNext = call;
        break;
      }
    }
    if (iteratorNext == null) throw mismatch("destination Iterator.next call is missing");
    AbstractInsnNode cast = nextMeaningful(iteratorNext);
    if (!(cast instanceof TypeInsnNode type) || cast.getOpcode() != Opcodes.CHECKCAST
        || !type.desc.equals("com/fs/starfarer/api/campaign/JumpPointAPI$JumpDestination")) {
      throw mismatch("destination Iterator.next result cast changed");
    }
    VarInsnNode destinationStore =
        requireVar(nextMeaningful(cast), Opcodes.ASTORE, "StrategicModule destination local");
    int destinationLocal = destinationStore.var;

    VarInsnNode nullReceiver =
        requireVar(nextMeaningful(destinationStore), Opcodes.ALOAD, "StrategicModule null destination receiver");
    if (nullReceiver.var != destinationLocal) { throw mismatch("null destination check reads a different local"); }
    MethodInsnNode nullGet = asMethod(nextMeaningful(nullReceiver), "StrategicModule null destination getter");
    requireJumpDestinationGetter(nullGet);
    JumpInsnNode nullJump =
        requireJump(nextMeaningful(nullGet), Opcodes.IFNULL, "StrategicModule null destination branch");

    VarInsnNode exactReceiver =
        requireVar(nextMeaningful(nullJump), Opcodes.ALOAD, "StrategicModule exact destination receiver");
    if (exactReceiver.var != destinationLocal) { throw mismatch("exact destination check reads a different local"); }
    MethodInsnNode exactGet = asMethod(nextMeaningful(exactReceiver), "StrategicModule exact destination getter");
    requireJumpDestinationGetter(exactGet);
    MethodInsnNode exactLocation = asMethod(nextMeaningful(exactGet), "StrategicModule exact destination location");
    requireContainingLocationGetter(exactLocation);
    VarInsnNode targetLoad =
        requireVar(nextMeaningful(exactLocation), Opcodes.ALOAD, "StrategicModule requested location local");
    if (targetLoad.var != 1) { throw mismatch("requested LocationAPI is not read from static argument local 1"); }
    JumpInsnNode exactJump =
        requireJump(nextMeaningful(targetLoad), Opcodes.IF_ACMPEQ, "StrategicModule exact destination branch");

    VarInsnNode fallbackLoad =
        requireVar(nextMeaningful(exactJump), Opcodes.ILOAD, "StrategicModule hyperspace fallback flag");
    JumpInsnNode fallbackDisabled =
        requireJump(nextMeaningful(fallbackLoad), Opcodes.IFEQ, "StrategicModule fallback-disabled branch");
    requireVar(nextMeaningful(fallbackDisabled), Opcodes.ALOAD, "StrategicModule hyperspace local");
    VarInsnNode fallbackReceiver = requireVar(nextMeaningful(nextMeaningful(fallbackDisabled)), Opcodes.ALOAD,
        "StrategicModule fallback destination receiver");
    if (fallbackReceiver.var != destinationLocal) {
      throw mismatch("fallback destination check reads a different local");
    }
    MethodInsnNode fallbackGet =
        asMethod(nextMeaningful(fallbackReceiver), "StrategicModule fallback destination getter");
    requireJumpDestinationGetter(fallbackGet);
    MethodInsnNode fallbackLocation =
        asMethod(nextMeaningful(fallbackGet), "StrategicModule fallback destination location");
    requireContainingLocationGetter(fallbackLocation);
    JumpInsnNode fallbackJump =
        requireJump(nextMeaningful(fallbackLocation), Opcodes.IF_ACMPNE, "StrategicModule fallback destination branch");

    if (nullJump.label != fallbackDisabled.label || nullJump.label != fallbackJump.label) {
      throw mismatch("destination rejection branches no longer share one continue target");
    }
    AbstractInsnNode acceptedEntry = nextMeaningful(exactJump.label);
    if (acceptedEntry == null || acceptedEntry != nextMeaningful(fallbackJump)) {
      throw mismatch("exact and fallback destination branches do not converge");
    }
    AbstractInsnNode candidateStart = acceptedEntry;
    if (!(candidateStart instanceof VarInsnNode candidateLoad) || candidateLoad.getOpcode() != Opcodes.ALOAD
        || candidateLoad.var != jumpPointLocal
        || !(nextMeaningful(candidateLoad) instanceof MethodInsnNode candidateCall)
        || !callMatches(candidateCall, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/campaign/JumpPoint", "getLocation",
            "()Lorg/lwjgl/util/vector/Vector2f;")) {
      candidateStart = null;
      for (AbstractInsnNode cursor = acceptedEntry; cursor != null && cursor != nullJump.label; cursor =
          nextMeaningful(cursor)) {
        if (!(cursor instanceof VarInsnNode candidateLoad) || candidateLoad.getOpcode() != Opcodes.ALOAD
            || candidateLoad.var != jumpPointLocal)
          continue;
        AbstractInsnNode next = nextMeaningful(candidateLoad);
        if (next instanceof MethodInsnNode candidateCall && callMatches(candidateCall, Opcodes.INVOKEVIRTUAL,
            "com/fs/starfarer/campaign/JumpPoint", "getLocation", "()Lorg/lwjgl/util/vector/Vector2f;")) {
          candidateStart = candidateLoad;
          break;
        }
      }
      if (candidateStart == null) { throw mismatch("accepted destination path no longer reaches jump-point distance"); }
    }

    MethodInsnNode unsafeCall =
        calls(method, Opcodes.INVOKESTATIC, owner, "getNumUnsafeFleetsAround", UNSAFE_FLEETS_DESC).get(0);
    if (method.instructions.indexOf(unsafeCall) <= method.instructions.indexOf(candidateStart)
        || method.instructions.indexOf(unsafeCall) >= method.instructions.indexOf(nullJump.label)) {
      throw mismatch("unsafe-fleet score moved outside the accepted destination path");
    }
    VarInsnNode unsafeLoad =
        requireVar(nextMeaningful(unsafeCall), Opcodes.ILOAD, "StrategicModule hostile-market candidate score");
    AbstractInsnNode sum = nextMeaningful(unsafeLoad);
    if (sum == null || sum.getOpcode() != Opcodes.IADD) {
      throw mismatch("hostile-market score is not added to the unsafe-fleet score");
    }
    AbstractInsnNode scoreStore = nextMeaningful(sum);
    if (!(scoreStore instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
        || !field.desc.equals("I")) {
      throw mismatch("combined unsafe score is not stored in the candidate int field");
    }

    int fleetLocal = 0;
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    Frame<SourceValue> unsafeFrame = frameBefore(method, unsafeCall, frames);
    if (unsafeFrame.getStackSize() < 3
        || !sourceIsLocal(unsafeFrame.getStack(unsafeFrame.getStackSize() - 3), Opcodes.ALOAD, fleetLocal)
        || !sourceIsLocal(unsafeFrame.getStack(unsafeFrame.getStackSize() - 2), Opcodes.ALOAD, jumpPointLocal)
        || !sourceIsFloatConstant(unsafeFrame.getStack(unsafeFrame.getStackSize() - 1), 750f)) {
      throw mismatch("unsafe-fleet call arguments changed");
    }

    return new StrategicJumpCommon(method, destinations, acceptedEntry, candidateStart, jumpPointLocal, fleetLocal,
        unsafeLoad.var);
  }

  private static StrategicJumpOriginal inspectStrategicJumpOriginal(StrategicJumpCommon common, boolean required) {
    MethodNode method = common.method;
    MethodInsnNode hostile = calls(method, Opcodes.INVOKESTATIC, "com/fs/starfarer/api/util/Misc",
        "getNumHostileMarkets", HOSTILE_MARKETS_DESC).get(0);
    if (method.instructions.indexOf(hostile) > method.instructions.indexOf(common.destinations)) {
      if (required) throw mismatch("hostile-market call is not in the vanilla eager position");
      return null;
    }

    AbstractInsnNode radiusInsn = previousMeaningful(hostile);
    if (!(radiusInsn instanceof LdcInsnNode radius) || !isFloat(radius, 4000f)) {
      if (required) throw mismatch("hostile-market radius changed");
      return null;
    }
    VarInsnNode jumpLoad =
        requireVar(previousMeaningful(radius), Opcodes.ALOAD, "StrategicModule eager jump-point argument");
    MethodInsnNode faction = asMethod(previousMeaningful(jumpLoad), "StrategicModule eager faction getter");
    VarInsnNode fleetLoad =
        requireVar(previousMeaningful(faction), Opcodes.ALOAD, "StrategicModule eager fleet receiver");
    if (jumpLoad.var != common.jumpPointLocal || fleetLoad.var != common.fleetLocal
        || !callMatches(faction, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/CampaignFleetAPI",
            "getFaction", "()Lcom/fs/starfarer/api/campaign/FactionAPI;")) {
      if (required) throw mismatch("hostile-market eager arguments changed");
      return null;
    }
    VarInsnNode firstStore =
        requireVar(nextMeaningful(hostile), Opcodes.ISTORE, "StrategicModule eager hostile score local");
    VarInsnNode multiplyLoad =
        requireVar(nextMeaningful(firstStore), Opcodes.ILOAD, "StrategicModule eager hostile score multiplier load");
    AbstractInsnNode two = nextMeaningful(multiplyLoad);
    AbstractInsnNode multiply = nextMeaningful(two);
    VarInsnNode secondStore =
        requireVar(nextMeaningful(multiply), Opcodes.ISTORE, "StrategicModule eager hostile score result");
    if (firstStore.var != common.unsafeLocal || multiplyLoad.var != common.unsafeLocal
        || secondStore.var != common.unsafeLocal || !isIntegerLdc(two, 2) || multiply == null
        || multiply.getOpcode() != Opcodes.IMUL
        || nextMeaningful(secondStore) != previousMeaningful(common.destinations)) {
      if (required) throw mismatch("hostile-market eager score sequence changed");
      return null;
    }
    return new StrategicJumpOriginal(fleetLoad, secondStore);
  }

  private static StrategicJumpLazy inspectStrategicJumpLazy(StrategicJumpCommon common, boolean required) {
    MethodNode method = common.method;
    MethodInsnNode hostile = calls(method, Opcodes.INVOKESTATIC, "com/fs/starfarer/api/util/Misc",
        "getNumHostileMarkets", HOSTILE_MARKETS_DESC).get(0);
    if (method.instructions.indexOf(hostile) < method.instructions.indexOf(common.destinations)) {
      if (required) throw mismatch("hostile-market call remains before getDestinations");
      return null;
    }

    VarInsnNode destinationReceiver =
        requireVar(previousMeaningful(common.destinations), Opcodes.ALOAD, "StrategicModule lazy destination receiver");
    VarInsnNode sentinelStore =
        requireVar(previousMeaningful(destinationReceiver), Opcodes.ISTORE, "StrategicModule lazy sentinel local");
    AbstractInsnNode sentinel = previousMeaningful(sentinelStore);
    if (destinationReceiver.var != common.jumpPointLocal || sentinelStore.var != common.unsafeLocal
        || !isIntegerLdc(sentinel, STRATEGIC_JUMP_UNCOMPUTED)) {
      if (required) throw mismatch("lazy hostile-market sentinel initialization changed");
      return null;
    }

    AbstractInsnNode radiusInsn = previousMeaningful(hostile);
    if (!(radiusInsn instanceof LdcInsnNode radius) || !isFloat(radius, 4000f)) {
      if (required) throw mismatch("lazy hostile-market radius changed");
      return null;
    }
    VarInsnNode jumpLoad =
        requireVar(previousMeaningful(radius), Opcodes.ALOAD, "StrategicModule lazy jump-point argument");
    MethodInsnNode faction = asMethod(previousMeaningful(jumpLoad), "StrategicModule lazy faction getter");
    VarInsnNode fleetLoad =
        requireVar(previousMeaningful(faction), Opcodes.ALOAD, "StrategicModule lazy fleet receiver");
    JumpInsnNode guard =
        requireJump(previousMeaningful(fleetLoad), Opcodes.IF_ICMPNE, "StrategicModule lazy score guard");
    AbstractInsnNode guardSentinel = previousMeaningful(guard);
    VarInsnNode guardLoad =
        requireVar(previousMeaningful(guardSentinel), Opcodes.ILOAD, "StrategicModule lazy score guard local");
    if (jumpLoad.var != common.jumpPointLocal || fleetLoad.var != common.fleetLocal
        || guardLoad.var != common.unsafeLocal || !isIntegerLdc(guardSentinel, STRATEGIC_JUMP_UNCOMPUTED)
        || guardLoad != common.acceptedEntry || nextMeaningful(guard.label) != common.candidateStart
        || !callMatches(faction, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/CampaignFleetAPI",
            "getFaction", "()Lcom/fs/starfarer/api/campaign/FactionAPI;")) {
      if (required) throw mismatch("lazy hostile-market guard or arguments changed");
      return null;
    }

    AbstractInsnNode two = nextMeaningful(hostile);
    AbstractInsnNode multiply = nextMeaningful(two);
    VarInsnNode store =
        requireVar(nextMeaningful(multiply), Opcodes.ISTORE, "StrategicModule lazy hostile score result");
    if (!isIntegerLdc(two, 2) || multiply == null || multiply.getOpcode() != Opcodes.IMUL
        || store.var != common.unsafeLocal || nextMeaningful(store) != common.candidateStart) {
      if (required) throw mismatch("lazy hostile-market score sequence changed");
      return null;
    }
    return new StrategicJumpLazy(sentinelStore, guard, store);
  }

  private static void requireJumpDestinationGetter(MethodInsnNode call) {
    requireCall(call, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/api/campaign/JumpPointAPI$JumpDestination",
        "getDestination", "()Lcom/fs/starfarer/api/campaign/SectorEntityToken;",
        "StrategicModule JumpDestination.getDestination");
  }

  private static void requireContainingLocationGetter(MethodInsnNode call) {
    requireCall(call, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/SectorEntityToken",
        "getContainingLocation", "()Lcom/fs/starfarer/api/campaign/LocationAPI;",
        "StrategicModule destination containing location");
  }


  private record StrategicJumpCommon(MethodNode method, MethodInsnNode destinations, AbstractInsnNode acceptedEntry,
      AbstractInsnNode candidateStart, int jumpPointLocal, int fleetLocal, int unsafeLocal) {
  }

  private record StrategicJumpOriginal(AbstractInsnNode start, AbstractInsnNode end) {
  }

  private record StrategicJumpLazy(VarInsnNode sentinelStore, JumpInsnNode guard, VarInsnNode scoreStore) {
  }

  private record StrategicJumpSource(MethodInsnNode call, AbstractInsnNode argument) {
  }

  private record StrategicJumpExpiry(JumpInsnNode expiryJump, VarInsnNode clearStart, FieldInsnNode clearWrite,
      MethodInsnNode findCall) {
  }

  private PatchReport patchCampaignSnapshotReuse(ClassNode node) {
    String methodDesc = "(FLcom/fs/starfarer/util/A/new;)V";
    String hookDesc = "(Ljava/util/Collection;)Ljava/util/List;";
    AllocationSpec advanceSnapshots = new AllocationSpec("java/util/ArrayList", "(Ljava/util/Collection;)V",
        "borrowLocationAdvanceSnapshot", hookDesc, 3);
    AllocationSpec pausedSnapshots = new AllocationSpec("java/util/ArrayList", "(Ljava/util/Collection;)V",
        "borrowPausedLocationSnapshot", hookDesc, 2);

    MethodNode advance = requireMethod(node, "advance", methodDesc);
    boolean advanceScopeInstalled = ensureScratchScope(node.name, advance, "BaseLocation.advance");
    int advanceChanged = replaceSnapshotAllocationGroup(node.name, advance, advanceSnapshots, advanceScopeInstalled,
        "BaseLocation.advance defensive snapshots");

    MethodNode paused = requireMethod(node, "advanceEvenIfPaused", methodDesc);
    boolean pausedScopeInstalled = ensureScratchScope(node.name, paused, "BaseLocation.advanceEvenIfPaused");
    int pausedChanged = replaceSnapshotAllocationGroup(node.name, paused, pausedSnapshots, pausedScopeInstalled,
        "BaseLocation.advanceEvenIfPaused defensive snapshots");

    PatchReport report = new PatchReport();
    report.add("reusable BaseLocation defensive snapshots", advanceChanged + pausedChanged);
    return report;
  }

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

  private PatchReport patchEmptyMemoryAdvanceFastPath(ClassNode node) {
    MethodNode advance = requireMethod(node, "advance", "(F)V");
    FieldNode expireField = requireField(node, "expire", "Ljava/util/List;");
    FieldNode requireField = requireField(node, "require", "Ljava/util/LinkedHashMap;");

    int legacyExpireHooks = countCalls(advance, Opcodes.INVOKESTATIC, HOOKS, "memoryExpireIterator",
        "(Ljava/util/List;)Ljava/util/Iterator;");
    int legacyRequireHooks = countCalls(advance, Opcodes.INVOKESTATIC, HOOKS, "memoryRequireIterator",
        "(Ljava/util/Collection;)Ljava/util/Iterator;");
    if (legacyExpireHooks != 0 || legacyRequireHooks != 0) {
      throw mismatch("Memory.advance contains the retired iterator-hook implementation");
    }

    List<MethodInsnNode> expireIterators =
        calls(advance, Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;");
    List<MethodInsnNode> requireIterators =
        calls(advance, Opcodes.INVOKEINTERFACE, "java/util/Collection", "iterator", "()Ljava/util/Iterator;");
    requireCount("Memory.advance expire iterator", expireIterators.size(), 1);
    requireCount("Memory.advance require iterator", requireIterators.size(), 1);

    Frame<SourceValue>[] frames = sourceFrames(node.name, advance);
    FieldInsnNode expireSource = requireSourceField(receiverSource(advance, expireIterators.get(0), frames),
        Opcodes.GETFIELD, node.name, "Ljava/util/List;", "Memory.advance expire iterator source");
    if (!expireSource.name.equals(expireField.name)) {
      throw mismatch("Memory.advance expire iterator no longer reads expire");
    }
    MethodInsnNode values =
        requireSourceMethod(receiverSource(advance, requireIterators.get(0), frames), Opcodes.INVOKEVIRTUAL,
            "java/util/LinkedHashMap", "values", "()Ljava/util/Collection;", "Memory.advance require values view");
    FieldInsnNode requireSource = requireSourceField(receiverSource(advance, values, frames), Opcodes.GETFIELD,
        node.name, LINKED_MAP_DESC, "Memory.advance require values source");
    if (!requireSource.name.equals(requireField.name)) {
      throw mismatch("Memory.advance require iterator no longer reads require");
    }

    int expireEmptyCalls = countCalls(advance, Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z");
    int requireEmptyCalls = countCalls(advance, Opcodes.INVOKEVIRTUAL, "java/util/LinkedHashMap", "isEmpty", "()Z");
    boolean guarded = expireEmptyCalls == 1 && requireEmptyCalls == 1
        && hasMemoryEmptyGuard(node.name, advance, expireField.name, requireField.name);
    if ((expireEmptyCalls != 0 || requireEmptyCalls != 0) && !guarded) {
      throw mismatch("Memory.advance has a foreign/partial empty guard");
    }

    List<MethodInsnNode> getInstances = calls(advance, Opcodes.INVOKESTATIC, "com/fs/starfarer/campaign/CampaignEngine",
        "getInstance", "()Lcom/fs/starfarer/campaign/CampaignEngine;");
    requireCount("Memory.advance CampaignEngine.getInstance calls", getInstances.size(), 2);
    MethodInsnNode paused =
        only(calls(advance, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/campaign/CampaignEngine", "isPaused", "()Z"),
            "Memory.advance pause check");
    MethodInsnNode pauseSource = requireSourceMethod(receiverSource(advance, paused, frames), Opcodes.INVOKESTATIC,
        "com/fs/starfarer/campaign/CampaignEngine", "getInstance", "()Lcom/fs/starfarer/campaign/CampaignEngine;",
        "Memory.advance pause receiver");
    if (pauseSource != getInstances.get(0)) { throw mismatch("Memory.advance pause check moved after timed work"); }
    MethodInsnNode getClock = only(calls(advance, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/campaign/CampaignEngine",
        "getClock", "()Lcom/fs/starfarer/campaign/CampaignClock;"), "Memory.advance clock lookup");
    MethodInsnNode clockSource = requireSourceMethod(receiverSource(advance, getClock, frames), Opcodes.INVOKESTATIC,
        "com/fs/starfarer/campaign/CampaignEngine", "getInstance", "()Lcom/fs/starfarer/campaign/CampaignEngine;",
        "Memory.advance clock receiver");
    MethodInsnNode workStart = getInstances.get(1);
    if (clockSource != workStart) {
      throw mismatch("Memory.advance timed work no longer starts from the second engine read");
    }
    MethodInsnNode convert =
        only(calls(advance, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/campaign/CampaignClock", "convertToDays", "(F)F"),
            "Memory.advance clock conversion");
    if (requireSourceMethod(receiverSource(advance, convert, frames), Opcodes.INVOKEVIRTUAL,
        "com/fs/starfarer/campaign/CampaignEngine", "getClock", "()Lcom/fs/starfarer/campaign/CampaignClock;",
        "Memory.advance conversion receiver") != getClock
        || !sourceIsLocal(argumentSource(advance, convert, frames, 0), Opcodes.FLOAD, 1)) {
      throw mismatch("Memory.advance convertToDays input changed");
    }
    AbstractInsnNode convertedStore = nextMeaningful(convert);
    if (!(convertedStore instanceof VarInsnNode store) || store.getOpcode() != Opcodes.FSTORE) {
      throw mismatch("Memory.advance clock conversion is not stored");
    }

    requireMemoryEmptyPathShape(node.name, advance, expireField.name, requireField.name, guarded, getInstances.get(0),
        paused, workStart, getClock, convert, store, expireIterators.get(0), values, requireIterators.get(0));
    if (guarded) { throw already("Memory.advance empty fast return is already present"); }

    // The old implementation replaced both iterator() calls with static
    // helpers. Telemetry showed that the helper/config/isEmpty overhead was
    // greater than the saved empty-iterator allocations. Skip the complete
    // expiration/require section instead. Restoration and pause handling stay
    // byte-for-byte vanilla; only an unpaused Memory with both work queues
    // empty returns before clock conversion and iterator creation.
    LabelNode nonEmpty = new LabelNode();
    InsnList guard = new InsnList();
    guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
    guard.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, expireField.name, expireField.desc));
    guard.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z", true));
    guard.add(new JumpInsnNode(Opcodes.IFEQ, nonEmpty));
    guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
    guard.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, requireField.name, requireField.desc));
    guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/LinkedHashMap", "isEmpty", "()Z", false));
    guard.add(new JumpInsnNode(Opcodes.IFEQ, nonEmpty));
    guard.add(new InsnNode(Opcodes.RETURN));
    guard.add(nonEmpty);
    guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
    advance.instructions.insertBefore(workStart, guard);

    if (!hasMemoryEmptyGuard(node.name, advance, expireField.name, requireField.name)) {
      throw mismatch("Memory.advance empty fast return postcondition failed");
    }
    requireMemoryEmptyPathShape(node.name, advance, expireField.name, requireField.name, true, getInstances.get(0),
        paused, workStart, getClock, convert, store, expireIterators.get(0), values, requireIterators.get(0));

    PatchReport report = new PatchReport();
    report.add("inline empty Memory.advance fast return", 1);
    return report;
  }

  private static boolean hasMemoryEmptyGuard(String owner, MethodNode method, String expireFieldName,
      String requireFieldName) {
    for (MethodInsnNode expireEmpty : calls(method, Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z")) {
      AbstractInsnNode receiver = previousMeaningful(expireEmpty);
      if (!(receiver instanceof FieldInsnNode expireField) || expireField.getOpcode() != Opcodes.GETFIELD
          || !expireField.owner.equals(owner) || !expireField.name.equals(expireFieldName)
          || !expireField.desc.equals("Ljava/util/List;"))
        continue;
      AbstractInsnNode loadThis = previousMeaningful(expireField);
      if (!(loadThis instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0) continue;
      AbstractInsnNode firstJumpInsn = nextMeaningful(expireEmpty);
      if (!(firstJumpInsn instanceof JumpInsnNode firstJump) || firstJump.getOpcode() != Opcodes.IFEQ) continue;

      AbstractInsnNode requireLoad = nextMeaningful(firstJump);
      if (!(requireLoad instanceof VarInsnNode requireThis) || requireThis.getOpcode() != Opcodes.ALOAD
          || requireThis.var != 0)
        continue;
      AbstractInsnNode requireFieldInsn = nextMeaningful(requireLoad);
      if (!(requireFieldInsn instanceof FieldInsnNode requireField) || requireField.getOpcode() != Opcodes.GETFIELD
          || !requireField.owner.equals(owner) || !requireField.name.equals(requireFieldName)
          || !requireField.desc.equals("Ljava/util/LinkedHashMap;"))
        continue;
      AbstractInsnNode requireEmptyInsn = nextMeaningful(requireField);
      if (!(requireEmptyInsn instanceof MethodInsnNode requireEmpty)
          || !callMatches(requireEmpty, Opcodes.INVOKEVIRTUAL, "java/util/LinkedHashMap", "isEmpty", "()Z"))
        continue;
      AbstractInsnNode secondJumpInsn = nextMeaningful(requireEmpty);
      if (!(secondJumpInsn instanceof JumpInsnNode secondJump) || secondJump.getOpcode() != Opcodes.IFEQ
          || secondJump.label != firstJump.label)
        continue;
      AbstractInsnNode emptyReturn = nextMeaningful(secondJump);
      if (emptyReturn == null || emptyReturn.getOpcode() != Opcodes.RETURN) continue;
      if (nextMeaningful(firstJump.label) != nextMeaningful(emptyReturn)) continue;
      return true;
    }
    return false;
  }

  private static void requireMemoryEmptyPathShape(String owner, MethodNode method, String expireFieldName,
      String requireFieldName, boolean guarded, MethodInsnNode pauseEngine, MethodInsnNode paused,
      MethodInsnNode workStart, MethodInsnNode getClock, MethodInsnNode convert, VarInsnNode convertedStore,
      MethodInsnNode expireIterator, MethodInsnNode values, MethodInsnNode requireIterator) {
    if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
      throw mismatch("Memory.advance gained exception handlers");
    }
    requireCount("Memory.advance returns", countOpcode(method, Opcodes.RETURN), guarded ? 3 : 2);

    AbstractInsnNode pauseJumpInsn = nextMeaningful(paused);
    AbstractInsnNode pauseReturn = nextMeaningful(pauseJumpInsn);
    if (nextMeaningful(pauseEngine) != paused || !(pauseJumpInsn instanceof JumpInsnNode pauseJump)
        || pauseJump.getOpcode() != Opcodes.IFEQ || pauseReturn == null || pauseReturn.getOpcode() != Opcodes.RETURN) {
      throw mismatch("Memory.advance pause gate changed structurally");
    }
    AbstractInsnNode workEntry = nextMeaningful(pauseReturn);
    if (nextMeaningful(pauseJump.label) != workEntry) {
      throw mismatch("Memory.advance pause branch no longer reaches timed work entry");
    }
    if (guarded) {
      AbstractInsnNode expireLoadInsn = workEntry;
      AbstractInsnNode expireFieldInsn = nextMeaningful(expireLoadInsn);
      AbstractInsnNode expireEmptyInsn = nextMeaningful(expireFieldInsn);
      AbstractInsnNode firstJumpInsn = nextMeaningful(expireEmptyInsn);
      AbstractInsnNode requireLoadInsn = nextMeaningful(firstJumpInsn);
      AbstractInsnNode requireFieldInsn = nextMeaningful(requireLoadInsn);
      AbstractInsnNode requireEmptyInsn = nextMeaningful(requireFieldInsn);
      AbstractInsnNode secondJumpInsn = nextMeaningful(requireEmptyInsn);
      AbstractInsnNode emptyReturn = nextMeaningful(secondJumpInsn);
      if (!(expireLoadInsn instanceof VarInsnNode expireLoad) || expireLoad.getOpcode() != Opcodes.ALOAD
          || expireLoad.var != 0 || !(expireFieldInsn instanceof FieldInsnNode expireField)
          || expireField.getOpcode() != Opcodes.GETFIELD || !expireField.owner.equals(owner)
          || !expireField.name.equals(expireFieldName) || !expireField.desc.equals(LIST_DESC)
          || !(expireEmptyInsn instanceof MethodInsnNode expireEmpty)
          || !callMatches(expireEmpty, Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z")
          || !(firstJumpInsn instanceof JumpInsnNode firstJump) || firstJump.getOpcode() != Opcodes.IFEQ
          || !(requireLoadInsn instanceof VarInsnNode requireLoad) || requireLoad.getOpcode() != Opcodes.ALOAD
          || requireLoad.var != 0 || !(requireFieldInsn instanceof FieldInsnNode requireField)
          || requireField.getOpcode() != Opcodes.GETFIELD || !requireField.owner.equals(owner)
          || !requireField.name.equals(requireFieldName) || !requireField.desc.equals(LINKED_MAP_DESC)
          || !(requireEmptyInsn instanceof MethodInsnNode requireEmpty)
          || !callMatches(requireEmpty, Opcodes.INVOKEVIRTUAL, "java/util/LinkedHashMap", "isEmpty", "()Z")
          || !(secondJumpInsn instanceof JumpInsnNode secondJump) || secondJump.getOpcode() != Opcodes.IFEQ
          || firstJump.label != secondJump.label || emptyReturn == null || emptyReturn.getOpcode() != Opcodes.RETURN
          || nextMeaningful(firstJump.label) != workStart || nextMeaningful(emptyReturn) != workStart) {
        throw mismatch("Memory.advance empty guard is not the exact post-pause prefix");
      }
    } else if (workEntry != workStart) { throw mismatch("Memory.advance has foreign work before clock conversion"); }

    AbstractInsnNode deltaLoad = nextMeaningful(getClock);
    if (nextMeaningful(workStart) != getClock || !(deltaLoad instanceof VarInsnNode load)
        || load.getOpcode() != Opcodes.FLOAD || load.var != 1 || nextMeaningful(deltaLoad) != convert
        || nextMeaningful(convert) != convertedStore) {
      throw mismatch("Memory.advance clock conversion order changed");
    }

    AbstractInsnNode expireLoadInsn = nextMeaningful(convertedStore);
    AbstractInsnNode expireFieldInsn = nextMeaningful(expireLoadInsn);
    AbstractInsnNode expireStoreInsn = nextMeaningful(expireIterator);
    AbstractInsnNode expireGotoInsn = nextMeaningful(expireStoreInsn);
    if (!(expireLoadInsn instanceof VarInsnNode expireLoad) || expireLoad.getOpcode() != Opcodes.ALOAD
        || expireLoad.var != 0 || !(expireFieldInsn instanceof FieldInsnNode expireField)
        || expireField.getOpcode() != Opcodes.GETFIELD || !expireField.owner.equals(owner)
        || !expireField.name.equals(expireFieldName) || !expireField.desc.equals(LIST_DESC)
        || nextMeaningful(expireFieldInsn) != expireIterator || !(expireStoreInsn instanceof VarInsnNode expireStore)
        || expireStore.getOpcode() != Opcodes.ASTORE || !(expireGotoInsn instanceof JumpInsnNode expireGoto)
        || expireGoto.getOpcode() != Opcodes.GOTO) {
      throw mismatch("Memory.advance expire loop setup changed");
    }
    AbstractInsnNode expireBody = nextMeaningful(expireGoto);
    AbstractInsnNode expireTestLoadInsn = nextMeaningful(expireGoto.label);
    AbstractInsnNode expireHasNextInsn = nextMeaningful(expireTestLoadInsn);
    AbstractInsnNode expireLoopJumpInsn = nextMeaningful(expireHasNextInsn);
    if (!(expireTestLoadInsn instanceof VarInsnNode expireTestLoad) || expireTestLoad.getOpcode() != Opcodes.ALOAD
        || expireTestLoad.var != expireStore.var || !(expireHasNextInsn instanceof MethodInsnNode expireHasNext)
        || !callMatches(expireHasNext, Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z")
        || !(expireLoopJumpInsn instanceof JumpInsnNode expireLoopJump) || expireLoopJump.getOpcode() != Opcodes.IFNE
        || nextMeaningful(expireLoopJump.label) != expireBody) {
      throw mismatch("Memory.advance expire loop control flow changed");
    }

    AbstractInsnNode requireLoadInsn = nextMeaningful(expireLoopJump);
    AbstractInsnNode requireFieldInsn = nextMeaningful(requireLoadInsn);
    AbstractInsnNode requireStoreInsn = nextMeaningful(requireIterator);
    AbstractInsnNode requireGotoInsn = nextMeaningful(requireStoreInsn);
    if (!(requireLoadInsn instanceof VarInsnNode requireLoad) || requireLoad.getOpcode() != Opcodes.ALOAD
        || requireLoad.var != 0 || !(requireFieldInsn instanceof FieldInsnNode requireField)
        || requireField.getOpcode() != Opcodes.GETFIELD || !requireField.owner.equals(owner)
        || !requireField.name.equals(requireFieldName) || !requireField.desc.equals(LINKED_MAP_DESC)
        || nextMeaningful(requireFieldInsn) != values || nextMeaningful(values) != requireIterator
        || !(requireStoreInsn instanceof VarInsnNode requireStore) || requireStore.getOpcode() != Opcodes.ASTORE
        || !(requireGotoInsn instanceof JumpInsnNode requireGoto) || requireGoto.getOpcode() != Opcodes.GOTO) {
      throw mismatch("Memory.advance require loop setup changed");
    }
    AbstractInsnNode requireBody = nextMeaningful(requireGoto);
    AbstractInsnNode requireTestLoadInsn = nextMeaningful(requireGoto.label);
    AbstractInsnNode requireHasNextInsn = nextMeaningful(requireTestLoadInsn);
    AbstractInsnNode requireLoopJumpInsn = nextMeaningful(requireHasNextInsn);
    AbstractInsnNode terminalReturn = nextMeaningful(requireLoopJumpInsn);
    if (!(requireTestLoadInsn instanceof VarInsnNode requireTestLoad) || requireTestLoad.getOpcode() != Opcodes.ALOAD
        || requireTestLoad.var != requireStore.var || !(requireHasNextInsn instanceof MethodInsnNode requireHasNext)
        || !callMatches(requireHasNext, Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z")
        || !(requireLoopJumpInsn instanceof JumpInsnNode requireLoopJump) || requireLoopJump.getOpcode() != Opcodes.IFNE
        || nextMeaningful(requireLoopJump.label) != requireBody || terminalReturn == null
        || terminalReturn.getOpcode() != Opcodes.RETURN || nextMeaningful(terminalReturn) != null) {
      throw mismatch("Memory.advance empty-path terminal control flow changed");
    }
  }


  /**
   * Adds notifications to the two authoritative CampaignEngine system-list mutations.
   * Deferred removal already funnels through removeStarSystem(), so no advance-method
   * surface is required and this remains independent from the market-scheduler patch.
   */
  private PatchReport patchCoreWorldsTopologyHooks(ClassNode node) {
    String sectorDesc = "Lcom/fs/starfarer/api/campaign/SectorAPI;";
    String systemDesc = "Lcom/fs/starfarer/api/campaign/StarSystemAPI;";
    String hookDesc = "(" + sectorDesc + systemDesc + ")V";
    MethodNode create = requireMethod(node, "createStarSystem", "(Ljava/lang/String;)" + systemDesc);
    MethodNode remove = requireMethod(node, "removeStarSystem", "(" + systemDesc + ")V");
    if ((create.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
        || (remove.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
      throw mismatch("CampaignEngine core-worlds topology methods have no code");
    }

    AbstractInsnNode createReturn = onlyInsn(opcodeReturns(create, Opcodes.ARETURN, "CampaignEngine.createStarSystem"),
        "CampaignEngine.createStarSystem ARETURN");
    AbstractInsnNode removeReturn = onlyInsn(campaignReturns(remove), "CampaignEngine.removeStarSystem RETURN");
    int systemLocal = requireCoreWorldsCreateSystemLocal(node.name, create);
    int removeArgument = argumentLocal(remove, 0);
    requireCoreWorldsRemoveShape(node.name, remove, removeArgument, removeReturn);

    int createHooks = countCalls(create, Opcodes.INVOKESTATIC, CORE_WORLDS_RUNTIME, "starSystemAdded", hookDesc);
    int removeHooks = countCalls(remove, Opcodes.INVOKESTATIC, CORE_WORLDS_RUNTIME, "starSystemRemoved", hookDesc);
    if (createHooks == 1 && removeHooks == 1) {
      validateCoreWorldsCreateHook(create, createReturn, hookDesc, systemLocal);
      validateCoreWorldsVoidHook(remove, removeReturn, "starSystemRemoved", hookDesc, removeArgument);
      throw already("CampaignEngine core-worlds topology hooks match");
    }
    if (createHooks != 0 || removeHooks != 0) {
      throw mismatch("CampaignEngine core-worlds topology hook state is partial: create=" + createHooks + "/1, remove="
          + removeHooks + "/1");
    }

    // ARETURN already has the return object below the hook arguments on the operand stack.
    // Calling the hook immediately before ARETURN consumes only the two newly pushed values
    // and leaves the original return value untouched.
    InsnList createHook = new InsnList();
    createHook.add(new VarInsnNode(Opcodes.ALOAD, 0));
    createHook.add(new VarInsnNode(Opcodes.ALOAD, systemLocal));
    createHook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CORE_WORLDS_RUNTIME, "starSystemAdded", hookDesc, false));
    create.instructions.insertBefore(createReturn, createHook);

    InsnList removeHook = new InsnList();
    removeHook.add(new VarInsnNode(Opcodes.ALOAD, 0));
    removeHook.add(new VarInsnNode(Opcodes.ALOAD, removeArgument));
    removeHook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CORE_WORLDS_RUNTIME, "starSystemRemoved", hookDesc, false));
    remove.instructions.insertBefore(removeReturn, removeHook);

    validateCoreWorldsCreateHook(create, createReturn, hookDesc, systemLocal);
    validateCoreWorldsVoidHook(remove, removeReturn, "starSystemRemoved", hookDesc, removeArgument);
    PatchReport report = new PatchReport();
    report.add("CampaignEngine core-worlds system membership epilogues", 2);
    return report;
  }

  private static int requireCoreWorldsCreateSystemLocal(String owner, MethodNode method) {
    TypeInsnNode allocation = null;
    int allocations = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
          && type.desc.equals("com/fs/starfarer/campaign/StarSystem")) {
        allocation = type;
        allocations++;
      }
    }
    requireCount("CampaignEngine.createStarSystem StarSystem allocations", allocations, 1);
    MethodInsnNode constructor = only(
        calls(method, Opcodes.INVOKESPECIAL, "com/fs/starfarer/campaign/StarSystem", "<init>", "(Ljava/lang/String;)V"),
        "CampaignEngine.createStarSystem constructor");
    requireVar(previousMeaningful(constructor), Opcodes.ALOAD, argumentLocal(method, 0),
        "CampaignEngine.createStarSystem constructor name argument");
    AbstractInsnNode duplicate = previousMeaningful(previousMeaningful(constructor));
    if (duplicate == null || duplicate.getOpcode() != Opcodes.DUP || previousMeaningful(duplicate) != allocation) {
      throw mismatch("CampaignEngine.createStarSystem allocation/constructor sequence changed");
    }
    AbstractInsnNode storeInsn = nextMeaningful(constructor);
    if (!(storeInsn instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) {
      throw mismatch("CampaignEngine.createStarSystem result is not stored in a local");
    }
    int systemLocal = store.var;
    requireCount("CampaignEngine.createStarSystem system-local stores", countStores(method, systemLocal), 1);

    MethodInsnNode addition =
        only(calls(method, Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z"),
            "CampaignEngine.createStarSystem List.add");
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    if (!sourceIsFieldDesc(receiverSource(method, addition, frames), "Ljava/util/List;")) {
      throw mismatch("CampaignEngine.createStarSystem add receiver is not a List field");
    }
    int addedLocal = requireLoadedLocal(argumentSource(method, addition, frames, 0), Opcodes.ALOAD,
        "CampaignEngine.createStarSystem added system");
    if (addedLocal != systemLocal) { throw mismatch("CampaignEngine.createStarSystem adds a different local"); }
    // Return materialization is deliberately not constrained. The hook uses the exact local
    // proven to be inserted into CampaignEngine.starSystems, while preserving whatever
    // compatible expression the method places on the ARETURN stack.
    return systemLocal;
  }

  private static void requireCoreWorldsRemoveShape(String owner, MethodNode method, int systemLocal,
      AbstractInsnNode returnInsn) {
    MethodInsnNode removal =
        only(calls(method, Opcodes.INVOKEINTERFACE, "java/util/List", "remove", "(Ljava/lang/Object;)Z"),
            "CampaignEngine.removeStarSystem List.remove");
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    if (!sourceIsFieldDesc(receiverSource(method, removal, frames), "Ljava/util/List;")) {
      throw mismatch("CampaignEngine.removeStarSystem remove receiver is not a List field");
    }
    int removedLocal = requireLoadedLocal(argumentSource(method, removal, frames, 0), Opcodes.ALOAD,
        "CampaignEngine.removeStarSystem argument");
    if (removedLocal != systemLocal) { throw mismatch("CampaignEngine.removeStarSystem removes a different local"); }
    AbstractInsnNode discard = nextMeaningful(removal);
    if (discard == null || discard.getOpcode() != Opcodes.POP) {
      throw mismatch("CampaignEngine.removeStarSystem removal result is not discarded");
    }
    // Compatible builds may perform unrelated bookkeeping after the authoritative list
    // removal. The single proven normal RETURN remains the safe notification boundary.
  }

  private static void validateCoreWorldsCreateHook(MethodNode method, AbstractInsnNode returnInsn, String hookDesc,
      int systemLocal) {
    MethodInsnNode hook = asMethod(previousMeaningful(returnInsn), "CampaignEngine.createStarSystem core-worlds hook");
    requireCall(hook, Opcodes.INVOKESTATIC, CORE_WORLDS_RUNTIME, "starSystemAdded", hookDesc,
        "CampaignEngine.createStarSystem core-worlds hook");
    requireVar(previousMeaningful(hook), Opcodes.ALOAD, systemLocal,
        "CampaignEngine.createStarSystem hook system argument");
    requireVar(previousMeaningful(previousMeaningful(hook)), Opcodes.ALOAD, 0,
        "CampaignEngine.createStarSystem hook sector argument");
  }

  private static void validateCoreWorldsVoidHook(MethodNode method, AbstractInsnNode returnInsn, String hookName,
      String hookDesc, int systemLocal) {
    MethodInsnNode hook = asMethod(previousMeaningful(returnInsn), method.name + " core-worlds hook");
    requireCall(hook, Opcodes.INVOKESTATIC, CORE_WORLDS_RUNTIME, hookName, hookDesc, method.name + " core-worlds hook");
    requireVar(previousMeaningful(hook), Opcodes.ALOAD, systemLocal, method.name + " core-worlds system argument");
    requireVar(previousMeaningful(previousMeaningful(hook)), Opcodes.ALOAD, 0,
        method.name + " core-worlds sector argument");
  }

  /**
   * Notifies the core-worlds index after all normal BaseLocation tag API exits.
   * Direct edits through the live getTags() collection remain covered by the
   * bounded rotating non-core audit in the runtime.
   */
  private PatchReport patchCoreWorldsTagHooks(ClassNode node) {
    String locationDesc = "Lcom/fs/starfarer/api/campaign/LocationAPI;";
    String tagHookDesc = "(" + locationDesc + "Ljava/lang/String;)V";
    String clearHookDesc = "(" + locationDesc + ")V";
    MethodNode add = requireMethod(node, "addTag", "(Ljava/lang/String;)V");
    MethodNode remove = requireMethod(node, "removeTag", "(Ljava/lang/String;)V");
    MethodNode clear = requireMethod(node, "clearTags", "()V");
    if ((add.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
        || (remove.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
        || (clear.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
      throw mismatch("BaseLocation core-worlds tag methods have no code");
    }

    requireCoreWorldsTagMutationShape(node.name, add, "add", "(Ljava/lang/Object;)Z", true);
    requireCoreWorldsTagMutationShape(node.name, remove, "remove", "(Ljava/lang/Object;)Z", true);
    requireCoreWorldsTagMutationShape(node.name, clear, "clear", "()V", false);
    List<AbstractInsnNode> addReturns = campaignReturns(add);
    List<AbstractInsnNode> removeReturns = campaignReturns(remove);
    List<AbstractInsnNode> clearReturns = campaignReturns(clear);
    requireCount("BaseLocation.addTag normal returns", addReturns.size(), 1);
    requireCount("BaseLocation.removeTag normal returns", removeReturns.size(), 2);
    requireCount("BaseLocation.clearTags normal returns", clearReturns.size(), 1);

    int addHooks = countCalls(add, Opcodes.INVOKESTATIC, CORE_WORLDS_RUNTIME, "locationTagAdded", tagHookDesc);
    int removeHooks = countCalls(remove, Opcodes.INVOKESTATIC, CORE_WORLDS_RUNTIME, "locationTagRemoved", tagHookDesc);
    int clearHooks = countCalls(clear, Opcodes.INVOKESTATIC, CORE_WORLDS_RUNTIME, "locationTagsCleared", clearHookDesc);
    if (addHooks == addReturns.size() && removeHooks == removeReturns.size() && clearHooks == clearReturns.size()) {
      validateCoreWorldsTagHookReturns(add, addReturns, "locationTagAdded", tagHookDesc, true);
      validateCoreWorldsTagHookReturns(remove, removeReturns, "locationTagRemoved", tagHookDesc, true);
      validateCoreWorldsTagHookReturns(clear, clearReturns, "locationTagsCleared", clearHookDesc, false);
      throw already("BaseLocation core-worlds tag hooks match");
    }
    if (addHooks != 0 || removeHooks != 0 || clearHooks != 0) {
      throw mismatch(
          "BaseLocation core-worlds tag hook state is partial: add=" + addHooks + "/" + addReturns.size() + ", remove="
              + removeHooks + "/" + removeReturns.size() + ", clear=" + clearHooks + "/" + clearReturns.size());
    }

    insertCoreWorldsTagHooks(add, addReturns, "locationTagAdded", tagHookDesc, true);
    insertCoreWorldsTagHooks(remove, removeReturns, "locationTagRemoved", tagHookDesc, true);
    insertCoreWorldsTagHooks(clear, clearReturns, "locationTagsCleared", clearHookDesc, false);
    validateCoreWorldsTagHookReturns(add, addReturns, "locationTagAdded", tagHookDesc, true);
    validateCoreWorldsTagHookReturns(remove, removeReturns, "locationTagRemoved", tagHookDesc, true);
    validateCoreWorldsTagHookReturns(clear, clearReturns, "locationTagsCleared", clearHookDesc, false);
    PatchReport report = new PatchReport();
    report.add("BaseLocation core-tag mutation epilogues",
        addReturns.size() + removeReturns.size() + clearReturns.size());
    return report;
  }

  private static void requireCoreWorldsTagMutationShape(String owner, MethodNode method, String operation,
      String descriptor, boolean stringArgument) {
    MethodInsnNode mutation = only(calls(method, Opcodes.INVOKEVIRTUAL, "java/util/HashSet", operation, descriptor),
        "BaseLocation." + method.name + " HashSet." + operation);
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    if (!sourceIsFieldDesc(receiverSource(method, mutation, frames), "Ljava/util/HashSet;")) {
      throw mismatch("BaseLocation." + method.name + " mutation receiver is not the tag HashSet field");
    }
    if (stringArgument) {
      int loaded = requireLoadedLocal(argumentSource(method, mutation, frames, 0), Opcodes.ALOAD,
          "BaseLocation." + method.name + " tag argument");
      if (loaded != argumentLocal(method, 0)) {
        throw mismatch("BaseLocation." + method.name + " mutates a different tag argument local");
      }
    }
  }

  private static void insertCoreWorldsTagHooks(MethodNode method, List<AbstractInsnNode> returns, String hookName,
      String hookDesc, boolean stringArgument) {
    int tagLocal = stringArgument ? argumentLocal(method, 0) : -1;
    for (AbstractInsnNode returnInsn : returns) {
      InsnList hook = new InsnList();
      hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
      if (stringArgument) hook.add(new VarInsnNode(Opcodes.ALOAD, tagLocal));
      hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CORE_WORLDS_RUNTIME, hookName, hookDesc, false));
      method.instructions.insertBefore(returnInsn, hook);
    }
  }

  private static void validateCoreWorldsTagHookReturns(MethodNode method, List<AbstractInsnNode> returns,
      String hookName, String hookDesc, boolean stringArgument) {
    int tagLocal = stringArgument ? argumentLocal(method, 0) : -1;
    for (AbstractInsnNode returnInsn : returns) {
      MethodInsnNode hook = asMethod(previousMeaningful(returnInsn), method.name + " core-worlds tag hook");
      requireCall(hook, Opcodes.INVOKESTATIC, CORE_WORLDS_RUNTIME, hookName, hookDesc,
          method.name + " core-worlds tag hook");
      AbstractInsnNode locationLoad;
      if (stringArgument) {
        AbstractInsnNode tag = previousMeaningful(hook);
        requireVar(tag, Opcodes.ALOAD, tagLocal, method.name + " core-worlds tag argument");
        locationLoad = previousMeaningful(tag);
      } else {
        locationLoad = previousMeaningful(hook);
      }
      requireVar(locationLoad, Opcodes.ALOAD, 0, method.name + " core-worlds location argument");
    }
  }


  /**
   * Replaces the terminal vanilla core-worlds recomputation only after the
   * SectorAPI local and adjacent RouteManager/RETURN protocol are proven.
   */
  private PatchReport patchCoreWorldsExtentCache(ClassNode node) {
    MethodNode advance = requireMethod(node, "advance", "(F)V");
    String sectorDesc = "Lcom/fs/starfarer/api/campaign/SectorAPI;";
    MethodInsnNode getSector =
        only(calls(advance, Opcodes.INVOKESTATIC, "com/fs/starfarer/api/Global", "getSector", "()" + sectorDesc),
            "CoreScript.advance Global.getSector");
    AbstractInsnNode stored = nextMeaningful(getSector);
    if (!(stored instanceof VarInsnNode sectorStore) || sectorStore.getOpcode() != Opcodes.ASTORE) {
      throw mismatch("CoreScript.advance sector result is not stored in an object local");
    }
    int sectorLocal = sectorStore.var;
    requireCount("CoreScript.advance sector local stores", countStores(advance, sectorLocal), 1);
    requireSectorReceiver(advance, sectorLocal, "isPaused", "()Z");
    requireSectorReceiver(advance, sectorLocal, "getClock", "()Lcom/fs/starfarer/api/campaign/CampaignClockAPI;");

    List<MethodInsnNode> originals =
        calls(advance, Opcodes.INVOKESTATIC, "com/fs/starfarer/api/util/Misc", "computeCoreWorldsExtent", "()V");
    List<MethodInsnNode> hooks =
        calls(advance, Opcodes.INVOKESTATIC, CORE_WORLDS_RUNTIME, "update", "(" + sectorDesc + ")V");

    if (originals.isEmpty() && hooks.size() == 1) {
      validateCoreWorldsHookShape(advance, hooks.get(0), sectorLocal);
      throw already("CoreScript core-worlds hook postcondition matches");
    }
    if (originals.size() != 1 || !hooks.isEmpty()) {
      throw mismatch("CoreScript core-worlds call state: expected original=1, hook=0; " + "found original="
          + originals.size() + ", hook=" + hooks.size());
    }

    MethodInsnNode original = originals.get(0);
    MethodInsnNode routeAdvance = asMethod(previousMeaningful(original), "CoreScript core-worlds predecessor");
    requireCall(routeAdvance, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/api/impl/campaign/fleets/RouteManager",
        "advance", "(F)V", "CoreScript RouteManager.advance predecessor");
    AbstractInsnNode after = nextMeaningful(original);
    if (after == null || after.getOpcode() != Opcodes.RETURN) {
      throw mismatch("CoreScript core-worlds call is not the terminal action");
    }

    advance.instructions.insertBefore(original, new VarInsnNode(Opcodes.ALOAD, sectorLocal));
    makeStatic(original, CORE_WORLDS_RUNTIME, "update", "(" + sectorDesc + ")V");
    PatchReport report = new PatchReport();
    report.add("coreWorldsExtentCache", 1);
    return report;
  }

  private static void requireSectorReceiver(MethodNode method, int sectorLocal, String name, String desc) {
    MethodInsnNode call =
        only(calls(method, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/SectorAPI", name, desc),
            "CoreScript.advance SectorAPI." + name);
    AbstractInsnNode receiver = previousMeaningful(call);
    if (!(receiver instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != sectorLocal) {
      throw mismatch("CoreScript.advance SectorAPI." + name + " does not use the stored sector local");
    }
  }

  private static void validateCoreWorldsHookShape(MethodNode method, MethodInsnNode hook, int sectorLocal) {
    AbstractInsnNode receiver = previousMeaningful(hook);
    if (!(receiver instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != sectorLocal) {
      throw mismatch("CoreScript core-worlds hook does not load the proven sector local");
    }
    MethodInsnNode routeAdvance = asMethod(previousMeaningful(receiver), "CoreScript core-worlds hook predecessor");
    requireCall(routeAdvance, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/api/impl/campaign/fleets/RouteManager",
        "advance", "(F)V", "CoreScript RouteManager.advance before hook");
    AbstractInsnNode after = nextMeaningful(hook);
    if (after == null || after.getOpcode() != Opcodes.RETURN) {
      throw mismatch("CoreScript core-worlds hook is not the terminal action");
    }
    requireCount("CoreScript vanilla core-worlds calls after patch",
        countCalls(method, Opcodes.INVOKESTATIC, "com/fs/starfarer/api/util/Misc", "computeCoreWorldsExtent", "()V"),
        0);
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


  private PatchReport patchMarketShareLinearAggregation(ClassNode node) {
    MethodNode wrapper = requireMethod(node, "getMarketSharePercentPerFaction", MARKET_SHARE_METHOD_DESC);
    List<MethodNode> rawMethods = methods(node, MARKET_SHARE_RAW_METHOD, MARKET_SHARE_METHOD_DESC);
    if (!rawMethods.isEmpty()) {
      requireCount("market-share raw method", rawMethods.size(), 1);
      requireMarketShareLinearPostcondition(node, wrapper, rawMethods.get(0));
      throw already("linear market-share aggregation postcondition matches");
    }

    if ((wrapper.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || wrapper.instructions == null
        || wrapper.instructions.size() == 0) {
      throw mismatch("CommodityMarketData.getMarketSharePercentPerFaction has no concrete code");
    }
    requireCount("original market-share getMarkets call",
        countCalls(wrapper, Opcodes.INVOKEVIRTUAL, node.name, "getMarkets", "()Ljava/util/List;"), 1);
    requireCount("original market-share single-faction call",
        countCalls(wrapper, Opcodes.INVOKEVIRTUAL, node.name, "getMarketSharePercent", "(L" + FACTION_API + ";)I"), 1);
    requireCount("original market-share containsKey call",
        countCalls(wrapper, Opcodes.INVOKEINTERFACE, "java/util/Map", "containsKey", "(Ljava/lang/Object;)Z"), 1);
    requireCount("original market-share Map.put call", countCalls(wrapper, Opcodes.INVOKEINTERFACE, "java/util/Map",
        "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), 1);
    requireCount("original market-share LinkedHashMap allocation",
        countTypeInstructions(wrapper, Opcodes.NEW, "java/util/LinkedHashMap"), 1);
    requireCount("original market-share export-share call", countCalls(wrapper, Opcodes.INVOKEVIRTUAL, node.name,
        "getExportMarketSharePercent", "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)I"), 0);

    int originalAccess = wrapper.access;
    String originalSignature = wrapper.signature;
    String[] originalExceptions = exceptions(wrapper);
    wrapper.name = MARKET_SHARE_RAW_METHOD;
    wrapper.access =
        (wrapper.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;

    ClassNode template =
        loadOwnedTemplate(MARKET_SHARE_TEMPLATE_OWNER, MARKET_SHARE_TEMPLATE_RESOURCE, "market-share aggregation");
    MethodNode templateWrapper = requireMethod(template, "getMarketSharePercentPerFaction", MARKET_SHARE_METHOD_DESC);
    MethodNode installed = cloneAndRemapOwnedTemplateMethod(templateWrapper, MARKET_SHARE_TEMPLATE_OWNER, node.name,
        "market-share aggregation");
    installed.access = originalAccess;
    installed.signature = originalSignature;
    installed.exceptions = originalExceptions == null ? null : new ArrayList<>(List.of(originalExceptions));
    node.methods.add(installed);

    requireMarketShareLinearPostcondition(node, installed, wrapper);
    PatchReport report = new PatchReport();
    report.add("linear market-share aggregation", 1);
    report.add("preserved vanilla market-share fallback", 1);
    return report;
  }

  private static void requireMarketShareLinearPostcondition(ClassNode node, MethodNode wrapper, MethodNode raw) {
    if ((raw.access & Opcodes.ACC_PRIVATE) == 0 || (raw.access & Opcodes.ACC_SYNTHETIC) == 0
        || (raw.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0) {
      throw mismatch("market-share raw method metadata changed");
    }
    requireCount("linear market-share raw fallback calls",
        countCalls(wrapper, -1, node.name, MARKET_SHARE_RAW_METHOD, MARKET_SHARE_METHOD_DESC), 2);
    requireCount("linear market-share getMarkets call",
        countCalls(wrapper, Opcodes.INVOKEVIRTUAL, node.name, "getMarkets", "()Ljava/util/List;"), 1);
    requireCount("linear market-share export-share call", countCalls(wrapper, Opcodes.INVOKEVIRTUAL, node.name,
        "getExportMarketSharePercent", "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)I"), 1);
    requireCount("linear market-share single-faction calls",
        countCalls(wrapper, -1, node.name, "getMarketSharePercent", "(L" + FACTION_API + ";)I"), 0);
    requireCount("linear market-share runtime eligibility guard", countCalls(wrapper, Opcodes.INVOKESTATIC,
        MARKET_SHARE_RUNTIME, MARKET_SHARE_ELIGIBILITY_METHOD, MARKET_SHARE_ELIGIBILITY_DESC), 1);
    requireCount("linear market-share exact-class literals", countClassLiterals(wrapper, node.name), 0);
    requireCount("linear market-share direct getClass guards",
        countCalls(wrapper, Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;"), 0);
    requireCount("linear market-share LinkedHashMap allocation",
        countTypeInstructions(wrapper, Opcodes.NEW, "java/util/LinkedHashMap"), 1);
    requireCount("linear market-share local identity maps",
        countTypeInstructions(wrapper, Opcodes.NEW, "java/util/IdentityHashMap"), 2);
    requireNoOwnedTemplateReferences(node, MARKET_SHARE_TEMPLATE_OWNER, "market-share aggregation");
  }

  private PatchReport patchMarketShareDataPutElision(ClassNode node) {
    MarketSharePutShape shape = requireMarketSharePutShape(node);
    if (shape.optimized()) { throw already("market-share data put-elision postcondition matches"); }

    Set<AbstractInsnNode> moving = new LinkedHashSet<>();
    AbstractInsnNode cursor = shape.start();
    while (cursor != null) {
      if (cursor instanceof LabelNode || cursor instanceof FrameNode) {
        throw mismatch("market-share put sequence contains a control-flow boundary");
      }
      moving.add(cursor);
      if (cursor == shape.end()) break;
      cursor = cursor.getNext();
    }
    if (cursor == null) throw mismatch("market-share put sequence is not contiguous");
    for (AbstractInsnNode insn : shape.method().instructions.toArray()) {
      if (moving.contains(insn)) continue;
      if (insn instanceof JumpInsnNode jump && moving.contains(jump.label)) {
        throw mismatch("market-share put sequence has an external jump target");
      }
    }
    for (TryCatchBlockNode block : shape.method().tryCatchBlocks) {
      if (moving.contains(block.start) || moving.contains(block.end) || moving.contains(block.handler)) {
        throw mismatch("market-share put sequence crosses an exception-handler boundary");
      }
    }

    InsnList moved = new InsnList();
    cursor = shape.start();
    while (cursor != null) {
      AbstractInsnNode next = cursor.getNext();
      shape.method().instructions.remove(cursor);
      moved.add(cursor);
      if (cursor == shape.end()) break;
      cursor = next;
    }
    shape.method().instructions.insertBefore(shape.guard().label, moved);

    MarketSharePutShape post = requireMarketSharePutShape(node);
    if (!post.optimized()) { throw mismatch("market-share put sequence did not move inside the null branch"); }
    PatchReport report = new PatchReport();
    report.add("existing-entry market-share put elision", 1);
    return report;
  }

  private static MarketSharePutShape requireMarketSharePutShape(ClassNode node) {
    String desc = "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)L" + MARKET_SHARE_DATA + ";";
    MethodNode method = requireMethod(node, "getMarketShareData", desc);
    if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
      throw mismatch("CommodityMarketData.getMarketShareData has no concrete code");
    }
    MethodInsnNode get = only(calls(method, Opcodes.INVOKEVIRTUAL, "java/util/LinkedHashMap", "get",
        "(Ljava/lang/Object;)Ljava/lang/Object;"), "market-share data LinkedHashMap.get");
    MethodInsnNode put = only(calls(method, Opcodes.INVOKEVIRTUAL, "java/util/LinkedHashMap", "put",
        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), "market-share data LinkedHashMap.put");
    requireCount("market-share data allocation", countTypeInstructions(method, Opcodes.NEW, MARKET_SHARE_DATA), 1);

    AbstractInsnNode cast = nextMeaningful(get);
    if (!(cast instanceof TypeInsnNode type) || cast.getOpcode() != Opcodes.CHECKCAST
        || !type.desc.equals(MARKET_SHARE_DATA)) {
      throw mismatch("market-share data get result cast changed");
    }
    AbstractInsnNode storeInsn = nextMeaningful(cast);
    if (!(storeInsn instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) {
      throw mismatch("market-share data local store changed");
    }
    int dataLocal = store.var;

    List<JumpInsnNode> guards = new ArrayList<>();
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!(insn instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFNONNULL) continue;
      AbstractInsnNode loadInsn = previousMeaningful(jump);
      if (loadInsn instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == dataLocal) {
        guards.add(jump);
      }
    }
    JumpInsnNode guard = only(guards, "market-share data non-null guard");

    AbstractInsnNode end = nextMeaningful(put);
    if (end == null || end.getOpcode() != Opcodes.POP) {
      throw mismatch("market-share data put result discard changed");
    }
    AbstractInsnNode valueLoad = previousMeaningful(put);
    AbstractInsnNode keyLoad = previousMeaningful(valueLoad);
    AbstractInsnNode fieldRead = previousMeaningful(keyLoad);
    AbstractInsnNode thisLoad = previousMeaningful(fieldRead);
    if (!(valueLoad instanceof VarInsnNode value) || value.getOpcode() != Opcodes.ALOAD || value.var != dataLocal
        || !(keyLoad instanceof VarInsnNode key) || key.getOpcode() != Opcodes.ALOAD || key.var != 1
        || !(fieldRead instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
        || !field.owner.equals(node.name) || !field.desc.equals("Ljava/util/LinkedHashMap;")
        || !(thisLoad instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD || self.var != 0) {
      throw mismatch("market-share data put operand sequence changed");
    }

    int targetIndex = method.instructions.indexOf(guard.label);
    int startIndex = method.instructions.indexOf(thisLoad);
    int endIndex = method.instructions.indexOf(end);
    if (targetIndex < 0 || startIndex < 0 || endIndex < startIndex) {
      throw mismatch("market-share data put indices are invalid");
    }
    boolean original = targetIndex < startIndex && nextMeaningful(guard.label) == thisLoad;
    boolean optimized = startIndex < targetIndex && previousMeaningful(guard.label) == end;
    if (!original && !optimized) { throw mismatch("market-share data put is neither vanilla nor optimized shape"); }
    AbstractInsnNode returnLoad = nextMeaningful(guard.label);
    if (optimized) {
      if (!(returnLoad instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != dataLocal
          || nextMeaningful(returnLoad) == null || nextMeaningful(returnLoad).getOpcode() != Opcodes.ARETURN) {
        throw mismatch("market-share data optimized return path changed");
      }
    }
    return new MarketSharePutShape(method, guard, thisLoad, end, optimized);
  }

  private record MarketSharePutShape(MethodNode method, JumpInsnNode guard, AbstractInsnNode start,
      AbstractInsnNode end, boolean optimized) {
  }

  private PatchReport patchPunitivePlayerShareLocalCache(ClassNode node) {
    MethodNode original = requireMethod(node, "getExpeditionReasons", PUNITIVE_REASONS_DESC);
    List<MethodNode> helpers = methods(node, PUNITIVE_HELPER_METHOD, PUNITIVE_HELPER_DESC);
    if (!helpers.isEmpty()) {
      requireCount("punitive player-share helper", helpers.size(), 1);
      requirePunitivePlayerSharePostcondition(node, original, helpers.get(0));
      throw already("punitive local player-share cache postcondition matches");
    }

    PunitiveShareSites originalSites = requireOriginalPunitiveShareSites(node.name, original);
    ClassNode expandedClass = readClassExpanded(writeClass(node));
    MethodNode expanded = requireMethod(expandedClass, "getExpeditionReasons", PUNITIVE_REASONS_DESC);
    PunitiveShareSites sites = requireOriginalPunitiveShareSites(node.name, expanded);
    if (sites.sharesLocal() != originalSites.sharesLocal() || sites.playerLocal() != originalSites.playerLocal()) {
      throw mismatch("punitive player-share locals changed while expanding frames");
    }

    int cacheLocal = expanded.maxLocals;
    appendInitializedLocalToExpandedFrames(expanded, cacheLocal, "java/util/IdentityHashMap");
    InsnList initialization = new InsnList();
    initialization.add(new TypeInsnNode(Opcodes.NEW, "java/util/IdentityHashMap"));
    initialization.add(new InsnNode(Opcodes.DUP));
    initialization.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/IdentityHashMap", "<init>", "()V", false));
    initialization.add(new VarInsnNode(Opcodes.ASTORE, cacheLocal));
    AbstractInsnNode entry = expanded.instructions.getFirst();
    if (entry == null) throw mismatch("punitive getExpeditionReasons has no code");
    expanded.instructions.insertBefore(entry, initialization);
    expanded.maxLocals = cacheLocal + 1;

    MethodInsnNode competitive = sites.directCalls().get(0);
    InsnList competitiveArgs = new InsnList();
    competitiveArgs.add(new VarInsnNode(Opcodes.ALOAD, sites.sharesLocal()));
    competitiveArgs.add(new VarInsnNode(Opcodes.ALOAD, cacheLocal));
    expanded.instructions.insertBefore(competitive, competitiveArgs);
    makeStatic(competitive, node.name, PUNITIVE_HELPER_METHOD, PUNITIVE_HELPER_DESC);

    MethodInsnNode freePort = sites.directCalls().get(1);
    InsnList freePortArgs = new InsnList();
    freePortArgs.add(new InsnNode(Opcodes.ACONST_NULL));
    freePortArgs.add(new VarInsnNode(Opcodes.ALOAD, cacheLocal));
    expanded.instructions.insertBefore(freePort, freePortArgs);
    makeStatic(freePort, node.name, PUNITIVE_HELPER_METHOD, PUNITIVE_HELPER_DESC);

    int methodIndex = node.methods.indexOf(original);
    if (methodIndex < 0) throw mismatch("punitive method replacement index missing");
    node.methods.set(methodIndex, expanded);

    ClassNode template =
        loadOwnedTemplate(PUNITIVE_TEMPLATE_OWNER, PUNITIVE_TEMPLATE_RESOURCE, "punitive player-share cache");
    MethodNode templateHelper = requireMethod(template, PUNITIVE_HELPER_METHOD, PUNITIVE_HELPER_DESC);
    MethodNode installedHelper = cloneAndRemapOwnedTemplateMethod(templateHelper, PUNITIVE_TEMPLATE_OWNER, node.name,
        "punitive player-share cache");
    installedHelper.access = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
    node.methods.add(installedHelper);

    requirePunitivePlayerSharePostcondition(node, expanded, installedHelper);
    PatchReport report = new PatchReport();
    report.add("punitive competitive player-share reuse", 1);
    report.add("punitive free-port local identity cache", 1);
    return report;
  }

  private static PunitiveShareSites requireOriginalPunitiveShareSites(String owner, MethodNode method) {
    if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || method.instructions == null
        || method.instructions.size() == 0) {
      throw mismatch("punitive getExpeditionReasons has no concrete code");
    }
    MethodInsnNode perFaction = only(calls(method, Opcodes.INVOKEINTERFACE, COMMODITY_MARKET_DATA_API,
        "getMarketSharePercentPerFaction", MARKET_SHARE_METHOD_DESC), "punitive per-faction share call");
    List<MethodInsnNode> direct = calls(method, Opcodes.INVOKEINTERFACE, COMMODITY_MARKET_DATA_API,
        "getMarketSharePercent", "(L" + FACTION_API + ";)I");
    requireCount("punitive direct player-share calls", direct.size(), 2);
    direct
        .sort((left, right) -> Integer.compare(method.instructions.indexOf(left), method.instructions.indexOf(right)));
    int perFactionIndex = method.instructions.indexOf(perFaction);
    if (perFactionIndex >= method.instructions.indexOf(direct.get(0))
        || method.instructions.indexOf(direct.get(0)) >= method.instructions.indexOf(direct.get(1))) {
      throw mismatch("punitive market-share call order changed");
    }

    AbstractInsnNode sharesStoreInsn = nextMeaningful(perFaction);
    if (!(sharesStoreInsn instanceof VarInsnNode sharesStore) || sharesStore.getOpcode() != Opcodes.ASTORE) {
      throw mismatch("punitive per-faction shares are not stored in one local");
    }
    int sharesLocal = sharesStore.var;

    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    int perFactionDataLocal = requireLoadedLocal(receiverSource(method, perFaction, frames), Opcodes.ALOAD,
        "punitive per-faction data receiver");
    int firstDataLocal = requireLoadedLocal(receiverSource(method, direct.get(0), frames), Opcodes.ALOAD,
        "punitive competitive data receiver");
    if (firstDataLocal != perFactionDataLocal) {
      throw mismatch("punitive competitive share does not reuse per-faction data object");
    }
    int playerLocal = requireLoadedLocal(argumentSource(method, direct.get(0), frames, 0), Opcodes.ALOAD,
        "punitive competitive player faction");
    int freePortPlayerLocal = requireLoadedLocal(argumentSource(method, direct.get(1), frames, 0), Opcodes.ALOAD,
        "punitive free-port player faction");
    if (playerLocal != freePortPlayerLocal) {
      throw mismatch("punitive player-share calls use different player locals");
    }

    List<MethodInsnNode> playerGetters =
        calls(method, Opcodes.INVOKEINTERFACE, SECTOR_API, "getPlayerFaction", "()L" + FACTION_API + ";");
    int matchingPlayerStores = 0;
    for (MethodInsnNode getter : playerGetters) {
      AbstractInsnNode next = nextMeaningful(getter);
      if (next instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE && store.var == playerLocal) {
        matchingPlayerStores++;
      }
    }
    requireCount("punitive player-faction local initialization", matchingPlayerStores, 1);
    return new PunitiveShareSites(perFaction, direct, sharesLocal, playerLocal);
  }

  private static void requirePunitivePlayerSharePostcondition(ClassNode node, MethodNode method, MethodNode helper) {
    if ((helper.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC)) != (Opcodes.ACC_PRIVATE
        | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC)
        || (helper.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0) {
      throw mismatch("punitive player-share helper metadata changed");
    }
    requireCount("punitive original player-share calls", countCalls(method, Opcodes.INVOKEINTERFACE,
        COMMODITY_MARKET_DATA_API, "getMarketSharePercent", "(L" + FACTION_API + ";)I"), 0);
    requireCount("punitive cached player-share calls",
        countCalls(method, Opcodes.INVOKESTATIC, node.name, PUNITIVE_HELPER_METHOD, PUNITIVE_HELPER_DESC), 2);
    requireCount("punitive per-faction share call", countCalls(method, Opcodes.INVOKEINTERFACE,
        COMMODITY_MARKET_DATA_API, "getMarketSharePercentPerFaction", MARKET_SHARE_METHOD_DESC), 1);
    requireCount("punitive local IdentityHashMap allocation",
        countTypeInstructions(method, Opcodes.NEW, "java/util/IdentityHashMap"), 1);
    requireCount("punitive local IdentityHashMap construction",
        countCalls(method, Opcodes.INVOKESPECIAL, "java/util/IdentityHashMap", "<init>", "()V"), 1);
    requireCount("punitive helper runtime eligibility guard", countCalls(helper, Opcodes.INVOKESTATIC,
        MARKET_SHARE_RUNTIME, MARKET_SHARE_ELIGIBILITY_METHOD, MARKET_SHARE_ELIGIBILITY_DESC), 1);
    requireCount("punitive helper concrete-class literals", countClassLiterals(helper, COMMODITY_MARKET_DATA), 0);
    requireCount("punitive helper direct fallback calls", countCalls(helper, Opcodes.INVOKEINTERFACE,
        COMMODITY_MARKET_DATA_API, "getMarketSharePercent", "(L" + FACTION_API + ";)I"), 2);
    requireCount("punitive helper static fields", countPunitiveRetainedFields(node), 0);
    requireNoOwnedTemplateReferences(node, PUNITIVE_TEMPLATE_OWNER, "punitive player-share cache");
  }

  private static int countPunitiveRetainedFields(ClassNode node) {
    int count = 0;
    for (FieldNode field : node.fields) {
      if ((field.access & Opcodes.ACC_STATIC) != 0 && field.name.startsWith("spp$punitive")) count++;
    }
    return count;
  }

  private record PunitiveShareSites(MethodInsnNode perFactionCall, List<MethodInsnNode> directCalls, int sharesLocal,
      int playerLocal) {
  }


  // ---------------------------------------------------------------------
  // Clean AoTD BaseIndustry deficit wrapper
  // ---------------------------------------------------------------------

  private PatchReport patchAoTDCleanDeficitPath(ClassNode node) {
    List<MethodNode> rawMethods = methods(node, AOTD_DEFICIT_RAW_METHOD, AOTD_DEFICIT_DESC);
    MethodNode wrapper = requireMethod(node, "getMaxDeficit", AOTD_DEFICIT_DESC);
    int resolverCalls = countCalls(wrapper, Opcodes.INVOKESTATIC, RUNTIME_BRIDGE, "resolveAoTDMaxDeficit",
        "(Ljava/lang/Object;[Ljava/lang/String;)Ljava/lang/Object;");
    if (!rawMethods.isEmpty()) {
      requireCount("AoTD clean deficit raw method", rawMethods.size(), 1);
      MethodNode raw = rawMethods.get(0);
      if ((raw.access & Opcodes.ACC_PRIVATE) == 0 || (raw.access & Opcodes.ACC_SYNTHETIC) == 0) {
        throw mismatch("AoTD clean deficit raw method metadata changed");
      }
      requireCount("AoTD clean deficit resolver call", resolverCalls, 1);
      requireCount("AoTD clean deficit raw fallback call",
          countCalls(wrapper, -1, node.name, AOTD_DEFICIT_RAW_METHOD, AOTD_DEFICIT_DESC), 1);
      requireNoAoTDDeficitTemplateReferences(node);
      throw already("clean BaseIndustry deficit wrapper postcondition matches");
    }

    requireCount("AoTD clean deficit raw method", rawMethods.size(), 0);
    requireCount("AoTD clean deficit resolver call", resolverCalls, 0);
    if ((wrapper.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || wrapper.instructions == null
        || wrapper.instructions.size() == 0) {
      throw mismatch("BaseIndustry.getMaxDeficit has no concrete code");
    }

    int originalAccess = wrapper.access;
    String originalSignature = wrapper.signature;
    String[] originalExceptions = exceptions(wrapper);
    wrapper.name = AOTD_DEFICIT_RAW_METHOD;
    wrapper.access =
        (wrapper.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;

    ClassNode template = loadAoTDDeficitTemplate();
    MethodNode templateWrapper = requireMethod(template, "getMaxDeficit", AOTD_DEFICIT_DESC);
    MethodNode installed = cloneAndRemapAoTDDeficitTemplateMethod(templateWrapper, node.name);
    installed.access = originalAccess;
    installed.signature = originalSignature;
    installed.exceptions = originalExceptions == null ? null : new ArrayList<>(List.of(originalExceptions));
    node.methods.add(installed);

    requireCount("AoTD clean deficit resolver call", countCalls(installed, Opcodes.INVOKESTATIC, RUNTIME_BRIDGE,
        "resolveAoTDMaxDeficit", "(Ljava/lang/Object;[Ljava/lang/String;)Ljava/lang/Object;"), 1);
    requireCount("AoTD clean deficit raw fallback call",
        countCalls(installed, -1, node.name, AOTD_DEFICIT_RAW_METHOD, AOTD_DEFICIT_DESC), 1);
    requireNoAoTDDeficitTemplateReferences(node);

    PatchReport report = new PatchReport();
    report.add("clean BaseIndustry deficit wrapper", 1);
    report.add("preserved vanilla deficit method", 1);
    return report;
  }

  private static ClassNode loadAoTDDeficitTemplate() {
    try (InputStream input = PrepatcherTransformer.class.getResourceAsStream(AOTD_DEFICIT_TEMPLATE_RESOURCE)) {
      if (input == null) throw mismatch("missing BaseIndustry deficit template resource");
      ClassNode template = readClass(input.readAllBytes());
      if (!AOTD_DEFICIT_TEMPLATE_OWNER.equals(template.name)) {
        throw mismatch("BaseIndustry deficit template owner changed: " + template.name);
      }
      return template;
    } catch (IOException ex) {
      throw mismatch("unable to read BaseIndustry deficit template: " + ex.getMessage());
    }
  }

  private static MethodNode cloneAndRemapAoTDDeficitTemplateMethod(MethodNode source, String targetOwner) {
    MethodNode copy =
        new MethodNode(Opcodes.ASM8, source.access, source.name, source.desc, source.signature, exceptions(source));
    source.accept(copy);
    remapAoTDDeficitTemplateMethod(copy, targetOwner);
    return copy;
  }

  private static void remapAoTDDeficitTemplateMethod(MethodNode method, String targetOwner) {
    method.desc = remapAoTDDeficitTemplateText(method.desc, targetOwner);
    method.signature = remapAoTDDeficitTemplateText(method.signature, targetOwner);
    if (method.exceptions != null) {
      for (int i = 0; i < method.exceptions.size(); i++) {
        method.exceptions.set(i, remapAoTDDeficitTemplateText(method.exceptions.get(i), targetOwner));
      }
    }
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof FieldInsnNode field) {
        field.owner = remapAoTDDeficitTemplateText(field.owner, targetOwner);
        field.desc = remapAoTDDeficitTemplateText(field.desc, targetOwner);
      } else if (insn instanceof MethodInsnNode call) {
        call.owner = remapAoTDDeficitTemplateText(call.owner, targetOwner);
        call.desc = remapAoTDDeficitTemplateText(call.desc, targetOwner);
      } else if (insn instanceof TypeInsnNode type) {
        type.desc = remapAoTDDeficitTemplateText(type.desc, targetOwner);
      } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type) {
        ldc.cst = Type.getType(remapAoTDDeficitTemplateText(type.getDescriptor(), targetOwner));
      } else if (insn instanceof MultiANewArrayInsnNode array) {
        array.desc = remapAoTDDeficitTemplateText(array.desc, targetOwner);
      } else if (insn instanceof FrameNode frame) {
        remapAoTDDeficitTemplateFrameValues(frame.local, targetOwner);
        remapAoTDDeficitTemplateFrameValues(frame.stack, targetOwner);
      } else if (insn instanceof InvokeDynamicInsnNode) {
        throw mismatch("unexpected invokedynamic in BaseIndustry deficit template");
      }
    }
    for (TryCatchBlockNode block : method.tryCatchBlocks) {
      block.type = remapAoTDDeficitTemplateText(block.type, targetOwner);
    }
    if (method.localVariables != null) {
      for (LocalVariableNode local : method.localVariables) {
        local.desc = remapAoTDDeficitTemplateText(local.desc, targetOwner);
        local.signature = remapAoTDDeficitTemplateText(local.signature, targetOwner);
      }
    }
  }

  private static void remapAoTDDeficitTemplateFrameValues(List<Object> values, String targetOwner) {
    if (values == null) return;
    for (int i = 0; i < values.size(); i++) {
      Object value = values.get(i);
      if (value instanceof String text) {
        values.set(i, remapAoTDDeficitTemplateText(text, targetOwner));
      }
    }
  }

  private static String remapAoTDDeficitTemplateText(String value, String targetOwner) {
    return value == null ? null : value.replace(AOTD_DEFICIT_TEMPLATE_OWNER, targetOwner);
  }

  private static void requireNoAoTDDeficitTemplateReferences(ClassNode node) {
    for (FieldNode field : node.fields) {
      if ((field.desc != null && field.desc.contains(AOTD_DEFICIT_TEMPLATE_OWNER))
          || (field.signature != null && field.signature.contains(AOTD_DEFICIT_TEMPLATE_OWNER))) {
        throw mismatch("BaseIndustry deficit template field reference leaked");
      }
    }
    for (MethodNode method : node.methods) {
      if ((method.desc != null && method.desc.contains(AOTD_DEFICIT_TEMPLATE_OWNER))
          || (method.signature != null && method.signature.contains(AOTD_DEFICIT_TEMPLATE_OWNER))) {
        throw mismatch("BaseIndustry deficit template method signature leaked");
      }
      for (AbstractInsnNode insn : method.instructions.toArray()) {
        if (insn instanceof FieldInsnNode field && field.owner.equals(AOTD_DEFICIT_TEMPLATE_OWNER)) {
          throw mismatch("BaseIndustry deficit template field instruction leaked");
        }
        if (insn instanceof MethodInsnNode call && call.owner.equals(AOTD_DEFICIT_TEMPLATE_OWNER)) {
          throw mismatch("BaseIndustry deficit template method instruction leaked");
        }
        if (insn instanceof TypeInsnNode type && type.desc.equals(AOTD_DEFICIT_TEMPLATE_OWNER)) {
          throw mismatch("BaseIndustry deficit template type instruction leaked");
        }
      }
    }
  }

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


  private PatchReport patchShipAdvanceScratch(ClassNode node) {
    MethodNode advance = requireMethod(node, "advance", "(F)V");
    PatchState state =
        uniformPatchState("Ship.advance scratch reuse", scratchScopeState(node.name, advance, "Ship.advance"),
            storedSimpleAllocationState(advance, "java/util/ArrayList", "()V", "borrowShipList",
                "()Ljava/util/ArrayList;", 3, "Ship.advance command/group lists"),
            storedSimpleAllocationState(advance, "java/util/HashSet", "()V", "borrowShipSet", "()Ljava/util/HashSet;",
                2, "Ship.advance command/weapon sets"),
            shipCommandSnapshotState(node.name, advance), shipScratchMutationState(advance));
    if (state == PatchState.PATCHED) {
      throw already("all Ship.advance scratch hooks and scopes satisfy their postconditions");
    }
    boolean scopeInstalled = ensureScratchScope(node.name, advance, "Ship.advance");
    int changed = 0;
    changed += replaceStoredSimpleAllocations(node.name, advance, "java/util/ArrayList", "()V", "borrowShipList",
        "()Ljava/util/ArrayList;", 3, scopeInstalled, "Ship.advance command/group lists");
    changed += replaceStoredSimpleAllocations(node.name, advance, "java/util/HashSet", "()V", "borrowShipSet",
        "()Ljava/util/HashSet;", 2, false, "Ship.advance command/weapon sets");
    changed += replaceShipCommandSnapshot(node.name, advance);
    changed += replaceShipScratchMutations(node.name, advance);

    PatchReport report = new PatchReport();
    report.add("reusable Ship.advance command/group collections", changed);
    return report;
  }

  private static PatchState shipScratchMutationState(MethodNode method) {
    int rawList = countCalls(method, Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z");
    int rawSet = countCalls(method, Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z");
    int rawSetAll = countCalls(method, Opcodes.INVOKEINTERFACE, "java/util/Set", "addAll", "(Ljava/util/Collection;)Z");
    int listHooks =
        countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "scratchListAdd", "(Ljava/util/List;Ljava/lang/Object;)Z");
    int setHooks =
        countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "scratchSetAdd", "(Ljava/util/Set;Ljava/lang/Object;)Z");
    int setAllHooks =
        countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "scratchSetAddAll", "(Ljava/util/Set;Ljava/util/Collection;)Z");
    boolean original =
        rawList == 4 && rawSet == 1 && rawSetAll == 1 && listHooks == 0 && setHooks == 0 && setAllHooks == 0;
    boolean patched =
        rawList == 1 && rawSet == 0 && rawSetAll == 0 && listHooks == 3 && setHooks == 1 && setAllHooks == 1;
    if (!original && !patched) { throw mismatch("Ship.advance scratch mutation hooks are partial or changed"); }
    return original ? PatchState.ORIGINAL : PatchState.PATCHED;
  }

  private static int replaceShipScratchMutations(String owner, MethodNode method) {
    AllocationSpec listSpec =
        new AllocationSpec("java/util/ArrayList", "()V", "borrowShipList", "()Ljava/util/ArrayList;", 3);
    AllocationSpec setSpec =
        new AllocationSpec("java/util/HashSet", "()V", "borrowShipSet", "()Ljava/util/HashSet;", 2);
    List<VarInsnNode> listStores = storedHookResultStoresRelaxed(method, listSpec);
    List<VarInsnNode> setStores = storedHookResultStoresRelaxed(method, setSpec);
    if (listStores.size() != 3 || setStores.size() != 2) {
      throw mismatch("Ship.advance scratch mutation locals are ambiguous");
    }
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    int changed = 0;
    for (MethodInsnNode call : new ArrayList<>(
        calls(method, Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z"))) {
      SourceValue receiver = receiverSource(method, call, frames);
      if (!sourceIsActiveHookLocal(method, call, receiver, listStores)) continue;
      makeStatic(call, HOOKS, "scratchListAdd", "(Ljava/util/List;Ljava/lang/Object;)Z");
      changed++;
    }
    for (MethodInsnNode call : new ArrayList<>(
        calls(method, Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z"))) {
      SourceValue receiver = receiverSource(method, call, frames);
      if (!sourceIsActiveHookLocal(method, call, receiver, setStores)) continue;
      makeStatic(call, HOOKS, "scratchSetAdd", "(Ljava/util/Set;Ljava/lang/Object;)Z");
      changed++;
    }
    for (MethodInsnNode call : new ArrayList<>(
        calls(method, Opcodes.INVOKEINTERFACE, "java/util/Set", "addAll", "(Ljava/util/Collection;)Z"))) {
      SourceValue receiver = receiverSource(method, call, frames);
      if (!sourceIsActiveHookLocal(method, call, receiver, setStores)) continue;
      makeStatic(call, HOOKS, "scratchSetAddAll", "(Ljava/util/Set;Ljava/util/Collection;)Z");
      changed++;
    }
    if (changed != 5 || shipScratchMutationState(method) != PatchState.PATCHED) {
      throw mismatch("Ship.advance scratch mutation hook postcondition failed");
    }
    return changed;
  }


  private static int replaceShipCommandSnapshot(String owner, MethodNode method) {
    String hookDesc = "(Ljava/util/Collection;)Ljava/util/ArrayList;";
    List<MethodInsnNode> raw =
        calls(method, Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "(Ljava/util/Collection;)V");
    List<MethodInsnNode> hooks = calls(method, Opcodes.INVOKESTATIC, HOOKS, "borrowShipListSnapshot", hookDesc);
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    List<MethodInsnNode> directFieldSnapshots = new ArrayList<>();
    List<MethodInsnNode> listenerSnapshots = new ArrayList<>();
    for (MethodInsnNode constructor : raw) {
      SourceValue argument = argumentSource(method, constructor, frames, 0);
      FieldInsnNode field = optionalSourceField(argument, Opcodes.GETFIELD, owner, LIST_DESC);
      if (field != null) {
        MethodInsnNode iterator = asMethod(nextMeaningful(constructor), "Ship.advance command snapshot iterator");
        requireCall(iterator, Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "iterator", "()Ljava/util/Iterator;",
            "Ship.advance command snapshot iterator");
        directFieldSnapshots.add(constructor);
        continue;
      }
      if (sourceIsMethod(argument, Opcodes.INVOKEINTERFACE,
          "com/fs/starfarer/api/combat/listeners/CombatListenerManagerAPI", "getListeners",
          "(Ljava/lang/Class;)Ljava/util/List;")) {
        listenerSnapshots.add(constructor);
      }
    }
    boolean original =
        directFieldSnapshots.size() == 1 && listenerSnapshots.size() == 1 && raw.size() == 2 && hooks.isEmpty();
    boolean patched =
        directFieldSnapshots.isEmpty() && listenerSnapshots.size() == 1 && raw.size() == 1 && hooks.size() == 1;
    if (patched) throw already("Ship.advance command snapshot hook is already present");
    if (!original) { throw mismatch("Ship.advance listener/command snapshot ownership changed structurally"); }
    MethodInsnNode constructor = directFieldSnapshots.get(0);
    AllocationPair pair =
        allocationPair(method, constructor, frames, "java/util/ArrayList", "Ship.advance command snapshot");
    method.instructions.remove(pair.allocation());
    method.instructions.remove(pair.duplicate());
    makeStatic(constructor, HOOKS, "borrowShipListSnapshot", hookDesc);
    return 1;
  }

  private static final class DynamicParticleCleanup implements StructuredPatch<DynamicParticleCleanup.Plan> {
    private record Plan(String owner, MethodNode advance, MethodInsnNode removeAll) {
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
      return Match.plan(new Plan(node.name, advance, removeAll));
    }

    @Override
    public PatchReport mutate(Plan plan) {
      installScratchScope(plan.owner(), plan.advance(), "DynamicParticleGroup.advance");
      int changed = replaceStoredSimpleAllocations(plan.owner(), plan.advance(), "java/util/ArrayList", "()V",
          "borrowParticleRemovalList", "()Ljava/util/ArrayList;", 1, true, "DynamicParticleGroup expired list");
      changed += replaceParticleScratchMutation(plan.owner(), plan.advance());
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

  private static int replaceParticleScratchMutation(String owner, MethodNode method) {
    AllocationSpec spec =
        new AllocationSpec("java/util/ArrayList", "()V", "borrowParticleRemovalList", "()Ljava/util/ArrayList;", 1);
    List<VarInsnNode> stores = storedHookResultStoresRelaxed(method, spec);
    if (stores.size() != 1) throw mismatch("particle scratch local is ambiguous");
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    List<MethodInsnNode> raw = calls(method, Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z");
    if (raw.size() != 1
        || !sourceIsActiveHookLocal(method, raw.get(0), receiverSource(method, raw.get(0), frames), stores)) {
      throw mismatch("particle scratch List.add receiver changed");
    }
    makeStatic(raw.get(0), HOOKS, "scratchListAdd", "(Ljava/util/List;Ljava/lang/Object;)Z");
    if (particleScratchMutationState(method) != PatchState.PATCHED) {
      throw mismatch("particle scratch peak hook postcondition failed");
    }
    return 1;
  }


  private static PatchState shipCommandSnapshotState(String owner, MethodNode method) {
    String label = "Ship.advance command snapshot";
    String hookDesc = "(Ljava/util/Collection;)Ljava/util/ArrayList;";
    List<MethodInsnNode> raw =
        calls(method, Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "(Ljava/util/Collection;)V");
    List<MethodInsnNode> hooks = calls(method, Opcodes.INVOKESTATIC, HOOKS, "borrowShipListSnapshot", hookDesc);
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    List<MethodInsnNode> directFieldSnapshots = new ArrayList<>();
    List<MethodInsnNode> listenerSnapshots = new ArrayList<>();
    for (MethodInsnNode constructor : raw) {
      SourceValue argument = argumentSource(method, constructor, frames, 0);
      FieldInsnNode field = optionalSourceField(argument, Opcodes.GETFIELD, owner, LIST_DESC);
      if (field != null) {
        requireThisFieldReceiver(field, label);
        MethodInsnNode iterator = asMethod(nextMeaningful(constructor), label + " iterator");
        requireCall(iterator, Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "iterator", "()Ljava/util/Iterator;",
            label + " iterator");
        directFieldSnapshots.add(constructor);
      } else if (sourceIsMethod(argument, Opcodes.INVOKEINTERFACE,
          "com/fs/starfarer/api/combat/listeners/CombatListenerManagerAPI", "getListeners",
          "(Ljava/lang/Class;)Ljava/util/List;")) {
            listenerSnapshots.add(constructor);
          }
    }
    for (MethodInsnNode hook : hooks) {
      FieldInsnNode field =
          optionalSourceField(argumentSource(method, hook, frames, 0), Opcodes.GETFIELD, owner, LIST_DESC);
      if (field == null) throw mismatch(label + " hook input is not the owned command field");
      requireThisFieldReceiver(field, label + " hook");
      MethodInsnNode iterator = asMethod(nextMeaningful(hook), label + " patched iterator");
      requireCall(iterator, Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "iterator", "()Ljava/util/Iterator;",
          label + " patched iterator");
    }

    boolean original =
        directFieldSnapshots.size() == 1 && listenerSnapshots.size() == 1 && raw.size() == 2 && hooks.isEmpty();
    boolean patched =
        directFieldSnapshots.isEmpty() && listenerSnapshots.size() == 1 && raw.size() == 1 && hooks.size() == 1;
    if (!original && !patched) {
      throw mismatch("Ship.advance listener/command snapshot ownership changed structurally");
    }
    return original ? PatchState.ORIGINAL : PatchState.PATCHED;
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


  private PatchReport patchCommRelaySystemIndex(ClassNode node) {
    String desc = "(" + ENTITY_TOKEN_DESC + ")" + ENTITY_TOKEN_DESC;
    MethodNode method = requireMethod(node, "findNearestCommRelayToReceive", desc);
    boolean scopeInstalled = ensureScratchScope(node.name, method, "IntelManager.findNearestCommRelayToReceive");
    String hookDesc = "(L" + CAMPAIGN_ENGINE + ";Lorg/lwjgl/util/vector/Vector2f;FF)Ljava/util/List;";
    List<MethodInsnNode> raw =
        calls(method, Opcodes.INVOKEVIRTUAL, CAMPAIGN_ENGINE, "getStarSystems", "()Ljava/util/List;");
    int hooks = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "commRelayCandidateSystems", hookDesc);
    boolean original = raw.size() == 1 && hooks == 0;
    boolean patched = raw.isEmpty() && hooks == 1;
    if (patched) {
      if (scopeInstalled) throw mismatch("comm relay hook exists without its scratch scope");
      throw already("comm relay system-index hook is already present");
    }
    if (!original) throw mismatch("IntelManager comm-relay system scan changed structurally");

    MethodInsnNode getSystems = raw.get(0);
    Frame<SourceValue>[] frames = sourceFrames(node.name, method);
    requireSourceMethod(receiverSource(method, getSystems, frames), Opcodes.INVOKESTATIC, CAMPAIGN_ENGINE,
        "getInstance", "()L" + CAMPAIGN_ENGINE + ";", "comm-relay CampaignEngine source");
    requireStoredLocalProducer(method, 2, Opcodes.ASTORE, Opcodes.INVOKEINTERFACE,
        "com/fs/starfarer/api/campaign/SectorEntityToken", "getLocationInHyperspace",
        "()Lorg/lwjgl/util/vector/Vector2f;", getSystems, "comm-relay player hyperspace location");
    requireFloatLocalProducer(method, 3, getSystems, "comm-relay hyperspace units-per-light-year");
    requireCount("MAX_RANGE_AROUND_SYSTEM field",
        countFields(method, Opcodes.GETSTATIC, node.name, "MAX_RANGE_AROUND_SYSTEM", "F"), 2);

    method.instructions.insertBefore(getSystems, new VarInsnNode(Opcodes.ALOAD, 2));
    method.instructions.insertBefore(getSystems, new VarInsnNode(Opcodes.FLOAD, 3));
    method.instructions.insertBefore(getSystems,
        new FieldInsnNode(Opcodes.GETSTATIC, node.name, "MAX_RANGE_AROUND_SYSTEM", "F"));
    makeStatic(getSystems, HOOKS, "commRelayCandidateSystems", hookDesc);

    PatchReport report = new PatchReport();
    report.add("ordered hyperspace comm-relay system spatial index", 1);
    return report;
  }


  // ---------------------------------------------------------------------
  // Structural matcher and verifier helpers
  // ---------------------------------------------------------------------


  private static void requireIntelEntityWrapper(ClassNode node, String desc, String listField, String hookDesc) {
    MethodNode wrapper = requireMethod(node, "getIntelIconEntity", desc);
    List<AbstractInsnNode> code = meaningfulInstructions(wrapper);
    requireCount("getIntelIconEntity wrapper instruction count", code.size(), 7);
    requireVar(code.get(0), Opcodes.ALOAD, 0, "Intel wrapper owner");
    requireVar(code.get(1), Opcodes.ALOAD, 0, "Intel wrapper field owner");
    requireField(code.get(2), Opcodes.GETFIELD, node.name, listField, LIST_DESC, "Intel wrapper List field");
    requireVar(code.get(3), Opcodes.ALOAD, argumentLocal(wrapper, 0), "Intel wrapper plugin");
    requireCall(asMethod(code.get(4), "Intel wrapper hook"), Opcodes.INVOKESTATIC, HOOKS, "getIntelIconEntityIndexed",
        hookDesc, "Intel wrapper hook");
    if (!(code.get(5) instanceof TypeInsnNode cast) || cast.getOpcode() != Opcodes.CHECKCAST
        || !cast.desc.equals("com/fs/starfarer/campaign/CustomCampaignEntity")) {
      throw mismatch("Intel wrapper result cast changed");
    }
    if (code.get(6).getOpcode() != Opcodes.ARETURN) throw mismatch("Intel wrapper return changed");
  }

  private static void requireSystemNebulaWrapper(ClassNode node, String desc, List<String> fields, String hookDesc) {
    MethodNode wrapper = requireMethod(node, "updateSystemNebulas", desc);
    List<AbstractInsnNode> code = meaningfulInstructions(wrapper);
    requireCount("updateSystemNebulas wrapper instruction count", code.size(), 9);
    requireVar(code.get(0), Opcodes.ALOAD, 0, "nebula wrapper owner");
    for (int i = 0; i < fields.size(); i++) {
      requireVar(code.get(1 + i * 2), Opcodes.ALOAD, 0, "nebula wrapper field owner " + i);
      requireField(code.get(2 + i * 2), Opcodes.GETFIELD, node.name, fields.get(i), LIST_DESC,
          "nebula wrapper field " + i);
    }
    requireCall(asMethod(code.get(7), "nebula wrapper hook"), Opcodes.INVOKESTATIC, HOOKS, "updateSystemNebulasCached",
        hookDesc, "nebula wrapper hook");
    if (code.get(8).getOpcode() != Opcodes.RETURN) throw mismatch("nebula wrapper return changed");
  }

  private static void requireHitTestWrapper(ClassNode node, String desc, String hookDesc) {
    MethodNode wrapper = requireMethod(node, "OO0000", desc);
    List<AbstractInsnNode> code = meaningfulInstructions(wrapper);
    requireCount("OO0000 wrapper instruction count", code.size(), 6);
    requireVar(code.get(0), Opcodes.ALOAD, 0, "hit-test wrapper owner");
    requireVar(code.get(1), Opcodes.FLOAD, argumentLocal(wrapper, 0), "hit-test wrapper x");
    requireVar(code.get(2), Opcodes.FLOAD, argumentLocal(wrapper, 1), "hit-test wrapper y");
    requireVar(code.get(3), Opcodes.FLOAD, argumentLocal(wrapper, 2), "hit-test wrapper radius");
    requireCall(asMethod(code.get(4), "hit-test wrapper hook"), Opcodes.INVOKESTATIC, HOOKS, "hitTestCached", hookDesc,
        "hit-test wrapper hook");
    if (code.get(5).getOpcode() != Opcodes.ARETURN) throw mismatch("hit-test wrapper return changed");
  }


  private static int findComparedLocationLocal(String owner, MethodNode method, MethodInsnNode from,
      AbstractInsnNode boundary) {
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    Set<Integer> locals = new LinkedHashSet<>();
    for (AbstractInsnNode insn = from.getNext(); insn != null && insn != boundary; insn = insn.getNext()) {
      if (!(insn instanceof JumpInsnNode jump)
          || (jump.getOpcode() != Opcodes.IF_ACMPEQ && jump.getOpcode() != Opcodes.IF_ACMPNE))
        continue;
      Frame<SourceValue> frame = frameBefore(method, jump, frames);
      if (frame.getStackSize() < 2) continue;
      SourceValue left = frame.getStack(frame.getStackSize() - 2);
      SourceValue right = frame.getStack(frame.getStackSize() - 1);
      Integer local = null;
      if (sourceIsMethod(left, -1, "com/fs/starfarer/api/campaign/SectorEntityToken", "getContainingLocation",
          "()" + LOCATION_DESC)) {
        local = optionalLoadedLocal(right, Opcodes.ALOAD);
      } else if (sourceIsMethod(right, -1, "com/fs/starfarer/api/campaign/SectorEntityToken", "getContainingLocation",
          "()" + LOCATION_DESC)) {
            local = optionalLoadedLocal(left, Opcodes.ALOAD);
          }
      if (local != null) locals.add(local);
    }
    if (locals.size() != 1) {
      throw mismatch("expected one destination-containing-location comparison local, found " + locals);
    }
    return locals.iterator().next();
  }

  private static int findAnchorComparisonLocal(String owner, MethodNode method, MethodInsnNode from) {
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    Set<Integer> locals = new LinkedHashSet<>();
    int meaningful = 0;
    for (AbstractInsnNode insn = from.getNext(); insn != null && meaningful < 100; insn = insn.getNext()) {
      if (insn.getOpcode() >= 0) meaningful++;
      if (!(insn instanceof JumpInsnNode jump)
          || (jump.getOpcode() != Opcodes.IF_ACMPEQ && jump.getOpcode() != Opcodes.IF_ACMPNE))
        continue;
      Frame<SourceValue> frame = frameBefore(method, jump, frames);
      if (frame.getStackSize() < 2) continue;
      SourceValue left = frame.getStack(frame.getStackSize() - 2);
      SourceValue right = frame.getStack(frame.getStackSize() - 1);
      Integer local = null;
      if (sourceIsMethod(left, -1, "com/fs/starfarer/api/campaign/StarSystemAPI", "getHyperspaceAnchor",
          "()" + ENTITY_TOKEN_DESC)) {
        local = optionalLoadedLocal(right, Opcodes.ALOAD);
      } else if (sourceIsMethod(right, -1, "com/fs/starfarer/api/campaign/StarSystemAPI", "getHyperspaceAnchor",
          "()" + ENTITY_TOKEN_DESC)) {
            local = optionalLoadedLocal(left, Opcodes.ALOAD);
          }
      if (local != null) locals.add(local);
    }
    if (locals.size() != 1) throw mismatch("expected one hyperspace-anchor comparison local, found " + locals);
    return locals.iterator().next();
  }

  private static void requireSystemLocalProducer(MethodNode method, int local, AbstractInsnNode before, String label) {
    int valid = 0;
    for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null && insn != before; insn =
        insn.getNext()) {
      if (!(insn instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE || store.var != local) continue;
      AbstractInsnNode producer = previousMeaningful(store);
      if (producer instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST
          && (cast.desc.equals("com/fs/starfarer/campaign/StarSystem")
              || cast.desc.equals("com/fs/starfarer/api/campaign/StarSystemAPI"))) {
        valid++;
      } else if (producer instanceof MethodInsnNode call
          && Type.getReturnType(call.desc).getDescriptor().equals(STAR_SYSTEM_DESC)) {
            valid++;
          }
    }
    requireCountAtLeast(label + " system-local producers", valid, 1);
  }


  @FunctionalInterface
  interface PatchAction {
    PatchReport apply(ClassNode node);
  }

  /**
   * A patch that proves its structural contract before it changes anything.
   *
   * <p>{@link #match(ClassNode)} may read the whole class but must leave it untouched; it either
   * returns a proven plan or refuses with a reason. {@link #mutate} receives only that plan and
   * never sees the {@code ClassNode}, so it cannot search and cannot reach a site the match did
   * not prove. Callers that only need the question "would this patch apply?" — postcondition
   * validation and the composition probe — run the match alone.
   */
  interface StructuredPatch<P> {
    Match<P> match(ClassNode node);

    PatchReport mutate(P plan);

    /** What must hold once the plan has been applied. Runs before the envelope serializes. */
    default void verify(P plan, ClassNode node) {}
  }

  /** Outcome of {@link StructuredPatch#match}: a proven plan, or a refusal with its reason. */
  static final class Match<P> {
    private final P plan;
    private final String reason;
    private final boolean alreadyApplied;

    private Match(P plan, String reason, boolean alreadyApplied) {
      this.plan = plan;
      this.reason = reason;
      this.alreadyApplied = alreadyApplied;
    }

    static <P> Match<P> plan(P plan) {
      return new Match<>(plan, null, false);
    }

    /** The complete result of this patch is already present and verified. */
    static <P> Match<P> alreadyApplied(String reason) {
      return new Match<>(null, reason, true);
    }

    /** The class does not carry the structure this patch requires. */
    static <P> Match<P> incompatible(String reason) {
      return new Match<>(null, reason, false);
    }
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
  private interface CountPatchAction {
    int apply(ClassNode node);
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

  private record BufferConstruction(AbstractInsnNode start, AbstractInsnNode end) {
  }


  static final class PatchReport {
    int total;
    final List<String> details = new ArrayList<>();

    void add(String label, int count) {
      if (count <= 0) return;
      total += count;
      details.add(label + "=" + count);
    }

    @Override
    public String toString() {
      return String.join(", ", details);
    }
  }


}
