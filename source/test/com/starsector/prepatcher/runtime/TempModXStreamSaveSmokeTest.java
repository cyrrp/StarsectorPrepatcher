package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.combat.MutableStatWithTempMods;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

import java.lang.reflect.Field;
import java.util.Map;

/** Actual-agent/XStream check that transient hybrid state never enters campaign saves. */
public final class TempModXStreamSaveSmokeTest {
  private TempModXStreamSaveSmokeTest() {}

  public static void main(String[] args) throws Exception {
    MutableStatWithTempMods stat = new MutableStatWithTempMods(100f);
    stat.addTemporaryModFlat(10f, "flat", "flat", 5f);
    stat.addTemporaryModPercent(20f, "percent", "percent", 10f);
    stat.advance(2f);

    XStream xstream = new XStream(new DomDriver());
    XStream.setupDefaultSecurity(xstream);
    xstream.allowTypesByWildcard(new String[]{"com.fs.starfarer.api.combat.**", "java.util.**"});

    String xml = xstream.toXML(stat);
    require(
        !xml.contains("spp$tempModHybrid") && !xml.contains("spp$commodityTemporalOwner")
            && !xml.contains("spp$commodityTemporalRole"),
        "transient hybrid/commodity binding state leaked into XML/save");
    require(xml.contains("timeRemaining>8.0<") && xml.contains("timeRemaining>18.0<"),
        "writeReplace did not materialize current durations before save");

    MutableStatWithTempMods loaded = (MutableStatWithTempMods) xstream.fromXML(xml);
    require(
        findField(loaded.getClass(), "spp$commodityTemporalOwner").get(loaded) == null
            && findField(loaded.getClass(), "spp$commodityTemporalRole").getInt(loaded) == 0,
        "deserialized stat retained commodity owner/role binding");
    loaded.advance(8f);
    require(!loaded.hasMod("flat"), "deserialized flat modifier did not expire at preserved deadline");
    require(loaded.hasMod("percent"), "deserialized longer modifier expired early");

    Object percent = rawMods(loaded).get("percent");
    require(percent != null && Float.floatToIntBits(remaining(percent)) == Float.floatToIntBits(10f),
        "deserialized survivor duration was not preserved");

    System.out.println("OK actual-javaagent XStream save round-trip xmlBytes="
        + xml.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> rawMods(MutableStatWithTempMods stat) throws ReflectiveOperationException {
    Field field = MutableStatWithTempMods.class.getDeclaredField("tempMods");
    field.setAccessible(true);
    return (Map<String, Object>) field.get(stat);
  }

  private static float remaining(Object mod) throws ReflectiveOperationException {
    Field field = mod.getClass().getDeclaredField("timeRemaining");
    field.setAccessible(true);
    return field.getFloat(mod);
  }


  private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      try {
        Field field = current.getDeclaredField(name);
        field.setAccessible(true);
        return field;
      } catch (NoSuchFieldException ignored) {}
    }
    throw new NoSuchFieldException(type.getName() + "." + name);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
