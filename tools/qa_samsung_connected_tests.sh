#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${V_SLOT_ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
GRADLE="${V_SLOT_GRADLE:-$ROOT/gradlew}"
APK_PAYLOAD_DIGEST="${V_SLOT_APK_PAYLOAD_DIGEST:-$ROOT/tools/apk_payload_sha256.sh}"
PORTRAIT_SMOKE_TEST="com.vslot.app.MainActivitySmokeTest#homeNavigationOpensSlotPaytableSettingsAndPrivacyFallback"
LARGE_FONT_TESTS="com.vslot.app.MainActivitySmokeTest#largeFontLegalCopyWrapsAndKeepsActionsReachable,com.vslot.app.MainActivitySmokeTest#largeFontDialogCopyWrapsAndKeepsActionsReachable,com.vslot.app.ThirdPartyNoticesTest#settingsOpensThirdPartyNoticesWithBundledNoticeText"
COMPACT_SETTINGS_TEST="com.vslot.app.MainActivitySmokeTest#settingsCompactPortraitKeepsScrollableControlsAboveSafetyFooter"
COMPACT_LANDSCAPE_TEST="com.vslot.app.MainActivitySmokeTest#compactLandscapeKeepsHomeAndSlotActionsReachable"
COMPACT_WM_SIZE="720x1280"
COMPACT_WM_DENSITY="320"
COMPACT_FONT_SCALE="1.0"
COMPACT_LANDSCAPE_WM_SIZE="720x1080"
COMPACT_LANDSCAPE_WM_DENSITY="320"
FULL_SUITE_EXPECTED_TESTS=65
FULL_SUITE_EXPECTED_SKIPPED=3
QA_APPLICATION_ID="com.vslot.app.qa"

if [[ ! -x "$ADB" ]]; then
  echo "ADB not found or not executable at $ADB. Set V_SLOT_ADB, ANDROID_HOME, or install Android platform-tools." >&2
  exit 1
fi

if [[ ! -x "$GRADLE" ]]; then
  echo "Gradle launcher not found or not executable at $GRADLE. Set V_SLOT_GRADLE or restore gradlew." >&2
  exit 1
fi
if [[ ! -x "$APK_PAYLOAD_DIGEST" ]]; then
  echo "APK payload digest tool not found or not executable at $APK_PAYLOAD_DIGEST." >&2
  exit 1
fi

serial="${1:-${ANDROID_SERIAL:-}}"
if [[ -z "$serial" ]]; then
  samsung_devices=()
  while IFS= read -r device; do
    [[ -n "$device" ]] && samsung_devices+=("$device")
  done < <("$ADB" devices -l | awk '/device / && /model:SM_/ {print $1}')
  if [[ "${#samsung_devices[@]}" -ne 1 ]]; then
    echo "Pass a Samsung device serial, for example: tools/qa_samsung_connected_tests.sh SAMSUNG_TEST_SERIAL_0001" >&2
    "$ADB" devices -l >&2
    exit 1
  fi
  serial="${samsung_devices[0]}"
fi

lock_serial="${serial//[^[:alnum:]_.-]/_}"
lock_dir="${TMPDIR:-/tmp}"
lock_dir="${lock_dir%/}/v-slot-samsung-qa-${lock_serial}.lock"
lock_acquired=0

release_lock() {
  if [[ "$lock_acquired" == "1" ]]; then
    if ! rm -rf -- "$lock_dir"; then
      echo "Failed to release Samsung QA lock at $lock_dir." >&2
    fi
    lock_acquired=0
  fi
}

if ! mkdir "$lock_dir" 2>/dev/null; then
  lock_pid=""
  if [[ -r "$lock_dir/pid" ]]; then
    read -r lock_pid < "$lock_dir/pid" || true
  fi
  echo "V Slot Samsung QA is already running for $serial${lock_pid:+ (PID $lock_pid)}. Stop it before rerunning." >&2
  exit 2
fi
lock_acquired=1
printf '%s\n' "$$" > "$lock_dir/pid"

trap release_lock EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

device_state="$("$ADB" -s "$serial" get-state 2>/dev/null | tr -d '\r' || true)"
if [[ "$device_state" != "device" ]]; then
  echo "Device $serial is not ready for QA (adb state: ${device_state:-not connected})." >&2
  echo "Authorize USB debugging, keep the phone online, then rerun this script." >&2
  "$ADB" devices -l >&2
  exit 1
fi

manufacturer="$("$ADB" -s "$serial" shell getprop ro.product.manufacturer | tr -d '\r' | tr '[:upper:]' '[:lower:]')"
model="$("$ADB" -s "$serial" shell getprop ro.product.model | tr -d '\r')"
android_version="$("$ADB" -s "$serial" shell getprop ro.build.version.release | tr -d '\r')"
build_fingerprint="$("$ADB" -s "$serial" shell getprop ro.build.fingerprint | tr -d '\r')"
one_ui_version="$("$ADB" -s "$serial" shell getprop ro.build.version.oneui | tr -d '\r')"
is_qemu="$("$ADB" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')"
device_locale="$("$ADB" -s "$serial" shell getprop persist.sys.locale | tr -d '\r')"
if [[ -z "$device_locale" ]]; then
  device_locale="$("$ADB" -s "$serial" shell getprop ro.product.locale | tr -d '\r')"
fi

if [[ "$manufacturer" != "samsung" || "$is_qemu" == "1" ]]; then
  echo "Device $serial is $manufacturer $model, not a physical Samsung." >&2
  exit 1
fi

conflicting_qa_pids() {
  ps -axo pid=,command= | awk -v serial="$serial" '
    index($0, serial) &&
    # Keep the target text split so this awk process cannot match its own argv
    # on Linux while the equivalent external QA process names still match.
    ($0 ~ /qa[_]release_aab_samsung/ || $0 ~ /dragon[-]slots-qa/) &&
    $0 !~ /tools\/qa_samsung_connected_tests\.sh/ {
      print $1
    }
  '
}

print_conflicting_qa() {
  local pids
  pids="$(conflicting_qa_pids)"
  [[ -z "$pids" ]] && return 0
  ps -p "$(echo "$pids" | tr '\n' ',')" -o pid,ppid,pgid,stat,command 2>/dev/null || true
}

stop_conflicting_qa() {
  local pid pgid
  while read -r pid; do
    [[ -z "$pid" ]] && continue
    pgid="$(ps -p "$pid" -o pgid= 2>/dev/null | tr -d ' ')"
    if [[ -n "$pgid" ]]; then
      kill -TERM "-$pgid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
    else
      kill -TERM "$pid" 2>/dev/null || true
    fi
  done < <(conflicting_qa_pids)
  return 0
}

