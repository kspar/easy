"""Tests for the grading-image reconciler.

The interesting behaviour is all about what must *not* happen: a bad image must not become live, a
failed grade must be undone, and a digest that has already failed must not be retried every few
minutes forever. So the reconciler takes its Docker access and its grading call as arguments, and
every test below drives one of those paths with a fake.

This matters more than usual because the alternative is finding out on the host that grades real
submissions. A gate that has never fired is a gate that may not work.
"""

from __future__ import annotations

import json
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "files"))

import easy_grading_sync as sync  # noqa: E402

REGISTRY = "ghcr.io/kspar/easy"


class FakeDocker:
    """Just enough daemon: a tag table, a label table, and a record of what was asked."""

    def __init__(self, published, smoke_ok=True):
        # published: {digest: {"declared": str, "installed": str}}
        self.published = published
        self.smoke_ok = smoke_ok
        self.tags = {}          # ref -> image id
        self.labels = {}        # ref -> {label: value}
        self.calls = []
        self.removed = []

    # -- the channel, as CI would have left it ----------------------------------------------------
    def publish_channel(self, name, channel, digest):
        ref = f"{REGISTRY}/{name}:{channel}"
        self.tags[ref] = f"sha256:{digest}"
        self.labels[ref] = {
            sync.LABEL_INPUTS: digest,
            sync.LABEL_DECLARED: self.published[digest]["declared"],
            sync.LABEL_INSTALLED: self.published[digest]["installed"],
        }

    def make_live(self, name, digest, extra_tags=()):
        """Pretend a previous run already put this digest live, so state and reality agree."""
        pinned = f"{REGISTRY}/{name}:i{digest}"
        self.tags[pinned] = f"sha256:{digest}"
        self.labels[pinned] = dict(self.labels.get(f"{REGISTRY}/{name}:dev", {}))
        for target in [name, *extra_tags]:
            self.tags[target] = f"sha256:{digest}"

    # -- the interface the reconciler uses --------------------------------------------------------
    def pull(self, ref):
        self.calls.append(("pull", ref))
        if ref not in self.tags:
            raise RuntimeError(f"no such published ref {ref}")

    def label(self, ref, key):
        return self.labels.get(ref, {}).get(key)

    def image_id(self, ref):
        return self.tags.get(ref)

    def tag(self, source, target):
        self.calls.append(("tag", source, target))
        self.tags[target] = self.tags[source]
        self.labels[target] = dict(self.labels.get(source, {}))

    def smoke(self, ref, env):
        self.calls.append(("smoke", ref, tuple(sorted(env.items()))))
        return (True, "ok") if self.smoke_ok else (False, "declared 1.0 installed 0.9")

    def local_refs(self, prefix):
        return [r for r in self.tags if r.startswith(prefix)]

    def remove(self, ref):
        self.removed.append(ref)
        self.tags.pop(ref, None)


def config(tmp_path, images=None):
    return {
        "registry": REGISTRY,
        "channel": "dev",
        "images": images or [{"name": "tiivad", "tags": ["tiivad:tsl-compose"]}],
        "state_path": str(tmp_path / "state.json"),
        "keep": 3,
        "min_free_gb": 0,
        "executor_url": "http://127.0.0.1:5111",
    }


def graded_100(url, image, timeout=300):
    return True, "grade=100"


def graded_wrong(url, image, timeout=300):
    return False, "grade=0"


@pytest.fixture
def logs():
    return []


def run(cfg, docker, state, logs, grade=graded_100):
    return sync.reconcile(cfg, docker, state, logs.append, grade=grade)


# ------------------------------------------------------------------------------------------------
# Reading an image's own declaration


def test_expectations_come_from_the_image_not_from_configuration():
    got = sync.declared_to_expectations("numpy~=1.23.4 tiivad==0.0.33")
    assert got == {"EASY_EXPECT_NUMPY_COMPATIBLE": "1.23.4", "EASY_EXPECT_TIIVAD": "0.0.33"}


def test_a_commit_pin_yields_no_expectation():
    # `grader@<sha>` is not a version any installed package reports.
    assert sync.declared_to_expectations("grader@" + "a" * 40) == {}
    assert "EASY_EXPECT_NUMPY_COMPATIBLE" in sync.declared_to_expectations(
        "numpy~=1.23.4 grader@" + "a" * 40
    )


# ------------------------------------------------------------------------------------------------
# The happy paths


