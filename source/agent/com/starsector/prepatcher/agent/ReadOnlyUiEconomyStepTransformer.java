package com.starsector.prepatcher.agent;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.FieldInsnNode;
import jdk.internal.org.objectweb.asm.tree.FieldNode;
import jdk.internal.org.objectweb.asm.tree.JumpInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Set;

/**
 * Removes exact sector-wide economy refreshes whose only trigger is opening a
 * read-only UI surface. Each target has an independent structural contract and
 * marker; a changed call site retains the original tripleStep() invocation.
 */
final class ReadOnlyUiEconomyStepTransformer implements ClassFileTransformer {
    static final String COMMAND_TAB = "com/fs/starfarer/campaign/command/F";
    static final String COMMODITY_DETAIL_V2 =
            "com/fs/starfarer/campaign/ui/marketinfo/cdd/CommodityDetailDialogV2";
    static final String COMMODITY_DETAIL_LEGACY =
            "com/fs/starfarer/campaign/ui/marketinfo/CommodityDetailDialog";
    static final String MARKET_CMD =
            "com/fs/starfarer/api/impl/campaign/rulecmd/salvage/MarketCMD";
    static final String NEX_MARKET_CMD =
            "com/fs/starfarer/api/impl/campaign/rulecmd/salvage/Nex_MarketCMD";
    static final Set<String> TARGET_CLASSES = Set.of(
            COMMAND_TAB, COMMODITY_DETAIL_V2, COMMODITY_DETAIL_LEGACY,
            MARKET_CMD, NEX_MARKET_CMD);

    private static final String GLOBAL = "com/fs/starfarer/api/Global";
    private static final String SECTOR_API =
            "com/fs/starfarer/api/campaign/SectorAPI";
    private static final String ECONOMY_API =
            "com/fs/starfarer/api/campaign/econ/EconomyAPI";
    private static final String MARKET_API_DESC =
            "Lcom/fs/starfarer/api/campaign/econ/MarketAPI;";
    private static final String COMMODITY_ON_MARKET =
            "com/fs/starfarer/campaign/econ/CommodityOnMarket";
    private static final String MARKER =
            "spp$patched$readOnlyUiNoGlobalEconomyStep";
    private static final String MARKER_PREFIX =
            "StarsectorPrepatcher:read-only-ui-economy-v1:";

    private final boolean commandTabEnabled;
    private final boolean commodityDetailEnabled;
    private final boolean marketDefensesEnabled;
    private final ClassLoader runtimeLoader;

    ReadOnlyUiEconomyStepTransformer(
            boolean commandTabEnabled,
            boolean commodityDetailEnabled,
            boolean marketDefensesEnabled,
            ClassLoader runtimeLoader) {
        this.commandTabEnabled = commandTabEnabled;
        this.commodityDetailEnabled = commodityDetailEnabled;
        this.marketDefensesEnabled = marketDefensesEnabled;
        this.runtimeLoader = runtimeLoader;
    }

    boolean isTargetEnabled(String className) {
        return switch (className) {
            case COMMAND_TAB -> commandTabEnabled;
            case COMMODITY_DETAIL_V2, COMMODITY_DETAIL_LEGACY -> commodityDetailEnabled;
            case MARKET_CMD, NEX_MARKET_CMD -> marketDefensesEnabled;
            default -> false;
        };
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (!TARGET_CLASSES.contains(className) || !isTargetEnabled(className)) return null;
        if (!acceptsLoader(className, loader)) {
            record(className, "SKIPPED_LOADER");
            PrepatcherLog.warn("SKIPPED_LOADER readOnlyUiNoGlobalEconomyStep "
                    + className + ": target loader=" + loaderName(loader)
                    + ", runtime loader=" + loaderName(runtimeLoader));
            return null;
        }

        try {
            ClassNode node = read(classfileBuffer);
            if (!className.equals(node.name)) {
                throw new StructuralMismatch("unexpected owner " + node.name);
            }
            FieldNode marker = field(node, MARKER);
            if (marker != null) {
                requireMarker(marker, className);
                requirePatchedShape(node, className);
                record(className, "ALREADY_APPLIED");
                return null;
            }

            MethodNode method = targetMethod(node, className);
            MethodInsnNode tripleStep = requireOriginalShape(node, method, className);
            MethodInsnNode getEconomy = (MethodInsnNode) previousMeaningful(tripleStep);
            MethodInsnNode getSector = (MethodInsnNode) previousMeaningful(getEconomy);
            method.instructions.remove(getSector);
            method.instructions.remove(getEconomy);
            method.instructions.remove(tripleStep);

            node.fields.add(new FieldNode(Opcodes.ASM8,
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
                            | Opcodes.ACC_SYNTHETIC,
                    MARKER, "Ljava/lang/String;", null, markerValue(className)));
            requirePatchedShape(node, className);

            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            byte[] transformed = writer.toByteArray();
            requirePatchedShape(read(transformed), className);
            record(className, "APPLIED");
            PrepatcherLog.info("APPLIED readOnlyUiNoGlobalEconomyStep to "
                    + className + ": removed exact Global.getSector() -> getEconomy()"
                    + " -> tripleStep() UI-only sequence");
            return transformed;
        } catch (StructuralMismatch mismatch) {
            record(className, "SKIPPED_STRUCTURAL");
            PrepatcherLog.warn("SKIPPED_STRUCTURAL readOnlyUiNoGlobalEconomyStep in "
                    + className + ": " + mismatch.getMessage()
                    + "; original tripleStep remains active");
            return null;
        } catch (Throwable failure) {
            record(className, "SKIPPED_ERROR");
            PrepatcherLog.error("SKIPPED_ERROR readOnlyUiNoGlobalEconomyStep in "
                    + className + "; original tripleStep remains active", failure);
            return null;
        }
    }

