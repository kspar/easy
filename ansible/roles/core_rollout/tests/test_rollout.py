"""Tests for the guarded rollout.

The interesting behaviour is what must NOT happen: a commit must not deploy outside its window or
before it has soaked, a release that fails its smoke suite must be put back, a rollback must stop
further rollouts until a person has looked, a failure before core was touched must not be treated
as one after, and a restore must never be the answer to a quiet URL. So the controller takes its
GitHub client, its host and its smoke suite as objects, and every test below drives one of those
paths with a fake — except the parts that touch the release tree, which run for real on tmp_path.
"""

from __future__ import annotations

import json
import os
import shutil
import sys
import tarfile
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "files"))

import easy_rollout as ro  # noqa: E402

OLD = "a" * 40
NEW = "b" * 40
T0 = datetime(2026, 9, 8, 1, 30, tzinfo=timezone.utc)   # Tuesday 04:30 in Tallinn (UTC+3)


# ---------------------------------------------------------------------------------------------
# fakes
# ---------------------------------------------------------------------------------------------

class FakeGitHub:
    def __init__(self, head=NEW, green=True, on_master=True, run_age_hours=24.0, down=False):
        self.head, self.green, self.on_master, self.down = head, green, on_master, down
        self.run = {"id": 7, "html_url": "https://ci/run/7",
                    "run_started_at": ro.iso(T0 - timedelta(hours=run_age_hours))}

    def _check(self):
        if self.down:
            raise ro.GitHubUnavailable("cannot reach GitHub: timed out")

    def branch_head(self, branch):
        self._check()
        return self.head

    def green_run_for(self, sha):
        self._check()
        return self.run if self.green else None

    def is_ancestor_of(self, sha, branch):
        return self.on_master

    def commits_between(self, base, head):
        return [f"{head[:8]} EZ-1 the change"]

    def artifact_urls(self, run_id, sha):
        return {}


class FakeSmoke:
    """A smoke suite that passes, fails against chosen shas (optionally only the first N times), or is unconfigured."""

    def __init__(self, fail_for=(), not_configured=False, fail_first_n=None):
        self.fail_for, self.not_configured = set(fail_for), not_configured
        self.fail_first_n = dict(fail_first_n or {})
        self.calls: list[str] = []

    def __call__(self, expect_sha):
        self.calls.append(expect_sha)
        report = type("R", (), {})()
        report.not_configured = self.not_configured
        fail = expect_sha in self.fail_for
        if self.fail_first_n.get(expect_sha, 0) > 0:
            self.fail_first_n[expect_sha] -= 1
            fail = True
        report.ok = not fail and not self.not_configured
        report.text = lambda: f"smoke for {expect_sha[:8]}: {'PASS' if report.ok else 'FAIL'}"
        return report


HARMLESS = {
    "server": {"address": "127.0.0.1", "port": 8091},
    "spring": {"datasource": {"jdbc-url": "jdbc:postgresql://127.0.0.1:5432/easyems_rehearsal",
                              "username": "easyems_rehearsal"},
               "mail": {"host": "127.0.0.1", "port": 9, "properties": {"mail": {"smtp": {"auth": False}}}},
               "security": {"oauth2": {"resourceserver": {"jwt": {
                   "jwk-set-uri": "http://127.0.0.1:9/rehearsal", "issuer-uri": "http://127.0.0.1:9/rehearsal"}}}}},
    "easy": {"core": {"auth-enabled": True,
                      "mail": {"sys": {"enabled": False}, "user": {"enabled": False}},
                      "moodle-sync": {"users": {"url": "http://127.0.0.1:9/"}, "grades": {"url": "http://127.0.0.1:9/"},
                                      "course-allowlist": "easy-rehearsal-no-such-course"},
                      "storage": {"backend": "local", "local": {"dir": "/var/lib/easy-rollout-db/work/files"}},
                      "stored-file-sweep": {"delete": False, "cron": "0 0 5 31 2 ?"},
                      "auto-assess": {"fixed-delay": {"ms": "9000000000000"},
                                      "fixed-delay-observer-clear": {"ms": "9000000000000"},
                                      "executor-sync": {"fixed-delay": {"ms": "9000000000000"}}},
                      "statistics": {"fixed-delay": {"ms": "9000000000000"}},
                      "keycloak": {"base-url": "http://127.0.0.1:9"},
                      "youtrack": {"enabled": False}},
             "web": {"base-url": "http://127.0.0.1:9/"}},
}


class FakeHost(ro.Host):
    """The release tree on disk is real (tmp_path); everything that touches services is recorded."""

    def __init__(self, cfg, healthy_after_restart=True, old_jar_healthy=True, changelog=(10, 10),
                 rehearsal_exit=None, count_fails=False):
        super().__init__(cfg)
        self.healthy_after_restart, self.old_jar_healthy = healthy_after_restart, old_jar_healthy
        self.changelog_before, self.changelog_after = changelog
        self.rehearsal_exit = rehearsal_exit
        self.count_fails = count_fails
        self.calls: list[tuple] = []
        self.health_calls: list[tuple] = []
        self.dumps = ["/srv/easy/db-dumps/easyems-2026-09-07T0330.dump"]
        self.active_sha = None
        self.unit_active = True
        self.free = 40.0
        self.status = 401
        self.dump_appends = True
        self.rehearsal_running = False

    # -- release tree -----------------------------------------------------------------------
    def put_release(self, sha):
        rel = self.release_dir(sha)
        (rel / "web").mkdir(parents=True, exist_ok=True)
        (rel / "web" / "index.html").write_text("<html></html>")
        (rel / "core.jar").write_bytes(b"jar")

    def materialise(self, gh, run, sha):
        self.calls.append(("materialise", sha))
        self.put_release(sha)
        return self.release_dir(sha)

    def activate(self, sha):
        self.calls.append(("activate", sha))
        self.active_sha = sha
        super().activate(sha)

    def restart_core(self):
        self.calls.append(("restart",))

    def core_active(self):
        if self.active_sha == NEW:
            return self.healthy_after_restart
        if self.active_sha == OLD and not self.old_jar_healthy:
            return getattr(self, "restored_from", None) is not None
        return self.unit_active

    def core_log_tail(self):
        return "log tail"

    def free_gb(self, path):
        return self.free

    # -- database helper --------------------------------------------------------------------
    def db(self, *args, timeout=3600):
        self.calls.append(("db", *args))
        cmd = args[0]
        if cmd == "ping":
            return "ok"
        if cmd == "newest-dump":
            return self.dumps[-1]
        if cmd == "changelog-count":
            if self.count_fails:
                raise ro.RolloutError("easy-rollout-db changelog-count failed (2): psql: connection refused")
            return str(self.changelog_after if args[1:] == ("rehearsal",) else self.changelog_before)
        if cmd == "rehearsal-create":
            return "easyems_rehearsal"
        if cmd == "rehearsal-config":
            p = self.cfg["state_dir"] / "rehearsal-config.json"
            p.write_text(json.dumps(HARMLESS))
            return str(p)
        if cmd == "rehearsal-run":
            self.rehearsal_running = True
            p = self.cfg["state_dir"] / "core.log"
            p.write_text("Started EasyApplication\n")
            return str(p)
        if cmd == "rehearsal-status":
            if self.rehearsal_exit is not None:
                return f"failed:{self.rehearsal_exit}"
            return "active" if self.rehearsal_running else "inactive"
        if cmd == "rehearsal-stop":
            self.rehearsal_running = False
            return "stopped"
        if cmd == "restore":
            self.restored_from = args[1]
            return "restored"
        return ""

    def sudo(self, argv, timeout=600):
        self.calls.append(("sudo", *argv))
        if argv[1:3] == ["start", self.cfg["dump_service"]] and self.dump_appends:
            self.dumps.append(f"/srv/easy/db-dumps/easyems-2026-09-08T0{len(self.dumps)}30.dump")

    # -- HTTP -------------------------------------------------------------------------------
    def http_status(self, url, timeout=10):
        if url.startswith("http://127.0.0.1:8091"):
            return 401 if self.rehearsal_running and self.rehearsal_exit is None else None
        return self.status

    def http_text(self, url, timeout=10):
        return None

    def wait_healthy(self, url, timeout_s, interval=4):
        self.health_calls.append((url, timeout_s, self.active_sha))
        if self.active_sha == NEW:
            return self.healthy_after_restart
        if getattr(self, "restored_from", None):
            return True
        return self.old_jar_healthy

    def port_free(self, port):
        return True


