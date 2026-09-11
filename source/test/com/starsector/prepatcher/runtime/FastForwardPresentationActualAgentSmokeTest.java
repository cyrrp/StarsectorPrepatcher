package com.starsector.prepatcher.runtime;

import java.util.LinkedHashSet;
import java.util.Set;

/** Loads every aggressive presentation target through the packaged javaagent. */
public final class FastForwardPresentationActualAgentSmokeTest {
  private static final String[] TARGETS = {"com.fs.starfarer.campaign.CampaignState",
      "com.fs.starfarer.campaign.CampaignEngine", "com.fs.starfarer.campaign.BaseLocation",
      "com.fs.starfarer.campaign.BaseCampaignEntity", "com.fs.starfarer.campaign.fleet.CampaignFleet",
      "com.fs.starfarer.campaign.SensorContactIndicatorManager", "com.fs.starfarer.campaign.CampaignPlanet",
      "com.fs.starfarer.campaign.JumpPoint", "com.fs.starfarer.api.impl.campaign.terrain.StarCoronaTerrainPlugin",
      "com.fs.starfarer.api.impl.campaign.terrain.MagneticFieldTerrainPlugin",
      "com.fs.starfarer.api.impl.campaign.terrain.SpatialAnomalyTerrainPlugin",
      "com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin",
      "com.fs.starfarer.api.impl.campaign.terrain.RadioChatterTerrainPlugin",
      "com.fs.starfarer.api.impl.campaign.velfield.SlipstreamTerrainPlugin2",
      "com.fs.starfarer.api.impl.campaign.velfield.SlipstreamEntityPlugin2",
      "com.fs.starfarer.api.impl.campaign.terrain.BaseTerrain",
      "com.fs.starfarer.api.impl.campaign.abilities.BaseDurationAbility",
      "com.fs.starfarer.api.impl.campaign.abilities.BaseToggleAbility",
      "com.fs.starfarer.api.impl.campaign.abilities.GenerateSlipsurgeAbility$SlipsurgeEffectScript",
      "com.fs.starfarer.api.impl.campaign.entities.GateHaulerEntityPlugin",
      "com.fs.starfarer.api.impl.campaign.GateEntityPlugin",
      "com.fs.starfarer.api.impl.campaign.world.MoteParticleScript",
      "com.fs.starfarer.api.impl.campaign.CoronalTapParticleScript",
      "com.fs.starfarer.api.impl.campaign.world.ZigLeashAssignmentAI"};

  private static final Set<String> OVERLAPS =
      Set.of("com.fs.starfarer.campaign.CampaignState", "com.fs.starfarer.campaign.CampaignEngine",
          "com.fs.starfarer.campaign.BaseLocation", "com.fs.starfarer.campaign.BaseCampaignEntity",
          "com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin");

  private FastForwardPresentationActualAgentSmokeTest() {}

  public static void main(String[] args) throws Exception {
    require(TARGETS.length == 24, "aggressive target inventory must contain 24 classes");
    require(new LinkedHashSet<>(Set.of(TARGETS)).size() == TARGETS.length,
        "aggressive target inventory contains duplicates");
    require("transformer-installed".equals(System.getProperty("starsector.prepatcher.status")),
        "prepatcher transformer was not installed: " + System.getProperty("starsector.prepatcher.status"));
    require("presentation->structural".equals(System.getProperty("starsector.prepatcher.presentationStructuralOrder")),
        "unexpected presentation/structural transformer order: "
            + System.getProperty("starsector.prepatcher.presentationStructuralOrder"));

    ClassLoader loader = ClassLoader.getSystemClassLoader();
    for (String name : TARGETS) {
      Class.forName(name, false, loader);
      String key = "starsector.prepatcher.patchStatus." + name + ".fastForwardPresentation";
      String status = System.getProperty(key);
      require("APPLIED".equals(status), name + " presentation status: expected APPLIED, found " + status);
      if (OVERLAPS.contains(name)) {
        require(
            "PASSED".equals(
                System.getProperty("starsector.prepatcher.patchStatus." + name + ".presentationStructuralComposition")),
            name + " presentation/structural composition did not pass");
      }
      System.out.println("APPLIED " + name);
    }
    System.out.println("OK fast-forward presentation actual-javaagent classes=" + TARGETS.length);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
