package com.starsector.prepatcher.agent;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Installs the old Faster Rendering loader's pre-define Prepatcher bridge. */
final class FasterRenderingPredefineBridge {
    static final String PROPERTY =
            "starsector.prepatcher.internal.frPredefineBridge.0.18.4";
    private static final String APP_LOADER =
            "com/genir/renderer/loaders/AppClassLoader";
    private static final String CLASS_TRANSFORMER =
            "com/genir/renderer/loaders/ClassTransformer";
    private static final String TRANSFORM_METHOD = "transformBytes";
    private static final String TRANSFORM_DESC =
            "(Ljava/lang/String;[BLjava/util/List;)[B";

    private FasterRenderingPredefineBridge() {
    }

    static Result install(Instrumentation instrumentation) {
        ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
        ClassLoader frImplementationLoader = systemLoader == null
                ? null : systemLoader.getClass().getClassLoader();
        BridgeController controller = new BridgeController();
        BridgeClassTransformer transformer = new BridgeClassTransformer();
        Class<?> targetClass = null;
        boolean registered = false;
        boolean propertyInstalled = false;
        try {
            validateLoaderArchitecture(systemLoader);
            Properties properties = System.getProperties();
            synchronized (properties) {
                if (properties.containsKey(PROPERTY)) {
                    throw new IllegalStateException("the internal bridge property is already occupied");
                }
                properties.put(PROPERTY, controller);
                propertyInstalled = true;
            }

            instrumentation.addTransformer(transformer, true);
            registered = true;
            targetClass = findLoadedClass(
                    instrumentation, CLASS_TRANSFORMER, frImplementationLoader);
            if (targetClass == null) {
                targetClass = Class.forName(CLASS_TRANSFORMER.replace('/', '.'),
                        false, frImplementationLoader);
            } else {
                if (!instrumentation.isModifiableClass(targetClass)) {
                    throw new IllegalStateException("loaded ClassTransformer is not modifiable");
                }
                instrumentation.retransformClasses(targetClass);
            }
            if (transformer.applied.get() != 1) {
                throw new IllegalStateException("bridge transformer application count was "
                        + transformer.applied.get() + " instead of 1");
            }
            if (!instrumentation.removeTransformer(transformer)) {
                throw new IllegalStateException("could not remove the temporary bridge transformer");
            }
            registered = false;

            runLiveProbe(systemLoader, controller);
            return Result.passed(new Installation(controller),
                    "old AppClassLoader data flow and live pre-define bridge were proven; "
                            + "legacy illegal-name repair is installed");
        } catch (Throwable failure) {
            Throwable root = rootCause(failure);
            if (registered) instrumentation.removeTransformer(transformer);
            if (propertyInstalled) removeProperty(controller);
            if (targetClass != null && transformer.applied.get() > 0) {
                try {
                    instrumentation.retransformClasses(targetClass);
                } catch (Throwable rollbackFailure) {
                    PrepatcherLog.error("Could not restore Faster Rendering ClassTransformer "
                            + "after bridge setup failed.", rollbackFailure);
                }
            }
            controller.disarm();
            return Result.failed(root.getClass().getSimpleName() + ": "
                    + String.valueOf(root.getMessage()));
        }
    }

    private static void validateLoaderArchitecture(ClassLoader systemLoader) throws Exception {
        if (systemLoader == null || !APP_LOADER.replace('/', '.')
                .equals(systemLoader.getClass().getName())) {
            throw new IllegalStateException("system loader is not the legacy Faster Rendering AppClassLoader");
        }
        ClassLoader implementationLoader = systemLoader.getClass().getClassLoader();
        ClassNode app = readResource(implementationLoader, APP_LOADER + ".class");
        MethodNode findBytecode = requireMethod(app, "findBytecode",
                "(Ljava/lang/String;)[B");
        MethodInsnNode transformCall = uniqueCall(findBytecode, Opcodes.INVOKESTATIC,
                CLASS_TRANSFORMER, TRANSFORM_METHOD, TRANSFORM_DESC);
        if (nextMeaningful(transformCall) == null
                || nextMeaningful(transformCall).getOpcode() != Opcodes.ARETURN) {
            throw new IllegalStateException("findBytecode does not return the transformed bytes directly");
        }

        MethodNode findClass = requireMethod(app, "findClass",
                "(Ljava/lang/String;)Ljava/lang/Class;");
        MethodInsnNode bytecodeCall = uniqueCall(findClass, Opcodes.INVOKEVIRTUAL,
                APP_LOADER, "findBytecode", "(Ljava/lang/String;)[B");
        AbstractInsnNode store = nextMeaningful(bytecodeCall);
        if (!(store instanceof VarInsnNode stored) || store.getOpcode() != Opcodes.ASTORE) {
            throw new IllegalStateException("findClass does not retain the bytes from findBytecode");
        }
        MethodInsnNode defineCall = uniqueCall(findClass, Opcodes.INVOKESPECIAL,
                "java/lang/ClassLoader", "defineClass",
                "(Ljava/lang/String;[BIILjava/security/ProtectionDomain;)Ljava/lang/Class;");
        boolean storedBytesFeedDefine = false;
        for (AbstractInsnNode instruction = store.getNext(); instruction != null
             && instruction != defineCall; instruction = instruction.getNext()) {
            if (instruction instanceof VarInsnNode variable
                    && instruction.getOpcode() == Opcodes.ALOAD
                    && variable.var == stored.var) {
                storedBytesFeedDefine = true;
            }
        }
        if (!storedBytesFeedDefine || nextMeaningful(defineCall) == null
                || nextMeaningful(defineCall).getOpcode() != Opcodes.ARETURN) {
            throw new IllegalStateException("findClass does not pass findBytecode output directly to defineClass");
        }

        ClassNode transformer = readResource(
                implementationLoader, CLASS_TRANSFORMER + ".class");
        locateFinalReturn(requireMethod(transformer, TRANSFORM_METHOD, TRANSFORM_DESC));
    }

