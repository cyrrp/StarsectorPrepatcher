package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.StarsectorPrepatcherEconomyHotpathRuntime;
import com.fs.starfarer.api.StarsectorPrepatcherHooks;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.campaign.econ.reach.ReachEconomy;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

/** Actual-javaagent smoke for the owner-local ReachEconomy group index. */
public final class EconomyHotpathActualAgentSmokeTest {
    private EconomyHotpathActualAgentSmokeTest() {}

    public static void main(String[] args) throws Exception {
        Field stateField = ReachEconomy.class.getDeclaredField("spp$econGroupIndexState");
        stateField.setAccessible(true);
        int modifiers = stateField.getModifiers();
        require(Modifier.isPrivate(modifiers), "econ-group state field is not private");
        require(Modifier.isTransient(modifiers), "econ-group state field is not transient");
        require(stateField.isSynthetic(), "econ-group state field is not synthetic");
        require(stateField.getType() == Object.class, "econ-group state field type changed");

        StarsectorPrepatcherHooks.registerEconomyGroupIndexComponent(1);
        StarsectorPrepatcherHooks.registerEconomyGroupIndexComponent(2);
        verifyWrapperAndImmediateRemovalRelease(stateField);
        verifyOwnerCycleCollects(stateField);
        boolean aotd = args.length > 0 && "aotd".equals(args[0]);
        if (aotd) verifyAoTDInheritedWrapper(stateField);

        System.out.println("OK economy-hotpath-actual-agent"
                + " transient-owner-field indexed-wrapper mutable-copy"
                + " remove-releases-immediately owner-cycle-collects"
                + (aotd ? " aotd-inherited-indexed-wrapper" : ""));
    }

    private static void verifyWrapperAndImmediateRemovalRelease(Field stateField)
            throws Exception {
        ReachEconomy economy = new ReachEconomy();
        Object engine = new Object();
        MarketFixture a1 = new MarketFixture("a1", "A", economy);
        MarketFixture b1 = new MarketFixture("b1", "B", economy);
        MarketFixture a2 = new MarketFixture("a2", "A", economy);
        economy.addMarket(a1.market);
        economy.addMarket(b1.market);
        economy.addMarket(a2.market);
        activate(economy, engine, 71L);

        List<?> first = economy.getMarketsInGroup("A");
        requireIdentityList(first, a1.market, a2.market);
        Object state = stateField.get(economy);
        require(state != null, "econ-group wrapper did not install owner-local state");
        require(groupIndexMarkets(state) != null,
                "econ-group wrapper did not build the owner-local index");

        first.clear();
        requireIdentityList(economy.getMarketsInGroup("A"), a1.market, a2.market);

        economy.removeMarket(a1.market);
        require(groupIndexMarkets(state) == null,
                "econ-group removeMarket retained the removed market until a future query");
        requireIdentityList(economy.getMarketsInGroup("A"), a2.market);

        economy.addMarket(a1.market);
        require(groupIndexMarkets(state) == null,
                "econ-group addMarket did not invalidate owner-local arrays immediately");
        requireIdentityList(economy.getMarketsInGroup("A"), a2.market, a1.market);
    }

    private static void verifyAoTDInheritedWrapper(Field stateField) throws Exception {
        Class<?> type = Class.forName(
                "data.kaysaar.aotd.tot.scripts.economy.AoTDReachEconomy");
        require(StarsectorPrepatcherEconomyHotpathRuntime
                        .isReachEconomyClassEligible(type),
                "exact AoTDReachEconomy did not pass the econ-group runtime contract");
        ReachEconomy economy = (ReachEconomy) type.getConstructor().newInstance();
        Object engine = new Object();
        MarketFixture a1 = new MarketFixture("aotd-a1", "A", economy);
        MarketFixture b1 = new MarketFixture("aotd-b1", "B", economy);
        MarketFixture a2 = new MarketFixture("aotd-a2", "A", economy);
        economy.addMarket(a1.market);
        economy.addMarket(b1.market);
        economy.addMarket(a2.market);
        activate(economy, engine, 73L);

        requireIdentityList(economy.getMarketsInGroup("A"), a1.market, a2.market);
        Object state = stateField.get(economy);
        require(state != null && groupIndexMarkets(state) != null,
                "exact AoTDReachEconomy inherited wrapper did not build econ-group state");
        economy.removeMarket(a1.market);
        require(groupIndexMarkets(state) == null,
                "AoTDReachEconomy inherited removeMarket did not release econ-group arrays");
        requireIdentityList(economy.getMarketsInGroup("A"), a2.market);
    }

