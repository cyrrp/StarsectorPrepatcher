package com.starsector.prepatcher.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import com.starsector.prepatcher.agent.presentation.FastForwardPresentationTransformer;

/** Verifies that late presentation-only classes never disable structural startup. */
public final class FastForwardPresentationLoadedTargetPolicyTest {
  private FastForwardPresentationLoadedTargetPolicyTest() {}

  public static void main(String[] args) throws Exception {
    PrepatcherConfig config = PrepatcherConfig.load(null);
    FastForwardPresentationTransformer plan = new FastForwardPresentationTransformer(config);
    ClassLoader runtimeLoader = ClassLoader.getSystemClassLoader();

    Class<?> presentationOnly = Class.forName("com.fs.starfarer.campaign.CampaignPlanet", false, runtimeLoader);
    boolean disableForPresentationOnly = inspect(instrumentation(presentationOnly), plan, runtimeLoader);
    require(!disableForPresentationOnly, "a presentation-only target disabled the whole presentation transformer");
    require(
        "SKIPPED_ALREADY_LOADED".equals(System.getProperty(
            "starsector.prepatcher.patchStatus." + presentationOnly.getName() + ".fastForwardPresentation")),
        "presentation-only target did not receive an explicit local skip status");

    Class<?> frameMarker = Class.forName("com.fs.starfarer.campaign.CampaignState", false, runtimeLoader);
    boolean disableForMarker = inspect(instrumentation(frameMarker), plan, runtimeLoader);
    require(disableForMarker, "an already-loaded frame marker did not disable presentation coalescing");
    require(
        "SKIPPED_ALREADY_LOADED".equals(System
            .getProperty("starsector.prepatcher.patchStatus." + frameMarker.getName() + ".fastForwardPresentation")),
        "frame marker did not receive an explicit skip status");

    System.out.println("OK fast-forward loaded-target policy"
        + " presentation-only=local-skip frame-marker=presentation-only-disable");
  }

  private static boolean inspect(Instrumentation instrumentation, FastForwardPresentationTransformer plan,
      ClassLoader runtimeLoader) throws Exception {
    Method method = PrepatcherAgent.class.getDeclaredMethod("recordLoadedPresentationTargets", Instrumentation.class,
        FastForwardPresentationTransformer.class, ClassLoader.class);
    method.setAccessible(true);
    return (Boolean) method.invoke(null, instrumentation, plan, runtimeLoader);
  }

  private static Instrumentation instrumentation(Class<?>... loadedClasses) {
    return (Instrumentation) Proxy.newProxyInstance(
        FastForwardPresentationLoadedTargetPolicyTest.class.getClassLoader(), new Class<?>[]{Instrumentation.class},
        (proxy, method, args) -> {
          if (method.getName().equals("getAllLoadedClasses")) return loadedClasses;
          Class<?> type = method.getReturnType();
          if (type == boolean.class) return false;
          if (type == int.class) return 0;
          if (type == long.class) return 0L;
          return null;
        });
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
