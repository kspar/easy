-- EZ-1771: how many rows actually violate the three NOT NULL constraints we want to add.
--
-- Run against **production**, or the anonymised copy — not dev, which is local dev data and says
-- nothing about what is in the real table. Only the counts are needed; nothing here selects a row.
--
-- Why it has to be asked rather than assumed: `addNotNullConstraint` fails on the first null it
-- meets, and migrations run inside the Spring context, so a failed one means core does not start.
--
-- What is already known without the database:
--
--   * **No current code path can write a null `created_at`.** All four writers set it explicitly —
--     AddTeachersToCourse:93, JoinCourseByInvite:56, JoinMoodleLinkedCourseByInvite:47 and
--     moodle_students:252. So any nulls are historical, not ongoing.
--   * **There is no changeset to blame.** Both access tables were *renamed* into their current names
--     in `initial.xml` (`student_course` → `student_course_access`), so they predate the changelog and
--     `created_at`'s nullability was inherited from a pre-Liquibase schema. Nothing ever asserted it.
--   * **`last_accessed` is a reliable backfill source.** Changeset `120225-1` added it NOT NULL with
--     `defaultValueComputed="CURRENT_TIMESTAMP"`, so every row has one, and it is an upper bound:
--     the row certainly existed by then. The course's own `created_at` is the matching lower bound.

-- 1. article_version.title — expected to be zero. The table is weeks old (EZ-1573) and core always
--    writes a title; the constraint was simply left off the original createTable.
SELECT 'article_version.title' AS col,
       count(*)                                  AS total_rows,
       count(*) FILTER (WHERE title IS NULL)     AS null_rows;

-- 2. The two created_at columns.
SELECT 'student_course_access.created_at' AS col,
       count(*)                                       AS total_rows,
       count(*) FILTER (WHERE created_at IS NULL)     AS null_rows,
       min(created_at)                                AS earliest_non_null
FROM student_course_access
UNION ALL
SELECT 'teacher_course_access.created_at',
       count(*),
       count(*) FILTER (WHERE created_at IS NULL),
       min(created_at)
FROM teacher_course_access;

-- 3. Only matters if either count above is non-zero: what a backfill would have to work with.
--    Both bounds should come back equal to null_rows; if they do not, the null rows are stranger than
--    "old" and the backfill needs rethinking rather than choosing.
SELECT 'student_course_access' AS tbl,
       count(*)                                              AS null_rows,
       count(*) FILTER (WHERE a.last_accessed IS NOT NULL)   AS have_last_accessed,
       count(*) FILTER (WHERE c.created_at IS NOT NULL)      AS have_course_created_at
FROM student_course_access a
         JOIN course c ON c.id = a.course_id
WHERE a.created_at IS NULL
UNION ALL
SELECT 'teacher_course_access',
       count(*),
       count(*) FILTER (WHERE a.last_accessed IS NOT NULL),
       count(*) FILTER (WHERE c.created_at IS NOT NULL)
FROM teacher_course_access a
         JOIN course c ON c.id = a.course_id
WHERE a.created_at IS NULL;
