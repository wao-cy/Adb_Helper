#!/bin/bash
# =============================================================
# 编译 app_process JAR 包
# 用法: ./tools/build_jars.sh [android_jar_path]
# 不传参数则自动查找 Android SDK 中的 android.jar
# =============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TOOLS_SRC="$SCRIPT_DIR/../app/src/main/java/com/adbhelper/app/tools"
ASSETS_DIR="$SCRIPT_DIR/../app/src/main/assets"

# ----- 路径处理：统一转成 Unix 风格 -----
to_unix_path() {
    echo "$1" | sed 's|\\|/|g' | sed 's|C:|/c|g' | sed 's|D:|/d|g' | sed 's|E:|/e|g'
}

# ----- 确定 android.jar 和 SDK 目录 -----
if [ -n "$1" ]; then
    ANDROID_JAR="$(to_unix_path "$1")"
    # 从 android.jar 反向推导 SDK_DIR
    SDK_DIR="$(dirname "$(dirname "$(dirname "$ANDROID_JAR")")")"
else
    RAW_HOME="$(to_unix_path "${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}")"
    SDK_DIR="$RAW_HOME"
    LATEST_API=$(ls "$SDK_DIR/platforms/" 2>/dev/null | sort -V | tail -1)
    if [ -z "$LATEST_API" ]; then
        echo "ERROR: cannot find Android SDK platforms."
        echo "Set ANDROID_HOME or pass android.jar path as argument."
        exit 1
    fi
    ANDROID_JAR="$SDK_DIR/platforms/$LATEST_API/android.jar"
fi

echo "SDK dir:     $SDK_DIR"
echo "android.jar: $ANDROID_JAR"
echo "source:      $TOOLS_SRC"
echo "target:      $ASSETS_DIR"
echo ""

# ----- 查找 d8 -----
D8=$(find "$SDK_DIR/build-tools" -maxdepth 2 \( -name "d8" -o -name "d8.bat" -o -name "d8.exe" \) 2>/dev/null | sort -V | tail -1)
if [ -z "$D8" ]; then
    echo "ERROR: d8 not found in $SDK_DIR/build-tools"
    exit 1
fi
echo "d8: $D8"
echo ""

# ----- 编译函数 -----
compile_jar() {
    local name="$1"
    local main="$TOOLS_SRC/$name.java"
    local out_jar="$ASSETS_DIR/$name.jar"

    if [ ! -f "$main" ]; then
        echo "SKIP: $main not found"
        return
    fi

    echo "--- $name ---"
    mkdir -p "$TOOLS_SRC/build"

    javac -cp "$ANDROID_JAR" -d "$TOOLS_SRC/build" "$main"
    echo "  javac OK"

    # d8 需要 .class 在包路径下
    cd "$TOOLS_SRC/build"
    "$D8" --lib "$ANDROID_JAR" --min-api 24 --output "$out_jar" \
        $(find . -name "*.class" | sed 's|^\./||')
    cd "$SCRIPT_DIR"

    echo "  d8 OK: $out_jar ($(du -h "$out_jar" | grep -oE '^[^ ]+'))"
    echo ""

    rm -rf "$TOOLS_SRC/build"
}

compile_jar "AppIconResolver"
compile_jar "AppListResolver"

echo "All done."
