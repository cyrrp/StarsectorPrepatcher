package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.campaign.econ.reach.FinishEconomyUpdateTask;
import com.fs.starfarer.campaign.econ.reach.ReachEconomyStepper;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import sun.misc.Unsafe;

/** Actual-fork/XStream gate for save-time task detachment and load-time semantic restart. */
public final class AoTDRuntimeTaskXStreamSanitizationTest {
    private static final String STEPPER_CLASS =
            "data.kaysaar.aotd.tot.scripts.economy.AoTDEconomyReachStepper";
    private static final String EPOCH_SNAPSHOT_CLASS =
            "data.kaysaar.aotd.tot.scripts.economy.AoTDRuntimeEpoch$EpochSnapshot";
    private static final String UPDATE_TASK_CLASS =
            "data.kaysaar.aotd.tot.scripts.economy.AoTDUpdateMarketAgainTask";
    private static final String POST_TASK_CLASS =
            "data.kaysaar.aotd.tot.scripts.economy.AoTDPostImmigrationTradeSnapshotTask";
    private static final String MAIN_TASK_CLASS =
            "com.fs.starfarer.campaign.econ.reach.MainWorkTask2";
    private static final String AOTD_MAIN_TASK_CLASS =
            "data.kaysaar.aotd.tot.scripts.economy.AoTdMainWorkTask2";
    private static final String IMMIGRATION_TASK_CLASS =
            "com.fs.starfarer.campaign.econ.reach.ImmigrationTask";

    private AoTDRuntimeTaskXStreamSanitizationTest() {}

    public static void main(String[] args) throws Exception {
        String xstreamVersion = XStream.class.getPackage().getImplementationVersion();
        require("1.4.10".equals(xstreamVersion),
                "runtime-task gate requires XStream 1.4.10, found " + xstreamVersion);

        installSettingsStub();
        Class<?> stepperType = Class.forName(STEPPER_CLASS);
        XStream xstream = xstream(stepperType.getClassLoader());

        Object live = newStepper(stepperType);
        List<Object> liveTasks = installInProgressFinishPhase(stepperType, live);
        Object suspend = invoke(stepperType, live, "suspendRuntimeTasksForSave");
        require(boolField(suspend, "graphDetached"),
                "beforeGameSave did not detach the runtime task graph");
        require(intField(suspend, "taskCount") == 1
                        && "FINISH".equals(objectField(suspend, "restartPhase")),
                "save checkpoint did not preserve the exact FINISH suffix");
        require(field(stepperType, "tasks").get(live) == null,
                "detached runtime task list remained in the serialized owner field");
        require(field(stepperType, "suspendedRuntimeTasks").get(live) == liveTasks,
                "same-process save suspension did not retain the exact live list transiently");

        String xml = xstream.toXML(live);
        require(!xml.contains("<tasks>") && !xml.contains("<suspendedRuntimeTasks>"),
                "new save serialized a process-local task graph");
        require(xml.contains("<runtimeRestartPhase>FINISH</runtimeRestartPhase>"),
                "new save omitted the semantic restart checkpoint");

        Object resume = invoke(stepperType, live, "resumeRuntimeTasksAfterSave");
        require(!boolField(resume, "graphRestored")
                        && "resume-semantic-restart".equals(objectField(resume, "action"))
                        && "FINISH".equals(objectField(resume, "restartPhase"))
                        && field(stepperType, "tasks").get(live) == null,
                "save resume reused a graph invalidated by Starsector post-save restore");
        require(field(stepperType, "suspendedRuntimeTasks").get(live) == null
                        && field(stepperType, "runtimeRestartPhase").get(live) != null,
                "save resume lost its semantic suffix or retained the detached graph");

        Object loaded = xstream.fromXML(xml);
        require(stepperType.isInstance(loaded), "checkpoint XML did not load as the actual fork");
        assertReadResolveGuard(stepperType, loaded);
        Object restart = restartAfterLoad(stepperType, loaded);
        assertRestartReport(restart, 1, "FINISH", true);
        assertPreservedCadence(stepperType, loaded);
        require(field(stepperType, "tasks").get(loaded) == null
                        && !field(stepperType, "runtimeTaskLoadGuard").getBoolean(loaded),
                "post-bind restart recreated tasks eagerly or left the load guard active");

        // Backward compatibility: spp10 could serialize the inherited task list directly.
        Object legacy = newStepper(stepperType);
        installInProgressFinishPhase(stepperType, legacy);
        String legacyXml = xstream.toXML(legacy);
        require(legacyXml.contains("<tasks>"),
                "legacy fixture did not contain the process-local task graph under test");
        Object migrated = xstream.fromXML(legacyXml);
        assertReadResolveGuard(stepperType, migrated);
        Object migratedRestart = restartAfterLoad(stepperType, migrated);
        assertRestartReport(migratedRestart, 1, "FINISH", true);
        assertPreservedCadence(stepperType, migrated);

        verifyMainRestartModesAndFactories(stepperType, xstream);
        verifySameProcessMainSemanticRestart(stepperType);
        verifyPhaseSuffixesAndStableIdRebind(stepperType, xstream);

        System.out.println("OK actual-fork AoTDEconomyReachStepper XStream " + xstreamVersion
                + " save-detach/resume + spp10-task-migration + spp11-semantic-restart"
                + " + MAIN FULL/PRICE_REMAINING/LISTENERS_ONLY/DROP"
                + " + UPDATE/IMMIGRATION/POST suffixes + stable-ID rebind"
                + " + same-process success/failure/nested/WAITING cleanup");
    }