stop_conflicting_app() {
  if [[ "${V_SLOT_SKIP_CONFLICT_STOP:-0}" == "1" ]]; then
    return 0
  fi
  "$ADB" -s "$serial" shell am force-stop com.dragonslots >/dev/null 2>&1 || true
}

keep_device_awake() {
  "$ADB" -s "$serial" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  "$ADB" -s "$serial" shell wm dismiss-keyguard >/dev/null 2>&1 || true
  "$ADB" -s "$serial" shell input keyevent 82 >/dev/null 2>&1 || true
}

device_is_locked() {
  "$ADB" -s "$serial" shell dumpsys window 2>/dev/null |
    grep -Eq "mDreamingLockscreen=true|mShowingLockscreen=true|mCurrentFocus=.*Bouncer"
}

require_device_unlocked() {
  keep_device_awake
  sleep 1
  if device_is_locked; then
    echo "Samsung $model ($serial) is on a secure lock screen. Unlock the phone manually, then rerun this script." >&2
    exit 3
  fi
}

power_state_captured=0
original_stay_on_while_plugged_in=""
accelerometer_rotation_captured=0
user_rotation_captured=0
wm_size_captured=0
wm_density_captured=0
font_scale_captured=0
original_accelerometer_rotation=""
original_user_rotation=""
original_wm_physical_size=""
original_wm_size_override=""
original_wm_effective_size=""
original_wm_physical_density=""
original_wm_density_override=""
original_wm_effective_density=""
original_font_scale=""
current_wm_size_output=""
current_wm_physical_size=""
current_wm_size_override=""
current_wm_effective_size=""
current_wm_density_output=""
current_wm_physical_density=""
current_wm_density_override=""
current_wm_effective_density=""
display_size=""
display_density=""
font_scale=""
rotation_probe_installed=0
qa_status="not_run"
portrait_smoke_status="not_run"
font_scale_2_status="not_run"
compact_portrait_settings_status="not_run"
compact_landscape_rotation_1_status="not_run"
compact_landscape_rotation_3_status="not_run"
landscape_rotation_1_status="not_run"
landscape_rotation_3_status="not_run"
portrait_smoke_tests=null
font_scale_2_tests=null
compact_portrait_settings_tests=null
compact_landscape_rotation_1_tests=null
compact_landscape_rotation_3_tests=null
landscape_rotation_1_tests=null
landscape_rotation_3_tests=null
portrait_smoke_skipped=null
font_scale_2_skipped=null
compact_portrait_settings_skipped=null
compact_landscape_rotation_1_skipped=null
compact_landscape_rotation_3_skipped=null
landscape_rotation_1_skipped=null
landscape_rotation_3_skipped=null
orientation_1_verified=false
orientation_1_observed=""
orientation_1_width=""
orientation_1_height=""
orientation_1_test_status="not_run"
orientation_3_verified=false
orientation_3_observed=""
orientation_3_width=""
orientation_3_height=""
orientation_3_test_status="not_run"
evidence_ready=0
evidence_dir="${V_SLOT_QA_EVIDENCE_DIR:-$ROOT/qa/screenshots/evidence}"
qa_apk="${V_SLOT_QA_APK:-$ROOT/app/build/outputs/apk/qa/app-qa.apk}"
connected_results_dir="${V_SLOT_CONNECTED_RESULTS_DIR:-$ROOT/app/build/outputs/androidTest-results/connected/qa}"
git_commit="$(git -C "$ROOT" rev-parse --verify HEAD 2>/dev/null || true)"
if [[ ! "$git_commit" =~ ^[0-9a-fA-F]{40,64}$ ]]; then
  git_commit="uncommitted"
fi
tested_apk_payload_sha256=""

hash_text() {
  local value="$1"
  if command -v shasum >/dev/null 2>&1; then
    printf '%s' "$value" | shasum -a 256 | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then
    printf '%s' "$value" | sha256sum | awk '{print $1}'
  else
    printf '%s' "$value" | openssl dgst -sha256 | awk '{print $NF}'
  fi
}

hash_file() {
  local file="$1"
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
  else
    openssl dgst -sha256 "$file" | awk '{print $NF}'
  fi
}

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  value="${value//$'\r'/\\r}"
  value="${value//$'\t'/\\t}"
  printf '%s' "$value"
}

