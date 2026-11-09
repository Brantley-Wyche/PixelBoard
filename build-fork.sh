#!/usr/bin/env bash
# ==============================================================================
# PixelBoard fork build (Windows / Git Bash)
#
# Compiles the patch bundle from source, patches a stock Gboard bundle, signs
# it with the shared PixelBoard key (same certificate as the official 1.3.x
# releases, so it installs as an update) and optionally installs it over adb.
#
# Usage: ./build-fork.sh [--install] [stock_gboard.apkm]
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

INSTALL=false
TOOLS="${PIXELBOARD_TOOLS:-$LOCALAPPDATA/pixelboard-tools}"
INPUT_APK="$TOOLS/input/gboard-18.5.3.apkm"
for arg in "$@"; do
    case "$arg" in
        -i|--install) INSTALL=true ;;
        *) INPUT_APK="$arg" ;;
    esac
done

EXPECTED_CERT_SHA256="07e18f42ec4f5f179dd25b8efab17c992a1fabf7e2deb37e4c4972e9e830e63d"
KEYSTORE_PATH="tools/patcher-data/pixelboard.keystore"

export JAVA_HOME="${JAVA21_HOME:-$(ls -d "$TOOLS"/jdk-21* 2>/dev/null | head -n 1)}"
export ANDROID_HOME="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
BUILD_TOOLS="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -n 1)"
export PATH="$JAVA_HOME/bin:$BUILD_TOOLS:$ANDROID_HOME/platform-tools:$PATH"
# GitHub Packages (Morphe gradle plugin) needs a token with read:packages.
export GITHUB_ACTOR="${GITHUB_ACTOR:-$(gh api user --jq .login)}"
export GITHUB_TOKEN="${GITHUB_TOKEN:-$(gh auth token)}"
# Norton's HTTPS scanning re-signs traffic; trust the Windows certificate store.
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"
# sanitize_apk_resources.py prints emoji; the Windows console code page can't.
export PYTHONUTF8=1

[[ -x "$JAVA_HOME/bin/java" ]] || { echo "❌ Java 21 not found under $TOOLS (set JAVA21_HOME)"; exit 1; }
[[ -f "$INPUT_APK" ]] || { echo "❌ Stock Gboard bundle not found: $INPUT_APK"; exit 1; }
[[ -f "$KEYSTORE_PATH" ]] || {
    echo "❌ $KEYSTORE_PATH missing. Convert tools/morphe-data/imported.keystore (see docs/fork.md)."
    exit 1
}

echo "=== 🎹 Compiling patch bundle ==="
(cd pixelboard-patches && ./gradlew :patches:buildAndroid --console=plain -q)
MPP_PATH="$(ls pixelboard-patches/patches/build/libs/patches-*.mpp | grep -v -e javadoc -e sources | head -n 1)"
echo "✅ $MPP_PATH"

echo "=== 🔨 Patching $INPUT_APK ==="
mkdir -p output
java -jar tools/patcher.jar patch \
    --patches="$MPP_PATH" \
    --options-file=options.json \
    --striplibs=arm64-v8a \
    --signer="PixelBoard" \
    --keystore="$KEYSTORE_PATH" \
    --keystore-password=pixelboard \
    --keystore-entry-alias=pixelboard \
    --keystore-entry-password=pixelboard \
    --temporary-files-path="tools/patcher-data/tmp" \
    -o=output/PixelBoard.apk \
    -r=output/patching-result.json \
    "$INPUT_APK"
# patcher.jar re-saves its tracked data keystore and result file on every run.
git checkout -- tools/morphe-data output/patching-result.json 2>/dev/null || true
rm -f output/*-merged.apk

# The sanitizer compares XML entries against the stock base APK, not the bundle.
STOCK_BASE="$INPUT_APK"
if [[ "$INPUT_APK" == *.apkm ]]; then
    STOCK_BASE="tools/patcher-data/stock-base.apk"
    unzip -p "$INPUT_APK" base.apk > "$STOCK_BASE"
fi
python tools/sanitize_apk_resources.py output/PixelBoard.apk "$STOCK_BASE" "$KEYSTORE_PATH"

CERT="$(apksigner.bat verify --print-certs output/PixelBoard.apk | grep -i "SHA-256 digest" | head -n 1 | awk '{print $NF}')"
if [[ "${CERT,,}" != "$EXPECTED_CERT_SHA256" ]]; then
    echo "❌ Signed with an unexpected certificate ($CERT); refusing to continue."
    exit 1
fi
echo "✅ output/PixelBoard.apk signed with the PixelBoard key"

if [[ "$INSTALL" == true ]]; then
    echo "=== 📲 Installing as an update ==="
    IME="com.akshaykadam.pixelboard/com.android.inputmethod.latin.LatinIME"
    PREVIOUS_IME="$(adb shell settings get secure default_input_method | tr -d '\r')"
    adb install -r output/PixelBoard.apk
    adb shell am force-stop com.akshaykadam.pixelboard
    # Android falls back to another keyboard while the package is replaced.
    if [[ "$PREVIOUS_IME" == "$IME" ]]; then
        adb shell ime set "$IME"
    fi
fi
