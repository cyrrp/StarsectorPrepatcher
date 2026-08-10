package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge;
import com.starsector.prepatcher.agent.PrepatcherConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** No-throw, O(1) runtime boundary for one completed Starsector economy restoration. */
public final class AoTDEconomyRestoreRuntimeTest {
    private AoTDEconomyRestoreRuntimeTest() {}

    public static void main(String[] args) throws Exception {
        Path configFile = Files.createTempFile(
                "prepatcher-aotd-economy-restore", ".properties");
        try {
            Files.writeString(configFile,
                    "patch.aotdCleanDeficitPath=true\n"
                            + "patch.aotdEconomyRestoreCoordination=true\n"
                            + "patch.uiMarketMutationRefresh=false\n",
                    StandardCharsets.UTF_8);
            StarsectorPrepatcherRuntimeBridge.configure(
                    PrepatcherConfig.load(configFile), configFile.getParent());
            StarsectorPrepatcherRuntimeBridge.setAoTDEconomyRestoreCompletionContract(
                    true, "runtime-test");

            AtomicInteger completed = new AtomicInteger();
            long negotiated = register(completed::incrementAndGet);
            require(negotiated
                            == StarsectorPrepatcherRuntimeBridge.AOTD_REQUIRED_CAPABILITIES,
                    "unexpected required profile: 0x" + Long.toHexString(negotiated));

            StarsectorPrepatcherRuntimeBridge.publishAoTDEconomyRestoreComplete();
            StarsectorPrepatcherRuntimeBridge.publishAoTDEconomyRestoreComplete();
            require(completed.get() == 2,
                    "successful restore callback count changed: " + completed.get());
            require(StarsectorPrepatcherRuntimeBridge
                            .getAoTDEconomyRestoreSignalCount() == 2L,
                    "successful signal count changed");
            require(StarsectorPrepatcherRuntimeBridge
                            .getAoTDEconomyRestoreCompletionCount() == 2L,
                    "successful completion count changed");
            require(StarsectorPrepatcherRuntimeBridge
                            .getAoTDEconomyRestoreFailureCount() == 0L,
                    "successful callbacks recorded failures");

            AtomicInteger ordinaryFailures = new AtomicInteger();
            register(() -> {
                ordinaryFailures.incrementAndGet();
                throw new IllegalStateException("synthetic recoverable restore failure");
            });
            for (int i = 0; i < 5; i++) {
                StarsectorPrepatcherRuntimeBridge.publishAoTDEconomyRestoreComplete();
            }
            require(ordinaryFailures.get() == 5,
                    "ordinary callback failures were not retryable");
            require((StarsectorPrepatcherRuntimeBridge.getAoTDNegotiatedCapabilities()
                            & StarsectorPrepatcherRuntimeBridge
                                    .AOTD_CAPABILITY_ECONOMY_RESTORE_COORDINATION) != 0L,
                    "ordinary callback failure removed restore capability");
            require(StarsectorPrepatcherRuntimeBridge
                            .getAoTDEconomyRestoreFailureCount() == 5L,
                    "ordinary failure counter changed");

            AtomicInteger linkageFailures = new AtomicInteger();
            long beforeLinkage = register(() -> {
                linkageFailures.incrementAndGet();
                throw new IllegalAccessError("synthetic broken restore callback");
            });
            StarsectorPrepatcherRuntimeBridge.publishAoTDEconomyRestoreComplete();
            StarsectorPrepatcherRuntimeBridge.publishAoTDEconomyRestoreComplete();
            require(linkageFailures.get() == 1,
                    "linkage-broken callback was invoked more than once");
            long afterLinkage =
                    StarsectorPrepatcherRuntimeBridge.getAoTDNegotiatedCapabilities();
            require(afterLinkage
                            == (beforeLinkage
                                    & ~StarsectorPrepatcherRuntimeBridge
                                            .AOTD_CAPABILITY_ECONOMY_RESTORE_COORDINATION),
                    "LinkageError removed capabilities other than restore coordination: before=0x"
                            + Long.toHexString(beforeLinkage)
                            + ", after=0x" + Long.toHexString(afterLinkage));
            require((afterLinkage
                            & StarsectorPrepatcherRuntimeBridge
                                    .AOTD_CAPABILITY_NATIVE_DELIVERY_EVENTS) != 0L,
                    "LinkageError incorrectly removed delivery capability");
            require(StarsectorPrepatcherRuntimeBridge
                            .getAoTDEconomyRestoreFailureCount() == 6L,
                    "linkage failure counter changed");
            String status = StarsectorPrepatcherRuntimeBridge.getAoTDForkContractStatus();
            require(status.contains(
                            "economyRestoreListener=disabled-linkage:java.lang.IllegalAccessError"),
                    "missing restore-listener downgrade diagnostic: " + status);

            System.out.println("OK AoTD economy restore callbacks=" + completed.get()
                    + " ordinaryFailures=" + ordinaryFailures.get()
                    + " linkageFailures=" + linkageFailures.get()
                    + " capabilities=0x" + Long.toHexString(afterLinkage));
        } finally {
            Files.deleteIfExists(configFile);
        }
    }

    private static long register(Runnable restoreListener) {
        return StarsectorPrepatcherRuntimeBridge.registerAoTDForkContract(
                "aotd_theory_of_toolbox",
                StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_FORK_VERSION,
                StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_DECLARED_CAPABILITIES,
                ignored -> { },
                (industry, ids) -> null,
                restoreListener);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
