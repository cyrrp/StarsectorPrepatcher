package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.jar.JarFile;

import sun.misc.Unsafe;

public final class AoTDSchedulerBridgeTransformerTest {
    private static final String BRIDGE_ENTRY =
            "data/kaysaar/aotd/tot/compat/SchedulerBridge.class";
    private static final String STATE_ENTRY =
            "data/kaysaar/aotd/tot/compat/SchedulerBridge$State.class";
    private static final String CONTRACT_ENTRY =
            "data/kaysaar/aotd/tot/compat/PrepatcherContract.class";
    private static final String ECONOMY_ENTRY =
            "data/kaysaar/aotd/tot/scripts/economy/AoTDEconomy.class";
    private static final String ECONOMY =
            "data/kaysaar/aotd/tot/scripts/economy/AoTDEconomy";
    private static final String UI_COORDINATOR =
            "data/kaysaar/aotd/tot/scripts/economy/AoTDUIEconomyRefreshCoordinator";

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
        require(transformer.transform(modLoader,
                AoTDSchedulerBridgeTransformer.TARGET, null, null,
                withBridgeContract(original, 9, "AOTD_SCHEDULER_BRIDGE_V9")) == null,
                "obsolete V9 bridge was transformed");
        require("UNSUPPORTED_CONTRACT".equals(System.getProperty(
                        "starsector.prepatcher.aotdBridgePatch")),
                "obsolete bridge did not publish unsupported-contract status");
        require(transformer.transform(modLoader,
                AoTDSchedulerBridgeTransformer.TARGET, null, null,
                withBridgeContract(original, 11, "AOTD_SCHEDULER_BRIDGE_V11")) == null,
                "future V11 bridge was transformed");
        require(transformer.transform(modLoader,
                AoTDSchedulerBridgeTransformer.TARGET, null, null,
                withoutMethod(original, "publishRuntimeEpoch", "(JJ)V")) == null,
                "partial current bridge was transformed");
        require(transformer.transform(modLoader,
                AoTDSchedulerBridgeTransformer.TARGET, null, null,
                withoutMethod(original, "economyRestoreCompleteSignal",
                        "()Ljava/lang/Runnable;")) == null,
                "current bridge without restore callback factory was transformed");

        Class<?> unpatchedBridge = new ByteMapLoader(classes, false).loadClass(
                "data.kaysaar.aotd.tot.compat.SchedulerBridge");
        Object unavailable = unpatchedBridge.getMethod("initialize").invoke(null);
        require("PREPATCHER_UNAVAILABLE".equals(String.valueOf(unavailable)),
                "no-agent bridge did not fail open: " + unavailable);
        boolean rejected = false;
        try { unpatchedBridge.getMethod("requireProductionProfile").invoke(null); }
        catch (java.lang.reflect.InvocationTargetException expected) { rejected = expected.getCause() instanceof IllegalStateException; }
        require(rejected, "unpatched bridge accepted the production profile");

        Path configPath = Files.createTempFile("spp-aotd-bridge-", ".properties");
        Files.writeString(configPath,
                "patch.aotdCleanDeficitPath=true\n"
                        + "patch.campaignCargoNoGlobalEconomyStep=true\n");
        com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge.configure(
                PrepatcherConfig.load(configPath), Path.of("."));
        com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge
                .setAoTDEconomyRestoreCompletionContract(true, "bridge-test");
        Map<String, byte[]> patchedClasses = new HashMap<>(classes);
        patchedClasses.put(BRIDGE_ENTRY, patched);
        patchedClasses.put(ECONOMY_ENTRY,
                postCommitEconomyFixture(classes.get(ECONOMY_ENTRY)));
        ByteMapLoader patchedLoader = new ByteMapLoader(patchedClasses, true);
        Class<?> patchedBridge = patchedLoader.loadClass(
                "data.kaysaar.aotd.tot.compat.SchedulerBridge");
        Object active = patchedBridge.getMethod("initialize").invoke(null);
        require("ACTIVE".equals(String.valueOf(active)),
                "patched bridge did not activate: " + active);
        long capabilities = ((Long) patchedBridge.getMethod(
                "getNegotiatedCapabilities").invoke(null)).longValue();
        require(capabilities == 4095L, "unexpected negotiated capabilities: " + capabilities);
        patchedBridge.getMethod("requireProductionProfile").invoke(null);
        Class<?> runtime = Class.forName(
                "com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge");
        long restoreSignalsBefore = ((Long) patchedBridge.getMethod(
                "getEconomyRestoreCompleteSignalCount").invoke(null)).longValue();
        runtime.getMethod("publishAoTDEconomyRestoreComplete").invoke(null);
        long restoreSignalsAfter = ((Long) patchedBridge.getMethod(
                "getEconomyRestoreCompleteSignalCount").invoke(null)).longValue();
        require(restoreSignalsAfter == restoreSignalsBefore + 1L,
                "loader-local restore callback was not delivered exactly once");
        verifyCommittedForkDiagnosticsCannotSelectGlobalFallback(
                patchedLoader);
        patchedBridge.getMethod("publishRuntimeEpoch", long.class, long.class)
                .invoke(null, 11L, 23L);
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

