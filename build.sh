#!/usr/bin/env bash
# 快传 · 无 Gradle 构建脚本
# 依赖：Android SDK build-tools（aapt2 / d8 / zipalign / apksigner）、JDK 8+、android.jar
set -e
cd "$(dirname "$0")"

: "${ANDROID_JAR:?请设置 ANDROID_JAR，例如 $HOME/Android/Sdk/platforms/android-34/android.jar}"
: "${BUILD_TOOLS:?请设置 BUILD_TOOLS，例如 $HOME/Android/Sdk/build-tools/34.0.0}"
: "${KEYSTORE:?请设置 KEYSTORE 指向你的签名文件}"
: "${KS_PASS:?请设置 KS_PASS}"
: "${KEY_ALIAS:?请设置 KEY_ALIAS}"
JAVAC="${JAVAC:-javac}"
OUT="${1:-../快传.apk}"

rm -rf build
mkdir -p build/classes build/dex

"$BUILD_TOOLS/aapt2" compile --dir res -o build/res.zip
"$BUILD_TOOLS/aapt2" link -o build/base.apk -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml -R build/res.zip --java build/gen --auto-add-overlay

"$JAVAC" -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d build/classes \
  $(find src -name '*.java') $(find build/gen -name '*.java')

"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 23 --output build/dex \
  $(find build/classes -name '*.class')

cp build/base.apk build/unsigned.apk
( cd build && zip -q -j unsigned.apk dex/classes.dex )
"$BUILD_TOOLS/zipalign" -f 4 build/unsigned.apk build/aligned.apk
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:${KEY_PASS:-$KS_PASS}" --ks-key-alias "$KEY_ALIAS" \
  --out "$OUT" build/aligned.apk

echo "built $OUT"
