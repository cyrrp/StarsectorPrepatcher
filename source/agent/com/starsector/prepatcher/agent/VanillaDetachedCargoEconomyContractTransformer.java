package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

/**
 * Proves that the current vanilla Economy tripleStep is only three nextStep
 * calls and that getEconomy returns the exact ReachEconomy field. No bytecode
 * is changed; the result gates the detached-Cargo vanilla skip at runtime.
 */
final class VanillaDetachedCargoEconomyContractTransformer
        implements ClassFileTransformer {
    static final String TARGET = "com/fs/starfarer/campaign/econ/Economy";
    private static final String REACH_DESC =
            "Lcom/fs/starfarer/campaign/econ/reach/ReachEconomy;";
    private static final String RUNTIME =
            "com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge";

    private final boolean enabled;
    private final ClassLoader runtimeLoader;

    VanillaDetachedCargoEconomyContractTransformer(
            boolean enabled, ClassLoader runtimeLoader) {
        this.enabled = enabled;
        this.runtimeLoader = runtimeLoader;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (!enabled || !TARGET.equals(className)) return null;
        if (!runtimeVisibleFrom(loader)) {
            reject("SKIPPED_LOADER", "target loader cannot see runtime bridge");
            return null;
        }
        try {
            ClassNode node = read(classfileBuffer);
            validate(node);
            if (!publishRuntime(true)) {
                record("SKIPPED_RUNTIME");
                return null;
            }
            record("READY");
            PrepatcherLog.info("READY campaignCargoNoGlobalEconomyStep vanilla contract: "
                    + "Economy.tripleStep=3x nextStep, getEconomy=exact ReachEconomy");
        } catch (StructuralMismatch mismatch) {
            reject("SKIPPED_STRUCTURAL", mismatch.getMessage());
        } catch (Throwable failure) {
            publishRuntime(false);
            record("SKIPPED_ERROR");
            PrepatcherLog.error("SKIPPED_ERROR vanilla detached-Cargo economy contract; "
                    + "original tripleStep remains active", failure);
        }
        return null;
    }

    private static void validate(ClassNode node) {
        if (!TARGET.equals(node.name)) {
            throw new StructuralMismatch("unexpected owner " + node.name);
        }
        FieldNode economyField = null;
        for (FieldNode field : node.fields) {
            if (REACH_DESC.equals(field.desc)) {
                if (economyField != null) {
                    throw new StructuralMismatch("multiple ReachEconomy fields");
                }
                economyField = field;
            }
        }
        if (economyField == null || (economyField.access & Opcodes.ACC_STATIC) != 0) {
            throw new StructuralMismatch("exact ReachEconomy instance field missing");
        }

        MethodNode getter = requireMethod(node, "getEconomy", "()" + REACH_DESC);
        requirePublicInstance(getter, "getEconomy");
        List<AbstractInsnNode> getterInstructions = meaningful(getter);
        if (getterInstructions.size() != 3) {
            throw new StructuralMismatch("getEconomy instruction count changed");
        }
        requireLoadThis(getterInstructions.get(0), "getEconomy receiver");
        if (!(getterInstructions.get(1) instanceof FieldInsnNode fieldRead)
                || fieldRead.getOpcode() != Opcodes.GETFIELD
                || !node.name.equals(fieldRead.owner)
                || !economyField.name.equals(fieldRead.name)
                || !REACH_DESC.equals(fieldRead.desc)
                || getterInstructions.get(2).getOpcode() != Opcodes.ARETURN) {
            throw new StructuralMismatch("getEconomy no longer returns the exact field");
        }

        MethodNode triple = requireMethod(node, "tripleStep", "()V");
        requirePublicInstance(triple, "tripleStep");
        if (triple.tryCatchBlocks != null && !triple.tryCatchBlocks.isEmpty()) {
            throw new StructuralMismatch("tripleStep gained exception regions");
        }
        List<AbstractInsnNode> instructions = meaningful(triple);
        if (instructions.size() != 7) {
            throw new StructuralMismatch("tripleStep instruction count changed");
        }
        for (int i = 0; i < 3; i++) {
            requireLoadThis(instructions.get(i * 2), "tripleStep receiver " + i);
            AbstractInsnNode candidate = instructions.get(i * 2 + 1);
            if (!(candidate instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !node.name.equals(call.owner)
                    || !"nextStep".equals(call.name)
                    || !"()V".equals(call.desc)) {
                throw new StructuralMismatch(
                        "tripleStep call " + i + " is not exact nextStep()V");
            }
        }
        if (instructions.get(6).getOpcode() != Opcodes.RETURN) {
            throw new StructuralMismatch("tripleStep no longer ends with RETURN");
        }
    }

    private void reject(String status, String reason) {
        publishRuntime(false);
        record(status);
        PrepatcherLog.warn(status + " vanilla detached-Cargo economy contract: "
                + reason + "; original tripleStep remains active");
    }

    private boolean publishRuntime(boolean operational) {
        if (runtimeLoader == null) return true;
        try {
            Class<?> bridge = Class.forName(RUNTIME, false, runtimeLoader);
            Method publish = bridge.getMethod(
                    "setVanillaDetachedCargoEconomyContract",
                    boolean.class, String.class);
            publish.invoke(null, operational, null);
            return true;
        } catch (Throwable failure) {
            PrepatcherLog.warn("Could not publish vanilla detached-Cargo economy contract: "
                    + failure.getClass().getName());
            return false;
        }
    }

    private boolean runtimeVisibleFrom(ClassLoader loader) {
        if (runtimeLoader == null) return true;
        for (ClassLoader current = loader; current != null; current = current.getParent()) {
            if (current == runtimeLoader) return true;
        }
        return false;
    }

    private static void requirePublicInstance(MethodNode method, String label) {
        if ((method.access & Opcodes.ACC_PUBLIC) == 0
                || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
                | Opcodes.ACC_NATIVE)) != 0) {
            throw new StructuralMismatch(label + " access changed");
        }
    }

    private static void requireLoadThis(AbstractInsnNode instruction, String label) {
        if (!(instruction instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD || load.var != 0) {
            throw new StructuralMismatch(label + " changed");
        }
    }

    private static MethodNode requireMethod(ClassNode node, String name, String desc) {
        MethodNode result = null;
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) {
                if (result != null) {
                    throw new StructuralMismatch("duplicate method " + name + desc);
                }
                result = method;
            }
        }
        if (result == null) throw new StructuralMismatch("missing method " + name + desc);
        return result;
    }

    private static List<AbstractInsnNode> meaningful(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() >= 0) result.add(instruction);
        }
        return result;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM8);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static void record(String status) {
        System.setProperty(
                "starsector.prepatcher.detachedCargoVanillaEconomyContract", status);
    }

    private static final class StructuralMismatch extends RuntimeException {
        StructuralMismatch(String message) { super(message); }
    }
}
