package com.starsector.prepatcher.agent;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.FieldInsnNode;
import jdk.internal.org.objectweb.asm.tree.FieldNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.TypeInsnNode;
import jdk.internal.org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Exact-current-game semantic gate for the synchronous vanilla live-market
 * localization. It changes no bytecode and publishes Economy/ReachEconomy
 * readiness independently so load order cannot create a partial activation.
 */
final class VanillaMarketOpenLocalizationContractTransformer
        implements ClassFileTransformer {
    static final String ECONOMY = "com/fs/starfarer/campaign/econ/Economy";
    static final String REACH =
            "com/fs/starfarer/campaign/econ/reach/ReachEconomy";
    static final Set<String> TARGET_CLASSES = Set.of(ECONOMY, REACH);
    private static final String PARAMS =
            "com/fs/starfarer/campaign/econ/reach/MainWorkTask$EconWorkParams";
    private static final String PARAMS_DESC = "L" + PARAMS + ";";
    private static final String RUNTIME =
            "com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge";

    private final boolean enabled;
    private final ClassLoader runtimeLoader;
    private volatile boolean economyReady;
    private volatile boolean reachReady;

    VanillaMarketOpenLocalizationContractTransformer(
            boolean enabled, ClassLoader runtimeLoader) {
        this.enabled = enabled;
        this.runtimeLoader = runtimeLoader;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (!enabled || (!ECONOMY.equals(className) && !REACH.equals(className))) {
            return null;
        }
        if (!runtimeVisibleFrom(loader)) {
            reject(className, "SKIPPED_LOADER",
                    "target loader cannot see runtime bridge");
            return null;
        }
        try {
            ClassNode node = read(classfileBuffer);
            if (ECONOMY.equals(className)) {
                validateEconomy(node);
                economyReady = true;
                if (!publishRuntime(true, true, null)) {
                    record(className, "SKIPPED_RUNTIME");
                    return null;
                }
            } else {
                validateReach(node);
                reachReady = true;
                if (!publishRuntime(false, true, null)) {
                    record(className, "SKIPPED_RUNTIME");
                    return null;
                }
            }
            record(className, "READY");
            recordAggregate();
            PrepatcherLog.info("READY vanilla market-open localization contract: "
                    + className);
        } catch (StructuralMismatch mismatch) {
            reject(className, "SKIPPED_STRUCTURAL", mismatch.getMessage());
        } catch (Throwable failure) {
            publishRuntime(ECONOMY.equals(className), false,
                    failure.getClass().getName());
            record(className, "SKIPPED_ERROR");
            recordAggregate();
            PrepatcherLog.error("SKIPPED_ERROR vanilla market-open localization "
                    + "contract for " + className
                    + "; original global steps remain active", failure);
        }
        return null;
    }

    private static void validateEconomy(ClassNode node) {
        if (!ECONOMY.equals(node.name)) {
            throw new StructuralMismatch("unexpected Economy owner " + node.name);
        }
        String reachDesc = "L" + REACH + ";";
        FieldNode reachField = null;
        for (FieldNode field : node.fields) {
            if (!reachDesc.equals(field.desc)) continue;
            if (reachField != null) {
                throw new StructuralMismatch("multiple ReachEconomy fields");
            }
            reachField = field;
        }
        if (reachField == null || (reachField.access & Opcodes.ACC_STATIC) != 0) {
            throw new StructuralMismatch("exact ReachEconomy instance field missing");
        }

        MethodNode getter = requireMethod(node, "getEconomy", "()" + reachDesc);
        requirePublicInstance(getter, "getEconomy");
        List<AbstractInsnNode> getterBody = meaningful(getter);
        if (getterBody.size() != 3) {
            throw new StructuralMismatch("getEconomy instruction count changed");
        }
        requireLoad(getterBody.get(0), Opcodes.ALOAD, 0, "getEconomy receiver");
        if (!(getterBody.get(1) instanceof FieldInsnNode read)
                || read.getOpcode() != Opcodes.GETFIELD
                || !node.name.equals(read.owner)
                || !reachField.name.equals(read.name)
                || !reachDesc.equals(read.desc)
                || getterBody.get(2).getOpcode() != Opcodes.ARETURN) {
            throw new StructuralMismatch("getEconomy no longer returns exact field");
        }

        MethodNode noArg = requireMethod(node, "nextStep", "()V");
        requirePublicInstance(noArg, "nextStep()V");
        List<AbstractInsnNode> noArgBody = meaningful(noArg);
        if (noArgBody.size() != 4) {
            throw new StructuralMismatch("nextStep() delegate body changed");
        }
        requireLoad(noArgBody.get(0), Opcodes.ALOAD, 0, "nextStep receiver");
        if (noArgBody.get(1).getOpcode() != Opcodes.ACONST_NULL
                || !(noArgBody.get(2) instanceof MethodInsnNode delegate)
                || delegate.getOpcode() != Opcodes.INVOKEVIRTUAL
                || !ECONOMY.equals(delegate.owner)
                || !"nextStep".equals(delegate.name)
                || !("(" + PARAMS_DESC + ")V").equals(delegate.desc)
                || noArgBody.get(3).getOpcode() != Opcodes.RETURN) {
            throw new StructuralMismatch("nextStep() is not exact null-params delegate");
        }

        MethodNode step = requireMethod(
                node, "nextStep", "(" + PARAMS_DESC + ")V");
        requirePublicInstance(step, "nextStep(params)");
        requireNoTryCatch(step, "nextStep(params)");
        requireCount("Market.updatePrevStability",
                countCalls(step, Opcodes.INVOKEVIRTUAL,
                        "com/fs/starfarer/campaign/econ/Market",
                        "updatePrevStability", "()V"), 1);
        requireCount("EconWorkParams allocation", countNew(step, PARAMS), 1);
        requireCount("withIncomeAndUpkeep writes",
                countFieldWrites(step, PARAMS, "withIncomeAndUpkeep", "Z"), 1);
        requireCount("withStockpileUpdate writes",
                countFieldWrites(step, PARAMS, "withStockpileUpdate", "Z"), 1);
        requireCount("withImmigration writes",
                countFieldWrites(step, PARAMS, "withImmigration", "Z"), 1);
        requireCount("ReachEconomy.nextStep",
                countCalls(step, Opcodes.INVOKEVIRTUAL, REACH, "nextStep",
                        "(" + PARAMS_DESC + ")V"), 1);

        MethodNode stockpile = requireMethod(node, "forceStockpileUpdate",
                "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V");
        requirePublicInstance(stockpile, "forceStockpileUpdate");
        requireNoTryCatch(stockpile, "forceStockpileUpdate");
        requireCount("MarketAPI.getAllCommodities",
                countCalls(stockpile, Opcodes.INVOKEINTERFACE,
                        "com/fs/starfarer/api/campaign/econ/MarketAPI",
                        "getAllCommodities", "()Ljava/util/List;"), 1);
        requireCount("CommodityOnMarketAPI.isNonEcon",
                countCalls(stockpile, Opcodes.INVOKEINTERFACE,
                        "com/fs/starfarer/api/campaign/econ/CommodityOnMarketAPI",
                        "isNonEcon", "()Z"), 1);
        requireCount("MainWorkTask2.updateStockpileAndPrice",
                countCallsByName(stockpile, Opcodes.INVOKESTATIC,
                        "com/fs/starfarer/campaign/econ/reach/MainWorkTask2",
                        "updateStockpileAndPrice"), 1);
    }

    private static void validateReach(ClassNode node) {
        if (!REACH.equals(node.name)) {
            throw new StructuralMismatch("unexpected ReachEconomy owner " + node.name);
        }
        MethodNode step = requireMethod(
                node, "nextStep", "(" + PARAMS_DESC + ")V");
        requirePublicInstance(step, "ReachEconomy.nextStep");
        requireNoTryCatch(step, "ReachEconomy.nextStep");

        requireCount("admin stats refresh",
                countCallsByName(step, Opcodes.INVOKEINTERFACE,
                        "com/fs/starfarer/api/characters/MutableCharacterStatsAPI",
                        "refreshCharacterStatsEffects"), 1);
        requireCount("admin governed-market refresh",
                countCallsByName(step, Opcodes.INVOKEINTERFACE,
                        "com/fs/starfarer/api/characters/MutableCharacterStatsAPI",
                        "refreshGovernedOutpostEffects"), 1);

        String main = "com/fs/starfarer/campaign/econ/reach/MainWorkTask2";
        String again = "com/fs/starfarer/campaign/econ/reach/UpdateMarketsAgainTask";
        String immigration = "com/fs/starfarer/campaign/econ/reach/ImmigrationTask";
        String finish = "com/fs/starfarer/campaign/econ/reach/FinishEconomyUpdateTask";
        requireCount("MainWorkTask2 allocation", countNew(step, main), 1);
        requireCount("UpdateMarketsAgainTask allocation", countNew(step, again), 1);
        requireCount("ImmigrationTask allocation", countNew(step, immigration), 1);
        requireCount("FinishEconomyUpdateTask allocation", countNew(step, finish), 1);
        requireCount("MainWorkTask2 constructor",
                countCallsByName(step, Opcodes.INVOKESPECIAL, main, "<init>"), 1);
        requireCount("UpdateMarketsAgainTask constructor",
                countCallsByName(step, Opcodes.INVOKESPECIAL, again, "<init>"), 1);
        requireCount("ImmigrationTask constructor",
                countCallsByName(step, Opcodes.INVOKESPECIAL, immigration, "<init>"), 1);
        requireCount("FinishEconomyUpdateTask constructor",
                countCallsByName(step, Opcodes.INVOKESPECIAL, finish, "<init>"), 1);
        requireCount("MultiFrameTask.doNextBatch loops",
                countCallsByName(step, Opcodes.INVOKEVIRTUAL,
                        "com/fs/starfarer/campaign/econ/contract/iter/MultiFrameTask",
                        "doNextBatch"), 4);
        requireCount("MultiFrameTask.isDone loops",
                countCallsByName(step, Opcodes.INVOKEVIRTUAL,
                        "com/fs/starfarer/campaign/econ/contract/iter/MultiFrameTask",
                        "isDone"), 4);
        requireCount("withImmigration read",
                countFieldReads(step, PARAMS, "withImmigration", "Z"), 1);
        requireCount("forceNonUIStep read",
                countFieldReads(step, PARAMS, "forceNonUIStep", "Z"), 1);

        int mainIndex = firstNewIndex(step, main);
        int againIndex = firstNewIndex(step, again);
        int immigrationIndex = firstNewIndex(step, immigration);
        int finishIndex = firstNewIndex(step, finish);
        if (!(mainIndex < againIndex && againIndex < immigrationIndex
                && immigrationIndex < finishIndex)) {
            throw new StructuralMismatch("ReachEconomy task phase order changed");
        }
    }

    private void reject(String className, String status, String reason) {
        if (ECONOMY.equals(className)) economyReady = false;
        else reachReady = false;
        publishRuntime(ECONOMY.equals(className), false, reason);
        record(className, status);
        recordAggregate();
        PrepatcherLog.warn(status + " vanilla market-open localization contract for "
                + className + ": " + reason
                + "; original global steps remain active");
    }

    private boolean publishRuntime(boolean economy, boolean operational, String reason) {
        if (runtimeLoader == null) return true;
        try {
            Class<?> bridge = Class.forName(RUNTIME, false, runtimeLoader);
            Method publish = bridge.getMethod(
                    economy ? "setVanillaMarketOpenEconomyContract"
                            : "setVanillaMarketOpenReachContract",
                    boolean.class, String.class);
            publish.invoke(null, operational, reason);
            return true;
        } catch (Throwable failure) {
            PrepatcherLog.warn("Could not publish vanilla market-open localization "
                    + "contract: " + failure.getClass().getName());
            return false;
        }
    }

    private void recordAggregate() {
        String status = economyReady && reachReady ? "READY" : "AWAITING";
        System.setProperty(
                "starsector.prepatcher.vanillaMarketOpenLocalizationContract",
                status);
    }

    private static void record(String className, String status) {
        String suffix = ECONOMY.equals(className) ? "Economy" : "Reach";
        System.setProperty(
                "starsector.prepatcher.vanillaMarketOpenLocalization"
                        + suffix + "Contract",
                status);
    }

    private boolean runtimeVisibleFrom(ClassLoader loader) {
        if (runtimeLoader == null) return true;
        for (ClassLoader current = loader; current != null; current = current.getParent()) {
            if (current == runtimeLoader) return true;
        }
        return false;
    }

    private static int firstNewIndex(MethodNode method, String type) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() < 0) continue;
            if (instruction instanceof TypeInsnNode allocation
                    && instruction.getOpcode() == Opcodes.NEW
                    && type.equals(allocation.desc)) {
                return index;
            }
            index++;
        }
        return Integer.MAX_VALUE;
    }

    private static int countNew(MethodNode method, String type) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode allocation
                    && allocation.getOpcode() == Opcodes.NEW
                    && type.equals(allocation.desc)) count++;
        }
        return count;
    }

    private static int countFieldWrites(
            MethodNode method, String owner, String name, String desc) {
        return countFields(method, Opcodes.PUTFIELD, owner, name, desc);
    }

    private static int countFieldReads(
            MethodNode method, String owner, String name, String desc) {
        return countFields(method, Opcodes.GETFIELD, owner, name, desc);
    }

    private static int countFields(
            MethodNode method, int opcode, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == opcode
                    && owner.equals(field.owner) && name.equals(field.name)
                    && desc.equals(field.desc)) count++;
        }
        return count;
    }

    private static int countCalls(
            MethodNode method, int opcode, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == opcode
                    && owner.equals(call.owner) && name.equals(call.name)
                    && desc.equals(call.desc)) count++;
        }
        return count;
    }

    private static int countCallsByName(
            MethodNode method, int opcode, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == opcode
                    && owner.equals(call.owner) && name.equals(call.name)) count++;
        }
        return count;
    }

    private static void requireCount(String label, int actual, int expected) {
        if (actual != expected) {
            throw new StructuralMismatch(label + " expected=" + expected
                    + " actual=" + actual);
        }
    }

    private static void requireNoTryCatch(MethodNode method, String label) {
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
            throw new StructuralMismatch(label + " gained exception regions");
        }
    }

    private static void requirePublicInstance(MethodNode method, String label) {
        if ((method.access & Opcodes.ACC_PUBLIC) == 0
                || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
                | Opcodes.ACC_NATIVE)) != 0) {
            throw new StructuralMismatch(label + " access changed");
        }
    }

    private static void requireLoad(
            AbstractInsnNode instruction, int opcode, int local, String label) {
        if (!(instruction instanceof VarInsnNode load)
                || load.getOpcode() != opcode || load.var != local) {
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

    private static final class StructuralMismatch extends RuntimeException {
        StructuralMismatch(String message) { super(message); }
    }
}
