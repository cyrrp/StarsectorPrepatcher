package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarFile;

/** Exact market-policy call-site, guard, idempotency and negative gate. */
public final class MarketOverviewMutationTransformerTest {
    private static final String TARGET = MarketOverviewMutationTransformer.TARGET;
    private static final String RUNTIME =
            "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge";
    private static final String MARKET_API =
            "com/fs/starfarer/api/campaign/econ/MarketAPI";
    private static final String MARKET_DESC = "L" + MARKET_API + ";";
    private static final String LEGACY_MARKET_FIELD = "String.interface$float";
    private static final String REPAIRED_MARKET_FIELD = "String_interface$float";

    private MarketOverviewMutationTransformerTest() {}

    public static void main(String[] args) throws Exception {
        require(args.length == 1,
                "Usage: MarketOverviewMutationTransformerTest <starfarer_obf.jar>");
        byte[] original = readClass(Path.of(args[0]), TARGET);
        byte[] repaired = IllegalObfuscatedMemberNameRepair.repair(TARGET, original);
        require(repaired != original, "market overview fixture was not name-repaired");
        exerciseVariant(original, LEGACY_MARKET_FIELD);
        exerciseVariant(repaired, REPAIRED_MARKET_FIELD);
        assertFieldRejections(original);

        MarketOverviewMutationTransformer disabled =
                new MarketOverviewMutationTransformer(false, null);
        require(disabled.transform(null, TARGET, null, null, original) == null,
                "disabled transformer changed class");
        System.out.println("OK market overview mutation transformer: raw/repaired field aliases, "
                + "exact policy branches, shared-helper one-shot guard, global fallback, "
                + "idempotent, fail-closed");
    }

    private static void exerciseVariant(byte[] original, String expectedMarketField)
            throws Exception {
        MarketOverviewMutationTransformer transformer =
                new MarketOverviewMutationTransformer(true, null);
        byte[] patched = transformer.transform(null, TARGET, null, null, original);
        require(patched != null, "exact market overview was not patched: "
                + System.getProperty(MarketOverviewMutationTransformer.statusProperty()));
        inspect(patched, expectedMarketField);
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
    }

    private static void inspect(byte[] bytes, String expectedMarketField) {
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
        MethodInsnNode guard = uniqueCall(recreate, RUNTIME,
                "shouldHandleVanillaUiMutationEconomyStep",
                "(Ljava/lang/Object;Ljava/lang/Object;)Z");
        AbstractInsnNode marketRead = previousMeaningful(guard);
        require(marketRead instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.GETFIELD
                        && TARGET.equals(field.owner)
                        && expectedMarketField.equals(field.name)
                        && MARKET_DESC.equals(field.desc),
                "guard does not use the selected market-field alias");
    }

    private static void assertFieldRejections(byte[] original) {
        assertRejected(renameMarketField(original, LEGACY_MARKET_FIELD,
                        "future_market_identity"),
                "unknown market-field alias was accepted");

        ClassNode wrongType = read(original);
        field(wrongType, LEGACY_MARKET_FIELD).desc = "Ljava/lang/Object;";
        assertRejected(write(wrongType), "wrong market-field descriptor was accepted");

        ClassNode ambiguous = read(original);
        FieldNode legacy = field(ambiguous, LEGACY_MARKET_FIELD);
        ambiguous.fields.add(new FieldNode(Opcodes.ASM9, legacy.access,
                REPAIRED_MARKET_FIELD, legacy.desc, legacy.signature, null));
        assertRejected(write(ambiguous), "ambiguous market-field aliases were accepted");
    }

    private static void assertRejected(byte[] bytes, String message) {
        byte[] before = bytes.clone();
        MarketOverviewMutationTransformer transformer =
                new MarketOverviewMutationTransformer(true, null);
        require(transformer.transform(null, TARGET, null, null, bytes) == null, message);
        require(Arrays.equals(bytes, before), "rejected market-overview bytes were mutated");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        MarketOverviewMutationTransformer.statusProperty())),
                "market-field rejection status is not SKIPPED_STRUCTURAL");
    }

    private static byte[] renameMarketField(byte[] bytes, String from, String to) {
        ClassNode node = read(bytes);
        FieldNode declaration = field(node, from);
        require(declaration != null, "missing field alias " + from);
        declaration.name = to;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field
                        && TARGET.equals(field.owner) && from.equals(field.name)) {
                    field.name = to;
                }
            }
        }
        return write(node);
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

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode node) {
        AbstractInsnNode current = node.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
