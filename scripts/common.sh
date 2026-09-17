#!/usr/bin/env bash
# Shared by source builds and verification. Does not change the caller's cwd.
find_game_root() {
  local candidate
  candidate="$(cd -- "$1" && pwd -P)" || return 1
  while :; do
    if [[ "${candidate##*/}" == starsector-core && -f "$candidate/starfarer.api.jar" && -f "$candidate/starfarer_obf.jar" ]]; then
      candidate="$(dirname -- "$candidate")"
      continue
    fi
    if [[ -f "$candidate/starsector-core/starfarer.api.jar" && -f "$candidate/starsector-core/starfarer_obf.jar" ]] ||
       [[ -f "$candidate/starfarer.api.jar" && -f "$candidate/starfarer_obf.jar" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
    [[ "$candidate" != / ]] || break
    candidate="$(dirname -- "$candidate")"
  done
  return 1
}
GAME_ROOT="$(find_game_root "${STARSECTOR_ROOT:-$MOD_ROOT}")" || {
  echo 'Cannot find Starsector JARs. Set STARSECTOR_ROOT to the game installation.' >&2
  return 1
}
if [[ -f "$GAME_ROOT/starsector-core/starfarer.api.jar" ]]; then
  CORE="$GAME_ROOT/starsector-core"
else
  CORE="$GAME_ROOT"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  [[ -x "$JAVA_HOME/bin/javac" && -x "$JAVA_HOME/bin/jar" && -x "$JAVA_HOME/bin/java" ]] || {
    echo 'JAVA_HOME must point to a JDK (java, javac and jar required).' >&2
    return 1
  }
  export PATH="$JAVA_HOME/bin:$PATH"
fi
for tool in java javac jar; do
  command -v "$tool" >/dev/null || { echo "Missing JDK tool: $tool" >&2; return 1; }
done
BUILD="$MOD_ROOT/.build"
export GAME_ROOT CORE BUILD
[[ ! -L "$BUILD" ]] || { echo 'Refusing a symlinked .build directory.' >&2; return 1; }

clean_build_directory() {
  local name="$1" target
  case "$name" in
    agent-raw-classes|asm-raw-classes|agent-classes|build-classes|bootstrap-classes|test-classes|fr-smoke-classes) ;;
    *) echo "Unexpected build directory: $name" >&2; return 1 ;;
  esac
  target="$BUILD/$name"
  [[ ! -L "$target" ]] || { echo "Refusing a symlinked build target: $target" >&2; return 1; }
  rm -rf -- "$target"
  mkdir -p -- "$target"
}
