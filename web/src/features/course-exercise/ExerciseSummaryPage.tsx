import { useCallback, useRef, useState } from 'react'
import {
  Typography,
  CircularProgress,
  Alert,
  Box,
  Chip,
  Collapse,
  Divider,
  IconButton,
  Tooltip,
  useMediaQuery,
} from '@mui/material'
import { useTheme } from '@mui/material/styles'
import {
  ArrowBackOutlined,
  ExpandMoreOutlined,
  CheckCircle,
  CircleOutlined,
  FaceOutlined,
  FirstPageOutlined,
  UpdateOutlined,
  LastPageOutlined,
  VerticalSplitOutlined,
  LibraryBooksOutlined,
  SettingsOutlined,
} from '@mui/icons-material'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { format, type Locale } from 'date-fns'
import { et, enGB } from 'date-fns/locale'
import { useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../../auth/AuthContext.tsx'
import {
  useExerciseDetails,
  useTeacherExerciseDetails,
  useSubmissions,
  useParticipants,
  useCourseGroups,
} from '../../api/exercises.ts'
import type {
  ExceptionStudent,
  ExceptionGroup,
  StudentParticipant,
  GroupResp,
  SubmissionResp,
} from '../../api/types.ts'
import usePageTitle from '../../hooks/usePageTitle.ts'
import SubmitTab, { type SubmitTabHandle } from './SubmitTab.tsx'
import AutoTestResults from './AutoTestResults.tsx'
import TeacherFeedback from './TeacherFeedback.tsx'
import PreviousSubmissions from './PreviousSubmissions.tsx'
import RobotPlaceholder from '../../components/RobotPlaceholder.tsx'
import ExerciseSettingsDialog from './ExerciseSettingsDialog.tsx'
import { RobotIcon } from '../../components/icons.tsx'
import AutogradeAnimation from './AutogradeAnimation.tsx'

function GradeBanner({
  submissions,
  threshold,
}: {
  submissions: SubmissionResp[] | undefined
  threshold: number
}) {
  const { t } = useTranslation()

  if (!submissions || submissions.length === 0) return null

  const latest = submissions[0]
  if (!latest.grade) {
    return (
      <Alert severity="info" sx={{ mb: 2 }} iconMapping={{ info: <CircleOutlined fontSize="inherit" /> }}>
        {t('submission.currentGrade')}: {t('exercises.notGraded')}
      </Alert>
    )
  }

  const grade = latest.grade.grade
  const severity = grade >= threshold ? 'success' : 'warning'
  const indirect = !latest.grade.is_graded_directly

  return (
    <Alert severity={severity} sx={{ mb: 2 }} iconMapping={{ success: <CheckCircle fontSize="inherit" />, warning: <CircleOutlined fontSize="inherit" /> }}>
      {t('submission.currentGrade')}: {grade} / 100
      {indirect && (
        <Tooltip title={t('submission.gradePreviousSubmission')}>
          <UpdateOutlined sx={{ fontSize: 18, ml: 1, verticalAlign: 'text-bottom', cursor: 'help' }} />
        </Tooltip>
      )}
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
                <VerticalSplitOutlined fontSize="small" />
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
                  <FirstPageOutlined fontSize="small" />
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
                  <LastPageOutlined fontSize="small" />
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
  const { activeRole } = useAuth()

  if (activeRole === 'student') {
    return <StudentExerciseView />
  }
  return <TeacherExerciseView />
}

function StudentExerciseView() {
  const { courseId, courseExerciseId } = useParams<{
    courseId: string
    courseExerciseId: string
  }>()
  const navigate = useNavigate()
  const { t, i18n } = useTranslation()
  const dateFnsLocale = i18n.language === 'et' ? et : enGB

  const {
    data: exercise,
    isLoading,
    error,
  } = useExerciseDetails(courseId!, courseExerciseId!)

  const { data: submissions } = useSubmissions(courseId!, courseExerciseId!)

  const submitTabRef = useRef<SubmitTabHandle>(null)
  const queryClient = useQueryClient()

  // --- Autograde animation state machine ---
  //
  // State: idle → grading → completed → revealing → idle
  //
  // The autograde animation plays a multi-phase visual (compile/test/analyze),
  // then a checkmark, then typewriter-reveals each test result one by one.
  // During this whole sequence, the GradeBanner and sidebar exercise status must
  // NOT update prematurely — they should reflect the pre-autograde state until
  // the reveal animation finishes, then update as the final step.
  //
  // GradeBanner freeze mechanism:
  //   GradeBanner and AutoTestResults both read from the same submissions query.
  //   When autograde completes, SubmitTab refetches submissions (so AutoTestResults
  //   can render the results during the typewriter). But GradeBanner must NOT see
  //   the new grade yet. We solve this with a snapshot:
  //
  //   - During 'grading': frozenSubmissionsRef tracks live data (so the banner
  //     updates naturally after the post-submit refetch, e.g. showing "Not graded"
  //     for the new submission).
  //   - When autograde completes ('completed'): we freeze the snapshot. From this
  //     point, GradeBanner reads stale data while AutoTestResults reads live data.
  //   - When typewriter finishes ('idle'): we unfreeze and GradeBanner sees the
  //     new grade.
  //
  // Sidebar freeze:
  //   The exercises list query invalidation was removed from useAwaitAutograde.
  //   It's only refetched in handleStaggerDone after the typewriter finishes.

  const [autogradeStatus, setAutogradeStatus] = useState<'idle' | 'grading' | 'completed' | 'revealing'>('idle')
  const frozenSubmissionsRef = useRef(submissions)
  const freezeRef = useRef(false)

  // While grading (before autograde result arrives), keep the snapshot in sync
  // with live data so the banner reflects the post-submit state naturally.
  if (autogradeStatus === 'grading' && !freezeRef.current) {
    frozenSubmissionsRef.current = submissions
  }

  const handleAutogradeStart = useCallback(() => {
    freezeRef.current = false
    setAutogradeStatus('grading')
  }, [])

  const handleSubmitted = useCallback(() => {
    if (autogradeStatus === 'grading') {
      // Freeze now — the next submissions refetch will contain the autograde
      // result, which GradeBanner must not show until the typewriter finishes.
      frozenSubmissionsRef.current = submissions
      freezeRef.current = true
      setAutogradeStatus('completed')
    }
  }, [autogradeStatus, submissions])

  const handleRevealReady = useCallback(() => {
    setAutogradeStatus('revealing')
  }, [])

  const handleStaggerDone = useCallback(() => {
    queryClient.refetchQueries({
      queryKey: ['student', 'courses', courseId, 'exercises'],
    })
    setTimeout(() => {
      freezeRef.current = false
      setAutogradeStatus('idle')
    }, 600)
  }, [queryClient, courseId])

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
        submissions={autogradeStatus === 'idle' ? submissions : frozenSubmissionsRef.current}
        threshold={exercise.threshold}
      />

      <SubmitTab
        ref={submitTabRef}
        courseId={courseId!}
        courseExerciseId={courseExerciseId!}
        exercise={exercise}
        initialSolution={latestSubmission?.solution}
        onSubmitted={handleSubmitted}
        onAutogradeStart={handleAutogradeStart}
      />

      {(autogradeStatus === 'grading' || autogradeStatus === 'completed') && (
        <Box sx={{ my: 2 }}>
          <AutogradeAnimation
            status={autogradeStatus}
            onRevealReady={handleRevealReady}
          />
        </Box>
      )}

      {latestSubmission?.auto_assessment && autogradeStatus !== 'grading' && autogradeStatus !== 'completed' && (
        <>
          <Divider sx={{ my: 3 }} />
          <AutoTestResults
            autoAssessment={latestSubmission.auto_assessment}
            staggerReveal={autogradeStatus === 'revealing'}
            onStaggerDone={handleStaggerDone}
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
        solutionFileName={exercise.solution_file_name}
        onRestore={(solution) => submitTabRef.current?.setSolution(solution)}
      />
    </>
  )

  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 1 }}>
        <IconButton
          onClick={() => navigate(`/courses/${courseId}/exercises`)}
          size="small"
        >
          <ArrowBackOutlined />
        </IconButton>
        <Typography variant="h5">{exercise.effective_title}</Typography>
        <Tooltip title={exercise.grader_type === 'AUTO' ? t('exercises.gradedAutomatically') : t('exercises.gradedByTeacher')}>
          {exercise.grader_type === 'AUTO'
            ? <RobotIcon sx={{ fontSize: 22, color: 'text.secondary', ml: 0.5 }} />
            : <FaceOutlined sx={{ fontSize: 22, color: 'text.secondary', ml: 0.5 }} />
          }
        </Tooltip>
      </Box>

      <Box sx={{ display: 'flex', gap: 1, mb: 2, flexWrap: 'wrap' }}>
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

function formatExceptionValue(
  ev: { value: string | null } | null,
  dateFnsLocale: Locale,
): string | null {
  if (!ev) return null
  if (ev.value === null) return null
  return format(new Date(ev.value), 'PPp', { locale: dateFnsLocale })
}

function visibilityLabel(
  ev: { value: string | null } | null,
  t: (key: string) => string,
  dateFnsLocale: Locale,
): string | null {
  if (!ev) return null
  if (ev.value === null) return t('exercises.hidden')
  const d = new Date(ev.value)
  if (d.getTime() <= Date.now()) return t('exercises.visible')
  return `${t('exercises.visibleFrom')}: ${format(d, 'PPp', { locale: dateFnsLocale })}`
}

const EXCEPTIONS_OPEN_KEY = 'exerciseExceptions.open'

function ExceptionsSummary({
  exceptionStudents,
  exceptionGroups,
  students,
  groups,
  t,
  dateFnsLocale,
}: {
  exceptionStudents: ExceptionStudent[]
  exceptionGroups: ExceptionGroup[]
  students: StudentParticipant[]
  groups: GroupResp[]
  t: (key: string) => string
  dateFnsLocale: Locale
}) {
  const [open, setOpen] = useState(() => {
    try {
      return localStorage.getItem(EXCEPTIONS_OPEN_KEY) === 'true'
    } catch {
      return false
    }
  })

  if (exceptionStudents.length === 0 && exceptionGroups.length === 0) return null

  function toggleOpen() {
    setOpen((prev) => {
      const next = !prev
      localStorage.setItem(EXCEPTIONS_OPEN_KEY, String(next))
      return next
    })
  }

  function renderRow(
    label: string,
    ex: { soft_deadline: { value: string | null } | null; hard_deadline: { value: string | null } | null; student_visible_from: { value: string | null } | null },
  ) {
    const parts: string[] = []
    const vis = visibilityLabel(ex.student_visible_from, t, dateFnsLocale)
    if (vis) parts.push(vis)
    const sd = formatExceptionValue(ex.soft_deadline, dateFnsLocale)
    if (sd) parts.push(`${t('exercises.deadline')}: ${sd}`)
    const hd = formatExceptionValue(ex.hard_deadline, dateFnsLocale)
    if (hd) parts.push(`${t('exercises.closingTime')}: ${hd}`)

    if (parts.length === 0) return null

    return (
      <Box key={label} sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap', alignItems: 'baseline' }}>
        <Typography variant="body2" sx={{ fontWeight: 500, fontSize: '0.8rem' }}>
          {label}:
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ fontSize: '0.8rem' }}>
          {parts.join(' · ')}
        </Typography>
      </Box>
    )
  }

  const rows = [
    ...exceptionStudents.map((ex) => {
      const s = students.find((s) => s.id === ex.student_id)
      const name = s ? `${s.given_name} ${s.family_name}` : ex.student_id
      return renderRow(name, ex)
    }),
    ...exceptionGroups.map((ex) => {
      const g = groups.find((g) => Number(g.id) === ex.group_id)
      return renderRow(g?.name ?? String(ex.group_id), ex)
    }),
  ].filter(Boolean)

  if (rows.length === 0) return null

  const count = exceptionStudents.length + exceptionGroups.length

  return (
    <Box sx={{ mb: 2 }}>
      <Box
        onClick={toggleOpen}
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 0.5,
          cursor: 'pointer',
          userSelect: 'none',
          py: 0.5,
        }}
      >
        <ExpandMoreOutlined
          sx={{
            fontSize: 18,
            color: 'text.secondary',
            transform: open ? 'rotate(0deg)' : 'rotate(-90deg)',
            transition: 'transform 0.2s',
          }}
        />
        <Typography variant="caption" color="text.secondary" sx={{ textTransform: 'uppercase', letterSpacing: '0.05em' }}>
          {t('exercises.exceptions')} ({count})
        </Typography>
      </Box>
      <Collapse in={open}>
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5, pl: 3.25, pt: 0.5 }}>
          {rows}
        </Box>
      </Collapse>
    </Box>
  )
}

