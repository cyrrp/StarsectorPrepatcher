package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarFile;

/** Route-level regression for raw and Java-27-repaired UI market field names. */
public final class UiMutationRepairedNamePipelineTest {
    private static final String TRADE = TradeMarketMutationTransformer.TARGET;
    private static final String OVERVIEW = MarketOverviewMutationTransformer.TARGET;
    private static final String CARGO_STATUS =
            "starsector.prepatcher.campaignCargoNoGlobalEconomyStepPatch";

    private UiMutationRepairedNamePipelineTest() {}

    public static void main(String[] args) throws Exception {
        require(args.length == 1,
                "Usage: UiMutationRepairedNamePipelineTest <starfarer_obf.jar>");
        Path gameJar = Path.of(args[0]);
        byte[] trade = readClass(gameJar, TRADE);
        byte[] overview = readClass(gameJar, OVERVIEW);

        for (Route route : Route.values()) {
            exerciseTradeRoute(route, trade);
            exerciseOverviewRoute(route, overview);
        }
        assertLateCargoFailureRollsBack(trade);
        System.out.println("OK UI mutation pipeline raw/JAVA27_STANDARD/FR_AGENT_CHAIN/"
                + "FR_PREDEFINE_BRIDGE aliases, composition and atomic rollback");
    }

    private static void exerciseTradeRoute(Route route, byte[] raw) throws Exception {
        clearTradeStatuses();
        byte[] input = route.preRepaired
                ? IllegalObfuscatedMemberNameRepair.repair(TRADE, raw) : raw;
        OrderedTransformerPipeline pipeline = tradePipeline(route);
        byte[] transformed = apply(route, pipeline, TRADE, input);
        require(transformed != null, route + " returned null for the trade target");
        require("APPLIED".equals(System.getProperty(
                        TradeMarketMutationTransformer.statusProperty())),
                route + " did not apply the trade mutation patch");
        require("APPLIED".equals(System.getProperty(CARGO_STATUS)),
                route + " did not apply the later Cargo patch");
        require(field(read(transformed), "spp$patched$tradeMarketMutationRefresh") != null,
                route + " trade marker missing");
        require(field(read(transformed), "spp$patched$aotdDetachedCargoContext") != null,
                route + " Cargo marker missing");
        require(System.getProperty(pipelineFailure(TRADE)) == null,
                route + " published an unexpected trade pipeline failure");
        verify(transformed);
        byte[] repeated = apply(route, pipeline, TRADE, transformed);
        if (route.predefine) {
            require(Arrays.equals(repeated, transformed),
                    route + " changed the fully composed trade class on reprocessing");
        } else {
            require(repeated == null,
                    route + " changed the fully composed trade class on reprocessing");
        }
    }

    private static void exerciseOverviewRoute(Route route, byte[] raw) throws Exception {
        System.clearProperty(MarketOverviewMutationTransformer.statusProperty());
        System.clearProperty(pipelineFailure(OVERVIEW));
        byte[] input = route.preRepaired
                ? IllegalObfuscatedMemberNameRepair.repair(OVERVIEW, raw) : raw;
        OrderedTransformerPipeline pipeline = overviewPipeline(route);
        byte[] transformed = apply(route, pipeline, OVERVIEW, input);
        require(transformed != null, route + " returned null for the market overview target");
        require("APPLIED".equals(System.getProperty(
                        MarketOverviewMutationTransformer.statusProperty())),
                route + " did not apply the market overview patch");
        require(field(read(transformed), "spp$patched$marketOverviewMutationRefresh") != null,
                route + " market overview marker missing");
        require(System.getProperty(pipelineFailure(OVERVIEW)) == null,
                route + " published an unexpected overview pipeline failure");
        verify(transformed);
        byte[] repeated = apply(route, pipeline, OVERVIEW, transformed);
        if (route.predefine) {
            require(Arrays.equals(repeated, transformed),
                    route + " changed the market overview class on reprocessing");
        } else {
            require(repeated == null,
                    route + " changed the market overview class on reprocessing");
        }
    }

