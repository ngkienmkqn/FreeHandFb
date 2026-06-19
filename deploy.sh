#!/usr/bin/env bash
# Build debug APK và cài lên thiết bị Android đang kết nối qua USB/Wi‑Fi.
#
# Cách dùng:
#   ./deploy.sh           # build + cài + mở app
#   ./deploy.sh --no-launch   # chỉ build + cài
#   ./deploy.sh --release     # build release (cần signing config)

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

APP_ID="com.example.commenthelper"
MAIN_ACTIVITY=".MainActivity"
LAUNCH_APP=true
BUILD_TYPE="debug"

for arg in "$@"; do
  case "$arg" in
    --no-launch) LAUNCH_APP=false ;;
    --release) BUILD_TYPE="release" ;;
    -h|--help)
      echo "Usage: ./deploy.sh [--no-launch] [--release]"
      exit 0
      ;;
    *)
      echo "Tham số không hợp lệ: $arg (dùng --help để xem hướng dẫn)"
      exit 1
      ;;
  esac
done

if ! command -v adb >/dev/null 2>&1; then
  echo "❌ Không tìm thấy adb. Cài Android SDK Platform-Tools và thêm vào PATH."
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

if [[ "$LAUNCH_APP" == true ]]; then
  TARGET_DEVICE="$(echo "$DEVICES" | head -n 1)"
  echo "🚀 Mở app trên thiết bị: $TARGET_DEVICE"
  adb -s "$TARGET_DEVICE" shell am start -n "${APP_ID}/${MAIN_ACTIVITY}" >/dev/null
fi

echo "✅ Xong."
