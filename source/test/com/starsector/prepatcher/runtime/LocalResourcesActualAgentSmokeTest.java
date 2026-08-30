package com.starsector.prepatcher.runtime;

/**
 * Focused actual-agent check for Local Resources. This class deliberately has
 * no static references to game classes, so the legacy FR system loader remains
 * the sole owner of the tested target.
 */
public final class LocalResourcesActualAgentSmokeTest {
    private static final String TARGET = "com.fs.starfarer.api.impl.campaign.submarkets."
            + "LocalResourcesSubmarketPlugin";

    private LocalResourcesActualAgentSmokeTest() {}

    public static void main(String[] args) throws Exception {
        String expectedProfile = args.length == 0 ? null : args[0];
        String actualProfile = System.getProperty(
                "starsector.prepatcher.javaCompatibilityProfile");
        if (expectedProfile != null) {
            require(expectedProfile.equals(actualProfile),
                    "compatibility profile: expected=" + expectedProfile
                            + " actual=" + actualProfile);
        }

        ClassLoader loader = ClassLoader.getSystemClassLoader();
        Class<?> target = Class.forName(TARGET, false, loader);
        require(target.getClassLoader() == loader,
                "Local Resources was not defined by the active system loader: target="
                        + target.getClassLoader() + " system=" + loader);
        require("APPLIED".equals(status("localResourcesNoColdMarketData")),
                "Local Resources cold-data patch was not applied: "
                        + status("localResourcesNoColdMarketData"));
        require("APPLIED".equals(status("localResourcesTooltipSnapshot")),
                "Local Resources tooltip/frame patch was not applied: "
                        + status("localResourcesTooltipSnapshot"));

        System.out.println("OK local-resources actual-agent profile=" + actualProfile
                + " loader=" + loader.getClass().getName());
    }

    private static String status(String patch) {
        return System.getProperty("starsector.prepatcher.patchStatus."
                + TARGET + '.' + patch);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