    private static Object newStepper(Class<?> stepperType) throws Exception {
        // The stock RC8 obfuscated JVM classes contain identifiers rejected by a regular JDK 17
        // verifier. Allocate the isolated serialization owner without running Economy.<clinit>;
        // the test initializes every persistent field it observes explicitly below.
        return unsafe().allocateInstance(stepperType);
    }

    private static List<Object> installInProgressFinishPhase(Class<?> stepperType, Object stepper)
            throws Exception {
        installInProgressCadence(stepperType, stepper);

        List<Object> tasks = new ArrayList<>();
        tasks.add(unsafe().allocateInstance(FinishEconomyUpdateTask.class));
        field(stepperType, "tasks").set(stepper, tasks);
        return tasks;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void installInProgressCadence(Class<?> stepperType, Object stepper)
            throws Exception {
        Class<? extends Enum> stateType =
                (Class<? extends Enum>) ReachEconomyStepper.State.class;
        field(stepperType, "state").set(stepper, Enum.valueOf(stateType, "DOING_TASKS"));
        field(stepperType, "elapsed").setFloat(stepper, 7.25f);
        field(stepperType, "untilNext").setFloat(stepper, 4.5f);
        field(stepperType, "iterLeft").setInt(stepper, 2);
        field(stepperType, "prevMonth").setInt(stepper, 8);
        field(stepperType, "monthEndRefreshPending").setBoolean(stepper, true);
        field(stepperType, "pendingPreviousMonth").setInt(stepper, 7);
    }

    private static void verifyMainRestartModesAndFactories(
            Class<?> stepperType, XStream xstream) throws Exception {
        Class<?> mainTaskType = Class.forName(AOTD_MAIN_TASK_CLASS, false,
                stepperType.getClassLoader());
        MarketAPI allA = market("main-all-a");
        MarketAPI remainingA = market("main-remaining-a");
        MarketAPI remainingB = market("main-remaining-b");
        List<MarketAPI> allMarkets = List.of(allA, remainingA, remainingB);
        List<MarketAPI> remainingMarkets = List.of(remainingA, remainingB);

        assertMainCheckpointRoundTrip(
                stepperType,
                xstream,
                newMainCheckpointTask(mainTaskType, "FULL", allMarkets, true),
                "FULL",
                0,
                true,
                "MAIN_WORK>UPDATE_MARKETS>IMMIGRATION>POST_IMMIGRATION>FINISH",
                List.of());

        Object loadedPriceRemaining = assertMainCheckpointRoundTrip(
                stepperType,
                xstream,
                newMainCheckpointTask(
                        mainTaskType, "PRICE_REMAINING", remainingMarkets, true),
                "PRICE_REMAINING",
                2,
                true,
                "MAIN_WORK>UPDATE_MARKETS>IMMIGRATION>POST_IMMIGRATION>FINISH",
                List.of("main-remaining-a", "main-remaining-b"));
        Object loadedProcessed = market("main-all-a");
        Object loadedRemainingA = market("main-remaining-a");
        Object loadedRemainingB = market("main-remaining-b");
        Object restartPhase = field(stepperType, "runtimeRestartPhase")
                .get(loadedPriceRemaining);
        @SuppressWarnings("unchecked")
        List<Object> rebound = (List<Object>) invokePrivate(
                loadedPriceRemaining,
                "resolveRestartMarkets",
                restartPhase,
                List.of(loadedProcessed, loadedRemainingB, loadedRemainingA));
        require(rebound.size() == 2
                        && rebound.get(0) == loadedRemainingA
                        && rebound.get(1) == loadedRemainingB
                        && !rebound.contains(loadedProcessed),
                "MAIN PRICE_REMAINING stable-ID rebind changed exact unattempted scope/order");

        assertMainCheckpointRoundTrip(
                stepperType,
                xstream,
                newMainCheckpointTask(mainTaskType, "LISTENERS_ONLY", List.of(), true),
                "LISTENERS_ONLY",
                0,
                true,
                "MAIN_WORK>UPDATE_MARKETS>IMMIGRATION>POST_IMMIGRATION>FINISH",
                List.of());
        assertMainCheckpointRoundTrip(
                stepperType,
                xstream,
                newMainCheckpointTask(mainTaskType, "FULL", allMarkets, false),
                "DROP",
                0,
                false,
                "UPDATE_MARKETS>IMMIGRATION>POST_IMMIGRATION>FINISH",
                List.of());

        Object priceFactory = invokeStaticPrivate(
                mainTaskType,
                "forRuntimePriceRemaining",
                allMarkets,
                remainingMarkets,
                null,
                null);
        require("PRICE_REMAINING".equals(objectField(priceFactory, "runtimeResumeMode").toString())
                        && sameIdentityOrder(
                                castList(objectField(priceFactory, "aotdMarkets")),
                                remainingMarkets)
                        && sameIdentityOrder(
                                castList(objectField(priceFactory, "runtimeGlobalDataMarkets")),
                                allMarkets)
                        && !boolField(priceFactory, "mtDataCreated"),
                "PRICE_REMAINING factory lost remaining-price scope or full global-data rebuild");

        Object listenersFactory = invokeStaticPrivate(
                mainTaskType,
                "forRuntimeListenersOnly",
                allMarkets,
                null,
                null);
        require("LISTENERS_ONLY".equals(
                                objectField(listenersFactory, "runtimeResumeMode").toString())
                        && castList(objectField(listenersFactory, "aotdMarkets")).isEmpty()
                        && sameIdentityOrder(
                                castList(objectField(listenersFactory, "runtimeGlobalDataMarkets")),
                                allMarkets)
                        && !boolField(listenersFactory, "mtDataCreated")
                        && !boolField(listenersFactory, "mtListenersNotified"),
                "LISTENERS_ONLY factory can repeat price work or suppress its one listener pass");
    }

    private static void verifySameProcessMainSemanticRestart(Class<?> stepperType)
            throws Exception {
        ClassLoader loader = stepperType.getClassLoader();
        Class<?> mainTaskType = Class.forName(AOTD_MAIN_TASK_CLASS, false, loader);
        Class<?> registryType = Class.forName(
                "data.kaysaar.aotd.tot.compat.MarketRegistry", false, loader);
        Class<?> planType = Class.forName(AOTD_MAIN_TASK_CLASS + "$MarketPriceCommitPlan", false,
                loader);
        Method claimPrice = registryType.getMethod("claimMarketForPrice", Object.class);
        Method abandon = registryType.getMethod(
                "abandon",
                Class.forName(
                        "data.kaysaar.aotd.tot.compat.MarketRegistry$WorkTicket",
                        false,
                        loader),
                boolean.class);

        for (String outcome : List.of("success", "failure")) {
            MarketAPI market = market("same-process-main-" + outcome);
            registryType.getMethod("clear").invoke(null);
            registryType.getMethod("replaceAllMarkets", Map.class)
                    .invoke(null, Map.of(market.getId(), market));
            Object firstTicket = claimPrice.invoke(null, market);
            require(firstTicket != null,
                    "same-process " + outcome + " fixture could not claim price work");

            Object plan = unsafe().allocateInstance(planType);
            field(planType, "ticket").set(plan, firstTicket);
            Object main = newMainCheckpointTask(mainTaskType, "FULL", List.of(market), true);
            field(mainTaskType, "aotdStarted").setBoolean(main, true);
            field(mainTaskType, "mtCommitPlans").set(main, new ArrayList<>(List.of(plan)));
            AtomicInteger cancellations = new AtomicInteger();
            Future<?> future = (Future<?>) Proxy.newProxyInstance(
                    Future.class.getClassLoader(),
                    new Class<?>[] {Future.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "cancel" -> {
                            cancellations.incrementAndGet();
                            yield true;
                        }
                        case "isCancelled" -> cancellations.get() > 0;
                        case "isDone" -> false;
                        case "get" -> null;
                        default -> null;
                    });
            field(mainTaskType, "mtFutures").set(main, new ArrayList<>(List.of(future)));

            Object stepper = newStepper(stepperType);
            installInProgressCadence(stepperType, stepper);
            List<Object> liveTasks = new ArrayList<>(List.of(main));
            field(stepperType, "tasks").set(stepper, liveTasks);
            Object suspend = invoke(stepperType, stepper, "suspendRuntimeTasksForSave");
            require(boolField(suspend, "graphDetached")
                            && field(stepperType, "tasks").get(stepper) == null,
                    "same-process " + outcome + " did not detach MAIN under the save barrier");

            if ("failure".equals(outcome)) {
                Object nestedSuspend = invoke(
                        stepperType, stepper, "suspendRuntimeTasksForSave");
                require(intField(nestedSuspend, "suspensionDepth") == 2,
                        "nested save did not increment suspension depth");
                Object nestedResume = invoke(
                        stepperType, stepper, "resumeRuntimeTasksAfterSave");
                require(intField(nestedResume, "suspensionDepth") == 1
                                && cancellations.get() == 0
                                && field(stepperType, "suspendedRuntimeTasks").get(stepper)
                                        == liveTasks,
                        "inner failed-save resume released the suspended MAIN graph early");
            }

            Object resume = invoke(stepperType, stepper, "resumeRuntimeTasksAfterSave");
            require("resume-semantic-restart".equals(objectField(resume, "action"))
                            && !boolField(resume, "graphRestored")
                            && intField(resume, "taskCount") == 1
                            && "MAIN_WORK".equals(objectField(resume, "restartPhase"))
                            && field(stepperType, "tasks").get(stepper) == null
                            && field(stepperType, "runtimeRestartPhase").get(stepper) != null,
                    "same-process " + outcome + " reused or lost the invalidated MAIN suffix");
            require(cancellations.get() == 1
                            && castList(objectField(main, "mtFutures")).isEmpty()
                            && castList(objectField(main, "mtCommitPlans")).isEmpty()
                            && objectField(main, "mtOffloadBatch") == null,
                    "same-process " + outcome + " did not release MAIN futures/tickets/DTOs");

            Object replacementTicket = claimPrice.invoke(null, market);
            require(replacementTicket != null,
                    "same-process " + outcome + " left the old price ticket snapshot-building");
            abandon.invoke(null, replacementTicket, true);
        }

        Object waitingMain = newMainCheckpointTask(
                mainTaskType, "FULL", List.of(market("same-process-waiting")), true);
        Object waiting = newStepper(stepperType);
        field(stepperType, "state").set(waiting, ReachEconomyStepper.State.WAITING);
        field(stepperType, "tasks").set(waiting, new ArrayList<>(List.of(waitingMain)));
        invoke(stepperType, waiting, "suspendRuntimeTasksForSave");
        Object waitingResume = invoke(stepperType, waiting, "resumeRuntimeTasksAfterSave");
        require("resume-discard".equals(objectField(waitingResume, "action"))
                        && !boolField(waitingResume, "graphRestored")
                        && "none".equals(objectField(waitingResume, "restartPhase"))
                        && field(stepperType, "tasks").get(waiting) == null
                        && field(stepperType, "suspendedRuntimeTasks").get(waiting) == null
                        && field(stepperType, "runtimeRestartPhase").get(waiting) == null,
                "WAITING save retained a task graph or invented a semantic restart checkpoint");
        Object extraResume = invoke(stepperType, waiting, "resumeRuntimeTasksAfterSave");
        require("resume".equals(objectField(extraResume, "action"))
                        && intField(extraResume, "suspensionDepth") == 0
                        && field(stepperType, "tasks").get(waiting) == null,
                "unbalanced extra save resume mutated the already-discarded graph");
        registryType.getMethod("clear").invoke(null);
    }

    private static Object assertMainCheckpointRoundTrip(
            Class<?> stepperType,
            XStream xstream,
            Object task,
            String expectedMode,
            int remainingMarkets,
            boolean progressKnown,
            String plannedStages,
            List<String> expectedMarketIds) throws Exception {
        Object stepper = newStepper(stepperType);
        installInProgressCadence(stepperType, stepper);
        field(stepperType, "tasks").set(stepper, new ArrayList<>(List.of(task)));
        invoke(stepperType, stepper, "suspendRuntimeTasksForSave");
        String xml = xstream.toXML(stepper);
        require(!xml.contains("<tasks>")
                        && xml.contains("<runtimeRestartMainMode>"
                                + expectedMode
                                + "</runtimeRestartMainMode>"),
                "MAIN " + expectedMode + " checkpoint was not serialized without its task graph");
        for (String marketId : expectedMarketIds) {
            require(xml.contains("<string>" + marketId + "</string>"),
                    "MAIN " + expectedMode + " checkpoint lost stable market ID " + marketId);
        }

        Object loaded = xstream.fromXML(xml);
        assertReadResolveGuard(stepperType, loaded);
        Object report = restartAfterLoad(stepperType, loaded);
        assertPhaseReport(
                report,
                "MAIN_WORK",
                remainingMarkets,
                plannedStages,
                true,
                progressKnown,
                expectedMode);
        assertPreservedCadence(stepperType, loaded);
        return loaded;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object newMainCheckpointTask(
            Class<?> mainTaskType, String mode, List<MarketAPI> markets, boolean currentMarker)
            throws Exception {
        Object task = unsafe().allocateInstance(mainTaskType);
        field(mainTaskType, "runtimeMainCheckpointV1").setBoolean(task, currentMarker);
        Field modeField = field(mainTaskType, "runtimeResumeMode");
        modeField.set(task, Enum.valueOf((Class<? extends Enum>) modeField.getType(), mode));
        field(mainTaskType, "aotdMarkets").set(task, new ArrayList<>(markets));
        return task;
    }

    private static void verifyPhaseSuffixesAndStableIdRebind(
            Class<?> stepperType, XStream xstream) throws Exception {
        ClassLoader loader = stepperType.getClassLoader();
        Object main = unsafe().allocateInstance(Class.forName(MAIN_TASK_CLASS, false, loader));
        assertDirectTaskPhase(
                stepperType,
                main,
                "MAIN_WORK",
                0,
                "UPDATE_MARKETS>IMMIGRATION>POST_IMMIGRATION>FINISH",
                false,
                "DROP");

        Object update = unsafe().allocateInstance(Class.forName(UPDATE_TASK_CLASS, false, loader));
        Object updated = market("updated");
        Object updateA = market("update-a");
        Object updateB = market("update-b");
        field(update.getClass(), "markets").set(
                update, new ArrayList<>(List.of(updated, updateA, updateB)));
        field(update.getClass(), "marketIndex").setInt(update, 1);
        Object updateStepper = newStepper(stepperType);
        installInProgressCadence(stepperType, updateStepper);
        field(stepperType, "tasks").set(
                updateStepper, new ArrayList<>(List.of(update)));
        Object updateReport = restartAfterLoad(stepperType, updateStepper);
        assertPhaseReport(
                updateReport,
                "UPDATE_MARKETS",
                2,
                "UPDATE_MARKETS>IMMIGRATION>POST_IMMIGRATION>FINISH",
                false,
                true,
                "NONE");
        Object loadedUpdated = market("updated");
        Object loadedUpdateA = market("update-a");
        Object loadedUpdateB = market("update-b");
        Object updateRestartPhase = field(stepperType, "runtimeRestartPhase").get(updateStepper);
        @SuppressWarnings("unchecked")
        List<Object> reboundUpdate = (List<Object>) invokePrivate(
                updateStepper,
                "resolveRestartMarkets",
                updateRestartPhase,
                List.of(loadedUpdated, loadedUpdateB, loadedUpdateA));
        require(reboundUpdate.size() == 2
                        && reboundUpdate.get(0) == loadedUpdateA
                        && reboundUpdate.get(1) == loadedUpdateB
                        && !reboundUpdate.contains(loadedUpdated),
                "UPDATE_MARKETS stable-ID rebind changed remaining membership/order");

        Object immigration =
                unsafe().allocateInstance(Class.forName(IMMIGRATION_TASK_CLASS, false, loader));
        Object remainingA = market("remaining-a");
        Object remainingB = market("remaining-b");
        field(immigration.getClass(), "markets").set(
                immigration, new ArrayList<>(List.of(remainingA, remainingB)));
        Object immigrationStepper = newStepper(stepperType);
        installInProgressCadence(stepperType, immigrationStepper);
        field(stepperType, "tasks").set(
                immigrationStepper, new ArrayList<>(List.of(immigration)));
        invoke(stepperType, immigrationStepper, "suspendRuntimeTasksForSave");
        String immigrationXml = xstream.toXML(immigrationStepper);
        require(!immigrationXml.contains("<tasks>")
                        && immigrationXml.contains("<string>remaining-a</string>")
                        && immigrationXml.contains("<string>remaining-b</string>"),
                "IMMIGRATION save did not replace runtime tasks with stable market IDs");
        Object loadedImmigration = xstream.fromXML(immigrationXml);
        assertReadResolveGuard(stepperType, loadedImmigration);
        Object immigrationReport = restartAfterLoad(stepperType, loadedImmigration);
        assertPhaseReport(
                immigrationReport,
                "IMMIGRATION",
                2,
                "IMMIGRATION>POST_IMMIGRATION>FINISH",
                true,
                true,
                "NONE");

        Object loadedProcessed = market("processed");
        Object loadedA = market("remaining-a");
        Object loadedB = market("remaining-b");
        Object restartPhase = field(stepperType, "runtimeRestartPhase").get(loadedImmigration);
        @SuppressWarnings("unchecked")
        List<Object> rebound = (List<Object>) invokePrivate(
                loadedImmigration,
                "resolveRestartMarkets",
                restartPhase,
                List.of(loadedProcessed, loadedB, loadedA));
        require(rebound.size() == 2
                        && rebound.get(0) == loadedA
                        && rebound.get(1) == loadedB
                        && !rebound.contains(loadedProcessed),
                "IMMIGRATION stable-ID rebind changed remaining membership/order");

        Object post = unsafe().allocateInstance(Class.forName(POST_TASK_CLASS, false, loader));
        assertDirectTaskPhase(
                stepperType,
                post,
                "POST_IMMIGRATION",
                0,
                "POST_IMMIGRATION>FINISH",
                true,
                "NONE");
    }

    private static void assertDirectTaskPhase(
            Class<?> stepperType,
            Object task,
            String phase,
            int remainingMarkets,
            String plannedStages,
            boolean progressKnown,
            String mainRestartMode) throws Exception {
        Object stepper = newStepper(stepperType);
        installInProgressCadence(stepperType, stepper);
        field(stepperType, "tasks").set(stepper, new ArrayList<>(List.of(task)));
        assertPhaseReport(
                restartAfterLoad(stepperType, stepper),
                phase,
                remainingMarkets,
                plannedStages,
                false,
                progressKnown,
                mainRestartMode);
    }

    private static void assertPhaseReport(
            Object report,
            String phase,
            int remainingMarkets,
            String plannedStages,
            boolean expectedGuard,
            boolean progressKnown,
            String mainRestartMode) throws Exception {
        require(phase.equals(objectField(report, "restartPhase"))
                        && intField(report, "discardedTasks") == 1
                        && intField(report, "remainingMarketCount") == remainingMarkets
                        && boolField(report, "remainingMarketProgressKnown") == progressKnown
                        && !boolField(report, "unknownStage")
                        && boolField(report, "stageHadStarted")
                        && expectedGuard == boolField(report, "loadGuardWasActive")
                        && plannedStages.equals(objectField(report, "plannedStages"))
                        && mainRestartMode.equals(objectField(report, "mainRestartMode")),
                "wrong semantic restart suffix/report for " + phase + ": "
                        + objectField(report, "plannedStages"));
    }

    private static void assertReadResolveGuard(Class<?> stepperType, Object stepper)
            throws Exception {
        require(field(stepperType, "tasks").get(stepper) == null,
                "readResolve retained the serialized runtime task graph");
        require(field(stepperType, "runtimeTaskLoadGuard").getBoolean(stepper),
                "readResolve did not block task recreation before onGameLoad epoch binding");
        require(field(stepperType, "suspendedRuntimeTasks").get(stepper) == null
                        && field(stepperType, "runtimeTaskSaveSuspensionDepth").getInt(stepper) == 0,
                "readResolve retained same-process save suspension state");
    }

    private static Object restartAfterLoad(Class<?> stepperType, Object stepper) throws Exception {
        Class<?> epochSnapshot = Class.forName(EPOCH_SNAPSHOT_CLASS);
        Method restart = stepperType.getMethod("restartRuntimeTasksAfterLoad", epochSnapshot);
        return restart.invoke(stepper, new Object[] {null});
    }

    private static void assertRestartReport(
            Object report, int expectedDiscarded, String expectedPhase, boolean expectedGuard)
            throws Exception {
        require(intField(report, "discardedTasks") == expectedDiscarded,
                "restart report changed discarded task count");
        require(expectedPhase.equals(objectField(report, "restartPhase")),
                "restart report changed semantic suffix: " + objectField(report, "restartPhase"));
        require(boolField(report, "loadGuardWasActive") == expectedGuard
                        && boolField(report, "iterationRestarted"),
                "restart report lost load-guard/in-progress iteration evidence");
        require(intField(report, "remainingMarketCount") == 0
                        && boolField(report, "remainingMarketProgressKnown")
                        && !boolField(report, "unknownStage")
                        && boolField(report, "stageHadStarted")
                        && "FINISH".equals(objectField(report, "plannedStages"))
                        && "NONE".equals(objectField(report, "mainRestartMode")),
                "known FINISH checkpoint became an unknown or partial-market restart");
        require("DOING_TASKS".equals(objectField(report, "preservedState").toString())
                        && Float.floatToIntBits(floatField(report, "preservedElapsed"))
                                == Float.floatToIntBits(7.25f)
                        && Float.floatToIntBits(floatField(report, "preservedUntilNext"))
                                == Float.floatToIntBits(4.5f)
                        && intField(report, "preservedIterationsLeft") == 2
                        && intField(report, "preservedMonth") == 8
                        && boolField(report, "preservedMonthEndRefreshPending")
                        && intField(report, "preservedPreviousMonth") == 7,
                "restart report did not preserve calendar/iteration checkpoint evidence");
        require(longField(report, "campaignEpoch") == 0L
                        && longField(report, "economyEpoch") == 0L,
                "null isolated epoch unexpectedly published a live runtime identity");
    }

    private static void assertPreservedCadence(Class<?> stepperType, Object stepper)
            throws Exception {
        require("DOING_TASKS".equals(objectField(stepper, "state").toString()),
                "load restart changed the selected iteration state");
        require(Float.floatToIntBits(field(stepperType, "elapsed").getFloat(stepper))
                                == Float.floatToIntBits(7.25f)
                        && Float.floatToIntBits(field(stepperType, "untilNext").getFloat(stepper))
                                == Float.floatToIntBits(4.5f)
                        && field(stepperType, "iterLeft").getInt(stepper) == 2
                        && field(stepperType, "prevMonth").getInt(stepper) == 8
                        && field(stepperType, "monthEndRefreshPending").getBoolean(stepper)
                        && field(stepperType, "pendingPreviousMonth").getInt(stepper) == 7,
                "load restart changed calendar cadence or month-end intent");
    }

    private static Object invoke(Class<?> owner, Object target, String name) throws Exception {
        return owner.getMethod(name).invoke(target);
    }

    private static Object invokePrivate(Object target, String name, Object... arguments)
            throws Exception {
        for (Method method : target.getClass().getDeclaredMethods()) {
            if (!name.equals(method.getName())
                    || method.getParameterCount() != arguments.length) continue;
            method.setAccessible(true);
            return method.invoke(target, arguments);
        }
        throw new NoSuchMethodException(target.getClass().getName() + '.' + name);
    }

    private static Object invokeStaticPrivate(
            Class<?> owner, String name, Object... arguments) throws Exception {
        for (Method method : owner.getDeclaredMethods()) {
            if (!name.equals(method.getName())
                    || method.getParameterCount() != arguments.length) continue;
            method.setAccessible(true);
            return method.invoke(null, arguments);
        }
        throw new NoSuchMethodException(owner.getName() + '.' + name);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return (List<Object>) value;
    }

    private static boolean sameIdentityOrder(List<?> actual, List<?> expected) {
        if (actual == null || expected == null || actual.size() != expected.size()) return false;
        for (int i = 0; i < actual.size(); i++) {
            if (actual.get(i) != expected.get(i)) return false;
        }
        return true;
    }

    private static MarketAPI market(String id) {
        return (MarketAPI) Proxy.newProxyInstance(
                MarketAPI.class.getClassLoader(),
                new Class<?>[] {MarketAPI.class},
                (proxy, method, arguments) -> {
                    if ("getId".equals(method.getName())) return id;
                    if ("toString".equals(method.getName())) return "Market[" + id + ']';
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) return proxy == arguments[0];
                    Class<?> result = method.getReturnType();
                    if (!result.isPrimitive()) return null;
                    if (result == boolean.class) return false;
                    if (result == char.class) return '\0';
                    if (result == byte.class) return (byte) 0;
                    if (result == short.class) return (short) 0;
                    if (result == int.class) return 0;
                    if (result == long.class) return 0L;
                    if (result == float.class) return 0f;
                    if (result == double.class) return 0d;
                    return null;
                });
    }

    private static void installSettingsStub() {
        if (Global.getSettings() != null) return;
        SettingsAPI settings = (SettingsAPI) Proxy.newProxyInstance(
                SettingsAPI.class.getClassLoader(),
                new Class<?>[] {SettingsAPI.class},
                (proxy, method, arguments) -> {
                    if ("getBoolean".equals(method.getName())) return false;
                    Class<?> result = method.getReturnType();
                    if (!result.isPrimitive()) return null;
                    if (result == boolean.class) return false;
                    if (result == char.class) return '\0';
                    if (result == byte.class) return (byte) 0;
                    if (result == short.class) return (short) 0;
                    if (result == int.class) return 0;
                    if (result == long.class) return 0L;
                    if (result == float.class) return 0f;
                    if (result == double.class) return 0d;
                    return null;
                });
        Global.setSettings(settings);
    }

    private static XStream xstream(ClassLoader loader) {
        XStream xstream = new XStream(new DomDriver());
        XStream.setupDefaultSecurity(xstream);
        xstream.allowTypesByWildcard(new String[] {
                "data.kaysaar.aotd.tot.scripts.economy.**",
                "com.fs.starfarer.campaign.econ.reach.**",
                "com.fs.starfarer.campaign.econ.contract.iter.**",
                "java.lang.**",
                "java.util.**"
        });
        xstream.setClassLoader(loader);
        return xstream;
    }

    private static boolean boolField(Object owner, String name) throws Exception {
        return field(owner.getClass(), name).getBoolean(owner);
    }

    private static int intField(Object owner, String name) throws Exception {
        return field(owner.getClass(), name).getInt(owner);
    }

    private static long longField(Object owner, String name) throws Exception {
        return field(owner.getClass(), name).getLong(owner);
    }

    private static float floatField(Object owner, String name) throws Exception {
        return field(owner.getClass(), name).getFloat(owner);
    }

    private static Object objectField(Object owner, String name) throws Exception {
        return field(owner.getClass(), name).get(owner);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Continue through fork-owned shadows and the vanilla superclass.
            }
        }
        throw new NoSuchFieldException(type.getName() + '.' + name);
    }

    private static Unsafe unsafe() throws ReflectiveOperationException {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
