#!/usr/bin/env bash
# generate-index.sh — scans wallpaper folders, emits one index.json per folder
# + a root manifest listing all folders. Output dir is web-served (Web Station,
# nginx alias, whatever serves static files). Zero deps beyond bash+jq.
#
# Usage: generate-index.sh <WALLPAPER_ROOT> <OUTPUT_ROOT> [BASE_URL]
#   WALLPAPER_ROOT  e.g. /volume1/wallpapers
#   OUTPUT_ROOT     e.g. /volume1/web/wallpapers  (web-served)
#   BASE_URL        e.g. https://your-nas.example/wallpapers
#                   (prepended to image paths; default: absolute paths)

set -euo pipefail

WALLPAPER_ROOT="${1:?usage: generate-index.sh <WALLPAPER_ROOT> <OUTPUT_ROOT> [BASE_URL]}"
OUTPUT_ROOT="${2:?need OUTPUT_ROOT}"
BASE_URL="${3:-}"

command -v jq >/dev/null || { echo "jq missing" >&2; exit 2; }

# image extensions we accept
shopt -s nocaseglob nullglob

manifest="[]"
for folder in "$WALLPAPER_ROOT"/*/; do
    folder="${folder%/}"
    name="$(basename "$folder")"
    [ -f "$folder" ] && continue
    outdir="$OUTPUT_ROOT/$name"
    mkdir -p "$outdir"
    items="[]"
    for f in "$folder"/*.{jpg,jpeg,png,webp,gif}; do
        [ -f "$f" ] || continue
        fname="$(basename "$f")"
        url="${BASE_URL%/}/$name/$fname"
        # id: stable, filesystem-independent (folder/filename)
        items=$(jq --arg id "$name/$fname" --arg name "$fname" --arg byline "$name" --arg url "$url" \
            '. + [{id:$id, name:$name, artist:$byline, url:$url, downloadUrl:$url}]' <<<"$items")
    done
    echo "$items" > "$outdir/index.json"
    manifest=$(jq --arg name "$name" '. + [{name:$name}]' <<<"$manifest")
    echo "indexed $name: $(echo "$items" | jq length) files" >&2
done
echo "$manifest" > "$OUTPUT_ROOT/manifest.json"
echo "manifest written" >&2