restore_setting() {
  local namespace="$1"
  local key="$2"
  local value="$3"
  local restored_value=""
  local attempt
  for attempt in 1 2 3; do
    if [[ -z "$value" || "$value" == "null" ]]; then
      "$ADB" -s "$serial" shell settings delete "$namespace" "$key" >/dev/null 2>&1 || continue
    else
      "$ADB" -s "$serial" shell settings put "$namespace" "$key" "$value" >/dev/null 2>&1 || continue
    fi
    restored_value="$("$ADB" -s "$serial" shell settings get "$namespace" "$key" 2>/dev/null | tr -d '\r')" || true
    if [[ -z "$value" || "$value" == "null" ]]; then
      [[ -z "$restored_value" || "$restored_value" == "null" ]] && return 0
    elif [[ "$restored_value" == "$value" ]]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

read_wm_size_state() {
  local output physical override
  local physical_count override_count
  output="$("$ADB" -s "$serial" shell wm size 2>/dev/null | tr -d '\r')" || return 1
  physical_count="$(printf '%s\n' "$output" | sed -nE '/^[[:space:]]*Physical size: [0-9]+x[0-9]+[[:space:]]*$/p' | wc -l | tr -d ' ')"
  override_count="$(printf '%s\n' "$output" | sed -nE '/^[[:space:]]*Override size: [0-9]+x[0-9]+[[:space:]]*$/p' | wc -l | tr -d ' ')"
  [[ "$physical_count" == "1" && ( "$override_count" == "0" || "$override_count" == "1" ) ]] || return 1
  physical="$(printf '%s\n' "$output" | sed -nE 's/^[[:space:]]*Physical size: ([0-9]+x[0-9]+)[[:space:]]*$/\1/p')"
  override="$(printf '%s\n' "$output" | sed -nE 's/^[[:space:]]*Override size: ([0-9]+x[0-9]+)[[:space:]]*$/\1/p')"
  [[ "$physical" =~ ^[0-9]+x[0-9]+$ ]] || return 1
  current_wm_size_output="$output"
  current_wm_physical_size="$physical"
  current_wm_size_override="$override"
  current_wm_effective_size="${override:-$physical}"
}

read_wm_density_state() {
  local output physical override
  local physical_count override_count
  output="$("$ADB" -s "$serial" shell wm density 2>/dev/null | tr -d '\r')" || return 1
  physical_count="$(printf '%s\n' "$output" | sed -nE '/^[[:space:]]*Physical density: [0-9]+[[:space:]]*$/p' | wc -l | tr -d ' ')"
  override_count="$(printf '%s\n' "$output" | sed -nE '/^[[:space:]]*Override density: [0-9]+[[:space:]]*$/p' | wc -l | tr -d ' ')"
  [[ "$physical_count" == "1" && ( "$override_count" == "0" || "$override_count" == "1" ) ]] || return 1
  physical="$(printf '%s\n' "$output" | sed -nE 's/^[[:space:]]*Physical density: ([0-9]+)[[:space:]]*$/\1/p')"
  override="$(printf '%s\n' "$output" | sed -nE 's/^[[:space:]]*Override density: ([0-9]+)[[:space:]]*$/\1/p')"
  [[ "$physical" =~ ^[0-9]+$ ]] || return 1
  current_wm_density_output="$output"
  current_wm_physical_density="$physical"
  current_wm_density_override="$override"
  current_wm_effective_density="${override:-$physical}"
}

wm_size_override_matches() {
  local expected_override="$1"
  local expected_effective
  read_wm_size_state || return 1
  expected_effective="${expected_override:-$current_wm_physical_size}"
  [[ "$current_wm_size_override" == "$expected_override" &&
    "$current_wm_effective_size" == "$expected_effective" ]]
}

wm_density_override_matches() {
  local expected_override="$1"
  local expected_effective
  read_wm_density_state || return 1
  expected_effective="${expected_override:-$current_wm_physical_density}"
  [[ "$current_wm_density_override" == "$expected_override" &&
    "$current_wm_effective_density" == "$expected_effective" ]]
}

ensure_wm_size_override() {
  local expected_override="$1"
  local expected_effective
  read_wm_size_state || return 1
  if [[ "$wm_size_captured" == "1" && "$current_wm_physical_size" != "$original_wm_physical_size" ]]; then
    return 1
  fi
  expected_effective="${expected_override:-$current_wm_physical_size}"
  if [[ "$current_wm_size_override" == "$expected_override" ]]; then
    [[ "$current_wm_effective_size" == "$expected_effective" ]]
    return
  fi
  if [[ -n "$expected_override" ]]; then
    "$ADB" -s "$serial" shell wm size "$expected_override" >/dev/null 2>&1 || return 1
  else
    "$ADB" -s "$serial" shell wm size reset >/dev/null 2>&1 || return 1
  fi
  wm_size_override_matches "$expected_override" || return 1
  [[ "$wm_size_captured" != "1" || "$current_wm_physical_size" == "$original_wm_physical_size" ]]
}

ensure_wm_density_override() {
  local expected_override="$1"
  local expected_effective
  local physical_density
  read_wm_density_state || return 1
  if [[ "$wm_density_captured" == "1" && "$current_wm_physical_density" != "$original_wm_physical_density" ]]; then
    return 1
  fi
  physical_density="$current_wm_physical_density"
  expected_effective="${expected_override:-$current_wm_physical_density}"
  if [[ "$current_wm_density_override" == "$expected_override" ]]; then
    [[ "$current_wm_effective_density" == "$expected_effective" ]]
    return
  fi
  if [[ -n "$expected_override" ]]; then
    "$ADB" -s "$serial" shell wm density "$expected_override" >/dev/null 2>&1 || return 1
  else
    "$ADB" -s "$serial" shell wm density reset >/dev/null 2>&1 || return 1
  fi
  if [[ -z "$expected_override" ]] && ! wm_density_override_matches "$expected_override"; then
    "$ADB" -s "$serial" shell wm density "$physical_density" >/dev/null 2>&1 || return 1
  fi
  wm_density_override_matches "$expected_override" || return 1
  [[ "$wm_density_captured" != "1" || "$current_wm_physical_density" == "$original_wm_physical_density" ]]
}

restore_wm_size() {
  ensure_wm_size_override "$original_wm_size_override"
}

restore_wm_density() {
  ensure_wm_density_override "$original_wm_density_override"
}

original_display_state_matches() {
  read_wm_size_state || return 1
  [[ "$current_wm_physical_size" == "$original_wm_physical_size" &&
    "$current_wm_size_override" == "$original_wm_size_override" &&
    "$current_wm_effective_size" == "$original_wm_effective_size" ]] || return 1
  read_wm_density_state || return 1
  [[ "$current_wm_physical_density" == "$original_wm_physical_density" &&
    "$current_wm_density_override" == "$original_wm_density_override" &&
    "$current_wm_effective_density" == "$original_wm_effective_density" ]]
}

restore_display_overrides_and_font() {
  local restore_status=0
  if ! restore_wm_size; then
    restore_status=1
  fi
  if ! restore_wm_density; then
    restore_status=1
  fi
  if ! restore_setting system font_scale "$original_font_scale"; then
    restore_status=1
  fi
  if ! original_display_state_matches; then
    restore_status=1
  fi
  return "$restore_status"
}

set_stage_status() {
  local stage="$1"
  local status="$2"
  case "$stage" in
    portrait_smoke) portrait_smoke_status="$status" ;;
    font_scale_2_0_first_launch_legal_notices) font_scale_2_status="$status" ;;
    compact_portrait_settings) compact_portrait_settings_status="$status" ;;
    compact_landscape_rotation_1) compact_landscape_rotation_1_status="$status" ;;
    compact_landscape_rotation_3) compact_landscape_rotation_3_status="$status" ;;
    landscape_rotation_1)
      landscape_rotation_1_status="$status"
      orientation_1_test_status="$status"
      ;;
    landscape_rotation_3)
      landscape_rotation_3_status="$status"
      orientation_3_test_status="$status"
      ;;
    *)
      echo "Unknown Samsung QA stage: $stage" >&2
      return 1
      ;;
  esac
}

mark_running_stage_interrupted() {
  [[ "$portrait_smoke_status" != "running" ]] || portrait_smoke_status="interrupted"
  [[ "$font_scale_2_status" != "running" ]] || font_scale_2_status="interrupted"
  [[ "$compact_portrait_settings_status" != "running" ]] || compact_portrait_settings_status="interrupted"
  [[ "$compact_landscape_rotation_1_status" != "running" ]] || compact_landscape_rotation_1_status="interrupted"
  [[ "$compact_landscape_rotation_3_status" != "running" ]] || compact_landscape_rotation_3_status="interrupted"
  if [[ "$landscape_rotation_1_status" == "running" ]]; then
    set_stage_status landscape_rotation_1 interrupted
  fi
  if [[ "$landscape_rotation_3_status" == "running" ]]; then
    set_stage_status landscape_rotation_3 interrupted
  fi
}

