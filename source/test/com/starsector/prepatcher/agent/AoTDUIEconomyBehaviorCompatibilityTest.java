package com.starsector.prepatcher.agent;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.FieldInsnNode;
import jdk.internal.org.objectweb.asm.tree.FieldNode;
import jdk.internal.org.objectweb.asm.tree.InvokeDynamicInsnNode;
import jdk.internal.org.objectweb.asm.tree.IntInsnNode;
import jdk.internal.org.objectweb.asm.tree.JumpInsnNode;
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
    private static final String MAIN_RESTART_MODE = MAIN + "$RuntimeRestartMode";
    private static final String MAIN_RESTART_PROGRESS = MAIN + "$RuntimeRestartProgress";
    private static final String COORDINATOR =
            ROOT + "scripts/economy/AoTDUIEconomyRefreshCoordinator";
    private static final String BASELINE =
            ROOT + "scripts/economy/AoTDEconomySemanticBaseline";
    private static final String BASELINE_SCOPE = BASELINE + "$Scope";
    private static final String UPDATE =
            ROOT + "scripts/economy/AoTDUpdateMarketAgainTask";
    private static final String INDUSTRY_DATA =
            ROOT + "scripts/economy/AoTDIndustryData";
    private static final String REACH_STEPPER =
            ROOT + "scripts/economy/AoTDEconomyReachStepper";
    private static final String RUNTIME_TASK_RESTART_REPORT =
            REACH_STEPPER + "$RuntimeTaskRestartReport";
    private static final String PLUGIN =
            ROOT + "plugins/AoTDToolboxTheoryPlugin";
    private static final String COMMODITY_MARKET_DATA =
            ROOT + "scripts/commoditydata/AoTDCommodityMarketData";
    private static final String SUPPLY_DEMAND_DATA =
            ROOT + "scripts/commoditydata/AoTDSupplyDemandData";
    private static final String TRADE_MANAGER =
            ROOT + "scripts/trade/manager/AoTDTradeManager";
    private static final String PREPARED_SNAPSHOT =
            TRADE_MANAGER + "$PreparedSnapshot";
    private static final String MARKET_DATA =
            ROOT + "scripts/trade/models/AoTDMarketData";
    private static final String POST_IMMIGRATION_CAPTURE =
            MARKET_DATA + "$PostImmigrationCapture";
    private static final String POST_IMMIGRATION_FALLBACK_REASON =
            MARKET_DATA + "$PostImmigrationFallbackReason";
    private static final String MARKET_REGISTRY = ROOT + "compat/MarketRegistry";
    private static final String MARKET_ECONOMY_STATE = ROOT + "compat/MarketEconomyState";
    private static final String TRADE_CAPTURE_PROOF =
            MARKET_REGISTRY + "$TradeCaptureProof";
    private static final String MARKET_REGISTRY_COMMIT_STATUS =
            MARKET_REGISTRY + "$CommitStatus";
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
        ClassNode mainRestartMode = read(jar, MAIN_RESTART_MODE);
        ClassNode mainRestartProgress = read(jar, MAIN_RESTART_PROGRESS);
        ClassNode coordinator = read(jar, COORDINATOR);
        ClassNode baseline = read(jar, BASELINE);
        ClassNode baselineScope = read(jar, BASELINE_SCOPE);
        ClassNode update = read(jar, UPDATE);
        ClassNode industryData = read(jar, INDUSTRY_DATA);
        ClassNode reachStepper = read(jar, REACH_STEPPER);
        ClassNode runtimeTaskRestartReport = read(jar, RUNTIME_TASK_RESTART_REPORT);
        ClassNode plugin = read(jar, PLUGIN);
        ClassNode commodityMarketData = read(jar, COMMODITY_MARKET_DATA);
        ClassNode supplyDemandData = read(jar, SUPPLY_DEMAND_DATA);
        ClassNode tradeManager = read(jar, TRADE_MANAGER);
        ClassNode preparedSnapshot = read(jar, PREPARED_SNAPSHOT);
        ClassNode marketData = read(jar, MARKET_DATA);
        ClassNode postImmigrationCapture = read(jar, POST_IMMIGRATION_CAPTURE);
        ClassNode postImmigrationFallbackReason = read(jar, POST_IMMIGRATION_FALLBACK_REASON);
        ClassNode marketRegistry = read(jar, MARKET_REGISTRY);
        ClassNode marketEconomyState = read(jar, MARKET_ECONOMY_STATE);
        ClassNode tradeCaptureProof = read(jar, TRADE_CAPTURE_PROOF);
        ClassNode marketRegistryCommitStatus = read(jar, MARKET_REGISTRY_COMMIT_STATUS);
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
        verifyRuntimeTaskSaveLoadSanitization(
                plugin,
                reachStepper,
                runtimeTaskRestartReport,
                main,
                mainRestartMode,
                mainRestartProgress,
                post,
                finish);
        verifyPostImmigrationCommittedFastPath(
                supplyDemandData,
                marketData,
                postImmigrationCapture,
                postImmigrationFallbackReason,
                tradeManager,
                preparedSnapshot,
                marketRegistry,
                marketEconomyState,
                tradeCaptureProof,
                marketRegistryCommitStatus,
                post,
                main,
                update);
        verifyMonthEndMaterializedInvalidation(industryData);
        verifyEconomyRouting(economy);
        verifySingleMarketPipeline(reach);
        verifyNoGlobalCommodityBuildInUiMode(main);
        verifyListenerOnlyBoundary(finish);
        verifySubsetRegistryAudit(post);

        System.out.println("OK aotd-ui-economy-behavior"
                + " spp11-explicit-dispatch standard-steps-global"
                + " owner-local-transient-revision-gate"
                + " post-commit-no-throw baseline-fail-open"
                + " diagnostic-arguments-no-throw"
                + " transient-linear-supply-demand-snapshot"
                 + " runtime-task-save-detach/load-restart"
                 + " materialized-generation-proven-post-immigration-fast-path/repair"
                 + " stale-proof-atomic-publish/failure-containment month-end-materialized-dirty"
                + " single-market-main/update/immigration/snapshot"
                + " no-ui-global-commodity-build no-ui-global-trade-cut"
                + " explicit-cargo-skip ui-market-mutation-refresh"
                + " read-only-ui-call-sites-vanilla-owned"
                + " listener-boundary subset-registry-audit");
    }

    private static void verifyMonthEndMaterializedInvalidation(ClassNode industryData) {
        MethodNode method = requireMethod(
                industryData, "applyEndOfMonthChange", "(" + MARKET + ")V");
        int changedProof = instructionIndex(
                method, "java/util/LinkedHashMap", "equals", "(Ljava/lang/Object;)Z");
        int dirty = instructionIndex(
                method, MARKET_REGISTRY, "markDirty", "(Ljava/lang/Object;II)V");
        int materializedMonthEndMask = (1 << 3) | (1 << 5) | (1 << 6) | (1 << 7);
        require(changedProof >= 0
                        && dirty > changedProof
                        && countCalls(
                                method,
                                MARKET_REGISTRY,
                                "markDirty",
                                "(Ljava/lang/Object;II)V") == 1
                        && countIntConstant(method, materializedMonthEndMask) == 1,
                "month-end desired-state mutation lost its exact VALUE/DERIVED/PRICE/STOCK invalidation");
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

    private static void verifyRuntimeTaskSaveLoadSanitization(
            ClassNode plugin,
            ClassNode reachStepper,
            ClassNode restartReportNode,
            ClassNode main,
            ClassNode mainRestartMode,
            ClassNode mainRestartProgress,
            ClassNode post,
            ClassNode finish) {
        String saveReport = "L" + REACH_STEPPER + "$RuntimeTaskSaveReport;";
        String restartReport = "L" + REACH_STEPPER + "$RuntimeTaskRestartReport;";
        String epochSnapshot =
                "L" + ROOT + "scripts/economy/AoTDRuntimeEpoch$EpochSnapshot;";
        String workerManager = ROOT + "scripts/economy/AoTDWorkerManager";
        String restoreCoordinator =
                ROOT + "scripts/economy/AoTDEconomyRestoreCoordinator";

        for (String fieldName : new String[] {
                "runtimeTaskLoadGuard",
                "suspendedRuntimeTasks",
                "runtimeTaskSaveSuspensionDepth"}) {
            FieldNode field = requireField(reachStepper, fieldName);
            require((field.access & Opcodes.ACC_TRANSIENT) != 0,
                    "process-local stepper field became serialized: " + fieldName);
        }
        for (String fieldName : new String[] {
                "runtimeRestartPhase",
                "runtimeRestartMarketIds",
                "runtimeRestartTaskCount",
                "runtimeRestartHeadTask",
                "runtimeRestartMarketProgressKnown",
                "runtimeRestartUnknownStage",
                "runtimeRestartStageStarted",
                "runtimeRestartMainMode"}) {
            FieldNode field = requireField(reachStepper, fieldName);
            require((field.access & Opcodes.ACC_TRANSIENT) == 0,
                    "semantic runtime-task checkpoint became transient: " + fieldName);
        }
        require(("L" + MAIN_RESTART_MODE + ";").equals(
                        requireField(reachStepper, "runtimeRestartMainMode").desc),
                "stepper MAIN checkpoint lost its exact semantic mode");
        FieldNode checkpointMarker = requireField(main, "runtimeMainCheckpointV1");
        FieldNode resumeMode = requireField(main, "runtimeResumeMode");
        FieldNode globalDataMarkets = requireField(main, "runtimeGlobalDataMarkets");
        require("Z".equals(checkpointMarker.desc)
                        && (checkpointMarker.access & Opcodes.ACC_TRANSIENT) == 0
                        && ("L" + MAIN_RESTART_MODE + ";").equals(resumeMode.desc)
                        && (resumeMode.access & Opcodes.ACC_TRANSIENT) == 0
                        && "Ljava/util/List;".equals(globalDataMarkets.desc)
                        && (globalDataMarkets.access & Opcodes.ACC_TRANSIENT) != 0,
                "MAIN semantic checkpoint mixed persistent mode with process-local market scope");
        for (String mode : new String[] {
                "FULL", "PRICE_REMAINING", "LISTENERS_ONLY", "DROP"
        }) {
            requireField(mainRestartMode, mode);
        }
        requireMethod(main, "runtimeRestartProgress", "()L" + MAIN_RESTART_PROGRESS + ";");
        String reachEconomy = "Lcom/fs/starfarer/campaign/econ/reach/ReachEconomy;";
        String workParams =
                "Lcom/fs/starfarer/campaign/econ/reach/MainWorkTask$EconWorkParams;";
        MethodNode priceRemainingFactory = requireMethod(
                main,
                "forRuntimePriceRemaining",
                "(Ljava/util/List;Ljava/util/List;" + reachEconomy + workParams + ")L" + MAIN + ";");
        MethodNode listenersOnlyFactory = requireMethod(
                main,
                "forRuntimeListenersOnly",
                "(Ljava/util/List;" + reachEconomy + workParams + ")L" + MAIN + ";");
        require(countFieldWrites(
                        priceRemainingFactory,
                        main.name,
                        "runtimeGlobalDataMarkets",
                        "Ljava/util/List;") == 1
                        && countFieldWrites(
                                listenersOnlyFactory,
                                main.name,
                                "runtimeGlobalDataMarkets",
                                "Ljava/util/List;") == 1
                        && countFieldWrites(
                                priceRemainingFactory,
                                main.name,
                                "mtDataCreated",
                                "Z") == 0
                        && countFieldWrites(
                                listenersOnlyFactory,
                                main.name,
                                "mtDataCreated",
                                "Z") == 0,
                "MAIN suffix factory no longer rebuilds transient global data from the full scope");
        require("L".concat(MAIN_RESTART_MODE).concat(";").equals(
                        requireField(mainRestartProgress, "mode").desc)
                        && "Ljava/util/List;".equals(
                                requireField(mainRestartProgress, "markets").desc)
                        && "Z".equals(requireField(mainRestartProgress, "known").desc),
                "MAIN restart progress lost mode/exact-market/known proof state");
        MethodNode createTasks = requireMethod(reachStepper, "createTasks", "()V");
        require(countCalls(
                        createTasks,
                        main.name,
                        "forRuntimePriceRemaining",
                        priceRemainingFactory.desc) == 1
                        && countCalls(
                                createTasks,
                                main.name,
                                "forRuntimeListenersOnly",
                                listenersOnlyFactory.desc) == 1,
                "lazy task creation lost an exact MAIN price/listener semantic suffix");

        MethodNode suspend = requireMethod(
                reachStepper, "suspendRuntimeTasksForSave", "()" + saveReport);
        require(countCalls(suspend, REACH_STEPPER,
                        "captureRuntimeRestartCheckpoint", "(Ljava/util/List;)V") == 1,
                "save suspension no longer captures one semantic task checkpoint");
        require(countInstanceFieldWritesNamed(suspend, "tasks") == 1
                        && countInstanceFieldWritesNamed(suspend, "suspendedRuntimeTasks") == 1,
                "save suspension no longer detaches the inherited task graph exactly once");

        MethodNode resume = requireMethod(
                reachStepper, "resumeRuntimeTasksAfterSave", "()" + saveReport);
        require(countInstanceFieldWritesNamed(resume, "tasks") == 1
                        && countInstanceFieldWritesNamed(resume, "suspendedRuntimeTasks") == 1,
                "save resume no longer discards the detached task graph exactly once");
        require(countCalls(resume, REACH_STEPPER,
                        "clearRuntimeRestartCheckpoint", "()V") == 1,
                "non-iteration save resume retained an unnecessary semantic checkpoint");
        require(countCalls(
                        resume,
                        REACH_STEPPER,
                        "discardSuspendedRuntimeResources",
                        "(Ljava/util/List;)V") == 1
                        && countCalls(
                                resume,
                                REACH_STEPPER,
                                "endBaselineForTaskGraphDiscard",
                                "(Ljava/lang/String;)V") == 2,
                "same-process save can reuse restore-invalidated task inputs or leak its baseline");

        MethodNode discardSuspended = requireMethod(
                reachStepper,
                "discardSuspendedRuntimeResources",
                "(Ljava/util/List;)V");
        int finishCleanup = instructionIndex(
                discardSuspended, finish.name, "discardRuntimeStateAfterSave", "()V");
        int postCleanup = instructionIndex(
                discardSuspended, post.name, "discardRuntimeStateAfterSave", "()V");
        int mainCleanup = instructionIndex(
                discardSuspended,
                main.name,
                "discardUnattemptedRuntimeWorkAfterSave",
                "()V");
        require(finishCleanup >= 0
                        && postCleanup > finishCleanup
                        && mainCleanup > postCleanup,
                "semantic save cleanup lost Finish/Post/Main resource release coverage/order");

        MethodNode mainDiscard = requireMethod(
                main, "discardUnattemptedRuntimeWorkAfterSave", "()V");
        require(countCalls(
                        mainDiscard,
                        "java/util/concurrent/Future",
                        "cancel",
                        "(Z)Z") == 1
                        && countCalls(
                                mainDiscard,
                                MARKET_REGISTRY,
                                "abandon",
                                "(L" + MARKET_REGISTRY + "$WorkTicket;Z)V") == 1,
                "MAIN semantic save cleanup no longer cancels workers and restores ticket debt");
        MethodNode postDiscard = requireMethod(post, "discardRuntimeStateAfterSave", "()V");
        require(countCalls(
                        postDiscard,
                        "java/util/ArrayList",
                        "clear",
                        "()V") == 1
                        && countFieldWrites(postDiscard, post.name, "done", "Z") == 1,
                "POST semantic save cleanup can retain a partial prepared cut");
        MethodNode finishDiscard = requireMethod(finish, "discardRuntimeStateAfterSave", "()V");
        require(countCalls(
                        finishDiscard,
                        finish.name,
                        "discardRuntimeState",
                        "(Ljava/lang/String;)V") == 1,
                "FINISH semantic save cleanup no longer closes its process-local cut");
        MethodNode finishDiscardState = requireMethod(
                finish, "discardRuntimeState", "(Ljava/lang/String;)V");
        require(countCalls(
                        finishDiscardState,
                        ROOT + "scripts/economy/AoTDGlobalEconomyCoordinator$Boundary",
                        "close",
                        "()V") == 1
                        && countCalls(
                                finishDiscardState,
                                "java/util/concurrent/Future",
                                "cancel",
                                "(Z)Z") == 1
                        && countCalls(
                                finishDiscardState,
                                "java/util/ArrayList",
                                "clear",
                                "()V") == 1
                        && countFieldWrites(
                                finishDiscardState,
                                finish.name,
                                "boundary",
                                "L" + ROOT
                                        + "scripts/economy/AoTDGlobalEconomyCoordinator$Boundary;") == 3
                        && countFieldWrites(
                                finishDiscardState,
                                finish.name,
                                "batch",
                                "L" + ROOT
                                        + "scripts/trade/models/AoTDInternalTradeBatch;") == 1
                        && countFieldWrites(
                                finishDiscardState,
                                finish.name,
                                "done",
                                "Z") == 1,
                "FINISH save cleanup can leave an open global cut or worker graph");

        MethodNode readResolve = requireMethod(
                reachStepper, "readResolve", "()Ljava/lang/Object;");
        require((readResolve.access & Opcodes.ACC_PRIVATE) != 0,
                "XStream readResolve is no longer private owner-local state normalization");
        require(countCalls(readResolve, REACH_STEPPER,
                        "captureRuntimeRestartCheckpoint", "(Ljava/util/List;)V") == 1,
                "legacy task migration no longer captures a semantic suffix checkpoint");
        require(countInstanceFieldWritesNamed(readResolve, "tasks") == 1
                        && countInstanceFieldWritesNamed(readResolve, "runtimeTaskLoadGuard") == 1
                        && countInstanceFieldWritesNamed(
                                readResolve, "suspendedRuntimeTasks") == 1,
                "readResolve no longer drops task/save graphs and enables the load guard");
        require(countCallsWithOwnerPrefix(readResolve, "com/fs/starfarer/api/") == 0,
                "readResolve gained a live Starsector API call");

        MethodNode restart = requireMethod(
                reachStepper,
                "restartRuntimeTasksAfterLoad",
                "(" + epochSnapshot + ")" + restartReport);
        require(countInstanceFieldWritesNamed(restart, "tasks") == 1
                        && countInstanceFieldWritesNamed(restart, "runtimeTaskLoadGuard") == 1,
                "post-bind restart no longer leaves task creation lazy and opens the load guard");
        for (String fieldName : new String[] {
                "discardedTasks",
                "loadGuardWasActive",
                "iterationRestarted",
                "restartPhase",
                "remainingMarketCount",
                "remainingMarketProgressKnown",
                "unknownStage",
                "stageHadStarted",
                "plannedStages",
                "mainRestartMode",
                "campaignEpoch",
                "economyEpoch"}) {
            FieldNode field = requireField(restartReportNode, fieldName);
            require((field.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL))
                            == (Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL),
                    "runtime restart diagnostic is no longer immutable/public: " + fieldName);
        }
        MethodNode nextFrame = requireMethod(reachStepper, "nextFrame", "(F)V");
        require(countInstanceFieldReadsNamed(nextFrame, "runtimeTaskLoadGuard") == 1
                        && countInstanceFieldReadsNamed(
                                nextFrame, "runtimeTaskSaveSuspensionDepth") == 1,
                "nextFrame no longer gates both load migration and save suspension windows");

        MethodNode beforeSave = requireMethod(plugin, "beforeGameSave", "()V");
        int beginBarrier = instructionIndex(
                beforeSave, workerManager, "beginSaveAndWait", "()V");
        int suspendCall = instructionIndex(
                beforeSave, REACH_STEPPER, "suspendRuntimeTasksForSave", "()" + saveReport);
        require(beginBarrier >= 0 && suspendCall > beginBarrier,
                "plugin no longer detaches tasks under the established worker save barrier");

        for (String callback : new String[] {"afterGameSave", "onGameSaveFailed"}) {
            MethodNode method = requireMethod(plugin, callback, "()V");
            require(countCalls(method, REACH_STEPPER,
                            "resumeRuntimeTasksAfterSave", "()" + saveReport) == 2,
                    callback + " lost a normal or exceptional compiled-finally restore path");
            require(countCalls(method, workerManager, "endSave", "()V") == 4,
                    callback + " lost a normal or exceptional compiled-finally barrier release");
            int firstResume = instructionIndex(
                    method, REACH_STEPPER, "resumeRuntimeTasksAfterSave", "()" + saveReport);
            int firstEnd = instructionIndex(method, workerManager, "endSave", "()V");
            int lastResume = lastInstructionIndex(
                    method, REACH_STEPPER, "resumeRuntimeTasksAfterSave", "()" + saveReport);
            int lastEnd = lastInstructionIndex(method, workerManager, "endSave", "()V");
            require(firstResume >= 0 && firstEnd > firstResume
                            && lastResume > firstEnd && lastEnd > lastResume,
                    callback + " releases a save barrier before resolving detached tasks");
        }

        MethodNode onLoad = requireMethod(plugin, "onGameLoad", "(Z)V");
        int beginCampaign = instructionIndex(
                onLoad,
                workerManager,
                "beginCampaign",
                "(Ljava/lang/Object;Ljava/lang/String;)" + epochSnapshot);
        int restartCall = instructionIndex(
                onLoad,
                REACH_STEPPER,
                "restartRuntimeTasksAfterLoad",
                "(" + epochSnapshot + ")" + restartReport);
        int consumeRestore = instructionIndex(
                onLoad,
                restoreCoordinator,
                "consumeOnGameLoad",
                "(L" + ROOT + "scripts/economy/AoTDEconomy;)V");
        require(beginCampaign >= 0 && restartCall > beginCampaign && consumeRestore > restartCall,
                "load task restart no longer occurs after epoch bind and before restore consume");
    }

    private static void verifyPostImmigrationCommittedFastPath(
            ClassNode supplyDemandData,
            ClassNode marketData,
            ClassNode postImmigrationCapture,
            ClassNode postImmigrationFallbackReason,
            ClassNode tradeManager,
            ClassNode preparedSnapshot,
            ClassNode marketRegistry,
            ClassNode marketEconomyState,
            ClassNode tradeCaptureProof,
            ClassNode marketRegistryCommitStatus,
            ClassNode post,
            ClassNode main,
            ClassNode update) {
        String registry = MARKET_REGISTRY;
        String commodity = ROOT + "scripts/commoditydata/AoTDCommodityOnMarket";
        String linkedMap = "Ljava/util/LinkedHashMap;";

        FieldNode materializedInputGeneration =
                requireField(marketEconomyState, "materializedInputGeneration");
        require("J".equals(materializedInputGeneration.desc)
                        && (materializedInputGeneration.access & Opcodes.ACC_STATIC) == 0,
                "market state lost its per-market materialized-input generation");
        requireMethod(marketEconomyState, "getMaterializedInputGeneration", "()J");
        requireMethod(marketEconomyState, "setMaterializedInputGeneration", "(J)V");
        requireMethod(
                marketRegistry,
                "getMarketMaterializedInputGeneration",
                "(Ljava/lang/Object;)J");
        requireMethod(
                marketRegistry,
                "captureTradeInputProof",
                "(Ljava/lang/Object;)L" + TRADE_CAPTURE_PROOF + ";");
        requireMethod(
                marketRegistry,
                "isTradeCaptureProofCurrent",
                "(L" + TRADE_CAPTURE_PROOF + ";)Z");
        requireMethod(
                marketRegistry,
                "publishIfTradeCaptureProofsCurrent",
                "(Ljava/util/List;Ljava/util/function/BooleanSupplier;)Z");

        for (String field : new String[] {
                "marketId", "tradeInputToken", "marketSize", "factionId",
                "accessibilityBits", "hasSpaceport"
        }) {
            FieldNode proofField = requireField(tradeCaptureProof, field);
            require((proofField.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL))
                            == (Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL)
                            && (proofField.access & Opcodes.ACC_STATIC) == 0,
                    "trade capture proof field is not immutable public state: " + field);
        }
        require("J".equals(requireField(tradeCaptureProof, "tradeInputToken").desc)
                        && "I".equals(requireField(tradeCaptureProof, "marketSize").desc)
                        && "I".equals(requireField(tradeCaptureProof, "accessibilityBits").desc)
                        && "Z".equals(requireField(tradeCaptureProof, "hasSpaceport").desc),
                "trade capture proof scalar descriptors changed");
        requireMethod(
                tradeCaptureProof,
                "hasSameInputs",
                "(L" + TRADE_CAPTURE_PROOF + ";)Z");
        requireField(marketRegistryCommitStatus, "STALE_INPUT");

        MethodNode capturedNet = requireMethod(
                supplyDemandData, "getRawNetExportForGeneration", "(J)J");
        require((capturedNet.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNCHRONIZED))
                        == (Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNCHRONIZED),
                "authoritative net capture is not one synchronized read");
        MethodNode commitRefresh = requireMethod(
                supplyDemandData,
                "commitPreparedRefresh",
                "(L" + SUPPLY_DEMAND_DATA + "$PreparedRefresh;)Z");
        require(countLdc(commitRefresh, Long.MIN_VALUE) == 1,
                "generationless commit can retain a stale positive authoritative stamp");
        MethodNode prepareSupplyDemand = requireMethod(
                supplyDemandData,
                "prepareSupplyDemandData",
                "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;Z)L"
                        + SUPPLY_DEMAND_DATA + "$PreparedRefresh;");
        require(countCalls(
                        prepareSupplyDemand,
                        registry,
                        "getMarketMaterializedInputGeneration",
                        "(Ljava/lang/Object;)J") == 1,
                "supply/demand preparation lost its dedicated materialized-input token");
        require(countCalls(
                        prepareSupplyDemand,
                        registry,
                        "getMarketDirtyGeneration",
                        "(Ljava/lang/Object;)J") == 0,
                "supply/demand preparation regressed to the general dirty generation");

        MethodNode committed = requireMethod(
                marketData,
                "preparePostImmigrationCapture",
                "(" + MARKET + ")L" + POST_IMMIGRATION_CAPTURE + ";");
        require(countCalls(committed, registry, "getRegistryLifecycle",
                        "()L" + registry + "$RegistryLifecycle;") == 2,
                "post-immigration fast path lost its before/after READY proof");
        require(countCalls(committed, registry, "getMarketMaterializedInputGeneration",
                        "(Ljava/lang/Object;)J") == 2,
                "post-immigration fast path lost its before/after materialized-generation proof");
        require(countCalls(committed, registry, "getMarketDirtyGeneration",
                        "(Ljava/lang/Object;)J") == 0,
                "post-immigration fast path regressed to the general dirty generation");
        require(countCalls(committed, registry, "matchesMaterializedCheckpoint",
                        "(Ljava/lang/Object;JI)Z") == 1,
                "post-immigration fast path lost its pre-immigration size checkpoint proof");
        require(countCalls(committed,
                        "com/fs/starfarer/api/campaign/econ/MarketAPI",
                        "getSize", "()I") == 2,
                "post-immigration fast path lost its before/after market-size proof");
        require(countCalls(committed, BRIDGE, "hasCapability", "(J)Z") == 2,
                "post-immigration fast path no longer requires both proof capabilities");
        require(countCalls(committed, commodity, "peekSupplyDemandData",
                        "()L" + SUPPLY_DEMAND_DATA + ";") == 1,
                "post-immigration fast path no longer uses the non-creating aggregate lookup");
        require(countCalls(committed, SUPPLY_DEMAND_DATA,
                        "getRawNetExportForGeneration", "(J)J") == 1,
                "post-immigration fast path no longer captures generation and net atomically");
        require(countCalls(committed, SUPPLY_DEMAND_DATA, "getRawNetExport", "()I") == 0,
                "post-immigration fast path separated its generation and net reads");
        require(countCalls(committed, SUPPLY_DEMAND_DATA,
                        "computeRawNetForTradeSnapshot", "(" + MARKET + ")I") == 0,
                "committed fast path performs live industry calculation");

        MethodNode live = requireMethod(
                marketData,
                "captureLiveNetProduction",
                "(" + MARKET + "Ljava/util/List;)" + linkedMap);
        require(countCalls(live, commodity, "getSupplyDemandDataWithoutRefresh",
                        "()L" + SUPPLY_DEMAND_DATA + ";") == 1,
                "live fallback no longer creates a missing holder without refreshing it first");
        require(countCalls(live, commodity, "getSupplyDemandData",
                        "()L" + SUPPLY_DEMAND_DATA + ";") == 0,
                "live fallback can perform an eager holder refresh before its one live scan");
        require(countCalls(live, SUPPLY_DEMAND_DATA,
                        "computeRawNetForTradeSnapshot", "(" + MARKET + ")I") == 1,
                "whole-market live fallback was removed");

        require(countCalls(
                        requireMethod(main, "materializeMarketSupplyDemand",
                                "(Lcom/fs/starfarer/campaign/econ/Market;)Z"),
                        registry, "recordMaterializedCheckpoint",
                        "(Ljava/lang/Object;I)Z") == 1,
                "main market-atomic materialization no longer publishes its size checkpoint");
        require(countCalls(
                        requireMethod(update, "refreshAuthoritativeSupplyDemand",
                                "(" + MARKET + ")Z"),
                        registry, "recordMaterializedCheckpoint",
                        "(Ljava/lang/Object;I)Z") == 1,
                "transition materialization no longer publishes its size checkpoint");

        MethodNode updateProcess = requireMethod(
                update, "processMarket", "(" + MARKET + ")V");
        requireNonReadyRegistryReturnsBeforeUpdateScan(updateProcess, registry);
        int quarantineGate = instructionIndex(
                updateProcess, registry, "isQuarantined", "(Ljava/lang/Object;)Z");
        int materializedGate = instructionIndex(
                updateProcess,
                registry,
                "needsMaterializedReconciliation",
                "(Ljava/lang/Object;)Z");
        int industryScan = instructionIndex(
                updateProcess,
                "com/fs/starfarer/api/campaign/econ/MarketAPI",
                "getIndustries",
                "()Ljava/util/List;");
        require(quarantineGate >= 0 && industryScan > quarantineGate,
                "quarantined update market can reach its live industry scan");
        requireFalseMaterializedGateReturnsBeforeIndustryScan(
                updateProcess, materializedGate, industryScan);
        require(countCalls(
                        updateProcess,
                        update.name,
                        "hasCurrentMaterializedCheckpoint",
                        "(" + MARKET + ")Z") == 1
                        && countCalls(
                                updateProcess,
                                update.name,
                                "refreshAuthoritativeSupplyDemand",
                                "(" + MARKET + ")Z") == 1,
                "READY update lost checkpoint-driven authoritative repair");
        String materializedCommitDesc =
                "(Ljava/lang/Object;JIJ)L" + MARKET_REGISTRY_COMMIT_STATUS + ";";
        require(countCalls(
                        updateProcess,
                        registry,
                        "commitMaterializedStateDetailed",
                        materializedCommitDesc) == 1,
                "update task lost its generation+size-proven materialized commit");
        require(countCalls(
                        updateProcess,
                        registry,
                        "commitMaterializedStateDetailed",
                        "(Ljava/lang/Object;J)L" + MARKET_REGISTRY_COMMIT_STATUS + ";") == 0,
                "update task retained the proof-free materialized commit");
        MethodNode checkpointProof = requireMethod(
                update, "hasCurrentMaterializedCheckpoint", "(" + MARKET + ")Z");
        require(countCalls(
                        checkpointProof,
                        registry,
                        "getMarketMaterializedInputGeneration",
                        "(Ljava/lang/Object;)J") == 1
                        && countCalls(
                                checkpointProof,
                                registry,
                                "matchesMaterializedCheckpoint",
                                "(Ljava/lang/Object;JI)Z") == 1,
                "update checkpoint proof no longer binds generation and exact market size");

        FieldNode usedCommittedNet = requireField(preparedSnapshot, "usedCommittedNet");
        require("Z".equals(usedCommittedNet.desc)
                        && (usedCommittedNet.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL))
                                == (Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL),
                "prepared trade snapshot lost its immutable fast/live-path metric");
        requireImmutablePublicField(
                postImmigrationCapture,
                "fallbackReason",
                "L" + POST_IMMIGRATION_FALLBACK_REASON + ";");
        requireImmutablePublicField(postImmigrationCapture, "requiresMaterializedRefresh", "Z");
        requireImmutablePublicField(
                preparedSnapshot,
                "fallbackReason",
                "L" + POST_IMMIGRATION_FALLBACK_REASON + ";");
        requireImmutablePublicField(preparedSnapshot, "requiresMaterializedRefresh", "Z");
        FieldNode preparedProof = requireField(preparedSnapshot, "tradeCaptureProof");
        require(("L" + TRADE_CAPTURE_PROOF + ";").equals(preparedProof.desc)
                        && (preparedProof.access
                                        & (Opcodes.ACC_PUBLIC
                                                | Opcodes.ACC_FINAL
                                                | Opcodes.ACC_TRANSIENT))
                                == (Opcodes.ACC_PUBLIC
                                        | Opcodes.ACC_FINAL
                                        | Opcodes.ACC_TRANSIENT),
                "prepared trade snapshot lost its transient immutable capture proof");
        require("Z".equals(requireField(
                                postImmigrationFallbackReason,
                                "requiresMaterializedRefresh").desc),
                "fallback reason lost its materialized-repair classification");
        for (String reason : new String[] {
                "NONE",
                "REGISTRY_NOT_READY",
                "CAPABILITY_UNAVAILABLE",
                "GENERATION_UNAVAILABLE",
                "MATERIALIZED_CHECKPOINT_MISMATCH",
                "COMMODITY_STATE_MISSING",
                "COMMODITY_GENERATION_MISMATCH",
                "PROOF_CHANGED_DURING_CAPTURE"
        }) {
            requireField(postImmigrationFallbackReason, reason);
        }
        for (String counter : new String[] {
                "committedNetFastPaths",
                "liveNetFallbacks",
                "deferredRegistryMarkets",
                "materializedRefreshRequired",
                "staleProofRecaptures",
                "staleProofCommitRejections",
                "batchCommitRejections",
                "batchPublicationFailures",
                "registryBookkeepingFailures"
        }) {
            require("I".equals(requireField(post, counter).desc),
                    "post-immigration metric is not an int: " + counter);
        }

        MethodNode registryCommit = requireMethod(post, "commitRegistryState", "()V");
        require(countCalls(registryCommit, registry, "getRegistryLifecycle",
                        "()L" + registry + "$RegistryLifecycle;") == 1,
                "registry bootstrap defer no longer has one exact lifecycle check");
        require(countFieldReads(registryCommit,
                        registry + "$RegistryLifecycle", "EMPTY",
                        "L" + registry + "$RegistryLifecycle;") == 1,
                "registry commit defer is not restricted to the EMPTY lifecycle");
        require(countLdc(registryCommit,
                        "post-immigration.registry-commit-deferred-empty") == 1,
                "registry bootstrap defer metric missing");
        String atomicTradeCommitDesc =
                "(Ljava/lang/Object;L" + TRADE_CAPTURE_PROOF + ";IIIJ)L"
                        + MARKET_REGISTRY_COMMIT_STATUS + ";";
        require(countCalls(
                        registryCommit,
                        registry,
                        "commitTradeSnapshotDetailed",
                        atomicTradeCommitDesc) == 1,
                "non-empty registry commit lost its proof-validated atomic publication");
        require(countCalls(registryCommit, registry, "commitTradeSnapshotDetailed",
                        "(Ljava/lang/Object;J)L" + registry + "$CommitStatus;") == 0,
                "post-immigration task retained the proof-free trade commit");
        int materializedRepair = instructionIndex(
                registryCommit,
                registry,
                "commitTradeSnapshotDetailed",
                atomicTradeCommitDesc);
        int tradeCommit = instructionIndex(
                registryCommit,
                registry,
                "commitTradeSnapshotDetailed",
                atomicTradeCommitDesc);
        require(materializedRepair >= 0 && materializedRepair == tradeCommit,
                "materialized repair and trade-vector commit are no longer one registry call");
        require(countFieldReads(
                        registryCommit,
                        MARKET_REGISTRY_COMMIT_STATUS,
                        "STALE_INPUT",
                        "L" + MARKET_REGISTRY_COMMIT_STATUS + ";") == 1,
                "residual stale-input rejection is not handled separately from other failures");

        MethodNode prepareTradeSnapshot = requireMethod(
                tradeManager,
                "preparePostImmigrationSnapshot",
                "(" + MARKET + ")L" + PREPARED_SNAPSHOT + ";");
        require(countCalls(
                        prepareTradeSnapshot,
                        registry,
                        "captureTradeInputProof",
                        "(Ljava/lang/Object;)L" + TRADE_CAPTURE_PROOF + ";") == 2,
                "trade snapshot is not enclosed by before/after scalar+token proofs");
        require(countCalls(
                        prepareTradeSnapshot,
                        TRADE_CAPTURE_PROOF,
                        "hasSameInputs",
                        "(L" + TRADE_CAPTURE_PROOF + ";)Z") == 1,
                "trade snapshot lost its exact before/after proof comparison");

        MethodNode publishBatch = requireMethod(
                tradeManager,
                "commitPreparedSnapshots",
                "(Ljava/util/List;)Z");
        require(countCalls(
                        publishBatch,
                        registry,
                        "publishIfTradeCaptureProofsCurrent",
                        "(Ljava/util/List;Ljava/util/function/BooleanSupplier;)Z") == 1,
                "trade-manager publication is not registry-locked behind the full proof set");
        MethodNode publishCallback = requireMethod(
                tradeManager, "lambda$commitPreparedSnapshots$0", "(Ljava/util/List;)Z");
        MethodNode publishLocked = requireMethod(
                tradeManager, "publishPreparedSnapshotsLocked", "(Ljava/util/List;)Z");
        require(countOpcode(publishCallback, Opcodes.MONITORENTER) == 1
                        && countCalls(
                                publishCallback,
                                tradeManager.name,
                                "publishPreparedSnapshotsLocked",
                                "(Ljava/util/List;)Z") == 1
                        && countCallsOwnedBy(publishCallback, registry) == 0
                        && countCallsOwnedBy(publishLocked, registry) == 0,
                "trade publication no longer follows registry-lock -> manager-lock order");

        MethodNode doNextBatch = requireMethod(post, "doNextBatch", "()V");
        int recapture = instructionIndex(
                doNextBatch,
                post.name,
                "recaptureStalePreparedSnapshotsOnce",
                "()V");
        int diagnostics = instructionIndex(
                doNextBatch,
                post.name,
                "recomputePreparedDiagnostics",
                "()V");
        int publish = instructionIndex(
                doNextBatch,
                TRADE_MANAGER,
                "commitPreparedSnapshots",
                "(Ljava/util/List;)Z");
        require(recapture >= 0 && recapture < diagnostics && diagnostics < publish,
                "multi-frame post task does not rebuild final diagnostics after stale recapture");
        requireMethod(post, "recomputePreparedDiagnostics", "()V");
        requirePostPublicationFailureContainment(post, doNextBatch);
        MethodNode conservativeRequeue = requireMethod(
                post, "conservativelyRequeueUnfinishedRegistryState", "()V");
        require(countCalls(
                        conservativeRequeue,
                        registry,
                        "markDirty",
                        "(Ljava/lang/Object;II)V") == 1
                        && countCalls(
                                doNextBatch,
                                post.name,
                                "conservativelyRequeueUnfinishedRegistryState",
                                "()V") >= 1,
                "post publication bookkeeping failure no longer conservatively retains repair work");
        MethodNode recaptureMethod = requireMethod(
                post, "recaptureStalePreparedSnapshotsOnce", "()V");
        require(countCalls(
                        recaptureMethod,
                        TRADE_MANAGER,
                        "isPreparedSnapshotProofCurrent",
                        "(L" + PREPARED_SNAPSHOT + ";)Z") == 1
                        && countCalls(
                                recaptureMethod,
                                TRADE_MANAGER,
                                "preparePostImmigrationSnapshot",
                                "(" + MARKET + ")L" + PREPARED_SNAPSHOT + ";") == 1,
                "multi-frame post task lost selective stale-proof recapture");

        MethodNode atomicTradeCommit = requireMethod(
                marketRegistry, "commitTradeSnapshotDetailed", atomicTradeCommitDesc);
        int proofValidation = instructionIndex(
                atomicTradeCommit,
                registry,
                "tradeProofMismatchLocked",
                "(L" + TRADE_CAPTURE_PROOF + ";)I");
        int atomicCommit = instructionIndex(
                atomicTradeCommit,
                registry,
                "commitSynchronousMaskLocked",
                "(L" + MARKET_ECONOMY_STATE
                        + ";I" + "L" + registry + "$OutputDomain;J)L"
                        + MARKET_REGISTRY_COMMIT_STATUS + ";");
        require(proofValidation >= 0 && atomicCommit > proofValidation,
                "registry trade publication does not validate the capture proof before commit");
        MethodNode coalesceDirty = requireMethod(
                marketRegistry,
                "coalesceDirtyMaskMetadataLocked",
                "(L" + MARKET_ECONOMY_STATE + ";II)V");
        require(countCalls(
                        atomicTradeCommit,
                        registry,
                        "coalesceDirtyMaskMetadataLocked",
                        "(L" + MARKET_ECONOMY_STATE + ";II)V") >= 2,
                "atomic stale/duplicate repair no longer coalesces existing causal work");
        require(countCalls(
                        coalesceDirty,
                        MARKET_ECONOMY_STATE,
                        "setDirtyMask",
                        "(I)V") == 1
                        && countCalls(
                                coalesceDirty,
                                registry,
                                "advanceDomainRevisionsLocked",
                                null) == 0
                        && countCalls(
                                coalesceDirty,
                                MARKET_ECONOMY_STATE,
                                "setMaterializedInputGeneration",
                                "(J)V") == 0,
                "dirty-work coalescing can lose work or manufacture another materialized token");
        int lastCoalesce = lastInstructionIndex(
                atomicTradeCommit,
                registry,
                "coalesceDirtyMaskMetadataLocked",
                "(L" + MARKET_ECONOMY_STATE + ";II)V");
        require(lastCoalesce >= 0 && lastCoalesce < atomicCommit,
                "atomic repair coalescing moved after the trade-vector commit");
        requireEmptyLifecycleReturnsBeforeRegistryLoop(registryCommit, registry);
    }

    private static void requireFalseMaterializedGateReturnsBeforeIndustryScan(
            MethodNode method, int materializedGate, int industryScan) {
        int skipMetric = ldcIndex(method, "update-market-again.skipped-no-materialized-work");
        int earlyReturn = firstOpcodeBetween(method, Opcodes.RETURN, skipMetric, industryScan);
        require(materializedGate >= 0
                        && skipMetric > materializedGate
                        && earlyReturn > skipMetric
                        && industryScan > earlyReturn,
                "non-materialized debt can reach AoTDUpdateMarketAgainTask's industry traversal");
    }

    private static void requirePostPublicationFailureContainment(
            ClassNode post, MethodNode doNextBatch) {
        MethodInsnNode publish = findCall(
                doNextBatch,
                TRADE_MANAGER,
                "commitPreparedSnapshots",
                "(Ljava/util/List;)Z");
        require(publish != null,
                "post-immigration task lost its manager publication call");
        int publishIndex = indexOf(doNextBatch, publish);
        int commitAttempted = fieldWriteIndex(
                doNextBatch, post.name, "commitAttempted", "Z");
        require(commitAttempted >= 0 && commitAttempted < publishIndex,
                "post-immigration task can publish without recording its one commit attempt");
        require(isCoveredBy(doNextBatch, publish, "java/lang/RuntimeException"),
                "manager publication RuntimeException is no longer contained by the post task");
        require(hasCoveringHandlerFieldWrite(
                        doNextBatch, publish, null, post.name, "done", "Z"),
                "manager publication failure can leave commitAttempted=true with done=false");
    }

    private static void requireEmptyLifecycleReturnsBeforeRegistryLoop(
            MethodNode method, String registry) {
        FieldInsnNode empty = null;
        MethodInsnNode perMarketCommit = null;
        MethodInsnNode firstRegistryMutation = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && (registry + "$RegistryLifecycle").equals(field.owner)
                    && "EMPTY".equals(field.name)) {
                empty = field;
            }
            if (instruction instanceof MethodInsnNode call
                    && registry.equals(call.owner)
                    && "commitTradeSnapshotDetailed".equals(call.name)) {
                perMarketCommit = call;
            }
            if (firstRegistryMutation == null
                    && instruction instanceof MethodInsnNode call
                    && registry.equals(call.owner)
                    && ("markDirtyAndRequestMaterializedRefresh".equals(call.name)
                            || "markDirty".equals(call.name)
                            || "commitTradeSnapshotDetailed".equals(call.name))) {
                firstRegistryMutation = call;
            }
        }
        require(empty != null && perMarketCommit != null && firstRegistryMutation != null,
                "EMPTY defer or per-market registry mutation missing");
        AbstractInsnNode branchNode = nextExecutable(empty);
        require(branchNode instanceof JumpInsnNode
                        && branchNode.getOpcode() == Opcodes.IF_ACMPNE,
                "EMPTY lifecycle check is not an exact inequality guard");
        JumpInsnNode branch = (JumpInsnNode) branchNode;
        int branchIndex = indexOf(method, branch);
        int continueIndex = indexOf(method, branch.label);
        int commitIndex = indexOf(method, perMarketCommit);
        int firstMutationIndex = indexOf(method, firstRegistryMutation);
        int earlyReturn = -1;
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index > branchIndex && index < continueIndex
                    && instruction.getOpcode() == Opcodes.RETURN) {
                earlyReturn = index;
                break;
            }
            index++;
        }
        require(branchIndex < earlyReturn && earlyReturn < continueIndex
                        && continueIndex < firstMutationIndex
                        && firstMutationIndex <= commitIndex,
                "EMPTY registry path can enter the per-market mutation loop");
    }

    private static void requireNonReadyRegistryReturnsBeforeUpdateScan(
            MethodNode method, String registry) {
        FieldInsnNode ready = null;
        MethodInsnNode firstScan = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && (registry + "$RegistryLifecycle").equals(field.owner)
                    && "READY".equals(field.name)) {
                ready = field;
            }
            if (firstScan == null
                    && instruction instanceof MethodInsnNode call
                    && ((ROOT + "scripts/economy/AoTDIndustryData").equals(call.owner)
                            && "getInstance".equals(call.name)
                            || "com/fs/starfarer/api/campaign/econ/MarketAPI".equals(call.owner)
                                    && ("getIndustries".equals(call.name)
                                            || "getAllCommodities".equals(call.name)))) {
                firstScan = call;
            }
        }
        require(ready != null && firstScan != null,
                "update READY gate or first live market scan is missing");
        AbstractInsnNode branchNode = nextExecutable(ready);
        require(branchNode instanceof JumpInsnNode
                        && branchNode.getOpcode() == Opcodes.IF_ACMPEQ,
                "update registry gate is not an exact READY equality guard");
        JumpInsnNode branch = (JumpInsnNode) branchNode;
        int branchIndex = indexOf(method, branch);
        int continueIndex = indexOf(method, branch.label);
        int scanIndex = indexOf(method, firstScan);
        int earlyReturn = -1;
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index > branchIndex && index < continueIndex
                    && instruction.getOpcode() == Opcodes.RETURN) {
                earlyReturn = index;
                break;
            }
            index++;
        }
        require(branchIndex < earlyReturn && earlyReturn < continueIndex
                        && continueIndex < scanIndex,
                "EMPTY/BUILDING update path can enter live market reads");
    }

    private static AbstractInsnNode nextExecutable(AbstractInsnNode instruction) {
        AbstractInsnNode next = instruction.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
    }

    private static void verifyExplicitDispatcherContract(
            ClassNode contract, ClassNode bridge, ClassNode economy) {
        requireConstant(contract, "FORK_VERSION", "Ljava/lang/String;",
                "1.0.14-spp11");
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

    private static boolean isCoveredBy(
            MethodNode method, AbstractInsnNode instruction, String exceptionType) {
        int target = indexOf(method, instruction);
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            if ((exceptionType == null ? block.type == null : exceptionType.equals(block.type))
                    && indexOf(method, block.start) <= target
                    && target < indexOf(method, block.end)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCoveringHandlerFieldWrite(
            MethodNode method,
            AbstractInsnNode instruction,
            String exceptionType,
            String owner,
            String name,
            String desc) {
        int target = indexOf(method, instruction);
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            if (!(exceptionType == null ? block.type == null : exceptionType.equals(block.type))
                    || indexOf(method, block.start) > target
                    || target >= indexOf(method, block.end)) {
                continue;
            }
            for (AbstractInsnNode current = block.handler;
                    current != null;
                    current = current.getNext()) {
                if (current instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.PUTFIELD
                        && owner.equals(field.owner)
                        && name.equals(field.name)
                        && desc.equals(field.desc)) {
                    return true;
                }
                if (current.getOpcode() == Opcodes.ATHROW) break;
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

    private static int countInstanceFieldWritesNamed(MethodNode method, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && name.equals(field.name)) count++;
        }
        return count;
    }

    private static int countInstanceFieldReadsNamed(MethodNode method, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && name.equals(field.name)) count++;
        }
        return count;
    }

    private static int countFieldReads(
            MethodNode method, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
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

    private static MethodInsnNode findCall(
            MethodNode method, String owner, String name, String desc) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && desc.equals(call.desc)) {
                return call;
            }
        }
        return null;
    }

    private static int fieldWriteIndex(
            MethodNode method, String owner, String name, String desc) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && owner.equals(field.owner)
                    && name.equals(field.name)
                    && desc.equals(field.desc)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static int ldcIndex(MethodNode method, Object value) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode ldc && value.equals(ldc.cst)) return index;
            index++;
        }
        return -1;
    }

    private static int countIntConstant(MethodNode method, int value) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            Integer actual = switch (instruction.getOpcode()) {
                case Opcodes.ICONST_M1 -> -1;
                case Opcodes.ICONST_0 -> 0;
                case Opcodes.ICONST_1 -> 1;
                case Opcodes.ICONST_2 -> 2;
                case Opcodes.ICONST_3 -> 3;
                case Opcodes.ICONST_4 -> 4;
                case Opcodes.ICONST_5 -> 5;
                case Opcodes.BIPUSH, Opcodes.SIPUSH -> ((IntInsnNode) instruction).operand;
                default -> instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Integer number
                        ? number
                        : null;
            };
            if (actual != null && actual == value) count++;
        }
        return count;
    }

    private static int countOpcode(MethodNode method, int opcode) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) count++;
        }
        return count;
    }

    private static int firstOpcodeBetween(
            MethodNode method, int opcode, int exclusiveStart, int exclusiveEnd) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index > exclusiveStart && index < exclusiveEnd
                    && instruction.getOpcode() == opcode) {
                return index;
            }
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

    private static int countCallsWithOwnerPrefix(MethodNode method, String ownerPrefix) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.startsWith(ownerPrefix)) count++;
        }
        return count;
    }

    private static FieldNode requireField(ClassNode node, String name) {
        for (FieldNode field : node.fields) if (name.equals(field.name)) return field;
        throw new AssertionError("Missing field " + node.name + '.' + name);
    }

    private static void requireImmutablePublicField(
            ClassNode node, String name, String descriptor) {
        FieldNode field = requireField(node, name);
        require(descriptor.equals(field.desc)
                        && (field.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL))
                                == (Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL)
                        && (field.access & Opcodes.ACC_STATIC) == 0,
                "field is not immutable public instance state: "
                        + node.name + '.' + name + field.desc);
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
