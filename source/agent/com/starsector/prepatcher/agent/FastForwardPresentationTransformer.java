package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Structurally matched fast-forward presentation coalescing. Compatibility is decided from the
 * local methods and semantic call sites owned by each class-level plan; class and JAR hashes do not
 * participate in the runtime decision.
 */
public final class FastForwardPresentationTransformer implements ClassFileTransformer {
    private static final String RUNTIME =
            "com/fs/starfarer/api/StarsectorPrepatcherPresentationHooks";

    static final String OWNER_FIELD = "smo$patched$fastForwardPresentation";
    static final String MASK_FIELD = "smo$fastForwardPresentationMask";
    static final String OWNER_VALUE =
            "StarsectorPrepatcher:fast-forward-presentation-structural-v1";

    static final int FRAME_MARKER = 1;
    static final int ACTION_INDICATORS = 1 << 1;
    static final int LOCATION_VISUALS = 1 << 2;
    static final int FLOATING_TEXT = 1 << 3;
    static final int FLEET_VIEW = 1 << 4;
    static final int FLEET_PRESENTATION = 1 << 5;
    static final int SENSOR_INDICATORS = 1 << 6;
    static final int CELESTIAL_VISUALS = 1 << 7;
    static final int AURORA_ANIMATION = 1 << 8;
    static final int CONTINUOUS_SOUND = 1 << 9;
    static final int GATE_JITTER = 1 << 10;
    static final int GLOBAL_ANIMATIONS = 1 << 11;
    static final int SENSOR_FADERS = 1 << 12;
    static final int SLIPSTREAM_PARTICLES = 1 << 13;
    static final int PARTICLE_EMITTERS = 1 << 14;

    private enum Container {
        CORE("starfarer_obf.jar"),
        API("starfarer.api.jar");

        final String fileName;
        Container(String fileName) {
            this.fileName = fileName;
        }
    }

    private enum PlanKind { GENERIC, CAMPAIGN_STATE, CAMPAIGN_FLEET }
    private enum State { VANILLA, COMPLETE_PATCHED }

    private record CallSiteSpec(
            String methodName,
            String methodDesc,
            int originalOpcode,
            String originalOwner,
            String originalName,
            String originalDesc,
            String wrapperName,
            String wrapperDesc,
            int expectedCount) {}

    private record Component(int bit, List<CallSiteSpec> sites) {}

    private record PresentationClassPlan(
            Container container,
            PlanKind kind,
            List<Component> components) {}

    private record SiteRef(String methodName, String methodDesc, int instructionIndex) {}

    private record CampaignStateProtocol(
            SiteRef firstFalse,
            SiteRef engineAdvance,
            SiteRef loopTrue,
            SiteRef finalFalse,
            int substepLocal,
            int totalLocal) {}

    private record FleetPulseSite(SiteRef call) {}

    private static final class PresentationInspection {
        final State state;
        final int requestedMask;
        final Map<CallSiteSpec, List<SiteRef>> sites;
        final CampaignStateProtocol campaignState;
        final FleetPulseSite fleetPulse;

        PresentationInspection(State state, int requestedMask,
                               Map<CallSiteSpec, List<SiteRef>> sites,
                               CampaignStateProtocol campaignState,
                               FleetPulseSite fleetPulse) {
            this.state = state;
            this.requestedMask = requestedMask;
            this.sites = sites;
            this.campaignState = campaignState;
            this.fleetPulse = fleetPulse;
        }
    }

    private static final class StructuralMismatch extends RuntimeException {
        StructuralMismatch(String message) {
            super(message);
        }
    }

