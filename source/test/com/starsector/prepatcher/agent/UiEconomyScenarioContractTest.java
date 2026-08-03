package com.starsector.prepatcher.agent;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

/** Exact game-JAR contract for Survey, salvage LOOT and actual colony creation. */
public final class UiEconomyScenarioContractTest {
    private static final String SURVEY =
            "com/fs/starfarer/campaign/ui/marketinfo/PlanetSurveyPanel";
    private static final String SALVAGE =
            "com/fs/starfarer/api/impl/campaign/rulecmd/salvage/SalvageEntity";

    private UiEconomyScenarioContractTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected starfarer_obf.jar starfarer.api.jar");
        }
        ClassNode survey = readClass(Path.of(args[0]), SURVEY);
        ClassNode salvage = readClass(Path.of(args[1]), SALVAGE);
        checkSurvey(survey);
        checkColonization(survey);
        checkSalvage(salvage);
        System.out.println(
                "OK UI economy scenario contract: "
                        + "survey-local/colonization-global/loot-generated-before-transfer");
    }

    private static void checkSurvey(ClassNode node) {
        MethodNode method = method(node, "applySurveyResults");
        require(method != null, "PlanetSurveyPanel.applySurveyResults missing");
        List<MethodInsnNode> calls = calls(method);
        int surveyed = index(calls, "com/fs/starfarer/api/util/Misc", "setFullySurveyed");
        int data = index(calls, "com/fs/starfarer/api/util/Misc", "addSurveyDataFor");
        int reported = index(calls,
                "com/fs/starfarer/api/campaign/listeners/ListenerUtil",
                "reportPlayerSurveyedPlanet");
        require(surveyed >= 0 && data > surveyed && reported > data,
                "Survey result publication order changed");
        require(!containsStep(calls), "Survey unexpectedly contains economy step");
        require(indexName(calls, "showLoot") < 0,
                "Survey unexpectedly opens LOOT transfer UI");
    }

    private static void checkColonization(ClassNode node) {
        MethodNode colonization = null;
        for (MethodNode method : node.methods) {
            if (index(calls(method),
                    "com/fs/starfarer/api/campaign/econ/MarketAPI",
                    "setPlanetConditionMarketOnly") >= 0) {
                require(colonization == null,
                        "multiple PlanetSurveyPanel colonization candidates");
                colonization = method;
            }
        }
        require(colonization != null, "colonization method missing");
        List<MethodInsnNode> calls = calls(colonization);
        int condition = index(calls,
                "com/fs/starfarer/api/campaign/econ/MarketAPI",
                "setPlanetConditionMarketOnly");
        int add = index(calls,
                "com/fs/starfarer/api/campaign/econ/EconomyAPI", "addMarket");
        int step = index(calls,
                "com/fs/starfarer/api/campaign/econ/EconomyAPI", "tripleStep");
        int advance = index(calls,
                "com/fs/starfarer/api/campaign/econ/MarketAPI", "advance");
        int report = index(calls,
                "com/fs/starfarer/api/campaign/listeners/ListenerUtil",
                "reportPlayerColonizedPlanet");
        require(condition >= 0 && add > condition && step > add
                        && advance > step && report > advance,
                "colonization ordering changed: expected conditionOnly(false) -> "
                        + "addMarket -> tripleStep -> advance(0) -> callback");
        require(count(calls,
                "com/fs/starfarer/api/campaign/econ/EconomyAPI", "tripleStep") == 1,
                "colonization tripleStep count changed");
    }

    private static void checkSalvage(ClassNode node) {
        MethodNode method = method(node, "performSalvage");
        require(method != null, "SalvageEntity.performSalvage missing");
        List<MethodInsnNode> calls = calls(method);
        int generate = index(calls, SALVAGE, "generateSalvage");
        int show = index(calls,
                "com/fs/starfarer/api/campaign/VisualPanelAPI", "showLoot");
        require(generate >= 0 && show > generate,
                "salvage must be generated before LOOT transfer UI");
        require(!containsStep(calls), "performSalvage unexpectedly contains economy step");
    }

    private static boolean containsStep(List<MethodInsnNode> calls) {
        for (MethodInsnNode call : calls) {
            if (("nextStep".equals(call.name) || "doubleStep".equals(call.name)
                    || "tripleStep".equals(call.name))
                    && call.owner.contains("campaign/econ")) return true;
        }
        return false;
    }

    private static ClassNode readClass(Path jarPath, String internalName) throws Exception {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entry = jar.getJarEntry(internalName + ".class");
            require(entry != null, "missing " + internalName + " in " + jarPath);
            try (var input = jar.getInputStream(entry)) {
                ClassNode node = new ClassNode();
                new ClassReader(input.readAllBytes()).accept(node, 0);
                return node;
            }
        }
    }

    private static MethodNode method(ClassNode node, String name) {
        MethodNode found = null;
        for (MethodNode method : node.methods) {
            if (!name.equals(method.name)) continue;
            require(found == null, "duplicate method name " + node.name + "." + name);
            found = method;
        }
        return found;
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) result.add(call);
        }
        return result;
    }

    private static int index(List<MethodInsnNode> calls, String owner, String name) {
        for (int i = 0; i < calls.size(); i++) {
            MethodInsnNode call = calls.get(i);
            if (owner.equals(call.owner) && name.equals(call.name)) return i;
        }
        return -1;
    }

    private static int indexName(List<MethodInsnNode> calls, String name) {
        for (int i = 0; i < calls.size(); i++) if (name.equals(calls.get(i).name)) return i;
        return -1;
    }

    private static int count(List<MethodInsnNode> calls, String owner, String name) {
        int count = 0;
        for (MethodInsnNode call : calls) {
            if (owner.equals(call.owner) && name.equals(call.name)) count++;
        }
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
