package com.fs.starfarer.api;

import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.starsector.prepatcher.agent.PrepatcherConfig;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import sun.misc.Unsafe;

/** Pre-twConfirm weak token: one-shot, identity, thread and epoch semantics. */
public final class TradeMutationPreparationRuntimeTest {
    private TradeMutationPreparationRuntimeTest() {}

    public static void main(String[] args) throws Exception {
        Path configFile = Files.createTempFile("prepatcher-trade-mutation", ".properties");
        try {
            Files.writeString(configFile,
                    "patch.uiMarketMutationRefresh=true\n"
                            + "patch.marketScheduler=false\n",
                    StandardCharsets.UTF_8);
            StarsectorPrepatcherRuntimeBridge.configure(
                    PrepatcherConfig.load(configFile), configFile.getParent());
            long negotiated = StarsectorPrepatcherRuntimeBridge.registerAoTDForkContract(
                    "aotd_theory_of_toolbox",
                    StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_FORK_VERSION,
                    StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_DECLARED_CAPABILITIES,
                    ignored -> { }, (industry, ids) -> null);
            require((negotiated & StarsectorPrepatcherRuntimeBridge
                            .AOTD_CAPABILITY_UI_MARKET_MUTATION_REFRESH) != 0L,
                    "UI market-mutation capability was not negotiated");

            MarketAPI market = marketProxy("market-a");
            MarketAPI other = marketProxy("market-b");
            Method take = StarsectorPrepatcherRuntimeBridge.class.getDeclaredMethod(
                    "takePreparedTradeMutation", Object.class);
            take.setAccessible(true);
            Method preparePolicy = StarsectorPrepatcherRuntimeBridge.class.getDeclaredMethod(
                    "prepareUiMarketMutation", MarketAPI.class);
            preparePolicy.setAccessible(true);

            StarsectorPrepatcherRuntimeBridge
                    .prepareTradeMarketMutation(market);
            require((Boolean) take.invoke(null, market),
                    "same-market prepared trade token was not consumed");
            require(!((Boolean) take.invoke(null, market)),
                    "prepared trade token was not one-shot");

            StarsectorPrepatcherRuntimeBridge
                    .prepareTradeMarketMutation(market);
            require(!((Boolean) take.invoke(null, other)),
                    "different market consumed prepared trade token");

            StarsectorPrepatcherRuntimeBridge
                    .prepareTradeMarketMutation(market);
            AtomicBoolean crossThread = new AtomicBoolean(true);
            Thread thread = new Thread(() -> {
                try {
                    crossThread.set((Boolean) take.invoke(null, market));
                } catch (ReflectiveOperationException failure) {
                    throw new RuntimeException(failure);
                }
            });
            thread.start();
            thread.join();
            require(!crossThread.get(), "cross-thread trade token was visible");
            require((Boolean) take.invoke(null, market),
                    "cross-thread probe corrupted owning-thread token");

            StarsectorPrepatcherRuntimeBridge
                    .prepareTradeMarketMutation(market);
            StarsectorPrepatcherRuntimeBridge.publishAoTDRuntimeEpoch(2L, 2L);
            require(!((Boolean) take.invoke(null, market)),
                    "runtime epoch change did not invalidate trade token");

            StarsectorPrepatcherRuntimeBridge
                    .prepareTradeMarketMutation(market);
            preparePolicy.invoke(null, market);
            require(!((Boolean) take.invoke(null, market)),
                    "unrelated policy mutation did not clear stale trade token");

            AtomicInteger doubleSteps = new AtomicInteger();
            EconomyAPI economy = economyProxy(doubleSteps);
            StarsectorPrepatcherRuntimeBridge.prepareTradeMarketMutation(market);
            boolean handled = StarsectorPrepatcherRuntimeBridge
                    .shouldHandleTradeMarketMutationEconomyStep(
                            economy, market,
                            throwingTransaction(market, Failure.MARKET, null, null));
            require(!handled, "throwing transaction market getter was handled");
            require(doubleSteps.get() == 0,
                    "trade guard invoked doubleStep instead of returning false");
            economy.doubleStep();
            require(doubleSteps.get() == 1,
                    "throwing transaction market getter did not run original doubleStep once");
            require(!((Boolean) take.invoke(null, market)),
                    "throwing transaction market getter retained the prepared token");

            int expectedSteps = 1;
            for (TradeFailure fixture : List.of(
                    new TradeFailure("bought getter", Failure.BOUGHT, null, null),
                    new TradeFailure("sold getter", Failure.SOLD, null, null),
                    new TradeFailure("getStacksCopy", Failure.NONE,
                            cargoThrowing("getStacksCopy"), null),
                    new TradeFailure("isCommodityStack", Failure.NONE,
                            cargoWithStackThrowing("isCommodityStack"), null),
                    new TradeFailure("getSize", Failure.NONE,
                            cargoWithStackThrowing("getSize"), null),
                    new TradeFailure("getCommodityId", Failure.NONE,
                            cargoWithStackThrowing("getCommodityId"), null))) {
                StarsectorPrepatcherRuntimeBridge.prepareTradeMarketMutation(market);
                invokeGuarded(economy, market, throwingTransaction(
                        market, fixture.failure, fixture.bought, fixture.sold));
                expectedSteps++;
                require(doubleSteps.get() == expectedSteps,
                        "throwing " + fixture.label
                                + " did not run original doubleStep exactly once");
                require(!((Boolean) take.invoke(null, market)),
                        "throwing " + fixture.label + " retained the prepared token");
            }

            require(!StarsectorPrepatcherRuntimeBridge
                            .shouldHandleTradeMarketMutationEconomyStep(
                                    null, market,
                                    throwingTransaction(
                                            market, Failure.NONE, null, null)),
                    "null economy was incorrectly handled instead of preserving invoke semantics");
        } finally {
            Files.deleteIfExists(configFile);
        }
        System.out.println("OK trade guard preserves doubleStep across transaction/cargo/stack "
                + "inspection failures + token identity/thread/epoch/isolation");
    }

