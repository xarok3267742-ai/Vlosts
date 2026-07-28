#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${V_SLOT_ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
GRADLE="${V_SLOT_GRADLE:-$ROOT/gradlew}"
SERIAL="${1:-${ANDROID_SERIAL:-}}"
REMOTE_DIR="/sdcard/Download/VSlotStore"
OUTPUT_DIR="$ROOT/docs/store/assets/screenshots"
METADATA_FILE="$OUTPUT_DIR/capture-metadata.json"
QA_APK="$ROOT/app/build/outputs/apk/qa/app-qa.apk"
QA_TEST_APK="$ROOT/app/build/outputs/apk/androidTest/qa/app-qa-androidTest.apk"
APK_PAYLOAD_DIGEST="${V_SLOT_APK_PAYLOAD_DIGEST:-$ROOT/tools/apk_payload_sha256.sh}"
AAPT2="${V_SLOT_AAPT2:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/build-tools/36.0.0/aapt2}"
PYTHON="${V_SLOT_PYTHON:-python3}"
TESTS="com.vslot.app.MainActivitySmokeTest#homeNavigationOpensSlotPaytableSettingsAndPrivacyFallback,com.vslot.app.MainActivitySmokeTest#freeSpinsModeUsesFreeSpinCopyAndLocksStakeControls"
EXPECTED=(
  01-home.png
  02-violet-slot.png
  03-paytable.png
  04-settings.png
  05-free-spins.png
)

if [[ -z "$SERIAL" ]]; then
  echo "Pass an explicit Android emulator serial or set ANDROID_SERIAL." >&2
  exit 1
fi
if [[ ! -x "$ADB" || ! -x "$GRADLE" || ! -x "$APK_PAYLOAD_DIGEST" ]]; then
  echo "ADB, Gradle launcher, or APK payload digest tool is unavailable." >&2
  exit 1
fi
if ! command -v "$PYTHON" >/dev/null 2>&1; then
  echo "Python 3 is required to verify Store screenshot PNG geometry." >&2
  exit 1
fi
if [[ ! -x "$AAPT2" ]]; then
  echo "Pinned Android 36 aapt2 is unavailable: $AAPT2" >&2
  exit 1
fi
if [[ "$($ADB -s "$SERIAL" get-state 2>/dev/null || true)" != "device" ]]; then
  echo "Selected Android device is not ready: $SERIAL" >&2
  exit 1
fi
if [[ "$($ADB -s "$SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
  echo "Store screenshot capture is intentionally restricted to an explicit emulator." >&2
  exit 1
fi
api_level="$($ADB -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$api_level" != "36" ]]; then
  echo "Store screenshots require the target-SDK Android 36 emulator; selected API is $api_level." >&2
  exit 1
fi
if ! "$ADB" -s "$SERIAL" shell wm size | tr -d '\r' | grep -Fxq "Physical size: 1080x2400"; then
  echo "Store screenshots require a 1080x2400 AVD without a size override." >&2
  exit 1
fi
if ! "$ADB" -s "$SERIAL" shell wm density | tr -d '\r' | grep -Fxq "Physical density: 420"; then
  echo "Store screenshots require a 420dpi AVD without a density override." >&2
  exit 1
fi
original_locales="$($ADB -s "$SERIAL" shell settings get system system_locales | tr -d '\r')"
original_font_scale="$($ADB -s "$SERIAL" shell settings get system font_scale | tr -d '\r')"
original_accelerometer_rotation="$($ADB -s "$SERIAL" shell settings get system accelerometer_rotation | tr -d '\r')"
original_user_rotation="$($ADB -s "$SERIAL" shell settings get system user_rotation | tr -d '\r')"

restore_system_setting() {
  local key="$1"
  local value="$2"
  if [[ -n "$value" && "$value" != "null" ]]; then
    "$ADB" -s "$SERIAL" shell settings put system "$key" "$value" >/dev/null 2>&1 || true
  else
    "$ADB" -s "$SERIAL" shell settings delete system "$key" >/dev/null 2>&1 || true
  fi
}

restore_display() {
  "$ADB" -s "$SERIAL" shell wm size reset >/dev/null 2>&1 || true
  "$ADB" -s "$SERIAL" shell wm density reset >/dev/null 2>&1 || true
  restore_system_setting font_scale "$original_font_scale"
  restore_system_setting accelerometer_rotation "$original_accelerometer_rotation"
  restore_system_setting user_rotation "$original_user_rotation"
  restore_system_setting system_locales "$original_locales"
}
trap restore_display EXIT

"$ADB" -s "$SERIAL" shell wm size 1080x1920
"$ADB" -s "$SERIAL" shell wm density 360
if ! "$ADB" -s "$SERIAL" shell wm size | tr -d '\r' | grep -Fxq "Override size: 1080x1920"; then
  echo "Failed to configure the required 9:16 Play Store capture geometry." >&2
  exit 1
fi
if ! "$ADB" -s "$SERIAL" shell wm density | tr -d '\r' | grep -Fxq "Override density: 360"; then
  echo "Failed to configure the required Play Store capture density." >&2
  exit 1
fi
"$ADB" -s "$SERIAL" shell settings put system font_scale 1.0
"$ADB" -s "$SERIAL" shell settings put system system_locales ru-RU
if [[ "$($ADB -s "$SERIAL" shell settings get system font_scale | tr -d '\r')" != "1.0" ]]; then
  echo "Failed to configure the required Store screenshot font scale." >&2
  exit 1