    private static void assertLateCargoFailureRollsBack(byte[] raw) {
        clearTradeStatuses();
        byte[] damaged = changeFakeMarketLiteral(raw);
        byte[] compatible = IllegalObfuscatedMemberNameRepair.repair(TRADE, damaged);
        OrderedTransformerPipeline pipeline = tradePipeline(Route.JAVA27_STANDARD);
        byte[] result = pipeline.transform(null, TRADE, null, null, damaged);
        require(Arrays.equals(result, compatible),
                "late Cargo rejection did not roll back to the repaired pipeline input");
        ClassNode rolledBack = read(result);
        require(field(rolledBack, "spp$patched$tradeMarketMutationRefresh") == null,
                "trade marker survived atomic pipeline rollback");
        require(field(rolledBack, "spp$patched$aotdDetachedCargoContext") == null,
                "Cargo marker survived atomic pipeline rollback");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(CARGO_STATUS)),
                "late Cargo rejection status changed");
        String failure = System.getProperty(pipelineFailure(TRADE), "");
        require(failure.contains("stage 'AoTD detached-cargo context'")
                        && failure.contains("=SKIPPED_STRUCTURAL"),
                "late Cargo rejection did not publish the pipeline failure");
    }

    private static OrderedTransformerPipeline tradePipeline(Route route) {
        ClassLoader runtimeLoader = route.predefine
                ? UiMutationRepairedNamePipelineTest.class.getClassLoader() : null;
        return new OrderedTransformerPipeline(List.of(
                new OrderedTransformerPipeline.Stage("Trade mutation",
                        new TradeMarketMutationTransformer(true, runtimeLoader)),
                new OrderedTransformerPipeline.Stage("AoTD detached-cargo context",
                        new AoTDDetachedCargoContextTransformer(true, runtimeLoader))),
                runtimeLoader, route.predefine, route.repairInPipeline);
    }

    private static OrderedTransformerPipeline overviewPipeline(Route route) {
        ClassLoader runtimeLoader = route.predefine
                ? UiMutationRepairedNamePipelineTest.class.getClassLoader() : null;
        return new OrderedTransformerPipeline(List.of(
                new OrderedTransformerPipeline.Stage("Market mutation",
                        new MarketOverviewMutationTransformer(true, runtimeLoader))),
                runtimeLoader, route.predefine, route.repairInPipeline);
    }

    private static byte[] apply(
            Route route, OrderedTransformerPipeline pipeline, String target, byte[] bytes) {
        if (route.predefine) return pipeline.applyPredefine(target + ".class", bytes);
        return pipeline.transform(null, target, null, null, bytes);
    }

    private static void clearTradeStatuses() {
        System.clearProperty(TradeMarketMutationTransformer.statusProperty());
        System.clearProperty(CARGO_STATUS);
        System.clearProperty("starsector.prepatcher.aotdDetachedCargoContextPatch");
        System.clearProperty(pipelineFailure(TRADE));
    }

    private static String pipelineFailure(String target) {
        return "starsector.prepatcher.pipelineFailure." + target.replace('/', '.');
    }

    private static byte[] changeFakeMarketLiteral(byte[] bytes) {
        ClassNode node = read(bytes);
        int changed = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof LdcInsnNode literal
                        && "fake_market".equals(literal.cst)) {
                    literal.cst = "future_fake_market";
                    changed++;
                }
            }
        }
        require(changed == 1, "fake_market fixture literal count changed: " + changed);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] readClass(Path jarPath, String internalName) throws Exception {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entry = jar.getJarEntry(internalName + ".class");
            require(entry != null, "missing " + internalName);
            try (var input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
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
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static FieldNode field(ClassNode node, String name) {
        for (FieldNode field : node.fields) if (name.equals(field.name)) return field;
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private enum Route {
        JAVA17_STANDARD(false, false, false),
        JAVA27_STANDARD(false, true, false),
        FR_AGENT_CHAIN(true, false, false),
        FR_PREDEFINE_BRIDGE(true, false, true);

        private final boolean preRepaired;
        private final boolean repairInPipeline;
        private final boolean predefine;

        Route(boolean preRepaired, boolean repairInPipeline, boolean predefine) {
            this.preRepaired = preRepaired;
            this.repairInPipeline = repairInPipeline;
            this.predefine = predefine;
        }
    }
}