    private static MethodInsnNode requireOriginalShape(
            ClassNode node, MethodNode method, String className) {
        requireNoMarker(field(node, MARKER));
        requireTargetOwner(node, className);
        requireConcreteInstance(method, className);
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
            throw new StructuralMismatch("target method gained exception regions");
        }
        requireCount("class tripleStep calls", countCalls(node, ECONOMY_API,
                "tripleStep", "()V"), 1);
        requireCount("target tripleStep calls", countCalls(method, ECONOMY_API,
                "tripleStep", "()V"), 1);

        MethodInsnNode triple = uniqueCall(method, ECONOMY_API, "tripleStep", "()V");
        if (triple.getOpcode() != Opcodes.INVOKEINTERFACE || !triple.itf) {
            throw new StructuralMismatch("tripleStep invocation kind changed");
        }
        AbstractInsnNode getEconomyInsn = previousMeaningful(triple);
        AbstractInsnNode getSectorInsn = previousMeaningful(getEconomyInsn);
        if (!(getEconomyInsn instanceof MethodInsnNode getEconomy)
                || getEconomy.getOpcode() != Opcodes.INVOKEINTERFACE
                || !getEconomy.itf
                || !SECTOR_API.equals(getEconomy.owner)
                || !"getEconomy".equals(getEconomy.name)
                || !("()L" + ECONOMY_API + ";").equals(getEconomy.desc)) {
            throw new StructuralMismatch("exact SectorAPI.getEconomy receiver chain changed");
        }
        if (!(getSectorInsn instanceof MethodInsnNode getSector)
                || getSector.getOpcode() != Opcodes.INVOKESTATIC
                || getSector.itf
                || !GLOBAL.equals(getSector.owner)
                || !"getSector".equals(getSector.name)
                || !("()L" + SECTOR_API + ";").equals(getSector.desc)) {
            throw new StructuralMismatch("exact Global.getSector receiver chain changed");
        }

