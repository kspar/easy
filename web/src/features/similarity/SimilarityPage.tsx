import { useMemo, useState } from 'react'
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
import { format } from 'date-fns'
import { enGB } from 'date-fns/locale'
import usePageTitle from '../../hooks/usePageTitle.ts'
import useSavedGroup from '../../hooks/useSavedGroup.ts'
import {
  useCheckSimilarity,
  useCourseGroups,
  useTeacherCourseExercises,
  useTeacherSubmissionSummaries,
} from '../../api/exercises.ts'
import SimilarityDiff from './SimilarityDiff.tsx'
import { spaLinkProps } from '../../components/spaLink.ts'

/**
 * Above this many submissions, say out loud how many comparisons that is.
 *
 * Core compares every pair inside the request, so the work grows with the square of the class: 40
 * students is 780 comparisons, 300 is nearly 45,000. The scaling problem is EZ-1667's to solve; this
 * page's job is to not let a teacher click a button that will appear to hang.
 */
const PAIR_WARNING_THRESHOLD = 120

export default function SimilarityPage() {
  const { courseId } = useParams<{ courseId: string }>()
  const navigate = useNavigate()
  const { t } = useTranslation()
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

  // submission id -> student id, so each side of a pair can link to that student's grading view.
  // The similarity response carries names but no ids, and this is the only place both are known.
  const studentBySubmission = useMemo(() => {
    const map = new Map<string, string>()
    for (const r of submitted) if (r.submission) map.set(r.submission.id, r.student_id)
    return map
  }, [submitted])

  const check = useCheckSimilarity(exerciseId || undefined)
  const result = check.data

  const submissionsById = useMemo(() => {
    const map = new Map<string, NonNullable<typeof result>['submissions'][number]>()
    for (const s of result?.submissions ?? []) map.set(s.id, s)
    return map
  }, [result])

  const pairs = useMemo(
    () => (result?.scores ?? []).filter((s) => Math.max(s.score_a, s.score_b) >= minScore),
    [result, minScore],
  )

  const run = () => {
    if (!selected) return
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
          {t('similarity.scope', { submissions: submitted.length, pairs: pairCount })}
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

      {check.isError && <Alert severity="error" sx={{ mb: 2 }}>{t('similarity.failed')}</Alert>}

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
          </Box>

          {/* Core caps its answer at the 100 highest pairs. Silently showing 100 of 45,000 would
              read as "there are 100 suspicious pairs", so say which it is. */}
          {result.scores.length >= 100 && (
            <Alert severity="info" sx={{ mb: 2 }}>{t('similarity.capped')}</Alert>
          )}

          {result.scores.length === 0 && (
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
            // Outlined like every other surface: with the bespoke shadow scale gone (X-014), a
            // bare Accordion would be the app's only elevated element.
            return (
              <Accordion key={`${score.sub_1}-${score.sub_2}`} disableGutters variant="outlined">
                <AccordionSummary expandIcon={<ExpandMoreOutlined />}>
                  <Box
                    sx={{ display: 'flex', alignItems: 'center', gap: 2, width: '100%', pr: 2, flexWrap: 'wrap' }}
                  >
                    <Typography sx={{ fontWeight: 500 }}>
                      {a.given_name} {a.family_name} — {b.given_name} {b.family_name}
                    </Typography>
                    <Box sx={{ ml: 'auto', display: 'flex', gap: 1 }}>
                      {/* Labelled, because "84% · 61%" invites the question of which is which — and
                          the two answer different questions. */}
                      <Chip size="small" label={t('similarity.diceShort', { score: score.score_a })} />
                      <Chip
                        size="small"
                        variant="outlined"
                        label={t('similarity.levenshteinShort', { score: score.score_b })}
                      />
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
                          {sub.course_title} ·{' '}
                          {format(new Date(sub.created_at), 'dd/MM/yyyy HH:mm', { locale: enGB })}
                        </Typography>
                      </Box>
                    ))}
                  </Box>
                  <SimilarityDiff left={a.solution} right={b.solution} fileName="lahendus.py" />
                </AccordionDetails>
              </Accordion>
            )
          })}
        </>
      )}
    </>
  )
}
