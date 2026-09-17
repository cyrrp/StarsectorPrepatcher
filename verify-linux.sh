#!/usr/bin/env bash
set -euo pipefail
(( $# == 0 )) || { echo 'Set STARSECTOR_ROOT to select Linux JARs; positional JAR overrides are not supported here.' >&2; exit 2; }
MOD_ROOT="$(cd -- "$(dirname -- "$0")" && pwd)"
source "$MOD_ROOT/scripts/common.sh"
bash "$MOD_ROOT/build-agent.sh"
bash "$MOD_ROOT/scripts/test-shell.sh"
if command -v shellcheck >/dev/null; then
  (cd "$MOD_ROOT" && shellcheck -x build-agent.sh verify-structural.sh verify-linux.sh release.sh StarsectorPrepatcher.sh scripts/common.sh scripts/test-shell.sh)
else
  echo 'SKIPPED optional ShellCheck: executable not installed.'
fi
CANONICAL="$BUILD/canonical-core"
[[ -f "$CANONICAL/starfarer_obf.jar" ]] || { echo 'verify-linux.sh requires native Linux game JARs.' >&2; exit 1; }
mkdir -p "$BUILD/reports" "$BUILD/linux-test-classes"
TESTS="$BUILD/linux-test-classes"
LIBS="$MOD_ROOT/lib/*"
CANONICAL_CP="$TESTS:$BUILD/agent-raw-classes:$LIBS:$CANONICAL/*:$CORE/*"
NATIVE_CP="$TESTS:$BUILD/agent-raw-classes:$LIBS:$CORE/*"
find "$MOD_ROOT/source/test" -name '*.java' -print0 | xargs -0 javac -encoding UTF-8 --release 17 -cp "$CANONICAL_CP" -d "$TESTS"
java -cp "$TESTS" com.starsector.prepatcher.docs.DocumentationConsistencyTest "$MOD_ROOT" \
  2>&1 | tee "$BUILD/reports/linux-documentation-consistency.txt"
java -cp "$NATIVE_CP" com.starsector.prepatcher.agent.PlatformNamespaceIsolationTest \
  2>&1 | tee "$BUILD/reports/linux-platform-isolation.txt"
CONFIG="$BUILD/linux-all-enabled.properties"
{
  sed -e 's/^patch\.loadingTextReader=.*/patch.loadingTextReader=true/' \
      -e 's/^patch\.startupLogAggregation=.*/patch.startupLogAggregation=true/' "$MOD_ROOT/profiles/aggressive.properties"
  printf '\npatch.directMarketObservation=true\nlogging.statsIntervalSeconds=1\n'
} > "$CONFIG"
REFERENCE_JAVA="${REFERENCE_JAVA:-$CORE/jre_linux/bin/java}"
[[ -x "$REFERENCE_JAVA" ]] || { echo 'Set REFERENCE_JAVA to Java 17 for canonical bytecode regression tests.' >&2; exit 1; }
run_reference() {
  local test="$1"; shift
  "$REFERENCE_JAVA" -Xverify:none -cp "$CANONICAL_CP" "com.starsector.prepatcher.agent.$test" "$@" 2>&1 | tee "$BUILD/reports/linux-$test.txt"
}
run_reference CoreWorldsStructuralMatcherTest "$CONFIG" "$CANONICAL/starfarer.api.jar" "$CANONICAL/starfarer_obf.jar"
run_reference FastForwardPresentationCompatibilityTest "$CANONICAL/starfarer_obf.jar" "$CANONICAL/starfarer.api.jar"
run_reference StructuralCompatibilityTest "$CONFIG" "$CANONICAL/starfarer_obf.jar" "$CANONICAL/fs.common_obf.jar" "$CANONICAL/fs.sound_obf.jar" "$CANONICAL/starfarer.api.jar"
run_reference UiMutationRepairedNamePipelineTest "$CANONICAL/starfarer_obf.jar"
AOTD_JAR="${AOTD_TOOLBOX_JAR:-$GAME_ROOT/mods/AoTD-Theory-Of-Toolbox-Scheduler-Fork/jars/AoTDToolboxTheory.jar}"
if [[ -f "$AOTD_JAR" ]]; then
  for test in MarketShareAoTDForkCompatibilityTest EconomyHotpathAoTDForkCompatibilityTest AoTDForkCompatibilityTransformerTest AoTDSchedulerBridgeTransformerTest AoTDUIEconomyBehaviorCompatibilityTest; do
    run_reference "$test" "$AOTD_JAR"
  done
  run_reference AoTDForkMarkerScannerTest "$(dirname -- "$(dirname -- "$AOTD_JAR")")"
else
  echo "AoTD integration not exercised: no fork JAR at $AOTD_JAR"
fi
java -cp "$NATIVE_CP" com.starsector.prepatcher.agent.LinuxNamespaceCompatibilityTest "$CONFIG" \
  "$CORE/starfarer_obf.jar" "$CORE/fs.common_obf.jar" "$CORE/fs.sound_obf.jar" "$CORE/starfarer.api.jar" \
  2>&1 | tee "$BUILD/reports/linux-native-pipeline.txt"
# Real JVM agent route, using exactly the installed Mikohime/FR argument chain.
[[ -f "$CORE/Miko_Simple.txt" ]] || { echo 'Miko_Simple.txt required for Java 28 + FR smoke.' >&2; exit 1; }
for test in com.starsector.prepatcher.agent.JavaCompatibilityRouteSmokeMain com.starsector.prepatcher.runtime.CoreWorldsActualAgentSmokeTest com.starsector.prepatcher.runtime.LinuxRuntimeLinkageSmokeTest com.starsector.prepatcher.runtime.PlatformXmlActualAgentSmokeTest; do
  args_file="$BUILD/linux-${test##*.}.args"
  awk -v agent="$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar" -v tests="$TESTS" -v main="$test" '
    /^-Xms/ { print "-Xms128m"; next }
    /^-Xmx/ { print "-Xmx1024m"; next }
    /^-javaagent:/ && /StarsectorPrepatcherAgent[.]jar/ { next }
    /^-classpath / {
      print "\"-javaagent:" agent "\""
      sub(/^-classpath /, ""); sub(/^"/, ""); sub(/"$/, "")
      print "-classpath \"" tests ":" $0 "\""; next
    }
    /^com[.]fs[.]starfarer[.]StarfarerLauncher$/ { print main; next }
    { print }
  ' "$CORE/Miko_Simple.txt" > "$args_file"
  (cd "$CORE" && java "@$args_file" "$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar" "$BUILD/linux-native-targets.txt") 2>&1 | tee "$BUILD/reports/linux-${test##*.}.txt"
  if grep -q 'Atomic transformer pipeline rolled back' "$BUILD/reports/linux-${test##*.}.txt"; then
    echo "Unexpected pipeline rollback in $test" >&2
    exit 1
  fi
done
if [[ -f "$AOTD_JAR" ]]; then
  # Do not put test fixtures (especially their stub AoTDEconomy) in the game parent loader.
  SMOKE="$BUILD/linux-aotd-smoke-classes"
  mkdir -p "$SMOKE"
  javac --release 17 -d "$SMOKE" "$MOD_ROOT/source/test/com/starsector/prepatcher/runtime/LinuxAoTDIntegrationSmokeTest.java"
  args_file="$BUILD/linux-LinuxAoTDIntegrationSmokeTest.args"
  sed -e "s#${TESTS}:#${SMOKE}:#" \
      -e 's/com.starsector.prepatcher.runtime.PlatformXmlActualAgentSmokeTest/com.starsector.prepatcher.runtime.LinuxAoTDIntegrationSmokeTest/' \
      "$BUILD/linux-PlatformXmlActualAgentSmokeTest.args" > "$args_file"
  (cd "$CORE" && java "@$args_file" "$AOTD_JAR") \
    2>&1 | tee "$BUILD/reports/linux-LinuxAoTDIntegrationSmokeTest.txt"
fi
echo 'Linux canonical regression, native pipeline and actual Java/FR agent checks passed.'
