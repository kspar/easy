# coding=utf-8
"""What `grade_submission` lays out on disk before it hands anything to Docker.

The container is built from a directory this function writes, so every property here is part of the
contract with the grading script — and all of them fail *inside* a container, where the only
symptom a teacher sees is a submission that will not grade.

Docker is faked. Building a real image per test would make this the slowest thing in the repo and
would test Docker; what is worth testing is the directory, which is entirely ours.
"""
import os
import stat

import pytest

import containers
from containers import RunStatus, grade_submission

SUBMISSION = "print('tere')\n"
SCRIPT = "#!/bin/sh\npython3 /student-submission/lahendus.py\n"


@pytest.fixture
def captured(monkeypatch):
    """Replaces the container run and hands back a snapshot of the directory it was given.

    A snapshot rather than the directory itself, because `grade_submission` uses
    `tempfile.TemporaryDirectory()` — by the time it returns, the directory is gone. That is the
    point of `test_the_temp_directory_is_removed`, and it means anything a test wants to assert has
    to be read while the fake is executing.
    """
    seen = {}

    def fake_run(source_dir, max_run_time_sec, max_mem_MB, logger, request_id):
        seen["dir"] = source_dir
        seen["args"] = (max_run_time_sec, max_mem_MB, request_id)
        seen["files"] = {}
        seen["modes"] = {}
        for root, _, names in os.walk(source_dir):
            for n in names:
                path = os.path.join(root, n)
                rel = os.path.relpath(path, source_dir)
                with open(path, encoding="utf-8") as f:
                    seen["files"][rel] = f.read()
                seen["modes"][rel] = stat.S_IMODE(os.stat(path).st_mode)
        return RunStatus.SUCCESS, "output"

    monkeypatch.setattr(containers, "_run_in_container", fake_run)
    return seen


def run(logger, assets=(), submission=SUBMISSION):
    return grade_submission(submission, SCRIPT, list(assets), "python:3.12", 10, 64, logger, "req-1")


# --- the files the grading script expects to find ---------------------------------------------

def test_the_submission_is_written_under_both_names(captured, logger):
    """
    `lahendus.py` and `submission.py`, with identical content.

    New tiivad specs run `lahendus.py`, because a traceback naming it reads better to a student than
    one naming `submission.py`; the older graders still open `submission.py`. Dropping either breaks
    a generation of exercises, and the failure is a `FileNotFoundError` inside a container.
    """
    run(logger)

    assert captured["files"]["student-submission/lahendus.py"] == SUBMISSION
    assert captured["files"]["student-submission/submission.py"] == SUBMISSION


def test_the_grading_script_is_readable_and_executable_and_not_writable(captured, logger):
    # The Dockerfile ends `CMD /evaluate.sh`, so a script without the execute bit is a container
    # that exits immediately with a permission error — and `_was_memory_killed` would then be
    # inspecting a message about permissions.
    run(logger)

    mode = captured["modes"]["evaluate.sh"]

    assert mode & stat.S_IXUSR, "evaluate.sh is not executable"
    assert mode & stat.S_IRUSR, "evaluate.sh is not readable"
    assert mode == 0o500, f"expected 0o500, got {oct(mode)}"


def test_the_dockerfile_names_the_requested_base_image(captured, logger):
    run(logger)

    dockerfile = captured["files"]["Dockerfile"]

    assert "FROM python:3.12" in dockerfile
    # Both COPY lines matter: one brings the submission in, the other the script the CMD runs.
    assert "COPY student-submission /student-submission" in dockerfile
    assert "COPY evaluate.sh /" in dockerfile


def test_assets_are_written_beside_the_submission(captured, logger):
    run(logger, assets=[("input.txt", "1 2 3"), ("helper.py", "def h(): pass")])

    assert captured["files"]["student-submission/input.txt"] == "1 2 3"
    assert captured["files"]["student-submission/helper.py"] == "def h(): pass"


def test_an_asset_may_overwrite_the_submission_filename(captured, logger):
    """
    Recorded rather than prevented, because it is how some exercises work.

    An exercise whose asset is called `lahendus.py` replaces the student's submission — which is
    deliberate for a test that supplies a fixed program — and the ordering that makes it possible
    (assets written last) is load-bearing rather than accidental.
    """
    run(logger, assets=[("lahendus.py", "# supplied by the exercise")])

    assert captured["files"]["student-submission/lahendus.py"] == "# supplied by the exercise"
    # And the legacy copy is untouched, so the two names can differ.
    assert captured["files"]["student-submission/submission.py"] == SUBMISSION


def test_non_ascii_survives_the_round_trip_to_disk(captured, logger):
    # Every open() here passes encoding='utf-8' explicitly. Without it the encoding is the host's
    # locale, so an executor on a machine with a non-UTF-8 default would mangle exactly the
    # submissions that contain Estonian — and nothing in this repo would notice.
    run(logger, submission="print('õäöü ŠŽ 🎉')\n")

    assert captured["files"]["student-submission/lahendus.py"] == "print('õäöü ŠŽ 🎉')\n"


def test_the_limits_and_request_id_reach_the_container_runner(captured, logger):
    run(logger)
    assert captured["args"] == (10, 64, "req-1")


# --- cleanup ------------------------------------------------------------------------------------

def test_the_temp_directory_is_removed_on_success(captured, logger):
    run(logger)
    assert not os.path.exists(captured["dir"])


def test_the_temp_directory_is_removed_when_the_container_run_raises(monkeypatch, logger):
    """
    The leg that matters, because it is the one that runs on a bad day.

    Every submission gets a directory holding its source; an executor that leaks one per failed
    grading fills the disk of the host that grades everything, and the first symptom is unrelated
    exercises failing. `tempfile.TemporaryDirectory` as a context manager is what prevents it — this
    asserts the `with` is actually wrapping the failure path.
    """
    leaked = {}

    def explode(source_dir, *args, **kwargs):
        leaked["dir"] = source_dir
        raise RuntimeError("docker is on fire")

    monkeypatch.setattr(containers, "_run_in_container", explode)

    with pytest.raises(RuntimeError):
        run(logger)

    assert not os.path.exists(leaked["dir"]), "a failed grading left its temp directory behind"