write_evidence_manifest() {
  local exit_code="$1"
  local cleanup_status="$2"
  local result_status="failed"
  local apk_sha256="unavailable"
  local apk_payload_sha256="unavailable"
  local generated_at manifest_path temporary_path

  if [[ "$exit_code" == "0" && "$qa_status" == "passed" && "$cleanup_status" == "passed" ]]; then
    result_status="passed"
  elif [[ "$exit_code" == "129" || "$exit_code" == "130" || "$exit_code" == "143" ]]; then
    result_status="interrupted"
  fi
  if [[ -f "$qa_apk" ]]; then
    apk_sha256="$(hash_file "$qa_apk")"
    apk_payload_sha256="$("$APK_PAYLOAD_DIGEST" "$qa_apk")"
  fi

  generated_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  mkdir -p "$evidence_dir"
  manifest_path="$evidence_dir/samsung-qa-${serial_sha256:0:16}-$(date -u '+%Y%m%dT%H%M%SZ')-$$.json"
  temporary_path="$manifest_path.tmp"

  printf '%s\n' \
    '{' \
    '  "schema_version": 6,' \
    "  \"generated_at_utc\": \"$(json_escape "$generated_at")\"," \
    '  "source": {' \
    "    \"git_commit\": \"$(json_escape "$git_commit")\"" \
    '  },' \
    '  "device": {' \
    "    \"serial_sha256\": \"$(json_escape "$serial_sha256")\"," \
    "    \"manufacturer\": \"$(json_escape "$manufacturer")\"," \
    "    \"model\": \"$(json_escape "$model")\"," \
    "    \"android_version\": \"$(json_escape "$android_version")\"," \
    "    \"build_fingerprint\": \"$(json_escape "$build_fingerprint")\"," \
    "    \"one_ui_version\": \"$(json_escape "${one_ui_version:-unknown}")\"," \
    "    \"locale\": \"$(json_escape "${device_locale:-unknown}")\"," \
    "    \"size\": \"$(json_escape "${display_size:-unknown}")\"," \
    "    \"density\": \"$(json_escape "${display_density:-unknown}")\"," \
    "    \"font_scale\": \"$(json_escape "${font_scale:-unknown}")\"" \
    '  },' \
    '  "apk": {' \
    "    \"file_name\": \"$(json_escape "$(basename "$qa_apk")")\"," \
    "    \"sha256\": \"$(json_escape "$apk_sha256")\"," \
    "    \"payload_sha256\": \"$(json_escape "$apk_payload_sha256")\"" \
    '  },' \
    '  "orientations": [' \
    "    {\"user_rotation\": 1, \"observed_orientation\": ${orientation_1_observed:-null}, \"logical_width\": ${orientation_1_width:-null}, \"logical_height\": ${orientation_1_height:-null}, \"verified_landscape\": $orientation_1_verified, \"test_status\": \"$(json_escape "$orientation_1_test_status")\"}," \
    "    {\"user_rotation\": 3, \"observed_orientation\": ${orientation_3_observed:-null}, \"logical_width\": ${orientation_3_width:-null}, \"logical_height\": ${orientation_3_height:-null}, \"verified_landscape\": $orientation_3_verified, \"test_status\": \"$(json_escape "$orientation_3_test_status")\"}" \
    '  ],' \
    '  "stages": {' \
    "    \"portrait_smoke\": {\"status\": \"$(json_escape "$portrait_smoke_status")\", \"user_rotation\": 0, \"display_profile\": \"captured\", \"tests\": $portrait_smoke_tests, \"skipped\": $portrait_smoke_skipped}," \
    "    \"font_scale_2_0_first_launch_legal_notices\": {\"status\": \"$(json_escape "$font_scale_2_status")\", \"user_rotation\": 0, \"font_scale\": \"2.0\", \"tests\": $font_scale_2_tests, \"skipped\": $font_scale_2_skipped}," \
    "    \"compact_portrait_settings\": {\"status\": \"$(json_escape "$compact_portrait_settings_status")\", \"user_rotation\": 0, \"wm_size\": \"$(json_escape "$COMPACT_WM_SIZE")\", \"wm_density\": \"$(json_escape "$COMPACT_WM_DENSITY")\", \"font_scale\": \"$(json_escape "$COMPACT_FONT_SCALE")\", \"tests\": $compact_portrait_settings_tests, \"skipped\": $compact_portrait_settings_skipped}," \
    "    \"compact_landscape_rotation_1\": {\"status\": \"$(json_escape "$compact_landscape_rotation_1_status")\", \"user_rotation\": 1, \"wm_size\": \"$(json_escape "$COMPACT_LANDSCAPE_WM_SIZE")\", \"wm_density\": \"$(json_escape "$COMPACT_LANDSCAPE_WM_DENSITY")\", \"tests\": $compact_landscape_rotation_1_tests, \"skipped\": $compact_landscape_rotation_1_skipped}," \
    "    \"compact_landscape_rotation_3\": {\"status\": \"$(json_escape "$compact_landscape_rotation_3_status")\", \"user_rotation\": 3, \"wm_size\": \"$(json_escape "$COMPACT_LANDSCAPE_WM_SIZE")\", \"wm_density\": \"$(json_escape "$COMPACT_LANDSCAPE_WM_DENSITY")\", \"tests\": $compact_landscape_rotation_3_tests, \"skipped\": $compact_landscape_rotation_3_skipped}," \
    "    \"landscape_rotation_1\": {\"status\": \"$(json_escape "$landscape_rotation_1_status")\", \"user_rotation\": 1, \"display_profile\": \"captured\", \"tests\": $landscape_rotation_1_tests, \"skipped\": $landscape_rotation_1_skipped}," \
    "    \"landscape_rotation_3\": {\"status\": \"$(json_escape "$landscape_rotation_3_status")\", \"user_rotation\": 3, \"display_profile\": \"captured\", \"tests\": $landscape_rotation_3_tests, \"skipped\": $landscape_rotation_3_skipped}" \
    '  },' \
    '  "result": {' \
    "    \"status\": \"$(json_escape "$result_status")\"," \
    "    \"test_status\": \"$(json_escape "$qa_status")\"," \
    "    \"cleanup_status\": \"$(json_escape "$cleanup_status")\"," \
    "    \"exit_code\": $exit_code" \
    '  }' \
    '}' > "$temporary_path"
  mv "$temporary_path" "$manifest_path"
  echo "Samsung QA evidence: $manifest_path"
}

