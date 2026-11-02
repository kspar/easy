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
  LinearProgress,
  Link,
  MenuItem,
  Select,
  Slider,
  ToggleButton,
  ToggleButtonGroup,
  Tooltip,
  Typography,
} from '@mui/material'
import {
  ArrowBackOutlined,
  CompareArrowsOutlined,
  ExpandMoreOutlined,
  OpenInNewOutlined,
} from '@mui/icons-material'
import { useNavigate, useParams, useSearchParams, Link as RouterLink } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { formatDateTime, useDateLocale } from '../../i18n/dateLocale.ts'
import usePageTitle from '../../hooks/usePageTitle.ts'
import useSavedGroup from '../../hooks/useSavedGroup.ts'
import {
  useCheckSimilarity,
  useCourseGroups,
  useSimilarityProgress,
  useTeacherCourseExercises,
  useTeacherSubmissionSummaries,
} from '../../api/exercises.ts'
import { errorMessage } from '../../api/errorMessage.ts'
import SimilarityDiff from './SimilarityDiff.tsx'
import ReadOnlyCodeSnippet from '../course-exercise/ReadOnlyCodeSnippet.tsx'
import { SHARED_LIGHT, SHARED_DARK, matchEntries } from './similarityHighlight.ts'
import type { MatchedLines } from './similarityHighlight.ts'
import {
  formatClock,
  highlightFileName,
  isSimilarityBusy,
  isSimilarityRefusal,
  orderPairs,
  skippedByReason,
  usesMatchRanges,
} from './similarityResult.ts'
import type { PairOrder, SkippedReason } from './similarityResult.ts'
import { spaLinkProps } from '../../components/spaLink.ts'

/**
 * Above this many submissions, say out loud how many comparisons that is.
 *
 * Core compares every pair inside the request, so the work grows with the square of the class: 40
 * students is 780 comparisons, 300 is nearly 45,000. This page's job is to not let a teacher click a
 * button that will appear to hang.
 */
