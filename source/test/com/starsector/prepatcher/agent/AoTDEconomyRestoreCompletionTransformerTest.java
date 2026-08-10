package com.starsector.prepatcher.agent;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.InsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Analyzer;
import jdk.internal.org.objectweb.asm.tree.analysis.BasicValue;
import jdk.internal.org.objectweb.asm.tree.analysis.BasicVerifier;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.jar.JarFile;

/** Exact-current CoreLifecycle successful-tail transformation and drift fixtures. */
public final class AoTDEconomyRestoreCompletionTransformerTest {
    private static final String ENTRY =
            AoTDEconomyRestoreCompletionTransformer.TARGET + ".class";
    private static final String RUNTIME =
            "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge";
    private static final String HOOK = "publishAoTDEconomyRestoreComplete";

    private AoTDEconomyRestoreCompletionTransformerTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected starfarer.api.jar path");
        }
        byte[] original = readClass(Path.of(args[0]));
        AoTDEconomyRestoreCompletionTransformer transformer =
                new AoTDEconomyRestoreCompletionTransformer(true, null);

        byte[] transformed = transformer.transform(
                ClassLoader.getSystemClassLoader(),
                AoTDEconomyRestoreCompletionTransformer.TARGET,
                null, null, original);
        require(transformed != null, "exact CoreLifecycle method did not transform");
        require("APPLIED".equals(System.getProperty(
                        AoTDEconomyRestoreCompletionTransformer.statusProperty())),
                "applied status missing");
        inspectPostcondition(transformed);
        verifyMethod(transformed);

        require(transformer.transform(
                        ClassLoader.getSystemClassLoader(),
                        AoTDEconomyRestoreCompletionTransformer.TARGET,
                        null, null, transformed) == null,
                "fully transformed method was not idempotent");
        require("ALREADY_PATCHED".equals(System.getProperty(
                        AoTDEconomyRestoreCompletionTransformer.statusProperty())),
                "idempotent status missing");

        AoTDEconomyRestoreCompletionTransformer disabled =
                new AoTDEconomyRestoreCompletionTransformer(false, null);
        require(disabled.transform(
                        ClassLoader.getSystemClassLoader(),
                        AoTDEconomyRestoreCompletionTransformer.TARGET,
                        null, null, original) == null,
                "disabled restore transformer changed bytecode");
        require("DISABLED".equals(System.getProperty(
                        AoTDEconomyRestoreCompletionTransformer.statusProperty())),
                "disabled status missing");

        rejectStructural(transformer, mutate(original, method ->
                removeCall(method,
                        "com/fs/starfarer/api/campaign/econ/MarketAPI",
                        "reapplyIndustries", "()V")),
                "missing reapplyIndustries");
        rejectStructural(transformer, mutate(original, method ->
                method.instructions.insertBefore(
                        method.instructions.getFirst(), new InsnNode(Opcodes.RETURN))),
                "additional successful return");
        rejectStructural(transformer, mutate(original, method -> {
            MethodInsnNode conditions = findCall(method,
                    "com/fs/starfarer/api/campaign/econ/MarketAPI",
                    "reapplyConditions", "()V");
            MethodInsnNode industries = findCall(method,
                    "com/fs/starfarer/api/campaign/econ/MarketAPI",
                    "reapplyIndustries", "()V");
            method.instructions.remove(conditions);
            method.instructions.insert(industries, conditions);
        }), "reordered market reapply calls");
        rejectStructural(transformer, mutate(transformed, method -> {
            AbstractInsnNode successfulReturn = findOpcode(method, Opcodes.RETURN);
            method.instructions.insertBefore(successfulReturn, new InsnNode(Opcodes.NOP));
        }), "displaced existing hook");

        System.out.println("OK AoTD economy restore CoreLifecycle hook exact-tail/"
                + "idempotency/postcondition/drift/verification bytes=" + transformed.length);
    }

    private static void inspectPostcondition(byte[] bytes) {
        MethodNode method = method(bytes);
        int hookCount = 0;
        int returnCount = 0;
        MethodInsnNode hook = null;
        AbstractInsnNode successfulReturn = null;
        MethodInsnNode postRestore = findCall(method,
                "com/fs/starfarer/api/campaign/econ/Industry",
                "doPostSaveRestore", "()V");
        MethodInsnNode conditions = findCall(method,
                "com/fs/starfarer/api/campaign/econ/MarketAPI",
                "reapplyConditions", "()V");
        MethodInsnNode industries = findCall(method,
                "com/fs/starfarer/api/campaign/econ/MarketAPI",
                "reapplyIndustries", "()V");
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                returnCount++;
                successfulReturn = instruction;
            }
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && RUNTIME.equals(call.owner)
                    && HOOK.equals(call.name)
                    && "()V".equals(call.desc)) {
                hookCount++;
                hook = call;
            }
        }
        require(returnCount == 1, "successful return count changed: " + returnCount);
        require(hookCount == 1, "hook count changed: " + hookCount);
        require(previousMeaningful(successfulReturn) == hook,
                "hook is not immediately before the successful return");
        require(method.instructions.indexOf(postRestore)
                        < method.instructions.indexOf(conditions),
                "hook fixture lost post-restore/reapply order");
        require(method.instructions.indexOf(conditions)
                        < method.instructions.indexOf(industries),
                "hook fixture lost condition/industry order");
        require(method.instructions.indexOf(industries)
                        < method.instructions.indexOf(hook),
                "hook executes before all market reapply work");
    }

    private static void verifyMethod(byte[] bytes) throws Exception {
        MethodNode method = method(bytes);
        Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicVerifier());
        analyzer.analyze(AoTDEconomyRestoreCompletionTransformer.TARGET, method);
    }

    private static void rejectStructural(
            AoTDEconomyRestoreCompletionTransformer transformer,
            byte[] changed,
            String label) {
        require(transformer.transform(
                        ClassLoader.getSystemClassLoader(),
                        AoTDEconomyRestoreCompletionTransformer.TARGET,
                        null, null, changed) == null,
                label + " was transformed");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        AoTDEconomyRestoreCompletionTransformer.statusProperty())),
                label + " did not publish structural rejection");
    }

    private static byte[] mutate(byte[] source, Consumer<MethodNode> mutation) {
        ClassNode node = new ClassNode();
        new ClassReader(source).accept(node, 0);
        MethodNode method = requireMethod(node);
        mutation.accept(method);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void removeCall(
            MethodNode method, String owner, String name, String desc) {
        method.instructions.remove(findCall(method, owner, name, desc));
    }

    private static MethodInsnNode findCall(
            MethodNode method, String owner, String name, String desc) {
        MethodInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !owner.equals(call.owner)
                    || !name.equals(call.name)
                    || !desc.equals(call.desc)) continue;
            require(found == null, "call is not unique: " + owner + '.' + name + desc);
            found = call;
        }
        require(found != null, "call missing: " + owner + '.' + name + desc);
        return found;
    }

    private static AbstractInsnNode findOpcode(MethodNode method, int opcode) {
        AbstractInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() != opcode) continue;
            require(found == null, "opcode is not unique: " + opcode);
            found = instruction;
        }
        require(found != null, "opcode missing: " + opcode);
        return found;
    }

    private static MethodNode method(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return requireMethod(node);
    }

    private static MethodNode requireMethod(ClassNode node) {
        MethodNode found = null;
        for (MethodNode method : node.methods) {
            if (!AoTDEconomyRestoreCompletionTransformer.METHOD.equals(method.name)
                    || !AoTDEconomyRestoreCompletionTransformer.METHOD_DESC.equals(method.desc)) {
                continue;
            }
            require(found == null, "duplicate econPostSaveRestore");
            found = method;
        }
        require(found != null, "econPostSaveRestore missing");
        return found;
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static byte[] readClass(Path jarPath) throws Exception {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entry = jar.getJarEntry(ENTRY);
            require(entry != null, "missing " + ENTRY + " in " + jarPath);
            try (InputStream input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
