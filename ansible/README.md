# ansible

Configuration for the staging host, so that "what did we configure on that box" has a file for an
answer. `doc/staging-environment.md` §9 asks for this from the start — not because staging needs
Ansible, but because it makes production's eventual rebuild a known quantity.

Only the `hardening` role exists so far. Its reasoning, host by host, is in
`doc/staging-hardening.md` — **which is deliberately not in this repo** (see `.gitignore`), because
it is specific about one internet-facing machine in a way this role is not. Ask kspar for it. The
role's own comments carry the parts that generalise, and are worth reading before a first run.

## Running it

```sh
brew install ansible          # or pipx install ansible
cd ansible
cp inventory.example.yml inventory.yml && $EDITOR inventory.yml   # first time only
ansible-playbook site.yml --check --diff --ask-become-pass        # dry run first
ansible-playbook site.yml --ask-become-pass
```

**`inventory.yml` is not in the repo.** It holds the host addresses and the list of accounts allowed
to log in, which is the one thing in this directory worth keeping off a public repo — see the note
in `.gitignore`. Everything else, the role included, is standard hardening whose security does not
depend on being unreadable. The role has no default list of users and asserts a non-empty one, so a
missing inventory fails with an explanation rather than writing `AllowGroups` for an empty group.

The connecting account needs a password for sudo — that is the right posture and is left alone,
hence `--ask-become-pass`.

**`--ask-become-pass` needs a real terminal.** It wants a TTY it can turn echo off on. Anywhere that
cannot — a piped shell, an editor's run pane, a tool that captures output — falls back to
`GetPassWarning: Can not control echo on the terminal` and your sudo password is typed in the clear
into whatever is recording that session.

### Without the prompt

For runs that have no human at a terminal, `./run.sh` reads the sudo password from the macOS login
keychain and hands it to Ansible over a pipe. Store it once:

```sh
security add-generic-password -a "$USER" -s easy-staging-become -T /usr/bin/security -U -w
```

`-w` with no value prompts with echo off, so the password never lands in shell history, in a file,
or in a transcript. Then:

```sh
./run.sh site.yml --check --diff    # dry run
./run.sh site.yml --diff            # apply
```

Three things this deliberately is not:

- **Not a plaintext file.** `--become-password-file` accepts a path, and the obvious move is a
  0600 file in `~/.config`. The keychain keeps it encrypted at rest and readable only while your
  login session is unlocked, for the same effort.
- **Not Ansible Vault.** Vault would mean an encrypted file plus a vault password on disk to open
  it — the same secret one level down, with more ceremony. And this repo is public, so an encrypted
  secret committed to it is a liability rather than a convenience.
- **Not `NOPASSWD` sudo.** Removing the password from the host is the real fix for *automated*
  runs, but it is a weaker host, and the deliberate posture here keeps sudo behind a password. The
  one exception already planned is the single scoped grant the deploy script needs (see "Adding the
  rest" below).

Worth being clear-eyed about the trade: anything that can read that keychain item can become root
on the staging host. That is the point of storing it, and it is why the item is scoped to staging —
do not reuse it for production.

**Open a second SSH session and keep it open for the duration.** Ubuntu 24.04 runs sshd under socket
activation, so a bad config applies to the *next* connection with no restart involved — an already
open session is the difference between a fix and a rebuild. Assume the cloud console is not a way
back in unless you have specifically checked that it is.

If the run is happening headless — no terminal to hold that second session in — start a master
connection first and keep the rescue channel on it:

```sh
ALIAS=your-ssh-config-alias
ssh -M -S /tmp/$ALIAS-rescue.sock -o ControlPersist=yes -fN $ALIAS
ssh -S /tmp/$ALIAS-rescue.sock $ALIAS 'sudo tail /etc/ssh/sshd_config.d/10-hardening.conf'
```

Commands over that socket ride the already-authenticated channel instead of opening a new
connection, so they keep working even if sshd would now refuse a fresh login — which is exactly the
failure this is insurance against. Tear it down with `-O exit` when the run is verified.

## Layout

```
ansible.cfg              inventory path, yaml output, ssh multiplexing
inventory.example.yml    the committed template
inventory.yml            NOT IN GIT — real hosts and who may log in
run.sh                   the same run with the sudo password from the keychain
site.yml                 the play; further roles get added here
roles/hardening/         sshd, ufw, fail2ban, unattended-upgrade reboot policy
```

Hosts are named by their `~/.ssh/config` alias, so the address, user and key live in one place and
`ssh <alias>` and `ansible-playbook` cannot drift apart.

## Conventions

- **Everything is a variable with a default.** `roles/hardening/defaults/main.yml` is also where the
  reasoning for each value lives — read it before overriding one.
- **Guards come before changes.** The hardening role asserts the OS, that every allowed user
  exists, that the connecting user is among them, and that they have a working key, all before it
  touches sshd. On a host with no console, a lockout is a rebuild.
- **Validate before writing, verify after.** The sshd drop-in is checked by `sshd -t` as a template
  `validate:`, then the combined config is checked again and rolled back if it broke.
- Roles are named for what they produce, and each task name reads as a sentence in the output.

## Adding the rest

The remaining phase-1 work from the staging plan — Apache vhosts, postgres, Docker, the
`easy-core` and `easy-executor` units, mailpit, the backup cron — belongs in this same play as
further roles. Two things worth carrying over when you write them:

- The deploy script needs exactly one sudo grant: `NOPASSWD: /usr/bin/systemctl restart easy-core`.
  Scope it to that command; do not give the deploy account general sudo.
- Docker's iptables rules bypass ufw, so a published container port is internet-reachable even when
  `ufw status` says otherwise. This is not a bug anyone intends to fix. Publish container ports as
  `127.0.0.1:host:container`, or filter in the `DOCKER-USER` chain.
