package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/** Guards the exact market-open Economy.nextStep call with an explicit runtime action. */
final class AoTDMarketOpenContextTransformer implements ClassFileTransformer {
    static final String TARGET = "com/fs/starfarer/campaign/CampaignEngine";
    private static final String METHOD = "reportPlayerOpenedMarket";
    private static final String DESC =
            "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V";
    private static final String LEGACY_RAW = "spp$raw$reportPlayerOpenedMarket";
    private static final String RUNTIME =
            "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge";
    private static final String MARKER = "spp$patched$aotdMarketOpenContext";
    private static final String MARKER_VALUE =
            "StarsectorPrepatcher:aotd-market-open-explicit-guard-v4";
    private static final String MARKET_OPEN_GUARD =
            "shouldHandleVanillaMarketOpenEconomyStep";
    private static final String MARKET_OPEN_GUARD_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;)Z";

    private final boolean enabled;
    private final ClassLoader runtimeLoader;

    AoTDMarketOpenContextTransformer(boolean enabled, ClassLoader runtimeLoader) {
        this(enabled, enabled, enabled, runtimeLoader);
    }

    AoTDMarketOpenContextTransformer(
            boolean contextEnabled, boolean conditionOnlyEnabled,
            ClassLoader runtimeLoader) {
        this(contextEnabled, conditionOnlyEnabled, false, runtimeLoader);
    }

    AoTDMarketOpenContextTransformer(
            boolean contextEnabled, boolean conditionOnlyEnabled,
            boolean vanillaMarketOpenLocalizationEnabled,
            ClassLoader runtimeLoader) {
        this.enabled = contextEnabled || conditionOnlyEnabled
                || vanillaMarketOpenLocalizationEnabled;
        this.runtimeLoader = runtimeLoader;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!enabled || !TARGET.equals(className)) return null;
        if (!runtimeVisibleFrom(loader)) {
            record("SKIPPED_LOADER");
            PrepatcherLog.warn("AoTD market-open context not patched: target loader="
                    + loaderName(loader) + ", runtime loader=" + loaderName(runtimeLoader));
            return null;
        }
        try {
            ClassNode node = read(classfileBuffer);
            if (!TARGET.equals(node.name)) return null;
            FieldNode marker = field(node, MARKER);
            if (marker != null) {
                requireMarker(marker);
                requirePatchedShape(node);
                record("ALREADY_APPLIED");
                return null;
            }
            if (method(node, LEGACY_RAW, DESC) != null) {
                throw new StructuralMismatch("legacy raw helper exists without owned marker");
            }

            MethodNode original = requireMethod(node, METHOD, DESC);
            requireOriginalShape(original);
            patchMarketOpenGuard(original);
            node.fields.add(new FieldNode(Opcodes.ASM8,
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
                            | Opcodes.ACC_SYNTHETIC,
                    MARKER, "Ljava/lang/String;", null, MARKER_VALUE));
            requirePatchedShape(node);

            ClassWriter writer = new LoaderNeutralClassWriter(
                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            byte[] transformed = writer.toByteArray();
            requirePatchedShape(read(transformed));
            record("APPLIED");
            PrepatcherLog.info("APPLIED aotdMarketOpenContext to " + className
                    + ": explicit condition-only/live-market guard with original"
                    + " Economy.nextStep global fallback");
            return transformed;
        } catch (StructuralMismatch mismatch) {
            record("SKIPPED_STRUCTURAL");
            PrepatcherLog.warn("SKIPPED_STRUCTURAL aotdMarketOpenContext in "
                    + className + ": " + mismatch.getMessage());
            return null;
        } catch (Throwable failure) {
            record("SKIPPED_ERROR");
            PrepatcherLog.error("SKIPPED_ERROR aotdMarketOpenContext in "
                    + className + "; vanilla market-open behavior remains active.", failure);
            return null;
        }
    }

    private static void patchMarketOpenGuard(MethodNode method) {
        MethodInsnNode nextStepCall = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && "com/fs/starfarer/campaign/econ/Economy".equals(call.owner)
                    && "nextStep".equals(call.name) && "()V".equals(call.desc)) {
                if (nextStepCall != null) {
                    throw new StructuralMismatch("multiple Economy.nextStep calls");
                }
                nextStepCall = call;
            }
        }
        if (nextStepCall == null) {
            throw new StructuralMismatch("missing Economy.nextStep call");
        }
        int economyLocal = method.maxLocals;
        method.maxLocals = economyLocal + 1;
        LabelNode afterStep = new LabelNode();
        InsnList guard = new InsnList();
        // The Economy receiver is already on the stack at this point.
        guard.add(new VarInsnNode(Opcodes.ASTORE, economyLocal));
        guard.add(new VarInsnNode(Opcodes.ALOAD, economyLocal));
        guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                MARKET_OPEN_GUARD, MARKET_OPEN_GUARD_DESC, false));
        guard.add(new JumpInsnNode(Opcodes.IFNE, afterStep));
        guard.add(new VarInsnNode(Opcodes.ALOAD, economyLocal));
        method.instructions.insertBefore(nextStepCall, guard);
        method.instructions.insert(nextStepCall, afterStep);
    }

    private static void requireOriginalShape(MethodNode method) {
        if ((method.access & Opcodes.ACC_PUBLIC) == 0
                || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
                | Opcodes.ACC_NATIVE)) != 0) {
            throw new StructuralMismatch("reportPlayerOpenedMarket access changed");
        }
        requireOriginalBodyShape(method);
    }

    private static void requireOriginalBodyShape(MethodNode method) {
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
            throw new StructuralMismatch("reportPlayerOpenedMarket gained exception regions");
        }
        requireCount("Economy.nextStep calls", countCalls(method,
                "com/fs/starfarer/campaign/econ/Economy", "nextStep", "()V"), 1);
        requireCount("setCurrentlyOpenMarket calls", countCalls(method,
                TARGET, "setCurrentlyOpenMarket",
                "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V"), 1);
        requireCount("market-open guards", countCalls(method, RUNTIME,
                MARKET_OPEN_GUARD, MARKET_OPEN_GUARD_DESC), 0);

        int nextStep = instructionIndex(method, "com/fs/starfarer/campaign/econ/Economy",
                "nextStep", "()V");
        int publish = instructionIndex(method, TARGET, "setCurrentlyOpenMarket",
                "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V");
        if (nextStep < 0 || publish < 0 || nextStep >= publish) {
            throw new StructuralMismatch(
                    "vanilla nextStep-before-currentlyOpenMarket ordering changed");
        }
    }

    private static void requireGuardedShape(MethodNode method) {
        if ((method.access & Opcodes.ACC_PUBLIC) == 0
                || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
                | Opcodes.ACC_NATIVE)) != 0) {
            throw new StructuralMismatch("guarded reportPlayerOpenedMarket access changed");
        }
        requireCount("guarded exception regions", method.tryCatchBlocks.size(), 0);
        requireCount("guarded nextStep calls", countCalls(method,
                "com/fs/starfarer/campaign/econ/Economy", "nextStep", "()V"), 1);
        requireCount("guarded market-open guards", countCalls(method, RUNTIME,
                MARKET_OPEN_GUARD, MARKET_OPEN_GUARD_DESC), 1);
        requireCount("guarded setCurrentlyOpenMarket calls", countCalls(method,
                TARGET, "setCurrentlyOpenMarket",
                "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V"), 1);
        int nextStep = instructionIndex(method, "com/fs/starfarer/campaign/econ/Economy",
                "nextStep", "()V");
        int publish = instructionIndex(method, TARGET, "setCurrentlyOpenMarket",
                "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V");
        if (nextStep < 0 || publish < 0 || nextStep >= publish) {
            throw new StructuralMismatch(
                    "guarded nextStep-before-currentlyOpenMarket ordering changed");
        }
        MethodInsnNode guard = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && RUNTIME.equals(call.owner)
                    && MARKET_OPEN_GUARD.equals(call.name)
                    && MARKET_OPEN_GUARD_DESC.equals(call.desc)) {
                guard = call;
                break;
            }
        }
        if (guard == null) throw new StructuralMismatch("market-open guard missing");
        AbstractInsnNode marketLoad = previousMeaningful(guard);
        AbstractInsnNode economyLoad = previousMeaningful(marketLoad);
        if (!(marketLoad instanceof VarInsnNode marketVar)
                || marketVar.getOpcode() != Opcodes.ALOAD || marketVar.var != 1) {
            throw new StructuralMismatch("market-open guard market argument changed");
        }
        if (!(economyLoad instanceof VarInsnNode economyVar)
                || economyVar.getOpcode() != Opcodes.ALOAD) {
            throw new StructuralMismatch("market-open guard economy argument changed");
        }
        AbstractInsnNode branch = nextMeaningful(guard);
        if (!(branch instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFNE) {
            throw new StructuralMismatch("market-open guard branch changed");
        }
        MethodInsnNode fallback = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && "com/fs/starfarer/campaign/econ/Economy".equals(call.owner)
                    && "nextStep".equals(call.name) && "()V".equals(call.desc)) {
                fallback = call;
                break;
            }
        }
        if (fallback == null) throw new StructuralMismatch("market-open fallback missing");
        AbstractInsnNode fallbackReceiver = previousMeaningful(fallback);
        if (!(fallbackReceiver instanceof VarInsnNode receiverLoad)
                || receiverLoad.getOpcode() != Opcodes.ALOAD
                || receiverLoad.var != economyVar.var) {
            throw new StructuralMismatch("market-open fallback receiver changed");
        }
        if (instructionIndex(method, branch) >= instructionIndex(method, fallback)
                || instructionIndex(method, fallback)
                >= instructionIndex(method, jump.label)) {
            throw new StructuralMismatch(
                    "market-open guard does not enclose only nextStep fallback");
        }
    }

    private static void requirePatchedShape(ClassNode node) {
        requireMarker(field(node, MARKER));
        if (method(node, LEGACY_RAW, DESC) != null) {
            throw new StructuralMismatch("guarded class retained legacy raw helper");
        }
        requireGuardedShape(requireMethod(node, METHOD, DESC));
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static AbstractInsnNode nextMeaningful(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getNext();
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
    }

    private static int instructionIndex(
            MethodNode method, String owner, String name, String desc) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && desc.equals(call.desc)) return index;
            index++;
        }
        return -1;
    }

    private static int instructionIndex(MethodNode method, AbstractInsnNode target) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction == target) return index;
            index++;
        }
        return -1;
    }

    private static int countCalls(
            MethodNode method, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && desc.equals(call.desc)) count++;
        }
        return count;
    }

    private static void requireCount(String label, int actual, int expected) {
        if (actual != expected) {
            throw new StructuralMismatch(label + " expected=" + expected
                    + " actual=" + actual);
        }
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods)
            if (name.equals(method.name) && desc.equals(method.desc)) return method;
        return null;
    }

    private static MethodNode requireMethod(ClassNode node, String name, String desc) {
        MethodNode method = method(node, name, desc);
        if (method == null) throw new StructuralMismatch("missing method " + name + desc);
        return method;
    }

    private static FieldNode field(ClassNode node, String name) {
        for (FieldNode field : node.fields) if (name.equals(field.name)) return field;
        return null;
    }

    private static void requireMarker(FieldNode marker) {
        if (marker == null
                || marker.access != (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC
                | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC)
                || !"Ljava/lang/String;".equals(marker.desc)
                || !MARKER_VALUE.equals(marker.value)) {
            throw new StructuralMismatch("owned marker is missing or malformed");
        }
    }

    private boolean runtimeVisibleFrom(ClassLoader loader) {
        if (runtimeLoader == null) return true;
        for (ClassLoader current = loader; current != null; current = current.getParent())
            if (current == runtimeLoader) return true;
        return false;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM8);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static void record(String status) {
        System.setProperty("starsector.prepatcher.aotdMarketOpenContextPatch", status);
        System.setProperty(
                "starsector.prepatcher.planetConditionMarketOpenNoGlobalEconomyStepPatch",
                status);
    }

    private static String loaderName(ClassLoader loader) {
        if (loader == null) return "bootstrap";
        return loader.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(loader));
    }

    private static final class LoaderNeutralClassWriter extends ClassWriter {
        LoaderNeutralClassWriter(int flags) { super(flags); }
        @Override protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
        }
    }

    private static final class StructuralMismatch extends RuntimeException {
        StructuralMismatch(String message) { super(message); }
    }
}
