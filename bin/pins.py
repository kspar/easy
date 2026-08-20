#!/usr/bin/env python3
"""The one reader of the grading-image pins.

`doc/aae/pins/<environment>.yml` says which version of each grading library an environment should
run. This module is the only thing that parses those files, and everything that needs a pin — CI,
the test suite, the image builder, the auto-merge guard, Ansible — goes through it. The alternative
is what this repo had until 2026-08-20: the version written in one place, `grep -oE 'tiivad==[0-9.]+'`
in three others, and no way to tell whether the number a host advertised was the number it ran.

### Why the file format is what it is

One pin per line, `key: "value"`, no nesting, values always quoted. That is a deliberately poor
format for a human writing a config file and a very good one for this job:

  * **"Nothing but a value changed" has to be decidable.** `pins-automerge.yml` merges a pull
    request from someone with no write access, so it must be able to prove the diff is a version
    substitution and nothing else. With one fact per line that proof is `validate_patch` below;
    with nested YAML it would be a semantic tree comparison, and a merge bot nobody fully
    understands is worse than no merge bot.
  * **Values are quoted because YAML would otherwise lie about them.** Unquoted, `1.10` parses as
    the float `1.1`, and an image built from `silmused==1.1` is a plausible-looking disaster that
    would install a real, wrong, four-year-old release. `parse` rejects an unquoted value rather
    than accepting one and hoping.
  * **No dependency.** `aae/requirements-dev.txt` says its list is "the shortest one that works",
    for the good reason that the executor host runs untrusted code; pulling PyYAML in to read a
    production pin file would be crooked. Flat `key: "value"` is still valid YAML, so anything else
    that wants to read these files can, and `grep` works for a human at a terminal.

### Why there is one file per environment

The unit of permission is the file. "Who may change this?" is answered by the path before anything
is parsed, which is what lets a silmused bumper be denied pygrader, and a dev bumper be denied
production, without the authorisation check having to understand the contents. It also means one
pull request cannot touch two environments.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PINS_DIR = os.path.join(REPO_ROOT, "doc", "aae", "pins")
DOCKERFILE_DIR = os.path.join(REPO_ROOT, "doc", "aae", "dockerfiles")
SMOKE_DIR = os.path.join(DOCKERFILE_DIR, "smoke")
ALLOWLIST = os.path.join(REPO_ROOT, ".github", "pins-bumpers.yml")

SCHEMA = "1"

# The whole grammar. Anything else in the file is an error rather than something ignored: a line a
# reader silently skips is a pin somebody believes is in effect and is not.
LINE_RE = re.compile(r'^(?P<key>[A-Za-z0-9_.\-]+):[ \t]+"(?P<value>[^"]*)"[ \t]*$')
BARE_RE = re.compile(r"^(?P<key>[A-Za-z0-9_.\-]+):[ \t]*(?P<value>.*)$")

# A version, and only a version. This is the shape `validate_patch` will merge unattended, so it is
# deliberately narrow: digits and dots. Anything else — an epoch, a local version, `1.7.11.post1`,
# a PEP 440 pre-release — is a real thing PyPI will serve and is refused here on purpose, because
# each is a judgement about whether a grader should run it, and a judgement wants a human.
VERSION_RE = re.compile(r"^[0-9]+(\.[0-9]+){0,3}$")
GIT_SHA_RE = re.compile(r"^[0-9a-f]{40}$")
SERIAL_RE = re.compile(r"^[0-9]+$")
ENVIRONMENT_RE = re.compile(r"^[a-z][a-z0-9]*$")
LOGINS_RE = re.compile(r"^[A-Za-z0-9\- ]*$")

# Keys that are not an image pin. Every one of them changes what gets built for *every* image, so
# none is ever auto-mergeable however trustworthy the author.
META_KEYS = {"schema", "environment", "rebuild.SERIAL"}


class PinsError(Exception):
    """A pins file, allowlist or patch that cannot be trusted. Always fatal, never warned about."""


# --------------------------------------------------------------------------------------------------
# Parsing


def parse(text: str, *, source: str) -> dict[str, str]:
    """Flat `key: "value"` pairs. Raises on anything it does not fully understand."""
    out: dict[str, str] = {}
    for lineno, raw in enumerate(text.splitlines(), start=1):
        line = raw.rstrip()
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        m = LINE_RE.match(line)
        if not m:
            bare = BARE_RE.match(line.strip())
            if bare and not bare.group("value").startswith('"'):
                # The error worth being loudest about. `silmused.SILMUSED_VERSION: 1.10` is valid
                # YAML for the float 1.1, so a reader that accepted it would install a wrong
                # version that exists, and the mistake would surface as a grading bug rather than
                # as a parse error.
                raise PinsError(
                    f"{source}:{lineno}: value must be quoted — "
                    f'write {bare.group("key")}: "{bare.group("value").strip()}". '
                    "Unquoted, YAML reads a version like 1.10 as the number 1.1."
                )
            raise PinsError(f"{source}:{lineno}: not a `key: \"value\"` line: {line.strip()!r}")
        key, value = m.group("key"), m.group("value")
        if key in out:
            raise PinsError(f"{source}:{lineno}: {key} is set twice ({out[key]!r}, then {value!r})")
        out[key] = value
    return out


def check_value(key: str, value: str, *, source: str) -> None:
    """The shape a given key's value must have. Checked on read, not only on merge."""
    if key == "schema":
        if value != SCHEMA:
            raise PinsError(f"{source}: schema {value!r}, expected {SCHEMA!r}")
    elif key == "environment":
        if not ENVIRONMENT_RE.match(value):
            raise PinsError(f"{source}: environment {value!r} is not a plain lowercase name")
    elif key == "rebuild.SERIAL":
        if not SERIAL_RE.match(value):
            raise PinsError(f"{source}: rebuild.SERIAL {value!r} is not a whole number")
    elif key.endswith("_REF"):
        if not GIT_SHA_RE.match(value):
            raise PinsError(
                f"{source}: {key} {value!r} is not a full 40-character commit sha. A branch or "
                "short sha would make the build unreproducible, which is the thing this file exists "
                "to prevent."
            )
    elif not VERSION_RE.match(value):
        raise PinsError(f"{source}: {key} {value!r} is not a plain dotted version")


