package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarFile;

/** Structural and ownership checks for the optional Nexerelin caller patch. */
public final class MarketShareOptionalCompatibilityTest {
    private static final String TARGET =
            "exerelin/campaign/intel/Nex_PunitiveExpeditionManager";
    private static final String API =
            "com/fs/starfarer/api/campaign/econ/CommodityMarketDataAPI";
    private static final String REASONS_DESC =
            "(Lcom/fs/starfarer/api/impl/campaign/intel/punitive/"
                    + "PunitiveExpeditionManager$PunExData;)Ljava/util/List;";
    private static final String HELPER_DESC =
            "(Lcom/fs/starfarer/api/campaign/econ/CommodityMarketDataAPI;"
                    + "Lcom/fs/starfarer/api/campaign/FactionAPI;Ljava/util/Map;"
                    + "Ljava/util/IdentityHashMap;)I";

    private MarketShareOptionalCompatibilityTest() {}

    public static void main(String[] args) throws Exception {
        require(args.length == 1,
                "Usage: MarketShareOptionalCompatibilityTest <ExerelinCore.jar>");
        byte[] original = classBytes(Path.of(args[0]));
        PrepatcherConfig config = config(false, true);
        assertSwitchIsolation(original);

        PrepatcherTransformer transformer = new PrepatcherTransformer(config);
        byte[] patched = transformer.transform(null, TARGET, null, null, original);
        require(patched != null, "Nexerelin punitive patch did not apply");
        assertPostcondition(patched);
        verify(patched);

        int skippedBefore = integerProperty("starsector.prepatcher.skippedPatches");
        byte[] repeated = transformer.transform(null, TARGET, null, null, patched);
        require(repeated == null, "Nexerelin punitive patch is not idempotent");
        require(integerProperty("starsector.prepatcher.skippedPatches") == skippedBefore,
                "idempotent Nexerelin transform was reported as incompatible");

        ClassNode damaged = read(original);
        MethodNode reasons = method(damaged, "getExpeditionReasons", REASONS_DESC);
        List<MethodInsnNode> direct = calls(reasons, Opcodes.INVOKEINTERFACE,
                API, "getMarketSharePercent",
                "(Lcom/fs/starfarer/api/campaign/FactionAPI;)I");
        require(direct.size() == 2, "Nexerelin negative fixture call count changed");
        direct.get(0).name = "spp$damagedPlayerShare";
        String statusKey = "starsector.prepatcher.patchStatus."
                + TARGET.replace('/', '.') + ".punitivePlayerShareLocalCache";
        System.clearProperty(statusKey);
        byte[] rejected = new PrepatcherTransformer(config).transform(
                null, TARGET, null, null, write(damaged));
        require(rejected == null,
                "Nexerelin partial caller shape was transformed instead of rejected");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(statusKey)),
                "Nexerelin partial caller shape did not publish SKIPPED_STRUCTURAL");

        System.out.println("OK optional-nex-market-share applied idempotent verified "
                + "switch-isolation defaults-enabled negative-structural-retention-free");
    }

    private static void assertSwitchIsolation(byte[] original) throws Exception {
        PrepatcherTransformer vanillaOnly = new PrepatcherTransformer(
                config(true, false));
        require(vanillaOnly.isTargetEnabled(
                        PrepatcherTransformer.PUNITIVE_EXPEDITION_MANAGER),
                "vanilla punitive switch did not enable vanilla target");
        require(!vanillaOnly.isTargetEnabled(TARGET),
                "vanilla punitive switch unexpectedly enabled Nex target");
        require(vanillaOnly.transform(null, TARGET, null, null, original) == null,
                "disabled Nex switch still transformed the Nex target");

        PrepatcherTransformer nexOnly = new PrepatcherTransformer(
                config(false, true));
        require(!nexOnly.isTargetEnabled(
                        PrepatcherTransformer.PUNITIVE_EXPEDITION_MANAGER),
                "Nex punitive switch unexpectedly enabled vanilla target");
        require(nexOnly.isTargetEnabled(TARGET),
                "Nex punitive switch did not enable Nex target");

        PrepatcherConfig defaults = configWithProperties(new Properties());
        PrepatcherTransformer defaultTransformer =
                new PrepatcherTransformer(defaults);
        require(defaultTransformer.isTargetEnabled(
                        PrepatcherTransformer.PUNITIVE_EXPEDITION_MANAGER),
                "vanilla punitive switch is not enabled by default");
        require(defaultTransformer.isTargetEnabled(TARGET),
                "Nex punitive switch is not enabled by default");
    }

    private static PrepatcherConfig config(boolean vanilla, boolean nex)
            throws Exception {
        Properties properties = new Properties();
        properties.setProperty("patch.marketShareLinearAggregation", "false");
        properties.setProperty("patch.marketShareDataPutElision", "false");
        properties.setProperty("patch.punitivePlayerShareLocalCache",
                Boolean.toString(vanilla));
        properties.setProperty("patch.nexPunitivePlayerShareLocalCache",
                Boolean.toString(nex));
        return configWithProperties(properties);
    }

    private static PrepatcherConfig configWithProperties(Properties properties)
            throws Exception {
        Constructor<PrepatcherConfig> constructor =
                PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);
        return constructor.newInstance(properties);
    }

    private static void assertPostcondition(byte[] bytes) {
        ClassNode node = read(bytes);
        require(hasMarker(node, "punitivePlayerShareLocalCache"),
                "Nexerelin ownership marker missing");
        MethodNode reasons = method(node, "getExpeditionReasons", REASONS_DESC);
        MethodNode helper = method(node, "spp$punitiveCachedPlayerShare", HELPER_DESC);
        require((helper.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC
                        | Opcodes.ACC_SYNTHETIC))
                        == (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC
                        | Opcodes.ACC_SYNTHETIC),
                "Nexerelin helper metadata changed");
        require(calls(reasons, Opcodes.INVOKESTATIC, TARGET,
                        "spp$punitiveCachedPlayerShare", HELPER_DESC).size() == 2,
                "Nexerelin helper call count changed");
        require(calls(reasons, Opcodes.INVOKEINTERFACE, API,
                        "getMarketSharePercent",
                        "(Lcom/fs/starfarer/api/campaign/FactionAPI;)I").isEmpty(),
                "Nexerelin direct player-share calls remain");
        int allocations = 0;
        for (AbstractInsnNode insn : reasons.instructions.toArray()) {
            if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                    && "java/util/IdentityHashMap".equals(type.desc)) allocations++;
        }
        require(allocations == 1,
                "Nexerelin local cache allocation count changed: " + allocations);
        for (FieldNode field : node.fields) {
            require(!field.name.startsWith("spp$punitive"),
                    "Nexerelin patch introduced retained state: " + field.name);
        }
    }

    private static byte[] classBytes(Path jarPath) throws Exception {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entry = jar.getJarEntry(TARGET + ".class");
            require(entry != null, "Nexerelin target missing from " + jarPath);
            return jar.getInputStream(entry).readAllBytes();
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM8);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void verify(byte[] bytes) throws Exception {
        ClassNode node = read(bytes);
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
        }
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        List<MethodNode> found = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) found.add(method);
        }
        require(found.size() == 1,
                "method " + name + desc + " expected once, found " + found.size());
        return found.get(0);
    }

    private static List<MethodInsnNode> calls(MethodNode method, int opcode,
                                              String owner, String name, String desc) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == opcode
                    && call.owner.equals(owner) && call.name.equals(name)
                    && call.desc.equals(desc)) result.add(call);
        }
        return result;
    }

    private static boolean hasMarker(ClassNode node, String patchId) {
        String name = "smo$patched$" + patchId;
        for (FieldNode field : node.fields) {
            if (field.name.equals(name) && "Ljava/lang/String;".equals(field.desc)) return true;
        }
        return false;
    }

    private static int integerProperty(String key) {
        String value = System.getProperty(key);
        return value == null ? 0 : Integer.parseInt(value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
