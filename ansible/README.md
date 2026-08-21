# ansible

Configuration for the dev host, so that "what did we configure on that box" has a file for an
answer. `doc/dev-environment.md` §9 asks for this from the start — not because dev needs
Ansible, but because it makes production's eventual rebuild a known quantity.

As of 2026-08-08 this builds both dev hosts: the core host (hardening, postgres, core's config
and systemd unit, nginx with TLS) and the **IdP host** (Keycloak 25 on its own postgres, behind its
own nginx). What is missing is the executor and mailpit.

The hardening role's reasoning, host by host, is in `doc/dev-hardening.md` — **which is
deliberately not in this repo** (see `.gitignore`), because it is specific about one internet-facing
machine in a way the role is not. Ask kspar for it. The roles' own comments carry the parts that
generalise, and are worth reading before a first run.

## Running it

```sh
brew install ansible          # or pipx install ansible
cd ansible
cp inventories/dev/hosts.example.yml inventories/dev/hosts.yml   # first time only
$EDITOR inventories/dev/hosts.yml
ansible-playbook -i inventories/dev site.yml --check --diff --ask-become-pass   # dry run first
ansible-playbook -i inventories/dev site.yml --ask-become-pass
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

### Where credentials live

**Nowhere in this repo, and nowhere on your laptop.**

Core's config is two files on the server. `application.yaml` is written by the `core_config` role
every run and contains no credentials at all — the role greps the result and fails if one appears.
`secrets.yaml` next to it holds the database password, the Keycloak client secret and the Moodle
token, and Ansible only ever checks its *shape*: which keys exist, what mode it has. It never reads a
value.

The database password is generated **on the host** with `creates:`-style protection, so it never
crosses the network, never reaches the controller, and is never re-rolled by a later run (which would
leave core holding a credential postgres no longer accepts).

This started as a question about how to store secrets on the controller — vault, encrypted, gitignored
— and the better answer was to notice the controller never needs them. The server has to hold them in
plaintext regardless, because Spring reads them at startup; the only real choice was whether a
*second* copy existed too. There is no second copy, so there is nothing to encrypt, nothing to
gitignore, and nothing to hand to a colleague. Production benefits more than dev: its credentials
never touch anyone's machine.

The trade: rebuilding a host from nothing needs the secrets from somewhere else, and Ansible cannot
rotate what it cannot read. **Back `secrets.yaml` up** — it is a handful of lines and it is the only
copy.

### Checking a host without changing it

```sh
./run.sh smoke.yml                                              # dev
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

### The inventory is whatever your checkout says

A run applies the values in *your* working tree, so two checkouts of this repo are two different
answers to "what is dev configured with" — and the roles here are declarative, which means the older
checkout does not lose the race, it wins it. Whatever it holds is asserted over whatever was there.

That is not hypothetical. On 2026-08-21 the dev IdP's hostname was changed and applied from a
worktree; thirteen minutes later a run from a checkout that predated the push put the old hostname
back, and core spent the next few minutes answering 401 to every request because it was fetching
JWKS from a name whose certificate had just been retired. Nothing warned anybody: the second run was
correct about its own inventory and reported success.

So **pull before you run**, and if more than one person or session is working on a host, say so
first. Two habits make it cheap to catch:

- `./run.sh site.yml --check --diff --limit <host>` before an apply. A change you did not expect,
  in a role you did not touch, is somebody else's work about to be reverted — or yours, already.
- After applying config, look again a few minutes later rather than only at the moment the run
  finishes. `roles/core_config` writes with `backup: true`, so the host keeps a dated record of
  every version and who won:

  ```sh
  ssh <host> 'ls -lat --time-style=full-iso /srv/easy/conf/'   # application.yaml.<pid>.<date>~
  ```

### Environments

`roles/hardening/defaults/main.yml` holds the **strict** values — the answer for the host that
matters most — and each environment loosens what it needs to in its own `group_vars/all.yml`, with
the reason beside the override. Written the other way round, dev's conveniences would be what a
new production host silently inherits. Safe by omission; every relaxation deliberate.

`inventories/dev/group_vars/all/` is the worked example: agent forwarding on because everything
is co-located behind loopback there, a longer fail2ban leash because verifying the host means failing
auth at it, unattended reboots because there is nothing to interrupt.

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
  only exception is the deploy account, which gets two exact commands — restart core, and read its
  log — and nothing wider.

