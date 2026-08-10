package com.fs.starfarer.api;

import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.starsector.prepatcher.agent.PrepatcherConfig;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

/** Exact industry wrappers preserve calls and publish one-shot local contexts atomically. */
public final class IndustryMutationRuntimeTest {
    private IndustryMutationRuntimeTest() {}

    public static void main(String[] args) throws Exception {
        Path configFile = Files.createTempFile("prepatcher-industry-mutation", ".properties");
        try {
            Files.writeString(configFile,
                    "patch.uiMarketMutationRefresh=true\n",
                    StandardCharsets.UTF_8);
            StarsectorPrepatcherRuntimeBridge.configure(
                    PrepatcherConfig.load(configFile), configFile.getParent());
            StarsectorPrepatcherRuntimeBridge.setAoTDEconomyRestoreCompletionContract(
                    true, "runtime-test");
            long negotiated = StarsectorPrepatcherRuntimeBridge.registerAoTDForkContract(
                    "aotd_theory_of_toolbox",
                    StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_FORK_VERSION,
                    StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_DECLARED_CAPABILITIES,
                    ignored -> { }, (industry, ids) -> null, () -> { });
            require((negotiated & StarsectorPrepatcherRuntimeBridge
                    .AOTD_CAPABILITY_UI_MARKET_MUTATION_REFRESH) != 0L,
                    "UI market-mutation capability missing");

            installSettingsStub();
            AtomicInteger removes = new AtomicInteger();
            MarketAPI market = (MarketAPI) Proxy.newProxyInstance(
                    IndustryMutationRuntimeTest.class.getClassLoader(),
                    new Class<?>[] {MarketAPI.class}, (proxy, method, methodArgs) -> {
                        return switch (method.getName()) {
                            case "getAllCommodities" -> Collections.emptyList();
                            case "removeIndustry" -> { removes.incrementAndGet(); yield null; }
                            case "reapplyConditions", "reapplyIndustries" -> null;
                            case "toString" -> "industry-test-market";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == methodArgs[0];
                            default -> defaultValue(method.getReturnType());
                        };
                    });

            AtomicInteger starts = new AtomicInteger();
            AtomicInteger downgrades = new AtomicInteger();
            AtomicInteger cancels = new AtomicInteger();
            Industry industry = (Industry) Proxy.newProxyInstance(
                    IndustryMutationRuntimeTest.class.getClassLoader(),
                    new Class<?>[] {Industry.class}, (proxy, method, methodArgs) -> {
                        return switch (method.getName()) {
                            case "getMarket" -> market;
                            case "startUpgrading" -> { starts.incrementAndGet(); yield null; }
                            case "downgrade" -> { downgrades.incrementAndGet(); yield null; }
                            case "cancelUpgrade" -> { cancels.incrementAndGet(); yield null; }
                            case "toString" -> "industry-test-industry";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == methodArgs[0];
                            default -> defaultValue(method.getReturnType());
                        };
                    });

            System.setProperty("starsector.prepatcher.industryMarketMutationPatchGroup",
                    "awaiting-targets");
            StarsectorPrepatcherRuntimeBridge
                    .applyVanillaIndustryStartUpgrading(industry);
            require(starts.get() == 1, "startUpgrading was not invoked exactly once");
            require(takeContext(market) == null,
                    "partial industry patch group published a context");

            System.setProperty("starsector.prepatcher.industryMarketMutationPatchGroup", "ready");
            StarsectorPrepatcherRuntimeBridge
                    .applyVanillaIndustryStartUpgrading(industry);
            require(starts.get() == 2, "active startUpgrading was not invoked exactly once");
            requirePayload(market,
                    StarsectorPrepatcherRuntimeBridge.MUTATION_REASON_INDUSTRY_QUEUE);

            StarsectorPrepatcherRuntimeBridge.applyVanillaIndustryDowngrade(industry);
            require(downgrades.get() == 1, "downgrade was not invoked exactly once");
            requirePayload(market,
                    StarsectorPrepatcherRuntimeBridge.MUTATION_REASON_INDUSTRY_STRUCTURE);

            StarsectorPrepatcherRuntimeBridge
                    .applyVanillaIndustryCancelUpgrade(industry);
            require(cancels.get() == 1, "cancelUpgrade was not invoked exactly once");
            requirePayload(market,
                    StarsectorPrepatcherRuntimeBridge.MUTATION_REASON_INDUSTRY_QUEUE);

            StarsectorPrepatcherRuntimeBridge.applyVanillaIndustryRemoval(
                    market, "industry", null, false);
            require(removes.get() == 1, "removeIndustry was not invoked exactly once");
            requirePayload(market,
                    StarsectorPrepatcherRuntimeBridge.MUTATION_REASON_INDUSTRY_STRUCTURE);

            AtomicInteger tripleSteps = new AtomicInteger();
            EconomyAPI fallbackEconomy = economyProxy(tripleSteps);

            AtomicInteger preReads = new AtomicInteger();
            MarketAPI preFailureMarket = mutationMarket(preReads, 1);
            AtomicInteger preStarts = new AtomicInteger();
            AtomicInteger preCancels = new AtomicInteger();
            Industry preFailureIndustry = mutationIndustry(
                    preFailureMarket, preStarts, new AtomicInteger(), preCancels, null);
            StarsectorPrepatcherRuntimeBridge
                    .applyVanillaIndustryStartUpgrading(preFailureIndustry);
            require(preStarts.get() == 1,
                    "throwing industry pre-snapshot suppressed or duplicated mutation");
            // A later successful record in the same batch must retain the first
            // mutation's global fallback requirement.
            StarsectorPrepatcherRuntimeBridge
                    .applyVanillaIndustryCancelUpgrade(preFailureIndustry);
            require(preCancels.get() == 1,
                    "later industry mutation was not invoked exactly once");
            invokeTripleGuarded(fallbackEconomy, preFailureMarket);
            require(tripleSteps.get() == 1,
                    "industry pre-snapshot failure lost sticky global fallback");

            AtomicInteger postReads = new AtomicInteger();
            MarketAPI postFailureMarket = mutationMarket(postReads, 2);
            AtomicInteger postDowngrades = new AtomicInteger();
            Industry postFailureIndustry = mutationIndustry(
                    postFailureMarket, new AtomicInteger(), postDowngrades,
                    new AtomicInteger(), null);
            StarsectorPrepatcherRuntimeBridge
                    .applyVanillaIndustryDowngrade(postFailureIndustry);
            require(postDowngrades.get() == 1,
                    "throwing industry post-snapshot suppressed or duplicated mutation");
            invokeTripleGuarded(fallbackEconomy, postFailureMarket);
            require(tripleSteps.get() == 2,
                    "industry post-snapshot failure did not retain tripleStep");

            AtomicInteger unknownStarts = new AtomicInteger();
            RuntimeException lookupFailure = new RuntimeException("synthetic market lookup");
            Industry unknownMarketIndustry = mutationIndustry(
                    null, unknownStarts, new AtomicInteger(), new AtomicInteger(), lookupFailure);
            StarsectorPrepatcherRuntimeBridge
                    .applyVanillaIndustryStartUpgrading(unknownMarketIndustry);
            require(unknownStarts.get() == 1,
                    "throwing industry market lookup suppressed original mutation");
            invokeTripleGuarded(fallbackEconomy, market);
            require(tripleSteps.get() == 3,
                    "unknown-market industry failure did not poison next shared guard");

            RuntimeException mutationFailure =
                    new RuntimeException("synthetic original industry mutation");
            Industry throwingMutation = mutationIndustry(
                    market, new AtomicInteger(), new AtomicInteger(),
                    new AtomicInteger(), mutationFailure);
            try {
                StarsectorPrepatcherRuntimeBridge
                        .applyVanillaIndustryStartUpgrading(throwingMutation);
                throw new AssertionError("original industry exception was swallowed");
            } catch (RuntimeException actual) {
                require(actual == mutationFailure,
                        "industry wrapper changed original exception identity");
            }
        } finally {
            Files.deleteIfExists(configFile);
        }
        System.out.println("OK industry wrappers: call-through/local payload + pre/post/lookup "
                + "fail-open, sticky tripleStep, original exception semantics");
    }

