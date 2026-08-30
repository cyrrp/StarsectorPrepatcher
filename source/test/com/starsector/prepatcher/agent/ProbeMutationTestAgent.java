package com.starsector.prepatcher.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Deliberately incomplete/corrupt earlier agent used by Java 27 negative probes. */
public final class ProbeMutationTestAgent {
    private static final String OWNER =
            "com/fs/starfarer/api/PrepatcherJavaCompatibilityProbe";

    private ProbeMutationTestAgent() {
    }

    public static void premain(String mode, Instrumentation instrumentation) {
        if (!"valid".equals(mode) && !"no-op".equals(mode)
                && !"declaration-only".equals(mode)
                && !"corrupt-body".equals(mode)) {
            throw new IllegalArgumentException("Unknown probe mutation mode: " + mode);
        }
        if ("no-op".equals(mode)) return;
        instrumentation.addTransformer(new Mutation(mode), false);
    }

    private static final class Mutation implements ClassFileTransformer {
        private final String mode;

        private Mutation(String mode) {
            this.mode = mode;
        }

        @Override
        public byte[] transform(ClassLoader loader, String className,
                                Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain,
                                byte[] classfileBuffer) {
            if (!OWNER.equals(className) || classfileBuffer == null) return null;
            ClassNode node = new ClassNode(Opcodes.ASM9);
            new ClassReader(classfileBuffer).accept(node, 0);
            for (MethodNode method : node.methods) {
                if ("while.new".equals(method.name)) method.name = "while_new";
                if (!"declaration-only".equals(mode)) {
                    for (AbstractInsnNode instruction = method.instructions.getFirst();
                         instruction != null; instruction = instruction.getNext()) {
                        if (instruction instanceof MethodInsnNode call
                                && "while.new".equals(call.name)) {
                            call.name = "while_new";
                        }
                        if ("corrupt-body".equals(mode) && "while_new".equals(method.name)
                                && instruction.getOpcode() == Opcodes.ICONST_1) {
                            method.instructions.set(instruction, new InsnNode(Opcodes.ICONST_2));
                        }
                    }
                }
            }
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            return writer.toByteArray();
        }
    }
}
