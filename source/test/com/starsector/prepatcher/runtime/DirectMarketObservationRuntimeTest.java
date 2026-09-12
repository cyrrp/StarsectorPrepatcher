package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.StarsectorPrepatcherHooks;
import com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.starsector.prepatcher.agent.PrepatcherConfig;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;

/** File-output and fail-open behavior test for direct Market.advance observation. */
public final class DirectMarketObservationRuntimeTest {
  private DirectMarketObservationRuntimeTest() {}

  public static void main(String[] args) throws Exception {
    Path root = Files.createTempDirectory("prepatcher-direct-market-");
    try {
      PrepatcherConfig config = config();
      System.setProperty("starsector.prepatcher.version", "test");
      System.setProperty("starsector.prepatcher.sessionOrigin", "runtime-test");
      StarsectorPrepatcherRuntimeBridge.configure(config, root);

      Counter counter = new Counter();
      MarketAPI market = (MarketAPI) Proxy.newProxyInstance(ClassLoader.getSystemClassLoader(),
          new Class<?>[]{MarketAPI.class}, (proxy, method, arguments) -> {
            if (method.getName().equals("advance")) {
              float amount = (Float) arguments[0];
              // Simulates the entry probe installed in concrete Market.advance().
              StarsectorPrepatcherHooks.observeMarketAdvanceEntry((MarketAPI) proxy, amount);
              counter.calls++;
              counter.sum += amount;
            }
            return defaultValue(method.getReturnType());
          });

      String metadata = String.join("\u001f", "1", "mods/TestMod/test.jar", "example.Driver", "advance", "(F)V", "42",
          "0", "ARG0", "com.fs.starfarer.api.campaign.econ.MarketAPI", "185");
      long siteId = 0x1234abcdL;
      // Registration happens during class transformation, before the site executes.
      StarsectorPrepatcherRuntimeBridge.registerDirectMarketCallSite(siteId, metadata);
      Path session = waitForSession(root.resolve("logs/direct-market-observe"));
      String sessionMetadata = Files.readString(session.resolve("session.json"), StandardCharsets.UTF_8);
      require(sessionMetadata.contains("\"schema\": 3"), "observer session schema was not updated");
      require(sessionMetadata.contains("\"sessionOrigin\": \"runtime-test\""),
          "observer session origin was not persisted");
      require(session.getFileName().toString().startsWith("session-runtime-test-"),
          "observer smoke/test session was not visibly labelled: " + session);
      Path observations = session.resolve("observations.csv");
      Path sites = session.resolve("call-sites.csv");
      Path summary = session.resolve("summary.csv");
      Path unknownFile = session.resolve("unknown-stacks.csv");
      waitForContent(sites, "mods/TestMod/test.jar", 5000L);
      verifyUnknownIntervalHandoff(unknownFile);

      StarsectorPrepatcherHooks.observeDirectMarketAdvance(market, 0.25f, siteId, metadata);
      StarsectorPrepatcherHooks.observeDirectMarketAdvance(market, 0.75f, siteId, metadata);
      StarsectorPrepatcherHooks.advanceMarketScheduled(market, 0.125f, 1);
      // Simulates reflection/uninstrumented caller with no origin marker.
      market.advance(0.5f);
      require(counter.calls == 4, "observation paths changed call count");
      require(Float.floatToIntBits(counter.sum) == Float.floatToIntBits(1.625f),
          "observation paths changed amount sum: " + counter.sum);
      reportNow();

      waitForContent(observations, "example.Driver", 5000L);
      waitForSummaryCounts(summary, 1L, 2L, 1L, 5000L);
      waitForDataRows(unknownFile, 2, 5000L);

      // The stack budget is per reporting interval, not process lifetime.
      market.advance(0.25f);
      reportNow();
      waitForDataRows(unknownFile, 3, 5000L);

      String observationText = Files.readString(observations, StandardCharsets.UTF_8);
      require(observationText.contains("0x1234abcd"), "site id missing from observations");
      require(observationText.contains("example.Driver"), "caller metadata missing from observations");
      String unknown = Files.readString(unknownFile, StandardCharsets.UTF_8);
      require(dataRows(unknown) >= 3, "unknown-entry stack budget was not renewed per interval");
      require(!unknown.contains("StarsectorPrepatcherMarketDiagnostics"),
          "observer implementation leaked into sampled caller stacks");
      verifyNestedFailureAndRecovery(observations);

      System.out.println("OK direct-market-runtime session=" + session + " calls=" + counter.calls
          + " eager-manifest planet-origin interval-stack-budget");
    } finally {
      System.clearProperty("starsector.prepatcher.sessionOrigin");
      deleteRecursively(root);
    }
  }

