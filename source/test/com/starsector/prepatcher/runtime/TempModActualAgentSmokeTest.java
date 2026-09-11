package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.combat.MutableStatWithTempMods;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Executes the real transformed game class in a child JVM started with the packaged javaagent. */
public final class TempModActualAgentSmokeTest {
  private static final Field TEMP_MODS;
  private static final Field DEFERRED;
  private static final Field COUNTDOWN;
  private static final Field SCHEDULED_MIN;
  private static final Field MIN_COUNT;
  private static final Field KNOWN_SIZE;
  private static final Field FLAGS;
  private static Field remainingField;

  static {
    try {
      TEMP_MODS = field("tempMods");
      DEFERRED = field("spp$tempModHybridDeferredDays");
      COUNTDOWN = field("spp$tempModHybridTimeToNext");
      SCHEDULED_MIN = field("spp$tempModHybridScheduledMin");
      MIN_COUNT = field("spp$tempModHybridScheduledMinCount");
      KNOWN_SIZE = field("spp$tempModHybridKnownSize");
      FLAGS = field("spp$tempModHybridFlags");
      try {
        MutableStatWithTempMods.class.getDeclaredField("spp$tempModExpiryState");
        throw new AssertionError("legacy external scheduler state field remains");
      } catch (NoSuchFieldException expected) {
        // Correct hybrid shape.
      }
    } catch (ReflectiveOperationException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  private TempModActualAgentSmokeTest() {}

  public static void main(String[] args) throws Exception {
    assertFastDeadlineAndOnePassMaterialization();
    assertTiedMinimumExpiry();
    assertSynchronizationBeforeUpdateAndRemove();
    assertExternalExposureUsesDirectVanillaPath();
    assertSaveSynchronization();
    assertSubclassUsesDirectVanillaPath();
    assertDirectedFloatDeadlines();
    assertRandomizedMembershipDifferential();
    System.out.println("OK actual-javaagent MutableStatWithTempMods direct hybrid scheduler");
  }

  private static void assertFastDeadlineAndOnePassMaterialization() throws Exception {
    MutableStatWithTempMods stat = new MutableStatWithTempMods(0f);
    stat.addTemporaryModFlat(1f, "a", "a", 2f);
    stat.addTemporaryModFlat(3f, "b", "b", 4f);
    Object a = mods(stat).get("a");
    Object b = mods(stat).get("b");
    stat.advance(0.25f);
    stat.advance(0.25f);
    stat.advance(0.25f);
    require(bits(remaining(a)) == bits(1f), "fast path materialized nearest modifier");
    require(bits(remaining(b)) == bits(3f), "fast path materialized survivor");
    require(bits(DEFERRED.getFloat(stat)) == bits(0.75f), "deferred float countdown changed");
    require(bits(COUNTDOWN.getFloat(stat)) == bits(0.25f), "nearest countdown changed");

    stat.advance(0.25f);
    require(!mods(stat).containsKey("a"), "nearest modifier did not expire");
    require(mods(stat).containsKey("b"), "survivor expired early");
    require(!stat.getFlatMods().containsKey("a"), "expired modifier was not unmodified");
    require(stat.getFlatMods().containsKey("b"), "survivor modifier disappeared");
    require(bits(remaining(b)) == bits(2f), "expiry sweep was not one-pass materialization");
    require(bits(DEFERRED.getFloat(stat)) == bits(0f), "expiry sweep retained deferred time");
    require(bits(COUNTDOWN.getFloat(stat)) == bits(2f), "next countdown was not rebuilt");
    require(bits(SCHEDULED_MIN.getFloat(stat)) == bits(2f), "next minimum was not rebuilt");
    require(MIN_COUNT.getInt(stat) == 1 && KNOWN_SIZE.getInt(stat) == 1, "post-expiry schedule metadata changed");
    require((FLAGS.getInt(stat) & 1) != 0, "hybrid schedule did not remain valid");
  }

  private static void assertTiedMinimumExpiry() throws Exception {
    MutableStatWithTempMods stat = new MutableStatWithTempMods(0f);
    stat.addTemporaryModFlat(1f, "a", "a", 1f);
    stat.addTemporaryModFlat(1f, "b", "b", 1f);
    stat.addTemporaryModFlat(2f, "c", "c", 1f);
    require(MIN_COUNT.getInt(stat) == 2, "tied minima were not counted");
    stat.advance(0.4f);
    stat.advance(0.3f);
    stat.advance(0.3f);
    require(!mods(stat).containsKey("a") && !mods(stat).containsKey("b"), "tied minima did not expire together");
    require(mods(stat).containsKey("c"), "longer modifier expired with tied minima");
    require(close(remaining(mods(stat).get("c")), 1f), "survivor was not materialized once");
  }

  private static void assertSynchronizationBeforeUpdateAndRemove() throws Exception {
    MutableStatWithTempMods stat = new MutableStatWithTempMods(0f);
    stat.addTemporaryModFlat(5f, "a", "a", 1f);
    stat.addTemporaryModFlat(7f, "b", "b", 1f);
    Object a = mods(stat).get("a");
    Object b = mods(stat).get("b");
    stat.advance(1.25f);
    stat.advance(0.75f);
    require(bits(remaining(a)) == bits(5f), "pre-update fast path materialized state");
    stat.addTemporaryModFlat(2f, "a", "a2", 3f);
    require(close(remaining(a), 2f), "add/update did not synchronize and reset duration");
    require(close(remaining(b), 5f), "add/update did not materialize other modifiers");
    stat.advance(0.5f);
    stat.removeTemporaryMod("b");
    require(!mods(stat).containsKey("b"), "remove did not remove synchronized modifier");
    stat.advance(1.5f);
    require(!mods(stat).containsKey("a"), "updated modifier did not expire");
  }

  private static void assertExternalExposureUsesDirectVanillaPath() throws Exception {
    MutableStatWithTempMods stat = new MutableStatWithTempMods(0f);
    stat.addTemporaryModFlat(4f, "a", "a", 1f);
    Object a = mods(stat).get("a");
    stat.advance(1f);
    require(bits(remaining(a)) == bits(4f), "pre-exposure state unexpectedly materialized");
    require(stat.hasMod("a"), "hasMod lost a live deferred modifier");
    require(bits(remaining(a)) == bits(4f), "hasMod forced an unnecessary materialization");
    Map<?, ?> live = stat.getMods();
    require(close(remaining(a), 3f), "getMods did not synchronize");
    require((FLAGS.getInt(stat) & 2) != 0, "getMods did not mark external exposure");
    stat.advance(0.5f);
    require(close(remaining(a), 2.5f), "exposed stat did not execute exact vanilla advance");
    require(live == mods(stat), "getMods stopped returning its vanilla live map");
  }

  private static void assertSaveSynchronization() throws Exception {
    MutableStatWithTempMods stat = new MutableStatWithTempMods(0f);
    stat.addTemporaryModFlat(5f, "a", "a", 1f);
    Object a = mods(stat).get("a");
    stat.advance(1.5f);
    require(bits(remaining(a)) == bits(5f), "pre-save state unexpectedly materialized");
    Method write = MutableStatWithTempMods.class.getDeclaredMethod("writeReplace");
    write.setAccessible(true);
    require(write.invoke(stat) == stat, "writeReplace changed owner");
    require(close(remaining(a), 3.5f), "writeReplace did not synchronize timeRemaining");
    require(bits(DEFERRED.getFloat(stat)) == bits(0f), "save left deferred time");
  }

  private static void assertSubclassUsesDirectVanillaPath() throws Exception {
    DerivedStat stat = new DerivedStat(0f);
    stat.addTemporaryModFlat(1f, "a", "a", 1f);
    Object a = mods(stat).get("a");
    stat.advance(0.25f);
    stat.advance(0.25f);
    require(close(remaining(a), 0.5f), "subclass did not retain vanilla per-frame semantics");
    require((FLAGS.getInt(stat) & 4) != 0, "subclass did not enter permanent vanilla mode");
  }

  private static void assertDirectedFloatDeadlines() {
    require(expiryFrame(1.69113123f, 0.000101418875f, 20000) == 16672,
        "directed float fixture 1 expired on the wrong frame");
    require(expiryFrame(2.91829920f, 0.145914942f, 100) == 21, "directed float fixture 2 expired on the wrong frame");
    float duration = Float.intBitsToFloat(0x3e408c21);
    float days = Float.intBitsToFloat(0x364111a9);
    require(expiryFrame(duration, days, 70000) == 65378, "directed float fixture 3 expired on the wrong frame");
  }

  private static int expiryFrame(float duration, float days, int limit) {
    MutableStatWithTempMods stat = new MutableStatWithTempMods(0f);
    stat.addTemporaryModFlat(duration, "x", "x", 1f);
    for (int frame = 1; frame <= limit; frame++) {
      stat.advance(days);
      if (!stat.hasMod("x")) return frame;
    }
    throw new AssertionError("modifier did not expire within fixture limit");
  }

  private static void assertRandomizedMembershipDifferential() throws Exception {
    Random random = new Random(0x5EEDC0DEL);
    MutableStatWithTempMods actual = new MutableStatWithTempMods(0f);
    LinkedHashMap<String, Float> expected = new LinkedHashMap<>();
    for (int step = 0; step < 12_000; step++) {
      int op = random.nextInt(10);
      String key = "m" + random.nextInt(16);
      if (op < 3) {
        float duration = (1 + random.nextInt(32)) * 0.25f;
        actual.addTemporaryModFlat(duration, key, key, 1f);
        expected.put(key, duration);
      } else if (op == 3) {
        actual.removeTemporaryMod(key);
        expected.remove(key);
      } else {
        float days = (1 + random.nextInt(4)) * 0.25f;
        actual.advance(days);
        Iterator<Map.Entry<String, Float>> iterator = expected.entrySet().iterator();
        while (iterator.hasNext()) {
          Map.Entry<String, Float> entry = iterator.next();
          float remaining = entry.getValue() - days;
          if (remaining <= 0f) iterator.remove();
          else entry.setValue(remaining);
        }
      }
      List<String> actualKeys = new ArrayList<>(mods(actual).keySet());
      List<String> expectedKeys = new ArrayList<>(expected.keySet());
      require(actualKeys.equals(expectedKeys), "randomized membership/order mismatch at step " + step + ": actual="
          + actualKeys + " expected=" + expectedKeys);
    }
  }

  private static Field field(String name) throws ReflectiveOperationException {
    Field field = MutableStatWithTempMods.class.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mods(MutableStatWithTempMods stat) throws IllegalAccessException {
    Map<String, Object> result = (Map<String, Object>) TEMP_MODS.get(stat);
    return result == null ? Map.of() : result;
  }

  private static float remaining(Object mod) throws ReflectiveOperationException {
    Field field = remainingField;
    if (field == null) {
      field = mod.getClass().getDeclaredField("timeRemaining");
      field.setAccessible(true);
      remainingField = field;
    }
    return field.getFloat(mod);
  }

  private static int bits(float value) {
    return Float.floatToIntBits(value);
  }

  private static boolean close(float actual, float expected) {
    return Math.abs(actual - expected) <= 0.000002f;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static final class DerivedStat extends MutableStatWithTempMods {
    DerivedStat(float base) {
      super(base);
    }
  }
}