    private static void runLiveProbe(ClassLoader systemLoader,
                                     BridgeController controller) throws Exception {
        Class<?> appLoaderClass = systemLoader.getClass();
        if (!appLoaderClass.isInstance(systemLoader)) {
            throw new IllegalStateException("the live system loader is not the inspected AppClassLoader");
        }
        Method findBytecode = appLoaderClass.getMethod("findBytecode", String.class);
        int before = controller.calls.get();
        controller.beginProbe();
        byte[] result;
        try {
            result = (byte[]) findBytecode.invoke(systemLoader,
                    "com/fs/starfarer/api/Global.class");
        } finally {
            controller.endProbe();
        }
        int delta = controller.calls.get() - before;
        byte[] bridgeInput = controller.takeProbeInput();
        String resource = controller.takeProbeResource();
        if (delta != 1) {
            throw new IllegalStateException("live findBytecode invoked the bridge "
                    + delta + " times instead of once");
        }
        if (!"com/fs/starfarer/api/Global.class".equals(resource)) {
            throw new IllegalStateException("live bridge received unexpected resource " + resource);
        }
        if (bridgeInput == null || result == null || result != bridgeInput
                || !Arrays.equals(result, bridgeInput)) {
            throw new IllegalStateException("identity bridge did not return the exact FR byte array");
        }
    }

