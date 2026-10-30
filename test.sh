#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/usr/lib/android-sdk}"
ANDROID_JAR="${ANDROID_JAR:-$ANDROID_SDK_ROOT/platforms/android-23/android.jar}"

if [ ! -d "build/classes" ]; then
    echo "[AcidTests] Production classes missing; compiling sources first..."
    SKIP_TESTS=1 ./build.sh >/dev/null
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
