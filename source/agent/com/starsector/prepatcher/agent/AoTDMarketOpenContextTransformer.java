package com.starsector.prepatcher.agent;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.FieldNode;
import jdk.internal.org.objectweb.asm.tree.InsnList;
import jdk.internal.org.objectweb.asm.tree.InsnNode;
import jdk.internal.org.objectweb.asm.tree.LabelNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.TryCatchBlockNode;
import jdk.internal.org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Publishes the market argument before CampaignEngine invokes Economy.nextStep().
 * Vanilla stores currentlyOpenMarket only after that call, which otherwise makes
 * the AoTD fork select its all-market synchronous branch.
 */
final class AoTDMarketOpenContextTransformer implements ClassFileTransformer {
    static final String TARGET = "com/fs/starfarer/campaign/CampaignEngine";
    private static final String METHOD = "reportPlayerOpenedMarket";
    private static final String DESC =
            "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V";
    private static final String RAW = "spp$raw$reportPlayerOpenedMarket";
    private static final String RUNTIME =
            "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge";
    private static final String MARKER = "spp$patched$aotdMarketOpenContext";
    private static final String MARKER_VALUE =
            "StarsectorPrepatcher:aotd-market-open-context-v1";

    private final boolean enabled;
    private final ClassLoader runtimeLoader;

    AoTDMarketOpenContextTransformer(boolean enabled, ClassLoader runtimeLoader) {
        this.enabled = enabled;
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
            if (method(node, RAW, DESC) != null) {
                throw new StructuralMismatch("raw helper exists without owned marker");
            }

            MethodNode original = requireMethod(node, METHOD, DESC);
            requireOriginalShape(original);
            installWrapper(node, original);
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
                    + ": early market argument + finally-cleared runtime context");
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

    private static void installWrapper(ClassNode node, MethodNode original) {
        int wrapperAccess = original.access;
        String signature = original.signature;
        String[] exceptions = original.exceptions == null
                ? null : original.exceptions.toArray(String[]::new);

        original.name = RAW;
        original.access = (original.access
                & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_FINAL
                | Opcodes.ACC_SYNCHRONIZED))
                | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;

        MethodNode wrapper = new MethodNode(Opcodes.ASM8, wrapperAccess,
                METHOD, DESC, signature, exceptions);
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode handler = new LabelNode();
        InsnList code = wrapper.instructions;

        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "beginAoTDOpeningMarket", "(Ljava/lang/Object;)J", false));
        code.add(new VarInsnNode(Opcodes.LSTORE, 2));

        code.add(tryStart);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, RAW, DESC, false));
        code.add(tryEnd);
        code.add(new VarInsnNode(Opcodes.LLOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "endAoTDOpeningMarket", "(J)V", false));
        code.add(new InsnNode(Opcodes.RETURN));

        code.add(handler);
        code.add(new VarInsnNode(Opcodes.ASTORE, 4));
        code.add(new VarInsnNode(Opcodes.LLOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "endAoTDOpeningMarket", "(J)V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new InsnNode(Opcodes.ATHROW));
        wrapper.tryCatchBlocks.add(new TryCatchBlockNode(
                tryStart, tryEnd, handler, "java/lang/Throwable"));
        node.methods.add(wrapper);
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
        requireCount("runtime begin calls", countCalls(method, RUNTIME,
                "beginAoTDOpeningMarket", "(Ljava/lang/Object;)J"), 0);
        requireCount("runtime end calls", countCalls(method, RUNTIME,
                "endAoTDOpeningMarket", "(J)V"), 0);

        int nextStep = instructionIndex(method, "com/fs/starfarer/campaign/econ/Economy",
                "nextStep", "()V");
        int publish = instructionIndex(method, TARGET, "setCurrentlyOpenMarket",
                "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V");
        if (nextStep < 0 || publish < 0 || nextStep >= publish) {
            throw new StructuralMismatch(
                    "vanilla nextStep-before-currentlyOpenMarket ordering changed");
        }
    }

    private static void requirePatchedShape(ClassNode node) {
        requireMarker(field(node, MARKER));
        MethodNode wrapper = requireMethod(node, METHOD, DESC);
        MethodNode raw = requireMethod(node, RAW, DESC);
        requireOriginalBodyShape(raw);
        if ((raw.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC))
                != (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC)
                || (raw.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED
                | Opcodes.ACC_STATIC)) != 0) {
            throw new StructuralMismatch("raw helper access changed");
        }
        requireCount("wrapper begin", countCalls(wrapper, RUNTIME,
                "beginAoTDOpeningMarket", "(Ljava/lang/Object;)J"), 1);
        requireCount("wrapper end", countCalls(wrapper, RUNTIME,
                "endAoTDOpeningMarket", "(J)V"), 2);
        requireCount("wrapper raw call", countCalls(wrapper, node.name, RAW, DESC), 1);
        requireCount("wrapper try/finally", wrapper.tryCatchBlocks.size(), 1);
        requireCount("wrapper returns", countOpcode(wrapper, Opcodes.RETURN), 1);
        requireCount("wrapper rethrows", countOpcode(wrapper, Opcodes.ATHROW), 1);
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

    private static int countOpcode(MethodNode method, int opcode) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions)
            if (instruction.getOpcode() == opcode) count++;
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
