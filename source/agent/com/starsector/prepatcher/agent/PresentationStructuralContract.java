package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Ownership and postcondition contract for classes whose presentation and structural
 * transformation surfaces overlap. The structural presentation stage owns the presentation
 * hooks; the structural stage may add independent hooks only while preserving this contract.
 */
final class PresentationStructuralContract {
    static final String PRESENTATION_HOOKS =
            "com/fs/starfarer/api/StarsectorPrepatcherPresentationHooks";
    static final String OWNER_FIELD = FastForwardPresentationTransformer.OWNER_FIELD;
    static final String MASK_FIELD = FastForwardPresentationTransformer.MASK_FIELD;
    static final String OWNER_VALUE = FastForwardPresentationTransformer.OWNER_VALUE;

    static final String CAMPAIGN_STATE = "com/fs/starfarer/campaign/CampaignState";
    static final String CAMPAIGN_ENGINE = "com/fs/starfarer/campaign/CampaignEngine";
    static final String BASE_LOCATION = "com/fs/starfarer/campaign/BaseLocation";
    static final String BASE_CAMPAIGN_ENTITY = "com/fs/starfarer/campaign/BaseCampaignEntity";
    static final String JUMP_POINT = "com/fs/starfarer/campaign/JumpPoint";
    static final String HYPERSPACE_TERRAIN =
            "com/fs/starfarer/api/impl/campaign/terrain/HyperspaceTerrainPlugin";

    static final Set<String> OVERLAP_CLASSES = Set.of(
            CAMPAIGN_STATE,
            CAMPAIGN_ENGINE,
            BASE_LOCATION,
            BASE_CAMPAIGN_ENTITY,
            JUMP_POINT,
            HYPERSPACE_TERRAIN);

    private PresentationStructuralContract() {}

    static int expectedMask(String className, PrepatcherConfig config) {
        if (!OVERLAP_CLASSES.contains(className)) return 0;
        return FastForwardPresentationTransformer.requestedMaskForClass(className, config);
    }

    static void addOwnership(ClassNode node, int mask) {
        if (!OVERLAP_CLASSES.contains(node.name)) return;
        if (mask == 0) {
            throw new IllegalStateException("presentation overlap has no enabled feature mask: "
                    + node.name);
        }
        if (findFields(node, OWNER_FIELD) != 0 || findFields(node, MASK_FIELD) != 0) {
            throw new IllegalStateException("presentation ownership fields already exist in "
                    + node.name);
        }
        int access = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
                | Opcodes.ACC_SYNTHETIC;
        node.fields.add(new FieldNode(Opcodes.ASM8, access, OWNER_FIELD,
                "Ljava/lang/String;", null, OWNER_VALUE));
        node.fields.add(new FieldNode(Opcodes.ASM8, access, MASK_FIELD,
                "I", null, mask));
    }

    static State inspect(String className, byte[] bytes, PrepatcherConfig config) {
        if (!OVERLAP_CLASSES.contains(className)) return State.absent(className);
        ClassNode node = read(bytes);
        return inspectNode(className, node, config);
    }

