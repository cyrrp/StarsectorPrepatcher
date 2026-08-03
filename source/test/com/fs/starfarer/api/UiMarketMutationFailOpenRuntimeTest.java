package com.fs.starfarer.api;

import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.starsector.prepatcher.agent.PrepatcherConfig;

import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

/** Runtime fail-open boundary for setter snapshots, proof reads and diagnostics. */
public final class UiMarketMutationFailOpenRuntimeTest {
    private UiMarketMutationFailOpenRuntimeTest() {}

    public static void main(String[] args) throws Exception {
        Path configFile = Files.createTempFile("prepatcher-ui-mutation-fail-open", ".properties");
        try {
            Files.writeString(configFile,
                    "patch.uiMarketMutationRefresh=true\n"
                            + "patch.marketScheduler=false\n",
                    StandardCharsets.UTF_8);
            StarsectorPrepatcherRuntimeBridge.configure(
                    PrepatcherConfig.load(configFile), configFile.getParent());

            AtomicInteger tripleSteps = new AtomicInteger();
            EconomyAPI fallbackEconomy = economyProxy(tripleSteps);

            MutationMarket preFailure = new MutationMarket(1);
            PrintStream originalErr = System.err;
            RuntimeException loggingFailure = new RuntimeException("synthetic logger failure");
            try {
                System.setErr(new PrintStream(OutputStream.nullOutputStream()) {
                    @Override
                    public void println(String value) {
                        throw loggingFailure;
                    }
                });
                StarsectorPrepatcherRuntimeBridge.applyVanillaFreePortMutation(
                        preFailure.proxy, true);
            } finally {
                System.setErr(originalErr);
            }
            require(preFailure.freePortSets.get() == 1,
                    "throwing pre-snapshot suppressed or duplicated setFreePort");

            // A later successful setter in the same shared-helper batch must not
            // erase the earlier failure.
            StarsectorPrepatcherRuntimeBridge.applyVanillaImmigrationClosedMutation(
                    preFailure.proxy, true);
            require(preFailure.immigrationSets.get() == 1,
                    "later policy setter was not invoked exactly once");
            invokeTripleGuarded(fallbackEconomy, preFailure.proxy);
            require(tripleSteps.get() == 1,
                    "sticky pre-snapshot poison did not run original tripleStep once");

            MutationMarket postFailure = new MutationMarket(2);
            StarsectorPrepatcherRuntimeBridge.applyVanillaFreePortMutation(
                    postFailure.proxy, true);
            require(postFailure.freePortSets.get() == 1,
                    "throwing post-snapshot suppressed or duplicated setFreePort");
            invokeTripleGuarded(fallbackEconomy, postFailure.proxy);
            require(tripleSteps.get() == 2,
                    "post-snapshot failure did not run original tripleStep once");

            RuntimeException setterFailure = new RuntimeException("synthetic setter failure");
            MarketAPI throwingSetter = marketProxy((proxy, method, methodArgs) -> {
                if ("setFreePort".equals(method.getName())) throw setterFailure;
                if ("isFreePort".equals(method.getName())) return false;
                if ("getAllCommodities".equals(method.getName())) {
                    return Collections.emptyList();
                }
                return defaultValue(proxy, method, methodArgs, "throwing-setter-market");
            });
            try {
                StarsectorPrepatcherRuntimeBridge.applyVanillaFreePortMutation(
                        throwingSetter, true);
                throw new AssertionError("original setter exception was swallowed");
            } catch (RuntimeException actual) {
                require(actual == setterFailure,
                        "wrapper changed the original setter exception identity");
            }
        } finally {
            Files.deleteIfExists(configFile);
        }
        System.out.println("OK UI mutation fail-open: pre/post snapshots, sticky poison, "
                + "throwing diagnostics, original setter/step semantics");
    }

    private static void invokeTripleGuarded(EconomyAPI economy, MarketAPI market) {
        if (!StarsectorPrepatcherRuntimeBridge
                .shouldHandleVanillaUiMutationEconomyStep(economy, market)) {
            economy.tripleStep();
        }
    }

    private static EconomyAPI economyProxy(AtomicInteger tripleSteps) {
        return (EconomyAPI) Proxy.newProxyInstance(
                EconomyAPI.class.getClassLoader(), new Class<?>[] {EconomyAPI.class},
                (proxy, method, args) -> {
                    if ("tripleStep".equals(method.getName())) {
                        tripleSteps.incrementAndGet();
                        return null;
                    }
                    return defaultValue(proxy, method, args, "fallback-economy");
                });
    }

    private static final class MutationMarket {
        private final AtomicInteger commodityReads = new AtomicInteger();
        private final AtomicInteger freePortSets = new AtomicInteger();
        private final AtomicInteger immigrationSets = new AtomicInteger();
        private final int throwingRead;
        private final MarketAPI proxy;

        private MutationMarket(int throwingRead) {
            this.throwingRead = throwingRead;
            this.proxy = marketProxy((proxy, method, args) -> switch (method.getName()) {
                case "isFreePort" -> false;
                case "setFreePort" -> {
                    freePortSets.incrementAndGet();
                    yield null;
                }
                case "setImmigrationClosed" -> {
                    immigrationSets.incrementAndGet();
                    yield null;
                }
                case "getAllCommodities" -> {
                    int read = commodityReads.incrementAndGet();
                    if (read == throwingRead) {
                        throw new IllegalStateException(
                                "synthetic commodity snapshot failure #" + read);
                    }
                    yield Collections.emptyList();
                }
                case "reapplyConditions", "reapplyIndustries" -> null;
                default -> defaultValue(proxy, method, args, "mutation-market");
            });
        }
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(Object proxy, Method method, Object[] args) throws Throwable;
    }

    private static MarketAPI marketProxy(Invocation invocation) {
        return (MarketAPI) Proxy.newProxyInstance(
                MarketAPI.class.getClassLoader(), new Class<?>[] {MarketAPI.class},
                invocation::invoke);
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
