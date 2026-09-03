#!/usr/bin/env python3
"""easy-rollout — unattended, guarded updates of a Lahendus environment.

The dev host's `easy-autodeploy` installs whatever its branch points at, as soon as CI is green.
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
      ├─ head already failed once?     → never retried automatically
      ├─ green CI run for head?        → wait
      ├─ gates: window, freeze, CI age, soak on dev, ancestry of master, gap since last rollout
      │      unmet → remember why (`easy-rollout status`), alarm if it has been stuck for days
      └─ ROLLOUT
           1. preflight    disk, database, core healthy NOW, previous release intact,
                           baseline smoke — production must pass its own tests before we change it
           2. fetch        the CI artifacts (same layout as deploy.sh and easy-autodeploy)
           3. dump         the database, through the nightly backup unit
           4. rehearse     restore that dump into a scratch database and boot the NEW jar against
                           it, with every outbound integration pointed at nowhere. Migrations that
                           fail against real data, and config keys the release needs and the host
                           lacks, fail HERE — before production is touched.
           5. activate     flip the symlinks, restart core
           6. health       401 from /v2/ through the public vhost, unit active
           7. smoke        the full end-to-end suite: web, API, IdP, student and teacher flows,
                           an autograded submission through the executor, the Thonny contract
           8. done         current-sha, DEPLOYED, prune, notify with the commit list
         on failure at 5–7:
           rollback        previous symlinks, restart, health, smoke
                           → if the old jar does not come up and the release migrated the schema,
                             restore the dump taken in step 3 (policy-controlled)
                           → pause the rollout, mark the sha failed, escalate

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
import json
import os
import re
import shutil
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


# ---------------------------------------------------------------------------------------------
# small utilities
# ---------------------------------------------------------------------------------------------

def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).replace(microsecond=0).isoformat()


def parse_iso(s: str) -> datetime:
    dt = datetime.fromisoformat(s)
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


def hours_between(a: datetime, b: datetime) -> float:
    return (b - a).total_seconds() / 3600.0


def short(sha: str | None) -> str:
    return (sha or "")[:8] or "nothing"


def write_atomic(path: Path, text: str, mode: int = 0o644) -> None:
    """A half-written state file is worse than a stale one, so write beside and rename over."""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name("." + path.name + ".tmp")
    tmp.write_text(text)
    os.chmod(tmp, mode)
    os.replace(tmp, path)


class RolloutError(Exception):
    """A step failed in a way that was detected and explained. Anything else is a bug."""


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
            with self.sink.open("a") as f:
                f.write(line + "\n")

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
    # chain is up. Killing it half way would be worse than waiting.
    "health_timeout_migrating_s": 1800,
    "dump_service": "easy-db-backup.service",
    "db_helper": "/usr/local/bin/easy-rollout-db",
    "java": "/usr/bin/java",
    "window": {"days": ["Tue", "Thu"], "start": "04:00", "end": "05:30", "tz": "Europe/Tallinn"},
    "freeze": [],
    "gates": {
        "min_ci_age_hours": 6,
        "soak_hours": 12,
        "dev_version_url": "",
        "require_seen_on_dev": True,
        "require_on_master": True,
        "min_gap_hours": 20,
        "stuck_after_hours": 96,
        "stuck_repeat_hours": 24,
        "min_free_gb": 4,
    },
    "rehearsal": {
        "enabled": True,
        "port": 8091,
        "timeout_s": 1200,
        "java_opts": "-Xmx1g",
    },
    "smoke": {"required": True, "attempts": 3, "retry_delay_s": 60},
    "rollback": {"restore_db": "auto"},   # auto | never | always
    "notify": {
        "channels": {INFO: ["mail"], WARN: ["mail"], CRITICAL: ["mail", "webhook", "youtrack"]},
        "mail": {},
        "webhook": {},
        "youtrack": {},
    },
}


def deep_merge(base: dict, over: dict) -> dict:
    out = dict(base)
    for k, v in over.items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = deep_merge(out[k], v)
        else:
            out[k] = v
    return out


def load_config(path: Path) -> dict:
    cfg = deep_merge(DEFAULTS, json.loads(path.read_text()))
    cfg["root"] = Path(cfg["root"])
    cfg["state_dir"] = Path(cfg.get("state_dir") or (cfg["root"] / "rollout"))
    if not cfg["health_url"]:
        raise RolloutError("health_url is not configured")
    return cfg


def read_secret_file(path: str | Path | None) -> str | None:
    """A credential file's content, or None when it is absent or still a placeholder."""
    if not path:
        return None
    p = Path(path)
    if not p.exists():
        return None
    value = p.read_text().strip()
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
                   gates.dev_version_url, and when: the soak gate's evidence.
    `failed`     — shas that failed a rollout; never retried without a human clearing them.
    `history`    — the last rollout summaries, newest last.
    """

    def __init__(self, path: Path):
        self.path = path
        self.data = {"candidates": {}, "dev_seen": {}, "failed": {}, "history": [],
                     "notices": {}, "last_success_at": None}
        if path.exists():
            self.data.update(json.loads(path.read_text()))

    def save(self) -> None:
        write_atomic(self.path, json.dumps(self.data, indent=2, sort_keys=True) + "\n")

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
        with self.opener.open(req, timeout=300) as resp:
            return resp.read() if raw else json.load(resp)

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
        """True when `sha` is on `branch` — behind it or identical to it."""
        # `behind` means the branch has moved on past sha; `identical` means it is the tip. `ahead`
        # and `diverged` both mean the branch does NOT contain sha, which is the case this exists
        # to catch: a commit pushed straight at the release branch that master never saw.
        cmp = self.api(f"/repos/{self.repo}/compare/{sha}...{branch}")
        return cmp["status"] in ("behind", "identical")

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
        return (rel / "core.jar").is_file() and (rel / "web" / "index.html").is_file()

    def free_gb(self, path: Path) -> float:
        p = path
        while not p.exists():
            p = p.parent
        return shutil.disk_usage(p).free / 1e9

    def materialise(self, gh: GitHub, run: dict, sha: str) -> Path:
        rel = self.release_dir(sha)
        if self.release_complete(sha):
            log("  release already on disk, not re-downloading")
            return rel
        urls = gh.artifact_urls(run["id"], sha)
        with tempfile.TemporaryDirectory() as td:
            tmp = Path(td)
            for name, url in urls.items():
                log(f"  downloading {name}")
                zp = tmp / f"{name}.zip"
                zp.write_bytes(gh.api(url, raw=True))
                with zipfile.ZipFile(zp) as z:
                    z.extractall(tmp)
                zp.unlink()
            jar = next(iter(tmp.rglob(f"core-{sha}.jar")), None)
            tgz = next(iter(tmp.rglob(f"web-{sha}.tar.gz")), None)
            if jar is None or tgz is None:
                raise RolloutError("downloaded artifacts do not contain the jar and the dist")
            rel.mkdir(parents=True, exist_ok=True)
            shutil.copy2(jar, rel / "core.jar")
            web = rel / "web"
            if web.exists():
                shutil.rmtree(web)
            web.mkdir()
            with tarfile.open(tgz) as t:
                t.extractall(web, filter="data")
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
        shutil.copy2(cfg_src, web / "config.json")
        c = json.loads((web / "config.json").read_text())
        missing = [k for k in ("emsRoot",) if not c.get(k)]
        missing += ["keycloak." + k for k in ("url", "realm", "clientId") if not c.get("keycloak", {}).get(k)]
        if missing:
            raise RolloutError("config.json is missing: " + ", ".join(missing))
        if not (web / "index.html").is_file():
            raise RolloutError("no index.html in the unpacked dist")
        for link, target in ((self.root / "web" / "current", web),
                             (self.root / "core" / "current.jar", rel / "core.jar")):
            link.parent.mkdir(parents=True, exist_ok=True)
            tmp_link = link.with_name("." + link.name + ".new")
            if tmp_link.exists() or tmp_link.is_symlink():
                tmp_link.unlink()
            tmp_link.symlink_to(target)
            os.replace(tmp_link, link)

    def mark_current(self, sha: str) -> None:
        (self.release_dir(sha) / "DEPLOYED").write_text(iso(now_utc()) + "\n")
        write_atomic(self.root / "current-sha", sha + "\n")

    def prune(self, keep_shas: set[str]) -> None:
        rels = sorted((self.root / "releases").glob("*/"), key=lambda p: p.stat().st_mtime, reverse=True)
        for i, d in enumerate(rels):
            if i < int(self.cfg["keep_releases"]) or d.name in keep_shas:
                continue
            log(f"  pruning {d.name}")
            shutil.rmtree(d, ignore_errors=True)

    # -- services -------------------------------------------------------------------------------
    def restart_core(self) -> None:
        self.sudo(["/usr/bin/systemctl", "restart", self.cfg["service"]])

    def core_active(self) -> bool:
        return self.run(["systemctl", "is-active", "--quiet", self.cfg["service"]], check=False).returncode == 0

    def core_log_tail(self) -> str:
        cp = self.run(["sudo", "-n", "/usr/bin/journalctl", "-u", self.cfg["service"], "-n", "40", "--no-pager"],
                      check=False)
        return cp.stdout[-4000:]

    def dump_database(self) -> str:
        """Take a restore point through the nightly backup unit; returns the dump's path."""
        before = self.db("newest-dump")
        self.sudo(["/usr/bin/systemctl", "start", self.cfg["dump_service"]], timeout=3600)
        after = self.db("newest-dump")
        if not after or after == before:
            raise RolloutError(f"{self.cfg['dump_service']} finished but no new dump appeared "
                               f"(newest is still {before or 'none'})")
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

    def wait_healthy(self, url: str, timeout_s: int, interval: int = 4) -> bool:
        """401 (or 200) through the public vhost proves nginx, core and its filter chain."""
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            if self.http_status(url) in (200, 401, 403):
                return True
            sleep(interval)
        return False

    def port_free(self, port: int) -> bool:
        with socket.socket() as s:
            return s.connect_ex(("127.0.0.1", port)) != 0

    def popen(self, argv: list[str], cwd: Path, log_path: Path, env: dict | None = None) -> subprocess.Popen:
        f = log_path.open("w")
        return subprocess.Popen(argv, cwd=cwd, stdout=f, stderr=subprocess.STDOUT,
                                env={**os.environ, **(env or {})})


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

    def __call__(self, severity: str, subject: str, body: str) -> None:
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
            log(f"  NO notification channel is configured for {severity} — this message reached nobody")

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
        issue = {"summary": subject[:200],
                 "description": body[:20000],
                 "project": {"id": y["project_id"]}}
        if y.get("visibility_group_id"):
            issue["visibility"] = {"$type": "LimitedVisibility",
                                   "permittedGroups": [{"id": y["visibility_group_id"]}]}
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
    """The reason for an active freeze period, or None."""
    local_day = now.astimezone(ZoneInfo(tz_name)).date()
    for f in freeze:
        start = datetime.fromisoformat(f["from"]).date()
        end = datetime.fromisoformat(f["to"]).date()
        if start <= local_day <= end:
            return f"frozen until {f['to']}: {f.get('reason', 'no reason given')}"
    return None


