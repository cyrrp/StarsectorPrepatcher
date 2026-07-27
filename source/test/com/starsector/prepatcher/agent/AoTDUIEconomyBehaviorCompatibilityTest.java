package com.starsector.prepatcher.agent;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.FieldInsnNode;
import jdk.internal.org.objectweb.asm.tree.FieldNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Release gate for the owned AoTD single-market UI economy path.
 *
 * <p>This verifies the exact bytecode contract responsible for replacing the
 * all-market synchronous refresh with a revision-gated single-market cut. It is
 * intentionally fail-closed: a future fork must be reviewed when this semantic
 * surface changes.</p>
 */
public final class AoTDUIEconomyBehaviorCompatibilityTest {
    private static final String ROOT = "data/kaysaar/aotd/tot/";
    private static final String ECONOMY = ROOT + "scripts/economy/AoTDEconomy";
    private static final String REACH = ROOT + "scripts/economy/AoTDReachEconomy";
    private static final String MAIN = ROOT + "scripts/economy/AoTdMainWorkTask2";
    private static final String COORDINATOR =
            ROOT + "scripts/economy/AoTDUIEconomyRefreshCoordinator";
    private static final String FINISH =
            ROOT + "scripts/economy/AoTDFinishEconomyUpdateTask";
    private static final String POST =
            ROOT + "scripts/economy/AoTDPostImmigrationTradeSnapshotTask";
    private static final String BRIDGE = ROOT + "compat/SchedulerBridge";
    private static final String PARAMS =
            "Lcom/fs/starfarer/campaign/econ/reach/MainWorkTask$EconWorkParams;";
    private static final String MARKET =
            "Lcom/fs/starfarer/api/campaign/econ/MarketAPI;";

    private AoTDUIEconomyBehaviorCompatibilityTest() {}

    public static void main(String[] args) throws Exception {
        require(args.length == 1,
                "Usage: AoTDUIEconomyBehaviorCompatibilityTest <AoTDToolboxTheory.jar>");
        Path jar = Path.of(args[0]);

        ClassNode economy = read(jar, ECONOMY);
        ClassNode reach = read(jar, REACH);
        ClassNode main = read(jar, MAIN);
        ClassNode coordinator = read(jar, COORDINATOR);
        ClassNode finish = read(jar, FINISH);
        ClassNode post = read(jar, POST);
        ClassNode bridge = read(jar, BRIDGE);

        verifyOwnerLocalCoordinator(economy, coordinator);
        verifyBridgeSurface(bridge);
        verifyEconomyRouting(economy);
        verifySingleMarketPipeline(reach);
        verifyNoGlobalCommodityBuildInUiMode(main);
        verifyListenerOnlyBoundary(finish);
        verifySubsetRegistryAudit(post);

        System.out.println("OK aotd-ui-economy-behavior"
                + " early-market-context owner-local-transient-revision-gate"
                + " single-market-main/update/immigration/snapshot"
                + " no-ui-global-commodity-build no-ui-global-trade-cut"
                + " listener-boundary subset-registry-audit");
    }

    private static void verifyOwnerLocalCoordinator(
            ClassNode economy, ClassNode coordinator) {
        FieldNode ownerField = requireField(economy, "uiRefreshCoordinator");
        require((ownerField.access & Opcodes.ACC_TRANSIENT) != 0,
                "AoTDEconomy.uiRefreshCoordinator is not transient");
        require((ownerField.access & Opcodes.ACC_STATIC) == 0,
                "AoTDEconomy.uiRefreshCoordinator became static");
        require(("L" + COORDINATOR + ";").equals(ownerField.desc),
                "AoTDEconomy.uiRefreshCoordinator type changed: " + ownerField.desc);

        Set<String> allowed = Set.of("J", "I", "Ljava/lang/String;");
        for (FieldNode field : coordinator.fields) {
            require((field.access & Opcodes.ACC_STATIC) == 0,
                    "coordinator gained static state: " + field.name);
            require(allowed.contains(field.desc),
                    "coordinator gained object/campaign retention surface: "
                            + field.name + " " + field.desc);
        }
        requireMethod(coordinator, "isCurrent", "(" + MARKET + ")Z");
        requireMethod(coordinator, "recordCompleted", "(" + MARKET + ")V");
    }