        switch (className) {
            case COMMAND_TAB -> requireCommandAnchor(triple);
            case COMMODITY_DETAIL_V2, COMMODITY_DETAIL_LEGACY ->
                    requireCommodityAnchor(triple);
            case MARKET_CMD, NEX_MARKET_CMD ->
                    requireMarketDefensesAnchor(method, getSector, triple, className);
            default -> throw new StructuralMismatch("unsupported target " + className);
        }
        return triple;
    }

    private static void requireCommandAnchor(MethodInsnNode triple) {
        if (nextMeaningful(triple) == null
                || nextMeaningful(triple).getOpcode() != Opcodes.RETURN) {
            throw new StructuralMismatch("Command constructor tripleStep is no longer terminal");
        }
    }

    private static void requireCommodityAnchor(MethodInsnNode triple) {
        AbstractInsnNode loadThis = nextMeaningful(triple);
        AbstractInsnNode loadCommodity = nextMeaningful(loadThis);
        AbstractInsnNode getMarketInsn = nextMeaningful(loadCommodity);
        if (!(loadThis instanceof VarInsnNode thisLoad)
                || thisLoad.getOpcode() != Opcodes.ALOAD || thisLoad.var != 0
                || !(loadCommodity instanceof VarInsnNode commodityLoad)
                || commodityLoad.getOpcode() != Opcodes.ALOAD || commodityLoad.var != 1
                || !(getMarketInsn instanceof MethodInsnNode getMarket)
                || getMarket.getOpcode() != Opcodes.INVOKEVIRTUAL
                || !COMMODITY_ON_MARKET.equals(getMarket.owner)
                || !"getMarket".equals(getMarket.name)
                || !"()Lcom/fs/starfarer/campaign/econ/Market;".equals(getMarket.desc)) {
            throw new StructuralMismatch(
                    "commodity-detail post-step market/faction initialization anchor changed");
        }
    }

    private static void requireMarketDefensesAnchor(
            MethodNode method, MethodInsnNode getSector, MethodInsnNode triple,
            String surfaceOwner) {
        AbstractInsnNode branchInsn = previousMeaningful(getSector);
        AbstractInsnNode marketReadInsn = previousMeaningful(branchInsn);
        AbstractInsnNode loadThisInsn = previousMeaningful(marketReadInsn);
        if (!(branchInsn instanceof JumpInsnNode branch)
                || branch.getOpcode() != Opcodes.IFNULL
                || !(marketReadInsn instanceof FieldInsnNode marketRead)
                || marketRead.getOpcode() != Opcodes.GETFIELD
                || !surfaceOwner.equals(marketRead.owner)
                || !"market".equals(marketRead.name)
                || !MARKET_API_DESC.equals(marketRead.desc)
                || !(loadThisInsn instanceof VarInsnNode thisLoad)
                || thisLoad.getOpcode() != Opcodes.ALOAD || thisLoad.var != 0) {
            throw new StructuralMismatch("MarketCMD market-null guard changed");
        }
        AbstractInsnNode afterStep = nextMeaningful(triple);
        if (!(afterStep instanceof VarInsnNode interactionFleetLoad)
                || interactionFleetLoad.getOpcode() != Opcodes.ALOAD
                || interactionFleetLoad.var != 2
                || nextMeaningful(branch.label) != afterStep) {
            throw new StructuralMismatch("MarketCMD null-guard join changed");
        }

        int interaction = instructionIndex(method, surfaceOwner,
                "getInteractionTargetForFIDPI",
                "()Lcom/fs/starfarer/api/campaign/CampaignFleetAPI;");
        int stationFleet = instructionIndex(method, surfaceOwner,
                "getStationFleet",
                "()Lcom/fs/starfarer/api/campaign/CampaignFleetAPI;");
        int stationState = instructionIndex(method, surfaceOwner,
                "getStationState",
                "()Lcom/fs/starfarer/api/impl/campaign/rulecmd/salvage/"
                        + "MarketCMD$StationState;");
        int globalStep = instructionIndex(method, ECONOMY_API, "tripleStep", "()V");
        if (interaction < 0 || stationFleet <= interaction || stationState <= stationFleet
                || globalStep <= stationState) {
            throw new StructuralMismatch(
                    "MarketCMD defense state is no longer fully captured before tripleStep");
        }
    }

    private static void requirePatchedShape(ClassNode node, String className) {
        requireMarker(field(node, MARKER), className);
        requireTargetOwner(node, className);
        MethodNode method = targetMethod(node, className);
        requireConcreteInstance(method, className);
        requireCount("patched class tripleStep calls", countCalls(node, ECONOMY_API,
                "tripleStep", "()V"), 0);
        requireCount("patched target tripleStep calls", countCalls(method, ECONOMY_API,
                "tripleStep", "()V"), 0);
    }

    private static MethodNode targetMethod(ClassNode node, String className) {
        return switch (className) {
            case COMMAND_TAB -> requireMethod(node, "<init>",
                    "(Ljava/lang/Object;Lcom/fs/starfarer/coreui/_$o;)V");
            case COMMODITY_DETAIL_V2, COMMODITY_DETAIL_LEGACY -> requireMethod(node,
                    "<init>",
                    "(Lcom/fs/starfarer/campaign/econ/CommodityOnMarket;"
                            + "Lcom/fs/starfarer/ui/newui/L;"
                            + "Lcom/fs/starfarer/ui/interfacenew;"
                            + "Lcom/fs/starfarer/ui/oo0O$o;)V");
            case MARKET_CMD, NEX_MARKET_CMD ->
                    requireMethod(node, "showDefenses", "(Z)V");
            default -> throw new StructuralMismatch("unsupported target " + className);
        };
    }

    private static void requireConcreteInstance(MethodNode method, String className) {
        if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            throw new StructuralMismatch("target method is no longer concrete instance code");
        }
        if (MARKET_CMD.equals(className) || NEX_MARKET_CMD.equals(className)) {
            if ((method.access & Opcodes.ACC_PROTECTED) == 0) {
                throw new StructuralMismatch("MarketCMD.showDefenses is no longer protected");
            }
        } else if ((method.access & Opcodes.ACC_PUBLIC) == 0) {
            throw new StructuralMismatch("UI constructor is no longer public");
        }
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

    private static MethodInsnNode uniqueCall(
            MethodNode method, String owner, String name, String desc) {
        MethodInsnNode result = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && desc.equals(call.desc)) {
                if (result != null) {
                    throw new StructuralMismatch("multiple " + owner + '.' + name + desc);
                }
                result = call;
            }
        }
        if (result == null) throw new StructuralMismatch("missing " + owner + '.' + name + desc);
        return result;
    }

    private static int countCalls(
            ClassNode node, String owner, String name, String desc) {
        int count = 0;
        for (MethodNode method : node.methods) count += countCalls(method, owner, name, desc);
        return count;
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

    private static FieldNode field(ClassNode node, String name) {
        for (FieldNode field : node.fields) if (name.equals(field.name)) return field;
        return null;
    }

    private static void requireNoMarker(FieldNode marker) {
        if (marker != null) throw new StructuralMismatch("owned marker already exists");
    }

    private static void requireMarker(FieldNode marker, String className) {
        int access = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
                | Opcodes.ACC_SYNTHETIC;
        if (marker == null || marker.access != access
                || !"Ljava/lang/String;".equals(marker.desc)
                || !markerValue(className).equals(marker.value)) {
            throw new StructuralMismatch("owned marker is missing or malformed");
        }
    }

    private static String markerValue(String className) {
        return MARKER_PREFIX + switch (className) {
            case COMMAND_TAB -> "command-tab";
            case COMMODITY_DETAIL_V2 -> "commodity-detail-v2";
            case COMMODITY_DETAIL_LEGACY -> "commodity-detail-legacy";
            case MARKET_CMD -> "market-defenses";
            case NEX_MARKET_CMD -> "nex-market-defenses";
            default -> throw new StructuralMismatch("unsupported target " + className);
        };
    }

    private static void requireTargetOwner(ClassNode node, String className) {
        if (NEX_MARKET_CMD.equals(className) && !MARKET_CMD.equals(node.superName)) {
            throw new StructuralMismatch("Nex_MarketCMD superclass changed");
        }
    }

    private boolean acceptsLoader(String className, ClassLoader loader) {
        if (runtimeLoader == null) return true;
        if (NEX_MARKET_CMD.equals(className)) {
            // Nexerelin is owned by its child mod loader. This transformation adds only a
            // constant marker and removes a call sequence, so it needs no cross-loader bridge.
            return isSameOrChildLoader(loader, runtimeLoader);
        }
        return loader == runtimeLoader;
    }

    static boolean isSameOrChildLoader(ClassLoader candidate, ClassLoader expectedParent) {
        if (candidate == null || expectedParent == null) return candidate == expectedParent;
        for (ClassLoader current = candidate; current != null; current = current.getParent()) {
            if (current == expectedParent) return true;
        }
        return false;
    }

    private static void requireCount(String label, int actual, int expected) {
        if (actual != expected) {
            throw new StructuralMismatch(label + " expected=" + expected + " actual=" + actual);
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM8);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static void record(String className, String status) {
        System.setProperty(statusProperty(className), status);
    }

    static String statusProperty(String className) {
        return switch (className) {
            case COMMAND_TAB ->
                    "starsector.prepatcher.commandTabNoGlobalEconomyStepPatch";
            case COMMODITY_DETAIL_V2 ->
                    "starsector.prepatcher.commodityDetailV2NoGlobalEconomyStepPatch";
            case COMMODITY_DETAIL_LEGACY ->
                    "starsector.prepatcher.commodityDetailLegacyNoGlobalEconomyStepPatch";
            case MARKET_CMD ->
                    "starsector.prepatcher.marketDefensesNoGlobalEconomyStepPatch";
            case NEX_MARKET_CMD ->
                    "starsector.prepatcher.nexMarketDefensesNoGlobalEconomyStepPatch";
            default -> "starsector.prepatcher.unknownReadOnlyUiEconomyPatch";
        };
    }

    private static String loaderName(ClassLoader loader) {
        if (loader == null) return "bootstrap";
        return loader.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(loader));
    }

    private static final class StructuralMismatch extends RuntimeException {
        StructuralMismatch(String message) { super(message); }
    }
}