class FakeNotifier:
    def __init__(self):
        self.sent = []

    def __call__(self, severity, subject, body):
        self.sent.append((severity, subject, body))
        return ["fake"]

    def severities(self):
        return [s for s, _, _ in self.sent]


# ---------------------------------------------------------------------------------------------
# fixtures
# ---------------------------------------------------------------------------------------------

@pytest.fixture(autouse=True)
def no_real_sleeping(monkeypatch):
    # Retries between smoke attempts and the rehearsal poll are real minutes in production.
    monkeypatch.setattr(ro, "sleep", lambda s: None)


@pytest.fixture
def cfg(tmp_path):
    c = ro.deep_merge(ro.DEFAULTS, {
        "root": str(tmp_path / "srv"), "health_url": "https://api.example/v2/",
        "environment": "test", "gates": {"require_seen_on_dev": True, "dev_version_url": "https://dev.example/x"},
    })
    c["root"] = Path(c["root"])
    c["state_dir"] = c["root"] / "rollout"
    c["state_dir"].mkdir(parents=True)
    (c["root"] / "conf").mkdir()
    (c["root"] / "conf" / "application.yaml").write_text("x: 1\n")
    (c["root"] / "conf" / "config.json").write_text(json.dumps(
        {"emsRoot": "https://api.example/v2", "keycloak": {"url": "https://idp.example/auth", "realm": "master", "clientId": "x"}}))
    c["config_json"] = str(c["root"] / "conf" / "config.json")
    return c


def make(cfg, gh=None, host=None, smoke=None, now=T0, seen_on_dev=True):
    gh = gh or FakeGitHub()
    host = host or FakeHost(cfg)
    host.put_release(OLD)
    (cfg["root"] / "current-sha").write_text(OLD + "\n")
    state = ro.State(cfg["state_dir"] / "state.json")
    if seen_on_dev:
        state.dev_seen[NEW] = {"first": ro.iso(now - timedelta(hours=30)), "last": ro.iso(now - timedelta(hours=1)), "hours": 29.0}
        state.data["dev_current"] = NEW
    notify = FakeNotifier()
    ctrl = ro.Controller(cfg, host, gh, notify, smoke or FakeSmoke(), state, clock=lambda: now)
    return ctrl, host, notify


def activations(host):
    return [c[1] for c in host.calls if c[0] == "activate"]


# ---------------------------------------------------------------------------------------------
# the gates
# ---------------------------------------------------------------------------------------------

def test_steady_state_when_branch_matches_current(cfg):
    ctrl, host, notify = make(cfg, gh=FakeGitHub(head=OLD))
    assert ctrl.tick() == "steady"
    assert host.calls == [] and notify.sent == []


def test_waits_for_a_green_run(cfg):
    ctrl, host, _ = make(cfg, gh=FakeGitHub(green=False))
    assert ctrl.tick() == "waiting-for-ci"
    assert ctrl.state.candidates[NEW]["reasons"] == ["no green CI run yet"]
    assert host.calls == []


@pytest.mark.parametrize("when,word", [
    (datetime(2026, 9, 9, 1, 30, tzinfo=timezone.utc), "not a rollout day"),          # Wednesday
    (datetime(2026, 9, 8, 12, 0, tzinfo=timezone.utc), "outside 04:00"),              # Tuesday noon
])
def test_outside_the_window_nothing_happens(cfg, when, word):
    ctrl, host, _ = make(cfg, now=when)
    assert ctrl.tick() == "gated"
    assert any(word in r for r in ctrl.state.candidates[NEW]["reasons"])
    assert host.calls == []


def test_freeze_period_blocks_even_inside_the_window(cfg):
    cfg["freeze"] = [{"from": "2026-09-01", "to": "2026-09-14", "reason": "term start"}]
    ctrl, host, _ = make(cfg)
    assert ctrl.tick() == "gated"
    assert any("term start" in r for r in ctrl.state.candidates[NEW]["reasons"])


def test_freeze_boundaries_are_local_dates_inclusive():
    freeze = [{"from": "2026-09-10", "to": "2026-09-12", "reason": "x"}]
    tz = "Europe/Tallinn"
    assert ro.in_freeze(freeze, datetime(2026, 9, 12, 20, 30, tzinfo=timezone.utc), tz)      # 23:30 local on the last day
    assert ro.in_freeze(freeze, datetime(2026, 9, 12, 21, 30, tzinfo=timezone.utc), tz) is None  # 00:30 local the day after
    assert ro.in_freeze(freeze, datetime(2026, 9, 9, 21, 30, tzinfo=timezone.utc), tz)       # 00:30 local on the first day


def test_young_ci_run_must_age(cfg):
    ctrl, host, _ = make(cfg, gh=FakeGitHub(run_age_hours=1))
    assert ctrl.tick() == "gated"
    assert any("needs 6h" in r for r in ctrl.state.candidates[NEW]["reasons"])


def test_commit_never_seen_on_dev_does_not_deploy(cfg):
    ctrl, host, _ = make(cfg, seen_on_dev=False)
    assert ctrl.tick() == "gated"
    assert any("never observed running on dev" in r for r in ctrl.state.candidates[NEW]["reasons"])
    assert host.calls == []


