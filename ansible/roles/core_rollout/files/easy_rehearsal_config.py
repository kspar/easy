#!/usr/bin/env python3
"""Turn production's application.yaml into one a rehearsal core can be started with, harmlessly.

A rehearsal boots the NEW release against a fresh copy of the production database, so that a
migration which fails on real data — or a config key the release needs and the host lacks — fails
here rather than on production. The copy is disposable; everything ELSE core can reach is not. Core
sends mail, writes grades into Moodle, deletes idle accounts from Keycloak, deletes files from the
storage backend and grades queued submissions through the executor, and every one of those would
happen against the real system if the rehearsal ran with production's config. So the transform
below points each at nowhere, and `problems()` is the independent check that it did.

Why a transform and not a second template: the point is to boot the release with *exactly* the
keys production has, differing only in the ones that must differ. A hand-maintained rehearsal
config would drift from the real one and stop proving anything.

Secrets: production's application.yaml imports secrets.yaml. The rehearsal config drops that import
and carries dummies under the same key names, plus the real password for its own scratch database
role — so the file the rehearsal account can read holds no production credential. The KEY NAMES
are read from secrets.yaml (by root, in the helper) so a release that needs a new secret still
finds the key and fails on its value rather than on its absence — either way the rehearsal fails,
which is right, and the message names the key.

The output is JSON. Spring reads JSON as YAML (JSON is YAML), and JSON is what both the writer and
the guard can handle with the standard library alone.
"""

from __future__ import annotations

import copy
import json
import sys

REHEARSAL_DB = "easyems_rehearsal"
REHEARSAL_DB_USER = "easyems_rehearsal"
NEVER_CRON = "0 0 0 1 1 ? 2099"
STALLED_MS = "9000000000000"
DISCARD = "http://127.0.0.1:9/"
NO_COURSE = "easy-rehearsal-no-such-course"
DUMMY = "rehearsal-dummy"


def _get(d: dict, path: str, default=None):
    cur = d
    for k in path.split("."):
        if not isinstance(cur, dict) or k not in cur:
            return default
        cur = cur[k]
    return cur


def _set(d: dict, path: str, value) -> None:
    keys = path.split(".")
    cur = d
    for k in keys[:-1]:
        cur = cur.setdefault(k, {})
        if not isinstance(cur, dict):
            raise ValueError(f"{path}: {k} is not a mapping")
    cur[keys[-1]] = value


def _walk(d, prefix=""):
    if isinstance(d, dict):
        for k, v in d.items():
            yield from _walk(v, f"{prefix}{k}.")
    else:
        yield prefix[:-1], d


def transform(prod: dict, port: int, db_password: str, secret_key_paths: list[str],
              storage_dir: str, db_host: str = "127.0.0.1", db_port: int = 5432) -> dict:
    """Production's config → a rehearsal config. Pure; the helper does the file I/O."""
    cfg = copy.deepcopy(prod)

    # Loopback, on a port production does not use.
    _set(cfg, "server.address", "127.0.0.1")
    _set(cfg, "server.port", int(port))

    # Its own database, its own role, no import of the real secrets.
    _get(cfg, "spring.config", {}).pop("import", None)
    _set(cfg, "spring.datasource.jdbc-url", f"jdbc:postgresql://{db_host}:{db_port}/{REHEARSAL_DB}")
    _set(cfg, "spring.datasource.username", REHEARSAL_DB_USER)
    _set(cfg, "spring.datasource.password", db_password)
    _set(cfg, "spring.datasource.hikari.maximum-pool-size", 3)
    _set(cfg, "spring.datasource.hikari.minimum-idle", 1)

    # Every other secret exists by name and is worthless by value.
    for path in secret_key_paths:
        if path == "spring.datasource.password":
            continue
        _set(cfg, path, DUMMY)

    # Mail: relay is the discard port and both senders are off.
    _set(cfg, "spring.mail.host", "127.0.0.1")
    _set(cfg, "spring.mail.port", 9)
    _set(cfg, "spring.mail.properties.mail.smtp.auth", False)
    _set(cfg, "easy.core.mail.sys.enabled", False)
    _set(cfg, "easy.core.mail.user.enabled", False)

    # Moodle: both endpoints at nowhere, and an allowlist naming a course that does not exist —
    # EMPTY means unrestricted, which is the one value this must never be.
    _set(cfg, "easy.core.moodle-sync.users.url", DISCARD)
    _set(cfg, "easy.core.moodle-sync.grades.url", DISCARD)
    _set(cfg, "easy.core.moodle-sync.course-allowlist", NO_COURSE)

    # Storage: a scratch directory, never the bucket and never production's files, and the sweep
    # that deletes may only report.
    _set(cfg, "easy.core.storage.backend", "local")
    _set(cfg, "easy.core.storage.local.dir", storage_dir)
    _get(cfg, "easy.core.storage", {}).pop("s3", None)
    _set(cfg, "easy.core.stored-file-sweep.delete", False)

    # Nothing scheduled ever runs: every cron, wherever it is, is pinned to a date that never comes,
    # and the auto-assessment poller — a fixed delay, not a cron — is stalled.
    for path, value in list(_walk(cfg)):
        last = path.rsplit(".", 1)[-1]
        if last == "cron" or last.endswith("-cron"):
            _set(cfg, path, NEVER_CRON)
    _set(cfg, "easy.core.auto-assess.fixed-delay.ms", STALLED_MS)
    _set(cfg, "easy.core.auto-assess.fixed-delay-observer-clear.ms", STALLED_MS)
    _set(cfg, "easy.core.statistics.fixed-delay.ms", STALLED_MS)

    # Integrations that would file, post or link somewhere real.
    _set(cfg, "easy.core.youtrack.enabled", False)
    _set(cfg, "easy.core.web.base-url", "http://127.0.0.1:9/")
    _set(cfg, "easy.core.cors.allowed-origins", "")

    return cfg


