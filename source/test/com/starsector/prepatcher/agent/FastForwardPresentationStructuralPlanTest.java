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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Synthetic structural-plan coverage that does not require the game JARs. */
public final class FastForwardPresentationStructuralPlanTest {
    private static final String HOOKS =
            "com/fs/starfarer/api/StarsectorPrepatcherPresentationHooks";

    private FastForwardPresentationStructuralPlanTest() {}

    public static void main(String[] args) throws Exception {
        verifyTargetInventory();
        verifyProfileBaselines();
        verifyAllRegisteredPlans();
        verifyGenericPlanAndOwnership();
        verifyCampaignStateDataflow();
        verifyCampaignFleetPulseSelection();
        System.out.println("OK fast-forward presentation structural plan targets=24"
                + " safe=20/59 aggressive=24/71 all-plans ownership irrelevant-mutations"
                + " campaign-state-dataflow fleet-pulse-semantic basic-verifier");
    }

    private static void verifyTargetInventory() throws Exception {
        FastForwardPresentationTransformer transformer =
                new FastForwardPresentationTransformer(config(
                        "patch.fastForwardPresentation", "true"));
        require(transformer.targetClassesForTest().size() == 24,
                "presentation structural target inventory changed: "
                        + transformer.targetClassesForTest().size());
    }

    private static void verifyProfileBaselines() throws Exception {
        PrepatcherConfig safe = profileConfig(false);
        PrepatcherConfig aggressive = profileConfig(true);
        requireProfileBaseline(safe, 20, 59, "safe");
        requireProfileBaseline(aggressive, 24, 71, "aggressive");
    }

    private static PrepatcherConfig profileConfig(boolean aggressive) throws Exception {
        return config(
                "patch.fastForwardPresentation", "true",
                "patch.fastForwardFrameMarker", "true",
                "patch.fastForwardActionIndicators", "true",
                "patch.fastForwardLocationVisuals", "true",
                "patch.fastForwardFloatingText", "true",
                "patch.fastForwardFleetView", "true",
                "patch.fastForwardFleetPresentation", "true",
                "patch.fastForwardSensorIndicators", "true",
                "patch.fastForwardCelestialVisuals", "true",
                "patch.fastForwardAuroraAnimation", "true",
                "patch.fastForwardContinuousSound", "true",
                "patch.fastForwardGateJitter", "true",
                "patch.fastForwardGlobalAnimations", Boolean.toString(aggressive),
                "patch.fastForwardSensorFaders", Boolean.toString(aggressive),
                "patch.fastForwardSlipstreamParticles", Boolean.toString(aggressive),
                "patch.fastForwardParticleEmitters", Boolean.toString(aggressive));
    }

    private static void requireProfileBaseline(PrepatcherConfig config, int expectedClasses,
                                               int expectedHooks, String profile) {
        FastForwardPresentationTransformer transformer =
                new FastForwardPresentationTransformer(config);
        int classes = 0;
        int hooks = 0;
        for (String name : transformer.targetClassesForTest()) {
            int mask = FastForwardPresentationTransformer.requestedMaskForClass(name, config);
            if (mask == 0) continue;
            classes++;
            hooks += transformer.expectedHookCountForTest(name, config);
        }
        require(classes == expectedClasses,
                profile + " structural class baseline changed: " + classes);
        require(hooks == expectedHooks,
                profile + " wrapper-site baseline changed: " + hooks);
    }

