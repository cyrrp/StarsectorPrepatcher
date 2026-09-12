package com.starsector.prepatcher.agent.templates;

import com.fs.starfarer.api.StarsectorPrepatcherAotdContract;
import com.fs.starfarer.api.util.Pair;

/** Compile-time wrapper body copied into clean BaseIndustry. */
public abstract class BaseIndustryDeficitTemplate {
  private Pair<String, Integer> spp$baseIndustryRawGetMaxDeficit(String... commodityIds) {
    throw new AssertionError("template placeholder");
  }

  @SuppressWarnings("unchecked")
  public Pair<String, Integer> getMaxDeficit(String... commodityIds) {
    Object resolved = StarsectorPrepatcherAotdContract.resolveAoTDMaxDeficit(this, commodityIds);
    if (resolved != null) return (Pair<String, Integer>) resolved;
    return spp$baseIndustryRawGetMaxDeficit(commodityIds);
  }
}
