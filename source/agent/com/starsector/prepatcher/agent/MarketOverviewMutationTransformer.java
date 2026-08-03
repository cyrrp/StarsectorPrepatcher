package com.starsector.prepatcher.agent;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.FieldInsnNode;
import jdk.internal.org.objectweb.asm.tree.FieldNode;
import jdk.internal.org.objectweb.asm.tree.InsnList;
import jdk.internal.org.objectweb.asm.tree.JumpInsnNode;
import jdk.internal.org.objectweb.asm.tree.LabelNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

/**
 * Exact-call-site mutation context patch for the vanilla market
 * overview panel. Unknown callers of recreateWithEconUpdate() retain the
 * original global Economy.tripleStep().
 */
final class MarketOverviewMutationTransformer implements ClassFileTransformer {
    static final String TARGET = "com/fs/starfarer/campaign/ui/marketinfo/s";
    private static final String ACTION = "actionPerformed";
    private static final String ACTION_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String RECREATE = "recreateWithEconUpdate";
    private static final String RECREATE_DESC = "()V";
    private static final String MARKET_API = "com/fs/starfarer/api/campaign/econ/MarketAPI";
    private static final String ECONOMY_API = "com/fs/starfarer/api/campaign/econ/EconomyAPI";
    private static final String GLOBAL = "com/fs/starfarer/api/Global";
    private static final String SECTOR_API = "com/fs/starfarer/api/campaign/SectorAPI";
    private static final String RUNTIME = "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge";
    private static final String MARKET_DESC = "L" + MARKET_API + ";";
    private static final String MARKET_FIELD = "String.interface$float";
    private static final String MARKER = "spp$patched$marketOverviewMutationRefresh";
    private static final String MARKER_VALUE =
            "StarsectorPrepatcher:market-overview-mutation-refresh-v1";

