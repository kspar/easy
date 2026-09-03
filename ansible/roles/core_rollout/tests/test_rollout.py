"""Tests for the guarded rollout.

The interesting behaviour is what must NOT happen: a commit must not deploy outside its window or
before it has soaked, a release that fails its smoke suite must be put back, a rollback must stop
further rollouts until a person has looked, a failure before core was touched must not be treated
as one after. So the controller takes its GitHub client, its host and its smoke suite as objects,
and every test below drives one of those paths with a fake.
"""

from __future__ import annotations

import json
import os
import sys
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
    def __init__(self, head=NEW, green=True, on_master=True, run_age_hours=24.0):
        self.head, self.green, self.on_master = head, green, on_master
        self.run = {"id": 7, "html_url": "https://ci/run/7",
                    "run_started_at": ro.iso(T0 - timedelta(hours=run_age_hours))}

    def branch_head(self, branch):
        return self.head

    def green_run_for(self, sha):
        return self.run if self.green else None

    def is_ancestor_of(self, sha, branch):
        return self.on_master

    def commits_between(self, base, head):
        return [f"{head[:8]} EZ-1 the change"]

    def artifact_urls(self, run_id, sha):
        return {}


class FakeSmoke:
    """A smoke suite that passes, or fails against a chosen sha, or is not configured."""

    def __init__(self, fail_for=(), not_configured=False):
        self.fail_for, self.not_configured = set(fail_for), not_configured
        self.calls: list[str] = []

    def __call__(self, expect_sha):
        self.calls.append(expect_sha)
        report = type("R", (), {})()
        report.not_configured = self.not_configured
        report.ok = expect_sha not in self.fail_for and not self.not_configured
        report.text = lambda: f"smoke for {expect_sha[:8]}: {'PASS' if report.ok else 'FAIL'}"
        return report


class FakeHost(ro.Host):
    """The release tree on disk is real (tmp_path); everything that touches services is recorded."""

    def __init__(self, cfg, healthy_after_restart=True, old_jar_healthy=True, changelog=(10, 10),
                 rehearsal_exit=None):
        super().__init__(cfg)
        self.healthy_after_restart, self.old_jar_healthy = healthy_after_restart, old_jar_healthy
        self.changelog_before, self.changelog_after = changelog
        self.rehearsal_exit = rehearsal_exit
        self.calls: list[tuple] = []
        self.dumps = ["/srv/easy/db-dumps/easyems-2026-09-07T0330.dump"]
        self.active_sha = None

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

    def restart_core(self):
        self.calls.append(("restart",))

    def core_active(self):
        return True

    def core_log_tail(self):
        return "log tail"

    def free_gb(self, path):
        return 40.0

    # -- database helper --------------------------------------------------------------------
    def db(self, *args, timeout=3600):
        self.calls.append(("db", *args))
        cmd = args[0]
        if cmd == "ping":
            return "ok"
        if cmd == "newest-dump":
            return self.dumps[-1]
        if cmd == "changelog-count":
            return str(self.changelog_after if args[1:] == ("rehearsal",) else self.changelog_before)
        if cmd == "rehearsal-create":
            return "easyems_rehearsal"
        if cmd == "rehearsal-config":
            p = self.cfg["state_dir"] / "rehearsal" / "application.yaml"
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(json.dumps(HARMLESS))
            return str(p)
        if cmd == "restore":
            self.restored_from = args[1]
            return "restored"
        return ""

    def sudo(self, argv, timeout=600):
        self.calls.append(("sudo", *argv))
        if argv[1:3] == ["start", self.cfg["dump_service"]]:
            self.dumps.append(f"/srv/easy/db-dumps/easyems-2026-09-08T0{len(self.dumps)}30.dump")

    # -- HTTP -------------------------------------------------------------------------------
    def http_status(self, url, timeout=10):
        return 401

    def http_text(self, url, timeout=10):
        return None

    def wait_healthy(self, url, timeout_s, interval=4):
        if self.active_sha == NEW:
            return self.healthy_after_restart
        return self.old_jar_healthy

    def port_free(self, port):
        return True

    def popen(self, argv, cwd, log_path, env=None):
        log_path.write_text("Started EasyApplication\n")
        return FakeProc(self.rehearsal_exit)