Worth being clear-eyed about the trade: anything that can read that keychain item can become root
on the dev host. That is the point of storing it, and it is why the item is scoped to dev —
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
site.yml                     the plays: hardening everywhere, services by group
smoke.yml                    read-only health check, safe against production
grading-check.yml            does grading actually work — executor directly, then through core.
                             Submits work and runs a container, so NOT production-safe
run.sh                       dev only, sudo password from the keychain
roles/hardening/             sshd, ufw, fail2ban, unattended-upgrade reboot policy
roles/keycloak/              the IdP host entire: JVM, postgres, Keycloak, its unit, nginx, TLS,
                             and the /idp-admin/ gate in front of the admin console
roles/core_config/           core's config, its secrets file, and the guards on both
roles/postgres/              cluster on loopback, role, database
roles/core_service/          the systemd unit, the release tree, the deploy grant
roles/nginx/                 TLS, the SPA vhost, the API proxy
roles/executor/              Docker, the aae service as a non-root user, and the database rows
                             that make core aware of it
roles/executor_images/       the grading images: pulled from GHCR, verified, and made live — see
                             doc/aae/grading-images.md
roles/smoke/                 what smoke.yml runs
inventories/
  dev/
    hosts.example.yml        the committed template
    hosts.yml                NOT IN GIT — real hosts and who may log in
    group_vars/all.yml       what dev loosens, and why
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

Still to write: **mailpit** as a local catch-all so mail stays testable and cannot escape (§5), and
the **backup timer with a verified restore** (EZ-1114, EZ-1738 — and now `cloakdb` too, whose users
are the one thing on the IdP host that `doc/idp-setup.md` cannot reproduce).

The **executor** is now `roles/executor` (§6): Docker, `easy-executor.service` running gunicorn as a
non-root account whose only special grant is `docker`, and the `container_image` / `executor` /
`executor_container_image` rows without which a perfectly healthy executor is invisible to core.

The four grading images moved out to `roles/executor_images` (EZ-1781). They are built and verified by
CI, published to GHCR under a tag that is never overwritten, and pulled here — so a version bump no
longer needs anybody with a shell, every host runs provably the same bytes, and rollback is a retag
rather than a rebuild. `doc/aae/grading-images.md` is the reference;
`doc/aae/bumping-a-grading-library.md` is what a library maintainer reads.

`roles/keycloak` is the worked example of the "service roles target groups" advice below: the IdP was
always a separate VM, so nothing in it could assume co-location, and it brings its own postgres and
its own nginx for that reason. It duplicates `roles/nginx`'s certbot dance rather than sharing it —
deliberately, for now; if a third vhost appears, that is the moment to reconsider.

**Write them as service roles applied to groups, not as "the dev box".** Production separates
onto three hosts (core, IdP, executor) what dev keeps on one, so a role that assumes postgres or
the executor is on `127.0.0.1` is already wrong for production. Those addresses want to be variables
from the first role that touches them; retrofitting them later is the expensive version. `site.yml`
already has a play per group, and wants `serial: 1` before anything targets more than one host.

Four things learned building the existing roles, each of which cost a debugging round:

- **Guard on what exists, not on `ansible_check_mode`.** A dry run against a host that lacks the
  package simulates the install, so every task after it fails on something that is not there and
  aborts the run before the interesting output. `stat` the binary and key the rest on that: dry runs
  against an *installed* host then still check everything, which is the case that matters later.
  postgres, the Java runtime and nginx all needed this.
- **`set_fact` through a folded scalar gives you a string.** `"False"` is perfectly truthy in Jinja,
  so `{% if some_fact %}` takes the wrong branch and the symptom is a config that ignores your
  variable. Use `| bool` at the point of use.
- **Validate before reload, and roll back if it fails.** nginx and sshd can only validate a whole
  configuration, so a bad fragment is already on disk when you find out. Both roles restore the
  previous file and refuse to reload. nginx's rejected its own generated config on the first real
  run, which is exactly when you want that to work.
- **Docker's iptables rules bypass ufw**, so a published container port is internet-reachable even
  when `ufw status` says otherwise. This is not a bug anyone intends to fix. Publish container ports
  as `127.0.0.1:host:container`, or filter in the `DOCKER-USER` chain — and note that `smoke.yml`
  checks for exactly this.