    private static void verifyAllRegisteredPlans() throws Exception {
        PrepatcherConfig allEnabled = allEnabledConfig();
        FastForwardPresentationTransformer transformer =
                new FastForwardPresentationTransformer(allEnabled);
        int transformed = 0;
        for (String name : transformer.targetClassesForTest()) {
            byte[] vanilla;
            if (name.equals("com/fs/starfarer/campaign/CampaignState")) {
                vanilla = campaignState(31, 37, false, false);
            } else if (name.equals("com/fs/starfarer/campaign/fleet/CampaignFleet")) {
                vanilla = campaignFleet(false);
            } else {
                require(!transformer.specialPlanForTest(name),
                        "unknown special presentation plan: " + name);
                vanilla = genericTarget(name, transformer.callSitesForTest(name));
            }
            byte[] patched = transformer.transformBytesForTest(name, vanilla);
            require(patched != null, "registered plan did not apply: " + name);
            verifyBytecode(patched);
            require(fieldValue(patched, FastForwardPresentationTransformer.OWNER_FIELD)
                            .equals(FastForwardPresentationTransformer.OWNER_VALUE),
                    "registered plan owner missing: " + name);
            int expectedMask = FastForwardPresentationTransformer.requestedMaskForClass(
                    name, allEnabled);
            require(((Integer) fieldValue(patched,
                            FastForwardPresentationTransformer.MASK_FIELD)) == expectedMask,
                    "registered plan mask mismatch: " + name);
            require(transformer.transformBytesForTest(name, patched) == null,
                    "registered plan is not idempotent: " + name);
            transformed++;
        }
        require(transformed == 24, "not all structural plans were exercised: " + transformed);
    }

    private static PrepatcherConfig allEnabledConfig() throws Exception {
        return config(
                "patch.fastForwardPresentation", "true",
                "patch.fastForwardFrameMarker", "true",
                "patch.fastForwardActionIndicators", "true",
                "patch.fastForwardLocationVisuals", "true",
                "patch.fastForwardFloatingText", "true",
                "patch.fastForwardFleetView", "true",
                "patch.fastForwardFleetPresentation", "true",
                "patch.fastForwardSensorIndicators", "true",
                "patch.fastForwardCelestialVisuals", "true",
                "patch.fastForwardAuroraAnimation", "true",
                "patch.fastForwardContinuousSound", "true",
                "patch.fastForwardGateJitter", "true",
                "patch.fastForwardGlobalAnimations", "true",
                "patch.fastForwardSensorFaders", "true",
                "patch.fastForwardSlipstreamParticles", "true",
                "patch.fastForwardParticleEmitters", "true");
    }

    private static byte[] genericTarget(
            String name, java.util.List<FastForwardPresentationTransformer.TestCallSite> sites) {
        ClassNode node = base(name);
        Map<String, MethodNode> methods = new LinkedHashMap<>();
        for (FastForwardPresentationTransformer.TestCallSite site : sites) {
            String key = site.methodName() + site.methodDesc();
            MethodNode method = methods.computeIfAbsent(key, ignored -> {
                MethodNode created = new MethodNode(Opcodes.ASM8, Opcodes.ACC_PUBLIC,
                        site.methodName(), site.methodDesc(), null, null);
                node.methods.add(created);
                return created;
            });
            for (int i = 0; i < site.expectedCount(); i++) {
                if (site.originalOwner().equals(name)) {
                    method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                } else {
                    method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
                }
                for (Type argument : Type.getArgumentTypes(site.originalDesc())) {
                    addDefault(method, argument);
                }
                method.instructions.add(new MethodInsnNode(site.originalOpcode(),
                        site.originalOwner(), site.originalName(), site.originalDesc(),
                        site.originalOpcode() == Opcodes.INVOKEINTERFACE));
            }
        }
        for (MethodNode method : methods.values()) {
            addReturn(method, Type.getReturnType(method.desc));
            method.maxStack = 64;
            method.maxLocals = 1 + argumentSlots(method.desc);
        }
        return write(node);
    }

    private static int argumentSlots(String descriptor) {
        int slots = 0;
        for (Type type : Type.getArgumentTypes(descriptor)) slots += type.getSize();
        return slots;
    }

