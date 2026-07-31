# Anonymising a production copy

For seeding staging (EZ-1723) from a production dump. Run against a **restored copy**, never
against production.

## Why SQL and not the old Python script

`anonymise-dump.py` rewrote dump *text*, locating the account rows by matching an exact
`COPY public.account (...)` header. That header listed six columns; `account` now has thirteen, so
the script died with a `ValueError` before touching anything — verified against a real `pg_dump`.
It also had a hard ceiling of exactly 3190 accounts, because `PSEUDO_PAIRS` was set to the full
`11 colours × 290 birds` product with no slack; account 3191 would have died with
`IndexError: pop from empty list`.

Operating on the restored database instead means column order and column count cannot break it,
the result is reviewable in SQL, and each pass is re-runnable.

## Order of operations

```sh
# 1. Restore into a database whose NAME SAYS STAGING. The scripts refuse otherwise.
createdb -h host -U user easyems_staging
pg_restore -h host -U user -d easyems_staging prod-dump.sql   # or: psql ... < prod-dump.sql

# 2. Required pass. Run from THIS directory: \copy reads the wordlists relative to your cwd.
cd doc/core/anonymise-db
psql -h host -U user -d easyems_staging -f anonymise.sql

# 3. Optional passes — see the decision table below.
psql -h host -U user -d easyems_staging -f strip-teacher-feedback.sql
psql -h host -U user -d easyems_staging -f strip-submissions.sql
```

Do all of this **before** the database is reachable from anything but your own session, and before
any tester gets access to the host.

## The guard

Each script refuses to run unless the database name contains `staging`, `stage` or `anon`, and
exits non-zero without touching a row. This is the difference between a scripted mistake and a
catastrophe: pointed at production, `anonymise.sql` would rename every real user and delete every
live invitation.

If your staging database genuinely has another name, prefer renaming it. The escape hatch is
`-v ALLOW_ANY_DATABASE=true`, which prints a warning and proceeds.

## What each pass does

### `anonymise.sql` — required

| Data | Treatment |
| --- | --- |
| `account.given_name`, `family_name` | Estonian colour + bird, e.g. `Kollane Aara` |
| `account.email` | `colour.bird.N@ez.ez`, diacritics folded, `N` guarantees uniqueness at any account count |
| `account.pseudonym` | Regenerated as 32 hex chars, the format `account_checkin.kt` uses |
| `account.moodle_username` | Nulled. A real Moodle identifier, and not even mapped in `Tables.kt` any more — which is how it went unnoticed |
| `account.pre_migration_id` | Nulled |
| `stats_submission.student_pseudonym`, `latest_teacher_pseudonym` | Follow the new pseudonyms. This table stores pseudonyms rather than account ids, so it has to be remapped or the statistics point at pseudonyms that no longer exist |
| `student_course_access.moodle_username` | Nulled |
| `student_moodle_pending_access` (+ course group child) | Deleted. Invitations to people who never registered: pure PII, no account to pseudonymise, nothing of testing value |
| `course_invite_link` | Deleted. Live tokens; a tester can make a new invite in one click |
| `log_report` | Deleted. Client error reports, free text tied to a user id |

`account.username` is **preserved deliberately**. Imported accounts are unreachable because the dev
IdP has registration disabled, and an admin creating a dev-realm user whose username matches an
imported teacher is the auditable way to get realistic teacher access on staging. See
`doc/staging-environment.md` §3.4.

### `strip-teacher-feedback.sql` — recommended, needs a decision

Nulls `teacher_activity.feedback_md` / `feedback_html` and deletes `teacher_inline_comment`.
**Grades are kept**, so grade tables, statistics and the activity feed structure stay intact.

This is the most sensitive content in the database, and pseudonymising the account it points at
does not anonymise it — "you have failed this three times now, come see me" is about a real person
and identifiable to anyone who knows the course and the dates. Against that: teacher grading UI is
exactly what wants realistic feedback threads to test against.

Because the staging import happens once and then drifts, this is decided once. Decide before the
data lands.

### `strip-submissions.sql` — optional

Replaces `submission.solution`, `submission_draft.solution` and `anonymous_submission.solution`
with a placeholder. Student code is personal data in practice even after the account is
pseudonymised: submissions carry name headers, comments, and occasionally worse.

Most setups will want to keep submissions and accept the residual risk, since grading, plagiarism
comparison and the auto-assessment path all want real code of varying quality. Run this if the host
is shared more widely than the team.

## Deliberately not touched

- `stored_file` — exercise and article attachments, teacher-authored. Revisit if that assumption
  stops holding; the column is `bytea` and unbounded.
- `feedback_snippet` — teachers' own reusable phrases, not written about anyone in particular.
- `teacher_submission.solution` — the teacher's model solution, not student work.
- `course.course_code`, `course.moodle_short_name`, `course_group.name` — organisational.
- `group.name` — implicit groups are named after the account username, which is preserved.

## Verification

Each script ends with a summary. Rows labelled "should be 0" are assertions — a non-zero value
means something did not apply. `anonymise.sql` also reports how much remains for the two optional
passes, so it is obvious what has not been dealt with.

Tested against a restored dev dump padded to **5003 accounts**, which is past the old script's
ceiling: 0 non-`@ez.ez` emails, 0 duplicate emails, 0 orphaned pseudonyms in `stats_submission`.
All three scripts were confirmed to refuse a database named `easyems_guardtest` without modifying
a row.
