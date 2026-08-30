#!/usr/bin/env bash
set -euo pipefail

MOD_ROOT="$(cd "$(dirname "$0")" && pwd)"
BUILD="$MOD_ROOT/.build"
DRY_RUN=false
NOTES_FILE="$BUILD/release-notes.md"

FORCE=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true; shift ;;
    --force) FORCE=true; shift ;;
    *) NOTES_FILE="$1"; shift ;;
  esac
done

die() { echo "ERROR: $*" >&2; exit 1; }

MOD_NAME=$(basename "$MOD_ROOT")
[[ "$MOD_NAME" == "StarsectorPrepatcher" ]] \
  || die "Mod directory must be 'StarsectorPrepatcher', found '$MOD_NAME'."

VERSION=$(grep '"version"' "$MOD_ROOT/mod_info.json" | sed 's/.*"version": *"//;s/".*//')
TAG="v${VERSION}"
ZIP_NAME="StarsectorPrepatcher-${VERSION}.zip"
SHA_NAME="${ZIP_NAME}.sha256"
RELEASE_REPORT="$MOD_ROOT/docs/releases/${VERSION}.md"

[[ -f "$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar" ]] \
  || die "Agent JAR missing. Run build-agent.sh first."
[[ -f "$MOD_ROOT/jars/StarsectorPrepatcherBootstrap.jar" ]] \
  || die "Bootstrap JAR missing. Run build-agent.sh first."
[[ -f "$MOD_ROOT/SHA256SUMS.txt" ]] \
  || die "SHA256SUMS.txt missing. Run build-agent.sh first."
[[ -f "$NOTES_FILE" ]] \
  || die "Release notes file not found: $NOTES_FILE"
[[ -f "$RELEASE_REPORT" ]] \
  || die "Release report not found: $RELEASE_REPORT"
grep -q "## \[$VERSION\]" "$MOD_ROOT/CHANGELOG.md" \
  || die "CHANGELOG.md has no section for $VERSION."
command -v gh >/dev/null 2>&1 || die "gh CLI is not installed."
command -v jar >/dev/null 2>&1 || die "jar is not installed (JDK required)."
git -C "$MOD_ROOT" rev-parse "$TAG" >/dev/null 2>&1 \
  && die "Tag $TAG already exists." || true
[[ -z "$(git -C "$MOD_ROOT" status --porcelain)" ]] || $FORCE \
  || die "Git working tree is not clean. Commit all changes or use --force."

mkdir -p "$BUILD"
ZIP_PATH="$BUILD/$ZIP_NAME"
SHA_PATH="$BUILD/$SHA_NAME"
BODY_PATH="$BUILD/release-body.md"
rm -f "$ZIP_PATH" "$SHA_PATH" "$BODY_PATH"

INCLUDE_LIST="$BUILD/zip-include.txt"
awk '{print $NF}' "$MOD_ROOT/SHA256SUMS.txt" > "$INCLUDE_LIST"
printf 'SHA256SUMS.txt\n' >> "$INCLUDE_LIST"
LC_ALL=C sort -u -o "$INCLUDE_LIST" "$INCLUDE_LIST"

ENTRY_ARGS=()
while IFS= read -r f; do
  ENTRY_ARGS+=("${MOD_NAME}/${f}")
done < "$INCLUDE_LIST"

(cd "$MOD_ROOT/.." && jar cMf "$ZIP_PATH" "${ENTRY_ARGS[@]}")

if command -v sha256sum >/dev/null 2>&1; then
  ZIP_SHA256=$(sha256sum "$ZIP_PATH" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then
  ZIP_SHA256=$(shasum -a 256 "$ZIP_PATH" | awk '{print $1}')
else
  die "Neither sha256sum nor shasum is available."
fi

printf '%s  %s\n' "$ZIP_SHA256" "$ZIP_NAME" > "$SHA_PATH"

# The notes file is a full release-body template. It may contain placeholders
# {{ZIP_NAME}} and {{ZIP_SHA256}}, which are substituted with the actual values.
# When no placeholder is present, a standard Packaging section is appended.
if grep -q '{{ZIP_NAME}}\|{{ZIP_SHA256}}' "$NOTES_FILE"; then
  sed -e "s#{{ZIP_NAME}}#$ZIP_NAME#g" \
      -e "s#{{ZIP_SHA256}}#$ZIP_SHA256#g" \
      "$NOTES_FILE" > "$BODY_PATH"
else
  {
    cat "$NOTES_FILE"
    printf '\n## Packaging\n\n'
    printf 'Agent and bootstrap JARs have been rebuilt using the standard release workflow, and `SHA256SUMS.txt` has been regenerated.\n\n'
    printf 'GitHub release artifact:\n\n'
    printf '`%s`\n\n' "$ZIP_NAME"
    printf 'SHA-256:\n\n'
    printf '```\n%s\n```\n' "$ZIP_SHA256"
  } > "$BODY_PATH"
fi

echo "Version:  $VERSION"
echo "Tag:      $TAG"
echo "ZIP:      $ZIP_PATH"
echo "SHA-256:  $ZIP_SHA256"
echo "Body:     $BODY_PATH"
echo "Files:    $(wc -l < "$INCLUDE_LIST") entries"

if $DRY_RUN; then
  echo
  echo "======== Release body ========"
  cat "$BODY_PATH"
  echo "======== End body ========"
  echo
  echo "Dry run — no release created."
  exit 0
fi

CURRENT_COMMIT=$(git -C "$MOD_ROOT" rev-parse HEAD)
gh release create "$TAG" \
  "$ZIP_PATH" \
  "$SHA_PATH" \
  --target "$CURRENT_COMMIT" \
  --title "StarsectorPrepatcher $VERSION" \
  --notes-file "$BODY_PATH"

echo "Release created: $TAG"