def problems(cfg: dict, port: int) -> list[str]:
    """Everything wrong with a rehearsal config. Empty means it may be started."""
    out = []

    def want(path, value, why):
        got = _get(cfg, path, "<absent>")
        if got != value:
            out.append(f"{why}: {path} is {got!r}, must be {value!r}")

    want("server.address", "127.0.0.1", "loopback bind")
    want("server.port", int(port), "rehearsal port")
    url = str(_get(cfg, "spring.datasource.jdbc-url", ""))
    if not url.endswith(f"/{REHEARSAL_DB}"):
        out.append(f"database: jdbc-url {url!r} does not end in /{REHEARSAL_DB}")
    want("spring.datasource.username", REHEARSAL_DB_USER, "database role")
    if _get(cfg, "spring.config.import") is not None:
        out.append("the real secrets file is still imported")
    want("spring.mail.host", "127.0.0.1", "mail relay")
    want("spring.mail.port", 9, "mail relay port")
    want("easy.core.mail.sys.enabled", False, "system mail")
    want("easy.core.mail.user.enabled", False, "user mail")
    for p in ("easy.core.moodle-sync.users.url", "easy.core.moodle-sync.grades.url"):
        if not str(_get(cfg, p, "")).startswith(DISCARD):
            out.append(f"Moodle: {p} is {_get(cfg, p)!r}, must start with {DISCARD}")
    allow = _get(cfg, "easy.core.moodle-sync.course-allowlist", "")
    if not allow or allow != NO_COURSE:
        out.append(f"Moodle: course-allowlist is {allow!r} (empty means UNRESTRICTED), must be {NO_COURSE!r}")
    want("easy.core.storage.backend", "local", "storage backend")
    if _get(cfg, "easy.core.storage.s3") is not None:
        out.append("storage: an s3 block is still present")
    want("easy.core.stored-file-sweep.delete", False, "file sweep")
    for path, value in _walk(cfg):
        last = path.rsplit(".", 1)[-1]
        if (last == "cron" or last.endswith("-cron")) and value != NEVER_CRON:
            out.append(f"schedule: {path} is {value!r}, must be {NEVER_CRON!r}")
    want("easy.core.auto-assess.fixed-delay.ms", STALLED_MS, "auto-assessment poller")
    want("easy.core.youtrack.enabled", False, "YouTrack")
    if _get(cfg, "easy.core.auth-enabled") is not True:
        out.append("auth-enabled must stay true")
    return out


def secret_key_paths(secrets: dict) -> list[str]:
    """Dotted paths of every leaf in secrets.yaml — names only, never values."""
    return [path for path, _ in _walk(secrets)]


def main(argv=None) -> int:
    """Used by easy-rollout-db: read YAML (needs PyYAML, present on the host), write JSON."""
    import argparse
    p = argparse.ArgumentParser()
    p.add_argument("--application-yaml", required=True)
    p.add_argument("--secrets-yaml", required=True)
    p.add_argument("--port", type=int, required=True)
    p.add_argument("--db-password-file", required=True)
    p.add_argument("--storage-dir", required=True)
    p.add_argument("--out", required=True)
    args = p.parse_args(argv)
    import yaml  # type: ignore
    with open(args.application_yaml) as f:
        prod = yaml.safe_load(f) or {}
    with open(args.secrets_yaml) as f:
        secrets = yaml.safe_load(f) or {}
    with open(args.db_password_file) as f:
        password = f.read().strip()
    cfg = transform(prod, args.port, password, secret_key_paths(secrets), args.storage_dir)
    bad = problems(cfg, args.port)
    if bad:
        print("refusing to write a rehearsal config: " + "; ".join(bad), file=sys.stderr)
        return 1
    with open(args.out, "w") as f:
        json.dump(cfg, f, indent=2)
        f.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
