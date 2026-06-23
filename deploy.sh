#!/usr/bin/env bash
# Build debug APK và cài lên thiết bị Android đang kết nối qua USB/Wi‑Fi.
#
# Cách dùng:
#   ./deploy.sh              # build + cài + mở app
#   ./deploy.sh --logs       # build + cài + mở app + theo dõi logcat
#   ./deploy.sh --no-launch  # chỉ build + cài
#   ./deploy.sh --release    # build release (cần signing config)

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

APP_ID="com.example.commenthelper"
MAIN_ACTIVITY=".MainActivity"
LAUNCH_APP=true
FOLLOW_LOGS=false
BUILD_TYPE="debug"

for arg in "$@"; do
  case "$arg" in
    --no-launch) LAUNCH_APP=false ;;
    --logs) FOLLOW_LOGS=true ;;
    --release) BUILD_TYPE="release" ;;
    -h|--help)
      echo "Usage: ./deploy.sh [--no-launch] [--logs] [--release]"
      exit 0
      ;;
    *)
      echo "Tham số không hợp lệ: $arg (dùng --help để xem hướng dẫn)"
      exit 1
      ;;
  esac
done

java_major_version() {
  local java_bin="$1/bin/java"
  if [[ ! -x "$java_bin" ]]; then
    echo 0
    return
  fi
  "$java_bin" -version 2>&1 | awk -F'"' 'NR==1 { split($2, a, "."); print a[1]; exit }'
}

setup_java() {
  local candidate major

  if [[ -n "${JAVA_HOME:-}" ]]; then
    major="$(java_major_version "$JAVA_HOME")"
    if [[ "$major" -ge 17 && "$major" -le 21 ]]; then
      export PATH="$JAVA_HOME/bin:$PATH"
      return 0
    fi
    echo "⚠️  JAVA_HOME hiện tại dùng Java $major (Gradle cần Java 17–21). Đang tìm bản phù hợp..."
  fi

  local candidates=(
    "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
    "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
    "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
    "/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  )

  for candidate in "${candidates[@]}"; do
    if [[ -d "$candidate" ]]; then
      major="$(java_major_version "$candidate")"
      if [[ "$major" -ge 17 && "$major" -le 21 ]]; then
        export JAVA_HOME="$candidate"
        export PATH="$JAVA_HOME/bin:$PATH"
        echo "☕ Dùng Java $major: $JAVA_HOME"
        return 0
      fi
    fi
  done

  echo "❌ Không tìm thấy Java 17 hoặc 21."
  echo "   Cài: brew install openjdk@17"
  exit 1
}

setup_android_sdk() {
  local candidate

  if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/platforms" ]]; then
    export ANDROID_SDK_ROOT="$ANDROID_HOME"
    export PATH="$ANDROID_HOME/platform-tools:$PATH"
    return 0
  fi

  if [[ -n "${ANDROID_HOME:-}" ]]; then
    echo "⚠️  ANDROID_HOME=$ANDROID_HOME không phải Android SDK đầy đủ. Đang tìm SDK khác..."
  fi

  local candidates=(
    "$HOME/Library/Android/sdk"
    "$HOME/Android/Sdk"
    "/opt/homebrew/share/android-commandlinetools"
  )

  for candidate in "${candidates[@]}"; do
    if [[ -d "$candidate/platforms" ]]; then
      export ANDROID_HOME="$candidate"
      export ANDROID_SDK_ROOT="$candidate"
      export PATH="$ANDROID_HOME/platform-tools:$PATH"
      echo "📱 Dùng Android SDK: $ANDROID_HOME"
      return 0
    fi
  done

  echo "❌ Không tìm thấy Android SDK (thiếu thư mục platforms/)."
  echo "   Cài Android Studio hoặc: brew install --cask android-commandlinetools"
  exit 1
}

setup_java
setup_android_sdk

if ! command -v adb >/dev/null 2>&1; then
  echo "❌ Không tìm thấy adb trong PATH."
  exit 1
fi

DEVICES="$(adb devices | awk 'NR>1 && $2=="device" { print $1 }')"
DEVICE_COUNT="$(echo "$DEVICES" | sed '/^$/d' | wc -l | tr -d ' ')"

if [[ "$DEVICE_COUNT" -eq 0 ]]; then
  echo "❌ Không có thiết bị nào đang kết nối."
  echo "   Bật USB debugging / kết nối qua adb connect, rồi chạy: adb devices"
  exit 1
fi

if [[ "$DEVICE_COUNT" -gt 1 ]]; then
  echo "⚠️  Có $DEVICE_COUNT thiết bị. Gradle sẽ cài lên tất cả thiết bị đang kết nối:"
  echo "$DEVICES" | sed 's/^/   - /'
fi

GRADLE_TASK="installDebug"
if [[ "$BUILD_TYPE" == "release" ]]; then
  GRADLE_TASK="installRelease"
fi

echo "🔨 Build & cài APK ($BUILD_TYPE)..."
./gradlew "$GRADLE_TASK"

TARGET_DEVICE="$(echo "$DEVICES" | head -n 1)"

if [[ "$LAUNCH_APP" == true ]]; then
  echo "🚀 Mở app trên thiết bị: $TARGET_DEVICE"
  adb -s "$TARGET_DEVICE" shell am start -n "${APP_ID}/${MAIN_ACTIVITY}" >/dev/null
fi

echo "✅ Xong."

if [[ "$FOLLOW_LOGS" == true ]]; then
  echo "📋 Theo dõi log (Ctrl+C để dừng)..."
  adb -s "$TARGET_DEVICE" logcat -c
  exec adb -s "$TARGET_DEVICE" logcat -v time \
    FbAutoService:D MainActivity:D CommentHelper:D '*:S'
fi