def pins_path(env: str) -> str:
    if not ENVIRONMENT_RE.match(env):
        raise PinsError(f"{env!r} is not a plain lowercase environment name")
    return os.path.join(PINS_DIR, f"{env}.yml")


def load(env: str) -> dict[str, str]:
    """Every pin for one environment, fully validated."""
    path = pins_path(env)
    try:
        with open(path, encoding="utf-8") as f:
            text = f.read()
    except FileNotFoundError:
        raise PinsError(f"no pins file for environment {env!r} at {path}") from None
    return load_text(text, env=env, source=os.path.relpath(path, REPO_ROOT))


def load_text(text: str, *, env: str, source: str) -> dict[str, str]:
    values = parse(text, source=source)
    for key, value in values.items():
        check_value(key, value, source=source)
    for required in ("schema", "environment"):
        if required not in values:
            raise PinsError(f"{source}: missing {required}")
    if values["environment"] != env:
        # A file copied to start a new environment and never edited is otherwise indistinguishable
        # from one that was, and it would silently publish the wrong environment's channel.
        raise PinsError(
            f"{source}: says environment {values['environment']!r} but is named {env!r}"
        )
    known = set(images())
    for key in values:
        if key in META_KEYS:
            continue
        image = key.split(".", 1)[0]
        if "." not in key or image not in known:
            raise PinsError(
                f"{source}: {key} names no grading image — expected `<image>.<BUILD_ARG>` "
                f"where image is one of {', '.join(sorted(known))}"
            )
    return values


# --------------------------------------------------------------------------------------------------
# The images, and how they depend on each other


def images() -> list[str]:
    """Every grading image, by the filename that is also its local tag."""
    return sorted(
        name
        for name in os.listdir(DOCKERFILE_DIR)
        if os.path.isfile(os.path.join(DOCKERFILE_DIR, name)) and not name.startswith(".")
    )


def base_of(image: str) -> str | None:
    """The grading image this one builds `FROM`, if any.

    Derived from the Dockerfile rather than declared in the pins file, so it cannot fall out of step
    with the thing it describes. `imgrec` builds `FROM pygrader`, and before this the role had no
    way to know that — `ansible/roles/executor/tasks/main.yml` documented the resulting staleness as
    a known gap and declined to fix it. Reading the `FROM` line closes it by construction.
    """
    with open(os.path.join(DOCKERFILE_DIR, image), encoding="utf-8") as f:
        for line in f:
            m = re.match(r"^FROM\s+(\S+)", line.strip())
            if m:
                base = m.group(1).split(":")[0]
                return base if base in images() else None
    return None


def order() -> list[str]:
    """Images in an order where every base is built before whatever builds on it."""
    done: list[str] = []
    visiting: set[str] = set()

    def visit(name: str) -> None:
        if name in done:
            return
        if name in visiting:
            raise PinsError(f"{name} is its own base, directly or indirectly")
        visiting.add(name)
        base = base_of(name)
        if base:
            visit(base)
        visiting.discard(name)
        done.append(name)

    for name in images():
        visit(name)
    return done