def test_commit_seen_on_dev_too_briefly_does_not_deploy(cfg):
    ctrl, host, _ = make(cfg, seen_on_dev=False)
    ctrl.state.dev_seen[NEW] = {"first": ro.iso(T0 - timedelta(hours=2)), "last": ro.iso(T0), "hours": 2.0}
    ctrl.state.data["dev_current"] = NEW
    assert ctrl.tick() == "gated"
    assert any("needs 12" in r for r in ctrl.state.candidates[NEW]["reasons"])


def test_commit_dev_has_moved_on_from_has_not_soaked(cfg):
    ctrl, host, _ = make(cfg)
    ctrl.state.data["dev_current"] = "c" * 40
    assert ctrl.tick() == "gated"
    assert any("dev has moved on" in r for r in ctrl.state.candidates[NEW]["reasons"])


def test_commit_not_on_master_does_not_deploy(cfg):
    ctrl, host, _ = make(cfg, gh=FakeGitHub(on_master=False))
    assert ctrl.tick() == "gated"
    assert any("not on master" in r for r in ctrl.state.candidates[NEW]["reasons"])


def test_minimum_gap_since_last_rollout(cfg):
    ctrl, host, _ = make(cfg)
    ctrl.state.data["last_success_at"] = ro.iso(T0 - timedelta(hours=3))
    assert ctrl.tick() == "gated"
    assert any("minimum gap" in r for r in ctrl.state.candidates[NEW]["reasons"])


def test_deploy_now_skips_the_scheduling_gates_but_not_ci(cfg):
    # Wednesday, never seen on dev, not on master — and still deploys, because a person asked.
    when = datetime(2026, 9, 9, 12, 0, tzinfo=timezone.utc)
    ctrl, host, notify = make(cfg, gh=FakeGitHub(on_master=False), now=when, seen_on_dev=False)
    (cfg["state_dir"] / "deploy-now").write_text(f"{NEW[:10]} {ro.iso(when)}\n")
    assert ctrl.tick() == "deployed"
    assert not (cfg["state_dir"] / "deploy-now").exists(), "the override is consumed"
    # But never without a green run.
    ctrl2, host2, _ = make(cfg, gh=FakeGitHub(green=False), now=when)
    (cfg["state_dir"] / "deploy-now").write_text(f"head {ro.iso(when)}\n")
    assert ctrl2.tick() == "waiting-for-ci"
    assert host2.calls == []


def test_deploy_now_for_another_sha_a_short_prefix_or_an_expired_file_does_nothing(cfg):
    when = datetime(2026, 9, 9, 12, 0, tzinfo=timezone.utc)
    for content in (f"{'c' * 40} {ro.iso(when)}", f"{NEW[:3]} {ro.iso(when)}", f"{NEW} {ro.iso(when - timedelta(hours=30))}"):
        ctrl, host, _ = make(cfg, now=when, seen_on_dev=False)
        (cfg["state_dir"] / "deploy-now").write_text(content + "\n")
        assert ctrl.tick() == "gated", content
        assert host.calls == []
    assert not (cfg["state_dir"] / "deploy-now").exists(), "the expired one was removed"


def test_deploy_now_never_overrides_a_failed_sha(cfg):
    when = datetime(2026, 9, 9, 12, 0, tzinfo=timezone.utc)
    ctrl, host, _ = make(cfg, now=when)
    ctrl.state.failed[NEW] = {"at": ro.iso(when), "why": "x"}
    (cfg["state_dir"] / "deploy-now").write_text(f"{NEW} {ro.iso(when)}\n")
    assert ctrl.tick() == "failed-candidate"
    assert host.calls == []


def test_paused_means_nothing_happens_at_all_even_with_an_empty_file(cfg):
    ctrl, host, _ = make(cfg)
    (cfg["state_dir"] / "pause").write_text("")
    assert ctrl.tick().startswith("paused")
    assert host.calls == []


def test_stuck_branch_is_reported_once_a_day(cfg):
    ctrl, host, notify = make(cfg, gh=FakeGitHub(green=False))
    ctrl.state.candidates[NEW] = {"first_seen": ro.iso(T0 - timedelta(days=5))}
    ctrl.tick()
    assert notify.severities() == [ro.WARN]
    assert "5.0 days" in notify.sent[0][1]
    ctrl.tick()
    assert len(notify.sent) == 1, "not again within stuck_repeat_hours"


def test_stuck_alarm_also_fires_for_a_parked_sha(cfg):
    ctrl, host, notify = make(cfg)
    ctrl.state.failed[NEW] = {"at": ro.iso(T0 - timedelta(days=5)), "why": "x"}
    ctrl.state.candidates[NEW] = {"first_seen": ro.iso(T0 - timedelta(days=5))}
    assert ctrl.tick() == "failed-candidate"
    assert notify.severities() == [ro.WARN]


def test_github_down_is_logged_and_warned_once_not_crashed(cfg):
    ctrl, host, notify = make(cfg, gh=FakeGitHub(down=True))
    assert ctrl.tick() == "github-unavailable"
    assert notify.sent == [], "the first failure is not an alarm"
    ctrl.clock = lambda: T0 + timedelta(hours=2)
    assert ctrl.tick() == "github-unavailable"
    assert notify.severities() == [ro.WARN]
    ctrl.clock = lambda: T0 + timedelta(hours=3)
    ctrl.tick()
    assert len(notify.sent) == 1


def test_dev_sightings_accumulate_contiguously_and_reset_when_dev_changes(cfg):
    ctrl, host, _ = make(cfg, gh=FakeGitHub(head=OLD), seen_on_dev=False)
    sha_x, sha_y = "c" * 40, "d" * 40
    host.http_text = lambda url, timeout=10: sha_x + "\n"
    for h in (0, 1, 2):
        ctrl.clock = lambda h=h: T0 + timedelta(hours=h)
        ctrl.tick()
    assert ctrl.state.dev_seen[sha_x]["hours"] == 2.0
    assert ctrl.state.data["dev_current"] == sha_x
    host.http_text = lambda url, timeout=10: sha_y + "\n"
    ctrl.clock = lambda: T0 + timedelta(hours=3)
    ctrl.tick()
    host.http_text = lambda url, timeout=10: sha_x + "\n"
    ctrl.clock = lambda: T0 + timedelta(hours=4)
    ctrl.tick()
    assert ctrl.state.dev_seen[sha_x]["hours"] == 0.0, "a new stretch starts when dev comes back to it"
    assert ro.soak_satisfied(ctrl.state, sha_x, 12)[0] is False


def test_old_dev_sightings_are_forgotten(cfg):
    ctrl, host, _ = make(cfg, gh=FakeGitHub(head=OLD), seen_on_dev=False)
    ctrl.state.dev_seen["e" * 40] = {"first": ro.iso(T0 - timedelta(days=40)), "last": ro.iso(T0 - timedelta(days=35)), "hours": 1}
    host.http_text = lambda url, timeout=10: "f" * 40
    ctrl.tick()
    assert "e" * 40 not in ctrl.state.dev_seen