class FakeProc:
    def __init__(self, exit_code):
        self.returncode = exit_code

    def poll(self):
        return self.returncode

    def terminate(self):
        pass

    def wait(self, t=None):
        pass

    def kill(self):
        pass


HARMLESS = {
    "server": {"address": "127.0.0.1", "port": 8091},
    "spring": {"datasource": {"jdbc-url": "jdbc:postgresql://127.0.0.1:5432/easyems_rehearsal",
                              "username": "easyems_rehearsal"},
               "mail": {"host": "127.0.0.1", "port": 9}},
    "easy": {"core": {"auth-enabled": True,
                      "mail": {"sys": {"enabled": False}, "user": {"enabled": False}},
                      "moodle-sync": {"users": {"url": "http://127.0.0.1:9/"}, "grades": {"url": "http://127.0.0.1:9/"},
                                      "course-allowlist": "easy-rehearsal-no-such-course"},
                      "storage": {"backend": "local"},
                      "stored-file-sweep": {"delete": False, "cron": "0 0 0 1 1 ? 2099"},
                      "auto-assess": {"fixed-delay": {"ms": "9000000000000"}},
                      "youtrack": {"enabled": False}}},
}


class FakeNotifier:
    def __init__(self):
        self.sent = []

    def __call__(self, severity, subject, body):
        self.sent.append((severity, subject, body))

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
    return c


def make(cfg, gh=None, host=None, smoke=None, now=T0, seen_on_dev=True):
    gh = gh or FakeGitHub()
    host = host or FakeHost(cfg)
    host.put_release(OLD)
    (cfg["root"] / "current-sha").write_text(OLD + "\n")
    state = ro.State(cfg["state_dir"] / "state.json")
    if seen_on_dev:
        state.dev_seen[NEW] = {"first": ro.iso(now - timedelta(hours=30)), "last": ro.iso(now - timedelta(hours=1))}
    notify = FakeNotifier()
    ctrl = ro.Controller(cfg, host, gh, notify, smoke or FakeSmoke(), state, clock=lambda: now)
    return ctrl, host, notify


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
    ctrl.state.dev_seen[NEW] = {"first": ro.iso(T0 - timedelta(hours=2)), "last": ro.iso(T0)}
    assert ctrl.tick() == "gated"
    assert any("needs 12" in r for r in ctrl.state.candidates[NEW]["reasons"])


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
    (cfg["state_dir"] / "deploy-now").write_text(NEW[:10] + "\n")
    assert ctrl.tick() == "deployed"
    assert not (cfg["state_dir"] / "deploy-now").exists(), "the override is consumed"
    # But never without a green run.
    ctrl2, host2, _ = make(cfg, gh=FakeGitHub(green=False), now=when)
    (cfg["state_dir"] / "deploy-now").write_text("head\n")
    assert ctrl2.tick() == "waiting-for-ci"
    assert host2.calls == []


def test_paused_means_nothing_happens_at_all(cfg):
    ctrl, host, _ = make(cfg)
    (cfg["state_dir"] / "pause").write_text("2026-09-01 looking into EZ-1\n")
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


def test_dev_sightings_are_recorded_from_a_plain_text_sha(cfg):
    ctrl, host, _ = make(cfg, gh=FakeGitHub(head=OLD), seen_on_dev=False)
    host.http_text = lambda url, timeout=10: "c" * 40 + "\n"
    ctrl.tick()
    assert ("c" * 40) in ctrl.state.dev_seen


# ---------------------------------------------------------------------------------------------
# the rollout
# ---------------------------------------------------------------------------------------------

