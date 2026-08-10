package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.starsector.prepatcher.agent.PrepatcherConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** Reproduces Starsector's parent-runtime/child-mod loader topology. */
public final class AoTDScriptLoaderUiDispatchRuntimeTest {
    private static final String ECONOMY =
            "data.kaysaar.aotd.tot.scripts.economy.AoTDEconomy";

    private enum Mode { CARGO }

    private AoTDScriptLoaderUiDispatchRuntimeTest() {}

    public static void main(String[] args) throws Exception {
        Path configFile = Files.createTempFile(
                "prepatcher-aotd-script-loader", ".properties");
        try {
            Files.writeString(configFile,
                    "patch.campaignCargoNoGlobalEconomyStep=true\n"
                            + "patch.planetConditionMarketOpenNoGlobalEconomyStep=true\n"
                            + "patch.vanillaMarketOpenLocalization=true\n"
                            + "patch.uiMarketMutationRefresh=true\n"
                            + "patch.aotdCleanDeficitPath=true\n",
                    StandardCharsets.UTF_8);
            StarsectorPrepatcherRuntimeBridge.configure(
                    PrepatcherConfig.load(configFile), configFile.getParent());
            StarsectorPrepatcherRuntimeBridge.setAoTDEconomyRestoreCompletionContract(
                    true, "runtime-test");

            URL testClasses = AoTDScriptLoaderUiDispatchRuntimeTest.class
                    .getProtectionDomain().getCodeSource().getLocation();
            ClassLoader parent = AoTDScriptLoaderUiDispatchRuntimeTest.class
                    .getClassLoader();
            try (ChildFirstEconomyLoader forkLoader =
                         new ChildFirstEconomyLoader(new URL[] {testClasses}, parent);
                 ChildFirstEconomyLoader foreignLoader =
                         new ChildFirstEconomyLoader(new URL[] {testClasses}, parent)) {
                Consumer<Object> delivery = callback(
                        forkLoader, Consumer.class, "accept");
                BiFunction<Object, Object, Object> resolver = callback(
                        forkLoader, BiFunction.class, "apply");
                Runnable economyRestore = callback(forkLoader, Runnable.class, "run");
                long negotiated = StarsectorPrepatcherRuntimeBridge
                        .registerAoTDForkContract(
                                "aotd_theory_of_toolbox",
                                StarsectorPrepatcherRuntimeBridge
                                        .AOTD_CURRENT_FORK_VERSION,
                                StarsectorPrepatcherRuntimeBridge
                                        .AOTD_CURRENT_DECLARED_CAPABILITIES,
                                delivery, resolver, economyRestore);
                require(negotiated == StarsectorPrepatcherRuntimeBridge
                                .AOTD_CURRENT_DECLARED_CAPABILITIES,
                        "child-loader fork contract was not negotiated");

                Object economy = Class.forName(ECONOMY, true, forkLoader)
                        .getConstructor().newInstance();
                MarketAPI market = marketFixture(parent);
                require(StarsectorPrepatcherRuntimeBridge
                                .shouldHandleVanillaMarketOpenEconomyStep(
                                        economy, market),
                        "child-loader market-open dispatch fell back globally");
                require(intField(economy, "lastAction") == 1,
                        "child-loader market-open action mismatch");

                require(StarsectorPrepatcherRuntimeBridge
                                .shouldSkipVanillaCargoEconomyStep(
                                        economy, null, true, Mode.CARGO,
                                        null, null),
                        "child-loader synthetic Cargo dispatch fell back globally");
                require(intField(economy, "lastAction") == 2,
                        "child-loader Cargo action mismatch");
                require(intField(economy, "calls") == 2,
                        "unexpected child-loader dispatch count");

                Object foreign = Class.forName(ECONOMY, true, foreignLoader)
                        .getConstructor().newInstance();
                require(!StarsectorPrepatcherRuntimeBridge
                                .shouldHandleVanillaMarketOpenEconomyStep(
                                        foreign, market),
                        "unregistered duplicate loader received owned-fork semantics");
                require(intField(foreign, "calls") == 0,
                        "unregistered duplicate reached the dispatcher");
            }
        } finally {
            Files.deleteIfExists(configFile);
        }
        System.out.println("OK AoTD child-mod loader market-open/Cargo dispatch + "
                + "foreign-loader rejection");
    }

    @SuppressWarnings("unchecked")
    private static <T> T callback(
            ClassLoader loader, Class<T> type, String functionalMethod) {
        return (T) Proxy.newProxyInstance(loader, new Class<?>[] {type},
                (proxy, method, args) -> {
                    if (functionalMethod.equals(method.getName())) return null;
                    if ("toString".equals(method.getName())) return "loader-callback";
                    if ("hashCode".equals(method.getName())) {
                        return Integer.valueOf(System.identityHashCode(proxy));
                    }
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    return null;
                });
    }

    private static MarketAPI marketFixture(ClassLoader loader) {
        return (MarketAPI) Proxy.newProxyInstance(
                loader, new Class<?>[] {MarketAPI.class},
                (proxy, method, args) -> {
                    if ("getId".equals(method.getName())) return "script-loader-market";
                    if ("isPlanetConditionMarketOnly".equals(method.getName())) return false;
                    if ("toString".equals(method.getName())) return "script-loader-market";
                    if ("hashCode".equals(method.getName())) {
                        return Integer.valueOf(System.identityHashCode(proxy));
                    }
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    Class<?> result = method.getReturnType();
                    if (result == boolean.class) return false;
                    if (result == int.class) return 0;
                    if (result == long.class) return 0L;
                    if (result == float.class) return 0f;
                    if (result == double.class) return 0d;
                    return null;
                });
    }

    private static int intField(Object target, String name) throws Exception {
        Field field = target.getClass().getField(name);
        return field.getInt(target);
    }

    private static final class ChildFirstEconomyLoader extends URLClassLoader {
        ChildFirstEconomyLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            if (ECONOMY.equals(name)) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) loaded = findClass(name);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