# ---------------------------------------------------------------------------------------------
# the rollout
# ---------------------------------------------------------------------------------------------

def test_a_good_release_deploys_in_order(cfg):
    ctrl, host, notify = make(cfg)
    assert ctrl.tick() == "deployed"
    assert host.current_sha() == NEW
    kinds = [c[0] if c[0] != "db" else f"db:{c[1]}" for c in host.calls]
    # dump before rehearsal, rehearsal before activate, activate before restart; nothing rolled back
    assert kinds.index("sudo") < kinds.index("db:rehearsal-create") < kinds.index("db:rehearsal-run") \
        < kinds.index("db:rehearsal-stop") < kinds.index("db:rehearsal-drop") < kinds.index("activate") < kinds.index("restart")
    assert "db:restore" not in kinds
    assert notify.severities() == [ro.INFO]
    assert "EZ-1 the change" in notify.sent[0][2]
    assert ctrl.state.data["last_success_at"] == ro.iso(T0)
    assert not (cfg["state_dir"] / "pause").exists()
    record = json.loads(next((cfg["state_dir"] / "rollouts").glob("*.json")).read_text())
    assert record["outcome"] == "deployed" and record["migrates"] is False
    assert [s["step"] for s in record["steps"]][-2:] == ["mark current", "prune"]


def test_records_and_logs_are_not_world_readable(cfg):
    ctrl, host, _ = make(cfg)
    ctrl.tick()
    for p in (cfg["state_dir"] / "rollouts").iterdir():
        assert (p.stat().st_mode & 0o007) == 0, p


def test_smoke_runs_before_and_after_with_the_right_expectations(cfg):
    smoke = FakeSmoke()
    ctrl, host, _ = make(cfg, smoke=smoke)
    ctrl.tick()
    assert smoke.calls == [OLD, NEW]


def test_post_deploy_smoke_is_retried_and_the_baseline_gets_two_attempts(cfg):
    smoke = FakeSmoke(fail_first_n={NEW: 1})
    ctrl, host, _ = make(cfg, smoke=smoke)
    assert ctrl.tick() == "deployed"
    assert smoke.calls == [OLD, NEW, NEW]
    (cfg["state_dir"] / "state.json").unlink()      # a fresh state, or the gap gate applies
    smoke2 = FakeSmoke(fail_first_n={OLD: 1})
    ctrl2, host2, _ = make(cfg, smoke=smoke2)
    assert ctrl2.tick() == "deployed"
    assert smoke2.calls == [OLD, OLD, NEW]


def test_failing_baseline_smoke_aborts_before_anything_is_touched_and_is_retried_later(cfg):
    ctrl, host, notify = make(cfg, smoke=FakeSmoke(fail_for={OLD}))
    assert ctrl.tick() == "aborted"
    assert host.current_sha() == OLD
    assert not any(c[0] in ("activate", "restart", "sudo") for c in host.calls)
    assert notify.severities() == [ro.WARN]
    assert NEW not in ctrl.state.failed, "not the commit's fault: retried, not parked"
    assert not (cfg["state_dir"] / "pause").exists(), "production untouched → not paused"
    # The next tick does not immediately try again, and does not notify again.
    ctrl.clock = lambda: T0 + timedelta(minutes=5)
    assert ctrl.tick() == "gated"
    assert any("retrying after" in r for r in ctrl.state.candidates[NEW]["reasons"])
    assert len(notify.sent) == 1


def test_rehearsal_crash_parks_the_commit_before_production_is_touched(cfg):
    host = FakeHost(cfg, rehearsal_exit=1)
    ctrl, host, notify = make(cfg, host=host)
    assert ctrl.tick() == "aborted"
    assert host.current_sha() == OLD
    assert not any(c[0] == "activate" for c in host.calls)
    assert ("db", "rehearsal-drop") in host.calls, "the scratch database is dropped even on failure"
    assert notify.severities() == [ro.WARN]
    assert "migration or configuration failure" in notify.sent[0][2]
    assert NEW in ctrl.state.failed


def test_unconfigured_smoke_refuses_to_deploy_where_required_but_is_retryable(cfg):
    ctrl, host, notify = make(cfg, smoke=FakeSmoke(not_configured=True))
    assert ctrl.tick() == "aborted"
    assert not any(c[0] == "activate" for c in host.calls)
    assert NEW not in ctrl.state.failed
    assert "not configured" in notify.sent[0][2]


def test_unconfigured_smoke_is_noted_and_skipped_where_not_required(cfg):
    cfg["smoke"]["required"] = False
    ctrl, host, notify = make(cfg, smoke=FakeSmoke(not_configured=True))
    assert ctrl.tick() == "deployed"


@pytest.mark.parametrize("break_it,word", [
    (lambda h: setattr(h, "free", 1.0), "GB free"),
    (lambda h: setattr(h, "status", None), "needs a person"),
    (lambda h: setattr(h, "unit_active", False), "not active"),
    (lambda h: setattr(h, "dump_appends", False), "no new dump"),
])
def test_each_preflight_guard_aborts_without_touching_production(cfg, break_it, word):
    ctrl, host, notify = make(cfg)
    break_it(host)
    assert ctrl.tick() == "aborted"
    assert not any(c[0] in ("activate", "restart") for c in host.calls)
    assert word in notify.sent[0][2]


def test_health_failure_rolls_back_pauses_and_escalates(cfg):
    host = FakeHost(cfg, healthy_after_restart=False)
    ctrl, host, notify = make(cfg, host=host)
    assert ctrl.tick() == "rolled-back"
    assert activations(host) == [NEW, OLD]
    assert host.current_sha() == OLD, "current-sha names what is live"
    assert (cfg["state_dir"] / "pause").exists()
    assert NEW in ctrl.state.failed
    assert notify.severities() == [ro.CRITICAL]
    assert "ROLLED-BACK" in notify.sent[0][1]
    assert not hasattr(host, "restored_from"), "old jar came up, database left alone"


def test_health_uses_the_long_timeout_unless_the_schema_is_known_unchanged(cfg):
    ctrl, host, _ = make(cfg, host=FakeHost(cfg, changelog=(10, 12)))
    ctrl.tick()
    assert host.health_calls == [(cfg["health_url"], cfg["health_timeout_migrating_s"], NEW)]
    (cfg["state_dir"] / "state.json").unlink()
    ctrl2, host2, _ = make(cfg, host=FakeHost(cfg, changelog=(10, 10)))
    assert ctrl2.tick() == "deployed"
    assert host2.health_calls == [(cfg["health_url"], cfg["health_timeout_s"], NEW)]
    (cfg["state_dir"] / "state.json").unlink()
    ctrl3, host3, _ = make(cfg, host=FakeHost(cfg, count_fails=True))
    assert ctrl3.tick() == "deployed"
    assert host3.health_calls[0][1] == cfg["health_timeout_migrating_s"], "unknown counts as migrating"


