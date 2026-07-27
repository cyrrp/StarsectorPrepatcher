package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge;

/** Proves one-shot consumption, nesting and finally cleanup of the UI context. */
public final class AoTDOpeningMarketContextRuntimeTest {
    private AoTDOpeningMarketContextRuntimeTest() {}

    public static void main(String[] args) {
        Object outer = new Object();
        Object inner = new Object();
        long outerToken = StarsectorPrepatcherRuntimeBridge.beginAoTDOpeningMarket(outer);
        long innerToken = StarsectorPrepatcherRuntimeBridge.beginAoTDOpeningMarket(inner);
        require(StarsectorPrepatcherRuntimeBridge.consumeAoTDOpeningMarket() == inner,
                "inner context not consumed");
        require(StarsectorPrepatcherRuntimeBridge.consumeAoTDOpeningMarket() == null,
                "inner context was not one-shot");
        StarsectorPrepatcherRuntimeBridge.endAoTDOpeningMarket(innerToken);
        require(StarsectorPrepatcherRuntimeBridge.consumeAoTDOpeningMarket() == outer,
                "outer context was not restored after nesting");
        require(StarsectorPrepatcherRuntimeBridge.consumeAoTDOpeningMarket() == null,
                "outer context was not one-shot");
        StarsectorPrepatcherRuntimeBridge.endAoTDOpeningMarket(outerToken);
        require(StarsectorPrepatcherRuntimeBridge.consumeAoTDOpeningMarket() == null,
                "context leaked after finally cleanup");

        Object mismatched = new Object();
        long mismatchToken =
                StarsectorPrepatcherRuntimeBridge.beginAoTDOpeningMarket(mismatched);
        StarsectorPrepatcherRuntimeBridge.endAoTDOpeningMarket(mismatchToken + 1L);
        require(StarsectorPrepatcherRuntimeBridge.consumeAoTDOpeningMarket() == null,
                "token mismatch did not fail-stop the context");
        StarsectorPrepatcherRuntimeBridge.endAoTDOpeningMarket(mismatchToken);

        System.out.println(
                "OK aotd-opening-market-context one-shot/nested/finally-cleared/mismatch-fail-stop");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
