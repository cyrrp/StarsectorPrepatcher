package com.fs.starfarer.api;

import com.starsector.prepatcher.agent.PrepatcherConfig;
import com.starsector.prepatcher.agent.PrepatcherLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

import static com.fs.starfarer.api.StarsectorPrepatcherMarketScheduler.*;

/** Diagnostics own only detached values and observer aggregates, never scheduler state or campaign objects. */
final class StarsectorPrepatcherMarketDiagnostics {
  private static volatile ConstructionDiagnostics CONSTRUCTION_DIAGNOSTICS;
  private static volatile DirectMarketObserver DIRECT_MARKET_OBSERVER;
  private static final ThreadLocal<DirectMarketSiteCache> SITE_CACHE =
      ThreadLocal.withInitial(DirectMarketSiteCache::new);
  static ConstructionDiagnostics constructionDiagnostics() {
    return CONSTRUCTION_DIAGNOSTICS;
  }
  static DirectMarketObserver directObserver() {
    return DIRECT_MARKET_OBSERVER;
  }
  private static final class DirectMarketSiteCache {
    final long[] observerSiteIds = new long[8];
    final String[] observerMetadata = new String[8];
    final DirectMarketSiteStats[] observerStats = new DirectMarketSiteStats[8];
  }
  static void configureDiagnostics(PrepatcherConfig optimizerConfig, Path root) {
    CONSTRUCTION_DIAGNOSTICS = null;
    if (optimizerConfig.marketConstructionDiagnostics) {
      try {
        ConstructionDiagnostics diagnostics = new ConstructionDiagnostics(optimizerConfig, root);
        CONSTRUCTION_DIAGNOSTICS = diagnostics;
        diagnostics.start();
      } catch (Throwable failure) {
        CONSTRUCTION_DIAGNOSTICS = null;
        PrepatcherLog.error("Market construction diagnostics initialization failed; "
            + "scheduler behavior is unchanged and CSV sampling is disabled.", failure);
      }
    }
    if (optimizerConfig.directMarketObservation) {
      try {
        DirectMarketObserver observer = new DirectMarketObserver(optimizerConfig, root);
        DIRECT_MARKET_OBSERVER = observer;
        observer.start();
      } catch (Throwable failure) {
        DIRECT_MARKET_OBSERVER = null;
        PrepatcherLog.error("Direct Market.advance observation initialization failed; "
            + "market behavior remains unchanged and observation is disabled.", failure);
      }
    }
  }
  public static String dumpMarketSchedulerBaseline(String reason) {
    Path root = StarsectorPrepatcherHooks.modRoot;
    if (root == null) return "";
    String safeReason = reason == null || reason.isBlank() ? "manual" : reason.replaceAll("[^A-Za-z0-9._-]+", "_");
    String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(Instant.now());
    Path directory = root.resolve("logs").resolve("market-scheduler-baseline");
    Path csvPath = directory.resolve("baseline-" + stamp + '-' + safeReason + ".csv");
    Path jsonPath = directory.resolve("baseline-" + stamp + '-' + safeReason + ".json");

    var snapshot = StarsectorPrepatcherMarketScheduler.schedulerSnapshot();
    ArrayList<String[]> rows = new ArrayList<>();
    long pendingStepsTotal = 0L;
    long pendingRunsTotal = 0L;
    long deliveredCallbacksTotal = 0L;
    double deliveredAmountTotal = 0d;
    for (var market : snapshot.markets()) {
      pendingStepsTotal += market.pendingSteps();
      pendingRunsTotal += market.pendingRuns();
      deliveredCallbacksTotal += market.deliveredCallbacks();
      deliveredAmountTotal += market.deliveredAmount();
      rows.add(new String[]{market.marketId(), market.marketName(), Integer.toString(market.identityHash()),
          Integer.toString(market.pendingSteps()), Float.toString(market.pendingAmount()),
          Integer.toString(market.pendingRuns()), market.pendingHistory(), Long.toString(market.deliveredCallbacks()),
          Double.toString(market.deliveredAmount()), Long.toString(market.lastDeliveredSequence()),
          Integer.toString(market.lastDeliveredOrigin()), Long.toString(market.lastDeliveredNanos()),
          Long.toString(market.debtSequence()), Integer.toString(market.lastSource()),
          Integer.toString(market.sourceMask()), Boolean.toString(market.perSimulationTick()),
          Boolean.toString(market.constructionFullRate()), Integer.toString(market.constructionReasonMask()),
          Boolean.toString(market.inAdvance()), Boolean.toString(market.disabled()),
          Long.toString(market.nextDueBatch()), Long.toString(market.lastTouchedBatch())});
    }
    rows.sort((left, right) -> {
      int byId = left[0].compareTo(right[0]);
      return byId != 0 ? byId : left[2].compareTo(right[2]);
    });

    StringBuilder csv = new StringBuilder(
        "market_id,market_name,identity_hash,pending_steps,pending_amount,pending_runs,pending_history,delivered_callbacks,delivered_amount,last_delivered_sequence,last_delivered_origin,last_delivered_nanos,debt_sequence,last_source,source_mask,per_simulation_tick,construction_full_rate,construction_reason_mask,in_advance,disabled,next_due_batch,last_touched_batch\n");
    for (String[] row : rows) {
      for (int i = 0; i < row.length; i++) {
        if (i > 0) csv.append(',');
        csv.append(baselineCsv(row[i]));
      }
      csv.append('\n');
    }
    String json = "{\n" + "  \"schemaVersion\": 1,\n" + "  \"capturedUtc\": \"" + baselineJson(Instant.now().toString())
        + "\",\n" + "  \"reason\": \"" + baselineJson(reason == null ? "" : reason) + "\",\n" + "  \"schedulerReady\": "
        + snapshot.ready() + ",\n" + "  \"markets\": " + rows.size() + ",\n" + "  \"pendingSteps\": "
        + pendingStepsTotal + ",\n" + "  \"pendingRuns\": " + pendingRunsTotal + ",\n" + "  \"deliveredCallbacks\": "
        + deliveredCallbacksTotal + ",\n" + "  \"deliveredAmount\": " + deliveredAmountTotal + "\n" + "}\n";
    try {
      Files.createDirectories(directory);
      Files.writeString(csvPath, csv, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
      Files.writeString(jsonPath, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
      return csvPath.toAbsolutePath().normalize().toString();
    } catch (Throwable failure) {
      PrepatcherLog.warn("Could not write market scheduler baseline: " + failure);
      return "";
    }
  }
  private static String baselineCsv(String value) {
    String text = value == null ? "" : value;
    return '"' + text.replace("\"", "\"\"") + '"';
  }
  private static String baselineJson(String value) {
    if (value == null) return "";
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
  }
  record ConstructionSnapshot(int marketIdentityHash, String marketId, String marketName, String queueClass,
      int queueSize, int industryCount, int industryIndex, String industryId, String industryClass,
      boolean industryBuilding, boolean industryEffectiveBuilding, boolean industryUpgrading, int buildingIndustryIndex,
      String buildingIndustryId, String buildingIndustryClass, String buildingRawBuildingField,
      String buildingBuildProgress, String buildingBuildTime, String buildingUpgradeId, String buildingIsFunctional,
      int reportedBuildingWithoutRawIndustryIndex, String reportedBuildingWithoutRawIndustryId,
      String reportedBuildingWithoutRawIndustryClass, String reportedBuildingWithoutRawRawBuildingField,
      String reportedBuildingWithoutRawBuildProgress, String reportedBuildingWithoutRawBuildTime,
      String reportedBuildingWithoutRawUpgradeId, String reportedBuildingWithoutRawIsFunctional,
      int upgradingIndustryIndex, String upgradingIndustryId, String upgradingIndustryClass,
      String upgradingRawBuildingField, String upgradingBuildProgress, String upgradingBuildTime,
      String upgradingUpgradeId, String upgradingIsFunctional, String exceptionClass, String exceptionMessage) {
  }
  static final class ConstructionDiagnostics {
    private static final DateTimeFormatter SESSION_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    private static final String HEADER = "timestamp_utc,first_seen_utc,last_seen_utc,occurrences,"
        + "market_identity_hash,market_id,market_name,trigger,sample_bucket,transition,"
        + "reason_mask,reasons,queue_class,queue_size,industry_count,"
        + "industry_index,industry_id,industry_class,is_building,"
        + "effective_is_building,is_upgrading,reported_building_without_raw,"
        + "building_industry_index,building_industry_id,building_industry_class,"
        + "building_raw_building_field,building_build_progress,building_build_time,"
        + "building_upgrade_id,building_is_functional," + "reported_building_without_raw_industry_index,"
        + "reported_building_without_raw_industry_id," + "reported_building_without_raw_industry_class,"
        + "reported_building_without_raw_raw_building_field," + "reported_building_without_raw_build_progress,"
        + "reported_building_without_raw_build_time," + "reported_building_without_raw_upgrade_id,"
        + "reported_building_without_raw_is_functional,"
        + "upgrading_industry_index,upgrading_industry_id,upgrading_industry_class,"
        + "upgrading_raw_building_field,upgrading_build_progress,upgrading_build_time,"
        + "upgrading_upgrade_id,upgrading_is_functional,"
        + "mutation_epoch,audited_epoch,last_audit_batch,old_active,new_active,"
        + "old_reason_mask,new_reason_mask,exception_class,exception_message\n";

    private final int maxSamplesPerReason;
    private final int reportIntervalSeconds;
    private final Path output;
    private final ConcurrentHashMap<String, ConstructionDiagnosticSample> samples = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> samplesPerReason = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    ConstructionDiagnostics(PrepatcherConfig configuration, Path root) throws IOException {
      maxSamplesPerReason = configuration.marketConstructionDiagnosticsMaxSamplesPerReason;
      reportIntervalSeconds = configuration.statsLogIntervalSeconds > 0 ? configuration.statsLogIntervalSeconds : 30;
      String sessionName = "session-" + SESSION_FORMAT.format(Instant.now()) + "-pid" + ProcessHandle.current().pid();
      Path directory = root.resolve("logs").resolve("market-construction-diagnostics").resolve(sessionName);
      Files.createDirectories(directory);
      output = directory.resolve("samples.csv");
      Files.writeString(output, HEADER, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
      System.setProperty("starsector.prepatcher.marketConstructionDiagnosticsDir",
          directory.toAbsolutePath().toString());
      PrepatcherLog.info("Market construction diagnostics session: " + directory.toAbsolutePath());
    }

    void start() {
      Thread reporter = new Thread(this::reportLoop, "StarsectorPrepatcher-ConstructionDiagnostics");
      reporter.setDaemon(true);
      reporter.setPriority(Thread.MIN_PRIORITY);
      reporter.start();
    }

    void observe(ConstructionSnapshot probe, String trigger, long mutationEpoch, long auditedEpoch, long lastAuditBatch,
        boolean oldActive, boolean newActive, int oldReasonMask, int newReasonMask) {
      if (newReasonMask == 0 && oldReasonMask == 0 && oldActive == newActive) return;
      int identityHash = probe.marketIdentityHash;
      String marketId = probe.marketId;
      String marketName = probe.marketName;
      String reasonBucket = constructionSampleBucket(oldReasonMask, newReasonMask);
      String industryClass = probe == null ? "" : probe.industryClass;
      String industryId = probe == null ? "" : probe.industryId;
      String buildingClass = probe == null ? "" : probe.buildingIndustryClass;
      String buildingId = probe == null ? "" : probe.buildingIndustryId;
      String reportedWithoutRawClass = probe == null ? "" : probe.reportedBuildingWithoutRawIndustryClass;
      String reportedWithoutRawId = probe == null ? "" : probe.reportedBuildingWithoutRawIndustryId;
      String upgradingClass = probe == null ? "" : probe.upgradingIndustryClass;
      String upgradingId = probe == null ? "" : probe.upgradingIndustryId;
      String key = reasonBucket + '\u001f' + identityHash + '\u001f' + marketId + '\u001f' + oldReasonMask + '\u001f'
          + newReasonMask + '\u001f' + industryClass + '\u001f' + industryId + '\u001f' + buildingClass + '\u001f'
          + buildingId + '\u001f' + reportedWithoutRawClass + '\u001f' + reportedWithoutRawId + '\u001f'
          + upgradingClass + '\u001f' + upgradingId + '\u001f' + trigger;
      ConstructionDiagnosticSample existing = samples.get(key);
      if (existing == null) {
        AtomicInteger count = samplesPerReason.computeIfAbsent(reasonBucket, ignored -> new AtomicInteger());
        int slot = count.incrementAndGet();
        if (slot > maxSamplesPerReason) {
          count.decrementAndGet();
          StarsectorPrepatcherMetrics.CONSTRUCTION_DIAGNOSTIC_SAMPLES_DROPPED.increment();
          return;
        }
        ConstructionDiagnosticSample candidate =
            new ConstructionDiagnosticSample(identityHash, marketId, marketName, trigger, reasonBucket, mutationEpoch,
                auditedEpoch, lastAuditBatch, oldActive, newActive, oldReasonMask, newReasonMask, probe);
        ConstructionDiagnosticSample raced = samples.putIfAbsent(key, candidate);
        existing = raced == null ? candidate : raced;
        if (raced != null) count.decrementAndGet();
      }
      existing.observe();
    }

    private void reportLoop() {
      while (true) {
        try {
          Thread.sleep(reportIntervalSeconds * 1000L);
          writePending();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          try {
            writePending();
          } catch (Throwable ignored) {}
          return;
        } catch (Throwable failure) {
          PrepatcherLog.error("Market construction diagnostics report failed", failure);
        }
      }
    }

    private void writePending() throws IOException {
      String timestamp = Instant.now().toString();
      StringBuilder rows = new StringBuilder();
      for (ConstructionDiagnosticSample sample : samples.values()) {
        long occurrences = sample.pendingOccurrences.sumThenReset();
        if (occurrences <= 0L) continue;
        sample.append(rows, timestamp, occurrences);
      }
      if (rows.length() == 0) return;
      synchronized (writeLock) {
        Files.writeString(output, rows, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      }
    }

  }
  static final class ConstructionDiagnosticSample {
    final int marketIdentityHash;
    final String marketId;
    final String marketName;
    final String trigger;
    final String sampleBucket;
    final String transition;
    final long mutationEpoch;
    final long auditedEpoch;
    final long lastAuditBatch;
    final boolean oldActive;
    final boolean newActive;
    final int oldReasonMask;
    final int newReasonMask;
    final String queueClass;
    final int queueSize;
    final int industryCount;
    final int industryIndex;
    final String industryId;
    final String industryClass;
    final boolean building;
    final boolean effectiveBuilding;
    final boolean upgrading;
    final boolean reportedBuildingWithoutRaw;
    final int buildingIndustryIndex;
    final String buildingIndustryId;
    final String buildingIndustryClass;
    final String buildingRawBuildingField;
    final String buildingBuildProgress;
    final String buildingBuildTime;
    final String buildingUpgradeId;
    final String buildingIsFunctional;
    final int reportedBuildingWithoutRawIndustryIndex;
    final String reportedBuildingWithoutRawIndustryId;
    final String reportedBuildingWithoutRawIndustryClass;
    final String reportedBuildingWithoutRawRawBuildingField;
    final String reportedBuildingWithoutRawBuildProgress;
    final String reportedBuildingWithoutRawBuildTime;
    final String reportedBuildingWithoutRawUpgradeId;
    final String reportedBuildingWithoutRawIsFunctional;
    final int upgradingIndustryIndex;
    final String upgradingIndustryId;
    final String upgradingIndustryClass;
    final String upgradingRawBuildingField;
    final String upgradingBuildProgress;
    final String upgradingBuildTime;
    final String upgradingUpgradeId;
    final String upgradingIsFunctional;
    final String exceptionClass;
    final String exceptionMessage;
    final long firstSeenMillis = System.currentTimeMillis();
    final AtomicLong lastSeenMillis = new AtomicLong(firstSeenMillis);
    final LongAdder pendingOccurrences = new LongAdder();

    ConstructionDiagnosticSample(int marketIdentityHash, String marketId, String marketName, String trigger,
        String sampleBucket, long mutationEpoch, long auditedEpoch, long lastAuditBatch, boolean oldActive,
        boolean newActive, int oldReasonMask, int newReasonMask, ConstructionSnapshot probe) {
      this.marketIdentityHash = marketIdentityHash;
      this.marketId = marketId;
      this.marketName = marketName;
      this.trigger = trigger;
      this.sampleBucket = sampleBucket;
      this.transition = oldReasonMask == newReasonMask ? "" : oldReasonMask + "->" + newReasonMask;
      this.mutationEpoch = mutationEpoch;
      this.auditedEpoch = auditedEpoch;
      this.lastAuditBatch = lastAuditBatch;
      this.oldActive = oldActive;
      this.newActive = newActive;
      this.oldReasonMask = oldReasonMask;
      this.newReasonMask = newReasonMask;
      queueClass = probe == null ? "" : probe.queueClass;
      queueSize = probe == null ? -2 : probe.queueSize;
      industryCount = probe == null ? -2 : probe.industryCount;
      industryIndex = probe == null ? -1 : probe.industryIndex;
      industryId = probe == null ? "" : probe.industryId;
      industryClass = probe == null ? "" : probe.industryClass;
      building = probe != null && probe.industryBuilding;
      effectiveBuilding = probe != null && probe.industryEffectiveBuilding;
      upgrading = probe != null && probe.industryUpgrading;
      reportedBuildingWithoutRaw = probe != null && probe.reportedBuildingWithoutRawIndustryIndex >= 0;
      buildingIndustryIndex = probe == null ? -1 : probe.buildingIndustryIndex;
      buildingIndustryId = probe == null ? "" : probe.buildingIndustryId;
      buildingIndustryClass = probe == null ? "" : probe.buildingIndustryClass;
      buildingRawBuildingField = probe == null ? "" : probe.buildingRawBuildingField;
      buildingBuildProgress = probe == null ? "" : probe.buildingBuildProgress;
      buildingBuildTime = probe == null ? "" : probe.buildingBuildTime;
      buildingUpgradeId = probe == null ? "" : probe.buildingUpgradeId;
      buildingIsFunctional = probe == null ? "" : probe.buildingIsFunctional;
      reportedBuildingWithoutRawIndustryIndex = probe == null ? -1 : probe.reportedBuildingWithoutRawIndustryIndex;
      reportedBuildingWithoutRawIndustryId = probe == null ? "" : probe.reportedBuildingWithoutRawIndustryId;
      reportedBuildingWithoutRawIndustryClass = probe == null ? "" : probe.reportedBuildingWithoutRawIndustryClass;
      reportedBuildingWithoutRawRawBuildingField =
          probe == null ? "" : probe.reportedBuildingWithoutRawRawBuildingField;
      reportedBuildingWithoutRawBuildProgress = probe == null ? "" : probe.reportedBuildingWithoutRawBuildProgress;
      reportedBuildingWithoutRawBuildTime = probe == null ? "" : probe.reportedBuildingWithoutRawBuildTime;
      reportedBuildingWithoutRawUpgradeId = probe == null ? "" : probe.reportedBuildingWithoutRawUpgradeId;
      reportedBuildingWithoutRawIsFunctional = probe == null ? "" : probe.reportedBuildingWithoutRawIsFunctional;
      upgradingIndustryIndex = probe == null ? -1 : probe.upgradingIndustryIndex;
      upgradingIndustryId = probe == null ? "" : probe.upgradingIndustryId;
      upgradingIndustryClass = probe == null ? "" : probe.upgradingIndustryClass;
      upgradingRawBuildingField = probe == null ? "" : probe.upgradingRawBuildingField;
      upgradingBuildProgress = probe == null ? "" : probe.upgradingBuildProgress;
      upgradingBuildTime = probe == null ? "" : probe.upgradingBuildTime;
      upgradingUpgradeId = probe == null ? "" : probe.upgradingUpgradeId;
      upgradingIsFunctional = probe == null ? "" : probe.upgradingIsFunctional;
      exceptionClass = probe == null ? "" : probe.exceptionClass;
      exceptionMessage = probe == null ? "" : probe.exceptionMessage;
    }

    void observe() {
      lastSeenMillis.set(System.currentTimeMillis());
      pendingOccurrences.increment();
    }

    void append(StringBuilder output, String timestamp, long occurrences) {
      output.append(DirectMarketObserver.csv(timestamp)).append(',')
          .append(DirectMarketObserver.csv(Instant.ofEpochMilli(firstSeenMillis).toString())).append(',')
          .append(DirectMarketObserver.csv(Instant.ofEpochMilli(lastSeenMillis.get()).toString())).append(',')
          .append(occurrences).append(',').append(marketIdentityHash).append(',')
          .append(DirectMarketObserver.csv(marketId)).append(',').append(DirectMarketObserver.csv(marketName))
          .append(',').append(DirectMarketObserver.csv(trigger)).append(',')
          .append(DirectMarketObserver.csv(sampleBucket)).append(',').append(DirectMarketObserver.csv(transition))
          .append(',').append(newReasonMask).append(',')
          .append(DirectMarketObserver.csv(constructionReasonText(newReasonMask))).append(',')
          .append(DirectMarketObserver.csv(queueClass)).append(',').append(queueSize).append(',').append(industryCount)
          .append(',').append(industryIndex).append(',').append(DirectMarketObserver.csv(industryId)).append(',')
          .append(DirectMarketObserver.csv(industryClass)).append(',').append(building).append(',')
          .append(effectiveBuilding).append(',').append(upgrading).append(',').append(reportedBuildingWithoutRaw)
          .append(',').append(buildingIndustryIndex).append(',').append(DirectMarketObserver.csv(buildingIndustryId))
          .append(',').append(DirectMarketObserver.csv(buildingIndustryClass)).append(',')
          .append(DirectMarketObserver.csv(buildingRawBuildingField)).append(',')
          .append(DirectMarketObserver.csv(buildingBuildProgress)).append(',')
          .append(DirectMarketObserver.csv(buildingBuildTime)).append(',')
          .append(DirectMarketObserver.csv(buildingUpgradeId)).append(',')
          .append(DirectMarketObserver.csv(buildingIsFunctional)).append(',')
          .append(reportedBuildingWithoutRawIndustryIndex).append(',')
          .append(DirectMarketObserver.csv(reportedBuildingWithoutRawIndustryId)).append(',')
          .append(DirectMarketObserver.csv(reportedBuildingWithoutRawIndustryClass)).append(',')
          .append(DirectMarketObserver.csv(reportedBuildingWithoutRawRawBuildingField)).append(',')
          .append(DirectMarketObserver.csv(reportedBuildingWithoutRawBuildProgress)).append(',')
          .append(DirectMarketObserver.csv(reportedBuildingWithoutRawBuildTime)).append(',')
          .append(DirectMarketObserver.csv(reportedBuildingWithoutRawUpgradeId)).append(',')
          .append(DirectMarketObserver.csv(reportedBuildingWithoutRawIsFunctional)).append(',')
          .append(upgradingIndustryIndex).append(',').append(DirectMarketObserver.csv(upgradingIndustryId)).append(',')
          .append(DirectMarketObserver.csv(upgradingIndustryClass)).append(',')
          .append(DirectMarketObserver.csv(upgradingRawBuildingField)).append(',')
          .append(DirectMarketObserver.csv(upgradingBuildProgress)).append(',')
          .append(DirectMarketObserver.csv(upgradingBuildTime)).append(',')
          .append(DirectMarketObserver.csv(upgradingUpgradeId)).append(',')
          .append(DirectMarketObserver.csv(upgradingIsFunctional)).append(',').append(mutationEpoch).append(',')
          .append(auditedEpoch).append(',').append(lastAuditBatch).append(',').append(oldActive).append(',')
          .append(newActive).append(',').append(oldReasonMask).append(',').append(newReasonMask).append(',')
          .append(DirectMarketObserver.csv(exceptionClass)).append(',')
          .append(DirectMarketObserver.csv(exceptionMessage)).append('\n');
    }
  }
  private static String constructionSampleBucket(int oldReasonMask, int newReasonMask) {
    if (oldReasonMask != 0 && oldReasonMask != newReasonMask) {
      // Keep the number of quota categories fixed. The exact masks remain
      // available in the transition/old_reason_mask/new_reason_mask columns.
      if (oldReasonMask == CONSTRUCTION_REASON_INDUSTRY_UPGRADING
          && newReasonMask == (CONSTRUCTION_REASON_INDUSTRY_BUILDING | CONSTRUCTION_REASON_INDUSTRY_UPGRADING)) {
        return "TRANSITION_4_TO_6";
      }
      if (oldReasonMask == CONSTRUCTION_REASON_INDUSTRY_UPGRADING
          && newReasonMask == (CONSTRUCTION_REASON_INDUSTRY_UPGRADING
              | CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW)) {
        return "TRANSITION_4_TO_132";
      }
      return "TRANSITION_OTHER";
    }
    return constructionReasonBucket(newReasonMask);
  }
  private static String constructionReasonBucket(int reasonMask) {
    int semantic = reasonMask & CONSTRUCTION_REASON_OBSERVED_MASK;
    if (semantic == CONSTRUCTION_REASON_INDUSTRY_UPGRADING) { return "INDUSTRY_UPGRADING_WITHOUT_BUILDING"; }
    if (semantic == CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW
        || semantic == (CONSTRUCTION_REASON_INDUSTRY_UPGRADING | CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW)) {
      return "REPORTED_BUILDING_WITHOUT_RAW";
    }
    if (Integer.bitCount(semantic) > 1) return "MULTIPLE";
    if ((semantic & CONSTRUCTION_REASON_QUEUE_NON_EMPTY) != 0) return "QUEUE_NON_EMPTY";
    if ((semantic & CONSTRUCTION_REASON_INDUSTRY_BUILDING) != 0) return "INDUSTRY_BUILDING";
    if ((semantic & CONSTRUCTION_REASON_INDUSTRY_UPGRADING) != 0) return "INDUSTRY_UPGRADING";
    if ((semantic & CONSTRUCTION_REASON_QUEUE_ITEMS_UNKNOWN) != 0) return "QUEUE_ITEMS_UNKNOWN";
    if ((semantic & CONSTRUCTION_REASON_INDUSTRIES_UNKNOWN) != 0) return "INDUSTRIES_UNKNOWN";
    if ((semantic & CONSTRUCTION_REASON_PROBE_FAILURE) != 0) return "PROBE_FAILURE";
    if ((semantic & CONSTRUCTION_REASON_MUTATION_RACE) != 0) return "MUTATION_RACE";
    if ((semantic & CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW) != 0) { return "REPORTED_BUILDING_WITHOUT_RAW"; }
    return "INACTIVE";
  }
  private static String constructionReasonText(int reasonMask) {
    if (reasonMask == 0) return "NONE";
    StringBuilder result = new StringBuilder();
    appendReason(result, reasonMask, CONSTRUCTION_REASON_QUEUE_NON_EMPTY, "QUEUE_NON_EMPTY");
    appendReason(result, reasonMask, CONSTRUCTION_REASON_INDUSTRY_BUILDING, "INDUSTRY_BUILDING");
    appendReason(result, reasonMask, CONSTRUCTION_REASON_INDUSTRY_UPGRADING, "INDUSTRY_UPGRADING");
    appendReason(result, reasonMask, CONSTRUCTION_REASON_QUEUE_ITEMS_UNKNOWN, "QUEUE_ITEMS_UNKNOWN");
    appendReason(result, reasonMask, CONSTRUCTION_REASON_INDUSTRIES_UNKNOWN, "INDUSTRIES_UNKNOWN");
    appendReason(result, reasonMask, CONSTRUCTION_REASON_PROBE_FAILURE, "PROBE_FAILURE");
    appendReason(result, reasonMask, CONSTRUCTION_REASON_MUTATION_RACE, "MUTATION_RACE");
    appendReason(result, reasonMask, CONSTRUCTION_REASON_REPORTED_BUILDING_WITHOUT_RAW,
        "REPORTED_BUILDING_WITHOUT_RAW");
    return result.toString();
  }
  private static void appendReason(StringBuilder output, int mask, int bit, String label) {
    if ((mask & bit) == 0) return;
    if (output.length() > 0) output.append('|');
    output.append(label);
  }
  static final class DirectMarketCallToken {
    final DirectMarketSiteStats stats;
    final boolean timed;
    final long startNanos;

    DirectMarketCallToken(DirectMarketSiteStats stats, boolean timed, long startNanos) {
      this.stats = stats;
      this.timed = timed;
      this.startNanos = startNanos;
    }
  }
  /**
   * Low-overhead aggregate observer for direct Market.advance call sites.
   * All maps retain only strings/numbers and never MarketAPI instances.
   */
  static final class DirectMarketObserver {
    private static final DateTimeFormatter SESSION_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    private static final String SITE_HEADER =
        "site_id,mod_id,mod_name,mod_directory,jar_name,source,class,method,descriptor,line,ordinal,amount_source,call_owner,opcode\n";
    private static final String OBSERVATION_HEADER =
        "timestamp_utc,interval_seconds,site_id,mod_id,mod_name,mod_directory,jar_name,source,class,method,descriptor,line,ordinal,"
            + "amount_source,call_owner,opcode,interval_calls,cumulative_calls,calls_per_second,"
            + "sampled_calls,sampled_inclusive_ns,estimated_inclusive_ns,avg_sample_ns,"
            + "exceptions,recursive_calls,finite_amounts,amount_sum,amount_avg,"
            + "zero_amount,negative_amount,nan_amount,null_market,approx_unique_markets_sampled\n";
    private static final String STACK_HEADER = "timestamp_utc,site_id,samples,stack\n";
    private static final String SUMMARY_HEADER =
        "timestamp_utc,interval_seconds,scheduled_entries,scheduler_fallback_entries,"
            + "save_flush_entries,planet_condition_scheduled_entries,"
            + "planet_condition_fallback_entries,direct_callsite_entries,unknown_entries,"
            + "direct_wrapper_calls,site_count,site_overflow_calls,metadata_collisions\n";
    private static final String UNKNOWN_HEADER = "timestamp_utc,samples,stack\n";

    private final int timingSampleEvery;
    private final int stackSampleEvery;
    private final int maxStacksPerSite;
    private final int reportIntervalSeconds;
    private final int maxSites;
    private final int unknownStackSampleLimit;
    private final Path sessionDir;
    private final Path sitesFile;
    private final Path observationsFile;
    private final Path stacksFile;
    private final Path summaryFile;
    private final Path unknownFile;
    private final ConcurrentHashMap<Long, DirectMarketSiteStats> sites = new ConcurrentHashMap<>();
    private final DirectMarketSiteStats overflowSite = DirectMarketSiteStats.overflow();
    private final AtomicReference<UnknownStackInterval> unknownStackInterval =
        new AtomicReference<>(new UnknownStackInterval());
    private final LongAdder scheduledEntries = new LongAdder();
    private final LongAdder schedulerFallbackEntries = new LongAdder();
    private final LongAdder saveFlushEntries = new LongAdder();
    private final LongAdder planetConditionScheduledEntries = new LongAdder();
    private final LongAdder planetConditionFallbackEntries = new LongAdder();
    private final LongAdder directCallsiteEntries = new LongAdder();
    private final LongAdder unknownEntries = new LongAdder();
    private final LongAdder directWrapperCalls = new LongAdder();
    private final LongAdder siteOverflowCalls = new LongAdder();
    private final LongAdder metadataCollisions = new LongAdder();
    private final Object metadataWriteLock = new Object();
    private volatile long lastReportNanos = System.nanoTime();
    private final String sessionOrigin;

    DirectMarketObserver(PrepatcherConfig configuration, Path root) throws IOException {
      timingSampleEvery = configuration.directMarketTimingSampleEvery;
      stackSampleEvery = configuration.directMarketStackSampleEvery;
      maxStacksPerSite = configuration.directMarketMaxStacksPerSite;
      reportIntervalSeconds = configuration.directMarketReportIntervalSeconds;
      maxSites = configuration.directMarketMaxSites;
      unknownStackSampleLimit = configuration.directMarketUnknownStackSamples;
      sessionOrigin = sanitizeSessionOrigin(System.getProperty("starsector.prepatcher.sessionOrigin", "game"));

      String originSegment = "game".equals(sessionOrigin) ? "" : sessionOrigin + "-";
      String sessionName =
          "session-" + originSegment + SESSION_FORMAT.format(Instant.now()) + "-pid" + ProcessHandle.current().pid();
      Path base = root.resolve("logs").resolve("direct-market-observe");
      sessionDir = base.resolve(sessionName);
      Files.createDirectories(sessionDir);
      sitesFile = sessionDir.resolve("call-sites.csv");
      observationsFile = sessionDir.resolve("observations.csv");
      stacksFile = sessionDir.resolve("stacks.csv");
      summaryFile = sessionDir.resolve("summary.csv");
      unknownFile = sessionDir.resolve("unknown-stacks.csv");

      Files.writeString(sitesFile, SITE_HEADER, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
      Files.writeString(observationsFile, OBSERVATION_HEADER, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
      Files.writeString(stacksFile, STACK_HEADER, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
      Files.writeString(summaryFile, SUMMARY_HEADER, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
      Files.writeString(unknownFile, UNKNOWN_HEADER, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
      Files.writeString(sessionDir.resolve("session.json"), sessionJson(), StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW);
      System.setProperty("starsector.prepatcher.directMarketObservationDir", sessionDir.toAbsolutePath().toString());
      PrepatcherLog.info("Direct Market.advance observation session: " + sessionDir.toAbsolutePath());
    }

    void start() {
      Thread reporter = new Thread(this::reportLoop, "StarsectorPrepatcher-DirectMarketObserver");
      reporter.setDaemon(true);
      reporter.setPriority(Thread.MIN_PRIORITY);
      reporter.start();
    }

    void registerSite(long siteId, String metadata) {
      DirectMarketSiteStats stats = site(null, siteId, metadata);
      if (stats == overflowSite) return;
      writeMetadataIfNeeded(stats);
    }

    DirectMarketCallToken beginDirectCall(boolean nullMarket, int marketIdentity, float amount, long siteId,
        String metadata, boolean recursive) {
      DirectMarketSiteStats stats = site(SITE_CACHE.get(), siteId, metadata);
      long callNumber = stats.totalCalls.incrementAndGet();
      stats.intervalCalls.increment();
      directWrapperCalls.increment();
      if (Float.isFinite(amount)) {
        stats.finiteAmounts.increment();
        stats.amountSum.add(amount);
      } else if (Float.isNaN(amount)) {
        stats.nanAmounts.increment();
      }
      if (amount < 0f) stats.negativeAmounts.increment();
      else if (amount == 0f) stats.zeroAmounts.increment();
      if (nullMarket) stats.nullMarkets.increment();
      if (recursive) stats.recursiveCalls.increment();
      if (!nullMarket && (callNumber == 1L || (callNumber & 7L) == 0L)) {
        stats.observeMarketIdentity(marketIdentity);
      }

      boolean stackSample =
          maxStacksPerSite > 0 && (callNumber == 1L || (stackSampleEvery > 0 && callNumber % stackSampleEvery == 0L));
      if (stackSample) stats.recordStack(captureDirectStack(), maxStacksPerSite);

      boolean timed = timingSampleEvery > 0 && Math.floorMod(callNumber + stats.samplePhase, timingSampleEvery) == 0L;
      return new DirectMarketCallToken(stats, timed, timed ? System.nanoTime() : 0L);
    }

    void endDirectCall(DirectMarketCallToken token, boolean success) {
      if (token == null || token.stats == null) return;
      if (!success) token.stats.exceptions.increment();
      if (token.timed) {
        long elapsed = System.nanoTime() - token.startNanos;
        if (elapsed < 0L) elapsed = 0L;
        token.stats.sampledCalls.increment();
        token.stats.sampledInclusiveNanos.add(elapsed);
      }
    }

    void observeMarketEntry(int origin) {
      switch (origin) {
        case MARKET_ORIGIN_SCHEDULED -> scheduledEntries.increment();
        case MARKET_ORIGIN_SCHEDULER_FALLBACK -> schedulerFallbackEntries.increment();
        case MARKET_ORIGIN_SAVE_FLUSH -> saveFlushEntries.increment();
        case MARKET_ORIGIN_PLANET_CONDITION_SCHEDULED -> planetConditionScheduledEntries.increment();
        case MARKET_ORIGIN_PLANET_CONDITION_FALLBACK -> planetConditionFallbackEntries.increment();
        case MARKET_ORIGIN_DIRECT_CALL_SITE -> directCallsiteEntries.increment();
        default -> {
          unknownEntries.increment();
          UnknownStackInterval interval;
          while (true) {
            interval = unknownStackInterval.get();
            if (interval.tryAcquireWriter()) break;
          }
          try {
            int sample = interval.samples.getAndIncrement();
            if (sample < unknownStackSampleLimit) {
              String stack = captureDirectStack();
              interval.stacks.computeIfAbsent(stack, ignored -> new LongAdder()).increment();
            }
          } finally {
            interval.releaseWriter();
          }
        }
      }
    }

    private DirectMarketSiteStats site(DirectMarketSiteCache context, long siteId, String metadata) {
      int slot = (int) (siteId ^ (siteId >>> 32)) & 7;
      if (context != null) {
        DirectMarketSiteStats cached = context.observerStats[slot];
        String cachedMetadata = context.observerMetadata[slot];
        if (cached != null && context.observerSiteIds[slot] == siteId
            && (cachedMetadata == metadata || Objects.equals(cachedMetadata, metadata))) {
          return cached;
        }
      }

      DirectMarketSiteStats existing = sites.get(siteId);
      if (existing == null) {
        if (sites.size() >= maxSites) {
          siteOverflowCalls.increment();
          return overflowSite;
        }
        DirectMarketSiteStats candidate = new DirectMarketSiteStats(DirectMarketMetadata.parse(siteId, metadata));
        DirectMarketSiteStats raced = sites.putIfAbsent(siteId, candidate);
        existing = raced == null ? candidate : raced;
      }
      if (!existing.metadata.raw.equals(metadata)) metadataCollisions.increment();
      if (context != null) {
        context.observerSiteIds[slot] = siteId;
        context.observerMetadata[slot] = metadata;
        context.observerStats[slot] = existing;
      }
      return existing;
    }

    private void writeMetadataIfNeeded(DirectMarketSiteStats stats) {
      if (stats == null || stats == overflowSite || stats.metadataWritten) return;
      synchronized (metadataWriteLock) {
        if (stats.metadataWritten) return;
        StringBuilder row = new StringBuilder();
        appendSiteRow(row, stats.metadata);
        try {
          append(sitesFile, row);
          stats.metadataWritten = true;
        } catch (IOException failure) {
          PrepatcherLog.warn("Direct market call-site manifest write failed for " + unsignedHex(stats.metadata.siteId)
              + ": " + failure.getMessage());
        }
      }
    }

    private void reportLoop() {
      while (true) {
        try {
          Thread.sleep(reportIntervalSeconds * 1000L);
          report();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          try {
            report();
          } catch (Throwable ignored) {}
          return;
        } catch (Throwable failure) {
          PrepatcherLog.error("Direct Market.advance observation report failed", failure);
        }
      }
    }

    private void report() throws IOException {
      long nowNanos = System.nanoTime();
      double elapsedSeconds = Math.max(0.001, (nowNanos - lastReportNanos) / 1_000_000_000.0);
      lastReportNanos = nowNanos;
      String timestamp = Instant.now().toString();

      ArrayList<DirectMarketIntervalSnapshot> snapshots = new ArrayList<>();
      for (DirectMarketSiteStats stats : sites.values()) {
        DirectMarketIntervalSnapshot snapshot = stats.snapshot();
        if (snapshot.calls > 0L || !stats.metadataWritten) snapshots.add(snapshot);
      }
      DirectMarketIntervalSnapshot overflow = overflowSite.snapshot();
      if (overflow.calls > 0L) snapshots.add(overflow);
      snapshots.sort((left, right) -> Long.compare(right.estimatedInclusiveNanos(), left.estimatedInclusiveNanos()));

      StringBuilder observationRows = new StringBuilder();
      StringBuilder stackRows = new StringBuilder();
      for (DirectMarketIntervalSnapshot snapshot : snapshots) {
        DirectMarketSiteStats stats = snapshot.stats;
        writeMetadataIfNeeded(stats);
        if (snapshot.calls > 0L) {
          appendObservationRow(observationRows, timestamp, elapsedSeconds, snapshot);
        }
        stats.appendStackRows(stackRows, timestamp);
      }
      if (observationRows.length() > 0) append(observationsFile, observationRows);
      if (stackRows.length() > 0) append(stacksFile, stackRows);

      // Atomically detach the interval so stack signatures do not accumulate
      // for the process lifetime and calls racing the report go to one of the
      // two complete intervals rather than sharing a reset counter.
      UnknownStackInterval unknownInterval = unknownStackInterval.getAndSet(new UnknownStackInterval());
      unknownInterval.sealAndAwaitWriters();
      StringBuilder unknownRows = new StringBuilder();
      for (Map.Entry<String, LongAdder> entry : unknownInterval.stacks.entrySet()) {
        long samples = entry.getValue().sum();
        if (samples <= 0L) continue;
        unknownRows.append(csv(timestamp)).append(',').append(samples).append(',').append(csv(entry.getKey()))
            .append('\n');
      }
      if (unknownRows.length() > 0) append(unknownFile, unknownRows);

      long scheduled = scheduledEntries.sumThenReset();
      long fallback = schedulerFallbackEntries.sumThenReset();
      long save = saveFlushEntries.sumThenReset();
      long planetScheduled = planetConditionScheduledEntries.sumThenReset();
      long planetFallback = planetConditionFallbackEntries.sumThenReset();
      long direct = directCallsiteEntries.sumThenReset();
      long unknown = unknownEntries.sumThenReset();
      long wrappers = directWrapperCalls.sumThenReset();
      long overflowCalls = siteOverflowCalls.sumThenReset();
      long collisions = metadataCollisions.sumThenReset();
      String summary = csv(timestamp) + ',' + decimal(elapsedSeconds) + ',' + scheduled + ',' + fallback + ',' + save
          + ',' + planetScheduled + ',' + planetFallback + ',' + direct + ',' + unknown + ',' + wrappers + ','
          + sites.size() + ',' + overflowCalls + ',' + collisions + '\n';
      append(summaryFile, new StringBuilder(summary));

      if (wrappers > 0L || unknown > 0L || planetScheduled > 0L || planetFallback > 0L || overflowCalls > 0L
          || collisions > 0L) {
        PrepatcherLog.info("direct-market-observe: calls=" + wrappers + ", sites=" + sites.size()
            + ", scheduledEntries=" + scheduled + ", planetScheduledEntries=" + planetScheduled
            + ", planetFallbackEntries=" + planetFallback + ", directEntries=" + direct + ", unknownEntries=" + unknown
            + ", overflowCalls=" + overflowCalls + ", output=" + sessionDir.toAbsolutePath());
      }
    }

    private String sessionJson() {
      String version = System.getProperty("starsector.prepatcher.version", "unknown");
      return "{\n" + "  \"schema\": 3,\n" + "  \"mode\": \"observe\",\n" + "  \"createdUtc\": "
          + json(Instant.now().toString()) + ",\n" + "  \"prepatcherVersion\": " + json(version) + ",\n"
          + "  \"sessionOrigin\": " + json(sessionOrigin) + ",\n" + "  \"timingSampleEvery\": " + timingSampleEvery
          + ",\n" + "  \"stackSampleEvery\": " + stackSampleEvery + ",\n" + "  \"maxStacksPerSite\": "
          + maxStacksPerSite + ",\n" + "  \"reportIntervalSeconds\": " + reportIntervalSeconds + ",\n"
          + "  \"maxSites\": " + maxSites + ",\n" + "  \"unknownStackSamplesPerInterval\": " + unknownStackSampleLimit
          + ",\n"
          + "  \"behavior\": \"Engine-owned market calls share one scheduler. Direct mod calls remain synchronous and consume any pending scheduler debt before their own amount.\"\n"
          + "}\n";
    }

    private static String sanitizeSessionOrigin(String raw) {
      if (raw == null || raw.isBlank()) return "game";
      StringBuilder sanitized = new StringBuilder(Math.min(raw.length(), 48));
      for (int index = 0; index < raw.length() && sanitized.length() < 48; index++) {
        char value = raw.charAt(index);
        if ((value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z') || (value >= '0' && value <= '9')
            || value == '.' || value == '_' || value == '-') {
          sanitized.append(value);
        } else {
          sanitized.append('_');
        }
      }
      return sanitized.length() == 0 ? "game" : sanitized.toString();
    }

    private static void appendSiteRow(StringBuilder output, DirectMarketMetadata metadata) {
      output.append(csv(unsignedHex(metadata.siteId))).append(',').append(csv(metadata.modId)).append(',')
          .append(csv(metadata.modName)).append(',').append(csv(metadata.modDirectory)).append(',')
          .append(csv(metadata.jarName)).append(',').append(csv(metadata.source)).append(',')
          .append(csv(metadata.className)).append(',').append(csv(metadata.method)).append(',')
          .append(csv(metadata.descriptor)).append(',').append(metadata.line).append(',').append(metadata.ordinal)
          .append(',').append(csv(metadata.amountSource)).append(',').append(csv(metadata.callOwner)).append(',')
          .append(metadata.opcode).append('\n');
    }

    private static void appendObservationRow(StringBuilder output, String timestamp, double elapsedSeconds,
        DirectMarketIntervalSnapshot snapshot) {
      DirectMarketMetadata metadata = snapshot.stats.metadata;
      long estimated = snapshot.estimatedInclusiveNanos();
      double average = snapshot.sampledCalls == 0L ? 0.0 : (double) snapshot.sampledNanos / snapshot.sampledCalls;
      output.append(csv(timestamp)).append(',').append(decimal(elapsedSeconds)).append(',')
          .append(csv(unsignedHex(metadata.siteId))).append(',').append(csv(metadata.modId)).append(',')
          .append(csv(metadata.modName)).append(',').append(csv(metadata.modDirectory)).append(',')
          .append(csv(metadata.jarName)).append(',').append(csv(metadata.source)).append(',')
          .append(csv(metadata.className)).append(',').append(csv(metadata.method)).append(',')
          .append(csv(metadata.descriptor)).append(',').append(metadata.line).append(',').append(metadata.ordinal)
          .append(',').append(csv(metadata.amountSource)).append(',').append(csv(metadata.callOwner)).append(',')
          .append(metadata.opcode).append(',').append(snapshot.calls).append(',').append(snapshot.cumulativeCalls)
          .append(',').append(decimal(snapshot.calls / elapsedSeconds)).append(',').append(snapshot.sampledCalls)
          .append(',').append(snapshot.sampledNanos).append(',').append(estimated).append(',').append(decimal(average))
          .append(',').append(snapshot.exceptions).append(',').append(snapshot.recursiveCalls).append(',')
          .append(snapshot.finiteAmounts).append(',').append(decimal(snapshot.amountSum)).append(',')
          .append(decimal(snapshot.finiteAmounts == 0L ? 0.0 : snapshot.amountSum / snapshot.finiteAmounts)).append(',')
          .append(snapshot.zeroAmounts).append(',').append(snapshot.negativeAmounts).append(',')
          .append(snapshot.nanAmounts).append(',').append(snapshot.nullMarkets).append(',')
          .append(snapshot.approxUniqueMarkets).append('\n');
    }

    private static void append(Path file, StringBuilder content) throws IOException {
      Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String captureDirectStack() {
      try {
        return StackWalker.getInstance()
            .walk(stream -> stream.filter(frame -> !isObserverFrame(frame.getClassName())).limit(16)
                .map(frame -> frame.getClassName() + "#" + frame.getMethodName() + ':' + frame.getLineNumber())
                .collect(Collectors.joining(" <- ")));
      } catch (Throwable failure) {
        return "<stack unavailable: " + failure.getClass().getName() + ">";
      }
    }

    private static boolean isObserverFrame(String className) {
      return className.equals(StarsectorPrepatcherHooks.class.getName())
          || className.startsWith(StarsectorPrepatcherHooks.class.getName() + '$')
          || className.equals(StarsectorPrepatcherMarketScheduler.class.getName())
          || className.startsWith(StarsectorPrepatcherMarketScheduler.class.getName() + '$')
          || className.equals(StarsectorPrepatcherMarketDiagnostics.class.getName())
          || className.startsWith(StarsectorPrepatcherMarketDiagnostics.class.getName() + '$')
          || className.equals("com.fs.starfarer.campaign.econ.Market") || className.startsWith("java.lang.reflect.")
          || className.startsWith("jdk.internal.reflect.");
    }

    private static String csv(String value) {
      if (value == null) return "";
      boolean quote = false;
      for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        if (c == ',' || c == '"' || c == '\r' || c == '\n') {
          quote = true;
          break;
        }
      }
      if (!quote) return value;
      return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String json(String value) {
      if (value == null) return "null";
      StringBuilder out = new StringBuilder(value.length() + 16).append('"');
      for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        switch (c) {
          case '"' -> out.append("\\\"");
          case '\\' -> out.append("\\\\");
          case '\n' -> out.append("\\n");
          case '\r' -> out.append("\\r");
          case '\t' -> out.append("\\t");
          default -> {
            if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
          }
        }
      }
      return out.append('"').toString();
    }

    private static String decimal(double value) {
      if (!Double.isFinite(value)) return "0";
      return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static String unsignedHex(long value) {
      return "0x" + Long.toUnsignedString(value, 16);
    }
  }
  static final class UnknownStackInterval {
    final AtomicInteger samples = new AtomicInteger();
    final AtomicInteger activeWriters = new AtomicInteger();
    final ConcurrentHashMap<String, LongAdder> stacks = new ConcurrentHashMap<>();
    volatile boolean sealed;

    boolean tryAcquireWriter() {
      activeWriters.incrementAndGet();
      if (!sealed) return true;
      activeWriters.decrementAndGet();
      return false;
    }

    void releaseWriter() {
      activeWriters.decrementAndGet();
    }

    void sealAndAwaitWriters() {
      sealed = true;
      int spins = 0;
      while (activeWriters.get() != 0) {
        if (spins++ < 256) Thread.onSpinWait();
        else Thread.yield();
      }
    }
  }
  static final class DirectMarketSiteStats {
    final DirectMarketMetadata metadata;
    final long samplePhase;
    final AtomicLong totalCalls = new AtomicLong();
    final LongAdder intervalCalls = new LongAdder();
    final LongAdder finiteAmounts = new LongAdder();
    final DoubleAdder amountSum = new DoubleAdder();
    final LongAdder sampledCalls = new LongAdder();
    final LongAdder sampledInclusiveNanos = new LongAdder();
    final LongAdder exceptions = new LongAdder();
    final LongAdder recursiveCalls = new LongAdder();
    final LongAdder zeroAmounts = new LongAdder();
    final LongAdder negativeAmounts = new LongAdder();
    final LongAdder nanAmounts = new LongAdder();
    final LongAdder nullMarkets = new LongAdder();
    final AtomicLong[] uniqueWords = {new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()};
    final ConcurrentHashMap<String, LongAdder> stackSamples = new ConcurrentHashMap<>();
    final LongAdder overflowStackSamples = new LongAdder();
    volatile boolean metadataWritten;

    DirectMarketSiteStats(DirectMarketMetadata metadata) {
      this.metadata = metadata;
      this.samplePhase = metadata.siteId ^ (metadata.siteId >>> 32);
    }

    static DirectMarketSiteStats overflow() {
      DirectMarketSiteStats result = new DirectMarketSiteStats(
          new DirectMarketMetadata(0L, "<site-overflow>", "<site-overflow>", "<site-overflow>", "", "<site-overflow>",
              "<site-overflow>", "<site-overflow>", "", -1, -1, "UNKNOWN", "", -1, "<site-overflow>"));
      result.metadataWritten = true;
      return result;
    }

    void observeMarketIdentity(int identityHash) {
      int mixed = identityHash;
      mixed ^= mixed >>> 16;
      mixed *= 0x7feb352d;
      mixed ^= mixed >>> 15;
      int bucket = mixed & 255;
      AtomicLong word = uniqueWords[bucket >>> 6];
      long bit = 1L << (bucket & 63);
      long current;
      do {
        current = word.get();
        if ((current & bit) != 0L) return;
      } while (!word.compareAndSet(current, current | bit));
    }

    void recordStack(String stack, int maxStacks) {
      LongAdder existing = stackSamples.get(stack);
      if (existing != null) {
        existing.increment();
        return;
      }
      if (stackSamples.size() >= maxStacks) {
        overflowStackSamples.increment();
        return;
      }
      LongAdder candidate = new LongAdder();
      LongAdder raced = stackSamples.putIfAbsent(stack, candidate);
      (raced == null ? candidate : raced).increment();
    }

    DirectMarketIntervalSnapshot snapshot() {
      long calls = intervalCalls.sumThenReset();
      long sampled = sampledCalls.sumThenReset();
      long sampledNanos = sampledInclusiveNanos.sumThenReset();
      long errors = exceptions.sumThenReset();
      long recursive = recursiveCalls.sumThenReset();
      long finite = finiteAmounts.sumThenReset();
      double amountTotal = amountSum.sumThenReset();
      if (finite == 0L) amountTotal = 0.0;
      long zero = zeroAmounts.sumThenReset();
      long negative = negativeAmounts.sumThenReset();
      long nan = nanAmounts.sumThenReset();
      long nulls = nullMarkets.sumThenReset();
      return new DirectMarketIntervalSnapshot(this, calls, totalCalls.get(), sampled, sampledNanos, errors, recursive,
          finite, amountTotal, zero, negative, nan, nulls, approximateUniqueMarkets());
    }

    int approximateUniqueMarkets() {
      int bits = 0;
      for (AtomicLong word : uniqueWords) bits += Long.bitCount(word.get());
      if (bits <= 0) return 0;
      if (bits >= 256) return 256;
      return (int) Math.round(-256.0 * Math.log((256.0 - bits) / 256.0));
    }

    void appendStackRows(StringBuilder output, String timestamp) {
      for (Map.Entry<String, LongAdder> entry : stackSamples.entrySet()) {
        long samples = entry.getValue().sumThenReset();
        if (samples <= 0L) continue;
        output.append(DirectMarketObserver.csv(timestamp)).append(',')
            .append(DirectMarketObserver.csv(DirectMarketObserver.unsignedHex(metadata.siteId))).append(',')
            .append(samples).append(',').append(DirectMarketObserver.csv(entry.getKey())).append('\n');
      }
      long overflow = overflowStackSamples.sumThenReset();
      if (overflow > 0L) {
        output.append(DirectMarketObserver.csv(timestamp)).append(',')
            .append(DirectMarketObserver.csv(DirectMarketObserver.unsignedHex(metadata.siteId))).append(',')
            .append(overflow).append(',').append(DirectMarketObserver.csv("<additional stack signatures>"))
            .append('\n');
      }
    }
  }
  static final class DirectMarketMetadata {
    final long siteId;
    final String modId;
    final String modName;
    final String modDirectory;
    final String jarName;
    final String source;
    final String className;
    final String method;
    final String descriptor;
    final int line;
    final int ordinal;
    final String amountSource;
    final String callOwner;
    final int opcode;
    final String raw;

    DirectMarketMetadata(long siteId, String modId, String modName, String modDirectory, String jarName, String source,
        String className, String method, String descriptor, int line, int ordinal, String amountSource,
        String callOwner, int opcode, String raw) {
      this.siteId = siteId;
      this.modId = modId;
      this.modName = modName;
      this.modDirectory = modDirectory;
      this.jarName = jarName;
      this.source = source;
      this.className = className;
      this.method = method;
      this.descriptor = descriptor;
      this.line = line;
      this.ordinal = ordinal;
      this.amountSource = amountSource;
      this.callOwner = callOwner;
      this.opcode = opcode;
      this.raw = raw;
    }

    static DirectMarketMetadata parse(long siteId, String metadata) {
      String raw = metadata == null ? "" : metadata;
      String[] fields = raw.split("\u001f", -1);
      if (fields.length == 14 && "2".equals(fields[0])) {
        return new DirectMarketMetadata(siteId, fields[2], fields[3], fields[4], fields[5], fields[1], fields[6],
            fields[7], fields[8], parseInt(fields[9], -1), parseInt(fields[10], -1), fields[11], fields[12],
            parseInt(fields[13], -1), raw);
      }
      if (fields.length == 10 && "1".equals(fields[0])) {
        String modId = modIdFromSource(fields[1]);
        return new DirectMarketMetadata(siteId, modId, modId, modId, jarNameFromSource(fields[1]), fields[1], fields[2],
            fields[3], fields[4], parseInt(fields[5], -1), parseInt(fields[6], -1), fields[7], fields[8],
            parseInt(fields[9], -1), raw);
      }
      return new DirectMarketMetadata(siteId, "<malformed>", "<malformed>", "<malformed>", "", "<malformed>",
          "<malformed>", "<malformed>", "", -1, -1, "UNKNOWN", "", -1, raw);
    }

    private static String modIdFromSource(String source) {
      String normalized = source == null ? "" : source.replace('\\', '/');
      int marker = normalized.indexOf("/mods/");
      int start = marker >= 0 ? marker + 6 : (normalized.startsWith("mods/") ? 5 : -1);
      if (start < 0) return "unknown";
      int end = normalized.indexOf('/', start);
      return end < 0 ? normalized.substring(start) : normalized.substring(start, end);
    }

    private static String jarNameFromSource(String source) {
      String normalized = source == null ? "" : source.replace('\\', '/');
      int slash = normalized.lastIndexOf('/');
      String tail = slash < 0 ? normalized : normalized.substring(slash + 1);
      return tail.toLowerCase(java.util.Locale.ROOT).endsWith(".jar") ? tail : "";
    }

    private static int parseInt(String value, int fallback) {
      try {
        return Integer.parseInt(value);
      } catch (RuntimeException ignored) {
        return fallback;
      }
    }
  }
  static final class DirectMarketIntervalSnapshot {
    final DirectMarketSiteStats stats;
    final long calls;
    final long cumulativeCalls;
    final long sampledCalls;
    final long sampledNanos;
    final long exceptions;
    final long recursiveCalls;
    final long finiteAmounts;
    final double amountSum;
    final long zeroAmounts;
    final long negativeAmounts;
    final long nanAmounts;
    final long nullMarkets;
    final int approxUniqueMarkets;

    DirectMarketIntervalSnapshot(DirectMarketSiteStats stats, long calls, long cumulativeCalls, long sampledCalls,
        long sampledNanos, long exceptions, long recursiveCalls, long finiteAmounts, double amountSum, long zeroAmounts,
        long negativeAmounts, long nanAmounts, long nullMarkets, int approxUniqueMarkets) {
      this.stats = stats;
      this.calls = calls;
      this.cumulativeCalls = cumulativeCalls;
      this.sampledCalls = sampledCalls;
      this.sampledNanos = sampledNanos;
      this.exceptions = exceptions;
      this.recursiveCalls = recursiveCalls;
      this.finiteAmounts = finiteAmounts;
      this.amountSum = amountSum;
      this.zeroAmounts = zeroAmounts;
      this.negativeAmounts = negativeAmounts;
      this.nanAmounts = nanAmounts;
      this.nullMarkets = nullMarkets;
      this.approxUniqueMarkets = approxUniqueMarkets;
    }

    long estimatedInclusiveNanos() {
      if (sampledCalls <= 0L || calls <= 0L || sampledNanos <= 0L) return 0L;
      double estimate = ((double) sampledNanos * calls) / sampledCalls;
      if (estimate >= Long.MAX_VALUE) return Long.MAX_VALUE;
      return Math.max(0L, Math.round(estimate));
    }
  }
}
