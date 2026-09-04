#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${V_SLOT_ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
GRADLE="${V_SLOT_GRADLE:-$ROOT/gradlew}"
APK_PAYLOAD_DIGEST="${V_SLOT_APK_PAYLOAD_DIGEST:-$ROOT/tools/apk_payload_sha256.sh}"
TEST_CLASS="com.vslot.app.SlotFrameMetricsTest"
QA_APK="${V_SLOT_QA_APK:-$ROOT/app/build/outputs/apk/qa/app-qa.apk}"
EVIDENCE_DIR="${V_SLOT_FRAME_METRICS_EVIDENCE_DIR:-$ROOT/qa/frame-metrics/evidence}"

if [[ ! -x "$ADB" ]]; then
  echo "ADB not found or not executable at $ADB. Set V_SLOT_ADB or ANDROID_HOME." >&2
  exit 1
fi
if [[ ! -x "$GRADLE" ]]; then
  echo "Gradle launcher not found or not executable at $GRADLE." >&2
  exit 1
fi
if [[ ! -x "$APK_PAYLOAD_DIGEST" ]]; then
  echo "APK payload digest tool not found or not executable at $APK_PAYLOAD_DIGEST." >&2
  exit 1
fi

serial="${1:-${ANDROID_SERIAL:-}}"
if [[ -z "$serial" ]]; then
  devices=()
  while IFS= read -r device; do
    [[ -n "$device" ]] && devices+=("$device")
  done < <("$ADB" devices -l | awk '/device / && /model:SM_/ {print $1}')
  if [[ "${#devices[@]}" -ne 1 ]]; then
    echo "Set ANDROID_SERIAL or pass one Samsung serial; exactly one connected Samsung is required for automatic selection." >&2
    "$ADB" devices -l >&2
    exit 1
  fi
  serial="${devices[0]}"
fi

state="$("$ADB" -s "$serial" get-state 2>/dev/null | tr -d '\r' || true)"
if [[ "$state" != "device" ]]; then
  echo "Selected Android device is not ready (state: ${state:-not connected})." >&2
  exit 1
fi

sdk="$("$ADB" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ ! "$sdk" =~ ^[0-9]+$ ]] || (( sdk < 26 )); then
  echo "Slot frame metrics require Android API 26 or newer; selected device reports ${sdk:-unknown}." >&2
  exit 1
fi

manufacturer="$("$ADB" -s "$serial" shell getprop ro.product.manufacturer | tr -d '\r')"
manufacturer_lc="$(printf '%s' "$manufacturer" | tr '[:upper:]' '[:lower:]')"
model="$("$ADB" -s "$serial" shell getprop ro.product.model | tr -d '\r')"
android_version="$("$ADB" -s "$serial" shell getprop ro.build.version.release | tr -d '\r')"
fingerprint="$("$ADB" -s "$serial" shell getprop ro.build.fingerprint | tr -d '\r')"
is_qemu="$("$ADB" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')"

if [[ "$manufacturer_lc" == "samsung" && "$is_qemu" != "1" ]]; then
  frame_profile="physical_samsung"
elif [[ "$is_qemu" == "1" ]]; then
  frame_profile="emulator"
else
  echo "Selected device is $manufacturer $model, not a physical Samsung." >&2
  exit 1
fi

lock_serial="${serial//[^[:alnum:]_.-]/_}"
lock_dir="${TMPDIR:-/tmp}"
lock_dir="${lock_dir%/}/v-slot-samsung-qa-${lock_serial}.lock"
if ! mkdir "$lock_dir" 2>/dev/null; then
  echo "V Slot Samsung QA is already running for the selected device." >&2
  exit 2
fi
printf '%s\n' "$$" > "$lock_dir/pid"

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

