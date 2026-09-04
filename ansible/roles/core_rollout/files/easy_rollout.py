#!/usr/bin/env python3
"""easy-rollout — unattended, guarded updates of a Lahendus environment.

The dev host's `easy-autodeploy` installed whatever its branch pointed at, as soon as CI was green.
This does the same job for an environment where a bad release has an audience: it tracks a
branch, but between "the branch moved" and "core was restarted" it puts every check that can be
automated, and after the restart it proves the whole system works before it calls the rollout done.
If anything fails after core was touched, it puts the previous release back on its own, and if
anything fails at all it tells a human — by mail, by webhook, by filing an issue — and stops.

The flow, on every timer tick:

    tick
      ├─ paused?                       → say so, do nothing
      ├─ record what dev is running    (the soak gate needs a history)
      ├─ branch head == current-sha?   → steady state, silent
      ├─ head failed before?           → never retried automatically; remind daily
      ├─ green CI run for head?        → wait
      ├─ gates: window, freeze, CI age, soak on dev, ancestry of master, gap since last rollout,
      │         gap since a retryable failure
      │      unmet → remember why (`easy-rollout status`), alarm if it has been stuck for days
      └─ ROLLOUT
           1. preflight    disk, database, core healthy NOW, previous release intact
           2. baseline     the smoke suite against the CURRENT release — production must pass its
                           own tests before we change it, or a failure afterwards proves nothing
           3. fetch        the CI artifacts (same layout as deploy.sh and easy-autodeploy)
           4. dump         the database, through the nightly backup unit
           5. rehearse     restore that dump into a scratch database and boot the NEW jar against
                           it — under an account with no privileges, with every outbound
                           integration pointed at nowhere. Migrations that fail against real
                           data, and config keys the release needs and the host lacks, fail HERE.
           6. activate     flip the symlinks, restart core
           7. health       401 from /v2/ through the public vhost, unit active
           8. smoke        the full end-to-end suite: web, API, IdP, student and teacher flows,
                           an autograded submission through the executor, the Thonny contract
           9. mark         current-sha, DEPLOYED — the last step a failure of which rolls back
          10. prune        old releases; never a reason to roll back
         on failure at 6–9:
           rollback        previous symlinks, restart, health, smoke
                           → if the old jar does not START (unit not active) and the release
                             migrated the schema, restore the dump taken in step 4 (policy)
                           → pause the rollout, mark the sha failed, escalate
         on failure at 1–5, production untouched:
                           → a failure that is the commit's (rehearsal, config) parks the sha
                           → a failure that is not (network, disk, a busy backup) is retried
                             after a gap, and reported if it keeps happening

Pull, not push, for the same reason as easy-autodeploy: nothing in GitHub holds a credential for
this host. The only secrets on the host are the ones it needs anyway — a read-only Actions token,
the smoke accounts, and the notification credentials — and none of them can deploy anything.

Stdlib only, on purpose. It runs on a server with no virtualenv, and every dependency would be one
more thing a rollout could fail on before it ever reached the check that mattered.

The on-host release layout is a contract shared with deploy/deploy.sh and easy-autodeploy:

    <root>/releases/<sha>/{core.jar,web/}
    <root>/core/current.jar   -> releases/<sha>/core.jar
    <root>/web/current        -> releases/<sha>/web
    <root>/current-sha

State of this program lives under <root>/rollout/ (`state.json`, `pause`, `deploy-now`, `lock`,
one record per rollout under `rollouts/`), all of it plain text a person can read and edit.
"""

from __future__ import annotations

import argparse
import fcntl
import filecmp
import json
import os
import re
import shutil
import signal
import smtplib
import socket
import subprocess
import sys
import tarfile
import tempfile
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from datetime import datetime, timedelta, timezone
from email.message import EmailMessage
from pathlib import Path
from zoneinfo import ZoneInfo

API = "https://api.github.com"
USER_AGENT = "easy-rollout"

# Severities, in order. `channels` in the notify config maps each to a list of channel names.
INFO, WARN, CRITICAL = "info", "warn", "critical"

# Every wait goes through this so the tests can make a minute take no time.
sleep = time.sleep

# Records, logs and state are for the deploy group, not for every account on the host: they carry
# core's log tail and the smoke report.
FILE_MODE = 0o640
DIR_MODE = 0o750


# ---------------------------------------------------------------------------------------------
# small utilities
# ---------------------------------------------------------------------------------------------

def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).replace(microsecond=0).isoformat()


def parse_iso(s: str) -> datetime:
    dt = datetime.fromisoformat(s.replace("Z", "+00:00"))
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


def hours_between(a: datetime, b: datetime) -> float:
    return (b - a).total_seconds() / 3600.0


def short(sha: str | None) -> str:
    return (sha or "")[:8] or "nothing"


def write_atomic(path: Path, text: str, mode: int = FILE_MODE) -> None:
    """A half-written state file is worse than a stale one, so write beside and rename over."""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name("." + path.name + ".tmp")
    tmp.write_text(text)
    os.chmod(tmp, mode)
    os.replace(tmp, path)


class RolloutError(Exception):
    """A step failed in a way that was detected and explained. Anything else is a bug.

    `retryable` says whether the failure is a property of the commit (a migration that dies, a
    missing config key — it will fail again identically) or of the moment (GitHub down, the disk
    full, the backup unit busy — worth another go later). Only matters before production is
    touched; afterwards every failure ends in a rollback and a pause.
    """

    def __init__(self, msg: str, retryable: bool = False):
        super().__init__(msg)
        self.retryable = retryable


class Interrupted(RolloutError):
    """SIGTERM arrived, or the time budget ran out: finish the way a failure would.

    Retryable: a signal or a clock is not a property of the commit.
    """

    def __init__(self, msg: str):
        super().__init__(msg, retryable=True)


class Log:
    """Plain stdout — systemd puts it in the journal — plus a copy per rollout."""

    def __init__(self):
        self.lines: list[str] = []
        self.sink: Path | None = None

    def __call__(self, msg: str) -> None:
        line = f"{iso(now_utc())} {msg}"
        print(msg, flush=True)
        self.lines.append(line)
        if self.sink is not None:
            try:
                fd = os.open(self.sink, os.O_WRONLY | os.O_CREAT | os.O_APPEND, FILE_MODE)
                with os.fdopen(fd, "a") as f:
                    f.write(line + "\n")
            except OSError as e:
                # The journal still has it. A full disk must not turn a rollback into a crash.
                print(f"  (could not write {self.sink}: {e})", flush=True)

    def tail(self, n: int = 60) -> str:
        return "\n".join(self.lines[-n:])


log = Log()


# ---------------------------------------------------------------------------------------------
# configuration
# ---------------------------------------------------------------------------------------------

DEFAULTS: dict = {
    "repo": "kspar/easy",
    "branch": "prod-releases",
    "workflow": "CI",
    "root": "/srv/easy",
    "service": "easy-core",
    "keep_releases": 5,
    "token_file": "/etc/easy/github-token",
    "config_json": "/srv/easy/conf/config.json",
    "health_url": "",
    "health_timeout_s": 300,
    # A release that migrates the schema may spend a long time in Liquibase before the filter
    # chain is up. Killing it half way would be worse than waiting. Used whenever a migration is
    # not known NOT to have happened.
    "health_timeout_migrating_s": 1800,
    "dump_service": "easy-db-backup.service",
    "db_helper": "/usr/local/bin/easy-rollout-db",
    "window": {"days": ["Tue", "Thu"], "start": "04:00", "end": "05:30", "tz": "Europe/Tallinn"},
    "freeze": [],
    "gates": {
        "min_ci_age_hours": 6,
        "soak_hours": 12,
        "dev_version_url": "",
        "require_seen_on_dev": True,
        "require_on_master": True,
        "min_gap_hours": 20,
        "min_retry_gap_hours": 6,
        "deploy_now_ttl_hours": 24,
        "stuck_after_hours": 96,
        "stuck_repeat_hours": 24,
        "min_free_gb": 4,
    },
    "rehearsal": {
        "enabled": True,
        "port": 8091,
        "timeout_s": 1200,
    },
    "smoke": {"required": True, "attempts": 3, "baseline_attempts": 2, "retry_delay_s": 60},
    "rollback": {"restore_db": "auto"},   # auto | never | always
    "notify": {
        "channels": {INFO: ["mail"], WARN: ["mail"], CRITICAL: ["mail", "webhook"]},
        "mail": {},
        "webhook": {},
        "youtrack": {},
    },
}

RESTORE_POLICIES = ("auto", "never", "always")
CHANNELS = ("mail", "webhook", "youtrack")


def deep_merge(base: dict, over: dict) -> dict:
    out = dict(base)
    for k, v in over.items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = deep_merge(out[k], v)
        else:
            out[k] = v
    return out


