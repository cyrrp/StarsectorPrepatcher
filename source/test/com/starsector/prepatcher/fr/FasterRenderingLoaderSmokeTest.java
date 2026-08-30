package com.starsector.prepatcher.fr;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;

/**
 * Process-level smoke test for Faster Rendering's split javaagent topology.
 *
 * <p>This class deliberately has no compile-time dependency on Starsector or
 * prepatcher classes. FR delegates this non-{@code com.fs} class to its parent
 * loader, so all game/runtime classes must be requested explicitly from the
 * custom system loader. Adding typed game imports here would invalidate the
 * test by allowing the parent loader to define a second copy.</p>
 */
public final class FasterRenderingLoaderSmokeTest {
    private static final String FR_LOADER =
            "com.genir.renderer.loaders.AppClassLoader";
    private static final String FR_AGENT_LOADER =
            "com.genir.renderer.loaders.AppClassLoader$JavaAgentLoader";
    private static final String GLOBAL = "com.fs.starfarer.api.Global";
    private static final String CAMPAIGN_ENGINE =
            "com.fs.starfarer.campaign.CampaignEngine";
    private static final String HYPERSPACE_TERRAIN =
            "com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin";
    private static final String BASE_TERRAIN =
            "com.fs.starfarer.api.impl.campaign.terrain.BaseTerrain";
    private static final String INTEL_MANAGER =
            "com.fs.starfarer.campaign.comms.v2.IntelManager";
    private static final String COMMODITY_ON_MARKET =
            "com.fs.starfarer.campaign.econ.CommodityOnMarket";
    private static final String TRADE_UI =
            "com.fs.starfarer.campaign.ui.class";
    private static final String MARKET_OVERVIEW =
            "com.fs.starfarer.campaign.ui.marketinfo.s";
    private static final String VECTOR_2F = "org.lwjgl.util.vector.Vector2f";
    private static final String RUNTIME_HOOKS =
            "com.fs.starfarer.api.StarsectorPrepatcherHooks";
    private static final String HYPERSPACE_HOOKS =
            "com.fs.starfarer.api.StarsectorPrepatcherHyperspaceHooks";
    private static final String RUNTIME_BRIDGE =
            "com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge";
    private static final String PRESENTATION_HOOKS =
            "com.fs.starfarer.api.StarsectorPrepatcherPresentationHooks";
    private static final String AGENT =
            "com.starsector.prepatcher.agent.PrepatcherAgent";
    private static final String RUNTIME_ENTRY_PREFIX =
            "com/fs/starfarer/api/StarsectorPrepatcher";
    private static final int EXPECTED_RUNTIME_CLASS_COUNT = 113;

    private FasterRenderingLoaderSmokeTest() {}

