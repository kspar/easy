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
  * **It leaves nothing behind that anyone else sees.** It only ever writes as two synthetic
    accounts, into one course that exists for it, and the submissions it makes are the only
    writes. No teacher action grades, comments or edits anything.

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
    """Days until the served certificate expires; negative if already expired."""
    ctx = ssl.create_default_context()
    with socket.create_connection((hostname, port), timeout=15) as sock:
        with ctx.wrap_socket(sock, server_hostname=hostname) as tls:
            cert = tls.getpeercert()
    not_after = datetime.strptime(cert["notAfter"], "%b %d %H:%M:%S %Y %Z").replace(tzinfo=timezone.utc)
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

    @property
    def ok(self) -> bool:
        return not self.not_configured and all(c.ok or c.severity == WARN for c in self.checks)

    @property
    def failures(self) -> list[Check]:
        return [c for c in self.checks if not c.ok]

    def text(self) -> str:
        if self.not_configured:
            return "smoke: NOT CONFIGURED — the secrets file is absent or still a placeholder"
        lines = []
        for c in self.checks:
            mark = "ok  " if c.ok else ("warn" if c.severity == WARN else "FAIL")
            lines.append(f"  {mark} {c.name:<32} {c.seconds:5.1f}s  {c.detail}"[:400])
        n_fail = sum(1 for c in self.failures if c.severity == CRITICAL)
        n_warn = sum(1 for c in self.failures if c.severity == WARN)
        lines.append(f"smoke: {'PASS' if self.ok else 'FAIL'} — {len(self.checks)} checks, "
                     f"{n_fail} failed, {n_warn} warnings")
        return "\n".join(lines)

    def as_dict(self) -> dict:
        return {"ok": self.ok, "not_configured": self.not_configured,
                "checks": [c.__dict__ for c in self.checks]}


class Fail(Exception):
    """A check that decided it failed, with the reason."""


# ---------------------------------------------------------------------------------------------
# the suite
# ---------------------------------------------------------------------------------------------