cleanup() {
  local exit_code=$?
  local final_exit_code=$exit_code
  local cleanup_status="passed"
  trap - EXIT
  set +e

  if [[ "$exit_code" == "129" || "$exit_code" == "130" || "$exit_code" == "143" ]]; then
    mark_running_stage_interrupted
  fi

  if [[ -n "${watcher_pid:-}" ]]; then
    kill "$watcher_pid" 2>/dev/null || true
    wait "$watcher_pid" 2>/dev/null || true
  fi

  if [[ "$rotation_probe_installed" == "1" ]]; then
    if ! stop_rotation_probe; then
      echo "Failed to remove the V Slot rotation probe from Samsung $model." >&2
      cleanup_status="failed"
    fi
  fi

  if [[ "$wm_size_captured" == "1" ]]; then
    if ! restore_wm_size; then
      echo "Failed to restore wm size on Samsung $model." >&2
      cleanup_status="failed"
    fi
  fi
  if [[ "$wm_density_captured" == "1" ]]; then
    if ! restore_wm_density; then
      echo "Failed to restore wm density on Samsung $model." >&2
      cleanup_status="failed"
    fi
  fi
  if [[ "$wm_size_captured" == "1" && "$wm_density_captured" == "1" ]] &&
    ! original_display_state_matches; then
    echo "Failed to restore exact physical, override, and effective display state on Samsung $model." >&2
    cleanup_status="failed"
  fi
  if [[ "$font_scale_captured" == "1" ]]; then
    if ! restore_setting system font_scale "$original_font_scale"; then
      echo "Failed to restore font_scale on Samsung $model." >&2
      cleanup_status="failed"
    fi
  fi
  if [[ "$user_rotation_captured" == "1" ]]; then
    if ! restore_setting system user_rotation "$original_user_rotation"; then
      echo "Failed to restore user_rotation on Samsung $model." >&2
      cleanup_status="failed"
    fi
  fi
  if [[ "$accelerometer_rotation_captured" == "1" ]]; then
    if ! restore_setting system accelerometer_rotation "$original_accelerometer_rotation"; then
      echo "Failed to restore accelerometer_rotation on Samsung $model." >&2
      cleanup_status="failed"
    fi
  fi

  if [[ "$power_state_captured" == "1" ]]; then
    if ! restore_setting global stay_on_while_plugged_in "$original_stay_on_while_plugged_in"; then
      echo "Failed to restore stay_on_while_plugged_in on Samsung $model." >&2
      cleanup_status="failed"
    fi
  fi

  if [[ "$cleanup_status" != "passed" && "$final_exit_code" == "0" ]]; then
    final_exit_code=1
  fi
  if [[ "$evidence_ready" == "1" ]]; then
    if ! write_evidence_manifest "$final_exit_code" "$cleanup_status"; then
      echo "Failed to write Samsung QA evidence manifest in $evidence_dir." >&2
      [[ "$final_exit_code" == "0" ]] && final_exit_code=1
    fi
  fi

  release_lock
  exit "$final_exit_code"
}

trap cleanup EXIT

if [[ -n "$(conflicting_qa_pids)" ]]; then
  if [[ "${V_SLOT_STOP_EXTERNAL_QA:-0}" == "1" ]]; then
    echo "Stopping conflicting Samsung QA process for $serial."
    stop_conflicting_qa
    sleep 1
  else
    echo "Another Dragon Slots Samsung QA process is already using $serial. Stop it before running V Slot tests." >&2
    print_conflicting_qa >&2
    exit 2
  fi
fi

if [[ "${V_SLOT_STOP_EXTERNAL_QA:-0}" == "1" ]]; then
  (
    while true; do
      sleep 1
      stop_conflicting_qa || true
      stop_conflicting_app || true
      keep_device_awake || true
    done
  ) &
  watcher_pid="$!"
fi

echo "Running V Slot connected tests on Samsung $model (Android $android_version, serial $serial)."

if [[ "${V_SLOT_SKIP_CONFLICT_STOP:-0}" != "1" ]]; then
  stop_conflicting_app
fi

require_device_unlocked
if original_stay_on_while_plugged_in="$("$ADB" -s "$serial" shell settings get global stay_on_while_plugged_in 2>/dev/null | tr -d '\r')"; then
  power_state_captured=1
fi
if original_accelerometer_rotation="$("$ADB" -s "$serial" shell settings get system accelerometer_rotation 2>/dev/null | tr -d '\r')"; then
  accelerometer_rotation_captured=1
fi
if original_user_rotation="$("$ADB" -s "$serial" shell settings get system user_rotation 2>/dev/null | tr -d '\r')"; then
  user_rotation_captured=1
fi
if read_wm_size_state; then
  display_size="$current_wm_size_output"
  original_wm_physical_size="$current_wm_physical_size"
  original_wm_size_override="$current_wm_size_override"
  original_wm_effective_size="$current_wm_effective_size"
  wm_size_captured=1
fi
if read_wm_density_state; then
  display_density="$current_wm_density_output"
  original_wm_physical_density="$current_wm_physical_density"
  original_wm_density_override="$current_wm_density_override"
  original_wm_effective_density="$current_wm_effective_density"
  wm_density_captured=1
fi
if original_font_scale="$("$ADB" -s "$serial" shell settings get system font_scale 2>/dev/null | tr -d '\r')"; then
  font_scale_captured=1
  font_scale="$original_font_scale"
fi
if [[ "$power_state_captured" != "1" || "$accelerometer_rotation_captured" != "1" || "$user_rotation_captured" != "1" ||
  "$wm_size_captured" != "1" || "$wm_density_captured" != "1" || "$font_scale_captured" != "1" ]]; then
  echo "Could not capture Samsung power, wm size/density, font scale, and rotation settings; refusing to modify the device." >&2
  exit 1
fi

display_size="$(printf '%s' "$display_size" | tr '\n' ';')"
display_density="$(printf '%s' "$display_density" | tr '\n' ';')"
serial_sha256="$(hash_text "$serial")"
evidence_ready=1

"$ADB" -s "$serial" shell svc power stayon true >/dev/null 2>&1 || true
"$ADB" -s "$serial" shell input keyevent 82 >/dev/null 2>&1 || true

