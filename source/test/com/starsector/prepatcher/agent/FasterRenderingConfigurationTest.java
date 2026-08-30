package com.starsector.prepatcher.agent;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Effective-argument classification for Java 27 with new, legacy, or no FR. */
public final class FasterRenderingConfigurationTest {
    private FasterRenderingConfigurationTest() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("spp-fr-configuration-");
        Path self = createAgent(directory.resolve("prepatcher.jar"),
                "com.starsector.prepatcher.agent.PrepatcherAgent");
        Path renamedFr = createAgent(directory.resolve("renamed-renderer-agent.jar"),
                FasterRenderingConfiguration.AGENT_PREMAIN);
        Path namedFr = createAgent(directory.resolve("fr.agent.jar"), "test.NotFasterRendering");
        String frClasspath = directory.resolve("fr.jar") + File.pathSeparator
                + directory.resolve("game.jar");

        FasterRenderingConfiguration.Result none = inspect(self,
                List.of(agent(self)), null, directory.resolve("game.jar").toString());
        require(none.reliable() && !none.hasConfiguredRoute()
                        && !none.frRuntimeOnClasspath() && !none.agentAfterPrepatcher(),
                "no-FR configuration was not recognized: " + none.detail());

        FasterRenderingConfiguration.Result manifestAgent = inspect(self,
                List.of(agent(renamedFr), agent(self)), null, frClasspath);
        require(manifestAgent.reliable() && manifestAgent.frAgentConfigured()
                        && !manifestAgent.legacyLoaderConfigured()
                        && !manifestAgent.agentAfterPrepatcher(),
                "renamed FR agent manifest was not recognized: " + manifestAgent.detail());

        FasterRenderingConfiguration.Result namedAgent = inspect(self,
                List.of(agent(namedFr), agent(self)), null, frClasspath);
        require(namedAgent.reliable() && namedAgent.frAgentConfigured(),
                "fr.agent.jar basename was not recognized: " + namedAgent.detail());

        FasterRenderingConfiguration.Result laterAgent = inspect(self,
                List.of(agent(self), agent(namedFr)), null, frClasspath);
        require(laterAgent.reliable() && laterAgent.agentAfterPrepatcher(),
                "agent after Prepatcher was not detected: " + laterAgent.detail());

        FasterRenderingConfiguration.Result legacy = inspect(self,
                List.of(agent(self)), FasterRenderingConfiguration.LEGACY_SYSTEM_LOADER,
                frClasspath);
        require(legacy.reliable() && legacy.legacyLoaderConfigured()
                        && !legacy.frAgentConfigured() && !legacy.hasConflict(),
                "legacy FR configuration was not recognized: " + legacy.detail());

        FasterRenderingConfiguration.Result conflict = inspect(self,
                List.of(agent(renamedFr), agent(self)),
                FasterRenderingConfiguration.LEGACY_SYSTEM_LOADER, frClasspath);
        require(conflict.hasConflict(),
                "simultaneous FR routes were not rejected: " + conflict.detail());

        FasterRenderingConfiguration.Result orphanedRuntime = inspect(self,
                List.of(agent(self)), null, frClasspath);
        require(orphanedRuntime.hasOrphanedFrRuntime(),
                "orphaned fr.jar classpath was not detected: " + orphanedRuntime.detail());

        FasterRenderingConfiguration.Result duplicate = inspect(self,
                List.of(agent(self), agent(self)), null, "");
        require(!duplicate.reliable(),
                "duplicate Prepatcher agent was accepted: " + duplicate.detail());

        System.out.println("OK faster-rendering-configuration"
                + " none agent-manifest agent-basename order legacy conflict orphan duplicate");
    }

    private static FasterRenderingConfiguration.Result inspect(
            Path self, List<String> arguments, String loader, String classPath) {
        return FasterRenderingConfiguration.inspect(arguments, self, loader, classPath);
    }

    private static String agent(Path path) {
        return "-javaagent:" + path;
    }

    private static Path createAgent(Path path, String premainClass) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Premain-Class", premainClass);
        try (OutputStream output = Files.newOutputStream(path);
             JarOutputStream ignored = new JarOutputStream(output, manifest)) {
            // Only the manifest is needed for configuration inspection.
        }
        return path;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
