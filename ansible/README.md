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
cp inventories/staging/hosts.example.yml inventories/staging/hosts.yml   # first time only
$EDITOR inventories/staging/hosts.yml
ansible-playbook -i inventories/staging site.yml --check --diff --ask-become-pass   # dry run first
ansible-playbook -i inventories/staging site.yml --ask-become-pass
```

**`-i` is mandatory, and that is the main safety property here.** There is no default inventory and
no inventory contains two environments, so the environment cannot be omitted or forgotten — the
alternative, one inventory kept apart by remembering `--limit`, puts production's sshd one missing
flag away from being rewritten. Without `-i` the play matches no hosts and does nothing.

**`hosts.yml` is not in the repo.** It holds the host addresses and the list of accounts allowed to
log in, which is the one thing in this directory worth keeping off a public repo — see the note in
`.gitignore`. Everything else, the role included, is standard hardening whose security does not
depend on being unreadable. The role has no default list of users and asserts a non-empty one, so a
missing inventory fails with an explanation rather than writing `AllowGroups` for an empty group.

### Checking a host without changing it

```sh
./run.sh smoke.yml                                              # staging
ansible-playbook -i inventories/production smoke.yml --ask-become-pass
```

`smoke.yml` only reads, so it is safe against production and is the right first thing to point at a
host this repo has never managed. It verifies the sshd posture is still in effect, that the SSH group
matches the inventory, that ufw and the fail2ban jail are up, that the clock is NTP-synchronised
(JWT expiry and every assignment deadline depend on it), that no certificate is near expiry, that
disk is under 80%, and — the check most likely to earn its keep — that **nothing is listening on a
public address except 22, 80 and 443**. That last one is the only way the Docker-bypasses-ufw problem
becomes visible, because `ufw status` will happily report deny for a port Docker published.

It exits non-zero on a problem, prints every finding rather than stopping at the first, and reports
anything it could *not* check as absent rather than counting it as a pass — a green report on a host
where nothing is installed would otherwise be the most dangerous output it could produce.

### Environments

`roles/hardening/defaults/main.yml` holds the **strict** values — the answer for the host that
matters most — and each environment loosens what it needs to in its own `group_vars/all.yml`, with
the reason beside the override. Written the other way round, staging's conveniences would be what a
new production host silently inherits. Safe by omission; every relaxation deliberate.

`inventories/staging/group_vars/all.yml` is the worked example: agent forwarding on because
everything is co-located behind loopback there, a longer fail2ban leash because verifying the host
means failing auth at it, unattended reboots because there is nothing to interrupt.

**Nothing has been run against production.** `inventories/production/hosts.example.yml` records the
intended shape and the order to approach it in — the first useful run is `--check --diff` as an
*audit* of hosts nobody configured with this role, not an apply. Expect the Ubuntu 24.04 assert to
refuse if production is older, which is the assert working.

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
ansible.cfg                  no default inventory, yaml output, ssh multiplexing
site.yml                     the play; further roles get added here
run.sh                       staging only, sudo password from the keychain
roles/hardening/             sshd, ufw, fail2ban, unattended-upgrade reboot policy
inventories/
  staging/
    hosts.example.yml        the committed template
    hosts.yml                NOT IN GIT — real hosts and who may log in
    group_vars/all.yml       what staging loosens, and why
  production/
    hosts.example.yml        intended shape; nothing has been run against it
    group_vars/all.yml       near-empty on purpose — the defaults are the prod answer
```

Hosts are named by their `~/.ssh/config` alias, so the address, user and key live in one place and
`ssh <alias>` and `ansible-playbook` cannot drift apart.

## Conventions

- **Defaults are strict; environments loosen.** `roles/hardening/defaults/main.yml` is the production
  answer and also where the reasoning for each value lives. Relaxations go in an environment's
  `group_vars`, next to the reason. A new host should be safe by omission.
- **Guards come before changes.** The hardening role asserts the OS, that every allowed user
  exists, that the connecting user is among them, and that they have a working key, all before it
  touches sshd. On a host with no console, a lockout is a rebuild.
- **Grant lists are exclusive.** `hardening_ssh_users` is the whole set, not a set of additions:
  anyone in the SSH group who is not listed loses their membership. Adding people is not much use
  without taking them away, and an append-only list revokes nothing while looking like it did.
- **Validate before writing, verify after.** The sshd drop-in is checked by `sshd -t` as a template
  `validate:`, then the combined config is checked again and rolled back if it broke.
- Roles are named for what they produce, and each task name reads as a sentence in the output.

## Adding the rest

The remaining phase-1 work from the staging plan — Apache vhosts, postgres, Docker, the
`easy-core` and `easy-executor` units, mailpit, the backup cron — becomes further roles here.

**Write them as service roles applied to groups, not as "the staging box".** Production separates
onto three hosts (core, IdP, executor) what staging keeps on one, so a role that assumes postgres or
the executor is on `127.0.0.1` is already wrong for production. Those addresses want to be variables
from the first role that touches them; retrofitting them later is the expensive version. `site.yml`
gets a play per group at that point, and `serial: 1` before anything targets more than one host.

Three things worth carrying over when you write them:

- The deploy script needs exactly one sudo grant: `NOPASSWD: /usr/bin/systemctl restart easy-core`.
  Scope it to that command; do not give the deploy account general sudo.
- Docker's iptables rules bypass ufw, so a published container port is internet-reachable even when
  `ufw status` says otherwise. This is not a bug anyone intends to fix. Publish container ports as
  `127.0.0.1:host:container`, or filter in the `DOCKER-USER` chain.