verify_display_rotation() {
  local expected_rotation="$1"
  local expected_layout="$2"
  local attempt=1
  local max_attempts="${V_SLOT_ROTATION_VERIFY_ATTEMPTS:-15}"
  local display_dump window_dump rotation_token orientation width height dimension_line
  local accelerometer_rotation user_rotation
  if [[ ! "$max_attempts" =~ ^[0-9]+$ || "$max_attempts" -lt 1 ]]; then
    echo "V_SLOT_ROTATION_VERIFY_ATTEMPTS must be a positive integer." >&2
    return 1
  fi
  while [[ "$attempt" -le "$max_attempts" ]]; do
    window_dump="$("$ADB" -s "$serial" shell dumpsys window displays 2>/dev/null | tr -d '\r' || true)"
    display_dump="$("$ADB" -s "$serial" shell dumpsys display 2>/dev/null | tr -d '\r' || true)"
    rotation_token="$(printf '%s\n' "$window_dump" | sed -nE 's/.*mRotation=(ROTATION_)?(270|180|90|[0-3]).*/\2/p' | head -n 1 || true)"
    case "$rotation_token" in
      0) orientation=0 ;;
      1|90) orientation=1 ;;
      2|180) orientation=2 ;;
      3|270) orientation=3 ;;
      *) orientation="" ;;
    esac
    accelerometer_rotation="$("$ADB" -s "$serial" shell settings get system accelerometer_rotation 2>/dev/null | tr -d '\r' || true)"
    user_rotation="$("$ADB" -s "$serial" shell settings get system user_rotation 2>/dev/null | tr -d '\r' || true)"

    dimension_line="$(printf '%s\n' "$window_dump" | grep -m 1 -E 'cur=[0-9]+x[0-9]+' || true)"
    width="$(printf '%s\n' "$dimension_line" | sed -nE 's/.*cur=([0-9]+)x([0-9]+).*/\1/p' | head -n 1 || true)"
    height="$(printf '%s\n' "$dimension_line" | sed -nE 's/.*cur=([0-9]+)x([0-9]+).*/\2/p' | head -n 1 || true)"
    if [[ -z "$width" || -z "$height" ]]; then
      dimension_line="$(printf '%s\n' "$display_dump" | grep -m 1 -E 'logicalWidth=[0-9]+.*logicalHeight=[0-9]+|logical [0-9]+ x [0-9]+|mOverrideDisplayInfo=.*real [0-9]+ x [0-9]+|mBaseDisplayInfo=.*real [0-9]+ x [0-9]+' || true)"
      width="$(printf '%s\n' "$dimension_line" | sed -nE 's/.*logicalWidth=([0-9]+).*logicalHeight=([0-9]+).*/\1/p' | head -n 1 || true)"
      height="$(printf '%s\n' "$dimension_line" | sed -nE 's/.*logicalWidth=([0-9]+).*logicalHeight=([0-9]+).*/\2/p' | head -n 1 || true)"
      if [[ -z "$width" || -z "$height" ]]; then
        width="$(printf '%s\n' "$dimension_line" | sed -nE 's/.*logical ([0-9]+) x ([0-9]+).*/\1/p' | head -n 1 || true)"
        height="$(printf '%s\n' "$dimension_line" | sed -nE 's/.*logical ([0-9]+) x ([0-9]+).*/\2/p' | head -n 1 || true)"
      fi
      if [[ -z "$width" || -z "$height" ]]; then
        width="$(printf '%s\n' "$dimension_line" | sed -nE 's/.*real ([0-9]+) x ([0-9]+).*/\1/p' | head -n 1 || true)"
        height="$(printf '%s\n' "$dimension_line" | sed -nE 's/.*real ([0-9]+) x ([0-9]+).*/\2/p' | head -n 1 || true)"
      fi
    fi

    if [[ "$accelerometer_rotation" == "0" && "$user_rotation" == "$expected_rotation" &&
      "$orientation" == "$expected_rotation" && "$width" =~ ^[0-9]+$ && "$height" =~ ^[0-9]+$ ]] &&
      { [[ "$expected_layout" == "portrait" && "$height" -gt "$width" ]] ||
        [[ "$expected_layout" == "landscape" && "$width" -gt "$height" ]]; }; then
      verified_orientation="$orientation"
      verified_width="$width"
      verified_height="$height"
      return 0
    fi
    if [[ "$attempt" -lt "$max_attempts" ]]; then
      sleep 1
    fi
    attempt=$((attempt + 1))
  done

  verified_orientation="${orientation:-}"
  verified_width="${width:-}"
  verified_height="${height:-}"
  echo "Samsung rotation $expected_rotation was not verified as $expected_layout: display_rotation=${orientation:-unknown}, user_rotation=${user_rotation:-unknown}, accelerometer_rotation=${accelerometer_rotation:-unknown}, logical=${width:-unknown}x${height:-unknown}." >&2
  printf '%s\n' "$window_dump" | grep -E 'mRotation=| init=| cur=' | head -n 12 >&2 || true
  printf '%s\n' "$display_dump" | grep -E 'logicalWidth|logicalHeight|DisplayInfo' | head -n 12 >&2 || true
  return 1
}

verify_portrait_rotation() {
  verify_display_rotation 0 portrait
}

verify_landscape_rotation() {
  verify_display_rotation "$1" landscape
}

record_orientation_observation() {
  local rotation="$1"
  local verified="$2"
  if [[ "$rotation" == "1" ]]; then
    orientation_1_verified="$verified"
    orientation_1_observed="$verified_orientation"
    orientation_1_width="$verified_width"
    orientation_1_height="$verified_height"
  else
    orientation_3_verified="$verified"
    orientation_3_observed="$verified_orientation"
    orientation_3_width="$verified_width"
    orientation_3_height="$verified_height"
  fi
}

force_rotation() {
  local rotation="$1"
  local settle_seconds="${V_SLOT_ROTATION_SETTLE_SECONDS:-2}"
  local apply_attempts="${V_SLOT_ROTATION_APPLY_ATTEMPTS:-3}"
  local attempt=1
  local expected_layout
  if [[ ! "$settle_seconds" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
    echo "V_SLOT_ROTATION_SETTLE_SECONDS must be a non-negative number." >&2
    return 1
  fi
  if [[ ! "$apply_attempts" =~ ^[0-9]+$ || "$apply_attempts" -lt 1 ]]; then
    echo "V_SLOT_ROTATION_APPLY_ATTEMPTS must be a positive integer." >&2
    return 1
  fi
  case "$rotation" in
    0|2) expected_layout=portrait ;;
    1|3) expected_layout=landscape ;;
    *) return 1 ;;
  esac
  while [[ "$attempt" -le "$apply_attempts" ]]; do
    sleep "$settle_seconds"
    "$ADB" -s "$serial" shell wm user-rotation lock "$rotation" >/dev/null || return 1
    if verify_display_rotation "$rotation" "$expected_layout"; then
      return 0
    fi
    attempt=$((attempt + 1))
  done
  return 1
}

start_rotation_probe() {
  if [[ ! -f "$qa_apk" ]]; then
    echo "QA APK not found at $qa_apk; Samsung rotation cannot be verified with V Slot in the foreground." >&2
    return 1
  fi
  "$ADB" -s "$serial" install -r -t -d "$qa_apk" >/dev/null || return 1
  rotation_probe_installed=1
  "$ADB" -s "$serial" shell am force-stop "$QA_APPLICATION_ID" >/dev/null 2>&1 || true
  "$ADB" -s "$serial" shell am start -W -n "$QA_APPLICATION_ID/com.vslot.app.MainActivity" >/dev/null 2>&1 || return 1
  verify_rotation_probe_foreground
}

