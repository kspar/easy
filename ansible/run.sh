#!/usr/bin/env bash
#
# Run a playbook with the sudo password read from the macOS login keychain instead of a TTY prompt,
# so a run does not require a human at a terminal.
#
# Store the password once:
#
#   security add-generic-password -a "$USER" -s easy-staging-become -T /usr/bin/security -U -w
#
# (`-w` with no value prompts with echo off, so the password is never in shell history, never in a
# file, and never in the transcript of whatever is running this. `-T /usr/bin/security` is what
# lets it be read back without a GUI confirmation dialog; `-U` updates an existing item.)
#
# Usage — any ansible-playbook arguments are passed straight through:
#
#   ./run.sh site.yml --check --diff
#   ./run.sh site.yml --diff
#
set -euo pipefail

KEYCHAIN_SERVICE="${EASY_BECOME_KEYCHAIN_SERVICE:-easy-staging-become}"

cd "$(dirname "$0")"

if ! security find-generic-password -s "$KEYCHAIN_SERVICE" -w >/dev/null 2>&1; then
  cat >&2 <<EOF
No keychain item '$KEYCHAIN_SERVICE', so there is no sudo password to use.

Store it (you will be prompted, with echo off):

  security add-generic-password -a "\$USER" -s $KEYCHAIN_SERVICE -T /usr/bin/security -U -w

Or run the playbook the interactive way instead:

  ansible-playbook "\$@" --ask-become-pass
EOF
  exit 1
fi

# Process substitution rather than a temp file: the password reaches Ansible through a pipe fd and
# is never written to disk, not even briefly with 0600 on it.
exec ansible-playbook "$@" \
  --become-password-file <(security find-generic-password -s "$KEYCHAIN_SERVICE" -w)