    private static void addDefault(MethodNode method, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT ->
                    method.instructions.add(new InsnNode(Opcodes.ICONST_0));
            case Type.FLOAT -> method.instructions.add(new InsnNode(Opcodes.FCONST_0));
            case Type.LONG -> method.instructions.add(new InsnNode(Opcodes.LCONST_0));
            case Type.DOUBLE -> method.instructions.add(new InsnNode(Opcodes.DCONST_0));
            default -> method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        }
    }

    private static void addReturn(MethodNode method, Type type) {
        switch (type.getSort()) {
            case Type.VOID -> method.instructions.add(new InsnNode(Opcodes.RETURN));
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
                method.instructions.add(new InsnNode(Opcodes.ICONST_0));
                method.instructions.add(new InsnNode(Opcodes.IRETURN));
            }
            case Type.FLOAT -> {
                method.instructions.add(new InsnNode(Opcodes.FCONST_0));
                method.instructions.add(new InsnNode(Opcodes.FRETURN));
            }
            case Type.LONG -> {
                method.instructions.add(new InsnNode(Opcodes.LCONST_0));
                method.instructions.add(new InsnNode(Opcodes.LRETURN));
            }
            case Type.DOUBLE -> {
                method.instructions.add(new InsnNode(Opcodes.DCONST_0));
                method.instructions.add(new InsnNode(Opcodes.DRETURN));
            }
            default -> {
                method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
                method.instructions.add(new InsnNode(Opcodes.ARETURN));
            }
        }
    }

    private static void verifyBytecode(byte[] bytes) throws Exception {
        ClassNode node = read(bytes);
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
        }
    }

    private static void verifyGenericPlanAndOwnership() throws Exception {
        String name = "com/fs/starfarer/campaign/CampaignEngine";
        PrepatcherConfig config = config("patch.fastForwardActionIndicators", "true",
                "patch.fastForwardGlobalAnimations", "true");
        FastForwardPresentationTransformer transformer =
                new FastForwardPresentationTransformer(config);

        byte[] vanilla = campaignEngine(false);
        byte[] patched = transformer.transformBytesForTest(name, vanilla);
        require(patched != null, "generic plan did not apply");
        require(countHooks(patched) == 3, "generic hook count changed");
        require(fieldValue(patched, FastForwardPresentationTransformer.OWNER_FIELD)
                        .equals(FastForwardPresentationTransformer.OWNER_VALUE),
                "generic owner missing");
        require(((Integer) fieldValue(patched,
                        FastForwardPresentationTransformer.MASK_FIELD))
                        == (FastForwardPresentationTransformer.ACTION_INDICATORS
                        | FastForwardPresentationTransformer.GLOBAL_ANIMATIONS),
                "generic feature mask mismatch");
        require(transformer.transformBytesForTest(name, patched) == null,
                "generic plan is not idempotent");

        byte[] unrelated = campaignEngine(true);
        require(transformer.transformBytesForTest(name, unrelated) != null,
                "unrelated private field/method blocked structural matching");

        ClassNode missing = read(vanilla);
        MethodNode advance = method(missing, "advance", "(FLcom/fs/starfarer/util/A/new;)V");
        for (AbstractInsnNode insn : advance.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals("com/fs/starfarer/campaign/ActionIndicator")) {
                call.name = "changed";
                break;
            }
        }
        expectStructuralSkip(transformer, name, write(missing),
                "missing action-indicator site did not skip");

        ClassNode duplicate = read(vanilla);
        MethodNode duplicateAdvance = method(duplicate, "advance",
                "(FLcom/fs/starfarer/util/A/new;)V");
        duplicateAdvance.instructions.insertBefore(duplicateAdvance.instructions.getLast(),
                actionIndicatorCall());
        expectStructuralSkip(transformer, name, write(duplicate),
                "duplicated action-indicator site did not skip");

        ClassNode mixed = read(patched);
        MethodInsnNode mixedAction = hook(method(mixed, "advance",
                "(FLcom/fs/starfarer/util/A/new;)V"), "advanceActionIndicator");
        mixedAction.setOpcode(Opcodes.INVOKEVIRTUAL);
        mixedAction.owner = "com/fs/starfarer/campaign/ActionIndicator";
        mixedAction.name = "advance";
        mixedAction.desc = "(F)V";
        mixedAction.itf = false;
        expectStructuralSkip(transformer, name, write(mixed),
                "mixed original/wrapper state did not skip");

        ClassNode unowned = read(patched);
        unowned.fields.removeIf(field -> field.name.equals(
                FastForwardPresentationTransformer.OWNER_FIELD));
        expectStructuralSkip(transformer, name, write(unowned),
                "foreign hook-shaped state without owner did not skip");

        ClassNode badMask = read(patched);
        for (FieldNode field : badMask.fields) {
            if (field.name.equals(FastForwardPresentationTransformer.MASK_FIELD)) {
                field.value = FastForwardPresentationTransformer.ACTION_INDICATORS;
            }
        }
        expectStructuralSkip(transformer, name, write(badMask),
                "mask mismatch did not skip");
    }

    private static void verifyCampaignStateDataflow() throws Exception {
        String name = "com/fs/starfarer/campaign/CampaignState";
        PrepatcherConfig config = config("patch.fastForwardActionIndicators", "true");
        FastForwardPresentationTransformer transformer =
                new FastForwardPresentationTransformer(config);
        byte[] vanilla = campaignState(20, 24, false, false);
        byte[] patched = transformer.transformBytesForTest(name, vanilla);
        require(patched != null, "CampaignState plan did not apply with shifted locals");
        MethodNode method = method(read(patched), "advance",
                "(FLcom/fs/starfarer/util/A/new;)V");
        MethodInsnNode begin = hook(method, "beginOuterFrame");
        MethodInsnNode before = hook(method, "beforeSubstep");
        require(previousMeaningful(begin) instanceof VarInsnNode total
                        && total.getOpcode() == Opcodes.ILOAD && total.var == 20,
                "beginOuterFrame did not use inferred total-count local");
        AbstractInsnNode beforeTotal = previousMeaningful(before);
        AbstractInsnNode beforeIndex = previousMeaningful(beforeTotal);
        require(beforeIndex instanceof VarInsnNode index
                        && index.getOpcode() == Opcodes.ILOAD && index.var == 24,
                "beforeSubstep did not use inferred substep local");
        require(beforeTotal instanceof VarInsnNode total2
                        && total2.getOpcode() == Opcodes.ILOAD && total2.var == 20,
                "beforeSubstep did not use inferred total-count local");
        require(transformer.transformBytesForTest(name, patched) == null,
                "CampaignState plan is not idempotent");

        byte[] postTested = campaignState(21, 25, false, false, true);
        byte[] postTestedPatched = transformer.transformBytesForTest(name, postTested);
        require(postTestedPatched != null,
                "CampaignState plan did not apply to the real post-tested loop shape");
        verifyBytecode(postTestedPatched);
        require(transformer.transformBytesForTest(name, postTestedPatched) == null,
                "post-tested CampaignState plan is not idempotent");

        expectStructuralSkip(transformer, name, campaignState(20, 24, true, false),
                "CampaignState accepted a missing first false");
        expectStructuralSkip(transformer, name, campaignState(20, 24, false, true),
                "CampaignState accepted two CampaignEngine.advance calls");
    }

    private static void verifyCampaignFleetPulseSelection() throws Exception {
        String name = "com/fs/starfarer/campaign/fleet/CampaignFleet";
        PrepatcherConfig config = config("patch.fastForwardFleetPresentation", "true");
        FastForwardPresentationTransformer transformer =
                new FastForwardPresentationTransformer(config);
        byte[] patched = transformer.transformBytesForTest(name, campaignFleet(false));
        require(patched != null, "CampaignFleet plan did not apply");
        MethodNode method = method(read(patched), "advance", "(F)V");
        require(count(method, Opcodes.INVOKEVIRTUAL, "com/fs/graphics/util/Fader",
                        "advance", "(F)V") == 1,
                "CampaignFleet did not preserve the unrelated fader");
        require(count(method, Opcodes.INVOKESTATIC, HOOKS,
                        "advanceFleetPulseFader", "(Lcom/fs/graphics/util/Fader;F)V") == 1,
                "CampaignFleet pulse fader wrapper missing");
        expectStructuralSkip(transformer, name, campaignFleet(true),
                "CampaignFleet accepted two faders in the same presentation region");
    }

    private static InsnList actionIndicatorCall() {
        InsnList instructions = new InsnList();
        instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        instructions.add(new InsnNode(Opcodes.FCONST_0));
        instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/campaign/ActionIndicator", "advance", "(F)V", false));
        return instructions;
    }

    private static byte[] campaignEngine(boolean unrelated) {
        ClassNode node = base("com/fs/starfarer/campaign/CampaignEngine");
        if (unrelated) {
            node.fields.add(new FieldNode(Opcodes.ASM8,
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                    "unrelated", "I", null, null));
            MethodNode helper = new MethodNode(Opcodes.ASM8, Opcodes.ACC_PRIVATE,
                    "unrelatedHelper", "()V", null, null);
            helper.instructions.add(new InsnNode(Opcodes.RETURN));
            helper.maxStack = 0;
            helper.maxLocals = 1;
            node.methods.add(helper);
        }
        MethodNode method = new MethodNode(Opcodes.ASM8, Opcodes.ACC_PUBLIC,
                "advance", "(FLcom/fs/starfarer/util/A/new;)V", null, null);
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new InsnNode(Opcodes.FCONST_0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/campaign/ActionIndicator", "advance", "(F)V", false));
        for (int i = 0; i < 2; i++) {
            method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            method.instructions.add(new InsnNode(Opcodes.FCONST_0));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "com/fs/graphics/anim/AnimationManager", "advanceAll", "(F)V", false));
        }
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 2;
        method.maxLocals = 3;
        node.methods.add(method);
        return write(node);
    }

    private static byte[] campaignState(int totalLocal, int indexLocal,
                                        boolean firstTrue, boolean duplicateAdvance) {
        return campaignState(totalLocal, indexLocal, firstTrue, duplicateAdvance, false);
    }

    private static byte[] campaignState(int totalLocal, int indexLocal,
                                        boolean firstTrue, boolean duplicateAdvance,
                                        boolean postTested) {
        ClassNode node = base("com/fs/starfarer/campaign/CampaignState");
        MethodNode method = new MethodNode(Opcodes.ASM8, Opcodes.ACC_PUBLIC,
                "advance", "(FLcom/fs/starfarer/util/A/new;)V", null, null);
        LabelNode loop = new LabelNode();
        LabelNode exit = new LabelNode();
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.add(new InsnNode(Opcodes.ICONST_4));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, totalLocal));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new InsnNode(firstTrue ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/campaign/CampaignEngine", "setFastForwardIteration", "(Z)V", false));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, indexLocal));
        method.instructions.add(loop);
        if (!postTested) {
            method.instructions.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
            method.instructions.add(new VarInsnNode(Opcodes.ILOAD, totalLocal));
            method.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPGE, exit));
        }
        addEngineAdvance(method);
        if (duplicateAdvance) addEngineAdvance(method);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/campaign/CampaignEngine", "setFastForwardIteration", "(Z)V", false));
        method.instructions.add(new IincInsnNode(indexLocal, 1));
        if (postTested) {
            method.instructions.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
            method.instructions.add(new VarInsnNode(Opcodes.ILOAD, totalLocal));
            method.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPLT, loop));
        } else {
            method.instructions.add(new JumpInsnNode(Opcodes.GOTO, loop));
        }
        method.instructions.add(exit);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/campaign/CampaignEngine", "setFastForwardIteration", "(Z)V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 8;
        method.maxLocals = Math.max(totalLocal, indexLocal) + 1;
        node.methods.add(method);
        return write(node);
    }

    private static void addEngineAdvance(MethodNode method) {
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new InsnNode(Opcodes.FCONST_0));
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/campaign/CampaignEngine", "advance",
                "(FLcom/fs/starfarer/util/A/new;)V", false));
    }

    private static byte[] campaignFleet(boolean ambiguous) {
        ClassNode node = base("com/fs/starfarer/campaign/fleet/CampaignFleet");
        MethodNode method = new MethodNode(Opcodes.ASM8, Opcodes.ACC_PUBLIC,
                "advance", "(F)V", null, null);
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new InsnNode(Opcodes.FCONST_0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/campaign/fleet/CampaignFleetView", "advance", "(F)V", false));
        if (!ambiguous) {
            addFader(method);
            LabelNode next = new LabelNode();
            method.instructions.add(new JumpInsnNode(Opcodes.GOTO, next));
            method.instructions.add(next);
        }
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/campaign/fleet/FleetAbilityRenderer", "updateLayers", "()V", false));
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/campaign/fleet/CampaignFleetView", "clear", "()V", false));
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new InsnNode(Opcodes.FCONST_0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/campaign/E", "Ò00000", "(F)V", false));
        addFader(method);
        if (ambiguous) addFader(method);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                node.name, "updateSpeedBonus", "()V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 2;
        method.maxLocals = 3;
        node.methods.add(method);
        return write(node);
    }

    private static void addFader(MethodNode method) {
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.FCONST_0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "com/fs/graphics/util/Fader", "advance", "(F)V", false));
    }

    private static PrepatcherConfig config(String... pairs) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("patch.fastForwardPresentation", "true");
        properties.setProperty("patch.fastForwardFrameMarker", "true");
        properties.setProperty("fastForward.verbose", "false");
        String[] groups = {
                "ActionIndicators", "LocationVisuals", "FloatingText", "FleetView",
                "FleetPresentation", "SensorIndicators", "CelestialVisuals",
                "AuroraAnimation", "ContinuousSound", "GateJitter", "GlobalAnimations",
                "SensorFaders", "SlipstreamParticles", "ParticleEmitters"
        };
        for (String group : groups) {
            properties.setProperty("patch.fastForward" + group, "false");
        }
        for (int i = 0; i < pairs.length; i += 2) properties.setProperty(pairs[i], pairs[i + 1]);
        Constructor<PrepatcherConfig> constructor =
                PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);
        return constructor.newInstance(properties);
    }

    private static void expectStructuralSkip(FastForwardPresentationTransformer transformer,
                                             String name, byte[] bytes, String message) {
        String key = "starsector.prepatcher.patchStatus."
                + name.replace('/', '.') + ".fastForwardPresentation";
        System.clearProperty(key);
        require(transformer.transform(null, name, null, null, bytes) == null, message);
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(key)),
                message + ": status=" + System.getProperty(key));
    }

    private static ClassNode base(String name) {
        ClassNode node = new ClassNode(Opcodes.ASM8);
        node.version = Opcodes.V1_7;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = "java/lang/Object";
        return node;
    }

    private static int countHooks(byte[] bytes) {
        int result = 0;
        for (MethodNode method : read(bytes).methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(HOOKS)) result++;
            }
        }
        return result;
    }

    private static Object fieldValue(byte[] bytes, String name) {
        for (FieldNode field : read(bytes).fields) if (field.name.equals(name)) return field.value;
        throw new AssertionError("field not found: " + name);
    }

    private static MethodInsnNode hook(MethodNode method, String name) {
        MethodInsnNode result = null;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(HOOKS)
                    && call.name.equals(name)) {
                require(result == null, "duplicate hook " + name);
                result = call;
            }
        }
        require(result != null, "hook not found: " + name);
        return result;
    }

    private static int count(MethodNode method, int opcode, String owner, String name, String desc) {
        int result = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == opcode
                    && call.owner.equals(owner) && call.name.equals(name)
                    && call.desc.equals(desc)) result++;
        }
        return result;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        throw new AssertionError("method not found: " + name + desc);
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode insn) {
        for (AbstractInsnNode cursor = insn.getPrevious(); cursor != null; cursor = cursor.getPrevious()) {
            if (cursor.getOpcode() >= 0) return cursor;
        }
        return null;
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