    private static final Map<String, PresentationClassPlan> PLANS;
    static final Set<String> TARGET_CLASSES;
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    static {
        Map<String, PresentationClassPlan> plans = new LinkedHashMap<>();
        plans.put("com/fs/starfarer/campaign/CampaignState",
                new PresentationClassPlan(Container.CORE, PlanKind.CAMPAIGN_STATE,
                        List.of(new Component(FRAME_MARKER, List.of()))));
        plans.put("com/fs/starfarer/campaign/CampaignEngine", generic(Container.CORE,
                component(ACTION_INDICATORS,
                        site("advance", "(FLcom/fs/starfarer/util/A/new;)V", Opcodes.INVOKEVIRTUAL,
                                "com/fs/starfarer/campaign/ActionIndicator", "advance", "(F)V",
                                "advanceActionIndicator",
                                "(Lcom/fs/starfarer/campaign/ActionIndicator;F)V", 1)),
                component(GLOBAL_ANIMATIONS,
                        site("advance", "(FLcom/fs/starfarer/util/A/new;)V", Opcodes.INVOKEVIRTUAL,
                                "com/fs/graphics/anim/AnimationManager", "advanceAll", "(F)V",
                                "advanceAnimationManager",
                                "(Lcom/fs/graphics/anim/AnimationManager;F)V", 2))));
        plans.put("com/fs/starfarer/campaign/BaseLocation", generic(Container.CORE,
                component(LOCATION_VISUALS,
                        site("advance", "(FLcom/fs/starfarer/util/A/new;)V", Opcodes.INVOKEVIRTUAL,
                                "com/fs/graphics/util/Fader", "advance", "(F)V",
                                "advanceLocationLightFader",
                                "(Lcom/fs/graphics/util/Fader;F)V", 1),
                        site("advance", "(FLcom/fs/starfarer/util/A/new;)V", Opcodes.INVOKEVIRTUAL,
                                "com/fs/starfarer/campaign/BackgroundAndStars", "advance", "(F)V",
                                "advanceBackground",
                                "(Lcom/fs/starfarer/campaign/BackgroundAndStars;F)V", 1),
                        site("advance", "(FLcom/fs/starfarer/util/A/new;)V", Opcodes.INVOKEVIRTUAL,
                                "com/fs/graphics/particle/DynamicParticleGroup", "advance", "(F)V",
                                "advanceParticleGroup",
                                "(Lcom/fs/graphics/particle/DynamicParticleGroup;F)V", 1))));
        plans.put("com/fs/starfarer/campaign/BaseCampaignEntity", generic(Container.CORE,
                component(FLOATING_TEXT,
                        site("advance", "(F)V", Opcodes.INVOKEVIRTUAL,
                                "com/fs/starfarer/campaign/fleet/CampaignFloatingText", "advance", "(F)V",
                                "advanceFloatingText",
                                "(Lcom/fs/starfarer/campaign/fleet/CampaignFloatingText;F)V", 1),
                        site("advanceEvenIfPaused", "(F)V", Opcodes.INVOKEVIRTUAL,
                                "com/fs/starfarer/campaign/fleet/CampaignFloatingText", "advance", "(F)V",
                                "advanceFloatingText",
                                "(Lcom/fs/starfarer/campaign/fleet/CampaignFloatingText;F)V", 1)),
                component(SENSOR_INDICATORS,
                        site("advance", "(F)V", Opcodes.INVOKEVIRTUAL,
                                "com/fs/starfarer/campaign/ui/oOO0", "new", "(F)V",
                                "advanceSelectionIndicator", "(Ljava/lang/Object;F)V", 1)),
                component(SENSOR_FADERS,
                        site("advance", "(F)V", Opcodes.INVOKEVIRTUAL,
                                "com/fs/graphics/util/Fader", "advance", "(F)V",
                                "advanceSensorFader", "(Lcom/fs/graphics/util/Fader;F)V", 2))));
        plans.put("com/fs/starfarer/campaign/fleet/CampaignFleet",
                new PresentationClassPlan(Container.CORE, PlanKind.CAMPAIGN_FLEET, List.of(
                        component(FLEET_VIEW,
                                site("advance", "(F)V", Opcodes.INVOKEVIRTUAL,
                                        "com/fs/starfarer/campaign/fleet/CampaignFleetView", "advance", "(F)V",
                                        "advanceFleetView",
                                        "(Lcom/fs/starfarer/campaign/fleet/CampaignFleetView;F)V", 1)),
                        component(FLEET_PRESENTATION,
                                site("advance", "(F)V", Opcodes.INVOKEVIRTUAL,
                                        "com/fs/starfarer/campaign/fleet/FleetAbilityRenderer", "updateLayers", "()V",
                                        "updateFleetAbilityLayers",
                                        "(Lcom/fs/starfarer/campaign/fleet/FleetAbilityRenderer;)V", 1),
                                site("advance", "(F)V", Opcodes.INVOKEVIRTUAL,
                                        "com/fs/starfarer/campaign/fleet/CampaignFleetView", "clear", "()V",
                                        "clearFleetView",
                                        "(Lcom/fs/starfarer/campaign/fleet/CampaignFleetView;)V", 1),
                                site("advance", "(F)V", Opcodes.INVOKEVIRTUAL,
                                        "com/fs/starfarer/campaign/E", "Ò00000", "(F)V",
                                        "advanceSensorRangeIndicator",
                                        "(Lcom/fs/starfarer/campaign/E;F)V", 1)))));
        plans.put("com/fs/starfarer/campaign/SensorContactIndicatorManager", generic(Container.CORE,
                component(SENSOR_INDICATORS,
                        site("advance", "(F)V", Opcodes.INVOKEVIRTUAL,
                                "com/fs/starfarer/campaign/SensorContactIndicatorManager$SensorContactIndicator",
                                "advance", "(F)V", "advanceSensorContactIndicator",
                                "(Lcom/fs/starfarer/campaign/SensorContactIndicatorManager$SensorContactIndicator;F)V", 1))));
        plans.put("com/fs/starfarer/campaign/CampaignPlanet", generic(Container.CORE,
                component(CELESTIAL_VISUALS,
                        site("advance", "(F)V", Opcodes.INVOKEVIRTUAL,
                                "com/fs/starfarer/combat/entities/terrain/Planet", "advance", "(F)V",
                                "advancePlanetGraphics",
                                "(Lcom/fs/starfarer/combat/entities/terrain/Planet;F)V", 1))));
        plans.put("com/fs/starfarer/campaign/JumpPoint", generic(Container.CORE,
                component(CELESTIAL_VISUALS,
                        site("advance", "(F)V", Opcodes.INVOKEVIRTUAL,
                                "com/fs/starfarer/campaign/DynamicRingBand", "advance", "(F)V",
                                "advanceDynamicRingBand",
                                "(Lcom/fs/starfarer/campaign/DynamicRingBand;F)V", 1),
                        site("advance", "(F)V", Opcodes.INVOKEVIRTUAL,
                                "com/fs/starfarer/campaign/JumpPoint$RingData", "advance", "(F)V",
                                "advanceJumpRingData",
                                "(Lcom/fs/starfarer/campaign/JumpPoint$RingData;F)V", 2),
                        site("advance", "(F)V", Opcodes.INVOKEVIRTUAL,
                                "com/fs/starfarer/campaign/JumpPoint$CoronaData", "advance", "(F)V",
                                "advanceJumpCoronaData",
                                "(Lcom/fs/starfarer/campaign/JumpPoint$CoronaData;F)V", 1),
                        site("advance", "(F)V", Opcodes.INVOKEVIRTUAL,
                                "com/fs/starfarer/combat/entities/terrain/Planet", "advance", "(F)V",
                                "advancePlanetGraphics",
                                "(Lcom/fs/starfarer/combat/entities/terrain/Planet;F)V", 1))));

        for (String name : List.of(
                "com/fs/starfarer/api/impl/campaign/terrain/StarCoronaTerrainPlugin",
                "com/fs/starfarer/api/impl/campaign/terrain/MagneticFieldTerrainPlugin",
                "com/fs/starfarer/api/impl/campaign/terrain/SpatialAnomalyTerrainPlugin")) {
            plans.put(name, generic(Container.API,
                    component(AURORA_ANIMATION,
                            site("advance", "(F)V", Opcodes.INVOKEVIRTUAL,
                                    "com/fs/starfarer/api/impl/campaign/terrain/AuroraRenderer", "advance", "(F)V",
                                    "advanceAurora",
                                    "(Lcom/fs/starfarer/api/impl/campaign/terrain/AuroraRenderer;F)V", 1))));
        }
        plans.put("com/fs/starfarer/api/impl/campaign/terrain/HyperspaceTerrainPlugin",
                generic(Container.API, component(CONTINUOUS_SOUND,
                        lowPass("advance", "(F)V", 1))));
        plans.put("com/fs/starfarer/api/impl/campaign/terrain/RadioChatterTerrainPlugin",
                generic(Container.API, component(CONTINUOUS_SOUND,
                        suppressMusic("applyEffect", "(Lcom/fs/starfarer/api/campaign/SectorEntityToken;F)V", 1),
                        playLoop("applyEffect", "(Lcom/fs/starfarer/api/campaign/SectorEntityToken;F)V", 1))));
        plans.put("com/fs/starfarer/api/impl/campaign/velfield/SlipstreamTerrainPlugin2",
                generic(Container.API,
                        component(SLIPSTREAM_PARTICLES,
                                site("advance", "(F)V", Opcodes.INVOKEVIRTUAL,
                                        "com/fs/starfarer/api/impl/campaign/velfield/SlipstreamTerrainPlugin2",
                                        "addParticles", "()V", "addSlipstreamTerrainParticles",
                                        "(Lcom/fs/starfarer/api/impl/campaign/velfield/SlipstreamTerrainPlugin2;)V", 1),
                                site("advance", "(F)V", Opcodes.INVOKEVIRTUAL,
                                        "com/fs/starfarer/api/impl/campaign/velfield/SlipstreamTerrainPlugin2",
                                        "advanceParticles", "(F)V", "advanceSlipstreamTerrainParticles",
                                        "(Lcom/fs/starfarer/api/impl/campaign/velfield/SlipstreamTerrainPlugin2;F)V", 1)),
                        component(CONTINUOUS_SOUND,
                                lowPass("doSoundPlayback", "(F)V", 1),
                                site("doSoundPlayback", "(F)V", Opcodes.INVOKEINTERFACE,
                                        "com/fs/starfarer/api/SoundPlayerAPI", "playLoop",
                                        "(Ljava/lang/String;Ljava/lang/Object;FFLorg/lwjgl/util/vector/Vector2f;Lorg/lwjgl/util/vector/Vector2f;FF)V",
                                        "playLoopWithRange",
                                        "(Lcom/fs/starfarer/api/SoundPlayerAPI;Ljava/lang/String;Ljava/lang/Object;FFLorg/lwjgl/util/vector/Vector2f;Lorg/lwjgl/util/vector/Vector2f;FF)V", 2),
                                suppressMusic("doSoundPlayback", "(F)V", 1))));
        plans.put("com/fs/starfarer/api/impl/campaign/velfield/SlipstreamEntityPlugin2",
                generic(Container.API, component(SLIPSTREAM_PARTICLES,
                        site("advance", "(F)V", Opcodes.INVOKEVIRTUAL,
                                "com/fs/starfarer/api/impl/campaign/velfield/SlipstreamEntityPlugin2",
                                "addParticles", "()V", "addSlipstreamEntityParticles",
                                "(Lcom/fs/starfarer/api/impl/campaign/velfield/SlipstreamEntityPlugin2;)V", 1),
                        site("advance", "(F)V", Opcodes.INVOKEVIRTUAL,
                                "com/fs/starfarer/api/impl/campaign/velfield/SlipstreamEntityPlugin2",
                                "advanceParticles", "(F)V", "advanceSlipstreamEntityParticles",
                                "(Lcom/fs/starfarer/api/impl/campaign/velfield/SlipstreamEntityPlugin2;F)V", 1))));
        plans.put("com/fs/starfarer/api/impl/campaign/terrain/BaseTerrain",
                generic(Container.API, component(CONTINUOUS_SOUND,
                        suppressMusic("advance", "(F)V", 4), playLoop("advance", "(F)V", 4))));
        for (String name : List.of(
                "com/fs/starfarer/api/impl/campaign/abilities/BaseDurationAbility",
                "com/fs/starfarer/api/impl/campaign/abilities/BaseToggleAbility")) {
            plans.put(name, generic(Container.API, component(CONTINUOUS_SOUND,
                    suppressMusic("advance", "(F)V", 2), playLoop("advance", "(F)V", 2))));
        }
        plans.put("com/fs/starfarer/api/impl/campaign/abilities/GenerateSlipsurgeAbility$SlipsurgeEffectScript",
                generic(Container.API, component(CONTINUOUS_SOUND,
                        suppressMusic("advance", "(F)V", 1), playLoop("advance", "(F)V", 1))));
        plans.put("com/fs/starfarer/api/impl/campaign/entities/GateHaulerEntityPlugin",
                generic(Container.API, component(CONTINUOUS_SOUND,
                        playLoop("advance", "(F)V", 1))));
        plans.put("com/fs/starfarer/api/impl/campaign/GateEntityPlugin", generic(Container.API,
                component(CONTINUOUS_SOUND,
                        suppressMusic("playProximityLoop", "()V", 1),
                        playLoop("playProximityLoop", "()V", 2)),
                component(GATE_JITTER,
                        site("advance", "(F)V", Opcodes.INVOKEVIRTUAL,
                                "com/fs/starfarer/api/util/FaderUtil", "advance", "(F)V",
                                "advanceGateFader",
                                "(Lcom/fs/starfarer/api/util/FaderUtil;F)V", 3),
                        site("advance", "(F)V", Opcodes.INVOKEVIRTUAL,
                                "com/fs/starfarer/api/util/WarpingSpriteRendererUtil", "advance", "(F)V",
                                "advanceGateWarp",
                                "(Lcom/fs/starfarer/api/util/WarpingSpriteRendererUtil;F)V", 1),
                        site("advance", "(F)V", Opcodes.INVOKEVIRTUAL,
                                "com/fs/starfarer/api/util/JitterUtil", "updateSeed", "()V",
                                "updateGateJitter",
                                "(Lcom/fs/starfarer/api/util/JitterUtil;)V", 1)),
                component(PARTICLE_EMITTERS, emitter("advance", "(F)V", 1))));
        for (String name : List.of(
                "com/fs/starfarer/api/impl/campaign/world/MoteParticleScript",
                "com/fs/starfarer/api/impl/campaign/CoronalTapParticleScript",
                "com/fs/starfarer/api/impl/campaign/world/ZigLeashAssignmentAI")) {
            plans.put(name, generic(Container.API,
                    component(PARTICLE_EMITTERS, emitter("advance", "(F)V", 1))));
        }
        PLANS = Collections.unmodifiableMap(plans);
        TARGET_CLASSES = Collections.unmodifiableSet(new LinkedHashSet<>(plans.keySet()));
    }