    private static final String APPLY_FREE_PORT = "applyVanillaFreePortMutation";
    private static final String APPLY_IMMIGRATION_CLOSED =
            "applyVanillaImmigrationClosedMutation";
    private static final String APPLY_STOCKPILE_POLICY =
            "applyVanillaStockpilePolicyMutation";
    private static final String APPLY_IMMIGRATION_INCENTIVES =
            "applyVanillaImmigrationIncentivesMutation";
    private static final String BOOL_MUTATION_DESC = "(" + MARKET_DESC + "Z)V";
    private static final String BOXED_BOOL_MUTATION_DESC =
            "(" + MARKET_DESC + "Ljava/lang/Boolean;)V";
    private static final String ECON_STEP_GUARD =
            "shouldHandleVanillaUiMutationEconomyStep";
    private static final String ECON_STEP_GUARD_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;)Z";

    private final boolean enabled;
    private final ClassLoader runtimeLoader;

    MarketOverviewMutationTransformer(boolean enabled, ClassLoader runtimeLoader) {
        this.enabled = enabled;
        this.runtimeLoader = runtimeLoader;
    }

    boolean isTargetEnabled(String internalName) {
        return enabled && TARGET.equals(internalName);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!enabled || !TARGET.equals(className)) return null;
        if (!runtimeVisibleFrom(loader)) {
            record("SKIPPED_LOADER");
            PrepatcherLog.warn("Market overview mutation refresh not patched: target loader="
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

            requireOriginalShape(node);
            patchMutationCalls(requireMethod(node, ACTION, ACTION_DESC));
            patchEconomyGuard(requireMethod(node, RECREATE, RECREATE_DESC));
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
            PrepatcherLog.info("APPLIED market overview mutation refresh to " + className
                    + ": exact policy mutators publish one-shot reason/scope context;"
                    + " shared recreate helper keeps global fallback for unknown actions");
            return transformed;
        } catch (StructuralMismatch mismatch) {
            record("SKIPPED_STRUCTURAL");
            PrepatcherLog.warn("SKIPPED_STRUCTURAL market overview mutation refresh in "
                    + className + ": " + mismatch.getMessage()
                    + "; original mutation calls and tripleStep remain active");
            return null;
        } catch (Throwable failure) {
            record("SKIPPED_ERROR");
            PrepatcherLog.error("SKIPPED_ERROR market overview mutation refresh in "
                    + className + "; original mutation behavior remains active", failure);
            return null;
        }
    }

    private static void patchMutationCalls(MethodNode action) {
        List<MethodInsnNode> freePort = calls(action, MARKET_API, "setFreePort", "(Z)V");
        List<MethodInsnNode> immigrationClosed = calls(
                action, MARKET_API, "setImmigrationClosed", "(Z)V");
        List<MethodInsnNode> stockpile = calls(
                action, MARKET_API, "setUseStockpilesForShortages", "(Z)V");
        List<MethodInsnNode> incentives = calls(
                action, MARKET_API, "setImmigrationIncentivesOn", "(Ljava/lang/Boolean;)V");

        // Both free-port calls are wrapped. The runtime records a targeted/global
        // scope only when the value actually changes, so closing immigration
        // while free port is already off can still use the proven local path.
        for (MethodInsnNode call : freePort) replaceWithStatic(call, APPLY_FREE_PORT,
                BOOL_MUTATION_DESC);
        for (MethodInsnNode call : immigrationClosed) replaceWithStatic(call,
                APPLY_IMMIGRATION_CLOSED, BOOL_MUTATION_DESC);
        replaceWithStatic(stockpile.get(0), APPLY_STOCKPILE_POLICY, BOOL_MUTATION_DESC);
        replaceWithStatic(incentives.get(0), APPLY_IMMIGRATION_INCENTIVES,
                BOXED_BOOL_MUTATION_DESC);
    }

    private static void replaceWithStatic(MethodInsnNode call, String runtimeName, String desc) {
        call.setOpcode(Opcodes.INVOKESTATIC);
        call.owner = RUNTIME;
        call.name = runtimeName;
        call.desc = desc;
        call.itf = false;
    }

    private static void patchEconomyGuard(MethodNode method) {
        MethodInsnNode triple = uniqueCall(method, ECONOMY_API, "tripleStep", "()V");
        int economyLocal = method.maxLocals;
        method.maxLocals = economyLocal + 1;
        LabelNode afterStep = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ASTORE, economyLocal));
        guard.add(new VarInsnNode(Opcodes.ALOAD, economyLocal));
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET, MARKET_FIELD, MARKET_DESC));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                ECON_STEP_GUARD, ECON_STEP_GUARD_DESC, false));
        guard.add(new JumpInsnNode(Opcodes.IFNE, afterStep));
        guard.add(new VarInsnNode(Opcodes.ALOAD, economyLocal));
        method.instructions.insertBefore(triple, guard);
        method.instructions.insert(triple, afterStep);
    }

    private static void requireOriginalShape(ClassNode node) {
        requireNoMarker(field(node, MARKER));
        FieldNode market = field(node, MARKET_FIELD);
        if (market == null || !MARKET_DESC.equals(market.desc)
                || (market.access & Opcodes.ACC_STATIC) != 0) {
            throw new StructuralMismatch("market identity field changed");
        }
        MethodNode action = requireMethod(node, ACTION, ACTION_DESC);
        MethodNode recreate = requireMethod(node, RECREATE, RECREATE_DESC);
        requireConcreteInstance(action, ACTION);
        requireConcreteInstance(recreate, RECREATE);
        if (action.tryCatchBlocks != null && !action.tryCatchBlocks.isEmpty()) {
            throw new StructuralMismatch("actionPerformed gained exception regions");
        }
        requireCount("setFreePort calls", calls(action, MARKET_API,
                "setFreePort", "(Z)V").size(), 2);
        requireCount("setImmigrationClosed calls", calls(action, MARKET_API,
                "setImmigrationClosed", "(Z)V").size(), 2);
        requireCount("stockpile-policy calls", calls(action, MARKET_API,
                "setUseStockpilesForShortages", "(Z)V").size(), 1);
        requireCount("immigration-incentive calls", calls(action, MARKET_API,
                "setImmigrationIncentivesOn", "(Ljava/lang/Boolean;)V").size(), 1);
        requireCount("action recreate calls", calls(action, TARGET,
                RECREATE, RECREATE_DESC).size(), 5);

        // Verify the four UI anchors and order rather than relying only on the
        // occurrence number of an obfuscated setter.
        int freePortButton = callIndex(action,
                "com/fs/starfarer/campaign/ui/marketinfo/oOoO", "getFreePort",
                "()Lcom/fs/starfarer/ui/n;");
        int closedButton = callIndex(action,
                "com/fs/starfarer/campaign/ui/marketinfo/oOoO", "getClosed",
                "()Lcom/fs/starfarer/ui/n;");
        int stockpileButton = callIndex(action,
                "com/fs/starfarer/campaign/ui/marketinfo/ShippingPanel", "getUseStockpiles",
                "()Lcom/fs/starfarer/ui/n;");
        int incentivesButton = callIndex(action,
                "com/fs/starfarer/campaign/ui/marketinfo/oOoO", "getIncentivesToggle",
                "()Lcom/fs/starfarer/ui/n;");
        int adminButton = callIndex(action,
                "com/fs/starfarer/campaign/ui/marketinfo/t", "getPortrait",
                "()Lcom/fs/starfarer/ui/n;");
        if (!(freePortButton >= 0 && freePortButton < closedButton
                && closedButton < stockpileButton && stockpileButton < incentivesButton
                && incentivesButton < adminButton)) {
            throw new StructuralMismatch("market policy branch anchors/order changed");
        }
        List<MethodInsnNode> freePortCalls = calls(action, MARKET_API, "setFreePort", "(Z)V");
        List<MethodInsnNode> closedCalls = calls(
                action, MARKET_API, "setImmigrationClosed", "(Z)V");
        int firstFreePort = instructionIndex(action, freePortCalls.get(0));
        int secondFreePort = instructionIndex(action, freePortCalls.get(1));
        int firstClosed = instructionIndex(action, closedCalls.get(0));
        int secondClosed = instructionIndex(action, closedCalls.get(1));
        if (!(freePortButton < firstFreePort && firstFreePort < firstClosed
                && firstClosed < closedButton && closedButton < secondFreePort
                && secondFreePort < secondClosed && secondClosed < stockpileButton)) {
            throw new StructuralMismatch("free-port/immigration mutation layout changed");
        }
        requireOriginalRecreate(recreate);
    }

    private static void requireOriginalRecreate(MethodNode method) {
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
            throw new StructuralMismatch("recreateWithEconUpdate gained exception regions");
        }
        requireCount("recreate tripleStep calls", calls(method, ECONOMY_API,
                "tripleStep", "()V").size(), 1);
        requireCount("recreate guards", calls(method, RUNTIME,
                ECON_STEP_GUARD, ECON_STEP_GUARD_DESC).size(), 0);
        MethodInsnNode triple = uniqueCall(method, ECONOMY_API, "tripleStep", "()V");
        AbstractInsnNode getEconomyInsn = previousMeaningful(triple);
        AbstractInsnNode getSectorInsn = previousMeaningful(getEconomyInsn);
        if (!(getEconomyInsn instanceof MethodInsnNode getEconomy)
                || getEconomy.getOpcode() != Opcodes.INVOKEINTERFACE || !getEconomy.itf
                || !SECTOR_API.equals(getEconomy.owner)
                || !"getEconomy".equals(getEconomy.name)
                || !("()L" + ECONOMY_API + ";").equals(getEconomy.desc)
                || !(getSectorInsn instanceof MethodInsnNode getSector)
                || getSector.getOpcode() != Opcodes.INVOKESTATIC || getSector.itf
                || !GLOBAL.equals(getSector.owner) || !"getSector".equals(getSector.name)
                || !("()L" + SECTOR_API + ";").equals(getSector.desc)) {
            throw new StructuralMismatch("exact Global->Sector->Economy tripleStep chain changed");
        }
    }

    private static void requirePatchedShape(ClassNode node) {
        requireMarker(field(node, MARKER));
        MethodNode action = requireMethod(node, ACTION, ACTION_DESC);
        MethodNode recreate = requireMethod(node, RECREATE, RECREATE_DESC);
        requireCount("patched vanilla free-port calls", calls(action, MARKET_API,
                "setFreePort", "(Z)V").size(), 0);
        requireCount("patched vanilla immigration-closed calls", calls(action, MARKET_API,
                "setImmigrationClosed", "(Z)V").size(), 0);
        requireCount("patched vanilla stockpile calls", calls(action, MARKET_API,
                "setUseStockpilesForShortages", "(Z)V").size(), 0);
        requireCount("patched vanilla incentives calls", calls(action, MARKET_API,
                "setImmigrationIncentivesOn", "(Ljava/lang/Boolean;)V").size(), 0);
        requireCount("free-port wrappers", calls(action, RUNTIME,
                APPLY_FREE_PORT, BOOL_MUTATION_DESC).size(), 2);
        requireCount("immigration wrappers", calls(action, RUNTIME,
                APPLY_IMMIGRATION_CLOSED, BOOL_MUTATION_DESC).size(), 2);
        requireCount("stockpile wrappers", calls(action, RUNTIME,
                APPLY_STOCKPILE_POLICY, BOOL_MUTATION_DESC).size(), 1);
        requireCount("incentive wrappers", calls(action, RUNTIME,
                APPLY_IMMIGRATION_INCENTIVES, BOXED_BOOL_MUTATION_DESC).size(), 1);
        requireCount("guarded tripleStep calls", calls(recreate, ECONOMY_API,
                "tripleStep", "()V").size(), 1);
        requireCount("mutation economy guards", calls(recreate, RUNTIME,
                ECON_STEP_GUARD, ECON_STEP_GUARD_DESC).size(), 1);
        MethodInsnNode guard = uniqueCall(recreate, RUNTIME,
                ECON_STEP_GUARD, ECON_STEP_GUARD_DESC);
        AbstractInsnNode marketReadInsn = previousMeaningful(guard);
        AbstractInsnNode thisLoadInsn = previousMeaningful(marketReadInsn);
        AbstractInsnNode economyLoadInsn = previousMeaningful(thisLoadInsn);
        if (!(marketReadInsn instanceof FieldInsnNode marketRead)
                || marketRead.getOpcode() != Opcodes.GETFIELD
                || !TARGET.equals(marketRead.owner) || !MARKET_FIELD.equals(marketRead.name)
                || !MARKET_DESC.equals(marketRead.desc)
                || !(thisLoadInsn instanceof VarInsnNode thisLoad)
                || thisLoad.getOpcode() != Opcodes.ALOAD || thisLoad.var != 0
                || !(economyLoadInsn instanceof VarInsnNode economyLoad)
                || economyLoad.getOpcode() != Opcodes.ALOAD) {
            throw new StructuralMismatch("mutation guard identity arguments changed");
        }
        AbstractInsnNode branchInsn = nextMeaningful(guard);
        if (!(branchInsn instanceof JumpInsnNode branch)
                || branch.getOpcode() != Opcodes.IFNE) {
            throw new StructuralMismatch("mutation guard branch changed");
        }
    }

    private static void requireConcreteInstance(MethodNode method, String name) {
        if ((method.access & Opcodes.ACC_PUBLIC) == 0
                || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
                | Opcodes.ACC_NATIVE)) != 0) {
            throw new StructuralMismatch(name + " access changed");
        }
    }

    private static List<MethodInsnNode> calls(
            MethodNode method, String owner, String name, String desc) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && desc.equals(call.desc)) result.add(call);
        }
        return result;
    }

    private static MethodInsnNode uniqueCall(
            MethodNode method, String owner, String name, String desc) {
        List<MethodInsnNode> calls = calls(method, owner, name, desc);
        requireCount(name + desc + " calls", calls.size(), 1);
        return calls.get(0);
    }

    private static int callIndex(MethodNode method, String owner, String name, String desc) {
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

    private static MethodNode requireMethod(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) return method;
        }
        throw new StructuralMismatch("missing method " + name + desc);
    }

    private static FieldNode field(ClassNode node, String name) {
        for (FieldNode field : node.fields) if (name.equals(field.name)) return field;
        return null;
    }

    private static void requireNoMarker(FieldNode marker) {
        if (marker != null) throw new StructuralMismatch("owned marker already exists");
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
        for (ClassLoader current = loader; current != null; current = current.getParent()) {
            if (current == runtimeLoader) return true;
        }
        return false;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM8);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    static String statusProperty() {
        return "starsector.prepatcher.marketOverviewMutationPatch";
    }

    private static void record(String status) {
        System.setProperty(statusProperty(), status);
    }

    private static void requireCount(String label, int actual, int expected) {
        if (actual != expected) {
            throw new StructuralMismatch(label + " expected=" + expected
                    + " actual=" + actual);
        }
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