class Smoke:
    def __init__(self, cfg: dict, secrets: dict, expect_sha: str | None, log=print,
                 http=urllib_http, tls_days=tls_days_left, sleep=time.sleep):
        self.cfg, self.secrets, self.expect_sha = cfg, secrets, (expect_sha or "")
        self.log, self.http, self.tls_days, self.sleep = log, http, tls_days, sleep
        self.report = Report()
        self.web = cfg["web_url"].rstrip("/")
        self.api = cfg["api_url"].rstrip("/")
        self.idp = cfg["idp_url"].rstrip("/")          # includes the /auth prefix, as config.json does
        self.realm = cfg.get("realm", "master")
        self.tokens: dict[str, str] = {}
        self.index_html = ""

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

    def get(self, url: str, token: str | None = None, **kw) -> Response:
        h = {"Authorization": f"Bearer {token}"} if token else {}
        return self.http("GET", url, headers=h, **kw)

    def post_json(self, url: str, body, token: str | None = None, **kw) -> Response:
        h = {"Content-Type": "application/json"}
        if token:
            h["Authorization"] = f"Bearer {token}"
        return self.http("POST", url, headers=h, data=json.dumps(body).encode(), **kw)

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
        if (c.get("emsRoot") or "").rstrip("/") != self.api:
            raise Fail(f"emsRoot is {c.get('emsRoot')!r}, this environment's API is {self.api}")
        kc = c.get("keycloak") or {}
        if (kc.get("url") or "").rstrip("/") != self.idp or kc.get("realm") != self.realm:
            raise Fail(f"keycloak {kc.get('url')!r}/{kc.get('realm')!r} does not match {self.idp}/{self.realm}")
        return f"emsRoot {c['emsRoot']}, realm {kc['realm']}"

    def web_assets(self) -> str:
        if not self.index_html:
            raise Fail("index.html was not fetched")
        refs = re.findall(r'<script[^>]+src="([^"]+)"', self.index_html)
        refs += re.findall(r'<link[^>]+rel="stylesheet"[^>]+href="([^"]+)"', self.index_html)
        refs += re.findall(r'<link[^>]+href="([^"]+)"[^>]+rel="stylesheet"', self.index_html)
        refs = sorted({r for r in refs if not r.startswith(("http:", "https:", "data:"))})
        if not refs:
            raise Fail("index.html references no local scripts or stylesheets")
        bad = []
        sha_seen = False
        for ref in refs:
            url = ref if ref.startswith("http") else f"{self.web}/{ref.lstrip('/')}"
            r = self.get(url)
            ct = r.header("Content-Type")
            if r.status != 200 or not (ct.startswith(("text/", "application/javascript", "application/x-javascript"))):
                bad.append(f"{ref} → {r.status} {ct}")
            elif self.expect_sha and self.expect_sha[:7] in r.text():
                sha_seen = True
        if bad:
            raise Fail("; ".join(bad))
        if self.expect_sha and not sha_seen:
            raise Fail(f"none of {len(refs)} assets contains the commit {self.expect_sha[:7]} — the served bundle "
                       f"is not the release that was deployed (stale cache, or the symlink did not flip)")
        return f"{len(refs)} assets served" + (f", bundle stamped {self.expect_sha[:7]}" if sha_seen else "")

    def web_spa_fallback(self) -> str:
        r = self.expect(self.get(f"{self.web}/courses"), 200, what="GET /courses (deep route)")
        if "text/html" not in r.header("Content-Type"):
            raise Fail("deep route did not return the SPA")
        return "deep route returns the SPA"

    def tls(self) -> str:
        min_days = float(self.cfg.get("tls_min_days", 21))
        out = []
        for url in {self.web, self.api, self.idp}:
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
        self.expect(self.get(f"{self.api}/v2/"), 401, what="GET /v2/ without a token")
        return "401 — filter chain up behind the proxy"

    def api_statistics(self) -> str:
        r = self.expect(self.post_json(f"{self.api}/v2/unauth/statistics/common", {}), 200,
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
        self.expect(self.post_json(f"{self.api}/v2/account/checkin",
                                   {"first_name": "Smoke", "last_name": "Student"}, tok), 200,
                    what="student checkin")
        return "200"

    def student_courses(self) -> str:
        r = self.expect(self.get(f"{self.api}/v2/student/courses", self.token("student")), 200,
                        what="student course list")
        ids = [c["id"] for c in r.json().get("courses", [])]
        if str(self.cfg["course_id"]) not in ids:
            raise Fail(f"smoke course {self.cfg['course_id']} not among the student's courses {ids}")
        return f"{len(ids)} courses, smoke course present"

    def student_exercises(self) -> str:
        r = self.expect(self.get(f"{self.api}/v2/student/courses/{self.cfg['course_id']}/exercises",
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
        r = self.expect(self.get(f"{self.api}/v2/student/courses/{self.cfg['course_id']}/exercises/"
                                 f"{self.cfg['exercise_id']}", self.token("student")), 200,
                        what="student exercise details")
        return f"{len(r.body)} bytes"

    def _submit_and_grade(self, solution: str) -> dict:
        tok = self.token("student")
        base = f"{self.api}/v2/student/courses/{self.cfg['course_id']}/exercises/{self.cfg['exercise_id']}/submissions"
        self.expect(self.post_json(base, {"solution": solution}, tok), 200, what="submit")
        timeout = int(self.cfg.get("grade_timeout_s", 240))
        # /latest/await blocks server-side until grading finishes; the client timeout is the cap.
        self.expect(self.get(f"{base}/latest/await", tok, timeout=timeout), 200, what="await grading")
        # A few more polls after the await returns, for the status write that lands just after it.
        for _ in range(15):
            subs = self.expect(self.get(f"{base}/all", tok), 200, what="read submissions").json().get("submissions", [])
            if not subs:
                raise Fail("no submissions returned after submitting")
            latest = max(subs, key=lambda s: s["number"])
            if latest.get("autograde_status") != "IN_PROGRESS":
                break
            self.sleep(2)
        if latest.get("autograde_status") == "FAILED":
            raise Fail(f"autograde FAILED for submission {latest['id']} — executor or grading image problem")
        if latest.get("autograde_status") == "IN_PROGRESS":
            raise Fail(f"submission {latest['id']} still grading after {timeout}s")
        if not latest.get("grade"):
            raise Fail(f"submission {latest['id']} finished ({latest.get('autograde_status')}) but has no grade")
        return latest

    def autograde_good(self) -> str:
        latest = self._submit_and_grade(self.cfg["solutions"]["good"])
        g = latest["grade"]["grade"]
        if g < int(self.cfg.get("good_min_grade", 100)):
            fb = ((latest.get("auto_assessment") or {}).get("feedback") or "")[:200]
            raise Fail(f"known-good solution graded {g}: {fb!r}")
        return f"submission {latest['id']} graded {g} by the executor"

    def autograde_bad(self) -> str:
        latest = self._submit_and_grade(self.cfg["solutions"]["bad"])
        g = latest["grade"]["grade"]
        if g >= int(self.cfg.get("good_min_grade", 100)):
            raise Fail(f"known-BAD solution graded {g} — grading cannot fail, which means it proves nothing")
        return f"submission {latest['id']} correctly graded {g} (< full marks)"

    # -- teacher ---------------------------------------------------------------------------------
    def teacher_checkin(self) -> str:
        self.expect(self.post_json(f"{self.api}/v2/account/checkin",
                                   {"first_name": "Smoke", "last_name": "Teacher"}, self.token("teacher")), 200,
                    what="teacher checkin")
        return "200"

    def teacher_versions(self) -> str:
        r = self.expect(self.get(f"{self.api}/v2/versions", self.token("teacher")), 200, what="GET /v2/versions")
        v = r.json()
        core = v.get("core") or {}
        parts = [f"core {core.get('version')} ({core.get('commit')})"]
        if self.expect_sha and not self.expect_sha.startswith(str(core.get("commit") or "\0")):
            raise Fail(f"core reports commit {core.get('commit')!r}, expected {self.expect_sha[:7]} — the running jar "
                       f"is not the deployed release")
        execs = v.get("executors") or []
        if not execs:
            raise Fail("core knows no executors")
        down = [e["name"] for e in execs if not e.get("reachable")]
        if down:
            raise Fail(f"executor(s) unreachable from core: {down}")
        no_images = [e["name"] for e in execs if not e.get("grading_images")]
        if no_images:
            raise Fail(f"executor(s) report no grading images: {no_images}")
        parts.append(", ".join(f"{e['name']} {e.get('version')} {len(e['grading_images'])} images" for e in execs))
        return "; ".join(parts)

    def teacher_sees_grade(self) -> str:
        r = self.expect(self.get(f"{self.api}/v2/teacher/courses/{self.cfg['course_id']}/exercises/"
                                 f"{self.cfg['exercise_id']}/submissions/latest/students", self.token("teacher")),
                        200, what="teacher submission summaries")
        text = r.text()
        student = self.secrets["student"]["username"]
        if student not in text:
            raise Fail(f"the smoke student {student!r} does not appear in the teacher's latest-submissions view")
        return "teacher sees the student's submission"

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
        r = self.expect(self.get(f"{self.api}/v2/student/courses", self.token("student")), 200,
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
        self.check("web: assets and version stamp", self.web_assets)
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
        self.check("teacher: sees student's submission", self.teacher_sees_grade)
        self.check("thonny: token/logout endpoints", self.thonny_token_endpoint)
        self.check("thonny: student API surface", self.thonny_api_surface)
        self.check("thonny: keycloak.js adapter", self.thonny_keycloak_js, WARN)
        if self.cfg.get("executor_version_url"):
            self.check("executor: /v1/version direct", self.executor_direct)
        return self.report


# ---------------------------------------------------------------------------------------------
# entry points
# ---------------------------------------------------------------------------------------------

REQUIRED_SECRETS = ("client_id", "student", "teacher")


def load_secrets(path: str | Path) -> dict | None:
    """The smoke accounts, or None while the file is absent or still the role's placeholder."""
    p = Path(path)
    if not p.exists():
        return None
    text = p.read_text()
    if "CHANGEME" in text or not text.strip():
        return None
    s = json.loads(text)
    if any(k not in s for k in REQUIRED_SECRETS):
        return None
    for who in ("student", "teacher"):
        if not s[who].get("username") or not s[who].get("password"):
            return None
    return s


def run(cfg: dict, expect_sha: str | None = None, log=print, http=urllib_http, tls_days=tls_days_left,
        sleep=time.sleep, secrets: dict | None = None) -> Report:
    secrets = secrets if secrets is not None else load_secrets(cfg.get("secrets_file", "/etc/easy/smoke-secrets.json"))
    if secrets is None:
        r = Report(not_configured=True)
        log(r.text())
        return r
    return Smoke(cfg, secrets, expect_sha, log=log, http=http, tls_days=tls_days, sleep=sleep).run()


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