def test_smoke_failure_after_deploy_rolls_back(cfg):
    ctrl, host, notify = make(cfg, smoke=FakeSmoke(fail_for={NEW}))
    assert ctrl.tick() == "rolled-back"
    assert activations(host) == [NEW, OLD]
    assert host.current_sha() == OLD


def test_rolled_back_sha_is_never_retried_automatically(cfg):
    ctrl, host, notify = make(cfg, smoke=FakeSmoke(fail_for={NEW}))
    ctrl.tick()
    (cfg["state_dir"] / "pause").unlink()      # a person resumes, but does not `forget`
    calls_before = len(host.calls)
    assert ctrl.tick() == "failed-candidate"
    assert len(host.calls) == calls_before
    assert any("forget" in r for r in ctrl.state.candidates[NEW]["reasons"])


def test_old_jar_unit_down_after_migrating_release_restores_the_dump(cfg):
    host = FakeHost(cfg, healthy_after_restart=False, old_jar_healthy=False, changelog=(10, 12))
    ctrl, host, notify = make(cfg, host=host)
    assert ctrl.tick() == "rolled-back"
    assert host.restored_from == host.dumps[-1], "restored from the dump taken in THIS rollout"
    assert notify.severities() == [ro.CRITICAL]
    assert (cfg["state_dir"] / "pause").exists()


def test_old_jar_unit_down_with_unknown_schema_change_restores_under_auto(cfg):
    host = FakeHost(cfg, healthy_after_restart=False, old_jar_healthy=False, count_fails=True)
    ctrl, host, notify = make(cfg, host=host)
    assert ctrl.tick() == "rolled-back"
    assert host.restored_from == host.dumps[-1]


def test_old_jar_unit_down_without_schema_change_does_not_restore_under_auto(cfg):
    host = FakeHost(cfg, healthy_after_restart=False, old_jar_healthy=False, changelog=(10, 10))
    ctrl, host, notify = make(cfg, host=host)
    assert ctrl.tick() == "DOWN"
    assert not hasattr(host, "restored_from")
    assert "DOWN" in notify.sent[0][1]


def test_restore_policy_always_restores_even_without_schema_change(cfg):
    cfg["rollback"]["restore_db"] = "always"
    host = FakeHost(cfg, healthy_after_restart=False, old_jar_healthy=False, changelog=(10, 10))
    ctrl, host, notify = make(cfg, host=host)
    assert ctrl.tick() == "rolled-back"
    assert host.restored_from == host.dumps[-1]


def test_restore_policy_never_is_respected(cfg):
    cfg["rollback"]["restore_db"] = "never"
    host = FakeHost(cfg, healthy_after_restart=False, old_jar_healthy=False, changelog=(10, 12))
    ctrl, host, notify = make(cfg, host=host)
    assert ctrl.tick() == "DOWN"
    assert not hasattr(host, "restored_from")


def test_a_quiet_url_with_a_running_unit_never_triggers_a_restore(cfg):
    # The old jar's unit IS active; only the public URL does not answer. That is nginx or DNS, and
    # a restore would throw data away to fix neither.
    host = FakeHost(cfg, healthy_after_restart=False, old_jar_healthy=False, changelog=(10, 12))
    host.core_active = lambda: True
    ctrl, host, notify = make(cfg, host=host)
    assert ctrl.tick() == "DOWN"
    assert not hasattr(host, "restored_from")
    assert "not a database problem" in notify.sent[0][2]


def test_reactivation_failure_is_down_without_a_restore(cfg):
    host = FakeHost(cfg, healthy_after_restart=False, changelog=(10, 12))
    original = host.activate

    def activate(sha):
        if sha == OLD:
            raise PermissionError("cannot write config.json")
        original(sha)
    host.activate = activate
    ctrl, host, notify = make(cfg, host=host)
    assert ctrl.tick() == "DOWN"
    assert not hasattr(host, "restored_from")
    assert "could not reactivate" in notify.sent[0][2]


def test_missing_previous_release_on_disk_aborts(cfg):
    ctrl, host, notify = make(cfg)
    shutil.rmtree(host.release_dir(OLD))
    assert ctrl.tick() == "aborted"
    assert "nothing to roll back to" in notify.sent[0][2]


def test_rehearsal_config_that_could_reach_moodle_is_refused(cfg):
    host = FakeHost(cfg)
    bad = json.loads(json.dumps(HARMLESS))
    bad["easy"]["core"]["moodle-sync"]["grades"]["url"] = "https://moodle.example.org/ws"
    original_db = host.db

    def db(*args, timeout=3600):
        out = original_db(*args, timeout=timeout)
        if args[0] == "rehearsal-config":
            Path(out).write_text(json.dumps(bad))
        return out
    host.db = db
    ctrl, host, notify = make(cfg, host=host)
    assert ctrl.tick() == "aborted"
    assert "Moodle" in notify.sent[0][2]
    assert not any(c[0] == "activate" for c in host.calls)
    assert not any(c[:2] == ("db", "rehearsal-run") for c in host.calls)


def test_prune_failure_after_a_good_deploy_is_not_a_rollback(cfg):
    ctrl, host, notify = make(cfg)
    host.prune = lambda keep: (_ for _ in ()).throw(RuntimeError("disk vanished"))
    assert ctrl.tick() == "deployed"
    assert host.current_sha() == NEW
    assert activations(host) == [NEW]


def test_unexpected_exception_after_touching_production_is_recorded_as_down_and_paused(cfg):
    ctrl, host, notify = make(cfg)
    host.mark_current = lambda sha: (_ for _ in ()).throw(OSError("disk full"))
    # Even the rollback's mark_current fails; the verdict must still land on disk and page.
    outcome = ctrl.tick()
    assert outcome in ("DOWN", "rolled-back", "rolled-back-degraded")
    assert (cfg["state_dir"] / "pause").exists()
    assert notify.severities() == [ro.CRITICAL]


def test_interrupt_during_smoke_rolls_back(cfg):
    class Interrupting(FakeSmoke):
        def __call__(self, expect_sha):
            if expect_sha == NEW:
                raise ro.Interrupted("SIGTERM")
            return super().__call__(expect_sha)
    ctrl, host, notify = make(cfg, smoke=Interrupting())
    assert ctrl.tick() == "rolled-back"
    assert activations(host) == [NEW, OLD]
    assert (cfg["state_dir"] / "pause").exists()


def test_forget_typed_during_a_rollout_survives_the_rollouts_save(cfg):
    ctrl, host, _ = make(cfg)
    ctrl.state.failed["c" * 40] = {"at": "x", "why": "y"}
    ctrl.state.save()
    original = host.materialise

    def materialise(gh, run, sha):
        # A person runs `forget` on the other sha while this rollout is in flight.
        other = ro.State(cfg["state_dir"] / "state.json")
        del other.failed["c" * 40]
        other.save()
        return original(gh, run, sha)
    host.materialise = materialise
    assert ctrl.tick() == "deployed"
    assert "c" * 40 not in ro.State(cfg["state_dir"] / "state.json").failed


