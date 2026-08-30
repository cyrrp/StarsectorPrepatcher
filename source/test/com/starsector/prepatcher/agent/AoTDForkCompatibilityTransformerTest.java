package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Exact-JAR regression coverage for the fork-owned construction-start
 * boundary. The test deliberately mutates the current method surface to make
 * sure a future incompatible fork revision fails closed rather than receiving
 * a name-only transformation.
 */
public final class AoTDForkCompatibilityTransformerTest {
    private static final String ENTRY =
            "data/kaysaar/aotd/tot/industries/AoTDConstructionSite.class";
    private static final String TARGET =
            "data/kaysaar/aotd/tot/industries/AoTDConstructionSite";
    private static final String METHOD_DESC = "(Ljava/lang/String;)V";
    private static final String BRIDGE =
            "data/kaysaar/aotd/tot/compat/SchedulerBridge";
    private static final String BEFORE_DESC = "(Ljava/lang/Object;I)J";
    private static final String AFTER_DESC = "(JLjava/lang/Object;IJ)V";
    private static final String MARKER =
            "smo$patched$aotdConstructionStartBoundary";
    private static final String STATUS_KEY =
            "starsector.prepatcher.patchStatus."
                    + "data.kaysaar.aotd.tot.industries.AoTDConstructionSite."
                    + "aotdConstructionStartBoundary";

    private AoTDForkCompatibilityTransformerTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected AoTD JAR path");
        Map<String, byte[]> forkClasses = readClasses(Path.of(args[0]));
        byte[] original = forkClasses.get(TARGET.replace('/', '.'));
        require(original != null, "missing exact fork class: " + ENTRY);

        require(new AoTDForkCompatibilityTransformer(false).transform(
                null, TARGET, null, null, original) == null,
                "disabled compatibility transformer changed the class");
        require(new AoTDForkCompatibilityTransformer(true).transform(
                null, TARGET + "$Other", null, null, original) == null,
                "compatibility transformer changed an unrelated class");

        System.clearProperty(STATUS_KEY);
        AoTDForkCompatibilityTransformer transformer =
                new AoTDForkCompatibilityTransformer(true);
        byte[] patched = transformer.transform(
                new ClassLoader(ClassLoader.getSystemClassLoader()) {},
                TARGET, null, null, original);
        require(patched != null, "exact fork construction site did not patch");
        require("APPLIED".equals(System.getProperty(STATUS_KEY)),
                "unexpected apply status: " + System.getProperty(STATUS_KEY));
        inspectPatched(patched);
        verifyMethods(patched);
        verifyExactClassLoads(forkClasses, patched);

        System.clearProperty(STATUS_KEY);
        require(transformer.transform(null, TARGET, null, null, patched) == null,
                "repeated transformation was not idempotent");
        require("ALREADY_APPLIED".equals(System.getProperty(STATUS_KEY)),
                "unexpected idempotence status: " + System.getProperty(STATUS_KEY));

