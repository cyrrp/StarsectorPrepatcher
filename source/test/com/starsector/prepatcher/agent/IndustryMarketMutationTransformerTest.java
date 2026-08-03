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

/** Exact vanilla industry mutation wrappers and shared-helper fail-closed guard. */
public final class IndustryMarketMutationTransformerTest {
    private static final String RUNTIME =
            "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge";
    private static final String INDUSTRY =
            "com/fs/starfarer/api/campaign/econ/Industry";
    private static final String MARKET_API =
            "com/fs/starfarer/api/campaign/econ/MarketAPI";
    private static final String MODE =
            "com/fs/starfarer/api/campaign/econ/MarketAPI$MarketInteractionMode";

    private IndustryMarketMutationTransformerTest() {}

    public static void main(String[] args) throws Exception {
        require(args.length == 1,
                "Usage: IndustryMarketMutationTransformerTest <starfarer_obf.jar>");
        Path jar = Path.of(args[0]);
        IndustryMarketMutationTransformer transformer =
                new IndustryMarketMutationTransformer(true, null);

        byte[] dialogOriginal = readClass(jar,
                IndustryMarketMutationTransformer.INDUSTRY_DIALOG);
        byte[] dialogPatched = transformer.transform(null,
                IndustryMarketMutationTransformer.INDUSTRY_DIALOG,
                null, null, dialogOriginal);
        require(dialogPatched != null, "industry dialog was not patched");
        inspectDialog(dialogPatched);
        verify(dialogPatched);
        require(transformer.transform(null,
                IndustryMarketMutationTransformer.INDUSTRY_DIALOG,
                null, null, dialogPatched) == null,
                "dialog idempotent processing changed bytes");
        require("awaiting-targets".equals(System.getProperty(
                        IndustryMarketMutationTransformer.groupStatusProperty())),
                "dialog-only post-state activated the atomic group");

        byte[] panelOriginal = readClass(jar,
                IndustryMarketMutationTransformer.INDUSTRY_PANEL);
        byte[] panelPatched = transformer.transform(null,
                IndustryMarketMutationTransformer.INDUSTRY_PANEL,
                null, null, panelOriginal);
        require(panelPatched != null, "industry panel was not patched");
        inspectPanel(panelPatched);
        verify(panelPatched);
        require(transformer.transform(null,
                IndustryMarketMutationTransformer.INDUSTRY_PANEL,
                null, null, panelPatched) == null,
                "panel idempotent processing changed bytes");
        require("ready".equals(System.getProperty(
                        IndustryMarketMutationTransformer.groupStatusProperty())),
                "combined dialog/panel post-state did not activate the group");

        IndustryMarketMutationTransformer partial =
                new IndustryMarketMutationTransformer(true, null);
        require(partial.transform(null,
                IndustryMarketMutationTransformer.INDUSTRY_DIALOG,
                null, null, dialogOriginal) != null,
                "partial-group dialog fixture was not patched");
        ClassNode futurePanel = read(panelOriginal);
        MethodNode recreate = method(futurePanel, "recreateOverview", "()V");
        uniqueCall(recreate, "com/fs/starfarer/api/campaign/econ/EconomyAPI",
                "tripleStep", "()V").name = "futureTripleStep";
        require(partial.transform(null,
                IndustryMarketMutationTransformer.INDUSTRY_PANEL,
                null, null, write(futurePanel)) == null,
                "partial-group panel drift did not fail closed");
        require("disabled-structural-mismatch".equals(System.getProperty(
                        IndustryMarketMutationTransformer.groupStatusProperty())),
                "partial-group failure did not disable the atomic group");

        ClassNode future = read(dialogOriginal);
        MethodNode dismissed = method(future, "dialogDismissed",
                "(Lcom/fs/starfarer/ui/oo0O;I)V");
        MethodInsnNode start = uniqueCall(dismissed, INDUSTRY,
                "startUpgrading", "()V");
        start.name = "futureStartUpgrading";
        require(transformer.transform(null,
                IndustryMarketMutationTransformer.INDUSTRY_DIALOG,
                null, null, write(future)) == null,
                "future dialog drift did not fail closed");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                IndustryMarketMutationTransformer.statusProperty(
                        IndustryMarketMutationTransformer.INDUSTRY_DIALOG))),
                "future dialog status is not SKIPPED_STRUCTURAL");

        System.out.println("OK atomic industry mutations: exact five branches, "
                + "one-shot shared helper guard, custom-option fallback, idempotent, fail-closed");
    }

    private static void inspectDialog(byte[] bytes) {
        ClassNode node = read(bytes);
        require(field(node, "spp$patched$industryMarketMutations") != null,
                "dialog marker missing");
        MethodNode method = method(node, "dialogDismissed",
                "(Lcom/fs/starfarer/ui/oo0O;I)V");
        require(calls(method, RUNTIME, "applyVanillaIndustryStartUpgrading",
                "(L" + INDUSTRY + ";)V") == 1, "start wrapper mismatch");
        require(calls(method, RUNTIME, "applyVanillaIndustryDowngrade",
                "(L" + INDUSTRY + ";)V") == 1, "downgrade wrapper mismatch");
        require(calls(method, RUNTIME, "applyVanillaIndustryCancelUpgrade",
                "(L" + INDUSTRY + ";)V") == 1, "cancel wrapper mismatch");
        require(calls(method, RUNTIME, "applyVanillaIndustryRemoval",
                "(L" + MARKET_API + ";Ljava/lang/String;L" + MODE + ";Z)V") == 2,
                "remove wrapper mismatch");
        require(calls(method, INDUSTRY, "startUpgrading", "()V") == 0,
                "original start call remains");
        require(calls(method, MARKET_API, "removeIndustry",
                "(Ljava/lang/String;L" + MODE + ";Z)V") == 0,
                "original remove calls remain");
        require(calls(method,
                IndustryMarketMutationTransformer.INDUSTRY_PANEL,
                "recreateOverview", "()V") == 7,
                "unknown/custom shared-helper calls were removed");
    }

    private static void inspectPanel(byte[] bytes) {
        ClassNode node = read(bytes);
        require(field(node, "spp$patched$industryOverviewMutationGuard") != null,
                "panel marker missing");
        MethodNode method = method(node, "recreateOverview", "()V");
        require(calls(method, RUNTIME, "shouldHandleVanillaUiMutationEconomyStep",
                "(Ljava/lang/Object;Ljava/lang/Object;)Z") == 1,
                "panel guard missing");
        require(calls(method, "com/fs/starfarer/api/campaign/econ/EconomyAPI",
                "tripleStep", "()V") == 1,
                "global fallback tripleStep was removed");
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
