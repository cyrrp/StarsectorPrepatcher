package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.campaign.rules.Memory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual-javaagent verifier and behavioral smoke for the direct BaseIndustry wrapper. */
public final class MarketNoOpActualAgentSmokeTest {
  private MarketNoOpActualAgentSmokeTest() {}

  public static void main(String[] args) throws Exception {
    Field state = BaseIndustry.class.getDeclaredField("spp$marketNoOpState");
    Field countdown = BaseIndustry.class.getDeclaredField("spp$marketNoOpCountdown");
    Method raw = BaseIndustry.class.getDeclaredMethod("spp$baseIndustryRawAdvance", float.class);
    require(
        Modifier.isPrivate(state.getModifiers()) && Modifier.isTransient(state.getModifiers()) && state.isSynthetic(),
        "dormant state field metadata changed");
    require(Modifier.isPrivate(countdown.getModifiers()) && Modifier.isTransient(countdown.getModifiers())
        && countdown.isSynthetic(), "dormant countdown field metadata changed");
    require(Modifier.isPrivate(raw.getModifiers()) && raw.isSynthetic(),
        "retained raw BaseIndustry.advance metadata changed");
    state.setAccessible(true);
    countdown.setAccessible(true);

    AtomicInteger memoryCalls = new AtomicInteger();
    Memory memory = new Memory();
    MarketAPI market = market(memory, memoryCalls);
    InheritedIndustry industry = new InheritedIndustry();
    industry.bind(market);

    industry.advance(1f);
    require(memoryCalls.get() == 1, "first inherited callback did not execute vanilla body");
    require(state.getInt(industry) == 3 && countdown.getInt(industry) == 2,
        "first full callback did not enter dormant state");

    industry.advance(1f);
    industry.advance(1f);
    require(memoryCalls.get() == 1 && countdown.getInt(industry) == 0, "bounded dormant calls were not skipped");
    industry.advance(1f);
    require(memoryCalls.get() == 2 && countdown.getInt(industry) == 2,
        "periodic dormant audit did not execute vanilla body");

    int beforeDisruption = memoryCalls.get();
    industry.setDisrupted(1f, false);
    require(state.getInt(industry) == 0 && countdown.getInt(industry) == 0,
        "setDisrupted did not wake the dormant wrapper");
    industry.advance(1f);
    require(memoryCalls.get() > beforeDisruption, "woken industry did not execute the vanilla callback immediately");

    CustomDisruptionIndustry custom = new CustomDisruptionIndustry();
    for (int i = 0; i < 5; i++) custom.advance(0.25f);
    require(custom.checks == 5 && state.getInt(custom) == 1, "custom isDisrupted() industry was throttled");

    CustomKeyIndustry customKey = new CustomKeyIndustry();
    customKey.bind(market);
    for (int i = 0; i < 5; i++) customKey.advance(0.25f);
    require(customKey.keys == 5 && state.getInt(customKey) == 1, "custom getDisruptedKey() industry was throttled");

    CustomAdvanceIndustry override = new CustomAdvanceIndustry();
    for (int i = 0; i < 5; i++) override.advance(0.25f);
    require(override.calls == 5 && state.getInt(override) == 0, "custom advance() dispatch was intercepted");

    System.out.println("OK market-noop-actual-agent direct dormant wrapper/audit/wake/fallback");
  }

  private static MarketAPI market(Memory memory, AtomicInteger calls) {
    return (MarketAPI) Proxy.newProxyInstance(MarketNoOpActualAgentSmokeTest.class.getClassLoader(),
        new Class<?>[]{MarketAPI.class}, (proxy, method, args) -> {
          String name = method.getName();
          if (name.equals("getMemoryWithoutUpdate")) {
            calls.incrementAndGet();
            return memory;
          }
          if (name.equals("toString")) return "MarketNoOpTestMarket";
          if (name.equals("hashCode")) return System.identityHashCode(proxy);
          if (name.equals("equals")) return proxy == args[0];
          return defaultValue(method.getReturnType());
        });
  }

  private static Object defaultValue(Class<?> type) {
    if (type == boolean.class) return false;
    if (type == byte.class) return (byte) 0;
    if (type == short.class) return (short) 0;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    if (type == float.class) return 0f;
    if (type == double.class) return 0d;
    if (type == char.class) return (char) 0;
    return null;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static class InheritedIndustry extends BaseIndustry {
    @Override
    public void apply() {}

    void bind(MarketAPI market) {
      this.market = market;
    }
  }

  private static final class CustomDisruptionIndustry extends InheritedIndustry {
    int checks;

    @Override
    public boolean isDisrupted() {
      checks++;
      return false;
    }
  }

  private static final class CustomKeyIndustry extends InheritedIndustry {
    int keys;

    @Override
    public String getDisruptedKey() {
      keys++;
      return "$marketNoOpCustom";
    }
  }

  private static final class CustomAdvanceIndustry extends InheritedIndustry {
    int calls;

    @Override
    public void advance(float amount) {
      calls++;
    }
  }
}