def args_for(env: str, image: str, values: dict[str, str] | None = None) -> dict[str, str]:
    """The `--build-arg`s for one image: every pin whose key is prefixed with its name."""
    values = load(env) if values is None else values
    prefix = f"{image}."
    return {k[len(prefix):]: v for k, v in sorted(values.items()) if k.startswith(prefix)}


def _sha256_file(path: str) -> str | None:
    try:
        with open(path, "rb") as f:
            return hashlib.sha256(f.read()).hexdigest()
    except FileNotFoundError:
        return None


def digest(env: str, image: str, values: dict[str, str] | None = None) -> str:
    """This image's identity: everything that decides what a build of it would contain.

    Used as the immutable registry tag, so two things must hold. It must change when anything about
    the build changes — hence the Dockerfile, the smoke script and the recursive base digest, not
    just the version numbers. And it must *not* change for a build that would be identical, hence
    sorted keys and no environment name: when dev and production pin the same versions they resolve
    to one digest, one build, and one artefact, which is exactly what promoting a version dev has
    already proved should mean.
    """
    values = load(env) if values is None else values
    base = base_of(image)
    payload = {
        "schema": values["schema"],
        "name": image,
        "dockerfile": _sha256_file(os.path.join(DOCKERFILE_DIR, image)),
        "smoke": _sha256_file(os.path.join(SMOKE_DIR, f"{image}.sh")),
        "args": args_for(env, image, values),
        "serial": values.get("rebuild.SERIAL", "0"),
        "base": digest(env, base, values) if base else None,
    }
    blob = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(blob).hexdigest()[:12]


# --------------------------------------------------------------------------------------------------
# Does the thing being pinned exist?


def package_for(arg: str) -> str | None:
    """The PyPI package an `ARG` names, or None if the value is not an exact version of one."""
    if not arg.endswith("_VERSION"):
        # `_SPEC` keys are ranges (`numpy~=1.23.4`), so there is no single release to look up, and
        # `_REF` keys are git commits, checked separately.
        return None
    return arg[: -len("_VERSION")].lower()


def check_exists(env: str, image: str | None = None, *, timeout: int = 30) -> list[str]:
    """Confirm every pinned thing is actually published. Returns a list of problems.

    This is the cheap gate that catches the overwhelmingly common mistake — a typo'd, unpublished or
    yanked version — in seconds, before anything is built. It fails **closed**: a network error is a
    problem, not a pass, because "we could not check" must never merge on its own.
    """
    values = load(env)
    problems: list[str] = []
    for key, value in sorted(values.items()):
        if key in META_KEYS:
            continue
        img, arg = key.split(".", 1)
        if image and img != image:
            continue
        if arg.endswith("_REF"):
            problems.extend(_check_git_ref(key, value, timeout=timeout))
            continue
        pkg = package_for(arg)
        if pkg:
            problems.extend(_check_pypi(key, pkg, value, timeout=timeout))
    return problems


def _check_pypi(key: str, pkg: str, version: str, *, timeout: int) -> list[str]:
    url = f"https://pypi.org/pypi/{pkg}/{version}/json"
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            body = json.load(r)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return [f"{key}: {pkg} {version} is not on PyPI. {_nearby(pkg, timeout=timeout)}"]
        return [f"{key}: PyPI returned HTTP {e.code} for {pkg} {version} — cannot confirm it exists"]
    except Exception as e:  # noqa: BLE001 — any failure to check is a failure, see the docstring
        return [f"{key}: could not reach PyPI to confirm {pkg} {version} ({e})"]
    # A yanked release still answers 200, and installing one is exactly the situation somebody
    # yanked it to prevent.
    if any(f.get("yanked") for f in body.get("urls", [])) and body.get("urls"):
        if all(f.get("yanked") for f in body["urls"]):
            reason = next((f.get("yanked_reason") for f in body["urls"] if f.get("yanked")), None)
            return [f"{key}: {pkg} {version} has been yanked from PyPI" + (f" ({reason})" if reason else "")]
    return []


def _nearby(pkg: str, *, timeout: int) -> str:
    """The newest few real versions, so the failure message is actionable rather than just 'no'."""
    try:
        with urllib.request.urlopen(f"https://pypi.org/pypi/{pkg}/json", timeout=timeout) as r:
            releases = json.load(r).get("releases", {})
    except Exception:  # noqa: BLE001
        return ""
    known = [v for v, files in releases.items() if files and VERSION_RE.match(v)]
    known.sort(key=lambda v: [int(p) for p in v.split(".")])
    return "Recent published versions: " + ", ".join(known[-5:]) if known else ""


