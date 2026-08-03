package com.fs.starfarer.api;

import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.campaign.econ.CommodityOnMarket;
import com.fs.starfarer.campaign.econ.reach.CommodityMarketData;
import com.fs.starfarer.campaign.econ.reach.ReachEconomy;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Loader-safe compatibility gate for Local Resources legality and econ-group indexing.
 *
 * <p>The maintained AoTD Scheduler Fork is admitted only while the exact runtime
 * classes retain the semantic surfaces used by the optimized paths. Decisions
 * and optional MethodHandles live in ClassValue values, so the runtime does not
 * retain an optional mod class or its class loader across reloads.</p>
 */
public final class StarsectorPrepatcherEconomyHotpathRuntime {
    public static final String AOTD_COMMODITY_CLASS =
            "data.kaysaar.aotd.tot.scripts.commoditydata.AoTDCommodityOnMarket";
    public static final String AOTD_AVAILABLE_STAT_CLASS =
            "data.kaysaar.aotd.tot.scripts.commoditydata.AoTDAvailableStat";
    public static final String AOTD_SUPPLY_DEMAND_CLASS =
            "data.kaysaar.aotd.tot.scripts.commoditydata.AoTDSupplyDemandData";
    public static final String AOTD_COMMODITY_MARKET_DATA_CLASS =
            "data.kaysaar.aotd.tot.scripts.commoditydata.AoTDCommodityMarketData";
    public static final String AOTD_ECON_SPEC_CLASS =
            "data.kaysaar.aotd.tot.plugins.AoTDCommodityEconSpec";
    public static final String AOTD_CALCULATION_SCRIPT_CLASS =
            "data.kaysaar.aotd.tot.plugins.AoTDBaseDemSupCalc";
    public static final String AOTD_REACH_ECONOMY_CLASS =
            "data.kaysaar.aotd.tot.scripts.economy.AoTDReachEconomy";

    /** Sentinel returned when the exact AoTD committed supply/demand state cannot be peeked. */
    public static final long AOTD_RAW_TOTALS_UNAVAILABLE = Long.MIN_VALUE;

    private static final int KIND_UNSUPPORTED = 0;
    private static final int KIND_VANILLA_COMMODITY = 1;
    private static final int KIND_AOTD_COMMODITY = 2;
    private static final int KIND_VANILLA_REACH = 3;
    private static final int KIND_AOTD_REACH = 4;

    private static final Object[] UNSUPPORTED = {Integer.valueOf(KIND_UNSUPPORTED)};
    private static final Object[] VANILLA_COMMODITY = {
            Integer.valueOf(KIND_VANILLA_COMMODITY)};
    private static final Object[] VANILLA_REACH = {Integer.valueOf(KIND_VANILLA_REACH)};

    private static final ClassValue<Object[]> COMPATIBILITY = new ClassValue<>() {
        @Override
        protected Object[] computeValue(Class<?> type) {
            return computeCompatibility(type);
        }
    };

    private StarsectorPrepatcherEconomyHotpathRuntime() {}

    public static boolean isVanillaCommodity(Object value) {
        return value != null
                && kind(COMPATIBILITY.get(value.getClass())) == KIND_VANILLA_COMMODITY;
    }

    public static boolean isAoTDCommodity(Object value) {
        return value != null
                && kind(COMPATIBILITY.get(value.getClass())) == KIND_AOTD_COMMODITY;
    }

    public static boolean isAoTDCommodityClassEligible(Class<?> type) {
        return type != null && kind(COMPATIBILITY.get(type)) == KIND_AOTD_COMMODITY;
    }

    public static boolean isReachEconomyEligible(Object value) {
        if (value == null) return false;
        int kind = kind(COMPATIBILITY.get(value.getClass()));
        return kind == KIND_VANILLA_REACH || kind == KIND_AOTD_REACH;
    }

    public static boolean isReachEconomyClassEligible(Class<?> type) {
        if (type == null) return false;
        int kind = kind(COMPATIBILITY.get(type));
        return kind == KIND_VANILLA_REACH || kind == KIND_AOTD_REACH;
    }

    /**
     * True only when the raw field is the market-data implementation expected
     * by the admitted commodity class. AoTD's repair-on-access path must not be
     * bypassed when a vanilla CommodityMarketData was left in the field.
     */
    public static boolean isExpectedWarmMarketData(Object commodity, Object rawData) {
        if (commodity == null || rawData == null) return false;
        Object[] access = COMPATIBILITY.get(commodity.getClass());
        int kind = kind(access);
        if (kind == KIND_VANILLA_COMMODITY) {
            return rawData instanceof CommodityMarketData;
        }
        if (kind != KIND_AOTD_COMMODITY || !(rawData instanceof CommodityMarketData)) {
            return false;
        }
        Class<?> rawType = rawData.getClass();
        return AOTD_COMMODITY_MARKET_DATA_CLASS.equals(rawType.getName())
                && rawType.getClassLoader() == commodity.getClass().getClassLoader();
    }

