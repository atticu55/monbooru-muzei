# wallpaper-muzei

Tiny Muzei source app + NAS index generator. Phone wallpapers live in folders
on the NAS; Muzei picks a random one from a chosen folder. No gallery server,
no tagging, no images stored on the phone beyond Muzei's cache.

## Parts
- nas/generate-index.sh — bash+jq; scans wallpaper folders, writes
  per-folder index.json + manifest.json into a web-served output dir.
  Artwork entries: {id,name,artist,url,downloadUrl} (Muzei-compatible).
- app/ (pending) — Kotlin Muzei source: GETs manifest + chosen folder's
  index.json, random pick per load, artwork downloaded by Muzei itself.

## Status
- [x] index generator (tested: 2 folders, 5 files, clean JSON)
- [ ] audit of FluffyDiscord/muzei-custom-source skeleton (child agent)
- [ ] app build (gradle-in-docker), APK install on Pixel 8
- [ ] kaede: Web Station/static serving + Task Scheduler job
