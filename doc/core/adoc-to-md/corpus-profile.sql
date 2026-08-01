-- EZ-1731: profile the AsciiDoc corpus before migrating it to Markdown.
-- Read-only. Safe to run against production.
--
--   psql -h <host> -U <user> -d <db> -f corpus-profile.sql
--
-- This does NOT give the flag rate — that needs the converter to actually run. It gives the
-- shape of the corpus, which says which converter features matter and where to look first.

\echo ''
\echo '=== 1. Corpus size ==='
SELECT count(*) FILTER (WHERE valid_to IS NULL)                              AS current_versions,
       count(*)                                                             AS all_versions,
       count(*) FILTER (WHERE valid_to IS NULL AND text_adoc IS NOT NULL)   AS current_with_adoc,
       count(*) FILTER (WHERE valid_to IS NULL AND text_md   IS NOT NULL)   AS already_migrated,
       -- Expected 0. If not, those rows have no rendering to verify against and no fallback.
       count(*) FILTER (WHERE valid_to IS NULL AND text_adoc IS NOT NULL
                                              AND text_html IS NULL)        AS adoc_without_html,
       -- Rows where html exists but adoc does not: the old API accepted text_html directly.
       -- These cannot be converted from source and need the html-derived fallback.
       count(*) FILTER (WHERE valid_to IS NULL AND text_html IS NOT NULL
                                              AND text_adoc IS NULL)        AS html_without_adoc
FROM exercise_version;

\echo ''
\echo '=== 2. Other content types (expected 0 per kspar) ==='
SELECT 'course_exercise.instructions_adoc' AS what, count(*) AS rows FROM course_exercise WHERE instructions_adoc IS NOT NULL
UNION ALL
SELECT 'article_version.text_adoc',               count(*) FROM article_version  WHERE text_adoc IS NOT NULL;

\echo ''
\echo '=== 3. Which AsciiDoc constructs appear, and in how many exercises ==='
WITH current AS (
    SELECT id, text_adoc
    FROM exercise_version
    WHERE valid_to IS NULL AND text_adoc IS NOT NULL
), probe(label, pattern) AS (VALUES
    -- Handled by the prototyped pipeline
    ('admonition inline (NOTE: …)',        '(^|\n)(NOTE|TIP|WARNING|IMPORTANT|CAUTION):'),
    ('admonition block ([NOTE])',          '(^|\n)\[(NOTE|TIP|WARNING|IMPORTANT|CAUTION)\]'),
    ('source block ([source…])',           '(^|\n)\[source'),
    ('delimited listing (----)',           '(^|\n)----'),
    ('table (|===)',                       '(^|\n)\|==='),
    ('deep heading (==== or more)',        '(^|\n)====+ \S'),
    -- Stripped deliberately: highlighting the current frontend no longer styles
    ('Easy $run[] / $in[] / $nohl[]',      '\$(run|in|nohl)\['),
    ('Easy attr refs {run}/{nur}/…',       '\{(run|nur|in|ni|nohl|lhon)\}'),
    -- Need checking: not covered by the prototype
    ('image macro',                        'image::?[^\s\[]*\['),
    ('include:: directive',                '(^|\n)include::'),
    ('cross-reference (<<…>>)',            '<<[^>]+>>'),
    ('anchor ([[…]])',                     '(^|\n)\[\['),
    ('passthrough (+++ or pass:)',         '(\+\+\+|pass:)'),
    ('footnote',                           'footnote:'),
    ('literal block (....)',               '(^|\n)\.\.\.\.'),
    ('math (stem:/latexmath:)',            '(stem:|latexmath:)'),
    ('list continuation (lone +)',         '(^|\n)\+(\n|$)'),
    ('conditional (ifdef/ifeval)',         '(^|\n)(ifdef|ifndef|ifeval)::'),
    ('raw HTML in source',                 '</?(div|span|p|br|hr|b|i|em|strong|a|img|table|tr|td|th|ul|ol|li|code|pre|h[1-6])\M[^>]*>'),
    ('attribute definition (:name:)',      '(^|\n):[a-zA-Z0-9_-]+:')
)
SELECT p.label AS construct,
       count(c.id) AS exercises,
       round(100.0 * count(c.id) / nullif((SELECT count(*) FROM current), 0), 1) AS pct
FROM probe p
LEFT JOIN current c ON c.text_adoc ~ p.pattern
GROUP BY p.label
ORDER BY exercises DESC, construct;

\echo ''
\echo '=== 4. Size distribution — the long tail is where surprises live ==='
SELECT count(*)                       AS exercises,
       min(length(text_adoc))         AS min_chars,
       round(avg(length(text_adoc)))  AS avg_chars,
       percentile_cont(0.5)  WITHIN GROUP (ORDER BY length(text_adoc))::int AS median_chars,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY length(text_adoc))::int AS p95_chars,
       max(length(text_adoc))         AS max_chars
FROM exercise_version
WHERE valid_to IS NULL AND text_adoc IS NOT NULL;

\echo ''
\echo '=== 5. The 10 most construct-dense exercises — review these by hand first ==='
WITH current AS (
    SELECT ev.id, e.id AS exercise_id, ev.title, ev.text_adoc
    FROM exercise_version ev JOIN exercise e ON e.id = ev.exercise_id
    WHERE ev.valid_to IS NULL AND ev.text_adoc IS NOT NULL
)
SELECT exercise_id, left(title, 45) AS title, length(text_adoc) AS chars,
       (CASE WHEN text_adoc ~ 'image::?[^\s\[]*\[' THEN 1 ELSE 0 END
      + CASE WHEN text_adoc ~ '(^|\n)include::' THEN 1 ELSE 0 END
      + CASE WHEN text_adoc ~ '<<[^>]+>>' THEN 1 ELSE 0 END
      + CASE WHEN text_adoc ~ '(\+\+\+|pass:)' THEN 1 ELSE 0 END
      + CASE WHEN text_adoc ~ 'footnote:' THEN 1 ELSE 0 END
      + CASE WHEN text_adoc ~ '(stem:|latexmath:)' THEN 1 ELSE 0 END
      + CASE WHEN text_adoc ~ '(^|\n)(ifdef|ifndef|ifeval)::' THEN 1 ELSE 0 END
      + CASE WHEN text_adoc ~ '(^|\n)\|===' THEN 1 ELSE 0 END
       ) AS risky_constructs
FROM current
ORDER BY risky_constructs DESC, chars DESC
LIMIT 10;
