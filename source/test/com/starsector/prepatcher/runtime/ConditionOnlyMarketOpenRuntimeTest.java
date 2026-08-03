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

/** Exact vanilla condition-only eligibility and fail-closed coverage. */
public final class ConditionOnlyMarketOpenRuntimeTest {
    private ConditionOnlyMarketOpenRuntimeTest() {}

    public static void main(String[] args) throws Exception {
        Path configFile = Files.createTempFile("prepatcher-condition-only", ".properties");
        Files.writeString(configFile,
                "patch.planetConditionMarketOpenNoGlobalEconomyStep=true\n",
                StandardCharsets.UTF_8);
        PrepatcherConfig config = PrepatcherConfig.load(configFile);
        StarsectorPrepatcherRuntimeBridge.configure(config, configFile.getParent());
        testExactVanilla();
        Files.deleteIfExists(configFile);
        System.out.println("OK condition-only exact-vanilla-skip/live-member/colonization-transition/subclass/fallback");
    }

    private static void testExactVanilla() throws Exception {
        Path root = Files.createTempDirectory("spp-condition-only-fixture-");
        try {
            Path marketSource = writeSource(root,
                    "fixture/Market.java",
                    "package fixture; public class Market { "
                            + "private boolean conditionOnly; "
                            + "public Market(boolean value) { conditionOnly = value; } "
                            + "public boolean isPlanetConditionMarketOnly() "
                            + "{ return conditionOnly; } "
                            + "public void setPlanetConditionMarketOnly(boolean value) "
                            + "{ conditionOnly = value; } }");
            Path reachSource = writeSource(root,
                    "com/fs/starfarer/campaign/econ/reach/ReachEconomy.java",
                    "package com.fs.starfarer.campaign.econ.reach; "
                            + "public class ReachEconomy { "
                            + "private final java.util.List<Object> markets; "
                            + "public ReachEconomy(java.util.List<Object> markets) "
                            + "{ this.markets = markets; } "
                            + "public java.util.List<Object> getMarkets() { return markets; } }");
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
                            + "com.fs.starfarer.campaign.econ.reach.ReachEconomy { "
                            + "public InnerReachEconomy(java.util.List<Object> markets) "
                            + "{ super(markets); } }");

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            require(compiler != null, "JDK compiler unavailable");
            int rc = compiler.run(null, null, null,
                    "-encoding", "UTF-8", "-d", root.toString(),
                    marketSource.toString(), reachSource.toString(),
                    economySource.toString(), outerSource.toString(),
                    innerSource.toString());
            require(rc == 0, "fixture compilation failed: " + rc);

            try (ChildFirstLoader loader = new ChildFirstLoader(
                    new URL[] {root.toUri().toURL()},
                    ConditionOnlyMarketOpenRuntimeTest.class.getClassLoader())) {
                Class<?> marketClass = Class.forName("fixture.Market", true, loader);
                Object conditionOnly = marketClass.getConstructor(boolean.class)
                        .newInstance(true);
                Object normal = marketClass.getConstructor(boolean.class)
                        .newInstance(false);
                Class<?> reachClass = Class.forName(
                        "com.fs.starfarer.campaign.econ.reach.ReachEconomy", true, loader);
                Class<?> economyClass = Class.forName(
                        "com.fs.starfarer.campaign.econ.Economy", true, loader);

                Object emptyReach = reachClass.getConstructor(java.util.List.class)
                        .newInstance(java.util.Collections.emptyList());
                Object vanilla = economyClass.getConstructor(reachClass)
                        .newInstance(emptyReach);
                require(StarsectorPrepatcherRuntimeBridge
                                .shouldSkipConditionOnlyMarketOpenEconomyStep(
                                        vanilla, conditionOnly),
                        "condition-only market outside economy was not skipped");
                require(StarsectorPrepatcherRuntimeBridge
                                .getConditionOnlyVanillaStepsSkipped() == 1L,
                        "condition-only skip counter mismatch");
                marketClass.getMethod("setPlanetConditionMarketOnly", boolean.class)
                        .invoke(conditionOnly, false);
                Object colonizedReach = reachClass.getConstructor(java.util.List.class)
                        .newInstance(java.util.Collections.singletonList(conditionOnly));
                Object colonizedEconomy = economyClass.getConstructor(reachClass)
                        .newInstance(colonizedReach);
                require(!StarsectorPrepatcherRuntimeBridge
                                .shouldSkipConditionOnlyMarketOpenEconomyStep(
                                        colonizedEconomy, conditionOnly),
                        "colonized live market still qualified for condition-only skip");
                marketClass.getMethod("setPlanetConditionMarketOnly", boolean.class)
                        .invoke(conditionOnly, true);
                require(!StarsectorPrepatcherRuntimeBridge
                                .shouldSkipConditionOnlyMarketOpenEconomyStep(
                                        vanilla, normal),
                        "normal market was skipped");

                Object memberReach = reachClass.getConstructor(java.util.List.class)
                        .newInstance(java.util.Collections.singletonList(conditionOnly));
                Object memberEconomy = economyClass.getConstructor(reachClass)
                        .newInstance(memberReach);
                require(!StarsectorPrepatcherRuntimeBridge
                                .shouldSkipConditionOnlyMarketOpenEconomyStep(
                                        memberEconomy, conditionOnly),
                        "condition-only market already in economy was skipped");

                Class<?> outerClass = Class.forName("fixture.OuterEconomy", true, loader);
                Object outer = outerClass.getConstructor(reachClass).newInstance(emptyReach);
                require(!StarsectorPrepatcherRuntimeBridge
                                .shouldSkipConditionOnlyMarketOpenEconomyStep(
                                        outer, conditionOnly),
                        "Economy subclass was treated as exact vanilla");
                Class<?> innerClass = Class.forName(
                        "fixture.InnerReachEconomy", true, loader);
                Object inner = innerClass.getConstructor(java.util.List.class)
                        .newInstance(java.util.Collections.emptyList());
                Object economyWithInnerSubclass = economyClass.getConstructor(reachClass)
                        .newInstance(inner);
                require(!StarsectorPrepatcherRuntimeBridge
                                .shouldSkipConditionOnlyMarketOpenEconomyStep(
                                        economyWithInnerSubclass, conditionOnly),
                        "ReachEconomy subclass was treated as exact vanilla");
                require(!StarsectorPrepatcherRuntimeBridge
                                .shouldSkipConditionOnlyMarketOpenEconomyStep(vanilla, null),
                        "null market was skipped");
            }
        } finally {
            deleteTree(root);
        }
    }

    private static Path writeSource(Path root, String relative, String source)
            throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, source, StandardCharsets.UTF_8);
        return path;
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (Exception ignored) { }
            });
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class ChildFirstLoader extends URLClassLoader {
        ChildFirstLoader(URL[] urls, ClassLoader parent) { super(urls, parent); }
        @Override protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null && (name.startsWith("fixture.")
                        || name.startsWith("com.fs.starfarer.campaign.econ."))) {
                    try { loaded = findClass(name); }
                    catch (ClassNotFoundException ignored) { }
                }
                if (loaded == null) loaded = super.loadClass(name, false);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }
}
