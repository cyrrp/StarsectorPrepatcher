package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.StarsectorPrepatcherHooks;
import com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge;
import com.starsector.prepatcher.agent.PrepatcherConfig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.Random;
import java.util.regex.Pattern;

/** Differential tests for startup text parsing and save/load helper semantics. */
public final class LoadingSaveRuntimeRegressionTest {
  private LoadingSaveRuntimeRegressionTest() {}

  public static void main(String[] args) throws Exception {
    Path temp = Files.createTempDirectory("smo-loading-save-test");
    try {
      configure(temp, 15);
      testUtf8Reader();
      testUtf8ReaderClosesOnFailure();
      testRuleLiteralHelpers();
      testProgressFrequency(temp);
      System.out.println("OK loading-save-runtime utf8-streaming close-on-failure"
          + " literal-parser-differential progress-frequency");
    } finally {
      deleteRecursively(temp);
    }
  }

  private static void testUtf8Reader() throws Exception {
    StringBuilder boundary = new StringBuilder(70_000);
    while (boundary.length() < 65_535) boundary.append('a');
    boundary.append('\u20ac'); // multi-byte UTF-8 sequence straddles common chunk boundaries
    boundary.append("\r\nПривет 世界 😀\rEND");

    String[] samples =
        {"", "plain ascii", "a\rb\r\nc\n", boundary.toString(), "\r\r\r", "Latin Ελληνικά Кириллица 日本語 한글 😀"};
    for (String sample : samples) {
      byte[] bytes = sample.getBytes(StandardCharsets.UTF_8);
      TrackingInputStream input = new TrackingInputStream(bytes);
      String actual = StarsectorPrepatcherHooks.readUtf8Normalized(input);
      String expected = new String(bytes, StandardCharsets.UTF_8).replace("\r", "");
      require(expected.equals(actual), "UTF-8 reader mismatch for sample length " + sample.length());
      require(input.closed, "UTF-8 reader did not close input");
    }

    byte[][] malformed = {{(byte) 0xC3}, {(byte) 0xE2, (byte) 0x82},
        {(byte) 0xF0, (byte) 0x28, (byte) 0x8C, (byte) 0x28}, {'a', '\r', (byte) 0xC0, (byte) 0xAF, 'b'}};
    for (byte[] bytes : malformed) {
      TrackingInputStream input = new TrackingInputStream(bytes);
      String actual = StarsectorPrepatcherHooks.readUtf8Normalized(input);
      String expected = new String(bytes, StandardCharsets.UTF_8).replace("\r", "");
      require(expected.equals(actual), "malformed UTF-8 replacement behavior changed");
      require(input.closed, "malformed UTF-8 input was not closed");
    }
  }

  private static void testUtf8ReaderClosesOnFailure() {
    FailingInputStream input = new FailingInputStream(1024, 333);
    boolean failed = false;
    try {
      StarsectorPrepatcherHooks.readUtf8Normalized(input);
    } catch (IOException expected) {
      failed = true;
    }
    require(failed, "read failure was swallowed");
    require(input.closed, "failed input was not closed");
  }

  private static void testRuleLiteralHelpers() {
    String[] edges = {"", "\n", "\n\n", ":", "::", "a:", ":a", "a::b", "\nOR\n", "a\nOR\nb", "a\nOR\n", "\nOR\na",
        "a\r\nb", "a\n\n", "OR"};
    for (String value : edges) compareLiteralHelpers(value);

    Random random = new Random(0x5A17C0DEL);
    char[] alphabet = {'a', 'b', 'c', ' ', '\t', '\r', '\n', ':', 'O', 'R', '#', '$'};
    for (int iteration = 0; iteration < 100_000; iteration++) {
      int length = random.nextInt(96);
      StringBuilder value = new StringBuilder(length);
      for (int i = 0; i < length; i++) value.append(alphabet[random.nextInt(alphabet.length)]);
      compareLiteralHelpers(value.toString());
    }
  }

  private static void compareLiteralHelpers(String value) {
    require(value.replaceAll("\\r", "").equals(StarsectorPrepatcherHooks.removeCarriageReturns(value)),
        "CR removal changed for " + printable(value));
    require(value.replaceAll("\\n", " ").equals(StarsectorPrepatcherHooks.replaceNewlinesWithSpace(value)),
        "newline replacement changed for " + printable(value));
    require(Arrays.equals(value.split("\\n"), StarsectorPrepatcherHooks.splitNewlines(value)),
        "newline split changed for " + printable(value));
    require(Arrays.equals(value.split("\nOR\n"), StarsectorPrepatcherHooks.splitOrBlocks(value)),
        "OR split changed for " + printable(value));
    require(Arrays.equals(value.split(Pattern.quote(":")), StarsectorPrepatcherHooks.splitColon(value)),
        "colon split changed for " + printable(value));
  }

  private static void testProgressFrequency(Path temp) throws Exception {
    configure(temp, 15);
    require(Float.floatToIntBits(StarsectorPrepatcherHooks.saveLoadProgressMinIntervalSeconds()) == Float
        .floatToIntBits(1f / 15f), "15 Hz progress interval mismatch");
    configure(temp, 0);
    require(Float.floatToIntBits(StarsectorPrepatcherHooks.saveLoadProgressMinIntervalSeconds()) == Float
        .floatToIntBits(1f / 60f), "vanilla progress fallback mismatch");
  }

  private static void configure(Path temp, int hz) throws Exception {
    Path file = temp.resolve("optimizer-" + hz + ".properties");
    Properties properties = new Properties();
    properties.setProperty("logging.statsIntervalSeconds", "0");
    properties.setProperty("saveLoad.progressHz", Integer.toString(hz));
    try (var output = Files.newOutputStream(file)) {
      properties.store(output, "test");
    }
    StarsectorPrepatcherRuntimeBridge.configure(PrepatcherConfig.load(file), temp);
  }

  private static String printable(String value) {
    return value.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (root == null || !Files.exists(root)) return;
    try (var paths = Files.walk(root)) {
      paths.sorted((left, right) -> right.getNameCount() - left.getNameCount()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException ex) {
          throw new RuntimeException(ex);
        }
      });
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static final class TrackingInputStream extends ByteArrayInputStream {
    boolean closed;

    TrackingInputStream(byte[] data) {
      super(data);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }

  private static final class FailingInputStream extends InputStream {
    final int total;
    final int failAfter;
    int position;
    boolean closed;

    FailingInputStream(int total, int failAfter) {
      this.total = total;
      this.failAfter = failAfter;
    }

    @Override
    public int read() throws IOException {
      if (position >= failAfter) throw new IOException("synthetic failure");
      if (position >= total) return -1;
      return position++ & 0x7f;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      if (position >= failAfter) throw new IOException("synthetic failure");
      if (position >= total) return -1;
      int count = Math.min(length, Math.min(total - position, failAfter - position));
      for (int i = 0; i < count; i++) buffer[offset + i] = (byte) ((position + i) & 0x7f);
      position += count;
      return count;
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
