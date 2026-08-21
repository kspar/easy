-- How to test a data-rewriting changeset before writing it.
--
--   1. copy this file, fill in the table and column
--   2. scp it to the host and run it there — the dev database is bound to loopback:
--        scp mine.sql easycoredev:/tmp/mine.sql
--        security find-generic-password -s easy-staging-become -w \
--          | ssh easycoredev 'sudo -S -p "" -u postgres psql -d easyems_dev -f /tmp/mine.sql'
--   3. only then write the changeset in core/src/main/resources/db/changesets/v4.xml
--
-- ## Why this exists
--
-- `:core:test` runs Liquibase against a **fresh empty** Testcontainers database. So a backfill
-- matches nothing, `addNotNullConstraint` succeeds trivially, and the suite goes green without having
-- answered the only interesting question about the changeset: what it does when the data it is meant
-- to fix is actually there.
--
-- And the failure mode is not a wrong number. `addNotNullConstraint` fails on the first row it
-- cannot satisfy, migrations run inside the Spring context, so **core does not start** — on whichever
-- environment holds that row, which by definition is the one nobody tested against.
--
-- Worked example: EZ-1771, changesets `210826-1` and `210826-2`. Two columns looked identical to a
-- third and were not — one had no offending rows and needed no backfill, the others had rows and a
-- changeset without a backfill would have taken core down. Neither fact was knowable without asking.
-- Written up in `doc/testing-log.md` under "a migration that only runs against an empty database has
-- not been tested".
--
-- Everything below runs inside a transaction that is rolled back. Nothing is changed.

\set ON_ERROR_STOP on
BEGIN;

-- 1. How many rows actually violate what you are about to require?
--    Answer this before deciding whether a backfill is needed at all. Do not guess from the age of
--    the table: a long-lived one can be clean and a new one can be dirty.
SELECT count(*)                                     AS total_rows,
       count(*) FILTER (WHERE my_column IS NULL)    AS offending_rows
FROM my_table;

-- 2. If there are any, is the value you plan to backfill *from* populated for every one of them?
--    A backfill source that is itself missing on some rows leaves them behind, and step 4 then fails.
--    `offending` and `have_source` must be equal.
SELECT count(*)                                        AS offending,
       count(*) FILTER (WHERE src.a_column IS NOT NULL) AS have_source
FROM my_table t
         JOIN source_table src ON src.id = t.source_id
WHERE t.my_column IS NULL;

-- 3. The backfill, exactly as the changeset will run it.
--    Prefer a join to a related row over a literal where one is defensible — it is real data rather
--    than an invented value, and costs one clause. EZ-1771 used the course's own `created_at`,
--    because an access row cannot predate its course, making it a true lower bound.
UPDATE my_table t
SET my_column = src.a_column
FROM source_table src
WHERE src.id = t.source_id AND t.my_column IS NULL;

-- 4. Nothing left, and then the constraint that would otherwise stop core from starting.
SELECT count(*) FILTER (WHERE my_column IS NULL) AS should_be_zero FROM my_table;

ALTER TABLE my_table ALTER COLUMN my_column SET NOT NULL;

ROLLBACK;

-- 5. Prove the rollback worked, so a dry run cannot be mistaken for having applied it.
--    This should report the same count as step 1.
SELECT count(*) FILTER (WHERE my_column IS NULL) AS unchanged FROM my_table;
