#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/usr/lib/android-sdk}"
ANDROID_JAR="${ANDROID_JAR:-$ANDROID_SDK_ROOT/platforms/android-23/android.jar}"
DX="${DX:-$ANDROID_SDK_ROOT/build-tools/debian/dx}"
AAPT="${AAPT:-$(command -v aapt || echo /usr/bin/aapt)}"
ZIPALIGN="${ZIPALIGN:-$(command -v zipalign || echo /usr/bin/zipalign)}"
APKSIGNER="${APKSIGNER:-$(command -v apksigner || echo /usr/bin/apksigner)}"

KEYSTORE_FILE="${KEYSTORE_FILE:-/root/debug.keystore}"
KEYSTORE_PASS="${KEYSTORE_PASS:-android}"
KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"
KEY_PASS="${KEY_PASS:-android}"

# Extract single-source-of-truth version metadata from AndroidManifest.xml
VERSION_CODE=$(sed -n 's/.*android:versionCode="\([0-9][0-9]*\)".*/\1/p' AndroidManifest.xml | head -1)
VERSION_NAME=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml | head -1)
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-0.0.0}"

rm -rf build
mkdir -p build/gen/com/acid/browser build/classes build/apk

cat > build/gen/com/acid/browser/BuildInfo.java <<EOF
package com.acid.browser;

/** Auto-generated from AndroidManifest.xml by build.sh. Do not edit by hand. */
public final class BuildInfo {
    public static final int VERSION_CODE = ${VERSION_CODE};
    public static final String VERSION_NAME = "${VERSION_NAME}";
    private BuildInfo() {}
}
EOF

echo "[Acid-browser] 1. Generating R.java and initial package with aapt (v${VERSION_NAME} / ${VERSION_CODE})..."
"$AAPT" package -f -m -0 arsc \
    -S res \
    -J build/gen \
    -M AndroidManifest.xml \
    -I "$ANDROID_JAR" \
    -F build/apk/unaligned.apk

echo "[Acid-browser] 2. Compiling Java sources..."
javac -proc:none -encoding UTF-8 -source 1.8 -target 1.8 -Xlint:-options \
    -cp "$ANDROID_JAR" \
    $(find src build/gen -name "*.java") \
    -d build/classes

if [ "${SKIP_TESTS:-0}" != "1" ] && [ -x "./test.sh" ]; then
    FROM_BUILD_SH=1 ./test.sh
fi

echo "[Acid-browser] 3. Converting classes to classes.dex (minSdk=24)..."
cd build/classes
"$DX" --dex --min-sdk-version=24 --output=../classes.dex $(find . -name "*.class")
cd "$SCRIPT_DIR"

echo "[Acid-browser] 4. Adding classes.dex and assets to APK..."
cd build
"$AAPT" add "apk/unaligned.apk" "classes.dex"
cd "$SCRIPT_DIR"
for a in $(find assets -type f); do
    "$AAPT" add "build/apk/unaligned.apk" "$a"
done

echo "[Acid-browser] 5. Zipaligning APK to 4-byte boundary..."
"$ZIPALIGN" -p -f 4 "build/apk/unaligned.apk" "build/apk/aligned.apk"

echo "[Acid-browser] 6. Signing APK..."
if [ ! -f "$KEYSTORE_FILE" ]; then
    keytool -genkey -v -keystore "$KEYSTORE_FILE" \
        -storepass "$KEYSTORE_PASS" -alias "$KEY_ALIAS" -keypass "$KEY_PASS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
fi

"$APKSIGNER" sign --ks "$KEYSTORE_FILE" \
    --ks-pass "pass:$KEYSTORE_PASS" \
    --ks-key-alias "$KEY_ALIAS" \
    --key-pass "pass:$KEY_PASS" \
    --out "build/Acid-browser.apk" \
    "build/apk/aligned.apk"

echo "[Acid-browser] Build successful: build/Acid-browser.apk"
ls -lh build/Acid-browser.apk
