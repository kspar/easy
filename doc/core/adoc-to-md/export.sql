-- ============================================================================
--  OUTPUT IS PRODUCTION DATA. DO NOT PASTE IT ANYWHERE PUBLIC.
--
--  This writes every exercise's AsciiDoc source and rendered HTML to a local
--  file. Keep it on your machine. YouTrack (easy.youtrack.cloud) is public with
--  guest access and github.com/kspar/easy is a public repo — the export, and
--  anything quoting it, belongs in neither.
-- ============================================================================
--
-- EZ-1731 step 1: export what the dry-run converter needs.
--
--   psql -h <host> -U <user> -d <db> -v out=/absolute/path/export.jsonl -f export.sql
--
-- Produces one JSON object per line: {"exercise_id": …, "version_id": …, "title": …,
-- "text_adoc": …, "text_html": …}. JSON rather than CSV because exercise text is full of
-- newlines and quotes, and JSON string escaping handles that without a dialect argument.
--
-- Read-only. Current versions only — old ones cannot be edited, so they do not need `text_md`.

\copy (SELECT row_to_json(t) FROM (SELECT e.id AS exercise_id, ev.id AS version_id, ev.title, ev.text_adoc, ev.text_html FROM exercise_version ev JOIN exercise e ON e.id = ev.exercise_id WHERE ev.valid_to IS NULL AND ev.text_adoc IS NOT NULL ORDER BY e.id) t) TO :'out'

\echo ''
\echo 'Exported. Row count:'
SELECT count(*) AS exported_rows
FROM exercise_version
WHERE valid_to IS NULL AND text_adoc IS NOT NULL;
