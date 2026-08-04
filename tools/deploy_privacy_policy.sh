#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RENDERER="$ROOT/tools/render_privacy_policy.py"
POLICY_FILE="${1:-}"
SSH_TARGET="${V_SLOT_PRIVACY_SSH_TARGET:-}"
KNOWN_HOSTS="${V_SLOT_PRIVACY_KNOWN_HOSTS:-}"
IDENTITY_FILE="${V_SLOT_PRIVACY_SSH_IDENTITY_FILE:-}"
REMOTE_PATH="${V_SLOT_PRIVACY_REMOTE_PATH:-/var/www/v-slot/privacy-policy.html}"
PUBLIC_URL="${V_SLOT_PRIVACY_POLICY_URL:-}"
SUPPORT_EMAIL="${V_SLOT_SUPPORT_EMAIL:-}"
DEVELOPER_LEGAL_NAME="${V_SLOT_DEVELOPER_LEGAL_NAME:-}"
LOCK_PATH="/var/www/v-slot/.privacy-policy-deploy.lock"

if [[ -z "$POLICY_FILE" || ! -f "$POLICY_FILE" ]]; then
  echo "Usage: V_SLOT_PRIVACY_SSH_TARGET=user@host V_SLOT_PRIVACY_KNOWN_HOSTS=... V_SLOT_PRIVACY_POLICY_URL=https://... $0 POLICY_FILE" >&2
  exit 2
fi
if [[ -z "$SSH_TARGET" || -z "$KNOWN_HOSTS" || -z "$PUBLIC_URL" ]]; then
  echo "V_SLOT_PRIVACY_SSH_TARGET, V_SLOT_PRIVACY_KNOWN_HOSTS and V_SLOT_PRIVACY_POLICY_URL are required." >&2
  exit 2
fi
if [[ -z "${SUPPORT_EMAIL//[[:space:]]/}" || -z "${DEVELOPER_LEGAL_NAME//[[:space:]]/}" ]]; then
  echo "V_SLOT_SUPPORT_EMAIL and V_SLOT_DEVELOPER_LEGAL_NAME are required." >&2
  exit 2
fi
if [[ ! "$SSH_TARGET" =~ ^[A-Za-z_][A-Za-z0-9._-]*@[A-Za-z0-9.-]+$ || "$SSH_TARGET" == root@* ]]; then
  echo "V_SLOT_PRIVACY_SSH_TARGET must name a dedicated non-root deploy user and host." >&2
  exit 2
fi
if [[ ! "$REMOTE_PATH" =~ ^/var/www/v-slot/[A-Za-z0-9._-]+$ ]]; then
  echo "V_SLOT_PRIVACY_REMOTE_PATH must name a file directly inside /var/www/v-slot." >&2
  exit 2
fi
if [[ ! -r "$KNOWN_HOSTS" ]]; then
  echo "V_SLOT_PRIVACY_KNOWN_HOSTS must be a readable, pre-verified known_hosts file." >&2
  exit 2
fi
if [[ -n "$IDENTITY_FILE" && ! -r "$IDENTITY_FILE" ]]; then
  echo "V_SLOT_PRIVACY_SSH_IDENTITY_FILE must be readable when provided." >&2
  exit 2
fi

reviewed="$(mktemp)"
downloaded="$(mktemp)"
control_directory="$(mktemp -d)"
control_path="$control_directory/ssh-control"
lock_token="${control_directory##*/}-$$"
lock_token_path="$LOCK_PATH/token"
remote_tmp=""
remote_backup=""
backup_sha=""
local_sha=""
live_replaced=0
lock_maybe_held=0
ssh_options=(
  -o BatchMode=yes
  -o ConnectTimeout=15
  -o ControlMaster=auto
  -o ControlPersist=60
  -o "ControlPath=$control_path"
  -o StrictHostKeyChecking=yes
  -o "UserKnownHostsFile=$KNOWN_HOSTS"
)
if [[ -n "$IDENTITY_FILE" ]]; then
  ssh_options+=(-o IdentitiesOnly=yes -i "$IDENTITY_FILE")
fi

run_remote() {
  ssh "${ssh_options[@]}" "$SSH_TARGET" "$1"
}

release_lock() {
  local command
  printf -v command \
    "if [ \"\$(cat %q 2>/dev/null)\" = %q ]; then rm -f -- %q && rmdir -- %q; else exit 76; fi" \
    "$lock_token_path" "$lock_token" "$lock_token_path" "$LOCK_PATH"
  run_remote "$command" || return 1
  lock_maybe_held=0
}

rollback_remote() {
  local command
  if [[ -n "$remote_backup" ]]; then
    printf -v command \
      "current=missing; if [ -f %q ]; then current=\$(sha256sum %q | cut -d ' ' -f 1); fi; if [ \"\$current\" = %q ]; then mv -f -- %q %q; elif [ \"\$current\" = %q ]; then rm -f -- %q; else exit 75; fi" \
      "$REMOTE_PATH" "$REMOTE_PATH" "$local_sha" "$remote_backup" "$REMOTE_PATH" \
      "$backup_sha" "$remote_backup"
    run_remote "$command" || return 1
    remote_backup=""
    backup_sha=""
  else
    printf -v command \
      "current=missing; if [ -f %q ]; then current=\$(sha256sum %q | cut -d ' ' -f 1); fi; if [ \"\$current\" = %q ]; then rm -f -- %q; elif [ \"\$current\" != missing ]; then exit 75; fi" \
      "$REMOTE_PATH" "$REMOTE_PATH" "$local_sha" "$REMOTE_PATH"
    run_remote "$command" || return 1
  fi
  live_replaced=0
}

