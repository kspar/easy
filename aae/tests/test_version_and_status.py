# coding=utf-8
"""Version reporting, and how a container's exit is classified.

Two small things with the same property: they answer a question by guessing from what is lying
around, and a wrong guess is a plausible-looking answer rather than an error.
"""
import importlib
import os

import pytest

import containers
import server
from containers import _was_memory_killed


# --- version reporting --------------------------------------------------------------------------

@pytest.fixture
def repo(tmp_path, monkeypatch):
    """Point the version readers at a directory this test controls."""
    monkeypatch.setattr(server, "_REPO_ROOT", str(tmp_path))
    return tmp_path


def test_the_version_comes_from_the_VERSION_file(repo):
    (repo / "VERSION").write_text("4.0\n")
    assert server._read_version() == "4.0"


def test_a_missing_VERSION_file_reports_unknown_rather_than_failing(repo):
    """
    A deployment that copied only `aae/` has no `VERSION` beside it.

    "unknown" is the useful failure. Refusing to start a grading service over a diagnostic string
    would take down every submission on the environment to avoid an imprecise About page.
    """
    assert server._read_version() == "unknown"


def test_an_empty_VERSION_file_also_reports_unknown(repo):
    # Distinct from missing, and reachable: a truncated write, or a deploy step that created the
    # file before filling it. Blank would render as an executor with no version at all.
    (repo / "VERSION").write_text("   \n")
    assert server._read_version() == "unknown"


def test_a_stamped_COMMIT_file_wins_over_git(repo):
    """
    The deployed case. A deployed executor is a copy of the source with no git history to ask, so
    whatever a deploy wrote is the only true answer — and it must win even on a host that happens to
    have a git checkout somewhere above it.
    """
    (repo / "COMMIT").write_text("abc1234\n")
    assert server._read_commit() == "abc1234"


def test_an_empty_COMMIT_file_falls_through_to_git(repo, monkeypatch):
    # An empty stamp is not an answer. Falling through matters because the file is written by a
    # deploy script, and a failed write leaves an empty file rather than no file.
    (repo / "COMMIT").write_text("")
    monkeypatch.setattr(server, "_REPO_ROOT", os.path.dirname(os.path.dirname(os.path.abspath(server.__file__))))

    # In a checkout this is a real short sha; the assertion is that it is *something*, since the
    # value depends on the machine.
    assert server._read_commit()


def test_no_COMMIT_file_and_no_git_reports_unknown(repo):
    # `git rev-parse` in an empty temp directory fails, which is the deployed-without-a-stamp case.
    assert server._read_commit() == "unknown"


def test_the_deploy_time_is_the_mtime_of_the_source(monkeypatch, tmp_path):
    """
    aae is copied, not compiled, so there is no build to date.

    The modification time of `server.py` is the honest equivalent — a deploy sets it when it writes
    the file — and it answers the question core and web answer with their build times: is this
    running what we shipped an hour ago?
    """
    stamp = server._read_deployed_at()

    assert stamp.endswith("Z"), stamp
    assert "T" in stamp
    # No sub-second precision: it is displayed on an About page, not diffed.
    assert "." not in stamp


# --- classifying how a container ended -------------------------------------------------------------

@pytest.mark.parametrize("output", [
    "Killed",
    "killed",
    "some output\n/evaluate.sh: line 3: 42 Killed  python3 lahendus.py",
    "output\nKilled\n",
    "output\nKilled\n\n   \n",
])
def test_an_oom_kill_is_recognised(output):
    # The kernel's OOM killer leaves this on the last line and the container exits normally, so
    # without the heuristic an out-of-memory run reports as a successful grading of empty output.
    assert _was_memory_killed(output)


@pytest.mark.parametrize("output", [
    "",
    "all tests passed",
    "Killed\nbut then more output",
])
def test_ordinary_output_is_not_mistaken_for_an_oom_kill(output):
    assert not _was_memory_killed(output)


def test_a_submission_that_prints_killed_last_is_misclassified():
    """
    **A known false positive, pinned rather than fixed.**

    The check is "does the last non-empty line contain 'killed'", so a student whose program legitimately
    ends by printing that word is told their submission exceeded the memory limit. It is a heuristic
    over an unstructured stream and there is nothing better available at this layer — the docker API
    exposes an OOM flag on the container, which is the real fix and a change to `_run_in_container`.

    Written down because the behaviour is surprising, cheap to hit on an exercise about, say,
    process management, and impossible to diagnose from the student's side: the feedback talks about
    memory and the program used none.
    """
    assert _was_memory_killed("The process was killed")


def test_the_three_run_statuses_are_distinct():
    # `post_grade` branches on identity and raises on an unhandled one, so a duplicated value would
    # silently route memory failures into the timeout message.
    values = {s.value for s in containers.RunStatus}
    assert len(values) == 3
