package com.starsector.prepatcher.agent;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.FieldNode;
import jdk.internal.org.objectweb.asm.tree.JumpInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.VarInsnNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Analyzer;
import jdk.internal.org.objectweb.asm.tree.analysis.BasicValue;
import jdk.internal.org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/** Exact structural, negative and idempotency gate for read-only UI removals. */
public final class ReadOnlyUiEconomyStepTransformerTest {
    private static final String ECONOMY_API =
            "com/fs/starfarer/api/campaign/econ/EconomyAPI";
    private static final String MARKER =
            "spp$patched$readOnlyUiNoGlobalEconomyStep";

    private ReadOnlyUiEconomyStepTransformerTest() {}

    public static void main(String[] args) throws Exception {
        require(args.length == 2 || args.length == 3,
                "Usage: ReadOnlyUiEconomyStepTransformerTest "
                        + "<starfarer_obf.jar> <starfarer.api.jar> [ExerelinCore.jar]");
        Path obf = Path.of(args[0]);
        Path api = Path.of(args[1]);
        Map<String, byte[]> targets = new LinkedHashMap<>();
        targets.put(ReadOnlyUiEconomyStepTransformer.COMMAND_TAB,
                readClass(obf, ReadOnlyUiEconomyStepTransformer.COMMAND_TAB));
        targets.put(ReadOnlyUiEconomyStepTransformer.COMMODITY_DETAIL_V2,
                readClass(obf, ReadOnlyUiEconomyStepTransformer.COMMODITY_DETAIL_V2));
        targets.put(ReadOnlyUiEconomyStepTransformer.COMMODITY_DETAIL_LEGACY,
                readClass(obf, ReadOnlyUiEconomyStepTransformer.COMMODITY_DETAIL_LEGACY));
        targets.put(ReadOnlyUiEconomyStepTransformer.MARKET_CMD,
                readClass(api, ReadOnlyUiEconomyStepTransformer.MARKET_CMD));
        if (args.length == 3) {
            targets.put(ReadOnlyUiEconomyStepTransformer.NEX_MARKET_CMD,
                    readClass(Path.of(args[2]),
                            ReadOnlyUiEconomyStepTransformer.NEX_MARKET_CMD));
        }

        ReadOnlyUiEconomyStepTransformer all =
                new ReadOnlyUiEconomyStepTransformer(true, true, true, null);
        for (Map.Entry<String, byte[]> entry : targets.entrySet()) {
            String target = entry.getKey();
            ClassNode raw = read(entry.getValue());
            require(countCalls(raw, ECONOMY_API, "tripleStep", "()V") == 1,
                    target + " raw tripleStep contract changed");
            byte[] transformed = all.transform(
                    null, target, null, null, entry.getValue());
            require(transformed != null, target + " was not transformed: "
                    + System.getProperty(
                    ReadOnlyUiEconomyStepTransformer.statusProperty(target)));
            ClassNode patched = read(transformed);
            require(field(patched, MARKER) != null,
                    target + " marker missing");
            require(countCalls(patched, ECONOMY_API, "tripleStep", "()V") == 0,
                    target + " still invokes EconomyAPI.tripleStep");
            verify(patched);

            byte[] duplicate = all.transform(null, target, null, null, transformed);
            require(duplicate == null, target + " idempotent reprocessing changed bytes");
            require("ALREADY_APPLIED".equals(System.getProperty(
                            ReadOnlyUiEconomyStepTransformer.statusProperty(target))),
                    target + " idempotency status changed");
        }

        verifyFeatureIsolation(targets);
        verifyNegativeFixtures(targets);
        if (targets.containsKey(ReadOnlyUiEconomyStepTransformer.NEX_MARKET_CMD)) {
            verifyNexLoaderPolicy(targets);
        }
        System.out.println("OK read-only UI economy-step transformer: "
                + "Command + commodity V2/legacy + vanilla/Nex MarketCMD defenses, "
                + "independent flags, structural fail-closed, idempotent, ASM verified");
    }

