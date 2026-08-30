package com.starsector.prepatcher.agent;

/** Subprocess entry point used to report the route selected by premain. */
public final class JavaCompatibilityRouteSmokeMain {
    private JavaCompatibilityRouteSmokeMain() {
    }

    public static void main(String[] args) {
        System.out.println("profile=" + System.getProperty(
                "starsector.prepatcher.javaCompatibilityProfile"));
        System.out.println("probe=" + System.getProperty(
                "starsector.prepatcher.javaCompatibilityProbe"));
        System.out.println("status=" + System.getProperty(
                "starsector.prepatcher.status"));
        System.out.println("legacyPreloaded=" + System.getProperty(
                "starsector.prepatcher.test.legacyFrPreloaded"));
    }
}