def _check_git_ref(key: str, sha: str, *, timeout: int) -> list[str]:
    repo = GIT_REPOS.get(key)
    if not repo:
        return [f"{key}: no repository known for this ref — add it to GIT_REPOS in bin/pins.py"]
    try:
        out = subprocess.run(
            ["git", "ls-remote", repo, sha],
            capture_output=True, text=True, timeout=timeout, check=False,
        )
    except subprocess.TimeoutExpired:
        return [f"{key}: timed out asking {repo} about {sha}"]
    if out.returncode != 0:
        return [f"{key}: could not reach {repo} ({out.stderr.strip()})"]
    if out.stdout.strip():
        return []
    # `git ls-remote <sha>` only matches advertised refs, so a commit that is real but not at the
    # tip of anything prints nothing. That is not proof of absence, and treating it as an error
    # would refuse a perfectly good pin — so say what is known and let a human decide.
    return [
        f"{key}: {sha} is not the tip of any branch or tag in {repo}. It may still be a real "
        "commit; confirm by hand rather than auto-merging."
    ]


# The repository each `_REF` pin points into. Here rather than in the pins file because it is not a
# thing anybody bumps, and a URL in an auto-mergeable file would be a way to redirect a build.
GIT_REPOS = {
    "pygrader.PYTHON_GRADER_REF": "https://github.com/kspar/python-grader.git",
}


# --------------------------------------------------------------------------------------------------
# Is this pull request one we may merge unattended?


def load_allowlist(path: str = ALLOWLIST) -> dict[str, list[str]]:
    """`<environment>.<image>: "login login"`, parsed by the same grammar as the pins themselves."""
    with open(path, encoding="utf-8") as f:
        raw = parse(f.read(), source=os.path.relpath(path, REPO_ROOT))
    out: dict[str, list[str]] = {}
    for key, value in raw.items():
        if not LOGINS_RE.match(value):
            raise PinsError(f"{path}: {key} is not a space-separated list of GitHub logins")
        out[key] = value.split()
    return out


def parse_patch(patch: str) -> list[tuple[str, str, str]]:
    """The (key, old, new) triples a unified diff of a pins file changes.

    Raises unless the patch is *only* value substitutions: the same keys, in the same order, on both
    sides. A key added, removed or renamed is a structural change to what an environment runs, and
    is not something to merge without a person looking.
    """
    removed = [ln[1:] for ln in patch.splitlines() if ln.startswith("-") and not ln.startswith("---")]
    added = [ln[1:] for ln in patch.splitlines() if ln.startswith("+") and not ln.startswith("+++")]
    if not removed:
        raise PinsError("the patch changes no existing line — nothing to bump")
    if len(removed) != len(added):
        raise PinsError(
            f"the patch removes {len(removed)} line(s) and adds {len(added)} — a version bump "
            "replaces each line it touches, so these must match"
        )
    changes: list[tuple[str, str, str]] = []
    for old_line, new_line in zip(removed, added):
        old = LINE_RE.match(old_line.strip())
        new = LINE_RE.match(new_line.strip())
        if not old or not new:
            raise PinsError(
                "every changed line must be a quoted `key: \"value\"` pin; got "
                f"{old_line.strip()!r} -> {new_line.strip()!r}"
            )
        if old.group("key") != new.group("key"):
            raise PinsError(
                f"this renames {old.group('key')} to {new.group('key')} rather than changing a "
                "value"
            )
        changes.append((old.group("key"), old.group("value"), new.group("value")))
    return changes