    private static void verifyBridgeSurface(ClassNode bridge) {
        MethodNode method = requireMethod(
                bridge, "consumeOpeningMarket", "()Ljava/lang/Object;");
        require((method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
                        == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC),
                "SchedulerBridge.consumeOpeningMarket is not public static");
    }

    private static void verifyEconomyRouting(ClassNode economy) {
        MethodNode next = requireMethod(economy, "nextStep", "(" + PARAMS + ")V");
        require(countCalls(next, COORDINATOR,
                        "consumeOpeningMarket", "()" + MARKET) == 1,
                "AoTDEconomy.nextStep no longer consumes the early market context once");
        require(countCalls(next, ECONOMY, "runUiMarketRefresh",
                        "(" + MARKET + PARAMS + "Ljava/lang/String;Z)V") == 1,
                "AoTDEconomy.nextStep no longer routes to the UI coordinator");

        MethodNode triple = requireMethod(economy, "tripleStep", "()V");
        require(countCalls(triple, ECONOMY, "runUiMarketRefresh",
                        "(" + MARKET + PARAMS + "Ljava/lang/String;Z)V") == 1,
                "Cargo tripleStep no longer uses revision-gated UI refresh");
    }

    private static void verifySingleMarketPipeline(ClassNode reach) {
        String desc = "(" + PARAMS + MARKET + "Ljava/lang/String;)V";
        MethodNode ui = requireMethod(reach, "nextStepForUiMarket", desc);

        require(countCalls(ui,
                        "com/fs/starfarer/campaign/econ/reach/ReachEconomy",
                        "getMarkets", "()Ljava/util/List;") == 0,
                "UI path scans the complete ReachEconomy market list");
        require(countCalls(ui, REACH, "runMainTask",
                        "(Ljava/util/List;" + PARAMS + MARKET + ")V") == 2,
                "UI path must have initial and conditional local follow-up main tasks");
        require(countConstructors(ui,
                        ROOT + "scripts/economy/AoTDUpdateMarketAgainTask",
                        "(Lcom/fs/starfarer/campaign/econ/Economy;" + MARKET + ")V") == 2,
                "UI path must reconcile only the selected market before/after snapshot");
        require(countConstructors(ui,
                        "com/fs/starfarer/campaign/econ/reach/ImmigrationTask",
                        "(Ljava/util/List;Lcom/fs/starfarer/campaign/econ/reach/ReachEconomy;Z)V") == 1,
                "UI path must run one singleton immigration task");
        require(countConstructors(ui,
                        POST,
                        "(Ljava/util/List;Ljava/lang/String;)V") == 1,
                "UI path must capture one singleton post-immigration snapshot batch");
        require(countCalls(ui, FINISH, "notifyEconomyListenersOnly",
                        "(Lcom/fs/starfarer/campaign/econ/Economy;Ljava/lang/String;)V") == 1,
                "UI path lost the observable economyUpdated listener boundary");
        require(countConstructors(ui, FINISH,
                        "(Lcom/fs/starfarer/campaign/econ/Economy;)V") == 0,
                "UI path reopened the global internal-trade cut");
        require(countConstructors(ui,
                        ROOT + "scripts/commoditydata/AoTDCommodityMarketData",
                        null) == 0,
                "UI path directly rebuilds global commodity-market data");
    }