json_number_or_null() {
  local value="$1"
  if [[ "$value" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
    printf '%s' "$value"
  else
    printf 'null'
  fi
}

metric_value() {
  local key="$1"
  printf '%s\n' "$metrics_line" | tr ' ' '\n' | awk -F= -v key="$key" '$1 == key {print $2; exit}'
}

serial_sha256="$(hash_text "$serial")"
git_commit="$(git -C "$ROOT" rev-parse --verify HEAD 2>/dev/null || true)"
if [[ ! "$git_commit" =~ ^[0-9a-fA-F]{40,64}$ ]]; then
  git_commit="uncommitted"
fi
qa_status="not_run"
evidence_ready=1
power_state_captured=0
original_stay_on_while_plugged_in=""
metrics_line=""
samples=""
p50_ms=""
p95_ms=""
max_ms=""
jank_rate_pct=""
refresh_hz=""
missed_deadline_threshold_ms=""
missed_deadline_rate_pct=""
dropped_callbacks=""
log_file=""

restore_setting() {
  local namespace="$1"
  local key="$2"
  local value="$3"
  if [[ -z "$value" || "$value" == "null" ]]; then
    "$ADB" -s "$serial" shell settings delete "$namespace" "$key" >/dev/null 2>&1
  else
    "$ADB" -s "$serial" shell settings put "$namespace" "$key" "$value" >/dev/null 2>&1
  fi
}

restore_setting_and_verify() {
  local namespace="$1"
  local key="$2"
  local value="$3"
  local actual=""
  local attempt
  for attempt in 1 2 3; do
    restore_setting "$namespace" "$key" "$value" || continue
    sleep 1
    actual="$("$ADB" -s "$serial" shell settings get "$namespace" "$key" 2>/dev/null | tr -d '\r' || true)"
    if [[ -z "$value" || "$value" == "null" ]]; then
      [[ -z "$actual" || "$actual" == "null" ]] && return 0
    elif [[ "$actual" == "$value" ]]; then
      return 0
    fi
  done
  return 1
}

write_evidence() {
  local exit_code="$1"
  local result_status="failed"
  local apk_sha256="unavailable"
  local apk_payload_sha256="unavailable"
  local generated_at manifest_path temporary_path raw_log_path
  local p95_limit_ms=250.0
  local max_limit_ms=500.0
  local jank_rate_limit_pct=null
  local missed_deadline_limit_pct=null
  if [[ "$frame_profile" == "physical_samsung" ]]; then
    p95_limit_ms=50.0
    max_limit_ms=150.0
    jank_rate_limit_pct=10.0
    missed_deadline_limit_pct=25.0
  fi
  if [[ "$exit_code" == "0" && "$qa_status" == "passed" ]]; then
    result_status="passed"
  elif [[ "$exit_code" == "129" || "$exit_code" == "130" || "$exit_code" == "143" ]]; then
    result_status="interrupted"
  fi
  if [[ -f "$QA_APK" ]]; then
    apk_sha256="$(hash_file "$QA_APK")"
    apk_payload_sha256="$("$APK_PAYLOAD_DIGEST" "$QA_APK")"
  fi
  generated_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  mkdir -p "$EVIDENCE_DIR"
  manifest_path="$EVIDENCE_DIR/frame-metrics-${serial_sha256:0:16}-$(date -u '+%Y%m%dT%H%M%SZ')-$$.json"
  temporary_path="$manifest_path.tmp"
  printf '%s\n' \
    '{' \
    '  "schema_version": 2,' \
    "  \"generated_at_utc\": \"$(json_escape "$generated_at")\"," \
    "  \"qa_profile\": \"$(json_escape "$frame_profile")\"," \
    '  "source": {' \
    "    \"git_commit\": \"$(json_escape "$git_commit")\"" \
    '  },' \
    '  "device": {' \
    "    \"serial_sha256\": \"$(json_escape "$serial_sha256")\"," \
    "    \"manufacturer\": \"$(json_escape "$manufacturer_lc")\"," \
    "    \"model\": \"$(json_escape "$model")\"," \
    "    \"android_version\": \"$(json_escape "$android_version")\"," \
    "    \"build_fingerprint_sha256\": \"$(json_escape "$(hash_text "$fingerprint")")\"" \
    '  },' \
    '  "apk": {' \
    "    \"file_name\": \"$(json_escape "$(basename "$QA_APK")")\"," \
    "    \"sha256\": \"$(json_escape "$apk_sha256")\"," \
    "    \"payload_sha256\": \"$(json_escape "$apk_payload_sha256")\"" \
    '  },' \
    '  "metrics": {' \
    "    \"samples\": $(json_number_or_null "$samples")," \
    "    \"p50_ms\": $(json_number_or_null "$p50_ms")," \
    "    \"p95_ms\": $(json_number_or_null "$p95_ms")," \
    "    \"max_ms\": $(json_number_or_null "$max_ms")," \
    "    \"jank_rate_pct\": $(json_number_or_null "$jank_rate_pct")," \
    "    \"refresh_hz\": $(json_number_or_null "$refresh_hz")," \
    "    \"missed_deadline_threshold_ms\": $(json_number_or_null "$missed_deadline_threshold_ms")," \
    "    \"missed_deadline_rate_pct\": $(json_number_or_null "$missed_deadline_rate_pct")," \
    "    \"dropped_callbacks\": $(json_number_or_null "$dropped_callbacks")" \
    '  },' \
    '  "limits": {' \
    "    \"p95_ms\": $p95_limit_ms," \
    "    \"max_ms\": $max_limit_ms," \
    "    \"jank_rate_pct\": $jank_rate_limit_pct," \
    "    \"missed_deadline_rate_pct\": $missed_deadline_limit_pct" \
    '  },' \
    '  "result": {' \
    "    \"status\": \"$(json_escape "$result_status")\"," \
    "    \"test_status\": \"$(json_escape "$qa_status")\"," \
    "    \"exit_code\": $exit_code" \
    '  }' \
    '}' > "$temporary_path"
  mv "$temporary_path" "$manifest_path"
  raw_log_path="${manifest_path%.json}.log"
  if [[ -n "$log_file" && -f "$log_file" ]]; then
    {
      printf '%s\n' \
        "git_commit=$git_commit" \
        "apk_sha256=$apk_sha256" \
        "apk_payload_sha256=$apk_payload_sha256" \
        "result_status=$result_status"
    } >> "$log_file"
    cp "$log_file" "$raw_log_path"
  else
    printf '%s\n' \
      'V_SLOT_FRAME_METRICS_QA' \
      "git_commit=$git_commit" \
      "apk_sha256=$apk_sha256" \
      "apk_payload_sha256=$apk_payload_sha256" \
      "frame_profile=$frame_profile" \
      "result_status=$result_status" > "$raw_log_path"
  fi
  echo "Frame metrics QA evidence: $manifest_path"
  echo "Frame metrics QA raw log: $raw_log_path"
}

cleanup() {
  local exit_code=$?
  local final_exit_code=$exit_code
  trap - EXIT
  set +e
  if [[ "$power_state_captured" == "1" ]]; then
    if ! restore_setting_and_verify global stay_on_while_plugged_in "$original_stay_on_while_plugged_in"; then
      echo "Failed to restore stay_on_while_plugged_in on $model." >&2
      [[ "$final_exit_code" == "0" ]] && final_exit_code=1
    fi
  fi
  if [[ "$evidence_ready" == "1" ]]; then
    write_evidence "$final_exit_code" || {
      echo "Failed to write frame-metrics evidence." >&2
      [[ "$final_exit_code" == "0" ]] && final_exit_code=1
    }
  fi
  rm -rf -- "$lock_dir"
  exit "$final_exit_code"
}

trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

if original_stay_on_while_plugged_in="$("$ADB" -s "$serial" shell settings get global stay_on_while_plugged_in 2>/dev/null | tr -d '\r')"; then
  power_state_captured=1
else
  echo "Could not capture stay_on_while_plugged_in on $model; refusing to modify the device." >&2
  exit 1
fi
"$ADB" -s "$serial" shell settings put global stay_on_while_plugged_in 7 >/dev/null
"$ADB" -s "$serial" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"$ADB" -s "$serial" shell wm dismiss-keyguard >/dev/null 2>&1 || true
if "$ADB" -s "$serial" shell dumpsys window 2>/dev/null | grep -Eq 'mDreamingLockscreen=true|mShowingLockscreen=true|mCurrentFocus=.*Bouncer'; then
  echo "Unlock $model before running slot frame-metrics QA." >&2
  exit 3
fi

redact_stream() {
  local line
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line//$serial/<redacted-serial>}"
    printf '%s\n' "$line"
  done
}

output_dir="${V_SLOT_FRAME_METRICS_DIR:-$ROOT/qa/frame-metrics}"
mkdir -p "$output_dir"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
log_file="$output_dir/slot-frame-metrics-$timestamp.log"

{
  echo "V_SLOT_FRAME_METRICS_QA"
  echo "generated_at_utc=$timestamp"
  echo "test_class=$TEST_CLASS"
  echo "serial_sha256=$serial_sha256"
  echo "manufacturer=$manufacturer"
  echo "model=$model"
  echo "android_version=$android_version"
  echo "api_level=$sdk"
  echo "frame_profile=$frame_profile"
  echo "build_fingerprint_sha256=$(hash_text "$fingerprint")"
} | tee "$log_file"

"$ADB" -s "$serial" logcat -c

set +e
ANDROID_SERIAL="$serial" "$GRADLE" --no-daemon :app:connectedQaAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.class=$TEST_CLASS" \
  "-Pandroid.testInstrumentationRunnerArguments.slot_frame_profile=$frame_profile" 2>&1 |
  redact_stream |
  tee -a "$log_file"
pipeline_status=("${PIPESTATUS[@]}")
set -e

status="${pipeline_status[0]}"
if [[ "${pipeline_status[1]}" -ne 0 ]]; then
  status="${pipeline_status[1]}"
fi
if [[ "${pipeline_status[2]}" -ne 0 ]]; then
  status="${pipeline_status[2]}"
fi

{
  echo "slot_frame_metrics_logcat:"
  "$ADB" -s "$serial" logcat -d -v brief -s SlotFrameMetrics:I '*:S'
} | redact_stream | tee -a "$log_file"

echo "Frame metrics QA log: $log_file"
metrics_line="$(grep -Eo 'SLOT_FRAME_METRICS samples=.*' "$log_file" | tail -n 1 || true)"
samples="$(metric_value samples)"
p50_ms="$(metric_value p50_ms)"
p95_ms="$(metric_value p95_ms)"
max_ms="$(metric_value max_ms)"
jank_rate_pct="$(metric_value jank_rate_pct)"
refresh_hz="$(metric_value refresh_hz)"
missed_deadline_threshold_ms="$(metric_value missed_deadline_gt_ms)"
missed_deadline_rate_pct="$(metric_value missed_deadline_rate_pct)"
dropped_callbacks="$(metric_value dropped_callbacks)"
if [[ "$status" == "0" && -n "$samples" && -n "$p95_ms" && -n "$max_ms" ]]; then
  qa_status="passed"
else
  qa_status="failed"
  [[ "$status" != "0" ]] || status=1
fi
exit "$status"
