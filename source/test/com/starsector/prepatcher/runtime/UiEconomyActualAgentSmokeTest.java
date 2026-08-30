package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/** Final real-javaagent status smoke for all non-economic UI transformers. */
public final class UiEconomyActualAgentSmokeTest {
    private UiEconomyActualAgentSmokeTest() {}

    public static void main(String[] args) throws Exception {
        require(args.length <= 1,
                "Usage: UiEconomyActualAgentSmokeTest [ExerelinCore.jar]");
        ClassLoader loader = UiEconomyActualAgentSmokeTest.class.getClassLoader();
        Class.forName("com.fs.starfarer.campaign.CampaignEngine", false, loader);
        require("APPLIED".equals(System.getProperty(
                        "starsector.prepatcher.aotdMarketOpenContextPatch", "")),
                "condition-only market-open transformer was not APPLIED");

        try {
            Class.forName("com.fs.starfarer.campaign.ui.class", false, loader);
        } catch (ClassFormatError expectedForKeywordClassName) {
            if (Runtime.version().feature() >= 27) throw expectedForKeywordClassName;
            // The stock JVM loader rejects this obfuscated keyword name after the transformer
            // has already received the class bytes on legacy runtimes without name repair.
        }
        require("APPLIED".equals(System.getProperty(
                        "starsector.prepatcher.campaignCargoNoGlobalEconomyStepPatch", "")),
                "shared CARGO/LOOT transformer was not APPLIED");
        require("APPLIED".equals(System.getProperty(
                        "starsector.prepatcher.tradeMarketMutationPatch", "")),
                "trade market-mutation guard was not APPLIED");

        triggerTransform("com.fs.starfarer.campaign.ui.marketinfo.s", loader);
        require("APPLIED".equals(System.getProperty(
                        "starsector.prepatcher.marketOverviewMutationPatch", "")),
                "market-overview mutation transformer was not APPLIED");

        triggerTransform("com.fs.starfarer.campaign.command.F", loader);
        triggerTransform(
                "com.fs.starfarer.campaign.ui.marketinfo.cdd.CommodityDetailDialogV2", loader);
        triggerTransform(
                "com.fs.starfarer.campaign.ui.marketinfo.CommodityDetailDialog", loader);
        triggerTransform(
                "com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD", loader);
        require("APPLIED".equals(System.getProperty(
                        "starsector.prepatcher.commandTabNoGlobalEconomyStepPatch", "")),
                "Command-tab read-only UI transformer was not APPLIED");
        require("APPLIED".equals(System.getProperty(
                        "starsector.prepatcher.commodityDetailV2NoGlobalEconomyStepPatch", "")),
                "commodity-detail V2 transformer was not APPLIED");
        require("APPLIED".equals(System.getProperty(
                        "starsector.prepatcher.commodityDetailLegacyNoGlobalEconomyStepPatch", "")),
                "legacy commodity-detail transformer was not APPLIED");
        require("APPLIED".equals(System.getProperty(
                        "starsector.prepatcher.marketDefensesNoGlobalEconomyStepPatch", "")),
                "MarketCMD defense transformer was not APPLIED");

        if (args.length == 1) {
            URL nexJar = Path.of(args[0]).toUri().toURL();
            try (URLClassLoader nexLoader =
                         new URLClassLoader(new URL[]{nexJar}, loader)) {
                triggerTransform(
                        "com.fs.starfarer.api.impl.campaign.rulecmd.salvage.Nex_MarketCMD",
                        nexLoader);
            }
            require("APPLIED".equals(System.getProperty(
                            "starsector.prepatcher."
                                    + "nexMarketDefensesNoGlobalEconomyStepPatch", "")),
                    "Nex_MarketCMD child-loader defense transformer was not APPLIED");
        }

        Class.forName("com.fs.starfarer.campaign.econ.Economy", false, loader);
        Class.forName("com.fs.starfarer.campaign.econ.reach.ReachEconomy", false, loader);
        require(StarsectorPrepatcherRuntimeBridge
                        .isVanillaDetachedCargoEconomyContractOperational(),
                "vanilla synthetic-Cargo Economy contract is not READY");
        require(StarsectorPrepatcherRuntimeBridge
                        .isVanillaMarketOpenLocalizationContractOperational(),
                "vanilla live-market localization contract is not READY");
        require("READY".equals(System.getProperty(
                        "starsector.prepatcher.vanillaMarketOpenLocalizationContract", "")),
                "vanilla live-market localization status is not READY");
        System.out.println(
                "OK actual-agent UI economy contracts: condition-only + CARGO/LOOT + "
                        + "live-market localization + mutation contexts + "
                        + "Command/commodity/vanilla+Nex defenses APPLIED");
    }

    private static void triggerTransform(String className, ClassLoader loader)
            throws ClassNotFoundException {
        try {
            Class.forName(className, false, loader);
        } catch (ClassFormatError expectedForObfuscatedIdentifier) {
            if (Runtime.version().feature() >= 27) throw expectedForObfuscatedIdentifier;
            // Some game classes contain JVM-illegal obfuscated member identifiers. The
            // Java-17 compatibility smoke may still observe the legacy unchanged identifier.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