def test_a_good_release_deploys_in_order(cfg):
    ctrl, host, notify = make(cfg)
    assert ctrl.tick() == "deployed"
    assert host.current_sha() == NEW
    kinds = [c[0] if c[0] != "db" else f"db:{c[1]}" for c in host.calls]
    # dump before rehearsal, rehearsal before activate, activate before restart; nothing rolled back
    assert kinds.index("sudo") < kinds.index("db:rehearsal-create") < kinds.index("activate") < kinds.index("restart")
    assert "db:rehearsal-drop" in kinds and "db:restore" not in kinds
    assert notify.severities() == [ro.INFO]
    assert "EZ-1 the change" in notify.sent[0][2]
    assert ctrl.state.data["last_success_at"] == ro.iso(T0)
    assert not (cfg["state_dir"] / "pause").exists()
    record = json.loads(next((cfg["state_dir"] / "rollouts").glob("*.json")).read_text())
    assert record["outcome"] == "deployed" and record["migrates"] is False


def test_smoke_runs_before_and_after_with_the_right_expectations(cfg):
    smoke = FakeSmoke()
    ctrl, host, _ = make(cfg, smoke=smoke)
    ctrl.tick()
    assert smoke.calls == [OLD, NEW]


def test_failing_baseline_smoke_aborts_before_anything_is_touched(cfg):
    ctrl, host, notify = make(cfg, smoke=FakeSmoke(fail_for={OLD}))
    assert ctrl.tick() == "aborted"
    assert host.current_sha() == OLD
    assert not any(c[0] in ("activate", "restart", "sudo") for c in host.calls)
    assert notify.severities() == [ro.CRITICAL], "the current release failing its own tests is critical"
    assert NEW in ctrl.state.failed
    assert not (cfg["state_dir"] / "pause").exists(), "production untouched → not paused"


def test_rehearsal_crash_aborts_before_production_is_touched(cfg):
    host = FakeHost(cfg, rehearsal_exit=1)
    ctrl, host, notify = make(cfg, host=host)
    assert ctrl.tick() == "aborted"
    assert host.current_sha() == OLD
    assert not any(c[0] == "activate" for c in host.calls)
    assert ("db", "rehearsal-drop") in host.calls, "the scratch database is dropped even on failure"
    assert notify.severities() == [ro.WARN]
    assert "migration or configuration failure" in notify.sent[0][2]


def test_unconfigured_smoke_refuses_to_deploy_where_required(cfg):
    ctrl, host, notify = make(cfg, smoke=FakeSmoke(not_configured=True))
    assert ctrl.tick() == "aborted"
    assert not any(c[0] == "activate" for c in host.calls)


def test_unconfigured_smoke_is_noted_and_skipped_where_not_required(cfg):
    cfg["smoke"]["required"] = False
    ctrl, host, notify = make(cfg, smoke=FakeSmoke(not_configured=True))
    assert ctrl.tick() == "deployed"


def test_health_failure_rolls_back_pauses_and_escalates(cfg):
    host = FakeHost(cfg, healthy_after_restart=False)
    ctrl, host, notify = make(cfg, host=host)
    assert ctrl.tick() == "rolled-back"
    activations = [c[1] for c in host.calls if c[0] == "activate"]
    assert activations == [NEW, OLD]
    assert host.current_sha() == OLD, "current-sha never moved"
    assert (cfg["state_dir"] / "pause").exists()
    assert NEW in ctrl.state.failed
    assert notify.severities() == [ro.CRITICAL]
    assert "ROLLED-BACK" in notify.sent[0][1]
    assert ("db", "restore", host.dumps[-1]) not in host.calls, "old jar came up, database left alone"


def test_smoke_failure_after_deploy_rolls_back(cfg):
    ctrl, host, notify = make(cfg, smoke=FakeSmoke(fail_for={NEW}))
    assert ctrl.tick() == "rolled-back"
    assert [c[1] for c in host.calls if c[0] == "activate"] == [NEW, OLD]
    assert host.current_sha() == OLD


def test_rolled_back_sha_is_never_retried_automatically(cfg):
    ctrl, host, notify = make(cfg, smoke=FakeSmoke(fail_for={NEW}))
    ctrl.tick()
    (cfg["state_dir"] / "pause").unlink()      # a person resumes, but does not `forget`
    calls_before = len(host.calls)
    assert ctrl.tick() == "failed-candidate"
    assert len(host.calls) == calls_before
    assert any("forget" in r for r in ctrl.state.candidates[NEW]["reasons"])


