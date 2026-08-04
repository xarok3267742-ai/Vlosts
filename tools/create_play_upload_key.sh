#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
DEFAULT_JAVA_HOME="$HOME/Library/Application Support/V Slot/toolchains/temurin-17.0.20+8/Contents/Home"
JAVA_HOME="${JAVA_HOME:-$DEFAULT_JAVA_HOME}"
KEYSTORE_INPUT="${V_SLOT_RELEASE_STORE_FILE:-$HOME/Library/Application Support/V Slot/signing/v-slot-upload.jks}"
METADATA_PATH="$ROOT/docs/store/upload-key-certificate.json"
KEY_ALIAS="${V_SLOT_RELEASE_KEY_ALIAS:-v-slot-upload}"
KEYCHAIN_ACCOUNT="$(id -un)"
KEY_DNAME="CN=V Slot Upload Key"

has_extended_acl() {
  [[ -n "$(ls -lde "$1" | sed -n '2,$p')" ]]
}

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "This bootstrap stores signing secrets in macOS Keychain and must run on macOS." >&2
  exit 1
fi
if [[ ! "$KEY_ALIAS" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "V_SLOT_RELEASE_KEY_ALIAS must contain only letters, digits, dot, underscore or hyphen." >&2
  exit 2
fi
for command in security openssl shasum; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Required command is unavailable: $command" >&2
    exit 1
  fi
done
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

keystore_name="$(basename "$KEYSTORE_INPUT")"
keystore_dir_input="$(dirname "$KEYSTORE_INPUT")"
if [[ "$KEYSTORE_INPUT" == *$'\n'* || ! "$keystore_name" =~ ^[A-Za-z0-9._-]+\.jks$ ]]; then
  echo "The upload keystore must use a safe .jks filename." >&2
  exit 2
fi

umask 077
mkdir -p "$keystore_dir_input"
if [[ -L "$keystore_dir_input" ]]; then
  echo "The upload-key directory must not be a symbolic link." >&2
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
if [[ "$(stat -f '%Su' "$keystore_dir")" != "$KEYCHAIN_ACCOUNT" ]]; then
  echo "The upload-key directory must be owned by the current user." >&2
  exit 1
fi
directory_mode="$(stat -f '%Lp' "$keystore_dir")"
if (( (8#$directory_mode & 077) != 0 )); then
  echo "The upload-key directory must not be accessible by group or other users." >&2
  exit 1
fi
if has_extended_acl "$keystore_dir"; then
  echo "The upload-key directory must not grant access through an extended ACL." >&2
  exit 1
fi
if [[ -L "$KEYSTORE_PATH" || -L "$METADATA_PATH" ]]; then
  echo "Upload-key output paths must not be symbolic links." >&2
  exit 1
fi

LOCK_PATH="$keystore_dir/.v-slot-upload-key-bootstrap.lock"
lock_held=0
staging_dir=""
staged_keystore=""
store_password_file=""
key_password_file=""
metadata_tmp=""
store_password_service=""
key_password_service=""
store_secret_maybe_added=0
key_secret_maybe_added=0
success=0

cleanup() {
  local status=$?
  local cleanup_failed=0
  trap - EXIT INT TERM
  set +e

  if [[ "$success" -eq 0 ]]; then
    if [[ -n "$metadata_tmp" && -e "$METADATA_PATH" && "$METADATA_PATH" -ef "$metadata_tmp" ]]; then
      rm -f -- "$METADATA_PATH" || cleanup_failed=1
    fi
    if [[ -n "$staged_keystore" && -e "$KEYSTORE_PATH" && "$KEYSTORE_PATH" -ef "$staged_keystore" ]]; then
      rm -f -- "$KEYSTORE_PATH" || cleanup_failed=1
    fi
    if [[ "$key_secret_maybe_added" -eq 1 && -n "$key_password_service" ]]; then
      security delete-generic-password -a "$KEYCHAIN_ACCOUNT" -s "$key_password_service" >/dev/null 2>&1 || cleanup_failed=1
    fi
    if [[ "$store_secret_maybe_added" -eq 1 && -n "$store_password_service" ]]; then
      security delete-generic-password -a "$KEYCHAIN_ACCOUNT" -s "$store_password_service" >/dev/null 2>&1 || cleanup_failed=1
    fi
  fi

  rm -f -- "$store_password_file" "$key_password_file" "$metadata_tmp" "$staged_keystore" 2>/dev/null || cleanup_failed=1
  if [[ -n "$staging_dir" ]]; then
    rmdir "$staging_dir" 2>/dev/null || cleanup_failed=1
  fi
  if [[ "$lock_held" -eq 1 ]]; then
    rmdir "$LOCK_PATH" 2>/dev/null || cleanup_failed=1
  fi
  unset store_password key_password

  if [[ "$cleanup_failed" -ne 0 ]]; then
    echo "Upload-key cleanup was incomplete; inspect $keystore_dir and Keychain before retrying." >&2
    if [[ "$status" -eq 0 ]]; then
      status=1
    fi
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if ! mkdir "$LOCK_PATH"; then
  echo "Another upload-key bootstrap is active, or a stale lock requires inspection: $LOCK_PATH" >&2
  exit 1
fi
lock_held=1
if [[ -e "$KEYSTORE_PATH" || -e "$METADATA_PATH" ]]; then
  echo "Refusing to overwrite an existing upload key or pinned certificate metadata." >&2
  exit 1
fi

staging_dir="$(mktemp -d "$keystore_dir/.v-slot-upload-key.XXXXXX")"
staged_keystore="$staging_dir/upload-key.jks"
store_password_file="$staging_dir/store-password"
key_password_file="$staging_dir/key-password"
metadata_tmp="$(mktemp "$ROOT/docs/store/.upload-key-certificate.XXXXXX")"
store_password="$(openssl rand -hex 32)"
key_password="$(openssl rand -hex 32)"
printf '%s\n' "$store_password" >"$store_password_file"
printf '%s\n' "$key_password" >"$key_password_file"
chmod 0600 "$store_password_file" "$key_password_file"

"$KEYTOOL" -genkeypair \
  -noprompt \
  -keystore "$staged_keystore" \
  -storetype JKS \
  -storepass:file "$store_password_file" \
  -alias "$KEY_ALIAS" \
  -keypass:file "$key_password_file" \
  -keyalg RSA \
  -keysize 4096 \
  -sigalg SHA256withRSA \
  -validity 10000 \
  -dname "$KEY_DNAME"
chmod 0600 "$staged_keystore"

certificate_sha256="$("$KEYTOOL" -exportcert \
  -keystore "$staged_keystore" \
  -storepass:file "$store_password_file" \
  -alias "$KEY_ALIAS" | shasum -a 256 | awk '{print $1}')"
if [[ ! "$certificate_sha256" =~ ^[0-9a-f]{64}$ ]]; then
  echo "Could not derive the upload certificate SHA-256." >&2
  exit 1
fi
store_password_service="com.vslot.play-upload.$certificate_sha256.store-password"
key_password_service="com.vslot.play-upload.$certificate_sha256.key-password"

if security find-generic-password -a "$KEYCHAIN_ACCOUNT" -s "$store_password_service" >/dev/null 2>&1 ||
  security find-generic-password -a "$KEYCHAIN_ACCOUNT" -s "$key_password_service" >/dev/null 2>&1; then
  echo "Unexpected upload-key secrets already exist in Keychain." >&2
  exit 1
fi

store_secret_maybe_added=1
printf '%s\n%s\n' "$store_password" "$store_password" |
  security add-generic-password \
    -a "$KEYCHAIN_ACCOUNT" \
    -s "$store_password_service" \
    -T "" \
    -w >/dev/null
key_secret_maybe_added=1
printf '%s\n%s\n' "$key_password" "$key_password" |
  security add-generic-password \
    -a "$KEYCHAIN_ACCOUNT" \
    -s "$key_password_service" \
    -T "" \
    -w >/dev/null

created_at_utc="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
cat >"$metadata_tmp" <<EOF
{
  "schema_version": 1,
  "application_id": "com.vslot.app",
  "key_role": "play_upload",
  "alias": "$KEY_ALIAS",
  "certificate_sha256": "$certificate_sha256",
  "key_algorithm": "RSA",
  "key_size_bits": 4096,
  "signature_algorithm": "SHA256withRSA",
  "created_at_utc": "$created_at_utc",
  "keychain_store_password_service": "$store_password_service",
  "keychain_key_password_service": "$key_password_service"
}
EOF
chmod 0644 "$metadata_tmp"

ln "$staged_keystore" "$KEYSTORE_PATH"
ln "$metadata_tmp" "$METADATA_PATH"
if has_extended_acl "$KEYSTORE_PATH"; then
  echo "The generated upload keystore unexpectedly has an extended ACL." >&2
  exit 1
fi
success=1

echo "Created V Slot Play upload key: $KEYSTORE_PATH"
echo "Pinned upload certificate metadata: $METADATA_PATH"
echo "Upload certificate SHA-256: $certificate_sha256"
echo "Back up the keystore and Keychain secrets outside this Mac before the first Play upload."
