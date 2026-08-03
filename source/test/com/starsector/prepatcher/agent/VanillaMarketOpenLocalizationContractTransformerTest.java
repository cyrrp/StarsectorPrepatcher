package com.starsector.prepatcher.agent;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

/** Raw/final exact-body gate and future fail-closed coverage for market-open localization. */
public final class VanillaMarketOpenLocalizationContractTransformerTest {
    private VanillaMarketOpenLocalizationContractTransformerTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected starfarer_obf.jar");
        }
        byte[] economy = readEntry(args[0],
                VanillaMarketOpenLocalizationContractTransformer.ECONOMY + ".class");
        byte[] reach = readEntry(args[0],
                VanillaMarketOpenLocalizationContractTransformer.REACH + ".class");

        VanillaMarketOpenLocalizationContractTransformer raw =
                new VanillaMarketOpenLocalizationContractTransformer(true, null);
        require(raw.transform(null,
                        VanillaMarketOpenLocalizationContractTransformer.ECONOMY,
                        null, null, economy) == null,
                "contract transformer changed Economy bytes");
        require(raw.transform(null,
                        VanillaMarketOpenLocalizationContractTransformer.REACH,
                        null, null, reach) == null,
                "contract transformer changed ReachEconomy bytes");
        require("READY".equals(System.getProperty(
                        "starsector.prepatcher.vanillaMarketOpenLocalizationContract")),
                "raw market-open localization contracts were not READY");

        Path configFile = Files.createTempFile(
                "prepatcher-market-open-contract", ".properties");
        try {
            Files.writeString(configFile,
                    "patch.vanillaMarketOpenLocalization=true\n"
                            + "patch.marketScheduler=true\n",
                    StandardCharsets.UTF_8);
            PrepatcherConfig config = PrepatcherConfig.load(configFile);
            PrepatcherTransformer structural = new PrepatcherTransformer(config, null);
            byte[] finalEconomy = structural.transform(null,
                    VanillaMarketOpenLocalizationContractTransformer.ECONOMY,
                    null, null, economy);
            byte[] finalReach = structural.transform(null,
                    VanillaMarketOpenLocalizationContractTransformer.REACH,
                    null, null, reach);
            VanillaMarketOpenLocalizationContractTransformer finalGate =
                    new VanillaMarketOpenLocalizationContractTransformer(true, null);
            finalGate.transform(null,
                    VanillaMarketOpenLocalizationContractTransformer.ECONOMY,
                    null, null, finalEconomy == null ? economy : finalEconomy);
            finalGate.transform(null,
                    VanillaMarketOpenLocalizationContractTransformer.REACH,
                    null, null, finalReach == null ? reach : finalReach);
            require("READY".equals(System.getProperty(
                            "starsector.prepatcher.vanillaMarketOpenLocalizationContract")),
                    "post-structural market-open localization contracts were not READY");
        } finally {
            Files.deleteIfExists(configFile);
        }

        VanillaMarketOpenLocalizationContractTransformer futureGate =
                new VanillaMarketOpenLocalizationContractTransformer(true, null);
        futureGate.transform(null,
                VanillaMarketOpenLocalizationContractTransformer.ECONOMY,
                null, null, changeEconomyDelegate(economy));
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.prepatcher.vanillaMarketOpenLocalizationEconomyContract")),
                "changed Economy contract did not fail closed");
        futureGate.transform(null,
                VanillaMarketOpenLocalizationContractTransformer.REACH,
                null, null, changeReachPhase(reach));
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.prepatcher.vanillaMarketOpenLocalizationReachContract")),
                "changed ReachEconomy contract did not fail closed");

        System.out.println("OK vanilla-market-open-localization contract raw/final/"
                + "economy-future/reach-future fail-closed");
    }

    private static byte[] changeEconomyDelegate(byte[] bytes) {
        ClassNode node = read(bytes);
        MethodNode method = method(node, "nextStep", "()V");
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && "nextStep".equals(call.name)) {
                call.name = "futureNextStep";
                return write(node);
            }
        }
        throw new AssertionError("Economy nextStep delegate missing");
    }

    private static byte[] changeReachPhase(byte[] bytes) {
        ClassNode node = read(bytes);
        MethodNode method = method(node, "nextStep",
                "(Lcom/fs/starfarer/campaign/econ/reach/MainWorkTask$EconWorkParams;)V");
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && "com/fs/starfarer/campaign/econ/contract/iter/MultiFrameTask"
                            .equals(call.owner)
                    && "doNextBatch".equals(call.name)) {
                call.name = "futureDoNextBatch";
                return write(node);
            }
        }
        throw new AssertionError("ReachEconomy task drain missing");
    }

    private static byte[] readEntry(String jarPath, String name) throws Exception {
        try (JarFile jar = new JarFile(Path.of(jarPath).toFile())) {
            var entry = jar.getJarEntry(name);
            require(entry != null, "missing " + name);
            try (var input = jar.getInputStream(entry)) {
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
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) return method;
        }
        throw new AssertionError("missing method " + name + desc);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
