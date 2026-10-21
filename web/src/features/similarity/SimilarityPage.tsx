import { Fragment, useMemo, useState } from 'react'
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  FormControl,
  IconButton,
  InputLabel,
  Link,
  MenuItem,
  Select,
  Slider,
  Tooltip,
  Typography,
} from '@mui/material'
import {
  ArrowBackOutlined,
  CompareArrowsOutlined,
  ExpandMoreOutlined,
  OpenInNewOutlined,
  TuneOutlined,
} from '@mui/icons-material'
import { useNavigate, useParams, useSearchParams, Link as RouterLink } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { formatDateTime, useDateLocale } from '../../i18n/dateLocale.ts'
import usePageTitle from '../../hooks/usePageTitle.ts'
import useSavedGroup from '../../hooks/useSavedGroup.ts'
import {
  useCheckSimilarity,
  useCourseGroups,
  useSimilarityEstimate,
  useSimilarityRunning,
  useTeacherCourseExercises,
  useTeacherSubmissionSummaries,
} from '../../api/exercises.ts'
import SimilarityDiff from './SimilarityDiff.tsx'
import ReadOnlyCodeSnippet from '../course-exercise/ReadOnlyCodeSnippet.tsx'
import type { MatchedLines } from './similarityHighlight.ts'
import { groupAuthors, identicalOthers } from './similarityGroups.ts'
import { isSimilarityRefusal } from './similarityError.ts'
import { emptyReason, hasUnreadableScores } from './similarityEmptyState.ts'
import { skippedByReason } from './similaritySkipped.ts'
import type { SkippedReason } from './similaritySkipped.ts'
import { highlightFileName } from './similarityLanguage.ts'
import { comparedBy, coverage, pairMetrics, resultNotes, usesMatchRanges } from './similarityProvider.ts'
import { estimateView, formatDuration } from './similarityEstimate.ts'
import type { ResultNote } from './similarityProvider.ts'
import type { SimilarityMetricResp } from '../../api/types.ts'
import CourseSimilaritySettingsDialog from '../course-settings/CourseSimilaritySettingsDialog.tsx'
import { errorMessage } from '../../api/errorMessage.ts'
import { SHARED_LIGHT, SHARED_DARK } from './similarityHighlight.ts'
import { spaLinkProps } from '../../components/spaLink.ts'

/**
 * Above this many submissions, say out loud how many comparisons that is - when core cannot say how
 * long they will take (an older core, with no estimate). Where it can, the estimate says it instead.
 *
 * Core compares every pair inside the request, so the work grows with the square of the class: 40
 * students is 780 comparisons, 300 is nearly 45,000. This page's job is to not let a teacher click a
 * button that will appear to hang.
 */
const PAIR_WARNING_THRESHOLD = 120

/**
 * How many pairs core keeps by each of its two orders — most code shared, and highest score — so it
 * returns at most twice this, and the threshold always has the highest-scoring ones to filter.
 */
const MAX_PAIRS = 100

