# Bug reporting from the app

EZ-1786. A signed-in user opens the account menu, picks **Report a bug**, types into one box, and
optionally attaches the last half hour of what their browser was doing. Core stores that, emails the
admin, and — where it is configured to — files a YouTrack issue restricted to the team.

Before this, the only route offered was prose on the About page pointing at Discord. That lost the
two things that make a report actionable, which build and what the app was doing, and put triage in
a chat channel instead of the tracker.

## 0. What this replaced, and what it did not

There is a **second, older path** that looks like this one and is not: `POST /v2/management/log`
(`core/ems/service/management/report_client_log.kt` → table `log_report`). That is the client
noticing something on its own and saying so. It has no caller in the React app — it is a leftover
from the deleted Kotlin-JS UI — and it is deliberately untouched.

The distinction is worth keeping because the two tables are read for different reasons. A `log_report`
row is machinery talking and nobody promised to read it. A `bug_report` row is a person asking for
help, so it needs an outcome: an issue id, or a record of why there isn't one. Those columns have no
business on `log_report`, which `/v2/account/export` already hands back to the user who asked what we
hold about them.

Two habits of the older endpoint are deliberately **not** copied:

- **`@Async` on the controller method.** Spring's proxy answers 200 before any work happens and drops
  every exception into the executor, so a validation failure and a successful write are
  indistinguishable to the client. Here the async boundary is one level in, on the forwarding service.
- **Unthrottled admin mail on a path any student can drive.** `report_client_log`'s own comment says
  this out loud. Hence §3.

## 1. The shape of it

```
account menu ──▶ BugReportDialog ──▶ POST /v2/bug-reports
                                          │
                            ┌─────────────┴──────────────┐
                            ▼                            ▼
                     insert bug_report            (returns { id })
                     (committed first)
                            │
              ┌─────────────┴─────────────┐
              ▼                           ▼
     @Async forward to YouTrack     admin email
     (issue id written back)        (always, whatever forwarding does)
              │
              ▼
     @Scheduled retry sweep for what did not land
```

The ordering inside the handler is the design, not an accident of writing it:

1. **Rate-limit** (§3), before anything is stored.
2. **Insert, synchronously.** The row is the report. Everything after it is a best-effort delivery of
   something already safe.
3. **Return the id.** The reporter gets a receipt they can quote.
4. **Forward and email**, both `@Async`, so neither delays the response.

So a YouTrack outage delays a delivery; it never loses a report. And the admin email means nobody has
to be watching the tracker for a report to be noticed.

Files: `core/ems/service/bugreport/CommonCreateBugReport.kt` (endpoint),
`BugReportForwardService.kt` (state machine), `YouTrackService.kt` (HTTP),
`web/src/features/bug-report/` (dialog and activity buffer),
`web/src/components/ErrorBoundary.tsx` and `CrashScreen.tsx`.

## 2. What a report carries

| Field | From | Notes |
| --- | --- | --- |
| `message` | the reporter | Free text, max 5000. The only required field. |
| `diagnostics` | their browser | The activity log (§4). **Null means they declined**, which is not the same as empty. |
| `page_url` | the client | Path and query string. No hash — that is a scroll position. |
| `web_version` | the client | `4.0 (b14b916)`, from the build constants. |
| `user_agent` | the client | The browser's own word. |
| reporter, timestamp, core build | **the server** | Never the client's word for it. |

The last row matters. The YouTrack description is assembled server-side from `caller.id`,
`caller.email`, `caller.roles` and `BuildProperties`, so the identity on an issue is what the token
said, not what a request body claimed. The three client-supplied fields are labelled as reported
rather than presented as fact.

## 3. The rate limit

An hourly cap per caller, read from `system_configuration` under `bug_report_max_per_hour`, and
**defaulting to 10 when the row is absent** rather than to unlimited. Over the cap the endpoint
returns 400 `BUG_REPORT_RATE_LIMITED` and stores nothing — a limit that counted the rejected attempt
would lock someone out for an hour on their first retry.

This exists because the endpoint is open to every signed-in role and fans out to an email and to a
tracker. An issue tracker is considerably harder to tidy up afterwards than an inbox.

Tuning it needs no redeploy:

```sql
INSERT INTO system_configuration (key, value) VALUES ('bug_report_max_per_hour', '25')
  ON CONFLICT (key) DO UPDATE SET value = excluded.value;
```

## 4. The activity log

`web/src/features/bug-report/breadcrumbs.ts`. A capped ring buffer with four sources, each hooked at
the one place the codebase already funnels it:

