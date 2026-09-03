#!/usr/bin/env python3
"""easy-smoke — does a deployed Lahendus environment actually work, end to end?

Not "is the port open": a student logs in, opens a course, submits a solution and gets it graded by
the executor; a teacher logs in and sees that grade and the versions page; the web bundle that is
served is the one that was deployed; the IdP answers at every URL the SPA and the Thonny plugin
hardcode. It is what a person did by hand after every production deploy (doc/production-update.md
step 5), written down so a machine can do it before and after every rollout — and so a rollback is
decided by evidence rather than by whoever is awake.

Two properties every check here has to keep:

  * **It must be able to fail.** The autograde check submits a solution that is WRONG on purpose
    and requires a grade below full marks. A suite that only ever submitted the right answer would
    pass against an executor that grades everything 100 — which is exactly the kind of broken that
    looks fine from outside. See doc/testing.md on detectors that need a positive case.
  * **It writes as little as it can, and only as its own two accounts.** Per attempt: two
    submissions by the smoke student into the smoke course, each tagged with a nonce so the run
    reads back its own and not a concurrent run's; a `checkin` by each account, which core answers
    by updating that account's name, email and last-seen. Nothing else. A grading failure the suite
    reports is one core has already mailed the system address about, as it would for any student.

Configuration comes from the rollout config (`smoke` block) and credentials from a separate
secrets file the role creates as a placeholder and never reads. Stdlib only, like everything else
that runs on these hosts.

Usable on its own:

    easy-smoke --config /etc/easy/rollout.json [--expect-sha <sha>] [--json]
"""

from __future__ import annotations

import argparse
import json
import re
import secrets as secrets_mod
import socket
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

USER_AGENT = "easy-smoke"
CRITICAL, WARN = "critical", "warn"
API_PREFIX = "/v2"


# ---------------------------------------------------------------------------------------------
# HTTP, small enough to fake
# ---------------------------------------------------------------------------------------------

@dataclass
class Response:
    status: int
    headers: dict
    body: bytes

    def text(self) -> str:
        return self.body.decode("utf-8", "replace")

    def json(self):
        return json.loads(self.body or b"null")

    def header(self, name: str) -> str:
        return next((v for k, v in self.headers.items() if k.lower() == name.lower()), "")


def urllib_http(method: str, url: str, headers: dict | None = None, data: bytes | None = None,
                timeout: int = 30) -> Response:
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"User-Agent": USER_AGENT, **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return Response(resp.status, dict(resp.headers.items()), resp.read())
    except urllib.error.HTTPError as e:
        return Response(e.code, dict(e.headers.items()), e.read())
    except (urllib.error.URLError, socket.timeout, ConnectionError, OSError) as e:
        return Response(0, {}, str(e).encode())


def tls_days_left(hostname: str, port: int = 443) -> float:
    """Days until the served certificate expires.

    The default context verifies the chain, so an already-expired or untrusted certificate is
    reported as the SSL error itself rather than as a negative number — still a failure, and a
    more useful one.
    """
    ctx = ssl.create_default_context()
    with socket.create_connection((hostname, port), timeout=15) as sock:
        with ctx.wrap_socket(sock, server_hostname=hostname) as tls:
            cert = tls.getpeercert()
    not_after = datetime.fromtimestamp(ssl.cert_time_to_seconds(cert["notAfter"]), tz=timezone.utc)
    return (not_after - datetime.now(timezone.utc)).total_seconds() / 86400


# ---------------------------------------------------------------------------------------------
# the report
# ---------------------------------------------------------------------------------------------

@dataclass
class Check:
    name: str
    ok: bool
    severity: str
    detail: str = ""
    seconds: float = 0.0


