#!/usr/bin/env bash
# Convert the site's PNG sources to WebP for smaller payloads and better
# Lighthouse scores.
#
# The PNGs are the sources and they are kept: a WebP that has gone stale is
# only ever fixed by converting the PNG again, so the PNGs have to still be
# there when that happens. The social cards stay PNG, because some OG /
# Twitter Card scrapers still don't handle WebP reliably.
#
# Requires cwebp (brew install webp | apt-get install webp). Idempotent:
# re-running converts what is there again.
#
# The screenshots in the repository's screenshots/ folder are the source for
# the site's copies of them, so they are converted too.

set -euo pipefail

cd "$(dirname "$0")/.."

EXCLUDE=("og-preview.png" "preview.png")

is_excluded() {
    local name="$1"
    for e in "${EXCLUDE[@]}"; do
        [[ "$name" == "$e" ]] && return 0
    done
    return 1
}

if ! command -v cwebp >/dev/null; then
    echo "cwebp not found. Install it with: brew install webp  (macOS) or apt-get install webp (Linux)" >&2
    exit 1
fi

convert_one() {
    local png="$1"
    local webp="$2"
    cwebp -q 90 -metadata none -quiet "$png" -o "$webp"
    echo "Converted $(basename "$png") -> $(basename "$webp")"
}

shopt -s nullglob
converted=0
for png in site/img/*.png; do
    name=$(basename "$png")
    if is_excluded "$name"; then
        continue
    fi
    convert_one "$png" "${png%.png}.webp"
    converted=$((converted + 1))
done

# The app's own screenshots, as the site shows them.
for png in screenshots/*.png; do
    name=$(basename "$png")
    if is_excluded "$name"; then
        continue
    fi
    convert_one "$png" "site/img/${name%.png}.webp"
    converted=$((converted + 1))
done

echo "Done. Converted $converted image(s)."
