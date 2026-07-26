package com.starsector.prepatcher.agent;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Installs the typed runtime into the loader that owns Starsector classes. */
final class RuntimeInstaller {
    private static final String ANCHOR_CLASS = "com.fs.starfarer.api.Global";
    private static final String PAYLOAD_PACKAGE = "com/fs/starfarer/api/";
    private static final String PAYLOAD_PREFIX = PAYLOAD_PACKAGE + "StarsectorPrepatcher";
    private static final String HOOKS_CLASS =
            "com.fs.starfarer.api.StarsectorPrepatcherHooks";
    private static final String HYPERSPACE_HOOKS_CLASS =
            "com.fs.starfarer.api.StarsectorPrepatcherHyperspaceHooks";
    private static final String BRIDGE_CLASS =
            "com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge";
    private static final String CORE_WORLDS_RUNTIME_CLASS =
            "com.fs.starfarer.api.StarsectorPrepatcherCoreWorldsRuntime";
    private static final String TEMP_MOD_HOOKS_CLASS =
            "com.fs.starfarer.api.StarsectorPrepatcherTempModHooks";
    private static final String PRESENTATION_HOOKS_CLASS =
            "com.fs.starfarer.api.StarsectorPrepatcherPresentationHooks";
    private static final String STRATEGIC_JUMP_INDEX_CLASS =
            "com.fs.starfarer.api.StarsectorPrepatcherStrategicJumpIndex";
    private static final String MARKET_SHARE_RUNTIME_CLASS =
            "com.fs.starfarer.api.StarsectorPrepatcherMarketShareRuntime";
    private static final Set<String> REQUIRED_CLASSES = Set.of(
            HOOKS_CLASS, HYPERSPACE_HOOKS_CLASS, BRIDGE_CLASS,
            CORE_WORLDS_RUNTIME_CLASS, TEMP_MOD_HOOKS_CLASS,
            PRESENTATION_HOOKS_CLASS, STRATEGIC_JUMP_INDEX_CLASS,
            MARKET_SHARE_RUNTIME_CLASS);

    private RuntimeInstaller() {}

    /**
     * Defines every payload class without asking the javaagent loader to load
     * any of them. The returned loader is the identity the transformer must
     * require for classes that call the typed hooks.
     */
    static ClassLoader install(Path agentJar, Object config, Path modRoot) throws Throwable {
        ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
        Class<?> anchor = Class.forName(ANCHOR_CLASS, false, systemLoader);
        ClassLoader runtimeLoader = anchor.getClassLoader();
        if (runtimeLoader == null || runtimeLoader != systemLoader) {
            throw new IllegalStateException("Starsector API anchor is not owned by the system loader: "
                    + describeLoader(runtimeLoader) + " (system=" + describeLoader(systemLoader) + ")");
        }

        List<PayloadClass> payload = readPayload(agentJar);
        MethodHandles.Lookup targetLookup = MethodHandles.privateLookupIn(
                anchor, MethodHandles.lookup());
        Map<String, Class<?>> defined = new LinkedHashMap<>();
        for (PayloadClass item : payload) {
            Class<?> runtimeClass = targetLookup.defineClass(item.bytecode());
            if (runtimeClass.getClassLoader() != runtimeLoader) {
                throw new IllegalStateException("Runtime payload class has the wrong loader: "
                        + runtimeClass.getName() + " -> "
                        + describeLoader(runtimeClass.getClassLoader()));
            }
            Class<?> duplicate = defined.put(runtimeClass.getName(), runtimeClass);
            if (duplicate != null) {
                throw new IllegalStateException("Duplicate runtime payload class: "
                        + runtimeClass.getName());
            }
        }

        if (!defined.keySet().containsAll(REQUIRED_CLASSES)) {
            Set<String> missing = new java.util.LinkedHashSet<>(REQUIRED_CLASSES);
            missing.removeAll(defined.keySet());
            throw new IllegalStateException("Agent JAR is missing runtime payload classes: " + missing);
        }

        Class<?> bridge = defined.get(BRIDGE_CLASS);
        Method configure = bridge.getMethod("configure", Object.class, Path.class);
        try {
            configure.invoke(null, config, modRoot);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }

        PrepatcherLog.info("Target-loader runtime installed: classes=" + defined.size()
                + ", loader=" + describeLoader(runtimeLoader));
        return runtimeLoader;
    }

    private static List<PayloadClass> readPayload(Path agentJar) throws IOException {
        if (agentJar == null || !Files.isRegularFile(agentJar)) {
            throw new IOException("Agent JAR is not a regular file: " + agentJar);
        }

        List<PayloadClass> result = new ArrayList<>();
        try (JarFile jar = new JarFile(agentJar.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(PAYLOAD_PREFIX)
                        || !name.endsWith(".class")) {
                    continue;
                }
                if (name.indexOf('/', PAYLOAD_PACKAGE.length()) >= 0) {
                    throw new IOException("Runtime payload must stay in " + PAYLOAD_PACKAGE
                            + ": " + name);
                }
                try (InputStream input = jar.getInputStream(entry)) {
                    result.add(new PayloadClass(name, input.readAllBytes()));
                }
            }
        }
        if (result.isEmpty()) {
            throw new IOException("No runtime payload entries with prefix " + PAYLOAD_PREFIX
                    + " in " + agentJar);
        }

        // HotSpot may resolve synthetic ClassValue/anonymous implementations
        // while verifying their outer hook class. Define the deepest support
        // classes first; a NestHost attribute does not require the host to be
        // initialized (or even defined) until nest access is actually checked.
        // All members are present before configure() initializes either hook.
        result.sort(Comparator.comparingInt(PayloadClass::nestingDepth).reversed()
                .thenComparing(PayloadClass::entryName));
        return result;
    }

    private static String describeLoader(ClassLoader loader) {
        if (loader == null) return "bootstrap";
        return loader.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(loader));
    }

    private record PayloadClass(String entryName, byte[] bytecode) {
        int nestingDepth() {
            int depth = 0;
            for (int i = PAYLOAD_PACKAGE.length(); i < entryName.length(); i++) {
                if (entryName.charAt(i) == '$') depth++;
            }
            return depth;
        }
    }
}
