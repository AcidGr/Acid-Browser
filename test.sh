#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/usr/lib/android-sdk}"
ANDROID_JAR="${ANDROID_JAR:-$ANDROID_SDK_ROOT/platforms/android-23/android.jar}"

if [ ! -d "build/classes" ] || [ "${FROM_BUILD_SH:-0}" != "1" ]; then
    mkdir -p build/gen/com/acid/browser build/classes
    if [ ! -f "build/gen/com/acid/browser/BuildInfo.java" ]; then
        SKIP_TESTS=1 ./build.sh >/dev/null
    else
        javac -proc:none -encoding UTF-8 -source 1.8 -target 1.8 -Xlint:-options \
            -cp "$ANDROID_JAR" \
            $(find src build/gen -name "*.java") \
            -d build/classes
    fi
fi

rm -rf build/test-stubs build/test-classes
mkdir -p build/test-stubs build/test-classes

javac -proc:none -encoding UTF-8 -source 1.8 -target 1.8 -Xlint:-options \
    -cp "$ANDROID_JAR" \
    $(find test/stubs -name "*.java") \
    -d build/test-stubs

javac -proc:none -encoding UTF-8 -source 1.8 -target 1.8 -Xlint:-options \
    -cp "build/test-stubs:build/classes:$ANDROID_JAR" \
    $(find test/com -name "*.java") \
    -d build/test-classes

echo "[AcidTests] Running JVM regression test suite..."
java -cp "build/test-stubs:build/classes:build/test-classes:$ANDROID_JAR" \
    com.acid.browser.TestRunner

echo "[AcidTests] Running JS bridge fill readback regression tests..."
node test/test_fill_readback.js
