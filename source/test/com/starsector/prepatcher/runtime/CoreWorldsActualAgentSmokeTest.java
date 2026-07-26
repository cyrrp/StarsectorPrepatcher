package com.starsector.prepatcher.runtime;

/** Loads all three core-worlds targets through the real javaagent. */
public final class CoreWorldsActualAgentSmokeTest {
    private CoreWorldsActualAgentSmokeTest() {}

    public static void main(String[] args) throws Exception {
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        Class<?> coreScript = Class.forName(
                "com.fs.starfarer.api.impl.campaign.CoreScript", false, loader);
        Class<?> campaignEngine = Class.forName(
                "com.fs.starfarer.campaign.CampaignEngine", false, loader);
        Class<?> baseLocation = Class.forName(
                "com.fs.starfarer.campaign.BaseLocation", false, loader);
        Class<?> runtime = Class.forName(
                "com.fs.starfarer.api.StarsectorPrepatcherCoreWorldsRuntime", false, loader);

        String coreStatus = status("com.fs.starfarer.api.impl.campaign.CoreScript");
        String engineStatus = status("com.fs.starfarer.campaign.CampaignEngine");
        String locationStatus = status("com.fs.starfarer.campaign.BaseLocation");
        require("APPLIED".equals(coreStatus), "unexpected CoreScript status: " + coreStatus);
        require("APPLIED".equals(engineStatus),
                "unexpected CampaignEngine status: " + engineStatus);
        require("APPLIED".equals(locationStatus),
                "unexpected BaseLocation status: " + locationStatus);
        require(coreScript.getClassLoader() == runtime.getClassLoader()
                        && campaignEngine.getClassLoader() == runtime.getClassLoader()
                        && baseLocation.getClassLoader() == runtime.getClassLoader(),
                "core-worlds target/runtime loader mismatch");

        System.out.println("OK actual-agent core-worlds statuses="
                + coreStatus + "/" + engineStatus + "/" + locationStatus
                + " loader=" + runtime.getClassLoader());
    }

    private static String status(String className) {
        return System.getProperty("starsector.prepatcher.patchStatus."
                + className + ".coreWorldsExtentCache");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
