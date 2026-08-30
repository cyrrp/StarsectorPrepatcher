package com.starsector.prepatcher.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Behavioral proof that an earlier Faster Rendering javaagent repaired both symbols. */
final class FasterRenderingAgentProbe {
    private static final String OWNER =
            "com/fs/starfarer/api/PrepatcherJavaCompatibilityProbe";
    private static final String ILLEGAL_NAME = "while.new";

    private FasterRenderingAgentProbe() {
    }

    static Result run(Instrumentation instrumentation) {
        Capture capture = new Capture();
        instrumentation.addTransformer(capture, false);
        byte[] input = null;
        try {
            input = createProbeClass();
            Class<?> defined = new ProbeLoader().define(input);
            byte[] observed = capture.bytes;
            if (observed == null) {
                return Result.failed(false,
                        "the downstream observer did not receive the probe class");
            }
            String repairedName = validateRepairedBytes(observed);
            Method method = defined.getDeclaredMethod(repairedName, int.class);
            Object value = method.invoke(null, 41);
            if (!(value instanceof Integer integer) || integer != 42) {
                return Result.failed(true, "the repaired test method returned " + value
                        + " instead of 42");
            }
            return Result.passed("an earlier agent consistently repaired the declaration "
                    + "and call to '" + ILLEGAL_NAME + "' as '" + repairedName + "'");
        } catch (Throwable failure) {
            Throwable root = rootCause(failure);
            boolean changed = input != null && capture.bytes != null
                    && !Arrays.equals(input, capture.bytes);
            return Result.failed(changed, root.getClass().getSimpleName() + ": "
                    + String.valueOf(root.getMessage()));
        } finally {
            instrumentation.removeTransformer(capture);
            capture.bytes = null;
        }
    }

    private static byte[] createProbeClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                OWNER, null, "java/lang/Object", null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC,
                "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object",
                "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor declaration = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                ILLEGAL_NAME, "(I)I", null, null);
        declaration.visitCode();
        declaration.visitVarInsn(Opcodes.ILOAD, 0);
        declaration.visitInsn(Opcodes.ICONST_1);
        declaration.visitInsn(Opcodes.IADD);
        declaration.visitInsn(Opcodes.IRETURN);
        declaration.visitMaxs(0, 0);
        declaration.visitEnd();

        MethodVisitor caller = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "call", "(I)I", null, null);
        caller.visitCode();
        caller.visitVarInsn(Opcodes.ILOAD, 0);
        caller.visitMethodInsn(Opcodes.INVOKESTATIC, OWNER,
                ILLEGAL_NAME, "(I)I", false);
        caller.visitInsn(Opcodes.IRETURN);
        caller.visitMaxs(0, 0);
        caller.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String validateRepairedBytes(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(node, 0);
        if (!OWNER.equals(node.name) || node.version != Opcodes.V1_8
                || !"java/lang/Object".equals(node.superName)
                || !node.fields.isEmpty() || node.methods.size() != 3) {
            throw new IllegalStateException("the earlier agent changed the probe class envelope");
        }

        MethodNode declaration = null;
        MethodNode caller = null;
        for (MethodNode method : node.methods) {
            if ("(I)I".equals(method.desc) && !"call".equals(method.name)) {
                if (declaration != null) {
                    throw new IllegalStateException("multiple repaired declarations were produced");
                }
                declaration = method;
            } else if ("call".equals(method.name) && "(I)I".equals(method.desc)) {
                caller = method;
            } else if (!"<init>".equals(method.name) || !"()V".equals(method.desc)) {
                throw new IllegalStateException("the earlier agent added or changed a probe method");
            }
        }
        if (declaration == null || caller == null) {
            throw new IllegalStateException("the repaired declaration or its caller is missing");
        }
        if (ILLEGAL_NAME.equals(declaration.name) || !isLegalMethodName(declaration.name)) {
            throw new IllegalStateException("the declaration still has an illegal name: "
                    + declaration.name);
        }
        requireOpcodes(declaration, Opcodes.ILOAD, Opcodes.ICONST_1,
                Opcodes.IADD, Opcodes.IRETURN);
        requireOpcodes(caller, Opcodes.ILOAD, Opcodes.INVOKESTATIC, Opcodes.IRETURN);

        MethodInsnNode invocation = null;
        for (AbstractInsnNode instruction = caller.instructions.getFirst();
             instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call) {
                if (invocation != null) {
                    throw new IllegalStateException("the probe caller gained an extra invocation");
                }
                invocation = call;
            }
        }
        if (invocation == null || invocation.getOpcode() != Opcodes.INVOKESTATIC
                || !OWNER.equals(invocation.owner)
                || !declaration.name.equals(invocation.name)
                || !"(I)I".equals(invocation.desc)) {
            throw new IllegalStateException("the declaration and its call were not repaired consistently");
        }
        return declaration.name;
    }

    private static void requireOpcodes(MethodNode method, int... expected) {
        List<Integer> actual = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
             instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() >= 0) actual.add(instruction.getOpcode());
        }
        if (actual.size() != expected.length) {
            throw new IllegalStateException("the body of " + method.name + method.desc
                    + " changed: opcodes=" + actual);
        }
        for (int index = 0; index < expected.length; index++) {
            if (actual.get(index) != expected[index]) {
                throw new IllegalStateException("the body of " + method.name + method.desc
                        + " changed: opcodes=" + actual);
            }
        }
    }

    private static boolean isLegalMethodName(String name) {
        return !name.isEmpty() && name.indexOf('.') < 0 && name.indexOf(';') < 0
                && name.indexOf('[') < 0 && name.indexOf('/') < 0
                && name.indexOf('<') < 0 && name.indexOf('>') < 0;
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    record Result(boolean passed, boolean changedProbeBytes, String detail) {
        static Result passed(String detail) {
            return new Result(true, true, detail);
        }

        static Result failed(boolean changedProbeBytes, String detail) {
            return new Result(false, changedProbeBytes,
                    detail == null ? "unknown failure" : detail);
        }
    }

    private static final class Capture implements ClassFileTransformer {
        private volatile byte[] bytes;

        @Override
        public byte[] transform(ClassLoader loader, String className,
                                Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain,
                                byte[] classfileBuffer) {
            if (OWNER.equals(className) && classfileBuffer != null) {
                bytes = classfileBuffer.clone();
            }
            return null;
        }
    }

    private static final class ProbeLoader extends ClassLoader {
        private ProbeLoader() {
            super(null);
        }

        private Class<?> define(byte[] bytes) {
            return defineClass(OWNER.replace('/', '.'), bytes, 0, bytes.length);
        }
    }
}
