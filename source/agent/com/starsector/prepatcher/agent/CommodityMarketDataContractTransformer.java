package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;

/**
 * Exact-current-game semantic gate for filtered CommodityMarketData
 * reconstruction. It changes no bytecode. Structural drift leaves the original
 * doubleStep/tripleStep paths active.
 */
final class CommodityMarketDataContractTransformer
        implements ClassFileTransformer {
    static final String TARGET =
            "com/fs/starfarer/campaign/econ/reach/CommodityMarketData";
    private static final String RUNTIME =
            "com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge";
    private static final String CONSTRUCTOR =
            "(Ljava/lang/String;Ljava/lang/String;)V";

    private final boolean enabled;
    private final ClassLoader runtimeLoader;

    CommodityMarketDataContractTransformer(
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
            ClassNode node = new ClassNode();
            new ClassReader(classfileBuffer).accept(node, 0);
            validate(node);
            if (!publish(true, null)) {
                record("SKIPPED_RUNTIME");
                return null;
            }
            record("READY");
            PrepatcherLog.info("READY vanilla affected-commodity commit contract");
        } catch (StructuralMismatch mismatch) {
            reject("SKIPPED_STRUCTURAL", mismatch.getMessage());
        } catch (Throwable failure) {
            publish(false, failure.getClass().getName());
            record("SKIPPED_ERROR");
            PrepatcherLog.error("SKIPPED_ERROR vanilla affected-commodity commit contract; "
                    + "original global economy steps remain active", failure);
        }
        return null;
    }

    private static void validate(ClassNode node) {
        if (!TARGET.equals(node.name)) {
            throw new StructuralMismatch("unexpected owner " + node.name);
        }
        MethodNode constructor = null;
        for (MethodNode method : node.methods) {
            if ("<init>".equals(method.name) && CONSTRUCTOR.equals(method.desc)) {
                if (constructor != null) {
                    throw new StructuralMismatch("duplicate String/String constructor");
                }
                constructor = method;
            }
        }
        if (constructor == null || (constructor.access & Opcodes.ACC_PUBLIC) == 0
                || (constructor.access & Opcodes.ACC_STATIC) != 0) {
            throw new StructuralMismatch("public String/String constructor missing");
        }
        if (constructor.tryCatchBlocks != null && !constructor.tryCatchBlocks.isEmpty()) {
            throw new StructuralMismatch("constructor gained exception regions");
        }
        requireCount(constructor, Opcodes.INVOKESTATIC,
                "com/fs/starfarer/api/Global", "getSector",
                "()Lcom/fs/starfarer/api/campaign/SectorAPI;", 1,
                "Global.getSector");
        requireCount(constructor, Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/SectorAPI", "getEconomy",
                "()Lcom/fs/starfarer/api/campaign/econ/EconomyAPI;", 1,
                "SectorAPI.getEconomy");
        requireCount(constructor, Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/econ/EconomyAPI", "getMarketsInGroup",
                "(Ljava/lang/String;)Ljava/util/List;", 1,
                "EconomyAPI.getMarketsInGroup");
        requireCount(constructor, Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/econ/MarketAPI", "getCommodityData",
                "(Ljava/lang/String;)Lcom/fs/starfarer/api/campaign/econ/CommodityOnMarketAPI;",
                2, "MarketAPI.getCommodityData");
        requireCallArgument(constructor, Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/econ/EconomyAPI", "getMarketsInGroup",
                "(Ljava/lang/String;)Ljava/util/List;", 2, "econ-group argument");
        requireEveryCallArgument(constructor, Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/econ/MarketAPI", "getCommodityData",
                "(Ljava/lang/String;)Lcom/fs/starfarer/api/campaign/econ/CommodityOnMarketAPI;",
                1, "commodity-id argument");
        requireFieldAssignment(constructor, "commodityId", 1);
        requireFieldAssignment(constructor, "econGroup", 2);
        requireCount(constructor, Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/econ/CommodityOnMarketAPI",
                "updateMaxSupplyAndDemand", "()V", 1,
                "CommodityOnMarketAPI.updateMaxSupplyAndDemand");
        requireCount(constructor, Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/campaign/econ/CommodityOnMarket",
                "setCommodityMarketData",
                "(Lcom/fs/starfarer/campaign/econ/reach/CommodityMarketData;)V",
                1, "CommodityOnMarket.setCommodityMarketData");
        requireCount(constructor, Opcodes.INVOKEVIRTUAL, TARGET,
                "adjustMarketShare", "(Ljava/util/List;)V", 1,
                "adjustMarketShare");
    }

    private static void requireCount(MethodNode method, int opcode, String owner,
                                     String name, String desc, int expected,
                                     String label) {
        int actual = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == opcode
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && desc.equals(call.desc)) {
                actual++;
            }
        }
        if (actual != expected) {
            throw new StructuralMismatch(label + " count changed: expected "
                    + expected + ", found " + actual);
        }
    }

    private static void requireCallArgument(MethodNode method, int opcode, String owner,
                                            String name, String desc, int local,
                                            String label) {
        MethodInsnNode match = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == opcode && owner.equals(call.owner)
                    && name.equals(call.name) && desc.equals(call.desc)) {
                if (match != null) throw new StructuralMismatch(label + " call is not unique");
                match = call;
            }
        }
        if (match == null || !isAload(previousMeaningful(match), local)) {
            throw new StructuralMismatch(label + " no longer flows from local " + local);
        }
    }

    private static void requireEveryCallArgument(MethodNode method, int opcode, String owner,
                                                 String name, String desc, int local,
                                                 String label) {
        int matches = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == opcode && owner.equals(call.owner)
                    && name.equals(call.name) && desc.equals(call.desc)) {
                matches++;
                if (!isAload(previousMeaningful(call), local)) {
                    throw new StructuralMismatch(label + " no longer flows from local " + local);
                }
            }
        }
        if (matches == 0) throw new StructuralMismatch(label + " calls missing");
    }

    private static void requireFieldAssignment(MethodNode method, String fieldName, int local) {
        FieldInsnNode match = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && TARGET.equals(field.owner) && fieldName.equals(field.name)
                    && "Ljava/lang/String;".equals(field.desc)) {
                if (match != null) {
                    throw new StructuralMismatch(fieldName + " assignment is not unique");
                }
                match = field;
            }
        }
        AbstractInsnNode value = previousMeaningful(match);
        AbstractInsnNode receiver = previousMeaningful(value);
        if (!isAload(value, local) || !isAload(receiver, 0)) {
            throw new StructuralMismatch(fieldName + " assignment flow changed");
        }
    }

    private static boolean isAload(AbstractInsnNode instruction, int local) {
        return instruction instanceof VarInsnNode variable
                && variable.getOpcode() == Opcodes.ALOAD && variable.var == local;
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private void reject(String status, String reason) {
        publish(false, reason);
        record(status);
        PrepatcherLog.warn(status + " vanilla affected-commodity commit contract: "
                + reason + "; original global economy steps remain active");
    }

    private boolean publish(boolean operational, String reason) {
        if (runtimeLoader == null) return true;
        try {
            Class<?> bridge = Class.forName(RUNTIME, false, runtimeLoader);
            Method method = bridge.getMethod(
                    "setCommodityMarketDataContract",
                    boolean.class, String.class);
            method.invoke(null, operational, reason);
            return true;
        } catch (Throwable failure) {
            PrepatcherLog.warn("Could not publish affected-commodity contract: "
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

    private static void record(String status) {
        System.setProperty(
                "starsector.prepatcher.commodityMarketDataContract",
                status);
    }

    private static final class StructuralMismatch extends RuntimeException {
        StructuralMismatch(String message) { super(message); }
    }
}
