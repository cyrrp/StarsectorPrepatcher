#!/usr/bin/env bash
# format.sh — check or apply the repo's Java style via Eclipse JDT.
#   tools/format/format.sh check  [path ...]   exit 1 if any file would change
#   tools/format/format.sh apply  [path ...]   rewrite files in place
# Options: --config <file> --java-home <jdk dir>. Default path: source/
# Exit codes: 0 clean/applied, 1 differences found (check), 2 usage/tool error.
# Jars come from .build/format/lib, populated by fetch.sh; nothing is downloaded here. The driver is
# compiled with the local JDK into .build/format/classes (cached).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
action="" ; config="$here/eclipse-jdt-format.properties" ; javahome="${JAVA_HOME:-}"
paths=()
while [ $# -gt 0 ]; do
  case "$1" in
    check|apply) action="$1" ;;
    --config) config="$2" ; shift ;;
    --java-home) javahome="$2" ; shift ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \?//' ; exit 0 ;;
    -*) echo "unknown option: $1" >&2 ; exit 2 ;;
    *) paths+=("$1") ;;
  esac
  shift
done
[ -z "$action" ] && { echo "usage: format.sh check|apply [path ...]" >&2 ; exit 2 ; }
[ ${#paths[@]} -eq 0 ] && paths=("$root/source")

find_jdk() {
  if [ -n "$javahome" ] && [ -x "$javahome/bin/java" ]; then return; fi
  if [ -n "$javahome" ] && [ -x "$javahome/bin/java.exe" ]; then return; fi
  if command -v java >/dev/null && command -v javac >/dev/null; then javahome="" ; return; fi
  echo "No JDK found: set JAVA_HOME or --java-home (JDK 17+ required)" >&2 ; exit 2
}
find_jdk
java_bin="${javahome:+$(cygpath -u "$javahome" 2>/dev/null || echo "$javahome")/bin/}java"
javac_bin="${javahome:+$(cygpath -u "$javahome" 2>/dev/null || echo "$javahome")/bin/}javac"

# Windows JDKs need Windows-style classpaths; convert when cygpath exists.
win() { command -v cygpath >/dev/null && cygpath -w "$1" || echo "$1"; }
sep=":" ; command -v cygpath >/dev/null && sep=";"
# Formatter jars are developer-only tooling, so they are fetched on demand rather than committed.
lib="$root/.build/format/lib"
if [ ! -d "$lib" ] || [ -z "$(ls -A "$lib" 2>/dev/null)" ]; then
  echo "Formatter libraries are missing. Run tools/format/fetch.sh first." >&2
  exit 2
fi
cp="$(win "$lib")\\*"
out="$root/.build/format/classes"
stamp="$out/JdtFormat.class"
if [ ! -f "$stamp" ] || [ "$here/JdtFormat.java" -nt "$stamp" ]; then
  mkdir -p "$out"
  "$javac_bin" -cp "$cp" -d "$(win "$out")" "$(win "$here/JdtFormat.java")" || exit 2
fi
wpaths=()
for p in "${paths[@]}"; do wpaths+=("$(win "$p")"); done
"$java_bin" -cp "$cp$sep$(win "$out")" JdtFormat --config "$(win "$config")" "--$action" "${wpaths[@]}"
