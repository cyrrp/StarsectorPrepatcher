package com.starsector.prepatcher.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

public final class PrepatcherConfig {
    private final Properties properties = new Properties();

    public final boolean enabled;
    public final boolean mapRenderStuff;
    public final boolean mapHitTest;
    public final boolean labelSpatialCandidates;
    public final boolean intelCallbackCache;
    public final boolean intelArrowRendering;
    public final boolean intelReconciliation;
    public final boolean intelEntityIndex;
    public final boolean systemNebulaCache;
    public final boolean sampleCacheClearThrottle;
    public final boolean gridLineCap;
    public final boolean campaignListenerThrottle;
    public final boolean routeJumpPointIndex;
    public final boolean strategicJumpDestinationFirst;
    public final boolean strategicJumpDestinationIndex;
    /** Wall-clock work admitted per 16.67 ms, shared by all strategic indexes. */
    public final int strategicJumpIndexBudgetMicros;
    /** Secondary bound for exceptionally cheap work units in one maintenance call. */
    public final int strategicJumpIndexMaxWorkUnits;
    /** Maximum new location states admitted per 16.67 ms. */
    public final int strategicJumpIndexAdmissionBurst;
    /** Strong LRU bound; campaign generation changes clear all states. */
    public final int strategicJumpIndexMaxLocations;
    public final int strategicJumpIndexIdleTtlMs;
    public final int strategicJumpIndexFailureRetryMs;
    public final boolean campaignSnapshotReuse;
    public final boolean entityScriptSnapshotReuse;
    public final boolean emptyMemoryAdvanceFastPath;
    public final boolean coreWorldsExtentCache;
    public final boolean coreWorldsSkipFastForwardIterations;
    public final boolean coreWorldsCheckMemoryExpiry;
    public final int coreWorldsValidationFrames;
    public final boolean economyLocationCache;
    public final boolean economyPersistentSnapshots;
    public final boolean commodityEventModDirtyCache;
    public final boolean marketScheduler;
    public final boolean directMarketObservation;
    public final boolean commodityTemporalFastPath;
    public final boolean marketNoOpCallbacks;
    public final boolean aotdCleanDeficitPath;
    public final boolean marketShareLinearAggregation;
    public final boolean marketShareDataPutElision;
    public final boolean punitivePlayerShareLocalCache;
    public final boolean nexPunitivePlayerShareLocalCache;
    public final boolean tempModExpiryScheduler;
    public final boolean commRelaySystemIndex;
    public final boolean shipAdvanceScratch;
    public final boolean particleCleanup;
    public final boolean loadingTextReader;
    public final boolean startupLogAggregation;
    public final boolean rulesLiteralParser;
    public final boolean saveLoadProgressThrottle;
    public final boolean saveOutputBufferDedup;
    public final boolean hyperspaceViewportBounds;
    public final boolean skipNoOpTerrainLayer;
    public final boolean terrainRandomReuse;
    public final boolean automatonBufferReuse;
    public final boolean starfieldCleanupBuffers;
    public final boolean starfieldLinearRemoval;
    public final boolean fastForwardEnabled;
    public final boolean fastForwardFrameMarker;
    public final boolean fastForwardActionIndicators;
    public final boolean fastForwardLocationVisuals;
    public final boolean fastForwardFloatingText;
    public final boolean fastForwardFleetView;
    public final boolean fastForwardFleetPresentation;
    public final boolean fastForwardSensorIndicators;
    public final boolean fastForwardCelestialVisuals;
    public final boolean fastForwardAuroraAnimation;
    public final boolean fastForwardContinuousSound;
    public final boolean fastForwardGateJitter;
    public final boolean fastForwardGlobalAnimations;
    public final boolean fastForwardSensorFaders;
    public final boolean fastForwardSlipstreamParticles;
    public final boolean fastForwardParticleEmitters;
    public final boolean fastForwardVerbose;
    public final boolean fastForwardMetrics;
    public final boolean fastForwardVisualTimeSimulation;
    public final int intelMapLocationCacheMs;
    public final int intelArrowDataCacheMs;
    public final int intelEntityIndexTtlMs;
    public final int cacheMaintenanceIntervalMs;
    public final int intelEntityIndexOwnerIdleTtlMs;
    public final int hoverMaxHz;
    public final float hoverCellPixels;
    public final int hoverCellTtlMs;
    public final int hoverOwnerIdleTtlMs;
    public final int hoverCellPruneIntervalMs;
    public final int hoverMaxCells;
    public final int labelIndexTtlMs;
    public final int labelOwnerIdleTtlMs;
    public final int systemNebulaMaxAgeMs;
    public final int sampleCacheClearMinIntervalMs;
    public final int gridMaxLinesPerAxis;
    public final float gridBaseSpacing;
    public final int campaignListenerAuditMs;
    public final int routeIndexTtlMs;
    public final int commRelayIndexTtlMs;
    public final int marketSchedulerBatches;
    public final int marketSchedulerHiddenBatches;
    public final boolean marketSchedulerHotCurrentLocation;
    public final boolean marketSchedulerHotPlayerOwned;
    public final boolean marketSchedulerHotInteraction;
    public final int marketSchedulerPolicyAuditBatches;
    public final int marketSchedulerConstructionAuditBatches;
    public final String marketSchedulerPerSimulationTickMemoryKey;
    public final int marketSchedulerMaxPendingRuns;
    public final boolean marketSchedulerExactReplayBeforeSave;
    public final boolean marketAdvanceSemanticRiskObserver;
    public final boolean marketConstructionDiagnostics;
    public final int marketConstructionDiagnosticsMaxSamplesPerReason;
    public final int directMarketTimingSampleEvery;
    public final int directMarketStackSampleEvery;
    public final int directMarketMaxStacksPerSite;
    public final int directMarketReportIntervalSeconds;
    public final int directMarketMaxSites;
    public final int directMarketUnknownStackSamples;
    public final int economyStructureAuditMs;
    public final int marketStructureAuditFrames;
    public final int commodityTemporalAuditFrames;
    public final int marketNoOpIndustryAuditFrames;
    public final int statsLogIntervalSeconds;
    public final int saveLoadProgressHz;
    public final int starfieldRemoveAllThreshold;
    public final boolean scratchTrimEnabled;
    public final int scratchTrimGraceMs;
    public final int scratchTrimMaxListCapacity;
    public final int scratchTrimMaxIdentityEntries;
    public final int scratchTrimMaxSetEntries;
    public final int scratchTrimMaxPooledCollections;
    public final int starfieldPoolMaxRetainedCapacity;
    public final int starfieldPoolOversizedGraceMs;

