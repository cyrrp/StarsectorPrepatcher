package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

/**
 * Guards the exact vanilla market-transaction doubleStep call with a runtime
 * fail-open predicate. The original virtual call remains in bytecode and runs
 * whenever transaction inspection or the targeted refresh cannot be proven safe.
 */
final class TradeMarketMutationTransformer implements ClassFileTransformer {
    static final String TARGET = "com/fs/starfarer/campaign/ui/class";
    private static final String METHOD = "confirmTransaction";
    private static final String METHOD_DESC = "()V";
    private static final String MARKET = "com/fs/starfarer/campaign/econ/Market";
    private static final String MARKET_API =
            "com/fs/starfarer/api/campaign/econ/MarketAPI";
    private static final String ECONOMY_API =
            "com/fs/starfarer/api/campaign/econ/EconomyAPI";
    private static final String TRANSACTION =
            "com/fs/starfarer/api/campaign/PlayerMarketTransaction";
    private static final String RUNTIME =
            "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge";
    private static final String LEGACY_MARKET_FIELD = "if.new$class";
    private static final String REPAIRED_MARKET_FIELD = "if_new$class";
    private static final String MARKET_DESC = "L" + MARKET + ";";
    private static final String GUARD =
            "shouldHandleTradeMarketMutationEconomyStep";
    private static final String GUARD_DESC =
            "(L" + ECONOMY_API + ";L" + MARKET_API + ";L" + TRANSACTION + ";)Z";
    private static final String MARKER = "spp$patched$tradeMarketMutationRefresh";
    private static final String MARKER_VALUE =
            "StarsectorPrepatcher:trade-market-mutation-refresh-v2";
    private static final String PREPARE =
            "prepareTradeMarketMutation";
    private static final String PREPARE_DESC = "(L" + MARKET_API + ";)V";
    private static final String TRADE_UI =
            "com/fs/starfarer/campaign/ui/class";
    private static final String TW_CONFIRM = "twConfirm";

    private final boolean enabled;
    private final ClassLoader runtimeLoader;

    TradeMarketMutationTransformer(boolean enabled, ClassLoader runtimeLoader) {
        this.enabled = enabled;
        this.runtimeLoader = runtimeLoader;
    }

