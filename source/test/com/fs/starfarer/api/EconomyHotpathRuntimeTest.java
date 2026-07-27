package com.fs.starfarer.api;

import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.campaign.econ.CommodityOnMarket;
import com.fs.starfarer.campaign.econ.reach.CommodityMarketData;
import com.fs.starfarer.campaign.econ.reach.MarketShareData;
import com.fs.starfarer.campaign.econ.reach.ReachEconomy;
import com.starsector.prepatcher.agent.PrepatcherConfig;
import sun.misc.Unsafe;

import java.lang.ref.WeakReference;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** Runtime equivalence, invalidation and retention tests for economy hot-path patches P0-P2. */
public final class EconomyHotpathRuntimeTest {
    private static final Unsafe U = unsafe();

    private EconomyHotpathRuntimeTest() {}

    public static void main(String[] args) throws Exception {
        installSettingsStub();
        StarsectorPrepatcherHooks.configure(config(true), Path.of("."));

        verifyLocalResourcesColdPredicate();
        verifyLocalResourcesWarmAndForeignFallback();
        verifyEconomyGroupIndexMutationAndAudit();
        verifyEconomyGroupIndexDoesNotCreateStaticObjectRoots();

        System.out.println("OK economy-hotpath-runtime"
                + " p0-cold-peek p0-warm-equivalence p0-foreign-fallback"
                + " p2-copy-order-epoch-audit-bounded-keys gc-no-static-root");
    }

    private static void verifyLocalResourcesColdPredicate() throws Exception {
        MarketAPI illegalMarket = market("illegal", null, false, null, true, null);
        boolean[][] inputs = {
                {false, false},
                {true, false},
                {false, true},
                {true, true}
        };
        boolean[] expected = {false, true, true, true};

        for (int i = 0; i < inputs.length; i++) {
            CommodityOnMarket commodity = coldCommodity(
                    inputs[i][0], inputs[i][0] ? 3 : 0,
                    inputs[i][1], inputs[i][1] ? 4 : 0);
            boolean actual = StarsectorPrepatcherHooks.localResourcesShouldHaveCommodity(
                    illegalMarket, commodity);
            require(actual == expected[i],
                    "P0 cold legality predicate mismatch at row " + i);
            require(commodityVar("commodityMarketData", CommodityMarketData.class)
                            .get(commodity) == null,
                    "P0 cold path materialized CommodityMarketData");
        }

        MarketAPI legalMarket = market("legal", null, false, null, false, null);
        CommodityOnMarket empty = coldCommodity(false, 0, false, 0);
        require(StarsectorPrepatcherHooks.localResourcesShouldHaveCommodity(
                        legalMarket, empty),
                "P0 changed the vanilla legal-market fast path");
    }

