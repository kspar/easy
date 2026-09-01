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

**One caveat on that last sentence, because it is the safety net everything else leans on.** The email
goes through `SendMailService.sendSystemNotification`, which returns quietly — logging at `info` —
when `easy.core.mail.sys.enabled` is false or `easy.core.mail.sys.to` is unset. On an environment
without system mail configured *and* with forwarding off, a report lands in the table and nothing
announces it. The row is still there and `/v2/account/export` still returns it, so nothing is lost,
but "somebody finds out" becomes "somebody runs a query". An environment that wants this feature
should have one of the two delivery paths actually working.

Files: `core/ems/service/bugreport/CommonCreateBugReport.kt` (endpoint),
`BugReportForwardService.kt` (state machine), `YouTrackService.kt` (HTTP),
`web/src/features/bug-report/` (dialog and activity buffer),
`web/src/components/ErrorBoundary.tsx` and `CrashScreen.tsx`.

## 2. What a report carries

| Field | From | Notes |
| --- | --- | --- |
| `message` | the reporter | Free text, max 5000. The only required field. |
| `diagnostics` | their browser | The context header and the activity log (§4). **Null means they declined**, which is not the same as empty. |
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

## 4. The context header and the activity log

`diagnostics` is two things joined by a blank line: a header saying what the world looked like, and
a log saying what happened in it.

### 4.0 The context header

`web/src/features/bug-report/reportContext.ts`. Aligned plain text, because the audience is a person
reading a YouTrack issue:

```
filed         2026-09-01T13:37:58.506Z (Europe/Tallinn)
page          /courses/12/exercises/34?tab=testing
tab open      41m 12s, currently visible
web build     4.0 (559bfef), built 2026-09-01T13:37:46.700Z
deployed      4.1 (aa12cd3)  ← THIS TAB IS RUNNING AN OLDER BUILD
environment   DEV
core at       https://dev.core.lahendus.ut.ee/v2
idp           https://idp.lahendus.ut.ee/auth/ realm master
account       someone
role          student of admin, teacher, student
session       authenticated and checked in
language      et (browser en-GB)
theme         dark
screen        1512×857 viewport, 1512×982 screen @2×
network       online, 4g, 10 Mbps, 50 ms rtt
storage       local ok, session unavailable, cookies enabled
user agent    Mozilla/5.0 …
```

Three of those rows have each been the entire content of a bug:

- **`deployed`** appears only when this tab is behind, and it ends a triage on its own. The reporter
  cannot know it — that is what the update banner exists for — and "the fix is deployed, this tab
  has never loaded it" is otherwise indistinguishable from the fix not working.
- **`role`** says *which of* the available roles is in use. A teacher looking at the student view and
  reporting a missing page is one of the commonest false bugs there is.
- **`storage unavailable`** is the invisible cause behind a family of login and lost-work reports:
  the breadcrumb buffer, the remembered role and the 401-recovery guard all degrade silently when
  storage throws.

Half of this is React state and half is global, and the whole thing has to be producible from
`ErrorBoundary` — which sits outside the router and outside `AuthProvider`, deliberately, so it
survives a throw in either. So the React side **pushes** into a module-level registry
(`updateReportContext`) from effects, and a field nobody pushed is simply absent rather than printed
as `undefined`. An absent row is the honest rendering of "the app never got that far".

Core's own build is not in here. The server adds it (§2) — it is the one field the browser has no
business claiming.

### 4.1 The activity log

`web/src/features/bug-report/breadcrumbs.ts`. A capped ring buffer. Every source is hooked at the
one place the codebase already funnels it, which is why this stays cheap to keep correct:

| Kind | Hooked in | Recorded |
| --- | --- | --- |
| `error` | `main.tsx`, `ErrorBoundary`, `RouteCrash` | Uncaught throws, rejections, render errors, with the first few stack frames |
| `console` | `main.tsx` | `console.error` / `console.warn`, patched, always forwarding to the real console |
| `api` | `api/client.ts` | **Every failure**, every write, every network error, and any read over 5s |
| `route` | `AppLayout` + page load | Path and query string |
| `auth` | `AuthContext`, `QueryProvider` | check-sso, sign-in with roles, checkin, every refresh, expiry, 401s, IdP redirects, role switches |
| `action` | the feature that did it | Merge conflicts by field, draft-save failures, submissions, the error message the user was actually shown |
| `state` | `main.tsx`, `UpdateAvailableBanner` | Offline/online, tab visibility, a newer build deployed underneath |

Caps: **400 entries, 30 minutes, 400 characters each**, oldest evicted first, and **40 000 characters
serialised**. Each bounds a different failure — a render loop, a tab left open for a week, one
enormous serialised object, and the four of them multiplied together.

That last cap is the one with a scar. Only the server could see it: `MAX_ENTRIES × MAX_TEXT` is well
over core's `@Size` on the column, so a busy session produced a payload that came back **400, report
discarded** — for the reporter whose session had the most to say. The serialiser now trims from the
old end and says `… N earlier entries trimmed` rather than silently starting mid-story.