    boolean isTargetEnabled(String internalName) {
        return enabled && TARGET.equals(internalName);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (!enabled || !TARGET.equals(className)) return null;
        if (!runtimeVisibleFrom(loader)) {
            record("SKIPPED_LOADER");
            PrepatcherLog.warn("Trade market-mutation refresh not patched: target loader="
                    + loaderName(loader) + ", runtime loader=" + loaderName(runtimeLoader));
            return null;
        }
        try {
            ClassNode node = read(classfileBuffer);
            if (!TARGET.equals(node.name)) {
                throw new StructuralMismatch("unexpected owner " + node.name);
            }
            String marketField = requireMarketFieldName(node);
            FieldNode marker = field(node, MARKER);
            if (marker != null) {
                requireMarker(marker);
                requirePatchedShape(node, marketField);
                record("ALREADY_APPLIED");
                return null;
            }
            requireOriginalShape(node, marketField);
            MethodNode method = requireMethod(node, METHOD, METHOD_DESC);
            MethodInsnNode twConfirm = uniqueCall(
                    method, TRADE_UI, TW_CONFIRM, "()V");
            InsnList prepare = new InsnList();
            prepare.add(new VarInsnNode(Opcodes.ALOAD, 0));
            prepare.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET,
                    marketField, MARKET_DESC));
            prepare.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                    PREPARE, PREPARE_DESC, false));
            method.instructions.insertBefore(twConfirm, prepare);

            MethodInsnNode doubleStep = uniqueCall(
                    method, ECONOMY_API, "doubleStep", "()V");
            int economyLocal = method.maxLocals;
            method.maxLocals = economyLocal + 1;
            LabelNode afterStep = new LabelNode();
            InsnList guard = new InsnList();
            guard.add(new VarInsnNode(Opcodes.ASTORE, economyLocal));
            guard.add(new VarInsnNode(Opcodes.ALOAD, economyLocal));
            guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
            guard.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET,
                    marketField, MARKET_DESC));
            guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
            guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                    GUARD, GUARD_DESC, false));
            guard.add(new JumpInsnNode(Opcodes.IFNE, afterStep));
            guard.add(new VarInsnNode(Opcodes.ALOAD, economyLocal));
            method.instructions.insertBefore(doubleStep, guard);
            method.instructions.insert(doubleStep, afterStep);

            node.fields.add(new FieldNode(Opcodes.ASM8,
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
                            | Opcodes.ACC_SYNTHETIC,
                    MARKER, "Ljava/lang/String;", null, MARKER_VALUE));
            requirePatchedShape(node, marketField);
            ClassWriter writer = new LoaderNeutralClassWriter(
                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            byte[] transformed = writer.toByteArray();
            ClassNode emitted = read(transformed);
            requirePatchedShape(emitted, requireMarketFieldName(emitted));
            record("APPLIED");
            PrepatcherLog.info("APPLIED trade market-mutation refresh to " + className
                    + ".confirmTransaction: pre-twConfirm debt barrier and exact "
                    + "transaction-local guard preserve doubleStep as fallback");
            return transformed;
        } catch (StructuralMismatch mismatch) {
            record("SKIPPED_STRUCTURAL");
            PrepatcherLog.warn("SKIPPED_STRUCTURAL trade market-mutation refresh in "
                    + className + ": " + mismatch.getMessage()
                    + "; original doubleStep remains active");
            return null;
        } catch (Throwable failure) {
            record("SKIPPED_ERROR");
            PrepatcherLog.error("SKIPPED_ERROR trade market-mutation refresh in "
                    + className + "; original doubleStep remains active", failure);
            return null;
        }
    }

    private static void requireOriginalShape(ClassNode node, String marketField) {
        if (field(node, MARKER) != null) {
            throw new StructuralMismatch("unexpected patch marker");
        }
        requireResolvedMarketField(node, marketField);
        MethodNode method = requireMethod(node, METHOD, METHOD_DESC);
        if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
                | Opcodes.ACC_NATIVE)) != 0) {
            throw new StructuralMismatch("confirmTransaction is not concrete instance method");
        }
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
            throw new StructuralMismatch("confirmTransaction gained exception regions");
        }
        requireCount("EconomyAPI.doubleStep",
                calls(method, ECONOMY_API, "doubleStep", "()V").size(), 1);
        requireCount("twConfirm",
                calls(method, TRADE_UI, TW_CONFIRM, "()V").size(), 1);
        requireCount("transaction construction",
                calls(method, TRANSACTION, "<init>",
                        "(L" + MARKET_API + ";"
                                + "Lcom/fs/starfarer/api/campaign/econ/SubmarketAPI;"
                                + "Lcom/fs/starfarer/api/campaign/CampaignUIAPI$CoreUITradeMode;)V")
                        .size(), 1);
        requireCount("transaction report",
                calls(method, "com/fs/starfarer/campaign/CampaignEngine",
                        "reportPlayerMarketTransaction",
                        "(L" + TRANSACTION + ";)V").size(), 1);
        requireCount("Market.updatePriceMult",
                calls(method, MARKET, "updatePriceMult", "()V").size(), 1);
        if (!hasAloadImmediatelyBeforeTransactionReport(method, 1)) {
            throw new StructuralMismatch("PlayerMarketTransaction local slot changed");
        }
        MethodInsnNode twConfirm = uniqueCall(method, TRADE_UI, TW_CONFIRM, "()V");
        MethodInsnNode report = uniqueCall(method,
                "com/fs/starfarer/campaign/CampaignEngine",
                "reportPlayerMarketTransaction", "(L" + TRANSACTION + ";)V");
        MethodInsnNode doubleStep = uniqueCall(method, ECONOMY_API, "doubleStep", "()V");
        if (doubleStep.getOpcode() != Opcodes.INVOKEINTERFACE || !doubleStep.itf) {
            throw new StructuralMismatch("doubleStep invocation kind changed");
        }
        MethodInsnNode price = uniqueCall(method, MARKET, "updatePriceMult", "()V");
        if (!(instructionIndex(method, twConfirm) < instructionIndex(method, report)
                && instructionIndex(method, report) < instructionIndex(method, doubleStep)
                && instructionIndex(method, doubleStep) < instructionIndex(method, price))) {
            throw new StructuralMismatch(
                    "twConfirm/report/doubleStep/updatePriceMult order changed");
        }
    }

    private static boolean hasAloadImmediatelyBeforeTransactionReport(
            MethodNode method, int expectedLocal) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !"com/fs/starfarer/campaign/CampaignEngine".equals(call.owner)
                    || !"reportPlayerMarketTransaction".equals(call.name)) {
                continue;
            }
            AbstractInsnNode previous = previousMeaningful(instruction);
            return previous instanceof VarInsnNode load
                    && load.getOpcode() == Opcodes.ALOAD
                    && load.var == expectedLocal;
        }
        return false;
    }

    private static void requirePatchedShape(ClassNode node, String marketField) {
        requireResolvedMarketField(node, marketField);
        FieldNode marker = field(node, MARKER);
        requireMarker(marker);
        MethodNode method = requireMethod(node, METHOD, METHOD_DESC);
        requireCount("preserved EconomyAPI.doubleStep",
                calls(method, ECONOMY_API, "doubleStep", "()V").size(), 1);
        requireCount("runtime trade guard",
                calls(method, RUNTIME, GUARD, GUARD_DESC).size(), 1);
        requireCount("runtime trade pre-mutation barrier",
                calls(method, RUNTIME, PREPARE, PREPARE_DESC).size(), 1);
        requireCount("twConfirm",
                calls(method, TRADE_UI, TW_CONFIRM, "()V").size(), 1);
        requireCount("Market.updatePriceMult",
                calls(method, MARKET, "updatePriceMult", "()V").size(), 1);

        MethodInsnNode prepare = uniqueCall(method, RUNTIME, PREPARE, PREPARE_DESC);
        MethodInsnNode twConfirm = uniqueCall(method, TRADE_UI, TW_CONFIRM, "()V");
        if (nextMeaningful(prepare) != twConfirm) {
            throw new StructuralMismatch("trade barrier is not immediately before twConfirm");
        }
        AbstractInsnNode marketReadInsn = previousMeaningful(prepare);
        AbstractInsnNode thisLoadInsn = previousMeaningful(marketReadInsn);
        if (!(marketReadInsn instanceof FieldInsnNode marketRead)
                || marketRead.getOpcode() != Opcodes.GETFIELD
                || !TARGET.equals(marketRead.owner)
                || !marketField.equals(marketRead.name)
                || !MARKET_DESC.equals(marketRead.desc)
                || !(thisLoadInsn instanceof VarInsnNode thisLoad)
                || thisLoad.getOpcode() != Opcodes.ALOAD || thisLoad.var != 0) {
            throw new StructuralMismatch("trade barrier market identity arguments changed");
        }

        MethodInsnNode guard = uniqueCall(method, RUNTIME, GUARD, GUARD_DESC);
        AbstractInsnNode transactionLoadInsn = previousMeaningful(guard);
        marketReadInsn = previousMeaningful(transactionLoadInsn);
        thisLoadInsn = previousMeaningful(marketReadInsn);
        AbstractInsnNode economyLoadInsn = previousMeaningful(thisLoadInsn);
        if (!(transactionLoadInsn instanceof VarInsnNode transactionLoad)
                || transactionLoad.getOpcode() != Opcodes.ALOAD || transactionLoad.var != 1
                || !(marketReadInsn instanceof FieldInsnNode guardMarketRead)
                || guardMarketRead.getOpcode() != Opcodes.GETFIELD
                || !TARGET.equals(guardMarketRead.owner)
                || !marketField.equals(guardMarketRead.name)
                || !MARKET_DESC.equals(guardMarketRead.desc)
                || !(thisLoadInsn instanceof VarInsnNode guardThisLoad)
                || guardThisLoad.getOpcode() != Opcodes.ALOAD
                || guardThisLoad.var != 0
                || !(economyLoadInsn instanceof VarInsnNode economyLoad)
                || economyLoad.getOpcode() != Opcodes.ALOAD) {
            throw new StructuralMismatch("trade guard identity arguments changed");
        }
        AbstractInsnNode branchInsn = nextMeaningful(guard);
        if (!(branchInsn instanceof JumpInsnNode branch)
                || branch.getOpcode() != Opcodes.IFNE) {
            throw new StructuralMismatch("trade guard branch changed");
        }
        MethodInsnNode fallback = uniqueCall(method, ECONOMY_API, "doubleStep", "()V");
        AbstractInsnNode fallbackReceiverInsn = previousMeaningful(fallback);
        if (!(fallbackReceiverInsn instanceof VarInsnNode fallbackReceiver)
                || fallbackReceiver.getOpcode() != Opcodes.ALOAD
                || fallbackReceiver.var != economyLoad.var
                || instructionIndex(method, branchInsn) >= instructionIndex(method, fallback)
                || instructionIndex(method, fallback) >= instructionIndex(method, branch.label)) {
            throw new StructuralMismatch("trade global fallback receiver/branch changed");
        }
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode node) {
        AbstractInsnNode current = node.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static AbstractInsnNode nextMeaningful(AbstractInsnNode node) {
        AbstractInsnNode current = node.getNext();
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
    }

    private static int instructionIndex(MethodNode method, AbstractInsnNode target) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction == target) return index;
            index++;
        }
        return -1;
    }

    private static List<MethodInsnNode> calls(
            MethodNode method, String owner, String name, String desc) {
        ArrayList<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && desc.equals(call.desc)) {
                result.add(call);
            }
        }
        return result;
    }

    private static MethodInsnNode uniqueCall(
            MethodNode method, String owner, String name, String desc) {
        List<MethodInsnNode> result = calls(method, owner, name, desc);
        requireCount(owner + "." + name, result.size(), 1);
        return result.get(0);
    }

    private static MethodNode requireMethod(ClassNode node, String name, String desc) {
        MethodNode result = null;
        for (MethodNode method : node.methods) {
            if (!name.equals(method.name) || !desc.equals(method.desc)) continue;
            if (result != null) throw new StructuralMismatch("duplicate method " + name + desc);
            result = method;
        }
        if (result == null) throw new StructuralMismatch("missing method " + name + desc);
        return result;
    }

    private static FieldNode field(ClassNode node, String name) {
        for (FieldNode field : node.fields) if (name.equals(field.name)) return field;
        return null;
    }

    private static String requireMarketFieldName(ClassNode node) {
        FieldNode match = null;
        for (FieldNode candidate : node.fields) {
            if (!LEGACY_MARKET_FIELD.equals(candidate.name)
                    && !REPAIRED_MARKET_FIELD.equals(candidate.name)) {
                continue;
            }
            if (match != null) {
                throw new StructuralMismatch("market field aliases are ambiguous");
            }
            match = candidate;
        }
        if (match == null) {
            throw new StructuralMismatch("exact market field changed");
        }
        if (!MARKET_DESC.equals(match.desc) || (match.access & Opcodes.ACC_STATIC) != 0) {
            throw new StructuralMismatch("exact market field changed");
        }
        return match.name;
    }

    private static void requireResolvedMarketField(ClassNode node, String name) {
        String resolved = requireMarketFieldName(node);
        if (!resolved.equals(name)) {
            throw new StructuralMismatch("market field alias changed during transformation");
        }
    }

    private static void requireMarker(FieldNode marker) {
        if (marker == null || !"Ljava/lang/String;".equals(marker.desc)
                || !MARKER_VALUE.equals(marker.value)
                || (marker.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
                | Opcodes.ACC_SYNTHETIC))
                != (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC)) {
            throw new StructuralMismatch("patch marker changed");
        }
    }

    private static void requireCount(String label, int actual, int expected) {
        if (actual != expected) {
            throw new StructuralMismatch(label + " count changed: expected "
                    + expected + ", found " + actual);
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private boolean runtimeVisibleFrom(ClassLoader loader) {
        if (runtimeLoader == null) return true;
        for (ClassLoader current = loader; current != null; current = current.getParent()) {
            if (current == runtimeLoader) return true;
        }
        return false;
    }

    static String statusProperty() {
        return "starsector.prepatcher.tradeMarketMutationPatch";
    }

    private static void record(String status) {
        System.setProperty(statusProperty(), status);
    }

    private static String loaderName(ClassLoader loader) {
        if (loader == null) return "bootstrap";
        return loader.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(loader));
    }

    private static final class LoaderNeutralClassWriter extends ClassWriter {
        LoaderNeutralClassWriter(int flags) {
            super(flags);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
        }
    }

    private static final class StructuralMismatch extends RuntimeException {
        StructuralMismatch(String message) { super(message); }
    }
}
