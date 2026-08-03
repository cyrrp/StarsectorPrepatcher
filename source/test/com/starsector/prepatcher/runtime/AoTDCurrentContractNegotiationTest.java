package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge;
import com.starsector.prepatcher.agent.PrepatcherConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Strict-current spp8 negotiation, safe profile, and mismatch diagnostics. */
public final class AoTDCurrentContractNegotiationTest {
    private AoTDCurrentContractNegotiationTest() {}

    public static void main(String[] args) throws Exception {
        Path configFile = Files.createTempFile(
                "prepatcher-current-aotd-contract", ".properties");
        try {
            Files.writeString(configFile,
                    "patch.campaignCargoNoGlobalEconomyStep=false\n"
                            + "patch.lootTransferNoGlobalEconomyStep=false\n"
                            + "patch.planetConditionMarketOpenNoGlobalEconomyStep=false\n"
                            + "patch.vanillaMarketOpenLocalization=false\n"
                            + "patch.uiMarketMutationRefresh=false\n"
                            + "patch.aotdCleanDeficitPath=true\n",
                    StandardCharsets.UTF_8);
            StarsectorPrepatcherRuntimeBridge.configure(
                    PrepatcherConfig.load(configFile), configFile.getParent());

            long safeProfile = registerCurrent();
            require(safeProfile == StarsectorPrepatcherRuntimeBridge
                            .AOTD_REQUIRED_CAPABILITIES,
                    "safe profile did not negotiate exactly the required contract: 0x"
                            + Long.toHexString(safeProfile));
            require((safeProfile & StarsectorPrepatcherRuntimeBridge
                            .AOTD_CAPABILITY_UI_ECONOMY_DISPATCH) != 0L,
                    "safe profile dropped required UI economy dispatch");
            require((safeProfile & StarsectorPrepatcherRuntimeBridge
                            .AOTD_CAPABILITY_UI_MARKET_MUTATION_REFRESH) == 0L,
                    "safe profile negotiated disabled optional mutation refresh");

            for (String unsupported : new String[] {
                    "1.0.14-spp4", "1.0.14-spp5", "1.0.14-spp6",
                    "1.0.14-spp8-unreviewed-future"}) {
                long rejected = register(unsupported,
                        StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_DECLARED_CAPABILITIES,
                        ignored -> { }, (industry, ids) -> null);
                require(rejected == 0L, "unsupported fork was accepted: " + unsupported);
                require("rejected-fork-version-mismatch".equals(System.getProperty(
                                "starsector.prepatcher.aotdContract")),
                        "unsupported fork did not publish version-mismatch diagnostic");
            }

            long wrongMask = register(
                    StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_FORK_VERSION,
                    StarsectorPrepatcherRuntimeBridge.AOTD_REQUIRED_CAPABILITIES,
                    ignored -> { }, (industry, ids) -> null);
            require(wrongMask == 0L, "partial declared mask was accepted");
            require("rejected-declared-capabilities-mismatch".equals(System.getProperty(
                            "starsector.prepatcher.aotdContract")),
                    "partial mask did not publish declared-mask diagnostic");

            long missingCallbacks = register(
                    StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_FORK_VERSION,
                    StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_DECLARED_CAPABILITIES,
                    null, null);
            require(missingCallbacks == 0L, "current contract without callbacks was accepted");
            require("rejected-delivery-listener-missing".equals(System.getProperty(
                            "starsector.prepatcher.aotdContract")),
                    "missing callback did not publish callback diagnostic");

            long missingResolver = register(
                    StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_FORK_VERSION,
                    StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_DECLARED_CAPABILITIES,
                    ignored -> { }, null);
            require(missingResolver == 0L,
                    "current contract without deficit resolver was accepted");
            require("rejected-deficit-resolver-missing".equals(System.getProperty(
                            "starsector.prepatcher.aotdContract")),
                    "missing resolver did not publish resolver diagnostic");

            Files.writeString(configFile,
                    "patch.uiMarketMutationRefresh=true\n"
                            + "patch.aotdCleanDeficitPath=true\n",
                    StandardCharsets.UTF_8);
            StarsectorPrepatcherRuntimeBridge.configure(
                    PrepatcherConfig.load(configFile), configFile.getParent());
            long fullProfile = registerCurrent();
            require(fullProfile == StarsectorPrepatcherRuntimeBridge
                            .AOTD_CURRENT_DECLARED_CAPABILITIES,
                    "enabled optional profile did not negotiate the full current mask: 0x"
                            + Long.toHexString(fullProfile));
        } finally {
            Files.deleteIfExists(configFile);
        }
        System.out.println("OK AoTD strict spp8 contract required=0x3ff optional=0x7ff "
                + "safe/current/obsolete/future/mask/callback diagnostics");
    }

    private static long registerCurrent() {
        return register(StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_FORK_VERSION,
                StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_DECLARED_CAPABILITIES,
                ignored -> { }, (industry, ids) -> null);
    }

    private static long register(
            String forkVersion, long declaredCapabilities,
            java.util.function.Consumer<Object> deliveryListener,
            java.util.function.BiFunction<Object, Object, Object> deficitResolver) {
        return StarsectorPrepatcherRuntimeBridge.registerAoTDForkContract(
                "aotd_theory_of_toolbox", forkVersion, declaredCapabilities,
                deliveryListener, deficitResolver);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
