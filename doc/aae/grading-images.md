# Grading images

The four Docker images that grade submissions — `tiivad`, `silmused`, `pygrader`, `imgrec` — how their
library versions are chosen, how the images are built, and how a host comes to run one.

If you maintain one of those libraries and just want to bump a version, read
[`bumping-a-grading-library.md`](bumping-a-grading-library.md) instead. This is the reference.

## Why this exists

Before August 2026 a version lived as a literal in a Dockerfile. Bumping one meant a pull request, a
core dev to merge it, and a core dev again to run Ansible. Twenty-odd silmused bumps went that way.

PR #70 exposed the deeper problem. The Ansible role only rebuilt images that were **missing**, so the
Dockerfile on the host said `silmused==1.7.11` while grading actually ran 1.7.4 — for a fortnight,
invisibly, because nothing anywhere recorded what was installed. And rollback was impossible even by
hand: `docker build -t silmused .` moves the tag in place, so the previous image survived only as
untagged layers.

Three things follow from that, and the design is mostly those three:

- The version has one home, and it is not a Dockerfile.
- Every image says what is **actually installed** in it, checked before publication.
- Every version ever published still exists under a name, so going back is a retag.

## The pieces

```
doc/aae/pins/dev.yml            what dev should run
doc/aae/pins/prod.yml           what production should run
doc/aae/dockerfiles/<image>     how to build it — with ARGs, no version literals
doc/aae/dockerfiles/smoke/      the check baked into each image
bin/pins.py                     the only thing that parses any of the above
bin/build-grading-images.sh     build, verify, publish
.github/workflows/grading-images.yml    runs that, on a pull request and on master
.github/workflows/pins-guard.yml        fast feedback for a contributor
.github/workflows/pins-automerge.yml    the merge decision
.github/pins-bumpers.yml        who may bump what, where
ansible/roles/executor_images/  the host side: pull, verify, retag, gate, prune
```

### The pins files

Flat, one pin per line, `<image>.<BUILD_ARG>: "value"`, values always quoted. Deliberately a poor
format for writing config by hand and a good one for this job:

- **A merge bot has to prove a diff is only a version substitution.** With one fact per line that is a
  short function (`parse_patch` in `bin/pins.py`); with nested YAML it would be a semantic tree
  comparison, and a merge bot nobody fully understands is worse than none.
- **Unquoted, YAML reads `1.10` as the float `1.1`.** An image built from `silmused==1.1` would install
  a real, wrong, years-old release. The parser rejects an unquoted value rather than accepting it.
- **No new dependency.** `aae/requirements-dev.txt` documents a deliberately minimal list, because the
  executor host runs untrusted code. Flat `key: "value"` is still valid YAML, so anything else can
  read these files, and `grep` works.

**One file per environment**, because the unit of permission is the file. "Who may change this?" is
answered by the path before anything is parsed, which is what lets somebody be trusted with dev and
not production — and what stops one pull request touching both.

### bin/pins.py

Everything that needs a pin goes through it: CI, the aae test suite, the image builder, the merge bot,
Ansible. Useful subcommands:

```sh
bin/pins.py get silmused.SILMUSED_VERSION      # 1.7.11
bin/pins.py args --docker silmused             # --build-arg SILMUSED_VERSION=1.7.11 …
bin/pins.py order                              # pygrader imgrec silmused tiivad
bin/pins.py digest silmused                    # bbbbaca01eba
bin/pins.py declared tiivad                    # numpy~=1.23.4 tiivad==0.0.33
bin/pins.py check-exists --env dev             # is every pinned version published?
bin/pins.py git-ref pygrader                   # the pinned python-grader commit
```

Two things are **derived rather than declared**, so they cannot fall out of step:

- **The dependency order**, read from the `FROM` lines. `imgrec` builds `FROM pygrader`, and the old
  role had no way to know that — it documented the resulting staleness as a known gap and declined to
  fix it. Reading the Dockerfile closes it.
- **The inputs digest**: sha256 over the Dockerfile, its smoke script, its resolved args,
  `rebuild.SERIAL`, and recursively its base's digest. So a pygrader bump changes imgrec's digest and
  rebuilds it too.

The digest deliberately excludes the environment name. Two environments pinning the same versions
resolve to one digest, one build, one artefact — which is what promoting a version dev has proved
should mean.

### The images

Each Dockerfile takes its versions as build args, declared as late as possible (an `ARG` invalidates
every layer after it, and a silmused bump must not rebuild postgres), and checks the value in the same
layer that uses it. A build given no argument does not fail — docker substitutes the empty string —
so without that check the error is pip complaining about `numpy~=`, which says nothing about the
missing argument behind it.