    public static void main(String[] args) throws Throwable {
        require(args.length == 1,
                "Usage: FasterRenderingLoaderSmokeTest <prepatcher-agent.jar>");
        Path agentJar = Path.of(args[0]).toAbsolutePath().normalize();
        assertAgentIsNotOnApplicationClasspath(agentJar);

        ClassLoader system = ClassLoader.getSystemClassLoader();
        require(system != null, "custom system class loader is null");
        require(FR_LOADER.equals(system.getClass().getName()),
                "expected FR system loader " + FR_LOADER + ", got " + describe(system));
        require(FasterRenderingLoaderSmokeTest.class.getClassLoader() != system,
                "harness was defined by the FR child loader; classpath topology is contaminated");

        Class<?> global = load(system, GLOBAL);
        Class<?> campaignEngine = load(system, CAMPAIGN_ENGINE);
        Class<?> hyperspaceTerrain = load(system, HYPERSPACE_TERRAIN);
        Class<?> baseTerrain = load(system, BASE_TERRAIN);
        Class<?> intelManager = load(system, INTEL_MANAGER);
        Class<?> commodityOnMarket = load(system, COMMODITY_ON_MARKET);
        Class<?> tradeUi = loadUiTarget(system, TRADE_UI);
        Class<?> marketOverview = loadUiTarget(system, MARKET_OVERVIEW);
        Class<?> hooks = load(system, RUNTIME_HOOKS);
        Class<?> hyperspaceHooks = load(system, HYPERSPACE_HOOKS);
        Class<?> bridge = load(system, RUNTIME_BRIDGE);
        Class<?> presentationHooks = load(system, PRESENTATION_HOOKS);
        Class<?> vector2f = load(system, VECTOR_2F);

        assertDefinedBy(system, global);
        assertDefinedBy(system, campaignEngine);
        assertDefinedBy(system, hyperspaceTerrain);
        assertDefinedBy(system, baseTerrain);
        assertDefinedBy(system, intelManager);
        assertDefinedBy(system, commodityOnMarket);
        if (tradeUi != null) assertDefinedBy(system, tradeUi);
        if (marketOverview != null) assertDefinedBy(system, marketOverview);
        assertDefinedBy(system, hooks);
        assertDefinedBy(system, hyperspaceHooks);
        assertDefinedBy(system, bridge);
        assertDefinedBy(system, presentationHooks);
        int runtimeClassCount = assertRuntimeInventory(agentJar, system);

        // This is the exact descriptor that previously failed while resolving
        // IntelManager.findNearestCommRelayToReceive under FR. Looking it up as
        // a MethodHandle forces every reference in the descriptor to resolve.
        MethodType descriptor = MethodType.methodType(
                List.class, campaignEngine, vector2f, float.class, float.class);
        MethodHandle commRelay = MethodHandles.publicLookup().findStatic(
                hooks, "commRelayCandidateSystems", descriptor);
        Object result = commRelay.invokeWithArguments(null, null, 1.0f, 1.0f);
        require(result instanceof List<?>,
                "null-engine comm relay probe did not return a List: " + result);
        String intelPatchStatus = System.getProperty(
                "starsector.prepatcher.patchStatus." + INTEL_MANAGER
                        + ".commRelaySystemIndex");
        require("APPLIED".equals(intelPatchStatus),
                "FR did not transform the real comm-relay caller: status="
                        + intelPatchStatus);
        String commodityPatchStatus = System.getProperty(
                "starsector.prepatcher.patchStatus." + COMMODITY_ON_MARKET
                        + ".commodityEventModDirtyCache");
        require("APPLIED".equals(commodityPatchStatus),
                "FR did not transform CommodityOnMarket: status="
                        + commodityPatchStatus);
        String presentationPatchStatus = System.getProperty(
                "starsector.prepatcher.patchStatus." + CAMPAIGN_ENGINE
                        + ".fastForwardPresentation");
        require("APPLIED".equals(presentationPatchStatus),
                "FR did not apply presentation coalescing before the structural transformer: status="
                        + presentationPatchStatus);
        String entityLookupRepairStatus = System.getProperty(
                "starsector.prepatcher.patchStatus." + CAMPAIGN_ENGINE
                        + ".entityLookupIndexRepair");
        require("APPLIED".equals(entityLookupRepairStatus),
                "FR did not apply the post-load entity-index repair: status="
                        + entityLookupRepairStatus);
        String apiPresentationPatchStatus = System.getProperty(
                "starsector.prepatcher.patchStatus." + BASE_TERRAIN
                        + ".fastForwardPresentation");
        require("APPLIED".equals(apiPresentationPatchStatus),
                "FR did not apply presentation coalescing from starfarer.api.jar: status="
                        + apiPresentationPatchStatus);
        String frMutatedPresentationStatus = System.getProperty(
                "starsector.prepatcher.patchStatus." + HYPERSPACE_TERRAIN
                        + ".fastForwardPresentation");
        require("APPLIED".equals(frMutatedPresentationStatus),
                "FR-mutated presentation target did not match its unchanged local sound surface: status="
                        + frMutatedPresentationStatus);
        String tradeMutationStatus = System.getProperty(
                "starsector.prepatcher.tradeMarketMutationPatch");
        require("APPLIED".equals(tradeMutationStatus),
                "FR did not apply the trade market-mutation patch: status="
                        + tradeMutationStatus);
        String marketOverviewStatus = System.getProperty(
                "starsector.prepatcher.marketOverviewMutationPatch");
        require("APPLIED".equals(marketOverviewStatus),
                "FR did not apply the market-overview mutation patch: status="
                        + marketOverviewStatus);
        String cargoStatus = System.getProperty(
                "starsector.prepatcher.campaignCargoNoGlobalEconomyStepPatch");
        require("APPLIED".equals(cargoStatus),
                "FR did not preserve the later Cargo patch after trade mutation: status="
                        + cargoStatus);
        require(System.getProperty("starsector.prepatcher.pipelineFailure." + TRADE_UI) == null,
                "FR trade target published an atomic pipeline failure");
        require(System.getProperty(
                        "starsector.prepatcher.pipelineFailure." + MARKET_OVERVIEW) == null,
                "FR market-overview target published an atomic pipeline failure");

        Class<?> agent = load(system, AGENT);
        ClassLoader agentLoader = agent.getClassLoader();
        require(agentLoader != null, "prepatcher agent was loaded by bootstrap loader");
        require(FR_AGENT_LOADER.equals(agentLoader.getClass().getName()),
                "expected FR javaagent loader " + FR_AGENT_LOADER
                        + ", got " + describe(agentLoader));
        require(agentLoader != system,
                "agent and runtime hooks unexpectedly share a loader; smoke no longer covers FR split topology");

        require("transformer-installed".equals(
                        System.getProperty("starsector.prepatcher.status")),
                "prepatcher agent did not install its transformer: status="
                        + System.getProperty("starsector.prepatcher.status"));

        System.out.println("OK FR loader topology"
                + " system=" + describe(system)
                + " agent=" + describe(agentLoader)
                + " hooks=" + describe(hooks.getClassLoader()));
        System.out.println("OK typed descriptor " + hooks.getName()
                + ".commRelayCandidateSystems" + descriptor);
        System.out.println("OK transformed caller " + INTEL_MANAGER
                + " commRelaySystemIndex=" + intelPatchStatus);
        System.out.println("OK transformed economy target " + COMMODITY_ON_MARKET
                + " commodityEventModDirtyCache=" + commodityPatchStatus);
        System.out.println("OK ordered overlapping target " + CAMPAIGN_ENGINE
                + " fastForwardPresentation=" + presentationPatchStatus);
        System.out.println("OK entity-index actual-loader transform status="
                + entityLookupRepairStatus);
        System.out.println("OK FR API-container target " + BASE_TERRAIN
                + " fastForwardPresentation=" + apiPresentationPatchStatus);
        System.out.println("OK FR upstream-mutated target " + HYPERSPACE_TERRAIN
                + " fastForwardPresentation=" + frMutatedPresentationStatus);
        System.out.println("OK FR normalized UI market fields trade=" + tradeMutationStatus
                + " overview=" + marketOverviewStatus + " cargo=" + cargoStatus);
        System.out.println("OK runtime payload inventory classes=" + runtimeClassCount);
    }

