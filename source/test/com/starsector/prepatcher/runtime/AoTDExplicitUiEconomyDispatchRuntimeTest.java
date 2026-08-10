package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.starsector.prepatcher.agent.PrepatcherConfig;

import data.kaysaar.aotd.tot.scripts.economy.AoTDEconomy;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Runtime contract coverage for the same-loader spp11 explicit UI dispatcher. */
public final class AoTDExplicitUiEconomyDispatchRuntimeTest {
    private enum Mode { CARGO }

    private AoTDExplicitUiEconomyDispatchRuntimeTest() {}

    public static void main(String[] args) throws Exception {
        Path configFile = Files.createTempFile("prepatcher-aotd-ui-dispatch", ".properties");
        try {
            Files.writeString(configFile,
                    "patch.campaignCargoNoGlobalEconomyStep=true\n"
                            + "patch.lootTransferNoGlobalEconomyStep=true\n"
                            + "patch.planetConditionMarketOpenNoGlobalEconomyStep=true\n"
                            + "patch.vanillaMarketOpenLocalization=true\n"
                            + "patch.uiMarketMutationRefresh=true\n",
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
            require(negotiated == StarsectorPrepatcherRuntimeBridge
                            .AOTD_CURRENT_DECLARED_CAPABILITIES,
                    "spp11 explicit dispatcher capabilities were not negotiated");

            MarketAPI market = marketFixture();
            AoTDEconomy economy = new AoTDEconomy();

            require(StarsectorPrepatcherRuntimeBridge
                            .shouldHandleVanillaMarketOpenEconomyStep(economy, market),
                    "market-open action was not dispatched");
            requireAction(economy, 1, market, 0L, null, "market-open");

            require(StarsectorPrepatcherRuntimeBridge.shouldSkipVanillaCargoEconomyStep(
                            economy, market, false, Mode.CARGO, null, null),
                    "live Cargo action was not dispatched");
            requireAction(economy, 2, market, 2L, null, "live Cargo");

            require(StarsectorPrepatcherRuntimeBridge.shouldSkipVanillaCargoEconomyStep(
                            economy, null, true, Mode.CARGO, null, null),
                    "synthetic Cargo action was not dispatched");
            requireAction(economy, 2, null, 1L, null, "synthetic Cargo");

            Method record = StarsectorPrepatcherRuntimeBridge.class.getDeclaredMethod(
                    "recordUiMarketMutation", Object.class, int.class, int.class,
                    String[].class);
            record.setAccessible(true);
            Method take = StarsectorPrepatcherRuntimeBridge.class.getDeclaredMethod(
                    "takeUiMarketMutationContext", Object.class);
            take.setAccessible(true);
            int reason = StarsectorPrepatcherRuntimeBridge.MUTATION_REASON_TRADE_TRANSACTION;
            int scope = StarsectorPrepatcherRuntimeBridge.REFRESH_SCOPE_LOCAL_COMMODITIES
                    | StarsectorPrepatcherRuntimeBridge
                    .REFRESH_SCOPE_AFFECTED_GLOBAL_COMMODITIES;
            record.invoke(null, market, reason, scope,
                    new String[] {"supplies", "ore", "ore", ""});
            require(StarsectorPrepatcherRuntimeBridge
                            .shouldHandleVanillaUiMutationEconomyStep(economy, market),
                    "targeted mutation action was not dispatched");
            requireAction(economy, 3, market,
                    ((long) reason << 32) | (scope & 0xffffffffL),
                    new String[] {"ore", "supplies"}, "targeted mutation");

            int beforeGlobal = economy.calls;
            record.invoke(null, market, reason,
                    scope | StarsectorPrepatcherRuntimeBridge.REFRESH_SCOPE_GLOBAL_TOPOLOGY,
                    new String[] {"ore"});
            require(!StarsectorPrepatcherRuntimeBridge
                            .shouldHandleVanillaUiMutationEconomyStep(economy, market),
                    "global-topology mutation suppressed its global fallback");
            require(economy.calls == beforeGlobal,
                    "global-topology mutation reached the local dispatcher");

            economy.result = false;
            int beforeRejected = economy.calls;
            require(!StarsectorPrepatcherRuntimeBridge
                            .shouldHandleVanillaMarketOpenEconomyStep(economy, market),
                    "dispatcher rejection suppressed the original global step");
            require(economy.calls == beforeRejected + 1,
                    "dispatcher rejection did not attempt the explicit action once");

            economy.result = true;
            economy.fail = true;
            require(!StarsectorPrepatcherRuntimeBridge
                            .shouldHandleVanillaMarketOpenEconomyStep(economy, market),
                    "dispatcher exception suppressed the original global step");

            FutureAoTDEconomy future = new FutureAoTDEconomy();
            require(!StarsectorPrepatcherRuntimeBridge
                            .shouldHandleVanillaMarketOpenEconomyStep(future, market),
                    "future AoTD subclass received exact-fork UI semantics");
            require(future.calls == 0,
                    "future AoTD subclass reached the exact dispatcher");

            record.invoke(null, market, reason, scope, new String[] {"ore"});
            require(!StarsectorPrepatcherRuntimeBridge
                            .shouldHandleVanillaUiMutationEconomyStep(future, market),
                    "future AoTD subclass received exact mutation semantics");
            require(take.invoke(null, market) == null,
                    "future AoTD subclass retained a stale mutation payload");

            System.out.println("OK AoTD spp11 explicit market/cargo/mutation dispatch + "
                    + "global/reject/exception/subclass fallback");
        } finally {
            Files.deleteIfExists(configFile);
        }
    }

    private static MarketAPI marketFixture() {
        return (MarketAPI) Proxy.newProxyInstance(
                AoTDExplicitUiEconomyDispatchRuntimeTest.class.getClassLoader(),
                new Class<?>[] {MarketAPI.class},
                (proxy, method, args) -> {
                    if ("getId".equals(method.getName())) return "dispatch-test-market";
                    if ("isPlanetConditionMarketOnly".equals(method.getName())) return false;
                    if ("toString".equals(method.getName())) return "dispatch-test-market";
                    if ("hashCode".equals(method.getName())) {
                        return Integer.valueOf(System.identityHashCode(proxy));
                    }
                    if ("equals".equals(method.getName())) return proxy == args[0];
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

    private static void requireAction(
            AoTDEconomy economy, int action, MarketAPI market, long detail,
            String[] commodityIds, String label) {
        require(economy.lastAction == action, label + " action mismatch");
        require(economy.lastMarket == market, label + " market identity mismatch");
        require(economy.lastDetail == detail, label + " detail mismatch");
        require(Arrays.equals(economy.lastCommodityIds, commodityIds),
                label + " commodity IDs mismatch: "
                        + Arrays.toString(economy.lastCommodityIds));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FutureAoTDEconomy extends AoTDEconomy {}
}
