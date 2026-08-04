#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
DEFAULT_JAVA_HOME="$HOME/Library/Application Support/V Slot/toolchains/temurin-17.0.20+8/Contents/Home"
JAVA_HOME="${JAVA_HOME:-$DEFAULT_JAVA_HOME}"
KEYSTORE_INPUT="${V_SLOT_RELEASE_STORE_FILE:-$HOME/Library/Application Support/V Slot/signing/v-slot-upload.jks}"
METADATA_PATH="$ROOT/docs/store/upload-key-certificate.json"
KEYCHAIN_ACCOUNT="$(id -un)"

has_extended_acl() {
  [[ -n "$(ls -lde "$1" | sed -n '2,$p')" ]]
}

if [[ "$#" -eq 0 ]]; then
  echo "Usage: tools/with_play_upload_key.sh ./gradlew [GRADLE ARG ...]" >&2
  exit 2
fi
if [[ "$1" != "./gradlew" && "$1" != "$ROOT/gradlew" ]]; then
  echo "This wrapper only executes the repository Gradle wrapper." >&2
  exit 2
fi
shift
gradle_arguments=()
for argument in "$@"; do
  case "$argument" in
    --daemon|-Dorg.gradle.daemon=true|-Dorg.gradle.daemon=yes)
      echo "Persistent Gradle daemons are forbidden while signing secrets are present." >&2
      exit 2
      ;;
    --no-daemon)
      ;;
    --)
      echo "The Gradle argument separator is forbidden while signing secrets are present." >&2
      exit 2
      ;;
    *)
      gradle_arguments+=("$argument")
      ;;
  esac
done
if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "This wrapper reads signing secrets from macOS Keychain and must run on macOS." >&2
  exit 1
fi
if [[ ! -x "$JAVA_HOME/bin/java" || ! -x "$JAVA_HOME/bin/keytool" ]]; then
  echo "A working release JDK 17 is required at JAVA_HOME." >&2
  exit 1
fi
java_version="$("$JAVA_HOME/bin/java" -version 2>&1 | sed -n '1p')"
if [[ "$java_version" != *'"17.'* ]]; then
  echo "JAVA_HOME must point to JDK 17; found: $java_version" >&2
  exit 1
fi
KEYTOOL="$JAVA_HOME/bin/keytool"
export JAVA_HOME

for command in python3 security shasum; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Required command is unavailable: $command" >&2
    exit 1
  fi
done
if [[ -L "$METADATA_PATH" || ! -f "$METADATA_PATH" ]]; then
  echo "Pinned upload certificate metadata is missing or unsafe." >&2
  exit 1
fi

keystore_name="$(basename "$KEYSTORE_INPUT")"
keystore_dir_input="$(dirname "$KEYSTORE_INPUT")"
if [[ "$KEYSTORE_INPUT" == *$'\n'* || ! "$keystore_name" =~ ^[A-Za-z0-9._-]+\.jks$ || -L "$KEYSTORE_INPUT" ]]; then
  echo "The upload keystore path is unsafe." >&2
  exit 1
fi
if [[ ! -d "$keystore_dir_input" || -L "$keystore_dir_input" ]]; then
  echo "The upload-key directory is missing or unsafe." >&2
  exit 1
fi
keystore_dir="$(cd "$keystore_dir_input" && pwd -P)"
case "$keystore_dir/" in
  "$ROOT/"*)
    echo "The upload keystore must be stored outside the Git repository." >&2
    exit 1
    ;;
esac
KEYSTORE_PATH="$keystore_dir/$keystore_name"
if [[ ! -f "$KEYSTORE_PATH" || -L "$KEYSTORE_PATH" ]]; then
  echo "Upload keystore is missing or unsafe." >&2
  exit 1
fi
if [[ "$(stat -f '%Su' "$KEYSTORE_PATH")" != "$KEYCHAIN_ACCOUNT" ]]; then
  echo "Upload keystore must be owned by the current user." >&2
  exit 1
fi
if [[ "$(stat -f '%Su' "$keystore_dir")" != "$KEYCHAIN_ACCOUNT" ]]; then
  echo "The upload-key directory must be owned by the current user." >&2
  exit 1
