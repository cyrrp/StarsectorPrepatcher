package com.starsector.prepatcher.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PrepatcherAgent {
    public static final String VERSION = "0.17.0";
    private PrepatcherAgent() {}

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        Path agentJar = locateAgentJar();
        Path modRoot = locateModRoot(agentJar);
        Path logPath = modRoot.resolve("logs").resolve("prepatcher.log");
        PrepatcherLog.initialize(logPath);
        PrepatcherLog.info("StarsectorPrepatcher " + VERSION + " javaagent starting");
        PrepatcherLog.info("Agent JAR: " + agentJar);
        PrepatcherLog.info("Mod root: " + modRoot);
        warnIfStandalonePresentationAgentPresent();

        try {
            Path configPath = resolveConfigPath(agentArgs, modRoot);
            PrepatcherConfig config = PrepatcherConfig.load(configPath);

            AoTDForkMarkerScanner.Result aotdMarker = AoTDForkMarkerScanner.scan(modRoot);
            System.setProperty("starsector.prepatcher.aotdMarkerStatus",
                    aotdMarker.status().name().toLowerCase(java.util.Locale.ROOT));
            if (aotdMarker.markerJar() != null) {
                System.setProperty("starsector.prepatcher.aotdMarkerJar",
                        aotdMarker.markerJar().toString());
            }
            if (aotdMarker.status() == AoTDForkMarkerScanner.Status.CANDIDATE_FOUND) {
                PrepatcherLog.info("AoTD native-contract candidate: "
                        + aotdMarker.markerJar()
                        + "; activation awaits runtime handshake.");
            } else if (aotdMarker.status() == AoTDForkMarkerScanner.Status.AMBIGUOUS
                    || aotdMarker.status() == AoTDForkMarkerScanner.Status.ERROR) {
                PrepatcherLog.warn("AoTD native-contract discovery: "
                        + aotdMarker.status() + "; " + aotdMarker.detail());
            } else {
                PrepatcherLog.info("AoTD native-contract discovery: "
                        + aotdMarker.status() + "; " + aotdMarker.detail());
            }

            System.setProperty("starsector.prepatcher.agentActive", "true");
            System.setProperty("starsector.prepatcher.version", VERSION);
            System.setProperty("starsector.prepatcher.log", logPath.toString());

            if (!config.enabled) {
                PrepatcherLog.warn("Prepatcher disabled by configuration; no bytecode patches registered.");
                System.setProperty("starsector.prepatcher.status", "disabled-by-config");
                return;
            }

            // Transformer plans reference JDK-internal ASM types. Export the packages before
            // constructing any plan whose verifier may resolve a ClassWriter subclass.
            exportInternalAsm(instrumentation);

            PrepatcherTransformer transformPlan = new PrepatcherTransformer(config);
            ReadOnlyUiEconomyStepTransformer readOnlyUiEconomyPlan =
                    new ReadOnlyUiEconomyStepTransformer(
                            config.commandTabNoGlobalEconomyStep,
                            config.commodityDetailNoGlobalEconomyStep,
                            config.marketDefensesNoGlobalEconomyStep, null);
            MarketOverviewMutationTransformer marketOverviewMutationPlan =
                    new MarketOverviewMutationTransformer(
                            config.uiMarketMutationRefresh, null);
            TradeMarketMutationTransformer tradeMutationPlan =
                    new TradeMarketMutationTransformer(
                            config.uiMarketMutationRefresh, null);
            IndustryMarketMutationTransformer industryMutationPlan =
                    new IndustryMarketMutationTransformer(
                            config.uiMarketMutationRefresh, null);
            boolean syntheticCargoEconomySkipEnabled =
                    config.campaignCargoNoGlobalEconomyStep
                            || config.lootTransferNoGlobalEconomyStep;
            boolean marketOpenContextEnabled = config.marketScheduler
                    || config.planetConditionMarketOpenNoGlobalEconomyStep
                    || config.vanillaMarketOpenLocalization;
            boolean syntheticCargoUiEnabled = config.campaignCargoNoGlobalEconomyStep
                    || config.lootTransferNoGlobalEconomyStep
                    || config.vanillaMarketOpenLocalization;
            Set<String> startupAuditTargets = collectStartupAuditTargets(
                    transformPlan, readOnlyUiEconomyPlan,
                    marketOverviewMutationPlan, tradeMutationPlan, industryMutationPlan,
                    config.uiMarketMutationRefresh, syntheticCargoEconomySkipEnabled,
                    marketOpenContextEnabled, syntheticCargoUiEnabled,
                    config.vanillaMarketOpenLocalization, config.marketScheduler, false);
            FastForwardPresentationTransformer presentationPlan =
                    new FastForwardPresentationTransformer(config);
            Class<?> loadedTarget = findLoadedTarget(
                    instrumentation, startupAuditTargets, ClassLoader.getSystemClassLoader());
            if (loadedTarget != null) {
                failForLoadedTarget("before runtime installation", loadedTarget,
                        "target-loaded-before-runtime");
                return;
            }

            ClassLoader runtimeLoader;
            try {
                runtimeLoader = RuntimeInstaller.install(agentJar, config, modRoot);
            } catch (ClassNotFoundException ex) {
                System.setProperty("starsector.prepatcher.status", "runtime-unavailable");
                PrepatcherLog.warn("Starsector runtime is unavailable; no bytecode patches registered: "
                        + ex.getMessage());
                return;
            }

            loadedTarget = findLoadedTarget(
                    instrumentation, startupAuditTargets, runtimeLoader);
            if (loadedTarget != null) {
                failForLoadedTarget("during runtime installation", loadedTarget,
                        "target-loaded-during-runtime-install");
                return;
            }

            boolean presentationMarkerLoaded = recordLoadedPresentationTargets(
                    instrumentation, presentationPlan, runtimeLoader);

            // AoTD owns an inert bridge class. Patch only that verified class at
            // load time so the mod never uses reflection or loader probing.
            instrumentation.addTransformer(
                    new AoTDSchedulerBridgeTransformer(runtimeLoader), false);
            instrumentation.addTransformer(
                    new AoTDForkCompatibilityTransformer(config.marketScheduler), false);
            System.setProperty("starsector.prepatcher.aotdBridgePatch", "transformer-installed");
            System.setProperty("starsector.prepatcher.aotdForkCompatibilityPatch",
                    config.marketScheduler ? "transformer-installed" : "disabled");
            if (!presentationMarkerLoaded) {
                instrumentation.addTransformer(
                        new FastForwardPresentationTransformer(config, runtimeLoader), false);
                System.setProperty(
                        "starsector.prepatcher.presentationStatus", "transformer-installed");
            } else {
                System.setProperty("starsector.prepatcher.presentationStatus",
                        "disabled-frame-marker-already-loaded");
            }
            ClassFileTransformer transformer = new PrepatcherTransformer(config, runtimeLoader);
            instrumentation.addTransformer(transformer, false);
            instrumentation.addTransformer(
                    new ReadOnlyUiEconomyStepTransformer(
                            config.commandTabNoGlobalEconomyStep,
                            config.commodityDetailNoGlobalEconomyStep,
                            config.marketDefensesNoGlobalEconomyStep, runtimeLoader),
                    false);
            initializeReadOnlyUiEconomyStatuses(config);
            instrumentation.addTransformer(
                    new MarketOverviewMutationTransformer(
                            config.uiMarketMutationRefresh, runtimeLoader), false);
            System.setProperty(MarketOverviewMutationTransformer.statusProperty(),
                    config.uiMarketMutationRefresh
                            ? "transformer-installed" : "disabled");
            instrumentation.addTransformer(
                    new TradeMarketMutationTransformer(
                            config.uiMarketMutationRefresh, runtimeLoader), false);
            System.setProperty(TradeMarketMutationTransformer.statusProperty(),
                    config.uiMarketMutationRefresh
                            ? "transformer-installed" : "disabled");
            instrumentation.addTransformer(
                    new IndustryMarketMutationTransformer(
                            config.uiMarketMutationRefresh, runtimeLoader), false);
            for (String industryTarget
                    : IndustryMarketMutationTransformer.TARGET_CLASSES) {
                System.setProperty(
                        IndustryMarketMutationTransformer
                                .statusProperty(industryTarget),
                        config.uiMarketMutationRefresh
                                ? "transformer-installed" : "disabled");
            }
            // Registered after the structural plan so it validates the final
            // Economy body seen by later detached-Cargo call-site logic.
            instrumentation.addTransformer(
                    new VanillaDetachedCargoEconomyContractTransformer(
                            syntheticCargoEconomySkipEnabled, runtimeLoader),
                    false);
            System.setProperty(
                    "starsector.prepatcher.detachedCargoVanillaEconomyContract",
                    syntheticCargoEconomySkipEnabled
                            ? "awaiting-economy-load" : "disabled");
            instrumentation.addTransformer(
                    new VanillaMarketOpenLocalizationContractTransformer(
                            config.vanillaMarketOpenLocalization, runtimeLoader),
                    false);
            System.setProperty(
                    "starsector.prepatcher.vanillaMarketOpenLocalizationContract",
                    config.vanillaMarketOpenLocalization
                            ? "awaiting-economy-and-reach-load" : "disabled");
            instrumentation.addTransformer(
                    new CommodityMarketDataContractTransformer(
                            config.uiMarketMutationRefresh, runtimeLoader), false);
            System.setProperty(
                    "starsector.prepatcher.commodityMarketDataContract",
                    config.uiMarketMutationRefresh
                            ? "awaiting-commodity-market-data-load" : "disabled");
            // Registered after the structural plan so it guards the final
            // CampaignEngine Economy.nextStep call with the explicit market
            // action while retaining that virtual call as the global fallback.
            instrumentation.addTransformer(
                    new AoTDMarketOpenContextTransformer(
                            marketOpenContextEnabled,
                            config.planetConditionMarketOpenNoGlobalEconomyStep,
                            config.vanillaMarketOpenLocalization,
                            runtimeLoader),
                    false);
            System.setProperty("starsector.prepatcher.aotdMarketOpenContextPatch",
                    marketOpenContextEnabled ? "transformer-installed" : "disabled");
            System.setProperty(
                    "starsector.prepatcher.planetConditionMarketOpenNoGlobalEconomyStepPatch",
                    config.planetConditionMarketOpenNoGlobalEconomyStep
                            ? "transformer-installed" : "disabled");
            System.setProperty(
                    "starsector.prepatcher.vanillaMarketOpenLocalizationPatch",
                    config.vanillaMarketOpenLocalization
                            ? "transformer-installed" : "disabled");
            instrumentation.addTransformer(
                    new AoTDDetachedCargoContextTransformer(
                            syntheticCargoUiEnabled, runtimeLoader),
                    false);
            String detachedCargoPatchStatus = syntheticCargoUiEnabled
                    ? "transformer-installed" : "disabled";
            System.setProperty(
                    "starsector.prepatcher.campaignCargoNoGlobalEconomyStepPatch",
                    detachedCargoPatchStatus);
            System.setProperty("starsector.prepatcher.aotdDetachedCargoContextPatch",
                    detachedCargoPatchStatus);
            System.setProperty("starsector.prepatcher.lootTransferNoGlobalEconomyStepPatch",
                    config.lootTransferNoGlobalEconomyStep
                            ? "transformer-installed" : "disabled");
            System.setProperty("starsector.prepatcher.presentationStructuralOrder",
                    presentationMarkerLoaded ? "structural-only" : "presentation->structural");
            if (config.directMarketObservation || config.marketScheduler
                    || config.marketAdvanceSemanticRiskObserver) {
                instrumentation.addTransformer(
                        new DirectMarketObserveTransformer(config, runtimeLoader, modRoot), false);
                System.setProperty("starsector.prepatcher.directMarketObservation",
                        config.directMarketObservation ? "enabled"
                                : (config.marketScheduler ? "scheduler-sync-only" : "risk-observer-only"));
                PrepatcherLog.info(config.directMarketObservation
                        ? "Direct Market.advance transformer installed for telemetry and scheduler debt synchronization."
                        : (config.marketScheduler
                                ? "Direct Market.advance transformer installed to synchronize scheduler debt before mod-owned calls."
                                : "Static Market.advance semantic-risk observer installed."));
            } else {
                System.setProperty("starsector.prepatcher.directMarketObservation", "disabled");
            }
            System.setProperty("starsector.prepatcher.status", "transformer-installed");
            if (presentationMarkerLoaded) {
                PrepatcherLog.info("Structural transformer installed; presentation transformer"
                        + " stayed disabled because its frame marker was already loaded.");
            } else {
                PrepatcherLog.info("Presentation and structural transformers installed in explicit"
                        + " presentation->structural order. Overlapping targets publish a"
                        + " presentation ownership/mask contract that the structural transformer"
                        + " revalidates after every commit and at final composition.");
            }
        } catch (Throwable ex) {
            System.setProperty("starsector.prepatcher.status", "agent-error");
            PrepatcherLog.error("Fatal agent initialization error; prepatcher has failed open and the game will continue unpatched.", ex);
        }
    }

    static Set<String> collectStartupAuditTargets(
            PrepatcherTransformer transformPlan,
            ReadOnlyUiEconomyStepTransformer readOnlyUiEconomyPlan,
            MarketOverviewMutationTransformer marketOverviewMutationPlan,
            TradeMarketMutationTransformer tradeMutationPlan,
            IndustryMarketMutationTransformer industryMutationPlan,
            boolean commodityMarketDataContractEnabled,
            boolean detachedCargoContractEnabled,
            boolean marketOpenContextEnabled,
            boolean detachedCargoContextEnabled,
            boolean marketOpenContractEnabled,
            boolean aotdForkCompatibilityEnabled,
            boolean includeDisabled) {
        Set<String> targets = new LinkedHashSet<>();
        for (String target : PrepatcherTransformer.TARGET_CLASSES) {
            if (includeDisabled || transformPlan.isTargetEnabled(target)) targets.add(target);
        }
        for (String target : PrepatcherTransformer.OPTIONAL_TARGET_CLASSES) {
            if (includeDisabled || transformPlan.isTargetEnabled(target)) targets.add(target);
        }
        for (String target : ReadOnlyUiEconomyStepTransformer.TARGET_CLASSES) {
            if (includeDisabled || readOnlyUiEconomyPlan.isTargetEnabled(target)) {
                targets.add(target);
            }
        }
        if (includeDisabled
                || marketOverviewMutationPlan.isTargetEnabled(
                MarketOverviewMutationTransformer.TARGET)) {
            targets.add(MarketOverviewMutationTransformer.TARGET);
        }
        if (includeDisabled
                || tradeMutationPlan.isTargetEnabled(TradeMarketMutationTransformer.TARGET)) {
            targets.add(TradeMarketMutationTransformer.TARGET);
        }
        for (String target : IndustryMarketMutationTransformer.TARGET_CLASSES) {
            if (includeDisabled || industryMutationPlan.isTargetEnabled(target)) targets.add(target);
        }
        if (includeDisabled || commodityMarketDataContractEnabled) {
            targets.add(CommodityMarketDataContractTransformer.TARGET);
        }
        if (includeDisabled || detachedCargoContractEnabled) {
            targets.add(VanillaDetachedCargoEconomyContractTransformer.TARGET);
        }
        if (includeDisabled || marketOpenContextEnabled) {
            targets.add(AoTDMarketOpenContextTransformer.TARGET);
        }
        if (includeDisabled || detachedCargoContextEnabled) {
            targets.add(AoTDDetachedCargoContextTransformer.TARGET);
        }
        if (includeDisabled || marketOpenContractEnabled) {
            targets.addAll(VanillaMarketOpenLocalizationContractTransformer.TARGET_CLASSES);
        }
        // The bridge transformer is always registered. The fork-owned mutation
        // boundary follows the scheduler feature switch.
        targets.add(AoTDSchedulerBridgeTransformer.TARGET);
        if (includeDisabled || aotdForkCompatibilityEnabled) {
            targets.add(AoTDForkCompatibilityTransformer.TARGET);
        }
        return Collections.unmodifiableSet(targets);
    }

    static Class<?> findLoadedTarget(Instrumentation instrumentation,
                                     Set<String> startupAuditTargets,
                                     ClassLoader runtimeLoader) {
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            String internalName = loaded.getName().replace('.', '/');
            if (!startupAuditTargets.contains(internalName)) continue;
            ClassLoader expectedLoader = runtimeLoader;
            if (PrepatcherTransformer.SOUND.equals(internalName)
                    && runtimeLoader != null && runtimeLoader.getParent() != null) {
                // Vanilla owns sound.Sound in the system loader; FR keeps it
                // in that custom loader's parent. Ignore unrelated duplicate
                // names, but fail closed if the actual enabled target is late.
                ClassLoader actual = loaded.getClassLoader();
                if (actual == runtimeLoader || actual == runtimeLoader.getParent()) return loaded;
                continue;
            }
            if (ReadOnlyUiEconomyStepTransformer.NEX_MARKET_CMD.equals(internalName)) {
                if (ReadOnlyUiEconomyStepTransformer.isSameOrChildLoader(
                        loaded.getClassLoader(), runtimeLoader)) return loaded;
                continue;
            }
            if (loaded.getClassLoader() == expectedLoader) return loaded;
        }
        return null;
    }


    private static void initializeReadOnlyUiEconomyStatuses(PrepatcherConfig config) {
        initializeReadOnlyUiEconomyStatus(
                ReadOnlyUiEconomyStepTransformer.COMMAND_TAB,
                config.commandTabNoGlobalEconomyStep);
        initializeReadOnlyUiEconomyStatus(
                ReadOnlyUiEconomyStepTransformer.COMMODITY_DETAIL_V2,
                config.commodityDetailNoGlobalEconomyStep);
        initializeReadOnlyUiEconomyStatus(
                ReadOnlyUiEconomyStepTransformer.COMMODITY_DETAIL_LEGACY,
                config.commodityDetailNoGlobalEconomyStep);
        initializeReadOnlyUiEconomyStatus(
                ReadOnlyUiEconomyStepTransformer.MARKET_CMD,
                config.marketDefensesNoGlobalEconomyStep);
        initializeReadOnlyUiEconomyStatus(
                ReadOnlyUiEconomyStepTransformer.NEX_MARKET_CMD,
                config.marketDefensesNoGlobalEconomyStep);
    }

    private static void initializeReadOnlyUiEconomyStatus(
            String className, boolean enabled) {
        System.setProperty(ReadOnlyUiEconomyStepTransformer.statusProperty(className),
                enabled ? "transformer-installed" : "disabled");
    }

    private static boolean recordLoadedPresentationTargets(
            Instrumentation instrumentation,
            FastForwardPresentationTransformer presentationPlan,
            ClassLoader runtimeLoader) {
        final String frameMarker = "com/fs/starfarer/campaign/CampaignState";
        List<String> loadedTargets = new ArrayList<>();
        boolean markerLoaded = false;
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            String internalName = loaded.getName().replace('.', '/');
            if (loaded.getClassLoader() != runtimeLoader
                    || !FastForwardPresentationTransformer.TARGET_CLASSES.contains(internalName)
                    || !presentationPlan.isTargetEnabled(internalName)) {
                continue;
            }
            loadedTargets.add(loaded.getName());
            System.setProperty("starsector.prepatcher.patchStatus." + loaded.getName()
                    + ".fastForwardPresentation", "SKIPPED_ALREADY_LOADED");
            markerLoaded |= frameMarker.equals(internalName);
        }
        if (loadedTargets.isEmpty()) return false;

        Collections.sort(loadedTargets);
        if (markerLoaded) {
            PrepatcherLog.warn("Fast-forward presentation frame marker was already loaded;"
                    + " only the presentation transformer is disabled and structural patches"
                    + " will continue. Loaded presentation targets=" + loadedTargets);
        } else {
            PrepatcherLog.warn("Some fast-forward presentation targets were already loaded and"
                    + " will remain vanilla; other presentation and structural targets will"
                    + " continue. Loaded targets=" + loadedTargets);
        }
        return markerLoaded;
    }

    private static void failForLoadedTarget(String phase, Class<?> target, String status) {
        System.setProperty("starsector.prepatcher.status", status);
        ClassLoader loader = target.getClassLoader();
        String loaderName = loader == null ? "bootstrap"
                : loader.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(loader));
        PrepatcherLog.warn("Target class was already loaded " + phase
                + "; no bytecode patches registered: " + target.getName()
                + " (loader=" + loaderName + ")");
    }

    private static void warnIfStandalonePresentationAgentPresent() {
        try {
            for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                String normalized = argument.toLowerCase(java.util.Locale.ROOT);
                if (!normalized.startsWith("-javaagent:")
                        || !normalized.contains("fastforwardpresentationpatch")) {
                    continue;
                }
                System.setProperty("starsector.prepatcher.presentationStandaloneAgent", "detected");
                PrepatcherLog.warn("A standalone FastForward Presentation Patch javaagent is also"
                        + " configured. Remove that entry: its patches are integrated into"
                        + " StarsectorPrepatcher and running both agents is unsupported.");
                return;
            }
        } catch (Throwable failure) {
            PrepatcherLog.warn("Could not inspect JVM arguments for a duplicate standalone"
                    + " FastForward Presentation Patch agent: " + failure);
        }
    }

    private static void exportInternalAsm(Instrumentation instrumentation) {
        Module javaBase = Object.class.getModule();
        Module ours = PrepatcherAgent.class.getModule();
        Map<String, Set<Module>> exports = new HashMap<>();
        exports.put("jdk.internal.org.objectweb.asm", Collections.singleton(ours));
        exports.put("jdk.internal.org.objectweb.asm.tree", Collections.singleton(ours));
        exports.put("jdk.internal.org.objectweb.asm.tree.analysis", Collections.singleton(ours));
        instrumentation.redefineModule(
                javaBase,
                Collections.emptySet(),
                exports,
                Collections.emptyMap(),
                Collections.emptySet(),
                Collections.emptyMap());
        PrepatcherLog.info("Exported JDK-internal ASM packages to the prepatcher agent module.");
    }

    private static Path locateAgentJar() {
        try {
            URI uri = PrepatcherAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            return Paths.get(uri).toAbsolutePath().normalize();
        } catch (Exception ex) {
            return Paths.get("../mods/StarsectorPrepatcher/agent/StarsectorPrepatcherAgent.jar")
                    .toAbsolutePath().normalize();
        }
    }

    private static Path locateModRoot(Path agentJar) {
        Path parent = agentJar.getParent();
        if (parent != null && parent.getFileName() != null
                && "agent".equalsIgnoreCase(parent.getFileName().toString())) {
            Path root = parent.getParent();
            if (root != null) return root;
        }
        return Paths.get("../mods/StarsectorPrepatcher").toAbsolutePath().normalize();
    }

    private static Path resolveConfigPath(String args, Path modRoot) {
        if (args == null || args.isBlank()) {
            return modRoot.resolve("prepatcher.properties");
        }
        String value = args.trim();
        if (value.startsWith("config=")) value = value.substring("config=".length()).trim();
        Path path = Paths.get(value);
        if (!path.isAbsolute()) {
            Path cwdRelative = path.toAbsolutePath().normalize();
            if (Files.isRegularFile(cwdRelative)) return cwdRelative;
            path = modRoot.resolve(path).normalize();
        }
        return path;
    }

}
