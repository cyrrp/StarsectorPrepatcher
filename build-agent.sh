#!/usr/bin/env bash
set -euo pipefail
MOD_ROOT="$(cd "$(dirname "$0")" && pwd)"
find_game_root() {
  local candidate="$1"
  while [[ "$candidate" != "/" ]]; do
    if [[ -d "$candidate/starsector-core" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
    candidate="$(dirname "$candidate")"
  done
  return 1
}
GAME_ROOT="$(find_game_root "$MOD_ROOT")" || {
  echo "Could not find a Starsector root above $MOD_ROOT." >&2
  exit 1
}
CORE="$GAME_ROOT/starsector-core"
BUILD="$MOD_ROOT/.build"
rm -rf "$BUILD"
mkdir -p "$BUILD/agent-classes" "$BUILD/bootstrap-classes" "$MOD_ROOT/agent" "$MOD_ROOT/jars"
CP_AGENT="$CORE/starfarer.api.jar:$CORE/starfarer_obf.jar:$CORE/fs.common_obf.jar:$CORE/fs.sound_obf.jar:$CORE/lwjgl.jar:$CORE/lwjgl_util.jar"
CP_BOOT="$CORE/starfarer.api.jar:$CORE/starfarer_obf.jar:$CORE/log4j-1.2.9.jar"
EXPORTS=(
  --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED
  --add-exports java.base/jdk.internal.org.objectweb.asm.tree=ALL-UNNAMED
  --add-exports java.base/jdk.internal.org.objectweb.asm.tree.analysis=ALL-UNNAMED
)
find "$MOD_ROOT/source/agent" -name '*.java' -print0 | xargs -0 javac -encoding UTF-8 -source 17 -target 17 "${EXPORTS[@]}" -cp "$CP_AGENT" -d "$BUILD/agent-classes"
find "$MOD_ROOT/source/bootstrap" -name '*.java' -print0 | xargs -0 javac -encoding UTF-8 -source 17 -target 17 -cp "$CP_BOOT" -d "$BUILD/bootstrap-classes"
printf '%s\n' \
  'Manifest-Version: 1.0' \
  'Implementation-Title: StarsectorPrepatcher Agent' \
  'Implementation-Version: 0.18.3' \
  'Premain-Class: com.starsector.prepatcher.agent.PrepatcherAgent' \
  'Can-Redefine-Classes: false' \
  'Can-Retransform-Classes: false' '' > "$BUILD/agent.mf"
printf '%s\n' \
  'Manifest-Version: 1.0' \
  'Implementation-Title: StarsectorPrepatcher Bootstrap' \
  'Implementation-Version: 0.18.3' '' > "$BUILD/bootstrap.mf"
jar cfm "$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar" "$BUILD/agent.mf" -C "$BUILD/agent-classes" .
jar cfm "$MOD_ROOT/jars/StarsectorPrepatcherBootstrap.jar" "$BUILD/bootstrap.mf" -C "$BUILD/bootstrap-classes" .

# RuntimeInstaller reads these classfiles as bytes from the agent JAR and
# defines them in the target game loader. Keep them as normal class entries,
# but never link to them from the agent control-loader classes.
AGENT_ENTRIES="$BUILD/agent-entries.txt"
jar tf "$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar" > "$AGENT_ENTRIES"
REQUIRED_RUNTIME_PAYLOAD=(
  'com/fs/starfarer/api/StarsectorPrepatcherHooks.class'
  'com/fs/starfarer/api/StarsectorPrepatcherHyperspaceHooks.class'
  'com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge.class'
  'com/fs/starfarer/api/StarsectorPrepatcherCoreWorldsRuntime.class'
  'com/fs/starfarer/api/StarsectorPrepatcherTempModHooks.class'
  'com/fs/starfarer/api/StarsectorPrepatcherPresentationHooks.class'
  'com/fs/starfarer/api/StarsectorPrepatcherStrategicJumpIndex.class'
  'com/fs/starfarer/api/StarsectorPrepatcherMarketShareRuntime.class'
  'com/fs/starfarer/api/StarsectorPrepatcherEconomyHotpathRuntime.class'
)
for payload_entry in "${REQUIRED_RUNTIME_PAYLOAD[@]}"; do
  if ! grep -Fqx "$payload_entry" "$AGENT_ENTRIES"; then
    echo "Required target-loader runtime payload is missing from the agent JAR: $payload_entry" >&2
    exit 1
  fi
done
EXPECTED_RUNTIME_PAYLOAD_COUNT=113
runtime_payload_count="$(grep -Ec '^com/fs/starfarer/api/StarsectorPrepatcher[^/]*\.class$' "$AGENT_ENTRIES" || true)"
if [[ "$runtime_payload_count" -ne "$EXPECTED_RUNTIME_PAYLOAD_COUNT" ]]; then
  echo "Target-loader runtime payload inventory changed: expected $EXPECTED_RUNTIME_PAYLOAD_COUNT class entries, found $runtime_payload_count." >&2
  exit 1
fi

# Keep the release manifest synchronized with the exact tree produced by this build.
# Runtime logs, build intermediates and SHA256SUMS.txt itself are intentionally excluded.
CHECKSUM_INPUTS="$BUILD/checksum-inputs.txt"
CHECKSUM_OUTPUT="$BUILD/SHA256SUMS.txt"
if [[ ! -f "$MOD_ROOT/logs/README.txt" ]]; then
  echo 'Required checksum input logs/README.txt is missing.' >&2
  exit 1
fi
(
  cd "$MOD_ROOT"
  shopt -s nullglob
  for top_level in * .[!.]* ..?*; do
    if [[ -f "$top_level" && "$top_level" != 'SHA256SUMS.txt' ]]; then
      printf '%s\n' "$top_level"
    fi
  done
  find agent baseline docs jars media profiles source -type f -print
  printf '%s\n' 'logs/README.txt'
) | sed 's#^\./##' | LC_ALL=C sort -u > "$CHECKSUM_INPUTS"

if command -v sha256sum >/dev/null 2>&1; then
  checksum_file() { sha256sum "$1"; }
elif command -v shasum >/dev/null 2>&1; then
  checksum_file() { shasum -a 256 "$1"; }
else
  echo 'Neither sha256sum nor shasum is available.' >&2
  exit 1
fi

: > "$CHECKSUM_OUTPUT"
while IFS= read -r relative_path; do
  checksum_line="$(checksum_file "$MOD_ROOT/$relative_path")"
  digest="${checksum_line%% *}"
  printf '%s  %s\n' "$digest" "$relative_path" >> "$CHECKSUM_OUTPUT"
done < "$CHECKSUM_INPUTS"
mv "$CHECKSUM_OUTPUT" "$MOD_ROOT/SHA256SUMS.txt"

echo 'Build and checksum manifest completed.'
