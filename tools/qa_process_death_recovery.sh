#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${V_SLOT_ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
GRADLE="${V_SLOT_GRADLE:-$ROOT/gradlew}"
APK_PAYLOAD_DIGEST="${V_SLOT_APK_PAYLOAD_DIGEST:-$ROOT/tools/apk_payload_sha256.sh}"
QA_APK="${V_SLOT_QA_APK:-$ROOT/app/build/outputs/apk/qa/app-qa.apk}"
EVIDENCE_DIR="${V_SLOT_PROCESS_DEATH_EVIDENCE_DIR:-$ROOT/qa/process-death/evidence}"
PACKAGE="com.vslot.app.qa"
MAIN_COMPONENT="$PACKAGE/com.vslot.app.MainActivity"
RECEIVER_COMPONENT="$PACKAGE/com.vslot.app.debug.QaStateReceiver"
QA_ACTION="com.vslot.app.debug.QA_STATE"
QA_SLOT_ID="violet_fortune"
COMMAND_EXTRA="qa_command"

if [[ ! -x "$ADB" ]]; then
  echo "ADB not found or not executable at $ADB." >&2
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
  if [[ "$value" =~ ^[0-9]+$ ]]; then
    printf '%s' "$value"
  else
    printf 'null'
  fi
}

serial="${1:-${ANDROID_SERIAL:-}}"
allow_emulator_qa="${V_SLOT_ALLOW_EMULATOR_QA:-0}"
if [[ -z "$serial" ]]; then
  samsung_devices=()
  while IFS= read -r candidate; do
    [[ -z "$candidate" ]] && continue
    candidate_manufacturer="$("$ADB" -s "$candidate" shell getprop ro.product.manufacturer 2>/dev/null | tr -d '\r' | tr '[:upper:]' '[:lower:]' || true)"
    candidate_qemu="$("$ADB" -s "$candidate" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r' || true)"
    if [[ "$candidate_manufacturer" == "samsung" && "$candidate_qemu" != "1" ]]; then
      samsung_devices+=("$candidate")
    fi
  done < <("$ADB" devices | awk 'NR > 1 && $2 == "device" {print $1}')
  if [[ "${#samsung_devices[@]}" -ne 1 ]]; then
    echo "Pass an explicit physical Samsung serial or connect exactly one physical Samsung." >&2
    "$ADB" devices -l >&2
    exit 1
  fi
  serial="${samsung_devices[0]}"
fi

device_state="$("$ADB" -s "$serial" get-state 2>/dev/null | tr -d '\r' || true)"
if [[ "$device_state" != "device" ]]; then
  echo "Device $serial is not ready (adb state: ${device_state:-not connected})." >&2
  exit 1
fi

manufacturer="$("$ADB" -s "$serial" shell getprop ro.product.manufacturer | tr -d '\r' | tr '[:upper:]' '[:lower:]')"
is_qemu="$("$ADB" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')"
model="$("$ADB" -s "$serial" shell getprop ro.product.model | tr -d '\r')"
android_version="$("$ADB" -s "$serial" shell getprop ro.build.version.release | tr -d '\r')"
one_ui_version="$("$ADB" -s "$serial" shell getprop ro.build.version.oneui | tr -d '\r')"
if [[ "$manufacturer" == "samsung" && "$is_qemu" != "1" ]]; then
  qa_profile="physical_samsung"
elif [[ "$is_qemu" == "1" && "$allow_emulator_qa" == "1" ]]; then
  qa_profile="emulator"
else
  echo "Device $serial is not a physical Samsung ($manufacturer $model)." >&2
  exit 1
fi

lock_serial="${serial//[^[:alnum:]_.-]/_}"
lock_dir="${TMPDIR:-/tmp}"
lock_dir="${lock_dir%/}/v-slot-samsung-qa-${lock_serial}.lock"
if ! mkdir "$lock_dir" 2>/dev/null; then
  echo "Process-death QA is already running for the selected Samsung." >&2
  exit 2
fi
printf '%s\n' "$$" > "$lock_dir/pid"