        byte[] changedFuture = changeBuildingAssignment(original, Opcodes.ICONST_0);
        System.clearProperty(STATUS_KEY);
        require(transformer.transform(null, TARGET, null, null, changedFuture) == null,
                "changed future construction method was transformed");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(STATUS_KEY)),
                "changed future method did not fail closed: "
                        + System.getProperty(STATUS_KEY));

        byte[] orphanRaw = removeMarker(patched);
        System.clearProperty(STATUS_KEY);
        require(transformer.transform(null, TARGET, null, null, orphanRaw) == null,
                "orphan raw helper was accepted");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(STATUS_KEY)),
                "orphan raw helper did not fail closed: "
                        + System.getProperty(STATUS_KEY));

        System.out.println("OK aotd-fork-compatibility-transformer "
                + "construction-start-boundary exact/idempotent/fail-closed/basic-verifier");
    }

    private static void inspectPatched(byte[] bytes) {
        ClassNode node = read(bytes);
        FieldNode marker = field(node, MARKER);
        require(marker != null, "owned marker missing");
        require((marker.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC
                | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC))
                == (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC
                | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC),
                "owned marker access mismatch");

        MethodNode wrapper = method(node, "setAssignedWonder", METHOD_DESC);
        MethodNode raw = method(node,
                AoTDForkCompatibilityTransformer.RAW_SET_ASSIGNED_WONDER,
                METHOD_DESC);
        require(wrapper != null && raw != null, "wrapper/raw pair missing");
        require((wrapper.access & Opcodes.ACC_PUBLIC) != 0,
                "public API wrapper lost public access");
        require((raw.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC))
                == (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC),
                "raw helper is not private synthetic");
        require(countCalls(wrapper, BRIDGE, "beforeMarketMutation", BEFORE_DESC) == 1,
                "wrapper before boundary count mismatch");
        require(countCalls(wrapper, BRIDGE, "afterMarketMutation", AFTER_DESC) == 2,
                "wrapper after/finally boundary count mismatch");
        require(countCalls(wrapper, TARGET,
                AoTDForkCompatibilityTransformer.RAW_SET_ASSIGNED_WONDER,
                METHOD_DESC) == 1,
                "wrapper raw invocation count mismatch");
        require(wrapper.tryCatchBlocks.size() == 1
                        && "java/lang/Throwable".equals(wrapper.tryCatchBlocks.get(0).type),
                "wrapper does not own a Throwable finally boundary");
        require(countCalls(raw, BRIDGE, "beforeMarketMutation", BEFORE_DESC) == 0
                        && countCalls(raw, BRIDGE, "afterMarketMutation", AFTER_DESC) == 0,
                "raw helper contains a duplicated scheduler boundary");
        require(countFieldWrites(raw, TARGET, "building", "Z") == 1,
                "raw helper lost construction-state write");
        require(countFieldWrites(raw, TARGET, "assignedWonder", "Ljava/lang/String;") == 1,
                "raw helper lost assigned-wonder write");
    }

    private static byte[] changeBuildingAssignment(byte[] original, int replacementOpcode) {
        ClassNode node = read(original);
        MethodNode method = method(node, "setAssignedWonder", METHOD_DESC);
        require(method != null, "setAssignedWonder missing from future fixture");
        FieldInsnNode building = null;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && TARGET.equals(field.owner)
                    && "building".equals(field.name)
                    && "Z".equals(field.desc)) {
                require(building == null, "duplicate building write in fixture");
                building = field;
            }
        }
        require(building != null, "building write missing from fixture");
        AbstractInsnNode value = previousMeaningful(building);
        require(value != null && value.getOpcode() == Opcodes.ICONST_1,
                "unexpected original building value");
        method.instructions.set(value, new InsnNode(replacementOpcode));
        return write(node);
    }

    private static byte[] removeMarker(byte[] patched) {
        ClassNode node = read(patched);
        boolean removed = node.fields.removeIf(field -> MARKER.equals(field.name));
        require(removed, "marker missing from orphan-raw fixture");
        return write(node);
    }

    private static void verifyMethods(byte[] bytes) throws Exception {
        ClassNode node = read(bytes);
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            try {
                new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
            } catch (Throwable failure) {
                throw new AssertionError("BasicVerifier rejected " + node.name + "."
                        + method.name + method.desc, failure);
            }
        }
    }

    private static Map<String, byte[]> readClasses(Path jarPath) throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                String binaryName = entry.getName()
                        .substring(0, entry.getName().length() - ".class".length())
                        .replace('/', '.');
                try (var input = jar.getInputStream(entry)) {
                    classes.put(binaryName, input.readAllBytes());
                }
            }
        }
        return classes;
    }

    private static void verifyExactClassLoads(
            Map<String, byte[]> originalClasses, byte[] patchedTarget) throws Exception {
        Map<String, byte[]> classes = new HashMap<>(originalClasses);
        String targetName = TARGET.replace('/', '.');
        classes.put(targetName, patchedTarget);
        Class<?> loaded = new ForkClassLoader(classes).loadClass(targetName);
        require(loaded.getDeclaredMethod("setAssignedWonder", String.class) != null,
                "patched public API method did not load");
        require(loaded.getDeclaredMethod(
                        AoTDForkCompatibilityTransformer.RAW_SET_ASSIGNED_WONDER,
                        String.class) != null,
                "patched raw helper did not load");
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM8);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        MethodNode found = null;
        for (MethodNode method : node.methods) {
            if (!name.equals(method.name) || !desc.equals(method.desc)) continue;
            require(found == null, "duplicate method " + name + desc);
            found = method;
        }
        return found;
    }

    private static FieldNode field(ClassNode node, String name) {
        for (FieldNode field : node.fields) {
            if (name.equals(field.name)) return field;
        }
        return null;
    }

    private static int countCalls(MethodNode method, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && desc.equals(call.desc)) count++;
        }
        return count;
    }

    private static int countFieldWrites(MethodNode method, String owner,
                                        String name, String desc) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && owner.equals(field.owner) && name.equals(field.name)
                    && desc.equals(field.desc)) count++;
        }
        return count;
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode node) {
        AbstractInsnNode current = node == null ? null : node.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }


    private static final class ForkClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        ForkClassLoader(Map<String, byte[]> classes) {
            super(AoTDForkCompatibilityTransformerTest.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    byte[] bytes = classes.get(name);
                    if (bytes != null) loaded = defineClass(name, bytes, 0, bytes.length);
                }
                if (loaded == null) loaded = super.loadClass(name, false);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
