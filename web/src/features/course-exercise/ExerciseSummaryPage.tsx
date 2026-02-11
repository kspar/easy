import { useCallback, useRef, useState } from 'react'
import {
  Typography,
  CircularProgress,
  Alert,
  Box,
  Chip,
  Divider,
  IconButton,
  Tooltip,
  useMediaQuery,
} from '@mui/material'
import { useTheme } from '@mui/material/styles'
import {
  ArrowBack,
  FirstPage,
  LastPage,
  VerticalSplit,
} from '@mui/icons-material'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { format } from 'date-fns'
import { et, enUS } from 'date-fns/locale'
import {
  useExerciseDetails,
  useSubmissions,
} from '../../api/exercises.ts'
import usePageTitle from '../../hooks/usePageTitle.ts'
import SubmitTab from './SubmitTab.tsx'
import AutoTestResults from './AutoTestResults.tsx'
import TeacherFeedback from './TeacherFeedback.tsx'
import PreviousSubmissions from './PreviousSubmissions.tsx'

function GradeBanner({
  courseId,
  courseExerciseId,
  threshold,
}: {
  courseId: string
  courseExerciseId: string
  threshold: number
}) {
  const { t } = useTranslation()
  const { data: submissions } = useSubmissions(courseId, courseExerciseId)

  if (!submissions || submissions.length === 0) return null

  const latest = submissions[0]
  if (!latest.grade) {
    return (
      <Alert severity="info" sx={{ mb: 2 }}>
        {t('submission.currentGrade')}: {t('exercises.notGraded')}
      </Alert>
    )
  }

  const grade = latest.grade.grade
  const severity = grade >= threshold ? 'success' : 'warning'

  return (
    <Alert severity={severity} sx={{ mb: 2 }}>
      {t('submission.currentGrade')}: {grade} / 100
    </Alert>
  )
}

type CollapseState = 'none' | 'left' | 'right'

const DEFAULT_LEFT_PCT = 40
const MIN_PCT = 20
const MAX_PCT = 80
const HEADER_HEIGHT = 48
function readStored<T>(key: string, fallback: T): T {
  try {
    const v = localStorage.getItem(key)
    return v != null ? JSON.parse(v) : fallback
  } catch {
    return fallback
  }
}

function SplitPane({
  left,
  right,
  storageKey,
}: {
  left: React.ReactNode
  right: React.ReactNode
  storageKey: string
}) {
  const { t } = useTranslation()
  const theme = useTheme()
  const isMobile = useMediaQuery(theme.breakpoints.down('md'))
  const pctKey = `splitPane.${storageKey}.leftPct`
  const collapseKey = `splitPane.${storageKey}.collapsed`
  const [leftPct, setLeftPctRaw] = useState(() => readStored<number>(pctKey, DEFAULT_LEFT_PCT))
  const [collapsed, setCollapsedRaw] = useState<CollapseState>(() => readStored<CollapseState>(collapseKey, 'none'))

  const setLeftPct = useCallback((pct: number) => {
    setLeftPctRaw(pct)
    localStorage.setItem(pctKey, JSON.stringify(pct))
  }, [pctKey])

  const setCollapsed = useCallback((val: CollapseState | ((prev: CollapseState) => CollapseState)) => {
    setCollapsedRaw((prev) => {
      const next = typeof val === 'function' ? val(prev) : val
      localStorage.setItem(collapseKey, JSON.stringify(next))
      return next
    })
  }, [])
  const containerRef = useRef<HTMLDivElement>(null)
  const dragging = useRef(false)
  const leftPctRef = useRef(leftPct)
  leftPctRef.current = leftPct

  const handleMouseDown = useCallback(
    (e: React.MouseEvent) => {
      if (collapsed !== 'none' || !containerRef.current) return
      e.preventDefault()
      dragging.current = true

      const rect = containerRef.current.getBoundingClientRect()
      const currentDividerX = rect.left + (leftPctRef.current / 100) * rect.width
      const offsetX = e.clientX - currentDividerX

      const onMouseMove = (ev: MouseEvent) => {
        if (!dragging.current || !containerRef.current) return
        const rect = containerRef.current.getBoundingClientRect()
        const pct = ((ev.clientX - offsetX - rect.left) / rect.width) * 100
        setLeftPct(Math.min(MAX_PCT, Math.max(MIN_PCT, pct)))
      }

      const onMouseUp = () => {
        dragging.current = false
        document.removeEventListener('mousemove', onMouseMove)
        document.removeEventListener('mouseup', onMouseUp)
        document.body.style.cursor = ''
        document.body.style.userSelect = ''
      }

      document.body.style.cursor = 'col-resize'
      document.body.style.userSelect = 'none'
      document.addEventListener('mousemove', onMouseMove)
      document.addEventListener('mouseup', onMouseUp)
    },
    [collapsed],
  )

  const toggleCollapse = useCallback(
    (side: 'left' | 'right') => {
      setCollapsed((prev) => (prev === side ? 'none' : side))
    },
    [],
  )

  if (isMobile) {
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
        {left}
        {right}
      </Box>
    )
  }

  const showLeft = collapsed !== 'left'
  const showRight = collapsed !== 'right'

  return (
    <Box
      ref={containerRef}
      sx={{ display: 'flex', minHeight: 0, gap: 0 }}
    >
      {/* Left pane */}
      {showLeft && (
        <Box
          sx={{
            ...(collapsed === 'right'
              ? { flex: 1, minWidth: 0 }
              : { width: `${leftPct}%`, flexShrink: 0 }),
            position: 'sticky',
            top: HEADER_HEIGHT,
            maxHeight: `calc(100vh - ${HEADER_HEIGHT}px)`,
            overflow: 'auto',
            alignSelf: 'flex-start',
            pr: collapsed !== 'none' ? 0 : 2,
          }}
        >
          {left}
        </Box>
      )}

      {/* Gutter: drag handle + collapse buttons */}
      <Box
        sx={{
          width: 34,
          flexShrink: 0,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          position: 'relative',
        }}
      >
        {/* Draggable area — subtle line, only visible on hover */}
        {collapsed === 'none' && (
          <Box
            onMouseDown={handleMouseDown}
            sx={{
              position: 'absolute',
              top: 0,
              bottom: 0,
              left: '50%',
              transform: 'translateX(-50%)',
              width: 16,
              cursor: 'col-resize',
              display: 'flex',
              alignItems: 'stretch',
              justifyContent: 'center',
              '&::after': {
                content: '""',
                width: 1,
                bgcolor: 'transparent',
                transition: 'background-color 0.2s',
              },
              '&:hover::after': {
                bgcolor: 'action.disabled',
              },
            }}
          />
        )}

        {/* Collapse/restore buttons — sticky at top */}
        <Box
          sx={{
            position: 'sticky',
            top: HEADER_HEIGHT + 4,
            display: 'flex',
            flexDirection: 'column',
            gap: 0.25,
            zIndex: 1,
          }}
        >
          {collapsed !== 'none' ? (
            <Tooltip title={t('nav.splitView')} placement="right">
              <IconButton
                size="small"
                onClick={() => setCollapsed('none')}
                sx={{
                  opacity: 0.4,
                  '&:hover': { opacity: 1, bgcolor: 'action.hover' },
                }}
              >
                <VerticalSplit fontSize="small" />
              </IconButton>
            </Tooltip>
          ) : (
            <>
              <Tooltip title={t('nav.collapseLeft')} placement="right">
                <IconButton
                  size="small"
                  onClick={() => toggleCollapse('left')}
                  sx={{
                    opacity: 0.4,
                    '&:hover': { opacity: 1, bgcolor: 'action.hover' },
                  }}
                >
                  <FirstPage fontSize="small" />
                </IconButton>
              </Tooltip>
              <Tooltip title={t('nav.collapseRight')} placement="right">
                <IconButton
                  size="small"
                  onClick={() => toggleCollapse('right')}
                  sx={{
                    opacity: 0.4,
                    '&:hover': { opacity: 1, bgcolor: 'action.hover' },
                  }}
                >
                  <LastPage fontSize="small" />
                </IconButton>
              </Tooltip>
            </>
          )}
        </Box>
      </Box>

      {/* Right pane */}
      {showRight && (
        <Box
          sx={{
            flex: 1,
            minWidth: 0,
            pl: collapsed === 'left' ? 0 : 2,
          }}
        >
          {right}
        </Box>
      )}
    </Box>
  )
}

