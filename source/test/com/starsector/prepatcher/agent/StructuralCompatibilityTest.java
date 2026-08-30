package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarFile;

/** In-memory regression harness; it never writes modified game classes. */
public final class StructuralCompatibilityTest {
    private static final String HOOKS =
            "com/fs/starfarer/api/StarsectorPrepatcherHooks";
    private static final String HYPERSPACE_HOOKS =
            "com/fs/starfarer/api/StarsectorPrepatcherHyperspaceHooks";
    private static final String TEMP_MOD_HOOKS =
            "com/fs/starfarer/api/StarsectorPrepatcherTempModHooks";
    private static final String CORE_WORLDS_RUNTIME =
            "com/fs/starfarer/api/StarsectorPrepatcherCoreWorldsRuntime";
    private static final String ENTITY_LOOKUP_INDEX_REPAIR_FIELD =
            "spp$entityLookupIndexReady";

    private StructuralCompatibilityTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: StructuralCompatibilityTest <prepatcher.properties> <core.jar>...");
        }
        Path configPath = Path.of(args[0]).toAbsolutePath().normalize();
        Path configRoot = configPath.getParent();
        if (!Files.isDirectory(configRoot.resolve("source"))
                && configRoot.getParent() != null
                && Files.isDirectory(configRoot.getParent().resolve("source"))) {
            configRoot = configRoot.getParent();
        }
        Path testLog = configRoot.resolve(".build").resolve("structural-test.log");
        Files.createDirectories(testLog.getParent());
        PrepatcherLog.initialize(testLog);
        PrepatcherConfig defaults = PrepatcherConfig.load(null);
        require(!defaults.loadingTextReader,
                "patch.loadingTextReader must remain disabled when configuration is missing");
        require(!defaults.startupLogAggregation,
                "patch.startupLogAggregation must remain disabled when configuration is missing");
        require(!defaults.commodityEventModDirtyCache,
                "behavior-changing commodity cache must remain disabled when configuration is missing");
        require(!defaults.marketScheduler,
                "market scheduler must remain disabled when configuration is missing");
        require(!defaults.directMarketObservation,
                "direct-market observation must remain disabled when configuration is missing");
        require(defaults.entityLookupIndexRepair,
                "post-load entity-index repair must remain enabled when configuration is missing");
        require(!defaults.commodityTemporalFastPath,
                "aggressive commodity active set must remain disabled when configuration is missing");
        require(!defaults.marketNoOpCallbacks,
                "aggressive dormant-industry fast path must remain disabled when configuration is missing");
        require(!defaults.tempModExpiryScheduler,
                "behavior-changing temp-mod scheduler must remain disabled when configuration is missing");
        require(!defaults.economyPersistentSnapshots,
                "behavior-changing persistent economy snapshots must remain disabled when configuration is missing");
        require(!defaults.fastForwardGlobalAnimations,
                "fast-forward global animation callbacks must remain disabled when configuration is missing");
        require(!defaults.fastForwardSensorFaders,
                "fast-forward sensor faders must remain disabled when configuration is missing");
        require(!defaults.fastForwardSlipstreamParticles,
                "fast-forward slipstream particles must remain disabled when configuration is missing");
        require(!defaults.fastForwardParticleEmitters,
                "fast-forward particle emitters must remain disabled when configuration is missing");
        PrepatcherConfig config = PrepatcherConfig.load(configPath);

        int jars = 0;
        int classes = 0;
        int methods = 0;
        Set<String> foundTargets = new LinkedHashSet<>();
        for (int i = 1; i < args.length; i++) {
            Path jar = Path.of(args[i]).toAbsolutePath().normalize();
            if (!Files.isRegularFile(jar)) throw new IllegalArgumentException("JAR not found: " + jar);
            Result result = verifyJar(config, jar, foundTargets);
            jars++;
            classes += result.classes;
            methods += result.methods;
            System.out.println("OK structural jar=" + jar + " classes=" + result.classes
                    + " verifiedMethods=" + result.methods);
        }
        Set<String> missingTargets = new LinkedHashSet<>(PrepatcherTransformer.TARGET_CLASSES);
        missingTargets.removeAll(foundTargets);
        require(missingTargets.isEmpty(), "target classes missing from supplied JARs: " + missingTargets);
        require(foundTargets.size() == PrepatcherTransformer.TARGET_CLASSES.size(),
                "duplicate or unexpected target-class accounting");

        require(classes == PrepatcherTransformer.TARGET_CLASSES.size(),
                "not all optimizer target classes were found across supplied JARs: expected "
                        + PrepatcherTransformer.TARGET_CLASSES.size() + ", found " + classes);
        List<Path> suppliedJars = Arrays.stream(args).skip(1)
                .map(value -> Path.of(value).toAbsolutePath().normalize()).toList();
        runLoaderGuardTests(config, suppliedJars);
        runMarketSchedulerStructuralTest(suppliedJars);
        runEconomyAdvancePlanAtomicTests(suppliedJars);
        runMarketAdvancePlanAtomicTests(suppliedJars);
        runSaveMethodPlanAtomicTests(suppliedJars);
        runCoreMarketAdvanceCoverageTest(suppliedJars);
        runConstructionMutationCoverageTest(suppliedJars);
        runRemovedEconomyScratchSnapshotStructuralTest(suppliedJars);
        runHyperspaceViewportBoundsAtomicTest(suppliedJars);
        runNegativeMapRenderStuffTests(config, Path.of(args[1]).toAbsolutePath().normalize());
        runNegativeHitTestGroupTests(config, Path.of(args[1]).toAbsolutePath().normalize());
        runNegativeIntelArrowRenderingTests(Path.of(args[1]).toAbsolutePath().normalize());
        runNegativeEventsPanelReconciliationTests(Path.of(args[1]).toAbsolutePath().normalize());
        runNegativeLifecycleTests(config, Path.of(args[1]).toAbsolutePath().normalize());
        runEntityLookupIndexRepairStructuralTests(
                config, Path.of(args[1]).toAbsolutePath().normalize());
        runExp6OwnershipTests(config, Path.of(args[1]).toAbsolutePath().normalize());
        runInlineFastPathNegativeTests(config,
                Path.of(args[1]).toAbsolutePath().normalize());
        runExp8OwnershipTests(config, suppliedJars);
        System.out.println("OK negative-tests map-renderStuff missing ambiguous missing-allocation unrelated-call unrelated-change"
                + " marker-ownership scratch-scope-tamper wrapper-tamper wrapper-metadata idempotency"
                + " lifecycle-missing-write lifecycle-wrong-source exp6-marker-ownership"
                + " exp6-scratch-scope-tamper exp8-marker-ownership exp8-scratch-scope-tamper"
                + " exp8-late-postcondition-tamper entity-fastpath-prologue"
                + " entity-fastpath-epilogue memory-fastpath-source"
                + " memory-fastpath-order-placement inline-marker-receiver"
                + " inline-marker-branch inline-marker-order loader-guard"
                + " market-scheduler-complete-surface scheduler-scope-tamper"
                + " economy-advance-plan-masks/atomicity/ownership/postcondition"
                + " market-advance-plan-masks/atomicity/ownership/postcondition"
                + " removed-economy-scratch-snapshots"
                + " hyperspace-viewport-bounds-atomic-range/clamp"
                + " market-scheduler-planet-source/predicate-branch/receiver"
                + " market-scheduler-operands"
                + " construction-mutator-coverage semantic-capability-registration-tamper"
                + " map-renderStuff-config-isolation hit-test-group-atomicity"
                + " hit-test-marker-ownership hit-test-scope-tamper hit-test-wrapper-tamper"
                + " intel-arrow-rendering-atomicity intel-arrow-marker-ownership"
                + " intel-arrow-scope-tamper intel-arrow-callback/vector-tamper"
                + " events-panel-reconciliation-atomicity events-panel-marker-ownership"
                + " events-panel-scope-tamper events-panel-hook-tamper"
                + " entity-index-load-repair/transient/idempotency/composition/tamper"
                + " safe-missing-config-defaults composition-final-validation");
        System.out.println("SUMMARY jars=" + jars + " transformedClasses=" + classes
                + " verifiedMethods=" + methods);
    }



    private static void runEconomyAdvancePlanAtomicTests(List<Path> jarPaths)
            throws Exception {
        byte[] original = classBytes(jarPaths, PrepatcherTransformer.ECONOMY);
        Constructor<PrepatcherConfig> constructor =
                PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);

        for (int mask = 1; mask <= 7; mask++) {
            Properties properties = new Properties();
            properties.setProperty("patch.economyPersistentSnapshots",
                    Boolean.toString((mask & 1) != 0));
            properties.setProperty("patch.economyLocationCache",
                    Boolean.toString((mask & 2) != 0));
            properties.setProperty("patch.marketScheduler",
                    Boolean.toString((mask & 4) != 0));
            PrepatcherConfig featureConfig = constructor.newInstance(properties);
            byte[] patched = new PrepatcherTransformer(featureConfig).transform(
                    null, PrepatcherTransformer.ECONOMY, null, null, original);
            require(patched != null, "Economy.advance unified plan did not apply mask=" + mask);
            ClassNode node = read(patched);
            require(hasOwnershipMarker(node, "economyAdvancePlan"),
                    "Economy.advance unified ownership marker missing mask=" + mask);
            require(!hasOwnershipMarker(node, "economyPersistentSnapshots")
                            && !hasOwnershipMarker(node, "economyLocationCache")
                            && !hasOwnershipMarker(node, "marketScheduler"),
                    "Economy.advance emitted legacy split markers mask=" + mask);
            FieldNode maskField = requireField(node, "smo$economyAdvancePlanMask", "I");
            require(maskField.value instanceof Integer value && value == mask,
                    "Economy.advance plan mask field changed for mask=" + mask);

            boolean persistent = (mask & 1) != 0;
            boolean location = (mask & 2) != 0;
            boolean scheduler = (mask & 4) != 0;
            require((findFieldOrNull(node, "smo$economyMarketsSnapshotState") != null)
                            == persistent,
                    "Economy.advance persistent state presence changed mask=" + mask);
            require(countHook(patched, "updateEconomyLocationMapIfNeeded")
                            == (location && !persistent ? 1 : 0),
                    "Economy.advance nonpersistent location hook changed mask=" + mask);
            require(countHook(patched, "updateEconomyLocationMapIfNeededPersistent")
                            == (location && persistent ? 1 : 0),
                    "Economy.advance persistent location hook changed mask=" + mask);
            require(countHook(patched, "advanceMarketScheduled") == (scheduler ? 1 : 0),
                    "Economy.advance scheduler source count changed mask=" + mask);
            require(countHook(patched, "registerMarketSchedulerComponent")
                            == (scheduler ? 1 : 0),
                    "Economy.advance scheduler registration count changed mask=" + mask);

            byte[] repeated = new PrepatcherTransformer(featureConfig).transform(
                    null, PrepatcherTransformer.ECONOMY, null, null, patched);
            require(repeated == null,
                    "Economy.advance unified plan was not idempotent mask=" + mask);
        }

        Properties allProperties = new Properties();
        allProperties.setProperty("patch.economyPersistentSnapshots", "true");
        allProperties.setProperty("patch.economyLocationCache", "true");
        allProperties.setProperty("patch.marketScheduler", "true");
        allProperties.setProperty("patch.economyGroupIndex", "false");
        PrepatcherConfig all = constructor.newInstance(allProperties);

        ClassNode brokenLocation = read(original);
        MethodNode brokenAdvance = method(brokenLocation, "advance", "(F)V");
        MethodInsnNode update = uniqueCall(brokenAdvance, Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/campaign/econ/reach/ReachEconomy",
                "updateLocationMap", "()V");
        update.name = "smo$brokenUpdateLocationMap";
        byte[] rejected = new PrepatcherTransformer(all).transform(
                null, PrepatcherTransformer.ECONOMY, null, null, write(brokenLocation));
        require(rejected == null,
                "Economy.advance plan partially committed when location analysis failed");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.prepatcher.patchStatus."
                                + PrepatcherTransformer.ECONOMY.replace('/', '.')
                                + ".economyAdvancePlan")),
                "Economy.advance failed component was not rejected structurally");

        Properties snapshotsProperties = new Properties();
        snapshotsProperties.setProperty("patch.economyPersistentSnapshots", "true");
        snapshotsProperties.setProperty("patch.economyLocationCache", "false");
        snapshotsProperties.setProperty("patch.marketScheduler", "false");
        snapshotsProperties.setProperty("patch.economyGroupIndex", "false");
        byte[] snapshotsOnly = new PrepatcherTransformer(
                constructor.newInstance(snapshotsProperties)).transform(
                null, PrepatcherTransformer.ECONOMY, null, null, original);
        require(snapshotsOnly != null, "Economy.advance snapshot-only fixture did not apply");
        ClassNode unownedSplit = read(snapshotsOnly);
        require(unownedSplit.fields.removeIf(field -> field.name.equals(
                        "smo$patched$economyAdvancePlan")),
                "Economy.advance test fixture ownership marker missing");
        require(unownedSplit.fields.removeIf(field -> field.name.equals(
                        "smo$economyAdvancePlanMask")),
                "Economy.advance test fixture mask missing");
        rejected = new PrepatcherTransformer(all).transform(
                null, PrepatcherTransformer.ECONOMY, null, null, write(unownedSplit));
        require(rejected == null,
                "Economy.advance unified owner adopted an unowned split component");

        byte[] complete = new PrepatcherTransformer(all).transform(
                null, PrepatcherTransformer.ECONOMY, null, null, original);
        require(complete != null, "Economy.advance all-feature fixture did not apply");
        ClassNode tampered = read(complete);
        MethodInsnNode scheduled = uniqueHook(tampered, "advanceMarketScheduled");
        scheduled.name = "smo$brokenAdvanceMarketScheduled";
        rejected = new PrepatcherTransformer(all).transform(
                null, PrepatcherTransformer.ECONOMY, null, null, write(tampered));
        require(rejected == null,
                "Economy.advance marker accepted a damaged combined postcondition");

        Properties maskThreeProperties = new Properties();
        maskThreeProperties.setProperty("patch.economyPersistentSnapshots", "true");
        maskThreeProperties.setProperty("patch.economyLocationCache", "true");
        maskThreeProperties.setProperty("patch.marketScheduler", "false");
        maskThreeProperties.setProperty("patch.economyGroupIndex", "false");
        rejected = new PrepatcherTransformer(
                constructor.newInstance(maskThreeProperties)).transform(
                null, PrepatcherTransformer.ECONOMY, null, null, complete);
        require(rejected == null,
                "Economy.advance accepted a transformed class under a different feature mask");
        System.out.println("OK economy-advance-plan masks=7 atomic rollback ownership mask postcondition");
    }

    private static void runSaveMethodPlanAtomicTests(List<Path> jarPaths)
            throws Exception {
        byte[] original = classBytes(jarPaths, PrepatcherTransformer.CAMPAIGN_GAME_MANAGER);
        Constructor<PrepatcherConfig> constructor =
                PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);

        // Scenario bits remain config-oriented for readability in this test:
        // 1=output dedup, 2=scheduler, 4=campaign-cache maintenance.
        for (int scenarioMask : new int[]{1, 4, 5, 6, 7}) {
            Properties properties = savePlanProperties(scenarioMask);
            PrepatcherConfig featureConfig = constructor.newInstance(properties);
            byte[] patched = new PrepatcherTransformer(featureConfig).transform(
                    null, PrepatcherTransformer.CAMPAIGN_GAME_MANAGER, null, null, original);
            require(patched != null, "save method patches did not apply scenario=" + scenarioMask);
            ClassNode node = read(patched);

            boolean dedup = (scenarioMask & 1) != 0;
            boolean scheduler = (scenarioMask & 2) != 0;
            boolean maintenance = (scenarioMask & 4) != 0;
            boolean barrier = scheduler || maintenance;
            int barrierMask = (maintenance ? 1 : 0) | (scheduler ? 2 : 0);

            require(hasOwnershipMarker(node, "saveOutputBufferDedup") == dedup,
                    "save output-buffer ownership marker changed scenario=" + scenarioMask);
            require(hasOwnershipMarker(node, "saveMethodPlan") == barrier,
                    "save barrier ownership marker changed scenario=" + scenarioMask);
            require(!hasOwnershipMarker(node, "marketScheduler")
                            && !hasOwnershipMarker(node, "remoteMarketScheduler"),
                    "save method emitted legacy scheduler markers scenario=" + scenarioMask);
            FieldNode maskField = findFieldOrNull(node, "smo$saveMethodPlanMask");
            if (barrier) {
                require(maskField != null && "I".equals(maskField.desc),
                        "save barrier plan mask field missing scenario=" + scenarioMask);
                require(maskField.value instanceof Integer value && value == barrierMask,
                        "save barrier plan mask field changed scenario=" + scenarioMask);
            } else {
                require(maskField == null,
                        "buffer-only patch emitted save barrier mask scenario=" + scenarioMask);
            }

            MethodNode save = method(node, "o00000",
                    "(Lcom/fs/starfarer/campaign/CampaignEngine$o;JZ)Ljava/lang/String;");
            require(countRedundantSaveBuffers(save) == (dedup ? 0 : 1),
                    "save output-buffer component changed scenario=" + scenarioMask);
            require(countCalls(save, "flushMarketSchedulerBeforeSave")
                            == (scheduler ? 1 : 0),
                    "save scheduler barrier count changed scenario=" + scenarioMask);
            require(countHook(patched, "registerMarketSchedulerComponent")
                            == (scheduler ? 1 : 0),
                    "save scheduler registration count changed scenario=" + scenarioMask);
            require(countCalls(save, Opcodes.INVOKESTATIC, HOOKS,
                            "runCacheMaintenance", "(Z)V") == (maintenance ? 1 : 0),
                    "save cache-maintenance barrier count changed scenario=" + scenarioMask);
            if (maintenance) {
                AbstractInsnNode first = firstMeaningful(save);
                require(first != null && first.getOpcode() == Opcodes.ICONST_1,
                        "save forced maintenance flag is not first scenario=" + scenarioMask);
                MethodInsnNode maintenanceCall = (MethodInsnNode) nextMeaningful(first);
                require(maintenanceCall.owner.equals(HOOKS)
                                && maintenanceCall.name.equals("runCacheMaintenance")
                                && maintenanceCall.desc.equals("(Z)V"),
                        "save maintenance barrier is malformed scenario=" + scenarioMask);
                if (scheduler) {
                    MethodInsnNode flush = (MethodInsnNode) nextMeaningful(maintenanceCall);
                    require(flush.owner.equals(HOOKS)
                                    && flush.name.equals("flushMarketSchedulerBeforeSave")
                                    && flush.desc.equals("()V"),
                            "save scheduler barrier does not follow maintenance scenario="
                                    + scenarioMask);
                }
            } else if (scheduler) {
                AbstractInsnNode first = firstMeaningful(save);
                require(first instanceof MethodInsnNode call
                                && call.owner.equals(HOOKS)
                                && call.name.equals("flushMarketSchedulerBeforeSave")
                                && call.desc.equals("()V"),
                        "save scheduler barrier is not first scenario=" + scenarioMask);
            }

            byte[] repeated = new PrepatcherTransformer(featureConfig).transform(
                    null, PrepatcherTransformer.CAMPAIGN_GAME_MANAGER, null, null, patched);
            require(repeated == null,
                    "save method patches were not idempotent scenario=" + scenarioMask);
        }

        Properties allProperties = savePlanProperties(7);
        PrepatcherConfig all = constructor.newInstance(allProperties);

        // The regression addressed by this change: an incompatible optional
        // allocation pattern must skip only deduplication. The correctness
        // barrier and its scheduler capability registration must still commit.
        ClassNode brokenBuffer = read(original);
        MethodNode brokenSave = method(brokenBuffer, "o00000",
                "(Lcom/fs/starfarer/campaign/CampaignEngine$o;JZ)Ljava/lang/String;");
        boolean changed = false;
        for (AbstractInsnNode insn : brokenSave.instructions.toArray()) {
            if (insn instanceof LdcInsnNode ldc
                    && Integer.valueOf(1_048_576).equals(ldc.cst)) {
                ldc.cst = 1_048_575;
                changed = true;
                break;
            }
        }
        require(changed, "save buffer fixture did not find the 1 MiB constant");
        byte[] barrierOnly = new PrepatcherTransformer(all).transform(
                null, PrepatcherTransformer.CAMPAIGN_GAME_MANAGER, null, null,
                write(brokenBuffer));
        require(barrierOnly != null,
                "optional buffer mismatch rolled back the correctness-critical save barrier");
        ClassNode barrierOnlyNode = read(barrierOnly);
        require(hasOwnershipMarker(barrierOnlyNode, "saveMethodPlan"),
                "save barrier ownership marker missing after optional dedup mismatch");
        require(!hasOwnershipMarker(barrierOnlyNode, "saveOutputBufferDedup"),
                "failed optional dedup emitted an ownership marker");
        FieldNode barrierMask = requireField(barrierOnlyNode, "smo$saveMethodPlanMask", "I");
        require(barrierMask.value instanceof Integer value && value == 3,
                "save barrier mask changed after optional dedup mismatch");
        MethodNode barrierOnlySave = method(barrierOnlyNode, "o00000",
                "(Lcom/fs/starfarer/campaign/CampaignEngine$o;JZ)Ljava/lang/String;");
        require(countCalls(barrierOnlySave, "flushMarketSchedulerBeforeSave") == 1,
                "scheduler flush disappeared after optional dedup mismatch");
        require(countCalls(barrierOnlySave, Opcodes.INVOKESTATIC, HOOKS,
                        "runCacheMaintenance", "(Z)V") == 1,
                "cache maintenance disappeared after optional dedup mismatch");
        require(countHook(barrierOnly, "registerMarketSchedulerComponent") == 1,
                "save scheduler capability registration disappeared after dedup mismatch");
        require("APPLIED".equals(System.getProperty(
                        "starsector.prepatcher.patchStatus."
                                + PrepatcherTransformer.CAMPAIGN_GAME_MANAGER.replace('/', '.')
                                + ".saveMethodPlan")),
                "save barrier was not reported APPLIED after optional dedup mismatch");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.prepatcher.patchStatus."
                                + PrepatcherTransformer.CAMPAIGN_GAME_MANAGER.replace('/', '.')
                                + ".saveOutputBufferDedup")),
                "optional buffer mismatch was not isolated to its own patch status");

        // Conversely, a malformed barrier must not be adopted. The optional
        // buffer patch may still apply, but no scheduler capability is emitted.
        ClassNode brokenBarrier = read(original);
        MethodNode foreignSave = method(brokenBarrier, "o00000",
                "(Lcom/fs/starfarer/campaign/CampaignEngine$o;JZ)Ljava/lang/String;");
        InsnList foreignPrefix = new InsnList();
        foreignPrefix.add(new InsnNode(Opcodes.ICONST_0));
        foreignPrefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
                "runCacheMaintenance", "(Z)V", false));
        foreignSave.instructions.insert(foreignPrefix);
        byte[] dedupOnly = new PrepatcherTransformer(all).transform(
                null, PrepatcherTransformer.CAMPAIGN_GAME_MANAGER, null, null,
                write(brokenBarrier));
        require(dedupOnly != null,
                "optional dedup did not remain independently available after barrier rejection");
        ClassNode dedupOnlyNode = read(dedupOnly);
        require(!hasOwnershipMarker(dedupOnlyNode, "saveMethodPlan"),
                "malformed save barrier was adopted");
        require(hasOwnershipMarker(dedupOnlyNode, "saveOutputBufferDedup"),
                "independent output-buffer patch did not apply after barrier rejection");
        require(countHook(dedupOnly, "registerMarketSchedulerComponent") == 0,
                "scheduler capability was registered despite barrier rejection");

        Properties maintenanceBufferProperties = savePlanProperties(5);
        PrepatcherConfig maintenanceBuffer = constructor.newInstance(
                maintenanceBufferProperties);
        byte[] maintenanceBufferBytes = new PrepatcherTransformer(maintenanceBuffer).transform(
                null, PrepatcherTransformer.CAMPAIGN_GAME_MANAGER, null, null, original);
        require(maintenanceBufferBytes != null,
                "save maintenance+buffer fixture did not apply");

        // Removing either independent owner must not allow adoption of the
        // existing hook-shaped state.
        ClassNode unownedBuffer = read(maintenanceBufferBytes);
        require(unownedBuffer.fields.removeIf(field -> field.name.equals(
                        "smo$patched$saveOutputBufferDedup")),
                "save output-buffer ownership marker missing in fixture");
        byte[] rejected = new PrepatcherTransformer(maintenanceBuffer).transform(
                null, PrepatcherTransformer.CAMPAIGN_GAME_MANAGER, null, null,
                write(unownedBuffer));
        require(rejected == null,
                "save output-buffer patch adopted an unowned absent-buffer state");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.prepatcher.patchStatus."
                                + PrepatcherTransformer.CAMPAIGN_GAME_MANAGER.replace('/', '.')
                                + ".saveOutputBufferDedup")),
                "unowned output-buffer state was not rejected structurally");

        ClassNode unownedBarrier = read(maintenanceBufferBytes);
        require(unownedBarrier.fields.removeIf(field -> field.name.equals(
                        "smo$patched$saveMethodPlan")),
                "save barrier ownership marker missing in fixture");
        require(unownedBarrier.fields.removeIf(field -> field.name.equals(
                        "smo$saveMethodPlanMask")),
                "save barrier mask missing in fixture");
        rejected = new PrepatcherTransformer(maintenanceBuffer).transform(
                null, PrepatcherTransformer.CAMPAIGN_GAME_MANAGER, null, null,
                write(unownedBarrier));
        require(rejected == null,
                "save barrier adopted an unowned maintenance hook");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.prepatcher.patchStatus."
                                + PrepatcherTransformer.CAMPAIGN_GAME_MANAGER.replace('/', '.')
                                + ".saveMethodPlan")),
                "unowned save barrier state was not rejected structurally");

        byte[] complete = new PrepatcherTransformer(all).transform(
                null, PrepatcherTransformer.CAMPAIGN_GAME_MANAGER, null, null, original);
        require(complete != null, "save all-feature fixture did not apply");
        ClassNode tampered = read(complete);
        MethodNode tamperedSave = method(tampered, "o00000",
                "(Lcom/fs/starfarer/campaign/CampaignEngine$o;JZ)Ljava/lang/String;");
        MethodInsnNode flush = uniqueCall(tamperedSave, Opcodes.INVOKESTATIC,
                HOOKS, "flushMarketSchedulerBeforeSave", "()V");
        flush.name = "smo$brokenFlushMarketSchedulerBeforeSave";
        rejected = new PrepatcherTransformer(all).transform(
                null, PrepatcherTransformer.CAMPAIGN_GAME_MANAGER, null, null,
                write(tampered));
        require(rejected == null,
                "save barrier marker accepted a damaged combined postcondition");

        rejected = new PrepatcherTransformer(maintenanceBuffer).transform(
                null, PrepatcherTransformer.CAMPAIGN_GAME_MANAGER, null, null, complete);
        require(rejected == null,
                "save barrier accepted a transformed class under a different barrier mask");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.prepatcher.patchStatus."
                                + PrepatcherTransformer.CAMPAIGN_GAME_MANAGER.replace('/', '.')
                                + ".saveMethodPlan")),
                "save barrier mask mismatch was not rejected structurally");
        System.out.println("OK save-method barrier masks=2 optional-dedup isolation ownership postconditions");
    }

    private static Properties savePlanProperties(int mask) {
        Properties properties = new Properties();
        properties.setProperty("patch.saveOutputBufferDedup",
                Boolean.toString((mask & 1) != 0));
        properties.setProperty("patch.marketScheduler",
                Boolean.toString((mask & 2) != 0));
        boolean maintenance = (mask & 4) != 0;
        properties.setProperty("patch.labelSpatialCandidates", Boolean.toString(maintenance));
        properties.setProperty("patch.intelCallbackCache", "false");
        properties.setProperty("patch.intelArrowRendering", "false");
        properties.setProperty("patch.intelEntityIndex", "false");
        properties.setProperty("patch.mapHitTest", "false");
        properties.setProperty("patch.systemNebulaCache", "false");
        properties.setProperty("patch.sampleCacheClearThrottle", "false");
        properties.setProperty("patch.campaignListenerThrottle", "false");
        properties.setProperty("patch.routeJumpPointIndex", "false");
        properties.setProperty("patch.economyLocationCache", "false");
        properties.setProperty("patch.commRelaySystemIndex", "false");
        properties.setProperty("patch.commodityTemporalFastPath", "false");
        properties.setProperty("patch.economyGroupIndex", "false");
        properties.setProperty("scratch.trim.enabled", "false");
        properties.setProperty("patch.starfieldCleanupBuffers", "false");
        return properties;
    }

    private static void runMarketAdvancePlanAtomicTests(List<Path> jarPaths)
            throws Exception {
        byte[] original = classBytes(jarPaths, PrepatcherTransformer.MARKET);
        Constructor<PrepatcherConfig> constructor =
                PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);

        for (int mask = 1; mask <= 15; mask++) {
            Properties properties = new Properties();
            properties.setProperty("patch.economyPersistentSnapshots",
                    Boolean.toString((mask & 1) != 0));
            properties.setProperty("patch.commodityTemporalFastPath",
                    Boolean.toString((mask & 2) != 0));
            properties.setProperty("patch.directMarketObservation",
                    Boolean.toString((mask & 4) != 0));
            properties.setProperty("patch.marketScheduler",
                    Boolean.toString((mask & 8) != 0));
            properties.setProperty("patch.economyGroupIndex", "false");
            PrepatcherConfig featureConfig = constructor.newInstance(properties);
            byte[] patched = new PrepatcherTransformer(featureConfig).transform(
                    null, PrepatcherTransformer.MARKET, null, null, original);
            require(patched != null, "Market.advance unified plan did not apply mask=" + mask);
            ClassNode node = read(patched);
            require(hasOwnershipMarker(node, "marketAdvancePlan"),
                    "Market.advance unified ownership marker missing mask=" + mask);
            require(!hasOwnershipMarker(node, "economyPersistentSnapshots")
                            && !hasOwnershipMarker(node, "commodityTemporalFastPath")
                            && !hasOwnershipMarker(node, "directMarketObservation")
                            && !hasOwnershipMarker(node, "marketScheduler"),
                    "Market.advance emitted legacy split markers mask=" + mask);
            FieldNode maskField = requireField(node, "smo$marketAdvancePlanMask", "I");
            require(maskField.value instanceof Integer value && value == mask,
                    "Market.advance plan mask field changed for mask=" + mask);
            require(countHook(patched, "borrowPersistentSnapshotFrames")
                            == (((mask & 1) != 0) ? 2 : 0),
                    "Market.advance persistent component count changed mask=" + mask);
            require(countHook(patched, "advanceMarketCommodityTemporalState")
                            == (((mask & 2) != 0) ? 1 : 0),
                    "Market.advance commodity component count changed mask=" + mask);
            require(countHook(patched, "observeMarketAdvanceEntry")
                            == (((mask & 4) != 0) ? 1 : 0),
                    "Market.advance observation component count changed mask=" + mask);
            boolean scheduler = (mask & 8) != 0;
            require(countHook(patched, "beginMarketAdvanceInvocation")
                            == (scheduler ? 1 : 0)
                            && countHook(patched, "endMarketAdvanceInvocation")
                            == (scheduler ? 2 : 0)
                            && countHook(patched, "registerConstructionQueueOwner")
                            == (scheduler ? 1 : 0)
                            && countHook(patched, "flushPendingMarketBeforeMutation")
                            == (scheduler ? 3 : 0)
                            && countHook(patched, "registerMarketSchedulerComponent")
                            == (scheduler ? 1 : 0),
                    "Market.advance scheduler semantic component changed mask=" + mask);
            byte[] repeated = new PrepatcherTransformer(featureConfig).transform(
                    null, PrepatcherTransformer.MARKET, null, null, patched);
            require(repeated == null,
                    "Market.advance unified plan was not idempotent mask=" + mask);
        }

        Properties allProperties = new Properties();
        allProperties.setProperty("patch.economyPersistentSnapshots", "true");
        allProperties.setProperty("patch.commodityTemporalFastPath", "true");
        allProperties.setProperty("patch.directMarketObservation", "true");
        allProperties.setProperty("patch.marketScheduler", "true");
        allProperties.setProperty("patch.economyGroupIndex", "false");
        PrepatcherConfig all = constructor.newInstance(allProperties);

        ClassNode brokenCommodity = read(original);
        MethodNode brokenAdvance = method(brokenCommodity, "advance", "(F)V");
        MethodInsnNode commodityGetter = uniqueCall(brokenAdvance, Opcodes.INVOKEVIRTUAL,
                PrepatcherTransformer.MARKET, "getCommodities", "()Ljava/util/List;");
        commodityGetter.name = "smo$brokenGetCommodities";
        byte[] rejected = new PrepatcherTransformer(all).transform(
                null, PrepatcherTransformer.MARKET, null, null, write(brokenCommodity));
        require(rejected == null,
                "Market.advance plan partially committed when commodity analysis failed");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.prepatcher.patchStatus."
                                + PrepatcherTransformer.MARKET.replace('/', '.')
                                + ".marketAdvancePlan")),
                "Market.advance failed component was not rejected structurally");

        Properties snapshotsProperties = new Properties();
        snapshotsProperties.setProperty("patch.economyPersistentSnapshots", "true");
        snapshotsProperties.setProperty("patch.commodityTemporalFastPath", "false");
        snapshotsProperties.setProperty("patch.directMarketObservation", "false");
        snapshotsProperties.setProperty("patch.marketScheduler", "false");
        snapshotsProperties.setProperty("patch.economyGroupIndex", "false");
        byte[] snapshotsOnly = new PrepatcherTransformer(
                constructor.newInstance(snapshotsProperties)).transform(
                null, PrepatcherTransformer.MARKET, null, null, original);
        require(snapshotsOnly != null, "Market.advance snapshot-only fixture did not apply");
        ClassNode unownedSplit = read(snapshotsOnly);
        require(unownedSplit.fields.removeIf(field -> field.name.equals(
                        "smo$patched$marketAdvancePlan")),
                "Market.advance test fixture ownership marker missing");
        require(unownedSplit.fields.removeIf(field -> field.name.equals(
                        "smo$marketAdvancePlanMask")),
                "Market.advance test fixture mask missing");
        rejected = new PrepatcherTransformer(all).transform(
                null, PrepatcherTransformer.MARKET, null, null, write(unownedSplit));
        require(rejected == null,
                "Market.advance unified owner adopted an unowned split component");

        byte[] complete = new PrepatcherTransformer(all).transform(
                null, PrepatcherTransformer.MARKET, null, null, original);
        require(complete != null, "Market.advance all-feature fixture did not apply");
        ClassNode tampered = read(complete);
        MethodInsnNode observation = uniqueHook(tampered, "observeMarketAdvanceEntry");
        observation.name = "smo$brokenObserveMarketAdvanceEntry";
        rejected = new PrepatcherTransformer(all).transform(
                null, PrepatcherTransformer.MARKET, null, null, write(tampered));
        require(rejected == null,
                "Market.advance marker accepted a damaged combined postcondition");

        Properties maskThreeProperties = new Properties();
        maskThreeProperties.setProperty("patch.economyPersistentSnapshots", "true");
        maskThreeProperties.setProperty("patch.commodityTemporalFastPath", "true");
        maskThreeProperties.setProperty("patch.directMarketObservation", "false");
        maskThreeProperties.setProperty("patch.marketScheduler", "false");
        maskThreeProperties.setProperty("patch.economyGroupIndex", "false");
        rejected = new PrepatcherTransformer(
                constructor.newInstance(maskThreeProperties)).transform(
                null, PrepatcherTransformer.MARKET, null, null, complete);
        require(rejected == null,
                "Market.advance accepted a transformed class under a different feature mask");
        System.out.println("OK market-advance-plan masks=15 atomic rollback ownership mask postcondition");
    }

    private static void runCoreMarketAdvanceCoverageTest(List<Path> jarPaths) throws Exception {
        String market = "com/fs/starfarer/api/campaign/econ/MarketAPI";
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put(PrepatcherTransformer.ECONOMY + ".advance(F)V", 1);
        expected.put(PrepatcherTransformer.BASE_CAMPAIGN_ENTITY + ".advance(F)V", 1);
        expected.put(PrepatcherTransformer.PLANET_SURVEY_PANEL
                + ".new(Ljava/lang/String;Z)V", 1);
        expected.put(PrepatcherTransformer.LUDDIC_PATH_BASE_INTEL + ".notifyEnding()V", 1);
        expected.put(PrepatcherTransformer.PIRATE_BASE_INTEL + ".notifyEnding()V", 1);
        expected.put(PrepatcherTransformer.DECIV_TRACKER
                + ".decivilize(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;ZZ)V", 1);
        expected.put(PrepatcherTransformer.DECIV_TRACKER
                + ".removeColony(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;Z)V", 1);
        expected.put(PrepatcherTransformer.PK_CMD
                + ".convertSentinelToColony(Lcom/fs/starfarer/api/campaign/TextPanelAPI;"
                + "Lcom/fs/starfarer/api/campaign/CargoAPI;)Z", 1);

        Map<String, Integer> actual = new LinkedHashMap<>();
        for (Path jarPath : jarPaths) {
            try (JarFile jar = new JarFile(jarPath.toFile())) {
                for (var entries = jar.entries(); entries.hasMoreElements();) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                    ClassNode node = read(jar.getInputStream(entry).readAllBytes());
                    for (MethodNode method : node.methods) {
                        for (AbstractInsnNode insn : method.instructions.toArray()) {
                            if (!(insn instanceof MethodInsnNode call)
                                    || call.getOpcode() != Opcodes.INVOKEINTERFACE
                                    || !call.owner.equals(market)
                                    || !call.name.equals("advance")
                                    || !call.desc.equals("(F)V")) continue;
                            String key = node.name + "." + method.name + method.desc;
                            actual.merge(key, 1, Integer::sum);
                        }
                    }
                }
            }
        }
        require(actual.equals(expected), "core MarketAPI.advance coverage changed; expected="
                + expected + " actual=" + actual);
        System.out.println("OK core-market-advance-coverage scheduled=2 synchronized=6 unknown=0");
    }

    /** Locks the construction mutation inventory to the current game JARs. */
    private static void runConstructionMutationCoverageTest(List<Path> jarPaths)
            throws Exception {
        ClassNode market = read(classBytes(jarPaths, PrepatcherTransformer.MARKET));
        Set<String> marketInventory = publicMethodInventory(market, Set.of(
                "addIndustry", "removeIndustry", "instantiateIndustry"));
        Set<String> expectedMarket = Set.of(
                "addIndustry(Ljava/lang/String;)V",
                "addIndustry(Ljava/lang/String;Ljava/util/List;)V",
                "removeIndustry(Ljava/lang/String;"
                        + "Lcom/fs/starfarer/api/campaign/econ/MarketAPI$MarketInteractionMode;Z)V",
                "instantiateIndustry(Ljava/lang/String;)"
                        + "Lcom/fs/starfarer/api/campaign/econ/Industry;");
        require(marketInventory.equals(expectedMarket),
                "Market construction-mutator inventory drifted: " + marketInventory);

        ClassNode industry = read(classBytes(jarPaths, PrepatcherTransformer.BASE_INDUSTRY));
        Set<String> industryInventory = publicMethodInventory(industry, Set.of(
                "startBuilding", "finishBuildingOrUpgrading", "startUpgrading",
                "cancelUpgrade", "downgrade", "buildNextInQueue"));
        Set<String> expectedIndustry = Set.of(
                "startBuilding()V",
                "finishBuildingOrUpgrading()V",
                "startUpgrading()V",
                "cancelUpgrade()V",
                "downgrade()V",
                "buildNextInQueue(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V");
        require(industryInventory.equals(expectedIndustry),
                "BaseIndustry construction-mutator inventory drifted: "
                        + industryInventory);

        ClassNode queue = read(classBytes(jarPaths, PrepatcherTransformer.CONSTRUCTION_QUEUE));
        Set<String> queueInventory = publicMethodInventory(queue, Set.of(
                "getItems", "setItems", "addToEnd", "moveUp", "moveDown",
                "moveToFront", "moveToBack", "removeItem", "getItem", "hasItem"));
        Set<String> expectedQueue = Set.of(
                "getItems()Ljava/util/List;",
                "setItems(Ljava/util/List;)V",
                "addToEnd(Ljava/lang/String;I)V",
                "moveUp(Ljava/lang/String;)V",
                "moveDown(Ljava/lang/String;)V",
                "moveToFront(Ljava/lang/String;)V",
                "moveToBack(Ljava/lang/String;)V",
                "removeItem(Ljava/lang/String;)V",
                "getItem(Ljava/lang/String;)"
                        + "Lcom/fs/starfarer/api/impl/campaign/econ/impl/"
                        + "ConstructionQueue$ConstructionQueueItem;",
                "hasItem(Ljava/lang/String;)Z");
        require(queueInventory.equals(expectedQueue),
                "ConstructionQueue mutation inventory drifted: " + queueInventory);

        Set<String> marketDeclarations = jarWidePublicMethodInventory(
                jarPaths, expectedMarket);
        Set<String> expectedMarketDeclarations = new LinkedHashSet<>();
        for (String owner : List.of(
                "com/fs/starfarer/api/campaign/econ/MarketAPI",
                PrepatcherTransformer.MARKET,
                "com/fs/starfarer/campaign/econ/PlanetConditionMarket")) {
            for (String signature : expectedMarket) {
                expectedMarketDeclarations.add(owner + "." + signature);
            }
        }
        require(marketDeclarations.equals(expectedMarketDeclarations),
                "JAR-wide Market construction mutation declarations drifted: "
                        + marketDeclarations);

        Set<String> industryDeclarations = jarWidePublicMethodInventory(
                jarPaths, expectedIndustry);
        Set<String> expectedIndustryDeclarations = new LinkedHashSet<>();
        for (String signature : expectedIndustry) {
            expectedIndustryDeclarations.add(PrepatcherTransformer.BASE_INDUSTRY
                    + "." + signature);
            if (!signature.startsWith("buildNextInQueue")) {
                expectedIndustryDeclarations.add(
                        "com/fs/starfarer/api/campaign/econ/Industry." + signature);
            }
        }
        require(industryDeclarations.equals(expectedIndustryDeclarations),
                "JAR-wide BaseIndustry transition declarations drifted: "
                        + industryDeclarations);

        Set<String> queueMutationSignatures = Set.of(
                "setItems(Ljava/util/List;)V",
                "addToEnd(Ljava/lang/String;I)V",
                "moveUp(Ljava/lang/String;)V",
                "moveDown(Ljava/lang/String;)V",
                "moveToFront(Ljava/lang/String;)V",
                "moveToBack(Ljava/lang/String;)V",
                "removeItem(Ljava/lang/String;)V");
        Set<String> queueDeclarations = jarWidePublicMethodInventory(
                jarPaths, queueMutationSignatures);
        Set<String> expectedQueueDeclarations = new LinkedHashSet<>();
        for (String signature : queueMutationSignatures) {
            expectedQueueDeclarations.add(PrepatcherTransformer.CONSTRUCTION_QUEUE
                    + "." + signature);
        }
        require(queueDeclarations.equals(expectedQueueDeclarations),
                "JAR-wide ConstructionQueue declarations drifted: " + queueDeclarations);

        ClassNode planetConditionMarket = read(classBytes(jarPaths,
                "com/fs/starfarer/campaign/econ/PlanetConditionMarket"));
        require(isTrivialVoidNoOp(method(planetConditionMarket,
                        "addIndustry", "(Ljava/lang/String;)V"))
                        && isTrivialVoidNoOp(method(planetConditionMarket,
                        "addIndustry", "(Ljava/lang/String;Ljava/util/List;)V"))
                        && isTrivialVoidNoOp(method(planetConditionMarket,
                        "removeIndustry", "(Ljava/lang/String;"
                                + "Lcom/fs/starfarer/api/campaign/econ/"
                                + "MarketAPI$MarketInteractionMode;Z)V"))
                        && isTrivialNullReturn(method(planetConditionMarket,
                        "instantiateIndustry", "(Ljava/lang/String;)"
                                + "Lcom/fs/starfarer/api/campaign/econ/Industry;")),
                "PlanetConditionMarket construction-method exemption is no longer a no-op");

        System.out.println("OK construction-mutator-coverage market=3+factory"
                + " industry=4+2-internal queue=7+3-read-only jar-wide-noop-exemption");
    }

    private static Set<String> publicMethodInventory(ClassNode node, Set<String> names) {
        Set<String> result = new LinkedHashSet<>();
        for (MethodNode method : node.methods) {
            if (!names.contains(method.name)) continue;
            if ((method.access & Opcodes.ACC_PUBLIC) == 0) continue;
            result.add(method.name + method.desc);
        }
        return result;
    }

    private static Set<String> jarWidePublicMethodInventory(
            List<Path> jarPaths, Set<String> signatures) throws Exception {
        Set<String> result = new LinkedHashSet<>();
        for (Path jarPath : jarPaths) {
            try (JarFile jar = new JarFile(jarPath.toFile())) {
                for (var entries = jar.entries(); entries.hasMoreElements();) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                    ClassNode node = read(jar.getInputStream(entry).readAllBytes());
                    for (MethodNode method : node.methods) {
                        String signature = method.name + method.desc;
                        if ((method.access & Opcodes.ACC_PUBLIC) != 0
                                && signatures.contains(signature)) {
                            result.add(node.name + "." + signature);
                        }
                    }
                }
            }
        }
        return result;
    }

    private static boolean isTrivialVoidNoOp(MethodNode method) {
        List<Integer> opcodes = meaningfulOpcodes(method);
        return opcodes.equals(List.of(Opcodes.RETURN));
    }

    private static boolean isTrivialNullReturn(MethodNode method) {
        List<Integer> opcodes = meaningfulOpcodes(method);
        return opcodes.equals(List.of(Opcodes.ACONST_NULL, Opcodes.ARETURN));
    }

    private static List<Integer> meaningfulOpcodes(MethodNode method) {
        List<Integer> result = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn.getOpcode() >= 0) result.add(insn.getOpcode());
        }
        return result;
    }

    private static void runMarketSchedulerStructuralTest(
            List<Path> jarPaths) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("patch.marketScheduler", "true");
        properties.setProperty("patch.economyLocationCache", "false");
        properties.setProperty("patch.economyPersistentSnapshots", "false");
        properties.setProperty("patch.entityScriptSnapshotReuse", "false");
        properties.setProperty("patch.campaignSnapshotReuse", "false");
        properties.setProperty("patch.directMarketObservation", "false");
        properties.setProperty("patch.saveOutputBufferDedup", "false");
        Constructor<PrepatcherConfig> constructor =
                PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);
        PrepatcherConfig scheduler = constructor.newInstance(properties);

        byte[] stateOriginal = classBytes(jarPaths, PrepatcherTransformer.CAMPAIGN_STATE);
        byte[] state = new PrepatcherTransformer(scheduler).transform(
                null, PrepatcherTransformer.CAMPAIGN_STATE, null, null, stateOriginal);
        require(state != null, "market scheduler did not validate CampaignState batch protocol");
        require(countHook(state, "registerMarketSchedulerComponent") == 1,
                "CampaignState batch protocol did not register its scheduler component");

        ClassNode brokenState = read(stateOriginal);
        MethodNode stateAdvance = method(
                brokenState, "advance", "(FLcom/fs/starfarer/util/A/new;)V");
        ArrayList<MethodInsnNode> setters = new ArrayList<>();
        for (AbstractInsnNode insn : stateAdvance.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals(PrepatcherTransformer.CAMPAIGN_ENGINE)
                    && call.name.equals("setFastForwardIteration")
                    && call.desc.equals("(Z)V")) {
                setters.add(call);
            }
        }
        require(setters.size() == 3, "test input CampaignState batch protocol changed");
        AbstractInsnNode trueArgument = previousMeaningful(setters.get(1));
        stateAdvance.instructions.set(trueArgument, new InsnNode(Opcodes.ICONST_0));
        String stateStatus = "starsector.prepatcher.patchStatus."
                + PrepatcherTransformer.CAMPAIGN_STATE.replace('/', '.') + ".marketScheduler";
        System.clearProperty(stateStatus);
        byte[] brokenResult = new PrepatcherTransformer(scheduler).transform(
                null, PrepatcherTransformer.CAMPAIGN_STATE, null, null, write(brokenState));
        require(brokenResult == null,
                "market scheduler accepted a broken CampaignState batch protocol");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(stateStatus)),
                "broken CampaignState batch protocol did not fail closed structurally");

        byte[] engineOriginal = classBytes(jarPaths, PrepatcherTransformer.CAMPAIGN_ENGINE);
        byte[] engine = new PrepatcherTransformer(scheduler).transform(
                null, PrepatcherTransformer.CAMPAIGN_ENGINE, null, null, engineOriginal);
        require(engine != null, "market scheduler was not applied to CampaignEngine");
        require(countHook(engine, "beginMarketSchedulerTick") == 1
                        && countHook(engine, "endMarketSchedulerTick") >= 2,
                "CampaignEngine did not receive a full-method scheduler tick boundary");
        require(countHook(engine, "marketSchedulerFastForwardIterationChanged") == 1,
                "CampaignEngine did not receive the render-batch boundary hook");
        require(countHook(engine, "beginCampaignEngineChange") == 2
                        && countHook(engine, "completeCampaignEngineChange") >= 2,
                "market scheduler engine component was installed without lifecycle hooks");

        byte[] economyOriginal = classBytes(jarPaths, PrepatcherTransformer.ECONOMY);
        byte[] economy = new PrepatcherTransformer(scheduler).transform(
                null, PrepatcherTransformer.ECONOMY, null, null, economyOriginal);
        require(economy != null, "common market scheduler was not applied to Economy");
        require(countHook(economy, "beginMarketSchedulerTick") == 0
                        && countHook(economy, "advanceMarketScheduled") == 1,
                "Economy retained a second scheduler frame boundary");
        MethodInsnNode economyHook = uniqueHook(read(economy), "advanceMarketScheduled");
        require(isIntegerConstant(previousMeaningful(economyHook), 0),
                "Economy scheduler source is not ECONOMY_LOOP");

        byte[] entityOriginal = classBytes(
                jarPaths, PrepatcherTransformer.BASE_CAMPAIGN_ENTITY);
        byte[] entity = new PrepatcherTransformer(scheduler).transform(
                null, PrepatcherTransformer.BASE_CAMPAIGN_ENTITY, null, null,
                entityOriginal);
        require(entity != null && countHook(entity, "advanceMarketScheduled") == 1,
                "BaseCampaignEntity market scheduler bridge was not applied");
        MethodInsnNode entityHook = uniqueHook(read(entity), "advanceMarketScheduled");
        require(isIntegerConstant(previousMeaningful(entityHook), 1),
                "BaseCampaignEntity scheduler source is not PLANET_CONDITION");

        byte[] managerOriginal = classBytes(
                jarPaths, PrepatcherTransformer.CAMPAIGN_GAME_MANAGER);
        byte[] manager = new PrepatcherTransformer(scheduler).transform(
                null, PrepatcherTransformer.CAMPAIGN_GAME_MANAGER, null, null,
                managerOriginal);
        require(manager != null && countHook(manager, "flushMarketSchedulerBeforeSave") == 1,
                "market scheduler pre-save flush was not applied");
        require(countHook(manager, "runCacheMaintenance") == 1,
                "forced cache maintenance was not applied before market scheduler save flush");

        byte[] militaryOriginal = classBytes(
                jarPaths, PrepatcherTransformer.MILITARY_BASE);
        byte[] military = new PrepatcherTransformer(scheduler).transform(
                null, PrepatcherTransformer.MILITARY_BASE, null, null, militaryOriginal);
        require(military != null && countHook(military,
                        "registerMarketSchedulerComponent") == 1,
                "MilitaryBase replay wrapper did not register its semantic capability");
        byte[] militaryWithoutRegistration = removeSchedulerRegistration(military, 64);
        String militaryStatus = "starsector.prepatcher.patchStatus."
                + PrepatcherTransformer.MILITARY_BASE.replace('/', '.')
                + ".marketStepReplayMilitaryBase";
        System.clearProperty(militaryStatus);
        require(new PrepatcherTransformer(scheduler).transform(
                        null, PrepatcherTransformer.MILITARY_BASE, null, null,
                        militaryWithoutRegistration) == null,
                "MilitaryBase wrapper without capability registration was accepted");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(militaryStatus)),
                "MilitaryBase missing capability registration did not fail closed");

        byte[] queueOriginal = classBytes(
                jarPaths, PrepatcherTransformer.CONSTRUCTION_QUEUE);
        byte[] queue = new PrepatcherTransformer(scheduler).transform(
                null, PrepatcherTransformer.CONSTRUCTION_QUEUE, null, null, queueOriginal);
        require(queue != null && countHook(queue,
                        "registerMarketSchedulerComponent") == 1,
                "ConstructionQueue barriers did not register their semantic capability");
        byte[] queueWithoutRegistration = removeSchedulerRegistration(queue, 1024);
        String queueStatus = "starsector.prepatcher.patchStatus."
                + PrepatcherTransformer.CONSTRUCTION_QUEUE.replace('/', '.')
                + ".marketConstructionMutationBarriers";
        System.clearProperty(queueStatus);
        require(new PrepatcherTransformer(scheduler).transform(
                        null, PrepatcherTransformer.CONSTRUCTION_QUEUE, null, null,
                        queueWithoutRegistration) == null,
                "ConstructionQueue barriers without capability registration were accepted");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(queueStatus)),
                "ConstructionQueue missing capability registration did not fail closed");
    }


    private static void runRemovedEconomyScratchSnapshotStructuralTest(
            List<Path> jarPaths) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("patch.economySnapshotReuse", "true");
        properties.setProperty("patch.economyPersistentSnapshots", "false");
        properties.setProperty("patch.economyLocationCache", "false");
        properties.setProperty("patch.marketScheduler", "false");
        properties.setProperty("patch.directMarketObservation", "false");
        properties.setProperty("patch.commodityTemporalFastPath", "false");
        Constructor<PrepatcherConfig> constructor =
                PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);
        PrepatcherConfig removedOnly = constructor.newInstance(properties);
        require(!removedOnly.economyPersistentSnapshots,
                "removed economy scratch setting enabled persistent snapshots");

        byte[] economy = new PrepatcherTransformer(removedOnly).transform(
                null, PrepatcherTransformer.ECONOMY, null, null,
                classBytes(jarPaths, PrepatcherTransformer.ECONOMY));
        byte[] market = new PrepatcherTransformer(removedOnly).transform(
                null, PrepatcherTransformer.MARKET, null, null,
                classBytes(jarPaths, PrepatcherTransformer.MARKET));
        require(economy == null && market == null,
                "removed economy scratch setting still selected a transformer path");

        try (java.io.InputStream in = StructuralCompatibilityTest.class.getClassLoader()
                .getResourceAsStream(HOOKS + ".class")) {
            require(in != null, "target-loader hooks class bytes are unavailable");
            ClassNode hooks = read(in.readAllBytes());
            require(hooks.methods.stream().noneMatch(method ->
                            method.name.equals("borrowEconomyMarketsSnapshot")
                                    || method.name.equals("borrowEconomyCollectionSnapshot")),
                    "removed economy scratch runtime hooks remain in the payload");
        }
    }

    private static void runHyperspaceViewportBoundsAtomicTest(
            List<Path> jarPaths) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("patch.hyperspaceViewportBounds", "true");
        properties.setProperty("patch.terrainRandomReuse", "false");
        Constructor<PrepatcherConfig> constructor =
                PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);
        PrepatcherConfig boundsOnly = constructor.newInstance(properties);

        byte[] original = classBytes(jarPaths, HyperspacePatches.BASE_TILED);
        String statusKey = "starsector.prepatcher.patchStatus."
                + HyperspacePatches.BASE_TILED.replace('/', '.')
                + ".hyperspaceViewportBounds";

        byte[] patched = new PrepatcherTransformer(boundsOnly).transform(
                null, HyperspacePatches.BASE_TILED, null, null, original);
        require(patched != null, "atomic hyperspace viewport-bounds patch was not applied");
        ClassNode patchedNode = read(patched);
        require(HyperspacePatches.countPatchedViewportBounds(patchedNode) == 2,
                "atomic hyperspace viewport-bounds postcondition is incomplete");
        require(hasOwnershipMarker(patchedNode, "hyperspaceViewportBounds"),
                "atomic hyperspace viewport-bounds ownership marker is missing");
        require(!hasOwnershipMarker(patchedNode, "hyperspaceCulling")
                        && !hasOwnershipMarker(patchedNode, "hyperspaceYClamp"),
                "legacy split hyperspace ownership markers were emitted");
        verifyBytecode(patched);

        ClassNode partialRange = read(original);
        prepatchOneViewportRange(partialRange);
        System.clearProperty(statusKey);
        byte[] partialRangeResult = new PrepatcherTransformer(boundsOnly).transform(
                null, HyperspacePatches.BASE_TILED, null, null, write(partialRange));
        require(partialRangeResult == null,
                "atomic viewport-bounds patch modified a partially corrected range");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(statusKey)),
                "partial vertical-range state was not rejected structurally");
        require(!hasOwnershipMarker(partialRange, "hyperspaceViewportBounds"),
                "partial vertical-range input gained an ownership marker");

        ClassNode partialClamp = read(original);
        prepatchOneViewportClamp(partialClamp);
        System.clearProperty(statusKey);
        byte[] partialClampResult = new PrepatcherTransformer(boundsOnly).transform(
                null, HyperspacePatches.BASE_TILED, null, null, write(partialClamp));
        require(partialClampResult == null,
                "atomic viewport-bounds patch modified a partially corrected clamp");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(statusKey)),
                "partial vertical-clamp state was not rejected structurally");
        require(!hasOwnershipMarker(partialClamp, "hyperspaceViewportBounds"),
                "partial vertical-clamp input gained an ownership marker");

        Properties conflictingLegacy = new Properties();
        conflictingLegacy.setProperty("patch.hyperspaceCulling", "true");
        conflictingLegacy.setProperty("patch.hyperspaceYClamp", "false");
        PrepatcherConfig legacyConflict = constructor.newInstance(conflictingLegacy);
        require(!legacyConflict.hyperspaceViewportBounds,
                "disagreeing legacy hyperspace settings enabled a partial fix");
    }

    private static void runLoaderGuardTests(PrepatcherConfig config,
                                            List<Path> jarPaths) throws Exception {
        byte[] campaign = null;
        for (Path jarPath : jarPaths) {
            try (JarFile jar = new JarFile(jarPath.toFile())) {
                var entry = jar.getJarEntry(PrepatcherTransformer.CAMPAIGN_ENGINE + ".class");
                if (entry != null) {
                    campaign = jar.getInputStream(entry).readAllBytes();
                    break;
                }
            }
        }
        require(campaign != null, "CampaignEngine fixture missing for loader-guard test");

        ClassLoader runtimeLoader = new ClassLoader(null) {};
        ClassLoader foreignLoader = new ClassLoader(null) {};
        String property = "starsector.prepatcher.patchStatus."
                + PrepatcherTransformer.CAMPAIGN_ENGINE.replace('/', '.') + ".classLoader";
        System.clearProperty(property);
        byte[] rejected = new PrepatcherTransformer(config, runtimeLoader).transform(
                foreignLoader, PrepatcherTransformer.CAMPAIGN_ENGINE,
                null, null, campaign);
        require(rejected == null, "loader guard transformed a foreign com.fs target");
        require("SKIPPED_LOADER".equals(System.getProperty(property)),
                "loader guard did not publish SKIPPED_LOADER");

        System.clearProperty(property);
        byte[] accepted = new PrepatcherTransformer(config, runtimeLoader).transform(
                runtimeLoader, PrepatcherTransformer.CAMPAIGN_ENGINE,
                null, null, campaign);
        require(accepted != null, "loader guard rejected its own runtime loader");
        require(System.getProperty(property) == null,
                "matching loader unexpectedly published loader skip status");
    }

    private static Result verifyJar(PrepatcherConfig config, Path jarPath,
                                    Set<String> foundTargets) throws Exception {
        int classes = 0;
        int methods = 0;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (String className : PrepatcherTransformer.TARGET_CLASSES) {
                var entry = jar.getJarEntry(className + ".class");
                if (entry == null) continue;
                require(foundTargets.add(className), "target class occurs in more than one supplied JAR: " + className);                byte[] original = jar.getInputStream(entry).readAllBytes();
                PrepatcherTransformer transformer = new PrepatcherTransformer(config);
                byte[] transformed = transformer.transform(null, className, null, null, original);
                require(transformed != null, "no patch applied to " + className + " from " + jarPath);
                String compositionStatus = "starsector.prepatcher.patchStatus."
                        + className.replace('/', '.') + ".composition";
                require("PASSED".equals(System.getProperty(compositionStatus)),
                        "final composition validation did not pass for " + className);
                assertExpectedHooks(className, transformed);
                assertHookTargetsExist(transformed);
                methods += verifyBytecode(transformed);
                int skippedBefore = integerProperty("starsector.prepatcher.skippedPatches");
                byte[] second = transformer.transform(null, className, null, null, transformed);
                require(second == null, "transform is not idempotent for " + className);
                require("PASSED".equals(System.getProperty(compositionStatus)),
                        "idempotent composition validation did not pass for " + className);
                require(integerProperty("starsector.prepatcher.skippedPatches") == skippedBefore,
                        "already-patched class was reported as structurally incompatible: " + className);
                classes++;
            }
        }
        return new Result(classes, methods);
    }

    private static void runNegativeMapRenderStuffTests(PrepatcherConfig config, Path jarPath) throws Exception {
        byte[] h;
        byte[] a;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            h = jar.getInputStream(jar.getJarEntry(PrepatcherTransformer.H + ".class")).readAllBytes();
            a = jar.getInputStream(jar.getJarEntry(PrepatcherTransformer.A + ".class")).readAllBytes();
        }
        assertMapRenderStuffConfigIsolation(h, a);

        byte[] unrelated = mutateH(h, Mutation.UNRELATED_NOP);
        byte[] unrelatedResult = new PrepatcherTransformer(config)
                .transform(null, PrepatcherTransformer.H, null, null, unrelated);
        require(unrelatedResult != null, "unrelated H change blocked all patches");
        require(countHook(unrelatedResult, "retainAllFast") == 1,
                "unrelated H change blocked retainAllFast");
        verifyBytecode(unrelatedResult);

        byte[] unrelatedCall = mutateH(h, Mutation.UNRELATED_RETAIN);
        byte[] unrelatedCallResult = new PrepatcherTransformer(config)
                .transform(null, PrepatcherTransformer.H, null, null, unrelatedCall);
        require(unrelatedCallResult != null, "unrelated retainAll call blocked all H patches");
        require(countHook(unrelatedCallResult, "retainAllFast") == 1,
                "unrelated retainAll call was confused with map reconciliation");
        verifyBytecode(unrelatedCallResult);

        byte[] missing = mutateH(h, Mutation.MISSING_RETAIN);
        byte[] missingResult = new PrepatcherTransformer(config)
                .transform(null, PrepatcherTransformer.H, null, null, missing);
        require(missingResult != null, "other H patches were lost when retainAll was missing");
        require(countHook(missingResult, "retainAllFast") == 0,
                "missing retainAll site was patched unexpectedly");
        require(countHook(missingResult, "borrowEntityList") == 0
                        && countHook(missingResult, "borrowClassSet") == 0,
                "missing retainAll site left a partial renderStuff patch");
        assertHIndependentHooks(missingResult, "missing retainAll site");
        verifyBytecode(missingResult);

        byte[] ambiguous = mutateH(h, Mutation.DUPLICATE_RETAIN);
        byte[] ambiguousResult = new PrepatcherTransformer(config)
                .transform(null, PrepatcherTransformer.H, null, null, ambiguous);
        require(ambiguousResult != null, "other H patches were lost for ambiguous retainAll");
        require(countHook(ambiguousResult, "retainAllFast") == 0,
                "ambiguous retainAll sites were patched unexpectedly");
        require(countHook(ambiguousResult, "borrowEntityList") == 0
                        && countHook(ambiguousResult, "borrowClassSet") == 0,
                "ambiguous retainAll sites left a partial renderStuff patch");
        assertHIndependentHooks(ambiguousResult, "ambiguous retainAll sites");
        verifyBytecode(ambiguousResult);

        byte[] missingAllocation = mutateH(h, Mutation.MISSING_CLASS_SET);
        byte[] missingAllocationResult = new PrepatcherTransformer(config)
                .transform(null, PrepatcherTransformer.H, null, null, missingAllocation);
        require(missingAllocationResult != null,
                "independent H patches were lost when a render scratch allocation was missing");
        require(countHook(missingAllocationResult, "retainAllFast") == 0
                        && countHook(missingAllocationResult, "borrowEntityList") == 0
                        && countHook(missingAllocationResult, "borrowClassSet") == 0,
                "missing class-set allocation produced a partial renderStuff patch");
        assertHIndependentHooks(missingAllocationResult, "missing class-set allocation");
        verifyBytecode(missingAllocationResult);

        runOwnershipAndWrapperTests(config, h);
    }

    private static void assertMapRenderStuffConfigIsolation(byte[] h, byte[] a) throws Exception {
        Constructor<PrepatcherConfig> constructor =
                PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);

        Properties hitOnlyProperties = new Properties();
        hitOnlyProperties.setProperty("patch.mapRenderStuff", "false");
        hitOnlyProperties.setProperty("patch.mapHitTest", "true");
        hitOnlyProperties.setProperty("patch.labelSpatialCandidates", "false");
        hitOnlyProperties.setProperty("patch.intelEntityIndex", "false");
        hitOnlyProperties.setProperty("patch.systemNebulaCache", "false");
        hitOnlyProperties.setProperty("patch.sampleCacheClearThrottle", "false");
        hitOnlyProperties.setProperty("patch.gridLineCap", "false");
        hitOnlyProperties.setProperty("patch.intelCallbackCache", "false");
        PrepatcherConfig hitOnly = constructor.newInstance(hitOnlyProperties);
        require(new PrepatcherTransformer(hitOnly).transform(
                        null, PrepatcherTransformer.H, null, null, h) == null,
                "patch.mapHitTest unexpectedly selected H.renderStuff");
        byte[] patchedA = new PrepatcherTransformer(hitOnly).transform(
                null, PrepatcherTransformer.A, null, null, a);
        require(patchedA != null && countHook(patchedA, "borrowHitList") == 1
                        && countHook(patchedA, "borrowHitPoint") == 1
                        && countHook(patchedA, "hitTestCached") == 1,
                "patch.mapHitTest did not own the complete A hit-test group");

        Properties renderOnlyProperties = new Properties();
        renderOnlyProperties.setProperty("patch.mapRenderStuff", "true");
        renderOnlyProperties.setProperty("patch.mapHitTest", "false");
        renderOnlyProperties.setProperty("patch.labelSpatialCandidates", "false");
        renderOnlyProperties.setProperty("patch.intelEntityIndex", "false");
        renderOnlyProperties.setProperty("patch.systemNebulaCache", "false");
        renderOnlyProperties.setProperty("patch.sampleCacheClearThrottle", "false");
        renderOnlyProperties.setProperty("patch.gridLineCap", "false");
        renderOnlyProperties.setProperty("patch.intelCallbackCache", "false");
        PrepatcherConfig renderOnly = constructor.newInstance(renderOnlyProperties);
        byte[] patchedH = new PrepatcherTransformer(renderOnly).transform(
                null, PrepatcherTransformer.H, null, null, h);
        require(patchedH != null && countHook(patchedH, "retainAllFast") == 1
                        && countHook(patchedH, "borrowEntityList") == 1
                        && countHook(patchedH, "borrowClassSet") == 1,
                "patch.mapRenderStuff did not own its complete H.renderStuff group");
        require(new PrepatcherTransformer(renderOnly).transform(
                        null, PrepatcherTransformer.A, null, null, a) == null,
                "patch.mapRenderStuff unexpectedly selected A hit-test group");
    }

    private static void runNegativeHitTestGroupTests(
            PrepatcherConfig config, Path jarPath) throws Exception {
        byte[] original;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            original = jar.getInputStream(jar.getJarEntry(
                    PrepatcherTransformer.A + ".class")).readAllBytes();
        }
        Constructor<PrepatcherConfig> constructor =
                PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);

        Properties hitOnlyProperties = new Properties();
        hitOnlyProperties.setProperty("patch.mapHitTest", "true");
        hitOnlyProperties.setProperty("patch.intelCallbackCache", "false");
        PrepatcherConfig hitOnly = constructor.newInstance(hitOnlyProperties);
        String statusKey = "starsector.prepatcher.patchStatus."
                + PrepatcherTransformer.A.replace('/', '.') + ".mapHitTest";

        byte[] patched = new PrepatcherTransformer(hitOnly).transform(
                null, PrepatcherTransformer.A, null, null, original);
        require(patched != null, "atomic map hit-test group did not apply");
        ClassNode patchedNode = read(patched);
        require(hasOwnershipMarker(patchedNode, "mapHitTest"),
                "map hit-test ownership marker is missing");
        require(countHook(patched, "borrowHitList") == 1
                        && countHook(patched, "borrowHitPoint") == 1
                        && countHook(patched, "hitTestCached") == 1,
                "map hit-test postcondition is incomplete");
        verifyBytecode(patched);

        int skippedBefore = integerProperty("starsector.prepatcher.skippedPatches");
        require(new PrepatcherTransformer(hitOnly).transform(
                        null, PrepatcherTransformer.A, null, null, patched) == null,
                "map hit-test group is not idempotent");
        require(integerProperty("starsector.prepatcher.skippedPatches") == skippedBefore,
                "idempotent map hit-test group was reported as incompatible");

        ClassNode unowned = read(patched);
        require(unowned.fields.removeIf(field -> field.name.equals("smo$patched$mapHitTest")),
                "map hit-test marker was not emitted");
        System.clearProperty(statusKey);
        require(new PrepatcherTransformer(hitOnly).transform(
                        null, PrepatcherTransformer.A, null, null, write(unowned)) == null,
                "foreign complete hit-test group was modified");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(statusKey)),
                "foreign complete hit-test group was not rejected structurally");

        ClassNode unscoped = read(patched);
        removeScratchScope(method(unscoped, "smo$originalHitTest",
                "(FFF)Lcom/fs/starfarer/api/campaign/SectorEntityToken;"));
        System.clearProperty(statusKey);
        require(new PrepatcherTransformer(hitOnly).transform(
                        null, PrepatcherTransformer.A, null, null, write(unscoped)) == null,
                "owned hit-test hooks without scope were modified");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(statusKey)),
                "owned hit-test hooks without scope were accepted");

        ClassNode partial = read(patched);
        MethodInsnNode pointHook = uniqueHook(partial, "borrowHitPoint");
        pointHook.name = "borrowArrowVector";
        System.clearProperty(statusKey);
        require(new PrepatcherTransformer(hitOnly).transform(
                        null, PrepatcherTransformer.A, null, null, write(partial)) == null,
                "partial hit-test allocation group was modified");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(statusKey)),
                "partial hit-test allocation group was accepted");

        ClassNode tamperedWrapper = read(patched);
        method(tamperedWrapper, "OO0000",
                "(FFF)Lcom/fs/starfarer/api/campaign/SectorEntityToken;")
                .instructions.insert(new InsnNode(Opcodes.NOP));
        System.clearProperty(statusKey);
        require(new PrepatcherTransformer(hitOnly).transform(
                        null, PrepatcherTransformer.A, null, null, write(tamperedWrapper)) == null,
                "tampered hit-test wrapper was modified");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(statusKey)),
                "tampered hit-test wrapper was accepted");

        Properties withIndependentProperties = new Properties();
        withIndependentProperties.setProperty("patch.mapHitTest", "true");
        withIndependentProperties.setProperty("patch.intelCallbackCache", "true");
        PrepatcherConfig withIndependent = constructor.newInstance(withIndependentProperties);
        byte[] missingAllocation = mutateAHitTest(original, HitMutation.MISSING_HIT_LIST);
        byte[] missingAllocationResult = new PrepatcherTransformer(withIndependent).transform(
                null, PrepatcherTransformer.A, null, null, missingAllocation);
        require(missingAllocationResult != null,
                "missing hit-list allocation blocked independent A patch");
        require(countHook(missingAllocationResult, "getMapLocationCached") == 1,
                "missing hit-list allocation cancelled independent Intel callback patch");
        require(countHook(missingAllocationResult, "borrowHitList") == 0
                        && countHook(missingAllocationResult, "borrowHitPoint") == 0
                        && countHook(missingAllocationResult, "hitTestCached") == 0,
                "missing hit-list allocation produced a partial hit-test group");
        verifyBytecode(missingAllocationResult);

        byte[] missingSemantic = mutateAHitTest(original, HitMutation.MISSING_FACTOR_CALL);
        byte[] missingSemanticResult = new PrepatcherTransformer(withIndependent).transform(
                null, PrepatcherTransformer.A, null, null, missingSemantic);
        require(missingSemanticResult != null,
                "missing hit-test semantic anchor blocked independent A patch");
        require(countHook(missingSemanticResult, "getMapLocationCached") == 1,
                "missing hit-test semantic anchor cancelled independent Intel callback patch");
        require(countHook(missingSemanticResult, "borrowHitList") == 0
                        && countHook(missingSemanticResult, "borrowHitPoint") == 0
                        && countHook(missingSemanticResult, "hitTestCached") == 0,
                "missing semantic anchor produced a partial hit-test group");
        verifyBytecode(missingSemanticResult);

        Properties legacyProperties = new Properties();
        legacyProperties.setProperty("patch.mapHitTest", "false");
        legacyProperties.setProperty("patch.scratchCollections", "true");
        legacyProperties.setProperty("patch.hoverHitTestCache", "true");
        legacyProperties.setProperty("patch.intelCallbackCache", "false");
        PrepatcherConfig legacyOnly = constructor.newInstance(legacyProperties);
        require(new PrepatcherTransformer(legacyOnly).transform(
                        null, PrepatcherTransformer.A, null, null, original) == null,
                "removed split hit-test keys still select a transformer path");
    }

    private static void runNegativeIntelArrowRenderingTests(Path jarPath)
            throws Exception {
        byte[] original;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            original = jar.getInputStream(jar.getJarEntry(
                    PrepatcherTransformer.Z + ".class")).readAllBytes();
        }
        Constructor<PrepatcherConfig> constructor =
                PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);

        Properties groupProperties = new Properties();
        groupProperties.setProperty("patch.intelArrowRendering", "true");
        groupProperties.setProperty("patch.intelCallbackCache", "false");
        PrepatcherConfig groupOnly = constructor.newInstance(groupProperties);
        String statusKey = "starsector.prepatcher.patchStatus."
                + PrepatcherTransformer.Z.replace('/', '.') + ".intelArrowRendering";

        byte[] patched = new PrepatcherTransformer(groupOnly).transform(
                null, PrepatcherTransformer.Z, null, null, original);
        require(patched != null, "Intel-arrow rendering group did not apply");
        require(countHook(patched, "getArrowDataCached") == 1
                        && countHook(patched, "borrowArrowVector") == 2,
                "Intel-arrow rendering postcondition is incomplete");
        require(hasOwnershipMarker(read(patched), "intelArrowRendering"),
                "Intel-arrow rendering marker is missing");
        assertScratchScope(patched, "o00000", "(FF)V");
        verifyBytecode(patched);

        int skippedBefore = integerProperty("starsector.prepatcher.skippedPatches");
        require(new PrepatcherTransformer(groupOnly).transform(
                        null, PrepatcherTransformer.Z, null, null, patched) == null,
                "Intel-arrow rendering group is not idempotent");
        require(integerProperty("starsector.prepatcher.skippedPatches") == skippedBefore,
                "idempotent Intel-arrow rendering was reported as incompatible");

        ClassNode unowned = read(patched);
        require(unowned.fields.removeIf(field -> field.name.equals(
                        "smo$patched$intelArrowRendering")),
                "Intel-arrow rendering marker was not emitted");
        System.clearProperty(statusKey);
        require(new PrepatcherTransformer(groupOnly).transform(
                        null, PrepatcherTransformer.Z, null, null, write(unowned)) == null,
                "foreign complete Intel-arrow rendering group was modified");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(statusKey)),
                "foreign complete Intel-arrow rendering group was not rejected");

        ClassNode unscoped = read(patched);
        removeScratchScope(method(unscoped, "o00000", "(FF)V"));
        System.clearProperty(statusKey);
        require(new PrepatcherTransformer(groupOnly).transform(
                        null, PrepatcherTransformer.Z, null, null, write(unscoped)) == null,
                "Intel-arrow hooks without scope were modified");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(statusKey)),
                "Intel-arrow hooks without scope were accepted");

        ClassNode partialCallback = read(patched);
        uniqueHook(partialCallback, "getArrowDataCached").name = "getMapLocationCached";
        System.clearProperty(statusKey);
        require(new PrepatcherTransformer(groupOnly).transform(
                        null, PrepatcherTransformer.Z, null, null,
                        write(partialCallback)) == null,
                "partial Intel-arrow callback state was modified");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(statusKey)),
                "partial Intel-arrow callback state was accepted");

        ClassNode partialVector = read(patched);
        MethodInsnNode firstVectorHook = null;
        for (MethodNode candidate : partialVector.methods) {
            for (AbstractInsnNode insn : candidate.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals(HOOKS)
                        && call.name.equals("borrowArrowVector")) {
                    firstVectorHook = call;
                    break;
                }
            }
            if (firstVectorHook != null) break;
        }
        require(firstVectorHook != null, "Intel-arrow vector hook missing in test input");
        firstVectorHook.name = "borrowHitPoint";
        System.clearProperty(statusKey);
        require(new PrepatcherTransformer(groupOnly).transform(
                        null, PrepatcherTransformer.Z, null, null,
                        write(partialVector)) == null,
                "partial Intel-arrow vector state was modified");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(statusKey)),
                "partial Intel-arrow vector state was accepted");

        for (ArrowMutation mutation : ArrowMutation.values()) {
            byte[] mutated = mutateIntelArrowRendering(original, mutation);
            System.clearProperty(statusKey);
            require(new PrepatcherTransformer(groupOnly).transform(
                            null, PrepatcherTransformer.Z, null, null, mutated) == null,
                    mutation + " unexpectedly produced a partial Intel-arrow group");
            require("SKIPPED_STRUCTURAL".equals(System.getProperty(statusKey)),
                    mutation + " was not rejected structurally");
            require(countHook(mutated, "getArrowDataCached") == 0
                            && countHook(mutated, "borrowArrowVector") == 0,
                    mutation + " test input already contains optimizer hooks");
            verifyBytecode(mutated);
        }

        Properties callbackOnlyProperties = new Properties();
        callbackOnlyProperties.setProperty("patch.intelArrowRendering", "false");
        callbackOnlyProperties.setProperty("patch.intelCallbackCache", "true");
        PrepatcherConfig callbackOnly = constructor.newInstance(callbackOnlyProperties);
        require(new PrepatcherTransformer(callbackOnly).transform(
                        null, PrepatcherTransformer.Z, null, null, original) == null,
                "patch.intelCallbackCache still selects the Z.o00000 surface");

        Properties legacyProperties = new Properties();
        legacyProperties.setProperty("patch.intelArrowRendering", "false");
        legacyProperties.setProperty("patch.intelCallbackCache", "false");
        legacyProperties.setProperty("patch.arrowVectorPool", "true");
        PrepatcherConfig legacyOnly = constructor.newInstance(legacyProperties);
        require(new PrepatcherTransformer(legacyOnly).transform(
                        null, PrepatcherTransformer.Z, null, null, original) == null,
                "removed split Intel-arrow key still selects a transformer path");
    }

    private static byte[] mutateIntelArrowRendering(byte[] source, ArrowMutation mutation) {
        ClassNode node = read(source);
        MethodNode method = method(node, "o00000", "(FF)V");
        if (mutation == ArrowMutation.MISSING_CALLBACK) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEINTERFACE
                        && call.owner.equals(
                        "com/fs/starfarer/api/campaign/comm/IntelInfoPlugin")
                        && call.name.equals("getArrowData")
                        && call.desc.equals("(Lcom/fs/starfarer/api/ui/SectorMapAPI;)Ljava/util/List;")) {
                    call.name = "getArrowDataChanged";
                    return write(node);
                }
            }
            throw new AssertionError("Intel-arrow callback site not found in test input");
        }

        TypeInsnNode allocation = null;
        MethodInsnNode constructorCall = null;
        VarInsnNode store = null;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof TypeInsnNode candidate)
                    || candidate.getOpcode() != Opcodes.NEW
                    || !candidate.desc.equals("org/lwjgl/util/vector/Vector2f")) continue;
            for (AbstractInsnNode cursor = candidate.getNext(); cursor != null;
                 cursor = cursor.getNext()) {
                if (cursor instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESPECIAL
                        && call.owner.equals("org/lwjgl/util/vector/Vector2f")
                        && call.name.equals("<init>") && call.desc.equals("(FF)V")) {
                    AbstractInsnNode next = nextMeaningful(call);
                    if (next instanceof VarInsnNode var && var.getOpcode() == Opcodes.ASTORE) {
                        allocation = candidate;
                        constructorCall = call;
                        store = var;
                    }
                    break;
                }
                if (cursor.getOpcode() == Opcodes.NEW && cursor != candidate) break;
            }
            if (allocation != null) break;
        }
        require(allocation != null && constructorCall != null && store != null,
                "Intel-arrow vector allocation missing in test input");
        method.instructions.insertBefore(store, new InsnNode(Opcodes.ACONST_NULL));
        AbstractInsnNode cursor = allocation;
        while (cursor != null) {
            AbstractInsnNode next = cursor.getNext();
            method.instructions.remove(cursor);
            if (cursor == constructorCall) break;
            cursor = next;
        }
        return write(node);
    }

    private enum ArrowMutation {
        MISSING_CALLBACK,
        MISSING_VECTOR
    }

    private static void runNegativeEventsPanelReconciliationTests(Path jarPath)
            throws Exception {
        byte[] original;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            original = jar.getInputStream(jar.getJarEntry(
                    PrepatcherTransformer.EVENTS + ".class")).readAllBytes();
        }
        Constructor<PrepatcherConfig> constructor =
                PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);

        Properties groupProperties = new Properties();
        groupProperties.setProperty("patch.intelReconciliation", "true");
        groupProperties.setProperty("patch.intelCallbackCache", "false");
        PrepatcherConfig groupOnly = constructor.newInstance(groupProperties);
        String statusKey = "starsector.prepatcher.patchStatus."
                + PrepatcherTransformer.EVENTS.replace('/', '.') + ".intelReconciliation";

        byte[] patched = new PrepatcherTransformer(groupOnly).transform(
                null, PrepatcherTransformer.EVENTS, null, null, original);
        require(patched != null, "EventsPanel reconciliation group did not apply");
        require(countHook(patched, "fastContains") == 1
                        && countHook(patched, "existingIntelIconCandidates") == 1,
                "EventsPanel reconciliation postcondition is incomplete");
        require(hasOwnershipMarker(read(patched), "intelReconciliation"),
                "EventsPanel reconciliation marker is missing");
        assertScratchScope(patched, "addMissingIconsAndRows", "()V");
        verifyBytecode(patched);

        int skippedBefore = integerProperty("starsector.prepatcher.skippedPatches");
        require(new PrepatcherTransformer(groupOnly).transform(
                        null, PrepatcherTransformer.EVENTS, null, null, patched) == null,
                "EventsPanel reconciliation group is not idempotent");
        require(integerProperty("starsector.prepatcher.skippedPatches") == skippedBefore,
                "idempotent EventsPanel reconciliation was reported as incompatible");

        ClassNode unowned = read(patched);
        require(unowned.fields.removeIf(field -> field.name.equals(
                        "smo$patched$intelReconciliation")),
                "EventsPanel reconciliation marker was not emitted");
        System.clearProperty(statusKey);
        require(new PrepatcherTransformer(groupOnly).transform(
                        null, PrepatcherTransformer.EVENTS, null, null, write(unowned)) == null,
                "foreign complete EventsPanel reconciliation group was modified");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(statusKey)),
                "foreign complete EventsPanel reconciliation group was not rejected");

        ClassNode unscoped = read(patched);
        removeScratchScope(method(unscoped, "addMissingIconsAndRows", "()V"));
        System.clearProperty(statusKey);
        require(new PrepatcherTransformer(groupOnly).transform(
                        null, PrepatcherTransformer.EVENTS, null, null, write(unscoped)) == null,
                "EventsPanel reconciliation hooks without scope were modified");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(statusKey)),
                "EventsPanel reconciliation hooks without scope were accepted");

        ClassNode partialContains = read(patched);
        uniqueHook(partialContains, "fastContains").name = "existingIntelIconCandidates";
        System.clearProperty(statusKey);
        require(new PrepatcherTransformer(groupOnly).transform(
                        null, PrepatcherTransformer.EVENTS, null, null,
                        write(partialContains)) == null,
                "partial EventsPanel membership hook state was modified");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(statusKey)),
                "partial EventsPanel membership hook state was accepted");

        ClassNode partialCandidates = read(patched);
        uniqueHook(partialCandidates, "existingIntelIconCandidates").name = "fastContains";
        System.clearProperty(statusKey);
        require(new PrepatcherTransformer(groupOnly).transform(
                        null, PrepatcherTransformer.EVENTS, null, null,
                        write(partialCandidates)) == null,
                "partial EventsPanel candidate hook state was modified");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(statusKey)),
                "partial EventsPanel candidate hook state was accepted");

        Properties withIndependentProperties = new Properties();
        withIndependentProperties.setProperty("patch.intelReconciliation", "true");
        withIndependentProperties.setProperty("patch.intelCallbackCache", "true");
        PrepatcherConfig withIndependent = constructor.newInstance(withIndependentProperties);
        for (EventsMutation mutation : EventsMutation.values()) {
            byte[] mutated = mutateEventsPanel(original, mutation);
            byte[] result = new PrepatcherTransformer(withIndependent).transform(
                    null, PrepatcherTransformer.EVENTS, null, null, mutated);
            require(result != null,
                    mutation + " blocked independent EventsPanel callback patch");
            require(countHook(result, "getMapLocationCached") == 7,
                    mutation + " cancelled independent Intel callback patch");
            require(countHook(result, "fastContains") == 0
                            && countHook(result, "existingIntelIconCandidates") == 0,
                    mutation + " produced a partial EventsPanel reconciliation group");
            verifyBytecode(result);
        }

        Properties legacyProperties = new Properties();
        legacyProperties.setProperty("patch.intelReconciliation", "false");
        legacyProperties.setProperty("patch.intelCallbackCache", "false");
        legacyProperties.setProperty("patch.intelFastContains", "true");
        legacyProperties.setProperty("patch.intelExistingIconLookup", "true");
        PrepatcherConfig legacyOnly = constructor.newInstance(legacyProperties);
        require(new PrepatcherTransformer(legacyOnly).transform(
                        null, PrepatcherTransformer.EVENTS, null, null, original) == null,
                "removed split EventsPanel keys still select a transformer path");
    }

    private static byte[] mutateEventsPanel(byte[] source, EventsMutation mutation) {
        ClassNode node = read(source);
        MethodNode method = method(node, "addMissingIconsAndRows", "()V");
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (mutation == EventsMutation.MISSING_CONTAINS
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("java/util/List")
                    && call.name.equals("contains")
                    && call.desc.equals("(Ljava/lang/Object;)Z")) {
                call.owner = "java/util/Collection";
                return write(node);
            }
            if (mutation == EventsMutation.MISSING_VALUES
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("java/util/LinkedHashMap")
                    && call.name.equals("values")
                    && call.desc.equals("()Ljava/util/Collection;")) {
                call.setOpcode(Opcodes.INVOKEINTERFACE);
                call.owner = "java/util/Map";
                call.itf = true;
                return write(node);
            }
        }
        throw new AssertionError("EventsPanel mutation site not found: " + mutation);
    }

    private static byte[] mutateAHitTest(byte[] source, HitMutation mutation) {
        ClassNode node = read(source);
        MethodNode hit = method(node, "OO0000",
                "(FFF)Lcom/fs/starfarer/api/campaign/SectorEntityToken;");
        if (mutation == HitMutation.MISSING_FACTOR_CALL) {
            int changed = 0;
            for (AbstractInsnNode insn : hit.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && call.owner.equals(PrepatcherTransformer.H)
                        && call.name.equals("getFactor") && call.desc.equals("()F")) {
                    call.name = "getFactorChanged";
                    changed++;
                }
            }
            require(changed >= 1, "hit-test factor call missing in test input");
            return write(node);
        }

        TypeInsnNode allocation = null;
        MethodInsnNode constructorCall = null;
        for (AbstractInsnNode insn : hit.instructions.toArray()) {
            if (!(insn instanceof TypeInsnNode candidate)
                    || candidate.getOpcode() != Opcodes.NEW
                    || !candidate.desc.equals("java/util/ArrayList")) continue;
            for (AbstractInsnNode cursor = candidate.getNext(); cursor != null;
                 cursor = cursor.getNext()) {
                if (cursor instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESPECIAL
                        && call.owner.equals("java/util/ArrayList")
                        && call.name.equals("<init>")
                        && call.desc.equals("(Ljava/util/Collection;)V")) {
                    allocation = candidate;
                    constructorCall = call;
                    break;
                }
                if (cursor.getOpcode() == Opcodes.NEW && cursor != candidate) break;
            }
            if (allocation != null) break;
        }
        require(allocation != null && constructorCall != null,
                "hit-list allocation missing in test input");
        allocation.desc = "java/util/LinkedList";
        constructorCall.owner = "java/util/LinkedList";
        return write(node);
    }

    private static void runNegativeLifecycleTests(PrepatcherConfig config, Path jarPath) throws Exception {
        byte[] campaign;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            campaign = jar.getInputStream(jar.getJarEntry(
                    PrepatcherTransformer.CAMPAIGN_ENGINE + ".class")).readAllBytes();
        }

        ClassNode missingWrite = read(campaign);
        MethodNode reset = method(missingWrite, "resetInstance", "()V");
        FieldInsnNode resetWrite = singletonWrite(missingWrite, reset);
        reset.instructions.set(resetWrite, new InsnNode(Opcodes.POP));
        assertLifecycleRejectedButIndependentPatchSurvives(config, write(missingWrite),
                "missing resetInstance singleton write");

        ClassNode wrongSource = read(campaign);
        MethodNode set = method(wrongSource, "setInstance",
                "(L" + PrepatcherTransformer.CAMPAIGN_ENGINE + ";)V");
        FieldInsnNode setWrite = singletonWrite(wrongSource, set);
        AbstractInsnNode producer = previousMeaningful(setWrite);
        require(producer instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD,
                "setInstance singleton write producer changed in test input");
        set.instructions.set(producer, new InsnNode(Opcodes.ACONST_NULL));
        assertLifecycleRejectedButIndependentPatchSurvives(config, write(wrongSource),
                "wrong setInstance singleton source");
    }

    private static void runEntityLookupIndexRepairStructuralTests(
            PrepatcherConfig config, Path jarPath) throws Exception {
        byte[] original;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            original = jar.getInputStream(jar.getJarEntry(
                    PrepatcherTransformer.CAMPAIGN_ENGINE + ".class")).readAllBytes();
        }
        String status = "starsector.prepatcher.patchStatus."
                + PrepatcherTransformer.CAMPAIGN_ENGINE.replace('/', '.')
                + ".entityLookupIndexRepair";

        byte[] patched = new PrepatcherTransformer(config).transform(
                null, PrepatcherTransformer.CAMPAIGN_ENGINE, null, null, original);
        require(patched != null, "CampaignEngine entity-index repair did not compose");
        ClassNode repaired = read(patched);
        FieldNode readiness = null;
        for (FieldNode field : repaired.fields) {
            if (!field.name.equals(ENTITY_LOOKUP_INDEX_REPAIR_FIELD)) continue;
            require(readiness == null, "duplicate entity-index readiness field");
            readiness = field;
        }
        int expectedAccess = Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT
                | Opcodes.ACC_SYNTHETIC;
        require(readiness != null && readiness.desc.equals("Z")
                        && readiness.access == expectedAccess
                        && readiness.signature == null && readiness.value == null,
                "entity-index readiness field is not private/transient/synthetic");
        MethodNode advance = method(repaired, "advance", "(FLcom/fs/starfarer/util/A/new;)V");
        require(countCalls(advance, Opcodes.INVOKEVIRTUAL,
                        PrepatcherTransformer.CAMPAIGN_ENGINE,
                        "rebuildIDToEntityMap", "()V") == 2,
                "entity-index repair did not add exactly one guarded vanilla rebuild");
        require(new PrepatcherTransformer(config).transform(
                        null, PrepatcherTransformer.CAMPAIGN_ENGINE,
                        null, null, patched) == null,
                "entity-index repair is not idempotent");
        verifyBytecode(patched);

        Constructor<PrepatcherConfig> constructor =
                PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);
        for (boolean listenerThrottle : new boolean[]{false, true}) {
            Properties properties = new Properties();
            properties.setProperty("patch.entityLookupIndexRepair", "true");
            properties.setProperty("patch.campaignListenerThrottle",
                    Boolean.toString(listenerThrottle));
            PrepatcherConfig isolated = constructor.newInstance(properties);
            byte[] composed = new PrepatcherTransformer(isolated).transform(
                    null, PrepatcherTransformer.CAMPAIGN_ENGINE,
                    null, null, original);
            require(composed != null,
                    "entity-index repair failed with listenerThrottle=" + listenerThrottle);
            MethodNode composedAdvance = method(read(composed), "advance",
                    "(FLcom/fs/starfarer/util/A/new;)V");
            require(countCalls(composedAdvance, Opcodes.INVOKEVIRTUAL,
                            PrepatcherTransformer.CAMPAIGN_ENGINE,
                            "rebuildIDToEntityMap", "()V") == 2,
                    "listener composition changed entity-index rebuild count");
            require(countCalls(composedAdvance, Opcodes.INVOKESTATIC, HOOKS,
                            "readdChangeListenersIfNeeded",
                            "(Ljava/lang/Object;Ljava/util/List;Ljava/lang/Object;)V")
                            == (listenerThrottle ? 1 : 0),
                    "listener composition state does not match configuration");
            verifyBytecode(composed);
        }

        ClassNode changedRebuild = read(original);
        MethodNode rebuild = method(changedRebuild, "rebuildIDToEntityMap", "()V");
        rebuild.access = (rebuild.access & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PUBLIC;
        System.clearProperty(status);
        byte[] independent = new PrepatcherTransformer(config).transform(
                null, PrepatcherTransformer.CAMPAIGN_ENGINE,
                null, null, write(changedRebuild));
        require(independent != null,
                "changed entity-index rebuild blocked independent CampaignEngine patches");
        require(read(independent).fields.stream().noneMatch(
                        field -> field.name.equals(ENTITY_LOOKUP_INDEX_REPAIR_FIELD)),
                "changed entity-index rebuild was accepted by the repair matcher");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(status)),
                "changed entity-index rebuild did not fail open locally");
        verifyBytecode(independent);

        ClassNode partial = read(patched);
        require(partial.fields.removeIf(
                        field -> field.name.equals(ENTITY_LOOKUP_INDEX_REPAIR_FIELD)),
                "entity-index partial-state test input had no readiness field");
        System.clearProperty(status);
        require(new PrepatcherTransformer(config).transform(
                        null, PrepatcherTransformer.CAMPAIGN_ENGINE,
                        null, null, write(partial)) == null,
                "partial entity-index repair state was rewritten");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(status)),
                "partial entity-index repair state was not rejected structurally");
    }

    private static void runExp6OwnershipTests(PrepatcherConfig config, Path jarPath) throws Exception {
        Map<String, String> patches = new LinkedHashMap<>();
        patches.put(PrepatcherTransformer.BASE_LOCATION, "campaignSnapshotReuse");
        patches.put(PrepatcherTransformer.BASE_CAMPAIGN_ENTITY, "entityScriptSnapshotReuse");
        patches.put(PrepatcherTransformer.MEMORY, "emptyMemoryAdvanceFastPath");

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (Map.Entry<String, String> entry : patches.entrySet()) {
                String className = entry.getKey();
                String patchId = entry.getValue();
                byte[] original = jar.getInputStream(jar.getJarEntry(className + ".class")).readAllBytes();
                byte[] patched = new PrepatcherTransformer(config)
                        .transform(null, className, null, null, original);
                require(patched != null, className + " baseline transform failed in ownership test");

                ClassNode unowned = read(patched);
                require(unowned.fields.removeIf(field -> field.name.equals("smo$patched$" + patchId)),
                        className + " ownership marker was not emitted for " + patchId);
                byte[] result = new PrepatcherTransformer(config)
                        .transform(null, className, null, null, write(unowned));
                require(result == null, className + " foreign hook-shaped state was modified");
                require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                                "starsector.prepatcher.patchStatus."
                                        + className.replace('/', '.') + "." + patchId)),
                        className + " foreign hook-shaped state was not rejected structurally");

                if (className.equals(PrepatcherTransformer.BASE_LOCATION)) {
                    ClassNode unscoped = read(patched);
                    removeScratchScope(method(unscoped, "advance",
                            "(FLcom/fs/starfarer/util/A/new;)V"));
                    byte[] unscopedResult = new PrepatcherTransformer(config)
                            .transform(null, className, null, null, write(unscoped));
                    require(unscopedResult == null,
                            "BaseLocation snapshot hooks without an advance scope were modified");
                    require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                                    "starsector.prepatcher.patchStatus."
                                            + className.replace('/', '.') + "." + patchId)),
                            "BaseLocation snapshot hooks without an advance scope were accepted");
                }
            }
        }
    }

    private static void runInlineFastPathNegativeTests(
            PrepatcherConfig config, Path jarPath) throws Exception {
        byte[] entity;
        byte[] memory;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            entity = jar.getInputStream(jar.getJarEntry(
                    PrepatcherTransformer.BASE_CAMPAIGN_ENTITY + ".class")).readAllBytes();
            memory = jar.getInputStream(jar.getJarEntry(
                    PrepatcherTransformer.MEMORY + ".class")).readAllBytes();
        }

        ClassNode prologue = read(entity);
        MethodNode prologueRun = method(prologue, "runScripts", "(F)V");
        prologueRun.instructions.insertBefore(prologueRun.instructions.getFirst(), systemGcCall());
        assertInlinePatchRejected(config, PrepatcherTransformer.BASE_CAMPAIGN_ENTITY,
                "entityScriptSnapshotReuse", write(prologue),
                "runScripts foreign entry prologue");

        ClassNode epilogue = read(entity);
        MethodNode epilogueRun = method(epilogue, "runScripts", "(F)V");
        AbstractInsnNode terminalReturn = null;
        for (AbstractInsnNode insn : epilogueRun.instructions.toArray()) {
            if (insn.getOpcode() == Opcodes.RETURN) terminalReturn = insn;
        }
        require(terminalReturn != null, "runScripts terminal return missing in test input");
        epilogueRun.instructions.insertBefore(terminalReturn, systemGcCall());
        assertInlinePatchRejected(config, PrepatcherTransformer.BASE_CAMPAIGN_ENTITY,
                "entityScriptSnapshotReuse", write(epilogue),
                "runScripts foreign terminal epilogue");

        ClassNode wrongSource = read(memory);
        MethodNode wrongSourceAdvance = method(wrongSource, "advance", "(F)V");
        MethodInsnNode values = uniqueCall(wrongSourceAdvance, Opcodes.INVOKEVIRTUAL,
                "java/util/LinkedHashMap", "values", "()Ljava/util/Collection;");
        AbstractInsnNode valuesReceiver = previousMeaningful(values);
        require(valuesReceiver instanceof FieldInsnNode requireField
                        && requireField.getOpcode() == Opcodes.GETFIELD
                        && requireField.name.equals("require"),
                "Memory.advance require.values source changed in test input");
        ((FieldInsnNode) valuesReceiver).name = "data";
        assertInlinePatchRejected(config, PrepatcherTransformer.MEMORY,
                "emptyMemoryAdvanceFastPath", write(wrongSource),
                "Memory.advance foreign require.values source");

        ClassNode displacedWork = read(memory);
        MethodNode displacedAdvance = method(displacedWork, "advance", "(F)V");
        MethodInsnNode convert = uniqueCall(displacedAdvance, Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/campaign/CampaignClock", "convertToDays", "(F)F");
        displacedAdvance.instructions.insertBefore(convert, systemGcCall());
        assertInlinePatchRejected(config, PrepatcherTransformer.MEMORY,
                "emptyMemoryAdvanceFastPath", write(displacedWork),
                "Memory.advance foreign post-pause work in clock ordering");

        byte[] patchedEntity = new PrepatcherTransformer(config).transform(
                null, PrepatcherTransformer.BASE_CAMPAIGN_ENTITY, null, null, entity);
        require(patchedEntity != null, "runScripts baseline inline transform failed");

        ClassNode damagedEntityReceiver = read(patchedEntity);
        require(hasOwnershipMarker(damagedEntityReceiver, "entityScriptSnapshotReuse"),
                "runScripts inline ownership marker missing from test fixture");
        MethodNode damagedEntityReceiverRun = method(
                damagedEntityReceiver, "runScripts", "(F)V");
        List<AbstractInsnNode> damagedEntityReceiverCode =
                meaningfulInstructions(damagedEntityReceiverRun);
        require(damagedEntityReceiverCode.get(1) instanceof FieldInsnNode,
                "runScripts installed guard receiver missing from test fixture");
        ((FieldInsnNode) damagedEntityReceiverCode.get(1)).name = "smo$damaged$scripts";
        assertInlinePatchRejected(config, PrepatcherTransformer.BASE_CAMPAIGN_ENTITY,
                "entityScriptSnapshotReuse", write(damagedEntityReceiver),
                "runScripts marker-preserved guard receiver mutation");

        ClassNode damagedEntityBranch = read(patchedEntity);
        require(hasOwnershipMarker(damagedEntityBranch, "entityScriptSnapshotReuse"),
                "runScripts inline ownership marker missing from branch fixture");
        MethodNode damagedEntityBranchRun = method(damagedEntityBranch, "runScripts", "(F)V");
        List<AbstractInsnNode> damagedEntityBranchCode = meaningfulInstructions(damagedEntityBranchRun);
        require(damagedEntityBranchCode.get(3) instanceof JumpInsnNode,
                "runScripts installed guard branch missing from test fixture");
        LabelNode wrongTarget = new LabelNode();
        damagedEntityBranchRun.instructions.insertBefore(damagedEntityBranchCode.get(4), wrongTarget);
        ((JumpInsnNode) damagedEntityBranchCode.get(3)).label = wrongTarget;
        assertInlinePatchRejected(config, PrepatcherTransformer.BASE_CAMPAIGN_ENTITY,
                "entityScriptSnapshotReuse", write(damagedEntityBranch),
                "runScripts marker-preserved branch target mutation");

        byte[] patchedMemory = new PrepatcherTransformer(config).transform(
                null, PrepatcherTransformer.MEMORY, null, null, memory);
        require(patchedMemory != null, "Memory.advance baseline inline transform failed");

        ClassNode damagedMemoryReceiver = read(patchedMemory);
        require(hasOwnershipMarker(damagedMemoryReceiver, "emptyMemoryAdvanceFastPath"),
                "Memory.advance inline ownership marker missing from receiver fixture");
        MethodNode damagedMemoryReceiverAdvance = method(damagedMemoryReceiver, "advance", "(F)V");
        MethodInsnNode requireEmpty = uniqueCall(damagedMemoryReceiverAdvance,
                Opcodes.INVOKEVIRTUAL, "java/util/LinkedHashMap", "isEmpty", "()Z");
        AbstractInsnNode requireEmptyReceiver = previousMeaningful(requireEmpty);
        require(requireEmptyReceiver instanceof FieldInsnNode,
                "Memory.advance installed require guard receiver missing from test fixture");
        ((FieldInsnNode) requireEmptyReceiver).name = "data";
        assertInlinePatchRejected(config, PrepatcherTransformer.MEMORY,
                "emptyMemoryAdvanceFastPath", write(damagedMemoryReceiver),
                "Memory.advance marker-preserved guard receiver mutation");

        ClassNode damagedMemoryOrder = read(patchedMemory);
        require(hasOwnershipMarker(damagedMemoryOrder, "emptyMemoryAdvanceFastPath"),
                "Memory.advance inline ownership marker missing from order fixture");
        MethodNode damagedMemoryOrderAdvance = method(damagedMemoryOrder, "advance", "(F)V");
        List<MethodInsnNode> engineCalls = calls(damagedMemoryOrderAdvance,
                Opcodes.INVOKESTATIC, "com/fs/starfarer/campaign/CampaignEngine", "getInstance",
                "()Lcom/fs/starfarer/campaign/CampaignEngine;");
        require(engineCalls.size() == 2,
                "Memory.advance installed guard work boundary missing from test fixture");
        damagedMemoryOrderAdvance.instructions.insertBefore(engineCalls.get(1), systemGcCall());
        assertInlinePatchRejected(config, PrepatcherTransformer.MEMORY,
                "emptyMemoryAdvanceFastPath", write(damagedMemoryOrder),
                "Memory.advance marker-preserved work ordering mutation");
    }

    private static boolean hasOwnershipMarker(ClassNode node, String patchId) {
        return node.fields.stream().anyMatch(field -> field.name.equals("smo$patched$" + patchId));
    }

    private static InsnList systemGcCall() {
        InsnList result = new InsnList();
        result.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/System", "gc", "()V", false));
        return result;
    }

    private static MethodInsnNode uniqueCall(MethodNode method, int opcode,
                                             String owner, String name, String desc) {
        List<MethodInsnNode> calls = calls(method, opcode, owner, name, desc);
        require(calls.size() == 1, owner + "." + name + desc
                + " expected once in test input, found " + calls.size());
        return calls.get(0);
    }

    private static List<MethodInsnNode> calls(MethodNode method, int opcode,
                                               String owner, String name, String desc) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != opcode
                    || !call.owner.equals(owner) || !call.name.equals(name)
                    || !call.desc.equals(desc)) continue;
            result.add(call);
        }
        return result;
    }

    private static void assertInlinePatchRejected(
            PrepatcherConfig config, String className, String patchId,
            byte[] input, String scenario) {
        new PrepatcherTransformer(config)
                .transform(null, className, null, null, input);
        // Another independent patch may legitimately transform the same class.
        // The assertion is patch-local: the malformed target patch must be rejected.
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.prepatcher.patchStatus."
                                + className.replace('/', '.') + "." + patchId)),
                scenario + " was not rejected structurally");
    }

    private static void runExp8OwnershipTests(PrepatcherConfig config, List<Path> jarPaths)
            throws Exception {
        Map<String, List<String>> patches = new LinkedHashMap<>();
        patches.put(PrepatcherTransformer.BASE_CAMPAIGN_ENTITY,
                List.of("marketScheduler"));
        patches.put(PrepatcherTransformer.ECONOMY,
                List.of("economyAdvancePlan"));
        patches.put(PrepatcherTransformer.MARKET,
                List.of("marketAdvancePlan"));
        patches.put(PrepatcherTransformer.CAMPAIGN_GAME_MANAGER,
                List.of("saveMethodPlan", "saveOutputBufferDedup"));
        patches.put(PrepatcherTransformer.BASE_INDUSTRY,
                List.of("marketNoOpCallbacks"));
        patches.put(PrepatcherTransformer.MUTABLE_STAT,
                List.of("commodityTemporalBinding"));
        patches.put(PrepatcherTransformer.MUTABLE_STAT_WITH_TEMP_MODS,
                List.of("tempModExpiryScheduler"));
        patches.put(PrepatcherTransformer.COMMODITY_ON_MARKET,
                List.of("commodityEventModDirtyCache"));
        patches.put(PrepatcherTransformer.INTEL_MANAGER, List.of("commRelaySystemIndex"));
        patches.put(PrepatcherTransformer.SHIP, List.of("shipAdvanceScratch"));
        patches.put(PrepatcherTransformer.DYNAMIC_PARTICLE_GROUP, List.of("particleCleanup"));

        for (Map.Entry<String, List<String>> entry : patches.entrySet()) {
            String className = entry.getKey();
            byte[] original = classBytes(jarPaths, className);
            byte[] patched = new PrepatcherTransformer(config)
                    .transform(null, className, null, null, original);
            require(patched != null, className + " baseline exp8 transform failed");

            for (String patchId : entry.getValue()) {
                ClassNode unowned = read(patched);
                require(unowned.fields.removeIf(field -> field.name.equals("smo$patched$" + patchId)),
                        className + " exp8 ownership marker missing for " + patchId);
                byte[] result = new PrepatcherTransformer(config)
                        .transform(null, className, null, null, write(unowned));
                require(result == null, className + " foreign exp8 hook-shaped state was modified");
                require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                                "starsector.prepatcher.patchStatus."
                                        + className.replace('/', '.') + "." + patchId)),
                        className + " foreign exp8 state was not rejected for " + patchId);
            }
        }

        Map<String, MethodKey> scoped = new LinkedHashMap<>();
        scoped.put(PrepatcherTransformer.INTEL_MANAGER, new MethodKey(
                "findNearestCommRelayToReceive",
                "(Lcom/fs/starfarer/api/campaign/SectorEntityToken;)"
                        + "Lcom/fs/starfarer/api/campaign/SectorEntityToken;",
                "commRelaySystemIndex"));
        scoped.put(PrepatcherTransformer.SHIP, new MethodKey("advance", "(F)V",
                "shipAdvanceScratch"));
        scoped.put(PrepatcherTransformer.DYNAMIC_PARTICLE_GROUP,
                new MethodKey("advance", "(F)V", "particleCleanup"));

        for (Map.Entry<String, MethodKey> entry : scoped.entrySet()) {
            String className = entry.getKey();
            MethodKey key = entry.getValue();
            byte[] patched = new PrepatcherTransformer(config).transform(
                    null, className, null, null, classBytes(jarPaths, className));
            require(patched != null, className + " baseline exp8 scope transform failed");
            ClassNode unscoped = read(patched);
            removeScratchScope(method(unscoped, key.name, key.desc));
            byte[] result = new PrepatcherTransformer(config)
                    .transform(null, className, null, null, write(unscoped));
            require(result == null, className + " exp8 hooks without scope were modified");
            require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                            "starsector.prepatcher.patchStatus."
                                    + className.replace('/', '.') + "." + key.patchId)),
                    className + " exp8 hooks without scope were accepted");
        }

        byte[] enginePatched = new PrepatcherTransformer(config).transform(
                null, PrepatcherTransformer.CAMPAIGN_ENGINE, null, null,
                classBytes(jarPaths, PrepatcherTransformer.CAMPAIGN_ENGINE));
        require(enginePatched != null, "CampaignEngine market-scheduler tick transform failed");
        ClassNode unticked = read(enginePatched);
        removeHookScope(method(unticked, "advance", "(FLcom/fs/starfarer/util/A/new;)V"),
                "beginMarketSchedulerTick", "endMarketSchedulerTick", "market scheduler tick");
        byte[] untickedResult = new PrepatcherTransformer(config).transform(
                null, PrepatcherTransformer.CAMPAIGN_ENGINE, null, null, write(unticked));
        require(untickedResult == null,
                "CampaignEngine scheduler component without its tick boundary was modified");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.prepatcher.patchStatus."
                                + PrepatcherTransformer.CAMPAIGN_ENGINE.replace('/', '.')
                                + ".marketScheduler")),
                "CampaignEngine scheduler component without tick boundary was accepted");

        Map<String, HookTamper> latePostconditions = new LinkedHashMap<>();
        latePostconditions.put(PrepatcherTransformer.BASE_CAMPAIGN_ENTITY,
                new HookTamper("advanceMarketScheduled", "marketScheduler"));
        latePostconditions.put(PrepatcherTransformer.ECONOMY,
                new HookTamper("borrowPersistentSnapshotTimed",
                        "economyAdvancePlan"));
        latePostconditions.put(PrepatcherTransformer.SHIP,
                new HookTamper("borrowShipListSnapshot", "shipAdvanceScratch"));
        latePostconditions.put(PrepatcherTransformer.DYNAMIC_PARTICLE_GROUP,
                new HookTamper("removeExpiredParticles", "particleCleanup"));
        for (Map.Entry<String, HookTamper> entry : latePostconditions.entrySet()) {
            String className = entry.getKey();
            HookTamper tamper = entry.getValue();
            byte[] patched = new PrepatcherTransformer(config).transform(
                    null, className, null, null, classBytes(jarPaths, className));
            require(patched != null, className + " baseline exp8 postcondition transform failed");
            ClassNode damaged = read(patched);
            MethodInsnNode hook = uniqueHook(damaged, tamper.hookName);
            hook.name = "smo$tampered$" + hook.name;
            byte[] result = new PrepatcherTransformer(config)
                    .transform(null, className, null, null, write(damaged));
            require(result == null, className + " damaged late postcondition was modified");
            require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                            "starsector.prepatcher.patchStatus."
                                    + className.replace('/', '.') + "." + tamper.patchId)),
                    className + " damaged late postcondition was accepted as idempotent");
        }

        byte[] patchedEconomy = new PrepatcherTransformer(config).transform(
                null, PrepatcherTransformer.ECONOMY, null, null,
                classBytes(jarPaths, PrepatcherTransformer.ECONOMY));
        require(patchedEconomy != null,
                "Economy baseline remote-scheduler operand transform failed");
        ClassNode damagedScheduledAmount = read(patchedEconomy);
        MethodNode damagedScheduledAdvance = method(
                damagedScheduledAmount, "advance", "(F)V");
        MethodInsnNode scheduled = uniqueHook(
                damagedScheduledAmount, "advanceMarketScheduled");
        AbstractInsnNode scheduledSource = previousMeaningful(scheduled);
        require(isIntegerConstant(scheduledSource, 0),
                "Economy scheduled source changed in test input");
        AbstractInsnNode scheduledAmount = previousMeaningful(scheduledSource);
        require(scheduledAmount instanceof VarInsnNode amountLoad
                        && amountLoad.getOpcode() == Opcodes.FLOAD && amountLoad.var == 1,
                "Economy scheduled amount changed in test input");
        damagedScheduledAdvance.instructions.set(
                scheduledAmount, new InsnNode(Opcodes.FCONST_0));
        byte[] damagedScheduledResult = new PrepatcherTransformer(config).transform(
                null, PrepatcherTransformer.ECONOMY, null, null,
                write(damagedScheduledAmount));
        require(damagedScheduledResult == null,
                "Economy scheduler with damaged amount source was modified");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.prepatcher.patchStatus."
                                + PrepatcherTransformer.ECONOMY.replace('/', '.')
                                + ".economyAdvancePlan")),
                "Economy scheduler with damaged amount source was accepted as idempotent");
    }

    private static MethodInsnNode uniqueHook(ClassNode node, String name) {
        MethodInsnNode result = null;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKESTATIC
                        || !call.owner.equals(HOOKS) || !call.name.equals(name)) continue;
                require(result == null, "hook " + name + " is not unique in test input");
                result = call;
            }
        }
        require(result != null, "hook " + name + " is missing in test input");
        return result;
    }

    private static byte[] classBytes(List<Path> jars, String className) throws Exception {
        for (Path jarPath : jars) {
            try (JarFile jar = new JarFile(jarPath.toFile())) {
                var entry = jar.getJarEntry(className + ".class");
                if (entry != null) return jar.getInputStream(entry).readAllBytes();
            }
        }
        throw new AssertionError("Target class not found in supplied JARs: " + className);
    }

    private static void assertLifecycleRejectedButIndependentPatchSurvives(
            PrepatcherConfig config, byte[] input, String scenario) {
        byte[] result = new PrepatcherTransformer(config).transform(
                null, PrepatcherTransformer.CAMPAIGN_ENGINE, null, null, input);
        require(result != null, scenario + " blocked independent CampaignEngine patches");
        require(countHook(result, "beginCampaignEngineChange") == 0
                        && countHook(result, "completeCampaignEngineChange") == 0,
                scenario + " was accepted by lifecycle matcher");
        require(countHook(result, "readdChangeListenersIfNeeded") == 1,
                scenario + " blocked campaign listener throttle");
        verifyBytecode(result);
    }

    private static FieldInsnNode singletonWrite(ClassNode owner, MethodNode method) {
        String desc = "L" + PrepatcherTransformer.CAMPAIGN_ENGINE + ";";
        FieldInsnNode result = null;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC
                    || !field.owner.equals(owner.name) || !field.desc.equals(desc)) continue;
            require(result == null, method.name + " has multiple singleton writes in test input");
            result = field;
        }
        require(result != null, method.name + " singleton write missing in test input");
        return result;
    }

    private static void runOwnershipAndWrapperTests(PrepatcherConfig config, byte[] h) {
        byte[] patched = new PrepatcherTransformer(config)
                .transform(null, PrepatcherTransformer.H, null, null, h);
        require(patched != null, "H baseline transform failed in ownership test");

        ClassNode unowned = read(patched);
        require(unowned.fields.removeIf(field -> field.name.equals("smo$patched$mapRenderStuff")),
                "mapRenderStuff ownership marker was not emitted");
        byte[] unownedResult = new PrepatcherTransformer(config)
                .transform(null, PrepatcherTransformer.H, null, null, write(unowned));
        require(unownedResult == null, "foreign hook-shaped retainAll state was modified");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.prepatcher.patchStatus.com.fs.starfarer.coreui.A.H.mapRenderStuff")),
                "foreign hook-shaped mapRenderStuff state was not rejected structurally");

        ClassNode unscoped = read(patched);
        removeScratchScope(method(unscoped, "renderStuff", "(FZ)V"));
        byte[] unscopedResult = new PrepatcherTransformer(config)
                .transform(null, PrepatcherTransformer.H, null, null, write(unscoped));
        require(unscopedResult == null, "owned scratch hooks without a scope were modified");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.prepatcher.patchStatus.com.fs.starfarer.coreui.A.H.mapRenderStuff")),
                "owned mapRenderStuff hooks without a scratch scope were accepted");

        ClassNode partial = read(patched);
        MethodNode partialRender = method(partial, "renderStuff", "(FZ)V");
        MethodInsnNode classSetHook = null;
        for (AbstractInsnNode insn : partialRender.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals(HOOKS) && call.name.equals("borrowClassSet")
                    && call.desc.equals("()Ljava/util/HashSet;")) {
                classSetHook = call;
                break;
            }
        }
        require(classSetHook != null, "borrowClassSet hook missing in patched H");
        InsnList restoredAllocation = new InsnList();
        restoredAllocation.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashSet"));
        restoredAllocation.add(new InsnNode(Opcodes.DUP));
        restoredAllocation.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/util/HashSet", "<init>", "()V", false));
        partialRender.instructions.insertBefore(classSetHook, restoredAllocation);
        partialRender.instructions.remove(classSetHook);
        byte[] partialResult = new PrepatcherTransformer(config)
                .transform(null, PrepatcherTransformer.H, null, null, write(partial));
        require(partialResult == null, "owned partial renderStuff group was modified");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.prepatcher.patchStatus.com.fs.starfarer.coreui.A.H.mapRenderStuff")),
                "owned partial renderStuff group was not rejected structurally");

        ClassNode tampered = read(patched);
        method(tampered, "getIntelIconEntity",
                "(Lcom/fs/starfarer/api/campaign/comm/IntelInfoPlugin;)"
                        + "Lcom/fs/starfarer/campaign/CustomCampaignEntity;")
                .instructions.insert(new InsnNode(Opcodes.NOP));
        byte[] tamperedResult = new PrepatcherTransformer(config)
                .transform(null, PrepatcherTransformer.H, null, null, write(tampered));
        require(tamperedResult == null, "tampered wrapper was modified instead of rejected");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.prepatcher.patchStatus.com.fs.starfarer.coreui.A.H.intelEntityIndex")),
                "tampered wrapper was not rejected structurally");

        ClassNode annotated = read(h);
        MethodNode annotatedIntel = method(annotated, "getIntelIconEntity",
                "(Lcom/fs/starfarer/api/campaign/comm/IntelInfoPlugin;)"
                        + "Lcom/fs/starfarer/campaign/CustomCampaignEntity;");
        annotatedIntel.visibleAnnotations = new ArrayList<>();
        annotatedIntel.visibleAnnotations.add(new AnnotationNode("Ltest/StructuralMarker;"));
        byte[] annotatedResult = new PrepatcherTransformer(config)
                .transform(null, PrepatcherTransformer.H, null, null, write(annotated));
        require(annotatedResult != null, "declaration metadata blocked compatible wrapper patch");
        ClassNode wrapped = read(annotatedResult);
        MethodNode publicWrapper = method(wrapped, "getIntelIconEntity", annotatedIntel.desc);
        MethodNode privateOriginal = method(wrapped, "smo$originalGetIntelIconEntity", annotatedIntel.desc);
        require(publicWrapper.visibleAnnotations != null
                        && publicWrapper.visibleAnnotations.stream()
                        .anyMatch(annotation -> annotation.desc.equals("Ltest/StructuralMarker;")),
                "wrapper lost original declaration annotation");
        require(privateOriginal.visibleAnnotations == null || privateOriginal.visibleAnnotations.isEmpty(),
                "declaration annotation was duplicated on the private fallback");
        verifyBytecode(annotatedResult);
    }

    private static void removeScratchScope(MethodNode method) {
        removeHookScope(method, "beginScratchScope", "endScratchScope", "scratch");
    }

    private static void removeHookScope(MethodNode method, String beginName,
                                        String endName, String label) {
        TryCatchBlockNode handler = null;
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            AbstractInsnNode first = nextMeaningful(block.handler);
            if (block.type == null && first instanceof MethodInsnNode call
                    && call.owner.equals(HOOKS) && call.name.equals(endName)
                    && call.desc.equals("()V")) {
                require(handler == null, "multiple " + label + " catch-all handlers in test input");
                handler = block;
            }
        }
        require(handler != null, label + " catch-all handler is missing in test input");
        method.tryCatchBlocks.remove(handler);

        AbstractInsnNode cursor = handler.handler;
        while (cursor != null) {
            AbstractInsnNode next = cursor.getNext();
            boolean done = cursor.getOpcode() == Opcodes.ATHROW;
            method.instructions.remove(cursor);
            if (done) break;
            cursor = next;
        }

        int removedCalls = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(HOOKS)
                    && (call.name.equals(beginName) || call.name.equals(endName))
                    && call.desc.equals("()V")) {
                method.instructions.remove(call);
                removedCalls++;
            }
        }
        require(removedCalls >= 2, label + " scope calls are missing in test input");
    }

    private static void assertHIndependentHooks(byte[] bytes, String scenario) {
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("nearbyLabelIcons", 1);
        expected.put("getIntelIconEntityIndexed", 1);
        expected.put("updateSystemNebulasCached", 1);
        expected.put("forceClearSampleCacheThrottled", 1);
        expected.put("gridSpacing", 5);
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            require(countHook(bytes, entry.getKey()) == entry.getValue(),
                    scenario + " incorrectly cancelled independent H hook " + entry.getKey());
        }
    }

    private static byte[] mutateH(byte[] source, Mutation mutation) {
        ClassNode node = read(source);
        MethodNode render = method(node, "renderStuff", "(FZ)V");
        if (mutation == Mutation.UNRELATED_NOP) {
            render.instructions.insert(new InsnNode(Opcodes.NOP));
            return write(node);
        }
        if (mutation == Mutation.UNRELATED_RETAIN) {
            InsnList unrelated = new InsnList();
            unrelated.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashSet"));
            unrelated.add(new InsnNode(Opcodes.DUP));
            unrelated.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                    "java/util/HashSet", "<init>", "()V", false));
            unrelated.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
            unrelated.add(new InsnNode(Opcodes.DUP));
            unrelated.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                    "java/util/ArrayList", "<init>", "()V", false));
            unrelated.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                    "java/util/Set", "retainAll", "(Ljava/util/Collection;)Z", true));
            unrelated.add(new InsnNode(Opcodes.POP));
            render.instructions.insert(unrelated);
            return write(node);
        }

        if (mutation == Mutation.MISSING_CLASS_SET) {
            for (AbstractInsnNode insn : render.instructions.toArray()) {
                if (!(insn instanceof TypeInsnNode allocation)
                        || allocation.getOpcode() != Opcodes.NEW
                        || !allocation.desc.equals("java/util/HashSet")) continue;
                allocation.desc = "java/util/LinkedHashSet";
                for (AbstractInsnNode cursor = allocation.getNext(); cursor != null;
                     cursor = cursor.getNext()) {
                    if (cursor instanceof MethodInsnNode call
                            && call.getOpcode() == Opcodes.INVOKESPECIAL
                            && call.owner.equals("java/util/HashSet")
                            && call.name.equals("<init>") && call.desc.equals("()V")) {
                        call.owner = "java/util/LinkedHashSet";
                        return write(node);
                    }
                    if (cursor.getOpcode() == Opcodes.NEW) break;
                }
            }
            throw new AssertionError("target HashSet allocation not found in H.renderStuff");
        }

        MethodInsnNode retain = null;
        for (AbstractInsnNode insn : render.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("java/util/Set") && call.name.equals("retainAll")
                    && call.desc.equals("(Ljava/util/Collection;)Z")) {
                retain = call;
                break;
            }
        }
        require(retain != null, "vanilla retainAll site not found in test input");
        if (mutation == Mutation.MISSING_RETAIN) {
            retain.name = "removeAll";
            return write(node);
        }

        AbstractInsnNode keepLoad = previousMeaningful(retain);
        MethodInsnNode keySet = (MethodInsnNode) previousMeaningful(keepLoad);
        FieldInsnNode icons = (FieldInsnNode) previousMeaningful(keySet);
        int keepLocal = ((VarInsnNode) keepLoad).var;
        InsnList duplicate = new InsnList();
        duplicate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        duplicate.add(new FieldInsnNode(Opcodes.GETFIELD, icons.owner, icons.name, icons.desc));
        duplicate.add(new MethodInsnNode(keySet.getOpcode(), keySet.owner, keySet.name, keySet.desc, keySet.itf));
        duplicate.add(new VarInsnNode(Opcodes.ALOAD, keepLocal));
        duplicate.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "java/util/Set", "retainAll", "(Ljava/util/Collection;)Z", true));
        duplicate.add(new InsnNode(Opcodes.POP));
        render.instructions.insertBefore(retain, duplicate);
        return write(node);
    }


    private static void assertLocalResourcesColdDataPatch(byte[] bytes) {
        ClassNode node = read(bytes);
        MethodNode stockpile = method(node, "getStockpileLimit",
                "(Lcom/fs/starfarer/api/campaign/econ/CommodityOnMarketAPI;)I");
        require(countCalls(stockpile, Opcodes.INVOKEINTERFACE,
                        "com/fs/starfarer/api/campaign/econ/CommodityOnMarketAPI",
                        "getCommodityMarketData",
                        "()Lcom/fs/starfarer/api/campaign/econ/CommodityMarketDataAPI;") == 0,
                "Local Resources stockpile still materializes CommodityMarketData");
        require(countCalls(stockpile, Opcodes.INVOKESTATIC,
                        "com/fs/starfarer/campaign/econ/reach/CommodityMarketData",
                        "getShippingCapacity",
                        "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;Z)I") == 1,
                "Local Resources static shipping-capacity replacement changed");
        MethodNode shouldHave = method(node, "shouldHaveCommodity",
                "(Lcom/fs/starfarer/api/campaign/econ/CommodityOnMarketAPI;)Z");
        require(countCalls(shouldHave, Opcodes.INVOKESTATIC, HOOKS,
                        "localResourcesShouldHaveCommodity",
                        "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;"
                                + "Lcom/fs/starfarer/api/campaign/econ/CommodityOnMarketAPI;)Z") == 1,
                "Local Resources cold-safe predicate wrapper changed");
    }

    private static void assertLocalResourcesTooltipSnapshotPatch(byte[] bytes) {
        ClassNode node = read(bytes);
        String desc = "(Lcom/fs/starfarer/api/ui/TooltipMakerAPI;Z)V";
        MethodNode wrapper = method(node, "createTooltipAfterDescription", desc);
        MethodNode raw = method(node, "spp$rawCreateTooltipAfterDescription", desc);
        require(wrapper != null && raw != null,
                "Local Resources tooltip wrapper/raw pair missing");
        require(countCalls(wrapper, Opcodes.INVOKESTATIC, HOOKS,
                        "renderLocalResourcesTooltipSnapshot",
                        "(Lcom/fs/starfarer/api/impl/campaign/submarkets/"
                                + "LocalResourcesSubmarketPlugin;"
                                + "Lcom/fs/starfarer/api/ui/TooltipMakerAPI;Z)Z") == 1,
                "Local Resources tooltip snapshot hook count changed");
        require(countCalls(wrapper, Opcodes.INVOKESPECIAL,
                        PrepatcherTransformer.LOCAL_RESOURCES_SUBMARKET,
                        "spp$rawCreateTooltipAfterDescription", desc) == 1,
                "Local Resources tooltip raw fallback missing");
        JumpInsnNode fallback = null;
        for (AbstractInsnNode instruction = wrapper.instructions.getFirst();
             instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.IFEQ) {
                require(fallback == null,
                        "Local Resources tooltip has multiple raw fallback branches");
                fallback = (JumpInsnNode) instruction;
            }
        }
        AbstractInsnNode frameInstruction = fallback == null
                ? null : fallback.label.getNext();
        require(frameInstruction instanceof FrameNode,
                "Local Resources raw fallback frame is missing");
        FrameNode frame = (FrameNode) frameInstruction;
        require(frame.type == Opcodes.F_SAME
                        && (frame.local == null || frame.local.isEmpty())
                        && (frame.stack == null || frame.stack.isEmpty()),
                "Local Resources raw fallback is not an exact F_SAME frame");
        require(countCalls(raw, Opcodes.INVOKESTATIC,
                        "java/util/Collections", "sort",
                        "(Ljava/util/List;Ljava/util/Comparator;)V") == 1,
                "preserved Local Resources raw tooltip changed");
    }

    private static void assertMarketSharePatches(byte[] bytes) {
        ClassNode node = read(bytes);
        require(hasOwnershipMarker(node, "marketShareLinearAggregation"),
                "market-share linear ownership marker missing");
        require(hasOwnershipMarker(node, "marketShareDataPutElision"),
                "market-share put-elision ownership marker missing");
        MethodNode wrapper = method(node, "getMarketSharePercentPerFaction",
                "()Ljava/util/Map;");
        MethodNode raw = method(node,
                "spp$commodityMarketDataRawMarketSharePerFaction",
                "()Ljava/util/Map;");
        require((raw.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC))
                        == (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC),
                "market-share raw fallback metadata changed");
        require(countCalls(wrapper, Opcodes.INVOKEVIRTUAL,
                        PrepatcherTransformer.COMMODITY_MARKET_DATA,
                        "getMarkets", "()Ljava/util/List;") == 1,
                "market-share wrapper getMarkets count changed");
        require(countCalls(wrapper, Opcodes.INVOKEVIRTUAL,
                        PrepatcherTransformer.COMMODITY_MARKET_DATA,
                        "getMarketSharePercent",
                        "(Lcom/fs/starfarer/api/campaign/FactionAPI;)I") == 0,
                "market-share wrapper retained repeated single-faction scan");
        require(countCalls(wrapper, Opcodes.INVOKEVIRTUAL,
                        PrepatcherTransformer.COMMODITY_MARKET_DATA,
                        "getExportMarketSharePercent",
                        "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)I") == 1,
                "market-share wrapper export-share pass changed");

        MethodNode data = method(node, "getMarketShareData",
                "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)"
                        + "Lcom/fs/starfarer/campaign/econ/reach/MarketShareData;");
        MethodInsnNode put = uniqueCall(data, Opcodes.INVOKEVIRTUAL,
                "java/util/LinkedHashMap", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        JumpInsnNode guard = null;
        for (AbstractInsnNode insn : data.instructions.toArray()) {
            if (insn instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.IFNONNULL) {
                require(guard == null, "duplicate market-share non-null guard");
                guard = jump;
            }
        }
        require(guard != null, "market-share non-null guard missing");
        require(data.instructions.indexOf(put) < data.instructions.indexOf(guard.label),
                "market-share put is not inside the null branch");
    }

    private static void assertPunitivePlayerSharePatch(byte[] bytes, String owner) {
        ClassNode node = read(bytes);
        require(hasOwnershipMarker(node, "punitivePlayerShareLocalCache"),
                "punitive player-share ownership marker missing");
        MethodNode reasons = method(node, "getExpeditionReasons",
                "(Lcom/fs/starfarer/api/impl/campaign/intel/punitive/"
                        + "PunitiveExpeditionManager$PunExData;)Ljava/util/List;");
        String helperDesc = "(Lcom/fs/starfarer/api/campaign/econ/CommodityMarketDataAPI;"
                + "Lcom/fs/starfarer/api/campaign/FactionAPI;Ljava/util/Map;"
                + "Ljava/util/IdentityHashMap;)I";
        MethodNode helper = method(node, "spp$punitiveCachedPlayerShare", helperDesc);
        require((helper.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC
                        | Opcodes.ACC_SYNTHETIC))
                        == (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC
                        | Opcodes.ACC_SYNTHETIC),
                "punitive player-share helper metadata changed");
        require(countCalls(reasons, Opcodes.INVOKESTATIC, owner,
                        "spp$punitiveCachedPlayerShare", helperDesc) == 2,
                "punitive cached player-share call count changed");
        require(countCalls(reasons, Opcodes.INVOKEINTERFACE,
                        "com/fs/starfarer/api/campaign/econ/CommodityMarketDataAPI",
                        "getMarketSharePercent",
                        "(Lcom/fs/starfarer/api/campaign/FactionAPI;)I") == 0,
                "punitive direct player-share calls remain");
        int allocations = 0;
        for (AbstractInsnNode insn : reasons.instructions.toArray()) {
            if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals("java/util/IdentityHashMap")) allocations++;
        }
        require(allocations == 1,
                "punitive invocation-local cache allocation count changed: " + allocations);
        for (FieldNode field : node.fields) {
            require(!field.name.startsWith("spp$punitive"),
                    "punitive patch added retained state field " + field.name);
        }
    }

    private static void assertExpectedHooks(String className, byte[] bytes) {
        Map<String, Integer> expected = new LinkedHashMap<>();
        switch (className) {
            case PrepatcherTransformer.H -> {
                expected.put("retainAllFast", 1);
                expected.put("borrowEntityList", 1);
                expected.put("borrowClassSet", 1);
                expected.put("nearbyLabelIcons", 1);
                expected.put("getIntelIconEntityIndexed", 1);
                expected.put("updateSystemNebulasCached", 1);
                expected.put("forceClearSampleCacheThrottled", 1);
                expected.put("gridSpacing", 5);
            }
            case PrepatcherTransformer.A -> {
                expected.put("borrowHitList", 1);
                expected.put("borrowHitPoint", 1);
                expected.put("getMapLocationCached", 1);
                expected.put("hitTestCached", 1);
            }
            case PrepatcherTransformer.Z -> {
                expected.put("getArrowDataCached", 1);
                expected.put("borrowArrowVector", 2);
            }
            case PrepatcherTransformer.EVENTS -> {
                expected.put("getMapLocationCached", 7);
                expected.put("fastContains", 1);
                expected.put("existingIntelIconCandidates", 1);
            }
            case PrepatcherTransformer.CAMPAIGN_STATE ->
                    expected.put("registerMarketSchedulerComponent", 1);
            case PrepatcherTransformer.CAMPAIGN_ENGINE -> {
                expected.put("beginCampaignEngineChange", 2);
                expected.put("completeCampaignEngineChange", 2);
                expected.put("beginMarketSchedulerTick", 1);
                expected.put("endMarketSchedulerTick", 2);
                expected.put("marketSchedulerFastForwardIterationChanged", 1);
                expected.put("readdChangeListenersIfNeeded", 1);
                assertHookCount(bytes, CORE_WORLDS_RUNTIME, "starSystemAdded", 1);
                assertHookCount(bytes, CORE_WORLDS_RUNTIME, "starSystemRemoved", 1);
                require(hasOwnershipMarker(read(bytes), "coreWorldsExtentCache"),
                        "CampaignEngine core-worlds marker is missing");
            }
            case PrepatcherTransformer.COURSE_WIDGET -> {
                expected.put("routeJumpPointsForSystem", 3);
                expected.put("routeSystemsForAnchor", 1);
            }
            case PrepatcherTransformer.BASE_LOCATION -> {
                expected.put("borrowLocationAdvanceSnapshot", 3);
                expected.put("borrowPausedLocationSnapshot", 2);
                expected.put("strategicJumpLocationEntityChanged", 2);
                assertHookCount(bytes, CORE_WORLDS_RUNTIME, "locationTagAdded", 1);
                assertHookCount(bytes, CORE_WORLDS_RUNTIME, "locationTagRemoved", 2);
                assertHookCount(bytes, CORE_WORLDS_RUNTIME, "locationTagsCleared", 1);
                require(hasOwnershipMarker(read(bytes), "coreWorldsExtentCache"),
                        "BaseLocation core-worlds marker is missing");
                require(hasOwnershipMarker(read(bytes), "strategicJumpDestinationIndex"),
                        "BaseLocation destination-index marker is missing");
            }
            case PrepatcherTransformer.BASE_CAMPAIGN_ENTITY -> {
                // Entity-script empty fast path remains inline. The condition-only
                // market call site is routed through a marked scheduler/observer hook.
                expected.put("advanceMarketScheduled", 1);
            }
            case PrepatcherTransformer.MEMORY -> {
                // Fully inline; the previous helper-based version was measurable
                // in real campaign telemetry.
            }
            case PrepatcherTransformer.CORE_SCRIPT -> {
                assertHookCount(bytes, CORE_WORLDS_RUNTIME, "update", 1);
                require(hasOwnershipMarker(read(bytes), "coreWorldsExtentCache"),
                        "CoreScript core-worlds marker is missing");
            }
            case PrepatcherTransformer.ECONOMY -> {
                expected.put("updateEconomyLocationMapIfNeededPersistent", 1);
                expected.put("borrowPersistentSnapshotTimed", 1);
                expected.put("advanceMarketScheduled", 1);
                expected.put("newPersistentSnapshotState", 2);
                expected.put("markPersistentSnapshotStructure", 3);
            }
            case PrepatcherTransformer.MARKET -> {
                expected.put("borrowPersistentSnapshotFrames", 2);
                expected.put("newPersistentSnapshotState", 6);
                expected.put("markPersistentSnapshotStructure", 6);
                expected.put("advanceMarketCommodityTemporalState", 1);
                expected.put("observeMarketAdvanceEntry", 1);
                expected.put("beginMarketAdvanceInvocation", 1);
                expected.put("endMarketAdvanceInvocation", 2);
                expected.put("registerConstructionQueueOwner", 1);
                expected.put("flushPendingMarketBeforeMutation", 3);
                expected.put("registerMarketSchedulerComponent", 1);
                expected.put("markEconomyGroupStructureChanged", 1);
                expected.put("registerEconomyGroupIndexComponent", 1);
            }
            case PrepatcherTransformer.LOCAL_RESOURCES_SUBMARKET -> {
                expected.put("localResourcesShouldHaveCommodity", 1);
                expected.put("renderLocalResourcesTooltipSnapshot", 1);
                assertLocalResourcesColdDataPatch(bytes);
                assertLocalResourcesTooltipSnapshotPatch(bytes);
            }
            case PrepatcherTransformer.REACH_ECONOMY -> {
                expected.put("borrowMarketsInGroupIndexed", 1);
                expected.put("newPersistentSnapshotState", 1);
                expected.put("markEconomyGroupStructureChanged", 2);
                expected.put("registerEconomyGroupIndexComponent", 1);
            }
            case PrepatcherTransformer.COMMODITY_MARKET_DATA ->
                    assertMarketSharePatches(bytes);
            case PrepatcherTransformer.PUNITIVE_EXPEDITION_MANAGER ->
                    assertPunitivePlayerSharePatch(bytes, className);
            case PrepatcherTransformer.BASE_INDUSTRY -> {
                expected.put("isBaseIndustryDormantFastPathEligible", 1);
                expected.put("flushPendingMarketBeforeMutation", 4);
                expected.put("registerMarketSchedulerComponent", 1);
            }
            case PrepatcherTransformer.MILITARY_BASE,
                    PrepatcherTransformer.LIONS_GUARD_HQ -> {
                expected.put("hasCurrentMarketAdvanceBatch", 1);
                expected.put("currentMarketAdvanceBatchRunCount", 1);
                expected.put("currentMarketAdvanceBatchRunAmount", 1);
                expected.put("currentMarketAdvanceBatchRunRepeats", 1);
                expected.put("recordMarketComponentReplayedStep", 1);
                expected.put("registerMarketSchedulerComponent", 1);
            }
            case PrepatcherTransformer.RECENT_UNREST -> {
                expected.put("hasCurrentMarketAdvanceBatch", 1);
                expected.put("currentMarketAdvanceBatchRunCount", 1);
                expected.put("currentMarketAdvanceBatchRunAmount", 1);
                expected.put("currentMarketAdvanceBatchRunRepeats", 1);
                expected.put("recordMarketComponentReplayedStep", 1);
                expected.put("shouldContinueRecentUnrestReplay", 1);
                expected.put("registerMarketSchedulerComponent", 1);
            }
            case PrepatcherTransformer.CONSTRUCTION_QUEUE -> {
                expected.put("flushPendingConstructionQueueBeforeMutation", 7);
                expected.put("registerMarketSchedulerComponent", 1);
            }
            case PrepatcherTransformer.MUTABLE_STAT ->
                    expected.put("markCommodityTemporalOwnerDirty", 14);
            case PrepatcherTransformer.COMMODITY_ON_MARKET -> {
                // Inline dirty cache; no cross-loader hook is required.
            }
            case PrepatcherTransformer.MUTABLE_STAT_WITH_TEMP_MODS -> {
                assertHookCount(bytes, TEMP_MOD_HOOKS, "recordHybridInitialSweep", 1);
                assertHookCount(bytes, TEMP_MOD_HOOKS, "recordHybridExpirySweep", 1);
                assertHookCount(bytes, TEMP_MOD_HOOKS, "recordHybridSynchronizationSweep", 1);
                assertHookCount(bytes, TEMP_MOD_HOOKS, "recordHybridScheduleRebuild", 1);
                assertHookCount(bytes, TEMP_MOD_HOOKS, "recordHybridExternalExposure", 1);
                assertHookCount(bytes, TEMP_MOD_HOOKS, "recordHybridSubclassFallback", 1);
                assertHookCount(bytes, TEMP_MOD_HOOKS, "recordHybridFailureFallback", 1);
                assertHookCount(bytes, HOOKS, "markCommodityTemporalStatExposed", 2);
            }
            case PrepatcherTransformer.STRATEGIC_MODULE -> {
                ClassNode strategic = read(bytes);
                require(hasOwnershipMarker(strategic, "strategicJumpDestinationFirst"),
                        "StrategicModule destination-first marker is missing");
                require(hasOwnershipMarker(strategic, "strategicJumpDestinationIndex"),
                        "StrategicModule destination-index marker is missing");
                expected.put("strategicJumpPointsForDestination", 1);
                expected.put("strategicJumpDeferExpiredPlan", 1);
            }
            case PrepatcherTransformer.JUMP_POINT -> {
                ClassNode jumpPoint = read(bytes);
                require(hasOwnershipMarker(jumpPoint, "strategicJumpDestinationIndex"),
                        "JumpPoint destination-index marker is missing");
                expected.put("strategicJumpDestinationAdded", 1);
                expected.put("strategicJumpDestinationsChanged", 2);
            }
            case PrepatcherTransformer.JUMP_DESTINATION -> {
                ClassNode destination = read(bytes);
                require(hasOwnershipMarker(destination, "strategicJumpDestinationIndex"),
                        "JumpDestination destination-index marker is missing");
                expected.put("strategicJumpDestinationRetargeted", 1);
            }
            case PrepatcherTransformer.INTEL_MANAGER -> expected.put("commRelayCandidateSystems", 1);
            case PrepatcherTransformer.SHIP -> {
                expected.put("borrowShipList", 3);
                expected.put("borrowShipListSnapshot", 1);
                expected.put("borrowShipSet", 2);
            }
            case PrepatcherTransformer.DYNAMIC_PARTICLE_GROUP -> {
                expected.put("borrowParticleRemovalList", 1);
                expected.put("removeExpiredParticles", 1);
            }
            case PrepatcherTransformer.LOADING_UTILS -> {
                expected.put("readUtf8Normalized", 1);
                expected.put("startupLogSuppressed", 5);
            }
            case PrepatcherTransformer.SCRIPT_STORE_RUNNER -> expected.put("startupLogSuppressed", 1);
            case PrepatcherTransformer.RULES -> {
                expected.put("startupLogSuppressed", 1);
                expected.put("removeCarriageReturns", 3);
                expected.put("replaceNewlinesWithSpace", 1);
                expected.put("splitNewlines", 3);
                expected.put("splitOrBlocks", 1);
                expected.put("splitColon", 1);
            }
            case PrepatcherTransformer.SPEC_STORE -> expected.put("suppressStartupInfo", 53);
            case PrepatcherTransformer.TEXTURE_LOADER ->
                    expected.put("startupLogSuppressed", 1);
            case PrepatcherTransformer.SOUND -> {
                ClassNode sound = read(bytes);
                assertHookCount(bytes, HOOKS, "startupLogSuppressed", 0);
                require(hasOwnershipMarker(sound, "startupLogAggregation"),
                        "sound inline startup-log patch has no ownership marker");
                for (MethodNode method : sound.methods) {
                    for (AbstractInsnNode insn : method.instructions.toArray()) {
                        require(!(insn instanceof LdcInsnNode ldc
                                        && "Loading sound [".equals(ldc.cst)),
                                "sound inline startup-log patch left its INFO literal");
                    }
                }
            }
            case PrepatcherTransformer.PROGRESS_INPUT, PrepatcherTransformer.PROGRESS_OUTPUT ->
                    expected.put("saveLoadProgressMinIntervalSeconds", 1);
            case PrepatcherTransformer.CAMPAIGN_GAME_MANAGER -> {
                expected.put("runCacheMaintenance", 1);
                expected.put("flushMarketSchedulerBeforeSave", 1);
            }
            case PrepatcherTransformer.PLANET_SURVEY_PANEL,
                    PrepatcherTransformer.LUDDIC_PATH_BASE_INTEL,
                    PrepatcherTransformer.PIRATE_BASE_INTEL,
                    PrepatcherTransformer.PK_CMD ->
                    expected.put("advanceMarketSynchronized", 1);
            case PrepatcherTransformer.DECIV_TRACKER ->
                    expected.put("advanceMarketSynchronized", 2);
            case HyperspacePatches.BASE_TILED -> {
                assertHookCount(bytes, HYPERSPACE_HOOKS, "seededRandom", 3);
                ClassNode tiled = read(bytes);
                require(HyperspacePatches.countPatchedViewportBounds(tiled) == 2,
                        className + " atomic viewport-bounds postcondition is incomplete");
                require(hasOwnershipMarker(tiled, "hyperspaceViewportBounds"),
                        className + " atomic viewport-bounds marker is missing");
            }
            case HyperspacePatches.HYPER_TERRAIN -> {
                int layerFilters = countHook(bytes, HYPERSPACE_HOOKS, "filterLayers")
                        + countHook(bytes, HYPERSPACE_HOOKS, "filterLayerSet");
                require(layerFilters == 1,
                        className + " layer filter hook expected=1 actual=" + layerFilters);
                assertHookCount(bytes, HYPERSPACE_HOOKS, "seededRandom", 1);
                assertHookCount(bytes, HYPERSPACE_HOOKS, "automatonCellsInternal", 2);
            }
            case HyperspacePatches.AUTOMATON -> {
                assertHookCount(bytes, HYPERSPACE_HOOKS, "retainAutomatonSpare", 1);
                assertHookCount(bytes, HYPERSPACE_HOOKS, "acquireAutomatonBuffer", 1);
            }
            case HyperspacePatches.STARFIELD -> {
                assertHookCount(bytes, HYPERSPACE_HOOKS, "borrowArrayList", 1);
                assertHookCount(bytes, HYPERSPACE_HOOKS, "removeAllAndRelease", 1);
            }
            default -> throw new AssertionError("Unknown target " + className);
        }
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            require(countHook(bytes, entry.getKey()) == entry.getValue(),
                    className + " hook " + entry.getKey() + " expected=" + entry.getValue()
                            + " actual=" + countHook(bytes, entry.getKey()));
        }
        if (className.equals(PrepatcherTransformer.CAMPAIGN_GAME_MANAGER)) {
            assertRedundantSaveBufferRemoved(bytes);
        }
        if (className.equals(PrepatcherTransformer.H)) {
            assertScratchScope(bytes, "renderStuff", "(FZ)V");
            assertScratchScope(bytes, "getTextAlignmentFor",
                    "(Lcom/fs/starfarer/coreui/A/ooOO;)Lcom/fs/starfarer/api/ui/Alignment;");
        } else if (className.equals(PrepatcherTransformer.A)) {
            assertScratchScope(bytes, "smo$originalHitTest",
                    "(FFF)Lcom/fs/starfarer/api/campaign/SectorEntityToken;");
        } else if (className.equals(PrepatcherTransformer.Z)) {
            assertScratchScope(bytes, "o00000", "(FF)V");
        } else if (className.equals(PrepatcherTransformer.EVENTS)) {
            assertScratchScope(bytes, "addMissingIconsAndRows", "()V");
        } else if (className.equals(PrepatcherTransformer.CAMPAIGN_ENGINE)) {
            assertMarketSchedulerTickScope(bytes);
        } else if (className.equals(PrepatcherTransformer.BASE_LOCATION)) {
            assertScratchScope(bytes, "advance", "(FLcom/fs/starfarer/util/A/new;)V");
            assertScratchScope(bytes, "advanceEvenIfPaused",
                    "(FLcom/fs/starfarer/util/A/new;)V");
        } else if (className.equals(PrepatcherTransformer.BASE_CAMPAIGN_ENTITY)) {
            assertEntityScriptEmptyGuard(bytes);
        } else if (className.equals(PrepatcherTransformer.MEMORY)) {
            assertMemoryEmptyGuard(bytes);
        } else if (className.equals(PrepatcherTransformer.ECONOMY)) {
            assertEconomyPersistentSnapshots(bytes);
        } else if (className.equals(PrepatcherTransformer.MARKET)) {
            assertMarketPersistentSnapshots(bytes);
            assertMarketCommodityTemporalActiveSet(bytes);
        } else if (className.equals(PrepatcherTransformer.BASE_INDUSTRY)) {
            assertBaseIndustryDormantFastPath(bytes);
        } else if (className.equals(PrepatcherTransformer.MILITARY_BASE)
                || className.equals(PrepatcherTransformer.LIONS_GUARD_HQ)
                || className.equals(PrepatcherTransformer.RECENT_UNREST)) {
            assertMarketStepReplayWrapper(bytes,
                    className.equals(PrepatcherTransformer.RECENT_UNREST));
        } else if (className.equals(PrepatcherTransformer.MUTABLE_STAT)) {
            assertCommodityTemporalBinding(bytes);
        } else if (className.equals(PrepatcherTransformer.COMMODITY_ON_MARKET)) {
            assertCommodityEventModDirtyCache(bytes);
        } else if (className.equals(PrepatcherTransformer.MUTABLE_STAT_WITH_TEMP_MODS)) {
            assertTempModExpiryScheduler(bytes);
        } else if (className.equals(PrepatcherTransformer.INTEL_MANAGER)) {
            assertScratchScope(bytes, "findNearestCommRelayToReceive",
                    "(Lcom/fs/starfarer/api/campaign/SectorEntityToken;)"
                            + "Lcom/fs/starfarer/api/campaign/SectorEntityToken;");
        } else if (className.equals(PrepatcherTransformer.SHIP)) {
            assertScratchScope(bytes, "advance", "(F)V");
        } else if (className.equals(PrepatcherTransformer.DYNAMIC_PARTICLE_GROUP)) {
            assertScratchScope(bytes, "advance", "(F)V");
        }
    }

    private static void assertMarketStepReplayWrapper(byte[] bytes, boolean recent) {
        ClassNode node = read(bytes);
        MethodNode wrapper = method(node, "advance", "(F)V");
        MethodNode raw = method(node, "smo$advanceSingleStep", "(F)V");
        require((raw.access & Opcodes.ACC_PRIVATE) != 0
                        && (raw.access & Opcodes.ACC_SYNTHETIC) != 0,
                node.name + " replay body metadata changed");
        require(countCalls(wrapper, Opcodes.INVOKESPECIAL, node.name,
                        "smo$advanceSingleStep", "(F)V") == 2,
                node.name + " replay wrapper raw-call count changed");
        require(countCalls(wrapper, -1, node.name, "advance", "(F)V") == 0,
                node.name + " replay wrapper recurses into itself");
        require(countCalls(wrapper, Opcodes.INVOKESTATIC, HOOKS,
                        "shouldContinueRecentUnrestReplay",
                        "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)Z")
                        == (recent ? 1 : 0),
                node.name + " RecentUnrest break hook changed");
    }

    private static void assertBaseIndustryDormantFastPath(byte[] bytes) {
        ClassNode node = read(bytes);
        int fieldAccess = Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC;
        FieldNode state = requireField(node, "spp$marketNoOpState", "I");
        FieldNode countdown = requireField(node, "spp$marketNoOpCountdown", "I");
        require(state.access == fieldAccess && state.signature == null && state.value == null,
                "BaseIndustry dormant state field metadata changed");
        require(countdown.access == fieldAccess && countdown.signature == null
                        && countdown.value == null,
                "BaseIndustry dormant countdown field metadata changed");

        MethodNode wrapper = method(node, "advance", "(F)V");
        MethodNode raw = method(node, "spp$baseIndustryRawAdvance", "(F)V");
        require((raw.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC))
                        == (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC),
                "BaseIndustry raw advance metadata changed");
        require(countCalls(wrapper, Opcodes.INVOKEVIRTUAL, node.name,
                        "spp$baseIndustryRawAdvance", "(F)V") == 1,
                "BaseIndustry wrapper raw call changed");
        require(countCalls(wrapper, Opcodes.INVOKESTATIC, HOOKS,
                        "isBaseIndustryDormantFastPathEligible", "(Ljava/lang/Object;)Z") == 1,
                "BaseIndustry eligibility call changed");

        MethodNode disrupted = method(node, "setDisrupted", "(FZ)V");
        List<AbstractInsnNode> code = meaningfulInstructions(disrupted);
        require(code.size() >= 6
                        && code.get(0) instanceof VarInsnNode a0 && a0.getOpcode() == Opcodes.ALOAD
                        && a0.var == 0
                        && code.get(1).getOpcode() == Opcodes.ICONST_0
                        && code.get(2) instanceof FieldInsnNode s && s.getOpcode() == Opcodes.PUTFIELD
                        && s.owner.equals(node.name) && s.name.equals("spp$marketNoOpState")
                        && code.get(3) instanceof VarInsnNode a1 && a1.getOpcode() == Opcodes.ALOAD
                        && a1.var == 0
                        && code.get(4).getOpcode() == Opcodes.ICONST_0
                        && code.get(5) instanceof FieldInsnNode c && c.getOpcode() == Opcodes.PUTFIELD
                        && c.owner.equals(node.name) && c.name.equals("spp$marketNoOpCountdown"),
                "BaseIndustry setDisrupted wake prologue changed");
    }

    private static void assertMarketCommodityTemporalActiveSet(byte[] bytes) {
        ClassNode node = read(bytes);
        int fieldAccess = Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC;
        FieldNode state = requireField(node, "smo$commodityTemporalState", "Ljava/lang/Object;");
        require(state.access == fieldAccess && state.signature == null && state.value == null,
                "Market commodity temporal state field metadata changed");
        MethodNode advance = optionalMethod(node, "smo$marketAdvancePlanBody", "(F)V");
        if (advance == null) advance = method(node, "advance", "(F)V");
        require(countCalls(advance, Opcodes.INVOKESTATIC, HOOKS,
                "advanceMarketCommodityTemporalState",
                "(Ljava/lang/Object;Ljava/lang/Object;F)Ljava/lang/Object;") == 1,
                "Market commodity temporal hook count changed");
        require(countCalls(advance, Opcodes.INVOKEVIRTUAL, node.name,
                "getCommodities", "()Ljava/util/List;") == 0,
                "Market commodity temporal loop still calls getCommodities directly");
    }

    private static void assertCommodityTemporalBinding(byte[] bytes) {
        ClassNode node = read(bytes);
        int fieldAccess = Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC;
        FieldNode owner = requireField(node, "spp$commodityTemporalOwner", "Ljava/lang/Object;");
        FieldNode role = requireField(node, "spp$commodityTemporalRole", "I");
        require(owner.access == fieldAccess && owner.signature == null && owner.value == null,
                "MutableStat commodity owner field metadata changed");
        require(role.access == fieldAccess && role.signature == null && role.value == null,
                "MutableStat commodity role field metadata changed");
        require(countHook(bytes, "markCommodityTemporalOwnerDirty") == 14,
                "MutableStat commodity temporal dirty hook count changed");
    }

    private static void assertEconomyPersistentSnapshots(byte[] bytes) {
        ClassNode node = read(bytes);
        int fieldAccess = Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC;
        FieldNode state = requireField(node, "smo$economyMarketsSnapshotState",
                "Ljava/lang/Object;");
        require(state.access == fieldAccess && state.signature == null && state.value == null,
                "Economy persistent state field metadata changed");

        MethodNode accessor = method(node, "smo$borrowPersistentMarketsSnapshot",
                "()Ljava/util/List;");
        require(accessor.access == (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC),
                "Economy persistent accessor metadata changed");
        require(countCalls(accessor, Opcodes.INVOKESTATIC, HOOKS,
                "borrowPersistentSnapshotTimed",
                "(Ljava/lang/Object;Ljava/util/Collection;II)Ljava/util/ArrayList;") == 1,
                "Economy persistent accessor hook changed");

        MethodNode advance = method(node, "advance", "(F)V");
        MethodNode paused = method(node, "advanceMarketConditionsWhenPaused", "(F)V");
        require(countCalls(advance, Opcodes.INVOKEVIRTUAL, node.name,
                accessor.name, accessor.desc) == 1,
                "Economy.advance persistent market snapshot changed");
        require(countCalls(paused, Opcodes.INVOKEVIRTUAL, node.name,
                accessor.name, accessor.desc) == 1,
                "Economy paused persistent market snapshot changed");
        require(countCalls(advance, Opcodes.INVOKEVIRTUAL, node.name,
                "getMarketsCopy", "()Ljava/util/List;") == 0
                        && countCalls(paused, Opcodes.INVOKEVIRTUAL, node.name,
                        "getMarketsCopy", "()Ljava/util/List;") == 0,
                "Economy persistent snapshots left getMarketsCopy in a hot path");
        require(countHook(bytes, "updateEconomyLocationMapIfNeeded") == 0,
                "Economy retained the per-frame full-fingerprint location hook");
        require(countHook(bytes, "borrowEconomyMarketsSnapshot") == 0
                        && countHook(bytes, "borrowEconomyCollectionSnapshot") == 0,
                "Economy retained removed scratch snapshot hooks");
        require(countCalls(paused, Opcodes.INVOKESPECIAL, "java/util/ArrayList",
                        "<init>", "(Ljava/util/Collection;)V") == 1,
                "Economy paused condition snapshot no longer uses vanilla fresh-copy semantics");
        require(countCalls(paused, Opcodes.INVOKESTATIC, HOOKS,
                        "beginScratchScope", "()V") == 0,
                "Economy persistent snapshots retained a hidden paused scratch scope");
    }

    private static void assertMarketPersistentSnapshots(byte[] bytes) {
        ClassNode node = read(bytes);
        int fieldAccess = Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC;
        for (String fieldName : List.of("smo$marketConditionsSnapshotState",
                "smo$marketIndustriesSnapshotState")) {
            FieldNode state = requireField(node, fieldName, "Ljava/lang/Object;");
            require(state.access == fieldAccess && state.signature == null && state.value == null,
                    "Market persistent state field metadata changed: " + fieldName);
        }
        MethodNode conditions = method(node, "smo$borrowPersistentConditionsSnapshot",
                "(Ljava/util/Collection;)Ljava/util/ArrayList;");
        MethodNode industries = method(node, "smo$borrowPersistentIndustriesSnapshot",
                "(Ljava/util/Collection;)Ljava/util/ArrayList;");
        for (MethodNode accessor : List.of(conditions, industries)) {
            require(accessor.access == (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC),
                    "Market persistent accessor metadata changed: " + accessor.name);
            require(countCalls(accessor, Opcodes.INVOKESTATIC, HOOKS,
                    "borrowPersistentSnapshotFrames",
                    "(Ljava/lang/Object;Ljava/util/Collection;II)Ljava/util/ArrayList;") == 1,
                    "Market persistent accessor hook changed: " + accessor.name);
        }
        MethodNode advance = optionalMethod(node, "smo$marketAdvancePlanBody", "(F)V");
        if (advance == null) advance = method(node, "advance", "(F)V");
        require(countCalls(advance, Opcodes.INVOKEVIRTUAL, node.name,
                conditions.name, conditions.desc) == 1,
                "Market conditions snapshot call changed");
        require(countCalls(advance, Opcodes.INVOKEVIRTUAL, node.name,
                industries.name, industries.desc) == 1,
                "Market industries snapshot call changed");
        require(countHook(bytes, "borrowEconomyMarketsSnapshot") == 0
                        && countHook(bytes, "borrowEconomyCollectionSnapshot") == 0,
                "Market retained removed scratch snapshot hooks");
    }

    private static void assertTempModExpiryScheduler(byte[] bytes) {
        ClassNode node = read(bytes);
        int access = Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC;
        String[][] fields = {
                {"spp$tempModHybridDeferredDays", "F"},
                {"spp$tempModHybridTimeToNext", "F"},
                {"spp$tempModHybridScheduledMin", "F"},
                {"spp$tempModHybridScheduledMinCount", "I"},
                {"spp$tempModHybridKnownSize", "I"},
                {"spp$tempModHybridFlags", "I"}
        };
        for (String[] spec : fields) {
            FieldNode found = null;
            for (FieldNode field : node.fields) {
                if (!field.name.equals(spec[0])) continue;
                require(found == null, "duplicate temp-mod hybrid field " + spec[0]);
                found = field;
            }
            require(found != null, "temp-mod hybrid field missing: " + spec[0]);
            require(found.access == access && found.desc.equals(spec[1])
                            && found.signature == null && found.value == null,
                    "temp-mod hybrid field shape changed: " + spec[0]);
        }
        for (FieldNode field : node.fields) {
            require(!field.name.equals("spp$tempModExpiryState"),
                    "legacy temp-mod scheduler state remains");
        }
        require(method(node, "spp$originalTempModAdvance", "(F)V") != null,
                "temp-mod original advance missing");
        require(method(node, "spp$tempModHybridApplyElapsed", "(FZZ)V") != null,
                "temp-mod direct apply helper missing");
        require(method(node, "spp$tempModHybridSynchronize", "()V") != null,
                "temp-mod synchronization helper missing");
        require(method(node, "spp$tempModHybridAuditForCommodity", "()V") != null,
                "temp-mod commodity audit helper missing");
        require(countHook(bytes, TEMP_MOD_HOOKS, "advance") == 0,
                "legacy per-frame temp-mod hook remains");
        require(countHook(bytes, TEMP_MOD_HOOKS, "recordHybridExpirySweep") == 1,
                "hybrid expiry telemetry missing");
    }

    private static void assertCommodityEventModDirtyCache(byte[] bytes) {
        ClassNode node = read(bytes);
        FieldNode knownAbsent = null;
        for (FieldNode field : node.fields) {
            if (!field.name.equals("smo$commodityEventModKnownAbsent")) continue;
            require(knownAbsent == null,
                    "duplicate CommodityOnMarket known-absent cache field");
            knownAbsent = field;
        }
        int expectedAccess = Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC;
        require(knownAbsent != null && knownAbsent.desc.equals("Z")
                        && knownAbsent.access == expectedAccess
                        && knownAbsent.signature == null && knownAbsent.value == null,
                "CommodityOnMarket known-absent cache field changed");

        MethodNode method = method(node, "reapplyEventMod", "()V");
        require(countCalls(method, Opcodes.INVOKEVIRTUAL, node.name,
                        "getCombinedTradeModQuantity", "()F") == 1,
                "CommodityOnMarket dirty cache lost quantity calculation");
        require(countCalls(method, Opcodes.INVOKEVIRTUAL, node.name,
                        "getModValueForQuantity", "(F)F") == 1,
                "CommodityOnMarket dirty cache lost value calculation");
        require(countCalls(method, Opcodes.INVOKEVIRTUAL,
                        "com/fs/starfarer/api/combat/MutableStatWithTempMods",
                        "unmodifyFlat", "(Ljava/lang/String;)V") == 2,
                "CommodityOnMarket dirty cache removal count changed");
        require(countCalls(method, Opcodes.INVOKEVIRTUAL,
                        "com/fs/starfarer/api/combat/MutableStatWithTempMods",
                        "modifyFlat", "(Ljava/lang/String;FLjava/lang/String;)V") == 1,
                "CommodityOnMarket dirty cache modification count changed");

        int cacheReads = 0;
        int cacheWrites = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof FieldInsnNode field) || !field.owner.equals(node.name)) continue;
            if (!field.name.equals("smo$commodityEventModKnownAbsent")
                    || !field.desc.equals("Z")) continue;
            if (field.getOpcode() == Opcodes.GETFIELD) cacheReads++;
            if (field.getOpcode() == Opcodes.PUTFIELD) cacheWrites++;
        }
        require(cacheReads == 1 && cacheWrites == 2,
                "CommodityOnMarket known-absent cache access changed");

        List<MethodInsnNode> removals = calls(method, Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/api/combat/MutableStatWithTempMods",
                "unmodifyFlat", "(Ljava/lang/String;)V");
        List<MethodInsnNode> calculations = calls(method, Opcodes.INVOKEVIRTUAL,
                node.name, "getModValueForQuantity", "(F)F");
        require(method.instructions.indexOf(removals.get(0))
                        < method.instructions.indexOf(calculations.get(0)),
                "CommodityOnMarket nonzero slow path no longer removes eMod before calculation");
        require(method.tryCatchBlocks.isEmpty(),
                "CommodityOnMarket dirty cache gained an unexpected exception region");
    }

    private static void assertEntityScriptEmptyGuard(byte[] bytes) {
        ClassNode node = read(bytes);
        MethodNode method = method(node, "runScripts", "(F)V");
        require(countCalls(method, "beginScratchScope") == 0
                        && countCalls(method, "endScratchScope") == 0,
                "BaseCampaignEntity.runScripts still opens a generic scratch scope");
        require(countCalls(method, "borrowEntityScriptSnapshot") == 0,
                "BaseCampaignEntity.runScripts still calls the retired snapshot helper");
        require(countCalls(method, Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>",
                "(Ljava/util/Collection;)V") == 1,
                "BaseCampaignEntity.runScripts non-empty path lost its fresh vanilla snapshot");
        require(countCalls(method, Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z") == 1,
                "BaseCampaignEntity.runScripts has no unique inline empty guard");
        List<AbstractInsnNode> code = meaningfulInstructions(method);
        require(code.size() >= 6, "BaseCampaignEntity.runScripts guard is truncated");
        require(code.get(0) instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD
                        && load.var == 0,
                "BaseCampaignEntity.runScripts guard does not start from this");
        require(code.get(1) instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                        && field.owner.equals(node.name) && field.name.equals("scripts")
                        && field.desc.equals("Ljava/util/List;"),
                "BaseCampaignEntity.runScripts guard does not read scripts");
        require(code.get(2) instanceof MethodInsnNode empty
                        && empty.getOpcode() == Opcodes.INVOKEINTERFACE
                        && empty.owner.equals("java/util/List") && empty.name.equals("isEmpty")
                        && empty.desc.equals("()Z"),
                "BaseCampaignEntity.runScripts empty check changed");
        require(code.get(3) instanceof JumpInsnNode
                        && code.get(3).getOpcode() == Opcodes.IFEQ,
                "BaseCampaignEntity.runScripts empty guard branch changed");
        JumpInsnNode jump = (JumpInsnNode) code.get(3);
        require(code.get(4).getOpcode() == Opcodes.RETURN,
                "BaseCampaignEntity.runScripts empty guard does not return immediately");
        require(code.get(5) instanceof TypeInsnNode snapshot
                        && snapshot.getOpcode() == Opcodes.NEW
                        && snapshot.desc.equals("java/util/ArrayList")
                        && nextMeaningful(jump.label) == snapshot,
                "BaseCampaignEntity.runScripts non-empty branch does not rejoin at the original snapshot");
    }

    private static void assertMemoryEmptyGuard(byte[] bytes) {
        ClassNode node = read(bytes);
        MethodNode method = method(node, "advance", "(F)V");
        require(countCalls(method, "memoryExpireIterator") == 0
                        && countCalls(method, "memoryRequireIterator") == 0,
                "Memory.advance still calls the retired iterator helpers");
        require(countCalls(method, Opcodes.INVOKEINTERFACE, "java/util/List", "iterator",
                "()Ljava/util/Iterator;") == 1,
                "Memory.advance expire iterator semantics changed");
        require(countCalls(method, Opcodes.INVOKEINTERFACE, "java/util/Collection", "iterator",
                "()Ljava/util/Iterator;") == 1,
                "Memory.advance require iterator semantics changed");
        require(countCalls(method, Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z") == 1,
                "Memory.advance expire empty guard is missing or duplicated");
        require(countCalls(method, Opcodes.INVOKEVIRTUAL, "java/util/LinkedHashMap", "isEmpty",
                "()Z") == 1,
                "Memory.advance require empty guard is missing or duplicated");

        List<MethodInsnNode> engines = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("com/fs/starfarer/campaign/CampaignEngine")
                    && call.name.equals("getInstance")
                    && call.desc.equals("()Lcom/fs/starfarer/campaign/CampaignEngine;")) {
                engines.add(call);
            }
        }
        require(engines.size() == 2,
                "Memory.advance engine/pause structure changed");
        MethodInsnNode workStart = engines.get(1);
        AbstractInsnNode emptyReturn = previousMeaningful(workStart);
        AbstractInsnNode secondJumpInsn = previousMeaningful(emptyReturn);
        AbstractInsnNode requireEmpty = previousMeaningful(secondJumpInsn);
        AbstractInsnNode requireField = previousMeaningful(requireEmpty);
        AbstractInsnNode requireLoad = previousMeaningful(requireField);
        AbstractInsnNode firstJumpInsn = previousMeaningful(requireLoad);
        require(emptyReturn != null && emptyReturn.getOpcode() == Opcodes.RETURN
                        && secondJumpInsn instanceof JumpInsnNode secondJump
                        && secondJump.getOpcode() == Opcodes.IFEQ
                        && requireEmpty instanceof MethodInsnNode requireCall
                        && requireCall.owner.equals("java/util/LinkedHashMap")
                        && requireCall.name.equals("isEmpty")
                        && requireField instanceof FieldInsnNode field
                        && field.name.equals("require")
                        && requireLoad instanceof VarInsnNode load
                        && load.getOpcode() == Opcodes.ALOAD && load.var == 0
                        && firstJumpInsn instanceof JumpInsnNode firstJump
                        && firstJump.getOpcode() == Opcodes.IFEQ
                        && firstJump.label == secondJump.label
                        && nextMeaningful(firstJump.label) == workStart,
                "Memory.advance guard is not the exact post-pause prefix");
    }

    private static int countRedundantSaveBuffers(MethodNode save) {
        int redundant = 0;
        for (AbstractInsnNode insn : save.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.owner.equals("java/io/BufferedOutputStream")
                    || !call.name.equals("<init>")
                    || !call.desc.equals("(Ljava/io/OutputStream;I)V")) continue;
            AbstractInsnNode size = previousMeaningful(call);
            if (size instanceof LdcInsnNode ldc
                    && Integer.valueOf(1_048_576).equals(ldc.cst)) redundant++;
        }
        return redundant;
    }

    private static void assertRedundantSaveBufferRemoved(byte[] bytes) {
        ClassNode node = read(bytes);
        MethodNode save = method(node, "o00000",
                "(Lcom/fs/starfarer/campaign/CampaignEngine$o;JZ)Ljava/lang/String;");
        require(countRedundantSaveBuffers(save) == 0,
                "redundant outer 1 MiB save buffer is still present");
    }

    private static void assertMarketSchedulerTickScope(byte[] bytes) {
        ClassNode node = read(bytes);
        MethodNode scoped = method(node, "advance", "(FLcom/fs/starfarer/util/A/new;)V");
        require(countCalls(scoped, "beginMarketSchedulerTick") == 1,
                node.name + ".advance has no unique market-scheduler tick entry");
        int returns = 0;
        for (AbstractInsnNode insn : scoped.instructions.toArray()) {
            if (insn.getOpcode() < Opcodes.IRETURN || insn.getOpcode() > Opcodes.RETURN) continue;
            returns++;
            AbstractInsnNode previous = previousMeaningful(insn);
            require(previous instanceof MethodInsnNode call && call.owner.equals(HOOKS)
                            && call.name.equals("endMarketSchedulerTick") && call.desc.equals("()V"),
                    node.name + ".advance has an unclosed market-scheduler tick return");
        }
        require(countCalls(scoped, "endMarketSchedulerTick") == returns + 1,
                node.name + ".advance market-scheduler tick exit count changed");

        MethodNode setter = method(node, "setFastForwardIteration", "(Z)V");
        require(setter != null, node.name + ".setFastForwardIteration(Z)V is missing");
        require(countCalls(setter, Opcodes.INVOKESTATIC, HOOKS,
                        "marketSchedulerFastForwardIterationChanged", "(Z)V") == 1,
                node.name + ".setFastForwardIteration has no unique render-batch hook");
        for (AbstractInsnNode insn : setter.instructions.toArray()) {
            if (insn.getOpcode() != Opcodes.RETURN) continue;
            AbstractInsnNode hookInsn = previousMeaningful(insn);
            require(hookInsn instanceof MethodInsnNode hook && hook.owner.equals(HOOKS)
                            && hook.name.equals("marketSchedulerFastForwardIterationChanged")
                            && hook.desc.equals("(Z)V"),
                    node.name + ".setFastForwardIteration return does not close the batch boundary");
            AbstractInsnNode source = previousMeaningful(hookInsn);
            require(source instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD
                            && load.var == 1,
                    node.name + ".setFastForwardIteration hook source is not argument 1");
        }
    }

    private static void assertScratchScope(byte[] bytes, String name, String desc) {
        ClassNode node = read(bytes);
        MethodNode scoped = method(node, name, desc);
        require(countCalls(scoped, "beginScratchScope") == 1,
                node.name + "." + name + " has no unique scratch-scope entry");
        int returns = 0;
        for (AbstractInsnNode insn : scoped.instructions.toArray()) {
            if (insn.getOpcode() < Opcodes.IRETURN || insn.getOpcode() > Opcodes.RETURN) continue;
            returns++;
            AbstractInsnNode previous = previousMeaningful(insn);
            require(previous instanceof MethodInsnNode call && call.owner.equals(HOOKS)
                            && call.name.equals("endScratchScope") && call.desc.equals("()V"),
                    node.name + "." + name + " has an unscoped return");
        }
        require(countCalls(scoped, "endScratchScope") == returns + 1,
                node.name + "." + name + " scratch-scope exit count changed");

        boolean fullHandler = false;
        for (TryCatchBlockNode block : scoped.tryCatchBlocks) {
            if (block.type != null) continue;
            FrameNode frame = null;
            AbstractInsnNode cursor = block.handler.getNext();
            while (cursor != null && !(cursor instanceof MethodInsnNode)) {
                if (cursor instanceof FrameNode candidate) frame = candidate;
                cursor = cursor.getNext();
            }
            if (cursor instanceof MethodInsnNode call && call.owner.equals(HOOKS)
                    && call.name.equals("endScratchScope") && call.desc.equals("()V")) {
                require(frame != null && frame.type == Opcodes.F_FULL,
                        node.name + "." + name + " scratch handler is not F_FULL");
                require(frame.local != null && frame.local.equals(initialFrameLocals(node.name, scoped)),
                        node.name + "." + name + " scratch handler locals are not the entry locals");
                fullHandler = true;
            }
        }
        require(fullHandler, node.name + "." + name + " scratch catch-all is missing");
    }

    private static List<Object> initialFrameLocals(String owner, MethodNode method) {
        List<Object> locals = new ArrayList<>();
        if ((method.access & Opcodes.ACC_STATIC) == 0) locals.add(owner);
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            switch (argument.getSort()) {
                case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> locals.add(Opcodes.INTEGER);
                case Type.FLOAT -> locals.add(Opcodes.FLOAT);
                case Type.LONG -> locals.add(Opcodes.LONG);
                case Type.DOUBLE -> locals.add(Opcodes.DOUBLE);
                case Type.ARRAY -> locals.add(argument.getDescriptor());
                case Type.OBJECT -> locals.add(argument.getInternalName());
                default -> throw new AssertionError("unsupported frame argument " + argument);
            }
        }
        return locals;
    }

    private static void prepatchOneViewportRange(ClassNode node) {
        for (MethodNode method : node.methods) {
            int width = -1;
            int height = -1;
            AbstractInsnNode afterHeight = null;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call) || !call.desc.equals("()F")) continue;
                AbstractInsnNode store = nextMeaningful(call);
                if (!(store instanceof VarInsnNode local)
                        || local.getOpcode() != Opcodes.FSTORE) continue;
                if (call.name.equals("getVisibleWidth")) width = local.var;
                else if (call.name.equals("getVisibleHeight")) {
                    height = local.var;
                    afterHeight = store;
                }
            }
            if (width < 0 || height < 0 || afterHeight == null) continue;
            List<VarInsnNode> widthLoads = new ArrayList<>();
            for (AbstractInsnNode insn = afterHeight.getNext(); insn != null;
                 insn = insn.getNext()) {
                if (insn instanceof VarInsnNode load
                        && load.getOpcode() == Opcodes.FLOAD && load.var == width) {
                    widthLoads.add(load);
                }
            }
            if (widthLoads.size() != 3) continue;
            widthLoads.get(2).var = height;
            return;
        }
        throw new AssertionError("viewport vertical-range fixture site not found");
    }

    private static void prepatchOneViewportClamp(ClassNode node) {
        for (MethodNode method : node.methods) {
            boolean viewportMethod = false;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call
                        && call.name.equals("getVisibleHeight") && call.desc.equals("()F")) {
                    viewportMethod = true;
                    break;
                }
            }
            if (!viewportMethod) continue;
            List<InsnNode> comparisons = new ArrayList<>();
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof InsnNode length)
                        || length.getOpcode() != Opcodes.ARRAYLENGTH) continue;
                AbstractInsnNode previous = previousMeaningful(length);
                AbstractInsnNode next1 = nextMeaningful(length);
                AbstractInsnNode next2 = next1 == null ? null : nextMeaningful(next1);
                AbstractInsnNode next3 = next2 == null ? null : nextMeaningful(next2);
                if (!(previous instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.GETFIELD || !field.desc.startsWith("[[")
                        || next1 == null || next1.getOpcode() != Opcodes.I2F
                        || next2 == null || next2.getOpcode() != Opcodes.FCMPL
                        || !(next3 instanceof JumpInsnNode)) continue;
                comparisons.add(length);
            }
            if (comparisons.size() != 2) continue;
            InsnNode victim = comparisons.get(1);
            InsnList replacement = new InsnList();
            replacement.add(new InsnNode(Opcodes.ICONST_0));
            replacement.add(new InsnNode(Opcodes.AALOAD));
            replacement.add(new InsnNode(Opcodes.ARRAYLENGTH));
            method.instructions.insertBefore(victim, replacement);
            method.instructions.remove(victim);
            return;
        }
        throw new AssertionError("viewport vertical-clamp fixture site not found");
    }

    private static List<AbstractInsnNode> meaningfulInstructions(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn.getOpcode() >= 0) result.add(insn);
        }
        return result;
    }

    private static int countCalls(MethodNode method, String name) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(HOOKS)
                    && call.name.equals(name) && call.desc.equals("()V")) count++;
        }
        return count;
    }

    private static int countCalls(MethodNode method, int opcode, String owner,
                                  String name, String desc) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == opcode
                    && call.owner.equals(owner) && call.name.equals(name)
                    && call.desc.equals(desc)) count++;
        }
        return count;
    }

    private static int countHook(byte[] bytes, String name) {
        return countHook(bytes, HOOKS, name);
    }

    private static int countHook(byte[] bytes, String owner, String name) {
        int count = 0;
        ClassNode node = read(bytes);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals(owner) && call.name.equals(name)) count++;
            }
        }
        return count;
    }

    private static void assertHookTargetsExist(byte[] transformed) {
        Map<String, Integer> hooks = hookMethods(HOOKS);
        Map<String, Integer> hyperspaceHooks = hookMethods(HYPERSPACE_HOOKS);
        Map<String, Integer> tempModHooks = hookMethods(TEMP_MOD_HOOKS);
        ClassNode node = read(transformed);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call)
                        || (!call.owner.equals(HOOKS) && !call.owner.equals(HYPERSPACE_HOOKS)
                        && !call.owner.equals(TEMP_MOD_HOOKS))) continue;
                String key = call.name + call.desc;
                Map<String, Integer> ownerHooks = call.owner.equals(HOOKS) ? hooks
                        : call.owner.equals(HYPERSPACE_HOOKS) ? hyperspaceHooks : tempModHooks;
                Integer access = ownerHooks.get(key);
                require(access != null, "missing runtime hook target " + key);
                require(call.getOpcode() == Opcodes.INVOKESTATIC
                                && (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
                                == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC),
                        "runtime hook is not public static: " + key);
            }
        }
    }

    private static void assertHookCount(byte[] bytes, String owner, String name, int expected) {
        int actual = countHook(bytes, owner, name);
        require(actual == expected, name + " expected=" + expected + " actual=" + actual);
    }

    private static Map<String, Integer> hookMethods(String owner) {
        try (var stream = StructuralCompatibilityTest.class.getClassLoader()
                .getResourceAsStream(owner + ".class")) {
            require(stream != null, owner + ".class is missing from the test classpath");
            ClassNode hooks = read(stream.readAllBytes());
            Map<String, Integer> result = new LinkedHashMap<>();
            for (MethodNode method : hooks.methods) result.put(method.name + method.desc, method.access);
            return result;
        } catch (Exception ex) {
            throw new AssertionError("unable to inspect hook linkage for " + owner, ex);
        }
    }

    private static int verifyBytecode(byte[] bytes) {
        ClassNode node = read(bytes);
        int methods = 0;
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            try {
                new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
            } catch (AnalyzerException ex) {
                throw new AssertionError("BasicVerifier rejected " + node.name + "."
                        + method.name + method.desc, ex);
            }
            methods++;
        }
        return methods;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM8);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] removeSchedulerRegistration(byte[] bytes, int component) {
        ClassNode node = read(bytes);
        MethodNode initializer = method(node, "<clinit>", "()V");
        int removed = 0;
        for (AbstractInsnNode insn : initializer.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !call.owner.equals(HOOKS)
                    || !call.name.equals("registerMarketSchedulerComponent")
                    || !call.desc.equals("(I)V")) continue;
            AbstractInsnNode source = previousMeaningful(call);
            if (!isIntegerConstant(source, component)) continue;
            initializer.instructions.remove(source);
            initializer.instructions.remove(call);
            removed++;
        }
        require(removed > 0,
                "scheduler registration fixture missing component " + component);
        return write(node);
    }

    private static FieldNode findFieldOrNull(ClassNode node, String name) {
        FieldNode result = null;
        for (FieldNode field : node.fields) {
            if (!field.name.equals(name)) continue;
            require(result == null, "field " + name + " is duplicated in test input");
            result = field;
        }
        return result;
    }

    private static FieldNode requireField(ClassNode node, String name, String desc) {
        FieldNode found = null;
        for (FieldNode field : node.fields) {
            if (!field.name.equals(name) || !field.desc.equals(desc)) continue;
            require(found == null, "field " + name + desc + " occurs more than once");
            found = field;
        }
        require(found != null, "field " + name + desc + " is missing");
        return found;
    }

    private static MethodNode optionalMethod(ClassNode node, String name, String desc) {
        MethodNode found = null;
        for (MethodNode method : node.methods) {
            if (!method.name.equals(name) || !method.desc.equals(desc)) continue;
            require(found == null, "duplicate method " + name + desc);
            found = method;
        }
        return found;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        List<MethodNode> found = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) found.add(method);
        }
        require(found.size() == 1, "method " + name + desc + " expected once, found " + found.size());
        return found.get(0);
    }

    private static AbstractInsnNode firstMeaningful(MethodNode method) {
        AbstractInsnNode current = method.instructions.getFirst();
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode node) {
        AbstractInsnNode current = node.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }


    private static boolean isIntegerConstant(AbstractInsnNode insn, int value) {
        if (insn == null) return false;
        int opcode = insn.getOpcode();
        if (value >= 0 && value <= 5 && opcode == Opcodes.ICONST_0 + value) return true;
        if (value == -1 && opcode == Opcodes.ICONST_M1) return true;
        if (insn instanceof IntInsnNode integer) return integer.operand == value;
        return insn instanceof LdcInsnNode ldc && Integer.valueOf(value).equals(ldc.cst);
    }

    private static AbstractInsnNode nextMeaningful(AbstractInsnNode node) {
        AbstractInsnNode current = node.getNext();
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static int integerProperty(String key) {
        return Integer.parseInt(System.getProperty(key, "0"));
    }

    private enum Mutation { UNRELATED_NOP, UNRELATED_RETAIN, MISSING_RETAIN, DUPLICATE_RETAIN, MISSING_CLASS_SET }
    private enum HitMutation { MISSING_HIT_LIST, MISSING_FACTOR_CALL }
    private enum EventsMutation { MISSING_CONTAINS, MISSING_VALUES }
    private record MethodKey(String name, String desc, String patchId) {}
    private record HookTamper(String hookName, String patchId) {}

    private record Result(int classes, int methods) {}
}