    /**
     * The Industry API exposes MarketCMD.RaidDangerLevel in its method surface.
     * JDK dynamic-proxy generation resolves that type and initializes Misc, which
     * expects Global settings even though this fixture never calls a raid method.
     */
    private static void installSettingsStub() {
        SettingsAPI settings = (SettingsAPI) Proxy.newProxyInstance(
                IndustryMutationRuntimeTest.class.getClassLoader(),
                new Class<?>[] {SettingsAPI.class}, (proxy, method, methodArgs) -> {
                    return switch (method.getName()) {
                        case "getFloat" -> 1f;
                        case "getInt" -> 1;
                        case "toString" -> "industry-test-settings";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == methodArgs[0];
                        default -> defaultValue(method.getReturnType());
                    };
                });
        Global.setSettings(settings);
    }

    private static void requirePayload(MarketAPI market, int expectedReason) {
        Object[] context = takeContext(market);
        require(context != null && context.length == 6, "mutation context missing");
        int reason = ((Integer) context[1]).intValue();
        int scope = ((Integer) context[2]).intValue();
        require((reason & expectedReason) != 0, "industry reason missing");
        require((scope & StarsectorPrepatcherRuntimeBridge
                .REFRESH_SCOPE_INDUSTRY_STATE) != 0,
                "industry-state scope missing");
        require((scope & StarsectorPrepatcherRuntimeBridge
                .REFRESH_SCOPE_GLOBAL_TOPOLOGY) == 0,
                "successful local mutation unexpectedly forced global fallback");
        require(((String[]) context[5]).length == 0,
                "empty commodity vector produced affected IDs");
        require(takeContext(market) == null,
                "industry context was not one-shot");
    }

