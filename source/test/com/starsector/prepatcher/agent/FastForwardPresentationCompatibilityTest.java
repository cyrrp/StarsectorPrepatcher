package com.starsector.prepatcher.agent;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.FieldNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Analyzer;
import jdk.internal.org.objectweb.asm.tree.analysis.AnalyzerException;
import jdk.internal.org.objectweb.asm.tree.analysis.BasicValue;
import jdk.internal.org.objectweb.asm.tree.analysis.BasicVerifier;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarFile;

/** Offline structural verification for fast-forward presentation coalescing. */
public final class FastForwardPresentationCompatibilityTest {
    private static final String PRESENTATION_HOOKS =
            "com/fs/starfarer/api/StarsectorPrepatcherPresentationHooks";
    private static final String PREPATCHER_HOOK_PREFIX =
            "com/fs/starfarer/api/StarsectorPrepatcher";
    private static final String PATCH_MARKER_PREFIX = "smo$patched$";

    private static final Map<String, Expected> EXPECTED = expectedTargets();
    private static final Map<String, Set<String>> CHAIN_MARKERS = Map.of(
            "com/fs/starfarer/campaign/CampaignState", Set.of(
                    "smo$patched$marketScheduler"),
            "com/fs/starfarer/campaign/CampaignEngine", Set.of(
                    "smo$patched$campaignCacheLifecycle",
                    "smo$patched$coreWorldsExtentCache",
                    "smo$patched$marketScheduler",
                    "smo$patched$entityLookupIndexRepair",
                    "smo$patched$campaignListenerThrottle"),
            "com/fs/starfarer/campaign/BaseLocation", Set.of(
                    "smo$patched$campaignSnapshotReuse",
                    "smo$patched$coreWorldsExtentCache",
                    "smo$patched$strategicJumpDestinationIndex"),
            "com/fs/starfarer/campaign/BaseCampaignEntity", Set.of(
                    "smo$patched$entityScriptSnapshotReuse",
                    "smo$patched$marketScheduler"),
            "com/fs/starfarer/campaign/JumpPoint", Set.of(
                    "smo$patched$strategicJumpDestinationIndex"),
            "com/fs/starfarer/api/impl/campaign/terrain/HyperspaceTerrainPlugin", Set.of(
                    "smo$patched$skipNoOpTerrainLayer"));

    private FastForwardPresentationCompatibilityTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: FastForwardPresentationCompatibilityTest <starfarer_obf.jar> <starfarer.api.jar>");
        }
        Path coreJar = Path.of(args[0]).toAbsolutePath().normalize();
        Path apiJar = Path.of(args[1]).toAbsolutePath().normalize();
        require(Files.isRegularFile(coreJar), "core JAR not found: " + coreJar);
        require(Files.isRegularFile(apiJar), "API JAR not found: " + apiJar);

        Map<String, byte[]> originals = readOriginals(coreJar, apiJar);
        PrepatcherConfig safe = config(profileProperties(false));
        PrepatcherConfig aggressive = config(profileProperties(true));

        ProfileResult safeResult = verifyProfile("safe", safe, originals, false);
        require(safeResult.classes() == 20,
                "safe profile must patch exactly 20 classes, found " + safeResult.classes());
        require(safeResult.callSites() == 59,
                "safe profile must patch exactly 59 call sites, found " + safeResult.callSites());

        ProfileResult aggressiveResult = verifyProfile(
                "aggressive", aggressive, originals, true);
        require(aggressiveResult.classes() == 24,
                "aggressive profile must patch exactly 24 classes, found "
                        + aggressiveResult.classes());
        require(aggressiveResult.callSites() == 71,
                "aggressive profile must patch exactly 71 call sites, found "
                        + aggressiveResult.callSites());

        verifyDisabledGroups(originals);
        verifyStructuralMutationTolerance(safe, originals);
        verifyFeatureMasks(originals);
        verifyTransformerChain(originals);

        System.out.println("OK fast-forward presentation structuralTargets=" + EXPECTED.size()
                + " safeClasses=" + safeResult.classes()
                + " safeCallSites=" + safeResult.callSites()
                + " aggressiveClasses=" + aggressiveResult.classes()
                + " aggressiveCallSites=" + aggressiveResult.callSites()
                + " chainedTargets=" + CHAIN_MARKERS.size());
    }

    private static ProfileResult verifyProfile(String label, PrepatcherConfig config,
                                                Map<String, byte[]> originals,
                                                boolean aggressive) throws Exception {
        FastForwardPresentationTransformer transformer =
                new FastForwardPresentationTransformer(config);
        require(transformer.targetClassesForTest().equals(EXPECTED.keySet()),
                label + " target inventory changed: " + transformer.targetClassesForTest());

        int classes = 0;
        int callSites = 0;
        for (Map.Entry<String, Expected> entry : EXPECTED.entrySet()) {
            String name = entry.getKey();
            Expected expected = entry.getValue();
            require(expected.jar().equals(transformer.targetJarForTest(name)),
                    name + " container changed: " + transformer.targetJarForTest(name));
            byte[] original = originals.get(name);
            require(countPresentationCalls(original) == 0,
                    name + " already contains presentation hooks in the game JAR");

            byte[] transformed = transformer.transformBytesForTest(name, original);
            int expectedCalls = aggressive ? expected.aggressiveCalls() : expected.safeCalls();
            if (expectedCalls == 0) {
                require(transformed == null,
                        label + " disabled group unexpectedly transformed " + name);
                continue;
            }

            require(transformed != null, label + " did not transform " + name);
            require(classVersion(original) == classVersion(transformed),
                    label + " changed class version for " + name);
            int actualCalls = countPresentationCalls(transformed);
            require(actualCalls == expectedCalls,
                    label + " " + name + ": expected " + expectedCalls
                            + " presentation calls, found " + actualCalls);
            require(FastForwardPresentationTransformer.OWNER_VALUE.equals(
                            fieldValue(transformed,
                                    FastForwardPresentationTransformer.OWNER_FIELD)),
                    label + " presentation owner missing for " + name);
            require(((Integer) fieldValue(transformed,
                            FastForwardPresentationTransformer.MASK_FIELD))
                            == FastForwardPresentationTransformer.requestedMaskForClass(
                                    name, config),
                    label + " presentation feature mask mismatch for " + name);
            PresentationStructuralContract.State composition =
                    PresentationStructuralContract.inspect(name, transformed, config);
            require(composition.present()
                            == PresentationStructuralContract.OVERLAP_CLASSES.contains(name),
                    label + " presentation/structural ownership mismatch for " + name + ": "
                            + composition);
            require(transformer.transformBytesForTest(name, transformed) == null,
                    label + " presentation plan is not idempotent for " + name);
            verifyMethods(transformed);
            classes++;
            callSites += actualCalls;
        }
        return new ProfileResult(classes, callSites);
    }

    private static void verifyDisabledGroups(Map<String, byte[]> originals) throws Exception {
        Properties noGroupsProperties = profileProperties(false);
        setPresentationGroups(noGroupsProperties, false);
        PrepatcherConfig noGroups = config(noGroupsProperties);
        FastForwardPresentationTransformer noGroupsTransformer =
                new FastForwardPresentationTransformer(noGroups);
        for (Map.Entry<String, byte[]> entry : originals.entrySet()) {
            require(!noGroupsTransformer.isTargetEnabled(entry.getKey()),
                    "all-disabled configuration enabled " + entry.getKey());
            require(noGroupsTransformer.transformBytesForTest(
                            entry.getKey(), entry.getValue()) == null,
                    "all-disabled configuration transformed " + entry.getKey());
        }

        Properties masterOffProperties = profileProperties(true);
        masterOffProperties.setProperty("patch.fastForwardPresentation", "false");
        FastForwardPresentationTransformer masterOff =
                new FastForwardPresentationTransformer(config(masterOffProperties));
        for (Map.Entry<String, byte[]> entry : originals.entrySet()) {
            require(masterOff.transform(null, entry.getKey(), null, null,
                            entry.getValue()) == null,
                    "master-disabled configuration transformed " + entry.getKey());
        }
    }

    private static void verifyStructuralMutationTolerance(
            PrepatcherConfig config, Map<String, byte[]> originals) {
        FastForwardPresentationTransformer transformer =
                new FastForwardPresentationTransformer(config);
        for (String name : Set.of(
                "com/fs/starfarer/campaign/CampaignState",
                "com/fs/starfarer/campaign/CampaignEngine",
                "com/fs/starfarer/api/impl/campaign/terrain/BaseTerrain")) {
            ClassNode node = read(originals.get(name));
            node.fields.add(new FieldNode(Opcodes.ASM8,
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                    "smo$test$unrelated", "I", null, null));
            MethodNode helper = new MethodNode(Opcodes.ASM8, Opcodes.ACC_PRIVATE,
                    "smo$test$unrelated", "()V", null, null);
            helper.instructions.add(new jdk.internal.org.objectweb.asm.tree.InsnNode(
                    Opcodes.RETURN));
            helper.maxStack = 0;
            helper.maxLocals = 1;
            node.methods.add(helper);
            byte[] transformed = transformer.transformBytesForTest(name, write(node));
            require(transformed != null,
                    "unrelated field/method mutation blocked structural plan for " + name);
        }

        String damagedName = "com/fs/starfarer/campaign/CampaignPlanet";
        ClassNode damaged = read(originals.get(damagedName));
        MethodNode method = method(damaged, "advance", "(F)V");
        boolean changed = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals("com/fs/starfarer/combat/entities/terrain/Planet")
                    && call.name.equals("advance") && call.desc.equals("(F)V")) {
                call.name = "structurallyChanged";
                changed = true;
                break;
            }
        }
        require(changed, "relevant CampaignPlanet call not found for mutation");
        String status = statusKey(damagedName);
        System.clearProperty(status);
        require(transformer.transform(null, damagedName, null, null,
                        write(damaged)) == null,
                "relevant semantic damage did not fail open");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(status)),
                "relevant semantic damage did not report SKIPPED_STRUCTURAL: "
                        + System.getProperty(status));
    }

    private static void verifyFeatureMasks(Map<String, byte[]> originals) throws Exception {
        verifyMaskMatrix(originals, "com/fs/starfarer/campaign/CampaignEngine",
                new String[]{"patch.fastForwardActionIndicators",
                        "patch.fastForwardGlobalAnimations"});
        verifyMaskMatrix(originals, "com/fs/starfarer/campaign/BaseCampaignEntity",
                new String[]{"patch.fastForwardFloatingText",
                        "patch.fastForwardSensorIndicators",
                        "patch.fastForwardSensorFaders"});
        verifyMaskMatrix(originals, "com/fs/starfarer/campaign/fleet/CampaignFleet",
                new String[]{"patch.fastForwardFleetView",
                        "patch.fastForwardFleetPresentation"});
        verifyMaskMatrix(originals,
                "com/fs/starfarer/api/impl/campaign/velfield/SlipstreamTerrainPlugin2",
                new String[]{"patch.fastForwardContinuousSound",
                        "patch.fastForwardSlipstreamParticles"});
        verifyMaskMatrix(originals,
                "com/fs/starfarer/api/impl/campaign/GateEntityPlugin",
                new String[]{"patch.fastForwardContinuousSound",
                        "patch.fastForwardGateJitter",
                        "patch.fastForwardParticleEmitters"});
    }

    private static void verifyMaskMatrix(Map<String, byte[]> originals, String name,
                                         String[] flags) throws Exception {
        int combinations = 1 << flags.length;
        for (int combination = 1; combination < combinations; combination++) {
            Properties properties = profileProperties(false);
            setPresentationGroups(properties, false);
            for (int bit = 0; bit < flags.length; bit++) {
                properties.setProperty(flags[bit],
                        Boolean.toString((combination & (1 << bit)) != 0));
            }
            PrepatcherConfig config = config(properties);
            FastForwardPresentationTransformer transformer =
                    new FastForwardPresentationTransformer(config);
            byte[] transformed = transformer.transformBytesForTest(name, originals.get(name));
            require(transformed != null,
                    name + " feature-mask combination " + combination + " did not apply");
            require(((Integer) fieldValue(transformed,
                            FastForwardPresentationTransformer.MASK_FIELD))
                            == FastForwardPresentationTransformer.requestedMaskForClass(
                                    name, config),
                    name + " feature-mask combination " + combination + " mismatch");
            require(transformer.transformBytesForTest(name, transformed) == null,
                    name + " feature-mask combination " + combination + " is not idempotent");
        }
    }

    private static void verifyTransformerChain(Map<String, byte[]> originals)
            throws Exception {
        PrepatcherConfig config = config(chainProperties());
        FastForwardPresentationTransformer presentation =
                new FastForwardPresentationTransformer(config);
        Set<String> overlaps = new LinkedHashSet<>(
                presentation.targetClassesForTest());
        overlaps.retainAll(PrepatcherTransformer.TARGET_CLASSES);
        require(overlaps.equals(CHAIN_MARKERS.keySet()),
                "presentation/structural target intersection changed: " + overlaps);

        for (Map.Entry<String, Set<String>> entry : CHAIN_MARKERS.entrySet()) {
            String name = entry.getKey();
            byte[] original = originals.get(name);
            byte[] presentationBytes = presentation.transformBytesForTest(name, original);
            require(presentationBytes != null,
                    "presentation stage did not transform overlapping target " + name);
            int presentationCalls = countPresentationCalls(presentationBytes);
            require(presentationCalls == EXPECTED.get(name).safeCalls(),
                    "presentation stage call count changed for " + name);

            byte[] direct = new PrepatcherTransformer(config).transform(
                    null, name, null, null, original);
            require(direct != null,
                    "structural transformer did not transform vanilla overlap " + name);

            PresentationStructuralContract.State presentationState =
                    PresentationStructuralContract.inspect(name, presentationBytes, config);
            require(presentationState.present(),
                    "presentation stage did not publish composition ownership for " + name);

            byte[] chained = new PrepatcherTransformer(config).transform(
                    null, name, null, null, presentationBytes);
            require(chained != null,
                    "structural transformer rejected presentation-patched overlap " + name);
            presentationState.validate(chained, config);
            require("PASSED".equals(System.getProperty(
                            "starsector.prepatcher.patchStatus." + name.replace('/', '.')
                                    + ".presentationStructuralComposition")),
                    "combined presentation/structural status did not pass for " + name);
            require(countPresentationCalls(chained) == presentationCalls,
                    "structural transformer lost presentation hooks in " + name);
            require(patchMarkers(direct).equals(entry.getValue()),
                    "unexpected direct structural markers in " + name + ": "
                            + patchMarkers(direct));
            require(patchMarkers(chained).equals(entry.getValue()),
                    "chained structural markers changed in " + name + ": "
                            + patchMarkers(chained));
            require(prepatcherHookCalls(chained).equals(prepatcherHookCalls(direct)),
                    "chained structural hook postcondition differs in " + name
                            + ": direct=" + prepatcherHookCalls(direct)
                            + ", chained=" + prepatcherHookCalls(chained));
            require(classVersion(original) == classVersion(chained),
                    "transformer chain changed class version for " + name);
            verifyMethods(chained);

            String reverseStatus = statusKey(name);
            System.clearProperty(reverseStatus);
            byte[] reverse = presentation.transform(null, name, null, null, direct);
            String reverseResult = System.getProperty(reverseStatus);
            require(reverse != null || "SKIPPED_STRUCTURAL".equals(reverseResult),
                    "reverse structural->presentation order failed non-structurally for "
                            + name + ": status=" + reverseResult);
            if (reverse != null) verifyMethods(reverse);

            byte[] ownerless = removeField(presentationBytes,
                    PresentationStructuralContract.OWNER_FIELD);
            String compositionStatus = "starsector.prepatcher.patchStatus."
                    + name.replace('/', '.') + ".presentationStructuralComposition";
            System.clearProperty(compositionStatus);
            require(new PrepatcherTransformer(config).transform(
                            null, name, null, null, ownerless) == null,
                    "structural transformer accepted ownerless presentation hooks in " + name);
            require("SKIPPED_COMPOSITION".equals(System.getProperty(compositionStatus)),
                    "ownerless presentation state did not report SKIPPED_COMPOSITION in "
                            + name);
        }

        String tamperName = "com/fs/starfarer/campaign/CampaignEngine";
        byte[] tamperedHooks = renameFirstPresentationHook(
                presentation.transformBytesForTest(tamperName, originals.get(tamperName)));
        String tamperStatus = "starsector.prepatcher.patchStatus."
                + tamperName.replace('/', '.') + ".presentationStructuralComposition";
        System.clearProperty(tamperStatus);
        require(new PrepatcherTransformer(config).transform(
                        null, tamperName, null, null, tamperedHooks) == null,
                "structural transformer accepted a tampered presentation hook inventory");
        require("SKIPPED_COMPOSITION".equals(System.getProperty(tamperStatus)),
                "tampered presentation hook inventory did not fail composition");
    }

    private static Properties chainProperties() {
        Properties properties = profileProperties(false);

        // Exercise one stable existing structural patch on each overlap. The
        // CampaignEngine lifecycle companion is a required dependency of its
        // listener throttle and is therefore expected as a second marker.
        properties.setProperty("patch.campaignListenerThrottle", "true");
        properties.setProperty("patch.entityLookupIndexRepair", "true");
        properties.setProperty("patch.campaignSnapshotReuse", "true");
        properties.setProperty("patch.entityScriptSnapshotReuse", "true");
        properties.setProperty("patch.skipNoOpTerrainLayer", "true");
        properties.setProperty("patch.terrainRandomReuse", "false");
        properties.setProperty("patch.automatonBufferReuse", "false");

        properties.setProperty("patch.labelSpatialCandidates", "false");
        properties.setProperty("patch.intelCallbackCache", "false");
        properties.setProperty("patch.intelEntityIndex", "false");
        properties.setProperty("patch.mapHitTest", "false");
        properties.setProperty("patch.systemNebulaCache", "false");
        properties.setProperty("patch.sampleCacheClearThrottle", "false");
        properties.setProperty("patch.routeJumpPointIndex", "false");
        properties.setProperty("patch.strategicJumpDestinationFirst", "true");
        properties.setProperty("patch.strategicJumpDestinationIndex", "true");
        properties.setProperty("patch.economyLocationCache", "false");
        properties.setProperty("patch.marketScheduler", "true");
        properties.setProperty("patch.directMarketObservation", "false");
        properties.setProperty("patch.commRelaySystemIndex", "false");
        properties.setProperty("patch.commodityTemporalFastPath", "false");
        return properties;
    }

    private static Map<String, byte[]> readOriginals(Path corePath, Path apiPath)
            throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (JarFile core = new JarFile(corePath.toFile());
             JarFile api = new JarFile(apiPath.toFile())) {
            for (Map.Entry<String, Expected> entry : EXPECTED.entrySet()) {
                JarFile jar = entry.getValue().jar().equals("starfarer_obf.jar")
                        ? core : api;
                var jarEntry = jar.getJarEntry(entry.getKey() + ".class");
                require(jarEntry != null,
                        "target class missing from " + entry.getValue().jar()
                                + ": " + entry.getKey());
                byte[] bytes;
                try (var input = jar.getInputStream(jarEntry)) {
                    bytes = input.readAllBytes();
                }
                require(result.put(entry.getKey(), bytes) == null,
                        "duplicate target entry " + entry.getKey());
            }
        }
        require(result.size() == 24, "expected exactly 24 target classes");
        return result;
    }

    private static int countPresentationCalls(byte[] bytes) {
        ClassNode node = read(bytes);
        int count = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call
                        && call.owner.equals(PRESENTATION_HOOKS)) count++;
            }
        }
        return count;
    }

    private static Map<String, Integer> prepatcherHookCalls(byte[] bytes) {
        Map<String, Integer> calls = new TreeMap<>();
        ClassNode node = read(bytes);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call
                        && call.owner.startsWith(PREPATCHER_HOOK_PREFIX)
                        && !call.owner.equals(PRESENTATION_HOOKS)) {
                    String key = call.owner + "." + call.name + call.desc;
                    calls.merge(key, 1, Integer::sum);
                }
            }
        }
        return calls;
    }

    private static Set<String> patchMarkers(byte[] bytes) {
        Set<String> markers = new LinkedHashSet<>();
        for (FieldNode field : read(bytes).fields) {
            if (field.name.startsWith(PATCH_MARKER_PREFIX)
                    && !field.name.equals(FastForwardPresentationTransformer.OWNER_FIELD)) {
                markers.add(field.name);
            }
        }
        return markers;
    }

    private static String statusKey(String name) {
        return "starsector.prepatcher.patchStatus."
                + name.replace('/', '.') + ".fastForwardPresentation";
    }

    private static Object fieldValue(byte[] bytes, String fieldName) {
        for (FieldNode field : read(bytes).fields) {
            if (field.name.equals(fieldName)) return field.value;
        }
        throw new AssertionError("field not found: " + fieldName);
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        throw new AssertionError("method not found: " + node.name + "." + name + desc);
    }

    private static byte[] removeField(byte[] bytes, String fieldName) {
        ClassNode node = read(bytes);
        require(node.fields.removeIf(field -> field.name.equals(fieldName)),
                "field not found for removal: " + fieldName);
        return write(node);
    }

    private static byte[] renameFirstPresentationHook(byte[] bytes) {
        ClassNode node = read(bytes);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call
                        && call.owner.equals(PRESENTATION_HOOKS)) {
                    call.name = call.name + "$tampered";
                    return write(node);
                }
            }
        }
        throw new AssertionError("presentation hook not found for tamper");
    }

    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void verifyMethods(byte[] bytes) throws Exception {
        ClassNode node = read(bytes);
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            try {
                new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
            } catch (AnalyzerException failure) {
                throw new AssertionError("BasicVerifier rejected " + node.name + "."
                        + method.name + method.desc + ": " + failure.getMessage(), failure);
            }
        }
    }

    private static int classVersion(byte[] bytes) {
        return new ClassReader(bytes).readShort(6);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM8);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static PrepatcherConfig config(Properties properties) throws Exception {
        Constructor<PrepatcherConfig> constructor =
                PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);
        return constructor.newInstance(properties);
    }

    private static Properties profileProperties(boolean aggressive) {
        Properties properties = new Properties();
        properties.setProperty("patch.fastForwardPresentation", "true");
        properties.setProperty("patch.fastForwardFrameMarker", "true");
        properties.setProperty("fastForward.verbose", "false");
        properties.setProperty("fastForward.metrics", "false");
        properties.setProperty("fastForward.visualTime", "realtime");
        setPresentationGroups(properties, true);
        properties.setProperty("patch.fastForwardGlobalAnimations",
                Boolean.toString(aggressive));
        properties.setProperty("patch.fastForwardSensorFaders",
                Boolean.toString(aggressive));
        properties.setProperty("patch.fastForwardSlipstreamParticles",
                Boolean.toString(aggressive));
        properties.setProperty("patch.fastForwardParticleEmitters",
                Boolean.toString(aggressive));
        return properties;
    }

    private static void setPresentationGroups(Properties properties, boolean enabled) {
        String value = Boolean.toString(enabled);
        properties.setProperty("patch.fastForwardActionIndicators", value);
        properties.setProperty("patch.fastForwardLocationVisuals", value);
        properties.setProperty("patch.fastForwardFloatingText", value);
        properties.setProperty("patch.fastForwardFleetView", value);
        properties.setProperty("patch.fastForwardFleetPresentation", value);
        properties.setProperty("patch.fastForwardSensorIndicators", value);
        properties.setProperty("patch.fastForwardCelestialVisuals", value);
        properties.setProperty("patch.fastForwardAuroraAnimation", value);
        properties.setProperty("patch.fastForwardContinuousSound", value);
        properties.setProperty("patch.fastForwardGateJitter", value);
        properties.setProperty("patch.fastForwardGlobalAnimations", value);
        properties.setProperty("patch.fastForwardSensorFaders", value);
        properties.setProperty("patch.fastForwardSlipstreamParticles", value);
        properties.setProperty("patch.fastForwardParticleEmitters", value);
    }

    private static Map<String, Expected> expectedTargets() {
        Map<String, Expected> result = new LinkedHashMap<>();
        add(result, "com/fs/starfarer/campaign/CampaignState", "starfarer_obf.jar", 3, 3);
        add(result, "com/fs/starfarer/campaign/CampaignEngine", "starfarer_obf.jar", 1, 3);
        add(result, "com/fs/starfarer/campaign/BaseLocation", "starfarer_obf.jar", 3, 3);
        add(result, "com/fs/starfarer/campaign/BaseCampaignEntity", "starfarer_obf.jar", 3, 5);
        add(result, "com/fs/starfarer/campaign/fleet/CampaignFleet", "starfarer_obf.jar", 5, 5);
        add(result, "com/fs/starfarer/campaign/SensorContactIndicatorManager", "starfarer_obf.jar", 1, 1);
        add(result, "com/fs/starfarer/campaign/CampaignPlanet", "starfarer_obf.jar", 1, 1);
        add(result, "com/fs/starfarer/campaign/JumpPoint", "starfarer_obf.jar", 5, 5);

        add(result, "com/fs/starfarer/api/impl/campaign/terrain/StarCoronaTerrainPlugin", "starfarer.api.jar", 1, 1);
        add(result, "com/fs/starfarer/api/impl/campaign/terrain/MagneticFieldTerrainPlugin", "starfarer.api.jar", 1, 1);
        add(result, "com/fs/starfarer/api/impl/campaign/terrain/SpatialAnomalyTerrainPlugin", "starfarer.api.jar", 1, 1);
        add(result, "com/fs/starfarer/api/impl/campaign/terrain/HyperspaceTerrainPlugin", "starfarer.api.jar", 1, 1);
        add(result, "com/fs/starfarer/api/impl/campaign/terrain/RadioChatterTerrainPlugin", "starfarer.api.jar", 2, 2);
        add(result, "com/fs/starfarer/api/impl/campaign/velfield/SlipstreamTerrainPlugin2", "starfarer.api.jar", 4, 6);
        add(result, "com/fs/starfarer/api/impl/campaign/velfield/SlipstreamEntityPlugin2", "starfarer.api.jar", 0, 2);
        add(result, "com/fs/starfarer/api/impl/campaign/terrain/BaseTerrain", "starfarer.api.jar", 8, 8);
        add(result, "com/fs/starfarer/api/impl/campaign/abilities/BaseDurationAbility", "starfarer.api.jar", 4, 4);
        add(result, "com/fs/starfarer/api/impl/campaign/abilities/BaseToggleAbility", "starfarer.api.jar", 4, 4);
        add(result, "com/fs/starfarer/api/impl/campaign/abilities/GenerateSlipsurgeAbility$SlipsurgeEffectScript", "starfarer.api.jar", 2, 2);
        add(result, "com/fs/starfarer/api/impl/campaign/entities/GateHaulerEntityPlugin", "starfarer.api.jar", 1, 1);
        add(result, "com/fs/starfarer/api/impl/campaign/GateEntityPlugin", "starfarer.api.jar", 8, 9);
        add(result, "com/fs/starfarer/api/impl/campaign/world/MoteParticleScript", "starfarer.api.jar", 0, 1);
        add(result, "com/fs/starfarer/api/impl/campaign/CoronalTapParticleScript", "starfarer.api.jar", 0, 1);
        add(result, "com/fs/starfarer/api/impl/campaign/world/ZigLeashAssignmentAI", "starfarer.api.jar", 0, 1);
        return Map.copyOf(result);
    }

    private static void add(Map<String, Expected> targets, String name, String jar,
                            int safeCalls, int aggressiveCalls) {
        require(targets.put(name, new Expected(jar, safeCalls, aggressiveCalls)) == null,
                "duplicate expected target " + name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Expected(String jar, int safeCalls, int aggressiveCalls) {}
    private record ProfileResult(int classes, int callSites) {}
}
