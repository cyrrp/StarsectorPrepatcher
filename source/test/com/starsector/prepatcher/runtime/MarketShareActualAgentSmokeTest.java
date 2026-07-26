package com.starsector.prepatcher.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Loads the market-share targets through the real javaagent and audits injected ownership. */
public final class MarketShareActualAgentSmokeTest {
    private static final String STATUS_PREFIX = "starsector.prepatcher.patchStatus.";

    private MarketShareActualAgentSmokeTest() {}

    public static void main(String[] args) throws Exception {
        boolean loadNex = false;
        boolean loadAoTD = false;
        for (String arg : args) {
            if ("nex".equals(arg) && !loadNex) {
                loadNex = true;
            } else if ("aotd".equals(arg) && !loadAoTD) {
                loadAoTD = true;
            } else {
                throw new AssertionError(
                        "Usage: MarketShareActualAgentSmokeTest [nex] [aotd]");
            }
        }

        ClassLoader loader = ClassLoader.getSystemClassLoader();
        Class<?> marketData = Class.forName(
                "com.fs.starfarer.campaign.econ.reach.CommodityMarketData", false, loader);
        requireApplied(marketData, "marketShareLinearAggregation");
        requireApplied(marketData, "marketShareDataPutElision");
        Method raw = marketData.getDeclaredMethod(
                "spp$commodityMarketDataRawMarketSharePerFaction");
        require(Modifier.isPrivate(raw.getModifiers()) && raw.isSynthetic(),
                "market-share raw fallback metadata changed");
        require(marketData.getDeclaredMethod("getMarketSharePercentPerFaction") != raw,
                "market-share wrapper was not installed");

        Class<?> vanillaPunitive = Class.forName(
                "com.fs.starfarer.api.impl.campaign.intel.punitive.PunitiveExpeditionManager",
                false, loader);
        requireApplied(vanillaPunitive, "punitivePlayerShareLocalCache");
        auditPunitive(vanillaPunitive);

        int targets = 2;
        if (loadNex) {
            Class<?> nexPunitive = Class.forName(
                    "exerelin.campaign.intel.Nex_PunitiveExpeditionManager", false, loader);
            requireApplied(nexPunitive, "punitivePlayerShareLocalCache");
            auditPunitive(nexPunitive);
            targets++;
        }

        int compatibleForks = 0;
        if (loadAoTD) {
            Class<?> fork = Class.forName(
                    "data.kaysaar.aotd.tot.scripts.commoditydata.AoTDCommodityMarketData",
                    false, loader);
            Class<?> compatibility = Class.forName(
                    "com.fs.starfarer.api.StarsectorPrepatcherMarketShareRuntime",
                    false, loader);
            Method eligible = compatibility.getMethod("isEligibleClass", Class.class);
            require(Boolean.TRUE.equals(eligible.invoke(null, fork)),
                    "AoTD fork was not admitted by installed market-share runtime");
            require(fork.getMethod("getMarketSharePercentPerFaction").getDeclaringClass()
                            == marketData,
                    "AoTD fork no longer inherits the patched per-faction method");
            compatibleForks++;
        }

        System.out.println("OK market-share actual-javaagent targets=" + targets
                + " compatibleForks=" + compatibleForks + " no-retained-fields");
    }

    private static void auditPunitive(Class<?> type) {
        Method helper = null;
        for (Method method : type.getDeclaredMethods()) {
            if (!"spp$punitiveCachedPlayerShare".equals(method.getName())) continue;
            require(helper == null, "duplicate punitive helper in " + type.getName());
            helper = method;
        }
        require(helper != null, "punitive helper missing from " + type.getName());
        int modifiers = helper.getModifiers();
        require(Modifier.isPrivate(modifiers) && Modifier.isStatic(modifiers)
                        && helper.isSynthetic(),
                "punitive helper metadata changed in " + type.getName());
        for (Field field : type.getDeclaredFields()) {
            require(!field.getName().startsWith("spp$punitive"),
                    "punitive patch retained state in " + type.getName()
                            + ": " + field.getName());
        }
    }

    private static void requireApplied(Class<?> type, String patchId) {
        String key = STATUS_PREFIX + type.getName() + "." + patchId;
        String status = System.getProperty(key);
        require("APPLIED".equals(status) || "ALREADY_APPLIED".equals(status),
                "unexpected patch status " + key + "=" + status);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
