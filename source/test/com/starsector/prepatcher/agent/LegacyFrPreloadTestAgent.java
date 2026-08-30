package com.starsector.prepatcher.agent;

import java.lang.instrument.Instrumentation;

/** Loads the legacy FR ClassTransformer before Prepatcher to exercise retransformation setup. */
public final class LegacyFrPreloadTestAgent {
    private LegacyFrPreloadTestAgent() {
    }

    public static void premain(String ignored, Instrumentation instrumentation) throws Exception {
        ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
        ClassLoader implementationLoader = systemLoader.getClass().getClassLoader();
        Class.forName("com.genir.renderer.loaders.ClassTransformer", true,
                implementationLoader);
        System.setProperty("starsector.prepatcher.test.legacyFrPreloaded", "true");
    }
}