const PAIR_WARNING_THRESHOLD = 120

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
  const [order, setOrder] = useState<PairOrder>('shared')
  // The match picked in each opened pair, by the pair's key: marked and scrolled to on both sides.
  const [selectedMatch, setSelectedMatch] = useState<Record<string, number | undefined>>({})

  const { data: exercises, isLoading: exercisesLoading } = useTeacherCourseExercises(courseId)
  const { data: groups } = useCourseGroups(courseId!)

  const selected = exercises?.find((e) => e.exercise_id === exerciseId)

  // Which submissions to compare. Fetched as soon as an exercise is chosen rather than inside the
  // click handler, so the pair count — and any warning about it — is on screen *before* committing
  // to the wait.
  const { data: rows, isLoading: rowsLoading } = useTeacherSubmissionSummaries(
    courseId!,
    selected?.course_exercise_id ?? '',
    group || undefined,
  )

  const submitted = useMemo(() => (rows ?? []).filter((r) => r.submission), [rows])
  const pairCount = (submitted.length * (submitted.length - 1)) / 2

  const check = useCheckSimilarity(exerciseId || undefined)
  // What the last run was over. Its result is shown only under that selection: navigation changes the
  // selection without the selects' reset, and Back after running another exercise put that one's pairs
  // under this one's name. Its rows too, so a refetch of the summaries cannot take names from under it.
  const [ranOver, setRanOver] = useState<{ exerciseId: string; group: string; submitted: typeof submitted }>()
  const current = ranOver !== undefined && ranOver.exerciseId === exerciseId && ranOver.group === group
  const result = current ? check.data : undefined
  const waiting = check.isPending && current
  // Refused as busy: progress then says whether the running check is this teacher's own, left running.
  const busy = check.isError && current && isSimilarityBusy(check.error)
  const { data: progress } = useSimilarityProgress(waiting || busy)
  const comparing = progress?.running === true && progress.phase === 'comparing' && progress.total > 0

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

  // Filtered on the maximum: the share of whichever solution is covered more, so a small file pasted
  // whole into a larger one is not filtered out.
  const pairs = useMemo(
    () => orderPairs((result?.scores ?? []).filter((s) => s.max_similarity >= minScore), order),
    [result, minScore, order],
  )

  // Memoised per pair, because they are props of SimilarityDiff and in its effect's dependencies: built
  // inline, every render rebuilt every open pair's editors, and the slider renders per mousemove.
  const matchedByPair = useMemo(() => {
    const byPair = new Map<string, { left: MatchedLines[]; right: MatchedLines[] }>()
    for (const score of result?.scores ?? []) {
      byPair.set(`${score.sub_1}-${score.sub_2}`, {
        left: score.matches.map((m) => ({ startLine: m.start_line_1, endLine: m.end_line_1 })),
        right: score.matches.map((m) => ({ startLine: m.start_line_2, endLine: m.end_line_2 })),
      })
    }
    return byPair
  }, [result])

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

  const nameLink = (submissionId: string, name: string) => {
    const link = studentLink(submissionId)
    return link ? <Link {...spaLinkProps(link, navigate)}>{name}</Link> : name
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
            check.isPending ? <CircularProgress size={16} color="inherit" /> : <CompareArrowsOutlined />
          }
          disabled={!selected || check.isPending || rowsLoading || submitted.length < 2}
          onClick={run}
        >
          {check.isPending ? t('similarity.searching') : t('similarity.find')}
        </Button>
      </Box>

      {/* What is about to be compared, before committing to the wait. */}
      {selected && !rowsLoading && (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 2 }}>
          {t('similarity.scope', { submissions: submitted.length, count: pairCount })}
        </Typography>
      )}

      {selected && submitted.length < 2 && !rowsLoading && (
        <Alert severity="info" sx={{ mb: 2 }}>{t('similarity.tooFewSubmissions')}</Alert>
      )}

      {submitted.length > PAIR_WARNING_THRESHOLD && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          {t('similarity.slowWarning', { pairs: pairCount })}
        </Alert>
      )}

      {/* How far the check has got: it answers only at the end, up to two minutes later. */}
      {waiting && (
        <Box sx={{ mb: 2, maxWidth: 560 }}>
          <LinearProgress
            variant={comparing ? 'determinate' : 'indeterminate'}
            value={comparing && progress ? (100 * progress.done) / progress.total : undefined}
            aria-label={t('similarity.progressName')}
          />
          {/* Nothing until core has started: the first poll often lands before it has. */}
          {progress?.running && (
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.75 }}>
              {comparing
                ? t('similarity.progressComparing', {
                  done: progress.done, total: progress.total,
                  elapsed: formatClock(progress.elapsed_sec), budget: formatClock(progress.budget_sec),
                })
                : t('similarity.progressPreparing', { elapsed: formatClock(progress.elapsed_sec), budget: formatClock(progress.budget_sec) })}
            </Typography>
          )}
        </Box>
      )}

      {check.isError && current && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {busy && progress?.running
            ? t('similarity.busyOwnRun', { elapsed: formatClock(progress.elapsed_sec), budget: formatClock(progress.budget_sec) })
            : isSimilarityRefusal(check.error) ? errorMessage(check.error, t) : t('similarity.failed')}
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
                <Slider
                  size="small"
                  value={minScore}
                  onChange={(_, v) => setMinScore(v as number)}
                  valueLabelDisplay="auto"
                  min={0}
                  max={100}
                  sx={{ maxWidth: 160 }}
                />
              </Box>
            )}
            {/* JPlag ranks by how much code a pair shares, which puts a short copy at 100% below long
                pairs at 75%; edit distance ranks by its score already. */}
            {usesMatchRanges(result.provider) && result.scores.length > 0 && (
              <ToggleButtonGroup
                size="small"
                exclusive
                value={order}
                onChange={(_, next: PairOrder | null) => next && setOrder(next)}
                aria-label={t('similarity.orderName')}
              >
                <ToggleButton value="shared" sx={{ textTransform: 'none' }}>{t('similarity.orderShared')}</ToggleButton>
                <ToggleButton value="score" sx={{ textTransform: 'none' }}>{t('similarity.orderScore')}</ToggleButton>
              </ToggleButtonGroup>
            )}
          </Box>

          {/* What measured these, and what that means for reading them: a renamed copy scores high on
              JPlag and low on edit distance. A Python exercise whose answers are prose was compared as
              text instead, which renaming does change, and says so in place of the JPlag line. */}
          {usesMatchRanges(result.provider) && result.language === 'text' ? (
            <Alert severity="warning" sx={{ mb: 2 }}>{t('similarity.languageTextNote')}</Alert>
          ) : (
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2, maxWidth: '70ch' }}>
              {usesMatchRanges(result.provider) ? t('similarity.comparedByJplag') : t('similarity.comparedByEditDistance')}
            </Typography>
          )}

          {/* Silently showing 100 of 45,000 would read as "there are 100 suspicious pairs". */}
          {result.truncated && (
            <Alert severity="info" sx={{ mb: 2 }}>{t('similarity.capped')}</Alert>
          )}

          {/* Silently comparing 4 of 40 and reporting "no similar pairs" would read as a clean result.
              Named and linked: a solution that runs but could not be compared is itself worth a look. */}
          {result.skipped.length > 0 && (
            <Alert severity="warning" sx={{ mb: 2 }}>
              {t('similarity.skippedNote', { count: result.skipped.length, total: result.compared })}
              {skippedGroups.map(({ reason, students }) => (
                <Box key={reason} component="span" sx={{ display: 'block', mt: 0.5 }}>
                  {skippedLabel(reason, students.length)}:{' '}
                  {students.map((student, index) => (
                    <Fragment key={student.submissionId}>
                      {index > 0 && ', '}
                      {nameLink(student.submissionId, student.name ?? `#${student.submissionId}`)}
                    </Fragment>
                  ))}
                </Box>
              ))}
            </Alert>
          )}

          {/* Identical submissions by three or more students, compared once: twelve untouched copies of
              the starter code would otherwise be 66 rows at 100%. */}
          {result.groups.map((identical) => {
            const first = submissionsById.get(identical.submissions[0])
            return (
              <Accordion
                key={identical.submissions[0]}
                disableGutters
                variant="outlined"
                slotProps={{ transition: { unmountOnExit: true } }}
              >
                <AccordionSummary expandIcon={<ExpandMoreOutlined />}>
                  <Typography sx={{ fontWeight: 500 }}>
                    {t('similarity.identicalGroup', { count: new Set(identical.submissions.map((id) => studentBySubmission.get(id) ?? id)).size })}
                  </Typography>
                </AccordionSummary>
                <AccordionDetails>
                  {/* Names and links here, not in the summary: a link inside its button is two controls in one place. */}
                  <Typography variant="body2" sx={{ mb: 1.5 }}>
                    {identical.submissions.map((id, index) => {
                      const sub = submissionsById.get(id)
                      return (
                        <Fragment key={id}>
                          {index > 0 && ', '}
                          {nameLink(id, sub ? `${sub.given_name} ${sub.family_name}` : `#${id}`)}
                        </Fragment>
                      )
                    })}
                  </Typography>
                  {first && (
                    <>
                      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.75 }}>
                        {t('similarity.identicalGroupCode', { name: `${first.given_name} ${first.family_name}` })}
                      </Typography>
                      <ReadOnlyCodeSnippet code={first.solution} fileName={highlightFileName(result.language)} maxHeight={460} />
                    </>
                  )}
                </AccordionDetails>
              </Accordion>
            )
          })}

          {result.scores.length === 0 && result.groups.length === 0 && (
            <Typography color="text.secondary">{t('similarity.noPairs')}</Typography>
          )}

          {pairs.map((score) => {
            const a = submissionsById.get(score.sub_1)
            const b = submissionsById.get(score.sub_2)
            if (!a || !b) return null
            const sides = [
              { sub: a, link: studentLink(score.sub_1) },
              { sub: b, link: studentLink(score.sub_2) },
            ]
            const pairKey = `${score.sub_1}-${score.sub_2}`
            const jplag = usesMatchRanges(result.provider)
            const matchList = jplag ? matchEntries(score.matches) : { shown: [], more: 0 }
            // Outlined like every other surface: with the bespoke shadow scale gone (X-014), a
            // bare Accordion would be the app's only elevated element.
            //
            // `unmountOnExit`, because MUI otherwise keeps every collapsed pair mounted, and a hundred
            // pairs built two hundred editors nobody had opened.
            return (
              <Accordion
                key={pairKey}
                disableGutters
                variant="outlined"
                slotProps={{ transition: { unmountOnExit: true } }}
              >
                <AccordionSummary expandIcon={<ExpandMoreOutlined />}>
                  <Box
                    sx={{ display: 'flex', alignItems: 'center', gap: 1, columnGap: 2, width: '100%', pr: { xs: 0, sm: 2 }, flexWrap: 'wrap' }}
                  >
                    <Typography sx={{ fontWeight: 500, overflowWrap: 'anywhere' }}>
                      {a.given_name} {a.family_name} — {b.given_name} {b.family_name}
                    </Typography>
                    <Box sx={{ ml: 'auto', display: 'flex', gap: 1, flexWrap: 'wrap' }}>
                      {/* Labelled, because "84% · 61%" invites the question of which is which — and
                          the two answer different questions. The method is in the tooltip. */}
                      {jplag ? (
                        <>
                          {/* First, because the default order is by it: without it the list reads as unsorted. */}
                          {score.shared_tokens != null && (
                            <Tooltip title={t('similarity.sharedTooltip')}>
                              <Chip size="small" variant="outlined" label={t('similarity.sharedShort', { count: score.shared_tokens })} />
                            </Tooltip>
                          )}
                          <Tooltip title={t('similarity.similarityTooltip')}>
                            <Chip size="small" label={t('similarity.similarityShort', { score: score.similarity })} />
                          </Tooltip>
                          <Tooltip title={t('similarity.maxSimilarityTooltip')}>
                            <Chip size="small" variant="outlined" label={t('similarity.maxSimilarityShort', { score: score.max_similarity })} />
                          </Tooltip>
                        </>
                      ) : (
                        <Tooltip title={t('similarity.levenshteinTooltip')}>
                          <Chip size="small" label={t('similarity.levenshteinShort', { score: score.max_similarity })} />
                        </Tooltip>
                      )}
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
                  {/* Which block is the copy of which: the band shades every matched line alike. */}
                  {matchList.shown.length > 0 && (
                    <Box sx={{ mb: 1 }}>
                      <Typography variant="caption" color="text.secondary" component="div">
                        {t('similarity.matchesTitle')}
                      </Typography>
                      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5, mt: 0.5, alignItems: 'center' }}>
                        {matchList.shown.map((m) => {
                          const on = selectedMatch[pairKey] === m.index
                          return (
                            <Chip
                              key={m.index}
                              size="small"
                              clickable
                              color={on ? 'primary' : 'default'}
                              variant={on ? 'filled' : 'outlined'}
                              aria-pressed={on}
                              label={t('similarity.matchChip', { fromA: m.fromA, toA: m.toA, fromB: m.fromB, toB: m.toB })}
                              onClick={() => setSelectedMatch((picked) => ({ ...picked, [pairKey]: on ? undefined : m.index }))}
                            />
                          )
                        })}
                        {matchList.more > 0 && (
                          <Typography variant="caption" color="text.secondary">
                            {t('similarity.matchesMore', { count: matchList.more })}
                          </Typography>
                        )}
                      </Box>
                    </Box>
                  )}
                  <SimilarityDiff
                    left={a.solution}
                    right={b.solution}
                    fileName={highlightFileName(result.language)}
                    matchedLeft={jplag ? matchedByPair.get(pairKey)?.left : undefined}
                    matchedRight={jplag ? matchedByPair.get(pairKey)?.right : undefined}
                    selectedMatch={selectedMatch[pairKey]}
                    leftLabel={`${a.given_name} ${a.family_name}`}
                    rightLabel={`${b.given_name} ${b.family_name}`}
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