    static State inspectNode(String className, ClassNode node, PrepatcherConfig config) {
        int ownerFields = findFields(node, OWNER_FIELD);
        int maskFields = findFields(node, MASK_FIELD);
        Map<HookSite, Integer> hooks = presentationHooks(node);
        if (ownerFields == 0 && maskFields == 0 && hooks.isEmpty()) {
            return State.absent(className);
        }
        if (ownerFields != 1 || maskFields != 1) {
            throw new IllegalStateException("presentation ownership is partial in " + className
                    + ": ownerFields=" + ownerFields + ", maskFields=" + maskFields
                    + ", hooks=" + hooks);
        }
        FieldNode owner = field(node, OWNER_FIELD);
        FieldNode mask = field(node, MASK_FIELD);
        if (!OWNER_VALUE.equals(owner.value)) {
            throw new IllegalStateException("presentation owner value mismatch in " + className
                    + ": " + owner.value);
        }
        if (!(mask.value instanceof Integer actualMask)) {
            throw new IllegalStateException("presentation feature mask is not a constant int in "
                    + className + ": " + mask.value);
        }
        int expectedMask = expectedMask(className, config);
        if (expectedMask == 0) {
            throw new IllegalStateException("presentation-owned overlap is disabled by current "
                    + "configuration in " + className + ", actualMask=" + actualMask);
        }
        if (actualMask != expectedMask) {
            throw new IllegalStateException("presentation feature mask mismatch in " + className
                    + ": expected=" + expectedMask + ", actual=" + actualMask);
        }
        Map<HookSite, Integer> expectedHooks = expectedHooks(className, expectedMask);
        if (!hooks.equals(expectedHooks)) {
            throw new IllegalStateException("presentation hook postcondition mismatch in "
                    + className + ": expected=" + expectedHooks + ", actual=" + hooks);
        }
        return State.present(className, actualMask, hooks);
    }

    static Map<HookSite, Integer> expectedHooks(String className, int mask) {
        Map<HookSite, Integer> expected = new TreeMap<>();
        switch (className) {
            case CAMPAIGN_STATE -> {
                add(expected, "advance", "(FLcom/fs/starfarer/util/A/new;)V",
                        "beginOuterFrame", "(I)V", 1);
                add(expected, "advance", "(FLcom/fs/starfarer/util/A/new;)V",
                        "beforeSubstep", "(II)V", 1);
                add(expected, "advance", "(FLcom/fs/starfarer/util/A/new;)V",
                        "endOuterFrame", "()V", 1);
            }
            case CAMPAIGN_ENGINE -> {
                if ((mask & FastForwardPresentationTransformer.ACTION_INDICATORS) != 0) {
                    add(expected, "advance", "(FLcom/fs/starfarer/util/A/new;)V",
                            "advanceActionIndicator",
                            "(Lcom/fs/starfarer/campaign/ActionIndicator;F)V", 1);
                }
                if ((mask & FastForwardPresentationTransformer.GLOBAL_ANIMATIONS) != 0) {
                    add(expected, "advance", "(FLcom/fs/starfarer/util/A/new;)V",
                            "advanceAnimationManager",
                            "(Lcom/fs/graphics/anim/AnimationManager;F)V", 2);
                }
            }
            case BASE_LOCATION -> {
                add(expected, "advance", "(FLcom/fs/starfarer/util/A/new;)V",
                        "advanceLocationLightFader",
                        "(Lcom/fs/graphics/util/Fader;F)V", 1);
                add(expected, "advance", "(FLcom/fs/starfarer/util/A/new;)V",
                        "advanceBackground",
                        "(Lcom/fs/starfarer/campaign/BackgroundAndStars;F)V", 1);
                add(expected, "advance", "(FLcom/fs/starfarer/util/A/new;)V",
                        "advanceParticleGroup",
                        "(Lcom/fs/graphics/particle/DynamicParticleGroup;F)V", 1);
            }
            case BASE_CAMPAIGN_ENTITY -> {
                if ((mask & FastForwardPresentationTransformer.FLOATING_TEXT) != 0) {
                    add(expected, "advance", "(F)V", "advanceFloatingText",
                            "(Lcom/fs/starfarer/campaign/fleet/CampaignFloatingText;F)V", 1);
                    add(expected, "advanceEvenIfPaused", "(F)V", "advanceFloatingText",
                            "(Lcom/fs/starfarer/campaign/fleet/CampaignFloatingText;F)V", 1);
                }
                if ((mask & FastForwardPresentationTransformer.SENSOR_INDICATORS) != 0) {
                    add(expected, "advance", "(F)V", "advanceSelectionIndicator",
                            "(Ljava/lang/Object;F)V", 1);
                }
                if ((mask & FastForwardPresentationTransformer.SENSOR_FADERS) != 0) {
                    add(expected, "advance", "(F)V", "advanceSensorFader",
                            "(Lcom/fs/graphics/util/Fader;F)V", 2);
                }
            }
            case JUMP_POINT -> {
                add(expected, "advance", "(F)V", "advanceDynamicRingBand",
                        "(Lcom/fs/starfarer/campaign/DynamicRingBand;F)V", 1);
                add(expected, "advance", "(F)V", "advanceJumpRingData",
                        "(Lcom/fs/starfarer/campaign/JumpPoint$RingData;F)V", 2);
                add(expected, "advance", "(F)V", "advanceJumpCoronaData",
                        "(Lcom/fs/starfarer/campaign/JumpPoint$CoronaData;F)V", 1);
                add(expected, "advance", "(F)V", "advancePlanetGraphics",
                        "(Lcom/fs/starfarer/combat/entities/terrain/Planet;F)V", 1);
            }
            case HYPERSPACE_TERRAIN -> {
                add(expected, "advance", "(F)V", "applyLowPassFilter",
                        "(Lcom/fs/starfarer/api/SoundPlayerAPI;FF)V", 1);
            }
            default -> throw new IllegalArgumentException("not an overlap: " + className);
        }
        return Collections.unmodifiableMap(expected);
    }

