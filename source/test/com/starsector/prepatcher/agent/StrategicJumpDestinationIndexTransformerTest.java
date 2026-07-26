package com.starsector.prepatcher.agent;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.FieldInsnNode;
import jdk.internal.org.objectweb.asm.tree.JumpInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.VarInsnNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Analyzer;
import jdk.internal.org.objectweb.asm.tree.analysis.AnalyzerException;
import jdk.internal.org.objectweb.asm.tree.analysis.BasicValue;
import jdk.internal.org.objectweb.asm.tree.analysis.BasicVerifier;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.jar.JarFile;

/** Combined structural regression for destination-first and the incremental index. */
public final class StrategicJumpDestinationIndexTransformerTest {
    private static final String PATCH_ID = "strategicJumpDestinationIndex";
    private static final String HOOKS =
            "com/fs/starfarer/api/StarsectorPrepatcherHooks";
    private static final String METHOD_DESC =
            "(Lcom/fs/starfarer/api/campaign/CampaignFleetAPI;"
                    + "Lcom/fs/starfarer/api/campaign/LocationAPI;)"
                    + "Lcom/fs/starfarer/campaign/ai/StrategicModule$Oo;";
    private static final String INDEX_HOOK_DESC =
            "(Lcom/fs/starfarer/api/campaign/LocationAPI;Ljava/lang/Class;"
                    + "Lcom/fs/starfarer/api/campaign/LocationAPI;)Ljava/util/List;";
    private static final String UPDATE_DESC =
            "(Lcom/fs/starfarer/api/campaign/LocationAPI;F)V";
    private static final String DEFER_DESC =
            "(Lcom/fs/starfarer/api/campaign/LocationAPI;"
                    + "Lcom/fs/starfarer/api/campaign/LocationAPI;)Z";
    private static final String INVALIDATE_DESC =
            "(Lcom/fs/starfarer/api/campaign/JumpPointAPI;)V";
    private static final String ADDED_DESC =
            "(Lcom/fs/starfarer/api/campaign/JumpPointAPI;"
                    + "Lcom/fs/starfarer/api/campaign/JumpPointAPI$JumpDestination;)V";
    private static final String RETARGET_DESC =
            "(Lcom/fs/starfarer/api/campaign/JumpPointAPI$JumpDestination;)V";
    private static final String LOCATION_DESC =
            "(Lcom/fs/starfarer/api/campaign/LocationAPI;Ljava/lang/Object;)V";
    private static final String INDEX_STATUS =
            "starsector.prepatcher.patchStatus."
                    + "com.fs.starfarer.campaign.ai.StrategicModule." + PATCH_ID;

    private StrategicJumpDestinationIndexTransformerTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: StrategicJumpDestinationIndexTransformerTest "
                    + "<config> <starfarer_obf.jar> <starfarer.api.jar>");
        }
        PrepatcherConfig config = PrepatcherConfig.load(Path.of(args[0]));
        require(config.strategicJumpDestinationFirst, "destination-first must be enabled");
        require(config.strategicJumpDestinationIndex, "destination index must be enabled");

        Path obf = Path.of(args[1]);
        Path api = Path.of(args[2]);
        PrepatcherTransformer transformer = new PrepatcherTransformer(config);

        byte[] strategic = readClass(obf, PrepatcherTransformer.STRATEGIC_MODULE);
        byte[] jumpPoint = readClass(obf, PrepatcherTransformer.JUMP_POINT);
        byte[] baseLocation = readClass(obf, PrepatcherTransformer.BASE_LOCATION);
        byte[] jumpDestination = readClass(api, PrepatcherTransformer.JUMP_DESTINATION);

        byte[] strategicPatched = transform(transformer,
                PrepatcherTransformer.STRATEGIC_MODULE, strategic);
        assertStrategic(strategicPatched);
        assertIdempotent(transformer, PrepatcherTransformer.STRATEGIC_MODULE,
                strategicPatched);
        assertStrategicAtomicFailure(config, strategic);

        byte[] jumpPointPatched = transform(transformer,
                PrepatcherTransformer.JUMP_POINT, jumpPoint);
        assertJumpPoint(jumpPointPatched);
        assertIdempotent(transformer, PrepatcherTransformer.JUMP_POINT, jumpPointPatched);

        byte[] destinationPatched = transform(transformer,
                PrepatcherTransformer.JUMP_DESTINATION, jumpDestination);
        assertJumpDestination(destinationPatched);
        assertIdempotent(transformer, PrepatcherTransformer.JUMP_DESTINATION,
                destinationPatched);

        byte[] locationPatched = transform(transformer,
                PrepatcherTransformer.BASE_LOCATION, baseLocation);
        assertBaseLocation(locationPatched);
        assertIdempotent(transformer, PrepatcherTransformer.BASE_LOCATION, locationPatched);

        System.out.println("OK strategic destination index source + four topology surfaces "
                + "+ composition + idempotency");
    }

    private static byte[] transform(PrepatcherTransformer transformer,
                                    String name, byte[] original) {
        byte[] patched = transformer.transform(null, name, null, null, original);
        require(patched != null, name + " patch was not applied");
        verify(patched);
        return patched;
    }

    private static void assertIdempotent(PrepatcherTransformer transformer,
                                         String name, byte[] patched) {
        require(transformer.transform(null, name, null, null, patched) == null,
                name + " combined post-state was not idempotent");
    }

    private static void assertStrategic(byte[] bytes) {
        ClassNode node = read(bytes);
        require(hasMarker(node, "strategicJumpDestinationFirst"),
                "destination-first marker missing after composition");
        require(hasMarker(node, PATCH_ID), "destination-index marker missing");
        MethodNode method = method(node, "findNearestSafeJumpPoint", METHOD_DESC);
        require(count(method, Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/LocationAPI", "getEntities",
                "(Ljava/lang/Class;)Ljava/util/List;") == 0,
                "vanilla full jump-point source remains");
        require(count(method, Opcodes.INVOKESTATIC, HOOKS,
                "strategicJumpPointsForDestination", INDEX_HOOK_DESC) == 1,
                "indexed jump-point source missing");
        require(count(method, Opcodes.INVOKESTATIC,
                "com/fs/starfarer/api/util/Misc", "getNumHostileMarkets",
                "(Lcom/fs/starfarer/api/campaign/FactionAPI;"
                        + "Lcom/fs/starfarer/api/campaign/SectorEntityToken;F)I") == 1,
                "destination-first hostile score changed during composition");
        MethodNode update = method(node, "updateJumpPlanTo", UPDATE_DESC);
        MethodInsnNode defer = singleCall(update, "strategicJumpDeferExpiredPlan", DEFER_DESC);
        AbstractInsnNode requested = previousMeaningful(defer);
        require(requested instanceof VarInsnNode load
                        && load.getOpcode() == Opcodes.ALOAD && load.var == 1,
                "expired-plan deferral does not receive requested location local 1");
        AbstractInsnNode branch = nextMeaningful(defer);
        require(branch instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFEQ,
                "expired-plan deferral is not guarded by IFEQ");
        AbstractInsnNode earlyReturn = nextMeaningful(branch);
        require(earlyReturn != null && earlyReturn.getOpcode() == Opcodes.RETURN,
                "expired-plan deferral has no immediate early return");
    }

    private static void assertStrategicAtomicFailure(PrepatcherConfig config,
                                                     byte[] strategic) {
        ClassNode malformed = read(strategic);
        MethodNode update = method(malformed, "updateJumpPlanTo", UPDATE_DESC);
        AbstractInsnNode zero = null;
        for (AbstractInsnNode insn : update.instructions.toArray()) {
            if (!(insn instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.GETFIELD
                    || !field.owner.equals(
                    "com/fs/starfarer/campaign/ai/CampaignFleetAI$JumpPlan")
                    || !field.name.equals("timeLeft") || !field.desc.equals("F")) {
                continue;
            }
            AbstractInsnNode candidate = nextMeaningful(field);
            if (candidate != null && candidate.getOpcode() == Opcodes.FCONST_0) {
                require(zero == null, "ambiguous JumpPlan expiry fixture");
                zero = candidate;
            }
        }
        require(zero != null, "JumpPlan expiry fixture missing");
        update.instructions.set(zero, new jdk.internal.org.objectweb.asm.tree.InsnNode(
                Opcodes.FCONST_1));

        System.clearProperty(INDEX_STATUS);
        byte[] result = new PrepatcherTransformer(config).transform(
                null, PrepatcherTransformer.STRATEGIC_MODULE,
                null, null, write(malformed));
        require(result != null,
                "destination-first was rolled back with a non-matching index member");
        verify(result);
        ClassNode node = read(result);
        require(hasMarker(node, "strategicJumpDestinationFirst"),
                "destination-first did not survive index-plan rejection");
        require(!hasMarker(node, PATCH_ID),
                "destination index marker was published after atomic-plan rejection");
        MethodNode find = method(node, "findNearestSafeJumpPoint", METHOD_DESC);
        require(count(find, Opcodes.INVOKEINTERFACE,
                        "com/fs/starfarer/api/campaign/LocationAPI", "getEntities",
                        "(Ljava/lang/Class;)Ljava/util/List;") == 1,
                "index source was partially rewritten after expiry-member rejection");
        require(count(find, Opcodes.INVOKESTATIC, HOOKS,
                        "strategicJumpPointsForDestination", INDEX_HOOK_DESC) == 0,
                "index hook survived atomic-plan rejection");
        require(count(method(node, "updateJumpPlanTo", UPDATE_DESC),
                        Opcodes.INVOKESTATIC, HOOKS,
                        "strategicJumpDeferExpiredPlan", DEFER_DESC) == 0,
                "expiry deferral survived atomic-plan rejection");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(INDEX_STATUS)),
                "atomic-plan rejection did not report SKIPPED_STRUCTURAL");
    }

    private static void assertJumpPoint(byte[] bytes) {
        ClassNode node = read(bytes);
        require(hasMarker(node, PATCH_ID), "JumpPoint destination-index marker missing");
        assertEpilogue(method(node, "addDestination",
                        "(Lcom/fs/starfarer/api/campaign/JumpPointAPI$JumpDestination;)V"),
                "strategicJumpDestinationAdded", ADDED_DESC, 0, 1);
        assertEpilogue(method(node, "clearDestinations", "()V"),
                "strategicJumpDestinationsChanged", INVALIDATE_DESC, 0);
        assertEpilogue(method(node, "removeDestination",
                        "(Lcom/fs/starfarer/api/campaign/SectorEntityToken;)V"),
                "strategicJumpDestinationsChanged", INVALIDATE_DESC, 0);
    }

    private static void assertJumpDestination(byte[] bytes) {
        ClassNode node = read(bytes);
        require(hasMarker(node, PATCH_ID), "JumpDestination destination-index marker missing");
        assertEpilogue(method(node, "setDestination",
                        "(Lcom/fs/starfarer/api/campaign/SectorEntityToken;)V"),
                "strategicJumpDestinationRetargeted", RETARGET_DESC, 0);
    }

    private static void assertBaseLocation(byte[] bytes) {
        ClassNode node = read(bytes);
        require(hasMarker(node, PATCH_ID), "BaseLocation destination-index marker missing");
        assertEpilogue(method(node, "addObjectReal", "(Ljava/lang/Object;)V"),
                "strategicJumpLocationEntityChanged", LOCATION_DESC, 0, 1);
        assertEpilogue(method(node, "removeObjectReal", "(Ljava/lang/Object;)V"),
                "strategicJumpLocationEntityChanged", LOCATION_DESC, 0, 1);
    }

    private static void assertEpilogue(MethodNode method, String hookName,
                                       String hookDesc, int... expectedLocals) {
        require(count(method, Opcodes.INVOKESTATIC, HOOKS, hookName, hookDesc) == 1,
                "missing hook " + hookName + " in " + method.name + method.desc);
        AbstractInsnNode exit = singleReturn(method);
        AbstractInsnNode current = previousMeaningful(exit);
        require(current instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals(HOOKS) && call.name.equals(hookName)
                        && call.desc.equals(hookDesc),
                hookName + " is not directly before normal return");
        for (int i = expectedLocals.length - 1; i >= 0; i--) {
            current = previousMeaningful(current);
            require(current instanceof VarInsnNode load
                            && load.getOpcode() == Opcodes.ALOAD
                            && load.var == expectedLocals[i],
                    hookName + " operand " + i + " changed");
        }
    }

    private static AbstractInsnNode singleReturn(MethodNode method) {
        AbstractInsnNode result = null;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn.getOpcode() != Opcodes.RETURN) continue;
            require(result == null, "multiple returns in " + method.name + method.desc);
            result = insn;
        }
        require(result != null, "missing return in " + method.name + method.desc);
        return result;
    }


    private static MethodInsnNode singleCall(MethodNode method, String name, String desc) {
        MethodInsnNode result = null;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !call.owner.equals(HOOKS) || !call.name.equals(name)
                    || !call.desc.equals(desc)) continue;
            require(result == null, "multiple hooks " + name + desc);
            result = call;
        }
        require(result != null, "missing hook " + name + desc);
        return result;
    }

    private static int count(MethodNode method, int opcode, String owner,
                             String name, String desc) {
        int result = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == opcode
                    && call.owner.equals(owner) && call.name.equals(name)
                    && call.desc.equals(desc)) result++;
        }
        return result;
    }

    private static boolean hasMarker(ClassNode node, String patchId) {
        return node.fields.stream().anyMatch(field ->
                field.name.equals("smo$patched$" + patchId));
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(method -> method.name.equals(name)
                && method.desc.equals(desc)).findFirst()
                .orElseThrow(() -> new AssertionError("missing method " + name + desc));
    }

    private static byte[] readClass(Path jar, String internalName) throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            var entry = file.getJarEntry(internalName + ".class");
            require(entry != null, "missing class " + internalName);
            try (InputStream input = file.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM8);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] write(ClassNode node) {
        jdk.internal.org.objectweb.asm.ClassWriter writer =
                new jdk.internal.org.objectweb.asm.ClassWriter(
                        jdk.internal.org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void verify(byte[] bytes) {
        ClassNode node = read(bytes);
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            try {
                new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
            } catch (AnalyzerException ex) {
                throw new AssertionError("BasicVerifier rejected " + method.name + method.desc, ex);
            }
        }
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode node) {
        AbstractInsnNode current = node == null ? null : node.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static AbstractInsnNode nextMeaningful(AbstractInsnNode node) {
        AbstractInsnNode current = node == null ? null : node.getNext();
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
