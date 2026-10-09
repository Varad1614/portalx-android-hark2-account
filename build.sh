#!/bin/sh
# Builds PortalX.apk in the Hark sandbox (handles both x86_64 and aarch64 hosts).
set -e
cd "$(dirname "$0")"
export ANDROID_HOME=/workspace/work/android-sdk
EXTRA=""
if [ "$(uname -m)" = "aarch64" ]; then
  export JAVA_HOME=/workspace/work/tools/jdk-arm64
  EXTRA="-Pandroid.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/35.0.0/aapt2"
else
  export JAVA_HOME=$(ls -d /workspace/work/tools/x64/jdk-17*)
fi
./gradlew assembleRelease --no-daemon -q $EXTRA
cp app/build/outputs/apk/release/app-release.apk PortalX.apk
ls -la PortalX.apk