#### What is deliberately not recorded

Successful reads, and the successes of three endpoints this app calls on a timer: the statistics
long poll, the draft autosave, and the notifications poll (`isRepeating` in `api/client.ts`). A
student coding for half an hour generates hundreds of draft saves, and four hundred slots spent on
those are four hundred slots not holding the route, auth and error lines a report exists for.

**Only their successes.** A *failed* draft save is the single most valuable line this buffer can
hold — it is the state behind every "my code disappeared" report — and failures are recorded for
every path, unconditionally.

Aborts are not recorded either: react-query cancels in-flight queries on unmount, so every
navigation aborts something, and those read as network failures in a report where nothing failed.

### 4.2 Why the error id is the point

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

### 4.3 sessionStorage, and the two alternatives that are worse

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

### 4.4 Redaction

Bearer- and JWT-shaped strings are replaced **on the way in**, not on the way out. `AuthContext.tsx`
calls `console.error` when a token refresh fails, so a token reaching this buffer is not
hypothetical, and one should never sit in storage waiting for somebody to decide whether to send it.

**OAuth query parameters too**, since the buffer began recording the URL a page loaded on — and one
of those URLs is the IdP callback, carrying `?code=…&state=…&session_state=…`. The authorization
code is single-use and spent by the time anyone reads the report, which is not a reason to write a
credential into a database, an email and an issue tracker. The parameter name survives, so the line
still reads as a callback.

That pattern is anchored on the `?`/`&`/`#` that starts a query parameter rather than on a word
boundary, and the anchoring is load-bearing: `code` and `state` are ordinary words in a log about an
editor and a state machine, and an unanchored rule would quietly eat sentences containing them.

The context header is redacted on the way *out* as well, because it is assembled locally rather than
arriving through `record`.

### 4.5 Consent

The checkbox starts **ticked**, and beside it is an expander holding the exact string the request will
carry — produced by the same function, asserted equal by the browser spec.

That equality is the point rather than a nicety. A checkbox next to a *description* of what will be
sent asks someone to agree to something they cannot see. If the two ever diverge, make them the same
again; do not write a better summary.

Ticked-by-default is a judgement call. A strict reading of opt-in starts it unticked, and the result
is reports with no diagnostics at all — which is the situation this feature exists to replace. Full
disclosure is what makes ticked-by-default fair, and unticking it stores null rather than empty.

The label reads "Include my recent activity **and technical details**". It used to stop at
"activity", which was accurate until the context header (§4.0) added the account name, the browser,
the screen and the network to what the checkbox gates. A checkbox that undersells what it covers is
the same failure as one with no disclosure behind it, only quieter.

Opening the disclosure also scrolls it into view. `DialogContent` is the scroll container and the
panel opens below its fold, so expanding it used to reveal the first two lines of something the
reporter was being asked to consent to — and the browser spec asserts the panel is fully on screen,
not merely present, because a zero-height panel and a working one look identical to a count.

## 5. YouTrack

Off by default. Five settings in `application.yaml` and one in `secrets.yaml`:

```yaml
easy:
  core:
    youtrack:
      enabled: true
      base-url: "https://easy.youtrack.cloud"
      project-id: "0-0"                 # EZ
      visibility-group-id: "542-0"      # EZ Team
      issue-type-id: "81-29"            # Type = User-submitted issue
      retry-cron: "0 20 * * * *"
```

Those three ids are EZ's real ones, confirmed against the API on 2026-08-23. They are recorded here
because they are neither secret nor guessable, and because the alternative is every operator
rediscovering §5.1.

**The token goes in `secrets.yaml` and nowhere else.** `core_config` greps the config file it writes
for any key matching `password|secret|token` and fails the play if one appears; ansible creates the
key with a `CHANGEME` placeholder and never reads its value. See `ansible/README.md` on why the
controller holds no credentials at all.

A blank id, or a token still on its `CHANGEME` placeholder, is treated as **not configured**: core
logs a warning naming what is missing and stores the report without forwarding. A half-configured
integration must not turn a bug report into a 500 — the report is the thing worth keeping.

### 5.1 The three ids, and how to find them

The REST API does not accept `EZ`, `EZ Team` or `User-submitted issue`. It wants internal ids, and
keeping them as configuration means no lookup call, no admin-read scope on the token, and no startup
dependency on the tracker being reachable.

```sh
# project-id  →  0-0
curl -s -H "Authorization: Bearer $TOKEN" \
  'https://easy.youtrack.cloud/api/admin/projects?fields=id,shortName' | jq '.[] | select(.shortName=="EZ")'

# visibility-group-id  →  542-0
# NOTE: /api/groups, NOT /api/admin/groups. See the trap below.
curl -s -H "Authorization: Bearer $TOKEN" \
  'https://easy.youtrack.cloud/api/groups?fields=id,name' | jq '.[] | select(.name=="EZ Team")'

# issue-type-id  →  81-29
curl -s -H "Authorization: Bearer $TOKEN" \
  'https://easy.youtrack.cloud/api/admin/customFieldSettings/bundles/enum?fields=id,name,values(id,name)' \
  | jq '.[].values[]? | select(.name=="User-submitted issue")'
```