def validate_config(cfg: dict) -> list[str]:
    """What is wrong with a config, all of it at once. Empty means usable."""
    bad = []
    if not cfg.get("health_url"):
        bad.append("health_url is not configured")
    if cfg["rollback"]["restore_db"] not in RESTORE_POLICIES:
        bad.append(f"rollback.restore_db is {cfg['rollback']['restore_db']!r}, must be one of {RESTORE_POLICIES}")
    for sev, chans in cfg["notify"]["channels"].items():
        if sev not in (INFO, WARN, CRITICAL):
            bad.append(f"notify.channels has an unknown severity {sev!r}")
        for c in chans:
            if c not in CHANNELS:
                bad.append(f"notify.channels.{sev} names an unknown channel {c!r}")
    for key in ("keep_releases", "health_timeout_s", "health_timeout_migrating_s"):
        if not isinstance(cfg.get(key), int) or cfg[key] < 0:
            bad.append(f"{key} must be a non-negative integer")
    for key in ("port", "timeout_s"):
        if not isinstance(cfg["rehearsal"].get(key), int):
            bad.append(f"rehearsal.{key} must be an integer")
    w = cfg["window"]
    if not w.get("always"):
        for key in ("start", "end"):
            if not re.fullmatch(r"\d{2}:\d{2}", str(w.get(key, ""))):
                bad.append(f"window.{key} must be HH:MM")
        try:
            ZoneInfo(w.get("tz", "UTC"))
        except Exception:  # noqa: BLE001
            bad.append(f"window.tz {w.get('tz')!r} is not a known timezone")
        for d in w.get("days") or []:
            if d not in DAY_NAMES and d != "*":
                bad.append(f"window.days contains {d!r}")
    for f in cfg["freeze"]:
        for key in ("from", "to"):
            try:
                datetime.fromisoformat(str(f.get(key)))
            except ValueError:
                bad.append(f"freeze entry {f!r}: {key} is not a date")
    return bad


def load_config(path: Path) -> dict:
    cfg = deep_merge(DEFAULTS, json.loads(path.read_text()))
    cfg["root"] = Path(cfg["root"])
    cfg["state_dir"] = Path(cfg.get("state_dir") or (cfg["root"] / "rollout"))
    bad = validate_config(cfg)
    if bad:
        raise RolloutError(f"{path}: " + "; ".join(bad))
    return cfg


def read_secret_file(path: str | Path | None) -> str | None:
    """A credential file's content, or None when it is absent, unreadable or still a placeholder."""
    if not path:
        return None
    p = Path(path)
    try:
        value = p.read_text().strip()
    except OSError:
        return None
    if not value or value.startswith("CHANGEME"):
        return None
    return value


# ---------------------------------------------------------------------------------------------
# state
# ---------------------------------------------------------------------------------------------

class State:
    """Everything this program remembers between ticks, in one JSON file.

    `candidates` — shas the branch has pointed at and why they have not deployed yet.
    `dev_seen`   — which shas have been observed running on the environment named by
                   gates.dev_version_url, for how long CONTIGUOUSLY, and which one is there now:
                   the soak gate's evidence.
    `failed`     — shas that failed a rollout; never retried without a human clearing them.
    `history`    — the last rollout summaries, newest last.
    """

    EMPTY = {"candidates": {}, "dev_seen": {}, "dev_current": None, "failed": {}, "history": [],
             "notices": {}, "last_success_at": None}

    def __init__(self, path: Path):
        self.path = path
        self.data = json.loads(json.dumps(self.EMPTY))
        self.corrupt: str | None = None
        if path.exists():
            try:
                loaded = json.loads(path.read_text())
                if not isinstance(loaded, dict):
                    raise ValueError("not an object")
                self.data.update(loaded)
            except ValueError as e:
                # Keep the evidence, start fresh, and say so — a state file that cannot be read
                # must not stop every tick until somebody finds the traceback.
                aside = path.with_name(f"{path.name}.corrupt-{now_utc().strftime('%Y%m%dT%H%M%SZ')}")
                try:
                    os.replace(path, aside)
                except OSError:
                    pass
                self.corrupt = f"{path} was unreadable ({e}); moved to {aside} and started empty"

    def save(self) -> None:
        write_atomic(self.path, json.dumps(self.data, indent=2, sort_keys=True) + "\n")

    def reload_failed_from_disk(self) -> None:
        """A `forget` typed while a rollout ran must not be undone by that rollout's save."""
        if not self.path.exists():
            return
        try:
            disk = json.loads(self.path.read_text()).get("failed", {})
        except (ValueError, OSError):
            return
        for sha in [s for s in self.data["failed"] if s not in disk and s != self.data.get("_this_run")]:
            del self.data["failed"][sha]

    # -- convenience --------------------------------------------------------------------------
    @property
    def candidates(self) -> dict:
        return self.data["candidates"]

    @property
    def dev_seen(self) -> dict:
        return self.data["dev_seen"]

    @property
    def failed(self) -> dict:
        return self.data["failed"]

    def notice_due(self, key: str, repeat_hours: float, now: datetime) -> bool:
        last = self.data["notices"].get(key)
        return last is None or hours_between(parse_iso(last), now) >= repeat_hours

    def noticed(self, key: str, now: datetime) -> None:
        self.data["notices"][key] = iso(now)

    def clear_notice(self, key: str) -> None:
        self.data["notices"].pop(key, None)

    def record_history(self, summary: dict) -> None:
        self.data["history"] = (self.data["history"] + [summary])[-30:]


# ---------------------------------------------------------------------------------------------
# GitHub
# ---------------------------------------------------------------------------------------------

