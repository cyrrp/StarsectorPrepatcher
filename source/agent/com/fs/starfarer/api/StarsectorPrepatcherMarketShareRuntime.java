package com.fs.starfarer.api;

import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.campaign.econ.reach.CommodityMarketData;

import java.lang.reflect.Method;

/**
 * Loader-local compatibility gate for market-share optimizations.
 *
 * <p>The optimized aggregation is valid for vanilla CommodityMarketData and
 * for the owned AoTD scheduler fork while it inherits every method whose
 * virtual dispatch or call multiplicity is changed by the optimization. The
 * per-class decision is cached in ClassValue so optional mod classloaders are
 * not retained across campaign/mod reloads.</p>
 */
public final class StarsectorPrepatcherMarketShareRuntime {
    public static final String AOTD_COMMODITY_MARKET_DATA_CLASS =
            "data.kaysaar.aotd.tot.scripts.commoditydata.AoTDCommodityMarketData";

    private static final ClassValue<Boolean> ELIGIBLE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return Boolean.valueOf(computeEligibility(type));
        }
    };

    private StarsectorPrepatcherMarketShareRuntime() {}

    /** Hot-path entry used by the injected P0 and P1 bodies. */
    public static boolean isEligible(Object value) {
        return value != null && ELIGIBLE.get(value.getClass()).booleanValue();
    }

    /** Validation/diagnostic entry that does not require constructing the mod object. */
    public static boolean isEligibleClass(Class<?> type) {
        return type != null && ELIGIBLE.get(type).booleanValue();
    }

    private static boolean computeEligibility(Class<?> type) {
        if (type == CommodityMarketData.class) return true;
        if (!AOTD_COMMODITY_MARKET_DATA_CLASS.equals(type.getName())) return false;
        if (!CommodityMarketData.class.isAssignableFrom(type)) return false;

        try {
            // P0 replaces repeated virtual getMarketSharePercent() calls with
            // one getMarkets()/getExportMarketSharePercent() pass. P1 reuses
            // the per-faction result for the single-faction player query. Any
            // fork override of these methods, including the indirect data
            // accessor, therefore makes the inherited optimization unsafe.
            return resolvesToVanilla(type, "getMarketSharePercentPerFaction")
                    && resolvesToVanilla(type, "getMarketSharePercent", FactionAPI.class)
                    && resolvesToVanilla(type, "getMarkets")
                    && resolvesToVanilla(type, "getExportMarketSharePercent", MarketAPI.class)
                    && resolvesToVanilla(type, "getMarketShareData", MarketAPI.class);
        } catch (ReflectiveOperationException | SecurityException failure) {
            return false;
        }
    }

    private static boolean resolvesToVanilla(
            Class<?> type, String methodName, Class<?>... parameterTypes)
            throws ReflectiveOperationException {
        Method method = type.getMethod(methodName, parameterTypes);
        return method.getDeclaringClass() == CommodityMarketData.class;
    }
}
