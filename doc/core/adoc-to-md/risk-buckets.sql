-- ============================================================================
--  OUTPUT IS PRODUCTION DATA. DO NOT PASTE IT ANYWHERE PUBLIC.
--
--  This query returns exercise ids and titles from the live database. YouTrack
--  (easy.youtrack.cloud) is public with guest access, and github.com/kspar/easy
--  is a public repo — neither is a place for any of this output, including in
--  an issue comment or a commit message. Keep results local.
-- ============================================================================

-- EZ-1731 follow-up: how many exercises are safe to convert in bulk?
--
-- The construct profile counts each construct separately, so overlaps hide the real answer:
-- one exercise using math, a passthrough and an image is counted three times. This buckets each
-- exercise once, by the worst thing in it.
--
-- Read-only. Safe against production.
--   psql -h <host> -U <user> -d <db> -f risk-buckets.sql

\echo ''
\echo '=== Exercises bucketed by the worst construct they contain ==='
WITH current AS (
    SELECT ev.exercise_id, ev.text_adoc, length(ev.text_adoc) AS chars
    FROM exercise_version ev
    WHERE ev.valid_to IS NULL AND ev.text_adoc IS NOT NULL
), classified AS (
    SELECT exercise_id, chars,
        CASE
            -- Cannot be represented in Markdown at all: needs a decision, not a converter fix.
            WHEN text_adoc ~ '(stem:|latexmath:)'            THEN '3. math'
            -- Escapes to raw output; whatever it produced has to be inspected by a human.
            WHEN text_adoc ~ '(\+\+\+|pass:)'                THEN '4. passthrough'
            WHEN text_adoc ~ '</?(div|span|p|br|hr|b|i|em|strong|a|img|table|tr|td|th|ul|ol|li|code|pre|h[1-6])\M[^>]*>'
                                                             THEN '5. raw HTML'
            -- Link targets with no Markdown equivalent; harmless if unreferenced, and nothing
            -- in this corpus uses <<xref>>, so they probably are.
            WHEN text_adoc ~ '(^|\n)\[\['                    THEN '6. anchors'
            -- Convertible, but the image URL has to survive — cross-check against text_html.
            WHEN text_adoc ~ 'image::?[^\s\[]*\['            THEN '7. images'
            -- Stripped deliberately; the surrounding text is preserved.
            WHEN text_adoc ~ '(\$(run|in|nohl)\[|\{(run|nur|in|ni|nohl|lhon)\})'
                                                             THEN '8. Easy highlight markup only'
            ELSE                                                  '9. plain — bulk convert'
        END AS bucket
    FROM current
)
SELECT bucket,
       count(*) AS exercises,
       round(100.0 * count(*) / sum(count(*)) OVER (), 1) AS pct,
       max(chars) AS largest
FROM classified
GROUP BY bucket
ORDER BY bucket;

\echo ''
\echo '=== The math exercises, listed — these need a product decision, not a converter ==='
SELECT e.id AS exercise_id, left(ev.title, 50) AS title, length(ev.text_adoc) AS chars
FROM exercise_version ev JOIN exercise e ON e.id = ev.exercise_id
WHERE ev.valid_to IS NULL AND ev.text_adoc ~ '(stem:|latexmath:)'
ORDER BY ev.title
LIMIT 60;