class _StripAuthOnRedirect(urllib.request.HTTPRedirectHandler):
    """Artifact downloads redirect to pre-signed blob URLs that reject a bearer token."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        new = super().redirect_request(req, fp, code, msg, headers, newurl)
        if new is not None:
            new.remove_header("Authorization")
        return new


class GitHubUnavailable(Exception):
    """GitHub could not be asked: network, outage, or a token it no longer accepts."""


class GitHub:
    def __init__(self, repo: str, workflow: str, token: str):
        self.repo, self.workflow, self.token = repo, workflow, token
        self.opener = urllib.request.build_opener(_StripAuthOnRedirect)

    def api(self, path: str, raw: bool = False):
        req = urllib.request.Request(
            path if path.startswith("http") else f"{API}{path}",
            headers={"Authorization": f"Bearer {self.token}",
                     "Accept": "application/vnd.github+json",
                     "X-GitHub-Api-Version": "2022-11-28",
                     "User-Agent": USER_AGENT})
        try:
            with self.opener.open(req, timeout=300) as resp:
                return resp.read() if raw else json.load(resp)
        except urllib.error.HTTPError as e:
            if e.code in (401, 403):
                raise GitHubUnavailable(f"GitHub answered {e.code} — the token in the token file is expired or wrong") from e
            if e.code == 404:
                raise
            raise GitHubUnavailable(f"GitHub answered {e.code} for {path}") from e
        except (urllib.error.URLError, socket.timeout, ConnectionError, OSError) as e:
            raise GitHubUnavailable(f"cannot reach GitHub: {e}") from e

    def branch_head(self, branch: str) -> str:
        return self.api(f"/repos/{self.repo}/git/ref/heads/{branch}")["object"]["sha"]

    def green_run_for(self, sha: str) -> dict | None:
        """Newest green run of the release workflow for this commit, from any branch."""
        runs = self.api(f"/repos/{self.repo}/actions/runs?head_sha={sha}&status=success&per_page=20")
        runs = [r for r in runs["workflow_runs"]
                if r["status"] == "completed" and r["conclusion"] == "success"
                and r["name"] == self.workflow]
        if not runs:
            return None
        return sorted(runs, key=lambda r: r["run_started_at"])[-1]

    def artifact_urls(self, run_id: int, sha: str) -> dict[str, str]:
        arts = self.api(f"/repos/{self.repo}/actions/runs/{run_id}/artifacts")["artifacts"]
        by_name = {a["name"]: a for a in arts}
        out = {}
        for want in (f"core-{sha}", f"web-{sha}"):
            art = by_name.get(want)
            if art is None:
                raise RolloutError(f"artifact {want} missing from run {run_id} "
                                   f"(have: {sorted(by_name)}) — artifacts expire after 90 days, and "
                                   f"only branches listed in main.yml publish them")
            if art.get("expired"):
                raise RolloutError(f"artifact {want} has expired")
            out[want] = art["archive_download_url"]
        return out

    def is_ancestor_of(self, sha: str, branch: str) -> bool:
        """True when `branch` contains `sha`.

        GitHub's compare `base...head` reports the status of HEAD relative to BASE. With base=sha
        and head=branch: `identical` means the branch is at sha, `ahead` means the branch has
        moved on past sha — both mean the branch contains it. `behind` means sha has commits the
        branch lacks (a hotfix on top of master that master never got) and `diverged` means
        neither contains the other. Those two are what this exists to catch.
        """
        cmp = self.api(f"/repos/{self.repo}/compare/{sha}...{branch}")
        return cmp["status"] in ("ahead", "identical")

    def commits_between(self, base: str, head: str, limit: int = 40) -> list[str]:
        """One line per commit from base (exclusive) to head, oldest first."""
        try:
            cmp = self.api(f"/repos/{self.repo}/compare/{base}...{head}")
        except Exception as e:  # noqa: BLE001 — a missing changelog must not fail a rollout
            return [f"(could not list commits: {e})"]
        lines = [f"{c['sha'][:8]} {c['commit']['message'].splitlines()[0]}" for c in cmp.get("commits", [])]
        if len(lines) > limit:
            lines = lines[:limit] + [f"... and {len(lines) - limit} more"]
        return lines


# ---------------------------------------------------------------------------------------------
# the host: everything that touches the machine, gathered so tests can replace it
# ---------------------------------------------------------------------------------------------

class Host:
    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.root: Path = cfg["root"]

    # -- shell ----------------------------------------------------------------------------------
    def run(self, argv: list[str], timeout: int = 600, check: bool = True) -> subprocess.CompletedProcess:
        return subprocess.run(argv, capture_output=True, text=True, timeout=timeout, check=check)

    def sudo(self, argv: list[str], timeout: int = 600) -> subprocess.CompletedProcess:
        return self.run(["sudo", "-n", *argv], timeout=timeout)

    def db(self, *args: str, timeout: int = 3600) -> str:
        """The root-owned database helper; its argument list is the whole privilege."""
        cp = self.run(["sudo", "-n", self.cfg["db_helper"], *args], timeout=timeout, check=False)
        if cp.returncode != 0:
            raise RolloutError(f"easy-rollout-db {' '.join(args)} failed ({cp.returncode}): "
                               f"{(cp.stderr or cp.stdout).strip()[-800:]}")
        return cp.stdout.strip()

    # -- release tree ---------------------------------------------------------------------------
    def current_sha(self) -> str:
        f = self.root / "current-sha"
        return f.read_text().strip() if f.exists() else ""

    def release_dir(self, sha: str) -> Path:
        return self.root / "releases" / sha

    def release_complete(self, sha: str) -> bool:
        rel = self.release_dir(sha)
        return (rel / "core.jar").is_file() and (rel / "web" / "index.html").is_file() \
            and not (rel / ".partial").exists()

    def free_gb(self, path: Path) -> float:
        p = path
        while not p.exists():
            p = p.parent
        return shutil.disk_usage(p).free / 1e9

    def materialise(self, gh: GitHub, run: dict, sha: str) -> Path:
        """Download and unpack into releases/<sha>/, all or nothing.

        Built beside and renamed into place: a download interrupted by a full disk must not leave
        a directory that LOOKS like a release, because the next attempt would skip the download and
        install the half of it that exists.
        """
        rel = self.release_dir(sha)
        if self.release_complete(sha):
            log("  release already on disk, not re-downloading")
            return rel
        urls = gh.artifact_urls(run["id"], sha)
        partial = rel.with_name(f".{sha}.partial")
        if partial.exists():
            shutil.rmtree(partial)
        if rel.exists():
            shutil.rmtree(rel)
        with tempfile.TemporaryDirectory() as td:
            tmp = Path(td)
            for name, url in urls.items():
                log(f"  downloading {name}")
                zp = tmp / f"{name}.zip"
                try:
                    zp.write_bytes(gh.api(url, raw=True))
                except GitHubUnavailable as e:
                    raise RolloutError(str(e), retryable=True) from e
                with zipfile.ZipFile(zp) as z:
                    for member in z.infolist():
                        # A zip has no `filter="data"`; refuse anything that would land outside.
                        if member.filename.startswith(("/", "..")) or ".." in Path(member.filename).parts:
                            raise RolloutError(f"artifact {name} contains an unsafe path {member.filename!r}")
                    z.extractall(tmp)
                zp.unlink()
            jar = next(iter(tmp.rglob(f"core-{sha}.jar")), None)
            tgz = next(iter(tmp.rglob(f"web-{sha}.tar.gz")), None)
            if jar is None or tgz is None:
                raise RolloutError("downloaded artifacts do not contain the jar and the dist")
            partial.mkdir(parents=True)
            shutil.copy2(jar, partial / "core.jar")
            web = partial / "web"
            web.mkdir()
            with tarfile.open(tgz) as t:
                t.extractall(web, filter="data")
            # Explicit modes, whatever the umask and whatever the archive said: core (easy-core)
            # runs the jar and nginx (www-data) serves the dist, and neither is in the deploy
            # group — so the tree must be world-readable; and whoever deploys next must be able
            # to rewrite config.json in here, so it must be group-writable. The state directory's
            # 0640s are this program's own files, not these.
            for p in [partial, *partial.rglob("*")]:
                try:
                    os.chmod(p, 0o775 if p.is_dir() else 0o664)
                except OSError:
                    pass
        os.replace(partial, rel)
        return rel

    def activate(self, sha: str) -> None:
        """Point the live symlinks at a release, after writing this environment's config.json."""
        rel = self.release_dir(sha)
        web = rel / "web"
        if not (self.root / "conf" / "application.yaml").is_file():
            raise RolloutError(f"{self.root}/conf/application.yaml does not exist")
        cfg_src = Path(self.cfg["config_json"])
        if not cfg_src.is_file():
            raise RolloutError(f"{cfg_src} does not exist — it is this environment's config.json")
        c = json.loads(cfg_src.read_text())
        missing = [k for k in ("emsRoot",) if not c.get(k)]
        missing += ["keycloak." + k for k in ("url", "realm", "clientId") if not c.get("keycloak", {}).get(k)]
        if missing:
            raise RolloutError("config.json is missing: " + ", ".join(missing))
        if not (web / "index.html").is_file():
            raise RolloutError("no index.html in the unpacked dist")
        dst = web / "config.json"
        # A release somebody else installed may not be writable here. That is fine as long as
        # its config.json already says what ours says — which for a rollback it does.
        if not (dst.is_file() and filecmp.cmp(cfg_src, dst, shallow=False)):
            try:
                tmp = web / ".config.json.new"
                shutil.copyfile(cfg_src, tmp)
                os.chmod(tmp, 0o664)
                os.replace(tmp, dst)
            except PermissionError as e:
                raise RolloutError(f"cannot write {dst}: {e} — the release was installed by another account "
                                   f"and its config.json differs from {cfg_src}") from e
        for link, target in ((self.root / "web" / "current", web),
                             (self.root / "core" / "current.jar", rel / "core.jar")):
            link.parent.mkdir(parents=True, exist_ok=True)
            tmp_link = link.with_name("." + link.name + ".new")
            if tmp_link.exists() or tmp_link.is_symlink():
                tmp_link.unlink()
            tmp_link.symlink_to(target)
            os.replace(tmp_link, link)

    def mark_current(self, sha: str) -> None:
        try:
            (self.release_dir(sha) / "DEPLOYED").write_text(iso(now_utc()) + "\n")
        except OSError:
            pass  # somebody else's release; the sha file below is the record that matters
        write_atomic(self.root / "current-sha", sha + "\n", mode=0o664)

    def prune(self, keep_shas: set[str]) -> None:
        rels = sorted((p for p in (self.root / "releases").glob("*/") if not p.name.startswith(".")),
                      key=lambda p: p.stat().st_mtime, reverse=True)
        for i, d in enumerate(rels):
            if i < int(self.cfg["keep_releases"]) or d.name in keep_shas:
                continue
            log(f"  pruning {d.name}")
            shutil.rmtree(d, ignore_errors=True)
        for d in (self.root / "releases").glob(".*.partial"):
            shutil.rmtree(d, ignore_errors=True)

    # -- services -------------------------------------------------------------------------------
    def restart_core(self) -> None:
        self.sudo(["/usr/bin/systemctl", "restart", self.cfg["service"]])

    def core_active(self) -> bool:
        return self.run(["systemctl", "is-active", "--quiet", self.cfg["service"]], check=False).returncode == 0

    def core_unit(self) -> tuple[str, int]:
        """(ActiveState, NRestarts) of core's unit — the evidence that a slow start is a crash loop."""
        cp = self.run(["systemctl", "show", "-p", "ActiveState,NRestarts", "--value", self.cfg["service"]], check=False)
        lines = cp.stdout.split()
        state = lines[0] if lines else "unknown"
        try:
            restarts = int(lines[1]) if len(lines) > 1 else 0
        except ValueError:
            restarts = 0
        return state, restarts

    def core_log_tail(self) -> str:
        cp = self.run(["sudo", "-n", "/usr/bin/journalctl", "-u", self.cfg["service"], "-n", "40", "--no-pager"],
                      check=False)
        return cp.stdout[-4000:]

    def dump_database(self) -> str:
        """Take a restore point through the nightly backup unit; returns the dump's path."""
        before = self.db("newest-dump")
        try:
            self.sudo(["/usr/bin/systemctl", "start", self.cfg["dump_service"]], timeout=3600)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as e:
            raise RolloutError(f"{self.cfg['dump_service']} failed: {e}", retryable=True) from e
        after = self.db("newest-dump")
        if not after or after == before:
            raise RolloutError(f"{self.cfg['dump_service']} finished but no new dump appeared "
                               f"(newest is still {before or 'none'})", retryable=True)
        return after

    # -- HTTP -----------------------------------------------------------------------------------
    def http_status(self, url: str, timeout: int = 10) -> int | None:
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.status
        except urllib.error.HTTPError as e:
            return e.code
        except Exception:  # noqa: BLE001
            return None

    def http_text(self, url: str, timeout: int = 10) -> str | None:
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.read().decode("utf-8", "replace")
        except Exception:  # noqa: BLE001
            return None

    def wait_healthy(self, url: str, timeout_s: int, interval: int = 4, max_restarts: int = 2) -> bool:
        """401 (or 200) through the public vhost proves nginx, core and its filter chain.

        Waits the full timeout only while the unit is honestly still starting. A JVM that dies at
        boot is restarted by systemd and dies again; waiting half an hour for a URL that a crash
        loop will never answer is half an hour of outage before the rollback begins, so the unit's
        state is the other half of the evidence: `failed`, or more than a couple of restarts, ends
        the wait now.
        """
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            if self.http_status(url) in (200, 401, 403):
                return True
            state, restarts = self.core_unit()
            if state == "failed" or restarts > max_restarts:
                log(f"  {self.cfg['service']} is {state} after {restarts} restart(s); not waiting for the URL")
                return False
            sleep(interval)
        return False

    def port_free(self, port: int) -> bool:
        with socket.socket() as s:
            return s.connect_ex(("127.0.0.1", port)) != 0