    private static void invokeTripleGuarded(EconomyAPI economy, MarketAPI market) {
        if (!StarsectorPrepatcherRuntimeBridge
                .shouldHandleVanillaUiMutationEconomyStep(economy, market)) {
            economy.tripleStep();
        }
    }

    private static EconomyAPI economyProxy(AtomicInteger tripleSteps) {
        return (EconomyAPI) Proxy.newProxyInstance(
                IndustryMutationRuntimeTest.class.getClassLoader(),
                new Class<?>[] {EconomyAPI.class}, (proxy, method, methodArgs) -> {
                    if ("tripleStep".equals(method.getName())) {
                        tripleSteps.incrementAndGet();
                        return null;
                    }
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "industry-fallback-economy";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == methodArgs[0];
                            default -> null;
                        };
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static MarketAPI mutationMarket(
            AtomicInteger commodityReads, int throwingRead) {
        return (MarketAPI) Proxy.newProxyInstance(
                IndustryMutationRuntimeTest.class.getClassLoader(),
                new Class<?>[] {MarketAPI.class}, (proxy, method, methodArgs) -> {
                    return switch (method.getName()) {
                        case "getAllCommodities" -> {
                            int read = commodityReads.incrementAndGet();
                            if (read == throwingRead) {
                                throw new IllegalStateException(
                                        "synthetic industry snapshot failure #" + read);
                            }
                            yield Collections.emptyList();
                        }
                        case "reapplyConditions", "reapplyIndustries" -> null;
                        case "toString" -> "throwing-industry-market";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == methodArgs[0];
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }

    private static Industry mutationIndustry(
            MarketAPI market, AtomicInteger starts, AtomicInteger downgrades,
            AtomicInteger cancels, RuntimeException failure) {
        return (Industry) Proxy.newProxyInstance(
                IndustryMutationRuntimeTest.class.getClassLoader(),
                new Class<?>[] {Industry.class}, (proxy, method, methodArgs) -> {
                    return switch (method.getName()) {
                        case "getMarket" -> {
                            if (market == null && failure != null
                                    && "synthetic market lookup".equals(failure.getMessage())) {
                                throw failure;
                            }
                            yield market;
                        }
                        case "startUpgrading" -> {
                            starts.incrementAndGet();
                            if (failure != null
                                    && !"synthetic market lookup".equals(failure.getMessage())) {
                                throw failure;
                            }
                            yield null;
                        }
                        case "downgrade" -> {
                            downgrades.incrementAndGet();
                            yield null;
                        }
                        case "cancelUpgrade" -> {
                            cancels.incrementAndGet();
                            yield null;
                        }
                        case "toString" -> "throwing-industry";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == methodArgs[0];
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }

    private static Object[] takeContext(Object market) {
        try {
            Method take = StarsectorPrepatcherRuntimeBridge.class.getDeclaredMethod(
                    "takeUiMarketMutationContext", Object.class);
            take.setAccessible(true);
            return (Object[]) take.invoke(null, market);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("context consume failed", failure);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
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
