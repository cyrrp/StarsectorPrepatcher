package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.StarsectorPrepatcherHooks;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.starsector.prepatcher.agent.PrepatcherConfig;

import java.lang.reflect.Constructor;
import java.util.Properties;

/** Non-agent checks for configuration and one-time class eligibility. */
public final class MarketNoOpRuntimeRegressionTest {
  private MarketNoOpRuntimeRegressionTest() {}

  public static void main(String[] args) throws Exception {
    PrepatcherConfig enabled = config(true, 7);
    require(enabled.marketNoOpCallbacks && enabled.marketNoOpIndustryAuditFrames == 7,
        "market no-op configuration was not parsed");
    PrepatcherConfig disabled = config(false, 0);
    require(!disabled.marketNoOpCallbacks && disabled.marketNoOpIndustryAuditFrames == 0,
        "disabled market no-op configuration was not parsed");

    require(StarsectorPrepatcherHooks.isBaseIndustryDormantFastPathEligible(new InheritedIndustry()),
        "plain inherited BaseIndustry was rejected");
    require(!StarsectorPrepatcherHooks.isBaseIndustryDormantFastPathEligible(new CustomAdvanceIndustry()),
        "custom advance() was classified as dormant no-op");
    require(!StarsectorPrepatcherHooks.isBaseIndustryDormantFastPathEligible(new CustomDisruptionIndustry()),
        "custom isDisrupted() was classified as dormant no-op");
    require(!StarsectorPrepatcherHooks.isBaseIndustryDormantFastPathEligible(new CustomKeyIndustry()),
        "custom getDisruptedKey() was classified as dormant no-op");
    require(!StarsectorPrepatcherHooks.isBaseIndustryDormantFastPathEligible(null),
        "null industry was classified as eligible");

    System.out.println("OK market-noop-runtime config + inherited/custom eligibility");
  }

  private static PrepatcherConfig config(boolean enabled, int auditFrames) throws Exception {
    Properties properties = new Properties();
    properties.setProperty("patch.marketNoOpCallbacks", Boolean.toString(enabled));
    properties.setProperty("market.noOpIndustryAuditFrames", Integer.toString(auditFrames));
    properties.setProperty("logging.statsIntervalSeconds", "0");
    Constructor<PrepatcherConfig> constructor = PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
    constructor.setAccessible(true);
    return constructor.newInstance(properties);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static class InheritedIndustry extends BaseIndustry {
    @Override
    public void apply() {}
  }

  private static final class CustomAdvanceIndustry extends InheritedIndustry {
    @Override
    public void advance(float amount) {}
  }

  private static final class CustomDisruptionIndustry extends InheritedIndustry {
    @Override
    public boolean isDisrupted() { return false; }
  }

  private static final class CustomKeyIndustry extends InheritedIndustry {
    @Override
    public String getDisruptedKey() { return "$custom"; }
  }
}