# ---------------------------------------------------------------------------------------------
# notifications
# ---------------------------------------------------------------------------------------------

class Notifier:
    """Mail through the same relay core uses, a webhook, and a YouTrack issue for the worst cases.

    Every channel is best-effort and independent: a relay that is down must not stop the webhook,
    and no notification failure may ever fail a rollout — the rollout's own record on disk is the
    thing of record. Nothing sent here contains a credential; the messages are built from the
    rollout record, which never holds one.
    """

    def __init__(self, cfg: dict, env_label: str):
        self.cfg = cfg["notify"]
        self.env = env_label
        self.sent: list[tuple[str, str, str]] = []   # (severity, channel, subject) — for tests

    def __call__(self, severity: str, subject: str, body: str) -> list[str]:
        subject = f"[easy-rollout {self.env}] {severity.upper()}: {subject}"
        log(f"notify {severity}: {subject}")
        delivered = []
        for channel in self.cfg["channels"].get(severity, []):
            try:
                if getattr(self, f"_{channel}")(severity, subject, body):
                    self.sent.append((severity, channel, subject))
                    delivered.append(channel)
            except Exception as e:  # noqa: BLE001
                log(f"  notification via {channel} failed: {e}")
        if not delivered:
            # The journal is then the only place this went. Say so, so that a host with every
            # channel still a placeholder is at least visibly mute.
            log(f"  NO notification channel delivered this {severity} — it reached nobody")
        return delivered

    def _mail(self, severity: str, subject: str, body: str) -> bool:
        m = self.cfg.get("mail") or {}
        if not m.get("to") or not m.get("host"):
            return False
        msg = EmailMessage()
        msg["Subject"] = subject
        msg["From"] = m.get("from") or f"easy-rollout@{socket.getfqdn()}"
        msg["To"] = ", ".join(m["to"])
        msg.set_content(body)
        with smtplib.SMTP(m["host"], int(m.get("port", 25)), timeout=30) as s:
            if m.get("starttls"):
                s.starttls()
            auth = read_secret_file(m.get("auth_file"))
            if auth and ":" in auth:
                user, pw = auth.split(":", 1)
                s.login(user, pw)
            s.send_message(msg)
        return True

    def _webhook(self, severity: str, subject: str, body: str) -> bool:
        w = self.cfg.get("webhook") or {}
        url = read_secret_file(w.get("url_file"))
        if not url:
            return False
        fmt = w.get("format", "json")
        if fmt == "ntfy":
            data, headers = body.encode(), {"Title": subject, "Priority": "urgent" if severity == CRITICAL else "default",
                                            "Content-Type": "text/plain"}
        elif fmt == "slack":
            data, headers = json.dumps({"text": f"*{subject}*\n```{body[:2800]}```"}).encode(), {"Content-Type": "application/json"}
        else:
            data, headers = json.dumps({"severity": severity, "title": subject, "text": body,
                                        "environment": self.env}).encode(), {"Content-Type": "application/json"}
        headers["User-Agent"] = USER_AGENT
        req = urllib.request.Request(url, data=data, headers=headers, method="POST")
        with urllib.request.urlopen(req, timeout=30):
            pass
        return True

    def _youtrack(self, severity: str, subject: str, body: str) -> bool:
        y = self.cfg.get("youtrack") or {}
        token = read_secret_file(y.get("token_file"))
        if not token or not y.get("base_url") or not y.get("project_id"):
            return False
        if not y.get("visibility_group_id"):
            # The instance has guest access and the body carries core's log tail. Never public.
            log("  youtrack: no visibility group configured, refusing to file an issue publicly")
            return False
        issue = {"summary": subject[:200],
                 "description": body[:20000],
                 "project": {"id": y["project_id"]},
                 "visibility": {"$type": "LimitedVisibility",
                                "permittedGroups": [{"id": y["visibility_group_id"]}]}}
        req = urllib.request.Request(f"{y['base_url'].rstrip('/')}/api/issues?fields=idReadable",
                                     data=json.dumps(issue).encode(),
                                     headers={"Authorization": f"Bearer {token}",
                                              "Content-Type": "application/json",
                                              "Accept": "application/json", "User-Agent": USER_AGENT},
                                     method="POST")
        with urllib.request.urlopen(req, timeout=30) as resp:
            created = json.load(resp)
            log(f"  filed {created.get('idReadable', '?')}")
        return True


# ---------------------------------------------------------------------------------------------
# gates
# ---------------------------------------------------------------------------------------------

DAY_NAMES = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]


def in_window(window: dict, now: datetime) -> tuple[bool, str]:
    """Is `now` inside the maintenance window? Returns (yes, why-not)."""
    if window.get("always"):
        return True, ""
    tz = ZoneInfo(window.get("tz", "UTC"))
    local = now.astimezone(tz)
    days = window.get("days") or DAY_NAMES
    if days != ["*"] and DAY_NAMES[local.weekday()] not in days:
        return False, f"not a rollout day ({DAY_NAMES[local.weekday()]}; window is {','.join(days)})"
    start_h, start_m = map(int, window["start"].split(":"))
    end_h, end_m = map(int, window["end"].split(":"))
    t = local.hour * 60 + local.minute
    if not (start_h * 60 + start_m <= t < end_h * 60 + end_m):
        return False, f"outside {window['start']}–{window['end']} {window.get('tz', 'UTC')} (now {local:%H:%M})"
    return True, ""


def in_freeze(freeze: list[dict], now: datetime, tz_name: str) -> str | None:
    """The reason for an active freeze period, or None. Dates are local, inclusive."""
    local_day = now.astimezone(ZoneInfo(tz_name)).date()
    for f in freeze:
        start = datetime.fromisoformat(f["from"]).date()
        end = datetime.fromisoformat(f["to"]).date()
        if start <= local_day <= end:
            return f"frozen until {f['to']}: {f.get('reason', 'no reason given')}"
    return None


def soak_satisfied(state: State, sha: str, soak_hours: float) -> tuple[bool, str]:
    """Has `sha` run on dev for `soak_hours` in one unbroken stretch, and is it still there?

    Contiguous, not first-to-last: a commit that was on dev for twenty minutes, replaced for a
    week, and put back for twenty minutes has soaked for forty minutes, not a week. And still
    there: a commit dev has since rolled back from is one dev found wanting.
    """
    seen = state.dev_seen.get(sha)
    if not seen:
        return False, "never observed running on dev"
    hours = float(seen.get("hours", 0.0))
    if state.data.get("dev_current") != sha:
        return False, f"dev has moved on from it (was there {hours:.1f}h; dev now runs {short(state.data.get('dev_current'))})"
    if hours < soak_hours:
        return False, f"observed on dev for {hours:.1f}h, needs {soak_hours}h"
    return True, ""


# ---------------------------------------------------------------------------------------------
# the rollout itself
# ---------------------------------------------------------------------------------------------

