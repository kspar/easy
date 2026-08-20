#!/usr/bin/env python3
"""Keep this host's grading images in step with what CI has published for its environment.

Managed by Ansible (roles/executor_images) — local edits are overwritten.

### What it is for

Until 2026-08-20 a grading library was updated by editing a Dockerfile, waiting for a core dev to
merge it, and waiting again for somebody to run Ansible by hand. Rollback was not possible at all:
`docker build -t silmused .` moves the tag in place, so the previous image survived only as untagged
layers nobody would find under pressure. This closes both halves — a published tag is pulled rather
than rebuilt, and every version this host has run is still here under a name that means something.

### Pull, not push

The same reasoning as `roles/core_autodeploy`, and one better. Nothing in GitHub holds a credential
for this box and there is no inbound access; and because the published packages are public, this
needs no registry credential either — not even the read-only token the core deploy path requires.

The trust anchor is the channel tag. CI moves `<registry>/<image>:<channel>` only after that image
has built, passed its own smoke check, and had every image it is built on do the same. So "the
channel moved" is already a claim that the image works, which is why this needs no access to CI, no
API token and no rate limit.

### The two gates, and why one is not enough

**Before it is live:** the image's own `/easy-smoke.sh` runs, driven by the versions the image itself
declares. A failure here means the bare tag is never moved, so a bad image does not grade a single
submission.

**After it is live:** a synthetic submission is graded end to end. This is what `ansible/grading-check.yml`
does by hand on whichever image happened to be first; here it runs on the image that just changed,
every time one changes. A failure retags the previous image back before anyone notices.

A failed digest is then **quarantined**. Without that it would be retried every tick — grading broken
for a minute in every five, indefinitely — and the host would keep flapping between a working image
and a broken one instead of staying put and saying so.

### Why it runs as root

`docker` group membership is equivalent to root on any Linux host, which `roles/executor` already
writes down. A service account in that group would be confinement theatre. This differs from
`core_autodeploy`, whose account genuinely cannot read `secrets.yaml`, and the difference is worth
knowing rather than guessing at.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request

DEFAULT_CONFIG = "/etc/easy/grading-sync.json"

LABEL_INPUTS = "easy.grading.inputs"
LABEL_DECLARED = "easy.grading.declared"
LABEL_INSTALLED = "easy.grading.installed"

# The output contract aae expects from a grading script: feedback, fifty '#', then `grade: N`.
# Anything else is reported as "something went wrong during grading". Kept identical to
# ansible/grading-check.yml, because two definitions of "a submission that must score 100" would
# eventually disagree about which one is broken.
GRADE_SCRIPT = """#!/bin/bash
echo "Checking the submission:"
cat /student-submission/submission.py
printf '%0.s#' $(seq 1 50)
echo
echo "grade: 100"
"""
GRADE_SUBMISSION = "print('hello from the grading gate')\n"


class Docker:
    """Everything this script does to the daemon, in one place so a test can replace it."""

    def _run(self, *args, check=True, timeout=900):
        return subprocess.run(
            ["docker", *args], capture_output=True, text=True, check=check, timeout=timeout
        )

    def pull(self, ref):
        self._run("pull", "-q", ref, timeout=1800)

    def label(self, ref, key):
        out = self._run("image", "inspect", ref, "--format", "{{index .Config.Labels " + f'"{key}"' + "}}", check=False)
        if out.returncode != 0:
            return None
        value = out.stdout.strip()
        return value or None

    def image_id(self, ref):
        out = self._run("image", "inspect", ref, "--format", "{{.Id}}", check=False)
        return out.stdout.strip() if out.returncode == 0 else None

    def tag(self, source, target):
        self._run("tag", source, target)

    def smoke(self, ref, env):
        args = ["run", "--rm", "--network", "none", "--memory", "768m"]
        for key, value in sorted(env.items()):
            args += ["-e", f"{key}={value}"]
        out = self._run(*args, ref, "/easy-smoke.sh", check=False, timeout=600)
        return out.returncode == 0, (out.stdout + out.stderr).strip()

    def local_refs(self, prefix):
        out = self._run("images", "--format", "{{.Repository}}:{{.Tag}}", check=False)
        return [r for r in out.stdout.split() if r.startswith(prefix)]

    def remove(self, ref):
        self._run("image", "rm", ref, check=False)


def declared_to_expectations(declared: str) -> dict[str, str]:
    """Turn an image's own `easy.grading.declared` label into `EASY_EXPECT_*` variables.

    Read off the image rather than passed in from configuration, deliberately: it means this host can
    verify anything it has pulled without knowing what the pins file says, and that the check is
    always "does this image contain what *it* claims" rather than "what we happen to believe about
    it". Those are the same question only while nothing has gone wrong.

    `numpy~=1.23.4 tiivad==0.0.33` -> {NUMPY_COMPATIBLE: 1.23.4, TIIVAD: 0.0.33}, prefixed.
    """
    out = {}
    for token in (declared or "").split():
        m = re.match(r"^([A-Za-z0-9_.\-]+)(==|~=)([0-9][0-9.]*)$", token)
        if not m:
            # `grader@<sha>` and anything else unrecognised: a commit is not a version any installed
            # package reports, so there is nothing to assert about it.
            continue
        name, operator, version = m.groups()
        suffix = "_COMPATIBLE" if operator == "~=" else ""
        out[f"EASY_EXPECT_{name.upper()}{suffix}"] = version
    return out


def grade_once(executor_url: str, image: str, timeout: int = 300) -> tuple[bool, str]:
    """Grade a trivial submission on this image, through the executor, and check it scores 100."""
    body = json.dumps({
        "submission": GRADE_SUBMISSION,
        "grading_script": GRADE_SCRIPT,
        "assets": [],
        "image_name": image,
        "max_time_sec": 60,
        "max_mem_mb": 256,
    }).encode()
    request = urllib.request.Request(
        f"{executor_url}/v1/grade", data=body, headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.load(response)
    except Exception as e:  # noqa: BLE001 — any failure to grade is a failed gate
        return False, f"the executor did not grade it: {e}"
    grade = payload.get("grade")
    return grade == 100, f"grade={grade!r} feedback={str(payload.get('feedback'))[:200]!r}"


def load_state(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        # A first run, or a file somebody truncated. Rebuilding it from what is live is not possible
        # — the point of the file is to remember what came before — so start empty and let the next
        # reconcile record the truth.
        return {}


def save_state(path, state):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = f"{path}.new"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(state, f, indent=2, sort_keys=True)
        f.write("\n")
    os.replace(tmp, path)


def free_gb(path):
    """Free space on the filesystem `path` lives on, or None if that cannot be determined.

    Resolves to the nearest directory that exists, rather than asking about the file itself. Asking
    about the file looks equivalent and is not: on a first run the state file has not been written
    yet, `disk_usage` raises, and the caller reads that as "cannot tell" — so the floor silently did
    nothing on exactly the run where a host is emptiest and most likely to fill up.
    """
    probe = os.path.dirname(os.path.abspath(path)) or os.sep
    while probe != os.sep and not os.path.isdir(probe):
        probe = os.path.dirname(probe)
    try:
        return shutil.disk_usage(probe).free / 1e9
    except OSError:
        return None


def reconcile(cfg, docker, state, log, grade=grade_once):
    """Bring every image into line with its channel. Returns the state to persist.

    Written as one function over an injected `docker` and `grade` so that every branch below — and
    especially the ones that must *not* move the live tag — can be driven by a test. A gate that has
    never fired is a gate that may not work.
    """
    registry = cfg["registry"]
    channel = cfg["channel"]
    changed = False

    for image in cfg["images"]:
        name = image["name"]
        extra_tags = image.get("tags", [])
        entry = dict(state.get(name) or {})
        quarantined = list(entry.get("quarantine", []))

        channel_ref = f"{registry}/{name}:{channel}"
        try:
            docker.pull(channel_ref)
        except Exception as e:  # noqa: BLE001
            log(f"{name}: could not pull {channel_ref} ({e}); leaving it as it is")
            continue

        want = docker.label(channel_ref, LABEL_INPUTS)
        if not want:
            log(f"{name}: {channel_ref} carries no {LABEL_INPUTS} label — refusing to make it live")
            continue

        pinned = f"{registry}/{name}:i{want}"
        docker.tag(channel_ref, pinned)

        # Steady state, and the overwhelmingly common case: this runs every few minutes. Checking
        # that the bare tag really resolves to the wanted image — rather than trusting the state
        # file — is what makes "documented 1.7.11, grading with 1.7.4" unrepresentable rather than
        # merely unlikely.
        if entry.get("inputs") == want and docker.image_id(name) == docker.image_id(pinned):
            continue

        if want in quarantined:
            log(
                f"{name}: i{want} failed its gate before and is quarantined; still on "
                f"i{entry.get('inputs')}. Clear it with --unquarantine {name} to try again."
            )
            continue

        need = cfg.get("min_free_gb", 0)
        available = free_gb(cfg["state_path"])
        if need and available is None:
            # Proceeding rather than refusing: a host that cannot measure its own disk would
            # otherwise stop updating silently, which is worse than the risk being guarded against.
            # Said out loud so it is not silent either way.
            log(f"{name}: cannot determine free space, so the {need} GB floor is not being enforced")
        elif need and available < need:
            log(
                f"{name}: {available:.1f} GB free, below the {need} GB floor — refusing to pull more "
                f"onto the disk that grades student code. Prune old versions or raise the floor."
            )
            continue

        declared = docker.label(pinned, LABEL_DECLARED) or ""
        expectations = declared_to_expectations(declared)
        if not expectations:
            log(f"{name}: i{want} declares no versions to verify — refusing to make it live")
            continue

        ok, output = docker.smoke(pinned, expectations)
        if not ok:
            # Before the bare tag moves, so nothing has graded on it.
            quarantined.append(want)
            entry["quarantine"] = quarantined
            state[name] = entry
            changed = True
            log(f"{name}: i{want} failed its smoke check and was NOT made live:\n{output}")
            continue

        previous = entry.get("ref")
        for target in [name, *extra_tags]:
            docker.tag(pinned, target)
        log(f"{name}: i{want} is live ({declared})")

        # `tiivad:tsl-compose` and friends move with the bare name, because TSL exercises ask for
        # that exact string and core rejects a save whose container_image has no row.
        gate_ok, detail = grade(cfg["executor_url"], name)
        if not gate_ok:
            if previous:
                for target in [name, *extra_tags]:
                    docker.tag(previous, target)
                log(f"{name}: i{want} graded wrong ({detail}) — reverted to {previous}")
            else:
                log(
                    f"{name}: i{want} graded wrong ({detail}) and there is nothing to revert to, so "
                    f"it stays live. Grading with this image is broken until somebody looks."
                )
            quarantined.append(want)
            entry["quarantine"] = quarantined
            state[name] = entry
            changed = True
            continue

        entry.update({
            "ref": pinned,
            "inputs": want,
            "declared": declared,
            "installed": docker.label(pinned, LABEL_INSTALLED) or "",
            "previous": previous,
            "quarantine": quarantined,
        })
        state[name] = entry
        changed = True
        prune(docker, cfg, name, keep=cfg.get("keep", 3), never=[pinned, previous])

    if changed:
        save_state(cfg["state_path"], state)
    return state


def prune(docker, cfg, name, keep, never):
    """Drop old versions of one image, past the newest `keep`.

    Retention is what makes a rollback a local retag with no network, so this is not merely disk
    hygiene. The live and previous references are never candidates whatever `keep` says: those two
    are the rollback.
    """
    prefix = f"{cfg['registry']}/{name}:i"
    protected = {r for r in never if r}
    candidates = [r for r in docker.local_refs(prefix) if r not in protected]
    # Oldest first is not knowable from the tag, so this drops by however `docker images` ordered
    # them, which is newest-first. Anything beyond `keep` goes.
    for ref in candidates[max(0, keep - len(protected)):]:
        docker.remove(ref)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--config", default=DEFAULT_CONFIG)
    parser.add_argument("--once", action="store_true", help="accepted for symmetry; always one pass")
    parser.add_argument("--unquarantine", metavar="IMAGE", help="let a failed digest be tried again")
    args = parser.parse_args(argv)

    with open(args.config, encoding="utf-8") as f:
        cfg = json.load(f)

    def log(message):
        print(message, flush=True)

    state = load_state(cfg["state_path"])

    if args.unquarantine:
        entry = state.get(args.unquarantine)
        if not entry or not entry.get("quarantine"):
            log(f"{args.unquarantine}: nothing is quarantined")
            return 0
        log(f"{args.unquarantine}: forgetting {entry['quarantine']}")
        entry["quarantine"] = []
        save_state(cfg["state_path"], state)
        return 0

    reconcile(cfg, Docker(), state, log)
    return 0


if __name__ == "__main__":
    sys.exit(main())
