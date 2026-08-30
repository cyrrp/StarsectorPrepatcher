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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Exact vanilla industry-mutation patch. Only the five proven dialog
 * branches publish a one-shot mutation context. Unknown custom industry
 * options continue through the original IndustryListPanel.tripleStep(). The
 * dialog wrappers remain inert until both dialog and panel surfaces verify.
 */
final class IndustryMarketMutationTransformer implements ClassFileTransformer {
    static final String INDUSTRY_DIALOG =
            "com/fs/starfarer/campaign/ui/marketinfo/b";
    static final String INDUSTRY_PANEL =
            "com/fs/starfarer/campaign/ui/marketinfo/IndustryListPanel";
    static final Set<String> TARGET_CLASSES = Set.of(INDUSTRY_DIALOG, INDUSTRY_PANEL);

    private static final String DIALOG_DISMISSED = "dialogDismissed";
    private static final String DIALOG_DISMISSED_DESC =
            "(Lcom/fs/starfarer/ui/oo0O;I)V";
    private static final String RECREATE = "recreateOverview";
    private static final String RECREATE_DESC = "()V";

    private static final String INDUSTRY =
            "com/fs/starfarer/api/campaign/econ/Industry";
    private static final String MARKET_API =
            "com/fs/starfarer/api/campaign/econ/MarketAPI";
    private static final String ECONOMY_API =
            "com/fs/starfarer/api/campaign/econ/EconomyAPI";
    private static final String GLOBAL = "com/fs/starfarer/api/Global";
    private static final String SECTOR_API = "com/fs/starfarer/api/campaign/SectorAPI";
    private static final String MODE =
            "com/fs/starfarer/api/campaign/econ/MarketAPI$MarketInteractionMode";
    private static final String RUNTIME =
            "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge";

    private static final String INDUSTRY_DESC = "L" + INDUSTRY + ";";
    private static final String MARKET_DESC = "L" + MARKET_API + ";";
    private static final String PANEL_MARKET_FIELD = "market";