@dataclass
class Report:
    checks: list[Check] = field(default_factory=list)
    not_configured: bool = False
    reason: str = ""

    @property
    def ok(self) -> bool:
        return not self.not_configured and all(c.ok or c.severity == WARN for c in self.checks)

    @property
    def failures(self) -> list[Check]:
        return [c for c in self.checks if not c.ok]

    def text(self) -> str:
        if self.not_configured:
            return f"smoke: NOT CONFIGURED — {self.reason or 'the secrets file is absent or still a placeholder'}"
        lines = []
        for c in self.checks:
            mark = "ok  " if c.ok else ("warn" if c.severity == WARN else "FAIL")
            lines.append(f"  {mark} {c.name:<36} {c.seconds:5.1f}s  {c.detail}"[:400])
        n_fail = sum(1 for c in self.failures if c.severity == CRITICAL)
        n_warn = sum(1 for c in self.failures if c.severity == WARN)
        lines.append(f"smoke: {'PASS' if self.ok else 'FAIL'} — {len(self.checks)} checks, "
                     f"{n_fail} failed, {n_warn} warnings")
        return "\n".join(lines)

    def as_dict(self) -> dict:
        return {"ok": self.ok, "not_configured": self.not_configured, "reason": self.reason,
                "checks": [c.__dict__ for c in self.checks]}


class Fail(Exception):
    """A check that decided it failed, with the reason."""


# ---------------------------------------------------------------------------------------------
# the suite
# ---------------------------------------------------------------------------------------------