    private final PrepatcherConfig config;
    private final ClassLoader runtimeLoader;

    public FastForwardPresentationTransformer(PrepatcherConfig config) {
        this(config, null);
    }

    public FastForwardPresentationTransformer(PrepatcherConfig config,
                                              ClassLoader runtimeLoader) {
        this.config = config;
        this.runtimeLoader = runtimeLoader;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || !PLANS.containsKey(className) || !isTargetEnabled(className)) {
            return null;
        }
        if (runtimeLoader != null && loader != runtimeLoader) {
            recordStatus(className, "SKIPPED_LOADER");
            logOnce(className + ":loader", "Fast-forward presentation patch skipped "
                    + className + ": target loader=" + loaderName(loader)
                    + ", runtime loader=" + loaderName(runtimeLoader));
            return null;
        }
        try {
            byte[] transformed = transformStructural(className, classfileBuffer);
            if (transformed == null) {
                recordStatus(className, "ALREADY_APPLIED");
                return null;
            }
            recordStatus(className, "APPLIED");
            if (config.fastForwardVerbose) {
                PrepatcherLog.info("APPLIED structural fast-forward presentation plan to "
                        + className + ", bytes " + classfileBuffer.length
                        + " -> " + transformed.length);
            }
            return transformed;
        } catch (StructuralMismatch mismatch) {
            recordStatus(className, "SKIPPED_STRUCTURAL");
            logOnce(className + ":structural:" + mismatch.getMessage(),
                    "Fast-forward presentation patch skipped " + className + ": "
                            + mismatch.getMessage());
            return null;
        } catch (Throwable failure) {
            recordStatus(className, "SKIPPED_ERROR");
            PrepatcherLog.error("Fast-forward presentation patch failed for "
                    + className + "; using input bytes", failure);
            return null;
        }
    }

    /** Test entry point. Compatibility is structural; no class or container hash is consulted. */
    public byte[] transformBytesForTest(String className, byte[] classfileBuffer) {
        if (!PLANS.containsKey(className)) {
            throw new IllegalArgumentException("not a target: " + className);
        }
        if (!isTargetEnabled(className)) return null;
        return transformStructural(className, classfileBuffer);
    }

    public Set<String> targetClassesForTest() {
        return TARGET_CLASSES;
    }

    public String targetJarForTest(String className) {
        PresentationClassPlan plan = PLANS.get(className);
        if (plan == null) throw new IllegalArgumentException("not a target: " + className);
        return plan.container.fileName;
    }

    record TestCallSite(String methodName, String methodDesc, int originalOpcode,
                        String originalOwner, String originalName, String originalDesc,
                        int expectedCount) {}

    List<TestCallSite> callSitesForTest(String className) {
        PresentationClassPlan plan = PLANS.get(className);
        if (plan == null) throw new IllegalArgumentException("not a target: " + className);
        List<TestCallSite> result = new ArrayList<>();
        for (Component component : plan.components) {
            for (CallSiteSpec site : component.sites) {
                result.add(new TestCallSite(site.methodName, site.methodDesc,
                        site.originalOpcode, site.originalOwner, site.originalName,
                        site.originalDesc, site.expectedCount));
            }
        }
        return Collections.unmodifiableList(result);
    }

    boolean specialPlanForTest(String className) {
        PresentationClassPlan plan = PLANS.get(className);
        if (plan == null) throw new IllegalArgumentException("not a target: " + className);
        return plan.kind != PlanKind.GENERIC;
    }

    int expectedHookCountForTest(String className, PrepatcherConfig testConfig) {
        PresentationClassPlan plan = PLANS.get(className);
        if (plan == null) throw new IllegalArgumentException("not a target: " + className);
        int mask = requestedMaskForClass(className, testConfig);
        if (mask == 0) return 0;
        int count = 0;
        for (int value : expectedHooks(plan, mask).values()) count += value;
        return count;
    }

    static int requestedMaskForClass(String className, PrepatcherConfig config) {
        if (!config.fastForwardEnabled || !config.fastForwardFrameMarker) return 0;
        return switch (className) {
            case "com/fs/starfarer/campaign/CampaignState" ->
                    anyPresentationPatch(config) ? FRAME_MARKER : 0;
            case "com/fs/starfarer/campaign/CampaignEngine" ->
                    (config.fastForwardActionIndicators ? ACTION_INDICATORS : 0)
                            | (config.fastForwardGlobalAnimations ? GLOBAL_ANIMATIONS : 0);
            case "com/fs/starfarer/campaign/BaseLocation" ->
                    config.fastForwardLocationVisuals ? LOCATION_VISUALS : 0;
            case "com/fs/starfarer/campaign/BaseCampaignEntity" ->
                    (config.fastForwardFloatingText ? FLOATING_TEXT : 0)
                            | (config.fastForwardSensorIndicators ? SENSOR_INDICATORS : 0)
                            | (config.fastForwardSensorFaders ? SENSOR_FADERS : 0);
            case "com/fs/starfarer/campaign/fleet/CampaignFleet" ->
                    (config.fastForwardFleetView ? FLEET_VIEW : 0)
                            | (config.fastForwardFleetPresentation ? FLEET_PRESENTATION : 0);
            case "com/fs/starfarer/campaign/SensorContactIndicatorManager" ->
                    config.fastForwardSensorIndicators ? SENSOR_INDICATORS : 0;
            case "com/fs/starfarer/campaign/CampaignPlanet",
                 "com/fs/starfarer/campaign/JumpPoint" ->
                    config.fastForwardCelestialVisuals ? CELESTIAL_VISUALS : 0;
            case "com/fs/starfarer/api/impl/campaign/terrain/StarCoronaTerrainPlugin",
                 "com/fs/starfarer/api/impl/campaign/terrain/MagneticFieldTerrainPlugin",
                 "com/fs/starfarer/api/impl/campaign/terrain/SpatialAnomalyTerrainPlugin" ->
                    config.fastForwardAuroraAnimation ? AURORA_ANIMATION : 0;
            case "com/fs/starfarer/api/impl/campaign/terrain/HyperspaceTerrainPlugin",
                 "com/fs/starfarer/api/impl/campaign/terrain/RadioChatterTerrainPlugin",
                 "com/fs/starfarer/api/impl/campaign/terrain/BaseTerrain",
                 "com/fs/starfarer/api/impl/campaign/abilities/BaseDurationAbility",
                 "com/fs/starfarer/api/impl/campaign/abilities/BaseToggleAbility",
                 "com/fs/starfarer/api/impl/campaign/abilities/GenerateSlipsurgeAbility$SlipsurgeEffectScript",
                 "com/fs/starfarer/api/impl/campaign/entities/GateHaulerEntityPlugin" ->
                    config.fastForwardContinuousSound ? CONTINUOUS_SOUND : 0;
            case "com/fs/starfarer/api/impl/campaign/velfield/SlipstreamTerrainPlugin2" ->
                    (config.fastForwardContinuousSound ? CONTINUOUS_SOUND : 0)
                            | (config.fastForwardSlipstreamParticles ? SLIPSTREAM_PARTICLES : 0);
            case "com/fs/starfarer/api/impl/campaign/velfield/SlipstreamEntityPlugin2" ->
                    config.fastForwardSlipstreamParticles ? SLIPSTREAM_PARTICLES : 0;
            case "com/fs/starfarer/api/impl/campaign/GateEntityPlugin" ->
                    (config.fastForwardContinuousSound ? CONTINUOUS_SOUND : 0)
                            | (config.fastForwardGateJitter ? GATE_JITTER : 0)
                            | (config.fastForwardParticleEmitters ? PARTICLE_EMITTERS : 0);
            case "com/fs/starfarer/api/impl/campaign/world/MoteParticleScript",
                 "com/fs/starfarer/api/impl/campaign/CoronalTapParticleScript",
                 "com/fs/starfarer/api/impl/campaign/world/ZigLeashAssignmentAI" ->
                    config.fastForwardParticleEmitters ? PARTICLE_EMITTERS : 0;
            default -> 0;
        };
    }

    boolean isTargetEnabled(String className) {
        return requestedMaskForClass(className, config) != 0;
    }

    private byte[] transformStructural(String className, byte[] original) {
        PresentationClassPlan plan = PLANS.get(className);
        int requestedMask = requestedMaskForClass(className, config);
        if (plan == null || requestedMask == 0) return null;

        ClassNode input = read(original);
        PresentationInspection inspection = inspect(plan, input, requestedMask);
        if (inspection.state == State.COMPLETE_PATCHED) return null;

        ClassNode candidate = cloneNode(input);
        apply(plan, candidate, inspection);
        addOwnership(candidate, requestedMask);
        inspect(plan, candidate, requestedMask); // combined postcondition

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        candidate.accept(writer);
        byte[] transformed = writer.toByteArray();
        inspect(plan, read(transformed), requestedMask);
        return transformed;
    }

    private static PresentationInspection inspect(PresentationClassPlan plan, ClassNode node,
                                                  int requestedMask) {
        Ownership ownership = ownership(node);
        Map<CallSiteSpec, List<SiteRef>> sites = new LinkedHashMap<>();
        for (Component component : plan.components) {
            boolean requested = (requestedMask & component.bit) != 0;
            for (CallSiteSpec spec : component.sites) {
                SiteClassification classification = classifyCallSite(node, spec);
                if (ownership.present) {
                    if (requested) classification.requirePatched(node.name, spec);
                    else classification.requireOriginal(node.name, spec);
                } else {
                    classification.requireOriginal(node.name, spec);
                }
                if (requested && !ownership.present) sites.put(spec, classification.originalSites);
            }
        }

        CampaignStateProtocol campaignState = null;
        FleetPulseSite fleetPulse = null;
        if (plan.kind == PlanKind.CAMPAIGN_STATE) {
            campaignState = inspectCampaignState(node, ownership.present);
        } else if (plan.kind == PlanKind.CAMPAIGN_FLEET) {
            boolean requested = (requestedMask & FLEET_PRESENTATION) != 0;
            fleetPulse = inspectFleetPulse(node, ownership.present && requested,
                    ownership.present && !requested);
        }

        Map<HookKey, Integer> actualHooks = presentationHooks(node);
        Map<HookKey, Integer> expectedHooks = expectedHooks(plan, requestedMask);
        if (!ownership.present) {
            if (!actualHooks.isEmpty()) {
                throw mismatch(node.name + ": presentation hooks exist without ownership: "
                        + actualHooks);
            }
            return new PresentationInspection(State.VANILLA, requestedMask, sites,
                    campaignState, fleetPulse);
        }

        if (!OWNER_VALUE.equals(ownership.ownerValue)) {
            throw mismatch(node.name + ": presentation owner mismatch: "
                    + ownership.ownerValue);
        }
        if (ownership.mask != requestedMask) {
            throw mismatch(node.name + ": presentation mask mismatch: expected="
                    + requestedMask + ", actual=" + ownership.mask);
        }
        if (!actualHooks.equals(expectedHooks)) {
            throw mismatch(node.name + ": presentation hook inventory mismatch: expected="
                    + expectedHooks + ", actual=" + actualHooks);
        }
        return new PresentationInspection(State.COMPLETE_PATCHED, requestedMask, Map.of(),
                campaignState, fleetPulse);
    }

    private static void apply(PresentationClassPlan plan, ClassNode candidate,
                              PresentationInspection inspection) {
        if (plan.kind == PlanKind.CAMPAIGN_STATE) {
            applyCampaignState(candidate, inspection.campaignState);
        }
        for (Map.Entry<CallSiteSpec, List<SiteRef>> entry : inspection.sites.entrySet()) {
            CallSiteSpec spec = entry.getKey();
            for (SiteRef ref : entry.getValue()) {
                MethodInsnNode call = methodCallAt(candidate, ref);
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.owner = RUNTIME;
                call.name = spec.wrapperName;
                call.desc = spec.wrapperDesc;
                call.itf = false;
            }
        }
        if (plan.kind == PlanKind.CAMPAIGN_FLEET
                && (inspection.requestedMask & FLEET_PRESENTATION) != 0) {
            MethodInsnNode call = methodCallAt(candidate, inspection.fleetPulse.call);
            call.setOpcode(Opcodes.INVOKESTATIC);
            call.owner = RUNTIME;
            call.name = "advanceFleetPulseFader";
            call.desc = "(Lcom/fs/graphics/util/Fader;F)V";
            call.itf = false;
        }
    }

    private static void addOwnership(ClassNode node, int mask) {
        if (countFields(node, OWNER_FIELD) != 0 || countFields(node, MASK_FIELD) != 0) {
            throw mismatch(node.name + ": presentation ownership fields already exist");
        }
        int access = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
                | Opcodes.ACC_SYNTHETIC;
        node.fields.add(new FieldNode(Opcodes.ASM8, access, OWNER_FIELD,
                "Ljava/lang/String;", null, OWNER_VALUE));
        node.fields.add(new FieldNode(Opcodes.ASM8, access, MASK_FIELD,
                "I", null, mask));
    }

    private static CampaignStateProtocol inspectCampaignState(ClassNode node, boolean patched) {
        MethodNode method = requireMethod(node, "advance", "(FLcom/fs/starfarer/util/A/new;)V");
        List<MethodInsnNode> setters = calls(method, Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/campaign/CampaignEngine", "setFastForwardIteration", "(Z)V");
        List<MethodInsnNode> advances = calls(method, Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/campaign/CampaignEngine", "advance",
                "(FLcom/fs/starfarer/util/A/new;)V");
        if (setters.size() != 3 || advances.size() != 1) {
            throw mismatch(node.name + ".advance: expected setFastForwardIteration x3 and "
                    + "CampaignEngine.advance x1, found " + setters.size() + "/" + advances.size());
        }
        MethodInsnNode first = setters.get(0);
        MethodInsnNode middle = setters.get(1);
        MethodInsnNode last = setters.get(2);
        MethodInsnNode advance = advances.get(0);
        Frame<SourceValue>[] frames = sourceFrames(node.name, method);
        requireBooleanArgument(method, first, frames, false, "first fast-forward setter");
        requireBooleanArgument(method, middle, frames, true, "loop fast-forward setter");
        requireBooleanArgument(method, last, frames, false, "final fast-forward setter");
        int firstIndex = method.instructions.indexOf(first);
        int advanceIndex = method.instructions.indexOf(advance);
        int middleIndex = method.instructions.indexOf(middle);
        int lastIndex = method.instructions.indexOf(last);
        if (!(firstIndex < advanceIndex && advanceIndex < middleIndex && middleIndex < lastIndex)) {
            throw mismatch(node.name + ".advance: fast-forward protocol order changed");
        }
        JumpInsnNode backedge = uniqueBackedge(method, middleIndex, lastIndex, advanceIndex);
        int targetIndex = method.instructions.indexOf(backedge.label);
        LoopLocals loop = inferLoopLocals(node.name, method, frames, targetIndex,
                method.instructions.indexOf(backedge));

        List<MethodInsnNode> beginHooks = calls(method, Opcodes.INVOKESTATIC,
                RUNTIME, "beginOuterFrame", "(I)V");
        List<MethodInsnNode> beforeHooks = calls(method, Opcodes.INVOKESTATIC,
                RUNTIME, "beforeSubstep", "(II)V");
        List<MethodInsnNode> endHooks = calls(method, Opcodes.INVOKESTATIC,
                RUNTIME, "endOuterFrame", "()V");
        if (!patched) {
            if (!beginHooks.isEmpty() || !beforeHooks.isEmpty() || !endHooks.isEmpty()) {
                throw mismatch(node.name + ".advance: partial frame-marker hooks without owner");
            }
        } else {
            if (beginHooks.size() != 1 || beforeHooks.size() != 1 || endHooks.size() != 1) {
                throw mismatch(node.name + ".advance: frame-marker hook counts are not 1/1/1");
            }
            MethodInsnNode begin = beginHooks.get(0);
            MethodInsnNode before = beforeHooks.get(0);
            MethodInsnNode end = endHooks.get(0);
            requireLoadedArgument(method, begin, frames, 0, loop.totalLocal,
                    "beginOuterFrame total count");
            requireLoadedArgument(method, before, frames, 0, loop.substepLocal,
                    "beforeSubstep index");
            requireLoadedArgument(method, before, frames, 1, loop.totalLocal,
                    "beforeSubstep total count");
            if (nextMeaningful(begin) != first || nextMeaningful(before) != advance
                    || previousMeaningful(end) != last) {
                throw mismatch(node.name + ".advance: frame-marker hooks moved outside protocol anchors");
            }
        }
        return new CampaignStateProtocol(ref(method, first), ref(method, advance),
                ref(method, middle), ref(method, last), loop.substepLocal, loop.totalLocal);
    }

    private record LoopLocals(int substepLocal, int totalLocal) {}

    private static LoopLocals inferLoopLocals(String owner, MethodNode method,
                                              Frame<SourceValue>[] frames,
                                              int loopTargetIndex,
                                              int backedgeIndex) {
        List<LoopLocals> proven = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.get(loopTargetIndex);
             insn != null && method.instructions.indexOf(insn) <= backedgeIndex;
             insn = insn.getNext()) {
            if (!(insn instanceof JumpInsnNode jump) || !isIntegerCompare(jump.getOpcode())) {
                continue;
            }
            int target = method.instructions.indexOf(jump.label);
            // Accept either a pre-tested loop exit or the real game's post-tested
            // backedge, but ignore comparisons whose two successors remain inside
            // the loop body. This keeps unrelated body predicates out of the proof.
            if (target > loopTargetIndex && target <= backedgeIndex) continue;
            Frame<SourceValue> frame = frames[method.instructions.indexOf(jump)];
            if (frame == null || frame.getStackSize() < 2) continue;
            int left = loadedLocal(frame.getStack(frame.getStackSize() - 2));
            int right = loadedLocal(frame.getStack(frame.getStackSize() - 1));
            if (left < 0 || right < 0 || left == right) continue;
            boolean leftMutated = incrementedInRange(method, left, loopTargetIndex, backedgeIndex);
            boolean rightMutated = incrementedInRange(method, right, loopTargetIndex, backedgeIndex);
            if (leftMutated == rightMutated) continue;
            LoopLocals candidate = leftMutated
                    ? new LoopLocals(left, right) : new LoopLocals(right, left);
            if (!proven.contains(candidate)) proven.add(candidate);
        }
        if (proven.size() != 1) {
            throw mismatch(owner + ".advance: loop substep/total dataflow is "
                    + (proven.isEmpty() ? "missing" : "ambiguous: " + proven));
        }
        return proven.get(0);
    }

    private static void applyCampaignState(ClassNode node, CampaignStateProtocol protocol) {
        MethodNode method = requireMethod(node, "advance", "(FLcom/fs/starfarer/util/A/new;)V");
        AbstractInsnNode first = instructionAt(method, protocol.firstFalse.instructionIndex);
        AbstractInsnNode advance = instructionAt(method, protocol.engineAdvance.instructionIndex);
        AbstractInsnNode last = instructionAt(method, protocol.finalFalse.instructionIndex);
        InsnList begin = new InsnList();
        begin.add(new VarInsnNode(Opcodes.ILOAD, protocol.totalLocal));
        begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "beginOuterFrame", "(I)V", false));
        method.instructions.insertBefore(first, begin);
        InsnList before = new InsnList();
        before.add(new VarInsnNode(Opcodes.ILOAD, protocol.substepLocal));
        before.add(new VarInsnNode(Opcodes.ILOAD, protocol.totalLocal));
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "beforeSubstep", "(II)V", false));
        method.instructions.insertBefore(advance, before);
        method.instructions.insert(last, new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "endOuterFrame", "()V", false));
    }

    private static FleetPulseSite inspectFleetPulse(ClassNode node, boolean patched,
                                                    boolean disabledInOwnedClass) {
        MethodNode method = requireMethod(node, "advance", "(F)V");
        List<MethodInsnNode> originals = calls(method, Opcodes.INVOKEVIRTUAL,
                "com/fs/graphics/util/Fader", "advance", "(F)V");
        List<MethodInsnNode> wrappers = calls(method, Opcodes.INVOKESTATIC,
                RUNTIME, "advanceFleetPulseFader", "(Lcom/fs/graphics/util/Fader;F)V");
        MethodInsnNode speedAnchor = uniqueCall(method, Opcodes.INVOKEVIRTUAL,
                node.name, "updateSpeedBonus", "()V",
                "fleet speed-update anchor");
        if (patched) {
            if (originals.size() != 1 || wrappers.size() != 1) {
                throw mismatch(node.name + ".advance: fleet pulse fader patched state must contain "
                        + "one vanilla Fader.advance and one wrapper");
            }
            MethodInsnNode wrapper = wrappers.get(0);
            requireSameStraightLineRegion(method, wrapper, speedAnchor,
                    "fleet pulse fader wrapper");
            return new FleetPulseSite(ref(method, wrapper));
        }
        if (disabledInOwnedClass) {
            if (originals.size() != 2 || !wrappers.isEmpty()) {
                throw mismatch(node.name + ".advance: disabled fleet-presentation component is not vanilla");
            }
            return null;
        }
        if (originals.size() != 2 || !wrappers.isEmpty()) {
            throw mismatch(node.name + ".advance: expected two vanilla fleet Fader.advance calls");
        }
        List<MethodInsnNode> semantic = new ArrayList<>();
        for (MethodInsnNode call : originals) {
            if (sameStraightLineRegion(method, call, speedAnchor)
                    && method.instructions.indexOf(call) < method.instructions.indexOf(speedAnchor)) {
                semantic.add(call);
            }
        }
        if (semantic.size() != 1) {
            throw mismatch(node.name + ".advance: fleet pulse fader receiver is ambiguous relative "
                    + "to the speed-update block: " + semantic.size());
        }
        requireReceiverIsFieldOrLocal(node.name, method, semantic.get(0));
        return new FleetPulseSite(ref(method, semantic.get(0)));
    }

    private record CallKey(int opcode, String owner, String name, String desc) {}
    private record HookKey(String methodName, String methodDesc,
                           String hookName, String hookDesc) {}
    private record Ownership(boolean present, String ownerValue, int mask) {}

    private static final class SiteClassification {
        final List<SiteRef> originalSites;
        final int wrapperCount;

        SiteClassification(List<SiteRef> originalSites, int wrapperCount) {
            this.originalSites = originalSites;
            this.wrapperCount = wrapperCount;
        }

        void requireOriginal(String className, CallSiteSpec spec) {
            if (originalSites.size() != spec.expectedCount || wrapperCount != 0) {
                throw mismatch(className + "." + spec.methodName + spec.methodDesc + ": expected "
                        + spec.expectedCount + " original " + spec.originalOwner + "."
                        + spec.originalName + spec.originalDesc + " and no " + spec.wrapperName
                        + " wrappers; found original=" + originalSites.size()
                        + ", wrapper=" + wrapperCount);
            }
        }

        void requirePatched(String className, CallSiteSpec spec) {
            if (!originalSites.isEmpty() || wrapperCount != spec.expectedCount) {
                throw mismatch(className + "." + spec.methodName + spec.methodDesc + ": expected "
                        + spec.expectedCount + " " + spec.wrapperName + " wrappers and no original "
                        + "calls; found original=" + originalSites.size()
                        + ", wrapper=" + wrapperCount);
            }
        }
    }

    private static SiteClassification classifyCallSite(ClassNode node, CallSiteSpec spec) {
        MethodNode method = requireMethod(node, spec.methodName, spec.methodDesc);
        Frame<SourceValue>[] frames = sourceFrames(node.name, method);
        List<SiteRef> originals = new ArrayList<>();
        int wrappers = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (call.getOpcode() == spec.originalOpcode
                    && call.owner.equals(spec.originalOwner)
                    && call.name.equals(spec.originalName)
                    && call.desc.equals(spec.originalDesc)) {
                validateOriginalCall(node.name, method, call, frames, spec);
                originals.add(ref(method, call));
            } else if (call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals(RUNTIME)
                    && call.name.equals(spec.wrapperName)
                    && call.desc.equals(spec.wrapperDesc)) {
                validateWrapperCall(node.name, method, call, frames, spec);
                wrappers++;
            }
        }
        return new SiteClassification(Collections.unmodifiableList(originals), wrappers);
    }

    private static void validateOriginalCall(String owner, MethodNode method,
                                             MethodInsnNode call,
                                             Frame<SourceValue>[] frames,
                                             CallSiteSpec spec) {
        Frame<SourceValue> frame = frames[method.instructions.indexOf(call)];
        if (frame == null) throw mismatch(owner + "." + method.name + method.desc
                + ": analysis frame missing for " + spec.originalName);
        int args = Type.getArgumentTypes(call.desc).length;
        if (frame.getStackSize() < args + 1) {
            throw mismatch(owner + "." + method.name + method.desc
                    + ": receiver/argument stack is incomplete for " + spec.originalName);
        }
        SourceValue receiver = frame.getStack(frame.getStackSize() - args - 1);
        if (receiver == null || receiver.insns == null || receiver.insns.isEmpty()) {
            throw mismatch(owner + "." + method.name + method.desc
                    + ": receiver source is unresolved for " + spec.originalName);
        }
        for (int i = 0; i < args; i++) {
            SourceValue argument = frame.getStack(frame.getStackSize() - args + i);
            if (argument == null || argument.insns == null || argument.insns.isEmpty()) {
                throw mismatch(owner + "." + method.name + method.desc
                        + ": argument source is unresolved for " + spec.originalName);
            }
        }
        if (spec.originalOwner.equals(owner)) {
            int local = loadedLocal(receiver, Opcodes.ALOAD);
            if (local != 0) {
                throw mismatch(owner + "." + method.name + method.desc
                        + ": self-call receiver is not ALOAD 0 for " + spec.originalName);
            }
        }
    }

    private static void validateWrapperCall(String owner, MethodNode method,
                                            MethodInsnNode call,
                                            Frame<SourceValue>[] frames,
                                            CallSiteSpec spec) {
        Frame<SourceValue> frame = frames[method.instructions.indexOf(call)];
        int args = Type.getArgumentTypes(call.desc).length;
        if (frame == null || frame.getStackSize() < args) {
            throw mismatch(owner + "." + method.name + method.desc
                    + ": wrapper argument stack is incomplete for " + spec.wrapperName);
        }
        for (int i = 0; i < args; i++) {
            SourceValue argument = frame.getStack(frame.getStackSize() - args + i);
            if (argument == null || argument.insns == null || argument.insns.isEmpty()) {
                throw mismatch(owner + "." + method.name + method.desc
                        + ": wrapper argument source is unresolved for " + spec.wrapperName);
            }
        }
    }

    private static Ownership ownership(ClassNode node) {
        List<FieldNode> owners = fields(node, OWNER_FIELD);
        List<FieldNode> masks = fields(node, MASK_FIELD);
        if (owners.isEmpty() && masks.isEmpty()) return new Ownership(false, null, 0);
        if (owners.size() != 1 || masks.size() != 1) {
            throw mismatch(node.name + ": presentation ownership is partial: ownerFields="
                    + owners.size() + ", maskFields=" + masks.size());
        }
        FieldNode owner = owners.get(0);
        FieldNode mask = masks.get(0);
        if (!(owner.value instanceof String ownerValue) || !(mask.value instanceof Integer value)) {
            throw mismatch(node.name + ": presentation ownership fields are not constants");
        }
        return new Ownership(true, ownerValue, value);
    }

    private static Map<HookKey, Integer> expectedHooks(PresentationClassPlan plan,
                                                       int requestedMask) {
        Map<HookKey, Integer> result = new LinkedHashMap<>();
        if (plan.kind == PlanKind.CAMPAIGN_STATE) {
            addHook(result, "advance", "(FLcom/fs/starfarer/util/A/new;)V",
                    "beginOuterFrame", "(I)V", 1);
            addHook(result, "advance", "(FLcom/fs/starfarer/util/A/new;)V",
                    "beforeSubstep", "(II)V", 1);
            addHook(result, "advance", "(FLcom/fs/starfarer/util/A/new;)V",
                    "endOuterFrame", "()V", 1);
        }
        for (Component component : plan.components) {
            if ((requestedMask & component.bit) == 0) continue;
            for (CallSiteSpec spec : component.sites) {
                addHook(result, spec.methodName, spec.methodDesc,
                        spec.wrapperName, spec.wrapperDesc, spec.expectedCount);
            }
        }
        if (plan.kind == PlanKind.CAMPAIGN_FLEET
                && (requestedMask & FLEET_PRESENTATION) != 0) {
            addHook(result, "advance", "(F)V", "advanceFleetPulseFader",
                    "(Lcom/fs/graphics/util/Fader;F)V", 1);
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<HookKey, Integer> presentationHooks(ClassNode node) {
        Map<HookKey, Integer> result = new LinkedHashMap<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(RUNTIME)) {
                    HookKey key = new HookKey(method.name, method.desc, call.name, call.desc);
                    result.merge(key, 1, Integer::sum);
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static void addHook(Map<HookKey, Integer> map, String methodName,
                                String methodDesc, String hookName, String hookDesc, int count) {
        map.put(new HookKey(methodName, methodDesc, hookName, hookDesc), count);
    }

    private static PresentationClassPlan generic(Container container, Component... components) {
        return new PresentationClassPlan(container, PlanKind.GENERIC, List.of(components));
    }

    private static Component component(int bit, CallSiteSpec... sites) {
        return new Component(bit, List.of(sites));
    }

    private static CallSiteSpec site(String methodName, String methodDesc, int originalOpcode,
                                     String owner, String name, String desc,
                                     String wrapper, String wrapperDesc, int count) {
        return new CallSiteSpec(methodName, methodDesc, originalOpcode, owner, name, desc,
                wrapper, wrapperDesc, count);
    }

    private static CallSiteSpec suppressMusic(String method, String desc, int count) {
        return site(method, desc, Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/CampaignUIAPI", "suppressMusic", "(F)V",
                "suppressMusic", "(Lcom/fs/starfarer/api/campaign/CampaignUIAPI;F)V", count);
    }

    private static CallSiteSpec playLoop(String method, String desc, int count) {
        return site(method, desc, Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/SoundPlayerAPI", "playLoop",
                "(Ljava/lang/String;Ljava/lang/Object;FFLorg/lwjgl/util/vector/Vector2f;Lorg/lwjgl/util/vector/Vector2f;)V",
                "playLoop",
                "(Lcom/fs/starfarer/api/SoundPlayerAPI;Ljava/lang/String;Ljava/lang/Object;FFLorg/lwjgl/util/vector/Vector2f;Lorg/lwjgl/util/vector/Vector2f;)V",
                count);
    }

    private static CallSiteSpec lowPass(String method, String desc, int count) {
        return site(method, desc, Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/SoundPlayerAPI", "applyLowPassFilter", "(FF)V",
                "applyLowPassFilter", "(Lcom/fs/starfarer/api/SoundPlayerAPI;FF)V", count);
    }

    private static CallSiteSpec emitter(String method, String desc, int count) {
        return site(method, desc, Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/api/util/IntervalUtil", "advance", "(F)V",
                "advanceEmitterInterval", "(Lcom/fs/starfarer/api/util/IntervalUtil;F)V", count);
    }

    private static boolean anyPresentationPatch(PrepatcherConfig config) {
        return config.fastForwardActionIndicators || config.fastForwardLocationVisuals
                || config.fastForwardFloatingText || config.fastForwardFleetView
                || config.fastForwardFleetPresentation || config.fastForwardSensorIndicators
                || config.fastForwardCelestialVisuals || config.fastForwardAuroraAnimation
                || config.fastForwardContinuousSound || config.fastForwardGateJitter
                || config.fastForwardGlobalAnimations || config.fastForwardSensorFaders
                || config.fastForwardSlipstreamParticles || config.fastForwardParticleEmitters;
    }

    private static Frame<SourceValue>[] sourceFrames(String owner, MethodNode method) {
        try {
            MethodNode analysis = new MethodNode(Opcodes.ASM8, method.access, method.name,
                    method.desc, method.signature,
                    method.exceptions == null ? null : method.exceptions.toArray(String[]::new));
            method.accept(analysis);
            // ClassNode candidates are inspected before ClassWriter recomputes max stack.
            analysis.maxStack = Math.max(analysis.maxStack, 64);
            return new Analyzer<SourceValue>(new SourceInterpreter()).analyze(owner, analysis);
        } catch (AnalyzerException failure) {
            throw mismatch(owner + "." + method.name + method.desc
                    + ": source analysis failed: " + failure.getMessage());
        }
    }

    private static void requireBooleanArgument(MethodNode method, MethodInsnNode call,
                                               Frame<SourceValue>[] frames,
                                               boolean expected, String what) {
        Frame<SourceValue> frame = frames[method.instructions.indexOf(call)];
        if (frame == null || frame.getStackSize() < 1) throw mismatch(what + ": no stack frame");
        SourceValue value = frame.getStack(frame.getStackSize() - 1);
        int opcode = expected ? Opcodes.ICONST_1 : Opcodes.ICONST_0;
        if (value == null || value.insns == null || value.insns.size() != 1
                || value.insns.iterator().next().getOpcode() != opcode) {
            throw mismatch(what + ": expected constant " + expected);
        }
    }

    private static void requireLoadedArgument(MethodNode method, MethodInsnNode call,
                                              Frame<SourceValue>[] frames, int argument,
                                              int expectedLocal, String what) {
        Frame<SourceValue> frame = frames[method.instructions.indexOf(call)];
        int count = Type.getArgumentTypes(call.desc).length;
        if (frame == null || frame.getStackSize() < count) throw mismatch(what + ": no frame");
        SourceValue value = frame.getStack(frame.getStackSize() - count + argument);
        if (loadedLocal(value) != expectedLocal) {
            throw mismatch(what + ": expected ILOAD " + expectedLocal);
        }
    }

    private static int loadedLocal(SourceValue value) {
        return loadedLocal(value, Opcodes.ILOAD);
    }

    private static int loadedLocal(SourceValue value, int expectedOpcode) {
        if (value == null || value.insns == null || value.insns.isEmpty()) return -1;
        int local = -1;
        for (AbstractInsnNode source : value.insns) {
            if (!(source instanceof VarInsnNode load) || load.getOpcode() != expectedOpcode) {
                return -1;
            }
            if (local < 0) local = load.var;
            else if (local != load.var) return -1;
        }
        return local;
    }

    private static boolean incrementedInRange(MethodNode method, int local,
                                              int startIndex, int endIndex) {
        for (int i = Math.max(0, startIndex); i <= endIndex && i < method.instructions.size(); i++) {
            AbstractInsnNode insn = method.instructions.get(i);
            if (insn instanceof IincInsnNode increment && increment.var == local) return true;
        }
        return false;
    }

    private static boolean isIntegerCompare(int opcode) {
        return opcode >= Opcodes.IF_ICMPEQ && opcode <= Opcodes.IF_ICMPLE;
    }

    private static JumpInsnNode uniqueBackedge(MethodNode method, int start, int end,
                                               int advanceIndex) {
        List<JumpInsnNode> candidates = new ArrayList<>();
        for (int i = start + 1; i < end; i++) {
            AbstractInsnNode insn = method.instructions.get(i);
            if (insn instanceof JumpInsnNode jump
                    && method.instructions.indexOf(jump.label) <= advanceIndex) {
                candidates.add(jump);
            }
        }
        if (candidates.size() != 1) {
            throw mismatch(method.name + method.desc + ": loop backedge is ambiguous: "
                    + candidates.size());
        }
        return candidates.get(0);
    }

    private static boolean sameStraightLineRegion(MethodNode method,
                                                  AbstractInsnNode anchor,
                                                  AbstractInsnNode candidate) {
        int a = method.instructions.indexOf(anchor);
        int b = method.instructions.indexOf(candidate);
        if (a < 0 || b < 0) return false;
        int from = Math.min(a, b) + 1;
        int to = Math.max(a, b);
        for (int i = from; i < to; i++) {
            AbstractInsnNode insn = method.instructions.get(i);
            if (insn instanceof JumpInsnNode || insn instanceof LookupSwitchInsnNode
                    || insn instanceof TableSwitchInsnNode || isExit(insn.getOpcode())) {
                return false;
            }
        }
        return true;
    }

    private static void requireSameStraightLineRegion(MethodNode method,
                                                      AbstractInsnNode anchor,
                                                      AbstractInsnNode candidate,
                                                      String what) {
        if (!sameStraightLineRegion(method, anchor, candidate)
                || method.instructions.indexOf(candidate) <= method.instructions.indexOf(anchor)) {
            throw mismatch(method.name + method.desc + ": " + what
                    + " is not in the required straight-line semantic block");
        }
    }

    private static boolean isExit(int opcode) {
        return opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW;
    }

    private static void requireReceiverIsFieldOrLocal(String owner, MethodNode method,
                                                      MethodInsnNode call) {
        Frame<SourceValue>[] frames = sourceFrames(owner, method);
        Frame<SourceValue> frame = frames[method.instructions.indexOf(call)];
        int args = Type.getArgumentTypes(call.desc).length;
        if (frame == null || frame.getStackSize() < args + 1) {
            throw mismatch(owner + "." + method.name + method.desc
                    + ": fleet pulse receiver frame missing");
        }
        SourceValue receiver = frame.getStack(frame.getStackSize() - args - 1);
        if (receiver == null || receiver.insns == null || receiver.insns.isEmpty()) {
            throw mismatch(owner + "." + method.name + method.desc
                    + ": fleet pulse receiver source unresolved");
        }
        for (AbstractInsnNode source : receiver.insns) {
            int opcode = source.getOpcode();
            if (opcode != Opcodes.ALOAD && opcode != Opcodes.GETFIELD) {
                throw mismatch(owner + "." + method.name + method.desc
                        + ": fleet pulse receiver is not a field/local source");
            }
        }
    }

    private static MethodInsnNode uniqueEither(MethodNode method, CallKey first, CallKey second,
                                               String what) {
        List<MethodInsnNode> found = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call
                    && (matches(call, first) || matches(call, second))) found.add(call);
        }
        if (found.size() != 1) throw mismatch(method.name + method.desc + ": " + what
                + " is ambiguous: " + found.size());
        return found.get(0);
    }

    private static MethodInsnNode uniqueCall(MethodNode method, int opcode,
                                             String owner, String name, String desc,
                                             String what) {
        List<MethodInsnNode> found = calls(method, opcode, owner, name, desc);
        if (found.size() != 1) throw mismatch(method.name + method.desc + ": " + what
                + " is ambiguous: " + found.size());
        return found.get(0);
    }

    private static boolean matches(MethodInsnNode call, CallKey key) {
        return call.getOpcode() == key.opcode && call.owner.equals(key.owner)
                && call.name.equals(key.name) && call.desc.equals(key.desc);
    }

    private static List<MethodInsnNode> calls(MethodNode method, int opcode,
                                              String owner, String name, String desc) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == opcode
                    && call.owner.equals(owner) && call.name.equals(name)
                    && call.desc.equals(desc)) result.add(call);
        }
        return result;
    }

    private static MethodNode requireMethod(ClassNode node, String name, String desc) {
        MethodNode result = null;
        for (MethodNode method : node.methods) {
            if (!method.name.equals(name) || !method.desc.equals(desc)) continue;
            if (result != null) throw mismatch(node.name + ": duplicate method " + name + desc);
            result = method;
        }
        if (result == null) throw mismatch(node.name + ": method not found: " + name + desc);
        return result;
    }

    private static SiteRef ref(MethodNode method, AbstractInsnNode insn) {
        return new SiteRef(method.name, method.desc, method.instructions.indexOf(insn));
    }

    private static MethodInsnNode methodCallAt(ClassNode node, SiteRef ref) {
        MethodNode method = requireMethod(node, ref.methodName, ref.methodDesc);
        AbstractInsnNode insn = instructionAt(method, ref.instructionIndex);
        if (!(insn instanceof MethodInsnNode call)) {
            throw mismatch(node.name + "." + ref.methodName + ref.methodDesc
                    + ": planned call site is no longer a method call");
        }
        return call;
    }

    private static AbstractInsnNode instructionAt(MethodNode method, int index) {
        if (index < 0 || index >= method.instructions.size()) {
            throw mismatch(method.name + method.desc + ": instruction index out of range: " + index);
        }
        return method.instructions.get(index);
    }

    private static AbstractInsnNode nextMeaningful(AbstractInsnNode insn) {
        for (AbstractInsnNode cursor = insn.getNext(); cursor != null; cursor = cursor.getNext()) {
            if (cursor.getOpcode() >= 0) return cursor;
        }
        return null;
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode insn) {
        for (AbstractInsnNode cursor = insn.getPrevious(); cursor != null; cursor = cursor.getPrevious()) {
            if (cursor.getOpcode() >= 0) return cursor;
        }
        return null;
    }

    private static List<FieldNode> fields(ClassNode node, String name) {
        List<FieldNode> result = new ArrayList<>();
        for (FieldNode field : node.fields) if (field.name.equals(name)) result.add(field);
        return result;
    }

    private static int countFields(ClassNode node, String name) {
        return fields(node, name).size();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM8);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static ClassNode cloneNode(ClassNode input) {
        ClassNode clone = new ClassNode(Opcodes.ASM8);
        input.accept(clone);
        return clone;
    }

    private static StructuralMismatch mismatch(String message) {
        return new StructuralMismatch(message);
    }

    private static void recordStatus(String className, String status) {
        System.setProperty("starsector.prepatcher.patchStatus."
                + className.replace('/', '.') + ".fastForwardPresentation", status);
    }

    private static String loaderName(ClassLoader loader) {
        return loader == null ? "bootstrap" : loader.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(loader));
    }

    private static void logOnce(String key, String message) {
        if (LOGGED.add(key)) PrepatcherLog.warn(message);
    }
}
