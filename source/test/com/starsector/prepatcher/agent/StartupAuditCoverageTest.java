package com.starsector.prepatcher.agent;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.TypeInsnNode;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Guards the inventory boundary between non-retransformable transformer
 * registration and the startup loaded-class audit.
 */
public final class StartupAuditCoverageTest {
    private static final Map<Class<?>, String> SPECIAL_LOAD_POLICIES = Map.of(
            FastForwardPresentationTransformer.class,
            "presentation targets use recordLoadedPresentationTargets/local disable",
            DirectMarketObserveTransformer.class,
            "dynamic mod call-site transformer has no finite exact-target inventory");

    private StartupAuditCoverageTest() {}

    public static void main(String[] args) throws Exception {
        PremainInventory premainInventory = inspectPremain();
        Set<Class<?>> transformerTypes = premainInventory.transformerTypes();
        require(transformerTypes.containsAll(SPECIAL_LOAD_POLICIES.keySet()),
                "stale startup-load policy exemption: "
                        + difference(SPECIAL_LOAD_POLICIES.keySet(), transformerTypes));

        Map<String, Set<String>> targetsByTransformer = new LinkedHashMap<>();
        Set<String> registeredExactTargets = new LinkedHashSet<>();
        for (Class<?> transformerType : transformerTypes) {
            if (SPECIAL_LOAD_POLICIES.containsKey(transformerType)) continue;
            Set<String> targets = declaredExactTargets(transformerType);
            require(!targets.isEmpty(), "non-retransformable transformer "
                    + transformerType.getName()
                    + " has neither TARGET nor TARGET_CLASSES metadata and no explicit"
                    + " special startup-load policy");
            targetsByTransformer.put(transformerType.getSimpleName(), targets);
            registeredExactTargets.addAll(targets);
        }

        PrepatcherConfig config = PrepatcherConfig.load(null);
        Set<String> auditedUniverse = PrepatcherAgent.collectStartupAuditTargets(
                new PrepatcherTransformer(config),
                new ReadOnlyUiEconomyStepTransformer(false, false, false, null),
                new MarketOverviewMutationTransformer(false, null),
                new TradeMarketMutationTransformer(false, null),
                new IndustryMarketMutationTransformer(false, null),
                false, false, false, false, false, false, false, true);

        require(auditedUniverse.equals(registeredExactTargets),
                "startup audit and registered exact-target transformers diverged; missing="
                        + difference(registeredExactTargets, auditedUniverse)
                        + ", stale=" + difference(auditedUniverse, registeredExactTargets)
                        + ", inventory=" + targetsByTransformer);

        SyntheticTargetLoader targetLoader = new SyntheticTargetLoader(
                StartupAuditCoverageTest.class.getClassLoader());
        for (String target : auditedUniverse) {
            Class<?> loadedTarget = targetLoader.define(target, minimalClass(target));
            Class<?> found = PrepatcherAgent.findLoadedTarget(
                    instrumentation(loadedTarget), auditedUniverse, targetLoader);
            require(found == loadedTarget,
                    "startup audit did not reject already-loaded exact target " + target);
        }

        ClassLoader runtimeParent = StartupAuditCoverageTest.class.getClassLoader();
        SyntheticTargetLoader nexChildLoader = new SyntheticTargetLoader(runtimeParent);
        Class<?> loadedNexTarget = nexChildLoader.define(
                ReadOnlyUiEconomyStepTransformer.NEX_MARKET_CMD,
                minimalClass(ReadOnlyUiEconomyStepTransformer.NEX_MARKET_CMD));
        require(PrepatcherAgent.findLoadedTarget(
                        instrumentation(loadedNexTarget), auditedUniverse,
                        runtimeParent) == loadedNexTarget,
                "startup audit missed an already-loaded Nex child-loader target");
        SyntheticTargetLoader unrelatedNexLoader = new SyntheticTargetLoader(null);
        Class<?> unrelatedNexTarget = unrelatedNexLoader.define(
                ReadOnlyUiEconomyStepTransformer.NEX_MARKET_CMD,
                minimalClass(ReadOnlyUiEconomyStepTransformer.NEX_MARKET_CMD));
        require(PrepatcherAgent.findLoadedTarget(
                        instrumentation(unrelatedNexTarget), auditedUniverse,
                        runtimeParent) == null,
                "startup audit accepted an unrelated-loader Nex target");

        String sampleTarget = auditedUniverse.iterator().next();
        Class<?> sampleClass = targetLoader.loaded(sampleTarget);
        require(PrepatcherAgent.findLoadedTarget(
                        instrumentation(sampleClass), Set.of(), targetLoader) == null,
                "startup audit rejected a target absent from the enabled target set");
        require(PrepatcherAgent.findLoadedTarget(
                        instrumentation(sampleClass), auditedUniverse,
                        StartupAuditCoverageTest.class.getClassLoader()) == null,
                "startup audit accepted an unrelated class-loader copy");

        System.out.println("OK startup audit coverage transformers=" + transformerTypes.size()
                + " registrations=" + premainInventory.registrationCount()
                + " exactTargets=" + auditedUniverse.size()
                + " specialPolicies=" + SPECIAL_LOAD_POLICIES.size());
    }

