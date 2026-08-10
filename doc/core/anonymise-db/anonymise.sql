-- Anonymise a restored copy of the production database (EZ-1725).
--
-- Run this against a RESTORED COPY, never against production. See README.md.
--   psql -h host -U user -d easyems_dev -v ON_ERROR_STOP=1 -f anonymise.sql
--
-- Operates on the database rather than on dump text, deliberately: the previous
-- anonymise-dump.py matched an exact COPY header and broke the moment a column was added
-- to `account`. Column order cannot break SQL.
--
-- This file is the REQUIRED pass. Two optional ones go further:
--   strip-teacher-feedback.sql  - drops teacher feedback written about named students
--   strip-submissions.sql       - replaces student code
--
-- What is deliberately NOT touched:
--   account.username    - preserved on purpose. Imported accounts are unreachable because the
--                         dev IdP has registration disabled, and an admin creating a dev-realm
--                         user whose username matches an imported teacher is the auditable way
--                         to get realistic teacher access on dev. See doc/dev-environment.md.
--   course.course_code, course.moodle_short_name, course_group.name - organisational, not personal.
--   group.name          - implicit groups are named after the account username, which we keep.
--   stored_file         - exercise and article attachments, teacher-authored. Review if that
--                         assumption ever stops holding; the column is bytea and unbounded.
--   feedback_snippet    - teachers' own reusable phrases, not written about anyone in particular.
--   teacher_submission.solution - the teacher's model solution, not student work.

\set ON_ERROR_STOP on

-- Escape hatch for a dev database that is not named like one. Prefer renaming the database.
\if :{?ALLOW_ANY_DATABASE}
\else
  \set ALLOW_ANY_DATABASE false
\endif

-- Checked at the psql level, not inside a DO block: psql does not interpolate its variables
-- inside dollar-quoted strings, so :ALLOW_ANY_DATABASE would arrive as a literal colon.
SELECT current_database() !~ '(dev|stage|anon)' AS db_looks_unsafe \gset

\if :db_looks_unsafe
\if :ALLOW_ANY_DATABASE
\echo '!! ALLOW_ANY_DATABASE is set: proceeding against a database that does not look like a dev copy.'
\else
DO $$
BEGIN
    RAISE EXCEPTION E'Refusing to run against database "%".\n'
        'This script rewrites every account name and email and deletes invitations and logs. It is meant for a restored copy, whose name should say so. '
        'Restore the dump into a differently-named database (e.g. easyems_dev), or pass '
        '-v ALLOW_ANY_DATABASE=true if you are certain.', current_database();
END $$;
\endif
\endif

BEGIN;

-- ---------------------------------------------------------------------------
-- Pseudonym source: Estonian colours x birds, as the old script did, so dev
-- shows plausible names rather than user1/user2.
-- ---------------------------------------------------------------------------

CREATE TEMP TABLE colour_raw(word text);
CREATE TEMP TABLE bird_raw(word text);

\copy colour_raw(word) from 'varvid.txt'
\copy bird_raw(word) from 'linnud.txt'

CREATE TEMP TABLE colour AS
SELECT row_number() OVER (ORDER BY w) AS idx, w
FROM (SELECT DISTINCT lower(btrim(word)) AS w FROM colour_raw WHERE btrim(word) <> '') s;

CREATE TEMP TABLE bird AS
SELECT row_number() OVER (ORDER BY w) AS idx, w
FROM (SELECT DISTINCT lower(btrim(word)) AS w FROM bird_raw WHERE btrim(word) <> '') s;

-- Assign each account a (colour, bird) pair plus its row number. The pair cycles once the
-- account count exceeds colours x birds, and the row number keeps emails unique regardless —
-- the old script had a hard ceiling of exactly 3190 accounts and died with an opaque
-- "pop from empty list" past it.
-- old_pseudonym is captured here, before account is rewritten: stats_submission stores
-- pseudonyms rather than account ids, so the old value is the only way to find its rows again.
CREATE TEMP TABLE pseudonym_map AS
WITH n AS (
    SELECT (SELECT count(*) FROM colour) AS colours,
           (SELECT count(*) FROM bird) AS birds
), numbered AS (
    SELECT username, pseudonym AS old_pseudonym, row_number() OVER (ORDER BY username) AS i
    FROM account
)
SELECT
    numbered.username,
    numbered.old_pseudonym,
    md5(random()::text || numbered.username) AS new_pseudonym,
    numbered.i,
    c.w AS colour,
    b.w AS bird,
    -- How many times the colour x bird space has been exhausted before this account.
    (numbered.i - 1) / (n.colours * n.birds) AS cycle