    private static EconomyAPI economyProxy(AtomicInteger doubleSteps) {
        return (EconomyAPI) Proxy.newProxyInstance(
                EconomyAPI.class.getClassLoader(),
                new Class<?>[] {EconomyAPI.class},
                (proxy, method, args) -> {
                    if ("doubleStep".equals(method.getName())) {
                        doubleSteps.incrementAndGet();
                        return null;
                    }
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "economy-proxy";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == byte.class) return (byte) 0;
                    if (type == short.class) return (short) 0;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == float.class) return 0f;
                    if (type == double.class) return 0d;
                    if (type == char.class) return '\0';
                    return null;
                });
    }

    private static void invokeGuarded(
            EconomyAPI economy, MarketAPI market, PlayerMarketTransaction transaction) {
        if (!StarsectorPrepatcherRuntimeBridge
                .shouldHandleTradeMarketMutationEconomyStep(economy, market, transaction)) {
            economy.doubleStep();
        }
    }

    private static ThrowingTransaction throwingTransaction(
            MarketAPI market, Failure failure, CargoAPI bought, CargoAPI sold) throws Exception {
        ThrowingTransaction transaction = (ThrowingTransaction)
                unsafe().allocateInstance(ThrowingTransaction.class);
        transaction.expectedMarket = market;
        transaction.failure = failure;
        transaction.bought = bought;
        transaction.sold = sold;
        return transaction;
    }

    private static CargoAPI cargoThrowing(String methodName) {
        return (CargoAPI) Proxy.newProxyInstance(
                CargoAPI.class.getClassLoader(), new Class<?>[] {CargoAPI.class},
                (proxy, method, args) -> {
                    if (methodName.equals(method.getName())) {
                        throw new IllegalStateException("synthetic " + methodName + " failure");
                    }
                    return defaultValue(proxy, method, args, "cargo-proxy");
                });
    }

    private static CargoAPI cargoWithStackThrowing(String methodName) {
        CargoStackAPI stack = (CargoStackAPI) Proxy.newProxyInstance(
                CargoStackAPI.class.getClassLoader(), new Class<?>[] {CargoStackAPI.class},
                (proxy, method, args) -> {
                    if (methodName.equals(method.getName())) {
                        throw new IllegalStateException("synthetic " + methodName + " failure");
                    }
                    return switch (method.getName()) {
                        case "isCommodityStack" -> true;
                        case "getSize" -> 1f;
                        case "getCommodityId" -> "ore";
                        default -> defaultValue(proxy, method, args, "stack-proxy");
                    };
                });
        return (CargoAPI) Proxy.newProxyInstance(
                CargoAPI.class.getClassLoader(), new Class<?>[] {CargoAPI.class},
                (proxy, method, args) -> "getStacksCopy".equals(method.getName())
                        ? List.of(stack)
                        : defaultValue(proxy, method, args, "cargo-proxy"));
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static final class ThrowingTransaction extends PlayerMarketTransaction {
        private MarketAPI expectedMarket;
        private Failure failure;
        private CargoAPI bought;
        private CargoAPI sold;

        private ThrowingTransaction() {
            super(null, null, null);
        }

        @Override
        public MarketAPI getMarket() {
            if (failure == Failure.MARKET) {
                throw new IllegalStateException("synthetic market failure");
            }
            return expectedMarket;
        }

        @Override
        public CargoAPI getBought() {
            if (failure == Failure.BOUGHT) {
                throw new IllegalStateException("synthetic bought failure");
            }
            return bought;
        }

        @Override
        public CargoAPI getSold() {
            if (failure == Failure.SOLD) {
                throw new IllegalStateException("synthetic sold failure");
            }
            return sold;
        }
    }

    private enum Failure { NONE, MARKET, BOUGHT, SOLD }

    private record TradeFailure(
            String label, Failure failure, CargoAPI bought, CargoAPI sold) {}

    private static MarketAPI marketProxy(String label) {
        return (MarketAPI) Proxy.newProxyInstance(
                MarketAPI.class.getClassLoader(),
                new Class<?>[] {MarketAPI.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> label;
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == byte.class) return (byte) 0;
                    if (type == short.class) return (short) 0;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == float.class) return 0f;
                    if (type == double.class) return 0d;
                    if (type == char.class) return '\0';
                    return null;
                });
    }

    private static Object defaultValue(
            Object proxy, Method method, Object[] args, String label) {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> label;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            };
        }
        Class<?> type = method.getReturnType();
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return '\0';
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