export default function SimilarityPage() {
  const { courseId } = useParams<{ courseId: string }>()
  const navigate = useNavigate()
  const { t } = useTranslation()
  const dateFnsLocale = useDateLocale()
  usePageTitle(t('similarity.title'))

  // The exercise lives in the URL so a result is linkable and survives a refresh — the same thing
  // the old UI did with ?exercise=, and the reason a teacher can send "look at this one" to a
  // colleague.
  const [searchParams, setSearchParams] = useSearchParams()
  const exerciseId = searchParams.get('exercise') ?? ''
  const setExerciseId = (id: string) => {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      if (id) next.set('exercise', id)
      else next.delete('exercise')
      return next
    }, { replace: true })
  }

  const [group, setGroup] = useSavedGroup(courseId!)
  const [minScore, setMinScore] = useState(0)
  const [settingsOpen, setSettingsOpen] = useState(false)

  const { data: exercises, isLoading: exercisesLoading } = useTeacherCourseExercises(courseId)
  const { data: groups } = useCourseGroups(courseId!)

  const selected = exercises?.find((e) => e.exercise_id === exerciseId)

  // Which submissions to compare. Fetched as soon as an exercise is chosen rather than inside the
  // click handler, so the pair count — and any warning about it — is on screen *before* committing
  // to the wait.
  const {
    data: rows,
    isLoading: rowsLoading,
    isError: rowsFailed,
    error: rowsError,
  } = useTeacherSubmissionSummaries(courseId!, selected?.course_exercise_id ?? '', group || undefined)

  const submitted = useMemo(() => (rows ?? []).filter((r) => r.submission), [rows])
  const pairCount = (submitted.length * (submitted.length - 1)) / 2

  // What core says the check would take, before it is asked for (EZ-1667): pairs, about how long, and
  // whether it would be refused as too costly - so the refusal comes before the click, not after it.
  // The ids are the ones `run` sends, memoised so the query's key holds still between renders.
  const submittedIds = useMemo(() => submitted.map((r) => r.submission!.id), [submitted])
  const { data: estimateData } = useSimilarityEstimate(selected ? exerciseId : undefined, courseId!, submittedIds)
  const estimate = estimateView(estimateData)

  const check = useCheckSimilarity(exerciseId || undefined)
  // What the last run was over, taken when it was asked for. The result belongs to that selection and
  // is shown only under it: a reset on the selects' onChange was the only guard, and navigation changes
  // the selection without one - the sidebar link left the result under an empty select, and Back after
  // running another exercise put that one's pairs under this one's name. The rows are its own too, so a
  // refetch of the summaries (focus, after 30 s) cannot take the names and links out from under it.
  const [ranOver, setRanOver] = useState<{ exerciseId: string; group: string; submitted: typeof submitted }>()
  const current = ranOver !== undefined && ranOver.exerciseId === exerciseId && ranOver.group === group
  const result = current ? check.data : undefined
  // Also true for a run the page has left behind (a group or exercise change mid-run): core is still
  // computing it under its one permit, so a new run now would only wait and fail as busy.
  const running = useSimilarityRunning()

  // submission id -> student id, so each side of a pair can link to that student's grading view.
  // The similarity response carries names but no ids, and this is the only place both are known.
  const studentBySubmission = useMemo(() => {
    const map = new Map<string, string>()
    for (const r of ranOver?.submitted ?? []) if (r.submission) map.set(r.submission.id, r.student_id)
    return map
  }, [ranOver])

  const submissionsById = useMemo(() => {
    const map = new Map<string, NonNullable<typeof result>['submissions'][number]>()
    for (const s of result?.submissions ?? []) map.set(s.id, s)
    return map
  }, [result])

  // Filtered on the maximum, not the average: JPlag's maximalSimilarity is the max of the two
  // one-sided scores and its similarity is their weighted mean, so the max is always the higher
  // of the pair. A `Math.max` of the two would read as even-handed and be a no-op. The slider is
  // labelled for the maximum to match.
  // Split in two on purpose. `aboveThreshold` answers the slider's question alone; `pairs` then drops
  // scores whose submissions are missing from the response, which the map below would drop anyway —
  // so the heading counts only rows that will exist. Keeping both is what lets the empty state say
  // *which* emptiness it is rather than blaming a threshold that may be zero.
  const aboveThreshold = useMemo(
    () => (result?.scores ?? []).filter((s) => s.max_similarity >= minScore),
    [result, minScore],
  )

  const pairs = useMemo(
    () => aboveThreshold.filter((s) => submissionsById.has(s.sub_1) && submissionsById.has(s.sub_2)),
    [aboveThreshold, submissionsById],
  )

  // Memoised per score, because these arrays are props of SimilarityDiff and sit in its effect's
  // dependency list. Built inline they were a new identity on every parent render, so every open
  // accordion destroyed and rebuilt two CodeMirror editors — and the threshold slider fires per
  // mousemove, with up to MAX_PAIRS accordions mounted at once.
  const matchedByPair = useMemo(() => {
    const byPair = new Map<string, { left: MatchedLines[]; right: MatchedLines[] }>()
    for (const score of result?.scores ?? []) {
      // Guarded like `skipped` below, for the same response: an older core, which a deploy keeps
      // answering for minutes after the new page is live, sends scores without `matches`. Unguarded,
      // this memo threw during render and took the whole page down rather than only the highlight.
      const matches = score.matches ?? []
      byPair.set(`${score.sub_1}-${score.sub_2}`, {
        left: matches.map((m) => ({ startLine: m.start_line_1, endLine: m.end_line_1 })),
        right: matches.map((m) => ({ startLine: m.start_line_2, endLine: m.end_line_2 })),
      })
    }
    return byPair
  }, [result])

  const skipped = result?.skipped ?? []
  const covered = result ? coverage(result) : null
  const unreadable = hasUnreadableScores(result?.scores ?? [])
  // Identical submissions by three or more authors, which core reports once each rather than as every
  // pair among them - twelve untouched copies of the starter code were 66 rows at 100%, ahead of any real
  // copy. A pair side that is one of them stands for them all, and says so.
  const identicalGroups = result?.groups ?? []
  const studentOf = (submissionId: string) => studentBySubmission.get(submissionId)
  const othersBySubmission = useMemo(
    () => identicalOthers(result?.groups, (id) => studentBySubmission.get(id)),
    [result, studentBySubmission],
  )
  const emptyNow = result ? emptyReason(result.scores.length, aboveThreshold.length, identicalGroups.length) : null
  // Named, per reason, from the rows the run was over rather than whatever the summaries now say.
  const skippedGroups = useMemo(() => skippedByReason(result?.skipped ?? [], ranOver?.submitted ?? []), [result, ranOver])

  // A switch of literal keys rather than a lookup table, so the i18n test can see every one of them.
  const skippedLabel = (reason: SkippedReason, count: number) => {
    switch (reason) {
      case 'CANNOT_PARSE':
        return t('similarity.skippedParse', { count })
      case 'TOO_LARGE':
        return t('similarity.skippedLarge', { count })
      case 'TOO_COMPLEX':
        return t('similarity.skippedComplex', { count })
      case 'TOO_REPETITIVE':
        return t('similarity.skippedRepetitive', { count })
      case 'TOO_SMALL':
        return t('similarity.skippedSmall', { count })
      case 'OTHER':
        return t('similarity.skippedOther', { count })
    }
  }

  // Switches of literal keys, like skippedLabel, so the i18n test can see every one.
  const noteText = (note: ResultNote) => {
    switch (note) {
      case 'pinnedUnavailable':
        return t('similarity.pinnedUnavailable')
      case 'languageUnavailable':
        return t('similarity.languageUnavailable')
      case 'languageText':
        return t('similarity.languageTextNote')
      case 'languageSql':
        return t('similarity.languageSqlNote')
    }
  }

  // A metric the page has no words for is still shown, by the name core gave it: hiding it would hide
  // what the provider measured, and the score the slider filters on is there besides.
  const metricLabel = (metric: SimilarityMetricResp) => {
    switch (metric.id) {
      case 'similarity':
        return t('similarity.similarityShort', { score: metric.value })
      case 'max_similarity':
        return t('similarity.maxSimilarityShort', { score: metric.value })
      case 'shared_tokens':
        return t('similarity.sharedTokensShort', { count: metric.value })
      case 'levenshtein':
        return t('similarity.levenshteinShort', { score: metric.value })
      case 'dice':
        return t('similarity.diceShort', { score: metric.value })
      default:
        return t('similarity.metricOther', { name: metric.id, value: metric.unit === 'percent' ? `${metric.value}%` : metric.value })
    }
  }

  const metricTooltip = (id: string) => {
    switch (id) {
      case 'similarity':
        return t('similarity.similarityTooltip')
      case 'max_similarity':
        return t('similarity.maxSimilarityTooltip')
      case 'shared_tokens':
        return t('similarity.sharedTokensTooltip')
      case 'levenshtein':
        return t('similarity.levenshteinTooltip')
      case 'dice':
        return t('similarity.diceTooltip')
      default:
        return ''
    }
  }

  const run = () => {
    if (!selected) return
    setRanOver({ exerciseId, group, submitted })
    check.mutate({
      courseIds: [courseId!],
      submissionIds: submitted.map((r) => r.submission!.id),
    })
  }

  const studentLink = (submissionId: string) => {
    const studentId = studentBySubmission.get(submissionId)
    if (!studentId || !selected) return null
    return `/courses/${courseId}/exercises/${selected.course_exercise_id}?student=${encodeURIComponent(studentId)}`
  }

  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
        <IconButton
          component={RouterLink}
          to={`/courses/${courseId}/exercises`}
          size="small"
          aria-label={t('general.back')}
        >
          <ArrowBackOutlined />
        </IconButton>
        <Typography variant="h5">{t('similarity.title')}</Typography>
      </Box>

      <Typography variant="body2" color="text.secondary" sx={{ mb: 3, maxWidth: '70ch' }}>
        {t('similarity.explanation')}
      </Typography>

      <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', alignItems: 'center', mb: 2 }}>
        <FormControl size="small" sx={{ minWidth: 280 }} disabled={exercisesLoading}>
          <InputLabel id="similarity-exercise">{t('similarity.exercise')}</InputLabel>
          <Select
            labelId="similarity-exercise"
            label={t('similarity.exercise')}
            value={exercises?.some((e) => e.exercise_id === exerciseId) ? exerciseId : ''}
            onChange={(e) => {
              setExerciseId(e.target.value)
              check.reset()
            }}
          >
            {(exercises ?? []).map((e) => (
              <MenuItem key={e.course_exercise_id} value={e.exercise_id}>
                {e.effective_title}
              </MenuItem>
            ))}
          </Select>
        </FormControl>

        {groups && groups.length > 0 && (
          <FormControl size="small" sx={{ minWidth: 200 }}>
            <InputLabel id="similarity-group">{t('similarity.group')}</InputLabel>
            <Select
              labelId="similarity-group"
              label={t('similarity.group')}
              value={group}
              onChange={(e) => {
                setGroup(e.target.value)
                check.reset()
              }}
            >
              <MenuItem value="">{t('similarity.allStudents')}</MenuItem>
              {groups.map((g) => (
                <MenuItem key={g.id} value={g.id}>{g.name}</MenuItem>
              ))}
            </Select>
          </FormControl>
        )}

        <Button
          variant="contained"
          startIcon={
            running ? <CircularProgress size={16} color="inherit" /> : <CompareArrowsOutlined />
          }
          // Not offered for a check core has already said it would refuse: the alert below says why.
          disabled={!selected || running || rowsLoading || submitted.length < 2 || estimate.kind === 'tooCostly'}
          onClick={run}
        >
          {/* Named apart from an ordinary run: after a group or exercise change this is the run the
              page left behind, still holding core's one permit, and its result will not be shown. */}
          {running
            ? check.isPending && current ? t('similarity.searching') : t('similarity.waitingPrevious')
            : t('similarity.find')}
        </Button>

        <Button variant="text" startIcon={<TuneOutlined />} onClick={() => setSettingsOpen(true)}>
          {t('similarity.settingsButton')}
        </Button>
      </Box>

      <CourseSimilaritySettingsDialog courseId={courseId!} open={settingsOpen} onClose={() => setSettingsOpen(false)} />

      {/* What is about to be compared, before committing to the wait: and how long, once core says. */}
      {selected && !rowsLoading && (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 2 }}>
          {estimate.kind === 'unknown'
            ? t('similarity.scope', { submissions: submitted.length, count: pairCount })
            : estimate.kind === 'nothing'
              ? t('similarity.estimateNothing', { submissions: estimate.submissions })
              : t('similarity.estimateScope', {
              submissions: estimate.submissions,
              count: estimate.pairs,
              duration: formatDuration(estimate.seconds, t),
            })}
        </Typography>
      )}

      {estimate.kind === 'partial' && (
        <Alert severity="info" sx={{ mb: 2 }}>
          {t('similarity.partialAhead', {
            duration: formatDuration(estimate.seconds, t),
            budget: formatDuration(estimate.budget, t),
          })}
        </Alert>
      )}

      {estimate.kind === 'tooCostly' && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          {t('similarity.tooCostly', {
            duration: formatDuration(estimate.seconds, t),
            budget: formatDuration(estimate.budget, t),
          })}
        </Alert>
      )}

      {/* A failed load is said as one: empty, it read as "fewer than two submissions" and disabled Find. */}
      {selected && rowsFailed && (
        <Alert severity="error" sx={{ mb: 2 }}>{errorMessage(rowsError, t)}</Alert>
      )}

      {selected && submitted.length < 2 && !rowsLoading && !rowsFailed && (
        <Alert severity="info" sx={{ mb: 2 }}>{t('similarity.tooFewSubmissions')}</Alert>
      )}

      {estimate.kind === 'unknown' && submitted.length > PAIR_WARNING_THRESHOLD && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          {t('similarity.slowWarning', { pairs: pairCount })}
        </Alert>
      )}

      {check.isError && current && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {/* A named refusal gets its own sentence; anything else keeps the timeout advice, which is
              the right guess for a comparison that simply did not come back. */}
          {isSimilarityRefusal(check.error) ? errorMessage(check.error, t) : t('similarity.failed')}
        </Alert>
      )}

      {result && (
        <>
          <Divider sx={{ my: 3 }} />
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 3, flexWrap: 'wrap', mb: 2 }}>
            <Typography variant="subtitle1">
              {t('similarity.results', { count: pairs.length })}
            </Typography>
            {result.scores.length > 0 && (
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, minWidth: 260 }}>
                <Typography variant="caption" color="text.secondary" sx={{ whiteSpace: 'nowrap' }}>
                  {t('similarity.minScore')}
                </Typography>
                {/* The caption beside it is a sibling, not a label, so without this the control
                    announces as "0, slider" with no indication of what it filters — measured in the
                    browser, where the input carried aria-valuenow/min/max and no name at all.
                    a11y-baseline.json is empty by policy, so this is a new finding, not a known one. */}
                <Slider
                  size="small"
                  value={minScore}
                  onChange={(_, v) => setMinScore(v as number)}
                  valueLabelDisplay="auto"
                  min={0}
                  max={100}
                  aria-label={t('similarity.minScore')}
                  sx={{ maxWidth: 160 }}
                />
              </Box>
            )}
          </Box>

          {/* Which provider measured these, and what that means for reading them: a renamed copy scores
              high on JPlag and low on edit distance, and the teacher has to know which this was. */}
          {result.provider && (
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2, maxWidth: '70ch' }}>
              {comparedBy(result.provider, t)}
            </Typography>
          )}

          {/* Not every pair was compared: stopped at the time limit, or ruled out as unable to rank. */}
          {covered?.kind === 'incomplete' && (
            <Alert severity="warning" sx={{ mb: 2 }}>
              {t('similarity.incomplete', { compared: covered.compared, total: covered.total })}
            </Alert>
          )}
          {covered?.kind === 'ruledOut' && (
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 2 }}>
              {t('similarity.ruledOut', { compared: covered.compared, total: covered.total })}
            </Typography>
          )}

          {/* Core caps its answer at MAX_PAIRS by each of its two orders. Silently showing 200 of
              45,000 would read as "there are 200 suspicious pairs", so say which it is. */}
          {/* Instead of an emptiness sentence, which would blame a threshold of zero. */}
          {unreadable && <Alert severity="warning" sx={{ mb: 2 }}>{t('similarity.unreadable')}</Alert>}

          {result.truncated && (
            <Alert severity="info" sx={{ mb: 2 }}>{t('similarity.capped', { limit: MAX_PAIRS })}</Alert>
          )}

          {/* The course's pinned provider passed over, or the language compared as text; otherwise
              which tokeniser ran. A text-mode result is a weaker answer than a token one, and saying
              so is the difference between "nothing similar" and "we could not read this as code";
              an SQL one reads differently, and says how. resultNotes says which applies when. */}
          {resultNotes(result).map((note) => (
            <Alert key={note} severity={note === 'languageSql' || note === 'pinnedUnavailable' ? 'info' : 'warning'} sx={{ mb: 2 }}>
              {noteText(note)}
            </Alert>
          ))}

          {/* JPlag drops what it cannot parse or what is too short. Silently comparing 4 of 40 and
              reporting "no similar pairs" would read as a clean result, which is the one thing it
              is not. */}
          {skipped.length > 0 && (
            <Alert severity="warning" sx={{ mb: 2 }}>
              {t('similarity.skippedNote', { count: skipped.length, total: result.compared })}
              {/* One line per reason, each naming who, and linking to them like a pair does: a
                  submission that runs but could not be compared is itself a thing to look at. */}
              {skippedGroups.map((group) => (
                <Box key={group.reason} component="span" sx={{ display: 'block', mt: 0.5 }}>
                  {skippedLabel(group.reason, group.students.length)}:{' '}
                  {group.students.map((student, index) => {
                    const link = studentLink(student.submissionId)
                    const name = student.name ?? `#${student.submissionId}`
                    return (
                      <Fragment key={student.submissionId}>
                        {index > 0 && ', '}
                        {link ? <Link {...spaLinkProps(link, navigate)}>{name}</Link> : name}
                      </Fragment>
                    )
                  })}
                </Box>
              ))}
            </Alert>
          )}

          {identicalGroups.map((group) => {
            const first = submissionsById.get(group.submissions[0])
            return (
              // Names and links in the details, not the summary: the summary is a button, and a link
              // inside one is two controls in one place for keyboard and screen-reader users alike.
              <Accordion
                key={group.submissions[0]}
                disableGutters
                variant="outlined"
                slotProps={{ transition: { unmountOnExit: true } }}
              >
                <AccordionSummary expandIcon={<ExpandMoreOutlined />}>
                  <Typography sx={{ fontWeight: 500 }}>
                    {t('similarity.identicalGroup', { count: groupAuthors(group, studentOf) })}
                  </Typography>
                </AccordionSummary>
                <AccordionDetails>
                  <Typography variant="body2" sx={{ mb: 1.5 }}>
                    {group.submissions.map((id, index) => {
                      const sub = submissionsById.get(id)
                      const name = sub ? `${sub.given_name} ${sub.family_name}` : `#${id}`
                      const link = studentLink(id)
                      return (
                        <Fragment key={id}>
                          {index > 0 && ', '}
                          {link ? <Link {...spaLinkProps(link, navigate)}>{name}</Link> : name}
                        </Fragment>
                      )
                    })}
                  </Typography>
                  {first && (
                    <>
                      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.75 }}>
                        {t('similarity.identicalGroupCode', { name: `${first.given_name} ${first.family_name}` })}
                      </Typography>
                      <ReadOnlyCodeSnippet code={first.solution ?? ''} fileName={highlightFileName(result.language)} maxHeight={460} />
                    </>
                  )}
                </AccordionDetails>
              </Accordion>
            )
          })}

          {pairs.length === 0 && !unreadable && emptyNow && (
            <Typography color="text.secondary">
              {/* Two different emptinesses, and only one of them is the slider's doing. The threshold
                  is the explanation exactly when something scored and nothing survived the filter;
                  if nothing scored at all, saying "no pair is above the threshold" blames a control
                  the teacher may never have touched, which at the default of zero is nonsense.
                  These two were the wrong way round: the threshold sentence was shown when pairs
                  *were* above it but had no renderable submissions, and the "nothing found" sentence
                  when the slider had hidden everything. */}
              {t(`similarity.${emptyNow}`)}
            </Typography>
          )}

          {pairs.map((score) => {
            const a = submissionsById.get(score.sub_1)
            const b = submissionsById.get(score.sub_2)
            if (!a || !b) return null
            const sides = [
              { sub: a, link: studentLink(score.sub_1) },
              { sub: b, link: studentLink(score.sub_2) },
            ]
            // Outlined like every other surface: with the bespoke shadow scale gone (X-014), a
            // bare Accordion would be the app's only elevated element.
            //
            // `unmountOnExit` is load-bearing rather than tidiness. MUI keeps AccordionDetails
            // mounted inside a collapsed Collapse by default, so at the 100-pair cap every
            // SimilarityDiff built its editors whether or not the teacher opened it. Measured on a
            // 100-student cohort: 200 CodeMirror instances and 100 merge views with *zero* rows
            // expanded, each doing an async language load and a full diff, which locks the tab up
            // for seconds on a result nobody has started reading yet.
            return (
              <Accordion
                key={`${score.sub_1}-${score.sub_2}`}
                disableGutters
                variant="outlined"
                slotProps={{ transition: { unmountOnExit: true } }}
              >
                <AccordionSummary expandIcon={<ExpandMoreOutlined />}>
                  <Box
                    sx={{ display: 'flex', alignItems: 'center', gap: 2, width: '100%', pr: 2, flexWrap: 'wrap' }}
                  >
                    <Typography sx={{ fontWeight: 500 }}>
                      {a.given_name} {a.family_name} — {b.given_name} {b.family_name}
                    </Typography>
                    {/* A side that stands for a group of identical submissions says how many more it is, and
                        whose: after "A — B", an unnamed chip read as B's whichever side it belonged to. */}
                    {[{ id: score.sub_1, side: a }, { id: score.sub_2, side: b }].map(({ id, side }) => {
                      const others = othersBySubmission.get(id)
                      return others ? (
                        <Chip
                          key={id}
                          size="small"
                          variant="outlined"
                          label={t('similarity.identicalOthers', { count: others, name: `${side.given_name} ${side.family_name}` })}
                        />
                      ) : null
                    })}
                    <Box sx={{ ml: 'auto', display: 'flex', gap: 1 }}>
                      {/* Labelled, because "84% · 61%" invites the question of which is which — and
                          the two answer different questions. Named for what they measure rather
                          than for the algorithm; the algorithm is in the tooltip, for the reader
                          who wants to know how the number was arrived at. Whatever the provider
                          measured, in the order it sent them, the first filled. */}
                      {pairMetrics(score).map((metric, index) => (
                        <Tooltip key={metric.id} title={metricTooltip(metric.id)}>
                          <Chip size="small" variant={index === 0 ? 'filled' : 'outlined'} label={metricLabel(metric)} />
                        </Tooltip>
                      ))}
                    </Box>
                  </Box>
                </AccordionSummary>
                <AccordionDetails>
                  <Box sx={{ display: 'flex', gap: 2, mb: 1.5, flexWrap: 'wrap' }}>
                    {sides.map(({ sub, link }) => (
                      <Box key={sub.id} sx={{ flex: '1 1 300px' }}>
                        <Typography variant="body2" sx={{ fontWeight: 500 }}>
                          {link ? (
                            <Link {...spaLinkProps(link, navigate)} underline="none">
                              {sub.given_name} {sub.family_name}
                              <OpenInNewOutlined
                                sx={{ fontSize: 14, ml: 0.5, verticalAlign: 'middle' }}
                              />
                            </Link>
                          ) : (
                            <>
                              {sub.given_name} {sub.family_name}
                            </>
                          )}
                        </Typography>
                        <Typography variant="caption" color="text.secondary">
                          {/*
                            Absolute, not relative. The two sides of a similarity pair are almost
                            always submitted the same day, so "3 hours ago" on both would hide the
                            one thing a teacher is reading this page for: whether they were minutes
                            apart. Everywhere else a submission time is a <RelativeTime>; here the
                            exact clock time is the evidence.
                          */}
                          {sub.course_title} ·{' '}
                          {formatDateTime(new Date(sub.created_at), dateFnsLocale)}
                        </Typography>
                      </Box>
                    ))}
                  </Box>
                  {/*
                    The highlight is the opposite of what a merge view usually means, so it says so
                    once, next to the thing it describes. Without this the blue reads as "changed".
                  */}
                  <Box
                    sx={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 0.75,
                      mb: 0.75,
                      color: 'text.secondary',
                      fontSize: 12,
                    }}
                  >
                    <Box
                      aria-hidden
                      sx={{
                        width: 22,
                        height: 12,
                        borderRadius: 0.5,
                        flexShrink: 0,
                        bgcolor: (t) =>
                          t.palette.mode === 'dark' ? SHARED_DARK : SHARED_LIGHT,
                        outline: '1px solid',
                        outlineColor: 'divider',
                      }}
                    />
                    {t('similarity.sharedLegend')}
                  </Box>
                  {/* CodeMirror picks its grammar from the extension. The exercise list does not
                      carry solution_file_name, but the comparison already resolved a language and
                      that is the same decision, so derive it rather than add an API field. */}
                  <SimilarityDiff
                    left={a.solution ?? ''}
                    right={b.solution ?? ''}
                    fileName={highlightFileName(result.language)}
                    matchedLeft={matchedByPair.get(`${score.sub_1}-${score.sub_2}`)?.left}
                    matchedRight={matchedByPair.get(`${score.sub_1}-${score.sub_2}`)?.right}
                    matchRanges={usesMatchRanges(result)}
                  />
                </AccordionDetails>
              </Accordion>
            )
          })}
        </>
      )}
    </>
  )
}
