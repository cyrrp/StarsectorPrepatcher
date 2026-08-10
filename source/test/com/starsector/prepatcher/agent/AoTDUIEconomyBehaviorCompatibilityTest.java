package com.starsector.prepatcher.agent;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.FieldInsnNode;
import jdk.internal.org.objectweb.asm.tree.FieldNode;
import jdk.internal.org.objectweb.asm.tree.InvokeDynamicInsnNode;
import jdk.internal.org.objectweb.asm.tree.LdcInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.TryCatchBlockNode;
import jdk.internal.org.objectweb.asm.tree.TypeInsnNode;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
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
    private static final String BASELINE =
            ROOT + "scripts/economy/AoTDEconomySemanticBaseline";
    private static final String BASELINE_SCOPE = BASELINE + "$Scope";
    private static final String UPDATE =
            ROOT + "scripts/economy/AoTDUpdateMarketAgainTask";
    private static final String REACH_STEPPER =
            ROOT + "scripts/economy/AoTDEconomyReachStepper";
    private static final String COMMODITY_MARKET_DATA =
            ROOT + "scripts/commoditydata/AoTDCommodityMarketData";
    private static final String SUPPLY_DEMAND_DATA =
            ROOT + "scripts/commoditydata/AoTDSupplyDemandData";
    private static final String TRADE_MANAGER =
            ROOT + "scripts/trade/manager/AoTDTradeManager";
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
        ClassNode baseline = read(jar, BASELINE);
        ClassNode baselineScope = read(jar, BASELINE_SCOPE);
        ClassNode update = read(jar, UPDATE);
        ClassNode reachStepper = read(jar, REACH_STEPPER);
        ClassNode commodityMarketData = read(jar, COMMODITY_MARKET_DATA);
        ClassNode supplyDemandData = read(jar, SUPPLY_DEMAND_DATA);
        ClassNode tradeManager = read(jar, TRADE_MANAGER);
        ClassNode finish = read(jar, FINISH);
        ClassNode post = read(jar, POST);
        ClassNode contract = read(jar, CONTRACT);
        ClassNode bridge = read(jar, BRIDGE);

        verifyNoForkOwnedReadOnlyUiOverrides(jar);
        verifyExplicitDispatcherContract(contract, bridge, economy);
        verifyOwnerLocalCoordinator(economy, coordinator);
        verifyPostCommitNoThrowBoundaries(economy);
        verifySemanticBaselineFailOpen(baseline, baselineScope);
        verifyNoDiagnosticIndustryMarketReads(update);
        verifyDiagnosticArgumentBoundaries(
                commodityMarketData, reachStepper, finish, main, tradeManager);
        verifyLinearSupplyDemandSnapshot(supplyDemandData);
        verifyEconomyRouting(economy);
        verifySingleMarketPipeline(reach);
        verifyNoGlobalCommodityBuildInUiMode(main);
        verifyListenerOnlyBoundary(finish);
        verifySubsetRegistryAudit(post);

        System.out.println("OK aotd-ui-economy-behavior"
                + " spp10-explicit-dispatch standard-steps-global"
                + " owner-local-transient-revision-gate"
                + " post-commit-no-throw baseline-fail-open"
                + " diagnostic-arguments-no-throw"
                + " transient-linear-supply-demand-snapshot"
                + " single-market-main/update/immigration/snapshot"
                + " no-ui-global-commodity-build no-ui-global-trade-cut"
                + " explicit-cargo-skip ui-market-mutation-refresh"
                + " read-only-ui-call-sites-vanilla-owned"
                + " listener-boundary subset-registry-audit");
    }

    private static void verifyLinearSupplyDemandSnapshot(ClassNode supplyDemandData) {
        FieldNode industries = requireField(supplyDemandData, "stagingIndustries");
        require("Ljava/util/ArrayList;".equals(industries.desc),
                "supply/demand staging lost its ordered ArrayList industry snapshot");
        require((industries.access & Opcodes.ACC_TRANSIENT) != 0,
                "supply/demand staging industry snapshot became serialized state");

        MethodNode prepare = requireMethod(
                supplyDemandData,
                "prepareSupplyDemandData",
                "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;Z)L"
                        + SUPPLY_DEMAND_DATA + "$PreparedRefresh;");
        require(countCalls(
                        prepare,
                        "com/fs/starfarer/api/campaign/econ/MarketAPI",
                        "getIndustries",
                        "()Ljava/util/List;") == 1,
                "supply/demand preparation no longer takes one ordered industry pass");
        require(countCalls(
                        prepare,
                        "com/fs/starfarer/api/campaign/econ/MarketAPI",
                        "getIndustry",
                        "(Ljava/lang/String;)Lcom/fs/starfarer/api/campaign/econ/Industry;") == 0,
                "supply/demand preparation reintroduced repeated market.getIndustry(id) lookups");
        require(countCalls(
                        prepare,
                        "com/fs/starfarer/api/campaign/econ/Industry",
                        "getDemand",
                        "(Ljava/lang/String;)Lcom/fs/starfarer/api/campaign/econ/"
                                + "MutableCommodityQuantity;") == 1,
                "supply/demand preparation lost its single direct demand read site");
        require(countCalls(
                        prepare,
                        "com/fs/starfarer/api/campaign/econ/Industry",
                        "getSupply",
                        "(Ljava/lang/String;)Lcom/fs/starfarer/api/campaign/econ/"
                                + "MutableCommodityQuantity;") == 1,
                "supply/demand preparation lost its single direct supply read site");
    }

    private static void verifyExplicitDispatcherContract(
            ClassNode contract, ClassNode bridge, ClassNode economy) {
        requireConstant(contract, "FORK_VERSION", "Ljava/lang/String;",
                "1.0.14-spp10");
        requireConstant(contract, "PRODUCTION_CAPABILITIES", "J",
                Long.valueOf(0xbffL));
        requireConstant(contract, "DECLARED_CAPABILITIES", "J",
                Long.valueOf(0xfffL));
        requireConstant(contract, "CAPABILITY_UI_ECONOMY_DISPATCH", "J",
                Long.valueOf(1L << 9));
        requireConstant(contract, "CAPABILITY_ECONOMY_RESTORE_COORDINATION", "J",
                Long.valueOf(1L << 11));
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
        requireConstant(bridge, "BRIDGE_SCHEMA", "I", Integer.valueOf(10));
        requireConstant(bridge, "BRIDGE_MARKER", "Ljava/lang/String;",
                "AOTD_SCHEDULER_BRIDGE_V10");
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
        String coordinatorOnlyDesc = "(L" + COORDINATOR + ";)Z";
        require(countCalls(dispatcher, ECONOMY,
                        "recordConditionOnlySkipNoThrow", coordinatorOnlyDesc) == 1,
                "explicit market-open route lost its observable condition-only skip");
        require(countCalls(dispatcher, ECONOMY,
                        "recordSyntheticCargoSkipNoThrow", coordinatorOnlyDesc) == 1,
                "explicit Cargo route lost its observable synthetic skip");
        String runUiDesc = "(" + MARKET + PARAMS
                + "Ljava/lang/String;ZLjava/lang/String;J)Z";
        require(countCalls(dispatcher, ECONOMY, "runUiMarketRefresh",
                        runUiDesc) == 3,
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
        MethodNode completed = requireMethod(
                coordinator, "recordCompleted", "(" + MARKET + ")V");
        require(hasThrowableCatch(completed),
                "coordinator recordCompleted lost its Throwable fail-open boundary");
        requireThrowableCoveredCall(completed,
                "com/fs/starfarer/api/campaign/econ/MarketAPI",
                "getId", "()Ljava/lang/String;");
        requireThrowableCoveredCall(completed,
                ROOT + "compat/MarketRegistry", "needsDerivedRefresh",
                "(Ljava/lang/Object;)Z");
        requireCompletedMarkerPublishedLast(completed);

        MethodNode invalidate = requireMethod(
                coordinator, "invalidate", "(Ljava/lang/String;)V");
        require(hasThrowableCatch(invalidate),
                "coordinator invalidate lost its Throwable fail-open boundary");
        requireThrowableCoveredCall(invalidate, BASELINE, "operation",
                "(Ljava/lang/String;J)V");
    }

    private static void verifyPostCommitNoThrowBoundaries(ClassNode economy) {
        String coordinatorDesc = "L" + COORDINATOR + ";";
        String completedDesc = "(" + coordinatorDesc + MARKET
                + "Ljava/lang/String;J)Z";
        String coordinatorOnlyDesc = "(" + coordinatorDesc + ")Z";
        String runUiDesc = "(" + MARKET + PARAMS
                + "Ljava/lang/String;ZLjava/lang/String;J)Z";

        MethodNode runUi = requireMethod(economy, "runUiMarketRefresh", runUiDesc);
        int semanticRefresh = instructionIndex(runUi, REACH, "nextStepForUiMarket",
                "(" + PARAMS + MARKET + "Ljava/lang/String;)V");
        int safeCompletion = instructionIndex(runUi, ECONOMY,
                "recordUiRefreshCompletedNoThrow", completedDesc);
        require(semanticRefresh >= 0 && safeCompletion > semanticRefresh,
                "UI semantic Reach refresh no longer precedes safe completion publication");
        require(countCallsOwnedBy(runUi, BASELINE) == 0,
                "runUiMarketRefresh invokes baseline directly after commit");
        require(countCalls(runUi, COORDINATOR, "recordCompleted",
                        "(" + MARKET + ")V") == 0,
                "runUiMarketRefresh bypasses the safe completion helper");

        MethodNode dispatcher = requireMethod(economy,
                "dispatchPrepatcherUiEconomyStep",
                "(I" + MARKET + "J[Ljava/lang/String;)Z");
        int semanticMutation = instructionIndex(dispatcher, REACH,
                "nextStepForUiMarketMutation",
                "(" + PARAMS + MARKET
                        + "[Ljava/lang/String;ILjava/lang/String;)V");
        int safeMutationCompletion = instructionIndex(dispatcher, ECONOMY,
                "recordUiRefreshCompletedNoThrow", completedDesc);
        require(semanticMutation >= 0 && safeMutationCompletion > semanticMutation,
                "targeted mutation commit no longer precedes safe completion publication");
        require(countCallsOwnedBy(dispatcher, BASELINE) == 0,
                "explicit dispatcher invokes baseline directly after commit");
        require(countCalls(dispatcher, COORDINATOR, "recordCompleted",
                        "(" + MARKET + ")V") == 0,
                "explicit dispatcher bypasses the safe completion helper");

        MethodNode completed = requireMethod(economy,
                "recordUiRefreshCompletedNoThrow", completedDesc);
        MethodNode coalesced = requireMethod(economy,
                "recordUiRefreshSkipNoThrow", coordinatorOnlyDesc);
        MethodNode conditionOnly = requireMethod(economy,
                "recordConditionOnlySkipNoThrow", coordinatorOnlyDesc);
        MethodNode syntheticCargo = requireMethod(economy,
                "recordSyntheticCargoSkipNoThrow", coordinatorOnlyDesc);
        for (MethodNode helper : new MethodNode[] {
                completed, coalesced, conditionOnly, syntheticCargo}) {
            require(hasThrowableCatch(helper),
                    "safe UI helper lacks catch(Throwable): " + helper.name + helper.desc);
            requireCallsOwnedByThrowableCovered(helper,
                    Set.of(COORDINATOR, BASELINE));
        }
    }

    private static void verifySemanticBaselineFailOpen(
            ClassNode baseline, ClassNode scope) {
        MethodNode clinit = requireMethod(baseline, "<clinit>", "()V");
        require(!reachesCall(baseline, clinit,
                        "com/fs/starfarer/api/Global", "getLogger", new HashSet<>()),
                "semantic baseline eagerly resolves Global.getLogger from <clinit>");
        for (FieldNode field : baseline.fields) {
            require((field.access & Opcodes.ACC_STATIC) == 0
                            || !"Lorg/apache/log4j/Logger;".equals(field.desc),
                    "semantic baseline retained a static Logger field: " + field.name);
        }

        for (String[] guarded : new String[][] {
                {"initialize", "()V"},
                {"isEnabled", "()Z"},
                {"beginEconomyRevision", "(Ljava/lang/String;)J"},
                {"endEconomyRevision", "(JLjava/lang/String;)V"},
                {"operation", "(Ljava/lang/String;" + MARKET + ")V"},
                {"operation", "(Ljava/lang/String;J)V"},
                {"captureTradeSnapshot", "(Ljava/lang/String;" + MARKET + ")V"},
                {"flush", "(Ljava/lang/String;)V"}
        }) {
            MethodNode method = requireMethod(baseline, guarded[0], guarded[1]);
            require(hasThrowableCatch(method),
                    "baseline entrypoint lacks catch(Throwable): "
                            + method.name + method.desc);
        }

        String scopeDesc = "L" + BASELINE_SCOPE + ";";
        String coreBeginDesc = "(Ljava/lang/String;" + MARKET
                + "Ljava/lang/String;Z)" + scopeDesc;
        MethodNode coreBegin = requireMethod(baseline, "begin", coreBeginDesc);
        require(hasThrowableCatch(coreBegin),
                "baseline begin core lost its Throwable fail-open boundary");
        for (String[] delegator : new String[][] {
                {"begin", "(Ljava/lang/String;)" + scopeDesc},
                {"begin", "(Ljava/lang/String;" + MARKET + ")" + scopeDesc},
                {"begin", "(Ljava/lang/String;" + MARKET
                        + "Ljava/lang/String;)" + scopeDesc},
                {"beginMarketMutation", "(Ljava/lang/String;" + MARKET
                        + "Ljava/lang/String;)" + scopeDesc}
        }) {
            MethodNode method = requireMethod(baseline, delegator[0], delegator[1]);
            require(countCalls(method, BASELINE, "begin", coreBeginDesc) == 1,
                    "public baseline scope entrypoint no longer delegates to guarded core: "
                            + method.name + method.desc);
        }

        MethodNode close = requireMethod(scope, "close", "()V");
        require(hasThrowableCatch(close),
                "semantic baseline Scope.close lost catch(Throwable)");
        requireThrowableCoveredCall(close, BASELINE, "finish",
                "(" + scopeDesc + ")V");
    }

    private static void verifyNoDiagnosticIndustryMarketReads(ClassNode update) {
        String industry = "Lcom/fs/starfarer/api/campaign/econ/Industry;";
        String marketGetterOwner = "com/fs/starfarer/api/campaign/econ/Industry";
        String marketGetterDesc = "()" + MARKET;
        String counterDesc = "(Ljava/lang/String;J)V";
        int marketReads = 0;
        for (MethodNode method : update.methods) {
            marketReads += countCalls(
                    method, marketGetterOwner, "getMarket", marketGetterDesc);
        }
        require(marketReads == 0,
                "AoTDUpdateMarketAgainTask retained diagnostic Industry.getMarket reads");
        for (String name : new String[] {
                "applyPendingIndustrySuppression", "restoreIndustry"}) {
            MethodNode method = requireMethod(update, name, "(" + industry + ")V");
            require(countCalls(method, BASELINE, "operation", counterDesc) == 2,
                    "industry apply/unapply diagnostics are not getter-free counters in " + name);
        }
    }

    private static void verifyDiagnosticArgumentBoundaries(
            ClassNode commodityMarketData, ClassNode reachStepper,
            ClassNode finish, ClassNode main, ClassNode tradeManager) {
        String scopeDesc = "L" + BASELINE_SCOPE + ";";
        String beginDesc = "(Ljava/lang/String;" + MARKET
                + "Ljava/lang/String;)" + scopeDesc;

        requireSimpleBaselineArgumentRegion(
                requireMethod(commodityMarketData, "<init>",
                        "(Ljava/lang/String;Ljava/lang/String;)V"),
                "commodity-market-data.constructor", "begin", beginDesc, Set.of());

        requireSimpleBaselineArgumentRegion(
                requireMethod(reachStepper, "performBeforeMonthEnds", "(I)V"),
                "economy.month-end-preparation", "begin", beginDesc, Set.of());
        requireSimpleBaselineArgumentRegion(
                requireMethod(reachStepper, "nextFrame", "(F)V"),
                "iteration-complete", "endEconomyRevision",
                "(JLjava/lang/String;)V", Set.of());
        requireSimpleBaselineArgumentRegion(
                requireMethod(reachStepper, "createTasks", "()V"),
                "reach-stepper-iteration", "beginEconomyRevision",
                "(Ljava/lang/String;)J", Set.of());

        MethodNode openCut = requireMethod(finish, "openCut", "()V");
        require(countLdc(openCut, "internal-trade.factions") == 0,
                "removed internal-trade.factions diagnostic metric returned");
        require(countCalls(openCut,
                        ROOT + "scripts/trade/models/AoTDInternalTradeBatch",
                        "size", "()I") == 0,
                "openCut still reads batch.size solely for diagnostics");

        requireSimpleBaselineArgumentRegion(
                requireMethod(main, "waitForMarketPriceWorkers", "()V"),
                "main-work.wait-for-price-workers", "begin", beginDesc,
                Set.of("size"));
        requireSimpleBaselineArgumentRegion(
                requireMethod(main, "notifyCommoditiesUpdated",
                        "(Ljava/util/Collection;)V"),
                "main-work.notify-all-commodity-listeners", "begin", beginDesc,
                Set.of("size"));
        requireSimpleBaselineArgumentRegion(
                requireMethod(main, "materializeMarketSupplyDemand",
                        "(Lcom/fs/starfarer/campaign/econ/Market;)Z"),
                "supply-demand.market-commodities", "operation",
                "(Ljava/lang/String;J)V", Set.of("size"));

        requireSimpleBaselineArgumentRegion(
                requireMethod(tradeManager, "preparePostImmigrationSnapshot",
                        "(" + MARKET + ")L" + TRADE_MANAGER + "$PreparedSnapshot;"),
                "trade-manager.capture-post-immigration", "begin", beginDesc,
                Set.of("getFactionId"));
        requireSimpleBaselineArgumentRegion(
                requireMethod(tradeManager, "addMarket", "(" + MARKET + ")V"),
                "trade-manager.build-market-snapshot", "begin", beginDesc,
                Set.of("getFactionId"));
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

    private static void requireSimpleBaselineArgumentRegion(
            MethodNode method, String diagnosticKey,
            String baselineMethod, String baselineDesc,
            Set<String> forbiddenCallNames) {
        LdcInsnNode regionStart = null;
        MethodInsnNode regionEnd = null;
        int matches = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof LdcInsnNode constant)
                    || !diagnosticKey.equals(constant.cst)) continue;
            MethodInsnNode nextBaselineCall = null;
            for (AbstractInsnNode cursor = constant.getNext();
                 cursor != null; cursor = cursor.getNext()) {
                if (cursor instanceof MethodInsnNode call
                        && BASELINE.equals(call.owner)) {
                    nextBaselineCall = call;
                    break;
                }
            }
            if (nextBaselineCall == null
                    || !baselineMethod.equals(nextBaselineCall.name)
                    || !baselineDesc.equals(nextBaselineCall.desc)) continue;
            matches++;
            regionStart = constant;
            regionEnd = nextBaselineCall;
        }
        require(matches == 1,
                "expected one exact diagnostic argument region for " + diagnosticKey
                        + " in " + method.name + method.desc + ", found " + matches);

        boolean reachedCall = false;
        for (AbstractInsnNode instruction = regionStart;
             instruction != null; instruction = instruction.getNext()) {
            require(!(instruction instanceof InvokeDynamicInsnNode),
                    "diagnostic argument region uses invokedynamic/string concat for "
                            + diagnosticKey);
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.NEW) {
                require(!"java/lang/StringBuilder".equals(type.desc)
                                && !"java/lang/StringBuffer".equals(type.desc),
                        "diagnostic argument region allocates a string builder for "
                                + diagnosticKey);
            }
            if (instruction instanceof MethodInsnNode call) {
                boolean stringConcat = "java/lang/StringBuilder".equals(call.owner)
                        || "java/lang/StringBuffer".equals(call.owner)
                        || ("java/lang/String".equals(call.owner)
                                && "concat".equals(call.name));
                require(!stringConcat,
                        "diagnostic argument region concatenates a string for "
                                + diagnosticKey);
                require(!forbiddenCallNames.contains(call.name),
                        "diagnostic argument region invokes " + call.owner + '.'
                                + call.name + call.desc + " for " + diagnosticKey);
            }
            if (instruction == regionEnd) {
                reachedCall = true;
                break;
            }
        }
        require(reachedCall,
                "diagnostic argument region does not reach baseline call for "
                        + diagnosticKey);
    }

    private static int countLdc(MethodNode method, Object value) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode constant
                    && value.equals(constant.cst)) count++;
        }
        return count;
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

    private static void requireCompletedMarkerPublishedLast(MethodNode method) {
        Set<String> tokenFields = Set.of(
                "completedCampaignEpoch",
                "completedEconomyEpoch",
                "completedRegistryGeneration",
                "completedDirtyGeneration",
                "completedMarketDirtyGeneration",
                "completedMarketIdentityHash",
                "completedMarketId");
        Set<String> written = new HashSet<>();
        String lastTokenWrite = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && COORDINATOR.equals(field.owner)
                    && tokenFields.contains(field.name)) {
                written.add(field.name);
                lastTokenWrite = field.name;
            }
        }
        require(written.equals(tokenFields),
                "coordinator recordCompleted no longer publishes the complete token: "
                        + written);
        require("completedMarketId".equals(lastTokenWrite),
                "completedMarketId is not the final coalescing-token publication");
    }

    private static boolean hasThrowableCatch(MethodNode method) {
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            if ("java/lang/Throwable".equals(block.type)) return true;
        }
        return false;
    }

    private static void requireThrowableCoveredCall(
            MethodNode method, String owner, String name, String desc) {
        int found = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !owner.equals(call.owner) || !name.equals(call.name)
                    || !desc.equals(call.desc)) continue;
            found++;
            require(isThrowableCovered(method, call),
                    owner + '.' + name + " is outside catch(Throwable) in "
                            + method.name + method.desc);
        }
        require(found > 0, "missing proof call " + owner + '.' + name
                + desc + " in " + method.name + method.desc);
    }

    private static void requireCallsOwnedByThrowableCovered(
            MethodNode method, Set<String> owners) {
        int found = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !owners.contains(call.owner)) continue;
            found++;
            require(isThrowableCovered(method, call),
                    "safe helper call is outside catch(Throwable): "
                            + call.owner + '.' + call.name + call.desc);
        }
        require(found > 0,
                "safe helper lost all coordinator/baseline calls: "
                        + method.name + method.desc);
    }

    private static boolean isThrowableCovered(
            MethodNode method, AbstractInsnNode instruction) {
        int instructionIndex = indexOf(method, instruction);
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            if ("java/lang/Throwable".equals(block.type)
                    && indexOf(method, block.start) <= instructionIndex
                    && instructionIndex < indexOf(method, block.end)) {
                return true;
            }
        }
        return false;
    }

    private static int indexOf(MethodNode method, AbstractInsnNode target) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction == target) return index;
            index++;
        }
        return -1;
    }

    private static boolean reachesCall(
            ClassNode node, MethodNode method,
            String targetOwner, String targetName, Set<String> visited) {
        if (!visited.add(method.name + method.desc)) return false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            if (targetOwner.equals(call.owner) && targetName.equals(call.name)) {
                return true;
            }
            if (!node.name.equals(call.owner)) continue;
            MethodNode local = findMethod(node, call.name, call.desc);
            if (local != null
                    && reachesCall(node, local, targetOwner, targetName, visited)) {
                return true;
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

    private static int countCallsOwnedBy(MethodNode method, String owner) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)) count++;
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
        MethodNode method = findMethod(node, name, desc);
        if (method != null) return method;
        throw new AssertionError("Missing method " + node.name + '.' + name + desc);
    }

    private static MethodNode findMethod(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) return method;
        }
        return null;
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
