package com.starsector.prepatcher.agent;

import com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/** Structural and synchronous-behavior test for arbitrary mod call-site observation. */
public final class DirectMarketObserveTransformerTest {
    private static final String CLASS_NAME = "test/mod/DirectMarketCaller";
    private static final String HOOKS = "com/fs/starfarer/api/StarsectorPrepatcherHooks";
    private static final String HOOK_DESC =
            "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;FJLjava/lang/String;)V";

    private DirectMarketObserveTransformerTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("prepatcher-direct-transformer-");
        try {
            run(root);
        } finally {
            deleteRecursively(root);
        }
    }

    private static void run(Path root) throws Exception {
        Path gameRoot = root.resolve("Starsector 0.98 Test");
        Path modsRoot = gameRoot.resolve("mods");
        Path prepatcherRoot = modsRoot.resolve("StarsectorPrepatcher");
        Path testModRoot = modsRoot.resolve("TestMod");
        Path otherModRoot = modsRoot.resolve("OtherMod");
        Path testModJar = testModRoot.resolve("jars").resolve("test.jar");
        Path otherModJar = otherModRoot.resolve("jars").resolve("test.jar");
        Files.createDirectories(prepatcherRoot);
        Files.createDirectories(testModJar.getParent());
        Files.createDirectories(otherModJar.getParent());
        Files.writeString(testModRoot.resolve("mod_info.json"),
                "# Starsector-style comment\n{\n"
                        + "  \"id\": \"test_mod\", // owner id\n"
                        + "  \"name\": \"Test Mod Display\",\n"
                        + "  \"dependencies\": [{\"id\":\"wrong_dependency\","
                        + "\"name\":\"Wrong Dependency Display\"}],\n"
                        + "}\n",
                StandardCharsets.UTF_8);
        Files.writeString(otherModRoot.resolve("mod_info.json"),
                "{/* nested dependency must not win */"
                        + "\"dependencies\":[{\"id\":\"wrong_first\","
                        + "\"name\":\"Wrong First Display\"}],"
                        + "\"id\":\"other_mod\",\"name\":\"Other Mod Display\"}",
                StandardCharsets.UTF_8);

        PrepatcherConfig config = config(true, false);
        StarsectorPrepatcherRuntimeBridge.configure(config, prepatcherRoot);
        ClassLoader parent = ClassLoader.getSystemClassLoader();
        ChildLoader loader = new ChildLoader(parent);
        URL testModCodeSource = archiveCodeSource(testModJar);
        require(testModCodeSource.toExternalForm().contains("Starsector 0.98 Test")
                        && testModCodeSource.toExternalForm().endsWith("test.jar!/"),
                "regression code source does not contain raw spaces and !/: "
                        + testModCodeSource);
        ProtectionDomain domain = new ProtectionDomain(
                new CodeSource(testModCodeSource,
                        (Certificate[]) null), null, loader, null);
        byte[] original = generateClass();
        DirectMarketObserveTransformer transformer =
                new DirectMarketObserveTransformer(config, parent, prepatcherRoot);
        byte[] transformed = transformer.transform(loader, CLASS_NAME, null, domain, original);
        require(transformed != null, "candidate mod class was not transformed");

        PrepatcherConfig syncOnlyConfig = config(false, true);
        DirectMarketObserveTransformer syncOnlyTransformer =
                new DirectMarketObserveTransformer(syncOnlyConfig, parent, prepatcherRoot);
        byte[] syncOnly = syncOnlyTransformer.transform(
                loader, CLASS_NAME, null, domain, original);
        require(syncOnly != null && countHooks(syncOnly) == 4,
                "market scheduler did not install direct-call debt synchronization"
                        + " when observation was disabled");

        PrepatcherConfig riskConfig = config(false, false, true);
        Path riskLog = prepatcherRoot.resolve("logs")
                .resolve("market-advance-semantic-risks.csv");
        Files.createDirectories(riskLog.getParent());
        Files.writeString(riskLog,
                "modId,className,methodName,descriptor,componentType,riskCategory,calledOwner,calledMethod,bytecodeOffset\n",
                StandardCharsets.UTF_8);
        DirectMarketObserveTransformer riskTransformer =
                new DirectMarketObserveTransformer(riskConfig, parent, prepatcherRoot);
        byte[] riskResult = riskTransformer.transform(loader,
                "test/mod/RiskyIndustry", null, domain, generateRiskClass());
        require(riskResult == null,
                "semantic-risk observer-only mode instrumented a class containing Market.advance");
        require(countOriginalMarketAdvanceCalls(generateRiskClass()) == 1,
                "observer-only negative test no longer contains a direct Market.advance call");

        ProtectionDomain otherDomain = new ProtectionDomain(
                new CodeSource(archiveCodeSource(otherModJar),
                        (Certificate[]) null), null, loader, null);
        riskTransformer.transform(loader,
                "test/mod/RiskyIndustry", null, otherDomain, generateRiskClass());

        loader.addResource("test/mod/IndirectIndustryBase.class",
                generateIndirectIndustryBase());
        riskTransformer.transform(loader,
                "test/mod/IndirectRiskIndustry", null, domain,
                generateIndirectRiskIndustry());
        riskTransformer.transform(loader,
                "test/mod/UnrelatedAdvance", null, domain,
                generateUnrelatedAdvanceClass());
        String risks = Files.readString(prepatcherRoot.resolve("logs")
                .resolve("market-advance-semantic-risks.csv"), StandardCharsets.UTF_8);
        try (Stream<Path> files = Files.list(riskLog.getParent())) {
            require(files.anyMatch(path -> path.getFileName().toString()
                            .startsWith("market-advance-semantic-risks-legacy-")),
                    "legacy semantic-risk CSV was not rotated during schema upgrade");
        }
        require(risks.contains("test_mod")
                        && risks.contains("Test Mod Display")
                        && risks.contains("TestMod")
                        && risks.contains("test.mod.RiskyIndustry")
                        && risks.contains("Industry")
                        && risks.contains("INTERVAL_SINGLE_ELAPSE")
                        && risks.contains("RANDOM_ROLL")
                        && risks.contains("SINGLE_THRESHOLD_TRANSITION")
                        && risks.contains("MARKET_STRUCTURE_MUTATION"),
                "semantic-risk observer report is incomplete: " + risks);
        require(risks.contains("other_mod")
                        && risks.contains("Other Mod Display")
                        && risks.contains("OtherMod"),
                "semantic-risk dedup collapsed equal class names from different mods: " + risks);
        require(risks.contains("test.mod.IndirectRiskIndustry"),
                "semantic-risk hierarchy classification did not follow an indirect superclass");
        require(!risks.contains("test.mod.UnrelatedAdvance"),
                "semantic-risk observer classified an unrelated advance(F)V method");

        Path session = Path.of(System.getProperty(
                "starsector.prepatcher.directMarketObservationDir"));
        String manifest = Files.readString(
                session.resolve("call-sites.csv"), StandardCharsets.UTF_8);
        require(dataRows(manifest) == 4,
                "eager transformer registration did not persist four call sites before define: "
                        + manifest);
        require(manifest.contains("test.mod.DirectMarketCaller")
                        && manifest.contains("mods/TestMod/jars/test.jar")
                        && manifest.contains("mod_id,mod_name,mod_directory,jar_name")
                        && manifest.contains("test_mod")
                        && manifest.contains("Test Mod Display")
                        && manifest.contains("TestMod")
                        && manifest.contains("test.jar"),
                "eager transformer manifest lost class/source metadata: " + manifest);

        ClassNode node = read(transformed);
        int originals = 0;
        int hooks = 0;
        List<String> metadata = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call) {
                    if ("advance".equals(call.name) && "(F)V".equals(call.desc)
                            && "com/fs/starfarer/api/campaign/econ/MarketAPI".equals(call.owner)) {
                        originals++;
                    }
                    if (call.getOpcode() == Opcodes.INVOKESTATIC
                            && HOOKS.equals(call.owner)
                            && "observeDirectMarketAdvance".equals(call.name)
                            && HOOK_DESC.equals(call.desc)) {
                        hooks++;
                    }
                }
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text
                        && text.indexOf('\u001f') >= 0) {
                    metadata.add(text);
                }
            }
        }
        require(originals == 0, "original MarketAPI.advance calls remain: " + originals);
        require(hooks == 4, "expected four observation hooks, found " + hooks);
        require(metadata.stream().anyMatch(value -> value.contains("ARG1")),
                "float argument source was not classified");
        require(metadata.stream().anyMatch(value -> value.contains("CONST:1.0")),
                "constant amount source was not classified");
        for (String value : metadata) {
            String[] fields = value.split(String.valueOf('\u001f'), -1);
            require(fields.length >= 6
                            && fields[2].equals("test_mod")
                            && fields[3].equals("Test Mod Display")
                            && fields[4].equals("TestMod")
                            && fields[5].equals("test.jar"),
                    "explicit mod identity was not resolved from raw file URL: " + value);
        }

        Class<?> callerClass = loader.define(CLASS_NAME.replace('/', '.'), transformed);
        Object caller = callerClass.getConstructor().newInstance();
        Counter counter = new Counter();
        MarketAPI market = (MarketAPI) Proxy.newProxyInstance(parent,
                new Class<?>[]{MarketAPI.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("advance")) {
                        counter.calls++;
                        counter.sum += ((Float) arguments[0]);
                        if (counter.throwNext) throw new IllegalStateException("expected");
                    }
                    return defaultValue(method.getReturnType());
                });

        callerClass.getMethod("passThrough", MarketAPI.class, float.class)
                .invoke(caller, market, 2.5f);
        callerClass.getMethod("constant", MarketAPI.class).invoke(caller, market);
        callerClass.getMethod("duplicate", MarketAPI.class, float.class)
                .invoke(caller, market, 3f);
        require(counter.calls == 4, "wrapper changed call multiplicity: " + counter.calls);
        require(Float.floatToIntBits(counter.sum) == Float.floatToIntBits(9.5f),
                "wrapper changed delivered amount: " + counter.sum);

        counter.throwNext = true;
        try {
            callerClass.getMethod("passThrough", MarketAPI.class, float.class)
                    .invoke(caller, market, 1f);
            throw new AssertionError("original exception was suppressed");
        } catch (InvocationTargetException expected) {
            require(expected.getCause() instanceof IllegalStateException,
                    "wrong exception propagated: " + expected.getCause());
        }

        System.out.println("OK direct-market-transformer callSites=" + hooks
                + " calls=" + counter.calls
                + " eager-manifest scheduler-sync-only semantic-risk-report");
    }

    private static URL archiveCodeSource(Path jar) throws Exception {
        String encoded = jar.toUri().toURL().toExternalForm();
        return new URL(encoded.replace("%20", " ") + "!/");
    }

    private static PrepatcherConfig config(boolean observation, boolean scheduler) throws Exception {
        return config(observation, scheduler, false);
    }

    private static PrepatcherConfig config(
            boolean observation, boolean scheduler, boolean riskObserver) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("patch.directMarketObservation", Boolean.toString(observation));
        properties.setProperty("patch.marketScheduler", Boolean.toString(scheduler));
        properties.setProperty("observer.marketAdvanceSemanticRisks",
                Boolean.toString(riskObserver));
        properties.setProperty("directMarket.reportIntervalSeconds", "3600");
        properties.setProperty("logging.statsIntervalSeconds", "0");
        Constructor<PrepatcherConfig> constructor =
                PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);
        return constructor.newInstance(properties);
    }

    private static int countOriginalMarketAdvanceCalls(byte[] bytes) {
        ClassNode node = read(bytes);
        int calls = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call
                        && "com/fs/starfarer/api/campaign/econ/MarketAPI".equals(call.owner)
                        && "advance".equals(call.name) && "(F)V".equals(call.desc)) {
                    calls++;
                }
            }
        }
        return calls;
    }

    private static int countHooks(byte[] bytes) {
        ClassNode node = read(bytes);
        int hooks = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && HOOKS.equals(call.owner)
                        && "observeDirectMarketAdvance".equals(call.name)
                        && HOOK_DESC.equals(call.desc)) {
                    hooks++;
                }
            }
        }
        return hooks;
    }

    private static byte[] generateClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                CLASS_NAME, null, "java/lang/Object", null);

        var ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        var pass = writer.visitMethod(Opcodes.ACC_PUBLIC, "passThrough",
                "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;F)V", null, null);
        pass.visitCode();
        org.objectweb.asm.Label passLine = new org.objectweb.asm.Label();
        pass.visitLabel(passLine);
        pass.visitLineNumber(10, passLine);
        pass.visitVarInsn(Opcodes.ALOAD, 1);
        pass.visitVarInsn(Opcodes.FLOAD, 2);
        pass.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/econ/MarketAPI", "advance", "(F)V", true);
        pass.visitInsn(Opcodes.RETURN);
        pass.visitMaxs(0, 0);
        pass.visitEnd();

        var constant = writer.visitMethod(Opcodes.ACC_PUBLIC, "constant",
                "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V", null, null);
        constant.visitCode();
        constant.visitVarInsn(Opcodes.ALOAD, 1);
        constant.visitInsn(Opcodes.FCONST_1);
        constant.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/econ/MarketAPI", "advance", "(F)V", true);
        constant.visitInsn(Opcodes.RETURN);
        constant.visitMaxs(0, 0);
        constant.visitEnd();

        var duplicate = writer.visitMethod(Opcodes.ACC_PUBLIC, "duplicate",
                "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;F)V", null, null);
        duplicate.visitCode();
        duplicate.visitVarInsn(Opcodes.ALOAD, 1);
        duplicate.visitVarInsn(Opcodes.FLOAD, 2);
        duplicate.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/econ/MarketAPI", "advance", "(F)V", true);
        duplicate.visitVarInsn(Opcodes.ALOAD, 1);
        duplicate.visitVarInsn(Opcodes.FLOAD, 2);
        duplicate.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/econ/MarketAPI", "advance", "(F)V", true);
        duplicate.visitInsn(Opcodes.RETURN);
        duplicate.visitMaxs(0, 0);
        duplicate.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }


    private static byte[] generateRiskClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        String name = "test/mod/RiskyIndustry";
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                name, null,
                "com/fs/starfarer/api/impl/campaign/econ/impl/BaseIndustry", null);
        writer.visitField(Opcodes.ACC_PRIVATE,
                "interval", "Lcom/fs/starfarer/api/util/IntervalUtil;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "progress", "F", null, null).visitEnd();

        var advance = writer.visitMethod(Opcodes.ACC_PUBLIC, "advance", "(F)V", null, null);
        advance.visitCode();
        advance.visitVarInsn(Opcodes.ALOAD, 0);
        advance.visitFieldInsn(Opcodes.GETFIELD, name,
                "interval", "Lcom/fs/starfarer/api/util/IntervalUtil;");
        advance.visitVarInsn(Opcodes.FLOAD, 1);
        advance.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/api/util/IntervalUtil", "advance", "(F)V", false);
        advance.visitVarInsn(Opcodes.ALOAD, 0);
        advance.visitFieldInsn(Opcodes.GETFIELD, name,
                "interval", "Lcom/fs/starfarer/api/util/IntervalUtil;");
        advance.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/api/util/IntervalUtil", "intervalElapsed", "()Z", false);
        advance.visitInsn(Opcodes.POP);
        advance.visitMethodInsn(Opcodes.INVOKESTATIC,
                "java/lang/Math", "random", "()D", false);
        advance.visitInsn(Opcodes.POP2);
        advance.visitVarInsn(Opcodes.ALOAD, 0);
        advance.visitVarInsn(Opcodes.FLOAD, 1);
        advance.visitFieldInsn(Opcodes.PUTFIELD, name, "progress", "F");
        advance.visitVarInsn(Opcodes.ALOAD, 0);
        advance.visitFieldInsn(Opcodes.GETFIELD,
                "com/fs/starfarer/api/impl/campaign/econ/impl/BaseIndustry",
                "market", "Lcom/fs/starfarer/api/campaign/econ/MarketAPI;");
        advance.visitLdcInsn("synthetic_condition");
        advance.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/econ/MarketAPI",
                "addCondition", "(Ljava/lang/String;)V", true);
        advance.visitVarInsn(Opcodes.ALOAD, 0);
        advance.visitFieldInsn(Opcodes.GETFIELD,
                "com/fs/starfarer/api/impl/campaign/econ/impl/BaseIndustry",
                "market", "Lcom/fs/starfarer/api/campaign/econ/MarketAPI;");
        advance.visitVarInsn(Opcodes.FLOAD, 1);
        advance.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/econ/MarketAPI",
                "advance", "(F)V", true);
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        advance.visitVarInsn(Opcodes.FLOAD, 1);
        advance.visitInsn(Opcodes.FCONST_0);
        advance.visitInsn(Opcodes.FCMPG);
        advance.visitJumpInsn(Opcodes.IFLE, done);
        advance.visitLabel(done);
        advance.visitInsn(Opcodes.RETURN);
        advance.visitMaxs(0, 0);
        advance.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] generateIndirectIndustryBase() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "test/mod/IndirectIndustryBase", null,
                "com/fs/starfarer/api/impl/campaign/econ/impl/BaseIndustry", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] generateIndirectRiskIndustry() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        String name = "test/mod/IndirectRiskIndustry";
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                name, null, "test/mod/IndirectIndustryBase", null);
        var advance = writer.visitMethod(Opcodes.ACC_PUBLIC, "advance", "(F)V", null, null);
        advance.visitCode();
        advance.visitMethodInsn(Opcodes.INVOKESTATIC,
                "java/lang/Math", "random", "()D", false);
        advance.visitInsn(Opcodes.POP2);
        advance.visitInsn(Opcodes.RETURN);
        advance.visitMaxs(0, 0);
        advance.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] generateUnrelatedAdvanceClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        String name = "test/mod/UnrelatedAdvance";
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                name, null, "java/lang/Object", null);
        var advance = writer.visitMethod(Opcodes.ACC_PUBLIC, "advance", "(F)V", null, null);
        advance.visitCode();
        advance.visitMethodInsn(Opcodes.INVOKESTATIC,
                "java/lang/Math", "random", "()D", false);
        advance.visitInsn(Opcodes.POP2);
        advance.visitInsn(Opcodes.RETURN);
        advance.visitMaxs(0, 0);
        advance.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM8);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return '\0';
        return null;
    }

    private static int dataRows(String text) {
        String[] lines = text.split("\\R");
        int rows = 0;
        for (int i = 1; i < lines.length; i++) if (!lines[i].isBlank()) rows++;
        return rows;
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Counter {
        int calls;
        float sum;
        boolean throwNext;
    }

    private static final class ChildLoader extends ClassLoader {
        private final HashMap<String, byte[]> resources = new HashMap<>();

        ChildLoader(ClassLoader parent) {
            super(parent);
        }

        void addResource(String name, byte[] bytes) {
            resources.put(name, bytes);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            byte[] bytes = resources.get(name);
            return bytes == null ? super.getResourceAsStream(name)
                    : new ByteArrayInputStream(bytes);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
