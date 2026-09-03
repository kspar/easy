"""Tests for the end-to-end smoke suite, over a fake environment.

The fake is a small model of a healthy deployment: a web origin serving index.html, config.json and
a stamped bundle; an API that 401s without a token and answers the student and teacher calls with
one; an IdP that issues tokens; an executor that grades `good` 100 and anything else 0. Each test
then breaks one thing and checks the suite notices — including the case where grading cannot fail,
which is the failure the suite exists to catch.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.parse

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "files"))

import easy_smoke as sm  # noqa: E402

SHA = "b" * 40
WEB, API, IDP = "https://web.example", "https://api.example", "https://idp.example/auth"
CFG = {"web_url": WEB, "api_url": API, "idp_url": IDP, "realm": "master",
       "course_id": "42", "exercise_id": "7",
       "solutions": {"good": "print('Hello, smoke!')", "bad": "print('no')"},
       "grade_timeout_s": 5, "executor_version_url": "http://127.0.0.1:5111/v1/version"}
SECRETS = {"client_id": "easy-smoke", "client_secret": "s",
           "student": {"username": "easy-smoke-student", "password": "p"},
           "teacher": {"username": "easy-smoke-teacher", "password": "p"}}


class FakeEnv:
    """Answers HTTP for a healthy deployment; tests poke at its attributes to break things."""

    def __init__(self):
        self.commit = SHA[:7]
        self.bundle_commit = SHA[:7]
        self.config_cache = "no-store"
        self.grade_everything_100 = False
        self.executor_reachable = True
        self.keycloak_js = 404
        self.token_ok = True
        self.submissions: list[dict] = []
        self.in_progress_rounds = 1
        self.requests: list[tuple[str, str]] = []

    def __call__(self, method, url, headers=None, data=None, timeout=30):
        self.requests.append((method, url))
        headers = headers or {}
        u = urllib.parse.urlparse(url)
        path = u.path
        base = f"{u.scheme}://{u.netloc}"

        def ok(body, ct="application/json", extra=None):
            b = body if isinstance(body, bytes) else (json.dumps(body).encode() if not isinstance(body, str) else body.encode())
            return sm.Response(200, {"Content-Type": ct, **(extra or {})}, b)

        # --- web ---
        if base == WEB:
            if path == "/":
                return ok('<html><head><link rel="stylesheet" href="/assets/app.css"></head>'
                          '<body><div id="root"></div><script src="/assets/app.js"></script></body></html>',
                          "text/html", {"Strict-Transport-Security": "max-age=1"})
            if path == "/config.json":
                return ok({"emsRoot": API, "keycloak": {"url": IDP, "realm": "master", "clientId": "lahendus.ut.ee"}},
                          extra={"Cache-Control": self.config_cache})
            if path == "/assets/app.js":
                return ok(f'const c="{self.bundle_commit}";', "application/javascript")
            if path == "/assets/app.css":
                return ok("body{}", "text/css")
            if path == "/courses":
                return ok("<html><script></script></html>", "text/html")
            return sm.Response(404, {}, b"")

        # --- idp ---
        if base == "https://idp.example":
            if path == "/auth/realms/master/.well-known/openid-configuration":
                return ok({"issuer": f"{IDP}/realms/master", "jwks_uri": f"{IDP}/realms/master/protocol/openid-connect/certs"})
            if path == "/auth/realms/master/protocol/openid-connect/certs":
                return ok({"keys": [{"kid": "k"}]})
            if path == "/auth/realms/master/protocol/openid-connect/token":
                if method == "POST" and data:
                    form = urllib.parse.parse_qs(data.decode())
                    if not self.token_ok:
                        return sm.Response(401, {}, b'{"error":"invalid_grant"}')
                    return ok({"access_token": f"tok-{form['username'][0]}"})
                return sm.Response(400, {}, b'{"error":"invalid_request"}')
            if path == "/auth/realms/master/protocol/openid-connect/logout":
                return sm.Response(400, {}, b"")
            if path == "/auth/js/keycloak.js":
                return sm.Response(self.keycloak_js, {"Content-Type": "application/javascript"}, b"")
            return sm.Response(404, {}, b"")

        # --- executor direct ---
        if base == "http://127.0.0.1:5111":
            return ok({"version": "4.0", "commit": "abc1234", "grading_images": [{"name": "tiivad"}]})

        # --- api ---
        if base == API:
            if path == "/v2/unauth/statistics/common":
                return ok({"in_auto_assessing": 0, "total_submissions": 10, "total_users": 3})
            auth = headers.get("Authorization", "")
            if not auth.startswith("Bearer tok-"):
                return sm.Response(401, {}, b"")
            who = auth[len("Bearer tok-"):]
            if path == "/v2/account/checkin":
                return ok({})
            if path == "/v2/student/courses":
                return ok({"courses": [{"id": "42", "title": "Smoke"}]})
            if path == "/v2/student/courses/42/exercises":
                return ok({"exercises": [{"id": "7", "effective_title": "Hello", "grader_type": "AUTO", "is_open": True}]})
            if path == "/v2/student/courses/42/exercises/7":
                return ok({"title": "Hello"})
            if path == "/v2/student/courses/42/exercises/7/submissions" and method == "POST":
                sol = json.loads(data)["solution"]
                grade = 100 if (self.grade_everything_100 or sol == CFG["solutions"]["good"]) else 0
                self.submissions.append({"id": str(len(self.submissions) + 1), "number": len(self.submissions) + 1,
                                         "solution": sol, "autograde_status": "IN_PROGRESS",
                                         "grade": None, "_final": grade, "_rounds": self.in_progress_rounds})
                return ok({})
            if path == "/v2/student/courses/42/exercises/7/submissions/latest/await":
                return ok({})
            if path == "/v2/student/courses/42/exercises/7/submissions/all":
                for s in self.submissions:
                    if s["_rounds"] > 0:
                        s["_rounds"] -= 1
                    else:
                        s["autograde_status"] = "COMPLETED"
                        s["grade"] = {"grade": s["_final"], "is_autograde": True, "is_graded_directly": False}
                        s["auto_assessment"] = {"grade": s["_final"], "feedback": "fb"}
                return ok({"submissions": [{k: v for k, v in s.items() if not k.startswith("_")} for s in self.submissions]})
            if path == "/v2/versions":
                return ok({"core": {"version": "4.0", "commit": self.commit},
                           "executors": [{"name": "exec-1", "version": "4.0", "reachable": self.executor_reachable,
                                          "grading_images": [{"name": "tiivad", "libraries": []}]}]})
            if path == "/v2/teacher/courses/42/exercises/7/submissions/latest/students":
                return ok({"latest_submissions": [{"student_id": "easy-smoke-student", "grade": 100}]})
            return sm.Response(404, {}, b"")
        return sm.Response(0, {}, b"unreachable")


def run(env, expect_sha=SHA, cfg=None):
    return sm.run(cfg or CFG, expect_sha=expect_sha, log=lambda m: None, http=env,
                  tls_days=lambda host: 60.0, sleep=lambda s: None, secrets=SECRETS)


def failed(report):
    return {c.name for c in report.failures}


def test_a_healthy_environment_passes_with_only_the_known_warning():
    r = run(FakeEnv())
    assert r.ok, r.text()
    # keycloak.js is EZ-1803: warned, not failed.
    assert failed(r) == {"thonny: keycloak.js adapter"}
    assert r.checks[-1].name.startswith("executor: /v1/version")


def test_reports_are_readable():
    text = run(FakeEnv()).text()
    assert "smoke: PASS" in text and "executor: good solution" in text


def test_not_configured_when_secrets_are_placeholders(tmp_path):
    p = tmp_path / "s.json"
    p.write_text('{"client_id": "x", "student": {"username": "u", "password": "CHANGEME"}, "teacher": {}}')
    r = sm.run({**CFG, "secrets_file": str(p)}, log=lambda m: None)
    assert r.not_configured and not r.ok


def test_grading_that_cannot_fail_is_a_failure():
    env = FakeEnv()
    env.grade_everything_100 = True
    r = run(env)
    assert not r.ok
    assert "executor: bad solution → not full marks" in failed(r)
    assert "executor: good solution → full marks" not in failed(r)


def test_wrong_bundle_is_a_failure():
    env = FakeEnv()
    env.bundle_commit = "0000000"
    r = run(env)
    assert "web: assets and version stamp" in failed(r)
    assert "is not the release that was deployed" in next(c.detail for c in r.failures if c.name.startswith("web: assets"))


def test_core_running_a_different_commit_is_a_failure():
    env = FakeEnv()
    env.commit = "0000000"
    assert "teacher: /v2/versions" in failed(run(env))


def test_unreachable_executor_is_a_failure():
    env = FakeEnv()
    env.executor_reachable = False
    assert "teacher: /v2/versions" in failed(run(env))


def test_cached_config_json_is_a_failure():
    env = FakeEnv()
    env.config_cache = "public, max-age=3600"
    assert "web: config.json" in failed(run(env))


def test_login_failure_fails_every_authenticated_check():
    env = FakeEnv()
    env.token_ok = False
    r = run(env)
    assert {"idp: student login", "idp: teacher login", "student: courses", "executor: good solution → full marks"} <= failed(r)


def test_grading_that_never_finishes_is_a_failure():
    env = FakeEnv()
    env.in_progress_rounds = float("inf")
    r = run(env)
    assert "executor: good solution → full marks" in failed(r)
    assert "still grading" in next(c.detail for c in r.failures if c.name.startswith("executor: good"))


def test_expiring_certificate_is_a_failure():
    r = sm.run(CFG, expect_sha=SHA, log=lambda m: None, http=FakeEnv(), tls_days=lambda h: 3.0,
               sleep=lambda s: None, secrets=SECRETS)
    assert "tls: certificates" in failed(r)


def test_no_expect_sha_skips_the_stamp_comparison_only():
    env = FakeEnv()
    env.bundle_commit = "0000000"
    env.commit = "0000000"
    r = run(env, expect_sha=None)
    assert "web: assets and version stamp" not in failed(r)
    assert "teacher: /v2/versions" not in failed(r)


def test_a_crashing_check_is_a_failed_check_not_a_crashed_suite():
    env = FakeEnv()
    original = env.__call__

    def boom(method, url, **kw):
        if url.endswith("/v2/versions"):
            raise RuntimeError("kaboom")
        return original(method, url, **kw)
    r = sm.run(CFG, expect_sha=SHA, log=lambda m: None, http=boom, tls_days=lambda h: 60.0,
               sleep=lambda s: None, secrets=SECRETS)
    assert "teacher: /v2/versions" in failed(r)
    assert "kaboom" in next(c.detail for c in r.failures if c.name == "teacher: /v2/versions")
    assert len(r.checks) == 24