def soak_satisfied(state: State, sha: str, soak_hours: float) -> tuple[bool, str]:
    seen = state.dev_seen.get(sha)
    if not seen:
        return False, "never observed running on dev"
    hours = hours_between(parse_iso(seen["first"]), parse_iso(seen["last"]))
    if hours < soak_hours:
        return False, f"observed on dev for {hours:.1f}h, needs {soak_hours}h"
    return True, ""


# ---------------------------------------------------------------------------------------------
# the rollout itself
# ---------------------------------------------------------------------------------------------

class Rollout:
    """One attempt to move production from `previous` to `sha`, recorded step by step."""

    def __init__(self, cfg: dict, host: Host, gh: GitHub, notify: Notifier, smoke, sha: str, run: dict):
        self.cfg, self.host, self.gh, self.notify, self.smoke = cfg, host, gh, notify, smoke
        self.sha, self.run = sha, run
        self.previous = host.current_sha()
        self.started = now_utc()
        self.steps: list[dict] = []
        self.dump: str | None = None
        self.migrates: bool | None = None
        self.touched_production = False
        self.outcome = "unknown"
        self.detail = ""
        rollouts = cfg["state_dir"] / "rollouts"
        rollouts.mkdir(parents=True, exist_ok=True)
        stamp = self.started.strftime("%Y-%m-%dT%H%M%SZ")
        self.record_path = rollouts / f"{stamp}-{sha[:8]}.json"
        log.sink = rollouts / f"{stamp}-{sha[:8]}.log"

    # -- bookkeeping ----------------------------------------------------------------------------
    def step(self, name: str, fn, *args, **kw):
        log(f"==> {name}")
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
                "dump": self.dump, "run_url": self.run.get("html_url"), "steps": self.steps,
                "touched_production": self.touched_production}

    def save(self) -> None:
        write_atomic(self.record_path, json.dumps(self.summary(), indent=2) + "\n")

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
            raise RolloutError(f"only {free:.1f} GB free under {host.root}, need {gates['min_free_gb']}")
        host.db("ping")
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
        ok, text = self.run_smoke(expect_sha=self.previous, attempts=1)
        if not ok:
            raise RolloutError("the smoke suite fails against the CURRENT release, so a failure after the "
                               "deploy could not be attributed. Not deploying. Details:\n" + text)
        return "current release passes the full suite"

    def fetch(self) -> str:
        self.host.materialise(self.gh, self.run, self.sha)
        return "core.jar and web/ in place"

    def take_dump(self) -> str:
        self.dump = self.host.dump_database()
        return self.dump

    def rehearse(self) -> str:
        """Boot the new jar against a copy of production's data, with every integration disabled."""
        cfg, host = self.cfg, self.host
        r = cfg["rehearsal"]
        if not r["enabled"]:
            return "disabled by configuration"
        if not self.dump:
            raise RolloutError("no dump to rehearse against")
        if not host.port_free(int(r["port"])):
            raise RolloutError(f"port {r['port']} is in use — a previous rehearsal may still be running")
        work = cfg["state_dir"] / "rehearsal"
        work.mkdir(parents=True, exist_ok=True)
        before = int(host.db("changelog-count"))
        host.db("rehearsal-create", self.dump, timeout=3600)
        try:
            config_path = host.db("rehearsal-config", str(r["port"]))
            assert_rehearsal_config_is_harmless(Path(config_path).read_text(), int(r["port"]))
            argv = [cfg["java"], *str(r["java_opts"]).split(),
                    "-jar", str(host.release_dir(self.sha) / "core.jar"),
                    f"--spring.config.location=file:{config_path}"]
            proc = host.popen(argv, cwd=work, log_path=work / "core.log",
                              env={"EASY_LOG_PATH": str(work / "easy.log")})
            try:
                deadline = time.monotonic() + int(r["timeout_s"])
                while time.monotonic() < deadline:
                    if proc.poll() is not None:
                        raise RolloutError(f"the new release exited with status {proc.returncode} during the "
                                           f"rehearsal — migration or configuration failure. Last log lines:\n"
                                           + tail_of(work / "core.log"))
                    if host.http_status(f"http://127.0.0.1:{r['port']}/v2/") in (401, 403, 200):
                        break
                    sleep(3)
                else:
                    raise RolloutError(f"the new release did not answer within {r['timeout_s']}s in the rehearsal:\n"
                                       + tail_of(work / "core.log"))
            finally:
                proc.terminate()
                try:
                    proc.wait(60)
                except subprocess.TimeoutExpired:
                    proc.kill()
            after = int(host.db("changelog-count", "rehearsal"))
        finally:
            try:
                host.db("rehearsal-drop")
            except RolloutError as e:
                log(f"  warning: {e}")
        self.migrates = after > before
        return (f"new release booted against a copy of production; {after - before} changeset(s) applied"
                if self.migrates else "new release booted against a copy of production; no schema change")

    def activate(self) -> str:
        self.touched_production = True
        self.host.activate(self.sha)
        self.host.restart_core()
        return "symlinks flipped, core restarted"

    def health(self, sha: str) -> str:
        cfg, host = self.cfg, self.host
        timeout = cfg["health_timeout_migrating_s"] if self.migrates else cfg["health_timeout_s"]
        if not host.wait_healthy(cfg["health_url"], int(timeout)):
            raise RolloutError(f"core did not answer at {cfg['health_url']} within {timeout}s after installing "
                               f"{short(sha)}. Log:\n{host.core_log_tail()}")
        if not host.core_active():
            raise RolloutError(f"{cfg['health_url']} answers but {cfg['service']} is not active")
        return "401 from the public API, unit active"

    def run_smoke(self, expect_sha: str, attempts: int | None = None) -> tuple[bool, str]:
        s = self.cfg["smoke"]
        attempts = attempts or int(s["attempts"])
        text = ""
        for i in range(1, attempts + 1):
            report = self.smoke(expect_sha=expect_sha)
            text = report.text()
            if report.ok:
                return True, text
            if report.not_configured:
                # An unconfigured suite is a failed gate wherever the suite is required. Where it is
                # not, say so in the record rather than pretend a check happened.
                if not s.get("required", True):
                    return True, "smoke suite not configured, and not required on this environment"
                break
            if i < attempts:
                log(f"  smoke attempt {i}/{attempts} failed; retrying in {s['retry_delay_s']}s")
                sleep(int(s["retry_delay_s"]))
        return False, text

    def post_smoke(self) -> str:
        ok, text = self.run_smoke(expect_sha=self.sha)
        if not ok:
            raise RolloutError("smoke suite failed against the new release:\n" + text)
        return "every check passed"

    def finish(self) -> str:
        self.host.mark_current(self.sha)
        self.host.prune({self.sha, self.previous})
        return f"{short(self.sha)} is live"

    # -- rollback -------------------------------------------------------------------------------
    def rollback(self, why: str) -> None:
        host, cfg = self.host, self.cfg
        log(f"!!! rolling back to {short(self.previous)}: {why[:200]}")
        try:
            self.step("rollback: reactivate previous", self._reactivate_previous)
            self.step("rollback: health", self.health, self.previous)
        except RolloutError as e:
            policy = cfg["rollback"]["restore_db"]
            if self.dump and (policy == "always" or (policy == "auto" and self.migrates is not False)):
                log(f"!!! previous release does not come up; restoring the database from {self.dump} (policy {policy})")
                try:
                    self.step("rollback: restore database", lambda: host.db("restore", self.dump, timeout=7200))
                    self.step("rollback: health after restore", self.health, self.previous)
                except RolloutError as e2:
                    self.outcome, self.detail = "DOWN", f"rollback failed; production is not answering: {e2}"
                    return
            else:
                self.outcome = "DOWN"
                self.detail = (f"previous release does not come up and the database was not restored "
                               f"(policy {policy}, migrates={self.migrates}): {e}")
                return
        try:
            self.step("rollback: smoke", self._smoke_previous)
            self.outcome, self.detail = "rolled-back", f"production is back on {short(self.previous)} and passes smoke. {why}"
        except RolloutError as e:
            self.outcome, self.detail = "rolled-back-degraded", (f"production is back on {short(self.previous)} and "
                                                                 f"answers, but the smoke suite fails: {e}")

    def _smoke_previous(self) -> str:
        ok, text = self.run_smoke(expect_sha=self.previous)
        if not ok:
            raise RolloutError(text)
        return "every check passes on the previous release"

    def _reactivate_previous(self) -> str:
        self.host.activate(self.previous)
        self.host.restart_core()
        return f"symlinks back to {short(self.previous)}"

    # -- the whole thing ------------------------------------------------------------------------
    def execute(self) -> dict:
        log(f"### rollout {short(self.previous)} -> {short(self.sha)} — {self.run.get('html_url')}")
        try:
            self.step("preflight", self.preflight)
            self.step("baseline smoke", self.baseline_smoke)
            self.step("fetch artifacts", self.fetch)
            self.step("database dump", self.take_dump)
            self.step("rehearsal", self.rehearse)
            self.step("activate", self.activate)
            self.step("health", self.health, self.sha)
            self.step("smoke", self.post_smoke)
            self.step("finish", self.finish)
            self.outcome = "deployed"
        except RolloutError as e:
            if self.touched_production:
                self.rollback(str(e))
            else:
                self.outcome, self.detail = "aborted", f"production untouched: {e}"
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
                 clock=now_utc):
        self.cfg, self.host, self.gh, self.notify, self.smoke, self.state = cfg, host, gh, notify, smoke, state
        self.clock = clock

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
        return self.pause_file.read_text().strip() if self.pause_file.exists() else None

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
        now = iso(self.clock())
        entry = self.state.dev_seen.setdefault(sha, {"first": now, "last": now})
        entry["last"] = now
        # Forget sightings older than a month; the file should not grow forever.
        cutoff = self.clock() - timedelta(days=31)
        for k in [k for k, v in self.state.dev_seen.items() if parse_iso(v["last"]) < cutoff]:
            del self.state.dev_seen[k]

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
        return reasons

    def override_for(self, sha: str) -> bool:
        """`deploy-now` holding this sha or `head` skips the scheduling gates — never the checks."""
        if not self.deploy_now_file.exists():
            return False
        want = self.deploy_now_file.read_text().split()[0] if self.deploy_now_file.read_text().split() else ""
        return want == "head" or (len(want) >= 7 and sha.startswith(want))

    # -- one tick -------------------------------------------------------------------------------
    def tick(self) -> str:
        now = self.clock()
        state = self.state
        try:
            self.record_dev_sighting()
        except Exception as e:  # noqa: BLE001
            log(f"could not record dev sighting: {e}")

        reason = self.paused_reason()
        if reason:
            state.save()
            return f"paused: {reason}"

        head = self.gh.branch_head(self.cfg["branch"])
        current = self.host.current_sha()
        if head == current:
            state.candidates.clear()
            state.clear_notice("stuck")
            state.save()
            return "steady"

        cand = state.candidates.setdefault(head, {"first_seen": iso(now)})
        if head in state.failed:
            cand["reasons"] = [f"failed on {state.failed[head]['at']}: {state.failed[head]['why'][:200]} — "
                               f"`easy-rollout forget {head[:8]}` to allow a retry"]
            state.save()
            return "failed-candidate"

        run = self.gh.green_run_for(head)
        if run is None:
            cand["reasons"] = ["no green CI run yet"]
            self.maybe_alarm_stuck(head, cand, now)
            state.save()
            return "waiting-for-ci"

        override = self.override_for(head)
        reasons = [] if override else self.gate_reasons(head, run, now)
        if reasons:
            cand["reasons"] = reasons
            self.maybe_alarm_stuck(head, cand, now)
            state.save()
            return "gated"

        cand["reasons"] = []
        state.save()
        if override:
            log(f"deploy-now override for {short(head)} — scheduling gates skipped, checks are not")
            self.deploy_now_file.unlink(missing_ok=True)
        return self.rollout(head, run)

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
            r = Rollout(cfg, self.host, self.gh, self.notify, self.smoke, sha, run)
            summary = r.execute()
        now = self.clock()
        self.state.record_history({k: summary[k] for k in ("sha", "previous", "started", "outcome", "detail")})
        outcome = summary["outcome"]
        if outcome == "deployed":
            self.state.data["last_success_at"] = iso(now)
            self.state.candidates.clear()
            commits = self.gh.commits_between(summary["previous"], sha) if summary["previous"] else []
            self.notify(INFO, f"deployed {short(sha)}",
                        r.report() + "\n\nCommits:\n  " + "\n  ".join(commits or ["(none listed)"]))
        elif outcome == "aborted":
            # Production was never touched. Deterministic failures (a migration that does not apply,
            # a missing config key, a smoke suite that fails against the CURRENT release) will not
            # fix themselves, so the sha is parked until a person looks — but nothing is paused,
            # because a *new* commit that fixes the problem should deploy on its own.
            self.state.failed[sha] = {"at": iso(now), "why": summary["detail"]}
            severity = CRITICAL if "CURRENT release" in summary["detail"] else WARN
            self.notify(severity, f"rollout of {short(sha)} aborted before touching production",
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

def build(cfg: dict, smoke_factory=None):
    token = read_secret_file(cfg["token_file"])
    if token is None:
        raise RolloutError(f"{cfg['token_file']} is absent or still a placeholder — nothing can be resolved "
                           f"until a GitHub token with Actions:read is in it")
    host = Host(cfg)
    gh = GitHub(cfg["repo"], cfg["workflow"], token)
    notify = Notifier(cfg, cfg.get("environment", cfg["branch"]))
    state_dir: Path = cfg["state_dir"]
    state_dir.mkdir(parents=True, exist_ok=True)
    state = State(state_dir / "state.json")
    smoke = (smoke_factory or default_smoke_factory)(cfg)
    return Controller(cfg, host, gh, notify, smoke, state)


def default_smoke_factory(cfg: dict):
    """The end-to-end suite lives beside this file; imported lazily so `status` needs nothing."""
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import easy_smoke  # noqa: E402

    def run(expect_sha: str):
        return easy_smoke.run(cfg["smoke"], expect_sha=expect_sha, log=log)
    return run


def cmd_status(ctrl: Controller) -> int:
    s = ctrl.state.data
    print(f"branch        {ctrl.cfg['branch']}")
    print(f"current-sha   {ctrl.host.current_sha() or 'nothing'}")
    print(f"paused        {ctrl.paused_reason() or 'no'}")
    if ctrl.deploy_now_file.exists():
        print(f"deploy-now    {ctrl.deploy_now_file.read_text().strip()}")
    print(f"last success  {s.get('last_success_at') or 'never'}")
    for sha, c in s["candidates"].items():
        print(f"candidate     {sha[:8]} since {c['first_seen']}")
        for r in c.get("reasons", []):
            print(f"                - {r}")
    for sha, f in s["failed"].items():
        print(f"failed        {sha[:8]} at {f['at']}: {f['why'][:160]}")
    if s["dev_seen"]:
        print("seen on dev   " + ", ".join(f"{k[:8]} ({v['first'][:16]}..{v['last'][:16]})"
                                          for k, v in sorted(s["dev_seen"].items(), key=lambda kv: kv[1]["last"])[-5:]))
    for h in s["history"][-5:]:
        print(f"history       {h['started']} {h['previous'][:8] if h['previous'] else '-':>8} -> {h['sha'][:8]} {h['outcome']}: {h['detail'][:120]}")
    return 0


def cmd_check(ctrl: Controller) -> int:
    """What the next tick would decide, without doing it."""
    now = ctrl.clock()
    head = ctrl.gh.branch_head(ctrl.cfg["branch"])
    current = ctrl.host.current_sha()
    print(f"branch head   {head}")
    print(f"current-sha   {current or 'nothing'}")
    if head == current:
        print("decision      steady — nothing to do")
        return 0
    if ctrl.paused_reason():
        print(f"decision      paused: {ctrl.paused_reason()}")
        return 0
    if head in ctrl.state.failed:
        print(f"decision      {head[:8]} failed before and will not be retried automatically")
        return 0
    run = ctrl.gh.green_run_for(head)
    if run is None:
        print("decision      waiting for a green CI run")
        return 0
    print(f"ci run        {run['html_url']}")
    reasons = ctrl.gate_reasons(head, run, now)
    if ctrl.override_for(head):
        print("decision      deploy-now override present — would roll out on the next tick")
    elif reasons:
        print("decision      gated:")
        for r in reasons:
            print(f"                - {r}")
    else:
        print("decision      would roll out on the next tick")
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="easy-rollout", description=__doc__.split("\n\n")[0])
    p.add_argument("--config", default="/etc/easy/rollout.json")
    sub = p.add_subparsers(dest="cmd")
    sub.add_parser("tick", help="what the timer runs: decide, and roll out if everything allows it")
    sub.add_parser("status", help="state, candidates and why they wait, recent history")
    sub.add_parser("check", help="what the next tick would decide, without acting")
    sp = sub.add_parser("pause", help="stop automatic rollouts until `resume`")
    sp.add_argument("reason", nargs="*")
    sub.add_parser("resume", help="allow automatic rollouts again")
    sp = sub.add_parser("deploy-now", help="skip the scheduling gates for one commit (never the checks)")
    sp.add_argument("sha", help="a commit id prefix (7+ chars), or `head`")
    sp = sub.add_parser("forget", help="allow a failed commit to be attempted again")
    sp.add_argument("sha")
    sp = sub.add_parser("smoke", help="run the end-to-end suite now, against whatever is live")
    sp.add_argument("--expect-sha", default=None)
    sp = sub.add_parser("rollback", help="put a release that is on disk back, by hand")
    sp.add_argument("sha")
    sp = sub.add_parser("notify-failure", help="used by systemd OnFailure=: report that a unit failed")
    sp.add_argument("unit")
    args = p.parse_args(argv)

    cfg = load_config(Path(args.config))
    cmd = args.cmd or "tick"

    # Commands that only write a file need no token and no network.
    state_dir: Path = cfg["state_dir"]
    if cmd == "pause":
        state_dir.mkdir(parents=True, exist_ok=True)
        write_atomic(state_dir / "pause", f"{iso(now_utc())} {' '.join(args.reason) or 'paused by hand'}\n", 0o664)
        print("paused")
        return 0
    if cmd == "resume":
        (state_dir / "pause").unlink(missing_ok=True)
        print("resumed — the next tick may roll out")
        return 0
    if cmd == "deploy-now":
        write_atomic(state_dir / "deploy-now", args.sha + "\n", 0o664)
        print(f"the next tick will roll out {args.sha} if CI is green and every check passes")
        return 0
    if cmd == "forget":
        st = State(state_dir / "state.json")
        for k in [k for k in st.failed if k.startswith(args.sha)]:
            del st.failed[k]
            print(f"forgot {k}")
        st.save()
        return 0
    if cmd == "notify-failure":
        Notifier(cfg, cfg.get("environment", cfg["branch"]))(
            CRITICAL, f"{args.unit} failed",
            f"systemd reports {args.unit} failed. This is the rollout machinery itself, not a release: "
            f"`journalctl -u {args.unit} -n 100` on the host.")
        return 0

    ctrl = build(cfg)
    if cmd == "status":
        return cmd_status(ctrl)
    if cmd == "check":
        return cmd_check(ctrl)
    if cmd == "smoke":
        report = ctrl.smoke(expect_sha=args.expect_sha or ctrl.host.current_sha())
        print(report.text())
        return 0 if report.ok else 1
    if cmd == "rollback":
        target = next((d.name for d in (cfg["root"] / "releases").glob("*/") if d.name.startswith(args.sha)), None)
        if not target or not ctrl.host.release_complete(target):
            print(f"no complete release matching {args.sha} under {cfg['root']}/releases", file=sys.stderr)
            return 1
        ctrl.pause(f"manual rollback to {target[:8]}")
        ctrl.host.activate(target)
        ctrl.host.restart_core()
        ok = ctrl.host.wait_healthy(cfg["health_url"], int(cfg["health_timeout_s"]))
        if ok:
            ctrl.host.mark_current(target)
        print(f"{'healthy' if ok else 'NOT ANSWERING'} on {target[:8]}; automatic rollouts paused")
        return 0 if ok else 1
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