    private static void verifyLocalResourcesWarmAndForeignFallback() throws Exception {
        MarketAPI market = market("warm", null, false, null, true, null);
        IdentityHashMap<MarketAPI, Integer> warmShares = new IdentityHashMap<>();
        warmShares.put(market, 0);
        CommodityMarketData data = exactData(List.of(market), warmShares);
        MarketShareData shareData = marketShareData(data, market);

        CommodityOnMarket commodity = coldCommodity(false, 0, false, 0);
        commodityVar("commodityMarketData", CommodityMarketData.class)
                .set(commodity, data);

        shareData.setSourceIsIllegal(false);
        require(StarsectorPrepatcherHooks.localResourcesShouldHaveCommodity(
                        market, commodity),
                "P0 warm path changed sourceIsIllegal=false semantics");
        shareData.setSourceIsIllegal(true);
        require(!StarsectorPrepatcherHooks.localResourcesShouldHaveCommodity(
                        market, commodity),
                "P0 warm path changed sourceIsIllegal=true semantics");

        int[] getterCalls = {0};
        CommodityOnMarketAPI foreign = (CommodityOnMarketAPI) Proxy.newProxyInstance(
                EconomyHotpathRuntimeTest.class.getClassLoader(),
                new Class<?>[]{CommodityOnMarketAPI.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getCommodityMarketData")) {
                        getterCalls[0]++;
                        return data;
                    }
                    return defaultValue(proxy, method, args);
                });
        shareData.setSourceIsIllegal(false);
        require(StarsectorPrepatcherHooks.localResourcesShouldHaveCommodity(
                        market, foreign),
                "P0 foreign CommodityOnMarketAPI fallback changed result");
        require(getterCalls[0] == 1,
                "P0 foreign implementation did not retain the vanilla getter fallback");
    }

    private static void verifyEconomyGroupIndexMutationAndAudit() throws Exception {
        StarsectorPrepatcherHooks.registerEconomyGroupIndexComponent(1);
        StarsectorPrepatcherHooks.registerEconomyGroupIndexComponent(2);

        ReachEconomy economy = new ReachEconomy();
        MutableMarketFixture a1 = mutableMarket("a1", "A");
        MutableMarketFixture b1 = mutableMarket("b1", "B");
        MutableMarketFixture a2 = mutableMarket("a2", "A");
        economy.getMarkets().add(a1.market);
        economy.getMarkets().add(b1.market);
        economy.getMarkets().add(a2.market);

        Object engineMarker = new Object();
        activateCampaign(economy, engineMarker, 41L);
        Object state = StarsectorPrepatcherHooks.newPersistentSnapshotState();

        List<?> first = StarsectorPrepatcherHooks.borrowMarketsInGroupIndexed(
                economy, state, "A");
        requireIdentityList(first, a1.market, a2.market);
        first.clear();
        List<?> second = StarsectorPrepatcherHooks.borrowMarketsInGroupIndexed(
                economy, state, "A");
        requireIdentityList(second, a1.market, a2.market);
        require(first != second,
                "P2 returned a shared mutable cached list");

        StarsectorPrepatcherHooks.markEconomyGroupStructureChanged(state);
        require(groupIndexKeyCount(state) == 0,
                "P2 owner-local mutation barrier did not release indexed references immediately");
        requireIdentityList(StarsectorPrepatcherHooks.borrowMarketsInGroupIndexed(
                economy, state, "A"), a1.market, a2.market);

        int groupsBeforeUnknownQueries = groupIndexKeyCount(state);
        for (int i = 0; i < 128; i++) {
            List<?> unknown = StarsectorPrepatcherHooks.borrowMarketsInGroupIndexed(
                    economy, state, "missing-" + i);
            require(unknown != null && unknown.isEmpty(),
                    "P2 unknown group did not match vanilla empty-list behavior");
        }
        require(groupIndexKeyCount(state) == groupsBeforeUnknownQueries,
                "P2 retained arbitrary unknown group query strings");

        // Direct field/proxy mutation bypasses Market.setEconGroup; zero audit
        // interval must still detect it on the next request.
        b1.group = "A";
        List<?> audited = StarsectorPrepatcherHooks.borrowMarketsInGroupIndexed(
                economy, state, "A");
        requireIdentityList(audited, a1.market, b1.market, a2.market);

        // Direct source reorder also bypasses standard mutators and must be audited.
        MarketAPI firstMarket = economy.getMarkets().remove(0);
        economy.getMarkets().add(firstMarket);
        List<?> reordered = StarsectorPrepatcherHooks.borrowMarketsInGroupIndexed(
                economy, state, "A");
        requireIdentityList(reordered, b1.market, a2.market, a1.market);

        MutableMarketFixture b2 = mutableMarket("b2", "B");
        economy.getMarkets().add(b2.market);
        StarsectorPrepatcherHooks.markEconomyGroupStructureChanged();
        List<?> epochInvalidated = StarsectorPrepatcherHooks.borrowMarketsInGroupIndexed(
                economy, state, "B");
        requireIdentityList(epochInvalidated, b2.market);

        StarsectorPrepatcherHooks.configure(config(false), Path.of("."));
        require(StarsectorPrepatcherHooks.borrowMarketsInGroupIndexed(
                        economy, state, "A") == null,
                "P2 disabled configuration did not fail closed to vanilla");
        StarsectorPrepatcherHooks.configure(config(true), Path.of("."));
    }

    private static void verifyEconomyGroupIndexDoesNotCreateStaticObjectRoots()
            throws Exception {
        WeakReference<?>[] references = createIndexedGraphAndDropStrongReferences();
        awaitCollected(references);
    }

    private static WeakReference<?>[] createIndexedGraphAndDropStrongReferences()
            throws Exception {
        ReachEconomy economy = new ReachEconomy();
        Object engineMarker = new Object();
        OwnerBackReference handler = new OwnerBackReference(economy, "cycle", "C");
        MarketAPI market = (MarketAPI) Proxy.newProxyInstance(
                EconomyHotpathRuntimeTest.class.getClassLoader(),
                new Class<?>[]{MarketAPI.class}, handler);
        economy.getMarkets().add(market);
        Object state = StarsectorPrepatcherHooks.newPersistentSnapshotState();
        activateCampaign(economy, engineMarker, 99L);
        require(StarsectorPrepatcherHooks.borrowMarketsInGroupIndexed(
                        economy, state, "C") != null,
                "P2 retention fixture did not build an index");

        WeakReference<Object> engineRef = new WeakReference<>(engineMarker);
        WeakReference<ReachEconomy> economyRef = new WeakReference<>(economy);
        WeakReference<MarketAPI> marketRef = new WeakReference<>(market);
        WeakReference<Object> stateRef = new WeakReference<>(state);
        WeakReference<Object> handlerRef = new WeakReference<>(handler);
        return new WeakReference<?>[]{engineRef, economyRef, marketRef, stateRef, handlerRef};
    }

    private static void activateCampaign(ReachEconomy economy, Object engine, long generation)
            throws Exception {
        setStatic(StarsectorPrepatcherHooks.class, "campaignCacheGeneration", generation);
        setStatic(StarsectorPrepatcherHooks.class, "campaignCacheGenerationActive", true);
        setStatic(StarsectorPrepatcherHooks.class, "campaignEngineObserved", true);
        setStatic(StarsectorPrepatcherHooks.class, "activeCampaignEngine",
                new WeakReference<>(engine));
        setStatic(StarsectorPrepatcherHooks.class, "activeReachEconomy",
                new WeakReference<>(economy));
    }

    private static int groupIndexKeyCount(Object state) throws Exception {
        Object map = field(state.getClass(), "groupIndexByGroup").get(state);
        return map == null ? 0 : ((Map<?, ?>) map).size();
    }

    private static Map<FactionAPI, Integer> vanillaFactionAggregation(
            List<MarketAPI> markets, IdentityHashMap<MarketAPI, Integer> shares) {
        LinkedHashMap<FactionAPI, Integer> result = new LinkedHashMap<>();
        for (MarketAPI market : markets) {
            FactionAPI representative = market.getFaction();
            if (result.containsKey(representative)) continue;
            int total = 0;
            for (MarketAPI candidate : markets) {
                if (candidate.getFaction() == representative
                        || (representative.isPlayerFaction()
                        && candidate.isPlayerOwned())) {
                    total += shares.get(candidate);
                }
            }
            result.put(representative, total);
        }
        return result;
    }

    private static CommodityOnMarket coldCommodity(
            boolean demandLegal, int maxDemand,
            boolean supplyLegal, int maxSupply) throws Exception {
        CommodityOnMarket commodity =
                (CommodityOnMarket) U.allocateInstance(CommodityOnMarket.class);
        commodityVar("isDemandLegal", boolean.class).set(commodity, demandLegal);
        commodityVar("maxDemand", int.class).set(commodity, maxDemand);
        commodityVar("isSupplyLegal", boolean.class).set(commodity, supplyLegal);
        commodityVar("maxSupply", int.class).set(commodity, maxSupply);
        commodityVar("commodityMarketData", CommodityMarketData.class)
                .set(commodity, null);
        return commodity;
    }

    private static CommodityMarketData exactData(
            List<MarketAPI> markets, Map<MarketAPI, Integer> shares) throws Exception {
        CommodityMarketData data =
                (CommodityMarketData) U.allocateInstance(CommodityMarketData.class);
        set(data, "econGroup", "test-group");
        LinkedHashMap<MarketAPI, MarketShareData> marketShareData = new LinkedHashMap<>();
        for (MarketAPI market : markets) {
            MarketShareData entry = new MarketShareData();
            Integer share = shares.get(market);
            entry.setExportMarketShare((share == null ? 0 : share) / 100f);
            marketShareData.put(market, entry);
        }
        set(data, "marketShareData", marketShareData);
        installSectorMarkets(markets);
        return data;
    }

    @SuppressWarnings("unchecked")
    private static MarketShareData marketShareData(
            CommodityMarketData data, MarketAPI market) throws Exception {
        Map<MarketAPI, MarketShareData> values =
                (Map<MarketAPI, MarketShareData>) field(
                        CommodityMarketData.class, "marketShareData").get(data);
        return values.get(market);
    }

    private static void installSectorMarkets(List<MarketAPI> markets) {
        EconomyAPI economy = (EconomyAPI) Proxy.newProxyInstance(
                EconomyHotpathRuntimeTest.class.getClassLoader(),
                new Class<?>[]{EconomyAPI.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getMarketsInGroup")) {
                        return new ArrayList<>(markets);
                    }
                    return defaultValue(proxy, method, args);
                });
        SectorAPI sector = (SectorAPI) Proxy.newProxyInstance(
                EconomyHotpathRuntimeTest.class.getClassLoader(),
                new Class<?>[]{SectorAPI.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getEconomy")) return economy;
                    return defaultValue(proxy, method, args);
                });
        Global.setSector(sector);
    }

    private static FakeCommodityMarketData fakeData(
            List<MarketAPI> markets, Map<MarketAPI, Integer> shares) throws Exception {
        FakeCommodityMarketData data =
                (FakeCommodityMarketData) U.allocateInstance(FakeCommodityMarketData.class);
        set(data, "testMarkets", markets);
        set(data, "testShares", shares);
        set(data, "exportCalls", 0);
        set(data, "fallbackCalls", 0);
        return data;
    }

    private static FactionAPI faction(String id, String equalityKey, boolean player) {
        return (FactionAPI) Proxy.newProxyInstance(
                EconomyHotpathRuntimeTest.class.getClassLoader(),
                new Class<?>[]{FactionAPI.class},
                new FactionHandler(id, equalityKey, player));
    }

    private static MarketAPI market(String id, FactionAPI faction, boolean playerOwned,
                                    String group, boolean illegal, Object owner) {
        return (MarketAPI) Proxy.newProxyInstance(
                EconomyHotpathRuntimeTest.class.getClassLoader(),
                new Class<?>[]{MarketAPI.class},
                new MarketHandler(id, faction, playerOwned, group, illegal, owner));
    }

    private static MutableMarketFixture mutableMarket(String id, String group) {
        MutableMarketFixture fixture = new MutableMarketFixture(id, group);
        fixture.market = (MarketAPI) Proxy.newProxyInstance(
                EconomyHotpathRuntimeTest.class.getClassLoader(),
                new Class<?>[]{MarketAPI.class}, fixture);
        return fixture;
    }

    private static Object defaultValue(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        if (name.equals("equals")) return proxy == args[0];
        if (name.equals("hashCode")) return System.identityHashCode(proxy);
        if (name.equals("toString")) return "proxy@" + Integer.toHexString(
                System.identityHashCode(proxy));
        Class<?> type = method.getReturnType();
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

    private static void installSettingsStub() {
        SettingsAPI settings = (SettingsAPI) Proxy.newProxyInstance(
                EconomyHotpathRuntimeTest.class.getClassLoader(),
                new Class<?>[]{SettingsAPI.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getFloat")) return 1f;
                    if (method.getName().equals("getInt")) return 1;
                    return defaultValue(proxy, method, args);
                });
        Global.setSettings(settings);
    }

    private static PrepatcherConfig config(boolean economyGroupIndex) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("patch.localResourcesNoColdMarketData", "true");
        properties.setProperty("patch.marketShareFactionAggregation", "true");
        properties.setProperty("patch.economyGroupIndex",
                Boolean.toString(economyGroupIndex));
        properties.setProperty("economy.structureAuditMs", "0");
        properties.setProperty("observer.marketConstructionDiagnostics", "false");
        properties.setProperty("patch.directMarketObservation", "false");
        properties.setProperty("logging.statsIntervalSeconds", "0");
        Constructor<PrepatcherConfig> constructor =
                PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);
        return constructor.newInstance(properties);
    }

    private static void requireIdentityList(List<?> actual, Object... expected) {
        require(actual != null, "P2 unexpectedly selected the vanilla fallback");
        require(actual.size() == expected.length,
                "P2 group size mismatch: expected=" + expected.length
                        + " actual=" + actual.size());
        for (int i = 0; i < expected.length; i++) {
            require(actual.get(i) == expected[i],
                    "P2 changed source order/identity at " + i);
        }
    }

    private static void awaitCollected(WeakReference<?>[] references) throws Exception {
        for (int attempt = 0; attempt < 160; attempt++) {
            System.gc();
            System.runFinalization();
            byte[][] pressure = new byte[8][];
            for (int i = 0; i < pressure.length; i++) pressure[i] = new byte[256 * 1024];
            boolean allCollected = true;
            for (WeakReference<?> reference : references) {
                if (reference.get() != null) {
                    allCollected = false;
                    break;
                }
            }
            if (allCollected) return;
            Thread.sleep(5L);
        }
        StringBuilder retained = new StringBuilder();
        for (int i = 0; i < references.length; i++) {
            Object value = references[i].get();
            if (value != null) retained.append(i).append(':')
                    .append(value.getClass().getName()).append(' ');
        }
        throw new AssertionError("P2 indexed graph remained strongly rooted: " + retained);
    }

    private static VarHandle commodityVar(String name, Class<?> type) throws Exception {
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                CommodityOnMarket.class, MethodHandles.lookup());
        return lookup.findVarHandle(CommodityOnMarket.class, name, type);
    }

    private static int intField(Object owner, String name) throws Exception {
        return field(owner.getClass(), name).getInt(owner);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = field(owner.getClass(), name);
        if (field.getType() == boolean.class) {
            field.setBoolean(owner, (Boolean) value);
        } else if (field.getType() == int.class) {
            field.setInt(owner, (Integer) value);
        } else if (field.getType() == long.class) {
            field.setLong(owner, (Long) value);
        } else {
            field.set(owner, value);
        }
    }

    private static void setStatic(Class<?> owner, String name, Object value) throws Exception {
        Field field = field(owner, name);
        if (field.getType() == boolean.class) {
            field.setBoolean(null, (Boolean) value);
        } else if (field.getType() == long.class) {
            field.setLong(null, (Long) value);
        } else if (field.getType() == int.class) {
            field.setInt(null, (Integer) value);
        } else {
            field.set(null, value);
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Continue through the hierarchy.
            }
        }
        throw new NoSuchFieldException(type.getName() + '.' + name);
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeCommodityMarketData extends CommodityMarketData {
        private List<MarketAPI> testMarkets;
        private Map<MarketAPI, Integer> testShares;
        private int exportCalls;
        private int fallbackCalls;

        private FakeCommodityMarketData() {
            super("unused", null);
            throw new AssertionError("Unsafe allocation expected");
        }

        @Override
        public List<MarketAPI> getMarkets() {
            return testMarkets;
        }

        @Override
        public int getExportMarketSharePercent(MarketAPI market) {
            exportCalls++;
            return testShares.get(market);
        }

        @Override
        public int getMarketSharePercent(FactionAPI faction) {
            fallbackCalls++;
            int total = 0;
            for (MarketAPI market : testMarkets) {
                int share = getExportMarketSharePercent(market);
                if (market.getFaction() == faction
                        || (faction.isPlayerFaction() && market.isPlayerOwned())) {
                    total += share;
                }
            }
            return total;
        }
    }

    private static final class FactionHandler implements InvocationHandler {
        private final String id;
        private final String equalityKey;
        private final boolean player;

        private FactionHandler(String id, String equalityKey, boolean player) {
            this.id = id;
            this.equalityKey = equalityKey;
            this.player = player;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getId" -> id;
                case "isPlayerFaction" -> player;
                case "hashCode" -> equalityKey.hashCode();
                case "equals" -> args != null && args.length == 1
                        && args[0] != null
                        && Proxy.isProxyClass(args[0].getClass())
                        && Proxy.getInvocationHandler(args[0]) instanceof FactionHandler other
                        && Objects.equals(equalityKey, other.equalityKey);
                case "toString" -> id;
                default -> defaultValue(proxy, method, args);
            };
        }
    }

    private static class MarketHandler implements InvocationHandler {
        private final String id;
        private final FactionAPI faction;
        private final boolean playerOwned;
        private final String group;
        private final boolean illegal;
        @SuppressWarnings("unused")
        private final Object owner;

        private MarketHandler(String id, FactionAPI faction, boolean playerOwned,
                              String group, boolean illegal, Object owner) {
            this.id = id;
            this.faction = faction;
            this.playerOwned = playerOwned;
            this.group = group;
            this.illegal = illegal;
            this.owner = owner;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getId" -> id;
                case "getFaction" -> faction;
                case "getFactionId" -> faction == null ? null : faction.getId();
                case "isPlayerOwned" -> playerOwned;
                case "getEconGroup" -> group;
                case "isIllegal" -> illegal;
                default -> defaultValue(proxy, method, args);
            };
        }
    }

    private static final class MutableMarketFixture implements InvocationHandler {
        private final String id;
        private String group;
        private MarketAPI market;

        private MutableMarketFixture(String id, String group) {
            this.id = id;
            this.group = group;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getId" -> id;
                case "getEconGroup" -> group;
                default -> defaultValue(proxy, method, args);
            };
        }
    }

    /** Creates a real cycle market -> handler -> ReachEconomy for GC verification. */
    private static final class OwnerBackReference implements InvocationHandler {
        @SuppressWarnings("unused")
        private final ReachEconomy owner;
        private final String id;
        private final String group;

        private OwnerBackReference(ReachEconomy owner, String id, String group) {
            this.owner = owner;
            this.id = id;
            this.group = group;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getId" -> id;
                case "getEconGroup" -> group;
                default -> defaultValue(proxy, method, args);
            };
        }
    }
}
