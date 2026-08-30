package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

/**
 * Exact structural compatibility patches for methods owned by the maintained
 * AoTD Scheduler Fork rather than by a vanilla superclass.
 *
 * <p>This transformer deliberately uses no name-only allowlist. Each method is
 * matched against the semantic bytecode surface used by the patch, and a
 * changed future implementation is left untouched.</p>
 */
final class AoTDForkCompatibilityTransformer implements ClassFileTransformer {
    static final String TARGET =
            "data/kaysaar/aotd/tot/industries/AoTDConstructionSite";
    static final String CONSTRUCTION_SITE = TARGET;
    static final String PATCH_ID = "aotdConstructionStartBoundary";
    static final String RAW_SET_ASSIGNED_WONDER = "spp$rawSetAssignedWonder";

    private static final String SET_ASSIGNED_WONDER_DESC = "(Ljava/lang/String;)V";
    private static final String MARKET_API_DESC =
            "Lcom/fs/starfarer/api/campaign/econ/MarketAPI;";
    private static final String SCHEDULER_BRIDGE =
            "data/kaysaar/aotd/tot/compat/SchedulerBridge";
    private static final String BEFORE_DESC = "(Ljava/lang/Object;I)J";
    private static final String AFTER_DESC = "(JLjava/lang/Object;IJ)V";
    private static final String MARKER_FIELD = "smo$patched$aotdConstructionStartBoundary";
    private static final String MARKER_VALUE =
            "StarsectorPrepatcher:aotd-construction-start-v1";

    private static final int MUTATION_INDUSTRY_STRUCTURE = 1 << 1;
    private static final int DIRTY_STRUCTURE = 1;
    private static final int DIRTY_INDUSTRIES = 1 << 1;
    private static final int DIRTY_DERIVED_ECONOMY = 1 << 3;
    private static final int DIRTY_MASK =
            DIRTY_STRUCTURE | DIRTY_INDUSTRIES | DIRTY_DERIVED_ECONOMY;

    private final boolean enabled;

    AoTDForkCompatibilityTransformer(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!enabled || !CONSTRUCTION_SITE.equals(className)) return null;
        try {
            ClassNode node = read(classfileBuffer);
            if (!CONSTRUCTION_SITE.equals(node.name)) return null;

            FieldNode marker = field(node, MARKER_FIELD);
            if (marker != null) {
                requireMarker(marker);
                requirePatchedShape(node);
                record("ALREADY_APPLIED");
                return null;
            }
            if (method(node, RAW_SET_ASSIGNED_WONDER, SET_ASSIGNED_WONDER_DESC) != null) {
                throw new StructuralMismatch("raw helper exists without owned marker");
            }

            MethodNode original = requireMethod(
                    node, "setAssignedWonder", SET_ASSIGNED_WONDER_DESC);
            requireOriginalShape(node, original);
            installWrapper(node, original);
            node.fields.add(new FieldNode(Opcodes.ASM8,
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
                            | Opcodes.ACC_SYNTHETIC,
                    MARKER_FIELD, "Ljava/lang/String;", null, MARKER_VALUE));
            requirePatchedShape(node);

            ClassWriter writer = new LoaderNeutralClassWriter(
                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            byte[] transformed = writer.toByteArray();
            requirePatchedShape(read(transformed));
            record("APPLIED");
            PrepatcherLog.info("APPLIED " + PATCH_ID + " to " + className
                    + ": exact setAssignedWonder construction boundary");
            return transformed;
        } catch (StructuralMismatch mismatch) {
            record("SKIPPED_STRUCTURAL");
            PrepatcherLog.warn("SKIPPED_STRUCTURAL " + PATCH_ID + " in " + className
                    + ": " + mismatch.getMessage());
            return null;
        } catch (Throwable failure) {
            record("SKIPPED_ERROR");
            PrepatcherLog.error("SKIPPED_ERROR " + PATCH_ID + " in " + className
                    + "; fork class remains unchanged.", failure);
            return null;
        }
    }

    private static void installWrapper(ClassNode node, MethodNode original) {
        int publicAccess = original.access;
        String signature = original.signature;
        String[] exceptions = original.exceptions == null
                ? null : original.exceptions.toArray(String[]::new);

        original.name = RAW_SET_ASSIGNED_WONDER;
        original.access = (original.access
                & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_FINAL
                | Opcodes.ACC_SYNCHRONIZED))
                | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;