def test_a_new_digest_is_verified_then_made_live(tmp_path, logs):
    docker = FakeDocker({"aaa": {"declared": "tiivad==0.0.34", "installed": "tiivad==0.0.34"}})
    docker.publish_channel("tiivad", "dev", "aaa")
    state = run(config(tmp_path), docker, {}, logs)

    smoke = [c for c in docker.calls if c[0] == "smoke"]
    assert smoke, "the image was made live without being checked"
    # The smoke test must come before the bare tag moves, or a bad image grades a submission.
    assert docker.calls.index(smoke[0]) < docker.calls.index(("tag", f"{REGISTRY}/tiivad:iaaa", "tiivad"))
    assert docker.tags["tiivad"] == "sha256:aaa"
    assert docker.tags["tiivad:tsl-compose"] == "sha256:aaa"
    assert state["tiivad"]["inputs"] == "aaa"
    assert state["tiivad"]["installed"] == "tiivad==0.0.34"


def test_steady_state_changes_nothing(tmp_path, logs):
    docker = FakeDocker({"aaa": {"declared": "tiivad==0.0.34", "installed": "tiivad==0.0.34"}})
    docker.publish_channel("tiivad", "dev", "aaa")
    docker.make_live("tiivad", "aaa", ["tiivad:tsl-compose"])
    state = {"tiivad": {"inputs": "aaa", "ref": f"{REGISTRY}/tiivad:iaaa"}}

    run(config(tmp_path), docker, state, logs)

    assert not [c for c in docker.calls if c[0] == "smoke"]
    assert not [c for c in docker.calls if c[0] == "tag" and c[2] == "tiivad"]
    assert logs == []


def test_a_state_file_that_disagrees_with_the_live_tag_is_not_believed(tmp_path, logs):
    """The state file says the right digest is live; the bare tag actually points elsewhere.

    This is the PR #70 failure exactly — a host advertising a version it was not running. Trusting
    the record over the daemon is what allowed it, so the reconcile checks both.
    """
    docker = FakeDocker({"aaa": {"declared": "tiivad==0.0.34", "installed": "tiivad==0.0.34"}})
    docker.publish_channel("tiivad", "dev", "aaa")
    docker.make_live("tiivad", "aaa")
    docker.tags["tiivad"] = "sha256:something-older"
    state = {"tiivad": {"inputs": "aaa", "ref": f"{REGISTRY}/tiivad:iaaa"}}

    run(config(tmp_path), docker, state, logs)

    assert docker.tags["tiivad"] == "sha256:aaa"


# ------------------------------------------------------------------------------------------------
# The gates


def test_a_failed_smoke_check_never_becomes_live(tmp_path, logs):
    docker = FakeDocker(
        {"bad": {"declared": "tiivad==9.9.9", "installed": "tiivad==0.0.33"}}, smoke_ok=False
    )
    docker.publish_channel("tiivad", "dev", "bad")
    docker.tags["tiivad"] = "sha256:previous"

    state = run(config(tmp_path), docker, {}, logs)

    assert docker.tags["tiivad"] == "sha256:previous", "a failed image was made live"
    assert state["tiivad"]["quarantine"] == ["bad"]
    assert any("failed its smoke check" in m for m in logs)


def test_a_failed_grade_is_reverted(tmp_path, logs):
    docker = FakeDocker({
        "old": {"declared": "tiivad==0.0.33", "installed": "tiivad==0.0.33"},
        "new": {"declared": "tiivad==0.0.34", "installed": "tiivad==0.0.34"},
    })
    docker.publish_channel("tiivad", "dev", "new")
    old_ref = f"{REGISTRY}/tiivad:iold"
    docker.tags[old_ref] = "sha256:old"
    docker.tags["tiivad"] = "sha256:old"
    state = {"tiivad": {"inputs": "old", "ref": old_ref}}

    state = run(config(tmp_path), docker, state, logs, grade=graded_wrong)

    assert docker.tags["tiivad"] == "sha256:old", "a wrongly-grading image was left live"
    assert state["tiivad"]["quarantine"] == ["new"]
    assert state["tiivad"]["inputs"] == "old"
    assert any("reverted" in m for m in logs)


def test_a_failed_grade_with_nothing_to_revert_to_says_so(tmp_path, logs):
    docker = FakeDocker({"new": {"declared": "tiivad==0.0.34", "installed": "tiivad==0.0.34"}})
    docker.publish_channel("tiivad", "dev", "new")

    run(config(tmp_path), docker, {}, logs, grade=graded_wrong)

    # Nothing to go back to, so it stays live — but it must be said out loud rather than recorded as
    # a success.
    assert any("nothing to revert to" in m for m in logs)


