-- ============================================================================
--  OUTPUT IS PRODUCTION DATA. DO NOT PASTE IT ANYWHERE PUBLIC.
--
--  This selects every exercise's AsciiDoc source and rendered HTML. Keep the
--  result on your machine. YouTrack (easy.youtrack.cloud) is public with guest
--  access and github.com/kspar/easy is a public repo — the export, and anything
--  quoting it, belongs in neither.
-- ============================================================================
--
-- EZ-1731 step 1: export what the dry-run converter needs.
--
--   cd /tmp && sudo -u postgres psql -d easyems -t -A -f export.sql > /tmp/adoc-export.jsonl
--   wc -l /tmp/adoc-export.jsonl        # should match the current-version count
--
-- `-t -A` gives tuples-only unaligned output: one JSON object per line, nothing else. The
-- redirection is done by your own shell, so the file is written as you rather than as the
-- postgres user — which is the point.
--
-- Do NOT use \copy here. psql does not interpolate :'variables' inside \copy, and \copy writes
-- as the user running psql, which under `sudo -u postgres` cannot write into your home
-- directory. Both of those bit us; shell redirection sidesteps them.
--
-- The `cd /tmp` is only to stop psql warning that the postgres user cannot read your home
-- directory. Harmless either way.
--
-- JSON rather than CSV because exercise text is full of newlines and quotes; row_to_json escapes
-- them, so every row stays on exactly one line.
--
-- Read-only. Current versions only — older ones cannot be edited, so they need no text_md.

SELECT row_to_json(t)
FROM (
    SELECT e.id  AS exercise_id,
           ev.id AS version_id,
           ev.title,
           ev.text_adoc,
           ev.text_html
    FROM exercise_version ev
    JOIN exercise e ON e.id = ev.exercise_id
    WHERE ev.valid_to IS NULL
      AND ev.text_adoc IS NOT NULL
    ORDER BY e.id
) t;
