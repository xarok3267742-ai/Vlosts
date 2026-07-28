#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA="${V_SLOT_JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/java}}"
if [[ -z "$JAVA" ]]; then
  JAVA="$(command -v java || true)"
fi
if [[ -z "$JAVA" || ! -x "$JAVA" ]]; then
  echo "Java 17 is required to calculate the canonical APK payload SHA-256." >&2
  exit 1
fi
if [[ "$#" -ne 1 || ! -f "$1" ]]; then
  echo "Usage: tools/apk_payload_sha256.sh APK" >&2
  exit 64
fi

"$JAVA" "$ROOT/tools/ApkPayloadDigest.java" "$1"