class Rollout:
    """One attempt to move production from `previous` to `sha`, recorded step by step."""

    def __init__(self, cfg: dict, host: Host, gh: GitHub, notify: Notifier, smoke, sha: str, run: dict,
                 deadline: float | None = None):
        self.cfg, self.host, self.gh, self.notify, self.smoke = cfg, host, gh, notify, smoke
        self.sha, self.run = sha, run
        self.previous = host.current_sha()
        self.started = now_utc()
        self.deadline = deadline          # time.monotonic() value; None means no budget
        self.steps: list[dict] = []
        self.dump: str | None = None
        self.migrates: bool | None = None
        self.touched_production = False
        self.outcome = "unknown"
        self.detail = ""
        self.retryable = False
        self.rehearsal_log: Path | None = None
        rollouts = cfg["state_dir"] / "rollouts"
        rollouts.mkdir(parents=True, exist_ok=True, mode=DIR_MODE)
        stamp = self.started.strftime("%Y-%m-%dT%H%M%SZ")
        self.record_path = rollouts / f"{stamp}-{sha[:8]}.json"
        log.sink = rollouts / f"{stamp}-{sha[:8]}.log"

    # -- bookkeeping ----------------------------------------------------------------------------
    def check_budget(self) -> None:
        # Once production has been touched the budget no longer applies: the way out of a spent
        # budget IS the rollback, and refusing to run it would leave the bad release live. The
        # unit's TimeoutStartSec stays as the backstop for a rollback that itself hangs.
        if self.touched_production:
            return
        if self.deadline is not None and time.monotonic() > self.deadline:
            raise Interrupted("the rollout's time budget is spent")

    def step(self, name: str, fn, *args, **kw):
        log(f"==> {name}")
        self.check_budget()
        t0 = time.monotonic()
        try:
            result = fn(*args, **kw)
        except RolloutError as e:
            self.steps.append({"step": name, "ok": False, "seconds": round(time.monotonic() - t0), "error": str(e)})
            self.save()
            raise
        except Exception as e:  # noqa: BLE001 — a bug in a step is still a failed step
            err = f"{type(e).__name__}: {e}\n{traceback.format_exc()[-1500:]}"
            self.steps.append({"step": name, "ok": False, "seconds": round(time.monotonic() - t0), "error": err})
            self.save()
            raise RolloutError(err) from e
        self.steps.append({"step": name, "ok": True, "seconds": round(time.monotonic() - t0),
                           **({"note": result} if isinstance(result, str) and result else {})})
        self.save()
        return result

    def summary(self) -> dict:
        return {"sha": self.sha, "previous": self.previous, "started": iso(self.started),
                "outcome": self.outcome, "detail": self.detail, "migrates": self.migrates,
                "retryable": self.retryable,
                "dump": self.dump, "run_url": self.run.get("html_url"), "steps": self.steps,
                "touched_production": self.touched_production}

    def save(self) -> None:
        try:
            write_atomic(self.record_path, json.dumps(self.summary(), indent=2) + "\n")
        except OSError as e:
            log(f"  (could not write {self.record_path}: {e})")

    def report(self) -> str:
        lines = [f"environment  {self.cfg.get('environment', '?')}",
                 f"release      {self.sha} (was {self.previous or 'nothing'})",
                 f"ci run       {self.run.get('html_url', '?')}",
                 f"outcome      {self.outcome}",
                 f"detail       {self.detail}" if self.detail else None,
                 f"dump         {self.dump}" if self.dump else None,
                 f"record       {self.record_path}", ""]
        lines = [l for l in lines if l is not None]
        for s in self.steps:
            mark = "ok " if s["ok"] else "FAIL"
            lines.append(f"  {mark} {s['step']:<28} {s['seconds']:>5}s  {s.get('note') or s.get('error', '')}"[:300])
        return "\n".join(lines)

    # -- steps ----------------------------------------------------------------------------------
    def preflight(self) -> str:
        cfg, host = self.cfg, self.host
        gates = cfg["gates"]
        free = host.free_gb(host.root)
        if free < gates["min_free_gb"]:
            raise RolloutError(f"only {free:.1f} GB free under {host.root}, need {gates['min_free_gb']}", retryable=True)
        try:
            host.db("ping")
        except RolloutError as e:
            raise RolloutError(str(e), retryable=True) from e
        if not self.previous or not host.release_complete(self.previous):
            raise RolloutError(f"the current release {short(self.previous)} is not intact on disk, so there "
                               f"would be nothing to roll back to")
        if host.http_status(cfg["health_url"]) not in (200, 401, 403):
            raise RolloutError(f"core is not healthy at {cfg['health_url']} before the rollout — not deploying "
                               f"onto a broken system; this needs a person")
        if not host.core_active():
            raise RolloutError(f"{cfg['service']} is not active before the rollout")
        return f"{free:.0f} GB free, core healthy, previous release {short(self.previous)} intact"

    def baseline_smoke(self) -> str:
        ok, text, configured = self.run_smoke(expect_sha=self.previous,
                                              attempts=int(self.cfg["smoke"].get("baseline_attempts", 2)))
        if not configured and self.cfg["smoke"].get("required", True):
            raise RolloutError("the smoke suite is required on this environment and is not configured — "
                               "fill in the smoke secrets (doc/production-rollout.md §setup)", retryable=True)
        if not configured:
            return text
        if not ok:
            raise RolloutError("the smoke suite fails against the CURRENT release, so a failure after the "
                               "deploy could not be attributed. Not deploying. Details:\n" + text, retryable=True)
        return "current release passes the full suite"

    def fetch(self) -> str:
        self.host.materialise(self.gh, self.run, self.sha)
        return "core.jar and web/ in place"

    def take_dump(self) -> str:
        self.dump = self.host.dump_database()
        return self.dump

    def changelog_count(self, *args: str) -> int | None:
        """Rows in databasechangelog, or None when the helper could not say — never a made-up 0."""
        try:
            return int(self.host.db("changelog-count", *args))
        except (RolloutError, ValueError) as e:
            log(f"  could not count changesets ({e}); treating the schema change as unknown")
            return None

    def rehearse(self) -> str:
        """Boot the new jar against a copy of production's data, with every integration disabled."""
        cfg, host = self.cfg, self.host
        r = cfg["rehearsal"]
        if not r["enabled"]:
            return "disabled by configuration"
        if not self.dump:
            raise RolloutError("no dump to rehearse against")
        if not host.port_free(int(r["port"])):
            raise RolloutError(f"port {r['port']} is in use — a previous rehearsal may still be running", retryable=True)
        before = self.changelog_count()
        try:
            host.db("rehearsal-create", self.dump, timeout=3600)
        except RolloutError as e:
            raise RolloutError(str(e), retryable=True) from e
        try:
            config_path = host.db("rehearsal-config", str(r["port"]))
            assert_rehearsal_config_is_harmless(Path(config_path).read_text(), int(r["port"]))
            log_path = Path(host.db("rehearsal-run", self.sha, str(r["port"])))
            self.rehearsal_log = log_path
            try:
                deadline = time.monotonic() + int(r["timeout_s"])
                while time.monotonic() < deadline:
                    self.check_budget()
                    status = host.db("rehearsal-status")
                    if status.startswith("failed") or status == "inactive":
                        raise RolloutError(f"the new release exited ({status}) during the rehearsal — "
                                           f"migration or configuration failure. Last log lines:\n"
                                           + tail_of(log_path))
                    if host.http_status(f"http://127.0.0.1:{r['port']}/v2/") in (401, 403, 200):
                        break
                    sleep(3)
                else:
                    raise RolloutError(f"the new release did not answer within {r['timeout_s']}s in the rehearsal:\n"
                                       + tail_of(log_path))
            finally:
                try:
                    host.db("rehearsal-stop")
                except RolloutError as e:
                    log(f"  warning: {e}")
            after = self.changelog_count("rehearsal")
        finally:
            try:
                host.db("rehearsal-drop")
            except RolloutError as e:
                log(f"  warning: {e}")
        self.migrates = None if (before is None or after is None) else after > before
        if self.migrates is None:
            return "new release booted against a copy of production; schema change UNKNOWN (count failed)"
        return (f"new release booted against a copy of production; {after - before} changeset(s) applied"
                if self.migrates else "new release booted against a copy of production; no schema change")

    def activate(self) -> str:
        self.touched_production = True
        self.host.activate(self.sha)
        self.host.restart_core()
        return "symlinks flipped, core restarted"

    def health(self, sha: str) -> str:
        cfg, host = self.cfg, self.host
        # Unknown counts as migrating: the cost of waiting longer is minutes, the cost of
        # restarting core in the middle of a migration is a restore.
        timeout = cfg["health_timeout_s"] if self.migrates is False else cfg["health_timeout_migrating_s"]
        if not host.wait_healthy(cfg["health_url"], int(timeout)):
            raise RolloutError(f"core did not answer at {cfg['health_url']} within {timeout}s after installing "
                               f"{short(sha)}. Log:\n{host.core_log_tail()}")
        if not host.core_active():
            raise RolloutError(f"{cfg['health_url']} answers but {cfg['service']} is not active")
        return "401 from the public API, unit active"

    def run_smoke(self, expect_sha: str, attempts: int | None = None) -> tuple[bool, str, bool]:
        """(passed, report text, configured)."""
        s = self.cfg["smoke"]
        attempts = attempts or int(s["attempts"])
        text = ""
        for i in range(1, attempts + 1):
            report = self.smoke(expect_sha=expect_sha)
            text = report.text()
            if report.not_configured:
                # An unconfigured suite is a failed gate wherever the suite is required. Where it
                # is not, say so in the record rather than pretend a check happened.
                if not s.get("required", True):
                    return True, "smoke suite not configured, and not required on this environment", False
                return False, text, False
            if report.ok:
                return True, text, True
            if i < attempts:
                log(f"  smoke attempt {i}/{attempts} failed; retrying in {s['retry_delay_s']}s")
                sleep(int(s["retry_delay_s"]))
        return False, text, True

    def post_smoke(self) -> str:
        ok, text, _ = self.run_smoke(expect_sha=self.sha)
        if not ok:
            raise RolloutError("smoke suite failed against the new release:\n" + text)
        return "every check passed"

    def mark(self) -> str:
        self.host.mark_current(self.sha)
        return f"{short(self.sha)} is live"

    def prune(self) -> str:
        try:
            self.host.prune({self.sha, self.previous})
        except Exception as e:  # noqa: BLE001 — housekeeping is never a reason to roll back
            return f"prune failed, ignored: {e}"
        return "old releases pruned"

    # -- rollback -------------------------------------------------------------------------------
    def rollback(self, why: str) -> None:
        host, cfg = self.host, self.cfg
        log(f"!!! rolling back to {short(self.previous)}: {why[:200]}")
        try:
            self.step("rollback: reactivate previous", self._reactivate_previous)
        except RolloutError as e:
            # The symlinks could not even be moved back. Restoring the database would not help
            # and would discard data for nothing.
            self.outcome, self.detail = "DOWN", f"could not reactivate the previous release: {e}"
            return
        try:
            self.step("rollback: health", self.health, self.previous)
        except RolloutError as e:
            policy = cfg["rollback"]["restore_db"]
            unit_down = not host.core_active()
            # Restore only on positive evidence that the previous JAR does not start — the unit is
            # down or crash-looping — never because the public URL is quiet: that is nginx, DNS or
            # a slow warm-up, and a restore would throw data away to fix none of them.
            if not unit_down:
                self.outcome = "DOWN"
                self.detail = (f"{cfg['service']} is active on {short(self.previous)} but {cfg['health_url']} does "
                               f"not answer — not a database problem, so the database was left alone: {e}")
                return
            if self.dump and (policy == "always" or (policy == "auto" and self.migrates is not False)):
                log(f"!!! previous release does not start; restoring the database from {self.dump} "
                    f"(policy {policy}, migrates={self.migrates})")
                try:
                    self.step("rollback: restore database", lambda: host.db("restore", self.dump, timeout=7200))
                    self.step("rollback: health after restore", self.health, self.previous)
                except RolloutError as e2:
                    self.outcome, self.detail = "DOWN", f"rollback failed; production is not answering: {e2}"
                    return
            else:
                self.outcome = "DOWN"
                self.detail = (f"previous release does not start and the database was not restored "
                               f"(policy {policy}, migrates={self.migrates}): {e}")
                return
        try:
            self.step("rollback: smoke", self._smoke_previous)
            self.outcome, self.detail = "rolled-back", f"production is back on {short(self.previous)} and passes smoke. {why}"
        except RolloutError as e:
            self.outcome, self.detail = "rolled-back-degraded", (f"production is back on {short(self.previous)} and "
                                                                 f"answers, but the smoke suite fails: {e}")

    def _reactivate_previous(self) -> str:
        self.host.activate(self.previous)
        self.host.restart_core()
        # current-sha names what is live, whatever happens next.
        self.host.mark_current(self.previous)
        return f"symlinks back to {short(self.previous)}"

    def _smoke_previous(self) -> str:
        ok, text, _ = self.run_smoke(expect_sha=self.previous)
        if not ok:
            raise RolloutError(text)
        return "every check passes on the previous release"

    # -- the whole thing ------------------------------------------------------------------------
    def execute(self) -> dict:
        log(f"### rollout {short(self.previous)} -> {short(self.sha)} — {self.run.get('html_url')}")
        try:
            try:
                self.step("preflight", self.preflight)
                self.step("baseline smoke", self.baseline_smoke)
                self.step("fetch artifacts", self.fetch)
                self.step("database dump", self.take_dump)
                self.step("rehearsal", self.rehearse)
                self.step("activate", self.activate)
                self.step("health", self.health, self.sha)
                self.step("smoke", self.post_smoke)
                self.step("mark current", self.mark)
                self.outcome = "deployed"
            except RolloutError as e:
                if self.touched_production:
                    self.rollback(str(e))
                else:
                    self.retryable = e.retryable
                    self.outcome, self.detail = "aborted", f"production untouched: {e}"
            if self.outcome == "deployed":
                self.step("prune", self.prune)
        except BaseException as e:  # noqa: BLE001 — never leave without a verdict on disk
            self.outcome = "DOWN" if self.touched_production else "aborted"
            self.detail = f"unexpected failure ({type(e).__name__}: {e}); state of the host unknown — look"
            self.retryable = not self.touched_production
        self.save()
        log.sink = None
        return self.summary()