    /**
     * Peeks AoTD's already committed supply/demand object without invoking its
     * lazy materializer. High 32 bits contain supply, low 32 bits demand.
     */
    public static long peekAoTDRawTotals(Object commodity) {
        if (commodity == null) return AOTD_RAW_TOTALS_UNAVAILABLE;
        Object[] access = COMPATIBILITY.get(commodity.getClass());
        if (kind(access) != KIND_AOTD_COMMODITY || access.length != 9) {
            return AOTD_RAW_TOTALS_UNAVAILABLE;
        }
        try {
            Object available = ((MethodHandle) access[1]).invoke(commodity);
            if (available == null) return AOTD_RAW_TOTALS_UNAVAILABLE;
            Object data = ((MethodHandle) access[2]).invoke(available);
            if (data == null) return AOTD_RAW_TOTALS_UNAVAILABLE;
            int supply = (int) ((MethodHandle) access[3]).invoke(data);
            int demand = (int) ((MethodHandle) access[4]).invoke(data);
            return ((long) supply << 32) | (demand & 0xffffffffL);
        } catch (Throwable failure) {
            return AOTD_RAW_TOTALS_UNAVAILABLE;
        }
    }

    public static int unpackAoTDRawSupply(long totals) {
        return (int) (totals >> 32);
    }

    public static int unpackAoTDRawDemand(long totals) {
        return (int) totals;
    }

    /**
     * Converts the committed AoTD raw totals through the same calculation script
     * used by AoTDCommodityOnMarket.updateMaxSupplyAndDemand(). The method never
     * invokes the lazy supply/demand getter or CommodityMarketData constructor.
     * High 32 bits contain converted max supply, low 32 bits max demand.
     */
    public static long peekAoTDConvertedMaxima(Object commodity) {
        if (!(commodity instanceof CommodityOnMarketAPI typed)) {
            return AOTD_RAW_TOTALS_UNAVAILABLE;
        }
        Object[] access = COMPATIBILITY.get(commodity.getClass());
        if (kind(access) != KIND_AOTD_COMMODITY || access.length != 9) {
            return AOTD_RAW_TOTALS_UNAVAILABLE;
        }
        try {
            Object available = ((MethodHandle) access[1]).invoke(commodity);
            if (available == null) return AOTD_RAW_TOTALS_UNAVAILABLE;
            Object data = ((MethodHandle) access[2]).invoke(available);
            if (data == null) return AOTD_RAW_TOTALS_UNAVAILABLE;
            int rawSupply = (int) ((MethodHandle) access[3]).invoke(data);
            int rawDemand = (int) ((MethodHandle) access[4]).invoke(data);
            Object spec = ((MethodHandle) access[5]).invoke(data);
            if (spec == null) return AOTD_RAW_TOTALS_UNAVAILABLE;
            Object calculator = ((MethodHandle) access[6]).invoke(spec);
            if (calculator == null) return AOTD_RAW_TOTALS_UNAVAILABLE;
            MarketAPI market = typed.getMarket();
            String commodityId = typed.getId();
            int maxSupply = (int) ((MethodHandle) access[7]).invoke(
                    calculator, (float) rawSupply, market, commodityId);
            int maxDemand = (int) ((MethodHandle) access[8]).invoke(
                    calculator, (float) rawDemand, market, commodityId);
            return ((long) maxSupply << 32) | (maxDemand & 0xffffffffL);
        } catch (Throwable failure) {
            return AOTD_RAW_TOTALS_UNAVAILABLE;
        }
    }

    public static int unpackAoTDMaxSupply(long maxima) {
        return (int) (maxima >> 32);
    }

    public static int unpackAoTDMaxDemand(long maxima) {
        return (int) maxima;
    }

    private static Object[] computeCompatibility(Class<?> type) {
        if (type == CommodityOnMarket.class) return VANILLA_COMMODITY;
        if (type == ReachEconomy.class) return VANILLA_REACH;

        String name = type.getName();
        try {
            if (AOTD_COMMODITY_CLASS.equals(name)) {
                return computeAoTDCommodityAccess(type);
            }
            if (AOTD_REACH_ECONOMY_CLASS.equals(name)) {
                return computeAoTDReachCompatibility(type)
                        ? new Object[]{Integer.valueOf(KIND_AOTD_REACH)}
                        : UNSUPPORTED;
            }
        } catch (ReflectiveOperationException | SecurityException | LinkageError failure) {
            return UNSUPPORTED;
        }
        return UNSUPPORTED;
    }

