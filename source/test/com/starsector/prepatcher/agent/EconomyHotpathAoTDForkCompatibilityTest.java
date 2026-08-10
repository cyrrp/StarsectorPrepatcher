package com.starsector.prepatcher.agent;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.StarsectorPrepatcherEconomyHotpathRuntime;
import com.fs.starfarer.api.StarsectorPrepatcherHooks;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.CommoditySpecAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MutableCommodityQuantity;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.MutableStatWithTempMods;
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.campaign.econ.CommodityOnMarket;
import com.fs.starfarer.campaign.econ.Market;
import com.fs.starfarer.campaign.econ.reach.CommodityMarketData;
import com.fs.starfarer.campaign.econ.reach.ReachEconomy;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.VarInsnNode;
import jdk.internal.org.objectweb.asm.tree.InsnNode;
import sun.misc.Unsafe;

import java.io.InputStream;
import java.lang.ref.ReferenceQueue;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.jar.JarFile;

/** Exact owned-fork compatibility and fail-closed checks for economy hot paths. */
public final class EconomyHotpathAoTDForkCompatibilityTest {
    private static final Unsafe U = unsafe();

    private static final String COMMODITY =
            "data.kaysaar.aotd.tot.scripts.commoditydata.AoTDCommodityOnMarket";
    private static final String AVAILABLE =
            "data.kaysaar.aotd.tot.scripts.commoditydata.AoTDAvailableStat";
    private static final String SUPPLY_DEMAND =
            "data.kaysaar.aotd.tot.scripts.commoditydata.AoTDSupplyDemandData";
    private static final String ECON_SPEC =
            "data.kaysaar.aotd.tot.plugins.AoTDCommodityEconSpec";
    private static final String CALCULATION_SCRIPT =
            "data.kaysaar.aotd.tot.plugins.AoTDBaseDemSupCalc";
    private static final String ECON_SPEC_MANAGER =
            "data.kaysaar.aotd.tot.plugins.AoTDCommodityEconSpecManager";
    private static final String REACH =
            "data.kaysaar.aotd.tot.scripts.economy.AoTDReachEconomy";
    private static final String ECONOMY =
            "data.kaysaar.aotd.tot.scripts.economy.AoTDEconomy";
    private static final String LOCAL_RESOURCES =
            "data.kaysaar.aotd.tot.scripts.submarket.aotd.AoTDLocalResourcesSubmarketPlugin";
    private static final String NEX_LOCAL_RESOURCES =
            "data.kaysaar.aotd.tot.scripts.submarket.nex.AoTDxNexLocalResourcesSubmarketPlugin";
    private static final String LOCAL_RESOURCES_TOOLTIP =
            "data.kaysaar.aotd.tot.scripts.submarket.aotd.AoTDLocalResourcesTooltipSnapshot";

    private EconomyHotpathAoTDForkCompatibilityTest() {}

    private static final class FailOnceLinkedHashMap<K, V> extends LinkedHashMap<K, V> {
        private boolean armed;

        FailOnceLinkedHashMap(Map<? extends K, ? extends V> source) {
            super(source);
        }

        void arm() {
            armed = true;
        }

