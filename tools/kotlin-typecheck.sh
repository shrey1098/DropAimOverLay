#!/usr/bin/env bash

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$REPO/android/app/src/main/java/com/dropaim/app"
STUBS="$REPO/tools/ktcheck/stubs"
DIR="${KTCHECK_DIR:-${TMPDIR:-/tmp}/dropaim-ktcheck}"

KOTLIN_VER="1.9.24"
JVM_TARGET="17"
ANDROID_ALL="android-all-14-robolectric-10818077"
NANOHTTPD_VER="2.3.1"
MAVEN="https://repo1.maven.org/maven2"

mkdir -p "$DIR/lib"

fetch() {
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
