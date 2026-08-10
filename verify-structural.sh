#!/usr/bin/env bash
set -euo pipefail

MOD_ROOT="$(cd "$(dirname "$0")" && pwd)"
GAME_ROOT="$(cd "$MOD_ROOT/../.." && pwd)"
CORE="$GAME_ROOT/starsector-core"
BUILD="$MOD_ROOT/.build"
AGENT_CLASSES="$BUILD/agent-classes"
TEST_CLASSES="$BUILD/test-classes"
FR_SMOKE_CLASSES="$BUILD/fr-smoke-classes"
REPORT_DIR="$BUILD/reports"
EXPORTS=(
  --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED
  --add-exports java.base/jdk.internal.org.objectweb.asm.tree=ALL-UNNAMED
  --add-exports java.base/jdk.internal.org.objectweb.asm.tree.analysis=ALL-UNNAMED
)

bash "$MOD_ROOT/build-agent.sh"
rm -rf "$TEST_CLASSES" "$FR_SMOKE_CLASSES"
mkdir -p "$TEST_CLASSES" "$FR_SMOKE_CLASSES" "$REPORT_DIR"

TEST_CP="$AGENT_CLASSES:$CORE/starfarer.api.jar:$CORE/starfarer_obf.jar:$CORE/fs.common_obf.jar:$CORE/fs.sound_obf.jar:$CORE/lwjgl.jar:$CORE/lwjgl_util.jar:$CORE/log4j-1.2.9.jar:$CORE/xstream-1.4.10.jar:$CORE/jaxb-api-2.4.0-b180830.0359.jar:$CORE/json.jar"
FR_SMOKE_SOURCE="$MOD_ROOT/source/test/com/starsector/prepatcher/fr/FasterRenderingLoaderSmokeTest.java"
find "$MOD_ROOT/source/test" -name '*.java' ! -path "$FR_SMOKE_SOURCE" -print0 | \
  xargs -0 javac -encoding UTF-8 -source 17 -target 17 \
  "${EXPORTS[@]}" -cp "$TEST_CP" -d "$TEST_CLASSES"
javac -encoding UTF-8 -source 17 -target 17 \
  -d "$FR_SMOKE_CLASSES" "$FR_SMOKE_SOURCE"

java -cp "$TEST_CLASSES" \
  com.starsector.prepatcher.docs.DocumentationConsistencyTest "$MOD_ROOT" \
  2>&1 | tee "$REPORT_DIR/documentation-consistency.txt"

