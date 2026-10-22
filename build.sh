#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")"
LOG="build-$(date +%Y%m%d-%H%M%S).log"
exec > >(tee -a "$LOG") 2>&1

note(){ openclaw system event --mode now --session-key agent:main:main --text "wallpaper-muzei build: $1" || true; }

echo "=== [$(date)] pulling SDK image ==="
docker pull -q mobiledevops/android-sdk-image || { note "FAILED at docker pull sdk image"; exit 1; }

echo "=== [$(date)] installing SDK pieces into volume ==="
docker run --rm -v android-sdk-vol:/opt/android-sdk mobiledevops/android-sdk-image \
  bash -c 'yes | sdkmanager --licenses >/dev/null 2>&1; sdkmanager "platforms;android-35" "build-tools;35.0.0" >/dev/null && echo SDK_OK' \
  || { note "FAILED at sdkmanager install"; exit 1; }

echo "=== [$(date)] pulling gradle image ==="
docker pull -q gradle:8.12-jdk17 || { note "FAILED at docker pull gradle"; exit 1; }

echo "=== [$(date)] gradle assembleDebug ==="
docker run --rm \
  -v android-sdk-vol:/opt/android-sdk \
  -v "$(pwd)":/project -w /project \
  -v gradle-cache-vol:/home/gradle/.gradle \
  -e ANDROID_HOME=/opt/android-sdk -e ANDROID_SDK_ROOT=/opt/android-sdk \
  gradle:8.12-jdk17 gradle --no-daemon assembleDebug
rc=$?

if [ $rc -eq 0 ] && ls app/build/outputs/apk/debug/app-debug.apk >/dev/null 2>&1; then
  ls -la app/build/outputs/apk/debug/
  note "SUCCESS: APK ready at ~/projects/wallpaper-muzei/app/build/outputs/apk/debug/app-debug.apk"
else
  note "FAILED: gradle assembleDebug rc=$rc — check ~/projects/wallpaper-muzei/$LOG"
fi
