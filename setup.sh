#!/usr/bin/env bash
# Installe tout ce qu'il faut pour compiler (une seule fois) :
#   JDK, Android SDK (cmdline-tools + platform 36 + build-tools 36.0.0).
# Gradle est téléchargé tout seul par ./gradlew au premier build.
# Rien n'est installé en root sauf le JDK (apt).
set -euo pipefail

SDK="$HOME/Android/Sdk"
CMDTOOLS_ZIP="commandlinetools-linux-15859902_latest.zip"   # version listée sur developer.android.com/studio (nov. 2026)
PROJECT="$(cd "$(dirname "$0")" && pwd)"

echo "==> 1/3 JDK (AGP 9.x demande Java 17 minimum)"
if ! command -v javac >/dev/null 2>&1; then
  sudo apt-get update
  for p in openjdk-21-jdk-headless openjdk-17-jdk-headless openjdk-25-jdk-headless; do
    if apt-cache show "$p" >/dev/null 2>&1; then sudo apt-get install -y "$p" unzip curl; break; fi
  done
fi
javac -version
command -v unzip >/dev/null && command -v curl >/dev/null || sudo apt-get install -y unzip curl

echo "==> 2/3 Android cmdline-tools dans $SDK"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$SDK/cmdline-tools"
  tmp="$(mktemp -d)"
  curl -fL -o "$tmp/$CMDTOOLS_ZIP" "https://dl.google.com/android/repository/$CMDTOOLS_ZIP"
  unzip -q "$tmp/$CMDTOOLS_ZIP" -d "$tmp"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"   # sdkmanager exige ce chemin .../cmdline-tools/latest
  rm -rf "$tmp"
fi

echo "==> 3/3 Paquets SDK (accepte les licences)"
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
  "platform-tools" "platforms;android-36" "build-tools;36.0.0"

echo "sdk.dir=$SDK" > "$PROJECT/local.properties"
echo
echo "C'est prêt. Lance maintenant :  bash \"$PROJECT/build.sh\""