    private static void verifyNoGlobalCommodityBuildInUiMode(ClassNode main) {
        FieldNode local = requireField(main, "uiLocalMode");
        require("Z".equals(local.desc) && (local.access & Opcodes.ACC_STATIC) == 0,
                "AoTdMainWorkTask2.uiLocalMode contract changed");

        MethodNode localCtor = requireMethod(main, "<init>",
                "(Ljava/util/List;Lcom/fs/starfarer/campaign/econ/reach/ReachEconomy;"
                        + PARAMS + MARKET + ")V");
        require(countFieldWrites(localCtor, MAIN, "uiLocalMode", "Z") == 1,
                "single-market constructor no longer enables UI-local mode");

        MethodNode start = requireMethod(main, "startTaskState", "()V");
        require(hasFieldCopy(start, MAIN, "uiLocalMode", "mtDataCreated"),
                "UI-local mode no longer marks global commodity-data construction complete");
    }

    private static void verifyListenerOnlyBoundary(ClassNode finish) {
        MethodNode notify = requireMethod(finish, "notifyEconomyListenersOnly",
                "(Lcom/fs/starfarer/campaign/econ/Economy;Ljava/lang/String;)V");
        require((notify.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
                        == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC),
                "listener-only boundary is not public static");
        require(countCalls(notify,
                        "com/fs/starfarer/api/campaign/econ/EconomyAPI$EconomyUpdateListener",
                        "economyUpdated", "()V") == 1,
                "listener-only boundary no longer publishes economyUpdated");
    }

    private static void verifySubsetRegistryAudit(ClassNode post) {
        MethodNode commit = requireMethod(post, "commitRegistryState", "()V");
        String registry = ROOT + "compat/MarketRegistry";
        require(countCalls(commit, registry, "auditInvariants",
                        "(Ljava/util/Map;)L" + registry + "$InvariantReport;") == 1,
                "full-set registry audit path missing");
        require(countCalls(commit, registry, "auditInvariants",
                        "()L" + registry + "$InvariantReport;") == 1,
                "subset-safe registry audit path missing");
    }

    private static boolean hasFieldCopy(
            MethodNode method, String owner, String source, String target) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
             instruction != null; instruction = instruction.getNext()) {
            if (!(instruction instanceof FieldInsnNode read)
                    || read.getOpcode() != Opcodes.GETFIELD
                    || !owner.equals(read.owner) || !source.equals(read.name)
                    || !"Z".equals(read.desc)) continue;
            int remaining = 6;
            for (AbstractInsnNode next = read.getNext();
                 next != null && remaining-- > 0; next = next.getNext()) {
                if (next instanceof FieldInsnNode write
                        && write.getOpcode() == Opcodes.PUTFIELD
                        && owner.equals(write.owner) && target.equals(write.name)
                        && "Z".equals(write.desc)) return true;
            }
        }
        return false;
    }

    private static int countFieldWrites(
            MethodNode method, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && owner.equals(field.owner) && name.equals(field.name)
                    && desc.equals(field.desc)) count++;
        }
        return count;
    }

    private static int countConstructors(
            MethodNode method, String owner, String desc) {
        return countCalls(method, owner, "<init>", desc);
    }

    private static int countCalls(
            MethodNode method, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && (desc == null || desc.equals(call.desc))) count++;
        }
        return count;
    }

    private static FieldNode requireField(ClassNode node, String name) {
        for (FieldNode field : node.fields) if (name.equals(field.name)) return field;
        throw new AssertionError("Missing field " + node.name + '.' + name);
    }

    private static MethodNode requireMethod(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) return method;
        }
        throw new AssertionError("Missing method " + node.name + '.' + name + desc);
    }

    private static ClassNode read(Path jarPath, String internalName) throws Exception {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entry = jar.getJarEntry(internalName + ".class");
            require(entry != null, "Missing class " + internalName + " in " + jarPath);
            try (InputStream input = jar.getInputStream(entry)) {
                ClassNode node = new ClassNode(Opcodes.ASM8);
                new ClassReader(input).accept(node, 0);
                return node;
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
