package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.MutableStatWithTempMods;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/** Actual-fork/XStream 1.4.10 migration and round-trip gate for AoTD derived state. */
public final class AoTDSupplyDemandXStreamMigrationTest {
    private static final String DATA_CLASS =
            "data.kaysaar.aotd.tot.scripts.commoditydata.AoTDSupplyDemandData";

    /**
     * Exact spp9 field layout with a shared MutableStat in both derived maps. The relative XStream
     * reference is intentional: migration must accept old aliasing before readResolve drops the
     * detached industry graph.
     */
    private static final String SPP9_XML =
            """
            <data.kaysaar.aotd.tot.scripts.commoditydata.AoTDSupplyDemandData>
              <demandUnitsFromIndustries>
                <entry>
                  <string>legacy_industry</string>
                  <com.fs.starfarer.api.combat.MutableStat>
                    <base>3.0</base>
                    <modified>3.0</modified>
                    <flatMods>
                      <entry>
                        <string>legacy-industry</string>
                        <com.fs.starfarer.api.combat.MutableStat_-StatMod>
                          <source>legacy-industry</source>
                          <desc>legacy</desc>
                          <value>4.0</value>
                        </com.fs.starfarer.api.combat.MutableStat_-StatMod>
                      </entry>
                    </flatMods>
                  </com.fs.starfarer.api.combat.MutableStat>
                </entry>
              </demandUnitsFromIndustries>
              <supplyUnitsFromIndustries>
                <entry>
                  <string>legacy_industry</string>
                  <com.fs.starfarer.api.combat.MutableStat reference="../../../demandUnitsFromIndustries/entry/com.fs.starfarer.api.combat.MutableStat"/>
                </entry>
              </supplyUnitsFromIndustries>
              <commodityID>fixture_commodity</commodityID>
              <supply>17</supply>
              <demand>9</demand>
              <available>6</available>
              <additionalProduction>
                <base>0.0</base>
                <modified>0.0</modified>
                <flatMods>
                  <entry>
                    <string>legacy-temp</string>
                    <com.fs.starfarer.api.combat.MutableStat_-StatMod>
                      <source>legacy-temp</source>
                      <desc>legacy temp</desc>
                      <value>5.0</value>
                    </com.fs.starfarer.api.combat.MutableStat_-StatMod>
                  </entry>
                </flatMods>
                <tempMods>
                  <entry>
                    <string>legacy-temp</string>
                    <com.fs.starfarer.api.combat.MutableStatWithTempMods_-TemporaryStatMod>
                      <timeRemaining>8.0</timeRemaining>
                      <source>legacy-temp</source>
                    </com.fs.starfarer.api.combat.MutableStatWithTempMods_-TemporaryStatMod>
                  </entry>
                </tempMods>
              </additionalProduction>
              <additionalDemand>
                <base>0.0</base>
                <modified>0.0</modified>
              </additionalDemand>
              <additionalImport>
                <base>0.0</base>
                <modified>0.0</modified>
              </additionalImport>
              <additionalExport>
                <base>0.0</base>
                <modified>0.0</modified>
              </additionalExport>
            </data.kaysaar.aotd.tot.scripts.commoditydata.AoTDSupplyDemandData>
            """;

    private AoTDSupplyDemandXStreamMigrationTest() {}

