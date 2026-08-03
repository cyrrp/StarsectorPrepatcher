package com.fs.starfarer.api;

import java.util.concurrent.atomic.AtomicBoolean;

/** One-shot identity/fingerprint/thread semantics for the live-market token. */
public final class VanillaMarketOpenCoalescingRuntimeTest {
    private VanillaMarketOpenCoalescingRuntimeTest() {}

    public static void main(String[] args) throws Exception {
        Object economy = new Object();
        Object market = new Object();
        long fingerprint = 0x1234abcdL;

        StarsectorPrepatcherRuntimeBridge.recordVanillaMarketOpenRefresh(
                economy, market, fingerprint);
        require(StarsectorPrepatcherRuntimeBridge.consumeVanillaMarketOpenRefresh(
                        economy, market, fingerprint),
                "exact one-shot token was not consumed");
        require(!StarsectorPrepatcherRuntimeBridge.consumeVanillaMarketOpenRefresh(
                        economy, market, fingerprint),
                "token was reusable");

        StarsectorPrepatcherRuntimeBridge.recordVanillaMarketOpenRefresh(
                economy, market, fingerprint);
        require(!StarsectorPrepatcherRuntimeBridge.consumeVanillaMarketOpenRefresh(
                        economy, market, fingerprint + 1L),
                "changed fingerprint was coalesced");
        require(!StarsectorPrepatcherRuntimeBridge.consumeVanillaMarketOpenRefresh(
                        economy, market, fingerprint),
                "mismatch did not consume token fail-closed");

        StarsectorPrepatcherRuntimeBridge.recordVanillaMarketOpenRefresh(
                economy, market, fingerprint);
        AtomicBoolean otherThreadConsumed = new AtomicBoolean(true);
        Thread thread = new Thread(() -> otherThreadConsumed.set(
                StarsectorPrepatcherRuntimeBridge.consumeVanillaMarketOpenRefresh(
                        economy, market, fingerprint)));
        thread.start();
        thread.join();
        require(!otherThreadConsumed.get(), "cross-thread token was consumed");
        require(!StarsectorPrepatcherRuntimeBridge.consumeVanillaMarketOpenRefresh(
                        economy, market, fingerprint),
                "cross-thread mismatch did not clear token");

        System.out.println("OK vanilla-market-open coalescing one-shot/identity/"
                + "fingerprint/thread fail-closed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