cleanup() {
  local status=$?
  local command
  local rollback_failed=0
  trap - EXIT
  set +e
  rm -f "$reviewed" "$downloaded"
  if [[ "$live_replaced" -eq 1 ]]; then
    if ! rollback_remote >/dev/null 2>&1; then
      echo "CRITICAL: automatic privacy policy rollback failed; inspect $REMOTE_PATH immediately." >&2
      rollback_failed=1
    fi
  fi
  if [[ -n "$remote_tmp" ]]; then
    printf -v command "rm -f -- %q" "$remote_tmp"
    run_remote "$command" >/dev/null 2>&1
  fi
  if [[ -n "$remote_backup" && "$rollback_failed" -eq 0 ]]; then
    printf -v command "rm -f -- %q" "$remote_backup"
    run_remote "$command" >/dev/null 2>&1
  fi
  if [[ "$lock_maybe_held" -eq 1 && "$rollback_failed" -eq 0 ]]; then
    if ! release_lock >/dev/null 2>&1; then
      echo "WARNING: privacy policy deploy lock could not be released; inspect $LOCK_PATH." >&2
    fi
  fi
  ssh "${ssh_options[@]}" -O exit "$SSH_TARGET" >/dev/null 2>&1
  rm -f "$control_path"
  rmdir "$control_directory" >/dev/null 2>&1
  exit "$status"
}
trap cleanup EXIT

chmod 0600 "$reviewed" "$downloaded"
cp -- "$POLICY_FILE" "$reviewed"
python3 "$RENDERER" --check "$reviewed"
local_sha="$(shasum -a 256 "$reviewed" | awk '{print $1}')"

printf -v acquire_lock_command \
  "if mkdir -- %q; then if (umask 077 && printf '%%s' %q > %q); then exit 0; else rmdir -- %q; exit 1; fi; else exit 73; fi" \
  "$LOCK_PATH" "$lock_token" "$lock_token_path" "$LOCK_PATH"
lock_maybe_held=1
if ! run_remote "$acquire_lock_command"; then
  echo "Another privacy policy deployment is active, or a stale deploy lock requires inspection." >&2
  exit 1
fi

printf -v create_tmp_command "umask 077 && mktemp %q" "${REMOTE_PATH}.upload.XXXXXX"
remote_tmp="$(run_remote "$create_tmp_command")"
if [[ ! "$remote_tmp" =~ ^${REMOTE_PATH//./\.}\.upload\.[A-Za-z0-9]+$ ]]; then
  echo "Remote server returned an unexpected temporary path." >&2
  exit 1
fi
scp "${ssh_options[@]}" -- "$reviewed" "$SSH_TARGET:$remote_tmp"

printf -v backup_command \
  "if [ -f %q ]; then backup=\$(mktemp %q) && cp -p -- %q \"\$backup\" && digest=\$(sha256sum %q | cut -d ' ' -f 1) && printf '%%s\n%%s' \"\$backup\" \"\$digest\"; fi" \
  "$REMOTE_PATH" "${REMOTE_PATH}.backup.XXXXXX" "$REMOTE_PATH" "$REMOTE_PATH"
backup_metadata="$(run_remote "$backup_command")"
if [[ -n "$backup_metadata" ]]; then
  if [[ "$backup_metadata" != *$'\n'* ]]; then
    echo "Remote server returned incomplete backup metadata." >&2
    exit 1
  fi
  remote_backup="${backup_metadata%%$'\n'*}"
  backup_sha="${backup_metadata#*$'\n'}"
  if [[ ! "$remote_backup" =~ ^${REMOTE_PATH//./\.}\.backup\.[A-Za-z0-9]+$ || ! "$backup_sha" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Remote server returned unexpected backup metadata." >&2
    exit 1
  fi
fi
printf -v publish_command "chmod 0644 -- %q && mv -f -- %q %q" \
  "$remote_tmp" "$remote_tmp" "$REMOTE_PATH"
live_replaced=1
run_remote "$publish_command"
remote_tmp=""

curl_metadata="$(
  curl \
    --fail \
    --silent \
    --show-error \
    --location \
    --proto '=https' \
    --proto-redir '=https' \
    --connect-timeout 15 \
    --max-time 45 \
    --header 'Cache-Control: no-cache' \
    --output "$downloaded" \
    --write-out $'%{url_effective}\n%{content_type}' \
    "$PUBLIC_URL"
)"
effective_url="${curl_metadata%%$'\n'*}"
content_type="${curl_metadata#*$'\n'}"
if [[ "$effective_url" != "$PUBLIC_URL" ]]; then
  echo "Published privacy policy redirected away from its canonical URL." >&2
  exit 1
fi
normalized_content_type="$(printf '%s' "$content_type" | tr '[:upper:]' '[:lower:]')"
if [[ "$normalized_content_type" != "text/html" && "$normalized_content_type" != "text/html;"* ]]; then
  echo "Published privacy policy must return a text/html Content-Type." >&2
  exit 1
fi
python3 "$RENDERER" --check "$downloaded"

remote_sha="$(shasum -a 256 "$downloaded" | awk '{print $1}')"
if [[ "$local_sha" != "$remote_sha" ]]; then
  echo "Published privacy policy bytes do not match the reviewed local file." >&2
  exit 1
fi

if [[ -n "$remote_backup" ]]; then
  printf -v remove_backup_command "rm -f -- %q" "$remote_backup"
  run_remote "$remove_backup_command"
  remote_backup=""
fi
live_replaced=0
release_lock
echo "Published privacy policy: $PUBLIC_URL (sha256=$local_sha)"