export default function ExerciseSummaryPage() {
  const { courseId, courseExerciseId } = useParams<{
    courseId: string
    courseExerciseId: string
  }>()
  const navigate = useNavigate()
  const { t, i18n } = useTranslation()
  const dateFnsLocale = i18n.language === 'et' ? et : enUS

  const {
    data: exercise,
    isLoading,
    error,
  } = useExerciseDetails(courseId!, courseExerciseId!)

  const { data: submissions } = useSubmissions(courseId!, courseExerciseId!)

  usePageTitle(exercise?.effective_title)

  if (isLoading) return <CircularProgress />
  if (error)
    return <Alert severity="error">{t('general.somethingWentWrong')}</Alert>
  if (!exercise) return null

  const latestSubmission = submissions?.[0] ?? null

  const leftPane = (
    <>
      {exercise.text_html && (
        <Box dangerouslySetInnerHTML={{ __html: exercise.text_html }} />
      )}
      {exercise.instructions_html && (
        <Box
          sx={{ mt: 2 }}
          dangerouslySetInnerHTML={{ __html: exercise.instructions_html }}
        />
      )}
    </>
  )

  const rightPane = (
    <>
      <GradeBanner
        courseId={courseId!}
        courseExerciseId={courseExerciseId!}
        threshold={exercise.threshold}
      />

      <SubmitTab
        courseId={courseId!}
        courseExerciseId={courseExerciseId!}
        exercise={exercise}
      />

      {latestSubmission?.auto_assessment && (
        <>
          <Divider sx={{ my: 3 }} />
          <AutoTestResults
            autoAssessment={latestSubmission.auto_assessment}
          />
        </>
      )}

      <TeacherFeedback
        courseId={courseId!}
        courseExerciseId={courseExerciseId!}
      />

      <PreviousSubmissions
        courseId={courseId!}
        courseExerciseId={courseExerciseId!}
      />
    </>
  )

  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
        <IconButton
          onClick={() => navigate(`/courses/${courseId}/exercises`)}
          size="small"
        >
          <ArrowBack />
        </IconButton>
        <Typography variant="h5">{exercise.effective_title}</Typography>
      </Box>

      <Box sx={{ display: 'flex', gap: 1, mb: 2, flexWrap: 'wrap' }}>
        <Chip
          label={
            exercise.grader_type === 'AUTO'
              ? t('exercises.gradedAutomatically')
              : t('exercises.gradedByTeacher')
          }
          size="small"
          variant="outlined"
        />
        {exercise.deadline && (
          <Chip
            label={`${t('exercises.deadline')}: ${format(new Date(exercise.deadline), 'PPp', { locale: dateFnsLocale })}`}
            size="small"
            variant="outlined"
          />
        )}
        {!exercise.is_open && (
          <Chip
            label={t('submission.exerciseClosed')}
            size="small"
            color="error"
            variant="outlined"
          />
        )}
      </Box>

      <SplitPane storageKey="studentExercise" left={leftPane} right={rightPane} />
    </>
  )
}