    private static Map<HookSite, Integer> presentationHooks(ClassNode node) {
        Map<HookSite, Integer> hooks = new TreeMap<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null;
                 insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call
                        && call.owner.equals(PRESENTATION_HOOKS)) {
                    HookSite site = new HookSite(method.name, method.desc, call.name, call.desc);
                    hooks.merge(site, 1, Integer::sum);
                }
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(hooks));
    }

    private static void add(Map<HookSite, Integer> map, String methodName,
                            String methodDesc, String hookName, String hookDesc, int count) {
        map.put(new HookSite(methodName, methodDesc, hookName, hookDesc), count);
    }

    private static int findFields(ClassNode node, String name) {
        int count = 0;
        for (FieldNode field : node.fields) if (field.name.equals(name)) count++;
        return count;
    }

    private static FieldNode field(ClassNode node, String name) {
        for (FieldNode field : node.fields) if (field.name.equals(name)) return field;
        throw new IllegalStateException("field not found: " + node.name + "." + name);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM8);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    record HookSite(String methodName, String methodDesc,
                    String hookName, String hookDesc) implements Comparable<HookSite> {
        @Override
        public int compareTo(HookSite other) {
            int result = methodName.compareTo(other.methodName);
            if (result != 0) return result;
            result = methodDesc.compareTo(other.methodDesc);
            if (result != 0) return result;
            result = hookName.compareTo(other.hookName);
            if (result != 0) return result;
            return hookDesc.compareTo(other.hookDesc);
        }
    }

    static final class State {
        private final String className;
        private final boolean present;
        private final int mask;
        private final Map<HookSite, Integer> hooks;

        private State(String className, boolean present, int mask,
                      Map<HookSite, Integer> hooks) {
            this.className = className;
            this.present = present;
            this.mask = mask;
            this.hooks = hooks;
        }

        static State absent(String className) {
            return new State(className, false, 0, Map.of());
        }

        static State present(String className, int mask, Map<HookSite, Integer> hooks) {
            return new State(className, true, mask, hooks);
        }

        boolean present() {
            return present;
        }

        int mask() {
            return mask;
        }

        Map<HookSite, Integer> hooks() {
            return hooks;
        }

        void validate(byte[] candidate, PrepatcherConfig config) {
            State actual = inspect(className, candidate, config);
            if (present != actual.present || mask != actual.mask || !hooks.equals(actual.hooks)) {
                throw new IllegalStateException("presentation/structural composition changed in "
                        + className + ": expected=" + this + ", actual=" + actual);
            }
        }

        @Override
        public String toString() {
            return present ? "PRESENT(mask=" + mask + ", hooks=" + hooks + ")" : "ABSENT";
        }
    }
}
