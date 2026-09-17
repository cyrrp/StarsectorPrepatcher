#!/usr/bin/env bash
set -euo pipefail
MOD_ROOT="$(cd -- "$(dirname -- "$0")/.." && pwd)"
source "$MOD_ROOT/scripts/common.sh"
fixture="$(mktemp -d /tmp/prepatcher-shell.XXXXXX)"
cleanup() {
  case "$fixture" in /tmp/prepatcher-shell.*) rm -rf -- "$fixture" ;; *) return 1 ;; esac
}
trap cleanup EXIT
flat="$fixture/flat game"
split="$fixture/windows layout"
mkdir -p "$flat/mods/Test Mod/scripts" "$flat/mods/Test Mod/agent" "$split/starsector-core"
touch "$flat/starfarer.api.jar" "$flat/starfarer_obf.jar" "$split/starsector-core/starfarer.api.jar" "$split/starsector-core/starfarer_obf.jar"
[[ "$(find_game_root "$flat/mods/Test Mod")" == "$flat" ]]
[[ "$(find_game_root "$split/starsector-core")" == "$split" ]]
cp "$MOD_ROOT/StarsectorPrepatcher.sh" "$flat/mods/Test Mod/"
cp "$MOD_ROOT/scripts/common.sh" "$flat/mods/Test Mod/scripts/"
cp "$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar" "$flat/mods/Test Mod/agent/"
# Real Java argument parsing of an agent path with spaces, but no game/GUI startup.
printf '%s\n' '-classpath .' '-version' > "$flat/Miko_Simple.txt"
before="$(sha256sum "$flat/Miko_Simple.txt")"
STARSECTOR_ROOT="$flat" bash "$flat/mods/Test Mod/StarsectorPrepatcher.sh" --check
STARSECTOR_ROOT="$flat" bash "$flat/mods/Test Mod/StarsectorPrepatcher.sh" > "$fixture/java-spaces.log" 2>&1
[[ "$before" == "$(sha256sum "$flat/Miko_Simple.txt")" ]]
printf '%s\n' '-classpath .' '-javaagent:later.jar' '-version' > "$flat/Miko_Simple.txt"
if STARSECTOR_ROOT="$flat" bash "$flat/mods/Test Mod/StarsectorPrepatcher.sh" --check > "$fixture/rejected.log" 2>&1; then
  echo 'Launcher accepted an agent after its insertion point' >&2; exit 1
fi
mkdir -p "$fixture/outside"
ln -s "$fixture/outside" "$flat/mods/Test Mod/.build"
if STARSECTOR_ROOT="$flat" bash "$flat/mods/Test Mod/StarsectorPrepatcher.sh" --check > "$fixture/symlink.log" 2>&1; then
  echo 'Accepted symlinked build directory' >&2; exit 1
fi
echo 'OK shell flat/split roots, spaces, real Java argument parsing, unchanged config, agent ordering, symlink refusal'
