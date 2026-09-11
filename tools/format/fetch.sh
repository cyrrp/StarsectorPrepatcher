#!/usr/bin/env bash
# Fetch the Eclipse JDT formatter jars into .build/format/lib, verifying every download against
# SHA256SUMS.txt. These are developer tools only: they are never shipped with the mod, so they are
# downloaded on demand instead of being committed. Re-running is cheap; files already present with
# the right checksum are left alone.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
mod_root="$(cd "$here/../.." && pwd)"
lib="$mod_root/.toolcache/format/lib"
base="${FORMATTER_MAVEN_BASE:-https://repo1.maven.org/maven2}"

if command -v sha256sum >/dev/null 2>&1; then
  sum() { sha256sum "$1" | cut -d' ' -f1; }
elif command -v shasum >/dev/null 2>&1; then
  sum() { shasum -a 256 "$1" | cut -d' ' -f1; }
else
  echo "Neither sha256sum nor shasum is available; cannot verify downloads." >&2
  exit 1
fi

expected_for() {
  awk -v name="$1" '$2 == name { print $1 }' "$here/SHA256SUMS.txt"
}

mkdir -p "$lib"
missing=0
while read -r name path; do
  case "$name" in ''|'#'*) continue ;; esac
  expected="$(expected_for "$name")"
  if [[ -z "$expected" ]]; then
    echo "No checksum recorded for $name in SHA256SUMS.txt; refusing to fetch it." >&2
    exit 1
  fi
  target="$lib/$name"
  if [[ -f "$target" && "$(sum "$target")" == "$expected" ]]; then
    continue
  fi
  echo "fetching $name"
  tmp="$target.part"
  curl -fsSL --retry 3 -o "$tmp" "$base/$path"
  actual="$(sum "$tmp")"
  if [[ "$actual" != "$expected" ]]; then
    rm -f "$tmp"
    echo "Checksum mismatch for $name" >&2
    echo "  expected $expected" >&2
    echo "  actual   $actual" >&2
    exit 1
  fi
  mv "$tmp" "$target"
  missing=$((missing + 1))
done < "$here/artifacts.txt"

echo "formatter libraries ready in $lib ($missing fetched)"
