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
# Usage — any ansible-playbook arguments are passed straight through, against the dev inventory:
#
#   ./run.sh site.yml --check --diff
#   ./run.sh site.yml --diff
#
# Dev only. Production is interactive by design; see the check below and the README.
#
set -euo pipefail

# `easy-staging-become` and not `easy-dev-become`, alone among the names in this repo: it is a
# keychain item that already exists on the machines that use this, and renaming it here would only
# make run.sh stop finding it. Override with EASY_BECOME_KEYCHAIN_SERVICE if yours is named
# something else.
KEYCHAIN_SERVICE="${EASY_BECOME_KEYCHAIN_SERVICE:-easy-staging-become}"
INVENTORY="${EASY_INVENTORY:-inventories/dev}"

cd "$(dirname "$0")"

# Dev only, and not as a formality.
#
# The password this reads can be read by anything running as this user — that is the trade accepted
# for unattended dev runs. Production is where that trade stops being acceptable: it runs with
# --ask-become-pass and a person watching. Refusing here rather than documenting a convention means
# the wrong environment is a failure instead of a habit.
#
# What is checked is the INVENTORY, because that is what decides which hosts are touched — there is
# no default inventory and no inventory holds two environments (see ansible.cfg). This used to match
# `prod` anywhere in any argument, which also refused `import-prod-dump.yml`: a playbook whose whole
# job is loading a production dump *into dev*, i.e. the exact case the guard exists to protect and
# not the one it was refusing.
inventories=("$INVENTORY")
previous=""
for arg in "$@"; do
  case "$previous" in
    -i|--inventory|--inventory-file) inventories+=("$arg") ;;
  esac
  case "$arg" in
    -i=*|--inventory=*|--inventory-file=*) inventories+=("${arg#*=}") ;;
  esac
  previous="$arg"
done

# Production is refused by default and stays that way. What follows is a deliberate, temporary
# opt-in, added 2026-08-22 because production needed a firewall and `--ask-become-pass` cannot be
# answered from a tool with no TTY — see the README. Three conditions, all required, because any one
# of them alone would decay into a habit:
#
#   EASY_ALLOW_PRODUCTION=yes      typed each time, never exported in a shell profile
#   EASY_INVENTORY / -i            naming production explicitly, as always
#   a keychain item whose name says production, and which is NOT the dev one
#
# The last is the one that earns its place. Reusing `easy-staging-become` here would mean one stored
# secret that unlocks root on both environments, so revoking dev's convenience would revoke
# production's too and nobody would do it. Separate items mean
# `security delete-generic-password -s easy-prod-become` revokes production alone, completely, with
# no other change — which is what makes this grant temporary rather than permanent-by-accident.
production=false
for inventory in "${inventories[@]}"; do
  case "$inventory" in
    *prod*) production=true ;;
  esac
done

if [ "$production" = true ]; then
  if [ "${EASY_ALLOW_PRODUCTION:-}" != "yes" ]; then
    cat >&2 <<EOF
Refusing production: run.sh is dev-only unless you say otherwise, on purpose.

Production normally runs interactively, with a human present:

  ansible-playbook -i inventories/production site.yml --check --diff --ask-become-pass

To use a stored password anyway — which lets anything running as you become root on
production, and is a decision rather than a shortcut:

  EASY_ALLOW_PRODUCTION=yes EASY_BECOME_KEYCHAIN_SERVICE=easy-prod-become \\
    EASY_INVENTORY=inventories/production ./run.sh site.yml --diff
EOF
    exit 2
  fi

  case "$KEYCHAIN_SERVICE" in
    easy-staging-become|*dev*)
      echo "Refusing: '$KEYCHAIN_SERVICE' is dev's keychain item, and production does not share it." >&2
      echo "Store a separate one and name it for production, so it can be revoked on its own:" >&2
      echo "  security add-generic-password -a \"\$USER\" -s easy-prod-become -T /usr/bin/security -U -w" >&2
      exit 2
      ;;
    *prod*) : ;;
    *)
      echo "Refusing: '$KEYCHAIN_SERVICE' does not name production, so it is probably not the item you meant." >&2
      exit 2
      ;;
  esac

  echo "!! PRODUCTION, with a stored sudo password. Revoke with:" >&2
  echo "!!   security delete-generic-password -s $KEYCHAIN_SERVICE" >&2
fi

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
# dev host, and harmless because anything production-shaped was already refused above.
exec ansible-playbook -i "$INVENTORY" "$@" \
  --become-password-file <(security find-generic-password -s "$KEYCHAIN_SERVICE" -w)