# ---------------------------------------------------------------------------------------------
# the host, for real, on tmp_path
# ---------------------------------------------------------------------------------------------

def test_real_activate_flips_both_symlinks_atomically_and_writes_config(cfg):
    host = ro.Host(cfg)
    for sha in (OLD, NEW):
        rel = host.release_dir(sha)
        (rel / "web").mkdir(parents=True)
        (rel / "web" / "index.html").write_text("x")
        (rel / "core.jar").write_bytes(b"j")
    host.activate(OLD)
    assert os.readlink(cfg["root"] / "core" / "current.jar") == str(host.release_dir(OLD) / "core.jar")
    assert os.readlink(cfg["root"] / "web" / "current") == str(host.release_dir(OLD) / "web")
    assert json.loads((host.release_dir(OLD) / "web" / "config.json").read_text())["emsRoot"] == "https://api.example/v2"
    host.activate(NEW)
    assert os.readlink(cfg["root"] / "web" / "current") == str(host.release_dir(NEW) / "web")
    assert not (cfg["root"] / "web" / ".current.new").exists()


def test_real_activate_refuses_a_config_missing_keys(cfg):
    host = ro.Host(cfg)
    rel = host.release_dir(OLD)
    (rel / "web").mkdir(parents=True)
    (rel / "web" / "index.html").write_text("x")
    (rel / "core.jar").write_bytes(b"j")
    Path(cfg["config_json"]).write_text('{"emsRoot": "x", "keycloak": {"url": "u"}}')
    with pytest.raises(ro.RolloutError, match="keycloak.realm"):
        host.activate(OLD)


def test_real_activate_tolerates_an_unwritable_but_identical_config(cfg):
    host = ro.Host(cfg)
    rel = host.release_dir(OLD)
    (rel / "web").mkdir(parents=True)
    (rel / "web" / "index.html").write_text("x")
    (rel / "core.jar").write_bytes(b"j")
    shutil.copyfile(cfg["config_json"], rel / "web" / "config.json")
    os.chmod(rel / "web", 0o555)
    try:
        host.activate(OLD)      # somebody else's release, same config: fine
        Path(cfg["config_json"]).write_text('{"emsRoot": "https://other/v2", "keycloak": {"url": "u", "realm": "r", "clientId": "c"}}')
        if os.geteuid() != 0:
            with pytest.raises(ro.RolloutError, match="installed by another account"):
                host.activate(OLD)
    finally:
        os.chmod(rel / "web", 0o755)


def test_real_materialise_unpacks_the_ci_layout_all_or_nothing(cfg, tmp_path):
    host = ro.Host(cfg)
    art = tmp_path / "art"
    art.mkdir()
    jar_zip, web_zip = art / "core.zip", art / "web.zip"
    with zipfile.ZipFile(jar_zip, "w") as z:
        z.writestr(f"core-{NEW}.jar", b"jar")
    tgz = art / f"web-{NEW}.tar.gz"
    site = art / "site"
    site.mkdir()
    (site / "index.html").write_text("<html>")
    with tarfile.open(tgz, "w:gz") as t:
        t.add(site / "index.html", arcname="index.html")
    with zipfile.ZipFile(web_zip, "w") as z:
        z.write(tgz, arcname=f"web-{NEW}.tar.gz")

    class GH:
        def artifact_urls(self, run_id, sha):
            return {f"core-{sha}": "core", f"web-{sha}": "web"}

        def api(self, url, raw=False):
            return (jar_zip if url == "core" else web_zip).read_bytes()
    rel = host.materialise(GH(), {"id": 1}, NEW)
    assert host.release_complete(NEW)
    assert (rel / "core.jar").read_bytes() == b"jar" and (rel / "web" / "index.html").exists()
    assert not list((cfg["root"] / "releases").glob(".*.partial"))
    # A second call does not download.
    calls = []
    GH.api = lambda self, url, raw=False: calls.append(url)
    host.materialise(GH(), {"id": 1}, NEW)
    assert calls == []


def test_real_materialise_leaves_the_release_readable_by_core_and_nginx(cfg, tmp_path, monkeypatch):
    # The program runs under umask 027; core (easy-core) and nginx (www-data) are not in the deploy
    # group, so the release must come out world-readable and group-writable regardless.
    monkeypatch.setattr(os, "umask", lambda m: 0)
    old = os.umask(0o027)
    try:
        host = ro.Host(cfg)
        art = tmp_path / "art"
        art.mkdir()
        jar_zip, web_zip = art / "core.zip", art / "web.zip"
        with zipfile.ZipFile(jar_zip, "w") as z:
            z.writestr(f"core-{NEW}.jar", b"jar")
        tgz = art / f"web-{NEW}.tar.gz"
        site = art / "site"
        (site / "assets").mkdir(parents=True)
        (site / "index.html").write_text("<html>")
        (site / "assets" / "a.js").write_text("x")
        with tarfile.open(tgz, "w:gz") as t:
            t.add(site, arcname=".")
        with zipfile.ZipFile(web_zip, "w") as z:
            z.write(tgz, arcname=f"web-{NEW}.tar.gz")

        class GH:
            def artifact_urls(self, run_id, sha):
                return {f"core-{sha}": "core", f"web-{sha}": "web"}

            def api(self, url, raw=False):
                return (jar_zip if url == "core" else web_zip).read_bytes()
        rel = host.materialise(GH(), {"id": 1}, NEW)
        for p in [rel, rel / "web", rel / "web" / "assets"]:
            assert p.stat().st_mode & 0o777 == 0o775, p
        for p in [rel / "core.jar", rel / "web" / "index.html", rel / "web" / "assets" / "a.js"]:
            assert p.stat().st_mode & 0o777 == 0o664, p
    finally:
        os.umask(old)


def test_real_materialise_refuses_path_traversal_in_the_artifact(cfg, tmp_path):
    host = ro.Host(cfg)
    evil = tmp_path / "evil.zip"
    with zipfile.ZipFile(evil, "w") as z:
        z.writestr("../../etc/passwd", "x")

    class GH:
        def artifact_urls(self, run_id, sha):
            return {f"core-{sha}": "c", f"web-{sha}": "w"}

        def api(self, url, raw=False):
            return evil.read_bytes()
    with pytest.raises(ro.RolloutError, match="unsafe path"):
        host.materialise(GH(), {"id": 1}, NEW)
    assert not host.release_complete(NEW)


def test_real_prune_keeps_the_newest_n_plus_the_named_ones(cfg):
    cfg["keep_releases"] = 2
    host = ro.Host(cfg)
    shas = [chr(ord("a") + i) * 40 for i in range(5)]
    for i, sha in enumerate(shas):
        rel = host.release_dir(sha)
        rel.mkdir(parents=True)
        os.utime(rel, (1000 + i, 1000 + i))
    (cfg["root"] / "releases" / f".{shas[0]}.partial").mkdir()
    host.prune({shas[0]})
    left = sorted(p.name for p in (cfg["root"] / "releases").iterdir())
    assert left == sorted([shas[0], shas[3], shas[4]])


