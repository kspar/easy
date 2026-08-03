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
# Usage — any ansible-playbook arguments are passed straight through, against the staging inventory:
#
#   ./run.sh site.yml --check --diff
#   ./run.sh site.yml --diff
#
# Staging only. Production is interactive by design; see the check below and the README.
#
set -euo pipefail

KEYCHAIN_SERVICE="${EASY_BECOME_KEYCHAIN_SERVICE:-easy-staging-become}"
INVENTORY="${EASY_INVENTORY:-inventories/staging}"

cd "$(dirname "$0")"

# Staging only, and not as a formality.
#
# The password this reads can be read by anything running as this user — that is the trade accepted
# for unattended staging runs. Production is where that trade stops being acceptable: it runs with
# --ask-become-pass and a person watching. Refusing here rather than documenting a convention means
# the wrong environment is a failure instead of a habit.
for arg in "$@"; do
  case "$arg" in
    *production*|*prod*)
      echo "run.sh is staging-only, and '$arg' does not look like staging." >&2
      echo "Production runs interactively, with a human present:" >&2
      echo "  ansible-playbook -i inventories/production site.yml --check --diff --ask-become-pass" >&2
      exit 2
      ;;
  esac
done

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
#
# -i comes first so an explicitly passed -i later on the command line still wins — useful for a second
# staging host, and harmless because anything production-shaped was already refused above.
exec ansible-playbook -i "$INVENTORY" "$@" \
  --become-password-file <(security find-generic-password -s "$KEYCHAIN_SERVICE" -w)