    private static void verifyFeatureIsolation(Map<String, byte[]> targets) {
        ReadOnlyUiEconomyStepTransformer commodityOnly =
                new ReadOnlyUiEconomyStepTransformer(false, true, false, null);
        require(commodityOnly.transform(null,
                        ReadOnlyUiEconomyStepTransformer.COMMAND_TAB,
                        null, null,
                        targets.get(ReadOnlyUiEconomyStepTransformer.COMMAND_TAB)) == null,
                "command target ignored disabled flag");
        require(commodityOnly.transform(null,
                        ReadOnlyUiEconomyStepTransformer.COMMODITY_DETAIL_V2,
                        null, null,
                        targets.get(ReadOnlyUiEconomyStepTransformer.COMMODITY_DETAIL_V2)) != null,
                "commodity target ignored enabled flag");
        require(commodityOnly.transform(null,
                        ReadOnlyUiEconomyStepTransformer.MARKET_CMD,
                        null, null,
                        targets.get(ReadOnlyUiEconomyStepTransformer.MARKET_CMD)) == null,
                "MarketCMD target ignored disabled flag");
        if (targets.containsKey(ReadOnlyUiEconomyStepTransformer.NEX_MARKET_CMD)) {
            require(commodityOnly.transform(null,
                            ReadOnlyUiEconomyStepTransformer.NEX_MARKET_CMD,
                            null, null,
                            targets.get(ReadOnlyUiEconomyStepTransformer.NEX_MARKET_CMD)) == null,
                    "Nex_MarketCMD target ignored disabled flag");
        }
    }

