#!/usr/bin/env bash
# Lance les tests puis compile l'APK debug (signé avec la clé debug, installable directement).
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")" && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

cd "$PROJECT"
./gradlew --no-daemon testDebugUnitTest assembleDebug

APK="$PROJECT/app/build/outputs/apk/debug/app-debug.apk"
cp "$APK" "$PROJECT/apkpure-updater.apk"
echo
echo "APK prêt : $PROJECT/apkpure-updater.apk"
echo "Install via USB (débogage USB activé) :  $ANDROID_HOME/platform-tools/adb install -r \"$PROJECT/apkpure-updater.apk\""