def validate_change(
    *,
    filenames: list[str],
    patch: str,
    author: str,
    base_ref: str = "master",
    allowlist: dict[str, list[str]] | None = None,
) -> list[tuple[str, str, str]]:
    """Everything that must be true before a pins pull request may merge itself.

    Order matters only for the quality of the error message; every condition is checked.
    """
    if base_ref != "master":
        raise PinsError(f"pull request targets {base_ref!r}, not master")
    if len(filenames) != 1:
        raise PinsError(
            "changes " + str(len(filenames)) + " files ("
            + ", ".join(sorted(filenames))
            + "); a pins bump changes exactly one. This is also what stops a pull request from "
            "editing a workflow and a pin together."
        )
    name = filenames[0]
    expected_dir = "doc/aae/pins/"
    if not name.startswith(expected_dir) or not name.endswith(".yml"):
        raise PinsError(f"{name} is not a file under {expected_dir}")
    env = os.path.basename(name)[: -len(".yml")]
    if not ENVIRONMENT_RE.match(env):
        raise PinsError(f"{name} does not name an environment")

    changes = parse_patch(patch)
    allow = load_allowlist() if allowlist is None else allowlist

    for key, old, new in changes:
        if key in META_KEYS:
            raise PinsError(
                f"{key} changes what every image is built from, so it is never auto-merged "
                "regardless of who opened the pull request"
            )
        image = key.split(".", 1)[0]
        arg = key.split(".", 1)[1]
        if arg.endswith("_REF"):
            raise PinsError(
                f"{key} points at an unreviewed upstream tree rather than a released package, so "
                "moving it needs a person"
            )
        for value, which in ((old, "current"), (new, "new")):
            if not VERSION_RE.match(value):
                raise PinsError(
                    f"{key}: the {which} value {value!r} is not a plain dotted version. "
                    "Pre-releases and local versions are deliberately not auto-merged."
                )
        permitted = allow.get(f"{env}.{image}", [])
        if not permitted:
            raise PinsError(
                f"nobody is currently allowed to bump {image} on {env} without review "
                f"(.github/pins-bumpers.yml has no logins for {env}.{image})"
            )
        if author not in permitted:
            raise PinsError(f"{author} is not allowed to bump {image} on {env}")
    return changes


# --------------------------------------------------------------------------------------------------
# CLI


def _cmd_get(a) -> int:
    print(load(a.env)[a.key])
    return 0


def _cmd_args(a) -> int:
    for k, v in args_for(a.env, a.image).items():
        print(f"{k}={v}")
    return 0


def _cmd_order(a) -> int:
    print(" ".join(order()))
    return 0


def _cmd_digest(a) -> int:
    print(digest(a.env, a.image))
    return 0


def _cmd_check_exists(a) -> int:
    problems = check_exists(a.env, a.image)
    for p in problems:
        print(p, file=sys.stderr)
    if problems:
        return 1
    print(f"every pin in {a.env} is published")
    return 0


def _cmd_validate_patch(a) -> int:
    """Reads the GitHub pull-request files API verbatim, because that is what the merge bot has.

    Untrusted input arrives as a file and is parsed here, never interpolated into a shell command
    or a workflow expression.
    """
    with (sys.stdin if a.files == "-" else open(a.files, encoding="utf-8")) as f:
        payload = json.load(f)
    filenames = [entry["filename"] for entry in payload]
    patches = [entry.get("patch", "") for entry in payload]
    try:
        changes = validate_change(
            filenames=filenames,
            patch="\n".join(patches),
            author=a.author,
            base_ref=a.base_ref,
        )
    except PinsError as e:
        print(f"not auto-mergeable: {e}", file=sys.stderr)
        return 1
    for key, old, new in changes:
        print(f"{key}: {old} -> {new}")
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = p.add_subparsers(dest="cmd", required=True)

    # dev is the default environment because every caller that does not say otherwise is CI testing
    # what dev is about to run. Production is reached by promoting an artefact dev has proved, not
    # by being the thing tests happen to check.
    def env_arg(sp):
        sp.add_argument("--env", default="dev", help="environment (default: dev)")

    g = sub.add_parser("get", help="one pin's value")
    env_arg(g)
    g.add_argument("key")
    g.set_defaults(fn=_cmd_get)

    ar = sub.add_parser("args", help="KEY=VALUE build args for one image")
    env_arg(ar)
    ar.add_argument("image")
    ar.set_defaults(fn=_cmd_args)

    o = sub.add_parser("order", help="images, bases first")
    o.set_defaults(fn=_cmd_order)

    d = sub.add_parser("digest", help="an image's inputs digest")
    env_arg(d)
    d.add_argument("image")
    d.set_defaults(fn=_cmd_digest)

    c = sub.add_parser("check-exists", help="confirm every pinned version is published")
    env_arg(c)
    c.add_argument("image", nargs="?")
    c.set_defaults(fn=_cmd_check_exists)

    v = sub.add_parser("validate-patch", help="may this pull request merge itself?")
    v.add_argument("files", help="GitHub pulls/{n}/files JSON, or - for stdin")
    v.add_argument("--author", required=True)
    v.add_argument("--base-ref", default="master")
    v.set_defaults(fn=_cmd_validate_patch)

    a = p.parse_args(argv)
    try:
        return a.fn(a)
    except PinsError as e:
        print(f"pins: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