fi
directory_mode="$(stat -f '%Lp' "$keystore_dir")"
if (( (8#$directory_mode & 077) != 0 )); then
  echo "The upload-key directory must not be accessible by group or other users." >&2
  exit 1
fi
keystore_mode="$(stat -f '%Lp' "$KEYSTORE_PATH")"
if (( (8#$keystore_mode & 077) != 0 || (8#$keystore_mode & 400) == 0 )); then
  echo "Upload keystore permissions must allow only owner access." >&2
  exit 1
fi
if has_extended_acl "$keystore_dir" || has_extended_acl "$KEYSTORE_PATH"; then
  echo "The upload key must not be accessible through an extended ACL." >&2
  exit 1
fi

metadata="$({
  python3 - "$METADATA_PATH" <<'PY'
import json
import re
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    data = json.load(source)

required = {
    "schema_version": 1,
    "application_id": "com.vslot.app",
    "key_role": "play_upload",
    "key_algorithm": "RSA",
    "key_size_bits": 4096,
    "signature_algorithm": "SHA256withRSA",
}
for key, expected in required.items():
    if data.get(key) != expected:
        raise SystemExit(f"Invalid upload-key metadata field: {key}")

alias = data.get("alias", "")
fingerprint = data.get("certificate_sha256", "")
store_service = data.get("keychain_store_password_service", "")
key_service = data.get("keychain_key_password_service", "")
if not re.fullmatch(r"[A-Za-z0-9._-]+", alias):
    raise SystemExit("Invalid upload-key alias")
if not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
    raise SystemExit("Invalid upload certificate SHA-256")
if store_service != f"com.vslot.play-upload.{fingerprint}.store-password":
    raise SystemExit("Invalid Keychain service metadata")
if key_service != f"com.vslot.play-upload.{fingerprint}.key-password":
    raise SystemExit("Invalid Keychain service metadata")

print(alias)
print(fingerprint)
print(store_service)
print(key_service)
PY
} 2>&1)" || {
  echo "$metadata" >&2
  exit 1
}

key_alias="$(printf '%s\n' "$metadata" | sed -n '1p')"
expected_certificate_sha256="$(printf '%s\n' "$metadata" | sed -n '2p')"
store_password_service="$(printf '%s\n' "$metadata" | sed -n '3p')"
key_password_service="$(printf '%s\n' "$metadata" | sed -n '4p')"

store_password="$(security find-generic-password -a "$KEYCHAIN_ACCOUNT" -s "$store_password_service" -w)"
key_password="$(security find-generic-password -a "$KEYCHAIN_ACCOUNT" -s "$key_password_service" -w)"
if [[ -z "$store_password" || -z "$key_password" ]]; then
  echo "Upload-key passwords are missing from macOS Keychain." >&2
  exit 1
fi

umask 077
password_dir="$(mktemp -d "${TMPDIR:-/tmp}/v-slot-upload-key.XXXXXX")"
store_password_file="$password_dir/store-password"
cleanup() {
  local status=$?
  trap - EXIT
  set +e
  rm -f -- "$store_password_file"
  rmdir "$password_dir" 2>/dev/null
  unset store_password key_password metadata
  exit "$status"
}
trap cleanup EXIT
printf '%s\n' "$store_password" >"$store_password_file"
chmod 0600 "$store_password_file"

actual_certificate_sha256="$("$KEYTOOL" -exportcert \
  -keystore "$KEYSTORE_PATH" \
  -storepass:file "$store_password_file" \
  -alias "$key_alias" | shasum -a 256 | awk '{print $1}')"
if [[ "$actual_certificate_sha256" != "$expected_certificate_sha256" ]]; then
  echo "Upload keystore certificate does not match the pinned SHA-256." >&2
  exit 1
fi

rm -f -- "$store_password_file"
rmdir "$password_dir"
trap - EXIT

export V_SLOT_RELEASE_STORE_FILE="$KEYSTORE_PATH"
export V_SLOT_RELEASE_STORE_PASSWORD="$store_password"
export V_SLOT_RELEASE_KEY_ALIAS="$key_alias"
export V_SLOT_RELEASE_KEY_PASSWORD="$key_password"
export V_SLOT_RELEASE_CERT_SHA256="$expected_certificate_sha256"
unset store_password key_password metadata

exec "$ROOT/gradlew" --no-daemon "${gradle_arguments[@]}"