if (( $# == 0 )); then
  CORE_JARS=(
    "$CORE/starfarer_obf.jar"
    "$CORE/fs.common_obf.jar"
    "$CORE/fs.sound_obf.jar"
    "$CORE/starfarer.api.jar"
  )
else
  CORE_JARS=("$@")
fi

CLASS_PATH="$AGENT_CLASSES:$TEST_CLASSES"
VERIFICATION_CONFIG="$BUILD/structural-all-enabled.properties"
{
  printf '%s\n' '# Generated for structural coverage only; never shipped or used by startup smoke.'
  sed \
    -e 's/^patch\.loadingTextReader[[:space:]]*=.*/patch.loadingTextReader=true/' \
    -e 's/^patch\.startupLogAggregation[[:space:]]*=.*/patch.startupLogAggregation=true/' \
    "$MOD_ROOT/profiles/aggressive.properties"
  printf '%s\n' 'patch.directMarketObservation=true'
  printf '%s\n' 'logging.statsIntervalSeconds=1'
} > "$VERIFICATION_CONFIG"
grep -qx 'patch.loadingTextReader=true' "$VERIFICATION_CONFIG"
grep -qx 'patch.startupLogAggregation=true' "$VERIFICATION_CONFIG"
grep -qx 'patch.directMarketObservation=true' "$VERIFICATION_CONFIG"
grep -qx 'logging.statsIntervalSeconds=1' "$VERIFICATION_CONFIG"
java "${EXPORTS[@]}" -cp "$CLASS_PATH" \
  com.starsector.prepatcher.agent.StructuralCompatibilityTest \
  "$VERIFICATION_CONFIG" "${CORE_JARS[@]}" \
  2>&1 | tee "$REPORT_DIR/structural-verification.txt"

java "${EXPORTS[@]}" -cp "$CLASS_PATH" \
  com.starsector.prepatcher.agent.CoreWorldsStructuralMatcherTest \
  "$VERIFICATION_CONFIG" "$CORE/starfarer.api.jar" "$CORE/starfarer_obf.jar" \
  2>&1 | tee "$REPORT_DIR/core-worlds-structural-matcher.txt"

NEX_JAR="${NEXERELIN_JAR:-$GAME_ROOT/mods/Nexerelin/jars/ExerelinCore.jar}"
AOTD_JAR="${AOTD_TOOLBOX_JAR:-$GAME_ROOT/mods/AoTD-Theory-Of-Toolbox-Scheduler-Fork/jars/AoTDToolboxTheory.jar}"
if [[ -f "$NEX_JAR" ]]; then
  java "${EXPORTS[@]}" -cp "$CLASS_PATH" \
    com.starsector.prepatcher.agent.MarketShareOptionalCompatibilityTest \
    "$NEX_JAR" \
    2>&1 | tee "$REPORT_DIR/market-share-optional-nex.txt"
else
  echo "SKIPPED optional Nexerelin market-share compatibility: $NEX_JAR not found" | \
    tee "$REPORT_DIR/market-share-optional-nex.txt"
fi

MARKET_SHARE_LOAD_ARGS=("$CORE/starfarer_obf.jar" "$CORE/starfarer.api.jar")
if [[ -f "$NEX_JAR" ]]; then
  MARKET_SHARE_LOAD_ARGS+=("$NEX_JAR")
fi
java "${EXPORTS[@]}" -Xverify:all -cp "$TEST_CLASSES:$TEST_CP" \
  com.starsector.prepatcher.agent.MarketShareClassLoadSmokeTest \
  "${MARKET_SHARE_LOAD_ARGS[@]}" \
  2>&1 | tee "$REPORT_DIR/market-share-class-load.txt"

java -cp "$TEST_CLASSES" \
  com.starsector.prepatcher.agent.MarketShareAlgorithmDifferentialTest \
  2>&1 | tee "$REPORT_DIR/market-share-algorithm-differential.txt"

if [[ -f "$AOTD_JAR" ]]; then
  java "${EXPORTS[@]}" -cp "$TEST_CLASSES:$TEST_CP" \
    com.starsector.prepatcher.agent.MarketShareAoTDForkCompatibilityTest \
    "$AOTD_JAR" \
    2>&1 | tee "$REPORT_DIR/market-share-aotd-fork.txt"
else
  echo "SKIPPED AoTD market-share compatibility: $AOTD_JAR not found" | \
    tee "$REPORT_DIR/market-share-aotd-fork.txt"
fi

java "${EXPORTS[@]}" -cp "$CLASS_PATH" \
  com.starsector.prepatcher.agent.AoTDMarketOpenContextTransformerTest \
  "$CORE/starfarer_obf.jar" "$VERIFICATION_CONFIG" \
  2>&1 | tee "$REPORT_DIR/aotd-market-open-context.txt"

java "${EXPORTS[@]}" -cp "$CLASS_PATH" \
  com.starsector.prepatcher.agent.VanillaDetachedCargoEconomyContractTransformerTest \
  "$CORE/starfarer_obf.jar" \
  2>&1 | tee "$REPORT_DIR/vanilla-detached-cargo-economy-contract.txt"

java "${EXPORTS[@]}" -cp "$CLASS_PATH" \
  com.starsector.prepatcher.agent.VanillaMarketOpenLocalizationContractTransformerTest \
  "$CORE/starfarer_obf.jar" \
  2>&1 | tee "$REPORT_DIR/vanilla-market-open-localization-contract.txt"

java "${EXPORTS[@]}" -cp "$CLASS_PATH" \
  com.starsector.prepatcher.agent.CommodityMarketDataContractTransformerTest \
  "$CORE/starfarer_obf.jar" \
  2>&1 | tee "$REPORT_DIR/commodity-market-data-contract.txt"

java "${EXPORTS[@]}" -cp "$CLASS_PATH" \
  com.starsector.prepatcher.agent.MarketOverviewMutationTransformerTest \
  "$CORE/starfarer_obf.jar" \
  2>&1 | tee "$REPORT_DIR/market-overview-mutation-transformer.txt"

java "${EXPORTS[@]}" -cp "$CLASS_PATH" \
  com.starsector.prepatcher.agent.TradeMarketMutationTransformerTest \
  "$CORE/starfarer_obf.jar" \
  2>&1 | tee "$REPORT_DIR/trade-market-mutation-transformer.txt"

java "${EXPORTS[@]}" -cp "$CLASS_PATH" \
  com.starsector.prepatcher.agent.IndustryMarketMutationTransformerTest \
  "$CORE/starfarer_obf.jar" \
  2>&1 | tee "$REPORT_DIR/industry-market-mutation-transformer.txt"

java "${EXPORTS[@]}" -cp "$TEST_CLASSES:$TEST_CP" \
  com.fs.starfarer.api.UiMarketMutationContextRuntimeTest \
  2>&1 | tee "$REPORT_DIR/market-mutation-context-runtime.txt"

java "${EXPORTS[@]}" -cp "$TEST_CLASSES:$TEST_CP" \
  com.fs.starfarer.api.UiMarketMutationFailOpenRuntimeTest \
  2>&1 | tee "$REPORT_DIR/market-mutation-fail-open-runtime.txt"

java "${EXPORTS[@]}" -cp "$TEST_CLASSES:$TEST_CP" \
  com.fs.starfarer.api.TradeMutationPreparationRuntimeTest \
  2>&1 | tee "$REPORT_DIR/trade-mutation-preparation-runtime.txt"

java "${EXPORTS[@]}" -cp "$TEST_CLASSES:$TEST_CP" \
  com.fs.starfarer.api.IndustryMutationRuntimeTest \
  2>&1 | tee "$REPORT_DIR/industry-mutation-runtime.txt"

java "${EXPORTS[@]}" -cp "$CLASS_PATH" \
  com.starsector.prepatcher.agent.AoTDDetachedCargoContextTransformerTest \
  "$CORE/starfarer_obf.jar" \
  2>&1 | tee "$REPORT_DIR/aotd-detached-cargo-context.txt"

java "${EXPORTS[@]}" -cp "$CLASS_PATH" \
  com.starsector.prepatcher.agent.UiEconomyScenarioContractTest \
  "$CORE/starfarer_obf.jar" "$CORE/starfarer.api.jar" \
  2>&1 | tee "$REPORT_DIR/ui-economy-scenario-contract.txt"

READ_ONLY_UI_ARGS=("$CORE/starfarer_obf.jar" "$CORE/starfarer.api.jar")
if [[ -f "$NEX_JAR" ]]; then
  READ_ONLY_UI_ARGS+=("$NEX_JAR")
fi
java "${EXPORTS[@]}" -cp "$CLASS_PATH" \
  com.starsector.prepatcher.agent.ReadOnlyUiEconomyStepTransformerTest \
  "${READ_ONLY_UI_ARGS[@]}" \
  2>&1 | tee "$REPORT_DIR/read-only-ui-economy-step.txt"

java "${EXPORTS[@]}" -cp "$CLASS_PATH" \
  com.starsector.prepatcher.agent.AoTDEconomyRestoreCompletionTransformerTest \
  "$CORE/starfarer.api.jar" \
  2>&1 | tee "$REPORT_DIR/aotd-economy-restore-completion.txt"

if [[ -f "$AOTD_JAR" ]]; then
  AOTD_MOD_ROOT="$(cd "$(dirname "$AOTD_JAR")/.." && pwd)"
  java "${EXPORTS[@]}" -cp "$TEST_CLASSES:$TEST_CP" \
    com.starsector.prepatcher.agent.EconomyHotpathAoTDForkCompatibilityTest \
    "$AOTD_JAR" \
    2>&1 | tee "$REPORT_DIR/economy-hotpath-aotd-fork.txt"
  java "${EXPORTS[@]}" -cp "$TEST_CLASSES:$TEST_CP" \
    com.starsector.prepatcher.agent.AoTDForkCompatibilityTransformerTest \
    "$AOTD_JAR" \
    2>&1 | tee "$REPORT_DIR/aotd-fork-owned-transformer.txt"
  java "${EXPORTS[@]}" -cp "$TEST_CLASSES:$TEST_CP" \
    com.starsector.prepatcher.agent.AoTDSchedulerBridgeTransformerTest \
    "$AOTD_JAR" \
    2>&1 | tee "$REPORT_DIR/aotd-scheduler-bridge.txt"
  java "${EXPORTS[@]}" -cp "$TEST_CLASSES:$TEST_CP" \
    com.starsector.prepatcher.agent.AoTDUIEconomyBehaviorCompatibilityTest \
    "$AOTD_JAR" \
    2>&1 | tee "$REPORT_DIR/aotd-ui-economy-behavior.txt"
  java -cp "$TEST_CLASSES:$TEST_CP" \
    com.starsector.prepatcher.agent.AoTDForkMarkerScannerTest \
    "$AOTD_MOD_ROOT" \
    2>&1 | tee "$REPORT_DIR/aotd-marker-scanner.txt"
else
  echo "SKIPPED AoTD economy-hotpath compatibility: $AOTD_JAR not found" | \
    tee "$REPORT_DIR/economy-hotpath-aotd-fork.txt"
  echo "SKIPPED AoTD fork-owned transformer compatibility: $AOTD_JAR not found" | \
    tee "$REPORT_DIR/aotd-fork-owned-transformer.txt"
  echo "SKIPPED AoTD scheduler bridge: $AOTD_JAR not found" | \
    tee "$REPORT_DIR/aotd-scheduler-bridge.txt"
  echo "SKIPPED AoTD UI economy behavior: $AOTD_JAR not found" | \
    tee "$REPORT_DIR/aotd-ui-economy-behavior.txt"
  echo "SKIPPED AoTD marker scanner: $AOTD_JAR not found" | \
    tee "$REPORT_DIR/aotd-marker-scanner.txt"
fi

java "${EXPORTS[@]}" -cp "$CLASS_PATH" \
  com.starsector.prepatcher.agent.StrategicJumpDestinationFirstTransformerTest \
  "$VERIFICATION_CONFIG" "$CORE/starfarer_obf.jar" \
  2>&1 | tee "$REPORT_DIR/strategic-jump-destination-first.txt"

java "${EXPORTS[@]}" -cp "$CLASS_PATH" \
  com.starsector.prepatcher.agent.StrategicJumpDestinationIndexTransformerTest \
  "$VERIFICATION_CONFIG" "$CORE/starfarer_obf.jar" "$CORE/starfarer.api.jar" \
  2>&1 | tee "$REPORT_DIR/strategic-jump-destination-index.txt"

java "${EXPORTS[@]}" -cp "$CLASS_PATH" \
  com.starsector.prepatcher.agent.FastForwardPresentationStructuralPlanTest \
  2>&1 | tee "$REPORT_DIR/fast-forward-presentation-structural-plan.txt"

java "${EXPORTS[@]}" -cp "$CLASS_PATH" \
  com.starsector.prepatcher.agent.FastForwardPresentationCompatibilityTest \
  "$CORE/starfarer_obf.jar" "$CORE/starfarer.api.jar" \
  2>&1 | tee "$REPORT_DIR/fast-forward-presentation-compatibility.txt"

java "${EXPORTS[@]}" -cp "$TEST_CLASSES:$TEST_CP" \
  com.starsector.prepatcher.agent.DirectMarketObserveTransformerTest \
  2>&1 | tee "$REPORT_DIR/direct-market-transformer.txt"

RUNTIME_CP="$TEST_CLASSES:$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar:$CORE/starfarer.api.jar:$CORE/starfarer_obf.jar:$CORE/fs.common_obf.jar:$CORE/fs.sound_obf.jar:$CORE/lwjgl.jar:$CORE/lwjgl_util.jar:$CORE/xstream-1.4.10.jar:$CORE/jaxb-api-2.4.0-b180830.0359.jar:$CORE/json.jar"
java -cp "$RUNTIME_CP" \
  com.starsector.prepatcher.runtime.AoTDEconomyRestoreRuntimeTest \
  2>&1 | tee "$REPORT_DIR/aotd-economy-restore-runtime.txt"

{
  echo '== LifecycleGcRegressionTest =='
  java -cp "$RUNTIME_CP" com.starsector.prepatcher.runtime.LifecycleGcRegressionTest
  echo '== CacheMaintenanceRuntimeTest =='
  java -cp "$RUNTIME_CP" com.starsector.prepatcher.runtime.CacheMaintenanceRuntimeTest
  echo '== CoreWorldsRuntimeRegressionTest =='
  java -cp "$RUNTIME_CP" com.starsector.prepatcher.runtime.CoreWorldsRuntimeRegressionTest
  echo '== Exp6RuntimeRegressionTest =='
  java -cp "$RUNTIME_CP" com.starsector.prepatcher.runtime.Exp6RuntimeRegressionTest
  echo '== Exp8RuntimeRegressionTest =='
  java -cp "$RUNTIME_CP" com.starsector.prepatcher.runtime.Exp8RuntimeRegressionTest
  echo '== MarketSchedulerRuntimeTest =='
  java -cp "$RUNTIME_CP" com.starsector.prepatcher.runtime.MarketSchedulerRuntimeTest
  echo '== DirectMarketObservationRuntimeTest =='
  java -cp "$RUNTIME_CP" com.starsector.prepatcher.runtime.DirectMarketObservationRuntimeTest
  echo '== PersistentEconomyRuntimeRegressionTest =='
  java -cp "$RUNTIME_CP" com.starsector.prepatcher.runtime.PersistentEconomyRuntimeRegressionTest
  echo '== MarketNoOpRuntimeRegressionTest =='
  java -cp "$RUNTIME_CP" com.starsector.prepatcher.runtime.MarketNoOpRuntimeRegressionTest
  echo '== TempModExpiryRuntimeRegressionTest =='
  java -cp "$RUNTIME_CP" com.starsector.prepatcher.runtime.TempModExpiryRuntimeRegressionTest
  echo '== LoadingSaveRuntimeRegressionTest =='
  java -cp "$RUNTIME_CP" com.starsector.prepatcher.runtime.LoadingSaveRuntimeRegressionTest
  echo '== AoTDDomainRevisionRuntimeTest =='
  java -cp "$RUNTIME_CP" com.starsector.prepatcher.runtime.AoTDDomainRevisionRuntimeTest
  echo '== AoTDDeliveryListenerFailStopTest =='
  java -cp "$RUNTIME_CP" com.starsector.prepatcher.runtime.AoTDDeliveryListenerFailStopTest
  echo '== AoTDCurrentContractNegotiationTest =='
  java -cp "$RUNTIME_CP" \
    com.starsector.prepatcher.runtime.AoTDCurrentContractNegotiationTest
  echo '== AoTDExplicitUiEconomyDispatchRuntimeTest =='
  java -cp "$RUNTIME_CP" \
    com.starsector.prepatcher.runtime.AoTDExplicitUiEconomyDispatchRuntimeTest
  echo '== AoTDScriptLoaderUiDispatchRuntimeTest =='
  java -cp "$RUNTIME_CP" \
    com.starsector.prepatcher.runtime.AoTDScriptLoaderUiDispatchRuntimeTest
  echo '== AoTDDetachedCargoContextRuntimeTest =='
  java -cp "$RUNTIME_CP" com.starsector.prepatcher.runtime.AoTDDetachedCargoContextRuntimeTest
  echo '== ConditionOnlyMarketOpenRuntimeTest =='
  java -cp "$RUNTIME_CP" com.starsector.prepatcher.runtime.ConditionOnlyMarketOpenRuntimeTest
  echo '== EconomyHotpathRuntimeTest =='
  java -cp "$RUNTIME_CP" com.fs.starfarer.api.EconomyHotpathRuntimeTest
  echo '== AoTDSemanticBaselineTest =='
  java -cp "$RUNTIME_CP" com.starsector.prepatcher.aotd.AoTDSemanticBaselineTest \
    "$MOD_ROOT/baseline/aotd/deficit-scenarios.csv"
  echo '== StrategicJumpDestinationIndexRuntimeTest =='
  java -cp "$RUNTIME_CP" com.fs.starfarer.api.StrategicJumpDestinationIndexRuntimeTest
} 2>&1 | tee "$REPORT_DIR/runtime-regression.txt"

{
  echo '== FastForwardPresentationRuntimeTest =='
  java -noverify -cp "$RUNTIME_CP" \
    com.fs.starfarer.api.FastForwardPresentationRuntimeTest
  echo '== FastForwardPresentationLoadedTargetPolicyTest =='
  java -noverify -cp "$RUNTIME_CP" \
    com.starsector.prepatcher.agent.FastForwardPresentationLoadedTargetPolicyTest
} 2>&1 | tee "$REPORT_DIR/fast-forward-presentation-runtime.txt"

java "${EXPORTS[@]}" -cp "$RUNTIME_CP" \
  com.starsector.prepatcher.agent.StartupAuditCoverageTest \
  2>&1 | tee "$REPORT_DIR/startup-audit-coverage.txt"

if [[ -f "$AOTD_JAR" ]]; then
  for restore_order in core-first fork-first; do
    java \
      "-javaagent:$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar=config=$MOD_ROOT/profiles/aggressive.properties" \
      -cp "$RUNTIME_CP:$AOTD_JAR" \
      com.starsector.prepatcher.runtime.AoTDEconomyRestoreActualAgentSmokeTest \
      "$restore_order" \
      2>&1 | tee "$REPORT_DIR/aotd-economy-restore-actual-agent-$restore_order.txt"
  done
else
  for restore_order in core-first fork-first; do
    echo "SKIPPED AoTD economy-restore $restore_order actual-agent smoke: $AOTD_JAR not found" | \
      tee "$REPORT_DIR/aotd-economy-restore-actual-agent-$restore_order.txt"
  done
fi

if [[ -f "$AOTD_JAR" ]]; then
  java \
    --add-opens=java.base/java.util=ALL-UNNAMED \
    --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
    --add-opens=java.base/java.text=ALL-UNNAMED \
    --add-opens=java.desktop/java.awt.font=ALL-UNNAMED \
    --add-opens=java.desktop/java.awt=ALL-UNNAMED \
    -Dstarsector.prepatcher.sessionOrigin=aotd-supply-demand-xstream \
    "-javaagent:$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar=config=$MOD_ROOT/profiles/aggressive.properties" \
    -cp "$RUNTIME_CP:$AOTD_JAR" \
    com.starsector.prepatcher.runtime.AoTDSupplyDemandXStreamMigrationTest \
    2>&1 | tee "$REPORT_DIR/aotd-supply-demand-xstream-migration.txt"
else
  echo "SKIPPED AoTD supply/demand XStream migration: $AOTD_JAR not found" | \
    tee "$REPORT_DIR/aotd-supply-demand-xstream-migration.txt"
fi

if [[ -f "$AOTD_JAR" ]]; then
  java \
    --add-opens=java.base/java.util=ALL-UNNAMED \
    --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
    --add-opens=java.base/java.text=ALL-UNNAMED \
    --add-opens=java.desktop/java.awt.font=ALL-UNNAMED \
    --add-opens=java.desktop/java.awt=ALL-UNNAMED \
    -Dstarsector.prepatcher.sessionOrigin=aotd-runtime-task-xstream \
    "-javaagent:$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar=config=$MOD_ROOT/profiles/aggressive.properties" \
    -cp "$RUNTIME_CP:$AOTD_JAR" \
    com.starsector.prepatcher.runtime.AoTDRuntimeTaskXStreamSanitizationTest \
    2>&1 | tee "$REPORT_DIR/aotd-runtime-task-xstream-sanitization.txt"
else
  echo "SKIPPED AoTD runtime-task XStream sanitization: $AOTD_JAR not found" | \
    tee "$REPORT_DIR/aotd-runtime-task-xstream-sanitization.txt"
fi

UI_ECONOMY_AGENT_ARGS=()
if [[ -f "$NEX_JAR" ]]; then
  UI_ECONOMY_AGENT_ARGS+=("$NEX_JAR")
fi
java \
  "-javaagent:$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar=config=$MOD_ROOT/profiles/aggressive.properties" \
  -cp "$RUNTIME_CP" \
  com.starsector.prepatcher.runtime.CoreWorldsActualAgentSmokeTest \
  2>&1 | tee "$REPORT_DIR/core-worlds-actual-agent.txt"

java \
  "-javaagent:$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar=config=$MOD_ROOT/profiles/aggressive.properties" \
  -cp "$RUNTIME_CP" \
  com.starsector.prepatcher.runtime.VanillaDetachedCargoContractActualAgentSmokeTest \
  2>&1 | tee "$REPORT_DIR/vanilla-detached-cargo-actual-agent.txt"

java -cp "$RUNTIME_CP" \
  com.fs.starfarer.api.VanillaMarketOpenCoalescingRuntimeTest \
  2>&1 | tee "$REPORT_DIR/vanilla-market-open-coalescing-runtime.txt"

java \
  "-javaagent:$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar=config=$MOD_ROOT/profiles/aggressive.properties" \
  -cp "$RUNTIME_CP" \
  com.starsector.prepatcher.runtime.UiEconomyActualAgentSmokeTest \
  "${UI_ECONOMY_AGENT_ARGS[@]}" \
  2>&1 | tee "$REPORT_DIR/ui-economy-actual-agent.txt"

java -noverify \
  "-javaagent:$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar=config=$MOD_ROOT/profiles/aggressive.properties" \
  -cp "$RUNTIME_CP" \
  com.starsector.prepatcher.runtime.FastForwardPresentationActualAgentSmokeTest \
  2>&1 | tee "$REPORT_DIR/fast-forward-presentation-actual-agent.txt"

java \
  -Dstarsector.prepatcher.sessionOrigin=temp-mod-smoke \
  "-javaagent:$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar" \
  -cp "$RUNTIME_CP" \
  com.starsector.prepatcher.runtime.TempModActualAgentSmokeTest \
  2>&1 | tee "$REPORT_DIR/temp-mod-actual-agent-smoke.txt"

java \
  -Dstarsector.prepatcher.sessionOrigin=market-step-replay-smoke \
  "-javaagent:$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar" \
  -cp "$RUNTIME_CP" \
  com.starsector.prepatcher.runtime.MarketStepReplayActualAgentSmokeTest \
  2>&1 | tee "$REPORT_DIR/market-step-replay-actual-agent-smoke.txt"

# Exercise the read-only UI, condition-only opening and localization paths through
# the real javaagent with all unrelated
# bytecode patches disabled. Nexerelin remains an optional target.
MARKET_SHARE_AGENT_CONFIG="$BUILD/market-share-agent-smoke.properties"
sed -E \
  -e 's/^(patch\.[^=]+)=.*/\1=false/' \
  -e 's/^logging\.statsIntervalSeconds=.*/logging.statsIntervalSeconds=0/' \
  "$MOD_ROOT/prepatcher.properties" | \
  sed -E \
    -e 's/^patch\.marketShareLinearAggregation=false/patch.marketShareLinearAggregation=true/' \
    -e 's/^patch\.marketShareDataPutElision=false/patch.marketShareDataPutElision=true/' \
    -e 's/^patch\.punitivePlayerShareLocalCache=false/patch.punitivePlayerShareLocalCache=true/' \
    -e 's/^patch\.nexPunitivePlayerShareLocalCache=false/patch.nexPunitivePlayerShareLocalCache=true/' \
  > "$MARKET_SHARE_AGENT_CONFIG"
MARKET_SHARE_AGENT_CP="$RUNTIME_CP"
MARKET_SHARE_AGENT_ARGS=()
if [[ -f "$NEX_JAR" ]]; then
  MARKET_SHARE_AGENT_CP="$MARKET_SHARE_AGENT_CP:$NEX_JAR"
  MARKET_SHARE_AGENT_ARGS+=(nex)
fi
if [[ -f "$AOTD_JAR" ]]; then
  MARKET_SHARE_AGENT_CP="$MARKET_SHARE_AGENT_CP:$AOTD_JAR"
  MARKET_SHARE_AGENT_ARGS+=(aotd)
fi
java -Xverify:all \
  -Dstarsector.prepatcher.sessionOrigin=market-share-smoke \
  "-javaagent:$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar=config=$MARKET_SHARE_AGENT_CONFIG" \
  -cp "$MARKET_SHARE_AGENT_CP" \
  com.starsector.prepatcher.runtime.MarketShareActualAgentSmokeTest \
  "${MARKET_SHARE_AGENT_ARGS[@]}" \
  2>&1 | tee "$REPORT_DIR/market-share-actual-agent-smoke.txt"

# Exercise Local Resources legality and the owner-local econ-group index in isolation.
# The exact AoTDReachEconomy path is added when the maintained fork JAR is present.
ECONOMY_HOTPATH_AGENT_CONFIG="$BUILD/economy-hotpath-agent-smoke.properties"
sed -E \
  -e 's/^(patch\.[^=]+)=.*/\1=false/' \
  -e 's/^economy\.structureAuditMs=.*/economy.structureAuditMs=0/' \
  -e 's/^logging\.statsIntervalSeconds=.*/logging.statsIntervalSeconds=0/' \
  "$MOD_ROOT/prepatcher.properties" | \
  sed -E \
    -e 's/^patch\.localResourcesNoColdMarketData=false/patch.localResourcesNoColdMarketData=true/' \
    -e 's/^patch\.localResourcesTooltipSnapshot=false/patch.localResourcesTooltipSnapshot=true/' \
    -e 's/^patch\.economyGroupIndex=false/patch.economyGroupIndex=true/' \
  > "$ECONOMY_HOTPATH_AGENT_CONFIG"
ECONOMY_HOTPATH_AGENT_CP="$RUNTIME_CP"
ECONOMY_HOTPATH_AGENT_ARGS=()
if [[ -f "$AOTD_JAR" ]]; then
  ECONOMY_HOTPATH_AGENT_CP="$ECONOMY_HOTPATH_AGENT_CP:$AOTD_JAR"
  ECONOMY_HOTPATH_AGENT_ARGS+=(aotd)
fi
java -Xverify:all \
  -Dstarsector.prepatcher.sessionOrigin=economy-hotpath-smoke \
  "-javaagent:$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar=config=$ECONOMY_HOTPATH_AGENT_CONFIG" \
  -cp "$ECONOMY_HOTPATH_AGENT_CP" \
  com.starsector.prepatcher.runtime.EconomyHotpathActualAgentSmokeTest \
  "${ECONOMY_HOTPATH_AGENT_ARGS[@]}" \
  2>&1 | tee "$REPORT_DIR/economy-hotpath-actual-agent-smoke.txt"

# Exercise the active-set dependency contract with every other patch, including
# the standalone temp-mod switch, disabled. The transformer must still install
# its direct scheduler because the Market active set depends on it.
COMMODITY_SMOKE_CONFIG="$BUILD/commodity-temporal-agent-smoke.properties"
sed -E \
  -e 's/^(patch\.[^=]+)=.*/\1=false/' \
  -e 's/^commodity\.temporalAuditFrames=.*/commodity.temporalAuditFrames=7/' \
  -e 's/^logging\.statsIntervalSeconds=.*/logging.statsIntervalSeconds=0/' \
  "$MOD_ROOT/prepatcher.properties" | \
  sed -E 's/^patch\.commodityTemporalFastPath=false/patch.commodityTemporalFastPath=true/' \
  > "$COMMODITY_SMOKE_CONFIG"
java \
  "-javaagent:$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar=config=$COMMODITY_SMOKE_CONFIG" \
  -cp "$RUNTIME_CP" \
  com.starsector.prepatcher.runtime.CommodityTemporalAgentSmokeTest \
  2>&1 | tee "$REPORT_DIR/commodity-temporal-agent-smoke.txt"

# Exercise the direct dormant BaseIndustry wrapper in isolation. The test
# expects exactly two skipped callbacks between full vanilla audits.
MARKET_NOOP_SMOKE_CONFIG="$BUILD/market-noop-agent-smoke.properties"
sed -E \
  -e 's/^(patch\.[^=]+)=.*/\1=false/' \
  -e 's/^market\.noOpIndustryAuditFrames=.*/market.noOpIndustryAuditFrames=2/' \
  -e 's/^logging\.statsIntervalSeconds=.*/logging.statsIntervalSeconds=0/' \
  "$MOD_ROOT/prepatcher.properties" | \
  sed -E 's/^patch\.marketNoOpCallbacks=false/patch.marketNoOpCallbacks=true/' \
  > "$MARKET_NOOP_SMOKE_CONFIG"
java \
  "-javaagent:$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar=config=$MARKET_NOOP_SMOKE_CONFIG" \
  -cp "$RUNTIME_CP" \
  com.starsector.prepatcher.runtime.MarketNoOpActualAgentSmokeTest \
  2>&1 | tee "$REPORT_DIR/market-noop-actual-agent-smoke.txt"

java \
  --add-opens=java.base/java.util=ALL-UNNAMED \
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens=java.base/java.text=ALL-UNNAMED \
  --add-opens=java.desktop/java.awt.font=ALL-UNNAMED \
  --add-opens=java.desktop/java.awt=ALL-UNNAMED \
  -Dstarsector.prepatcher.sessionOrigin=temp-mod-xstream \
  "-javaagent:$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar" \
  -cp "$RUNTIME_CP" \
  com.starsector.prepatcher.runtime.TempModXStreamSaveSmokeTest \
  2>&1 | tee "$REPORT_DIR/temp-mod-xstream-save-smoke.txt"

java "${EXPORTS[@]}" \
  -Dstarsector.prepatcher.sessionOrigin=structural-hyperspace \
  -cp "$TEST_CLASSES:$TEST_CP" \
  com.starsector.prepatcher.agent.HyperspaceCompatibilityTest \
  "$VERIFICATION_CONFIG" "$CORE/starfarer_obf.jar" "$CORE/starfarer.api.jar" \
  "$REPORT_DIR/hyperspace-verification.txt"

java \
  -Dstarsector.prepatcher.sessionOrigin=startup-smoke \
  "-javaagent:$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar" \
  -version 2>&1 | tee "$REPORT_DIR/startup-smoke.txt"

FR_JAR="$CORE/fr.jar"
FR_SMOKE_REPORT="$REPORT_DIR/faster-rendering-loader-smoke.txt"
if [[ ! -f "$FR_JAR" ]]; then
  echo "SKIPPED Faster Rendering loader smoke: fr.jar not found at $FR_JAR" | \
    tee "$FR_SMOKE_REPORT"
else
  # Agent classes must not appear here. FR must keep the javaagent in its
  # JavaAgentLoader while the injected typed runtime lives in the custom
  # system loader, or this smoke would not cover the real split topology.
  FR_SMOKE_CP="$FR_JAR:$FR_SMOKE_CLASSES"
  FR_SMOKE_CP+=":$CORE/janino.jar:$CORE/commons-compiler.jar:$CORE/commons-compiler-jdk.jar"
  FR_SMOKE_CP+=":$CORE/starfarer.api.jar:$CORE/starfarer_obf.jar"
  FR_SMOKE_CP+=":$CORE/jogg-0.0.7.jar:$CORE/jorbis-0.0.15.jar:$CORE/json.jar"
  FR_SMOKE_CP+=":$CORE/lwjgl.jar:$CORE/jinput.jar:$CORE/log4j-1.2.9.jar:$CORE/lwjgl_util.jar"
  FR_SMOKE_CP+=":$CORE/fs.sound_obf.jar:$CORE/fs.common_obf.jar:$CORE/xstream-1.4.10.jar"
  FR_SMOKE_CP+=":$CORE/txw2-3.0.2.jar:$CORE/jaxb-api-2.4.0-b180830.0359.jar:$CORE/webp-imageio-0.1.6.jar"
  java \
    -Djava.system.class.loader=com.genir.renderer.loaders.AppClassLoader \
    -Dstarsector.prepatcher.sessionOrigin=fr-smoke \
    "-javaagent:$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar=config=$VERIFICATION_CONFIG" \
    -cp "$FR_SMOKE_CP" \
    com.starsector.prepatcher.fr.FasterRenderingLoaderSmokeTest \
    "$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar" \
    2>&1 | tee "$FR_SMOKE_REPORT"
fi

echo 'Documentation/structural/runtime/hyperspace/startup/FR verification completed.'