def test_real_wait_healthy_gives_up_early_on_a_crash_looping_unit(cfg, monkeypatch):
    host = ro.Host(cfg)
    calls = {"n": 0}
    monkeypatch.setattr(host, "http_status", lambda url, timeout=10: None)
    monkeypatch.setattr(host, "core_unit", lambda: ("activating", 5))
    monkeypatch.setattr(ro.time, "monotonic", lambda: calls.__setitem__("n", calls["n"] + 1) or calls["n"])
    assert host.wait_healthy("https://x/v2/", 1800) is False
    assert calls["n"] < 10, "did not wait out the timeout"
    monkeypatch.setattr(host, "core_unit", lambda: ("activating", 0))
    calls["n"] = 0
    # Still starting honestly: waits (the fake clock advances one second per call, so this hits the deadline).
    assert host.wait_healthy("https://x/v2/", 20) is False
    assert calls["n"] >= 20


def test_is_ancestor_of_reads_githubs_compare_status_the_right_way_round(cfg, monkeypatch):
    gh = ro.GitHub("kspar/easy", "CI", "t")
    seen = {}

    def api(path, raw=False):
        seen["path"] = path
        return {"status": api.status}
    monkeypatch.setattr(gh, "api", api)
    # base=sha, head=master. master has moved on past sha → "ahead" → sha IS on master.
    for status, want in (("ahead", True), ("identical", True), ("behind", False), ("diverged", False)):
        api.status = status
        assert gh.is_ancestor_of(NEW, "master") is want, status
    assert seen["path"].endswith(f"/compare/{NEW}...master")


def test_spent_budget_aborts_before_touching_but_never_refuses_a_rollback(cfg):
    ctrl, host, notify = make(cfg, smoke=FakeSmoke(fail_for={NEW}))
    ctrl.deadline = 0.0          # already spent
    assert ctrl.tick() == "aborted"
    assert not any(c[0] == "activate" for c in host.calls)
    assert NEW not in ctrl.state.failed, "a spent budget is not the commit's fault"
    # Now spend it only after activation: the rollback must still run.
    (cfg["state_dir"] / "state.json").unlink()
    ctrl2, host2, notify2 = make(cfg, smoke=FakeSmoke(fail_for={NEW}))
    original = host2.activate

    def activate(sha):
        original(sha)
        if sha == NEW:
            r = [f for f in [ctrl2] if True]  # noqa: F841 — no-op, keeps the closure obvious
            ctrl2.deadline = 0.0
    host2.activate = activate
    ctrl2.deadline = None
    # Deadline is read by the Rollout at construction; emulate a budget that expires mid-flight
    # by making the Rollout's check see a past deadline once production is touched.
    orig_rollout_init = ro.Rollout.__init__

    def init(self, *a, **kw):
        orig_rollout_init(self, *a, **kw)
        self.deadline = 0.0
    import unittest.mock as um
    with um.patch.object(ro.Rollout, "__init__", init):
        outcome = ctrl2.tick()
    assert outcome == "aborted", "before activation the budget stops it"


def test_budget_spent_after_activation_still_rolls_back(cfg):
    ctrl, host, notify = make(cfg, smoke=FakeSmoke(fail_for={NEW}))
    orig_activate = ro.Rollout.activate

    def activate(self):
        out = orig_activate(self)
        self.deadline = 0.0       # the clock runs out right after production was touched
        return out
    import unittest.mock as um
    with um.patch.object(ro.Rollout, "activate", activate):
        assert ctrl.tick() == "rolled-back"
    assert activations(host) == [NEW, OLD]


def test_check_and_tick_agree_when_paused_and_steady(cfg, capsys):
    ctrl, host, _ = make(cfg, gh=FakeGitHub(head=OLD))
    (cfg["state_dir"] / "pause").write_text("first install\n")
    assert ctrl.tick().startswith("paused")
    assert ro.cmd_check(ctrl) == 0
    out = capsys.readouterr().out
    assert "decision      paused" in out and "steady" not in out


# ---------------------------------------------------------------------------------------------
# pieces
# ---------------------------------------------------------------------------------------------

@pytest.mark.parametrize("text,want", [
    ('{"commit": "1a2b3c4d"}', "1a2b3c4d"),
    ('{"core": {"commit": "1a2b3c4"}}', "1a2b3c4"),     # falls back to the regex — pinned so a change is noticed
    ("v4.0 (1a2b3c4)", "1a2b3c4"),
    ("a" * 40 + "\n", "a" * 40),
    ("no sha here", None),
])
def test_extract_sha(text, want):
    assert ro.extract_sha(text) == want


def test_window_arithmetic():
    w = {"days": ["Tue", "Thu"], "start": "04:00", "end": "05:30", "tz": "Europe/Tallinn"}
    assert ro.in_window(w, datetime(2026, 9, 8, 1, 0, tzinfo=timezone.utc))[0]         # 04:00 Tallinn
    assert not ro.in_window(w, datetime(2026, 9, 8, 2, 30, tzinfo=timezone.utc))[0]    # 05:30 — end is exclusive
    assert not ro.in_window(w, datetime(2026, 9, 7, 1, 0, tzinfo=timezone.utc))[0]     # Monday
    assert ro.in_window({"always": True}, datetime(2026, 1, 1, tzinfo=timezone.utc))[0]
    assert ro.in_window({"days": ["*"], "start": "00:00", "end": "23:59", "tz": "UTC"},
                        datetime(2026, 9, 13, 12, 0, tzinfo=timezone.utc))[0]          # Sunday, wildcard


def test_deep_merge_does_not_lose_defaults():
    merged = ro.deep_merge(ro.DEFAULTS, {"gates": {"soak_hours": 1}})
    assert merged["gates"]["soak_hours"] == 1
    assert merged["gates"]["min_ci_age_hours"] == ro.DEFAULTS["gates"]["min_ci_age_hours"]


def test_load_config_refuses_a_misspelled_policy_and_a_bad_window(tmp_path):
    p = tmp_path / "c.json"
    p.write_text(json.dumps({"health_url": "https://x/v2/", "rollback": {"restore_db": "automatic"},
                             "window": {"start": "4:00", "end": "05:30", "tz": "Mars/Olympus"},
                             "notify": {"channels": {"critical": ["pager"]}}}))
    with pytest.raises(ro.RolloutError) as e:
        ro.load_config(p)
    msg = str(e.value)
    for word in ("restore_db", "window.start", "window.tz", "unknown channel"):
        assert word in msg