        MethodNode wrapper = new MethodNode(Opcodes.ASM8, publicAccess,
                "setAssignedWonder", SET_ASSIGNED_WONDER_DESC, signature, exceptions);
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode handler = new LabelNode();

        InsnList code = wrapper.instructions;
        addMarketArgument(code, node.name);
        code.add(new InsnNode(Opcodes.ICONST_2));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SCHEDULER_BRIDGE,
                "beforeMarketMutation", BEFORE_DESC, false));
        code.add(new VarInsnNode(Opcodes.LSTORE, 2));

        code.add(tryStart);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name,
                RAW_SET_ASSIGNED_WONDER, SET_ASSIGNED_WONDER_DESC, false));
        code.add(tryEnd);

        addAfterMutation(code, node.name, 2);
        code.add(new InsnNode(Opcodes.RETURN));

        code.add(handler);
        code.add(new VarInsnNode(Opcodes.ASTORE, 4));
        addAfterMutation(code, node.name, 2);
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new InsnNode(Opcodes.ATHROW));

        wrapper.tryCatchBlocks.add(new TryCatchBlockNode(
                tryStart, tryEnd, handler, "java/lang/Throwable"));
        node.methods.add(wrapper);
    }

    private static void addAfterMutation(InsnList code, String owner, int tokenLocal) {
        code.add(new VarInsnNode(Opcodes.LLOAD, tokenLocal));
        addMarketArgument(code, owner);
        code.add(new IntInsnNode(Opcodes.BIPUSH, DIRTY_MASK));
        code.add(new InsnNode(Opcodes.LCONST_0));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SCHEDULER_BRIDGE,
                "afterMarketMutation", AFTER_DESC, false));
    }

    private static void addMarketArgument(InsnList code, String owner) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "market", MARKET_API_DESC));
    }

    private static void requireOriginalShape(ClassNode node, MethodNode method) {
        if ((method.access & Opcodes.ACC_PUBLIC) == 0
                || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
                | Opcodes.ACC_NATIVE)) != 0) {
            throw new StructuralMismatch("setAssignedWonder access changed");
        }
        requireOriginalBodyShape(node, method);
    }

    private static void requireOriginalBodyShape(ClassNode node, MethodNode method) {
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
            throw new StructuralMismatch("setAssignedWonder gained exception regions");
        }
        if (countCalls(method, SCHEDULER_BRIDGE, "beforeMarketMutation", BEFORE_DESC) != 0
                || countCalls(method, SCHEDULER_BRIDGE, "afterMarketMutation", AFTER_DESC) != 0) {
            throw new StructuralMismatch("setAssignedWonder already owns a foreign boundary");
        }

        requireCount("assignedWonder writes",
                countFields(method, Opcodes.PUTFIELD, node.name,
                        "assignedWonder", "Ljava/lang/String;"), 1);
        requireCount("building writes",
                countFields(method, Opcodes.PUTFIELD, node.name, "building", "Z"), 1);
        FieldInsnNode building = onlyField(method, Opcodes.PUTFIELD,
                node.name, "building", "Z");
        AbstractInsnNode value = previousMeaningful(building);
        if (value == null || value.getOpcode() != Opcodes.ICONST_1) {
            throw new StructuralMismatch("setAssignedWonder no longer starts building=true");
        }
        requireCount("wonder instantiation",
                countCalls(method, "com/fs/starfarer/api/campaign/econ/MarketAPI",
                        "instantiateIndustry",
                        "(Ljava/lang/String;)Lcom/fs/starfarer/api/campaign/econ/Industry;"), 1);
        requireCount("contract registration",
                countCalls(method,
                        "data/kaysaar/aotd/tot/scripts/trade/contracts/AoTDTradeContractManager",
                        "addContract",
                        "(Ldata/kaysaar/aotd/tot/scripts/trade/contracts/AoTDTradeContract;)V"),
                1);
        requireCount("setAssignedWonder returns", countOpcode(method, Opcodes.RETURN), 1);
    }

    private static void requirePatchedShape(ClassNode node) {
        requireMarker(field(node, MARKER_FIELD));
        MethodNode wrapper = requireMethod(node, "setAssignedWonder", SET_ASSIGNED_WONDER_DESC);
        MethodNode raw = requireMethod(node, RAW_SET_ASSIGNED_WONDER, SET_ASSIGNED_WONDER_DESC);
        if ((raw.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC))
                != (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC)
                || (raw.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED
                | Opcodes.ACC_STATIC)) != 0) {
            throw new StructuralMismatch("raw setAssignedWonder helper access changed");
        }
        requireOriginalBodyShape(node, raw);
        requireCount("wrapper before boundary",
                countCalls(wrapper, SCHEDULER_BRIDGE,
                        "beforeMarketMutation", BEFORE_DESC), 1);
        requireCount("wrapper after boundaries",
                countCalls(wrapper, SCHEDULER_BRIDGE,
                        "afterMarketMutation", AFTER_DESC), 2);
        requireCount("wrapper raw invocation",
                countCalls(wrapper, node.name, RAW_SET_ASSIGNED_WONDER,
                        SET_ASSIGNED_WONDER_DESC), 1);
        requireCount("wrapper try/finally", wrapper.tryCatchBlocks.size(), 1);
        TryCatchBlockNode block = wrapper.tryCatchBlocks.get(0);
        if (!"java/lang/Throwable".equals(block.type)) {
            throw new StructuralMismatch("wrapper catch type changed");
        }
        requireCount("wrapper returns", countOpcode(wrapper, Opcodes.RETURN), 1);
        requireCount("wrapper rethrows", countOpcode(wrapper, Opcodes.ATHROW), 1);
        requireCount("wrapper market reads",
                countFields(wrapper, Opcodes.GETFIELD, node.name,
                        "market", MARKET_API_DESC), 3);
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

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM8);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    private static MethodNode requireMethod(ClassNode node, String name, String desc) {
        MethodNode method = method(node, name, desc);
        if (method == null) throw new StructuralMismatch("missing method " + name + desc);
        return method;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        MethodNode found = null;
        for (MethodNode method : node.methods) {
            if (!name.equals(method.name) || !desc.equals(method.desc)) continue;
            if (found != null) throw new StructuralMismatch("duplicate method " + name + desc);
            found = method;
        }
        return found;
    }

    private static FieldNode field(ClassNode node, String name) {
        FieldNode found = null;
        for (FieldNode field : node.fields) {
            if (!name.equals(field.name)) continue;
            if (found != null) throw new StructuralMismatch("duplicate field " + name);
            found = field;
        }
        return found;
    }

    private static int countCalls(MethodNode method, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && desc.equals(call.desc)) count++;
        }
        return count;
    }

    private static int countFields(MethodNode method, int opcode, String owner,
                                   String name, String desc) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof FieldInsnNode field && field.getOpcode() == opcode
                    && owner.equals(field.owner) && name.equals(field.name)
                    && desc.equals(field.desc)) count++;
        }
        return count;
    }

    private static FieldInsnNode onlyField(MethodNode method, int opcode, String owner,
                                           String name, String desc) {
        FieldInsnNode result = null;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != opcode
                    || !owner.equals(field.owner) || !name.equals(field.name)
                    || !desc.equals(field.desc)) continue;
            if (result != null) throw new StructuralMismatch("duplicate field site " + name);
            result = field;
        }
        if (result == null) throw new StructuralMismatch("missing field site " + name);
        return result;
    }

    private static int countOpcode(MethodNode method, int opcode) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn.getOpcode() == opcode) count++;
        }
        return count;
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode node) {
        AbstractInsnNode current = node == null ? null : node.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static void requireCount(String label, int actual, int expected) {
        if (actual != expected) {
            throw new StructuralMismatch(label + ": expected " + expected
                    + ", found " + actual);
        }
    }

    private static void record(String status) {
        System.setProperty("starsector.prepatcher.patchStatus."
                + CONSTRUCTION_SITE.replace('/', '.') + "." + PATCH_ID, status);
    }

    /** Avoids Class.forName on a not-yet-defined optional mod class. */
    private static final class LoaderNeutralClassWriter extends ClassWriter {
        LoaderNeutralClassWriter(int flags) {
            super(flags);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            return type1.equals(type2) ? type1 : "java/lang/Object";
        }
    }

    private static final class StructuralMismatch extends RuntimeException {
        StructuralMismatch(String message) {
            super(message);
        }
    }
}
