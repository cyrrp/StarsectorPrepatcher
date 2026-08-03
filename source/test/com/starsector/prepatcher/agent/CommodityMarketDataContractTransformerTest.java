package com.starsector.prepatcher.agent;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.VarInsnNode;

import java.nio.file.Path;
import java.util.jar.JarFile;

/** Exact constructor argument/data-flow contract and future-drift rejection. */
public final class CommodityMarketDataContractTransformerTest {
    private CommodityMarketDataContractTransformerTest() {}

    public static void main(String[] args) throws Exception {
        require(args.length == 1,
                "Usage: CommodityMarketDataContractTransformerTest <starfarer_obf.jar>");
        byte[] original = readClass(Path.of(args[0]),
                CommodityMarketDataContractTransformer.TARGET);
        CommodityMarketDataContractTransformer transformer =
                new CommodityMarketDataContractTransformer(true, null);

        require(transformer.transform(null, CommodityMarketDataContractTransformer.TARGET,
                        null, null, original) == null,
                "read-only contract transformer unexpectedly changed bytes");
        require("READY".equals(System.getProperty(
                        "starsector.prepatcher.commodityMarketDataContract")),
                "exact constructor contract was not accepted");
        require(transformer.transform(null, CommodityMarketDataContractTransformer.TARGET,
                        null, null, original) == null,
                "idempotent contract validation changed bytes");

        ClassNode future = read(original);
        MethodNode constructor = method(future, "<init>",
                "(Ljava/lang/String;Ljava/lang/String;)V");
        MethodInsnNode getMarkets = uniqueCall(constructor,
                "com/fs/starfarer/api/campaign/econ/EconomyAPI", "getMarketsInGroup",
                "(Ljava/lang/String;)Ljava/util/List;");
        AbstractInsnNode argument = previousMeaningful(getMarkets);
        require(argument instanceof VarInsnNode && argument.getOpcode() == Opcodes.ALOAD,
                "fixture did not find getMarketsInGroup argument");
        ((VarInsnNode) argument).var = 1;
        require(transformer.transform(null, CommodityMarketDataContractTransformer.TARGET,
                        null, null, write(future)) == null,
                "future argument-flow fixture unexpectedly changed bytes");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.prepatcher.commodityMarketDataContract")),
                "future argument-flow drift was not rejected");

        System.out.println("OK CommodityMarketData contract exact flow/idempotency/future drift");
    }

    private static byte[] readClass(Path jarPath, String internalName) throws Exception {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entry = jar.getJarEntry(internalName + ".class");
            require(entry != null, "missing " + internalName);
            try (var in = jar.getInputStream(entry)) {
                return in.readAllBytes();
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

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
