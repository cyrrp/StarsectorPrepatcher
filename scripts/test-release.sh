#!/usr/bin/env bash
set -euo pipefail
MOD_ROOT="$(cd -- "$(dirname -- "$0")/.." && pwd)"
fixture="$(mktemp -d /tmp/prepatcher-release.XXXXXX)"
cleanup() {
  case "$fixture" in /tmp/prepatcher-release.*) rm -rf -- "$fixture" ;; *) return 1 ;; esac
}
trap cleanup EXIT
release_fixture="$fixture/release game/mods/StarsectorPrepatcher"
mkdir -p "$release_fixture/agent" "$release_fixture/jars" "$release_fixture/docs/releases" "$release_fixture/.build"
cp "$MOD_ROOT/release.sh" "$release_fixture/"
printf '{"version": "1.2.3"}\n' > "$release_fixture/mod_info.json"
printf '## [1.2.3]\n' > "$release_fixture/CHANGELOG.md"
printf 'Release fixture\n' > "$release_fixture/docs/releases/1.2.3.md"
printf 'Fixture notes\n' > "$release_fixture/.build/release-notes.md"
printf 'agent fixture\n' > "$release_fixture/agent/StarsectorPrepatcherAgent.jar"
printf 'bootstrap fixture\n' > "$release_fixture/jars/StarsectorPrepatcherBootstrap.jar"
mkdir -p "$release_fixture/profiles" "$release_fixture/logs" "$release_fixture/scripts" "$release_fixture/source"
for file in LICENSE README.md README_RU.md prepatcher.properties StarsectorPrepatcher.bat StarsectorPrepatcher.sh install-agent.ps1 uninstall-agent.ps1 profiles/aggressive.properties profiles/debug.properties profiles/safe.properties logs/README.txt scripts/common.sh; do
  printf 'player fixture\n' > "$release_fixture/$file"
done
printf 'developer fixture\n' > "$release_fixture/build-agent.ps1"
printf 'source fixture\n' > "$release_fixture/source/Test.java"
(cd "$release_fixture" && find . -type f ! -path './.build/*' ! -name SHA256SUMS.txt -print0 | sort -z | xargs -0 sha256sum --text > SHA256SUMS.txt)
bash "$release_fixture/release.sh" --dry-run > "$fixture/release-valid.log" 2>&1 \
  || { cat "$fixture/release-valid.log" >&2; exit 1; }
[[ -s "$release_fixture/.build/StarsectorPrepatcher-1.2.3.zip" ]]
jar tf "$release_fixture/.build/StarsectorPrepatcher-1.2.3.zip" > "$fixture/release-entries.txt"
grep -qx 'StarsectorPrepatcher/install-agent.ps1' "$fixture/release-entries.txt"
grep -qx 'StarsectorPrepatcher/SHA256SUMS.txt' "$fixture/release-entries.txt"
if grep -Eq '/(build-agent.ps1|source/|docs/|release.sh)' "$fixture/release-entries.txt"; then
  echo 'Developer files leaked into the player archive' >&2; exit 1
fi
mkdir "$fixture/extracted"
(cd "$fixture/extracted" && jar xf "$release_fixture/.build/StarsectorPrepatcher-1.2.3.zip")
(cd "$fixture/extracted/StarsectorPrepatcher" && sha256sum --check --quiet SHA256SUMS.txt)
[[ "$(wc -l < "$fixture/extracted/StarsectorPrepatcher/SHA256SUMS.txt")" -eq 16 ]]
printf 'changed after build\n' >> "$release_fixture/agent/StarsectorPrepatcherAgent.jar"
if bash "$release_fixture/release.sh" --dry-run > "$fixture/release-stale.log" 2>&1; then
  echo 'Release accepted stale checksums' >&2; exit 1
fi
grep -q 'Release inputs differ from SHA256SUMS.txt' "$fixture/release-stale.log"
[[ ! -e "$release_fixture/.build/StarsectorPrepatcher-1.2.3.zip" ]]
echo 'OK release packages verified inputs and rejects files changed after build'