| Source | Hooked in | Recorded |
| --- | --- | --- |
| Uncaught errors, unhandled rejections | `main.tsx` | Message and the first five stack frames |
| `console.error` / `console.warn` | `main.tsx` | Patched, always forwarding to the real console |
| Failed API calls | `api/client.ts`, at the throw site | Method, path, status, **and core's error id** |
| Navigation | `AppLayout` | Path and query string |

Caps: **200 entries, 30 minutes, 300 characters each**, oldest evicted first. Each bounds a different
failure — a render loop, a tab left open for a week, one enormous serialised object.

### 4.1 Why the error id is the point

Core's exception handler mints a UUID and writes **the same value** to three places: its own log line,
the `RequestErrorResponse.id` returned to the browser, and the admin notification email. Recording
those ids client-side therefore turns a report into an exact grep key:

```sh
sudo easy-core-log --since '2026-08-23 09:00' -n 20000 | grep e7c1a9d0-…
```

That is the whole of the log-correlation story, and it needed no new infrastructure. Core has no MDC,
no request id and no correlation id — `core/ems/service/request_logger.kt` is entirely commented out
— and this feature deliberately did not add one. Without a report to anchor it, a request id would be
a per-request cost paid for a search nobody was going to run.

Retention sets the outer bound on how long this stays useful: journald is size-capped at 500 MB, and
the rolling file keeps 180 days under a 2 GB cap.

### 4.2 sessionStorage, and the two alternatives that are worse

The buffer persists to `sessionStorage`, which is neither of the obvious choices:

- **In memory** is empty after the reload a crash usually prompts, which is exactly when the reporter
  is standing on the page that lost it.
- **`localStorage`** outlives the session that produced it and is shared across tabs. This holds
  someone's console output and the pages they visited; per-tab, gone with the tab, is the right
  lifetime for that.

Reads and writes are both guarded, for the same reasons `web/src/api/localStorage.ts` documents:
storage throws in private windows, inside an iframe with third-party cookies blocked, and on a full
quota. This code runs inside a `console.error` patch, where a throw would turn one logged error into
two from a place nothing is watching.

### 4.3 Redaction

Bearer- and JWT-shaped strings are replaced **on the way in**, not on the way out. `AuthContext.tsx`
calls `console.error` when a token refresh fails, so a token reaching this buffer is not
hypothetical, and one should never sit in storage waiting for somebody to decide whether to send it.

### 4.4 Consent

The checkbox starts **ticked**, and beside it is an expander holding the exact string the request will
carry — produced by the same function, asserted equal by the browser spec.

That equality is the point rather than a nicety. A checkbox next to a *description* of what will be
sent asks someone to agree to something they cannot see. If the two ever diverge, make them the same
again; do not write a better summary.

Ticked-by-default is a judgement call. A strict reading of opt-in starts it unticked, and the result
is reports with no diagnostics at all — which is the situation this feature exists to replace. Full
disclosure is what makes ticked-by-default fair, and unticking it stores null rather than empty.

## 5. YouTrack

Off by default. Five settings in `application.yaml` and one in `secrets.yaml`:

```yaml
easy:
  core:
    youtrack:
      enabled: true
      base-url: "https://easy.youtrack.cloud"
      project-id: "<internal id>"
      visibility-group-id: "<internal id>"
      retry-cron: "0 20 * * * *"
```

**The token goes in `secrets.yaml` and nowhere else.** `core_config` greps the config file it writes
for any key matching `password|secret|token` and fails the play if one appears; ansible creates the
key with a `CHANGEME` placeholder and never reads its value. See `ansible/README.md` on why the
controller holds no credentials at all.

A blank id, or a token still on its `CHANGEME` placeholder, is treated as **not configured**: core
logs a warning naming what is missing and stores the report without forwarding. A half-configured
integration must not turn a bug report into a 500 — the report is the thing worth keeping.

### 5.1 The two ids, and how to find them

The REST API does not accept `EZ` or `EZ Team`. It wants internal ids, and resolving them at runtime
would need admin-read scope this token has no reason to hold, so they are configuration:

```sh
# project-id
curl -s -H "Authorization: Bearer $TOKEN" \
  'https://easy.youtrack.cloud/api/admin/projects?fields=id,shortName' | jq '.[] | select(.shortName=="EZ")'

# visibility-group-id
curl -s -H "Authorization: Bearer $TOKEN" \
  'https://easy.youtrack.cloud/api/admin/groups?fields=id,name' | jq '.[] | select(.name=="EZ Team")'
```

### 5.2 Every issue is restricted, and that is not configurable

```json
"visibility": { "$type": "LimitedVisibility", "permittedGroups": [{ "id": "…" }] }
```

There is no setting for "file it publicly", and `YouTrackService.createIssue` takes no visibility
parameter. The reasoning, since a future reader will be tempted to add one:

