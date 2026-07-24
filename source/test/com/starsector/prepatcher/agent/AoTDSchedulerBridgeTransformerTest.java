package com.starsector.prepatcher.agent;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.FieldNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarFile;

public final class AoTDSchedulerBridgeTransformerTest {
    private static final String BRIDGE_ENTRY =
            "data/kaysaar/aotd/tot/compat/SchedulerBridge.class";
    private static final String STATE_ENTRY =
            "data/kaysaar/aotd/tot/compat/SchedulerBridge$State.class";
    private static final String CONTRACT_ENTRY =
            "data/kaysaar/aotd/tot/compat/PrepatcherContract.class";

    private AoTDSchedulerBridgeTransformerTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected AoTD JAR path");
        Path jarPath = Path.of(args[0]);
        Map<String, byte[]> classes = readClasses(jarPath);
        byte[] original = classes.get(BRIDGE_ENTRY);
        require(original != null, "bridge class missing");
        require(!asConstants(original).contains("java/lang/reflect"),
                "original bridge contains reflection reference");

        ClassLoader modLoader = new ClassLoader(ClassLoader.getSystemClassLoader()) {};
        AoTDSchedulerBridgeTransformer transformer =
                new AoTDSchedulerBridgeTransformer(ClassLoader.getSystemClassLoader());
        byte[] patched = transformer.transform(
                modLoader,
                AoTDSchedulerBridgeTransformer.TARGET,
                null, null, original);
        require(patched != null, "bridge did not patch");
        require(!asConstants(patched).contains("java/lang/reflect"),
                "patched bridge contains reflection reference");
        inspectPatched(patched);
        ClassLoader unrelatedRuntime = new ClassLoader(null) {};
        require(new AoTDSchedulerBridgeTransformer(unrelatedRuntime).transform(
                modLoader, AoTDSchedulerBridgeTransformer.TARGET,
                null, null, original) == null,
                "bridge patched across unrelated sibling loaders");
        require(transformer.transform(modLoader,
                AoTDSchedulerBridgeTransformer.TARGET, null, null, patched) == null,
                "repeated transformation was not idempotent");

        Class<?> unpatchedBridge = new ByteMapLoader(classes, false).loadClass(
                "data.kaysaar.aotd.tot.compat.SchedulerBridge");
        Object unavailable = unpatchedBridge.getMethod("initialize").invoke(null);
        require("PREPATCHER_UNAVAILABLE".equals(String.valueOf(unavailable)),
                "no-agent bridge did not fail open: " + unavailable);
        boolean rejected = false;
        try { unpatchedBridge.getMethod("requireProductionProfile").invoke(null); }
        catch (java.lang.reflect.InvocationTargetException expected) { rejected = expected.getCause() instanceof IllegalStateException; }
        require(rejected, "unpatched bridge accepted the production profile");

        Path configPath = Files.createTempFile("spp-stage8-", ".properties");
        Files.writeString(configPath, "patch.aotdCleanDeficitPath=true\n");
        com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge.configure(
                PrepatcherConfig.load(configPath), Path.of("."));
        Map<String, byte[]> patchedClasses = new HashMap<>(classes);
        patchedClasses.put(BRIDGE_ENTRY, patched);
        Class<?> patchedBridge = new ByteMapLoader(patchedClasses, true).loadClass(
                "data.kaysaar.aotd.tot.compat.SchedulerBridge");
        Object active = patchedBridge.getMethod("initialize").invoke(null);
        require("ACTIVE".equals(String.valueOf(active)),
                "patched bridge did not activate: " + active);
        long capabilities = ((Long) patchedBridge.getMethod(
                "getNegotiatedCapabilities").invoke(null)).longValue();
        require(capabilities == 511L, "unexpected negotiated capabilities: " + capabilities);
        patchedBridge.getMethod("requireProductionProfile").invoke(null);
        patchedBridge.getMethod("publishRuntimeEpoch", long.class, long.class)
                .invoke(null, 11L, 23L);
        Class<?> runtime = Class.forName(
                "com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge");
        long campaignEpoch = ((Long) runtime.getMethod(
                "getAoTDCampaignEpoch").invoke(null)).longValue();
        long economyEpoch = ((Long) runtime.getMethod(
                "getAoTDEconomyEpoch").invoke(null)).longValue();
        require(campaignEpoch == 11L && economyEpoch == 23L,
                "runtime epoch publication failed: campaign=" + campaignEpoch
                        + ", economy=" + economyEpoch);
        long globalToken = ((Long) patchedBridge.getMethod(
                "beforeGlobalBoundary", int.class, boolean.class)
                .invoke(null, 1, false)).longValue();
        require(globalToken > 0L, "global boundary token missing");
        patchedBridge.getMethod("afterGlobalBoundary", long.class, long.class)
                .invoke(null, globalToken, 1L);

