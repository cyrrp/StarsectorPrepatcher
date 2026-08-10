package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge;
import com.starsector.prepatcher.agent.PrepatcherConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class AoTDDeliveryListenerFailStopTest {
    private AoTDDeliveryListenerFailStopTest() {}

    public static void main(String[] args) throws Exception {
        Path configFile = Files.createTempFile("prepatcher-aotd-delivery", ".properties");
        Files.writeString(configFile, "patch.aotdCleanDeficitPath=true\n");
        StarsectorPrepatcherRuntimeBridge.configure(
                PrepatcherConfig.load(configFile), configFile.getParent());
        StarsectorPrepatcherRuntimeBridge.setAoTDEconomyRestoreCompletionContract(
                true, "runtime-test");
        AtomicInteger calls = new AtomicInteger();
        Consumer<Object> broken = ignored -> {
            calls.incrementAndGet();
            throw new IllegalAccessError("synthetic broken nestmate callback");
        };

        long negotiated = StarsectorPrepatcherRuntimeBridge.registerAoTDForkContract(
                "aotd_theory_of_toolbox",
                StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_FORK_VERSION,
                StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_DECLARED_CAPABILITIES,
                broken, (industry, ids) -> null, () -> { });
        require((negotiated & StarsectorPrepatcherRuntimeBridge.AOTD_CAPABILITY_NATIVE_DELIVERY_EVENTS) != 0L,
                "delivery capability was not initially negotiated");

        Object market = new Object();
        StarsectorPrepatcherRuntimeBridge.publishAoTDMarketTimeDelivered(market, 1f, 1);
        StarsectorPrepatcherRuntimeBridge.publishAoTDMarketTimeDelivered(market, 1f, 1);

        require(calls.get() == 1, "broken callback was invoked more than once: " + calls.get());
        long runtimeCapabilities =
                StarsectorPrepatcherRuntimeBridge.getAoTDNegotiatedCapabilities();
        require((runtimeCapabilities
                & StarsectorPrepatcherRuntimeBridge.AOTD_CAPABILITY_NATIVE_DELIVERY_EVENTS) == 0L,
                "delivery capability remained active after LinkageError");
        String status = StarsectorPrepatcherRuntimeBridge.getAoTDForkContractStatus();
        require(status.contains("deliveryListener=disabled-linkage:java.lang.IllegalAccessError"),
                "missing disabled listener diagnostic: " + status);
        require(status.contains("callbackFailures=1"),
                "unexpected callback failure count: " + status);
        Files.deleteIfExists(configFile);
        System.out.println("AoTDDeliveryListenerFailStopTest: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