    private PrepatcherConfig(Properties p) {
        properties.putAll(p);
        enabled = bool("enabled", true);
        mapRenderStuff = bool("patch.mapRenderStuff", true);
        mapHitTest = bool("patch.mapHitTest", true);
        labelSpatialCandidates = bool("patch.labelSpatialCandidates", true);
        intelCallbackCache = bool("patch.intelCallbackCache", true);
        intelArrowRendering = bool("patch.intelArrowRendering", true);
        intelReconciliation = bool("patch.intelReconciliation", true);
        intelEntityIndex = bool("patch.intelEntityIndex", true);
        systemNebulaCache = bool("patch.systemNebulaCache", true);
        sampleCacheClearThrottle = bool("patch.sampleCacheClearThrottle", true);
        gridLineCap = bool("patch.gridLineCap", true);
        campaignListenerThrottle = bool("patch.campaignListenerThrottle", true);
        routeJumpPointIndex = bool("patch.routeJumpPointIndex", true);
        strategicJumpDestinationFirst = bool(
                "patch.strategicJumpDestinationFirst", false);
        boolean requestedStrategicJumpDestinationIndex = bool(
                "patch.strategicJumpDestinationIndex", false);
        if (requestedStrategicJumpDestinationIndex && !strategicJumpDestinationFirst) {
            PrepatcherLog.warn("patch.strategicJumpDestinationIndex requires "
                    + "patch.strategicJumpDestinationFirst; the destination index is disabled");
            requestedStrategicJumpDestinationIndex = false;
        }
        strategicJumpDestinationIndex = requestedStrategicJumpDestinationIndex;
        strategicJumpIndexBudgetMicros = integer(
                "strategicJump.indexBudgetMicros", 250, 25, 5_000);
        strategicJumpIndexMaxWorkUnits = integer(
                "strategicJump.indexMaxWorkUnits", 512, 1, 4_096);
        strategicJumpIndexAdmissionBurst = integer(
                "strategicJump.indexAdmissionBurst", 8, 1, 1_024);
        strategicJumpIndexMaxLocations = integer(
                "strategicJump.indexMaxLocations", 128, 8, 8_192);
        strategicJumpIndexIdleTtlMs = integer(
                "strategicJump.indexIdleTtlMs", 120_000, 1_000, 86_400_000);
        strategicJumpIndexFailureRetryMs = integer(
                "strategicJump.indexFailureRetryMs", 5_000, 100, 300_000);
        campaignSnapshotReuse = bool("patch.campaignSnapshotReuse", true);
        entityScriptSnapshotReuse = bool("patch.entityScriptSnapshotReuse", true);
        emptyMemoryAdvanceFastPath = bool("patch.emptyMemoryAdvanceFastPath", true);
        coreWorldsExtentCache = bool("patch.coreWorldsExtentCache", true);
        coreWorldsSkipFastForwardIterations = bool(
                "coreWorlds.skipFastForwardIterations", false);
        coreWorldsCheckMemoryExpiry = bool("coreWorlds.checkMemoryExpiry", true);
        coreWorldsValidationFrames = integer(
                "coreWorlds.validationFrames", 1, 1, 1_000_000);
        economyLocationCache = bool("patch.economyLocationCache", true);
        warnRemovedEconomySnapshotReuse();
        economyPersistentSnapshots = bool("patch.economyPersistentSnapshots", false);
        commodityEventModDirtyCache = bool("patch.commodityEventModDirtyCache", false);
        marketScheduler = bool("patch.marketScheduler", false);
        directMarketObservation = bool("patch.directMarketObservation", false);
        commodityTemporalFastPath = bool("patch.commodityTemporalFastPath", false);
        marketNoOpCallbacks = bool("patch.marketNoOpCallbacks", false);
        aotdCleanDeficitPath = bool("patch.aotdCleanDeficitPath", true);
        marketShareLinearAggregation = bool("patch.marketShareLinearAggregation", true);
        marketShareDataPutElision = bool("patch.marketShareDataPutElision", true);
        punitivePlayerShareLocalCache = bool("patch.punitivePlayerShareLocalCache", true);
        nexPunitivePlayerShareLocalCache = bool(
                "patch.nexPunitivePlayerShareLocalCache", true);
        tempModExpiryScheduler = bool("patch.tempModExpiryScheduler", false);
        commRelaySystemIndex = bool("patch.commRelaySystemIndex", true);
        shipAdvanceScratch = bool("patch.shipAdvanceScratch", true);
        particleCleanup = bool("patch.particleCleanup", true);
        loadingTextReader = bool("patch.loadingTextReader", false);
        startupLogAggregation = bool("patch.startupLogAggregation", false);
        rulesLiteralParser = bool("patch.rulesLiteralParser", true);
        saveLoadProgressThrottle = bool("patch.saveLoadProgressThrottle", true);
        saveOutputBufferDedup = bool("patch.saveOutputBufferDedup", true);
        hyperspaceViewportBounds = mergedHyperspaceViewportBounds();
        skipNoOpTerrainLayer = bool("patch.skipNoOpTerrainLayer", true);
        terrainRandomReuse = bool("patch.terrainRandomReuse", true);
        automatonBufferReuse = bool("patch.automatonBufferReuse", true);
        starfieldCleanupBuffers = bool("patch.starfieldCleanupBuffers", true);
        starfieldLinearRemoval = bool("patch.starfieldLinearRemoval", true);
        fastForwardEnabled = bool("patch.fastForwardPresentation", true);
        fastForwardFrameMarker = bool("patch.fastForwardFrameMarker", true);
        fastForwardActionIndicators = bool("patch.fastForwardActionIndicators", true);
        fastForwardLocationVisuals = bool("patch.fastForwardLocationVisuals", true);
        fastForwardFloatingText = bool("patch.fastForwardFloatingText", true);
        fastForwardFleetView = bool("patch.fastForwardFleetView", true);
        fastForwardFleetPresentation = bool("patch.fastForwardFleetPresentation", true);
        fastForwardSensorIndicators = bool("patch.fastForwardSensorIndicators", true);
        fastForwardCelestialVisuals = bool("patch.fastForwardCelestialVisuals", true);
        fastForwardAuroraAnimation = bool("patch.fastForwardAuroraAnimation", true);
        fastForwardContinuousSound = bool("patch.fastForwardContinuousSound", true);
        fastForwardGateJitter = bool("patch.fastForwardGateJitter", true);
        fastForwardGlobalAnimations = bool("patch.fastForwardGlobalAnimations", false);
        fastForwardSensorFaders = bool("patch.fastForwardSensorFaders", false);
        fastForwardSlipstreamParticles = bool("patch.fastForwardSlipstreamParticles", false);
        fastForwardParticleEmitters = bool("patch.fastForwardParticleEmitters", false);
        fastForwardVerbose = bool("fastForward.verbose", false);
        fastForwardMetrics = bool("fastForward.metrics", false);
        String fastForwardVisualTime = string("fastForward.visualTime", "realtime")
                .toLowerCase(Locale.ROOT);
        if (!fastForwardVisualTime.equals("realtime")
                && !fastForwardVisualTime.equals("simulation")) {
            PrepatcherLog.warn("Invalid fastForward.visualTime: " + fastForwardVisualTime
                    + "; using realtime");
            fastForwardVisualTime = "realtime";
        }
        fastForwardVisualTimeSimulation = fastForwardVisualTime.equals("simulation");
        intelMapLocationCacheMs = integer("intel.mapLocationCacheMs", 120, 0, 10_000);
        intelArrowDataCacheMs = integer("intel.arrowDataCacheMs", 120, 0, 10_000);
        intelEntityIndexTtlMs = integer("intel.entityIndexTtlMs", 500, 0, 60_000);
        cacheMaintenanceIntervalMs = integer("cache.maintenanceIntervalMs",
                30_000, 0, 3_600_000);
        intelEntityIndexOwnerIdleTtlMs = integer(
                "intel.entityIndexOwnerIdleTtlMs", 600_000, 0, 86_400_000);
        hoverMaxHz = integer("hover.maxHz", 60, 0, 1000);
        hoverCellPixels = decimal("hover.cellPixels", 3f, 0f, 100f);
        hoverCellTtlMs = integer("hover.cellTtlMs", 140, 0, 10_000);
        hoverOwnerIdleTtlMs = integer("hover.ownerIdleTtlMs",
                300_000, 0, 86_400_000);
        hoverCellPruneIntervalMs = integer("hover.cellPruneIntervalMs",
                30_000, 0, 3_600_000);
        hoverMaxCells = integer("hover.maxCells", 768, 0, 100_000);
        labelIndexTtlMs = integer("label.indexTtlMs", 750, 0, 60_000);
        labelOwnerIdleTtlMs = integer("label.ownerIdleTtlMs",
                600_000, 0, 86_400_000);
        systemNebulaMaxAgeMs = integer("systemNebula.maxAgeMs", 600_000, 0, 86_400_000);
        sampleCacheClearMinIntervalMs = integer("hyperspace.sampleCacheClearMinIntervalMs", 1500, 0, 60_000);
        gridMaxLinesPerAxis = integer("grid.maxLinesPerAxis", 180, 0, 10_000);
        gridBaseSpacing = decimal("grid.baseSpacing", 2000f, 1f, 1_000_000f);
        campaignListenerAuditMs = integer("campaign.listenerAuditMs", 1000, 0, 60_000);
        routeIndexTtlMs = integer("route.indexTtlMs", 250, 0, 60_000);
        commRelayIndexTtlMs = integer("commRelay.indexTtlMs", 250, 0, 60_000);
        marketSchedulerBatches = integer("market.scheduler.batches", 4, 1, 120);
        marketSchedulerHiddenBatches = integer("market.scheduler.hiddenBatches", 8, 1, 240);
        marketSchedulerHotCurrentLocation = bool(
                "market.scheduler.hot.currentLocation", true);
        marketSchedulerHotPlayerOwned = bool(
                "market.scheduler.hot.playerOwned", true);
        marketSchedulerHotInteraction = bool(
                "market.scheduler.hot.interaction", true);
        marketSchedulerPolicyAuditBatches = integer(
                "market.scheduler.policyAuditBatches", 60, 1, 36_000);
        marketSchedulerConstructionAuditBatches = integer(
                "market.remote.constructionAuditBatches", 60, 1, 36_000);
        marketSchedulerPerSimulationTickMemoryKey = string(
                "market.scheduler.perSimulationTickMemoryKey",
                "$starsectorPrepatcher_perSimulationTickMarket");
        marketSchedulerMaxPendingRuns = integer(
                "market.remote.maxPendingRuns", 32, 1, 4096);
        marketSchedulerExactReplayBeforeSave = bool(
                "market.remote.exactReplayBeforeSave", false);
        marketAdvanceSemanticRiskObserver = bool(
                "observer.marketAdvanceSemanticRisks", false);
        marketConstructionDiagnostics = bool(
                "observer.marketConstructionDiagnostics", false);
        marketConstructionDiagnosticsMaxSamplesPerReason = integer(
                "observer.marketConstructionDiagnosticsMaxSamplesPerReason",
                32, 1, 1024);
        directMarketTimingSampleEvery = integer(
                "directMarket.timingSampleEvery", 128, 0, 1_000_000);
        directMarketStackSampleEvery = integer(
                "directMarket.stackSampleEvery", 2048, 0, 10_000_000);
        directMarketMaxStacksPerSite = integer(
                "directMarket.maxStacksPerSite", 8, 0, 128);
        directMarketReportIntervalSeconds = integer(
                "directMarket.reportIntervalSeconds", 15, 1, 3600);
        directMarketMaxSites = integer(
                "directMarket.maxSites", 4096, 16, 65_536);
        directMarketUnknownStackSamples = integer(
                "directMarket.unknownStackSamples", 32, 0, 4096);
        economyStructureAuditMs = integer("economy.structureAuditMs", 1000, 0, 60_000);
        marketStructureAuditFrames = integer("market.structureAuditFrames", 120, 1, 100_000);
        commodityTemporalAuditFrames = integer("commodity.temporalAuditFrames", 60, 1, 100_000);
        marketNoOpIndustryAuditFrames = integer("market.noOpIndustryAuditFrames", 120, 0, 100_000);
        statsLogIntervalSeconds = integer("logging.statsIntervalSeconds", 0, 0, 3600);
        saveLoadProgressHz = integer("saveLoad.progressHz", 15, 0, 240);
        starfieldRemoveAllThreshold = integer("starfield.removeAllThreshold", 8, 1, 4096);
        scratchTrimEnabled = bool("scratch.trim.enabled", true);
        scratchTrimGraceMs = integer("scratch.trimGraceMs",
                300_000, 0, 86_400_000);
        scratchTrimMaxListCapacity = integer("scratch.trim.maxListCapacity",
                32_768, 16, 10_000_000);
        scratchTrimMaxIdentityEntries = integer("scratch.trim.maxIdentityEntries",
                32_768, 16, 10_000_000);
        scratchTrimMaxSetEntries = integer("scratch.trim.maxSetEntries",
                32_768, 16, 10_000_000);
        scratchTrimMaxPooledCollections = integer("scratch.trim.maxPooledCollections",
                32, 1, 4096);
        starfieldPoolMaxRetainedCapacity = integer(
                "starfield.pool.maxRetainedCapacity", 32_768, 16, 10_000_000);
        starfieldPoolOversizedGraceMs = integer(
                "starfield.pool.oversizedGraceMs", 300_000, 0, 86_400_000);
    }