    private static ClassNode readResource(ClassLoader loader, String resource) throws Exception {
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("missing " + resource);
            ClassNode node = new ClassNode(Opcodes.ASM9);
            new ClassReader(input.readAllBytes()).accept(node, 0);
            return node;
        }
    }

    private static MethodNode requireMethod(ClassNode node, String name, String desc) {
        MethodNode result = null;
        for (MethodNode method : node.methods) {
            if (!name.equals(method.name) || !desc.equals(method.desc)) continue;
            if (result != null) throw new IllegalStateException("duplicate " + name + desc);
            result = method;
        }
        if (result == null) throw new IllegalStateException("missing " + node.name + "." + name + desc);
        return result;
    }

    private static MethodInsnNode uniqueCall(MethodNode method, int opcode,
                                             String owner, String name, String desc) {
        MethodInsnNode result = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
             instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && instruction.getOpcode() == opcode
                    && owner.equals(call.owner) && name.equals(call.name)
                    && desc.equals(call.desc)) {
                if (result != null) throw new IllegalStateException("duplicate semantic call "
                        + owner + "." + name + desc + " in " + method.name + method.desc);
                result = call;
            }
        }
        if (result == null) throw new IllegalStateException("missing semantic call "
                + owner + "." + name + desc + " in " + method.name + method.desc);
        return result;
    }

    private static VarInsnNode locateFinalReturn(MethodNode method) {
        int lastApply = -1;
        boolean loopPresent = false;
        List<AbstractInsnNode> instructions = Arrays.asList(method.instructions.toArray());
        for (int index = 0; index < instructions.size(); index++) {
            AbstractInsnNode instruction = instructions.get(index);
            if (instruction instanceof MethodInsnNode call
                    && "com/genir/renderer/loaders/ClassConstantTransformer".equals(call.owner)
                    && "apply".equals(call.name) && "([B)[B".equals(call.desc)) {
                lastApply = index;
            }
            if (instruction instanceof JumpInsnNode jump
                    && instructions.indexOf(jump.label) < index) {
                loopPresent = true;
            }
        }
        if (lastApply < 0 || !loopPresent) {
            throw new IllegalStateException("transformBytes no longer has the FR transformation loop");
        }
        VarInsnNode returnedValue = null;
        int returnsAfterLoop = 0;
        for (int index = lastApply + 1; index < instructions.size(); index++) {
            if (instructions.get(index).getOpcode() != Opcodes.ARETURN) continue;
            returnsAfterLoop++;
            AbstractInsnNode previous = previousMeaningful(instructions.get(index));
            if (previous instanceof VarInsnNode variable
                    && previous.getOpcode() == Opcodes.ALOAD) {
                returnedValue = variable;
            }
        }
        if (returnsAfterLoop != 1 || returnedValue == null) {
            throw new IllegalStateException("transformBytes has no unique final byte-array return");
        }
        return returnedValue;
    }

    private static AbstractInsnNode nextMeaningful(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction.getNext();
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static Class<?> findLoadedClass(Instrumentation instrumentation,
                                            String internalName, ClassLoader loader) {
        String binaryName = internalName.replace('/', '.');
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (binaryName.equals(loaded.getName()) && loaded.getClassLoader() == loader) {
                return loaded;
            }
        }
        return null;
    }

    private static void removeProperty(BridgeController controller) {
        Properties properties = System.getProperties();
        synchronized (properties) {
            if (properties.get(PROPERTY) == controller) properties.remove(PROPERTY);
        }
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    record Result(boolean passed, String detail, Installation installation) {
        static Result passed(Installation installation, String detail) {
            return new Result(true, detail, installation);
        }

        static Result failed(String detail) {
            return new Result(false, detail, null);
        }
    }

    static final class Installation {
        private final BridgeController controller;

        private Installation(BridgeController controller) {
            this.controller = controller;
        }

        void arm(OrderedTransformerPipeline pipeline) {
            controller.arm(pipeline);
        }
    }

    private static final class BridgeController
            implements BiFunction<String, byte[], byte[]> {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile WeakReference<OrderedTransformerPipeline> pipeline =
                new WeakReference<>(null);
        private volatile boolean probing;
        private volatile byte[] probeInput;
        private volatile String probeResource;

        @Override
        public byte[] apply(String resourceName, byte[] bytes) {
            calls.incrementAndGet();
            if (probing) {
                probeInput = bytes;
                probeResource = resourceName;
            }
            byte[] compatible = IllegalObfuscatedMemberNameRepair.repair(resourceName, bytes);
            OrderedTransformerPipeline current = pipeline.get();
            return current == null
                    ? compatible : current.applyPredefine(resourceName, compatible);
        }

        private void arm(OrderedTransformerPipeline value) {
            pipeline = new WeakReference<>(value);
        }

        private void disarm() {
            pipeline.clear();
            probeInput = null;
            probeResource = null;
        }

        private void beginProbe() {
            probeInput = null;
            probeResource = null;
            probing = true;
        }

        private void endProbe() {
            probing = false;
        }

        private byte[] takeProbeInput() {
            byte[] result = probeInput;
            probeInput = null;
            return result;
        }

        private String takeProbeResource() {
            String result = probeResource;
            probeResource = null;
            return result;
        }
    }

    private static final class BridgeClassTransformer implements ClassFileTransformer {
        private final AtomicInteger applied = new AtomicInteger();

        @Override
        public byte[] transform(ClassLoader loader, String className,
                                Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain,
                                byte[] classfileBuffer) {
            if (!CLASS_TRANSFORMER.equals(className) || classfileBuffer == null) return null;
            ClassNode node = new ClassNode(Opcodes.ASM9);
            new ClassReader(classfileBuffer).accept(node, 0);
            if (!CLASS_TRANSFORMER.equals(node.name)) return null;
            MethodNode method = requireMethod(node, TRANSFORM_METHOD, TRANSFORM_DESC);
            VarInsnNode returnedValue = locateFinalReturn(method);
            if (containsBridgeCall(method)) {
                throw new IllegalStateException("ClassTransformer already contains a predefine bridge call");
            }

            InsnList bridge = new InsnList();
            bridge.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System",
                    "getProperties", "()Ljava/util/Properties;", false));
            bridge.add(new LdcInsnNode(PROPERTY));
            bridge.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Properties",
                    "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
            bridge.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/function/BiFunction"));
            bridge.add(new VarInsnNode(Opcodes.ALOAD, 0));
            bridge.add(new VarInsnNode(Opcodes.ALOAD, returnedValue.var));
            bridge.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                    "java/util/function/BiFunction", "apply",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
            bridge.add(new TypeInsnNode(Opcodes.CHECKCAST, "[B"));
            method.instructions.insertBefore(returnedValue, bridge);
            method.instructions.remove(returnedValue);

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            applied.incrementAndGet();
            return writer.toByteArray();
        }

        private static boolean containsBridgeCall(MethodNode method) {
            int propertyConstants = 0;
            int bridgeCalls = 0;
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                 instruction != null; instruction = instruction.getNext()) {
                if (instruction instanceof LdcInsnNode constant
                        && PROPERTY.equals(constant.cst)) propertyConstants++;
                if (instruction instanceof MethodInsnNode call
                        && "java/util/function/BiFunction".equals(call.owner)
                        && "apply".equals(call.name)) bridgeCalls++;
            }
            if (propertyConstants != bridgeCalls) {
                throw new IllegalStateException("partial predefine bridge state");
            }
            return propertyConstants != 0;
        }
    }
}
