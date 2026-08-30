package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarFile;

/** Exact structural coverage for Cargo skip/local guard and global fallback. */
public final class AoTDDetachedCargoContextTransformerTest {
    private static final String TARGET = "com/fs/starfarer/campaign/ui/class";
    private static final String ENTRY = TARGET + ".class";
    private static final String CTOR_DESC =
            "(Lcom/fs/starfarer/campaign/ui/class$Oo;"
                    + "Lcom/fs/starfarer/api/campaign/SectorEntityToken;"
                    + "Lcom/fs/starfarer/campaign/command/OutpostListPanel$Oo;"
                    + "Lcom/fs/starfarer/campaign/fleet/CargoData;"
                    + "Ljava/lang/String;Lcom/fs/starfarer/coreui/_$o;"
                    + "Lcom/fs/starfarer/ui/U;)V";
    private static final String RUNTIME =
            "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge";
    private static final String SHOULD_SKIP_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;ZLjava/lang/Object;"
                    + "Ljava/lang/Object;Ljava/lang/Object;)Z";

    private AoTDDetachedCargoContextTransformerTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected starfarer_obf.jar");
        }
        byte[] original;
        try (JarFile jar = new JarFile(Path.of(args[0]).toFile())) {
            var entry = jar.getJarEntry(ENTRY);
            require(entry != null, "missing " + ENTRY);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }

        AoTDDetachedCargoContextTransformer transformer =
                new AoTDDetachedCargoContextTransformer(true, null);
        byte[] patched = transformer.transform(null, TARGET, null, null, original);
        require(patched != null, "exact detached Cargo target did not patch");
        inspect(patched);
        verify(patched);

        require(transformer.transform(null, TARGET, null, null, patched) == null,
                "repeated detached-Cargo transformation was not idempotent");
        require("ALREADY_APPLIED".equals(System.getProperty(
                        "starsector.prepatcher.campaignCargoNoGlobalEconomyStepPatch")),
                "unexpected detached-Cargo idempotence status");

        byte[] future = changeFakeMarketLiteral(original);
        byte[] futureBefore = future.clone();
        require(transformer.transform(null, TARGET, null, null, future) == null,
                "future fake-market contract patched");
        require(Arrays.equals(future, futureBefore),
                "failed Cargo transformation mutated its input bytes");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.prepatcher.campaignCargoNoGlobalEconomyStepPatch")),
                "future fake-market contract did not fail closed");

        System.out.println(
                "OK campaign-cargo explicit-guard/global-fallback/"
                        + "idempotent/rollback/basic-verifier");
    }

    private static void inspect(byte[] bytes) {
        ClassNode node = read(bytes);
        FieldNode marker = field(node, "spp$patched$aotdDetachedCargoContext");
        require(marker != null
                        && "StarsectorPrepatcher:cargo-ui-economy-explicit-guard-v5"
                        .equals(marker.value),
                "detached-Cargo marker missing or stale");
        MethodNode constructor = method(node, "<init>", CTOR_DESC);
        require(constructor != null, "Cargo constructor missing");
        require(calls(constructor, RUNTIME,
                        "shouldSkipVanillaCargoEconomyStep",
                        SHOULD_SKIP_DESC) == 1,
                "vanilla detached-Cargo skip guard count mismatch");
        require(calls(constructor,
                        "com/fs/starfarer/api/campaign/econ/EconomyAPI",
                        "tripleStep", "()V") == 1,
                "EconomyAPI.tripleStep count changed");
        require(constructor.tryCatchBlocks.isEmpty(),
                "detached guard added an exception region");


        MethodInsnNode guard = uniqueCall(constructor, RUNTIME,
                "shouldSkipVanillaCargoEconomyStep", SHOULD_SKIP_DESC);
        AbstractInsnNode guardOtherCargo = previousMeaningful(guard);
        AbstractInsnNode guardOutpost = previousMeaningful(guardOtherCargo);
        AbstractInsnNode guardMode = previousMeaningful(guardOutpost);
        AbstractInsnNode guardDetached = previousMeaningful(guardMode);
        AbstractInsnNode guardMarket = previousMeaningful(guardDetached);
        AbstractInsnNode guardMarketOwner = previousMeaningful(guardMarket);
        AbstractInsnNode guardEconomy = previousMeaningful(guardMarketOwner);
        requireLoad(guardOtherCargo, Opcodes.ALOAD, 4, "guard other Cargo argument");
        requireLoad(guardOutpost, Opcodes.ALOAD, 3, "guard outpost argument");
        requireLoad(guardMode, Opcodes.ALOAD, 1, "guard mode argument");
        require(guardDetached instanceof VarInsnNode guardDetachedLoad
                        && guardDetachedLoad.getOpcode() == Opcodes.ILOAD,
                "guard derived detached branch local missing");
        require(guardMarket instanceof FieldInsnNode marketRead
                        && marketRead.getOpcode() == Opcodes.GETFIELD
                        && TARGET.equals(marketRead.owner)
                        && "Lcom/fs/starfarer/campaign/econ/Market;".equals(marketRead.desc),
                "guard live-market field missing");
        requireLoad(guardMarketOwner, Opcodes.ALOAD, 0,
                "guard live-market receiver");
        require(guardEconomy instanceof VarInsnNode guardEconomyLoad
                        && guardEconomyLoad.getOpcode() == Opcodes.ALOAD,
                "guard economy receiver missing");
        int guardEconomyLocal = ((VarInsnNode) guardEconomy).var;
        AbstractInsnNode branchInsn = nextMeaningful(guard);
        require(branchInsn instanceof JumpInsnNode branch
                        && branch.getOpcode() == Opcodes.IFNE,
                "vanilla skip guard does not branch around tripleStep");

        MethodInsnNode fallback = uniqueCall(constructor,
                "com/fs/starfarer/api/campaign/econ/EconomyAPI",
                "tripleStep", "()V");
        AbstractInsnNode fallbackReceiver = previousMeaningful(fallback);
        require(fallbackReceiver instanceof VarInsnNode fallbackLoad
                        && fallbackLoad.getOpcode() == Opcodes.ALOAD
                        && fallbackLoad.var == guardEconomyLocal,
                "Cargo global fallback receiver changed");
        require(indexOf(constructor, branchInsn) < indexOf(constructor, fallback)
                        && indexOf(constructor, fallback)
                        < indexOf(constructor, ((JumpInsnNode) branchInsn).label),
                "Cargo guard target does not follow the virtual fallback");
    }

    private static byte[] changeFakeMarketLiteral(byte[] bytes) {
        ClassNode node = read(bytes);
        int changed = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof LdcInsnNode ldc
                        && "fake_market".equals(ldc.cst)) {
                    ldc.cst = "future_fake_market";
                    changed++;
                }
            }
        }
        require(changed == 1, "fake_market fixture literal count changed: " + changed);
        return write(node);
    }

    private static void verify(byte[] bytes) throws Exception {
        ClassNode node = read(bytes);
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM8);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) return method;
        }
        return null;
    }

    private static FieldNode field(ClassNode node, String name) {
        for (FieldNode field : node.fields) {
            if (name.equals(field.name)) return field;
        }
        return null;
    }

    private static MethodInsnNode uniqueCall(
            MethodNode method, String owner, String name, String desc) {
        MethodInsnNode result = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && desc.equals(call.desc)) {
                require(result == null, "duplicate call " + owner + "." + name + desc);
                result = call;
            }
        }
        require(result != null, "missing call " + owner + "." + name + desc);
        return result;
    }

    private static int calls(MethodNode method, String owner, String name, String desc) {
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

    private static int indexOf(MethodNode method, AbstractInsnNode target) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction == target) return index;
            index++;
        }
        return -1;
    }

    private static void requireLoad(
            AbstractInsnNode instruction, int opcode, int local, String label) {
        require(instruction instanceof VarInsnNode variable
                        && variable.getOpcode() == opcode && variable.var == local,
                label + " changed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
