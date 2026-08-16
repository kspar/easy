# coding=utf-8
"""`POST /v1/grade` — the only endpoint core calls to grade anything.

Two halves, and the second is the one with consequences.

**Validation.** A request core sends that this rejects is a submission that never grades, and a
malformed one it accepts reaches `grade_submission` and fails somewhere less legible. `check_content`
compares the key set *exactly*, so it rejects both missing and extra keys — which makes it a
compatibility hinge between two services that deploy separately, and worth pinning in both
directions.

**The status → message mapping.** What a student is told when grading did not produce a grade. Every
branch here returns **0 points** with an Estonian sentence, and getting the branches confused tells a
student their program was too slow when it crashed. Nothing downstream can tell the difference: core
stores whatever text arrives.

Docker never runs. `grade_submission` is replaced per test, which is also the only way to reach the
TIME_EXCEEDED and MEM_EXCEEDED branches without actually exhausting a limit.
"""
import json

import pytest

import server
from containers import RunStatus

SEP = "#" * 50

VALID = {
    "submission": "print(1)",
    "grading_script": "#!/bin/sh\ntrue",
    "assets": [],
    "image_name": "python:3.12",
    "max_time_sec": 10,
    "max_mem_mb": 64,
}


@pytest.fixture
def grader(monkeypatch):
    """Replaces grading. Set `grader.result` to the (status, raw_output) the container 'produced'."""

    class Grader:
        result = (RunStatus.SUCCESS, f"feedback\n{SEP}\ngrade: 100")
        calls = []

    g = Grader()

    def fake(submission, grading_script, assets, image_name, max_time, max_mem, logger, request_id):
        g.calls.append({
            "submission": submission, "grading_script": grading_script, "assets": assets,
            "image_name": image_name, "max_time": max_time, "max_mem": max_mem,
        })
        return g.result

    monkeypatch.setattr(server, "grade_submission", fake)
    return g


def post(client, body, **kwargs):
    return client.post("/v1/grade", data=json.dumps(body), content_type="application/json", **kwargs)


# --- the happy path -----------------------------------------------------------------------------

def test_a_valid_request_is_graded_and_the_grade_comes_back(client, grader):
    resp = post(client, VALID)

    assert resp.status_code == 200
    assert resp.get_json() == {"grade": 100, "feedback": "feedback\n"}


def test_the_request_reaches_the_grader_intact(client, grader):
    # Asserted because a status assertion cannot see it: a request that arrived with the wrong
    # submission, or with the limits swapped, still produces a perfectly good-looking grade.
    post(client, dict(VALID, assets=[{"file_name": "a.txt", "file_content": "x"}]))

    call = grader.calls[-1]
    assert call["submission"] == "print(1)"
    assert call["image_name"] == "python:3.12"
    assert (call["max_time"], call["max_mem"]) == (10, 64)
    # Assets arrive as dicts and are handed on as tuples — the shape `containers.py` iterates.
    assert call["assets"] == [("a.txt", "x")]


# --- what a student is told when there is no grade ------------------------------------------------

def test_a_timeout_is_reported_as_a_timeout(client, grader):
    grader.result = (RunStatus.TIME_EXCEEDED, "")

    body = post(client, VALID).get_json()

    assert body["grade"] == 0
    assert body["feedback"] == server.TIME_EXCEEDED_MESSAGE
    assert "käivitusaega" in body["feedback"]


def test_running_out_of_memory_is_reported_as_memory_and_not_as_time(client, grader):
    # The two are one `elif` apart and read almost identically in the source. Telling a student their
    # program was too slow when it used too much memory sends them to optimise the wrong thing.
    grader.result = (RunStatus.MEM_EXCEEDED, "")

    body = post(client, VALID).get_json()

    assert body["feedback"] == server.MEM_EXCEEDED_MESSAGE
    assert body["feedback"] != server.TIME_EXCEEDED_MESSAGE
    assert "mälumahtu" in body["feedback"]


def test_unparseable_grader_output_gives_zero_and_shows_the_raw_output(client, grader):
    """
    The grader ran, produced something, and it was not a grade.

    Zero is the only safe number, but the raw output is appended deliberately: this is a broken
    *exercise*, and the teacher fixing it needs to see what the container printed. Swallowing it
    would leave them with an apology and nothing to act on.
    """
    grader.result = (RunStatus.SUCCESS, "Traceback (most recent call last):\n  ValueError")

    body = post(client, VALID).get_json()

    assert body["grade"] == 0
    assert body["feedback"].startswith(server.SOMETHING_FAILED_MESSAGE)
    assert "ValueError" in body["feedback"]


# --- validation -----------------------------------------------------------------------------------

@pytest.mark.parametrize("missing", sorted(VALID))
def test_every_field_is_required(client, grader, missing):
    body = {k: v for k, v in VALID.items() if k != missing}

    resp = post(client, body)

    assert resp.status_code == 400
    assert resp.get_json()["message"]
    assert not grader.calls, "a malformed request reached the grader"


def test_an_unexpected_field_is_rejected(client, grader):
    """
    Exact key-set comparison, in the other direction.

    This is strict enough to be a deployment hazard worth knowing about: core adding a field to the
    grading request breaks every executor that has not been updated, and the failure is 400 on every
    submission. Pinned so that anyone loosening it is doing so on purpose.
    """
    resp = post(client, dict(VALID, unexpected="x"))

    assert resp.status_code == 400
    assert not grader.calls


def test_assets_must_be_a_list_of_name_and_content_pairs(client, grader):
    assert post(client, dict(VALID, assets={"a": "b"})).status_code == 400
    assert post(client, dict(VALID, assets=[{"file_name": "a"}])).status_code == 400
    assert post(client, dict(VALID, assets=[{"file_name": "a", "file_content": "b", "mode": "x"}])).status_code == 400
    assert not grader.calls


def test_a_body_that_is_not_json_is_refused(client, grader):
    resp = client.post("/v1/grade", data="submission=print(1)", content_type="text/plain")

    assert resp.status_code == 400
    assert not grader.calls


def test_a_bad_request_answers_with_json_rather_than_an_html_error_page(client, grader):
    # Core parses the body; werkzeug's default 400 is HTML, which would surface as a parse error
    # rather than as the message this endpoint took the trouble to write.
    resp = post(client, {})

    assert resp.status_code == 400
    assert resp.is_json
    assert "message" in resp.get_json()


# --- version reporting ------------------------------------------------------------------------------

def test_the_version_endpoint_answers_the_three_fields_core_asks_for(client):
    body = client.get("/v1/version").get_json()

    assert set(body) == {"version", "commit", "built_at"}
    # Never blank: core renders "unreachable" for an executor that does not answer, so a blank
    # version would show as a reachable executor with nothing to say.
    assert body["version"]
    assert body["commit"]
