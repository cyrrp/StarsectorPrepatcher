package com.starsector.prepatcher.runtime;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Real fork JAR in a child mod loader, real Linux game/runtime in the parent. */
public final class LinuxAoTDIntegrationSmokeTest {
  public static void main(String[] args) throws Exception {
    List<URL> jars = new ArrayList<>();
    for (String arg : args) jars.add(Path.of(arg).toUri().toURL());
    ClassLoader parent = ClassLoader.getSystemClassLoader();
    try (URLClassLoader modLoader = new URLClassLoader(jars.toArray(URL[]::new), parent)) {
      Class<?> bridge = Class.forName("data.kaysaar.aotd.tot.compat.SchedulerBridge", true, modLoader);
      require(bridge.getClassLoader() == modLoader, "real fork did not load in child");
      Object state = bridge.getMethod("initialize").invoke(null);
      require("ACTIVE".equals(String.valueOf(state)), "fork handshake: " + state);
      long capabilities = (Long) bridge.getMethod("getNegotiatedCapabilities").invoke(null);
      require(capabilities == 0xfffL, "incomplete capabilities: 0x" + Long.toHexString(capabilities));
      Class<?> runtime = Class.forName("com.fs.starfarer.api.StarsectorPrepatcherAotdContract", false, parent);
      require(runtime.getClassLoader() == parent, "runtime not in parent");
      long before = (Long) bridge.getMethod("getEconomyRestoreCompleteSignalCount").invoke(null);
      runtime.getMethod("publishAoTDEconomyRestoreComplete").invoke(null);
      long after = (Long) bridge.getMethod("getEconomyRestoreCompleteSignalCount").invoke(null);
      require(after == before + 1, "restore callback was not delivered exactly once");
      for (String name : List.of(
          "data.kaysaar.aotd.tot.scripts.economy.AoTDEconomy",
          "data.kaysaar.aotd.tot.scripts.economy.AoTDReachEconomy",
          "data.kaysaar.aotd.tot.scripts.commoditydata.AoTDCommodityOnMarket",
          "data.kaysaar.aotd.tot.scripts.commoditydata.AoTDSupplyDemandData",
          "data.kaysaar.aotd.tot.scripts.economy.AoTDUIEconomyRefreshCoordinator")) {
        Class<?> type = Class.forName(name, false, modLoader);
        require(type.getClassLoader() == modLoader, "fixture shadowed real fork: " + name);
        type.getDeclaredMethods(); type.getDeclaredFields(); type.getDeclaredConstructors();
      }
      for (String key : System.getProperties().stringPropertyNames()) {
        if (key.startsWith("starsector.prepatcher.pipelineFailure.")) {
          throw new AssertionError(key + "=" + System.getProperty(key));
        }
      }
      System.out.println("OK Linux real AoTD child-loader integration: ACTIVE capabilities=0x"
          + Long.toHexString(capabilities) + " restore-signal=exactly-once native-economy-linkage");
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