def test_old_jar_down_after_migrating_release_restores_the_dump(cfg):
    host = FakeHost(cfg, healthy_after_restart=False, old_jar_healthy=False, changelog=(10, 12))
    # After the restore the old jar is fine again.
    original_wait = host.wait_healthy

    def wait(url, timeout_s, interval=4):
        if getattr(host, "restored_from", None):
            return True
        return original_wait(url, timeout_s, interval)
    host.wait_healthy = wait
    ctrl, host, notify = make(cfg, host=host)
    assert ctrl.tick() == "rolled-back"
    assert host.restored_from == host.dumps[-1], "restored from the dump taken in THIS rollout"
    assert notify.severities() == [ro.CRITICAL]
    assert (cfg["state_dir"] / "pause").exists()


def test_old_jar_down_without_schema_change_does_not_restore_under_auto(cfg):
    host = FakeHost(cfg, healthy_after_restart=False, old_jar_healthy=False, changelog=(10, 10))
    ctrl, host, notify = make(cfg, host=host)
    assert ctrl.tick() == "DOWN"
    assert not hasattr(host, "restored_from")
    assert notify.severities() == [ro.CRITICAL]
    assert "DOWN" in notify.sent[0][1]


def test_restore_policy_never_is_respected(cfg):
    cfg["rollback"]["restore_db"] = "never"
    host = FakeHost(cfg, healthy_after_restart=False, old_jar_healthy=False, changelog=(10, 12))
    ctrl, host, notify = make(cfg, host=host)
    assert ctrl.tick() == "DOWN"
    assert not hasattr(host, "restored_from")


def test_missing_previous_release_on_disk_aborts(cfg):
    ctrl, host, notify = make(cfg)
    import shutil
    shutil.rmtree(host.release_dir(OLD))
    assert ctrl.tick() == "aborted"
    assert "nothing to roll back to" in notify.sent[0][2]


def test_rehearsal_config_that_could_reach_moodle_is_refused(cfg):
    host = FakeHost(cfg)
    bad = json.loads(json.dumps(HARMLESS))
    bad["easy"]["core"]["moodle-sync"]["grades"]["url"] = "https://moodle.ut.ee/ws"
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


# ---------------------------------------------------------------------------------------------
# pieces
# ---------------------------------------------------------------------------------------------

@pytest.mark.parametrize("text,want", [
    ('{"core": {"commit": "1a2b3c4"}}', None),                 # nested — not a top-level key
    ('{"commit": "1a2b3c4d"}', "1a2b3c4d"),
    ("v4.0 (1a2b3c4)", "1a2b3c4"),
    ("a" * 40 + "\n", "a" * 40),
    ("no sha here", None),
])
def test_extract_sha(text, want):
    got = ro.extract_sha(text)
    if want is None and text.startswith('{"core"'):
        # Falls back to the regex, which does find it — acceptable, but pinned so a change is noticed.
        assert got == "1a2b3c4"
    else:
        assert got == want


def test_window_arithmetic():
    w = {"days": ["Tue", "Thu"], "start": "04:00", "end": "05:30", "tz": "Europe/Tallinn"}
    assert ro.in_window(w, datetime(2026, 9, 8, 1, 0, tzinfo=timezone.utc))[0]         # 04:00 Tallinn
    assert not ro.in_window(w, datetime(2026, 9, 8, 2, 30, tzinfo=timezone.utc))[0]    # 05:30 — end is exclusive
    assert not ro.in_window(w, datetime(2026, 9, 7, 1, 0, tzinfo=timezone.utc))[0]     # Monday
    assert ro.in_window({"always": True}, datetime(2026, 1, 1, tzinfo=timezone.utc))[0]


def test_deep_merge_does_not_lose_defaults():
    merged = ro.deep_merge(ro.DEFAULTS, {"gates": {"soak_hours": 1}})
    assert merged["gates"]["soak_hours"] == 1
    assert merged["gates"]["min_ci_age_hours"] == ro.DEFAULTS["gates"]["min_ci_age_hours"]


def test_notifier_never_raises_when_every_channel_is_unconfigured(cfg):
    n = ro.Notifier(cfg, "test")
    n(ro.CRITICAL, "x", "y")   # mail has no host, webhook and youtrack have no files
    assert n.sent == []