        // Simulate a runtime listener LinkageError after a successful
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

    /**
     * Uses the exact post-commit helper copied from the real fork JAR, its real
     * coordinator/baseline classes, and the active transformed bridge. The
     * minimal owner fixture avoids initializing Starsector's offline-invalid
     * obfuscated Economy superclass. Both diagnostics fail after the action has
     * committed; neither may select the preserved global economy step.
     */
    private static void verifyCommittedForkDiagnosticsCannotSelectGlobalFallback(
            ClassLoader forkLoader) throws Exception {
        Class<?> economyClass = forkLoader.loadClass(
                "data.kaysaar.aotd.tot.scripts.economy.AoTDEconomy");
        Object economy = economyClass.getConstructor().newInstance();

        Class<?> baseline = forkLoader.loadClass(
                "data.kaysaar.aotd.tot.scripts.economy.AoTDEconomySemanticBaseline");
        Field initialized = accessibleField(baseline, "initialized");
        Field enabled = accessibleField(baseline, "enabled");
        Field phases = accessibleField(baseline, "PHASES");
        Field operations = accessibleField(baseline, "OPERATIONS");
        Object originalPhases = phases.get(null);
        Object originalOperations = operations.get(null);
        boolean originalInitialized = initialized.getBoolean(null);
        boolean originalEnabled = enabled.getBoolean(null);

        Field counter = accessibleField(
                com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge.class,
                "DETACHED_CARGO_AOTD_DISPATCHES");
        Object originalCounter = counter.get(null);
        ThrowingOperations throwingOperations = new ThrowingOperations();
        ThrowingPhases throwingPhases = new ThrowingPhases();
        ThrowingLongAdder throwingCounter = new ThrowingLongAdder();
        int globalFallbacks = 0;
        try {
            initialized.setBoolean(null, true);
            enabled.setBoolean(null, true);

            Object scope = baseline.getMethod("begin", String.class)
                    .invoke(null, "synthetic-close-failure");
            putStaticObject(phases, throwingPhases);
            scope.getClass().getMethod("close").invoke(scope);
            require(throwingPhases.triggered.get(),
                    "real fork Scope.close diagnostic fault was not exercised");

            putStaticObject(phases, originalPhases);
            enabled.setBoolean(null, true);
            putStaticObject(operations, throwingOperations);
            putStaticObject(counter, throwingCounter);

            boolean handled = com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge
                    .shouldSkipVanillaCargoEconomyStep(
                            economy, null, true, SyntheticMode.CARGO, null, null);
            if (!handled) globalFallbacks++;

            require(throwingOperations.triggered.get(),
                    "real fork baseline diagnostic fault was not exercised");
            require(throwingCounter.triggered.get(),
                    "Prepatcher post-commit counter fault was not exercised");
            require(handled, "post-commit diagnostic fault changed handled=true to false");
            require(globalFallbacks == 0,
                    "post-commit diagnostic fault selected the global fallback");
            String status = String.valueOf(economyClass.getMethod(
                    "getUiRefreshStatusSummary").invoke(economy));
            require(status.contains("syntheticCargoSkipped=1"),
                    "fork semantic commit was not retained: " + status);
            require(economy.getClass().getClassLoader() == forkLoader,
                    "real fork instance escaped its registered loader");
        } finally {
            putStaticObject(counter, originalCounter);
            putStaticObject(phases, originalPhases);
            putStaticObject(operations, originalOperations);
            initialized.setBoolean(null, originalInitialized);
            enabled.setBoolean(null, originalEnabled);
        }
    }

