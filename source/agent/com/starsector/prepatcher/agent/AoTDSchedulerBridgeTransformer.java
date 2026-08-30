package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;

/** Rewrites the fork-owned no-op bridge to direct target-loader ABI calls. */
final class AoTDSchedulerBridgeTransformer implements ClassFileTransformer {
    static final String TARGET = "data/kaysaar/aotd/tot/compat/SchedulerBridge";
    private static final String STATE_DESC =
            "Ldata/kaysaar/aotd/tot/compat/SchedulerBridge$State;";
    private static final String INITIALIZE_DESC = "()" + STATE_DESC;
    private static final String ACTIVATE_DESC = "(J)" + STATE_DESC;
    private static final String RUNTIME_BRIDGE =
            "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge";
    private static final String PATCH_FIELD = "smo$patched$aotdSchedulerBridge";
    private static final String PATCH_VALUE = "StarsectorPrepatcher:aotd-bridge-v10";
    private static final int CURRENT_BRIDGE_SCHEMA = 10;
    private static final String CURRENT_BRIDGE_MARKER = "AOTD_SCHEDULER_BRIDGE_V10";

    private final ClassLoader runtimeLoader;

    AoTDSchedulerBridgeTransformer(ClassLoader runtimeLoader) {
        this.runtimeLoader = runtimeLoader;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!TARGET.equals(className)) return null;
        if (!runtimeVisibleFrom(loader)) {
            record("SKIPPED_LOADER");
            PrepatcherLog.warn("AoTD bridge not patched: bridge loader=" + loaderName(loader)
                    + ", runtime loader=" + loaderName(runtimeLoader));
            return null;
        }
        try {
            if (containsForbiddenReflectionReference(classfileBuffer)) {
                record("SKIPPED_REFLECTION_PRESENT");
                PrepatcherLog.warn("AoTD bridge not patched: reflection reference present.");
                return null;
            }
            ClassNode node = new ClassNode();
            new ClassReader(classfileBuffer).accept(node, 0);
            if (!TARGET.equals(node.name)) return null;
            if (fieldValue(node, PATCH_FIELD) != null) {
                record("ALREADY_PATCHED");
                return null;
            }
            Object schemaValue = fieldValue(node, "BRIDGE_SCHEMA");
            Object markerValue = fieldValue(node, "BRIDGE_MARKER");
            if (!Integer.valueOf(CURRENT_BRIDGE_SCHEMA).equals(schemaValue)
                    || !CURRENT_BRIDGE_MARKER.equals(markerValue)) {
                record("UNSUPPORTED_CONTRACT");
                PrepatcherLog.warn("Unsupported AoTD Scheduler Bridge: expected schema="
                        + CURRENT_BRIDGE_SCHEMA + ", marker=" + CURRENT_BRIDGE_MARKER
                        + "; actual schema=" + schemaValue + ", marker=" + markerValue);
                return null;
            }
            MethodNode initialize = require(node, "initialize", INITIALIZE_DESC);
            require(node, "activateFromPrepatcher", ACTIVATE_DESC);
            require(node, "deliverySignalConsumer", "()Ljava/util/function/Consumer;");
            require(node, "deficitResolverFunction", "()Ljava/util/function/BiFunction;");
            require(node, "economyRestoreCompleteSignal", "()Ljava/lang/Runnable;");
            rewrite(initialize, buildInitializeBody(), 7, 0);
            rewrite(require(node, "getDeliveredMarketGeneration",
                    "(Ljava/lang/Object;)J"), directObjectLong(
                    "getAoTDMarketDeliveredGeneration"), 1, 1);
            rewrite(require(node, "getLastMarketDeliverySequence",
                    "(Ljava/lang/Object;)J"), directObjectLong(
                    "getAoTDMarketLastDeliverySequence"), 1, 1);
            rewrite(require(node, "getLastMarketDeliveredAmount",
                    "(Ljava/lang/Object;)F"), directObjectFloat(
                    "getAoTDMarketLastDeliveredAmount"), 1, 1);
            rewrite(require(node, "getMarketStructuralGeneration",
                    "(Ljava/lang/Object;)J"), directObjectLong(
                    "getAoTDMarketStructuralGeneration"), 1, 1);
            rewrite(require(node, "beforeMarketMutation",
                    "(Ljava/lang/Object;I)J"), directBeforeMutation(), 2, 2);
            rewrite(require(node, "afterMarketMutation",
                    "(JLjava/lang/Object;IJ)V"), directAfterMutation(), 6, 6);
            rewrite(require(node, "publishRuntimeEpoch",
                    "(JJ)V"), directPublishRuntimeEpoch(), 4, 4);
            rewrite(require(node, "getRuntimeCapabilities",
                    "()J"), directNoArgLong("getAoTDNegotiatedCapabilities"), 2, 0);
            rewrite(require(node, "beforeGlobalBoundary",
                    "(IZ)J"), directBeforeGlobalBoundary(), 2, 2);
            rewrite(require(node, "afterGlobalBoundary",
                    "(JJ)V"), directAfterGlobalBoundary(), 4, 4);

            node.fields.add(new FieldNode(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
                            | Opcodes.ACC_SYNTHETIC,
                    PATCH_FIELD, "Ljava/lang/String;", null, PATCH_VALUE));
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            record("APPLIED");
            System.setProperty("starsector.prepatcher.aotdBridgeLoader", loaderName(loader));
            return writer.toByteArray();
        } catch (Throwable failure) {
            record("SKIPPED_ERROR");
            PrepatcherLog.error("AoTD bridge transformation failed open.", failure);
            return null;
        }
    }

    private static InsnList buildInitializeBody() {
        InsnList code = new InsnList();
        String contract = "data/kaysaar/aotd/tot/compat/PrepatcherContract";
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, contract, "MOD_ID", "Ljava/lang/String;"));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, contract, "FORK_VERSION", "Ljava/lang/String;"));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, contract, "DECLARED_CAPABILITIES", "J"));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET,
                "deliverySignalConsumer", "()Ljava/util/function/Consumer;", false));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET,
                "deficitResolverFunction", "()Ljava/util/function/BiFunction;", false));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET,
                "economyRestoreCompleteSignal", "()Ljava/lang/Runnable;", false));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_BRIDGE,
                "registerAoTDForkContract",
                "(Ljava/lang/String;Ljava/lang/String;JLjava/util/function/Consumer;"
                        + "Ljava/util/function/BiFunction;Ljava/lang/Runnable;)J", false));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET,
                "activateFromPrepatcher", ACTIVATE_DESC, false));
        code.add(new InsnNode(Opcodes.ARETURN));
        return code;
    }

    private static InsnList directNoArgLong(String name) {
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_BRIDGE, name,
                "()J", false));
        code.add(new InsnNode(Opcodes.LRETURN));
        return code;
    }

    private static InsnList directObjectLong(String name) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_BRIDGE, name,
                "(Ljava/lang/Object;)J", false));
        code.add(new InsnNode(Opcodes.LRETURN));
        return code;
    }

    private static InsnList directObjectFloat(String name) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_BRIDGE, name,
                "(Ljava/lang/Object;)F", false));
        code.add(new InsnNode(Opcodes.FRETURN));
        return code;
    }

    private static InsnList directBeforeMutation() {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_BRIDGE,
                "beforeAoTDMarketMutation", "(Ljava/lang/Object;I)J", false));
        code.add(new InsnNode(Opcodes.LRETURN));
        return code;
    }

    private static InsnList directAfterMutation() {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.LLOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new VarInsnNode(Opcodes.LLOAD, 4));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_BRIDGE,
                "afterAoTDMarketMutation", "(JLjava/lang/Object;IJ)V", false));
        // Preserve the fork-local dirty queue update after the runtime committed
        // structural generation. This is a direct same-loader call, not reflection.
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new VarInsnNode(Opcodes.LLOAD, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_BRIDGE,
                "getAoTDMarketStructuralGeneration", "(Ljava/lang/Object;)J", false));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET,
                "acceptMarketMutation", "(Ljava/lang/Object;IJJ)V", false));
        code.add(new InsnNode(Opcodes.RETURN));
        return code;
    }


    private static InsnList directPublishRuntimeEpoch() {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.LLOAD, 0));
        code.add(new VarInsnNode(Opcodes.LLOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_BRIDGE,
                "publishAoTDRuntimeEpoch", "(JJ)V", false));
        code.add(new InsnNode(Opcodes.RETURN));
        return code;
    }

    private static InsnList directBeforeGlobalBoundary() {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ILOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_BRIDGE,
                "beforeAoTDGlobalBoundary", "(IZ)J", false));
        code.add(new InsnNode(Opcodes.LRETURN));
        return code;
    }

    private static InsnList directAfterGlobalBoundary() {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.LLOAD, 0));
        code.add(new VarInsnNode(Opcodes.LLOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_BRIDGE,
                "afterAoTDGlobalBoundary", "(JJ)V", false));
        code.add(new InsnNode(Opcodes.RETURN));
        return code;
    }
    private static void rewrite(MethodNode method, InsnList body, int maxStack, int maxLocals) {
        if ((method.access & Opcodes.ACC_STATIC) == 0)
            throw new IllegalStateException("Bridge ABI method is not static: " + method.name);
        method.instructions = body;
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) method.localVariables.clear();
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        method.maxStack = maxStack;
        method.maxLocals = maxLocals;
    }

    private static MethodNode find(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods)
            if (name.equals(method.name) && desc.equals(method.desc)) return method;
        return null;
    }

    private static MethodNode require(ClassNode node, String name, String desc) {
        MethodNode method = find(node, name, desc);
        if (method != null) return method;
        throw new IllegalStateException("Missing bridge ABI method " + name + desc);
    }

    private boolean runtimeVisibleFrom(ClassLoader loader) {
        if (runtimeLoader == null) return true;
        for (ClassLoader current = loader; current != null; current = current.getParent())
            if (current == runtimeLoader) return true;
        return false;
    }

    private static Object fieldValue(ClassNode node, String name) {
        for (FieldNode field : node.fields) if (name.equals(field.name)) return field.value;
        return null;
    }

    private static boolean containsForbiddenReflectionReference(byte[] bytes) {
        String constants = new String(bytes, StandardCharsets.ISO_8859_1);
        return constants.contains("java/lang/reflect") || constants.contains("java.lang.reflect");
    }

    private static void record(String status) {
        System.setProperty("starsector.prepatcher.aotdBridgePatch", status);
    }

    private static String loaderName(ClassLoader loader) {
        if (loader == null) return "bootstrap";
        return loader.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(loader));
    }
}