    public static void main(String[] args) throws Exception {
        String xstreamVersion = XStream.class.getPackage().getImplementationVersion();
        require("1.4.10".equals(xstreamVersion),
                "migration gate requires XStream 1.4.10, found " + xstreamVersion);

        validateLegacyFixtureAlias();

        Class<?> dataType = Class.forName(DATA_CLASS);
        XStream current = xstream(dataType.getClassLoader());
        Object migrated = current.fromXML(SPP9_XML);
        require(dataType.isInstance(migrated), "spp9 XML did not load as the actual fork class");
        require("fixture_commodity".equals(field(dataType, "commodityID").get(migrated)),
                "commodity id changed during spp9 migration");
        require(intField(dataType, migrated, "supply") == 17
                        && intField(dataType, migrated, "demand") == 9
                        && intField(dataType, migrated, "available") == 6,
                "aggregate supply/demand/available values changed during spp9 migration");
        require(derivedMap(dataType, migrated, "demandUnitsFromIndustries").isEmpty()
                        && derivedMap(dataType, migrated, "supplyUnitsFromIndustries").isEmpty(),
                "spp9 detached per-industry cache contents survived readResolve");
        assertTemporaryModifier(
                (MutableStatWithTempMods) field(dataType, "additionalProduction").get(migrated),
                "legacy-temp",
                8f,
                "spp9 migration");

        MutableStat sharedDerived = new MutableStat(1f);
        sharedDerived.modifyFlat("fresh-cache-mod", 7f, "must not be serialized");
        derivedMap(dataType, migrated, "demandUnitsFromIndustries")
                .put("fresh_industry", sharedDerived);
        derivedMap(dataType, migrated, "supplyUnitsFromIndustries")
                .put("fresh_industry", sharedDerived);

        MutableStatWithTempMods sharedPersistent = new MutableStatWithTempMods(2f);
        sharedPersistent.modifyFlat("shared-persistent", 3f, "shared persistent state");
        field(dataType, "additionalDemand").set(migrated, sharedPersistent);
        field(dataType, "additionalImport").set(migrated, sharedPersistent);

        String newXml = current.toXML(migrated);
        require(!newXml.contains("fresh_industry")
                        && !newXml.contains("fresh-cache-mod")
                        && !newXml.contains("must not be serialized"),
                "new save retained derived per-industry cache contents or references");
        require(newXml.contains("<supply>17</supply>")
                        && newXml.contains("<demand>9</demand>")
                        && newXml.contains("<available>6</available>"),
                "new save omitted persistent aggregates");
        require(newXml.contains("legacy-temp")
                        && newXml.contains("<timeRemaining>8.0</timeRemaining>"),
                "new save omitted the persistent gameplay modifier");

        Object roundTripped = current.fromXML(newXml);
        require(derivedMap(dataType, roundTripped, "demandUnitsFromIndustries").isEmpty()
                        && derivedMap(dataType, roundTripped, "supplyUnitsFromIndustries").isEmpty(),
                "new-save round trip recreated derived cache contents");
        require(intField(dataType, roundTripped, "supply") == 17
                        && intField(dataType, roundTripped, "demand") == 9
                        && intField(dataType, roundTripped, "available") == 6,
                "new-save round trip changed persistent aggregates");
        assertTemporaryModifier(
                (MutableStatWithTempMods)
                        field(dataType, "additionalProduction").get(roundTripped),
                "legacy-temp",
                8f,
                "spp13 round trip");
        require(field(dataType, "additionalDemand").get(roundTripped)
                        == field(dataType, "additionalImport").get(roundTripped),
                "writeReplace/readResolve broke a shared persistent-stat alias");

        System.out.println("OK actual-fork AoTDSupplyDemandData XStream " + xstreamVersion
                + " spp9-migration/spp13-round-trip xmlBytes="
                + newXml.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    private static void validateLegacyFixtureAlias() throws Exception {
        XStream legacy = xstream(AoTDSupplyDemandXStreamMigrationTest.class.getClassLoader());
        legacy.alias(DATA_CLASS, LegacySupplyDemandData.class);
        LegacySupplyDemandData value = (LegacySupplyDemandData) legacy.fromXML(SPP9_XML);
        MutableStat demand = value.demandUnitsFromIndustries.get("legacy_industry");
        MutableStat supply = value.supplyUnitsFromIndustries.get("legacy_industry");
        require(demand != null && demand == supply,
                "embedded spp9 fixture lost its shared derived MutableStat alias");
        require(value.supply == 17 && value.demand == 9 && value.available == 6,
                "embedded spp9 fixture aggregates changed");
        assertTemporaryModifier(
                value.additionalProduction, "legacy-temp", 8f, "embedded spp9 fixture");
    }

    private static XStream xstream(ClassLoader loader) {
        XStream xstream = new XStream(new DomDriver());
        XStream.setupDefaultSecurity(xstream);
        xstream.allowTypesByWildcard(new String[] {
                "com.fs.starfarer.api.combat.**",
                "com.starsector.prepatcher.runtime.**",
                "data.kaysaar.aotd.tot.scripts.commoditydata.**",
                "java.util.**"
        });
        xstream.setClassLoader(loader);
        return xstream;
    }

    @SuppressWarnings("unchecked")
    private static LinkedHashMap<String, MutableStat> derivedMap(
            Class<?> type, Object owner, String name) throws ReflectiveOperationException {
        return (LinkedHashMap<String, MutableStat>) field(type, name).get(owner);
    }

    private static int intField(Class<?> type, Object owner, String name)
            throws ReflectiveOperationException {
        return field(type, name).getInt(owner);
    }

    private static void assertTemporaryModifier(
            MutableStatWithTempMods stat, String id, float expectedRemaining, String label)
            throws ReflectiveOperationException {
        require(stat != null && stat.hasMod(id), label + " lost modifier " + id);
        Object temporary = rawTempMods(stat).get(id);
        require(temporary != null
                        && Float.floatToIntBits(remaining(temporary))
                                == Float.floatToIntBits(expectedRemaining),
                label + " changed remaining modifier duration");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rawTempMods(MutableStatWithTempMods stat)
            throws ReflectiveOperationException {
        Field field = field(MutableStatWithTempMods.class, "tempMods");
        return (Map<String, Object>) field.get(stat);
    }

    private static float remaining(Object mod) throws ReflectiveOperationException {
        return field(mod.getClass(), "timeRemaining").getFloat(mod);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Continue through transformed or inherited implementations.
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    /** Deserialization-only model of the fields written by spp9. */
    private static final class LegacySupplyDemandData {
        private LinkedHashMap<String, MutableStat> demandUnitsFromIndustries;
        private LinkedHashMap<String, MutableStat> supplyUnitsFromIndustries;
        private String commodityID;
        private int supply;
        private int demand;
        private int available;
        private MutableStatWithTempMods additionalProduction;
        private MutableStatWithTempMods additionalDemand;
        private MutableStatWithTempMods additionalImport;
        private MutableStatWithTempMods additionalExport;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
