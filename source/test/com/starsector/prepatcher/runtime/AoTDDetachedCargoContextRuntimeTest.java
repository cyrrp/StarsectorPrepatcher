package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge;
import com.starsector.prepatcher.agent.PrepatcherConfig;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Proves exact vanilla Cargo/LOOT eligibility and UI-dispatch fail-stop behavior. */
public final class AoTDDetachedCargoContextRuntimeTest {
    private enum Mode { MARKET, CARGO, LOOT }

    private AoTDDetachedCargoContextRuntimeTest() {}

    public static void main(String[] args) throws Exception {
        Path configFile = Files.createTempFile("prepatcher-detached-cargo", ".properties");
        Files.writeString(configFile,
                "patch.campaignCargoNoGlobalEconomyStep=true\n"
                        + "patch.lootTransferNoGlobalEconomyStep=true\n",
                StandardCharsets.UTF_8);
        PrepatcherConfig config = PrepatcherConfig.load(configFile);
        StarsectorPrepatcherRuntimeBridge.configure(config, configFile.getParent());
        require(!StarsectorPrepatcherRuntimeBridge
                        .isVanillaDetachedCargoEconomyContractOperational(),
                "vanilla economy contract started enabled before structural proof");
        StarsectorPrepatcherRuntimeBridge.setVanillaDetachedCargoEconomyContract(
                true, "runtime-test");
        StarsectorPrepatcherRuntimeBridge.setAoTDEconomyRestoreCompletionContract(
                true, "runtime-test");
        require(StarsectorPrepatcherRuntimeBridge
                        .isVanillaDetachedCargoEconomyContractOperational(),
                "vanilla economy contract did not activate");

        testVanillaEligibility();
        long negotiated = StarsectorPrepatcherRuntimeBridge.registerAoTDForkContract(
                "aotd_theory_of_toolbox",
                StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_FORK_VERSION,
                StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_DECLARED_CAPABILITIES,
                ignored -> { }, (industry, ids) -> null, () -> { });
        require((negotiated
                        & StarsectorPrepatcherRuntimeBridge
                        .AOTD_CAPABILITY_UI_ECONOMY_DISPATCH) != 0L,
                "explicit UI dispatch capability was not negotiated");

        StarsectorPrepatcherRuntimeBridge.disableAoTDUiEconomyDispatch("runtime-test");
        require(!StarsectorPrepatcherRuntimeBridge
                        .shouldSkipVanillaDetachedCargoEconomyStep(
                                new Object(), true, Mode.CARGO, null, null),
                "disabled patch still evaluated detached Cargo as skippable");
        require((StarsectorPrepatcherRuntimeBridge.getAoTDNegotiatedCapabilities()
                        & StarsectorPrepatcherRuntimeBridge
                        .AOTD_CAPABILITY_UI_ECONOMY_DISPATCH) == 0L,
                "fail-stop downgrade retained UI dispatch capability");

        Files.deleteIfExists(configFile);
        System.out.println(
                "OK synthetic-cargo cargo+loot exact-vanilla-skip/subclass-fallback/"
                        + "dispatch-downgrade");
    }