  private static PrepatcherConfig config() throws Exception {
    Properties properties = new Properties();
    properties.setProperty("patch.directMarketObservation", "true");
    properties.setProperty("directMarket.timingSampleEvery", "1");
    properties.setProperty("directMarket.stackSampleEvery", "1");
    properties.setProperty("directMarket.maxStacksPerSite", "4");
    // Keep the daemon reporter out of deterministic interval-handoff tests.
    properties.setProperty("directMarket.reportIntervalSeconds", "3600");
    properties.setProperty("directMarket.maxSites", "32");
    properties.setProperty("directMarket.unknownStackSamples", "4");
    properties.setProperty("logging.statsIntervalSeconds", "0");
    Constructor<PrepatcherConfig> constructor = PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
    constructor.setAccessible(true);
    return constructor.newInstance(properties);
  }

  private static Path waitForSession(Path base) throws Exception {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      if (Files.isDirectory(base)) {
        try (Stream<Path> entries = Files.list(base)) {
          Path session = entries.filter(Files::isDirectory).findFirst().orElse(null);
          if (session != null) return session;
        }
      }
      Thread.sleep(25L);
    }
    throw new AssertionError("observation session directory was not created: " + base);
  }

  @SuppressWarnings("unchecked")
  private static void verifyUnknownIntervalHandoff(Path unknownFile) throws Exception {
    Object observer = directMarketObserver();

    var intervalField = observer.getClass().getDeclaredField("unknownStackInterval");
    intervalField.setAccessible(true);
    AtomicReference<Object> intervals = (AtomicReference<Object>) intervalField.get(observer);
    Object detached = intervals.get();
    Method acquire = detached.getClass().getDeclaredMethod("tryAcquireWriter");
    Method release = detached.getClass().getDeclaredMethod("releaseWriter");
    acquire.setAccessible(true);
    release.setAccessible(true);
    require(Boolean.TRUE.equals(acquire.invoke(detached)), "could not reserve an unknown-stack interval writer");

    Method report = observer.getClass().getDeclaredMethod("report");
    report.setAccessible(true);
    AtomicReference<Throwable> reportFailure = new AtomicReference<>();
    Thread reporting = new Thread(() -> {
      try {
        report.invoke(observer);
      } catch (InvocationTargetException failure) {
        reportFailure.set(failure.getCause());
      } catch (Throwable failure) {
        reportFailure.set(failure);
      }
    }, "direct-market-interval-handoff-test");
    reporting.setDaemon(true);

    try {
      reporting.start();
      long deadline = System.nanoTime() + 2_000_000_000L;
      while (intervals.get() == detached && reporting.isAlive() && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      require(intervals.get() != detached, "observer report did not detach the unknown-stack interval");
      require(reporting.isAlive(), "observer report did not wait for an active interval writer");

      var stacksField = detached.getClass().getDeclaredField("stacks");
      stacksField.setAccessible(true);
      Map<String, LongAdder> stacks = (Map<String, LongAdder>) stacksField.get(detached);
      stacks.computeIfAbsent("deterministic-race-stack", ignored -> new LongAdder()).increment();
    } finally {
      release.invoke(detached);
    }

    reporting.join(2_000L);
    require(!reporting.isAlive(), "observer report did not drain the interval writer");
    require(reportFailure.get() == null, "observer interval handoff report failed: " + reportFailure.get());
    waitForContent(unknownFile, "deterministic-race-stack", 2000L);
  }

  private static void verifyNestedFailureAndRecovery(Path observations) throws Exception {
    RuntimeException original = new IllegalStateException("nested market failure");
    int[] calls = new int[3];
    String metadata = String.join("\u001f", "1", "mods/TestMod/test.jar", "example.Nested", "advance", "(F)V", "42",
        "0", "ARG0", "com.fs.starfarer.api.campaign.econ.MarketAPI", "185");
    MarketAPI inner = (MarketAPI) Proxy.newProxyInstance(MarketAPI.class.getClassLoader(),
        new Class<?>[]{MarketAPI.class}, (proxy, method, arguments) -> {
          if (method.getName().equals("advance")) {
            calls[1]++;
            StarsectorPrepatcherHooks.observeMarketAdvanceEntry((MarketAPI) proxy, (Float) arguments[0]);
            throw original;
          }
          return defaultValue(method.getReturnType());
        });
    MarketAPI outer = (MarketAPI) Proxy.newProxyInstance(MarketAPI.class.getClassLoader(),
        new Class<?>[]{MarketAPI.class}, (proxy, method, arguments) -> {
          if (method.getName().equals("advance")) {
            calls[0]++;
            StarsectorPrepatcherHooks.observeMarketAdvanceEntry((MarketAPI) proxy, (Float) arguments[0]);
            StarsectorPrepatcherHooks.observeDirectMarketAdvance(inner, 0.125f, 0x202L, metadata);
          }
          return defaultValue(method.getReturnType());
        });
    try {
      StarsectorPrepatcherHooks.observeDirectMarketAdvance(outer, 0.25f, 0x201L, metadata);
      throw new AssertionError("observer swallowed a nested market exception");
    } catch (RuntimeException failure) {
      require(failure == original, "observer replaced the market exception");
    }
    MarketAPI recovered = (MarketAPI) Proxy.newProxyInstance(MarketAPI.class.getClassLoader(),
        new Class<?>[]{MarketAPI.class}, (proxy, method, arguments) -> {
          if (method.getName().equals("advance")) {
            calls[2]++;
            StarsectorPrepatcherHooks.observeMarketAdvanceEntry((MarketAPI) proxy, (Float) arguments[0]);
          }
          return defaultValue(method.getReturnType());
        });
    StarsectorPrepatcherHooks.observeDirectMarketAdvance(recovered, 0.5f, 0x203L, metadata);
    require(calls[0] == 1 && calls[1] == 1 && calls[2] == 1, "observer changed nested callback multiplicity");
    reportNow();
    String csv = Files.readString(observations, StandardCharsets.UTF_8);
    require(
        observationCounter(csv, "0x201", "exceptions") == 1L && observationCounter(csv, "0x202", "exceptions") == 1L,
        "nested failure was not attributed to both calls");
    require(
        observationCounter(csv, "0x202", "recursive_calls") == 1L
            && observationCounter(csv, "0x203", "recursive_calls") == 0L,
        "observer context did not unwind after failure");
  }

  private static long observationCounter(String csv, String site, String column) {
    String[] lines = csv.split("\\R");
    String[] header = lines[0].split(",");
    int field = java.util.Arrays.asList(header).indexOf(column);
    require(field >= 0, "missing observer column " + column);
    long total = 0;
    boolean found = false;
    for (int i = 1; i < lines.length; i++) {
      String[] row = lines[i].split(",", -1);
      if (row.length > field && row[2].replace("\"", "").equals(site)) {
        found = true;
        total += Long.parseLong(row[field].replace("\"", ""));
      }
    }
    require(found, "missing observer row " + site);
    return total;
  }

  private static Object directMarketObserver() throws Exception {
    var observerField = Class.forName("com.fs.starfarer.api.StarsectorPrepatcherMarketDiagnostics")
        .getDeclaredField("DIRECT_MARKET_OBSERVER");
    observerField.setAccessible(true);
    Object observer = observerField.get(null);
    require(observer != null, "direct-market observer was not configured");
    return observer;
  }

  private static void reportNow() throws Exception {
    Object observer = directMarketObserver();
    Method report = observer.getClass().getDeclaredMethod("report");
    report.setAccessible(true);
    try {
      report.invoke(observer);
    } catch (InvocationTargetException failure) {
      throw new AssertionError("direct-market observer report failed", failure.getCause());
    }
  }

  private static void waitForContent(Path file, String needle, long timeoutMs) throws Exception {
    long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
    while (System.nanoTime() < deadline) {
      if (Files.isRegularFile(file)) {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.contains(needle)) return;
      }
      Thread.sleep(50L);
    }
    throw new AssertionError("timed out waiting for '" + needle + "' in " + file);
  }

  private static void waitForSummaryCounts(Path file, long planetFallback, long direct, long unknown, long timeoutMs)
      throws Exception {
    long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
    while (System.nanoTime() < deadline) {
      if (Files.isRegularFile(file)) {
        String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\\R");
        if (lines.length > 1) {
          String[] header = lines[0].split(",", -1);
          int p = column(header, "planet_condition_fallback_entries");
          int d = column(header, "direct_callsite_entries");
          int u = column(header, "unknown_entries");
          long ps = 0L, ds = 0L, us = 0L;
          for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            String[] values = lines[i].split(",", -1);
            ps += Long.parseLong(values[p]);
            ds += Long.parseLong(values[d]);
            us += Long.parseLong(values[u]);
          }
          if (ps >= planetFallback && ds >= direct && us >= unknown) return;
        }
      }
      Thread.sleep(50L);
    }
    throw new AssertionError("timed out waiting for observer summary counts in " + file);
  }

  private static int column(String[] header, String name) {
    for (int i = 0; i < header.length; i++) if (header[i].equals(name)) return i;
    throw new AssertionError("missing CSV column " + name);
  }

  private static void waitForDataRows(Path file, int rows, long timeoutMs) throws Exception {
    long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
    while (System.nanoTime() < deadline) {
      if (Files.isRegularFile(file) && dataRows(Files.readString(file, StandardCharsets.UTF_8)) >= rows) return;
      Thread.sleep(50L);
    }
    throw new AssertionError("timed out waiting for " + rows + " data rows in " + file);
  }

  private static int dataRows(String text) {
    String[] lines = text.split("\\R");
    int rows = 0;
    for (int i = 1; i < lines.length; i++) if (!lines[i].isBlank()) rows++;
    return rows;
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) return null;
    if (type == boolean.class) return false;
    if (type == byte.class) return (byte) 0;
    if (type == short.class) return (short) 0;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    if (type == float.class) return 0f;
    if (type == double.class) return 0d;
    if (type == char.class) return '\0';
    return null;
  }

  private static void deleteRecursively(Path root) throws Exception {
    if (root == null || !Files.exists(root)) return;
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static final class Counter {
    int calls;
    float sum;
  }
}
