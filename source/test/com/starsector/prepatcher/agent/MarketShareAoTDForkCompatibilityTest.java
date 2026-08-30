package com.starsector.prepatcher.agent;

import com.fs.starfarer.api.StarsectorPrepatcherMarketShareRuntime;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.campaign.econ.reach.CommodityMarketData;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.InputStream;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;

/** Real-fork compatibility, future-override fail-closed, and ClassValue retention checks. */
public final class MarketShareAoTDForkCompatibilityTest {
    private static final String FORK_NAME =
            "data.kaysaar.aotd.tot.scripts.commoditydata.AoTDCommodityMarketData";
    private static final String FORK_INTERNAL = FORK_NAME.replace('.', '/');

    private MarketShareAoTDForkCompatibilityTest() {}

    public static void main(String[] args) throws Exception {
        require(args.length == 1,
                "Usage: MarketShareAoTDForkCompatibilityTest <AoTDToolboxTheory.jar>");
        Path jar = Path.of(args[0]);
        require(StarsectorPrepatcherMarketShareRuntime.isEligibleClass(
                        CommodityMarketData.class),
                "vanilla CommodityMarketData is not eligible");
        require(!StarsectorPrepatcherMarketShareRuntime.isEligibleClass(
                        UnknownInheritedCommodityMarketData.class),
                "unknown inherited subclass was admitted to the owned-fork path");

        auditRealFork(jar);
        auditFutureOverrideFallback(jar);
        auditClassLoaderRetention(jar);

        System.out.println("OK market-share-aotd-fork inherited-surface eligible "
                + "future-override-raw classvalue-loader-gc");
    }

    private static void auditRealFork(Path jar) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{jar.toUri().toURL()},
                MarketShareAoTDForkCompatibilityTest.class.getClassLoader())) {
            Class<?> fork = Class.forName(FORK_NAME, false, loader);
            require(fork.getClassLoader() == loader,
                    "AoTD fork class delegated instead of loading from supplied jar");
            require(CommodityMarketData.class.isAssignableFrom(fork),
                    "AoTD fork no longer extends CommodityMarketData");
            require(StarsectorPrepatcherMarketShareRuntime.isEligibleClass(fork),
                    "AoTD fork inherited market-share surface was rejected");

            requireVanillaDeclaration(fork, "getMarketSharePercentPerFaction");
            requireVanillaDeclaration(fork, "getMarketSharePercent", FactionAPI.class);
            requireVanillaDeclaration(fork, "getMarkets");
            requireVanillaDeclaration(fork, "getExportMarketSharePercent", MarketAPI.class);
            requireVanillaDeclaration(fork, "getMarketShareData", MarketAPI.class);
            require(fork.getDeclaredMethod("getExportIncome",
                            Class.forName(
                                    "com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI",
                                    false, fork.getClassLoader()))
                            .getDeclaringClass() == fork,
                    "expected unrelated AoTD getExportIncome override is missing");
        }
    }

    private static void auditFutureOverrideFallback(Path jar) throws Exception {
        byte[] mutated = addGetMarketsOverride(readClass(jar, FORK_INTERNAL));
        ClassLoader parent = MarketShareAoTDForkCompatibilityTest.class.getClassLoader();
        try (URLClassLoader dependencies = new URLClassLoader(
                new URL[]{jar.toUri().toURL()}, parent)) {
            ClassLoader loader = new ClassLoader(dependencies) {
                @Override
                protected Class<?> loadClass(String name, boolean resolve)
                        throws ClassNotFoundException {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null && FORK_NAME.equals(name)) {
                            loaded = defineClass(name, mutated, 0, mutated.length);
                        }
                        if (loaded == null) loaded = super.loadClass(name, false);
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                }
            };
            Class<?> futureFork = Class.forName(FORK_NAME, false, loader);
            require(futureFork.getMethod("getMarkets").getDeclaringClass() == futureFork,
                    "future-override fixture did not own getMarkets");
            require(!StarsectorPrepatcherMarketShareRuntime.isEligibleClass(futureFork),
                    "future AoTD critical override did not fail closed");
        }
    }

    private static void auditClassLoaderRetention(Path jar) throws Exception {
        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        RetentionRefs refs = classifyInDisposableLoader(jar, queue);
        forceCollection(refs, queue);
        require(refs.loader().get() == null,
                "ClassValue compatibility cache retained AoTD classloader");
        require(refs.type().get() == null,
                "ClassValue compatibility cache retained AoTD Class");
    }

    private static RetentionRefs classifyInDisposableLoader(
            Path jar, ReferenceQueue<Object> queue) throws Exception {
        URLClassLoader loader = new URLClassLoader(
                new URL[]{jar.toUri().toURL()},
                MarketShareAoTDForkCompatibilityTest.class.getClassLoader());
        Class<?> fork = Class.forName(FORK_NAME, false, loader);
        require(StarsectorPrepatcherMarketShareRuntime.isEligibleClass(fork),
                "disposable AoTD class was not eligible");
        WeakReference<Object> loaderRef = new WeakReference<>(loader, queue);
        WeakReference<Object> typeRef = new WeakReference<>(fork, queue);
        fork = null;
        loader.close();
        loader = null;
        return new RetentionRefs(loaderRef, typeRef);
    }

    private static void forceCollection(
            RetentionRefs refs, ReferenceQueue<Object> queue) throws InterruptedException {
        for (int attempt = 0;
             attempt < 80 && (refs.loader().get() != null || refs.type().get() != null);
             attempt++) {
            System.gc();
            System.runFinalization();
            byte[][] pressure = new byte[8][];
            for (int index = 0; index < pressure.length; index++) {
                pressure[index] = new byte[256 * 1024];
            }
            queue.remove(10L);
        }
    }

    private static void requireVanillaDeclaration(
            Class<?> type, String name, Class<?>... parameterTypes) throws Exception {
        require(type.getMethod(name, parameterTypes).getDeclaringClass()
                        == CommodityMarketData.class,
                "AoTD fork overrides critical method " + name);
    }

    private static byte[] readClass(Path jar, String internalName) throws Exception {
        try (JarFile input = new JarFile(jar.toFile())) {
            var entry = input.getJarEntry(internalName + ".class");
            require(entry != null, "AoTD class missing from " + jar + ": " + internalName);
            try (InputStream stream = input.getInputStream(entry)) {
                return stream.readAllBytes();
            }
        }
    }

    private static byte[] addGetMarketsOverride(byte[] original) {
        ClassNode node = new ClassNode(Opcodes.ASM8);
        new ClassReader(original).accept(node, 0);
        for (MethodNode method : node.methods) {
            require(!("getMarkets".equals(method.name)
                            && "()Ljava/util/List;".equals(method.desc)),
                    "AoTD fixture already overrides getMarkets");
        }
        MethodNode method = new MethodNode(Opcodes.ASM8, Opcodes.ACC_PUBLIC,
                "getMarkets", "()Ljava/util/List;", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                node.superName, "getMarkets", "()Ljava/util/List;", false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 1;
        method.maxLocals = 1;
        node.methods.add(method);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static final class UnknownInheritedCommodityMarketData
            extends CommodityMarketData {
        UnknownInheritedCommodityMarketData() {
            super("test", null);
        }
    }

    private record RetentionRefs(WeakReference<Object> loader,
                                 WeakReference<Object> type) {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