    private static Class<?> load(ClassLoader loader, String name)
            throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    private static Class<?> loadUiTarget(ClassLoader loader, String name)
            throws ClassNotFoundException {
        try {
            return load(loader, name);
        } catch (ClassFormatError legacyIllegalName) {
            if (Runtime.version().feature() >= 27) throw legacyIllegalName;
            return null;
        }
    }

    private static void assertDefinedBy(ClassLoader expected, Class<?> type) {
        require(type.getClassLoader() == expected,
                type.getName() + " expected loader=" + describe(expected)
                        + " actual=" + describe(type.getClassLoader()));
    }

    private static void assertAgentIsNotOnApplicationClasspath(Path agentJar) {
        String classPath = System.getProperty("java.class.path", "");
        for (String entry : classPath.split(
                java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) continue;
            Path candidate = Path.of(entry).toAbsolutePath().normalize();
            require(!candidate.equals(agentJar),
                    "agent JAR must be supplied only with -javaagent, not -classpath: "
                            + agentJar);
        }
    }

    private static int assertRuntimeInventory(Path agentJar, ClassLoader system)
            throws Exception {
        int count = 0;
        try (JarFile jar = new JarFile(agentJar.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(RUNTIME_ENTRY_PREFIX)
                        || !name.endsWith(".class")) {
                    continue;
                }
                String binaryName = name.substring(0, name.length() - ".class".length())
                        .replace('/', '.');
                assertDefinedBy(system, load(system, binaryName));
                count++;
            }
        }
        require(count == EXPECTED_RUNTIME_CLASS_COUNT,
                "runtime payload inventory changed: expected "
                        + EXPECTED_RUNTIME_CLASS_COUNT + ", got " + count);
        return count;
    }

    private static String describe(ClassLoader loader) {
        return loader == null ? "bootstrap" : loader.getClass().getName()
                + "@" + Integer.toHexString(System.identityHashCode(loader));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