Each image carries `/easy-smoke.sh`, which asserts installed == declared and then exercises what the
image is for: silmused starts its PostgreSQL and runs a query, imgrec makes a pixel (Pillow without
its compiled extension imports fine and dies on first use), tiivad imports the grader. The script is
baked in so CI, the host and a human all run the identical check.

After a successful build and smoke, three labels are stamped on:

| Label | Meaning |
| --- | --- |
| `easy.grading.inputs` | the digest — this image's identity |
| `easy.grading.declared` | what the pins asked for |
| `easy.grading.installed` | what pip actually resolved, read out of the finished image |

`installed` is the one that matters. It is why the About page can report real versions without running
anything, and why a `docker inspect` answers about the artefact rather than about a source file that
may have been placed but never built.

The declared string is also set as the environment variable `EASY_GRADING_DECLARED`, so a published
image can check *itself*: a label is metadata about an image and is invisible to a process inside it,
so `/easy-smoke.sh` had nothing to compare against and exited saying nothing was verified — which is
what the production runbook below tells an operator to run.

```sh
docker inspect --format '{{index .Config.Labels "easy.grading.installed"}}' silmused
```

### The registry

`ghcr.io/kspar/easy/<image>`, public — so the executor host needs **no registry credential at all**,
not even the read-only token the core deploy path requires.

| Reference | Mutable | Purpose |
| --- | --- | --- |
| `:i<12 hex>` | never | the identity. What a host pulls, what a rollback names |
| — | — | there is deliberately no `:<version>` tag; see below |
| `:dev`, `:prod` | yes | the channel an environment tracks |

CI **never rebuilds a digest it has already published**. That is what makes the pinned tag genuinely
immutable, and it is why a revert is fast: the reverted pins reproduce a digest that already exists,
so the build is skipped and the host usually still has the image.

There is no `:<version>` alias, though an early draft had one. It read as if it identified an
artefact and did not: two environments pinning the same library version but a different
`rebuild.SERIAL` produce two digests, both of which would claim `:1.7.11`, and the second push would
silently win. The digest is the identity; the labels on the package page say the rest.

Builds run on the daemon's default builder rather than a buildx container driver, which costs the
`type=gha` layer cache. `imgrec` resolves `FROM pygrader` against an image built moments earlier in
the same loop, and the label step builds `FROM` the staged image — neither of which a container
driver can see, since it has no access to the daemon's image store.

The four packages are **public without anybody making them so**: a package published by
`GITHUB_TOKEN` from this repository is linked to it and inherits its visibility. Confirmed by
anonymous pull — a token from `ghcr.io/token` with no credentials fetches every `:dev` manifest.

Worth knowing rather than assuming, because it is a property of *how* they were published. A package
created any other way — by hand with a PAT, or from a different repository — does not inherit
anything, and would have to have its visibility set before a host could pull it.

### The host

`ansible/roles/executor_images` installs `/usr/local/bin/easy-grading-sync` and a timer that runs it
every five minutes.

**It gets there by an Ansible run** — `ansible/run.sh site.yml --limit core` — and not by itself.
Nothing pulls this onto a host automatically: the `core_autodeploy` timer installs core build
artifacts and knows nothing about Ansible roles. So a host that has never had the role applied keeps
whatever grading images it already had, however many versions CI has published in the meantime. Easy
to misread as broken, because everything upstream looks green.

Per image, per tick:

1. Pull `<registry>/<image>:<channel>`.
2. If its digest matches what is recorded **and** the bare tag really resolves to it, stop. This is the
   steady state. Checking both is what makes "documented 1.7.11, grading with 1.7.4" unrepresentable
   rather than merely unlikely.
3. Run the image's own smoke check — **before** the bare tag moves, so a bad image never grades.
4. Move the bare tag, and every extra tag (`tiivad:tsl-compose`, which TSL exercises ask for by name).
5. Grade a synthetic submission end to end. On failure, retag the previous image back.
6. Record `state.json`; prune old versions past `executor_grading_keep`.

A digest that fails either gate is **quarantined**. Without that it would be retried every tick —
grading broken for a minute in every five, indefinitely.

Quarantine is the one state that needs a person, because the host will not leave it on its own. What
it looks like, and how to get out of it:

