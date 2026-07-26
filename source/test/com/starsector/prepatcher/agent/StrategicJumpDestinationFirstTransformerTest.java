package com.starsector.prepatcher.agent;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.FieldNode;
import jdk.internal.org.objectweb.asm.tree.InsnNode;
import jdk.internal.org.objectweb.asm.tree.JumpInsnNode;
import jdk.internal.org.objectweb.asm.tree.LdcInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.VarInsnNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Analyzer;
import jdk.internal.org.objectweb.asm.tree.analysis.AnalyzerException;
import jdk.internal.org.objectweb.asm.tree.analysis.BasicValue;
import jdk.internal.org.objectweb.asm.tree.analysis.BasicVerifier;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

/** Focused structural regression for destination-first StrategicModule routing. */
public final class StrategicJumpDestinationFirstTransformerTest {
    private static final String PATCH_ID = "strategicJumpDestinationFirst";
    private static final String METHOD_DESC =
            "(Lcom/fs/starfarer/api/campaign/CampaignFleetAPI;"
                    + "Lcom/fs/starfarer/api/campaign/LocationAPI;)"
                    + "Lcom/fs/starfarer/campaign/ai/StrategicModule$Oo;";
    private static final String HOSTILE_DESC =
            "(Lcom/fs/starfarer/api/campaign/FactionAPI;"
                    + "Lcom/fs/starfarer/api/campaign/SectorEntityToken;F)I";
    private static final String STATUS_KEY =
            "starsector.prepatcher.patchStatus."
                    + "com.fs.starfarer.campaign.ai.StrategicModule." + PATCH_ID;

    private StrategicJumpDestinationFirstTransformerTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: StrategicJumpDestinationFirstTransformerTest "
                            + "<config> <starfarer_obf.jar>");
        }
        PrepatcherConfig config = PrepatcherConfig.load(Path.of(args[0]));
        require(config.strategicJumpDestinationFirst,
                "test configuration must enable patch.strategicJumpDestinationFirst");
        byte[] original = readClass(Path.of(args[1]), PrepatcherTransformer.STRATEGIC_MODULE);

        assertVanillaState(original);
        byte[] patched = transform(config, original);
        require(patched != null, "vanilla StrategicModule was not patched");
        require("APPLIED".equals(System.getProperty(STATUS_KEY)),
                "vanilla patch status was not APPLIED");
        assertPatchedState(patched, true);
        verifyBytecode(patched);

        byte[] repeated = transform(config, patched);
        require(repeated == null, "second pass was not idempotent");
        require("ALREADY_APPLIED".equals(System.getProperty(STATUS_KEY)),
                "second pass status was not ALREADY_APPLIED");

        byte[] unrelated = addUnrelatedNop(original);
        byte[] unrelatedPatched = transform(config, unrelated);
        require(unrelatedPatched != null,
                "unrelated instruction changed destination-first compatibility");
        assertPatchedState(unrelatedPatched, true);

        byte[] wrongRadius = mutateRadius(original, 3999f);
        require(transform(config, wrongRadius) == null,
                "changed hostile-market radius was accepted");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(STATUS_KEY)),
                "changed radius did not report SKIPPED_STRUCTURAL");

        byte[] wrongMultiplier = mutateMultiplier(original);
        require(transform(config, wrongMultiplier) == null,
                "changed hostile-market multiplier was accepted");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(STATUS_KEY)),
                "changed multiplier did not report SKIPPED_STRUCTURAL");

        byte[] foreignLazy = removeOwnershipMarker(patched);
        assertPatchedState(foreignLazy, false);
        require(transform(config, foreignLazy) == null,
                "foreign destination-first state without ownership marker was accepted");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(STATUS_KEY)),
                "foreign lazy state did not report SKIPPED_STRUCTURAL");

        byte[] tamperedOwned = mutateGuardSentinel(patched);
        require(transform(config, tamperedOwned) == null,
                "tampered owned destination-first state was accepted");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(STATUS_KEY)),
                "tampered owned state did not report SKIPPED_STRUCTURAL");

        System.out.println("OK strategic destination-first vanilla/idempotent/unrelated"
                + "/radius/multiplier/foreign-lazy/owned-tamper");
    }

    private static byte[] transform(PrepatcherConfig config, byte[] bytes) {
        System.clearProperty(STATUS_KEY);
        return new PrepatcherTransformer(config).transform(null,
                PrepatcherTransformer.STRATEGIC_MODULE, null, null, bytes);
    }

    private static void assertVanillaState(byte[] bytes) {
        ClassNode node = read(bytes);
        MethodNode method = method(node);
        MethodInsnNode destinations = onlyCall(method,
                "com/fs/starfarer/campaign/JumpPoint", "getDestinations",
                "()Ljava/util/List;");
        MethodInsnNode hostile = onlyCall(method,
                "com/fs/starfarer/api/util/Misc", "getNumHostileMarkets", HOSTILE_DESC);
        require(method.instructions.indexOf(hostile) < method.instructions.indexOf(destinations),
                "vanilla hostile-market call is not eager");
        require(!hasMarker(node), "vanilla class already has ownership marker");
    }

    private static void assertPatchedState(byte[] bytes, boolean expectMarker) {
        ClassNode node = read(bytes);
        MethodNode method = method(node);
        MethodInsnNode destinations = onlyCall(method,
                "com/fs/starfarer/campaign/JumpPoint", "getDestinations",
                "()Ljava/util/List;");
        MethodInsnNode hostile = onlyCall(method,
                "com/fs/starfarer/api/util/Misc", "getNumHostileMarkets", HOSTILE_DESC);
        require(method.instructions.indexOf(hostile) > method.instructions.indexOf(destinations),
                "hostile-market call was not moved after destination enumeration");
        require(hasMarker(node) == expectMarker, "ownership marker state changed");

        AbstractInsnNode destinationReceiver = previousMeaningful(destinations);
        require(destinationReceiver instanceof VarInsnNode load
                        && load.getOpcode() == Opcodes.ALOAD,
                "getDestinations receiver changed");
        AbstractInsnNode sentinelStore = previousMeaningful(destinationReceiver);
        AbstractInsnNode sentinel = previousMeaningful(sentinelStore);
        require(sentinelStore instanceof VarInsnNode store
                        && store.getOpcode() == Opcodes.ISTORE
                        && sentinel instanceof LdcInsnNode ldc
                        && Integer.valueOf(Integer.MIN_VALUE).equals(ldc.cst),
                "sentinel initialization is missing");

        JumpInsnNode guard = null;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.IF_ICMPNE
                    && previousMeaningful(jump) instanceof LdcInsnNode ldc
                    && Integer.valueOf(Integer.MIN_VALUE).equals(ldc.cst)) {
                require(guard == null, "duplicate destination-first guard");
                guard = jump;
            }
        }
        require(guard != null, "destination-first guard is missing");
        AbstractInsnNode scoreBody = nextMeaningful(guard.label);
        require(scoreBody instanceof VarInsnNode jumpLoad
                        && jumpLoad.getOpcode() == Opcodes.ALOAD
                        && nextMeaningful(scoreBody) instanceof MethodInsnNode getLocation
                        && getLocation.owner.equals("com/fs/starfarer/campaign/JumpPoint")
                        && getLocation.name.equals("getLocation"),
                "guard does not join at the original candidate body");

        AbstractInsnNode radius = previousMeaningful(hostile);
        require(radius instanceof LdcInsnNode ldc
                        && ldc.cst instanceof Float value
                        && Float.floatToIntBits(value) == Float.floatToIntBits(4000f),
                "lazy hostile-market radius changed");
        AbstractInsnNode two = nextMeaningful(hostile);
        require(two != null && two.getOpcode() == Opcodes.ICONST_2
                        && nextMeaningful(two).getOpcode() == Opcodes.IMUL,
                "lazy hostile-market multiplier changed");
    }

    private static byte[] addUnrelatedNop(byte[] source) {
        ClassNode node = read(source);
        method(node).instructions.insert(new InsnNode(Opcodes.NOP));
        return write(node);
    }

    private static byte[] mutateRadius(byte[] source, float value) {
        ClassNode node = read(source);
        MethodInsnNode hostile = onlyCall(method(node),
                "com/fs/starfarer/api/util/Misc", "getNumHostileMarkets", HOSTILE_DESC);
        AbstractInsnNode radius = previousMeaningful(hostile);
        require(radius instanceof LdcInsnNode, "hostile-market radius constant missing");
        ((LdcInsnNode) radius).cst = Float.valueOf(value);
        return write(node);
    }

    private static byte[] mutateMultiplier(byte[] source) {
        ClassNode node = read(source);
        MethodInsnNode hostile = onlyCall(method(node),
                "com/fs/starfarer/api/util/Misc", "getNumHostileMarkets", HOSTILE_DESC);
        AbstractInsnNode two = nextMeaningful(nextMeaningful(nextMeaningful(hostile)));
        require(two != null && two.getOpcode() == Opcodes.ICONST_2,
                "vanilla multiplier constant missing");
        method(node).instructions.set(two, new InsnNode(Opcodes.ICONST_3));
        return write(node);
    }

    private static byte[] removeOwnershipMarker(byte[] source) {
        ClassNode node = read(source);
        List<FieldNode> retained = new ArrayList<>();
        for (FieldNode field : node.fields) {
            if (!field.name.equals("smo$patched$" + PATCH_ID)) retained.add(field);
        }
        require(retained.size() + 1 == node.fields.size(), "ownership marker missing");
        node.fields = retained;
        return write(node);
    }

    private static byte[] mutateGuardSentinel(byte[] source) {
        ClassNode node = read(source);
        MethodNode method = method(node);
        int changed = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof JumpInsnNode jump)
                    || jump.getOpcode() != Opcodes.IF_ICMPNE) continue;
            AbstractInsnNode sentinel = previousMeaningful(jump);
            if (sentinel instanceof LdcInsnNode ldc
                    && Integer.valueOf(Integer.MIN_VALUE).equals(ldc.cst)) {
                ldc.cst = Integer.valueOf(-1);
                changed++;
            }
        }
        require(changed == 1, "destination-first guard sentinel not found");
        return write(node);
    }

    private static boolean hasMarker(ClassNode node) {
        int count = 0;
        for (FieldNode field : node.fields) {
            if (field.name.equals("smo$patched$" + PATCH_ID)) count++;
        }
        require(count <= 1, "duplicate ownership marker");
        return count == 1;
    }

    private static MethodNode method(ClassNode node) {
        MethodNode found = null;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("findNearestSafeJumpPoint")
                    || !method.desc.equals(METHOD_DESC)) continue;
            require(found == null, "duplicate findNearestSafeJumpPoint method");
            found = method;
        }
        require(found != null, "findNearestSafeJumpPoint method missing");
        return found;
    }

    private static MethodInsnNode onlyCall(MethodNode method, String owner,
                                           String name, String desc) {
        MethodInsnNode found = null;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call)
                    || !call.owner.equals(owner) || !call.name.equals(name)
                    || !call.desc.equals(desc)) continue;
            require(found == null, "duplicate call " + owner + "." + name + desc);
            found = call;
        }
        require(found != null, "missing call " + owner + "." + name + desc);
        return found;
    }

    private static byte[] readClass(Path jar, String internalName) throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            var entry = file.getJarEntry(internalName + ".class");
            require(entry != null, "class missing from JAR: " + internalName);
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
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void verifyBytecode(byte[] bytes) {
        ClassNode node = read(bytes);
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            try {
                new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
            } catch (AnalyzerException ex) {
                throw new AssertionError("BasicVerifier rejected " + method.name + method.desc,
                        ex);
            }
        }
    }

    private static AbstractInsnNode nextMeaningful(AbstractInsnNode node) {
        AbstractInsnNode current = node == null ? null : node.getNext();
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode node) {
        AbstractInsnNode current = node == null ? null : node.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
