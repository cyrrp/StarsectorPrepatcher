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

TEST_CP="$AGENT_CLASSES:$CORE/starfarer.api.jar:$CORE/starfarer_obf.jar:$CORE/fs.common_obf.jar:$CORE/fs.sound_obf.jar:$CORE/lwjgl.jar:$CORE/lwjgl_util.jar:$CORE/xstream-1.4.10.jar:$CORE/jaxb-api-2.4.0-b180830.0359.jar"
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
  "$VERIFICATION_CONFIG" "$CORE/starfarer.api.jar" \
  2>&1 | tee "$REPORT_DIR/core-worlds-structural-matcher.txt"

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

java \
  "-javaagent:$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar=config=$MOD_ROOT/profiles/aggressive.properties" \
  -cp "$RUNTIME_CP" \
  com.starsector.prepatcher.runtime.CoreWorldsActualAgentSmokeTest \
  2>&1 | tee "$REPORT_DIR/core-worlds-actual-agent.txt"

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