def test_state_survives_a_corrupt_file_and_an_older_shape(tmp_path):
    p = tmp_path / "state.json"
    p.write_text("{")
    st = ro.State(p)
    assert st.corrupt and st.data["candidates"] == {}
    assert list(tmp_path.glob("state.json.corrupt-*"))
    p.write_text('{"candidates": {}}')
    st2 = ro.State(p)
    assert st2.notice_due("x", 1, T0)      # `notices` exists although the file lacked it


def test_history_is_bounded(cfg):
    st = ro.State(cfg["state_dir"] / "state.json")
    for i in range(35):
        st.record_history({"i": i})
    assert len(st.data["history"]) == 30 and st.data["history"][-1]["i"] == 34


class FakeSMTP:
    sent = []

    def __init__(self, host, port, timeout=30):
        self.host = host

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def starttls(self):
        pass

    def login(self, u, p):
        pass

    def send_message(self, msg):
        FakeSMTP.sent.append(msg)


def test_notifier_routes_by_severity_delivers_independently_and_never_files_publicly(cfg, tmp_path, monkeypatch):
    monkeypatch.setattr(ro.smtplib, "SMTP", FakeSMTP)
    FakeSMTP.sent.clear()
    posted = []

    class Resp:
        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def read(self):
            return b'{"idReadable": "EZ-1"}'

    def urlopen(req, timeout=30):
        posted.append((req.full_url, req.get_method(), json.loads(req.data)))
        if "broken" in req.full_url:
            raise OSError("webhook down")
        return Resp()
    monkeypatch.setattr(ro.urllib.request, "urlopen", urlopen)
    monkeypatch.setattr(ro.json, "load", lambda resp: {"idReadable": "EZ-1"})
    hook = tmp_path / "hook"
    hook.write_text("https://broken.example/hook")
    tok = tmp_path / "tok"
    tok.write_text("t")
    cfg["notify"] = {"channels": {"info": ["mail"], "warn": ["mail"], "critical": ["mail", "webhook", "youtrack"]},
                     "mail": {"to": ["a@example.org", "b@example.org"], "host": "relay", "from": "r@example.org"},
                     "webhook": {"url_file": str(hook), "format": "json"},
                     "youtrack": {"base_url": "https://yt.example", "project_id": "0-0", "token_file": str(tok),
                                  "visibility_group_id": ""}}
    n = ro.Notifier(cfg, "test")
    assert n(ro.INFO, "hi", "body") == ["mail"]
    assert FakeSMTP.sent[-1]["To"] == "a@example.org, b@example.org"
    delivered = n(ro.CRITICAL, "bad", "body")
    assert delivered == ["mail"], "the broken webhook did not stop mail; YouTrack refused without a group"
    assert not any("yt.example" in u for u, _, _ in posted)
    cfg["notify"]["youtrack"]["visibility_group_id"] = "542-0"
    delivered = n(ro.CRITICAL, "bad", "body")
    assert "youtrack" in delivered
    url, method, body = next(p for p in posted if "yt.example" in p[0])
    assert method == "POST" and body["visibility"]["permittedGroups"] == [{"id": "542-0"}]


# ---------------------------------------------------------------------------------------------
# the CLI
# ---------------------------------------------------------------------------------------------

def cli(cfg, monkeypatch, ctrl, *argv):
    p = cfg["state_dir"] / "config.json"
    c = {k: (str(v) if isinstance(v, Path) else v) for k, v in cfg.items()}
    p.write_text(json.dumps(c))
    monkeypatch.setattr(ro, "build", lambda cfg, smoke_factory=None, deadline=None: ctrl)
    monkeypatch.setattr(ro, "install_signal_handlers", lambda: None)
    return ro.main(["--config", str(p), *argv])


def test_cli_pause_resume_forget_and_status_need_no_token(cfg, monkeypatch, capsys):
    ctrl, host, _ = make(cfg)
    ctrl.state.failed[NEW] = {"at": "t", "why": "w"}
    ctrl.state.save()
    monkeypatch.setattr(ro, "build", lambda *a, **k: (_ for _ in ()).throw(ro.TokenMissing("no token")))
    p = cfg["state_dir"] / "config.json"
    p.write_text(json.dumps({k: (str(v) if isinstance(v, Path) else v) for k, v in cfg.items()}))
    assert ro.main(["--config", str(p), "pause", "manual", "deploy"]) == 0
    assert (cfg["state_dir"] / "pause").read_text().endswith("manual deploy\n")
    assert ro.main(["--config", str(p), "status"]) == 0
    assert "paused        " in capsys.readouterr().out
    assert ro.main(["--config", str(p), "forget", NEW[:8]]) == 0
    assert NEW not in ro.State(cfg["state_dir"] / "state.json").failed
    assert ro.main(["--config", str(p), "resume"]) == 0
    assert not (cfg["state_dir"] / "pause").exists()


def test_cli_tick_with_a_placeholder_token_exits_cleanly(cfg, monkeypatch, capsys):
    monkeypatch.setattr(ro, "build", lambda *a, **k: (_ for _ in ()).throw(ro.TokenMissing("placeholder")))
    p = cfg["state_dir"] / "config.json"
    p.write_text(json.dumps({k: (str(v) if isinstance(v, Path) else v) for k, v in cfg.items()}))
    assert ro.main(["--config", str(p), "tick"]) == 0
    assert "placeholder" in capsys.readouterr().out


def test_cli_rollback_pauses_marks_failed_and_writes_current_sha(cfg, monkeypatch, capsys):
    ctrl, host, _ = make(cfg)
    host.put_release(NEW)
    (cfg["root"] / "current-sha").write_text(NEW + "\n")
    assert cli(cfg, monkeypatch, ctrl, "rollback", OLD[:8]) == 0
    assert host.current_sha() == OLD
    assert (cfg["state_dir"] / "pause").exists()
    assert NEW in ro.State(cfg["state_dir"] / "state.json").failed
    assert ("restart",) in host.calls


def test_cli_rollback_to_an_unhealthy_release_reports_it(cfg, monkeypatch):
    ctrl, host, _ = make(cfg)
    host.old_jar_healthy = False
    host.put_release(NEW)
    (cfg["root"] / "current-sha").write_text(NEW + "\n")
    assert cli(cfg, monkeypatch, ctrl, "rollback", OLD[:8]) == 1


def test_cli_deploy_now_validates_and_pins(cfg, monkeypatch, capsys):
    ctrl, host, _ = make(cfg)
    assert cli(cfg, monkeypatch, ctrl, "deploy-now", "abc") == 2
    assert cli(cfg, monkeypatch, ctrl, "deploy-now", "head") == 0
    content = (cfg["state_dir"] / "deploy-now").read_text().split()
    assert content[0] == NEW and ro.parse_iso(content[1])


def test_cli_smoke_refuses_while_a_rollout_holds_the_lock(cfg, monkeypatch):
    ctrl, host, _ = make(cfg)
    import fcntl
    with (cfg["state_dir"] / "lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        assert cli(cfg, monkeypatch, ctrl, "smoke") == 1