serial_sha256="$(hash_text "$serial")"
git_commit="$(git -C "$ROOT" rev-parse --verify HEAD 2>/dev/null || true)"
if [[ ! "$git_commit" =~ ^[0-9a-fA-F]{40,64}$ ]]; then
  git_commit="uncommitted"
fi
qa_status="not_run"
evidence_ready=1
setting_captured=0
original_stay_on_while_plugged_in=""
prepared_initial_balance=""
prepared_reserved_balance=""
expected_balance=""
expected_level_xp=""
expected_free_spins=""
expected_win=""
settlement_id=""
prepare_pid=""
first_restart_pid=""
second_restart_pid=""
presentation_observed=false
first_draw_observed=false
pending_journal_cleared=false
second_restart_unchanged=false

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

write_evidence() {
  local exit_code="$1"
  local cleanup_status="$2"
  local result_status="failed"
  local apk_sha256="unavailable"
  local apk_payload_sha256="unavailable"
  local generated_at manifest_path temporary_path raw_log_path
  if [[ "$exit_code" == "0" && "$qa_status" == "passed" && "$cleanup_status" == "passed" ]]; then
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
  manifest_path="$EVIDENCE_DIR/process-death-${serial_sha256:0:16}-$(date -u '+%Y%m%dT%H%M%SZ')-$$.json"
  temporary_path="$manifest_path.tmp"
  printf '%s\n' \
    '{' \
    '  "schema_version": 5,' \
    "  \"generated_at_utc\": \"$(json_escape "$generated_at")\"," \
    "  \"qa_profile\": \"$(json_escape "$qa_profile")\"," \
    '  "source": {' \
    "    \"git_commit\": \"$(json_escape "$git_commit")\"" \
    '  },' \
    '  "device": {' \
    "    \"serial_sha256\": \"$(json_escape "$serial_sha256")\"," \
    "    \"manufacturer\": \"$(json_escape "$manufacturer")\"," \
    "    \"model\": \"$(json_escape "$model")\"," \
    "    \"android_version\": \"$(json_escape "$android_version")\"," \
    "    \"one_ui_version\": \"$(json_escape "${one_ui_version:-unknown}")\"" \
    '  },' \
    '  "apk": {' \
    "    \"file_name\": \"$(json_escape "$(basename "$QA_APK")")\"," \
    "    \"sha256\": \"$(json_escape "$apk_sha256")\"," \
    "    \"payload_sha256\": \"$(json_escape "$apk_payload_sha256")\"" \
    '  },' \
    '  "fixture": {' \
    "    \"settlement_id\": \"$(json_escape "${settlement_id:-unavailable}")\"," \
    "    \"initial_balance\": $(json_number_or_null "$prepared_initial_balance")," \
    "    \"reserved_balance\": $(json_number_or_null "$prepared_reserved_balance")," \
    "    \"expected_balance\": $(json_number_or_null "$expected_balance")," \
    "    \"expected_level_xp\": $(json_number_or_null "$expected_level_xp")," \
    "    \"expected_free_spins\": $(json_number_or_null "$expected_free_spins")," \
    "    \"expected_win\": $(json_number_or_null "$expected_win")" \
    '  },' \
    '  "processes": {' \
    "    \"prepare_pid\": $(json_number_or_null "$prepare_pid")," \
    "    \"first_restart_pid\": $(json_number_or_null "$first_restart_pid")," \
    "    \"second_restart_pid\": $(json_number_or_null "$second_restart_pid")" \
    '  },' \
    '  "verification": {' \
    "    \"presentation_observed\": $presentation_observed," \
    "    \"first_draw_observed\": $first_draw_observed," \
    "    \"pending_journal_cleared\": $pending_journal_cleared," \
    "    \"second_restart_unchanged\": $second_restart_unchanged" \
    '  },' \
    '  "result": {' \
    "    \"status\": \"$(json_escape "$result_status")\"," \
    "    \"test_status\": \"$(json_escape "$qa_status")\"," \
    "    \"cleanup_status\": \"$(json_escape "$cleanup_status")\"," \
    "    \"exit_code\": $exit_code" \
    '  }' \
    '}' > "$temporary_path"
  mv "$temporary_path" "$manifest_path"
  raw_log_path="${manifest_path%.json}.log"
  printf '%s\n' \
    'V_SLOT_PROCESS_DEATH_QA' \
    "git_commit=$git_commit" \
    "serial_sha256=$serial_sha256" \
    "apk_sha256=$apk_sha256" \
    "apk_payload_sha256=$apk_payload_sha256" \
    "settlement_id=${settlement_id:-unavailable}" \
    "initial_balance=${prepared_initial_balance:-unavailable}" \
    "reserved_balance=${prepared_reserved_balance:-unavailable}" \
    "expected_balance=${expected_balance:-unavailable}" \
    "expected_win=${expected_win:-unavailable}" \
    "prepare_pid=${prepare_pid:-unavailable}" \
    "first_restart_pid=${first_restart_pid:-unavailable}" \
    "second_restart_pid=${second_restart_pid:-unavailable}" \
    "presentation_observed=$presentation_observed" \
    "first_draw_observed=$first_draw_observed" \
    "pending_journal_cleared=$pending_journal_cleared" \
    "second_restart_unchanged=$second_restart_unchanged" \
    "cleanup_status=$cleanup_status" \
    "result_status=$result_status" > "$raw_log_path"
  echo "Process-death QA evidence: $manifest_path"
  echo "Process-death QA raw log: $raw_log_path"
}