FROM numbered
CROSS JOIN n
JOIN colour c ON c.idx = 1 + ((numbered.i - 1) % n.colours)
JOIN bird b ON b.idx = 1 + (((numbered.i - 1) / n.colours) % n.birds);

CREATE INDEX ON pseudonym_map(username);
CREATE INDEX ON pseudonym_map(old_pseudonym);

-- Estonian diacritics are not valid in the local part of an email address.
CREATE OR REPLACE FUNCTION pg_temp.fold(t text) RETURNS text AS $$
    SELECT translate($1, 'õäöüšž', 'oaousz');
$$ LANGUAGE sql IMMUTABLE;

-- ---------------------------------------------------------------------------
-- account
-- ---------------------------------------------------------------------------

UPDATE account a
SET given_name = initcap(m.colour),
    -- Numbered from the second cycle onwards, so display names stay unique however many accounts
    -- there are. Without it a 50k-account import gets 3190 distinct names used ~16 times each,
    -- and a grade table with sixteen identical students is worse than an obviously synthetic
    -- "Hall Emu 2". The first 3190 accounts get a bare name.
    family_name = initcap(m.bird) || CASE WHEN m.cycle > 0 THEN ' ' || (m.cycle + 1) ELSE '' END,
    email = pg_temp.fold(m.colour) || '.' || pg_temp.fold(m.bird) || '.' || m.i || '@ez.ez',
    -- Regenerated in the format account_checkin.kt uses: uuid hex, no dashes.
    pseudonym = m.new_pseudonym,
    -- Legacy columns holding real identifiers. moodle_username is not even mapped in
    -- Tables.kt any more, which is exactly why it went unnoticed.
    moodle_username = NULL,
    pre_migration_id = NULL
FROM pseudonym_map m
WHERE a.username = m.username;

-- stats_submission denormalises account.pseudonym in two columns, so both have to follow the
-- rewrite or the statistics start referring to pseudonyms that no longer exist anywhere.
UPDATE stats_submission s
SET student_pseudonym = m.new_pseudonym
FROM pseudonym_map m
WHERE s.student_pseudonym = m.old_pseudonym;

UPDATE stats_submission s
SET latest_teacher_pseudonym = m.new_pseudonym
FROM pseudonym_map m
WHERE s.latest_teacher_pseudonym = m.old_pseudonym;

-- ---------------------------------------------------------------------------
-- Moodle usernames outside `account`
-- ---------------------------------------------------------------------------

UPDATE student_course_access SET moodle_username = NULL WHERE moodle_username IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Pure-PII rows: people invited who never registered, so there is no account to pseudonymise
-- and nothing of testing value in keeping them. Child table first (FK on moodle_username).
-- ---------------------------------------------------------------------------

DELETE FROM student_moodle_pending_course_group_access;
DELETE FROM student_moodle_pending_access;

-- Live invite tokens. A tester can generate a fresh invite in one click.
DELETE FROM course_invite_link;

-- Client-side error reports: free-text messages tied to a user id, no testing value.
DELETE FROM log_report;

COMMIT;

-- ---------------------------------------------------------------------------
-- Report, so the operator can see it did something
-- ---------------------------------------------------------------------------

\echo ''
\echo 'Anonymisation complete. Remaining personal-ish data:'
SELECT 'accounts pseudonymised' AS item, count(*)::text AS value FROM account
UNION ALL SELECT 'accounts with a non-@ez.ez email (should be 0)',
    count(*)::text FROM account WHERE email NOT LIKE '%@ez.ez'
UNION ALL SELECT 'duplicate emails (should be 0)',
    count(*)::text FROM (SELECT email FROM account GROUP BY email HAVING count(*) > 1) d
UNION ALL SELECT 'duplicate display names (should be 0)',
    (SELECT count(*) FROM (SELECT given_name, family_name FROM account
                           GROUP BY given_name, family_name HAVING count(*) > 1) n)::text
UNION ALL SELECT 'moodle usernames left (should be 0)',
    (SELECT count(*) FROM account WHERE moodle_username IS NOT NULL)::text
UNION ALL SELECT 'pending invitations left (should be 0)',
    (SELECT count(*) FROM student_moodle_pending_access)::text
UNION ALL SELECT 'teacher feedback rows with text (strip-teacher-feedback.sql)',
    (SELECT count(*) FROM teacher_activity WHERE feedback_md IS NOT NULL OR feedback_html IS NOT NULL)::text
UNION ALL SELECT 'inline comments (strip-teacher-feedback.sql)',
    (SELECT count(*) FROM teacher_inline_comment)::text
UNION ALL SELECT 'submissions with code (strip-submissions.sql)',
    (SELECT count(*) FROM submission WHERE solution <> '')::text;
