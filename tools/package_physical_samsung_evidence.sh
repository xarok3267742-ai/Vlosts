#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 4 ]]; then
  echo "Usage: tools/package_physical_samsung_evidence.sh CONNECTED_JSON PROCESS_DEATH_JSON FRAME_METRICS_JSON OUTPUT_ZIP" >&2
  exit 2
fi

connected_manifest="$1"
process_manifest="$2"
frame_manifest="$3"
output_zip="$4"

for manifest in "$connected_manifest" "$process_manifest" "$frame_manifest"; do
  if [[ ! -s "$manifest" ]]; then
    echo "Evidence manifest not found or empty: $manifest" >&2
    exit 1
  fi
done
if ! command -v zip >/dev/null 2>&1; then
  echo "The zip command is required to package physical Samsung evidence." >&2
  exit 1
fi

connected_results_dir="$(dirname "$connected_manifest")/stage-results"
process_log="${process_manifest%.json}.log"
frame_log="${frame_manifest%.json}.log"
if [[ ! -s "$process_log" || ! -s "$frame_log" ]]; then
  echo "Matching process-death and frame-metrics raw logs are required." >&2
  exit 1
fi

stages=(
  portrait_smoke
  font_scale_2_0_first_launch_legal_notices
  compact_portrait_settings
  compact_landscape_rotation_1
  compact_landscape_rotation_3
  landscape_rotation_1
  landscape_rotation_3
)

temporary_dir="$(mktemp -d "${TMPDIR:-/tmp}/v-slot-physical-evidence.XXXXXX")"
trap 'rm -rf -- "$temporary_dir"' EXIT
mkdir -p "$temporary_dir/manifests" "$temporary_dir/raw/connected" \
  "$temporary_dir/raw/process-death" "$temporary_dir/raw/frame-metrics"
cp "$connected_manifest" "$temporary_dir/manifests/connected-tests.json"
cp "$process_manifest" "$temporary_dir/manifests/process-death.json"
cp "$frame_manifest" "$temporary_dir/manifests/frame-metrics.json"
cp "$process_log" "$temporary_dir/raw/process-death/process-death.log"
cp "$frame_log" "$temporary_dir/raw/frame-metrics/frame-metrics.log"

for stage in "${stages[@]}"; do
  source_dir="$connected_results_dir/$stage"
  destination_dir="$temporary_dir/raw/connected/$stage"
  mkdir -p "$destination_dir"
  report_count=0
  while IFS= read -r report; do
    [[ -n "$report" ]] || continue
    cp "$report" "$destination_dir/"
    report_count=$((report_count + 1))
  done < <(find "$source_dir" -maxdepth 1 -type f -name 'TEST-*.xml' -print 2>/dev/null | sort)
  if [[ "$report_count" -eq 0 ]]; then
    echo "No raw connected-test XML found for stage $stage." >&2
    exit 1
  fi
done

output_parent="$(dirname "$output_zip")"
mkdir -p "$output_parent"
output_parent="$(cd "$output_parent" && pwd)"
output_absolute="$output_parent/$(basename "$output_zip")"
rm -f "$output_absolute"
(
  cd "$temporary_dir"
  zip -q -X -r "$output_absolute" manifests raw
)
echo "Physical Samsung raw evidence archive: $output_absolute"