    private static PremainInventory inspectPremain() throws Exception {
        String resourceName = "/" + PrepatcherAgent.class.getName().replace('.', '/') + ".class";
        ClassNode node = new ClassNode();
        try (InputStream input = PrepatcherAgent.class.getResourceAsStream(resourceName)) {
            require(input != null, "could not read " + resourceName);
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        MethodNode premain = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals("premain")
                    && method.desc.equals("(Ljava/lang/String;Ljava/lang/instrument/Instrumentation;)V")) {
                premain = method;
                break;
            }
        }
        require(premain != null, "PrepatcherAgent.premain descriptor changed");

        Set<Class<?>> result = new LinkedHashSet<>();
        int registrationCount = 0;
        ClassLoader loader = StartupAuditCoverageTest.class.getClassLoader();
        for (AbstractInsnNode instruction : premain.instructions) {
            if (instruction instanceof TypeInsnNode typeInsn
                    && instruction.getOpcode() == Opcodes.NEW) {
                Class<?> type = Class.forName(typeInsn.desc.replace('/', '.'), false, loader);
                if (ClassFileTransformer.class.isAssignableFrom(type)) result.add(type);
            }
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("java/lang/instrument/Instrumentation")
                    && call.name.equals("addTransformer")
                    && call.desc.equals("(Ljava/lang/instrument/ClassFileTransformer;Z)V")) {
                registrationCount++;
                AbstractInsnNode flag = previousOpcode(call);
                require(flag != null && flag.getOpcode() == Opcodes.ICONST_0,
                        "premain registered a transformer without literal canRetransform=false");
            }
        }
        require(!result.isEmpty(), "premain transformer discovery returned an empty inventory");
        require(registrationCount == result.size(),
                "premain transformer registrations are not one-to-one with discoverable"
                        + " transformer policy owners: registrations=" + registrationCount
                        + ", owners=" + result);
        return new PremainInventory(Set.copyOf(result), registrationCount);
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static Set<String> declaredExactTargets(Class<?> transformerType)
            throws ReflectiveOperationException {
        Set<String> result = new LinkedHashSet<>();
        addTargetField(transformerType, "TARGET", result);
        addTargetField(transformerType, "TARGET_CLASSES", result);
        addTargetField(transformerType, "OPTIONAL_TARGET_CLASSES", result);
        return result;
    }

    private static void addTargetField(Class<?> type, String fieldName, Set<String> targets)
            throws ReflectiveOperationException {
        final Field field;
        try {
            field = type.getDeclaredField(fieldName);
        } catch (NoSuchFieldException ignored) {
            return;
        }
        require(Modifier.isStatic(field.getModifiers()),
                type.getName() + "." + fieldName + " must be static");
        field.setAccessible(true);
        Object value = field.get(null);
        if (value instanceof String target) {
            requireInternalName(type, fieldName, target);
            targets.add(target);
            return;
        }
        require(value instanceof Set<?>,
                type.getName() + "." + fieldName + " must be String or Set<String>");
        for (Object item : (Set<?>) value) {
            require(item instanceof String,
                    type.getName() + "." + fieldName + " contains a non-String target");
            String target = (String) item;
            requireInternalName(type, fieldName, target);
            targets.add(target);
        }
    }

    private static void requireInternalName(Class<?> owner, String field, String target) {
        require(!target.isBlank() && target.indexOf('/') > 0 && target.indexOf('.') < 0,
                owner.getName() + "." + field
                        + " is not a JVM internal class name: " + target);
    }

    private static byte[] minimalClass(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null,
                "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Instrumentation instrumentation(Class<?>... loadedClasses) {
        return (Instrumentation) Proxy.newProxyInstance(
                StartupAuditCoverageTest.class.getClassLoader(),
                new Class<?>[]{Instrumentation.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getAllLoadedClasses")) return loadedClasses;
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) return false;
                    if (returnType == int.class) return 0;
                    if (returnType == long.class) return 0L;
                    return null;
                });
    }

    private static Set<?> difference(Set<?> left, Set<?> right) {
        Set<Object> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class SyntheticTargetLoader extends ClassLoader {
        private final Map<String, Class<?>> loaded = new LinkedHashMap<>();

        private SyntheticTargetLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(String internalName, byte[] bytecode) {
            String binaryName = internalName.replace('/', '.');
            Class<?> type = defineClass(binaryName, bytecode, 0, bytecode.length);
            loaded.put(internalName, type);
            return type;
        }

        private Class<?> loaded(String internalName) {
            return loaded.get(internalName);
        }
    }

    private record PremainInventory(Set<Class<?>> transformerTypes, int registrationCount) {}
}
