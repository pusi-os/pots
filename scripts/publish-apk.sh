#!/usr/bin/env bash
# Builds the release APK, bumps the version and publishes it to the
# PixelDesk dashboard, so the phone can scan the QR code and install exactly
# what was just built.
#
#     scripts/publish-apk.sh
#
# What it does, in order:
#
#   1. bumps the version in `gradle/libs.versions.toml` (the patch of the
#      version name and the version code);
#   2. builds the release APK;
#   3. copies it to the dashboard host as `pots-latest.apk` and as
#      `pots-<version>-<code>.apk`, and writes `manifest.json` next to them —
#      which is what the dashboard reads for the version, the size and the
#      checksum;
#   4. commits the version bump, if the tree was otherwise clean.
#
# The APK is debug-signed unless KEYSTORE_FILE is set. Android Gradle Plugin
# picks the debug keystore from ANDROID_USER_HOME, and a stale build output can
# keep a signature from an earlier value. This script pins the Android user home
# to $HOME/.android, deletes the old APK first and verifies the result, so an
# update never lands on the phone with an incompatible signing key.
#
# The host, directory and Android user home can be overridden:
#
#     POTS_PUBLISH_HOST=al@192.168.1.222 POTS_PUBLISH_DIST=Projects/pots/dist \
#       POTS_ANDROID_USER_HOME=$HOME/.android scripts/publish-apk.sh
#
# `--no-commit` leaves the version bump in the working tree instead of
# committing it, and `--no-bump` keeps the version as it is.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

HOST="${POTS_PUBLISH_HOST:-al@192.168.1.222}"
DIST="${POTS_PUBLISH_DIST:-Projects/pots/dist}"
VARIANT="${POTS_VARIANT:-fossRelease}"
# Pin the directory that holds debug.keystore. AGP uses the same key for the
# release fallback, so changing this between builds makes updates fail with
# INSTALL_FAILED_UPDATE_INCOMPATIBLE.
export ANDROID_USER_HOME="${POTS_ANDROID_USER_HOME:-$HOME/.android}"

BUMP=1
COMMIT=1
for arg in "$@"; do
    case "$arg" in
        --no-bump) BUMP=0 ;;
        --no-commit) COMMIT=0 ;;
        *) echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

say() { printf '\033[1;35m==>\033[0m %s\n' "$*"; }

# --- 1. the version ----------------------------------------------------------

read_version() {
    python3 - "$@" <<'PY'
import pathlib, re, sys
text = pathlib.Path("gradle/libs.versions.toml").read_text()
name = re.search(r'^appVersion\s*=\s*"([^"]+)"', text, re.M).group(1)
code = int(re.search(r'^android-versionCode\s*=\s*"(\d+)"', text, re.M).group(1))
print(name, code)
PY
}

write_version() {
    python3 - "$1" "$2" <<'PY'
import pathlib, re, sys
name, code = sys.argv[1], sys.argv[2]
path = pathlib.Path("gradle/libs.versions.toml")
text = path.read_text()
text = re.sub(r'^appVersion\s*=\s*"[^"]+"', f'appVersion = "{name}"', text, flags=re.M)
text = re.sub(r'^android-versionCode\s*=\s*"\d+"', f'android-versionCode = "{code}"', text, flags=re.M)
path.write_text(text)
PY
}

read -r CURRENT_NAME CURRENT_CODE <<< "$(read_version)"
VERSION_NAME="$CURRENT_NAME"
VERSION_CODE="$CURRENT_CODE"

if [ "$BUMP" = "1" ]; then
    VERSION_NAME="$(python3 - "$CURRENT_NAME" <<'PY'
import sys
parts = sys.argv[1].split(".")
while len(parts) < 3:
    parts.append("0")
major, minor, patch = (int(p) for p in parts[:3])
if patch >= 9:
    major, minor, patch = major, minor + 1, 0
else:
    patch += 1
print(f"{major}.{minor}.{patch}")
PY
)"
    VERSION_CODE=$((CURRENT_CODE + 1))
    write_version "$VERSION_NAME" "$VERSION_CODE"
    say "version $CURRENT_NAME ($CURRENT_CODE) -> $VERSION_NAME ($VERSION_CODE)"
fi

# --- 2. the build ------------------------------------------------------------

APK="androidApp/build/outputs/apk/${VARIANT%/Release}/release/androidApp-foss-release.apk"
# The packaging task can stay up-to-date even when the debug keystore moved,
# leaving the previous signature in place. Force it to produce a fresh APK.
rm -f "$APK"

say "building the $VARIANT APK"
./gradlew ":androidApp:assemble$VARIANT" -q