verify_rotation_probe_foreground() {
  local max_attempts="${V_SLOT_FOREGROUND_VERIFY_ATTEMPTS:-5}"
  local settle_seconds="${V_SLOT_FOREGROUND_SETTLE_SECONDS:-1}"
  local attempt=1
  local activity_dump=""
  if [[ ! "$max_attempts" =~ ^[0-9]+$ || "$max_attempts" -lt 1 ]]; then
    echo "V_SLOT_FOREGROUND_VERIFY_ATTEMPTS must be a positive integer." >&2
    return 1
  fi
  if [[ ! "$settle_seconds" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
    echo "V_SLOT_FOREGROUND_SETTLE_SECONDS must be a non-negative number." >&2
    return 1
  fi
  while [[ "$attempt" -le "$max_attempts" ]]; do
    activity_dump="$("$ADB" -s "$serial" shell dumpsys activity activities 2>/dev/null || true)"
    if printf '%s\n' "$activity_dump" |
      grep -Eq 'mResumedActivity:.*com\.vslot\.app\.qa/com\.vslot\.app\.MainActivity'; then
      return 0
    fi
    if [[ "$attempt" -lt "$max_attempts" ]]; then
      sleep "$settle_seconds"
    fi
    attempt=$((attempt + 1))
  done
  echo "V Slot QA rotation probe did not become the resumed activity on Samsung $serial." >&2
  printf '%s\n' "$activity_dump" | grep -E 'mResumedActivity|topResumedActivity' | head -n 4 >&2 || true
  return 1
}

stop_rotation_probe() {
  [[ "$rotation_probe_installed" == "1" ]] || return 0
  "$ADB" -s "$serial" shell am force-stop "$QA_APPLICATION_ID" >/dev/null 2>&1 || true
  "$ADB" -s "$serial" uninstall "$QA_APPLICATION_ID" >/dev/null || return 1
  rotation_probe_installed=0
}

set_font_scale() {
  local expected="$1"
  local actual
  "$ADB" -s "$serial" shell settings put system font_scale "$expected" >/dev/null || return 1
  actual="$("$ADB" -s "$serial" shell settings get system font_scale 2>/dev/null | tr -d '\r')" || return 1
  [[ "$actual" == "$expected" ]]
}

set_stage_counts() {
  local stage="$1"
  local tests="$2"
  local skipped="$3"
  case "$stage" in
    portrait_smoke)
      portrait_smoke_tests="$tests"
      portrait_smoke_skipped="$skipped"
      ;;
    font_scale_2_0_first_launch_legal_notices)
      font_scale_2_tests="$tests"
      font_scale_2_skipped="$skipped"
      ;;
    compact_portrait_settings)
      compact_portrait_settings_tests="$tests"
      compact_portrait_settings_skipped="$skipped"
      ;;
    compact_landscape_rotation_1)
      compact_landscape_rotation_1_tests="$tests"
      compact_landscape_rotation_1_skipped="$skipped"
      ;;
    compact_landscape_rotation_3)
      compact_landscape_rotation_3_tests="$tests"
      compact_landscape_rotation_3_skipped="$skipped"
      ;;
    landscape_rotation_1)
      landscape_rotation_1_tests="$tests"
      landscape_rotation_1_skipped="$skipped"
      ;;
    landscape_rotation_3)
      landscape_rotation_3_tests="$tests"
      landscape_rotation_3_skipped="$skipped"
      ;;
    *)
      echo "Unknown Samsung QA stage: $stage" >&2
      return 1
      ;;
  esac
}

xml_suite_count() {
  local file="$1"
  local attribute="$2"
  sed -nE "s/.* ${attribute}=\"([0-9]+)\".*/\1/p" "$file" | head -n 1
}

verify_and_archive_connected_results() {
  local stage="$1"
  local expected_tests="$2"
  local expected_skipped="${3:-0}"
  local archive_dir="$evidence_dir/stage-results/$stage"
  local file value
  local tests=0
  local failures=0
  local errors=0
  local skipped=0
  local report_count=0

  rm -rf "$archive_dir"
  mkdir -p "$archive_dir"
  while IFS= read -r file; do
    [[ -n "$file" ]] || continue
    report_count=$((report_count + 1))
    cp "$file" "$archive_dir/"
    for attribute in tests failures errors skipped; do
      value="$(xml_suite_count "$file" "$attribute")"
      if [[ ! "$value" =~ ^[0-9]+$ ]]; then
        echo "Could not parse $attribute from connected-test report $file." >&2
        return 1
      fi
      case "$attribute" in
        tests) tests=$((tests + value)) ;;
        failures) failures=$((failures + value)) ;;
        errors) errors=$((errors + value)) ;;
        skipped) skipped=$((skipped + value)) ;;
      esac
    done
  done < <(find "$connected_results_dir" -type f -name 'TEST-*.xml' -print 2>/dev/null | sort)

  set_stage_counts "$stage" "$tests" "$skipped"
  if [[ "$report_count" == "0" ]]; then
    echo "No connected-test XML report was produced for Samsung QA stage $stage." >&2
    return 1
  fi
  if [[ "$failures" != "0" || "$errors" != "0" || "$skipped" != "$expected_skipped" ||
    ( -n "$expected_tests" && "$tests" != "$expected_tests" ) ||
    ( -z "$expected_tests" && "$tests" -lt 1 ) ]]; then
    echo "Samsung QA stage $stage report mismatch: tests=$tests expected=${expected_tests:-positive} failures=$failures errors=$errors skipped=$skipped expected_skipped=$expected_skipped." >&2
    return 1
  fi
}

verify_tested_apk_stable() {
  local current_payload_sha256
  if [[ ! -f "$qa_apk" ]]; then
    echo "QA APK not found at $qa_apk after connected tests." >&2
    return 1
  fi
  current_payload_sha256="$("$APK_PAYLOAD_DIGEST" "$qa_apk")"
  if [[ -z "$tested_apk_payload_sha256" ]]; then
    tested_apk_payload_sha256="$current_payload_sha256"
  elif [[ "$current_payload_sha256" != "$tested_apk_payload_sha256" ]]; then
    echo "QA APK payload changed between mandatory Samsung stages." >&2
    return 1
  fi
}

run_filtered_connected_test() {
  local stage="$1"
  local test_filter="$2"
  local expected_tests="$3"
  rm -rf "$connected_results_dir"
  ANDROID_SERIAL="$serial" "$GRADLE" connectedQaAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$test_filter" || return $?
  verify_tested_apk_stable || return $?
  verify_and_archive_connected_results "$stage" "$expected_tests"
}

run_portrait_smoke_stage() {
  local stage="portrait_smoke"
  local gradle_exit_code
  set_stage_status "$stage" "running"
  echo "Running mandatory Samsung portrait smoke stage."
  if ! restore_display_overrides_and_font || ! force_rotation 0; then
    set_stage_status "$stage" "configuration_failed"
    return 1
  fi
  if ! verify_portrait_rotation; then
    set_stage_status "$stage" "orientation_failed"
    return 1
  fi
  if run_filtered_connected_test "$stage" "$PORTRAIT_SMOKE_TEST" 1; then
    if verify_portrait_rotation; then
      set_stage_status "$stage" "passed"
    else
      set_stage_status "$stage" "postcondition_failed"
      return 1
    fi
  else
    gradle_exit_code=$?
    set_stage_status "$stage" "failed"
    return "$gradle_exit_code"
  fi
}

