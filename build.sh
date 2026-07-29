#!/bin/bash
# 灵喵 LingMiao 一键构建脚本
# 用法: ./build.sh [debug|release]

set -e

BUILD_TYPE=${1:-debug}
PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"

echo "=========================================="
echo "  灵喵 LingMiao 构建脚本"
echo "=========================================="
echo ""

# 检查环境
echo "[1/5] 检查构建环境..."
if ! command -v java &> /dev/null; then
    echo "❌ 未安装 JDK，请先安装 Java 11+"
    exit 1
fi
echo "  ✅ JDK: $(java -version 2>&1 | head -1)"

if [ ! -f "$PROJECT_ROOT/gradle/wrapper/gradle-wrapper.jar" ]; then
    echo "⚠️ Gradle wrapper jar 不存在，将使用系统 gradle"
    if ! command -v gradle &> /dev/null; then
        echo "❌ 未安装 Gradle，请先安装"
        exit 1
    fi
    USE_GRADLEW=false
else
    USE_GRADLEW=true
fi
echo "  ✅ Gradle Wrapper: $USE_GRADLEW"

# 检查Android SDK
if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
    echo "⚠️ ANDROID_HOME 未设置，尝试常见路径..."
    for p in "$HOME/Android/Sdk" "/opt/android-sdk" "$HOME/Library/Android/sdk"; do
        if [ -d "$p" ]; then
            export ANDROID_HOME="$p"
            break
        fi
    done
fi

if [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME" ]; then
    echo "  ✅ Android SDK: $ANDROID_HOME"
else
    echo "⚠️ Android SDK 未找到，编译APK需要安装 Android Studio"
fi

# 准备签名密钥
echo ""
echo "[2/5] 准备签名密钥..."
KEYSTORE="$PROJECT_ROOT/lingmiao.keystore"
if [ ! -f "$KEYSTORE" ]; then
    if command -v keytool &> /dev/null; then
        keytool -genkeypair -v \
            -keystore "$KEYSTORE" \
            -alias lingmiao_key \
            -keyalg RSA \
            -keysize 2048 \
            -validity 10000 \
            -storepass lingmiao123 \
            -keypass lingmiao123 \
            -dname "CN=LingMiao, OU=Billiards, O=AI, L=Unknown, ST=Unknown, C=CN" \
            -noprompt 2>/dev/null || true
        if [ -f "$KEYSTORE" ]; then
            echo "  ✅ 密钥库已生成: $KEYSTORE"
        fi
    fi
fi

# 创建签名配置
echo ""
echo "[3/5] 配置签名..."
KEYSTORE_PROPS="$PROJECT_ROOT/keystore.properties"
if [ ! -f "$KEYSTORE_PROPS" ] && [ -f "$KEYSTORE" ]; then
    cat > "$KEYSTORE_PROPS" << EOF
storeFile=$KEYSTORE
storePassword=lingmiao123
keyAlias=lingmiao_key
keyPassword=lingmiao123
EOF
    echo "  ✅ 签名配置已生成"
fi

# 编译
echo ""
echo "[4/5] 编译项目 (BUILD_TYPE=$BUILD_TYPE)..."
cd "$PROJECT_ROOT"

if [ "$USE_GRADLEW" = true ]; then
    if [ "$BUILD_TYPE" = "release" ]; then
        ./gradlew assembleRelease
    else
        ./gradlew assembleDebug
    fi
else
    if [ "$BUILD_TYPE" = "release" ]; then
        gradle assembleRelease
    else
        gradle assembleDebug
    fi
fi

# 输出结果
echo ""
echo "[5/5] 构建完成!"
echo ""
APK_DIR="$PROJECT_ROOT/app/build/outputs/apk/$BUILD_TYPE"
if [ -d "$APK_DIR" ]; then
    echo "APK位置: $APK_DIR"
    ls -lh "$APK_DIR"/*.apk 2>/dev/null || ls -lh "$APK_DIR"/*.aab 2>/dev/null || echo "(无输出文件)"
fi

echo ""
echo "=========================================="
echo "  构建完成 ✅"
echo "=========================================="
echo ""
echo "下一步:"
echo "  1. 把APK传到手机安装"
echo "  2. 打开灵喵，完成校准"
echo "  3. 选择游戏预设(抖音/天天/帝国/...)"
echo "  4. 启动悬浮辅助，开始使用"
echo ""
echo "如遇问题，查看日志: adb logcat -s LingMiao:V"
