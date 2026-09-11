package com.starsector.prepatcher.runtime;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Actual-javaagent smoke for local Market.advance step replay wrappers. */
public final class MarketStepReplayActualAgentSmokeTest {
  private MarketStepReplayActualAgentSmokeTest() {}

  public static void main(String[] args) throws Exception {
    verify("com.fs.starfarer.api.impl.campaign.econ.impl.MilitaryBase", "marketStepReplayMilitaryBase", false);
    verify("com.fs.starfarer.api.impl.campaign.econ.impl.LionsGuardHQ", "marketStepReplayLionsGuardHQ", false);
    verify("com.fs.starfarer.api.impl.campaign.econ.RecentUnrest", "marketStepReplayRecentUnrest", true);

    Class.forName("com.fs.starfarer.campaign.econ.Market", true, ClassLoader.getSystemClassLoader());
    String marketStatus =
        System.getProperty("starsector.prepatcher.patchStatus.com.fs.starfarer.campaign.econ.Market.marketAdvancePlan");
    require("APPLIED".equals(marketStatus) || "ALREADY_APPLIED".equals(marketStatus),
        "Market.advance plan was not installed: " + marketStatus);

    Class.forName("com.fs.starfarer.api.impl.campaign.econ.impl.ConstructionQueue", true,
        ClassLoader.getSystemClassLoader());
    String queueStatus = System.getProperty(
        "starsector.prepatcher.patchStatus.com.fs.starfarer.api.impl.campaign.econ.impl.ConstructionQueue.marketConstructionMutationBarriers");
    require("APPLIED".equals(queueStatus) || "ALREADY_APPLIED".equals(queueStatus),
        "ConstructionQueue barriers were not installed: " + queueStatus);

    Class.forName("com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry", true,
        ClassLoader.getSystemClassLoader());
    String industryStatus = System.getProperty("starsector.prepatcher.patchStatus."
        + "com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry." + "marketConstructionMutationBarriers");
    require("APPLIED".equals(industryStatus) || "ALREADY_APPLIED".equals(industryStatus),
        "BaseIndustry barriers were not installed: " + industryStatus);

    int capabilityMask =
        Integer.parseInt(System.getProperty("starsector.prepatcher.marketSchedulerCapabilityMask", "0"));
    int semanticMask = 32 | 64 | 128 | 256 | 512 | 1024;
    require((capabilityMask & semanticMask) == semanticMask,
        "actual-agent semantic capability registrations are incomplete: mask=" + capabilityMask);

    System.out.println("OK market-step-replay actual-javaagent wrappers=3"
        + " market-context construction-barriers semanticMask=" + semanticMask);
  }

  private static void verify(String className, String patchId, boolean recent) throws Exception {
    Class<?> type = Class.forName(className, true, ClassLoader.getSystemClassLoader());
    Method wrapper = type.getDeclaredMethod("advance", float.class);
    Method raw = type.getDeclaredMethod("smo$advanceSingleStep", float.class);
    require(Modifier.isPublic(wrapper.getModifiers()), className + " wrapper is not public");
    require(Modifier.isPrivate(raw.getModifiers()) && raw.isSynthetic(),
        className + " raw single-step body metadata changed");
    String status = System.getProperty("starsector.prepatcher.patchStatus." + className + "." + patchId);
    require("APPLIED".equals(status) || "ALREADY_APPLIED".equals(status),
        className + " replay patch was not installed: " + status);
    if (recent) {
      require(className.endsWith("RecentUnrest"), "RecentUnrest smoke target changed");
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