def test_a_quarantined_digest_is_not_retried(tmp_path, logs):
    docker = FakeDocker({"bad": {"declared": "tiivad==9.9.9", "installed": "tiivad==0.0.33"}})
    docker.publish_channel("tiivad", "dev", "bad")
    docker.tags["tiivad"] = "sha256:previous"
    state = {"tiivad": {"inputs": "previous", "quarantine": ["bad"]}}

    run(config(tmp_path), docker, state, logs)

    # Without this the host would flap between working and broken every tick, forever.
    assert not [c for c in docker.calls if c[0] == "smoke"]
    assert docker.tags["tiivad"] == "sha256:previous"
    assert any("quarantined" in m for m in logs)


def test_an_image_declaring_nothing_is_refused(tmp_path, logs):
    docker = FakeDocker({"aaa": {"declared": "", "installed": ""}})
    docker.publish_channel("tiivad", "dev", "aaa")
    docker.tags["tiivad"] = "sha256:previous"

    run(config(tmp_path), docker, {}, logs)

    # An image whose smoke check would assert nothing is not verified at all, so it must not be
    # treated as verified.
    assert docker.tags["tiivad"] == "sha256:previous"
    assert any("declares no versions" in m for m in logs)


def test_an_unlabelled_channel_image_is_refused(tmp_path, logs):
    docker = FakeDocker({"aaa": {"declared": "x==1", "installed": "x==1"}})
    docker.publish_channel("tiivad", "dev", "aaa")
    del docker.labels[f"{REGISTRY}/tiivad:dev"][sync.LABEL_INPUTS]
    docker.tags["tiivad"] = "sha256:previous"

    run(config(tmp_path), docker, {}, logs)

    assert docker.tags["tiivad"] == "sha256:previous"
    assert any("no easy.grading.inputs label" in m for m in logs)


def test_a_pull_failure_leaves_the_host_alone(tmp_path, logs):
    docker = FakeDocker({})
    docker.tags["tiivad"] = "sha256:previous"

    run(config(tmp_path), docker, {}, logs)

    assert docker.tags["tiivad"] == "sha256:previous"
    assert any("could not pull" in m for m in logs)


def test_a_disk_below_the_floor_refuses_to_pull_more(tmp_path, logs):
    docker = FakeDocker({"aaa": {"declared": "tiivad==1.0", "installed": "tiivad==1.0"}})
    docker.publish_channel("tiivad", "dev", "aaa")
    docker.tags["tiivad"] = "sha256:previous"
    cfg = config(tmp_path)
    cfg["min_free_gb"] = 10_000_000     # more than any real disk

    run(cfg, docker, {}, logs)

    assert not [c for c in docker.calls if c[0] == "smoke"]
    assert any("below the" in m for m in logs)


# ------------------------------------------------------------------------------------------------
# Retention, which is what makes rollback offline


def test_pruning_never_drops_the_live_or_previous_version(tmp_path, logs):
    docker = FakeDocker({"new": {"declared": "tiivad==1.1", "installed": "tiivad==1.1"}})
    docker.publish_channel("tiivad", "dev", "new")
    for old in ("v1", "v2", "v3", "v4"):
        docker.tags[f"{REGISTRY}/tiivad:i{old}"] = f"sha256:{old}"
    docker.tags["tiivad"] = "sha256:v4"
    state = {"tiivad": {"inputs": "v4", "ref": f"{REGISTRY}/tiivad:iv4"}}

    run(config(tmp_path), docker, state, logs)

    assert f"{REGISTRY}/tiivad:inew" not in docker.removed, "pruned the image it just made live"
    assert f"{REGISTRY}/tiivad:iv4" not in docker.removed, "pruned the rollback target"


# ------------------------------------------------------------------------------------------------
# State on disk


def test_state_is_written_atomically_and_reloads(tmp_path):
    path = tmp_path / "sub" / "state.json"
    sync.save_state(str(path), {"tiivad": {"inputs": "aaa"}})
    assert sync.load_state(str(path))["tiivad"]["inputs"] == "aaa"
    assert not (tmp_path / "sub" / "state.json.new").exists()


def test_a_corrupt_state_file_does_not_stop_a_reconcile(tmp_path):
    path = tmp_path / "state.json"
    path.write_text("{ this is not json")
    assert sync.load_state(str(path)) == {}