fi
if [[ "$($ADB -s "$SERIAL" shell settings get system system_locales | tr -d '\r')" != "ru-RU" ]]; then
  echo "Failed to configure the required Store screenshot locale." >&2
  exit 1
fi
"$ADB" -s "$SERIAL" shell settings put system accelerometer_rotation 0
"$ADB" -s "$SERIAL" shell settings put system user_rotation 0
"$ADB" -s "$SERIAL" shell rm -rf "$REMOTE_DIR"

ANDROID_SERIAL="$SERIAL" "$GRADLE" :app:connectedQaAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.class=$TESTS" \
  "-Pandroid.testInstrumentationRunnerArguments.capture_store_screenshots=true"

mkdir -p "$OUTPUT_DIR"
for file in "${EXPECTED[@]}"; do
  rm -f "$OUTPUT_DIR/$file"
done
"$ADB" -s "$SERIAL" pull "$REMOTE_DIR/." "$OUTPUT_DIR/"

for file in "${EXPECTED[@]}"; do
  path="$OUTPUT_DIR/$file"
  if [[ ! -s "$path" ]] || [[ "$(wc -c < "$path" | tr -d '[:space:]')" -lt 100000 ]]; then
    echo "Missing or unexpectedly small Store screenshot: $path" >&2
    exit 1
  fi
  dimensions="$("$PYTHON" - "$path" <<'PY'
import struct
import sys

with open(sys.argv[1], "rb") as source:
    header = source.read(24)
if len(header) != 24 or header[:8] != b"\x89PNG\r\n\x1a\n":
    raise SystemExit("invalid PNG")
width, height = struct.unpack(">II", header[16:24])
print(f"{width}x{height}")
PY
)"
  if [[ "$dimensions" != "1080x1920" ]]; then
    echo "Unexpected Store screenshot geometry: $path ($dimensions)" >&2
    exit 1
  fi
done

if [[ ! -s "$QA_APK" || ! -s "$QA_TEST_APK" ]]; then
  echo "QA app or instrumentation APK was not produced." >&2
  exit 1
fi
package_line="$($AAPT2 dump badging "$QA_APK" | sed -n '1p')"
package_name="$(printf '%s\n' "$package_line" | awk -F"'" '{ print $2 }')"
version_code="$(printf '%s\n' "$package_line" | awk -F"'" '{ print $4 }')"
version_name="$(printf '%s\n' "$package_line" | awk -F"'" '{ print $6 }')"
if [[ "$package_name" != "com.vslot.app.qa" || ! "$version_code" =~ ^[0-9]+$ || -z "$version_name" ]]; then
  echo "Unable to identify the expected QA package from $QA_APK" >&2
  exit 1
fi
apk_sha256="$(shasum -a 256 "$QA_APK" | awk '{ print $1 }')"
apk_payload_sha256="$("$APK_PAYLOAD_DIGEST" "$QA_APK")"
test_apk_sha256="$(shasum -a 256 "$QA_TEST_APK" | awk '{ print $1 }')"
test_apk_payload_sha256="$("$APK_PAYLOAD_DIGEST" "$QA_TEST_APK")"
captured_at_utc="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
avd_name="$($ADB -s "$SERIAL" shell getprop ro.boot.qemu.avd_name | tr -d '\r')"
locale="$($ADB -s "$SERIAL" shell settings get system system_locales | tr -d '\r')"
metadata_tmp="$METADATA_FILE.tmp"
{
  printf '{\n'
  printf '  "schema_version": 2,\n'
  printf '  "captured_at_utc": "%s",\n' "$captured_at_utc"
  printf '  "build_variant": "qa",\n'
  printf '  "package_name": "%s",\n' "$package_name"
  printf '  "version_code": %s,\n' "$version_code"
  printf '  "version_name": "%s",\n' "$version_name"
  printf '  "qa_apk_sha256": "%s",\n' "$apk_sha256"
  printf '  "qa_apk_payload_sha256": "%s",\n' "$apk_payload_sha256"
  printf '  "qa_test_apk_sha256": "%s",\n' "$test_apk_sha256"
  printf '  "qa_test_apk_payload_sha256": "%s",\n' "$test_apk_payload_sha256"
  printf '  "device": {"avd_name": "%s", "api_level": %s, "locale": "%s", "physical_size": "1080x2400", "physical_density_dpi": 420},\n' "$avd_name" "$api_level" "$locale"
  printf '  "capture": {"width": 1080, "height": 1920, "density_dpi": 360, "font_scale": 1.0},\n'
  printf '  "screenshot_sha256": {\n'
  for index in "${!EXPECTED[@]}"; do
    file="${EXPECTED[$index]}"
    digest="$(shasum -a 256 "$OUTPUT_DIR/$file" | awk '{ print $1 }')"
    comma=","
    if [[ "$index" -eq "$((${#EXPECTED[@]} - 1))" ]]; then
      comma=""
    fi
    printf '    "%s": "%s"%s\n' "$file" "$digest" "$comma"
  done
  printf '  }\n'
  printf '}\n'
} > "$metadata_tmp"
mv "$metadata_tmp" "$METADATA_FILE"

echo "Play Store screenshots captured in $OUTPUT_DIR"
