package com.starsector.prepatcher.agent;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.FieldNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Analyzer;
import jdk.internal.org.objectweb.asm.tree.analysis.BasicValue;
import jdk.internal.org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.file.Path;
import java.util.jar.JarFile;

/** Exact structural coverage for the early market-open context wrapper. */
public final class AoTDMarketOpenContextTransformerTest {
    private static final String TARGET =
            "com/fs/starfarer/campaign/CampaignEngine";
    private static final String ENTRY = TARGET + ".class";
    private static final String DESC =
            "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V";
    private static final String RUNTIME =
            "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge";

    private AoTDMarketOpenContextTransformerTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException(
                    "Expected starfarer_obf.jar [prepatcher.properties]");
        }
        byte[] original;
        try (JarFile jar = new JarFile(Path.of(args[0]).toFile())) {
            var entry = jar.getJarEntry(ENTRY);
            require(entry != null, "missing " + ENTRY);
            try (var input = jar.getInputStream(entry)) { original = input.readAllBytes(); }
        }

        byte[] pipelineInput = original;
        if (args.length == 2) {
            PrepatcherConfig config = PrepatcherConfig.load(Path.of(args[1]));
            byte[] structural = new PrepatcherTransformer(config, null).transform(
                    null, TARGET, null, null, original);
            require(structural != null, "main structural transformer did not patch CampaignEngine");
            pipelineInput = structural;
        }

        AoTDMarketOpenContextTransformer transformer =
                new AoTDMarketOpenContextTransformer(true, null);
        byte[] patched = transformer.transform(null, TARGET, null, null, pipelineInput);
        require(patched != null, "exact CampaignEngine did not patch");
        inspect(patched);
        verify(patched);

        require(transformer.transform(null, TARGET, null, null, patched) == null,
                "repeated transformation was not idempotent");
        require("ALREADY_APPLIED".equals(System.getProperty(
                        "starsector.prepatcher.aotdMarketOpenContextPatch")),
                "unexpected idempotence status");

        byte[] future = removeNextStep(original);
        require(transformer.transform(null, TARGET, null, null, future) == null,
                "future method without Economy.nextStep patched");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.prepatcher.aotdMarketOpenContextPatch")),
                "future method did not fail closed");

        System.out.println("OK aotd-market-open-context exact/idempotent/fail-closed/basic-verifier");
    }

    private static void inspect(byte[] bytes) {
        ClassNode node = read(bytes);
        require(field(node, "spp$patched$aotdMarketOpenContext") != null,
                "marker missing");
        MethodNode wrapper = method(node, "reportPlayerOpenedMarket", DESC);
        MethodNode raw = method(node, "spp$raw$reportPlayerOpenedMarket", DESC);
        require(wrapper != null && raw != null, "wrapper/raw pair missing");
        require(calls(wrapper, RUNTIME, "beginAoTDOpeningMarket",
                "(Ljava/lang/Object;)J") == 1, "begin count mismatch");
        require(calls(wrapper, RUNTIME, "endAoTDOpeningMarket", "(J)V") == 2,
                "end/finally count mismatch");
        require(calls(wrapper, TARGET, "spp$raw$reportPlayerOpenedMarket", DESC) == 1,
                "raw call missing");
        require(wrapper.tryCatchBlocks.size() == 1
                        && "java/lang/Throwable".equals(wrapper.tryCatchBlocks.get(0).type),
                "Throwable finally missing");
        require(calls(raw, "com/fs/starfarer/campaign/econ/Economy",
                "nextStep", "()V") == 1, "raw nextStep missing");
    }

    private static byte[] removeNextStep(byte[] bytes) {
        ClassNode node = read(bytes);
        MethodNode method = method(node, "reportPlayerOpenedMarket", DESC);
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call
                    && "com/fs/starfarer/campaign/econ/Economy".equals(call.owner)
                    && "nextStep".equals(call.name) && "()V".equals(call.desc)) {
                method.instructions.remove(insn);
                return write(node);
            }
        }
        throw new AssertionError("nextStep fixture call missing");
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
        for (MethodNode method : node.methods)
            if (name.equals(method.name) && desc.equals(method.desc)) return method;
        return null;
    }

    private static FieldNode field(ClassNode node, String name) {
        for (FieldNode field : node.fields) if (name.equals(field.name)) return field;
        return null;
    }

    private static int calls(MethodNode method, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && desc.equals(call.desc)) count++;
        }
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
