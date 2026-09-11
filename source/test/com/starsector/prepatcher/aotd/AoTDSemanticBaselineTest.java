package com.starsector.prepatcher.aotd;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executable semantic baseline for AoTD 1.0.11's patched
 * BaseIndustry.getMaxDeficit(String...). This test intentionally models the
 * current behavior, including edge cases, rather than the proposed optimized
 * implementation.
 */
public final class AoTDSemanticBaselineTest {
  private AoTDSemanticBaselineTest() {}

  public static void main(String[] args) throws Exception {
    Path fixture = args.length == 0 ? Path.of("baseline", "aotd", "deficit-scenarios.csv") : Path.of(args[0]);
    List<Row> rows = readRows(fixture);
    LinkedHashMap<String, List<Row>> scenarios = new LinkedHashMap<>();
    for (Row row : rows) scenarios.computeIfAbsent(row.scenario, ignored -> new ArrayList<>()).add(row);

    int assertions = 0;
    for (Map.Entry<String, List<Row>> entry : scenarios.entrySet()) {
      List<Row> scenarioRows = entry.getValue();
      Row first = scenarioRows.get(0);
      Result actual = evaluate(first.codex, first.aotdEnabled, first.targetId, scenarioRows);
      Result optimized = evaluateOptimized(first.codex, first.aotdEnabled, first.targetId, scenarioRows);
      String expectedCommodity = emptyToNull(first.expectedSelectedCommodity);
      require(equals(expectedCommodity, actual.commodity),
          entry.getKey() + " selected commodity expected=" + expectedCommodity + " actual=" + actual.commodity);
      require(first.expectedDeficit == actual.deficit,
          entry.getKey() + " deficit expected=" + first.expectedDeficit + " actual=" + actual.deficit);
      require(equals(actual.commodity, optimized.commodity) && actual.deficit == optimized.deficit,
          entry.getKey() + " optimized mismatch baseline=" + actual + " optimized=" + optimized);
      assertions += 3;
    }

    System.out.println("OK AoTD semantic baseline scenarios=" + scenarios.size() + " assertions=" + assertions
        + " algorithm=baseline-and-cached-stable-order-equivalent");
  }

  private static Result evaluate(boolean codex, boolean aotdEnabled, String targetId, List<Row> commodities) {
    Result result = new Result(null, 0);
    if (codex) return result;
    for (Row row : commodities) {
      int demand = javaFloatToInt(row.targetDemand);
      int available = row.available;
      if (aotdEnabled) {
        ArrayList<Industry> ordered = new ArrayList<>(row.industries);
        // Match the AoTD patch exactly: pairwise swaps on strictly greater order.
        for (int i = 0; i < ordered.size(); i++) {
          for (int j = i + 1; j < ordered.size(); j++) {
            Industry left = ordered.get(i);
            Industry right = ordered.get(j);
            if (left.order > right.order) {
              ordered.set(i, right);
              ordered.set(j, left);
            }
          }
        }
        for (Industry industry : ordered) {
          if (industry.id.equals(targetId)) break;
          available -= Math.max(0, javaFloatToInt(industry.demand));
        }
      }
      int deficit = Math.max(demand - available, 0);
      if (deficit > demand) deficit = demand;
      if (deficit > result.deficit) result = new Result(row.commodity, deficit);
    }
    return result;
  }

  private static Result evaluateOptimized(boolean codex, boolean aotdEnabled, String targetId, List<Row> commodities) {
    Result result = new Result(null, 0);
    if (codex) return result;
    List<Industry> ordered = commodities.isEmpty() ? List.of() : new ArrayList<>(commodities.get(0).industries);
    ordered.sort(Comparator.comparingInt(Industry::order));
    for (Row row : commodities) {
      int demand = javaFloatToInt(row.targetDemand);
      int available = row.available;
      if (aotdEnabled) {
        for (Industry industry : ordered) {
          if (industry.id.equals(targetId)) break;
          available -= Math.max(0, javaFloatToInt(industry.demand));
        }
      }
      int deficit = Math.max(demand - available, 0);
      if (deficit > demand) deficit = demand;
      if (deficit > result.deficit) result = new Result(row.commodity, deficit);
    }
    return result;
  }

  private static int javaFloatToInt(float value) {
    return (int) value;
  }

  private static List<Row> readRows(Path path) throws Exception {
    ArrayList<Row> rows = new ArrayList<>();
    try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String header = reader.readLine();
      require(header != null && header.startsWith("scenario,"), "unexpected fixture header");
      String line;
      int lineNumber = 1;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        if (line.isBlank()) continue;
        String[] parts = line.split(",", -1);
        require(parts.length == 11,
            "fixture column count at line " + lineNumber + ": expected 11 actual=" + parts.length);
        rows.add(new Row(parts[0], Boolean.parseBoolean(parts[1]), Boolean.parseBoolean(parts[2]), parts[3], parts[4],
            Integer.parseInt(parts[5]), Float.parseFloat(parts[6]), parseIndustries(parts[7]), parts[8],
            Integer.parseInt(parts[9])));
      }
    }
    return rows;
  }

  private static List<Industry> parseIndustries(String encoded) {
    ArrayList<Industry> result = new ArrayList<>();
    if (encoded.isBlank()) return result;
    for (String item : encoded.split(";")) {
      String[] fields = item.split("\\|", -1);
      require(fields.length == 3, "invalid industry fixture: " + item);
      result.add(new Industry(fields[0], Integer.parseInt(fields[1]), Float.parseFloat(fields[2])));
    }
    return result;
  }

  private static String emptyToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static boolean equals(Object left, Object right) {
    return left == null ? right == null : left.equals(right);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private record Industry(String id, int order, float demand) {
  }
  private record Result(String commodity, int deficit) {
  }
  private record Row(String scenario, boolean codex, boolean aotdEnabled, String targetId, String commodity,
      int available, float targetDemand, List<Industry> industries, String expectedSelectedCommodity,
      int expectedDeficit) {
  }
}
