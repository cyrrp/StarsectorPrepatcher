package com.fs.starfarer.api;

import com.starsector.prepatcher.agent.PrepatcherConfig;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/** One-shot UI market-mutation identity, merge, thread and epoch semantics. */
public final class UiMarketMutationContextRuntimeTest {
    private UiMarketMutationContextRuntimeTest() {}

    public static void main(String[] args) throws Exception {
        Path configFile = Files.createTempFile("prepatcher-ui-market-mutation", ".properties");
        try {
            Files.writeString(configFile,
                    "patch.uiMarketMutationRefresh=true\n"
                            + "patch.campaignCargoNoGlobalEconomyStep=true\n",
                    StandardCharsets.UTF_8);
            StarsectorPrepatcherRuntimeBridge.configure(
                    PrepatcherConfig.load(configFile), configFile.getParent());
            long negotiated = StarsectorPrepatcherRuntimeBridge.registerAoTDForkContract(
                    "aotd_theory_of_toolbox",
                    StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_FORK_VERSION,
                    StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_DECLARED_CAPABILITIES,
                    ignored -> { }, (industry, ids) -> null);
            require((negotiated
                    & StarsectorPrepatcherRuntimeBridge
                    .AOTD_CAPABILITY_UI_MARKET_MUTATION_REFRESH) != 0L,
                    "UI market-mutation capability was not negotiated");

            Method record = StarsectorPrepatcherRuntimeBridge.class.getDeclaredMethod(
                    "recordUiMarketMutation", Object.class, int.class, int.class);
            record.setAccessible(true);
            Method take = StarsectorPrepatcherRuntimeBridge.class.getDeclaredMethod(
                    "takeUiMarketMutationContext", Object.class);
            take.setAccessible(true);
            Object market = new Object();
            Object other = new Object();

            record.invoke(null, market,
                    StarsectorPrepatcherRuntimeBridge.MUTATION_REASON_IMMIGRATION_POLICY,
                    StarsectorPrepatcherRuntimeBridge.REFRESH_SCOPE_LOCAL_STATS);
            long packed = consumePacked(take, market);
            require((int) (packed >>> 32)
                            == StarsectorPrepatcherRuntimeBridge
                            .MUTATION_REASON_IMMIGRATION_POLICY,
                    "reason was not preserved");
            require((int) packed
                            == StarsectorPrepatcherRuntimeBridge
                            .REFRESH_SCOPE_LOCAL_STATS,
                    "scope was not preserved");
            require(consumePacked(take, market) == 0L,
                    "context was not one-shot");

            record.invoke(null, market,
                    StarsectorPrepatcherRuntimeBridge.MUTATION_REASON_FREE_PORT,
                    StarsectorPrepatcherRuntimeBridge
                            .REFRESH_SCOPE_AFFECTED_GLOBAL_COMMODITIES);
            record.invoke(null, market,
                    StarsectorPrepatcherRuntimeBridge.MUTATION_REASON_IMMIGRATION_POLICY,
                    StarsectorPrepatcherRuntimeBridge.REFRESH_SCOPE_IMMIGRATION);
            packed = consumePacked(take, market);
            require((((int) (packed >>> 32))
                            & StarsectorPrepatcherRuntimeBridge.MUTATION_REASON_FREE_PORT) != 0,
                    "merged free-port reason was lost");
            require((((int) packed)
                            & StarsectorPrepatcherRuntimeBridge
                            .REFRESH_SCOPE_AFFECTED_GLOBAL_COMMODITIES) != 0,
                    "merged global-fallback scope was lost");

            record.invoke(null, market,
                    StarsectorPrepatcherRuntimeBridge.MUTATION_REASON_STOCKPILE_POLICY,
                    StarsectorPrepatcherRuntimeBridge
                            .REFRESH_SCOPE_LOCAL_PRICE_STOCKPILE);
            require(consumePacked(take, other) == 0L,
                    "wrong market consumed context");
            require(consumePacked(take, market) == 0L,
                    "identity mismatch did not clear context fail-closed");

            record.invoke(null, market,
                    StarsectorPrepatcherRuntimeBridge.MUTATION_REASON_STOCKPILE_POLICY,
                    StarsectorPrepatcherRuntimeBridge
                            .REFRESH_SCOPE_LOCAL_PRICE_STOCKPILE);
            AtomicLong crossThread = new AtomicLong(-1L);
            Thread thread = new Thread(() -> crossThread.set(consumePacked(take, market)));
            thread.start();
            thread.join();
            require(crossThread.get() == 0L, "cross-thread context was visible");
            require(consumePacked(take, market) != 0L,
                    "cross-thread probe corrupted owning-thread context");

            record.invoke(null, market,
                    StarsectorPrepatcherRuntimeBridge.MUTATION_REASON_IMMIGRATION_POLICY,
                    StarsectorPrepatcherRuntimeBridge.REFRESH_SCOPE_IMMIGRATION);
            StarsectorPrepatcherRuntimeBridge.publishAoTDRuntimeEpoch(2L, 2L);
            require(consumePacked(take, market) == 0L,
                    "runtime epoch change did not invalidate context");
        } finally {
            Files.deleteIfExists(configFile);
        }
        System.out.println("OK UI market-mutation context one-shot/identity/merge/thread/epoch");
    }

    private static long consumePacked(Method take, Object market) {
        try {
            Object[] context = (Object[]) take.invoke(null, market);
            if (context == null) return 0L;
            int reason = ((Integer) context[1]).intValue();
            int scope = ((Integer) context[2]).intValue();
            return ((long) reason << 32) | (scope & 0xffffffffL);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("context consume failed", failure);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
