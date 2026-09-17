#!/usr/bin/env bash
set -euo pipefail
MOD_ROOT="$(cd -- "$(dirname -- "$0")" && pwd)"
source "$MOD_ROOT/scripts/common.sh"
ARGS_SOURCE="${PREPATCHER_ARGS:-$CORE/Miko_Simple.txt}"
AGENT="$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar"
[[ -s "$ARGS_SOURCE" && -s "$AGENT" ]] || {
  echo 'Missing agent or argument file. Set PREPATCHER_ARGS to a Java @argument file.' >&2; exit 1;
}
case "$AGENT" in *'"'*|*\\*|*$'\n'*) echo 'Unsupported quote/backslash/newline in agent path.' >&2; exit 1 ;; esac
mkdir -p "$MOD_ROOT/logs"
ARGS_FILE="$(mktemp "$MOD_ROOT/logs/launch.XXXXXX.args")"
trap 'rm -f -- "$ARGS_FILE"' EXIT
# Prepatcher follows every existing agent. Generated Mikohime settings stay untouched.
awk -v agent="$AGENT" '
  /^-javaagent:/ && /StarsectorPrepatcherAgent[.]jar/ { next }
  /^"-javaagent:/ && /StarsectorPrepatcherAgent[.]jar/ { next }
  /^"?-javaagent:/ && inserted { exit 2 }
  /^-classpath / { print "\"-javaagent:" agent "\""; inserted++ }
  { print }
  END { if (inserted != 1) exit 1 }
' "$ARGS_SOURCE" > "$ARGS_FILE" || { echo 'Expected exactly one -classpath line in the argument file.' >&2; exit 1; }
if [[ "${1:-}" == --check ]]; then echo 'OK argument file: Prepatcher appended after existing agents'; exit 0; fi
cd -- "$CORE"
java "@$ARGS_FILE" "$@"