        long staleGlobalToken = ((Long) patchedBridge.getMethod(
                "beforeGlobalBoundary", int.class, boolean.class)
                .invoke(null, 2, false)).longValue();
        patchedBridge.getMethod("publishRuntimeEpoch", long.class, long.class)
                .invoke(null, 11L, 24L);
        patchedBridge.getMethod("afterGlobalBoundary", long.class, long.class)
                .invoke(null, staleGlobalToken, 2L);
        long staleGlobalBoundaries = ((Long) runtime.getMethod(
                "getAoTDStaleGlobalBoundaryCount").invoke(null)).longValue();
        require(staleGlobalBoundaries == 1L,
                "stale global boundary was not rejected/accounted: "
                        + staleGlobalBoundaries);

        Object market = new Object();
        Class<?> registry = patchedBridge.getClassLoader().loadClass(
                "data.kaysaar.aotd.tot.compat.MarketRegistry");
        registry.getMethod("registerMarket", String.class, Object.class)
                .invoke(null, "test-market", market);
        runtime.getMethod("publishAoTDMarketTimeDelivered",
                Object.class, float.class, int.class).invoke(null, market, 0.25f, 7);
        long generation = ((Long) patchedBridge.getMethod(
                "getDeliveredMarketGeneration", Object.class).invoke(null, market)).longValue();
        require(generation == 1L, "delivery generation callback failed: " + generation);
        long sequence = ((Long) patchedBridge.getMethod(
                "getLastMarketDeliverySequence", Object.class).invoke(null, market)).longValue();
        require(sequence > 0L, "delivery sequence missing");
        float amount = ((Float) patchedBridge.getMethod(
                "getLastMarketDeliveredAmount", Object.class).invoke(null, market)).floatValue();
        require(amount == 0.25f, "delivery amount mismatch: " + amount);
        patchedBridge.getMethod("acceptMarketMutation", Object.class, int.class,
                long.class, long.class).invoke(null, market, 9, 0L, 1L);
        long signals = ((Long) patchedBridge.getMethod(
                "getDeliveredSignalCount").invoke(null)).longValue();
        require(signals == 1L, "delivery callback signal was not observed: " + signals);
        Object registered = registry.getMethod("lookupMarket", String.class)
                .invoke(null, "test-market");
        require(registered == market, "market registry identity was not preserved");
        int queued = ((Integer) registry.getMethod("queuedCount").invoke(null)).intValue();
        require(queued == 1, "delivery/mutation events did not coalesce: " + queued);
        String registryStatus = String.valueOf(registry.getMethod("statusSummary").invoke(null));
        require(registryStatus.contains("unknownDelivery=0"),
                "delivery bypassed registered market: " + registryStatus);
        require(registryStatus.contains("unknownMutation=0"),
                "mutation bypassed registered market: " + registryStatus);

        // Stage 11: simulate a runtime listener LinkageError after a successful
        // delivery. Prepatcher removes the capability; AoTD must observe the
        // live mask on the next capability check and immediately resynchronize
        // the missed delivered generation instead of waiting for a hard boundary.
        java.lang.reflect.Field runtimeListener = runtime.getDeclaredField("aotdDeliveryListener");
        runtimeListener.setAccessible(true);
        java.util.function.Consumer<Object> broken = ignored -> {
            throw new IllegalAccessError("synthetic runtime downgrade");
        };
        runtimeListener.set(null, broken);
        runtime.getMethod("publishAoTDMarketTimeDelivered",
                Object.class, float.class, int.class).invoke(null, market, 0.5f, 9);
        long runtimeMaskAfterFailure = ((Long) runtime.getMethod(
                "getAoTDNegotiatedCapabilities").invoke(null)).longValue();
        require((runtimeMaskAfterFailure & 2L) == 0L,
                "runtime delivery capability was not removed");
        boolean deliveryStillActive = ((Boolean) patchedBridge.getMethod(
                "hasCapability", long.class).invoke(null, 2L)).booleanValue();
        require(!deliveryStillActive, "AoTD retained stale delivery capability");
        long refreshedMask = ((Long) patchedBridge.getMethod(
                "getNegotiatedCapabilities").invoke(null)).longValue();
        require(refreshedMask == runtimeMaskAfterFailure,
                "AoTD runtime mask did not converge: local=0x"
                        + Long.toHexString(refreshedMask) + ", runtime=0x"
                        + Long.toHexString(runtimeMaskAfterFailure));
        long resyncs = ((Long) patchedBridge.getMethod(
                "getRuntimeCapabilityResynchronizationCount").invoke(null)).longValue();
        long repairedMarkets = ((Long) patchedBridge.getMethod(
                "getRuntimeCapabilityResynchronizedMarketCount").invoke(null)).longValue();
        require(resyncs == 1L, "runtime downgrade did not trigger one resync: " + resyncs);
        require(repairedMarkets == 1L,
                "missed delivery generation was not repaired: " + repairedMarkets);
        boolean production = ((Boolean) patchedBridge.getMethod(
                "hasProductionProfile").invoke(null)).booleanValue();
        require(!production, "production profile remained active after runtime downgrade");

