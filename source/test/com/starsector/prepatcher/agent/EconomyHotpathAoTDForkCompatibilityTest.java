package com.starsector.prepatcher.agent;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.StarsectorPrepatcherEconomyHotpathRuntime;
import com.fs.starfarer.api.StarsectorPrepatcherHooks;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.CommoditySpecAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.combat.MutableStatWithTempMods;
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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
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
        auditFutureOverrideFallbacks(jar);
        auditClassLoaderRetention(jar);

        System.out.println("OK economy-hotpath-aotd-fork"
                + " committed-converted-legality-no-materialization"
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

    private static final class UnknownCommodity extends CommodityOnMarket {
        private UnknownCommodity() { super(null, "test"); }
    }

    private static final class UnknownReach extends ReachEconomy {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
