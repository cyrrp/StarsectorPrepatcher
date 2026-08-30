package com.starsector.prepatcher.agent;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

/** Reads the effective JVM configuration without treating installed FR files as active. */
final class FasterRenderingConfiguration {
    static final String LEGACY_SYSTEM_LOADER =
            "com.genir.renderer.loaders.AppClassLoader";
    static final String AGENT_PREMAIN = "com.genir.renderer.agent.Agent";

    private FasterRenderingConfiguration() {
    }

    static Result inspect(Path prepatcherAgentJar) {
        try {
            return inspect(
                    ManagementFactory.getRuntimeMXBean().getInputArguments(),
                    prepatcherAgentJar,
                    System.getProperty("java.system.class.loader"),
                    System.getProperty("java.class.path", ""));
        } catch (Throwable failure) {
            return Result.unreliable("could not inspect effective JVM arguments: "
                    + failure.getClass().getSimpleName() + ": "
                    + String.valueOf(failure.getMessage()));
        }
    }

    static Result inspect(
            List<String> inputArguments,
            Path prepatcherAgentJar,
            String systemLoader,
            String classPath) {
        Path self = normalize(prepatcherAgentJar);
        List<String> problems = new ArrayList<>();
        int javaAgentIndex = 0;
        int selfIndex = -1;
        int selfCount = 0;
        boolean frAgentConfigured = false;

        for (String argument : inputArguments) {
            Path agentJar = parseJavaAgentPath(argument);
            if (agentJar == null) continue;
            agentJar = normalize(agentJar);
            boolean isSelf = sameFile(agentJar, self);
            if (isSelf) {
                selfIndex = javaAgentIndex;
                selfCount++;
            } else {
                String fileName = agentJar.getFileName() == null
                        ? "" : agentJar.getFileName().toString();
                String premain = readPremainClass(agentJar, problems);
                if ("fr.agent.jar".equalsIgnoreCase(fileName)
                        || AGENT_PREMAIN.equals(premain)) {
                    frAgentConfigured = true;
                }
            }
            javaAgentIndex++;
        }

        if (selfCount != 1) {
            problems.add("expected exactly one Prepatcher -javaagent entry, found "
                    + selfCount);
        }
        boolean agentAfterPrepatcher = selfIndex >= 0
                && selfIndex + 1 < javaAgentIndex;
        boolean legacyLoaderConfigured = LEGACY_SYSTEM_LOADER.equals(systemLoader);
        boolean frRuntimeOnClasspath = containsJar(classPath, "fr.jar");
        boolean reliable = problems.isEmpty();
        String detail = "newAgent=" + frAgentConfigured
                + ", legacyLoader=" + legacyLoaderConfigured
                + ", frRuntimeClasspath=" + frRuntimeOnClasspath
                + ", prepatcherAgentIndex=" + selfIndex
                + ", javaAgentCount=" + javaAgentIndex
                + (problems.isEmpty() ? "" : ", problems=" + problems);
        return new Result(reliable, frAgentConfigured, legacyLoaderConfigured,
                frRuntimeOnClasspath, agentAfterPrepatcher, detail);
    }

    private static Path parseJavaAgentPath(String argument) {
        if (argument == null) return null;
        String value = argument.trim();
        if (value.length() >= 2 && value.charAt(0) == '"'
                && value.charAt(value.length() - 1) == '"') {
            value = value.substring(1, value.length() - 1);
        }
        String prefix = "-javaagent:";
        if (!value.regionMatches(true, 0, prefix, 0, prefix.length())) return null;
        value = value.substring(prefix.length());
        int options = value.indexOf('=');
        if (options >= 0) value = value.substring(0, options);
        value = value.trim();
        if (value.length() >= 2 && value.charAt(0) == '"'
                && value.charAt(value.length() - 1) == '"') {
            value = value.substring(1, value.length() - 1);
        }
        if (value.isEmpty()) return null;
        try {
            return Paths.get(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Path normalize(Path path) {
        if (path == null) return Paths.get("").toAbsolutePath().normalize();
        return path.toAbsolutePath().normalize();
    }

    private static boolean sameFile(Path left, Path right) {
        if (left.equals(right)) return true;
        try {
            return Files.isSameFile(left, right);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String readPremainClass(Path jar, List<String> problems) {
        try (JarFile archive = new JarFile(jar.toFile())) {
            Manifest manifest = archive.getManifest();
            return manifest == null ? null
                    : manifest.getMainAttributes().getValue("Premain-Class");
        } catch (Exception failure) {
            problems.add("cannot read javaagent manifest " + jar + ": "
                    + failure.getClass().getSimpleName());
            return null;
        }
    }

    private static boolean containsJar(String classPath, String expectedName) {
        if (classPath == null || classPath.isBlank()) return false;
        for (String entry : classPath.split(Pattern.quote(File.pathSeparator))) {
            try {
                Path path = Paths.get(entry.trim());
                Path fileName = path.getFileName();
                if (fileName != null && expectedName.equalsIgnoreCase(fileName.toString())) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // A malformed unrelated classpath entry is not an FR signal.
            }
        }
        return false;
    }

    record Result(
            boolean reliable,
            boolean frAgentConfigured,
            boolean legacyLoaderConfigured,
            boolean frRuntimeOnClasspath,
            boolean agentAfterPrepatcher,
            String detail) {
        private static Result unreliable(String detail) {
            return new Result(false, false, false, false, false, detail);
        }

        boolean hasConfiguredRoute() {
            return frAgentConfigured || legacyLoaderConfigured;
        }

        boolean hasConflict() {
            return frAgentConfigured && legacyLoaderConfigured;
        }

        boolean hasOrphanedFrRuntime() {
            return frRuntimeOnClasspath && !hasConfiguredRoute();
        }
    }
}