def tail_of(path: Path, lines: int = 40) -> str:
    try:
        return "\n".join(path.read_text(errors="replace").splitlines()[-lines:])
    except OSError:
        return "(no log)"


# What the rehearsal's config must say before the JVM is started against it. The root helper writes
# that file (easy_rehearsal_config.py); this re-reads it and refuses if any property is missing, so
# a change to the helper that loses one is caught here rather than by a real Moodle gradebook.
#
# The file is JSON — which Spring reads as YAML, since JSON is YAML — so this needs no YAML library
# and checks structure rather than text. The list of properties lives in easy_rehearsal_config so
# writer and guard cannot drift apart; the guard is still a separate step because the writer runs
# as root and this runs as the account that starts the JVM, and each should refuse on its own.
def assert_rehearsal_config_is_harmless(text: str, port: int) -> None:
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import easy_rehearsal_config  # noqa: E402
    try:
        cfg = json.loads(text)
    except ValueError as e:
        raise RolloutError(f"refusing to start the rehearsal: its config is not JSON ({e})") from e
    problems = easy_rehearsal_config.problems(cfg, port)
    if problems:
        raise RolloutError("refusing to start the rehearsal: " + "; ".join(problems))


# ---------------------------------------------------------------------------------------------
# the tick: decide, then act
# ---------------------------------------------------------------------------------------------

