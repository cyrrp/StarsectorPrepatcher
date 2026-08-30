package com.starsector.prepatcher.agent;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;

/** Selects a Java route by executing behavior probes, never by FR version or checksum. */
final class JavaCompatibilityRoute {
    enum Profile {
        JAVA17_STANDARD,
        JAVA27_STANDARD,
        FR_AGENT_CHAIN,
        FR_PREDEFINE_BRIDGE
    }

    private final Profile profile;
    private final FasterRenderingPredefineBridge.Installation bridge;

    private JavaCompatibilityRoute(
            Profile profile, FasterRenderingPredefineBridge.Installation bridge) {
        this.profile = profile;
        this.bridge = bridge;
    }

    static JavaCompatibilityRoute select(
            Instrumentation instrumentation, Path prepatcherAgentJar) {
        int feature = Runtime.version().feature();
        if (feature < 27) {
            publish(Profile.JAVA17_STANDARD, "PASSED");
            PrepatcherLog.info("Java compatibility route: JAVA17_STANDARD (runtime feature "
                    + feature + ").");
            return new JavaCompatibilityRoute(Profile.JAVA17_STANDARD, null);
        }

        FasterRenderingConfiguration.Result configuration =
                FasterRenderingConfiguration.inspect(prepatcherAgentJar);
        System.setProperty("starsector.prepatcher.fasterRenderingConfiguration",
                configuration.detail());
        FasterRenderingAgentProbe.Result agentProbe = FasterRenderingAgentProbe.run(instrumentation);
        System.setProperty("starsector.prepatcher.javaCompatibilityAgentProbe",
                format(agentProbe.passed(), agentProbe.detail()));

        if (!configuration.reliable()) {
            return fatal(feature, "effective JVM configuration could not be proven: "
                    + configuration.detail() + "; agent probe: " + agentProbe.detail());
        }
        if (configuration.hasConflict()) {
            return fatal(feature, "both Faster Rendering agent and legacy system loader are "
                    + "configured; " + configuration.detail());
        }
        if (configuration.agentAfterPrepatcher()) {
            return fatal(feature, "Prepatcher is not the last javaagent; "
                    + configuration.detail() + "; agent probe: " + agentProbe.detail());
        }
        if (agentProbe.passed()) {
            if (configuration.legacyLoaderConfigured()) {
                return fatal(feature, "the agent-chain probe passed while the legacy Faster "
                        + "Rendering loader is configured; " + configuration.detail());
            }
            publish(Profile.FR_AGENT_CHAIN, "PASSED");
            PrepatcherLog.info("Java compatibility route: FR_AGENT_CHAIN; "
                    + agentProbe.detail());
            return new JavaCompatibilityRoute(Profile.FR_AGENT_CHAIN, null);
        }

        if (agentProbe.changedProbeBytes()) {
            return fatal(feature, "an earlier agent changed the Faster Rendering probe but did "
                    + "not produce a safe class: " + agentProbe.detail() + "; "
                    + configuration.detail());
        }
        if (configuration.frAgentConfigured()) {
            return fatal(feature, "a Faster Rendering javaagent is configured but its live "
                    + "probe failed: " + agentProbe.detail() + "; " + configuration.detail());
        }
        if (configuration.legacyLoaderConfigured()) {
            FasterRenderingPredefineBridge.Result bridgeProbe =
                    FasterRenderingPredefineBridge.install(instrumentation);
            System.setProperty("starsector.prepatcher.javaCompatibilityBridgeProbe",
                    format(bridgeProbe.passed(), bridgeProbe.detail()));
            if (bridgeProbe.passed()) {
                publish(Profile.FR_PREDEFINE_BRIDGE, "PASSED");
                PrepatcherLog.info("Java compatibility route: FR_PREDEFINE_BRIDGE; "
                        + bridgeProbe.detail());
                return new JavaCompatibilityRoute(
                        Profile.FR_PREDEFINE_BRIDGE, bridgeProbe.installation());
            }
            return fatal(feature, "configured legacy Faster Rendering bridge failed: "
                    + bridgeProbe.detail() + "; agent probe: " + agentProbe.detail());
        }
        System.setProperty("starsector.prepatcher.javaCompatibilityBridgeProbe",
                "SKIPPED:not configured");
        if (configuration.hasOrphanedFrRuntime()) {
            return fatal(feature, "fr.jar is active on the classpath without either the Faster "
                    + "Rendering agent or legacy system loader; " + configuration.detail());
        }

        publish(Profile.JAVA27_STANDARD, "PASSED");
        PrepatcherLog.info("Java compatibility route: JAVA27_STANDARD; no active Faster "
                + "Rendering route is present in the effective JVM configuration. Agent probe "
                + "failed unchanged as expected: " + agentProbe.detail());
        return new JavaCompatibilityRoute(Profile.JAVA27_STANDARD, null);
    }

    private static JavaCompatibilityRoute fatal(int feature, String reason) {
        System.setProperty("starsector.prepatcher.javaCompatibilityProfile", "UNAVAILABLE");
        System.setProperty("starsector.prepatcher.javaCompatibilityProbe",
                "FAILED:" + sanitize(reason));
        System.setProperty("starsector.prepatcher.status",
                "fatal-incompatible-java-route");
        String message = "StarsectorPrepatcher cannot prove a safe configured bytecode route "
                + "on Java " + feature + ". " + reason;
        PrepatcherLog.error(message, null);
        System.err.println("[StarsectorPrepatcher] " + message);
        throw new FatalCompatibilityException(message);
    }

    Profile profile() {
        return profile;
    }

    boolean usesPredefineBridge() {
        return profile == Profile.FR_PREDEFINE_BRIDGE;
    }

    boolean repairsIllegalNamesInAgentPipeline() {
        return profile == Profile.JAVA27_STANDARD;
    }

    void armBridge(OrderedTransformerPipeline pipeline) {
        if (bridge == null) {
            if (usesPredefineBridge()) {
                throw new IllegalStateException("predefine bridge profile has no installation");
            }
            return;
        }
        bridge.arm(pipeline);
    }

    private static void publish(Profile profile, String probe) {
        System.setProperty("starsector.prepatcher.javaCompatibilityProfile", profile.name());
        System.setProperty("starsector.prepatcher.javaCompatibilityProbe", probe);
    }

    private static String format(boolean passed, String detail) {
        return passed ? "PASSED" : "FAILED:" + sanitize(detail);
    }

    private static String sanitize(String text) {
        if (text == null) return "unknown failure";
        return text.replace('\r', ' ').replace('\n', ' ');
    }

    static final class FatalCompatibilityException extends RuntimeException {
        FatalCompatibilityException(String message) {
            super(message);
        }
    }
}