`easy.youtrack.cloud` is **public, with guest access**. The free-text box is unbounded, and what goes
into it is the most sensitive category of content we hold: a student pasting their own submission, a
teacher quoting feedback they wrote about a named person. `doc/dev-environment.md` §3.3 makes the
general point — pseudonymising the account a piece of prose points at does not anonymise the prose,
because "you have failed this three times now, come see me" is about a real person and identifiable to
anyone who knows the course and the dates.

Two rejected alternatives, so nobody has to re-derive them:

- **Public unless diagnostics were attached.** Gates the wrong field. The activity log is machine
  output; the text box is where a person writes about people.
- **A config key defaulting to restricted.** A default is a thing that gets overridden. There is no
  environment where publishing one of these is correct, so there is nothing to configure.

Getting `visibility-group-id` wrong does not make an issue public — YouTrack rejects the request. That
is the right direction for this to fail in.

**Verify it the only way that counts:** file one report, then open the issue **while logged out** (or
as `guest`) and confirm it is not visible. A restricted issue that is actually public is the one
failure mode here that cannot be walked back.

### 5.3 Custom fields are not set

Filing these as `Type: Bug` needs the per-field `$type` discriminator and a bundle lookup — a lot of
moving parts for a field triage sets in one click. The project default applies. Worth revisiting if
the volume ever makes hand-triage tedious.

### 5.4 Forward state, and what the sweep retries

`yt_state` on each row:

| State | Meaning |
| --- | --- |
| `PENDING` | Stored, not yet attempted |
| `SENT` | Filed; `yt_issue_id` holds the readable id |
| `FAILED` | Attempted and refused; `yt_error` holds why, `yt_attempts` counts |
| `DISABLED` | Forwarding was off when it arrived |

The sweep retries `FAILED` **and** `PENDING`, under five attempts and older than five minutes. Picking
up `PENDING` matters more than it looks: such a row with zero attempts is a report that arrived and
then core restarted before its `@Async` call ran, which is the one failure mode that leaves no error
message anywhere.

`DISABLED` is never retried. Those reports were taken while forwarding was off, and someone enabling
it is asking for new reports to be filed, not for the archive to arrive at once. Re-filing an old one
is a manual `UPDATE`, which is the right amount of friction.

## 6. Personal data

A `bug_report` row is personal data tied to an account, and the `diagnostics` column is the reason it
matters most: console output gathered from someone's own browser is exactly what a subject access
request is asking about. So the table is wired into all three places `log_report` already is:

| Where | What |
| --- | --- |
| `ExportPersonalData.kt` | `bug_reports.json` in the `/v2/account/export` zip, including `diagnostics` and the issue id |
| `delete_inactive_users.kt` | Rows deleted with the account |
| `doc/core/anonymise-db/anonymise.sql` | `DELETE FROM bug_report;` |

The YouTrack issues these produced are **not** deleted with the account. Those are ours, they are
already restricted to the team, and an issue that has lost its reporter is still a bug worth fixing.

Anonymisation deletes rather than pseudonymises, which also means a dev core restored from a
production dump can never re-file somebody's real bug report into the tracker.

## 7. Dev is deliberately off

Not for safety — the tracker is ours and an issue is harmless — but for noise. Dev exists to be poked
at, every button pressed twice and every form submitted with nonsense, and routing that into EZ would
bury real reports under test ones. Nothing in a filed issue distinguishes them: it records who
reported it, not which environment they were on.

So dev exercises everything up to the HTTP call — the dialog, the endpoint, the row, the `DISABLED`
state, the admin mail. The one step it does not cover is the one that has to be checked against the
real tracker anyway (§5.2).

## 8. Testing

| Suite | Covers |
| --- | --- |
| `core/.../bugreport/BugReportApiTest.kt` | Storage, roles, blank message, null-vs-empty diagnostics, the `DISABLED` path, and the rate limit — including that an absent config row means ten and not unlimited |
| `web/tests/unit/breadcrumbs.test.mjs` | The three caps, eviction order, redaction, surviving a reload, and a throwing storage losing breadcrumbs and nothing else |
| `web/tests/browser/bug-report.spec.mjs` | The dialog, the pre-checked box, **shown text equals sent text**, declined sends no key, and the rate limit reading as a wait |

The browser spec stubs a 400 carrying a known error id and asserts that id appears in the posted
payload. That positive case is load-bearing: an activity log that is always empty looks exactly like
one that is working and had nothing to say.

No wall clock in any of it. The rate-limit test fills the cap inside one test rather than moving time,
which `NoWallClockInFixturesTest` would refuse anyway.
