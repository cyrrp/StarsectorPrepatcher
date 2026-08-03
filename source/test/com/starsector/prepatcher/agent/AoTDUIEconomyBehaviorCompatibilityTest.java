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
import java.util.HashMap;
import java.util.Map;
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
    private static final String CONTRACT = ROOT + "compat/PrepatcherContract";
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
        ClassNode contract = read(jar, CONTRACT);
        ClassNode bridge = read(jar, BRIDGE);

        verifyNoForkOwnedReadOnlyUiOverrides(jar);
        verifyExplicitDispatcherContract(contract, bridge, economy);
        verifyOwnerLocalCoordinator(economy, coordinator);
        verifyEconomyRouting(economy);
        verifySingleMarketPipeline(reach);
        verifyNoGlobalCommodityBuildInUiMode(main);
        verifyListenerOnlyBoundary(finish);
        verifySubsetRegistryAudit(post);

        System.out.println("OK aotd-ui-economy-behavior"
                + " spp8-explicit-dispatch standard-steps-global"
                + " owner-local-transient-revision-gate"
                + " single-market-main/update/immigration/snapshot"
                + " no-ui-global-commodity-build no-ui-global-trade-cut"
                + " explicit-cargo-skip ui-market-mutation-refresh"
                + " read-only-ui-call-sites-vanilla-owned"
                + " listener-boundary subset-registry-audit");
    }

    private static void verifyExplicitDispatcherContract(
            ClassNode contract, ClassNode bridge, ClassNode economy) {
        requireConstant(contract, "FORK_VERSION", "Ljava/lang/String;",
                "1.0.14-spp8");
        requireConstant(contract, "PRODUCTION_CAPABILITIES", "J",
                Long.valueOf(0x3ffL));
        requireConstant(contract, "DECLARED_CAPABILITIES", "J",
                Long.valueOf(0x7ffL));
        requireConstant(contract, "CAPABILITY_UI_ECONOMY_DISPATCH", "J",
                Long.valueOf(1L << 9));
        requireConstant(contract, "UI_ECONOMY_ACTION_MARKET_OPEN", "I",
                Integer.valueOf(1));
        requireConstant(contract, "UI_ECONOMY_ACTION_CARGO", "I",
                Integer.valueOf(2));
        requireConstant(contract, "UI_ECONOMY_ACTION_MARKET_MUTATION", "I",
                Integer.valueOf(3));
        require(!hasField(contract, "ABI_VERSION"),
                "current fork contract retained obsolete ABI_VERSION");
        require(!hasField(contract, "CAPABILITY_UI_CALL_CONTEXTS"),
                "current fork contract retained obsolete UI capability alias");
        requireConstant(bridge, "BRIDGE_SCHEMA", "I", Integer.valueOf(9));
        requireConstant(bridge, "BRIDGE_MARKER", "Ljava/lang/String;",
                "AOTD_SCHEDULER_BRIDGE_V9");
        for (String legacy : new String[] {
                "consumeOpeningMarket", "consumeDetachedCargoOpen",
                "consumeUiMarketMutation", "consumeUiMarketMutationPayload"}) {
            require(!hasMethod(bridge, legacy),
                    "current SchedulerBridge retained legacy consumer " + legacy);
        }

        String desc = "(I" + MARKET + "J[Ljava/lang/String;)Z";
        MethodNode dispatcher = requireMethod(
                economy, "dispatchPrepatcherUiEconomyStep", desc);
        require((dispatcher.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL))
                        == (Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL),
                "AoTD explicit UI dispatcher is not public final");
        require((dispatcher.access & Opcodes.ACC_STATIC) == 0,
                "AoTD explicit UI dispatcher unexpectedly became static");
        require(countCalls(dispatcher, BRIDGE, "hasCapability", "(J)Z") >= 1,
                "AoTD explicit UI dispatcher lost capability gating");

        require(countCalls(dispatcher, ECONOMY, "isConditionOnlyOpeningMarket",
                        "(" + MARKET + ")Z") == 1,
                "explicit market-open route lost condition-only classification");
        MethodNode conditionOnly = requireMethod(
                economy, "isConditionOnlyOpeningMarket", "(" + MARKET + ")Z");
        require(countCallsNamed(conditionOnly, "lookupMarket") == 1,
                "condition-only classifier no longer rejects a registered live market");
        require(countCalls(dispatcher, COORDINATOR,
                        "recordConditionOnlySkip", "()V") == 1,
                "explicit market-open route lost its observable condition-only skip");
        require(countCalls(dispatcher, COORDINATOR,
                        "recordSyntheticCargoSkip", "()V") == 1,
                "explicit Cargo route lost its observable synthetic skip");
        require(countCalls(dispatcher, ECONOMY, "runUiMarketRefresh",
                        "(" + MARKET + PARAMS + "Ljava/lang/String;Z)V") == 3,
                "explicit UI dispatcher no longer owns all three local refresh routes");
        require(countCalls(dispatcher, REACH, "nextStepForUiMarketMutation",
                        "(" + PARAMS + MARKET
                                + "[Ljava/lang/String;ILjava/lang/String;)V") == 1,
                "explicit mutation route lost its targeted commodity refresh");
        require(countCalls(dispatcher,
                        "com/fs/starfarer/campaign/econ/Economy",
                        "nextStep", null) == 0,
                "explicit UI dispatcher unexpectedly owns a global fallback");
    }

    private static void verifyNoForkOwnedReadOnlyUiOverrides(Path jarPath)
            throws Exception {
        Set<String> vanillaTargets = Set.of(
                ReadOnlyUiEconomyStepTransformer.COMMAND_TAB,
                ReadOnlyUiEconomyStepTransformer.COMMODITY_DETAIL_V2,
                ReadOnlyUiEconomyStepTransformer.COMMODITY_DETAIL_LEGACY,
                ReadOnlyUiEconomyStepTransformer.MARKET_CMD);
        Map<String, String> superByClass = new HashMap<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                try (InputStream input = jar.getInputStream(entry)) {
                    ClassReader reader = new ClassReader(input);
                    superByClass.put(reader.getClassName(), reader.getSuperName());
                }
            }
        }
        for (String owner : superByClass.keySet()) {
            String current = superByClass.get(owner);
            int remaining = superByClass.size() + 1;
            while (current != null && remaining-- > 0) {
                require(!vanillaTargets.contains(current),
                        "owned AoTD fork subclasses read-only UI target: "
                                + owner + " -> " + current);
                current = superByClass.get(current);
            }
            require(remaining > 0, "cyclic AoTD class hierarchy near " + owner);
        }
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

    private static void verifyEconomyRouting(ClassNode economy) {
        MethodNode next = requireMethod(economy, "nextStep", "(" + PARAMS + ")V");
        verifyNoImplicitUiRouting(next, "AoTDEconomy.nextStep");
        require(countCalls(next, ECONOMY, "runGlobalEconomyStep",
                        "(" + PARAMS + "Ljava/lang/String;)V") == 1,
                "AoTDEconomy.nextStep no longer delegates exactly once to the global path");

        MethodNode triple = requireMethod(economy, "tripleStep", "()V");
        verifyNoImplicitUiRouting(triple, "AoTDEconomy.tripleStep");
        require(countCalls(triple, ECONOMY, "runGlobalEconomyStep",
                        "(" + PARAMS + "Ljava/lang/String;)V") == 3,
                "AoTDEconomy.tripleStep lost vanilla three-step global multiplicity");

        MethodNode doubleStep = requireMethod(economy, "doubleStep", "()V");
        verifyNoImplicitUiRouting(doubleStep, "AoTDEconomy.doubleStep");
        require(countCalls(doubleStep, ECONOMY, "runGlobalEconomyStep",
                        "(" + PARAMS + "Ljava/lang/String;)V") == 2,
                "AoTDEconomy.doubleStep lost vanilla two-step global multiplicity");

        MethodNode global = requireMethod(economy, "runGlobalEconomyStep",
                "(" + PARAMS + "Ljava/lang/String;)V");
        verifyNoImplicitUiRouting(global, "AoTDEconomy.runGlobalEconomyStep");
        require(countCalls(global, "com/fs/starfarer/campaign/econ/Economy",
                        "nextStep", "(" + PARAMS + ")V") == 1,
                "AoTDEconomy global path no longer invokes the original global step once");
    }

    private static void verifySingleMarketPipeline(ClassNode reach) {
        MethodNode next = requireMethod(reach, "nextStep", "(" + PARAMS + ")V");
        verifyNoImplicitUiRouting(next, "AoTDReachEconomy.nextStep");
        require(countCalls(next, REACH, "nextStepGlobally",
                        "(" + PARAMS + ")V") == 1,
                "AoTDReachEconomy.nextStep no longer delegates exactly once to its global path");

        MethodNode global = requireMethod(
                reach, "nextStepGlobally", "(" + PARAMS + ")V");
        require((global.access & Opcodes.ACC_PRIVATE) != 0,
                "AoTDReachEconomy global helper is externally callable");
        verifyNoImplicitUiRouting(global, "AoTDReachEconomy.nextStepGlobally");
        require(countCalls(global, REACH, "runMainTask",
                        "(Ljava/util/List;" + PARAMS + MARKET + ")V") == 1,
                "AoTDReachEconomy global path lost its all-market main task");

        String desc = "(" + PARAMS + MARKET + "Ljava/lang/String;)V";
        MethodNode ui = requireMethod(reach, "nextStepForUiMarket", desc);
        require((ui.access & Opcodes.ACC_PUBLIC) == 0,
                "AoTDReachEconomy local UI helper is public");

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

        MethodNode mutation = requireMethod(reach, "nextStepForUiMarketMutation",
                "(" + PARAMS + MARKET + "[Ljava/lang/String;ILjava/lang/String;)V");
        require((mutation.access & Opcodes.ACC_PUBLIC) == 0,
                "AoTDReachEconomy mutation UI helper is public");
        require(countCalls(mutation, REACH, "runMainTask",
                        "(Ljava/util/List;" + PARAMS + MARKET + "Z)V") == 2,
                "mutation path must have initial and conditional local main tasks");
        require(countCalls(mutation, REACH, "rebuildAffectedCommodityData",
                        "([Ljava/lang/String;)V") == 1,
                "mutation path must rebuild the affected commodity set once");
        require(countCalls(mutation, REACH, "notifyAffectedCommodityListeners",
                        "([Ljava/lang/String;)V") == 1,
                "mutation path must publish affected commodity callbacks once");
        int finalMainTask = lastInstructionIndex(mutation, REACH, "runMainTask",
                "(Ljava/util/List;" + PARAMS + MARKET + "Z)V");
        int rebuild = instructionIndex(mutation, REACH,
                "rebuildAffectedCommodityData", "([Ljava/lang/String;)V");
        int affectedCallbacks = instructionIndex(mutation, REACH,
                "notifyAffectedCommodityListeners", "([Ljava/lang/String;)V");
        require(finalMainTask >= 0 && rebuild > finalMainTask,
                "affected commodity rebuild runs before the final local main task");
        require(affectedCallbacks > rebuild,
                "affected commodity callbacks run before the rebuild");
    }

    private static void verifyNoImplicitUiRouting(MethodNode method, String label) {
        require(countCallsNamed(method, "getCurrentlyOpenMarket") == 0,
                label + " still infers UI intent from currentlyOpenMarket");
        require(countCallsWithNamePrefix(method, "consume") == 0,
                label + " still consumes an implicit UI context");
        require(countCallsNamed(method,
                        "runUiMarketRefresh",
                        "nextStepForUiMarket",
                        "nextStepForUiMarketMutation",
                        "dispatchPrepatcherUiEconomyStep") == 0,
                label + " still routes a standard step into UI-local work");
    }

    private static void verifyNoGlobalCommodityBuildInUiMode(ClassNode main) {
        FieldNode local = requireField(main, "uiLocalMode");
        require("Z".equals(local.desc) && (local.access & Opcodes.ACC_STATIC) == 0,
                "AoTdMainWorkTask2.uiLocalMode contract changed");

        MethodNode localCtor = requireMethod(main, "<init>",
                "(Ljava/util/List;Lcom/fs/starfarer/campaign/econ/reach/ReachEconomy;"
                        + PARAMS + MARKET + ")V");
        String extendedCtorDesc = "(Ljava/util/List;"
                + "Lcom/fs/starfarer/campaign/econ/reach/ReachEconomy;"
                + PARAMS + MARKET + "Z)V";
        require(countConstructors(localCtor, MAIN, extendedCtorDesc) == 1,
                "single-market constructor no longer delegates to the listener-aware form");
        MethodNode extendedCtor = requireMethod(main, "<init>", extendedCtorDesc);
        require(countFieldWrites(extendedCtor, MAIN, "uiLocalMode", "Z") == 1,
                "listener-aware constructor no longer enables UI-local mode");
        FieldNode notify = requireField(main, "notifyCommodityListeners");
        require("Ljava/lang/Boolean;".equals(notify.desc)
                        && (notify.access & Opcodes.ACC_STATIC) == 0,
                "listener suppression state is not boxed owner-local state");
        require(countFieldWrites(extendedCtor, MAIN,
                        "notifyCommodityListeners", "Ljava/lang/Boolean;") == 2,
                "listener-aware constructor does not capture callback policy");
        MethodNode batches = requireMethod(main, "doMultithreadedNextBatch", "()V");
        require(countCalls(batches, MAIN, "notifyCommoditiesUpdated",
                        "(Ljava/util/Collection;)V") == 1,
                "main task commodity callback boundary changed");

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

    private static int instructionIndex(
            MethodNode method, String owner, String name, String desc) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && desc.equals(call.desc)) return index;
            index++;
        }
        return -1;
    }

    private static int lastInstructionIndex(
            MethodNode method, String owner, String name, String desc) {
        int index = 0;
        int result = -1;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && desc.equals(call.desc)) result = index;
            index++;
        }
        return result;
    }

    private static int countCallsNamed(MethodNode method, String... names) {
        Set<String> accepted = Set.of(names);
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && accepted.contains(call.name)) count++;
        }
        return count;
    }

    private static int countCallsWithNamePrefix(MethodNode method, String prefix) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.name.startsWith(prefix)) count++;
        }
        return count;
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

    private static boolean hasField(ClassNode node, String name) {
        for (FieldNode field : node.fields) if (name.equals(field.name)) return true;
        return false;
    }

    private static boolean hasMethod(ClassNode node, String name) {
        for (MethodNode method : node.methods) if (name.equals(method.name)) return true;
        return false;
    }

    private static void requireConstant(
            ClassNode node, String name, String desc, Object value) {
        FieldNode field = requireField(node, name);
        require(desc.equals(field.desc),
                "Constant descriptor changed: " + node.name + '.' + name
                        + " expected=" + desc + " actual=" + field.desc);
        require(value.equals(field.value),
                "Constant value changed: " + node.name + '.' + name
                        + " expected=" + value + " actual=" + field.value);
        require((field.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC
                        | Opcodes.ACC_FINAL))
                        == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC
                        | Opcodes.ACC_FINAL),
                "Contract constant is not public static final: "
                        + node.name + '.' + name);
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
