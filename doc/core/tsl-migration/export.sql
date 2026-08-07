-- Exports every TSL exercise's spec and generated assets, one JSON object per line.
--
-- Read-only: this file contains no DDL, no writes, and no transaction. Safe against prod.
--
--   psql -h <host> -U <user> -d <db> -Atq -f export.sql > /tmp/tsl-export.jsonl
--
-- The -Atq flags matter and are not decoration: -A unaligned, -t no header or row count, -q no
-- chatter. The same settings as \pset directives inside this file would each echo a confirmation
-- line ("Output format is unaligned.") into the middle of the JSONL.
--
-- Redirecting psql rather than using \copy is deliberate — \copy resolves paths on the client and
-- quietly writes nothing useful when the two disagree, and it cannot be piped.
--
-- One line per *current* exercise version (valid_to IS NULL). Assets are nested rather than joined
-- so a file's content, which is full of newlines, stays inside one JSON string on one line.

SELECT json_build_object(
    'exercise_id', e.id,
    'version_id', ev.id,
    'auto_exercise_id', ae.id,
    'title', ev.title,
    'container_image', ae.container_image_id,
    'grading_script', ae.grading_script,
    'max_time_sec', ae.max_time_sec,
    'max_mem_mb', ae.max_mem_mb,
    'assets', (
        SELECT coalesce(
            json_agg(
                json_build_object('file_name', a.file_name, 'file_content', a.file_content)
                ORDER BY a.file_name
            ),
            '[]'::json
        )
        FROM asset a
        WHERE a.auto_exercise_id = ae.id
    )
)::text
FROM exercise_version ev
         JOIN exercise e ON e.id = ev.exercise_id
         JOIN automatic_exercise ae ON ae.id = ev.auto_exercise_id
WHERE ev.valid_to IS NULL
  -- Matches both tiivad:tsl-compose (JSON specs, current) and tiivad:tsl-spec (YAML, legacy).
  -- Broader than an equality check on purpose: an exercise on a container we forgot about is
  -- exactly the one that would be missed by the migration and break on its next save.
  AND ae.container_image_id LIKE '%tsl%'
ORDER BY e.id;