    private static final String START = "applyVanillaIndustryStartUpgrading";
    private static final String DOWNGRADE = "applyVanillaIndustryDowngrade";
    private static final String CANCEL = "applyVanillaIndustryCancelUpgrade";
    private static final String REMOVE = "applyVanillaIndustryRemoval";
    private static final String INDUSTRY_WRAPPER_DESC = "(" + INDUSTRY_DESC + ")V";
    private static final String REMOVE_DESC = "(" + MARKET_DESC
            + "Ljava/lang/String;L" + MODE + ";Z)V";
    private static final String ECON_STEP_GUARD =
            "shouldHandleVanillaUiMutationEconomyStep";
    private static final String ECON_STEP_GUARD_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;)Z";

    private static final String DIALOG_MARKER =
            "spp$patched$industryMarketMutations";
    private static final String PANEL_MARKER =
            "spp$patched$industryOverviewMutationGuard";
    private static final String DIALOG_MARKER_VALUE =
            "StarsectorPrepatcher:industry-market-mutations-v1";
    private static final String PANEL_MARKER_VALUE =
            "StarsectorPrepatcher:industry-overview-mutation-guard-v1";

    private final boolean enabled;
    private final ClassLoader runtimeLoader;
    private final Set<String> verifiedTargets = new HashSet<>();
    private boolean groupRejected;

    IndustryMarketMutationTransformer(boolean enabled, ClassLoader runtimeLoader) {
        this.enabled = enabled;
        this.runtimeLoader = runtimeLoader;
        System.setProperty(groupStatusProperty(), enabled ? "awaiting-targets" : "disabled");
    }

    boolean isTargetEnabled(String internalName) {
        return enabled && TARGET_CLASSES.contains(internalName);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!enabled || !TARGET_CLASSES.contains(className)) return null;
        if (!runtimeVisibleFrom(loader)) {
            record(className, "SKIPPED_LOADER");
            rejectGroup("loader-mismatch");
            PrepatcherLog.warn("Industry market-mutation patch not applied: target loader="
                    + loaderName(loader) + ", runtime loader=" + loaderName(runtimeLoader));
            return null;
        }
        try {
            ClassNode node = read(classfileBuffer);
            if (!className.equals(node.name)) return null;
            if (INDUSTRY_DIALOG.equals(className)) return transformDialog(node);
            return transformPanel(node);
        } catch (StructuralMismatch mismatch) {
            record(className, "SKIPPED_STRUCTURAL");
            rejectGroup("structural-mismatch");
            PrepatcherLog.warn("SKIPPED_STRUCTURAL industry market-mutation patch in "
                    + className + ": " + mismatch.getMessage()
                    + "; original industry mutation and tripleStep remain active");
            return null;
        } catch (Throwable failure) {
            record(className, "SKIPPED_ERROR");
            rejectGroup("transform-error");
            PrepatcherLog.error("SKIPPED_ERROR industry market-mutation patch in "
                    + className + "; original behavior remains active", failure);
            return null;
        }
    }

    private byte[] transformDialog(ClassNode node) {
        FieldNode marker = field(node, DIALOG_MARKER);
        if (marker != null) {
            requireMarker(marker, DIALOG_MARKER_VALUE);
            requirePatchedDialog(node);
            record(node.name, "ALREADY_APPLIED");
            acceptTarget(node.name);
            return null;
        }
        requireOriginalDialog(node);
        MethodNode method = requireMethod(node, DIALOG_DISMISSED, DIALOG_DISMISSED_DESC);
        replaceAll(method, INDUSTRY, "startUpgrading", "()V", START,
                INDUSTRY_WRAPPER_DESC);
        replaceAll(method, INDUSTRY, "downgrade", "()V", DOWNGRADE,
                INDUSTRY_WRAPPER_DESC);
        replaceAll(method, INDUSTRY, "cancelUpgrade", "()V", CANCEL,
                INDUSTRY_WRAPPER_DESC);
        replaceAll(method, MARKET_API, "removeIndustry",
                "(Ljava/lang/String;L" + MODE + ";Z)V", REMOVE, REMOVE_DESC);
        node.fields.add(marker(DIALOG_MARKER, DIALOG_MARKER_VALUE));
        requirePatchedDialog(node);
        byte[] transformed = write(node);
        requirePatchedDialog(read(transformed));
        record(node.name, "APPLIED");
        acceptTarget(node.name);
        PrepatcherLog.info("APPLIED exact industry affected-commodity wrappers to "
                + node.name + ": upgrade/downgrade/remove/cancel only");
        return transformed;
    }

    private byte[] transformPanel(ClassNode node) {
        FieldNode marker = field(node, PANEL_MARKER);
        if (marker != null) {
            requireMarker(marker, PANEL_MARKER_VALUE);
            requirePatchedPanel(node);
            record(node.name, "ALREADY_APPLIED");
            acceptTarget(node.name);
            return null;
        }
        requireOriginalPanel(node);
        patchEconomyGuard(requireMethod(node, RECREATE, RECREATE_DESC));
        node.fields.add(marker(PANEL_MARKER, PANEL_MARKER_VALUE));
        requirePatchedPanel(node);
        byte[] transformed = write(node);
        requirePatchedPanel(read(transformed));
        record(node.name, "APPLIED");
        acceptTarget(node.name);
        PrepatcherLog.info("APPLIED one-shot industry mutation guard to " + node.name
                + ".recreateOverview; unknown/custom callers retain tripleStep");
        return transformed;
    }

    private static void replaceAll(MethodNode method, String owner, String name,
                                   String desc, String runtimeName, String runtimeDesc) {
        List<MethodInsnNode> matches = calls(method, owner, name, desc);
        for (MethodInsnNode call : matches) {
            call.setOpcode(Opcodes.INVOKESTATIC);
            call.owner = RUNTIME;
            call.name = runtimeName;
            call.desc = runtimeDesc;
            call.itf = false;
        }
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
        guard.add(new FieldInsnNode(Opcodes.GETFIELD, INDUSTRY_PANEL,
                PANEL_MARKET_FIELD, MARKET_DESC));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                ECON_STEP_GUARD, ECON_STEP_GUARD_DESC, false));
        guard.add(new JumpInsnNode(Opcodes.IFNE, afterStep));
        guard.add(new VarInsnNode(Opcodes.ALOAD, economyLocal));
        method.instructions.insertBefore(triple, guard);
        method.instructions.insert(triple, afterStep);
    }

    private static void requireOriginalDialog(ClassNode node) {
        requireNoMarker(field(node, DIALOG_MARKER));
        MethodNode method = requireMethod(node, DIALOG_DISMISSED, DIALOG_DISMISSED_DESC);
        requireConcreteInstance(method, DIALOG_DISMISSED);
        requireCount("startUpgrading", calls(method, INDUSTRY,
                "startUpgrading", "()V").size(), 1);
        requireCount("downgrade", calls(method, INDUSTRY,
                "downgrade", "()V").size(), 1);
        requireCount("cancelUpgrade", calls(method, INDUSTRY,
                "cancelUpgrade", "()V").size(), 1);
        requireCount("removeIndustry", calls(method, MARKET_API,
                "removeIndustry", "(Ljava/lang/String;L" + MODE + ";Z)V").size(), 2);
        // All five exact mutations must feed the shared overview refresh.
        requireCount("recreateOverview", calls(method, INDUSTRY_PANEL,
                RECREATE, RECREATE_DESC).size(), 7);
    }

    private static void requirePatchedDialog(ClassNode node) {
        requireMarker(field(node, DIALOG_MARKER), DIALOG_MARKER_VALUE);
        MethodNode method = requireMethod(node, DIALOG_DISMISSED, DIALOG_DISMISSED_DESC);
        requireCount("remaining startUpgrading", calls(method, INDUSTRY,
                "startUpgrading", "()V").size(), 0);
        requireCount("remaining downgrade", calls(method, INDUSTRY,
                "downgrade", "()V").size(), 0);
        requireCount("remaining cancelUpgrade", calls(method, INDUSTRY,
                "cancelUpgrade", "()V").size(), 0);
        requireCount("remaining removeIndustry", calls(method, MARKET_API,
                "removeIndustry", "(Ljava/lang/String;L" + MODE + ";Z)V").size(), 0);
        requireCount("start wrappers", calls(method, RUNTIME,
                START, INDUSTRY_WRAPPER_DESC).size(), 1);
        requireCount("downgrade wrappers", calls(method, RUNTIME,
                DOWNGRADE, INDUSTRY_WRAPPER_DESC).size(), 1);
        requireCount("cancel wrappers", calls(method, RUNTIME,
                CANCEL, INDUSTRY_WRAPPER_DESC).size(), 1);
        requireCount("remove wrappers", calls(method, RUNTIME,
                REMOVE, REMOVE_DESC).size(), 2);
        requireCount("preserved recreateOverview", calls(method, INDUSTRY_PANEL,
                RECREATE, RECREATE_DESC).size(), 7);
    }

    private static void requireOriginalPanel(ClassNode node) {
        requireNoMarker(field(node, PANEL_MARKER));
        FieldNode market = field(node, PANEL_MARKET_FIELD);
        if (market == null || !MARKET_DESC.equals(market.desc)
                || (market.access & Opcodes.ACC_STATIC) != 0) {
            throw new StructuralMismatch("IndustryListPanel.market field changed");
        }
        MethodNode recreate = requireMethod(node, RECREATE, RECREATE_DESC);
        requireConcreteInstance(recreate, RECREATE);
        requireCount("tripleStep", calls(recreate, ECONOMY_API,
                "tripleStep", "()V").size(), 1);
        requireCount("existing guards", calls(recreate, RUNTIME,
                ECON_STEP_GUARD, ECON_STEP_GUARD_DESC).size(), 0);
        MethodInsnNode triple = uniqueCall(recreate, ECONOMY_API, "tripleStep", "()V");
        AbstractInsnNode getEconomyInsn = previousMeaningful(triple);
        AbstractInsnNode getSectorInsn = previousMeaningful(getEconomyInsn);
        if (!(getEconomyInsn instanceof MethodInsnNode getEconomy)
                || getEconomy.getOpcode() != Opcodes.INVOKEINTERFACE
                || !SECTOR_API.equals(getEconomy.owner)
                || !"getEconomy".equals(getEconomy.name)
                || !(("()L" + ECONOMY_API + ";").equals(getEconomy.desc))
                || !(getSectorInsn instanceof MethodInsnNode getSector)
                || getSector.getOpcode() != Opcodes.INVOKESTATIC
                || !GLOBAL.equals(getSector.owner)
                || !"getSector".equals(getSector.name)
                || !(("()L" + SECTOR_API + ";").equals(getSector.desc))) {
            throw new StructuralMismatch("exact Global->Economy->tripleStep chain changed");
        }
    }

    private static void requirePatchedPanel(ClassNode node) {
        requireMarker(field(node, PANEL_MARKER), PANEL_MARKER_VALUE);
        MethodNode recreate = requireMethod(node, RECREATE, RECREATE_DESC);
        requireCount("preserved tripleStep fallback", calls(recreate, ECONOMY_API,
                "tripleStep", "()V").size(), 1);
        requireCount("industry mutation guard", calls(recreate, RUNTIME,
                ECON_STEP_GUARD, ECON_STEP_GUARD_DESC).size(), 1);
        MethodInsnNode guard = uniqueCall(recreate, RUNTIME,
                ECON_STEP_GUARD, ECON_STEP_GUARD_DESC);
        AbstractInsnNode marketReadInsn = previousMeaningful(guard);
        AbstractInsnNode thisLoadInsn = previousMeaningful(marketReadInsn);
        AbstractInsnNode economyLoadInsn = previousMeaningful(thisLoadInsn);
        if (!(marketReadInsn instanceof FieldInsnNode marketRead)
                || marketRead.getOpcode() != Opcodes.GETFIELD
                || !INDUSTRY_PANEL.equals(marketRead.owner)
                || !PANEL_MARKET_FIELD.equals(marketRead.name)
                || !MARKET_DESC.equals(marketRead.desc)
                || !(thisLoadInsn instanceof VarInsnNode thisLoad)
                || thisLoad.getOpcode() != Opcodes.ALOAD || thisLoad.var != 0
                || !(economyLoadInsn instanceof VarInsnNode economyLoad)
                || economyLoad.getOpcode() != Opcodes.ALOAD) {
            throw new StructuralMismatch("industry guard identity arguments changed");
        }
        AbstractInsnNode branchInsn = nextMeaningful(guard);
        if (!(branchInsn instanceof JumpInsnNode branch)
                || branch.getOpcode() != Opcodes.IFNE) {
            throw new StructuralMismatch("industry guard branch changed");
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
        List<MethodInsnNode> result = calls(method, owner, name, desc);
        requireCount(name + desc, result.size(), 1);
        return result.get(0);
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

    private static FieldNode marker(String name, String value) {
        return new FieldNode(Opcodes.ASM8,
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
                        | Opcodes.ACC_SYNTHETIC,
                name, "Ljava/lang/String;", null, value);
    }

    private static void requireNoMarker(FieldNode marker) {
        if (marker != null) throw new StructuralMismatch("owned marker already exists");
    }

    private static void requireMarker(FieldNode marker, String value) {
        if (marker == null
                || marker.access != (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC
                | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC)
                || !"Ljava/lang/String;".equals(marker.desc)
                || !value.equals(marker.value)) {
            throw new StructuralMismatch("owned marker is missing or malformed");
        }
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

    private static byte[] write(ClassNode node) {
        ClassWriter writer = new LoaderNeutralClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    static String statusProperty(String className) {
        return "starsector.prepatcher.industryMarketMutationPatch."
                + className.replace('/', '.');
    }

    static String groupStatusProperty() {
        return "starsector.prepatcher.industryMarketMutationPatchGroup";
    }

    private synchronized void acceptTarget(String className) {
        if (groupRejected) return;
        verifiedTargets.add(className);
        System.setProperty(groupStatusProperty(),
                verifiedTargets.containsAll(TARGET_CLASSES) ? "ready" : "awaiting-targets");
    }

    private synchronized void rejectGroup(String reason) {
        groupRejected = true;
        System.setProperty(groupStatusProperty(), "disabled-" + reason);
    }

    private static void record(String className, String status) {
        System.setProperty(statusProperty(className), status);
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