class Smoke:
    def __init__(self, cfg: dict, secrets: dict, expect_sha: str | None, log=print,
                 http=urllib_http, tls_days=tls_days_left, sleep=time.sleep, nonce: str | None = None):
        self.cfg, self.secrets, self.expect_sha = cfg, secrets, (expect_sha or "")
        self.log, self.http, self.tls_days, self.sleep = log, http, tls_days, sleep
        self.report = Report()
        self.web = cfg["web_url"].rstrip("/")
        self.api = cfg["api_url"].rstrip("/")          # the ORIGIN; every call appends /v2
        self.idp = cfg["idp_url"].rstrip("/")          # includes the /auth prefix, as config.json does
        self.realm = cfg.get("realm", "master")
        self.nonce = nonce or secrets_mod.token_hex(6)
        self.tokens: dict[str, str] = {}
        self.index_html = ""
        self.submitted: dict[str, str] = {}            # "good"/"bad" -> submission id

    # -- plumbing -------------------------------------------------------------------------------
    def check(self, name: str, fn, severity: str = CRITICAL) -> None:
        t0 = time.monotonic()
        try:
            detail = fn() or ""
            self.report.checks.append(Check(name, True, severity, str(detail), time.monotonic() - t0))
        except Fail as e:
            self.report.checks.append(Check(name, False, severity, str(e), time.monotonic() - t0))
        except Exception as e:  # noqa: BLE001 — a crashing check is a failed check, never a crashed suite
            self.report.checks.append(Check(name, False, severity, f"{type(e).__name__}: {e}", time.monotonic() - t0))
        c = self.report.checks[-1]
        self.log(f"  {'ok  ' if c.ok else 'FAIL'} {name}: {c.detail[:160]}")

    def v2(self, path: str) -> str:
        return f"{self.api}{API_PREFIX}{path}"

    def get(self, url: str, token: str | None = None, **kw) -> Response:
        h = {"Authorization": f"Bearer {token}"} if token else {}
        return self.http("GET", url, headers=h, **kw)

    def post_json(self, url: str, body, token: str | None = None, **kw) -> Response:
        h = {"Content-Type": "application/json"}
        if token:
            h["Authorization"] = f"Bearer {token}"
        return self.http("POST", url, headers=h, data=None if body is None else json.dumps(body).encode(), **kw)

    def expect(self, resp: Response, *codes: int, what: str) -> Response:
        if resp.status not in codes:
            raise Fail(f"{what}: HTTP {resp.status} (wanted {'/'.join(map(str, codes))}) {resp.text()[:200]!r}")
        return resp

    def token(self, who: str) -> str:
        if who in self.tokens:
            return self.tokens[who]
        acct = self.secrets[who]
        form = urllib.parse.urlencode({
            "grant_type": "password", "client_id": self.secrets["client_id"],
            "client_secret": self.secrets.get("client_secret", ""),
            "username": acct["username"], "password": acct["password"], "scope": "openid"}).encode()
        resp = self.http("POST", f"{self.idp}/realms/{self.realm}/protocol/openid-connect/token",
                         headers={"Content-Type": "application/x-www-form-urlencoded"}, data=form)
        self.expect(resp, 200, what=f"password grant for {who}")
        tok = resp.json().get("access_token")
        if not tok:
            raise Fail(f"token response for {who} has no access_token")
        self.tokens[who] = tok
        return tok

    # -- web -------------------------------------------------------------------------------------
    def web_index(self) -> str:
        r = self.expect(self.get(f"{self.web}/"), 200, what="GET /")
        if "text/html" not in r.header("Content-Type"):
            raise Fail(f"/ is {r.header('Content-Type')!r}, not html")
        self.index_html = r.text()
        if "<script" not in self.index_html:
            raise Fail("index.html has no <script> — an empty or error page")
        return f"{len(self.index_html)} bytes of html"

    def web_config_json(self) -> str:
        r = self.expect(self.get(f"{self.web}/config.json"), 200, what="GET /config.json")
        if "no-store" not in r.header("Cache-Control"):
            raise Fail(f"config.json Cache-Control is {r.header('Cache-Control')!r}, must contain no-store "
                       f"(doc/release-procedure.md: a cached one hands a deploy the previous backend)")
        c = r.json()
        # emsRoot carries the API prefix — the SPA appends `/unauth/...` to it — so it must be this
        # environment's API origin plus exactly that prefix.
        ems = (c.get("emsRoot") or "").rstrip("/")
        want = f"{self.api}{API_PREFIX}"
        if ems != want:
            raise Fail(f"emsRoot is {c.get('emsRoot')!r}, this environment's API is {want}")
        kc = c.get("keycloak") or {}
        if (kc.get("url") or "").rstrip("/") != self.idp or kc.get("realm") != self.realm:
            raise Fail(f"keycloak {kc.get('url')!r}/{kc.get('realm')!r} does not match {self.idp}/{self.realm}")
        if not kc.get("clientId"):
            raise Fail("config.json has no keycloak.clientId")
        return f"emsRoot {ems}, realm {kc['realm']}"

    def web_version(self) -> str:
        # The build writes version.json beside the bundle (EZ-1752) — the same commit the About
        # page shows. Read that rather than grep the chunks, which move when the build changes.
        r = self.expect(self.get(f"{self.web}/version.json"), 200, what="GET /version.json")
        v = r.json()
        commit = str(v.get("commit") or "")
        if self.expect_sha and not self.expect_sha.startswith(commit or "\0"):
            raise Fail(f"version.json says commit {commit!r}, expected {self.expect_sha[:7]} — the served bundle is "
                       f"not the release that was deployed (stale cache, or the symlink did not flip)")
        return f"web {v.get('version')} ({commit})"

    def web_assets(self) -> str:
        if not self.index_html:
            raise Fail("index.html was not fetched")
        refs = re.findall(r'<script[^>]+src="([^"]+)"', self.index_html)
        refs += re.findall(r'<link[^>]+rel="(?:stylesheet|modulepreload)"[^>]+href="([^"]+)"', self.index_html)
        refs += re.findall(r'<link[^>]+href="([^"]+)"[^>]+rel="(?:stylesheet|modulepreload)"', self.index_html)
        refs = sorted({r for r in refs if not r.startswith(("http:", "https:", "data:"))})
        if not refs:
            raise Fail("index.html references no local scripts or stylesheets")
        bad = []
        for ref in refs:
            r = self.get(f"{self.web}/{ref.lstrip('/')}")
            ct = r.header("Content-Type")
            if r.status != 200 or not ct.startswith(("text/", "application/javascript", "application/x-javascript")):
                bad.append(f"{ref} → {r.status} {ct}")
        if bad:
            raise Fail("; ".join(bad))
        return f"{len(refs)} assets served"

    def web_spa_fallback(self) -> str:
        r = self.expect(self.get(f"{self.web}/courses"), 200, what="GET /courses (deep route)")
        if "text/html" not in r.header("Content-Type"):
            raise Fail("deep route did not return the SPA")
        return "deep route returns the SPA"

    def tls(self) -> str:
        min_days = float(self.cfg.get("tls_min_days", 21))
        out = []
        for url in sorted({self.web, self.api, self.idp}):
            host = urllib.parse.urlparse(url).hostname
            if not host or not url.startswith("https"):
                continue
            days = self.tls_days(host)
            if days < min_days:
                raise Fail(f"{host}: certificate expires in {days:.0f} days (minimum {min_days:.0f}) — renewal is not working")
            out.append(f"{host} {days:.0f}d")
        return ", ".join(out)

    def security_headers(self) -> str:
        r = self.get(f"{self.web}/")
        if not r.header("Strict-Transport-Security"):
            raise Fail("no Strict-Transport-Security header on the web origin")
        return "HSTS present"

    # -- core, anonymous -------------------------------------------------------------------------
    def api_unauth(self) -> str:
        self.expect(self.get(self.v2("/")), 401, what="GET /v2/ without a token")
        return "401 — filter chain up behind the proxy"

    def api_statistics(self) -> str:
        # No body: that is "I have nothing yet, answer now" (statistics.kt). A body is a long poll.
        r = self.expect(self.post_json(self.v2("/unauth/statistics/common"), None), 200,
                        what="POST /v2/unauth/statistics/common")
        s = r.json()
        for k in ("total_submissions", "total_users", "in_auto_assessing"):
            if not isinstance(s.get(k), int):
                raise Fail(f"statistics lacks {k}: {s}")
        return f"{s['total_users']} users, {s['total_submissions']} submissions, {s['in_auto_assessing']} grading now"

    # -- IdP -------------------------------------------------------------------------------------
    def idp_discovery(self) -> str:
        r = self.expect(self.get(f"{self.idp}/realms/{self.realm}/.well-known/openid-configuration"), 200,
                        what="OIDC discovery")
        d = r.json()
        want = f"{self.idp}/realms/{self.realm}"
        if d.get("issuer") != want:
            raise Fail(f"issuer is {d.get('issuer')!r}, core validates {want!r}")
        j = self.expect(self.get(d["jwks_uri"]), 200, what="JWKS")
        if not j.json().get("keys"):
            raise Fail("JWKS has no keys")
        return f"issuer {d['issuer']}, {len(j.json()['keys'])} keys"

    def idp_login(self, who: str):
        return lambda: f"token for {self.secrets[who]['username']}" if self.token(who) else ""

    # -- student ---------------------------------------------------------------------------------
    def student_checkin(self) -> str:
        tok = self.token("student")
        self.expect(self.post_json(self.v2("/account/checkin"),
                                   {"first_name": "Smoke", "last_name": "Student"}, tok), 200,
                    what="student checkin")
        return "200"

    def student_courses(self) -> str:
        r = self.expect(self.get(self.v2("/student/courses"), self.token("student")), 200,
                        what="student course list")
        ids = [c["id"] for c in r.json().get("courses", [])]
        if str(self.cfg["course_id"]) not in ids:
            raise Fail(f"smoke course {self.cfg['course_id']} not among the student's courses {ids}")
        return f"{len(ids)} courses, smoke course present"

    def student_exercises(self) -> str:
        r = self.expect(self.get(self.v2(f"/student/courses/{self.cfg['course_id']}/exercises"),
                                 self.token("student")), 200, what="student exercise list")
        exs = r.json().get("exercises", [])
        ex = next((e for e in exs if e["id"] == str(self.cfg["exercise_id"])), None)
        if ex is None:
            raise Fail(f"smoke exercise {self.cfg['exercise_id']} not in the course's {len(exs)} exercises")
        if ex.get("grader_type") != "AUTO":
            raise Fail(f"smoke exercise is graded {ex.get('grader_type')}, must be AUTO")
        if not ex.get("is_open", True):
            raise Fail("smoke exercise is closed for submissions")
        return f"{ex['effective_title']!r}, AUTO, open"

    def student_exercise_details(self) -> str:
        r = self.expect(self.get(self.v2(f"/student/courses/{self.cfg['course_id']}/exercises/"
                                         f"{self.cfg['exercise_id']}"), self.token("student")), 200,
                        what="student exercise details")
        return f"{len(r.body)} bytes"

    def tagged(self, solution: str, which: str) -> str:
        # A Python comment the grader ignores and this run can recognise its own submission by.
        return f"{solution.rstrip()}\n# easy-smoke {self.nonce} {which}\n"

    def _submit_and_grade(self, which: str) -> dict:
        tok = self.token("student")
        base = self.v2(f"/student/courses/{self.cfg['course_id']}/exercises/{self.cfg['exercise_id']}/submissions")
        solution = self.tagged(self.cfg["solutions"][which], which)
        self.expect(self.post_json(base, {"solution": solution}, tok), 200, what="submit")
        timeout = int(self.cfg.get("grade_timeout_s", 240))
        # /latest/await blocks server-side until the student's newest submission is graded; the
        # client timeout is the cap. It may return for somebody else's submission if two runs
        # overlap, which is why the read below matches on the solution and polls a little longer.
        self.expect(self.get(f"{base}/latest/await", tok, timeout=timeout), 200, what="await grading")
        mine = None
        for _ in range(15):
            subs = self.expect(self.get(f"{base}/all", tok), 200, what="read submissions").json().get("submissions", [])
            mine = next((s for s in subs if s.get("solution") == solution), None)
            if mine is None:
                raise Fail("the submission just made is not in the student's submissions")
            if mine.get("autograde_status") != "IN_PROGRESS":
                break
            self.sleep(2)
        if mine.get("autograde_status") == "FAILED":
            raise Fail(f"autograde FAILED for submission {mine['id']} — executor or grading image problem")
        if mine.get("autograde_status") == "IN_PROGRESS":
            raise Fail(f"submission {mine['id']} still grading after {timeout}s")
        if not mine.get("grade"):
            raise Fail(f"submission {mine['id']} finished ({mine.get('autograde_status')}) but has no grade")
        self.submitted[which] = str(mine["id"])
        return mine

    def autograde_good(self) -> str:
        latest = self._submit_and_grade("good")
        g = latest["grade"]["grade"]
        if g < int(self.cfg.get("good_min_grade", 100)):
            fb = ((latest.get("auto_assessment") or {}).get("feedback") or "")[:200]
            raise Fail(f"known-good solution graded {g}: {fb!r}")
        return f"submission {latest['id']} graded {g} by the executor"

    def autograde_bad(self) -> str:
        latest = self._submit_and_grade("bad")
        g = latest["grade"]["grade"]
        if g >= int(self.cfg.get("good_min_grade", 100)):
            raise Fail(f"known-BAD solution graded {g} — grading cannot fail, which means it proves nothing")
        return f"submission {latest['id']} correctly graded {g} (< full marks)"

    # -- teacher ---------------------------------------------------------------------------------
    def teacher_checkin(self) -> str:
        self.expect(self.post_json(self.v2("/account/checkin"),
                                   {"first_name": "Smoke", "last_name": "Teacher"}, self.token("teacher")), 200,
                    what="teacher checkin")
        return "200"

    def _versions(self) -> dict:
        return self.expect(self.get(self.v2("/versions"), self.token("teacher")), 200, what="GET /v2/versions").json()

    def teacher_versions(self) -> str:
        v = self._versions()
        core = v.get("core") or {}
        if self.expect_sha and not self.expect_sha.startswith(str(core.get("commit") or "\0")):
            raise Fail(f"core reports commit {core.get('commit')!r}, expected {self.expect_sha[:7]} — the running jar "
                       f"is not the deployed release")
        execs = v.get("executors") or []
        if not execs:
            raise Fail("core knows no executors")
        down = [e["name"] for e in execs if not e.get("reachable")]
        if down:
            raise Fail(f"executor(s) unreachable from core: {down}")
        return f"core {core.get('version')} ({core.get('commit')}); " + \
            ", ".join(f"{e['name']} {e.get('version')}" for e in execs)

    def teacher_grading_images(self) -> str:
        # Empty is one of three "cannot say" states in versions.kt (old aae, docker down, no answer),
        # so this warns rather than blocks; the grade round-trip above is the real check.
        execs = (self._versions().get("executors") or [])
        no_images = [e["name"] for e in execs if not e.get("grading_images")]
        if no_images:
            raise Fail(f"executor(s) report no grading images: {no_images}")
        return ", ".join(f"{e['name']} {len(e['grading_images'])} images" for e in execs)

    def teacher_sees_grade(self) -> str:
        r = self.expect(self.get(self.v2(f"/teacher/courses/{self.cfg['course_id']}/exercises/"
                                         f"{self.cfg['exercise_id']}/submissions/latest/students"), self.token("teacher")),
                        200, what="teacher submission summaries")
        student = self.secrets["student"]["username"]
        rows = r.json().get("latest_submissions") or []
        row = next((x for x in rows if x.get("student_id") == student), None)
        if row is None:
            raise Fail(f"the smoke student {student!r} is not on the teacher's roster for the exercise")
        sub = row.get("submission")
        if not sub:
            raise Fail(f"the teacher sees no submission for {student!r} although this run just made one")
        want = self.submitted.get("bad") or self.submitted.get("good")
        if want and str(sub.get("id")) != want:
            raise Fail(f"the teacher's latest submission for {student!r} is {sub.get('id')}, this run's was {want}")
        if not sub.get("grade"):
            raise Fail(f"the teacher sees submission {sub.get('id')} without a grade")
        return f"teacher sees submission {sub['id']} graded {sub['grade'].get('grade')}"

    # -- Thonny plugin contract ------------------------------------------------------------------
    def thonny_token_endpoint(self) -> str:
        # kspar/thonny-easy (easy/ez.py) hardcodes these two paths against the master realm, so
        # they must keep answering even if the SPA's config moves elsewhere. An empty POST is a
        # 400/401 from a live endpoint and a 404 from a moved one.
        origin = urllib.parse.urlparse(self.idp)
        base = f"{origin.scheme}://{origin.netloc}/auth/realms/master/protocol/openid-connect"
        r = self.http("POST", f"{base}/token", headers={"Content-Type": "application/x-www-form-urlencoded"}, data=b"")
        if r.status not in (400, 401):
            raise Fail(f"token endpoint the plugin uses answers {r.status} to an empty POST (wanted 400/401)")
        r2 = self.get(f"{base}/logout")
        if r2.status in (0, 404, 500, 502, 503):
            raise Fail(f"logout endpoint the plugin uses answers {r2.status}")
        return f"token {r.status}, logout {r2.status}"

    def thonny_api_surface(self) -> str:
        # The plugin uses the ordinary student API with an ordinary token — the same call it makes
        # first after login.
        r = self.expect(self.get(self.v2("/student/courses"), self.token("student")), 200,
                        what="plugin's first call (student courses)")
        return f"{len(r.json().get('courses', []))} courses"

    def thonny_keycloak_js(self) -> str:
        origin = urllib.parse.urlparse(self.idp)
        r = self.get(f"{origin.scheme}://{origin.netloc}/auth/js/keycloak.js")
        if r.status != 200:
            raise Fail(f"/auth/js/keycloak.js → {r.status}: the plugin's login page cannot load its adapter (EZ-1803)")
        return "served"

    # -- executor, directly ----------------------------------------------------------------------
    def executor_direct(self) -> str:
        url = self.cfg.get("executor_version_url")
        if not url:
            raise Fail("executor_version_url not configured")
        r = self.expect(self.get(url, timeout=10), 200, what="executor /v1/version")
        v = r.json()
        return f"executor {v.get('version')} ({v.get('commit')}), {len(v.get('grading_images') or [])} images"

    # -- run -------------------------------------------------------------------------------------
    def run(self) -> Report:
        self.check("web: index.html", self.web_index)
        self.check("web: config.json", self.web_config_json)
        self.check("web: version.json matches release", self.web_version)
        self.check("web: assets served", self.web_assets)
        self.check("web: SPA deep route", self.web_spa_fallback)
        self.check("tls: certificates", self.tls)
        self.check("web: security headers", self.security_headers, WARN)
        self.check("core: 401 without token", self.api_unauth)
        self.check("core: public statistics (db read)", self.api_statistics)
        self.check("idp: discovery and JWKS", self.idp_discovery)
        self.check("idp: student login", self.idp_login("student"))
        self.check("idp: teacher login", self.idp_login("teacher"))
        self.check("student: checkin", self.student_checkin)
        self.check("student: courses", self.student_courses)
        self.check("student: exercises", self.student_exercises)
        self.check("student: exercise details", self.student_exercise_details)
        self.check("executor: good solution → full marks", self.autograde_good)
        self.check("executor: bad solution → not full marks", self.autograde_bad)
        self.check("teacher: checkin", self.teacher_checkin)
        self.check("teacher: /v2/versions", self.teacher_versions)
        self.check("teacher: executors report grading images", self.teacher_grading_images, WARN)
        self.check("teacher: sees this run's submission", self.teacher_sees_grade)
        self.check("thonny: token/logout endpoints", self.thonny_token_endpoint)
        self.check("thonny: student API surface", self.thonny_api_surface)
        self.check("thonny: keycloak.js adapter", self.thonny_keycloak_js, WARN)
        if self.cfg.get("executor_version_url"):
            self.check("executor: /v1/version direct", self.executor_direct)
        return self.report