run_large_font_legal_stage() {
  local stage="font_scale_2_0_first_launch_legal_notices"
  local gradle_exit_code
  set_stage_status "$stage" "running"
  echo "Running Samsung first-launch/legal/notices stage at font_scale=2.0."
  if ! restore_wm_size || ! restore_wm_density || ! set_font_scale 2.0 || ! force_rotation 0; then
    set_stage_status "$stage" "configuration_failed"
    return 1
  fi
  if ! verify_portrait_rotation; then
    set_stage_status "$stage" "orientation_failed"
    return 1
  fi
  if run_filtered_connected_test "$stage" "$LARGE_FONT_TESTS" 3; then
    if verify_portrait_rotation; then
      set_stage_status "$stage" "passed"
    else
      set_stage_status "$stage" "postcondition_failed"
      return 1
    fi
  else
    gradle_exit_code=$?
    set_stage_status "$stage" "failed"
    return "$gradle_exit_code"
  fi
}

run_compact_portrait_settings_stage() {
  local stage="compact_portrait_settings"
  local gradle_exit_code
  set_stage_status "$stage" "running"
  echo "Running Samsung compact portrait Settings stage."
  if ! ensure_wm_size_override "$COMPACT_WM_SIZE" ||
    ! ensure_wm_density_override "$COMPACT_WM_DENSITY" ||
    ! set_font_scale "$COMPACT_FONT_SCALE" ||
    ! force_rotation 0; then
    set_stage_status "$stage" "configuration_failed"
    return 1
  fi
  if ! verify_portrait_rotation; then
    set_stage_status "$stage" "orientation_failed"
    return 1
  fi
  if run_filtered_connected_test "$stage" "$COMPACT_SETTINGS_TEST" 1; then
    if verify_portrait_rotation; then
      set_stage_status "$stage" "passed"
    else
      set_stage_status "$stage" "postcondition_failed"
      return 1
    fi
  else
    gradle_exit_code=$?
    set_stage_status "$stage" "failed"
    return "$gradle_exit_code"
  fi
}

run_compact_landscape_stage() {
  local rotation="$1"
  local stage="compact_landscape_rotation_$rotation"
  local gradle_exit_code
  set_stage_status "$stage" "running"
  echo "Running Samsung compact landscape stage at rotation $rotation."
  if ! ensure_wm_size_override "$COMPACT_LANDSCAPE_WM_SIZE" ||
    ! ensure_wm_density_override "$COMPACT_LANDSCAPE_WM_DENSITY" ||
    ! set_font_scale "$COMPACT_FONT_SCALE" ||
    ! start_rotation_probe ||
    ! force_rotation "$rotation"; then
    set_stage_status "$stage" "configuration_failed"
    return 1
  fi
  if ! verify_landscape_rotation "$rotation" || ! stop_rotation_probe; then
    set_stage_status "$stage" "orientation_failed"
    return 1
  fi
  if run_filtered_connected_test "$stage" "$COMPACT_LANDSCAPE_TEST" 1; then
    if start_rotation_probe &&
      force_rotation "$rotation" &&
      verify_landscape_rotation "$rotation" &&
      stop_rotation_probe; then
      set_stage_status "$stage" "passed"
    else
      set_stage_status "$stage" "postcondition_failed"
      return 1
    fi
  else
    gradle_exit_code=$?
    set_stage_status "$stage" "failed"
    return "$gradle_exit_code"
  fi
}

run_rotation_qa() {
  local rotation="$1"
  local stage="landscape_rotation_$rotation"
  local gradle_exit_code
  set_stage_status "$stage" "running"
  echo "Verifying Samsung landscape rotation $rotation."
  if ! restore_display_overrides_and_font ||
    ! start_rotation_probe ||
    ! force_rotation "$rotation"; then
    set_stage_status "$stage" "configuration_failed"
    return 1
  fi
  if ! verify_landscape_rotation "$rotation" || ! stop_rotation_probe; then
    record_orientation_observation "$rotation" false
    set_stage_status "$stage" "orientation_failed"
    return 1
  fi
  record_orientation_observation "$rotation" true

  rm -rf "$connected_results_dir"
  if ANDROID_SERIAL="$serial" "$GRADLE" connectedQaAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.notClass=com.vslot.app.SlotFrameMetricsTest"; then
    if ! verify_tested_apk_stable ||
      ! verify_and_archive_connected_results \
        "$stage" "$FULL_SUITE_EXPECTED_TESTS" "$FULL_SUITE_EXPECTED_SKIPPED"; then
      set_stage_status "$stage" "failed"
      return 1
    fi
    if start_rotation_probe &&
      force_rotation "$rotation" &&
      verify_landscape_rotation "$rotation" &&
      stop_rotation_probe; then
      record_orientation_observation "$rotation" true
      set_stage_status "$stage" "passed"
    else
      record_orientation_observation "$rotation" false
      set_stage_status "$stage" "postcondition_failed"
      return 1
    fi
  else
    gradle_exit_code=$?
    set_stage_status "$stage" "failed"
    return "$gradle_exit_code"
  fi
}

run_stage_or_exit() {
  local stage_exit_code
  require_device_unlocked
  if "$@"; then
    return 0
  else
    stage_exit_code=$?
    qa_status="failed"
    exit "$stage_exit_code"
  fi
}

if [[ -z "${JAVA_HOME:-}" && -d "$HOME/.cache/codex-jdks/jdk-17" ]]; then
  export JAVA_HOME="$HOME/.cache/codex-jdks/jdk-17"
fi

cd "$ROOT"
if [[ "${V_SLOT_SKIP_REPORT_CLEANUP:-0}" != "1" ]]; then
  rm -rf \
    "$ROOT/app/build/reports/androidTests/connected" \
    "$ROOT/app/build/outputs/androidTest-results/connected"
fi
qa_status="running"
run_stage_or_exit run_portrait_smoke_stage
run_stage_or_exit run_large_font_legal_stage
run_stage_or_exit run_compact_portrait_settings_stage
run_stage_or_exit run_compact_landscape_stage 1
run_stage_or_exit run_compact_landscape_stage 3
run_stage_or_exit run_rotation_qa 1
run_stage_or_exit run_rotation_qa 3
if [[ ! -f "$qa_apk" ]]; then
  echo "QA APK not found at $qa_apk after connected tests; evidence cannot identify the tested artifact." >&2
  qa_status="failed"
  exit 1
fi
qa_status="passed"
