-- Optional second pass: remove teacher feedback written about named students (EZ-1725).
--
--   psql -h host -U user -d easyems_staging -v ON_ERROR_STOP=1 -f strip-teacher-feedback.sql
--
-- This is the most sensitive content in the database. Pseudonymising the account it points at
-- does not anonymise it: "you have failed this three times now, come see me" is about a real
-- person and identifiable to anyone who knows the course and the dates.
--
-- The trade is real, which is why this is a separate file rather than part of anonymise.sql —
-- teacher grading UI is exactly what wants realistic feedback threads to test against. Decide
-- once, before the data lands on a shared host; see doc/staging-environment.md §3.3.
--
-- Grades are kept. Only the free text goes, so grade tables, statistics and the activity feed
-- structure stay intact and the grading UI still has something to show.

\set ON_ERROR_STOP on

-- Escape hatch for a staging database that is not named like one. Prefer renaming the database.
\if :{?ALLOW_ANY_DATABASE}
\else
  \set ALLOW_ANY_DATABASE false
\endif

-- Checked at the psql level, not inside a DO block: psql does not interpolate its variables
-- inside dollar-quoted strings, so :ALLOW_ANY_DATABASE would arrive as a literal colon.
SELECT current_database() !~ '(staging|stage|anon)' AS db_looks_unsafe \gset

\if :db_looks_unsafe
\if :ALLOW_ANY_DATABASE
\echo '!! ALLOW_ANY_DATABASE is set: proceeding against a database that does not look like a staging copy.'
\else
DO $$
BEGIN
    RAISE EXCEPTION E'Refusing to run against database "%".\n'
        'This script deletes teacher feedback about named students. It is meant for a restored copy, whose name should say so. '
        'Restore the dump into a differently-named database (e.g. easyems_staging), or pass '
        '-v ALLOW_ANY_DATABASE=true if you are certain.', current_database();
END $$;
\endif
\endif

BEGIN;

-- Keep the row (and its grade), drop the prose.
UPDATE teacher_activity
SET feedback_md = NULL,
    feedback_html = NULL
WHERE feedback_md IS NOT NULL OR feedback_html IS NOT NULL;

-- Inline comments are nothing but feedback, and carry a copy of the student's code alongside
-- the teacher's remark about it. Nothing to preserve.
DELETE FROM teacher_inline_comment;

COMMIT;

\echo ''
\echo 'Teacher feedback stripped:'
SELECT 'teacher_activity rows with text left (should be 0)' AS item,
       count(*)::text AS value
FROM teacher_activity WHERE feedback_md IS NOT NULL OR feedback_html IS NOT NULL
UNION ALL SELECT 'inline comments left (should be 0)', (SELECT count(*) FROM teacher_inline_comment)::text
UNION ALL SELECT 'teacher_activity rows kept, with grades', (SELECT count(*) FROM teacher_activity)::text
UNION ALL SELECT 'feedback_snippet rows (kept: teachers own phrasing, not about anyone)',
       (SELECT count(*) FROM feedback_snippet)::text;