    public static PrepatcherConfig load(Path file) {
        Properties p = new Properties();
        if (file != null && Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
                PrepatcherLog.info("Loaded configuration: " + file.toAbsolutePath());
            } catch (IOException ex) {
                PrepatcherLog.error("Unable to read configuration; defaults will be used: " + file, ex);
            }
        } else {
            PrepatcherLog.warn("Configuration file not found; defaults will be used: " + file);
        }
        return new PrepatcherConfig(p);
    }

    private void warnRemovedEconomySnapshotReuse() {
        if (!properties.containsKey("patch.economySnapshotReuse")) return;
        PrepatcherLog.warn("Removed setting patch.economySnapshotReuse is ignored; "
                + "use patch.economyPersistentSnapshots for owner-local snapshots");
    }

    private boolean mergedHyperspaceViewportBounds() {
        String unified = properties.getProperty("patch.hyperspaceViewportBounds");
        if (unified != null) {
            boolean enabled = bool("patch.hyperspaceViewportBounds", true);
            if (properties.containsKey("patch.hyperspaceCulling")
                    || properties.containsKey("patch.hyperspaceYClamp")) {
                PrepatcherLog.warn("Deprecated patch.hyperspaceCulling/patch.hyperspaceYClamp "
                        + "are ignored because patch.hyperspaceViewportBounds is present");
            }
            return enabled;
        }

        boolean hasCull = properties.containsKey("patch.hyperspaceCulling");
        boolean hasClamp = properties.containsKey("patch.hyperspaceYClamp");
        if (!hasCull && !hasClamp) return true;

        boolean cull = bool("patch.hyperspaceCulling", true);
        boolean clamp = bool("patch.hyperspaceYClamp", true);
        PrepatcherLog.warn("patch.hyperspaceCulling and patch.hyperspaceYClamp are deprecated; "
                + "use the atomic patch.hyperspaceViewportBounds setting");
        if (cull != clamp) {
            PrepatcherLog.warn("The legacy hyperspace bounds settings disagree; the atomic "
                    + "viewport-bounds patch is disabled rather than applying a partial fix");
            return false;
        }
        return cull;
    }

    private boolean bool(String key, boolean fallback) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true": case "yes": case "1": case "on": return true;
            case "false": case "no": case "0": case "off": return false;
            default:
                PrepatcherLog.warn("Invalid boolean for " + key + ": " + value + "; using " + fallback);
                return fallback;
        }
    }

    private int integer(String key, int fallback, int min, int max) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException ex) {
            PrepatcherLog.warn("Invalid integer for " + key + ": " + value + "; using " + fallback);
            return fallback;
        }
    }

    private float decimal(String key, float fallback, float min, float max) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        try {
            float parsed = Float.parseFloat(value.trim());
            if (!Float.isFinite(parsed)) return fallback;
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException ex) {
            PrepatcherLog.warn("Invalid float for " + key + ": " + value + "; using " + fallback);
            return fallback;
        }
    }

    private String string(String key, String fallback) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        return value.trim();
    }
}
