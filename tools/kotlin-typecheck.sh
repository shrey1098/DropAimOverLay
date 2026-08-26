#!/usr/bin/env bash
# Typecheck the Android app's Kotlin without an Android SDK.
#
# WHY THIS EXISTS
# A full `./gradlew assembleDebug` needs android.jar, AAPT2, AGP and the androidx
# AARs, all of which come from dl.google.com (Google Maven). Where that host is
# unreachable — a locked-down CI box, a sandboxed container — there is no way to
# build the APK at all, and Kotlin type errors then reach the operator's device
# as "the app won't install". This catches them in about a minute instead.
#
# It compiles every file in app/src/main/java/com/dropaim/app against:
#   - a REAL android.jar (Robolectric's android-all, API 34, from Maven Central)
#   - the REAL nanohttpd jars (Maven Central)
#   - hand-written stubs in ktcheck/stubs for androidx only, whose signatures were
#     transcribed from upstream source at the exact version app/build.gradle pins
#
# WHAT IT CATCHES: syntax, types, null-safety, overload resolution, bad overrides,
# and our own API misuse — the whole class of "it compiles on my machine" faults.
# WHAT IT DOES NOT: resources, the manifest, R.java, ProGuard, packaging, and any
# androidx API not covered by the stubs. It is a fast gate, not a substitute for
# a real build before you fly.
#
# Usage:  tools/kotlin-typecheck.sh          (downloads the toolchain on first run)
#         KTCHECK_DIR=/some/cache tools/kotlin-typecheck.sh
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$REPO/android/app/src/main/java/com/dropaim/app"
STUBS="$REPO/tools/ktcheck/stubs"
DIR="${KTCHECK_DIR:-${TMPDIR:-/tmp}/dropaim-ktcheck}"

# Keep these in step with android/build.gradle and android/app/build.gradle.
KOTLIN_VER="1.9.24"          # org.jetbrains.kotlin.android plugin version
JVM_TARGET="17"              # kotlinOptions { jvmTarget }
ANDROID_ALL="android-all-14-robolectric-10818077"   # API 34 == compileSdk 34
NANOHTTPD_VER="2.3.1"
MAVEN="https://repo1.maven.org/maven2"

mkdir -p "$DIR/lib"

fetch() {  # fetch <url> <dest>
  [ -s "$2" ] && return 0
  echo "  ↓ $(basename "$2")"
  curl -fsSL --retry 3 -o "$2.part" "$1" && mv "$2.part" "$2"
}

echo "== toolchain ($DIR) =="
if [ ! -x "$DIR/kotlinc/bin/kotlinc" ]; then
  fetch "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VER/kotlin-compiler-$KOTLIN_VER.zip" "$DIR/kotlinc.zip"
  unzip -q -o "$DIR/kotlinc.zip" -d "$DIR"
fi
fetch "$MAVEN/org/robolectric/android-all/${ANDROID_ALL#android-all-}/$ANDROID_ALL.jar" "$DIR/lib/$ANDROID_ALL.jar"
fetch "$MAVEN/org/nanohttpd/nanohttpd/$NANOHTTPD_VER/nanohttpd-$NANOHTTPD_VER.jar" "$DIR/lib/nanohttpd-$NANOHTTPD_VER.jar"
fetch "$MAVEN/org/nanohttpd/nanohttpd-websocket/$NANOHTTPD_VER/nanohttpd-websocket-$NANOHTTPD_VER.jar" "$DIR/lib/nanohttpd-websocket-$NANOHTTPD_VER.jar"

# The JDK-wide proxy/truststore options some environments inject make kotlinc
# print a noisy banner on every invocation; they are not needed here. Unset
# rather than blanked — an empty JAVA_TOOL_OPTIONS still prints the banner.
unset JAVA_TOOL_OPTIONS
KOTLINC="$DIR/kotlinc/bin/kotlinc"
CP="$DIR/lib/$ANDROID_ALL.jar:$DIR/lib/nanohttpd-$NANOHTTPD_VER.jar:$DIR/lib/nanohttpd-websocket-$NANOHTTPD_VER.jar"

echo "== stubs =="
"$KOTLINC" -nowarn -jvm-target "$JVM_TARGET" -classpath "$CP" -d "$DIR/stubs.jar" "$STUBS"/*.kt

echo "== app ($(ls "$SRC"/*.kt | wc -l) files) =="
"$KOTLINC" -jvm-target "$JVM_TARGET" -classpath "$CP:$DIR/stubs.jar" -d "$DIR/app.jar" "$SRC"/*.kt

echo
echo "✓ Kotlin typecheck passed — $(unzip -l "$DIR/app.jar" | grep -c 'com/dropaim/app/[^$]*\.class$') top-level classes."
echo "  Resources, manifest and packaging are NOT checked. Run a real"
echo "  ./gradlew assembleDebug before flying it."
