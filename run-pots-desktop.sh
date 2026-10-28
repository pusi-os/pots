#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$DIR/composeApp/build/compose/binaries/main-release/app/Pots/bin/Pots"
if [ ! -x "$APP" ]; then
  echo "Release build not found; building..." >&2
  (cd "$DIR" && ./gradlew :composeApp:createReleaseDistributable)
fi
export DISPLAY="${DISPLAY:-:0}"
exec "$APP" "$@"
