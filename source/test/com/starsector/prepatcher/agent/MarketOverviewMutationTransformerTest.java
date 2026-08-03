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

/** Exact market-policy call-site, guard, idempotency and negative gate. */
public final class MarketOverviewMutationTransformerTest {
    private static final String TARGET = MarketOverviewMutationTransformer.TARGET;
    private static final String RUNTIME =
            "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge";
    private static final String MARKET_API =
            "com/fs/starfarer/api/campaign/econ/MarketAPI";

    private MarketOverviewMutationTransformerTest() {}

    public static void main(String[] args) throws Exception {
        require(args.length == 1,
                "Usage: MarketOverviewMutationTransformerTest <starfarer_obf.jar>");
        byte[] original = readClass(Path.of(args[0]), TARGET);
        MarketOverviewMutationTransformer transformer =
                new MarketOverviewMutationTransformer(true, null);
        byte[] patched = transformer.transform(null, TARGET, null, null, original);
        require(patched != null, "exact market overview was not patched: "
                + System.getProperty(MarketOverviewMutationTransformer.statusProperty()));
        inspect(patched);
        verify(patched);

        require(transformer.transform(null, TARGET, null, null, patched) == null,
                "idempotent reprocessing changed bytes");
        require("ALREADY_APPLIED".equals(System.getProperty(
                        MarketOverviewMutationTransformer.statusProperty())),
                "idempotence status changed");

        ClassNode future = read(original);
        MethodNode action = method(future, "actionPerformed",
                "(Ljava/lang/Object;Ljava/lang/Object;)V");
        MethodInsnNode target = uniqueCall(action, MARKET_API,
                "setUseStockpilesForShortages", "(Z)V");
        target.name = "futureStockpilePolicy";
        require(transformer.transform(null, TARGET, null, null, write(future)) == null,
                "changed policy branch did not fail closed");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        MarketOverviewMutationTransformer.statusProperty())),
                "future fixture status is not SKIPPED_STRUCTURAL");

        MarketOverviewMutationTransformer disabled =
                new MarketOverviewMutationTransformer(false, null);
        require(disabled.transform(null, TARGET, null, null, original) == null,
                "disabled transformer changed class");
        System.out.println("OK market overview mutation transformer: exact policy branches, "
                + "shared-helper one-shot guard, global fallback, idempotent, fail-closed");
    }

    private static void inspect(byte[] bytes) {
        ClassNode node = read(bytes);
        require(field(node, "spp$patched$marketOverviewMutationRefresh") != null,
                "owned marker missing");
        MethodNode action = method(node, "actionPerformed",
                "(Ljava/lang/Object;Ljava/lang/Object;)V");
        MethodNode recreate = method(node, "recreateWithEconUpdate", "()V");
        require(calls(action, RUNTIME, "applyVanillaFreePortMutation",
                "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;Z)V") == 2,
                "free-port wrapper count mismatch");
        require(calls(action, RUNTIME, "applyVanillaImmigrationClosedMutation",
                "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;Z)V") == 2,
                "immigration-closed wrapper count mismatch");
        require(calls(action, RUNTIME, "applyVanillaStockpilePolicyMutation",
                "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;Z)V") == 1,
                "stockpile wrapper count mismatch");
        require(calls(action, RUNTIME, "applyVanillaImmigrationIncentivesMutation",
                "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;Ljava/lang/Boolean;)V") == 1,
                "incentive wrapper count mismatch");
        require(calls(recreate, RUNTIME, "shouldHandleVanillaUiMutationEconomyStep",
                "(Ljava/lang/Object;Ljava/lang/Object;)Z") == 1,
                "shared-helper guard missing");
        require(calls(recreate, "com/fs/starfarer/api/campaign/econ/EconomyAPI",
                "tripleStep", "()V") == 1,
                "global fallback call was removed");
    }

    private static byte[] readClass(Path jarPath, String internalName) throws Exception {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entry = jar.getJarEntry(internalName + ".class");
            require(entry != null, "missing " + internalName);
            try (var input = jar.getInputStream(entry)) { return input.readAllBytes(); }
        }
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
        throw new AssertionError("missing method " + name + desc);
    }

    private static MethodInsnNode uniqueCall(
            MethodNode method, String owner, String name, String desc) {
        MethodInsnNode result = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && desc.equals(call.desc)) {
                require(result == null, "duplicate call " + name);
                result = call;
            }
        }
        require(result != null, "missing call " + name);
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

    private static FieldNode field(ClassNode node, String name) {
        for (FieldNode field : node.fields) if (name.equals(field.name)) return field;
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