Or from a browser: YouTrack accepts an admin's existing session cookie on GET, so pasting any of
those URLs (without the `jq`) into a logged-in tab returns the JSON directly — no token, no shell.

**The trap.** `EZ Team` is a **`ProjectTeam`**, not a plain `UserGroup`. So
`GET /api/admin/groups/542-0` answers **404**, which reads exactly like a wrong id and cost one wrong
conclusion on 2026-08-23. Two paths that do find it:

```sh
curl -s -H "Authorization: Bearer $TOKEN" 'https://easy.youtrack.cloud/api/groups?fields=id,name'
curl -s -H "Authorization: Bearer $TOKEN" \
  'https://easy.youtrack.cloud/api/admin/projects/0-0?fields=team(id,name)'
```

A `ProjectTeam` works perfectly well in `permittedGroups` — it is a `UserGroup` subtype. The 404 is
about which admin collection it belongs to, nothing more.

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

**Verified against the real tracker on 2026-08-23**, and this is the only way that counts. A test
issue was posted to EZ with the exact body §5.3 shows, then read twice with no `Authorization`
header:

| Request, unauthenticated | Result |
| --- | --- |
| The restricted test issue | **404** |
| EZ-1786, an ordinary unrestricted issue in the same project | **200** |

The second row is the half that makes the first meaningful. A 404 on its own would equally well mean
a malformed request, a wrong id, or an instance with guest access switched off — the 200 proves guest
really can read EZ, so the 404 is the restriction doing its job. The test issue was deleted
afterwards.

Repeat both rows, in that order, whenever the group id or the visibility payload changes. A restricted
issue that is actually public is the one failure here that cannot be walked back, and
`YouTrackRequestBodyTest` guards the payload but cannot tell you what YouTrack did with it.

### 5.3 One custom field is set: `Type`

Filed issues get `Type: User-submitted issue` (bundle element `81-29`):

```json
"customFields": [
  { "$type": "SingleEnumIssueCustomField", "name": "Type", "value": { "id": "81-29" } }
]
```

The first version of this set no custom fields at all, reasoning that a bundle lookup was a lot of
moving parts for a field triage sets in one click. Two things changed it: EZ gained a type dedicated
to these, so reports from the wild became a filterable class rather than something indistinguishable
from a triaged bug; and having the element id to hand removes the lookup that was the entire
objection.

Referenced **by id, not by label** — `User-submitted issue` is what a human reads, `81-29` is what
survives someone renaming the value.

**A blank `issue-type-id` omits `customFields` entirely** and the project default applies. That is the
escape hatch: if the id is ever wrong, blanking the key restores issue filing with no code change.
Note that omitting is not the same request as sending null, which would mean *clear this field*.

Nothing else is set. `State`, `Assignee` and `Subsystem` are triage's to decide, and a reporter cannot
know which subsystem broke.

If YouTrack rejects the field the whole POST 400s, the row goes to `FAILED` with the message in
`yt_error`, and the sweep retries five times before giving up. That is deliberate: the admin email has
already gone out, so a wrong id costs issue filing but never a report, and parsing YouTrack's error
text to retry without the field would be guessing at a string format to paper over a config mistake
somebody should fix.

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
| `core/.../bugreport/YouTrackRequestBodyTest.kt` | The request body, with no Spring context and no live tracker: visibility always restricted to the configured group, the `Type` field's shape, and that a blank type id omits the field rather than nulling it |
| `web/tests/unit/breadcrumbs.test.mjs` | The three caps, eviction order, redaction, surviving a reload, and a throwing storage losing breadcrumbs and nothing else |
| `web/tests/browser/bug-report.spec.mjs` | The dialog, the pre-checked box, **shown text equals sent text**, declined sends no key, and the rate limit reading as a wait |

The browser spec stubs a 400 carrying a known error id and asserts that id appears in the posted
payload. That positive case is load-bearing: an activity log that is always empty looks exactly like
one that is working and had nothing to say.

`YouTrackRequestBodyTest` was checked the same way, by mutation rather than by trusting a green run:
changing the payload to `UnlimitedVisibility` fails `every issue is restricted to the configured
group`. Worth knowing that it does, because that assertion guards the one failure in this feature that
is silent and in the wrong direction — a wrong project id or field name gets a 400 somebody reads,
whereas a widened visibility creates the issue, looks like success, and publishes a student's
submission.

No wall clock in any of it. The rate-limit test fills the cap inside one test rather than moving time,
which `NoWallClockInFixturesTest` would refuse anyway.