cleanup() {
  local exit_code=$?
  local final_exit_code=$exit_code
  local cleanup_status="passed"
  trap - EXIT
  set +e
  if [[ "$setting_captured" == "1" ]]; then
    if ! restore_setting global stay_on_while_plugged_in "$original_stay_on_while_plugged_in"; then
      cleanup_status="failed"
    fi
  fi
  if [[ "$cleanup_status" != "passed" && "$final_exit_code" == "0" ]]; then
    final_exit_code=1
  fi
  if [[ "$evidence_ready" == "1" ]]; then
    if ! write_evidence "$final_exit_code" "$cleanup_status"; then
      echo "Failed to write redacted process-death evidence." >&2
      [[ "$final_exit_code" == "0" ]] && final_exit_code=1
    fi
  fi
  rm -rf -- "$lock_dir"
  exit "$final_exit_code"
}

trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

original_stay_on_while_plugged_in="$("$ADB" -s "$serial" shell settings get global stay_on_while_plugged_in | tr -d '\r')"
setting_captured=1
"$ADB" -s "$serial" shell settings put global stay_on_while_plugged_in 7 >/dev/null
"$ADB" -s "$serial" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"$ADB" -s "$serial" shell wm dismiss-keyguard >/dev/null 2>&1 || true
if "$ADB" -s "$serial" shell dumpsys window 2>/dev/null | grep -Eq 'mDreamingLockscreen=true|mShowingLockscreen=true|mCurrentFocus=.*Bouncer'; then
  echo "Unlock the selected device before running process-death QA." >&2
  exit 3
fi

response_field() {
  local response="$1"
  local requested="$2"
  printf '%s\n' "$response" | awk -F';' -v requested="$requested" '
    {
      for (field_index = 1; field_index <= NF; field_index += 1) {
        equals = index($field_index, "=")
        if (equals > 0 && substr($field_index, 1, equals - 1) == requested) {
          print substr($field_index, equals + 1)
          exit
        }
      }
    }
  '
}

require_field() {
  local response="$1"
  local key="$2"
  local expected="$3"
  local actual
  actual="$(response_field "$response" "$key")"
  if [[ "$actual" != "$expected" ]]; then
    echo "QA state mismatch for $key: expected $expected, observed ${actual:-missing}." >&2
    return 1
  fi
}