class Controller:
    def __init__(self, cfg: dict, host: Host, gh: GitHub, notify: Notifier, smoke, state: State,
                 clock=now_utc, deadline: float | None = None):
        self.cfg, self.host, self.gh, self.notify, self.smoke, self.state = cfg, host, gh, notify, smoke, state
        self.clock = clock
        self.deadline = deadline

    # -- files a person may drop into the state directory ---------------------------------------
    @property
    def pause_file(self) -> Path:
        return self.cfg["state_dir"] / "pause"

    @property
    def deploy_now_file(self) -> Path:
        return self.cfg["state_dir"] / "deploy-now"

    def pause(self, reason: str) -> None:
        write_atomic(self.pause_file, f"{iso(self.clock())} {reason}\n", mode=0o664)

    def paused_reason(self) -> str | None:
        """Presence pauses. An empty file is `touch`ed by a person in a hurry, and means it."""
        if not self.pause_file.exists():
            return None
        try:
            return self.pause_file.read_text().strip() or "(no reason given)"
        except OSError:
            return "(pause file unreadable)"

    # -- observing dev --------------------------------------------------------------------------
    def record_dev_sighting(self) -> None:
        url = self.cfg["gates"].get("dev_version_url")
        if not url:
            return
        text = self.host.http_text(url)
        if not text:
            return
        sha = extract_sha(text)
        if not sha:
            return
        now = self.clock()
        state = self.state
        entry = state.dev_seen.get(sha)
        previous = state.data.get("dev_current")
        if entry is None or previous != sha:
            # First sighting, or dev came back to it: a new stretch starts now.
            state.dev_seen[sha] = {"first": iso(now), "last": iso(now), "hours": 0.0}
        else:
            entry["hours"] = round(float(entry.get("hours", 0.0)) + hours_between(parse_iso(entry["last"]), now), 3)
            entry["last"] = iso(now)
        state.data["dev_current"] = sha
        # Forget sightings older than a month; the file should not grow forever.
        cutoff = now - timedelta(days=31)
        for k in [k for k, v in state.dev_seen.items() if parse_iso(v["last"]) < cutoff]:
            del state.dev_seen[k]

    # -- gates ----------------------------------------------------------------------------------
    def gate_reasons(self, sha: str, run: dict, now: datetime) -> list[str]:
        cfg, g = self.cfg, self.cfg["gates"]
        reasons = []
        ok, why = in_window(cfg["window"], now)
        if not ok:
            reasons.append(why)
        frozen = in_freeze(cfg["freeze"], now, cfg["window"].get("tz", "UTC"))
        if frozen:
            reasons.append(frozen)
        age = hours_between(parse_iso(run["run_started_at"]), now)
        if age < g["min_ci_age_hours"]:
            reasons.append(f"CI run is {age:.1f}h old, needs {g['min_ci_age_hours']}h")
        if g["require_seen_on_dev"]:
            ok, why = soak_satisfied(self.state, sha, g["soak_hours"])
            if not ok:
                reasons.append(why)
        if g["require_on_master"]:
            try:
                if not self.gh.is_ancestor_of(sha, "master"):
                    reasons.append("commit is not on master (push it there first, or use deploy-now)")
            except Exception as e:  # noqa: BLE001
                reasons.append(f"could not check ancestry of master: {e}")
        last = self.state.data.get("last_success_at")
        if last and hours_between(parse_iso(last), now) < g["min_gap_hours"]:
            reasons.append(f"last rollout was {hours_between(parse_iso(last), now):.1f}h ago, minimum gap {g['min_gap_hours']}h")
        attempt = self.state.candidates.get(sha, {}).get("last_attempt")
        if attempt and hours_between(parse_iso(attempt), now) < g.get("min_retry_gap_hours", 0):
            reasons.append(f"last attempt failed {hours_between(parse_iso(attempt), now):.1f}h ago for a reason that may "
                           f"pass later; retrying after {g['min_retry_gap_hours']}h")
        return reasons

    def deploy_now(self) -> tuple[str, datetime] | None:
        """The pending override as (sha-or-'head', written-at), or None if absent or expired."""
        if not self.deploy_now_file.exists():
            return None
        try:
            parts = self.deploy_now_file.read_text().split()
        except OSError:
            return None
        if not parts:
            return None
        want = parts[0]
        written = parse_iso(parts[1]) if len(parts) > 1 else self.clock()
        ttl = float(self.cfg["gates"].get("deploy_now_ttl_hours", 24))
        if hours_between(written, self.clock()) > ttl:
            log(f"deploy-now for {want} written {iso(written)} has expired ({ttl}h); ignoring and removing it")
            self.deploy_now_file.unlink(missing_ok=True)
            return None
        return want, written

    def override_for(self, sha: str) -> bool:
        """`deploy-now` naming this sha (7+ chars) or `head` skips the scheduling gates — never the checks."""
        pending = self.deploy_now()
        if pending is None:
            return False
        want, _ = pending
        if want == "head":
            return True
        if len(want) >= 7 and sha.startswith(want):
            return True
        log(f"deploy-now names {want} but the branch points at {short(sha)} — not applying it")
        return False

    # -- one tick -------------------------------------------------------------------------------
    def decide(self, now: datetime) -> dict:
        """What this tick should do, and why — without doing it.

        One ladder, used by the tick and by `easy-rollout check`, so that the two cannot disagree
        about what the next tick will do. Returns {"kind", "head", "current", "run", "reasons",
        "override", "error"}; `kind` is the word the tick reports.
        """
        state = self.state
        d = {"kind": "", "head": None, "current": self.host.current_sha(), "run": None,
             "reasons": [], "override": False, "error": None}
        reason = self.paused_reason()
        if reason:
            d.update(kind="paused", reasons=[reason])
            return d
        try:
            d["head"] = head = self.gh.branch_head(self.cfg["branch"])
        except GitHubUnavailable as e:
            d.update(kind="github-unavailable", error=str(e))
            return d
        if head == d["current"]:
            d["kind"] = "steady"
            return d
        if head in state.failed:
            d.update(kind="failed-candidate",
                     reasons=[f"failed on {state.failed[head]['at']}: {state.failed[head]['why'][:200]} — "
                              f"`easy-rollout forget {head[:8]}` to allow a retry"])
            return d
        try:
            d["run"] = run = self.gh.green_run_for(head)
        except GitHubUnavailable as e:
            d.update(kind="github-unavailable", error=str(e))
            return d
        if run is None:
            d.update(kind="waiting-for-ci", reasons=["no green CI run yet"])
            return d
        d["override"] = self.override_for(head)
        d["reasons"] = [] if d["override"] else self.gate_reasons(head, run, now)
        d["kind"] = "gated" if d["reasons"] else "rollout"
        return d

    def tick(self) -> str:
        now = self.clock()
        state = self.state
        if state.corrupt and state.notice_due("state-corrupt", 24, now):
            self.notify(WARN, "state file was unreadable and has been reset", state.corrupt)
            state.noticed("state-corrupt", now)
        try:
            self.record_dev_sighting()
        except Exception as e:  # noqa: BLE001
            log(f"could not record dev sighting: {e}")

        d = self.decide(now)
        kind, head = d["kind"], d["head"]
        if kind == "paused":
            state.save()
            return f"paused: {d['reasons'][0]}"
        if kind == "github-unavailable":
            return self.github_unavailable(d["error"], now)
        if kind == "steady":
            state.candidates.clear()
            for key in ("stuck", "github"):
                state.clear_notice(key)
            state.save()
            return "steady"

        state.clear_notice("github")
        cand = state.candidates.setdefault(head, {"first_seen": iso(now)})
        cand["reasons"] = d["reasons"]
        if kind in ("failed-candidate", "waiting-for-ci", "gated"):
            self.maybe_alarm_stuck(head, cand, now)
            state.save()
            return kind

        state.save()
        if d["override"]:
            log(f"deploy-now override for {short(head)} — scheduling gates skipped, checks are not")
            self.deploy_now_file.unlink(missing_ok=True)
        return self.rollout(head, d["run"])

    def github_unavailable(self, why: str, now: datetime) -> str:
        """Not a crash, not a CRITICAL every tick: a WARN once a day while it lasts."""
        log(f"github unavailable: {why}")
        g = self.cfg["gates"]
        first = self.state.data["notices"].get("github-since")
        if not first:
            self.state.data["notices"]["github-since"] = iso(now)
        elif hours_between(parse_iso(first), now) >= 1 and self.state.notice_due("github", g["stuck_repeat_hours"], now):
            self.notify(WARN, "GitHub has been unreachable for hours",
                        f"Every tick since {first} has failed to ask GitHub: {why}\n\nNothing is wrong with "
                        f"production. If the message names the token, put a new one in the token file.")
            self.state.noticed("github", now)
        self.state.save()
        return "github-unavailable"

    def maybe_alarm_stuck(self, head: str, cand: dict, now: datetime) -> None:
        g = self.cfg["gates"]
        waited = hours_between(parse_iso(cand["first_seen"]), now)
        if waited >= g["stuck_after_hours"] and self.state.notice_due("stuck", g["stuck_repeat_hours"], now):
            self.notify(WARN, f"{self.cfg['branch']} has pointed at {short(head)} for {waited / 24:.1f} days without deploying",
                        "The branch moved but no rollout has happened. Current reasons:\n  - "
                        + "\n  - ".join(cand.get("reasons", [])) +
                        f"\n\nNothing is wrong with production. Clear whatever is blocking, or `easy-rollout deploy-now {head[:8]}` "
                        f"to skip the scheduling gates (never the checks), or move the branch back.")
            self.state.noticed("stuck", now)

    # -- the rollout, with a lock and the escalation around it ----------------------------------
    def rollout(self, sha: str, run: dict) -> str:
        cfg = self.cfg
        cfg["state_dir"].mkdir(parents=True, exist_ok=True)
        lock_path = cfg["state_dir"] / "lock"
        with lock_path.open("w") as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                return "another rollout holds the lock"
            self.state.data["_this_run"] = sha
            r = Rollout(cfg, self.host, self.gh, self.notify, self.smoke, sha, run, deadline=self.deadline)
            summary = r.execute()
        now = self.clock()
        self.state.reload_failed_from_disk()
        self.state.record_history({k: summary[k] for k in ("sha", "previous", "started", "outcome", "detail")})
        outcome = summary["outcome"]
        cand = self.state.candidates.setdefault(sha, {"first_seen": iso(now)})
        if outcome == "deployed":
            self.state.data["last_success_at"] = iso(now)
            self.state.candidates.clear()
            commits = self.gh.commits_between(summary["previous"], sha) if summary["previous"] else []
            self.notify(INFO, f"deployed {short(sha)}",
                        r.report() + "\n\nCommits:\n  " + "\n  ".join(commits or ["(none listed)"]))
        elif outcome == "aborted" and summary.get("retryable"):
            # Production was never touched and the reason is not the commit's: GitHub, the disk,
            # the backup unit, a smoke suite that could not run. Try again after a gap, and say
            # so — once, not on every attempt.
            cand["last_attempt"] = iso(now)
            cand["reasons"] = [f"attempt at {iso(now)} aborted (will retry): {summary['detail'][:200]}"]
            if self.state.notice_due(f"retry-{sha[:8]}", self.cfg["gates"]["stuck_repeat_hours"], now):
                self.notify(WARN, f"rollout of {short(sha)} aborted before touching production; will retry",
                            r.report() + f"\n\nRetried no sooner than {self.cfg['gates']['min_retry_gap_hours']}h from now, "
                            f"at the next window. If this keeps happening, look at the reason above.")
                self.state.noticed(f"retry-{sha[:8]}", now)
        elif outcome == "aborted":
            # Production was never touched and the failure is the commit's. It will not fix itself,
            # so the sha is parked until a person looks — but nothing is paused, because a NEW
            # commit that fixes the problem should deploy on its own.
            self.state.failed[sha] = {"at": iso(now), "why": summary["detail"]}
            self.notify(WARN, f"rollout of {short(sha)} aborted before touching production",
                        r.report() + "\n\nA fixed commit pushed to the branch will be attempted at the next window. "
                        f"To retry this same commit: `easy-rollout forget {sha[:8]}`.")
        else:
            # Production was touched and had to be put back — or could not be. Either way a person
            # decides what happens next, so automatic rollouts stop here.
            self.state.failed[sha] = {"at": iso(now), "why": summary["detail"]}
            self.pause(f"automatic rollouts stopped after {outcome} of {sha[:8]}; `easy-rollout resume` when understood")
            self.notify(CRITICAL, f"{outcome.upper()}: {short(sha)} failed after deploy", r.report() +
                        "\n\nAutomatic rollouts are PAUSED. `easy-rollout status` on the host; "
                        "`easy-rollout resume` when the cause is understood.")
        self.state.data.pop("_this_run", None)
        self.state.save()
        return outcome


SHA_RE = re.compile(r"\b[0-9a-f]{7,40}\b")


def extract_sha(text: str) -> str | None:
    """A commit id from a version response — JSON `{"commit": "..."}`, `v4.0 (1a2b3c4)`, or bare."""
    try:
        data = json.loads(text)
        for key in ("commit", "gitCommit", "sha"):
            if isinstance(data, dict) and isinstance(data.get(key), str) and SHA_RE.fullmatch(data[key]):
                return data[key]
    except ValueError:
        pass
    m = SHA_RE.search(text)
    return m.group(0) if m else None


# ---------------------------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------------------------

class TokenMissing(RolloutError):
    """The token file is absent or a placeholder: nothing can be asked of GitHub yet."""


def build(cfg: dict, smoke_factory=None, deadline: float | None = None):
    token = read_secret_file(cfg["token_file"])
    if token is None:
        raise TokenMissing(f"{cfg['token_file']} is absent, unreadable or still a placeholder — nothing can be "
                           f"resolved until a GitHub token with Actions:read is in it")
    host = Host(cfg)
    gh = GitHub(cfg["repo"], cfg["workflow"], token)
    notify = Notifier(cfg, cfg.get("environment", cfg["branch"]))
    state = load_state(cfg)
    smoke = (smoke_factory or default_smoke_factory)(cfg)
    return Controller(cfg, host, gh, notify, smoke, state, deadline=deadline)


def load_state(cfg: dict) -> State:
    state_dir: Path = cfg["state_dir"]
    state_dir.mkdir(parents=True, exist_ok=True)
    return State(state_dir / "state.json")


