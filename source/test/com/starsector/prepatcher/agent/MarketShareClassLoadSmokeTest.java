package com.starsector.prepatcher.agent;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarFile;

/** JVM verifier/linker smoke for the three transformed market-share classes. */
public final class MarketShareClassLoadSmokeTest {
    private MarketShareClassLoadSmokeTest() {}

    public static void main(String[] args) throws Exception {
        require(args.length == 2 || args.length == 3,
                "Usage: MarketShareClassLoadSmokeTest <starfarer_obf.jar> "
                        + "<starfarer.api.jar> [ExerelinCore.jar]");
        PrepatcherConfig config = config();
        Map<String, byte[]> definitions = new LinkedHashMap<>();
        transformInto(definitions, config, Path.of(args[0]),
                PrepatcherTransformer.COMMODITY_MARKET_DATA);
        transformInto(definitions, config, Path.of(args[1]),
                PrepatcherTransformer.PUNITIVE_EXPEDITION_MANAGER);
        if (args.length == 3) {
            transformInto(definitions, config, Path.of(args[2]),
                    PrepatcherTransformer.NEX_PUNITIVE_EXPEDITION_MANAGER);
        }

        ClassLoader parent = MarketShareClassLoadSmokeTest.class.getClassLoader();
        ClassLoader loader = new ClassLoader(parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                    throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        byte[] bytes = definitions.get(name.replace('.', '/'));
                        if (bytes != null) {
                            loaded = defineClass(name, bytes, 0, bytes.length);
                        } else {
                            loaded = super.loadClass(name, false);
                        }
                    }
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
        };

        for (String internalName : definitions.keySet()) {
            Class<?> loaded = Class.forName(internalName.replace('/', '.'), false, loader);
            require(loaded.getClassLoader() == loader,
                    "patched target delegated to parent: " + internalName);
        }
        System.out.println("OK market-share-class-load targets=" + definitions.size());
    }

    private static void transformInto(Map<String, byte[]> definitions,
                                      PrepatcherConfig config,
                                      Path jarPath,
                                      String internalName) throws Exception {
        byte[] original;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entry = jar.getJarEntry(internalName + ".class");
            require(entry != null, "target missing from " + jarPath + ": " + internalName);
            original = jar.getInputStream(entry).readAllBytes();
        }
        byte[] patched = new PrepatcherTransformer(config)
                .transform(null, internalName, null, null, original);
        require(patched != null, "target did not transform: " + internalName);
        definitions.put(internalName, patched);
    }

    private static PrepatcherConfig config() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("patch.marketShareLinearAggregation", "true");
        properties.setProperty("patch.marketShareDataPutElision", "true");
        properties.setProperty("patch.punitivePlayerShareLocalCache", "true");
        properties.setProperty("patch.nexPunitivePlayerShareLocalCache", "true");
        Constructor<PrepatcherConfig> constructor =
                PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);
        return constructor.newInstance(properties);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