        @Override
        public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
            if (armed) {
                armed = false;
                throw new IllegalStateException("intentional publication failure");
            }
            return super.computeIfAbsent(key, mappingFunction);
        }
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 1,
                "Usage: EconomyHotpathAoTDForkCompatibilityTest <AoTDToolboxTheory.jar>");
        Path jar = Path.of(args[0]);

        require(StarsectorPrepatcherEconomyHotpathRuntime.isVanillaCommodity(
                        allocate(CommodityOnMarket.class)),
                "exact vanilla CommodityOnMarket was rejected");
        require(StarsectorPrepatcherEconomyHotpathRuntime.isReachEconomyClassEligible(
                        ReachEconomy.class),
                "exact vanilla ReachEconomy was rejected");
        require(!StarsectorPrepatcherEconomyHotpathRuntime.isAoTDCommodityClassEligible(
                        UnknownCommodity.class),
                "unknown CommodityOnMarket subclass entered the owned-fork path");
        require(!StarsectorPrepatcherEconomyHotpathRuntime.isReachEconomyClassEligible(
                        UnknownReach.class),
                "unknown ReachEconomy subclass entered the owned-fork path");

        installSettingsStub();
        configureHooks();

        auditRealFork(jar);
        verifyCommittedAoTDColdPredicate(jar);
        verifyPostImmigrationCommittedFastPath(jar);
        auditFutureOverrideFallbacks(jar);
        auditClassLoaderRetention(jar);

        System.out.println("OK economy-hotpath-aotd-fork"
                + " committed-converted-legality-no-materialization"
                + " post-immigration-materialized-generation/size-fast/live-repair"
                + " stale-proof-recapture/rollback/failure-done"
                + " econ-group-inherited-surface/add-super/bulk-init"
                + " local-resources-tooltip-one-call-read-only"
                + " local-resources-inheritance temporal-super"
                + " future-contract-fail-closed classvalue-loader-gc");
    }

    private static void auditRealFork(Path jar) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{jar.toUri().toURL()},
                EconomyHotpathAoTDForkCompatibilityTest.class.getClassLoader())) {
            Class<?> commodity = Class.forName(COMMODITY, false, loader);
            Class<?> available = Class.forName(AVAILABLE, false, loader);
            Class<?> data = Class.forName(SUPPLY_DEMAND, false, loader);
            Class<?> reach = Class.forName(REACH, false, loader);

            require(commodity.getClassLoader() == loader && reach.getClassLoader() == loader,
                    "owned-fork classes delegated instead of loading from supplied jar");
            require(CommodityOnMarket.class.isAssignableFrom(commodity),
                    "AoTD commodity no longer extends CommodityOnMarket");
            require(ReachEconomy.class.isAssignableFrom(reach),
                    "AoTD reach economy no longer extends ReachEconomy");
            require(StarsectorPrepatcherEconomyHotpathRuntime
                            .isAoTDCommodityClassEligible(commodity),
                    "AoTD commodity legality surface was rejected");
            require(StarsectorPrepatcherEconomyHotpathRuntime
                            .isReachEconomyClassEligible(reach),
                    "AoTD ReachEconomy econ-group surface was rejected");

            Method getAvailable = commodity.getMethod("getAoTDAvailableStat");
            require(getAvailable.getDeclaringClass() == commodity
                            && getAvailable.getReturnType() == available,
                    "AoTD committed-state accessor changed");
            require(commodity.getMethod("getCommodityMarketData").getDeclaringClass()
                            == commodity,
                    "AoTD repair-on-access market-data method changed");
            Field committed = available.getDeclaredField("supplyDemandData");
            require(committed.getType() == data,
                    "AoTD committed supply/demand field changed type");
            require(data.getMethod("getTotalRawUnitsFromSupply").getReturnType() == int.class
                            && data.getMethod("getTotalRawUnitsFromDemand")
                            .getReturnType() == int.class,
                    "AoTD raw total accessors changed");
            Class<?> econSpec = Class.forName(ECON_SPEC, false, loader);
            Class<?> calculator = Class.forName(CALCULATION_SCRIPT, false, loader);
            require(data.getMethod("getEconSpec").getDeclaringClass() == data
                            && data.getMethod("getEconSpec").getReturnType() == econSpec,
                    "AoTD econ-spec accessor changed");
            require(econSpec.getMethod("getCalculationScript").getDeclaringClass()
                            == econSpec
                            && econSpec.getMethod("getCalculationScript").getReturnType()
                            == calculator,
                    "AoTD calculation-script accessor changed");
            require(calculator.getMethod("convertRawUnitsToSupply",
                            float.class, MarketAPI.class, String.class)
                            .getReturnType() == int.class
                            && calculator.getMethod("convertRawUnitsToDemand",
                            float.class, MarketAPI.class, String.class)
                            .getReturnType() == int.class,
                    "AoTD raw-unit conversion surface changed");

            requireVanillaReachDeclaration(reach, "getMarketsInGroup", String.class);
            requireVanillaReachDeclaration(reach, "getMarkets");
            requireVanillaReachDeclaration(reach, "isInGroup",
                    String.class, MarketAPI.class);
            requireVanillaReachDeclaration(reach, "removeMarket", MarketAPI.class);

            Class<?> excDef = commodity.getMethod("getExcDefData").getReturnType();
            Field excess = excDef.getField("excess");
            Field deficit = excDef.getField("deficit");
            require(Modifier.isPublic(excess.getModifiers())
                            && Modifier.isPublic(deficit.getModifiers())
                            && MutableStatWithTempMods.class.isAssignableFrom(excess.getType())
                            && MutableStatWithTempMods.class.isAssignableFrom(deficit.getType()),
                    "AoTD commodity-temporal excess/deficit surface changed");
        }

        ClassNode reachNode = readNode(jar, REACH);
        MethodNode addMarket = requireMethod(reachNode, "addMarket",
                "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V");
        require(countCalls(addMarket, Opcodes.INVOKESPECIAL,
                        "com/fs/starfarer/campaign/econ/reach/ReachEconomy",
                        "addMarket",
                        "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V") == 1,
                "AoTDReachEconomy.addMarket no longer delegates exactly once to vanilla");

        ClassNode economyNode = readNode(jar, ECONOMY);
        int addAll = 0;
        int marketPopulationAddAll = 0;
        int listenerPopulationAddAll = 0;
        int groupReads = 0;
        for (MethodNode method : economyNode.methods) {
            if (!"<init>".equals(method.name)) continue;
            addAll += countCalls(method, Opcodes.INVOKEINTERFACE,
                    "java/util/List", "addAll", "(Ljava/util/Collection;)Z");
            marketPopulationAddAll += countAddAllWhoseNearestPriorCallIs(
                    method, "getMarkets");
            listenerPopulationAddAll += countAddAllWhoseNearestPriorCallIs(
                    method, "getUpdateListeners");
            groupReads += countNamedCalls(method, "getMarketsInGroup");
        }
        require(addAll == 2 && marketPopulationAddAll == 1
                        && listenerPopulationAddAll == 1,
                "AoTDEconomy bulk population surface changed: addAll=" + addAll
                        + " markets=" + marketPopulationAddAll
                        + " listeners=" + listenerPopulationAddAll);
        require(groupReads == 0,
                "AoTDEconomy queries econ-group index before completing bulk population");

        auditLocalResourcesInheritance(readNode(jar, LOCAL_RESOURCES));
        auditLocalResourcesInheritance(readNode(jar, NEX_LOCAL_RESOURCES));
        auditLocalResourcesTooltipSnapshot(jar);

        ClassNode commodityNode = readNode(jar, COMMODITY);
        require(findMethod(commodityNode, "advance", "(F)V") == null,
                "AoTD commodity now overrides the inherited temporal advance surface");
        ClassNode availableNode = readNode(jar, AVAILABLE);
        MethodNode advance = requireMethod(availableNode, "advance", "(F)V");
        require(countCalls(advance, Opcodes.INVOKESPECIAL,
                        "com/fs/starfarer/api/combat/MutableStatWithTempMods",
                        "advance", "(F)V") == 1,
                "AoTDAvailableStat.advance no longer delegates exactly once to base stat");
    }

    private static void verifyCommittedAoTDColdPredicate(Path jar) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{jar.toUri().toURL()},
                EconomyHotpathAoTDForkCompatibilityTest.class.getClassLoader())) {
            Class<?> commodityType = Class.forName(COMMODITY, false, loader);
            Class<?> availableType = Class.forName(AVAILABLE, false, loader);
            Class<?> dataType = Class.forName(SUPPLY_DEMAND, false, loader);
            Class<?> econSpecType = Class.forName(ECON_SPEC, false, loader);
            Class<?> calculatorType = Class.forName(CALCULATION_SCRIPT, false, loader);
            Class<?> econSpecManagerType = Class.forName(
                    ECON_SPEC_MANAGER, false, loader);

            Object commodity = U.allocateInstance(commodityType);
            Object available = availableType.getConstructor(float.class).newInstance(0f);
            Object data = dataType.getConstructor(String.class).newInstance("test");
            Object calculator = calculatorType.getConstructor().newInstance();
            Object spec = econSpecType.getConstructor(
                    String.class, float.class, float.class, String.class,
                    float.class, float.class).newInstance(
                    "test", 10f, 10f, CALCULATION_SCRIPT, 0.05f, 0.15f);
            dataType.getField("ecSpec").set(data, spec);
            @SuppressWarnings("unchecked")
            Map<String, Object> specs = (Map<String, Object>)
                    econSpecManagerType.getField("specs").get(null);
            specs.put("test", spec);
            calculatorType.getField("econMult").setFloat(null, 0.3f);
            installConversionSettings(calculator, 10f);
            try {
                dataType.getField("supply").setInt(data, 0);
                dataType.getField("demand").setInt(data, 7);
                Field committed = availableType.getDeclaredField("supplyDemandData");
                committed.setAccessible(true);
                committed.set(available, data);
                commodityVar("available", MutableStatWithTempMods.class)
                        .set(commodity, available);
                commodityVar("commodityId", String.class).set(commodity, "test");
                commodityVar("market", Market.class).set(
                        commodity, U.allocateInstance(Market.class));

                CommodityOnMarket core = (CommodityOnMarket) commodity;
                core.setDemandLegal(true);
                core.setSupplyLegal(false);
                require(core.getMaxDemand() == 0 && core.getMaxSupply() == 0,
                        "AoTD cold fixture unexpectedly contains converted max fields");
                require(commodityVar("commodityMarketData", CommodityMarketData.class)
                                .get(commodity) == null,
                        "AoTD cold fixture unexpectedly contains market data");

                MarketAPI illegal = (MarketAPI) Proxy.newProxyInstance(
                        EconomyHotpathAoTDForkCompatibilityTest.class.getClassLoader(),
                        new Class<?>[]{MarketAPI.class},
                        (proxy, method, args) -> {
                            if ("isIllegal".equals(method.getName())) return true;
                            return defaultValue(proxy, method, args);
                        });

                // Raw demand is positive, but 7 / (10 * 10 * 0.3) floors to zero.
                require(!StarsectorPrepatcherHooks.localResourcesShouldHaveCommodity(
                                illegal, (CommodityOnMarketAPI) commodity),
                        "legality path used raw AoTD demand positivity instead of converted maxDemand");
                dataType.getField("demand").setInt(data, 31);
                require(StarsectorPrepatcherHooks.localResourcesShouldHaveCommodity(
                                illegal, (CommodityOnMarketAPI) commodity),
                        "legality path ignored positive converted AoTD maxDemand");
                require(commodityVar("commodityMarketData", CommodityMarketData.class)
                                .get(commodity) == null,
                        "legality path materialized AoTD CommodityMarketData");
                require(committed.get(available) == data,
                        "legality path replaced/materialized AoTD supply-demand state");

                dataType.getField("demand").setInt(data, 0);
                require(!StarsectorPrepatcherHooks.localResourcesShouldHaveCommodity(
                                illegal, (CommodityOnMarketAPI) commodity),
                        "legality path returned true without legal converted supply or demand");
                dataType.getField("supply").setInt(data, 4);
                core.setSupplyLegal(true);
                require(!StarsectorPrepatcherHooks.localResourcesShouldHaveCommodity(
                                illegal, (CommodityOnMarketAPI) commodity),
                        "legality path used raw AoTD supply positivity instead of converted maxSupply");
                dataType.getField("supply").setInt(data, 40);
                require(StarsectorPrepatcherHooks.localResourcesShouldHaveCommodity(
                                illegal, (CommodityOnMarketAPI) commodity),
                        "legality path ignored positive converted AoTD maxSupply");
            } finally {
                // Do not leave Global holding an object from the disposable AoTD loader.
                installSettingsStub();
            }
        }
    }

    private static void verifyPostImmigrationCommittedFastPath(Path jar) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{jar.toUri().toURL()},
                EconomyHotpathAoTDForkCompatibilityTest.class.getClassLoader())) {
            Class<?> commodityType = Class.forName(COMMODITY, false, loader);
            Class<?> availableType = Class.forName(AVAILABLE, false, loader);
            Class<?> dataType = Class.forName(SUPPLY_DEMAND, false, loader);
            Class<?> marketDataType = Class.forName(
                    "data.kaysaar.aotd.tot.scripts.trade.models.AoTDMarketData",
                    false,
                    loader);
            Class<?> registryType = Class.forName(
                    "data.kaysaar.aotd.tot.compat.MarketRegistry", false, loader);
            Class<?> schedulerType = Class.forName(
                    "data.kaysaar.aotd.tot.compat.SchedulerBridge", false, loader);
            Class<?> contractType = Class.forName(
                    "data.kaysaar.aotd.tot.compat.PrepatcherContract", false, loader);
            Class<?> tradeManagerType = Class.forName(
                    "data.kaysaar.aotd.tot.scripts.trade.manager.AoTDTradeManager",
                    false,
                    loader);
            Class<?> postTaskType = Class.forName(
                    "data.kaysaar.aotd.tot.scripts.economy."
                            + "AoTDPostImmigrationTradeSnapshotTask",
                    false,
                    loader);
            Class<?> econSpecType = Class.forName(ECON_SPEC, false, loader);
            Class<?> calculatorType = Class.forName(CALCULATION_SCRIPT, false, loader);
            Class<?> econSpecManagerType = Class.forName(ECON_SPEC_MANAGER, false, loader);

            long capabilities = contractType.getField("DECLARED_CAPABILITIES").getLong(null);
            schedulerType.getMethod("activateFromPrepatcher", long.class)
                    .invoke(null, capabilities);

            TradeCommodity first = createTradeCommodity(
                    commodityType, availableType, dataType, "fast-a", 7, 2);
            TradeCommodity second = createTradeCommodity(
                    commodityType, availableType, dataType, "fast-b", 1, 4);
            List<CommodityOnMarketAPI> commodities = new ArrayList<>();
            commodities.add((CommodityOnMarketAPI) first.commodity);
            commodities.add((CommodityOnMarketAPI) second.commodity);

            Object calculator = calculatorType.getConstructor().newInstance();
            Object spec = econSpecType.getConstructor(
                    String.class,
                    float.class,
                    float.class,
                    String.class,
                    float.class,
                    float.class).newInstance(
                    "fast-a", 1f, 1f, CALCULATION_SCRIPT, 0.05f, 0.15f);
            dataType.getField("ecSpec").set(first.data, spec);
            dataType.getField("ecSpec").set(second.data, spec);
            @SuppressWarnings("unchecked")
            Map<String, Object> specs = (Map<String, Object>)
                    econSpecManagerType.getField("specs").get(null);
            specs.put("fast-a", spec);
            specs.put("fast-b", spec);
            calculatorType.getField("econMult").setFloat(null, 1f);
            installConversionSettings(calculator, 1f);

            AtomicInteger marketSize = new AtomicInteger(5);
            AtomicInteger commodityListReads = new AtomicInteger();
            AtomicInteger industrySnapshotReads = new AtomicInteger();
            AtomicInteger industrySupplyReads = new AtomicInteger();
            AtomicInteger industryDemandReads = new AtomicInteger();
            Map<String, MutableCommodityQuantity> supplies = Map.of(
                    "fast-a", quantity("fast-a", 7),
                    "fast-b", quantity("fast-b", 1));
            Map<String, MutableCommodityQuantity> demands = Map.of(
                    "fast-a", quantity("fast-a", 2),
                    "fast-b", quantity("fast-b", 4));
            Industry industry = (Industry) Proxy.newProxyInstance(
                    EconomyHotpathAoTDForkCompatibilityTest.class.getClassLoader(),
                    new Class<?>[]{Industry.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> "fixture-industry";
                        case "getSupply" -> {
                            industrySupplyReads.incrementAndGet();
                            yield supplies.get((String) args[0]);
                        }
                        case "getDemand" -> {
                            industryDemandReads.incrementAndGet();
                            yield demands.get((String) args[0]);
                        }
                        case "isDisrupted" -> false;
                        default -> defaultValue(proxy, method, args);
                    });
            StatBonus accessibility = new StatBonus();
            accessibility.modifyFlat("fixture", 1f);
            MarketAPI market = (MarketAPI) Proxy.newProxyInstance(
                    EconomyHotpathAoTDForkCompatibilityTest.class.getClassLoader(),
                    new Class<?>[]{MarketAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> "post-immigration-fast-path";
                        case "getFactionId" -> "fixture-faction";
                        case "getSize" -> marketSize.get();
                        case "getAllCommodities" -> {
                            commodityListReads.incrementAndGet();
                            yield commodities;
                        }
                        case "getIndustries" -> {
                            industrySnapshotReads.incrementAndGet();
                            yield List.of(industry);
                        }
                        case "getAccessibilityMod" -> accessibility;
                        case "hasSpaceport" -> true;
                        default -> defaultValue(proxy, method, args);
                    });

            try {
                registryType.getMethod("clear").invoke(null);
                registryType.getMethod("replaceAllMarkets", Map.class)
                        .invoke(null, Map.of("post-immigration-fast-path", market));

                Method getMaterializedGeneration = registryType.getMethod(
                        "getMarketMaterializedInputGeneration", Object.class);
                Method markDirty = registryType.getMethod(
                        "markDirty", Object.class, int.class, int.class);
                Method commitMaterialized = registryType.getMethod(
                        "commitMaterializedState", Object.class, long.class);
                Method commitTrade = registryType.getMethod(
                        "commitTradeSnapshot", Object.class, long.class);
                int priorityNormal = registryType.getField("PRIORITY_NORMAL").getInt(null);
                int ordinaryMask = registryType.getField("DIRTY_TRADE").getInt(null)
                        | registryType.getField("DIRTY_ACCESSIBILITY").getInt(null)
                        | registryType.getField("DIRTY_GLOBAL_REVISION").getInt(null);
                int derivedMask = schedulerType.getField("DIRTY_DERIVED_ECONOMY").getInt(null);
                int repairMask = registryType.getField("DIRTY_VALUE_STATE").getInt(null)
                        | registryType.getField("DIRTY_PRICE").getInt(null)
                        | registryType.getField("DIRTY_STOCKPILE").getInt(null)
                        | derivedMask;

                long initialMaterializedGeneration =
                        (Long) getMaterializedGeneration.invoke(null, market);
                require(initialMaterializedGeneration > 0L,
                        "fixture registry did not assign a materialized-input generation");
                require((Boolean) commitMaterialized.invoke(null, market, 0L)
                                && (Boolean) commitTrade.invoke(null, market, 0L),
                        "fixture could not settle initial materialized/trade registry state");

                markDirty.invoke(null, market, ordinaryMask, priorityNormal);
                require((Long) getMaterializedGeneration.invoke(null, market)
                                == initialMaterializedGeneration,
                        "trade/accessibility/faction work advanced the materialized-input token");
                require((Boolean) commitTrade.invoke(null, market, 0L),
                        "fixture could not settle ordinary post-immigration work");

                markDirty.invoke(null, market, derivedMask, priorityNormal);
                long generation = (Long) getMaterializedGeneration.invoke(null, market);
                require(generation > initialMaterializedGeneration,
                        "derived/materialized work did not advance its dedicated input token");
                require((Boolean) commitMaterialized.invoke(null, market, 0L)
                                && (Boolean) commitTrade.invoke(null, market, 0L),
                        "fixture could not settle derived materialized/trade work");

                require((Boolean) registryType
                                .getMethod(
                                        "recordMaterializedCheckpoint",
                                        Object.class,
                                        int.class)
                                .invoke(null, market, marketSize.get()),
                        "fixture could not publish its pre-immigration size checkpoint");

                Field authoritative = dataType.getDeclaredField("authoritativeDirtyGeneration");
                authoritative.setAccessible(true);
                authoritative.setLong(first.data, generation);
                authoritative.setLong(second.data, generation);

                Method prepare = marketDataType.getMethod(
                        "preparePostImmigrationCapture", MarketAPI.class);
                Object fastCapture = prepare.invoke(null, market);
                require(fastCapture.getClass().getField("usedCommittedNet")
                                .getBoolean(fastCapture),
                        "exact materialized-generation/size proof did not select the fast path");
                require(!fastCapture.getClass().getField("requiresMaterializedRefresh")
                                .getBoolean(fastCapture),
                        "exact committed proof requested an unnecessary materialized refresh");
                require(industrySnapshotReads.get() == 0,
                        "committed fast path touched live market industries");
                require(commodityListReads.get() == 1,
                        "committed fast path repeated market.getAllCommodities");
                require(Map.of("fast-a", 5, "fast-b", -3).equals(
                                capturedNetProduction(fastCapture)),
                        "committed fast path did not reuse the exact published net values");

                authoritative.setLong(second.data, Long.MIN_VALUE);
                commodityListReads.set(0);
                industrySnapshotReads.set(0);
                Object mixedCapture = prepare.invoke(null, market);
                require(!mixedCapture.getClass().getField("usedCommittedNet")
                                .getBoolean(mixedCapture),
                        "mixed authoritative generations did not select the live fallback");
                require(mixedCapture.getClass().getField("requiresMaterializedRefresh")
                                .getBoolean(mixedCapture),
                        "mixed authoritative generations did not request materialized repair");
                require(industrySnapshotReads.get() == commodities.size(),
                        "mixed generations did not recalculate every commodity from one live path");
                require(commodityListReads.get() == 1,
                        "live fallback repeated market.getAllCommodities");
                require(Map.of("fast-a", 5, "fast-b", -3).equals(
                                capturedNetProduction(mixedCapture)),
                        "mixed-generation fallback did not capture one coherent live cut");
                authoritative.setLong(second.data, generation);

                Object manager = tradeManagerType.getConstructor().newInstance();
                Method prepareTrade = tradeManagerType.getMethod(
                        "preparePostImmigrationSnapshot", MarketAPI.class);
                Method commitPreparedSnapshots = tradeManagerType.getMethod(
                        "commitPreparedSnapshots", List.class);
                Object firstPrepared = prepareTrade.invoke(manager, market);
                require((Boolean) commitPreparedSnapshots.invoke(
                                manager, List.of(firstPrepared)),
                        "fixture could not publish its generation-proven initial trade snapshot");

                // Vanilla immigration can increase market size without a callback. The fallback
                // may produce exactly the same net values, but must still queue one repair.
                marketSize.incrementAndGet();
                commodityListReads.set(0);
                industrySnapshotReads.set(0);
                Object grownCapture = prepare.invoke(null, market);
                require(!grownCapture.getClass().getField("usedCommittedNet")
                                .getBoolean(grownCapture),
                        "market growth reused a pre-immigration committed aggregate");
                require(grownCapture.getClass().getField("requiresMaterializedRefresh")
                                .getBoolean(grownCapture),
                        "market-size proof mismatch did not request materialized repair");
                require("MATERIALIZED_CHECKPOINT_MISMATCH".equals(
                                grownCapture.getClass().getField("fallbackReason")
                                        .get(grownCapture).toString()),
                        "market-size mismatch reported the wrong fallback reason");
                require(industrySnapshotReads.get() == commodities.size(),
                        "market growth did not recalculate the complete commodity set");
                require(Map.of("fast-a", 5, "fast-b", -3).equals(
                                capturedNetProduction(grownCapture)),
                        "market-growth fallback changed an unchanged live trade vector");

                Object grownPrepared = prepareTrade.invoke(manager, market);
                require(!grownPrepared.getClass().getField("changed").getBoolean(grownPrepared)
                                && grownPrepared.getClass()
                                        .getField("requiresMaterializedRefresh")
                                        .getBoolean(grownPrepared),
                        "unchanged trade values suppressed the size-mismatch repair signal");
                require((Boolean) commitPreparedSnapshots.invoke(
                                manager, List.of(grownPrepared)),
                        "unchanged live fallback failed its atomic trade-manager commit");

                Class<?> tradeProofType = Class.forName(
                        "data.kaysaar.aotd.tot.compat.MarketRegistry$TradeCaptureProof",
                        false,
                        loader);
                Method captureTradeProof = registryType.getMethod(
                        "captureTradeInputProof", Object.class);
                Method atomicTradeCommit = registryType.getMethod(
                        "commitTradeSnapshotDetailed",
                        Object.class,
                        tradeProofType,
                        int.class,
                        int.class,
                        int.class,
                        long.class);
                long beforeRepair = (Long) getMaterializedGeneration.invoke(null, market);
                Object grownProof = grownPrepared.getClass().getField("tradeCaptureProof")
                        .get(grownPrepared);
                Object firstRepairStatus = atomicTradeCommit.invoke(
                        null, market, grownProof, 0, repairMask, priorityNormal, 0L);
                require("COMMITTED".equals(firstRepairStatus.toString()),
                        "first proof mismatch did not atomically queue a materialized refresh: "
                                + firstRepairStatus);
                long repairGeneration = (Long) getMaterializedGeneration.invoke(null, market);
                require(repairGeneration > beforeRepair,
                        "materialized repair did not advance the input token exactly once");
                Object repeatRepairProof = captureTradeProof.invoke(null, market);
                Object repeatRepairStatus = atomicTradeCommit.invoke(
                        null,
                        market,
                        repeatRepairProof,
                        0,
                        repairMask,
                        priorityNormal,
                        0L);
                require("COMMITTED".equals(repeatRepairStatus.toString())
                                && (Long) getMaterializedGeneration.invoke(null, market)
                                        == repairGeneration,
                        "repeated proof mismatch was not coalesced at one input token");
                int retainedRepairMask = (Integer) registryType
                        .getMethod("getMarketDirtyMask", Object.class)
                        .invoke(null, market);
                int retainedValuePriceStock = repairMask & ~derivedMask;
                require((retainedRepairMask & retainedValuePriceStock)
                                == retainedValuePriceStock,
                        "coalesced repair lost VALUE/PRICE/STOCK work after the trade commit");

                for (Object data : List.of(first.data, second.data)) {
                    Object preparedRefresh = dataType
                            .getMethod("prepareSupplyDemandData", MarketAPI.class, boolean.class)
                            .invoke(data, market, true);
                    require((Boolean) dataType
                                    .getMethod(
                                            "commitPreparedRefresh",
                                            preparedRefresh.getClass())
                                    .invoke(data, preparedRefresh),
                            "scheduler materialization fixture did not commit a commodity");
                }
                require((Boolean) commitMaterialized.invoke(null, market, 0L),
                        "scheduler materialization fixture did not settle registry state");
                require((Boolean) registryType
                                .getMethod(
                                        "recordMaterializedCheckpoint",
                                        Object.class,
                                        int.class)
                                .invoke(null, market, marketSize.get()),
                        "scheduler materialization did not publish the repaired size checkpoint");
                industrySnapshotReads.set(0);
                Object repairedCapture = prepare.invoke(null, market);
                require(repairedCapture.getClass().getField("usedCommittedNet")
                                .getBoolean(repairedCapture)
                                && !repairedCapture.getClass()
                                        .getField("requiresMaterializedRefresh")
                                        .getBoolean(repairedCapture)
                                && industrySnapshotReads.get() == 0,
                        "completed scheduler materialization did not restore the committed fast path");

                Object staleTradeProof = captureTradeProof.invoke(null, market);
                long materializedBeforeOrdinaryStale =
                        (Long) getMaterializedGeneration.invoke(null, market);
                int accessibilityDirty =
                        registryType.getField("DIRTY_ACCESSIBILITY").getInt(null);
                markDirty.invoke(null, market, accessibilityDirty, priorityNormal);
                int dirtyBeforeStaleCommit = (Integer) registryType
                        .getMethod("getMarketDirtyMask", Object.class)
                        .invoke(null, market);
                Object staleStatus = atomicTradeCommit.invoke(
                        null,
                        market,
                        staleTradeProof,
                        0,
                        0,
                        priorityNormal,
                        0L);
                int dirtyAfterStaleCommit = (Integer) registryType
                        .getMethod("getMarketDirtyMask", Object.class)
                        .invoke(null, market);
                require("STALE_INPUT".equals(staleStatus.toString())
                                && (dirtyAfterStaleCommit & dirtyBeforeStaleCommit)
                                        == dirtyBeforeStaleCommit,
                        "stale atomic trade commit cleared externally dirtied work");
                require((Long) getMaterializedGeneration.invoke(null, market)
                                == materializedBeforeOrdinaryStale,
                        "ordinary stale trade rejection advanced the materialized-input token");
                require((Boolean) commitTrade.invoke(null, market, 0L),
                        "fixture could not settle residual stale-input trade work");

                // Exercise the real multi-frame task: capture in frame one, mutate scalar and token,
                // then require selective recapture before frame two publishes or commits registry.
                SectorAPI previousSector = Global.getSector();
                Map<String, Object> persistentData = new LinkedHashMap<>();
                persistentData.put(
                        (String) tradeManagerType.getField("memkey").get(null), manager);
                SectorAPI sector = (SectorAPI) Proxy.newProxyInstance(
                        EconomyHotpathAoTDForkCompatibilityTest.class.getClassLoader(),
                        new Class<?>[]{SectorAPI.class},
                        (proxy, method, args) -> {
                            if ("getPersistentData".equals(method.getName())) {
                                return persistentData;
                            }
                            return defaultValue(proxy, method, args);
                        });
                Global.setSector(sector);
                try {
                    Object postTask = postTaskType
                            .getConstructor(List.class, String.class)
                            .newInstance(List.of(market), "stale-proof-fixture");
                    Method doNextBatch = postTaskType.getMethod("doNextBatch");
                    doNextBatch.invoke(postTask);
                    accessibility.modifyFlat("fixture", 2f);
                    markDirty.invoke(null, market, accessibilityDirty, priorityNormal);
                    doNextBatch.invoke(postTask);
                    require((Boolean) postTaskType.getMethod("isDone").invoke(postTask),
                            "multi-frame post task did not finish after capture and commit frames");
                    require((Integer) privateField(
                                    postTaskType, postTask, "staleProofRecaptures") == 1,
                            "multi-frame post task did not selectively recapture its stale market");
                    require((Integer) privateField(postTaskType, postTask, "changed") == 1
                                    && (Integer) privateField(
                                                    postTaskType, postTask, "unchanged") == 0
                                    && (Integer) privateField(
                                                    postTaskType,
                                                    postTask,
                                                    "accessibilityChanges") == 1,
                            "multi-frame task diagnostics describe the stale pre-recapture cut");
                    Object published = tradeManagerType
                            .getMethod("getMarketData", MarketAPI.class)
                            .invoke(manager, market);
                    require(Float.floatToIntBits(
                                            published.getClass().getField("weight")
                                                    .getFloat(published))
                                    == Float.floatToIntBits(200f),
                            "multi-frame task published the pre-mutation accessibility scalar");

                    Object failureTask = postTaskType
                            .getConstructor(List.class, String.class)
                            .newInstance(List.of(market), "publication-failure-fixture");
                    doNextBatch.invoke(failureTask);
                    accessibility.modifyFlat("fixture", 2.5f);
                    markDirty.invoke(null, market, accessibilityDirty, priorityNormal);

                    Field factionsField = tradeManagerType.getDeclaredField("factionsTradeData");
                    factionsField.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    LinkedHashMap<Object, Object> originalFactions =
                            (LinkedHashMap<Object, Object>) factionsField.get(manager);
                    FailOnceLinkedHashMap<Object, Object> failingFactions =
                            new FailOnceLinkedHashMap<>(originalFactions);
                    failingFactions.arm();
                    factionsField.set(manager, failingFactions);
                    try {
                        doNextBatch.invoke(failureTask);
                        require((Boolean) postTaskType.getMethod("isDone").invoke(failureTask)
                                        && (Boolean) privateField(
                                                        postTaskType,
                                                        failureTask,
                                                        "commitAttempted")
                                        && !(Boolean) privateField(
                                                        postTaskType,
                                                        failureTask,
                                                        "committed")
                                        && (Integer) privateField(
                                                        postTaskType,
                                                        failureTask,
                                                        "batchPublicationFailures") == 1,
                                "contained manager failure left the post task live or committed");
                        Object retained = tradeManagerType
                                .getMethod("getMarketData", MarketAPI.class)
                                .invoke(manager, market);
                        require(Float.floatToIntBits(
                                                retained.getClass().getField("weight")
                                                        .getFloat(retained))
                                        == Float.floatToIntBits(200f),
                                "manager failure did not roll back to the previous complete cut");
                    } finally {
                        factionsField.set(manager, originalFactions);
                    }
                } finally {
                    Global.setSector(previousSector);
                }

                verifyMappingOnlyRollback(
                        tradeManagerType,
                        prepareTrade,
                        commitPreparedSnapshots,
                        market);

                accessibility.modifyFlat("fixture", 3f);
                markDirty.invoke(null, market, accessibilityDirty, priorityNormal);
                Object staleBatch = prepareTrade.invoke(manager, market);
                accessibility.modifyFlat("fixture", 4f);
                markDirty.invoke(null, market, accessibilityDirty, priorityNormal);
                require(!(Boolean) commitPreparedSnapshots.invoke(
                                manager, List.of(staleBatch)),
                        "trade manager published a batch after its scalar/token proof became stale");
                Object stillPublished = tradeManagerType
                        .getMethod("getMarketData", MarketAPI.class)
                        .invoke(manager, market);
                require(Float.floatToIntBits(
                                        stillPublished.getClass().getField("weight")
                                                .getFloat(stillPublished))
                                == Float.floatToIntBits(200f),
                        "failed proof validation partially published the stale trade candidate");
                Object freshBatch = prepareTrade.invoke(manager, market);
                require((Boolean) commitPreparedSnapshots.invoke(manager, List.of(freshBatch)),
                        "fresh scalar/token proof could not publish after stale rejection");

                Field committed = availableType.getDeclaredField("supplyDemandData");
                committed.setAccessible(true);
                committed.set(second.available, null);
                commodities.clear();
                commodities.add((CommodityOnMarketAPI) second.commodity);
                industrySnapshotReads.set(0);
                industrySupplyReads.set(0);
                industryDemandReads.set(0);
                Object missingHolderCapture = prepare.invoke(null, market);
                require("COMMODITY_STATE_MISSING".equals(
                                missingHolderCapture.getClass().getField("fallbackReason")
                                        .get(missingHolderCapture).toString()),
                        "missing holder did not select its explicit live fallback");
                require(industrySnapshotReads.get() == 1
                                && industrySupplyReads.get() == 1
                                && industryDemandReads.get() == 1,
                        "missing holder performed more than one live industry scan");
                committed.set(second.available, second.data);
                commodities.clear();
                commodities.add((CommodityOnMarketAPI) first.commodity);
                commodities.add((CommodityOnMarketAPI) second.commodity);

                Class<?> preparedType = Class.forName(
                        SUPPLY_DEMAND + "$PreparedRefresh", false, loader);
                Class<?> statusType = Class.forName(
                        SUPPLY_DEMAND + "$PreparedRefresh$Status", false, loader);
                Constructor<?> preparedConstructor = preparedType.getDeclaredConstructor(
                        dataType,
                        MarketAPI.class,
                        long.class,
                        int.class,
                        int.class,
                        int.class,
                        ArrayList.class,
                        LinkedHashMap.class,
                        LinkedHashMap.class,
                        statusType);
                preparedConstructor.setAccessible(true);
                Object generationless = preparedConstructor.newInstance(
                        first.data,
                        market,
                        0L,
                        7,
                        2,
                        0,
                        privateField(dataType, first.data, "stagingIndustries"),
                        privateField(dataType, first.data, "stagingDemandUnitsFromIndustries"),
                        privateField(dataType, first.data, "stagingSupplyUnitsFromIndustries"),
                        statusType.getField("READY").get(null));
                require((Boolean) dataType
                                .getMethod("commitPreparedRefresh", preparedType)
                                .invoke(first.data, generationless),
                        "generationless aggregate fixture did not commit");
                long staleCollision = (Long) dataType
                        .getMethod("getRawNetExportForGeneration", long.class)
                        .invoke(first.data, repairGeneration);
                require(staleCollision == Long.MIN_VALUE,
                        "generationless commit retained a stale positive authoritative stamp");

                accessibility.modifyFlat("fixture", 5f);
                markDirty.invoke(null, market, accessibilityDirty, priorityNormal);
                Object preRebuildBatch = prepareTrade.invoke(manager, market);
                Object publishedBeforeRebuild = tradeManagerType
                        .getMethod("getMarketData", MarketAPI.class)
                        .invoke(manager, market);
                int publishedWeightBeforeRebuild = Float.floatToIntBits(
                        publishedBeforeRebuild.getClass().getField("weight")
                                .getFloat(publishedBeforeRebuild));
                long oldRegistryToken =
                        (Long) getMaterializedGeneration.invoke(null, market);
                registryType.getMethod("clear").invoke(null);
                registryType.getMethod("replaceAllMarkets", Map.class)
                        .invoke(null, Map.of("post-immigration-fast-path", market));
                long rebuiltRegistryToken =
                        (Long) getMaterializedGeneration.invoke(null, market);
                require(rebuiltRegistryToken > 0L && rebuiltRegistryToken != oldRegistryToken,
                        "clear/replace reused a materialized token from the previous registry");
                require(!(Boolean) commitPreparedSnapshots.invoke(
                                manager, List.of(preRebuildBatch)),
                        "clear/replace with the same market object preserved an old registry proof");
                Object publishedAfterRebuild = tradeManagerType
                        .getMethod("getMarketData", MarketAPI.class)
                        .invoke(manager, market);
                require(Float.floatToIntBits(
                                        publishedAfterRebuild.getClass().getField("weight")
                                                .getFloat(publishedAfterRebuild))
                                == publishedWeightBeforeRebuild,
                        "registry rebuild proof rejection partially published the stale candidate");
            } finally {
                installSettingsStub();
            }
        }
    }

    private static void verifyMappingOnlyRollback(
            Class<?> tradeManagerType,
            Method prepareTrade,
            Method commitPreparedSnapshots,
            MarketAPI market) throws Exception {
        Object manager = tradeManagerType.getConstructor().newInstance();
        Field mappingsField = tradeManagerType.getDeclaredField("marketFactionById");
        Field factionsField = tradeManagerType.getDeclaredField("factionsTradeData");
        mappingsField.setAccessible(true);
        factionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> mappings = (Map<String, String>) mappingsField.get(manager);
        mappings.put(market.getId(), market.getFactionId());

        Object prepared = prepareTrade.invoke(manager, market);
        FailOnceLinkedHashMap<Object, Object> failingFactions =
                new FailOnceLinkedHashMap<>(Map.of());
        failingFactions.arm();
        factionsField.set(manager, failingFactions);
        try {
            commitPreparedSnapshots.invoke(manager, List.of(prepared));
            throw new AssertionError("intentional manager publication failure was not observed");
        } catch (InvocationTargetException expected) {
            require(expected.getCause() instanceof IllegalStateException,
                    "manager publication failure changed type: " + expected.getCause());
        }
        require(market.getFactionId().equals(mappings.get(market.getId()))
                        && failingFactions.isEmpty(),
                "rollback lost a valid market/faction mapping that had no prior snapshot");
    }

    private static TradeCommodity createTradeCommodity(
            Class<?> commodityType,
            Class<?> availableType,
            Class<?> dataType,
            String commodityId,
            int supply,
            int demand) throws Exception {
        Object commodity = U.allocateInstance(commodityType);
        Object available = availableType.getConstructor(float.class).newInstance(0f);
        Object data = dataType.getConstructor(String.class).newInstance(commodityId);
        dataType.getField("supply").setInt(data, supply);
        dataType.getField("demand").setInt(data, demand);
        Field committed = availableType.getDeclaredField("supplyDemandData");
        committed.setAccessible(true);
        committed.set(available, data);
        commodityVar("available", MutableStatWithTempMods.class).set(commodity, available);
        commodityVar("commodityId", String.class).set(commodity, commodityId);
        return new TradeCommodity(commodity, available, data);
    }

    private static MutableCommodityQuantity quantity(String commodityId, int value) {
        MutableCommodityQuantity quantity = new MutableCommodityQuantity(commodityId);
        MutableStat stat = quantity.getQuantity();
        stat.modifyFlat("fixture", value);
        return quantity;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> capturedNetProduction(Object capture) throws Exception {
        Object data = capture.getClass().getField("data").get(capture);
        return (Map<String, Integer>) data.getClass()
                .getField("netProductionValues").get(data);
    }

    private static Object privateField(Class<?> type, Object target, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void auditFutureOverrideFallbacks(Path jar) throws Exception {
        byte[] futureReach = addReachGroupOverride(readClass(jar, REACH));
        Class<?> reach = defineOwnedReplacement(jar, REACH, futureReach);
        require(!StarsectorPrepatcherEconomyHotpathRuntime
                        .isReachEconomyClassEligible(reach),
                "future AoTD getMarketsInGroup override did not fail closed");

        byte[] futureCommodity = renameAvailableAccessor(readClass(jar, COMMODITY));
        Class<?> commodity = defineOwnedReplacement(jar, COMMODITY, futureCommodity);
        require(!StarsectorPrepatcherEconomyHotpathRuntime
                        .isAoTDCommodityClassEligible(commodity),
                "future AoTD committed-state accessor change did not fail closed");
    }

    private static void auditClassLoaderRetention(Path jar) throws Exception {
        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        RetentionRefs refs = classifyInDisposableLoader(jar, queue);
        for (int attempt = 0;
             attempt < 120 && (refs.loader.get() != null
                     || refs.commodity.get() != null || refs.reach.get() != null);
             attempt++) {
            System.gc();
            System.runFinalization();
            byte[][] pressure = new byte[8][];
            for (int i = 0; i < pressure.length; i++) pressure[i] = new byte[256 * 1024];
            queue.remove(10L);
        }
        require(refs.loader.get() == null,
                "economy hotpath ClassValue retained AoTD classloader");
        require(refs.commodity.get() == null && refs.reach.get() == null,
                "economy hotpath ClassValue retained AoTD Class");
    }

    private static RetentionRefs classifyInDisposableLoader(
            Path jar, ReferenceQueue<Object> queue) throws Exception {
        URLClassLoader loader = new URLClassLoader(
                new URL[]{jar.toUri().toURL()},
                EconomyHotpathAoTDForkCompatibilityTest.class.getClassLoader());
        Class<?> commodity = Class.forName(COMMODITY, false, loader);
        Class<?> reach = Class.forName(REACH, false, loader);
        require(StarsectorPrepatcherEconomyHotpathRuntime
                        .isAoTDCommodityClassEligible(commodity)
                        && StarsectorPrepatcherEconomyHotpathRuntime
                        .isReachEconomyClassEligible(reach),
                "disposable AoTD classes were not eligible");
        RetentionRefs refs = new RetentionRefs(
                new WeakReference<>(loader, queue),
                new WeakReference<>(commodity, queue),
                new WeakReference<>(reach, queue));
        commodity = null;
        reach = null;
        loader.close();
        loader = null;
        return refs;
    }

    private static void auditLocalResourcesInheritance(ClassNode node) {
        require(findMethod(node, "shouldHaveCommodity",
                        "(Lcom/fs/starfarer/api/campaign/econ/CommodityOnMarketAPI;)Z") == null,
                node.name + " unexpectedly overrides shouldHaveCommodity");
        require(findMethod(node, "getStockpileLimit",
                        "(Lcom/fs/starfarer/api/campaign/econ/CommodityOnMarketAPI;)I") != null,
                node.name + " no longer owns its AoTD stockpile calculation");
        MethodNode tooltip = requireMethod(node, "createTooltipAfterDescription",
                "(Lcom/fs/starfarer/api/ui/TooltipMakerAPI;Z)V");
        require(countCalls(tooltip, Opcodes.INVOKESTATIC,
                        LOCAL_RESOURCES_TOOLTIP.replace('.', '/'), "render",
                        "(Lcom/fs/starfarer/api/impl/campaign/submarkets/"
                                + "LocalResourcesSubmarketPlugin;"
                                + "Lcom/fs/starfarer/api/ui/TooltipMakerAPI;"
                                + "Ldata/kaysaar/aotd/tot/scripts/submarket/aotd/"
                                + "AoTDLocalResourcesTooltipSnapshot$LimitResolver;)V") == 1,
                node.name + " does not delegate tooltip rendering to the snapshot helper");
        require(countCalls(tooltip, Opcodes.INVOKESTATIC,
                        "java/util/Collections", "sort",
                        "(Ljava/util/List;Ljava/util/Comparator;)V") == 0,
                node.name + " retained economic comparator work");
    }

    private static void auditLocalResourcesTooltipSnapshot(Path jar) throws Exception {
        ClassNode helper = readNode(jar, LOCAL_RESOURCES_TOOLTIP);
        MethodNode render = requireMethod(helper, "render",
                "(Lcom/fs/starfarer/api/impl/campaign/submarkets/"
                        + "LocalResourcesSubmarketPlugin;"
                        + "Lcom/fs/starfarer/api/ui/TooltipMakerAPI;"
                        + "Ldata/kaysaar/aotd/tot/scripts/submarket/aotd/"
                        + "AoTDLocalResourcesTooltipSnapshot$LimitResolver;)V");
        require(countCalls(render, Opcodes.INVOKEINTERFACE,
                        LOCAL_RESOURCES_TOOLTIP.replace('.', '/') + "$LimitResolver",
                        "getStockpileLimit",
                        "(Lcom/fs/starfarer/api/campaign/econ/CommodityOnMarketAPI;)I") == 1,
                "AoTD tooltip snapshot does not calculate each row through one resolver site");
        require(countCalls(render, Opcodes.INVOKEVIRTUAL,
                        "com/fs/starfarer/api/impl/campaign/submarkets/"
                                + "LocalResourcesSubmarketPlugin",
                        "getStockpileLimit",
                        "(Lcom/fs/starfarer/api/campaign/econ/CommodityOnMarketAPI;)I") == 0,
                "AoTD tooltip comparator retained virtual stockpile calculations");

        MethodNode peek = requireMethod(helper, "peekAoTDStockpileLimit",
                "(Ldata/kaysaar/aotd/tot/scripts/commoditydata/AoTDCommodityOnMarket;"
                        + "Ljava/util/Map;)Ljava/lang/Integer;");
        require(countCalls(peek, Opcodes.INVOKEVIRTUAL,
                        COMMODITY.replace('.', '/'), "peekSupplyDemandData",
                        "()Ldata/kaysaar/aotd/tot/scripts/commoditydata/"
                                + "AoTDSupplyDemandData;") == 1,
                "AoTD tooltip does not use the read-only committed-state accessor");
        require(countCalls(peek, Opcodes.INVOKEVIRTUAL,
                        COMMODITY.replace('.', '/'), "getSupplyDemandData",
                        "()Ldata/kaysaar/aotd/tot/scripts/commoditydata/"
                                + "AoTDSupplyDemandData;") == 0,
                "AoTD tooltip can materialize supply/demand data");

        ClassNode commodity = readNode(jar, COMMODITY);
        MethodNode commodityPeek = requireMethod(commodity, "peekSupplyDemandData",
                "()Ldata/kaysaar/aotd/tot/scripts/commoditydata/AoTDSupplyDemandData;");
        require(countCalls(commodityPeek, Opcodes.INVOKEVIRTUAL,
                        AVAILABLE.replace('.', '/'), "peekSupplyDemandData",
                        "()Ldata/kaysaar/aotd/tot/scripts/commoditydata/"
                                + "AoTDSupplyDemandData;") == 1,
                "AoTD commodity peek no longer delegates to the read-only stat accessor");
    }

    private static void requireVanillaReachDeclaration(
            Class<?> type, String name, Class<?>... parameters) throws Exception {
        require(type.getMethod(name, parameters).getDeclaringClass() == ReachEconomy.class,
                "AoTD ReachEconomy overrides critical method " + name);
    }

    private static Class<?> defineOwnedReplacement(Path jar, String name, byte[] bytes)
            throws Exception {
        URLClassLoader dependencies = new URLClassLoader(
                new URL[]{jar.toUri().toURL()},
                EconomyHotpathAoTDForkCompatibilityTest.class.getClassLoader());
        ClassLoader loader = new ClassLoader(dependencies) {
            @Override
            protected Class<?> loadClass(String requested, boolean resolve)
                    throws ClassNotFoundException {
                synchronized (getClassLoadingLock(requested)) {
                    Class<?> loaded = findLoadedClass(requested);
                    if (loaded == null && name.equals(requested)) {
                        loaded = defineClass(requested, bytes, 0, bytes.length);
                    }
                    if (loaded == null) loaded = super.loadClass(requested, false);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
        };
        return Class.forName(name, false, loader);
    }

    private static byte[] addReachGroupOverride(byte[] original) {
        ClassNode node = readNode(original);
        require(findMethod(node, "getMarketsInGroup",
                        "(Ljava/lang/String;)Ljava/util/List;") == null,
                "AoTD reach fixture already overrides getMarketsInGroup");
        MethodNode method = new MethodNode(Opcodes.ASM8, Opcodes.ACC_PUBLIC,
                "getMarketsInGroup", "(Ljava/lang/String;)Ljava/util/List;", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                node.superName, "getMarketsInGroup",
                "(Ljava/lang/String;)Ljava/util/List;", false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 2;
        method.maxLocals = 2;
        node.methods.add(method);
        return write(node);
    }

    private static byte[] renameAvailableAccessor(byte[] original) {
        ClassNode node = readNode(original);
        MethodNode method = requireMethod(node, "getAoTDAvailableStat",
                "()Ldata/kaysaar/aotd/tot/scripts/commoditydata/AoTDAvailableStat;");
        method.name = "spp$changedGetAoTDAvailableStat";
        return write(node);
    }

    private static ClassNode readNode(Path jar, String binaryName) throws Exception {
        return readNode(readClass(jar, binaryName));
    }

    private static ClassNode readNode(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM8);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] readClass(Path jar, String binaryName) throws Exception {
        String entryName = binaryName.replace('.', '/') + ".class";
        try (JarFile input = new JarFile(jar.toFile())) {
            var entry = input.getJarEntry(entryName);
            require(entry != null, "AoTD class missing: " + entryName);
            try (InputStream stream = input.getInputStream(entry)) {
                return stream.readAllBytes();
            }
        }
    }

    private static MethodNode requireMethod(ClassNode node, String name, String desc) {
        MethodNode method = findMethod(node, name, desc);
        require(method != null, "method missing: " + node.name + '.' + name + desc);
        return method;
    }

    private static MethodNode findMethod(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) return method;
        }
        return null;
    }

    private static int countCalls(
            MethodNode method, int opcode, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == opcode
                    && owner.equals(call.owner) && name.equals(call.name)
                    && desc.equals(call.desc)) count++;
        }
        return count;
    }

    private static int countNamedCalls(MethodNode method, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call && name.equals(call.name)) count++;
        }
        return count;
    }

    private static int countAddAllWhoseNearestPriorCallIs(
            MethodNode method, String priorCallName) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEINTERFACE
                    || !"java/util/List".equals(call.owner)
                    || !"addAll".equals(call.name)
                    || !"(Ljava/util/Collection;)Z".equals(call.desc)) {
                continue;
            }
            for (AbstractInsnNode prior = instruction.getPrevious();
                 prior != null; prior = prior.getPrevious()) {
                if (prior instanceof MethodInsnNode priorCall) {
                    if (priorCallName.equals(priorCall.name)) count++;
                    break;
                }
            }
        }
        return count;
    }

    private static Object allocate(Class<?> type) throws InstantiationException {
        return U.allocateInstance(type);
    }

    private static VarHandle commodityVar(String name, Class<?> type) throws Exception {
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                CommodityOnMarket.class, MethodHandles.lookup());
        return lookup.findVarHandle(CommodityOnMarket.class, name, type);
    }

    private static void installConversionSettings(Object calculator, float econUnit) {
        CommoditySpecAPI commoditySpec = (CommoditySpecAPI) Proxy.newProxyInstance(
                EconomyHotpathAoTDForkCompatibilityTest.class.getClassLoader(),
                new Class<?>[]{CommoditySpecAPI.class},
                (proxy, method, args) -> {
                    if ("getEconUnit".equals(method.getName())) return econUnit;
                    if ("getId".equals(method.getName())) return "test";
                    return defaultValue(proxy, method, args);
                });
        SettingsAPI settings = (SettingsAPI) Proxy.newProxyInstance(
                EconomyHotpathAoTDForkCompatibilityTest.class.getClassLoader(),
                new Class<?>[]{SettingsAPI.class},
                (proxy, method, args) -> {
                    if ("getInstanceOfScript".equals(method.getName())) return calculator;
                    if ("getCommoditySpec".equals(method.getName())) return commoditySpec;
                    if ("getFloat".equals(method.getName())) return 1f;
                    if ("getInt".equals(method.getName())) return 1;
                    return defaultValue(proxy, method, args);
                });
        Global.setSettings(settings);
    }

    private static void installSettingsStub() {
        SettingsAPI settings = (SettingsAPI) Proxy.newProxyInstance(
                EconomyHotpathAoTDForkCompatibilityTest.class.getClassLoader(),
                new Class<?>[]{SettingsAPI.class},
                (proxy, method, args) -> {
                    if ("getFloat".equals(method.getName())) return 1f;
                    if ("getInt".equals(method.getName())) return 1;
                    return defaultValue(proxy, method, args);
                });
        Global.setSettings(settings);
    }


    private static void configureHooks() throws Exception {
        Method method = StarsectorPrepatcherHooks.class.getDeclaredMethod(
                "configure", PrepatcherConfig.class, Path.class);
        method.setAccessible(true);
        method.invoke(null, config(), Path.of("."));
    }

    private static PrepatcherConfig config() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("patch.localResourcesNoColdMarketData", "true");
        properties.setProperty("patch.economyGroupIndex", "true");
        properties.setProperty("observer.marketConstructionDiagnostics", "false");
        properties.setProperty("patch.directMarketObservation", "false");
        properties.setProperty("logging.statsIntervalSeconds", "0");
        Constructor<PrepatcherConfig> constructor =
                PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);
        return constructor.newInstance(properties);
    }

    private static Object defaultValue(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "proxy@" + Integer.toHexString(
                    System.identityHashCode(proxy));
            default -> primitiveDefault(method.getReturnType());
        };
    }

    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }

    private static Unsafe unsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private record RetentionRefs(WeakReference<Object> loader,
                                 WeakReference<Object> commodity,
                                 WeakReference<Object> reach) {}

    private static final class TradeCommodity {
        private final Object commodity;
        private final Object available;
        private final Object data;

        private TradeCommodity(Object commodity, Object available, Object data) {
            this.commodity = commodity;
            this.available = available;
            this.data = data;
        }
    }

    private static final class UnknownCommodity extends CommodityOnMarket {
        private UnknownCommodity() { super(null, "test"); }
    }

    private static final class UnknownReach extends ReachEconomy {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