    private static void testVanillaEligibility() throws Exception {
        Path root = Files.createTempDirectory("spp-vanilla-economy-fixture-");
        try {
            Path reachSource = writeSource(root,
                    "com/fs/starfarer/campaign/econ/reach/ReachEconomy.java",
                    "package com.fs.starfarer.campaign.econ.reach; "
                            + "public class ReachEconomy { "
                            + "public java.util.List<Object> getMarkets() "
                            + "{ return java.util.Collections.emptyList(); } }");
            Path economySource = writeSource(root,
                    "com/fs/starfarer/campaign/econ/Economy.java",
                    "package com.fs.starfarer.campaign.econ; "
                            + "public class Economy { "
                            + "private final com.fs.starfarer.campaign.econ.reach.ReachEconomy econ; "
                            + "public Economy(com.fs.starfarer.campaign.econ.reach.ReachEconomy econ) "
                            + "{ this.econ = econ; } "
                            + "public com.fs.starfarer.campaign.econ.reach.ReachEconomy "
                            + "getEconomy() { return econ; } }");
            Path outerSource = writeSource(root,
                    "fixture/OuterEconomy.java",
                    "package fixture; public class OuterEconomy extends "
                            + "com.fs.starfarer.campaign.econ.Economy { "
                            + "public OuterEconomy("
                            + "com.fs.starfarer.campaign.econ.reach.ReachEconomy econ) "
                            + "{ super(econ); } }");
            Path innerSource = writeSource(root,
                    "fixture/InnerReachEconomy.java",
                    "package fixture; public class InnerReachEconomy extends "
                            + "com.fs.starfarer.campaign.econ.reach.ReachEconomy { }");
            Path cargoSource = writeSource(root,
                    "com/fs/starfarer/campaign/fleet/CargoData.java",
                    "package com.fs.starfarer.campaign.fleet; public class CargoData { }");

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            require(compiler != null, "JDK compiler is unavailable");
            int rc = compiler.run(null, null, null,
                    "-encoding", "UTF-8", "-d", root.toString(),
                    reachSource.toString(), economySource.toString(),
                    outerSource.toString(), innerSource.toString(), cargoSource.toString());
            require(rc == 0, "vanilla economy fixture compilation failed: " + rc);

            try (ChildFirstLoader loader = new ChildFirstLoader(
                    new URL[] {root.toUri().toURL()},
                    AoTDDetachedCargoContextRuntimeTest.class.getClassLoader())) {
                Class<?> reachClass = Class.forName(
                        "com.fs.starfarer.campaign.econ.reach.ReachEconomy",
                        true, loader);
                Class<?> economyClass = Class.forName(
                        "com.fs.starfarer.campaign.econ.Economy", true, loader);
                Object reach = reachClass.getConstructor().newInstance();
                Object vanilla = economyClass.getConstructor(reachClass)
                        .newInstance(reach);
                Object lootCargo = Class.forName(
                                "com.fs.starfarer.campaign.fleet.CargoData", true, loader)
                        .getConstructor().newInstance();

                require(StarsectorPrepatcherRuntimeBridge
                                .shouldSkipVanillaDetachedCargoEconomyStep(
                                        vanilla, true, Mode.CARGO, null, null),
                        "exact vanilla Economy/ReachEconomy was not skipped");
                require(StarsectorPrepatcherRuntimeBridge
                                .getDetachedCargoVanillaStepsSkipped() == 1L,
                        "vanilla skip counter mismatch");
                require(StarsectorPrepatcherRuntimeBridge
                                .shouldSkipVanillaDetachedCargoEconomyStep(
                                        vanilla, true, Mode.LOOT, null, lootCargo),
                        "exact vanilla loot transfer was not skipped");
                require(StarsectorPrepatcherRuntimeBridge
                                .getLootTransferVanillaStepsSkipped() == 1L,
                        "loot-transfer skip counter mismatch");
                require(!StarsectorPrepatcherRuntimeBridge
                                .shouldSkipVanillaDetachedCargoEconomyStep(
                                        vanilla, false, Mode.CARGO, null, null),
                        "non-synthetic vanilla Cargo was skipped");
                require(!StarsectorPrepatcherRuntimeBridge
                                .shouldSkipVanillaDetachedCargoEconomyStep(
                                        vanilla, true, Mode.MARKET, null, null),
                        "non-Cargo vanilla market was skipped");

                Class<?> outerClass = Class.forName(
                        "fixture.OuterEconomy", true, loader);
                Object outer = outerClass.getConstructor(reachClass)
                        .newInstance(reach);
                require(!StarsectorPrepatcherRuntimeBridge
                                .shouldSkipVanillaDetachedCargoEconomyStep(
                                        outer, true, Mode.CARGO, null, null),
                        "Economy subclass was incorrectly treated as vanilla");

                Class<?> innerClass = Class.forName(
                        "fixture.InnerReachEconomy", true, loader);
                Object innerReach = innerClass.getConstructor().newInstance();
                Object inner = economyClass.getConstructor(reachClass)
                        .newInstance(innerReach);
                require(!StarsectorPrepatcherRuntimeBridge
                                .shouldSkipVanillaDetachedCargoEconomyStep(
                                        inner, true, Mode.CARGO, null, null),
                        "ReachEconomy subclass was incorrectly treated as vanilla");
                require(StarsectorPrepatcherRuntimeBridge
                                .getDetachedCargoUnknownEconomyFallbacks() == 2L,
                        "unknown-economy fallback counter mismatch");
            }
        } finally {
            if (Files.exists(root)) {
                try (var paths = Files.walk(root)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); }
                        catch (Exception ignored) { }
                    });
                }
            }
        }
    }

    private static Path writeSource(Path root, String relative, String source)
            throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, source, StandardCharsets.UTF_8);
        return path;
    }

    private static final class ChildFirstLoader extends URLClassLoader {
        ChildFirstLoader(URL[] urls, ClassLoader parent) { super(urls, parent); }

        @Override
        protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            if (name.startsWith("com.fs.starfarer.campaign.econ.")
                    || name.startsWith("com.fs.starfarer.campaign.fleet.")
                    || name.startsWith("data.kaysaar.aotd.tot.scripts.economy.")
                    || name.startsWith("fixture.")) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        try { loaded = findClass(name); }
                        catch (ClassNotFoundException ignored) { }
                    }
                    if (loaded != null) {
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
