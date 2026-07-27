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
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PrepatcherAgent {
    public static final String VERSION = "0.13.0";
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

            PrepatcherTransformer transformPlan = new PrepatcherTransformer(config);
            FastForwardPresentationTransformer presentationPlan =
                    new FastForwardPresentationTransformer(config);
            Class<?> loadedTarget = findLoadedTarget(
                    instrumentation, transformPlan, ClassLoader.getSystemClassLoader());
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

            loadedTarget = findLoadedTarget(instrumentation, transformPlan, runtimeLoader);
            if (loadedTarget != null) {
                failForLoadedTarget("during runtime installation", loadedTarget,
                        "target-loaded-during-runtime-install");
                return;
            }

            boolean presentationMarkerLoaded = recordLoadedPresentationTargets(
                    instrumentation, presentationPlan, runtimeLoader);
            exportInternalAsm(instrumentation);

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
            // Registered after the structural plan so it wraps the final
            // CampaignEngine method body and publishes the market before
            // vanilla Economy.nextStep() observes currentlyOpenMarket.
            instrumentation.addTransformer(
                    new AoTDMarketOpenContextTransformer(config.marketScheduler, runtimeLoader),
                    false);
            System.setProperty("starsector.prepatcher.aotdMarketOpenContextPatch",
                    config.marketScheduler ? "transformer-installed" : "disabled");
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

    private static Class<?> findLoadedTarget(Instrumentation instrumentation,
                                             PrepatcherTransformer transformPlan,
                                             ClassLoader runtimeLoader) {
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            String internalName = loaded.getName().replace('.', '/');
            if (!PrepatcherTransformer.TARGET_CLASSES.contains(internalName)
                    || !transformPlan.isTargetEnabled(internalName)) {
                continue;
            }
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
            if (loaded.getClassLoader() == expectedLoader) return loaded;
        }
        return null;
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
