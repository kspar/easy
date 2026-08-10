-- Optional third pass: replace student-submitted code (EZ-1725).
--
--   psql -h host -U user -d easyems_dev -v ON_ERROR_STOP=1 -f strip-submissions.sql
--
-- Student code is personal data in practice even after the account is pseudonymised: submissions
-- carry name headers, comments, and occasionally things students should not have put in a file.
--
-- Running this costs you the most realistic part of the dataset — grading, plagiarism comparison
-- and the auto-assessment path all want real submissions of varying quality. Most dev setups
-- will want to keep them and accept the residual risk. Run this if the host is shared more widely
-- than the team, or if the anonymisation review decided against keeping student work.
--
-- Solutions are replaced rather than deleted: the columns are NOT NULL, and row counts, grades
-- and submission numbering stay intact so the UI has something coherent to render.

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
        'This script destroys every student submission. It is meant for a restored copy, whose name should say so. '
        'Restore the dump into a differently-named database (e.g. easyems_dev), or pass '
        '-v ALLOW_ANY_DATABASE=true if you are certain.', current_database();
END $$;
\endif
\endif

BEGIN;

UPDATE submission
SET solution = '# Submission removed during anonymisation (EZ-1725)' || E'\n';

UPDATE submission_draft
SET solution = '# Draft removed during anonymisation (EZ-1725)' || E'\n';

-- Anonymous submissions have no account attached, but they are still someone's code.
UPDATE anonymous_submission
SET solution = '# Submission removed during anonymisation (EZ-1725)' || E'\n';

COMMIT;

\echo ''
\echo 'Student code replaced:'
SELECT 'submissions' AS item, count(*)::text AS value FROM submission
UNION ALL SELECT 'submissions still carrying original code (should be 0)',
    (SELECT count(*) FROM submission WHERE solution NOT LIKE '%anonymisation%')::text
UNION ALL SELECT 'drafts', (SELECT count(*) FROM submission_draft)::text
UNION ALL SELECT 'anonymous submissions', (SELECT count(*) FROM anonymous_submission)::text
UNION ALL SELECT 'teacher_submission rows (kept: teachers model solutions, not student work)',
    (SELECT count(*) FROM teacher_submission)::text;