    private static Object[] computeAoTDCommodityAccess(Class<?> type)
            throws ReflectiveOperationException {
        if (!CommodityOnMarket.class.isAssignableFrom(type)) return UNSUPPORTED;

        Method availableMethod = type.getMethod("getAoTDAvailableStat");
        Method marketDataMethod = type.getMethod("getCommodityMarketData");
        if (availableMethod.getDeclaringClass() != type
                || marketDataMethod.getDeclaringClass() != type) {
            return UNSUPPORTED;
        }

        Class<?> availableType = availableMethod.getReturnType();
        if (!AOTD_AVAILABLE_STAT_CLASS.equals(availableType.getName())
                || availableType.getClassLoader() != type.getClassLoader()) {
            return UNSUPPORTED;
        }
        Field supplyDemandField = availableType.getDeclaredField("supplyDemandData");
        Class<?> dataType = supplyDemandField.getType();
        if (!AOTD_SUPPLY_DEMAND_CLASS.equals(dataType.getName())
                || dataType.getClassLoader() != type.getClassLoader()) {
            return UNSUPPORTED;
        }

        Method supplyMethod = dataType.getMethod("getTotalRawUnitsFromSupply");
        Method demandMethod = dataType.getMethod("getTotalRawUnitsFromDemand");
        Method econSpecMethod = dataType.getMethod("getEconSpec");
        Class<?> econSpecType = econSpecMethod.getReturnType();
        if (supplyMethod.getDeclaringClass() != dataType
                || demandMethod.getDeclaringClass() != dataType
                || econSpecMethod.getDeclaringClass() != dataType
                || supplyMethod.getReturnType() != int.class
                || demandMethod.getReturnType() != int.class
                || !AOTD_ECON_SPEC_CLASS.equals(econSpecType.getName())
                || econSpecType.getClassLoader() != type.getClassLoader()) {
            return UNSUPPORTED;
        }

        Method calculationScriptMethod = econSpecType.getMethod("getCalculationScript");
        Class<?> calculationScriptType = calculationScriptMethod.getReturnType();
        if (calculationScriptMethod.getDeclaringClass() != econSpecType
                || !AOTD_CALCULATION_SCRIPT_CLASS.equals(
                        calculationScriptType.getName())
                || calculationScriptType.getClassLoader() != type.getClassLoader()) {
            return UNSUPPORTED;
        }
        Method convertSupplyMethod = calculationScriptType.getMethod(
                "convertRawUnitsToSupply", float.class, MarketAPI.class, String.class);
        Method convertDemandMethod = calculationScriptType.getMethod(
                "convertRawUnitsToDemand", float.class, MarketAPI.class, String.class);
        if (convertSupplyMethod.getDeclaringClass() != calculationScriptType
                || convertDemandMethod.getDeclaringClass() != calculationScriptType
                || convertSupplyMethod.getReturnType() != int.class
                || convertDemandMethod.getReturnType() != int.class) {
            return UNSUPPORTED;
        }

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle getAvailable = lookup.unreflect(availableMethod).asType(
                MethodType.methodType(Object.class, Object.class));
        MethodHandles.Lookup privateAvailable = MethodHandles.privateLookupIn(
                availableType, lookup);
        MethodHandle peekData = privateAvailable.unreflectGetter(supplyDemandField).asType(
                MethodType.methodType(Object.class, Object.class));
        MethodHandle getSupply = lookup.unreflect(supplyMethod).asType(
                MethodType.methodType(int.class, Object.class));
        MethodHandle getDemand = lookup.unreflect(demandMethod).asType(
                MethodType.methodType(int.class, Object.class));
        MethodHandle getEconSpec = lookup.unreflect(econSpecMethod).asType(
                MethodType.methodType(Object.class, Object.class));
        MethodHandle getCalculationScript = lookup.unreflect(
                calculationScriptMethod).asType(
                MethodType.methodType(Object.class, Object.class));
        MethodType conversionType = MethodType.methodType(
                int.class, Object.class, float.class, MarketAPI.class, String.class);
        MethodHandle convertSupply = lookup.unreflect(convertSupplyMethod)
                .asType(conversionType);
        MethodHandle convertDemand = lookup.unreflect(convertDemandMethod)
                .asType(conversionType);
        return new Object[]{Integer.valueOf(KIND_AOTD_COMMODITY),
                getAvailable, peekData, getSupply, getDemand, getEconSpec,
                getCalculationScript, convertSupply, convertDemand};
    }

    private static boolean computeAoTDReachCompatibility(Class<?> type)
            throws ReflectiveOperationException {
        if (!ReachEconomy.class.isAssignableFrom(type)) return false;
        return resolvesToVanilla(type, "getMarketsInGroup", String.class)
                && resolvesToVanilla(type, "getMarkets")
                && resolvesToVanilla(type, "isInGroup", String.class, MarketAPI.class)
                && resolvesToVanilla(type, "removeMarket", MarketAPI.class);
    }

    private static boolean resolvesToVanilla(
            Class<?> type, String methodName, Class<?>... parameterTypes)
            throws ReflectiveOperationException {
        Method method = type.getMethod(methodName, parameterTypes);
        return method.getDeclaringClass() == ReachEconomy.class;
    }

    private static int kind(Object[] access) {
        return access == null || access.length == 0
                ? KIND_UNSUPPORTED : ((Integer) access[0]).intValue();
    }
}