    private static void verifyOwnerCycleCollects(Field stateField) throws Exception {
        WeakReference<?>[] references = createIndexedOwnerCycle(stateField);
        awaitCollected(references);
    }

    private static WeakReference<?>[] createIndexedOwnerCycle(Field stateField)
            throws Exception {
        ReachEconomy economy = new ReachEconomy();
        Object engine = new Object();
        MarketFixture cycle = new MarketFixture("cycle", "C", economy);
        economy.addMarket(cycle.market);
        activate(economy, engine, 72L);
        requireIdentityList(economy.getMarketsInGroup("C"), cycle.market);
        Object state = stateField.get(economy);
        require(state != null && groupIndexMarkets(state) != null,
                "econ-group GC fixture did not build owner-local state");

        WeakReference<Object> engineRef = new WeakReference<>(engine);
        WeakReference<ReachEconomy> economyRef = new WeakReference<>(economy);
        WeakReference<MarketAPI> marketRef = new WeakReference<>(cycle.market);
        WeakReference<Object> stateRef = new WeakReference<>(state);
        WeakReference<Object> handlerRef = new WeakReference<>(cycle);
        return new WeakReference<?>[]{engineRef, economyRef, marketRef, stateRef, handlerRef};
    }

    private static void activate(ReachEconomy economy, Object engine, long generation)
            throws Exception {
        setStatic("campaignCacheGeneration", generation);
        setStatic("campaignCacheGenerationActive", true);
        setStatic("campaignEngineObserved", true);
        setStatic("activeCampaignEngine", new WeakReference<>(engine));
        setStatic("activeReachEconomy", new WeakReference<>(economy));
    }

    private static Object groupIndexMarkets(Object state) throws Exception {
        Field field = findField(state.getClass(), "groupIndexMarkets");
        return field.get(state);
    }

    private static void setStatic(String name, Object value) throws Exception {
        Field field = findField(StarsectorPrepatcherHooks.class, name);
        if (field.getType() == boolean.class) {
            field.setBoolean(null, (Boolean) value);
        } else if (field.getType() == long.class) {
            field.setLong(null, (Long) value);
        } else {
            field.set(null, value);
        }
    }

    private static Field findField(Class<?> type, String name) throws Exception {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Continue.
            }
        }
        throw new NoSuchFieldException(type.getName() + '.' + name);
    }

    private static void requireIdentityList(List<?> actual, Object... expected) {
        require(actual.size() == expected.length,
                "econ-group list size mismatch: expected=" + expected.length
                        + " actual=" + actual.size());
        for (int i = 0; i < expected.length; i++) {
            require(actual.get(i) == expected[i],
                    "econ-group index changed market identity/order at " + i);
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
        throw new AssertionError("econ-group transformed owner cycle remained rooted: " + retained);
    }

    private static Object defaultValue(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "market@" + Integer.toHexString(
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** Handler deliberately retains its ReachEconomy owner to form a real cycle. */
    private static final class MarketFixture implements InvocationHandler {
        private final String id;
        private final String group;
        @SuppressWarnings("unused")
        private final ReachEconomy owner;
        private final MarketAPI market;

        private MarketFixture(String id, String group, ReachEconomy owner) {
            this.id = id;
            this.group = group;
            this.owner = owner;
            this.market = (MarketAPI) Proxy.newProxyInstance(
                    EconomyHotpathActualAgentSmokeTest.class.getClassLoader(),
                    new Class<?>[]{MarketAPI.class}, this);
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