function TeacherExerciseView() {
  const { courseId, courseExerciseId } = useParams<{
    courseId: string
    courseExerciseId: string
  }>()
  const navigate = useNavigate()
  const { t, i18n } = useTranslation()
  const dateFnsLocale = i18n.language === 'et' ? et : enGB
  const [settingsOpen, setSettingsOpen] = useState(false)

  const {
    data: exercise,
    isLoading,
    error,
  } = useTeacherExerciseDetails(courseId!, courseExerciseId!)
  const { data: participantsData } = useParticipants(courseId!)
  const { data: groupsData } = useCourseGroups(courseId!)

  usePageTitle(exercise ? (exercise.title_alias || exercise.title) : undefined)

  if (isLoading) return <CircularProgress />
  if (error)
    return <Alert severity="error">{t('general.somethingWentWrong')}</Alert>
  if (!exercise) return null

  const effectiveTitle = exercise.title_alias || exercise.title
  const students = participantsData?.students ?? []
  const groups = groupsData ?? []

  // Visibility chip
  const visibleFromDate = exercise.student_visible_from ? new Date(exercise.student_visible_from) : null
  const isScheduled = !exercise.student_visible && visibleFromDate && visibleFromDate.getTime() > Date.now()

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
    <RobotPlaceholder message={t('submission.noSubmissions')} />
  )

  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 1 }}>
        <IconButton
          onClick={() => navigate(`/courses/${courseId}/exercises`)}
          size="small"
        >
          <ArrowBackOutlined />
        </IconButton>
        <Typography variant="h5">{effectiveTitle}</Typography>
        <Tooltip title={exercise.grader_type === 'AUTO' ? t('exercises.gradedAutomatically') : t('exercises.gradedByTeacher')}>
          {exercise.grader_type === 'AUTO'
            ? <RobotIcon sx={{ fontSize: 22, color: 'text.secondary', ml: 0.5 }} />
            : <FaceOutlined sx={{ fontSize: 22, color: 'text.secondary', ml: 0.5 }} />
          }
        </Tooltip>
        <Box sx={{ flex: 1 }} />
        {exercise.has_lib_access && (
          <Tooltip title={t('exercises.openInLib')}>
            <IconButton
              onClick={() => navigate(`/library/${exercise.exercise_id}`)}
              size="small"
            >
              <LibraryBooksOutlined />
            </IconButton>
          </Tooltip>
        )}
        <Tooltip title={t('exercises.exerciseSettings')}>
          <IconButton size="small" onClick={() => setSettingsOpen(true)}>
            <SettingsOutlined />
          </IconButton>
        </Tooltip>
        <ExerciseSettingsDialog
          courseId={courseId!}
          courseExerciseId={courseExerciseId!}
          exercise={exercise}
          open={settingsOpen}
          onClose={() => setSettingsOpen(false)}
        />
      </Box>

      <Box sx={{ display: 'flex', gap: 1, mb: 2, flexWrap: 'wrap' }}>
        {!exercise.student_visible && !isScheduled && (
          <Chip
            label={t('exercises.hidden')}
            size="small"
            color="default"
          />
        )}
        {isScheduled && (
          <Chip
            label={`${t('exercises.visibleFrom')}: ${format(visibleFromDate, 'PPp', { locale: dateFnsLocale })}`}
            size="small"
            variant="outlined"
          />
        )}
        {exercise.soft_deadline && (
          <Chip
            label={`${t('exercises.deadline')}: ${format(new Date(exercise.soft_deadline), 'PPp', { locale: dateFnsLocale })}`}
            size="small"
            variant="outlined"
          />
        )}
        {exercise.hard_deadline && (
          <Chip
            label={`${t('exercises.closingTime')}: ${format(new Date(exercise.hard_deadline), 'PPp', { locale: dateFnsLocale })}`}
            size="small"
            variant="outlined"
          />
        )}
      </Box>

      <ExceptionsSummary
        exceptionStudents={exercise.exception_students ?? []}
        exceptionGroups={exercise.exception_groups ?? []}
        students={students}
        groups={groups}
        t={t}
        dateFnsLocale={dateFnsLocale}
      />

      <SplitPane storageKey="teacherExercise" left={leftPane} right={rightPane} />
    </>
  )
}
