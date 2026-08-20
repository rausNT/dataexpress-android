#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK="$ROOT/upstream/winlator-app.lock"
WORK="${1:-$ROOT/build/winlator-src}"

# shellcheck disable=SC1090
source "$LOCK"

rm -rf "$WORK"
git clone --filter=blob:none "$WINLATOR_REPOSITORY" "$WORK"
git -C "$WORK" checkout --detach "$WINLATOR_COMMIT"

# Keep upstream attribution and apply the same overlay used by the APK build.
cp "$ROOT/NOTICE-WINLATOR.md" "$WORK/NOTICE-DATAEXPRESS.md"
cp -R "$ROOT/overlay/." "$WORK/"

python3 "$ROOT/scripts/patch-winlator-source.py" "$WORK"

# Apply deterministic source patches. The script fails instead of silently
# building an unmodified Winlator when upstream layout changes.
for patch in "$ROOT"/patches/winlator/*.patch; do
  [[ -e "$patch" ]] || continue
  git -C "$WORK" apply --check "$patch"
  git -C "$WORK" apply "$patch"
done

echo "Prepared Winlator-derived source at: $WORK"
echo "Upstream commit: $WINLATOR_COMMIT"
