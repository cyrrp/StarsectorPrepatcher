package com.starsector.prepatcher.runtime;

import java.lang.reflect.Method;

/** Proves CoreLifecycle is patched before the real fork negotiates required capability bit 11. */
public final class AoTDEconomyRestoreActualAgentSmokeTest {
    private static final long RESTORE_CAPABILITY = 1L << 11;

    private AoTDEconomyRestoreActualAgentSmokeTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1
                || !("core-first".equals(args[0]) || "fork-first".equals(args[0]))) {
            throw new IllegalArgumentException("Expected core-first or fork-first");
        }
        String order = args[0];
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        Class<?> lifecycle;
        Class<?> bridge;
        Object state;
        if ("fork-first".equals(order)) {
            require("transformer-installed".equals(System.getProperty(
                            "starsector.prepatcher.aotdEconomyRestoreCompletionPatch")),
                    "fork-first fixture did not begin before CoreLifecycle definition");
            bridge = Class.forName(
                    "data.kaysaar.aotd.tot.compat.SchedulerBridge", true, loader);
            state = bridge.getMethod("initialize").invoke(null);
            lifecycle = Class.forName(
                    "com.fs.starfarer.api.impl.campaign.CoreLifecyclePluginImpl",
                    false,
                    loader);
        } else {
            lifecycle = Class.forName(
                    "com.fs.starfarer.api.impl.campaign.CoreLifecyclePluginImpl",
                    false,
                    loader);
            bridge = Class.forName(
                    "data.kaysaar.aotd.tot.compat.SchedulerBridge", true, loader);
            state = bridge.getMethod("initialize").invoke(null);
        }
        require("APPLIED".equals(System.getProperty(
                        "starsector.prepatcher.aotdEconomyRestoreCompletionPatch")),
                "CoreLifecycle restore-completion hook was not applied");
        require("ready".equals(System.getProperty(
                        "starsector.prepatcher.aotdEconomyRestoreCompletion")),
                "restore capability became visible without structural proof");

        require("ACTIVE".equals(String.valueOf(state)),
                "real fork bridge did not activate in " + order + " order: " + state);
        long capabilities = ((Long) bridge.getMethod(
                "getNegotiatedCapabilities").invoke(null)).longValue();
        require((capabilities & RESTORE_CAPABILITY) != 0L,
                "real fork negotiated without restore capability: 0x"
                        + Long.toHexString(capabilities));

        Class<?> runtime = Class.forName(
                "com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge", false, loader);
        require(lifecycle.getClassLoader() == runtime.getClassLoader()
                        && bridge.getClassLoader() == runtime.getClassLoader(),
                "CoreLifecycle/fork/runtime loader mismatch");
        long before = ((Long) bridge.getMethod(
                "getEconomyRestoreCompleteSignalCount").invoke(null)).longValue();
        Method publish = runtime.getMethod("publishAoTDEconomyRestoreComplete");
        publish.invoke(null);
        long after = ((Long) bridge.getMethod(
                "getEconomyRestoreCompleteSignalCount").invoke(null)).longValue();
        require(after == before + 1L,
                "actual-agent restore callback was not delivered exactly once");

        System.out.println("OK actual-agent AoTD economy restore " + order
                + " capabilities=0x" + Long.toHexString(capabilities)
                + " signals=" + after);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
