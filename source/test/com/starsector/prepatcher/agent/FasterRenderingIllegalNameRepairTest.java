package com.starsector.prepatcher.agent;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Structural and idempotency checks for Java 27 illegal obfuscator-name repair. */
public final class FasterRenderingIllegalNameRepairTest {
    private static final String CAMPAIGN_STATE =
            "com/fs/starfarer/campaign/CampaignState.class";

    private FasterRenderingIllegalNameRepairTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected starfarer_obf.jar");
        byte[] original = read(Path.of(args[0]), CAMPAIGN_STATE);
        byte[] repaired = IllegalObfuscatedMemberNameRepair.repair(CAMPAIGN_STATE, original);
        require(repaired != original, "CampaignState did not require illegal-name repair");
        require(!Arrays.equals(repaired, original), "CampaignState repair changed no bytes");
        require(originalContains(original, "while.new"), "fixture no longer contains while.new");
        require(!originalContains(repaired, "while.new"), "repaired bytes retain while.new");
        require(originalContains(repaired, "while_new"), "repaired bytes lack while_new");
        require(IllegalObfuscatedMemberNameRepair.repair(CAMPAIGN_STATE, repaired) == repaired,
                "repair is not identity-idempotent");
        verifyExactFieldRepair(Path.of(args[0]), TradeMarketMutationTransformer.TARGET,
                "if.new$class", "if_new$class");
        verifyExactFieldRepair(Path.of(args[0]), MarketOverviewMutationTransformer.TARGET,
                "String.interface$float", "String_interface$float");
        require(IllegalObfuscatedMemberNameRepair.repair("example/Unrelated.class", original)
                        == original,
                "repair changed a non-FR game namespace");
        verifyNoIllegalMethodNames(repaired);
        byte[] synthetic = syntheticDeclarationAndCall();
        byte[] syntheticRepaired = IllegalObfuscatedMemberNameRepair.repair(
                "com/fs/starfarer/api/LegacyFrRepairFixture.class", synthetic);
        verifyDeclarationAndCalls(syntheticRepaired);

        byte[] truncated = Arrays.copyOf(original, 12);
        boolean rejected = false;
        try {
            IllegalObfuscatedMemberNameRepair.repair(CAMPAIGN_STATE, truncated);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "truncated class file was accepted");
        System.out.println("OK Java27 illegal-name repair declaration+calls idempotent");
    }

    private static void verifyExactFieldRepair(
            Path jar, String owner, String legacyName, String repairedName) throws Exception {
        byte[] original = read(jar, owner + ".class");
        byte[] repaired = IllegalObfuscatedMemberNameRepair.repair(owner, original);
        require(repaired != original, owner + " did not require illegal-name repair");
        require(originalContains(original, legacyName),
                owner + " lacks expected legacy field " + legacyName);
        require(!originalContains(repaired, legacyName),
                owner + " retains legacy field " + legacyName);
        require(originalContains(repaired, repairedName),
                owner + " lacks repaired field " + repairedName);
        require(IllegalObfuscatedMemberNameRepair.repair(owner, repaired) == repaired,
                owner + " field repair is not identity-idempotent");

        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(repaired).accept(node, 0);
        int declarations = 0;
        int reads = 0;
        for (FieldNode field : node.fields) {
            require(!legacyName.equals(field.name),
                    owner + " retains legacy field declaration " + legacyName);
            if (repairedName.equals(field.name)) declarations++;
        }
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode field)
                        || !owner.equals(field.owner)) {
                    continue;
                }
                require(!legacyName.equals(field.name),
                        owner + " retains legacy field reference " + legacyName);
                if (repairedName.equals(field.name)) reads++;
            }
        }
        require(declarations == 1,
                owner + " repaired field declaration count changed: " + declarations);
        require(reads > 0, owner + " repaired field has no references");
    }

    private static void verifyDeclarationAndCalls(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(node, 0);
        int declarations = 0;
        int calls = 0;
        for (MethodNode method : node.methods) {
            require(!method.name.contains("."), "illegal method declaration remains: " + method.name);
            if ("while_new".equals(method.name)) declarations++;
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                 instruction != null; instruction = instruction.getNext()) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                require(!call.name.contains("."), "illegal method call remains: " + call.name);
                if ("while_new".equals(call.name)) calls++;
            }
        }
        require(declarations > 0, "repaired declaration was not found");
        require(calls > 0, "repaired call was not found");
    }

    private static void verifyNoIllegalMethodNames(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(node, 0);
        for (MethodNode method : node.methods) {
            require(!method.name.contains("."), "illegal method declaration remains: " + method.name);
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                 instruction != null; instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call) {
                    require(!call.name.contains("."), "illegal method call remains: " + call.name);
                }
            }
        }
    }

    private static byte[] syntheticDeclarationAndCall() {
        String owner = "com/fs/starfarer/api/LegacyFrRepairFixture";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        MethodVisitor declaration = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "while.new", "()I", null, null);
        declaration.visitCode();
        declaration.visitIntInsn(Opcodes.BIPUSH, 42);
        declaration.visitInsn(Opcodes.IRETURN);
        declaration.visitMaxs(1, 0);
        declaration.visitEnd();
        MethodVisitor caller = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call", "()I", null, null);
        caller.visitCode();
        caller.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "while.new", "()I", false);
        caller.visitInsn(Opcodes.IRETURN);
        caller.visitMaxs(1, 0);
        caller.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static boolean originalContains(byte[] bytes, String value) {
        byte[] needle = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        outer:
        for (int offset = 0; offset <= bytes.length - needle.length; offset++) {
            for (int index = 0; index < needle.length; index++) {
                if (bytes[offset + index] != needle[index]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static byte[] read(Path jar, String name) throws Exception {
        try (JarFile file = new JarFile(jar.toFile());
             InputStream input = file.getInputStream(file.getJarEntry(name))) {
            return input.readAllBytes();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