def default_smoke_factory(cfg: dict):
    """The end-to-end suite lives beside this file; imported lazily so `status` needs nothing."""
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import easy_smoke  # noqa: E402

    def run(expect_sha: str):
        return easy_smoke.run(cfg["smoke"], expect_sha=expect_sha, log=log)
    return run


def cmd_status(cfg: dict) -> int:
    state = load_state(cfg)
    s = state.data
    host = Host(cfg)
    state_dir: Path = cfg["state_dir"]
    print(f"branch        {cfg['branch']}")
    print(f"current-sha   {host.current_sha() or 'nothing'}")
    pause = state_dir / "pause"
    print(f"paused        {(pause.read_text().strip() or '(no reason given)') if pause.exists() else 'no'}")
    dn = state_dir / "deploy-now"
    if dn.exists():
        print(f"deploy-now    {dn.read_text().strip()}")
    print(f"last success  {s.get('last_success_at') or 'never'}")
    if s.get("dev_current"):
        print(f"dev runs      {s['dev_current'][:8]}")
    for sha, c in s["candidates"].items():
        print(f"candidate     {sha[:8]} since {c['first_seen']}")
        for r in c.get("reasons", []):
            print(f"                - {r}")
    for sha, f in s["failed"].items():
        print(f"failed        {sha[:8]} at {f['at']}: {f['why'][:160]}")
    if s["dev_seen"]:
        print("seen on dev   " + ", ".join(f"{k[:8]} ({v.get('hours', 0):.1f}h)"
                                          for k, v in sorted(s["dev_seen"].items(), key=lambda kv: kv[1]["last"])[-5:]))
    for h in s["history"][-5:]:
        print(f"history       {h['started']} {h['previous'][:8] if h['previous'] else '-':>8} -> {h['sha'][:8]} {h['outcome']}: {h['detail'][:120]}")
    return 0


def cmd_check(ctrl: Controller) -> int:
    """What the next tick would decide, without doing it — the tick's own ladder, printed."""
    d = ctrl.decide(ctrl.clock())
    print(f"branch head   {d['head'] or '?'}")
    print(f"current-sha   {d['current'] or 'nothing'}")
    if d["run"]:
        print(f"ci run        {d['run']['html_url']}")
    pending = ctrl.deploy_now()
    if pending:
        print(f"deploy-now    {pending[0]} (written {iso(pending[1])})")
    kind = d["kind"]
    if kind == "github-unavailable":
        print(f"decision      cannot ask GitHub: {d['error']}")
        return 1
    if kind == "rollout":
        print("decision      would roll out on the next tick" + (" (deploy-now override)" if d["override"] else ""))
    elif d["reasons"]:
        print(f"decision      {kind}:")
        for r in d["reasons"]:
            print(f"                - {r}")
    else:
        print(f"decision      {kind}")
    return 0


def install_signal_handlers() -> None:
    def on_term(signum, frame):
        raise Interrupted(f"received signal {signum}; finishing the way a failure would")
    signal.signal(signal.SIGTERM, on_term)
    signal.signal(signal.SIGINT, on_term)


def main(argv: list[str] | None = None) -> int:
    os.umask(0o027)
    p = argparse.ArgumentParser(prog="easy-rollout", description=__doc__.split("\n\n")[0])
    p.add_argument("--config", default="/etc/easy/rollout.json")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("tick", help="what the timer runs: decide, and roll out if everything allows it")
    sub.add_parser("status", help="state, candidates and why they wait, recent history")
    sub.add_parser("check", help="what the next tick would decide, without acting")
    sp = sub.add_parser("pause", help="stop automatic rollouts until `resume`")
    sp.add_argument("reason", nargs="*")
    sub.add_parser("resume", help="allow automatic rollouts again")
    sp = sub.add_parser("deploy-now", help="skip the scheduling gates for one commit (never the checks)")
    sp.add_argument("sha", help="a commit id prefix (7+ chars) that the branch points at, or `head`")
    sp = sub.add_parser("forget", help="allow a failed commit to be attempted again")
    sp.add_argument("sha")
    sp = sub.add_parser("smoke", help="run the end-to-end suite now, against whatever is live")
    sp.add_argument("--expect-sha", default=None)
    sp = sub.add_parser("rollback", help="put a release that is on disk back, by hand")
    sp.add_argument("sha")
    sub.add_parser("notify-test", help="send a test notification at every severity and say which channels delivered")
    sp = sub.add_parser("notify-failure", help="used by systemd OnFailure=: report that a unit failed")
    sp.add_argument("unit")
    args = p.parse_args(argv)

    cfg = load_config(Path(args.config))
    cmd = args.cmd
    state_dir: Path = cfg["state_dir"]

    # Commands that only touch the state directory need no token and no network, and run as the
    # person typing them.
    if cmd == "pause":
        state_dir.mkdir(parents=True, exist_ok=True)
        write_atomic(state_dir / "pause", f"{iso(now_utc())} {' '.join(args.reason) or 'paused by hand'}\n", 0o664)
        print("paused")
        return 0
    if cmd == "resume":
        (state_dir / "pause").unlink(missing_ok=True)
        print("resumed — the next tick may roll out")
        return 0
    if cmd == "forget":
        st = load_state(cfg)
        for k in [k for k in st.failed if k.startswith(args.sha)]:
            del st.failed[k]
            print(f"forgot {k}")
        st.save()
        return 0
    if cmd == "status":
        return cmd_status(cfg)
    if cmd == "deploy-now":
        want = args.sha
        if want != "head" and not re.fullmatch(r"[0-9a-f]{7,40}", want):
            print("deploy-now needs `head` or at least 7 hex characters of a commit id", file=sys.stderr)
            return 2
        if want == "head":
            # Pin it to the commit the branch points at NOW: an override must name one commit, not
            # whatever somebody pushes next week.
            try:
                want = build(cfg).gh.branch_head(cfg["branch"])
                print(f"head is {want}")
            except (RolloutError, GitHubUnavailable) as e:
                print(f"could not resolve head ({e}); storing `head` — it applies to the branch tip at the next "
                      f"tick and expires in {cfg['gates']['deploy_now_ttl_hours']}h", file=sys.stderr)
        write_atomic(state_dir / "deploy-now", f"{want} {iso(now_utc())}\n", 0o664)
        print(f"the next tick will roll out {want[:8]} if the branch points at it, CI is green and every check passes")
        return 0
    if cmd == "notify-failure":
        Notifier(cfg, cfg.get("environment", cfg["branch"]))(
            CRITICAL, f"{args.unit} failed",
            f"systemd reports {args.unit} failed. This is the rollout machinery itself, not a release: "
            f"`journalctl -u {args.unit} -n 100` on the host.")
        return 0
    if cmd == "notify-test":
        n = Notifier(cfg, cfg.get("environment", cfg["branch"]))
        ok = True
        for sev in (INFO, WARN, CRITICAL):
            got = n(sev, "test notification", f"easy-rollout notify-test at {iso(now_utc())}: this is a test of the {sev} channels.")
            print(f"{sev:<9} {', '.join(got) if got else 'REACHED NOBODY'}")
            ok = ok and bool(got)
        return 0 if ok else 1

    # Everything below needs GitHub or the smoke credentials, i.e. runs as the rollout account.
    budget = os.environ.get("EASY_ROLLOUT_BUDGET_S")
    deadline = (time.monotonic() + int(budget) * 0.9) if budget else None
    try:
        ctrl = build(cfg, deadline=deadline)
    except TokenMissing as e:
        if cmd == "tick":
            # An unconfigured host says so once a tick and exits cleanly, as autodeploy did: a
            # unit that fails every minute would page somebody about a host that is simply
            # not finished being set up.
            print(str(e))
            return 0
        raise
    if cmd == "check":
        return cmd_check(ctrl)
    if cmd == "smoke":
        with (state_dir / "lock").open("w") as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                print("a rollout is running; not starting a second smoke run against the same accounts", file=sys.stderr)
                return 1
            report = ctrl.smoke(expect_sha=args.expect_sha or ctrl.host.current_sha())
        print(report.text())
        return 0 if report.ok else 1
    if cmd == "rollback":
        target = next((d.name for d in (cfg["root"] / "releases").glob("*/") if d.name.startswith(args.sha)), None)
        if not target or not ctrl.host.release_complete(target):
            print(f"no complete release matching {args.sha} under {cfg['root']}/releases", file=sys.stderr)
            return 1
        with (state_dir / "lock").open("w") as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                print("a rollout is running; wait for it or stop easy-rollout.service first", file=sys.stderr)
                return 1
            was = ctrl.host.current_sha()
            ctrl.pause(f"manual rollback from {short(was)} to {target[:8]}")
            if was and was != target:
                ctrl.state.failed[was] = {"at": iso(now_utc()), "why": f"rolled back by hand to {target[:8]}"}
                ctrl.state.save()
            ctrl.host.activate(target)
            ctrl.host.restart_core()
            ctrl.host.mark_current(target)
            ok = ctrl.host.wait_healthy(cfg["health_url"], int(cfg["health_timeout_migrating_s"]))
        print(f"{'healthy' if ok else 'NOT ANSWERING'} on {target[:8]}; automatic rollouts paused, {short(was)} marked failed")
        return 0 if ok else 1
    install_signal_handlers()
    result = ctrl.tick()
    if result not in ("steady",):
        log(f"tick: {result}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except RolloutError as e:
        print(f"error: {e}", file=sys.stderr)
        sys.exit(1)