run_qa_command() {
  local command="$1"
  local output data
  if ! output="$("$ADB" -s "$serial" shell am broadcast \
      --user current \
      --receiver-foreground \
      -a "$QA_ACTION" \
      -n "$RECEIVER_COMPONENT" \
      --es "$COMMAND_EXTRA" "$command" 2>&1)"; then
    echo "$output" >&2
    return 1
  fi
  if ! printf '%s\n' "$output" | grep -Eq 'Broadcast completed: result=-1([,[:space:]]|$)'; then
    echo "QA command $command failed: $output" >&2
    return 1
  fi
  data="$(printf '%s\n' "$output" | sed -n 's/.*data="\([^"]*\)".*/\1/p' | tail -n 1)"
  if [[ -z "$data" ]]; then
    echo "QA command $command returned no structured state: $output" >&2
    return 1
  fi
  printf '%s\n' "$data"
}

get_package_pid() {
  local raw
  raw="$("$ADB" -s "$serial" shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
  set -- $raw
  if [[ "$#" -ne 1 || ! "$1" =~ ^[0-9]+$ ]]; then
    echo "Expected exactly one $PACKAGE process, observed: ${raw:-none}." >&2
    return 1
  fi
  printf '%s\n' "$1"
}

kill_exact_package_pid() {
  local expected_pid="$1"
  local current_pid deadline
  current_pid="$(get_package_pid)"
  if [[ "$current_pid" != "$expected_pid" ]]; then
    echo "Refusing to kill stale PID $expected_pid; $PACKAGE now owns PID $current_pid." >&2
    return 1
  fi
  # Direct SIGKILL from the shell UID is denied for a non-debuggable APK.
  # ActivityManager performs the same process death without force-stopping the app.
  "$ADB" -s "$serial" shell input keyevent KEYCODE_HOME >/dev/null
  deadline=$((SECONDS + 10))
  while (( SECONDS < deadline )); do
    current_pid="$("$ADB" -s "$serial" shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
    if [[ " $current_pid " != *" $expected_pid "* ]]; then
      return 0
    fi
    "$ADB" -s "$serial" shell am kill --user current "$PACKAGE" >/dev/null
    sleep 0.25
  done
  echo "PID $expected_pid survived ActivityManager process death." >&2
  return 1
}

verify_final_state() {
  local state="$1"
  require_field "$state" status state
  require_field "$state" balance "$expected_balance"
  require_field "$state" level_xp "$expected_level_xp"
  require_field "$state" free_spins "$expected_free_spins"
  require_field "$state" pending_settlement false
  require_field "$state" pending_settlement_id none
  require_field "$state" pending_presentation false
  require_field "$state" pending_presentation_id none
}

echo "Building release-like QA APK."
(cd "$ROOT" && "$GRADLE" --no-daemon :app:assembleQa)
if [[ ! -f "$QA_APK" ]]; then
  echo "QA APK not found at $QA_APK." >&2
  exit 1
fi
"$ADB" -s "$serial" install -r -t "$QA_APK" >/dev/null

prepared="$(run_qa_command prepare_process_death)"
require_field "$prepared" status prepared
require_field "$prepared" pending_settlement true
require_field "$prepared" pending_presentation false
settlement_id="$(response_field "$prepared" settlement_id)"
prepared_initial_balance="$(response_field "$prepared" initial_balance)"
prepared_reserved_balance="$(response_field "$prepared" reserved_balance)"
expected_balance="$(response_field "$prepared" expected_balance)"
expected_level_xp="$(response_field "$prepared" expected_level_xp)"
expected_free_spins="$(response_field "$prepared" expected_free_spins)"
expected_win="$(response_field "$prepared" expected_win)"
for numeric_value in "$prepared_initial_balance" "$prepared_reserved_balance" "$expected_balance" "$expected_level_xp" "$expected_free_spins" "$expected_win"; do
  if [[ ! "$numeric_value" =~ ^[0-9]+$ ]]; then
    echo "QA fixture returned a non-numeric expectation." >&2
    exit 1
  fi
done
if [[ -z "$settlement_id" || "$prepared_reserved_balance" -ge "$prepared_initial_balance" ]]; then
  echo "QA fixture did not reserve a real paid wager." >&2
  exit 1
fi

prepare_pid="$(get_package_pid)"
kill_exact_package_pid "$prepare_pid"

# Starting the read-only receiver first proves the journal survived a fresh process
# before MainActivity gets a chance to run production recovery.
survived="$(run_qa_command inspect_process_death)"
require_field "$survived" status state
require_field "$survived" balance "$prepared_reserved_balance"
require_field "$survived" pending_settlement true
require_field "$survived" pending_settlement_id "$settlement_id"
require_field "$survived" pending_presentation false
first_restart_pid="$(get_package_pid)"
if [[ "$first_restart_pid" == "$prepare_pid" ]]; then
  echo "The first process PID did not change after ActivityManager killed it." >&2
  exit 1
fi

"$ADB" -s "$serial" logcat -c
"$ADB" -s "$serial" shell am start --user current -n "$MAIN_COMPONENT" --es qa_open_slot "$QA_SLOT_ID" >/dev/null
deadline=$((SECONDS + 25))
settled=""
while (( SECONDS < deadline )); do
  observed="$(run_qa_command inspect_process_death)"
  require_field "$observed" status state
  pending_settlement="$(response_field "$observed" pending_settlement)"
  pending_presentation="$(response_field "$observed" pending_presentation)"
  presentation_log="$("$ADB" -s "$serial" logcat -d -s 'VSlotPresentation:I' '*:S' 2>/dev/null || true)"
  if [[ "$presentation_log" == *"modal_first_draw"* ]]; then
    first_draw_observed=true
    presentation_observed=true
  fi
  if [[ "$pending_settlement" == "true" && "$pending_presentation" == "true" ]]; then
    echo "Settlement and presentation journals were simultaneously populated." >&2
    exit 1
  fi
  if [[ "$pending_presentation" == "true" ]]; then
    require_field "$observed" pending_presentation_id "$settlement_id"
    require_field "$observed" balance "$expected_balance"
    require_field "$observed" level_xp "$expected_level_xp"
    presentation_observed=true
    observed_claim="$(response_field "$observed" presentation_claimed)"
    if [[ "$observed_claim" != "true" && "$observed_claim" != "false" ]]; then
      echo "Recovered presentation returned an invalid claim state: ${observed_claim:-missing}." >&2
      exit 1
    fi
  fi
  if [[ "$first_draw_observed" == "true" && "$pending_settlement" == "false" && "$pending_presentation" == "false" ]]; then
    verify_final_state "$observed"
    settled="$observed"
    break
  fi
  sleep 0.1
done
if [[ "$presentation_observed" != "true" ]]; then
  echo "The recovered durable presentation was never observed." >&2
  exit 1
fi
if [[ "$first_draw_observed" != "true" ]]; then
  echo "The recovered result dialog never completed its first draw." >&2
  exit 1
fi
if [[ -z "$settled" ]]; then
  echo "The recovered presentation journal was not acknowledged within the timeout." >&2
  exit 1
fi
pending_journal_cleared=true

first_restart_pid="$(get_package_pid)"
kill_exact_package_pid "$first_restart_pid"

second_state_before_activity="$(run_qa_command inspect_process_death)"
verify_final_state "$second_state_before_activity"
second_restart_pid="$(get_package_pid)"
if [[ "$second_restart_pid" == "$first_restart_pid" || "$second_restart_pid" == "$prepare_pid" ]]; then
  echo "The second process PID was not fresh after ActivityManager killed it." >&2
  exit 1
fi

"$ADB" -s "$serial" shell am start --user current -n "$MAIN_COMPONENT" --es qa_open_slot "$QA_SLOT_ID" >/dev/null
sleep 2
second_state_after_activity="$(run_qa_command inspect_process_death)"
verify_final_state "$second_state_after_activity"
require_field "$second_state_after_activity" presentation_claimed false
second_restart_unchanged=true

qa_status="passed"
echo "Process-death recovery passed on $qa_profile $model: one settlement, one presentation, no double credit."