    private static Field accessibleField(Class<?> owner, String name)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void putStaticObject(Field field, Object value) throws Exception {
        Unsafe unsafe = unsafe();
        Object base = unsafe.staticFieldBase(field);
        long offset = unsafe.staticFieldOffset(field);
        unsafe.putObjectVolatile(base, offset, value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static byte[] postCommitEconomyFixture(byte[] realEconomy) {
        require(realEconomy != null, "real AoTDEconomy class missing");
        ClassNode real = new ClassNode();
        new ClassReader(realEconomy).accept(real, 0);
        String helperDesc = "(L" + UI_COORDINATOR + ";)Z";
        MethodNode realHelper = null;
        for (MethodNode method : real.methods) {
            if ("recordSyntheticCargoSkipNoThrow".equals(method.name)
                    && helperDesc.equals(method.desc)) {
                realHelper = method;
                break;
            }
        }
        require(realHelper != null,
                "real fork synthetic-Cargo post-commit helper missing");

        ClassNode fixture = new ClassNode();
        fixture.version = real.version;
        fixture.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        fixture.name = ECONOMY;
        fixture.superName = "java/lang/Object";
        fixture.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "coordinator", "L" + UI_COORDINATOR + ";", null, null));

        MethodNode constructor = new MethodNode(
                Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new TypeInsnNode(Opcodes.NEW, UI_COORDINATOR));
        constructor.instructions.add(new InsnNode(Opcodes.DUP));
        constructor.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, UI_COORDINATOR, "<init>", "()V", false));
        constructor.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD, ECONOMY, "coordinator",
                "L" + UI_COORDINATOR + ";"));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        fixture.methods.add(constructor);

        MethodNode helper = new MethodNode(
                realHelper.access, realHelper.name, realHelper.desc,
                realHelper.signature,
                realHelper.exceptions == null
                        ? null : realHelper.exceptions.toArray(new String[0]));
        realHelper.accept(helper);
        fixture.methods.add(helper);

        MethodNode dispatcher = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                "dispatchPrepatcherUiEconomyStep",
                "(ILcom/fs/starfarer/api/campaign/econ/MarketAPI;J[Ljava/lang/String;)Z",
                null, null);
        dispatcher.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        dispatcher.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, ECONOMY, "coordinator",
                "L" + UI_COORDINATOR + ";"));
        dispatcher.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, ECONOMY,
                "recordSyntheticCargoSkipNoThrow", helperDesc, false));
        dispatcher.instructions.add(new InsnNode(Opcodes.IRETURN));
        fixture.methods.add(dispatcher);

        MethodNode status = new MethodNode(
                Opcodes.ACC_PUBLIC, "getUiRefreshStatusSummary",
                "()Ljava/lang/String;", null, null);
        status.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        status.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, ECONOMY, "coordinator",
                "L" + UI_COORDINATOR + ";"));
        status.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, UI_COORDINATOR,
                "statusSummary", "()Ljava/lang/String;", false));
        status.instructions.add(new InsnNode(Opcodes.ARETURN));
        fixture.methods.add(status);

        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        fixture.accept(writer);
        return writer.toByteArray();
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
        boolean restoreFactoryCall = false;
        for (AbstractInsnNode insn = initialize.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC) continue;
            if ("com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge".equals(call.owner)
                    && "registerAoTDForkContract".equals(call.name)
                    && ("(Ljava/lang/String;Ljava/lang/String;JLjava/util/function/Consumer;"
                            + "Ljava/util/function/BiFunction;Ljava/lang/Runnable;)J")
                            .equals(call.desc)) registrationCall = true;
            if (AoTDSchedulerBridgeTransformer.TARGET.equals(call.owner)
                    && "economyRestoreCompleteSignal".equals(call.name)
                    && "()Ljava/lang/Runnable;".equals(call.desc)) restoreFactoryCall = true;
            if (AoTDSchedulerBridgeTransformer.TARGET.equals(call.owner)
                    && "activateFromPrepatcher".equals(call.name)) activationCall = true;
        }
        require(registrationCall, "direct runtime registration call missing");
        require(restoreFactoryCall, "loader-local restore callback factory call missing");
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

    private static byte[] withBridgeContract(byte[] original, int schema, String marker) {
        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        for (FieldNode field : node.fields) {
            if ("BRIDGE_SCHEMA".equals(field.name)) field.value = Integer.valueOf(schema);
            if ("BRIDGE_MARKER".equals(field.name)) field.value = marker;
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] withoutMethod(byte[] original, String name, String desc) {
        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        node.methods.removeIf(method -> name.equals(method.name) && desc.equals(method.desc));
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
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

    private enum SyntheticMode { CARGO }

    private static final class ThrowingOperations extends LinkedHashMap<String, Long> {
        private final AtomicBoolean triggered = new AtomicBoolean();

        @Override
        public Long getOrDefault(Object key, Long defaultValue) {
            triggered.set(true);
            throw new LinkageError("synthetic fork baseline failure");
        }
    }

    private static final class ThrowingPhases extends LinkedHashMap<String, Object> {
        private final AtomicBoolean triggered = new AtomicBoolean();

        @Override
        public Object computeIfAbsent(
                String key, Function<? super String, ? extends Object> mappingFunction) {
            triggered.set(true);
            throw new LinkageError("synthetic fork scope-close failure");
        }
    }

    private static final class ThrowingLongAdder extends LongAdder {
        private final AtomicBoolean triggered = new AtomicBoolean();

        @Override
        public void increment() {
            triggered.set(true);
            throw new LinkageError("synthetic Prepatcher counter failure");
        }
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
        protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            String entry = name.replace('.', '/') + ".class";
            if (classes.containsKey(entry)) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) loaded = findClass(name);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
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