    private static void verifyNegativeFixtures(Map<String, byte[]> targets) {
        ReadOnlyUiEconomyStepTransformer all =
                new ReadOnlyUiEconomyStepTransformer(true, true, true, null);

        ClassNode command = read(targets.get(
                ReadOnlyUiEconomyStepTransformer.COMMAND_TAB));
        uniqueCall(command, ECONOMY_API, "tripleStep", "()V").name = "doubleStep";
        require(all.transform(null, command.name, null, null, write(command)) == null,
                "changed Command call site did not fail closed");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        ReadOnlyUiEconomyStepTransformer.statusProperty(command.name))),
                "changed Command status is not SKIPPED_STRUCTURAL");

        ClassNode detail = read(targets.get(
                ReadOnlyUiEconomyStepTransformer.COMMODITY_DETAIL_V2));
        MethodInsnNode detailTriple = uniqueCall(
                detail, ECONOMY_API, "tripleStep", "()V");
        AbstractInsnNode next = nextMeaningful(detailTriple);
        require(next != null, "commodity negative fixture anchor missing");
        require(next instanceof VarInsnNode,
                "commodity negative fixture first anchor is not a variable load");
        ((VarInsnNode) next).var = 2;
        require(all.transform(null, detail.name, null, null, write(detail)) == null,
                "changed commodity-detail anchor did not fail closed");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        ReadOnlyUiEconomyStepTransformer.statusProperty(detail.name))),
                "changed commodity-detail status is not SKIPPED_STRUCTURAL");

        for (String target : List.of(
                ReadOnlyUiEconomyStepTransformer.MARKET_CMD,
                ReadOnlyUiEconomyStepTransformer.NEX_MARKET_CMD)) {
            if (!targets.containsKey(target)) continue;
            ClassNode market = read(targets.get(target));
            MethodInsnNode marketTriple = uniqueCall(
                    market, ECONOMY_API, "tripleStep", "()V");
            AbstractInsnNode getEconomy = previousMeaningful(marketTriple);
            AbstractInsnNode getSector = previousMeaningful(getEconomy);
            AbstractInsnNode branch = previousMeaningful(getSector);
            require(branch instanceof JumpInsnNode,
                    target + " negative fixture branch missing");
            ((JumpInsnNode) branch).setOpcode(Opcodes.IFNONNULL);
            require(all.transform(null, market.name, null, null, write(market)) == null,
                    "changed " + target + " guard did not fail closed");
            require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                            ReadOnlyUiEconomyStepTransformer.statusProperty(market.name))),
                    "changed " + target + " status is not SKIPPED_STRUCTURAL");
        }

        if (targets.containsKey(ReadOnlyUiEconomyStepTransformer.NEX_MARKET_CMD)) {
            ClassNode futureNex = read(targets.get(
                    ReadOnlyUiEconomyStepTransformer.NEX_MARKET_CMD));
            futureNex.superName =
                    "com/fs/starfarer/api/impl/campaign/rulecmd/salvage/FutureNexMarketCMD";
            require(all.transform(null, futureNex.name, null, null, write(futureNex)) == null,
                    "future Nex_MarketCMD hierarchy did not fail closed");
            require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                            ReadOnlyUiEconomyStepTransformer.statusProperty(futureNex.name))),
                    "future Nex_MarketCMD status is not SKIPPED_STRUCTURAL");
        }
    }

    private static void verifyNexLoaderPolicy(Map<String, byte[]> targets) {
        ClassLoader runtimeLoader =
                ReadOnlyUiEconomyStepTransformerTest.class.getClassLoader();
        ClassLoader childModLoader = new ClassLoader(runtimeLoader) {};
        ClassLoader unrelatedLoader = new ClassLoader(null) {};
        ReadOnlyUiEconomyStepTransformer production =
                new ReadOnlyUiEconomyStepTransformer(true, true, true, runtimeLoader);
        byte[] nex = targets.get(ReadOnlyUiEconomyStepTransformer.NEX_MARKET_CMD);
        require(production.transform(childModLoader,
                        ReadOnlyUiEconomyStepTransformer.NEX_MARKET_CMD,
                        null, null, nex) != null,
                "Nex_MarketCMD child mod loader was rejected");
        require(production.transform(null,
                        ReadOnlyUiEconomyStepTransformer.NEX_MARKET_CMD,
                        null, null, nex) == null,
                "Nex_MarketCMD bootstrap-loader copy was accepted");
        require("SKIPPED_LOADER".equals(System.getProperty(
                        ReadOnlyUiEconomyStepTransformer.statusProperty(
                                ReadOnlyUiEconomyStepTransformer.NEX_MARKET_CMD))),
                "Nex_MarketCMD bootstrap-loader rejection status changed");
        require(production.transform(unrelatedLoader,
                        ReadOnlyUiEconomyStepTransformer.NEX_MARKET_CMD,
                        null, null, nex) == null,
                "Nex_MarketCMD unrelated-loader copy was accepted");
        require(production.transform(childModLoader,
                        ReadOnlyUiEconomyStepTransformer.MARKET_CMD,
                        null, null,
                        targets.get(ReadOnlyUiEconomyStepTransformer.MARKET_CMD)) == null,
                "vanilla MarketCMD accepted the child mod loader");
    }

    private static byte[] readClass(Path jarPath, String internalName) throws Exception {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entry = jar.getJarEntry(internalName + ".class");
            require(entry != null, "missing " + internalName + " in " + jarPath);
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

    private static void verify(ClassNode node) throws Exception {
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicVerifier());
            analyzer.analyze(node.name, method);
        }
    }

    private static int countCalls(
            ClassNode node, String owner, String name, String desc) {
        int count = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && owner.equals(call.owner) && name.equals(call.name)
                        && desc.equals(call.desc)) count++;
            }
        }
        return count;
    }

    private static MethodInsnNode uniqueCall(
            ClassNode node, String owner, String name, String desc) {
        MethodInsnNode result = null;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && owner.equals(call.owner) && name.equals(call.name)
                        && desc.equals(call.desc)) {
                    require(result == null, "duplicate " + owner + '.' + name + desc);
                    result = call;
                }
            }
        }
        require(result != null, "missing " + owner + '.' + name + desc);
        return result;
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

    private static FieldNode field(ClassNode node, String name) {
        for (FieldNode field : node.fields) if (name.equals(field.name)) return field;
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
