package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

/** Exact-body gate for the vanilla detached-Cargo economy skip. */
public final class VanillaDetachedCargoEconomyContractTransformerTest {
    private static final String TARGET =
            VanillaDetachedCargoEconomyContractTransformer.TARGET;
    private static final String ENTRY = TARGET + ".class";

    private VanillaDetachedCargoEconomyContractTransformerTest() {}

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

        VanillaDetachedCargoEconomyContractTransformer transformer =
                new VanillaDetachedCargoEconomyContractTransformer(true, null);
        require(transformer.transform(null, TARGET, null, null, original) == null,
                "contract-only transformer unexpectedly changed bytes");
        require("READY".equals(System.getProperty(
                        "starsector.prepatcher.detachedCargoVanillaEconomyContract")),
                "exact vanilla economy contract was not accepted");

        Path configFile = Files.createTempFile(
                "prepatcher-detached-cargo-contract", ".properties");
        byte[] composed;
        try {
            Files.writeString(configFile,
                    "patch.campaignCargoNoGlobalEconomyStep=true\n",
                    StandardCharsets.UTF_8);
            PrepatcherConfig config = PrepatcherConfig.load(configFile);
            composed = new PrepatcherTransformer(config, null).transform(
                    null, TARGET, null, null, original);
            require(composed != null,
                    "main structural transformer did not patch Economy");
        } finally {
            Files.deleteIfExists(configFile);
        }
        require(transformer.transform(null, TARGET, null, null, composed) == null,
                "post-structural contract-only transformer unexpectedly changed bytes");
        require("READY".equals(System.getProperty(
                        "starsector.prepatcher.detachedCargoVanillaEconomyContract")),
                "final post-structural vanilla economy contract was not accepted");

        byte[] future = changeFirstNextStep(composed);
        require(transformer.transform(null, TARGET, null, null, future) == null,
                "future contract-only fixture unexpectedly changed bytes");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.prepatcher.detachedCargoVanillaEconomyContract")),
                "changed tripleStep did not fail closed");

        System.out.println(
                "OK vanilla-detached-cargo-economy-contract raw/post-structural/"
                        + "future-fail-closed");
    }

    private static byte[] changeFirstNextStep(byte[] bytes) {
        ClassNode node = read(bytes);
        MethodNode triple = null;
        for (MethodNode method : node.methods) {
            if ("tripleStep".equals(method.name) && "()V".equals(method.desc)) {
                triple = method;
                break;
            }
        }
        require(triple != null, "tripleStep fixture missing");
        for (AbstractInsnNode instruction : triple.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && TARGET.equals(call.owner)
                    && "nextStep".equals(call.name)
                    && "()V".equals(call.desc)) {
                call.name = "futureNextStep";
                return write(node);
            }
        }
        throw new AssertionError("nextStep fixture call missing");
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