# ---------------------------------------------------------------------------------------------
# entry points
# ---------------------------------------------------------------------------------------------

REQUIRED_SECRETS = ("client_id", "client_secret", "student", "teacher")


def load_secrets(path: str | Path) -> tuple[dict | None, str]:
    """(the smoke accounts, "") — or (None, why) while the file is not usable."""
    p = Path(path)
    try:
        text = p.read_text()
    except FileNotFoundError:
        return None, f"{p} does not exist"
    except OSError as e:
        return None, f"{p} is not readable by this account ({e.strerror}) — run as the rollout account"
    if not text.strip() or "CHANGEME" in text:
        return None, f"{p} still holds the placeholder"
    try:
        s = json.loads(text)
    except ValueError as e:
        return None, f"{p} is not valid JSON: {e}"
    if not isinstance(s, dict):
        return None, f"{p} is not a JSON object"
    missing = [k for k in REQUIRED_SECRETS if not s.get(k)]
    if missing:
        return None, f"{p} lacks {', '.join(missing)}"
    for who in ("student", "teacher"):
        if not isinstance(s[who], dict) or not s[who].get("username") or not s[who].get("password"):
            return None, f"{p}: {who} needs username and password"
    return s, ""


def run(cfg: dict, expect_sha: str | None = None, log=print, http=urllib_http, tls_days=tls_days_left,
        sleep=time.sleep, secrets: dict | None = None, nonce: str | None = None) -> Report:
    if secrets is None:
        secrets, why = load_secrets(cfg.get("secrets_file", "/etc/easy/smoke-secrets.json"))
        if secrets is None:
            r = Report(not_configured=True, reason=why)
            log(r.text())
            return r
    return Smoke(cfg, secrets, expect_sha, log=log, http=http, tls_days=tls_days, sleep=sleep, nonce=nonce).run()


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="easy-smoke", description=__doc__.split("\n\n")[0])
    p.add_argument("--config", default="/etc/easy/rollout.json", help="rollout config; its `smoke` block is used")
    p.add_argument("--expect-sha", default=None, help="the commit the served bundle and core must report")
    p.add_argument("--json", action="store_true")
    args = p.parse_args(argv)
    cfg = json.loads(Path(args.config).read_text())
    smoke_cfg = cfg.get("smoke", cfg)
    report = run(smoke_cfg, expect_sha=args.expect_sha, log=(lambda m: None) if args.json else print)
    print(json.dumps(report.as_dict(), indent=2) if args.json else report.text())
    return 0 if report.ok else 1


if __name__ == "__main__":
    sys.exit(main())