if [ ! -f "$APK" ]; then
    # The flavour folder is named after the flavour, not the build type.
    # Prefer a release APK: a stale debug build from an earlier check must
    # never shadow the release on the dashboard.
    APK="$(find androidApp/build/outputs/apk -name '*release*.apk' -newer gradle/libs.versions.toml | head -1)"
fi
if [ ! -f "$APK" ]; then
    APK="$(find androidApp/build/outputs/apk -name '*.apk' -newer gradle/libs.versions.toml | head -1)"
fi
if [ ! -f "$APK" ]; then
    echo "the build produced no APK" >&2
    exit 1
fi
say "built $APK ($(du -h "$APK" | cut -f1))"

# A mismatch here means Gradle signed with a different debug keystore than the
# one this script pinned. Publishing that APK would break every existing install.
verify_debug_signing() {
    [ -n "${KEYSTORE_FILE:-}" ] && return 0
    local debug_ks="$ANDROID_USER_HOME/debug.keystore"
    [ -f "$debug_ks" ] || return 0

    local sdk
    sdk="$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null | head -1)"
    sdk="${sdk/#\~/$HOME}"
    sdk="${sdk:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}}"
    local apksigner
    apksigner="$(find "$sdk/build-tools" -maxdepth 2 -type f -name apksigner 2>/dev/null | sort -V | tail -1)"
    if [ -z "$apksigner" ]; then
        say "warning: apksigner not found, skipping signature check"
        return 0
    fi

    local expected actual
    expected="$(keytool -list -v -keystore "$debug_ks" -storepass android 2>/dev/null |
        sed -n 's/.*SHA256: //p' | head -1 | tr -d ':' | tr '[:upper:]' '[:lower:]')"
    actual="$("$apksigner" verify --print-certs "$APK" |
        sed -n 's/.*certificate SHA-256 digest: //p' | head -1 | tr -d ':' | tr '[:upper:]' '[:lower:]')"
    if [ -z "$expected" ] || [ -z "$actual" ]; then
        echo "could not read the APK signing certificate" >&2
        exit 1
    fi
    if [ "$expected" != "$actual" ]; then
        echo "APK was signed with the wrong debug key:" >&2
        echo "  expected $expected ($debug_ks)" >&2
        echo "  actual   $actual" >&2
        exit 1
    fi
    say "verified the debug signing key"
}

verify_debug_signing

# --- 3. the manifest ---------------------------------------------------------

SHA="$(sha256sum "$APK" | cut -d' ' -f1)"
COMMIT_SHA="$(git rev-parse --short HEAD 2>/dev/null || echo '')"
BYTES="$(stat -c%s "$APK")"
BUILT_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

MANIFEST="$(mktemp)"
cat > "$MANIFEST" <<EOF
{"name":"Pots","versionName":"$VERSION_NAME","versionCode":$VERSION_CODE,"sha256":"$SHA","gitCommit":"$COMMIT_SHA","builtAt":"$BUILT_AT","bytes":$BYTES}
EOF

# --- 4. the publish ----------------------------------------------------------

say "publishing to $HOST:$DIST"
REMOTE_TMP="/tmp/pots-$VERSION_NAME-$VERSION_CODE.apk"
scp -q "$APK" "$HOST:$REMOTE_TMP"
scp -q "$MANIFEST" "$HOST:/tmp/pots-manifest.json"
ssh "$HOST" "mkdir -p '$DIST' &&
             cp '$REMOTE_TMP' '$DIST/pots-$VERSION_NAME-$VERSION_CODE.apk' &&
             mv '$REMOTE_TMP' '$DIST/pots-latest.apk' &&
             mv /tmp/pots-manifest.json '$DIST/manifest.json' &&
             ls -l '$DIST/pots-latest.apk' '$DIST/manifest.json'"
rm -f "$MANIFEST"

say "published Pots $VERSION_NAME ($VERSION_CODE) — $SHA"

# --- 5. the version bump -----------------------------------------------------

if [ "$COMMIT" = "1" ] && [ "$BUMP" = "1" ]; then
    # The iOS project's version is written by the build, so it moves with
    # the version file and belongs in the same commit.
    if git diff --quiet -- . ':(exclude)gradle/libs.versions.toml' ':(exclude)iosApp/Configuration/Config.xcconfig'; then
        git add gradle/libs.versions.toml iosApp/Configuration/Config.xcconfig
        git commit -q -m "Bump the version to $VERSION_NAME ($VERSION_CODE)"
        say "committed the version bump"
    else
        say "the tree has other changes — the version bump was left in place"
    fi
fi

say "the dashboard has it: http://$HOST:44242/ (Pots → APK)"
