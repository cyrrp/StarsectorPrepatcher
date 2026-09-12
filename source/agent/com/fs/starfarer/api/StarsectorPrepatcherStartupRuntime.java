package com.fs.starfarer.api;

import com.starsector.prepatcher.agent.PrepatcherConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.LongAdder;

/** Startup logging suppression, save/load progress helpers, and text readers. */
final class StarsectorPrepatcherStartupRuntime {

  static final ThreadLocal<TextReadScratch> TEXT_READ_SCRATCH = ThreadLocal.withInitial(TextReadScratch::new);
  // ---------------------------------------------------------------------
  // Startup and save/load helpers
  // ---------------------------------------------------------------------

  /**
   * Streaming replacement for LoadingUtils' 1 MiB-per-resource byte buffer.
   * UTF-8 decoder state is preserved across chunk boundaries and carriage
   * returns are removed while copying, matching the observable vanilla result.
   */
  public static String readUtf8Normalized(InputStream input) throws IOException {
    if (input == null) throw new NullPointerException("input");
    TextReadScratch scratch = TEXT_READ_SCRATCH.get();
    char[] buffer = scratch.acquire();
    StringBuilder output = new StringBuilder(8192);
    long chars = 0L;
    long removed = 0L;
    try {
      Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
      while (true) {
        int count = reader.read(buffer, 0, buffer.length);
        if (count < 0) break;
        if (count == 0) continue;
        chars += count;
        int runStart = 0;
        for (int i = 0; i < count; i++) {
          if (buffer[i] != '\r') continue;
          if (i > runStart) output.append(buffer, runStart, i - runStart);
          runStart = i + 1;
          removed++;
        }
        if (runStart < count) output.append(buffer, runStart, count - runStart);
      }
      StarsectorPrepatcherMetrics.TEXT_READ_CALLS.increment();
      StarsectorPrepatcherMetrics.TEXT_READ_CHARS.add(chars);
      StarsectorPrepatcherMetrics.TEXT_READ_CR_REMOVED.add(removed);
      return output.toString();
    } finally {
      scratch.release();
      input.close();
    }
  }
  /** Called when the transformer removed an entire high-volume INFO block. */
  public static void startupLogSuppressed(int category) {
    startupLogCounter(category).increment();
  }
  /**
   * Drop-in replacement for Logger.info(Object) at broad SpecStore sites.
   * The already-computed message is deliberately ignored; WARN/ERROR calls are
   * untouched and the optimization can be disabled independently.
   */
  public static void suppressStartupInfo(Object logger, Object message, int category) {
    startupLogCounter(category).increment();
  }
  static LongAdder startupLogCounter(int category) {
    return switch (category) {
      case 0 -> StarsectorPrepatcherMetrics.STARTUP_LOG_JSON;
      case 1 -> StarsectorPrepatcherMetrics.STARTUP_LOG_CSV;
      case 2 -> StarsectorPrepatcherMetrics.STARTUP_LOG_SCRIPT;
      case 3 -> StarsectorPrepatcherMetrics.STARTUP_LOG_RULE;
      case 4 -> StarsectorPrepatcherMetrics.STARTUP_LOG_SPEC;
      case 5 -> StarsectorPrepatcherMetrics.STARTUP_LOG_TEXTURE;
      case 6 -> StarsectorPrepatcherMetrics.STARTUP_LOG_SOUND;
      default -> StarsectorPrepatcherMetrics.STARTUP_LOG_OTHER;
    };
  }
  public static String removeCarriageReturns(String value) {
    return removeCharacter(value, '\r');
  }
  public static String replaceNewlinesWithSpace(String value) {
    return replaceCharacter(value, '\n', ' ');
  }
  public static String[] splitNewlines(String value) {
    return splitLiteralDiscardTrailing(value, "\n");
  }
  public static String[] splitOrBlocks(String value) {
    return splitLiteralDiscardTrailing(value, "\nOR\n");
  }
  public static String[] splitColon(String value) {
    return splitLiteralDiscardTrailing(value, ":");
  }
  /** 0 means keep vanilla's 60 Hz progress-render ceiling. */
  public static float saveLoadProgressMinIntervalSeconds() {
    PrepatcherConfig c = StarsectorPrepatcherHooks.config;
    int hz = c == null ? 0 : c.saveLoadProgressHz;
    if (hz <= 0) return 1f / 60f;
    return 1f / hz;
  }
  static String removeCharacter(String value, char unwanted) {
    if (value == null) throw new NullPointerException("value");
    int first = value.indexOf(unwanted);
    if (first < 0) return value;
    int length = value.length();
    char[] result = new char[length - 1];
    value.getChars(0, first, result, 0);
    int out = first;
    for (int i = first + 1; i < length; i++) {
      char c = value.charAt(i);
      if (c != unwanted) result[out++] = c;
    }
    return new String(result, 0, out);
  }
  static String replaceCharacter(String value, char target, char replacement) {
    if (value == null) throw new NullPointerException("value");
    int first = value.indexOf(target);
    if (first < 0) return value;
    char[] result = value.toCharArray();
    result[first] = replacement;
    for (int i = first + 1; i < result.length; i++) {
      if (result[i] == target) result[i] = replacement;
    }
    return new String(result);
  }
  /** Exact positive-width literal equivalent of String.split(regex) with limit=0. */
  static String[] splitLiteralDiscardTrailing(String value, String delimiter) {
    if (value == null) throw new NullPointerException("value");
    int first = value.indexOf(delimiter);
    if (first < 0) return new String[]{value};

    // First pass computes the exact result size after String.split(..., 0)
    // discards trailing empty elements. This avoids an ArrayList and its
    // backing array in the rules loader's high-volume parsing path.
    int delimiterLength = delimiter.length();
    int start = 0;
    int index = first;
    int segment = 0;
    int retained = 0;
    while (index >= 0) {
      segment++;
      if (index > start) retained = segment;
      start = index + delimiterLength;
      index = value.indexOf(delimiter, start);
    }
    segment++;
    if (start < value.length()) retained = segment;
    if (retained == 0) return new String[0];

    String[] result = new String[retained];
    start = 0;
    index = first;
    for (int i = 0; i < retained; i++) {
      if (index < 0) {
        result[i] = value.substring(start);
      } else {
        result[i] = value.substring(start, index);
        start = index + delimiterLength;
        index = value.indexOf(delimiter, start);
      }
    }
    return result;
  }
  static final class TextReadScratch {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int RING_SIZE = 4;
    final char[][] buffers = new char[RING_SIZE][];
    int depth;

    char[] acquire() {
      int slot = depth++;
      if (slot >= RING_SIZE) return new char[BUFFER_SIZE];
      char[] buffer = buffers[slot];
      if (buffer == null) buffers[slot] = buffer = new char[BUFFER_SIZE];
      return buffer;
    }

    void release() {
      if (depth > 0) depth--;
    }
  }
}