```sh
ssh easycoredev 'sudo journalctl -u easy-grading-sync -n 60 --no-pager'
#   silmused: i7f3a9c1d2b4e failed its gate before and is quarantined; still on i90585262628a.
#   Clear it with --unquarantine silmused to try again.

ssh easycoredev 'sudo cat /srv/easy/aae/images/state.json'    # quarantine: [...] per image

# Once the cause is fixed — usually a new version published over the bad one:
ssh easycoredev 'sudo /usr/local/bin/easy-grading-sync --config /etc/easy/grading-sync.json \
  --unquarantine silmused'
```

Clearing it does not retry anything by itself; the next tick does. And note what the host is doing
meanwhile: still grading, on the last version that passed both gates. A quarantined image is a
deferred upgrade, not an outage — which is why nothing pages anybody about it, and why it needs
looking for rather than waiting for.

Direction is pull, so nothing in GitHub holds a credential for the host and there is no inbound access.
The trust anchor is that CI moves a channel tag only after the image, and everything it builds on,
passed. It runs as root with no service account, because `docker` group membership is equivalent to
root anyway and an account in that group would be confinement theatre.

**The images track `master`, not `dev-releases`.** Promoting `dev-releases` needs push access, which a
library maintainer does not have, so coupling them would defeat the point. Consequence: "what is dev
running" has two answers, one for the application and one for the graders.

## Rollback

| Who | How | Speed |
| --- | --- | --- |
| A maintainer | **Revert** on the merge commit → auto-merges | minutes; no rebuild |
| kspar, in a hurry | stop the timer, `docker tag <reg>/silmused:i<prev> silmused` | seconds, offline |
| Nobody | a failed gate reverts and quarantines by itself | — |

Stop the timer first, or the next tick puts the channel's version back — the same non-stickiness
`deploy/README.md` documents for `deploy.sh`. `state.json` names the previous reference.

## Production

Not automated. Production is not managed by this repository at all: no inventory entry, no
`group_vars`, and `ansible/run.sh` refuses any production-shaped inventory. What exists is the same
artefacts and a manual promotion:

```sh
# 1. What has dev proved?
ssh easycoredev 'sudo cat /srv/easy/aae/images/state.json'
# 2. Pull that exact image. Nothing is live yet.
ssh easyexecprod 'sudo docker pull ghcr.io/kspar/easy/silmused:i<digest>'
# 3. Prove it in place, still not live.
ssh easyexecprod 'sudo docker run --rm --network none --memory 768m \
  ghcr.io/kspar/easy/silmused:i<digest> /easy-smoke.sh'
# 4. Make it live — one metadata operation. For tiivad, BOTH names.
ssh easyexecprod 'sudo docker tag ghcr.io/kspar/easy/silmused:i<digest> silmused'
# Rollback, instant and offline:
ssh easyexecprod 'sudo docker tag ghcr.io/kspar/easy/silmused:i<previous> silmused'
```

Promote the **artefact**, not the version number: pulling the digest dev has been grading with runs the
same bytes, where re-pinning the version and rebuilding might not.

Onboarding production properly is an inventory entry plus `easy_environment: prod`, then populating
`prod:` in the allowlist. The pins file, channel tag and allowlist entries already exist.

> **Never `docker system prune -a` on an executor host.** It always broke grading, because
> `aae/containers.py` builds `FROM <name>` and never pulls — and now it also deletes every rollback
> target. Plain `docker image prune` spares tagged images; `-a` does not.

## Auto-merge, and why it is safe

`pins-guard.yml` runs on the pull request and is what a human reads. It is **not** the decision: on a
`pull_request` event the workflows that run come from the pull request's own head, so a contributor
could replace that file with `exit 0`.

`pins-automerge.yml` decides, on `workflow_run`, which runs master's definitions with a write token
after the checks have finished. Four things in it are load-bearing:

1. `actions/checkout` is given `ref: master` explicitly. With no ref, a `workflow_run` checkout takes
   the *pull request's* code into a job holding a write token.
2. The pull request is found by head SHA — `workflow_run.pull_requests` is empty for fork pull
   requests.
3. Every required check is verified by name. A green check proves nothing when the pull request
   supplied the workflow that produced it, and "nothing failed" is true of an empty list.
4. The merge passes `sha`, so it 409s rather than merging a commit pushed after validation.

The allowlist is per `(environment, image)`. This repository is public, so without it anyone could
move dev's grading library to any published version — and CI being green does not make that not
vandalism.

## Related

- [`bumping-a-grading-library.md`](bumping-a-grading-library.md) — the maintainer's version of this
- `ansible/README.md` — running the roles
- `deploy/README.md` — how core and web are deployed, which is a separate track
- EZ-1781 — the issue this came from; EZ-1737 — executor disk, which retention partly addresses