        System.out.println("AoTD scheduler bridge transformer test passed: "
                + registry.getMethod("statusSummary").invoke(null)
                + "; bridge=" + patchedBridge.getMethod("statusSummary").invoke(null));
    }

    private static void inspectPatched(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean marker = false;
        for (FieldNode field : node.fields) {
            if ("smo$patched$aotdSchedulerBridge".equals(field.name)) marker = true;
        }
        require(marker, "patch ownership marker missing");

        MethodNode initialize = null;
        for (MethodNode method : node.methods) {
            if ("initialize".equals(method.name)) initialize = method;
        }
        require(initialize != null, "initialize method missing");
        boolean registrationCall = false;
        boolean activationCall = false;
        for (AbstractInsnNode insn = initialize.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC) continue;
            if ("com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge".equals(call.owner)
                    && "registerAoTDForkContract".equals(call.name)) registrationCall = true;
            if (AoTDSchedulerBridgeTransformer.TARGET.equals(call.owner)
                    && "activateFromPrepatcher".equals(call.name)) activationCall = true;
        }
        require(registrationCall, "direct runtime registration call missing");
        require(activationCall, "activation call missing");

        MethodNode afterMutation = null;
        for (MethodNode method : node.methods) {
            if ("afterMarketMutation".equals(method.name)
                    && "(JLjava/lang/Object;IJ)V".equals(method.desc)) afterMutation = method;
        }
        require(afterMutation != null, "afterMarketMutation missing");
        boolean runtimeCommit = false;
        boolean localQueueCommit = false;
        for (AbstractInsnNode insn = afterMutation.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC) continue;
            if ("com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge".equals(call.owner)
                    && "afterAoTDMarketMutation".equals(call.name)) runtimeCommit = true;
            if (AoTDSchedulerBridgeTransformer.TARGET.equals(call.owner)
                    && "acceptMarketMutation".equals(call.name)) localQueueCommit = true;
        }
        require(runtimeCommit, "runtime mutation commit call missing");
        require(localQueueCommit, "fork-local dirty queue commit call missing");

        MethodNode publishEpoch = null;
        MethodNode runtimeCapabilities = null;
        MethodNode beforeGlobal = null;
        MethodNode afterGlobal = null;
        for (MethodNode method : node.methods) {
            if ("publishRuntimeEpoch".equals(method.name) && "(JJ)V".equals(method.desc)) publishEpoch = method;
            if ("getRuntimeCapabilities".equals(method.name) && "()J".equals(method.desc)) runtimeCapabilities = method;
            if ("beforeGlobalBoundary".equals(method.name) && "(IZ)J".equals(method.desc)) beforeGlobal = method;
            if ("afterGlobalBoundary".equals(method.name) && "(JJ)V".equals(method.desc)) afterGlobal = method;
        }
        require(publishEpoch != null, "publishRuntimeEpoch method missing");
        require(calls(publishEpoch, "publishAoTDRuntimeEpoch"),
                "runtime epoch publication call missing");
        require(runtimeCapabilities != null, "getRuntimeCapabilities method missing");
        require(calls(runtimeCapabilities, "getAoTDNegotiatedCapabilities"),
                "dynamic runtime capability query call missing");
        require(beforeGlobal != null && afterGlobal != null, "global boundary methods missing");
        require(calls(beforeGlobal, "beforeAoTDGlobalBoundary"), "runtime global begin call missing");
        require(calls(afterGlobal, "afterAoTDGlobalBoundary"), "runtime global end call missing");
    }

    private static boolean calls(MethodNode method, String name) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge".equals(call.owner)
                    && name.equals(call.name)) return true;
        }
        return false;
    }

    private static Map<String, byte[]> readClasses(Path jarPath) throws Exception {
        Map<String, byte[]> result = new HashMap<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var jarEntry = entries.nextElement();
                String entry = jarEntry.getName();
                if (!entry.startsWith("data/kaysaar/aotd/tot/")
                        || !entry.endsWith(".class")) continue;
                try (InputStream input = jar.getInputStream(jarEntry)) {
                    result.put(entry, input.readAllBytes());
                }
            }
            require(result.containsKey(BRIDGE_ENTRY), "missing bridge entry");
            require(result.containsKey(STATE_ENTRY), "missing state entry");
            require(result.containsKey(CONTRACT_ENTRY), "missing contract entry");
        }
        return result;
    }

    private static String asConstants(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class ByteMapLoader extends ClassLoader {
        private final Map<String, byte[]> classes;
        private final boolean patched;

        ByteMapLoader(Map<String, byte[]> classes, boolean patched) {
            super(ClassLoader.getSystemClassLoader());
            this.classes = classes;
            this.patched = patched;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            String entry = name.replace('.', '/') + ".class";
            byte[] bytes = classes.get(entry);
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
