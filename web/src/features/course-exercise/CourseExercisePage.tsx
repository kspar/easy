import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Typography,
  CircularProgress,
  Alert,
  Box,
  Chip,
  Collapse,
  Divider,
  IconButton,
  Tab,
  Tabs,
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
  CodeOutlined,
  SettingsOutlined,
} from '@mui/icons-material'
import { useParams, useNavigate, useSearchParams, Link as RouterLink } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { format, isPast, type Locale } from 'date-fns'
import { et, enGB } from 'date-fns/locale'
import { useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../../auth/useAuth.ts'
import {
  useDraft,
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
  TeacherExerciseDetails as TeacherExerciseDetailsType,
} from '../../api/types.ts'
import usePageTitle from '../../hooks/usePageTitle.ts'
import SolutionEditor, { type SolutionEditorHandle } from './SolutionEditor.tsx'
import AutoTestResults from './AutoTestResults.tsx'
import { isGraderFailed } from './okV3.ts'
import TeacherFeedback from './TeacherFeedback.tsx'
import PreviousSubmissions from './PreviousSubmissions.tsx'
import ExerciseSettingsDialog from './ExerciseSettingsDialog.tsx'
import EmbedDialog from '../library/EmbedDialog.tsx'
import { exerciseLink, spaLinkProps } from '../library/links.ts'
import { RobotIcon } from '../../components/icons.tsx'
import RenderedMarkdown from '../../components/markdown/RenderedMarkdown.tsx'
import AutogradeAnimation from './AutogradeAnimation.tsx'
import SubmissionsList from './SubmissionsList.tsx'
import StudentGradingView from './StudentGradingView.tsx'
import TeacherTestingTab from './TeacherTestingTab.tsx'
import AutoAssessTab from '../library/AutoAssessTab.tsx'
import { autoAssessDraftFrom } from '../library/exerciseDraft.ts'
import ErrorAlert from '../../components/ErrorAlert.tsx'

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

export default function CourseExercisePage() {
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
  const { t, i18n } = useTranslation()
  const dateFnsLocale = i18n.language === 'et' ? et : enGB

  const {
    data: exercise,
    isLoading,
    error,
  } = useExerciseDetails(courseId!, courseExerciseId!)

  const { data: submissions, isLoading: submissionsLoading } = useSubmissions(courseId!, courseExerciseId!)

  // The draft matters only for seeding the editor, and the editor must not initialise before it
  // arrives — a draft applied to an already-open editor would be silently ignored. `isSuccess`
  // is tracked separately: a draft read that *failed* means the server may hold a draft this
  // session never saw, and the editor must then not autosave over it.
  const {
    data: draft,
    isLoading: draftLoading,
    isSuccess: draftLoaded,
    // A cached draft is not good enough to seed from — another tab or device may have written a
    // newer one — so the editor waits for this mount's own refetch (useDraft sets
    // refetchOnMount: 'always'), not just for any data to exist.
    isFetchedAfterMount: draftFetchedThisMount,
  } = useDraft(courseId!, courseExerciseId!)

  const editorRef = useRef<SolutionEditorHandle>(null)
  const queryClient = useQueryClient()

  // Highlight a specific submission when clicking "submission #X" in TeacherFeedback
  const [highlightSubNumber, setHighlightSubNumber] = useState<number | undefined>()

  // --- Autograde animation state machine ---
  //
  // State: idle → grading → completed → revealing → idle
  //
  // The autograde animation plays a multi-phase visual (compile/test/analyze),
  // then a checkmark, then typewriter-reveals each test result one by one.
  //
  // The grade is NOT part of that sequence (audit X-007). GradeBanner used to read a frozen
  // snapshot of the submissions query until the typewriter finished, so a student learned their
  // grade about 4.3 seconds after the grader already knew it — a toll charged on the single most
  // frequent action in the product, on every attempt, forever. The banner now reads live data:
  // the grade appears the moment the result lands, and the animation goes on underneath it as
  // what it always was, the detail. The reveal keeps its drama; it just stops holding the answer
  // hostage.
  //
  // The sidebar goes with it. That list is the student's exercise nav, and its refetch used to wait
  // for the typewriter too — which was consistent while the banner also waited, and becomes a
  // contradiction the moment the banner does not: "100/100, passed" beside a sidebar entry still
  // showing the exercise as unsolved, on the same screen, for the length of the reveal. Both now
  // refresh when the result lands.

  const [autogradeStatus, setAutogradeStatus] = useState<'idle' | 'grading' | 'completed' | 'revealing'>('idle')

  const handleAutogradeStart = useCallback(() => {
    setAutogradeStatus('grading')
  }, [])

  const handleSubmitted = useCallback(() => {
    if (autogradeStatus !== 'grading') return
    setAutogradeStatus('completed')
    queryClient.refetchQueries({ queryKey: ['student', 'courses', courseId, 'exercises'] })
  }, [autogradeStatus, queryClient, courseId])

  const handleRevealReady = useCallback(() => {
    setAutogradeStatus('revealing')
  }, [])

  // The sidebar was already refreshed when the result landed, so all that is left here is leaving
  // the reveal state. The 600ms is the settle after the last test's status icon pops.
  const handleStaggerDone = useCallback(() => {
    setTimeout(() => setAutogradeStatus('idle'), 600)
  }, [])

  // When grading failed outright, there is nothing for the typewriter to reveal and
  // onStaggerDone would never fire, leaving the banner and sidebar frozen — so complete the
  // sequence by hand, cutting the celebration short as soon as the refetch says FAILED. Keyed on
  // the status, not on the assessment being absent: an absent assessment is also just "the
  // refetch has not landed yet" on a perfectly healthy run.
  const latestSubmission = submissions?.[0] ?? null
  useEffect(() => {
    if (autogradeStatus === 'idle' || autogradeStatus === 'grading') return
    if (latestSubmission != null && isGraderFailed(latestSubmission)) {
      handleStaggerDone()
    }
  }, [autogradeStatus, latestSubmission, handleStaggerDone])

  usePageTitle(exercise?.effective_title)

  if (isLoading) return <CircularProgress />
  if (error)
    return <ErrorAlert />
  if (!exercise) return null
  // The statement renders as soon as the details arrive; only the editor side waits for the
  // submissions and draft it seeds from, so a slow draft endpoint cannot blank the whole page.
  const editorLoading = submissionsLoading || draftLoading || !draftFetchedThisMount
  const animationPlaying = autogradeStatus === 'grading' || autogradeStatus === 'completed'

  // A draft wins over the latest submission when it is the more recent act and actually differs —
  // a draft autosaved moments before its own submission carries the same content and would only
  // mislabel the editor as holding unsubmitted work.
  const restoreDraft =
    draft != null &&
    (latestSubmission == null ||
      (new Date(draft.created_at) > new Date(latestSubmission.submission_time) &&
        draft.solution !== latestSubmission.solution))

  const leftPane = (
    <>
      {exercise.text_html && (
        <RenderedMarkdown html={exercise.text_html} />
      )}
      {exercise.instructions_html && (
        <RenderedMarkdown sx={{ mt: 2 }} html={exercise.instructions_html} />
      )}
    </>
  )

  const rightPane = editorLoading ? (
    <Box display="flex" justifyContent="center" py={8}>
      <CircularProgress />
    </Box>
  ) : (
    <>
      <GradeBanner submissions={submissions} threshold={exercise.threshold} />

      <SolutionEditor
        ref={editorRef}
        courseId={courseId!}
        courseExerciseId={courseExerciseId!}
        exercise={exercise}
        initialSolution={restoreDraft ? draft.solution : latestSubmission?.solution}
        initialIsDraft={restoreDraft}
        autosaveEnabled={draftLoaded}
        onSubmitted={handleSubmitted}
        onAutogradeStart={handleAutogradeStart}
      />

      {animationPlaying && (
        <Box sx={{ my: 2 }}>
          <AutogradeAnimation
            status={autogradeStatus}
            onRevealReady={handleRevealReady}
          />
        </Box>
      )}

      {latestSubmission?.auto_assessment && !animationPlaying && (
        <>
          <Divider sx={{ my: 3 }} />
          <AutoTestResults
            autoAssessment={latestSubmission.auto_assessment}
            staggerReveal={autogradeStatus === 'revealing'}
            onStaggerDone={handleStaggerDone}
          />
        </>
      )}

      {/* The one honest infrastructure-outage signal (audit X-026): without this the student
          gets pure silence — no results, no grade, no explanation. Shown even when an older
          assessment is still on screen (a failed retry leaves the stale one in place), and
          retired once a teacher has graded the submission by hand — the message asks them to
          contact a teacher who by then has already handled it. */}
      {latestSubmission != null &&
        isGraderFailed(latestSubmission) &&
        !animationPlaying &&
        latestSubmission.grade?.is_graded_directly !== true && (
          <Alert severity="warning" sx={{ mt: 2 }}>
            {t('submission.graderFailedStudent')}
          </Alert>
        )}

      <TeacherFeedback
        courseId={courseId!}
        courseExerciseId={courseExerciseId!}
        submissions={submissions}
        solutionFileName={exercise.solution_file_name}
        onSelectSubmissionNumber={setHighlightSubNumber}
      />

      <PreviousSubmissions
        courseId={courseId!}
        courseExerciseId={courseExerciseId!}
        solutionFileName={exercise.solution_file_name}
        onRestore={(solution) => editorRef.current?.setSolution(solution)}
        highlightSubmissionNumber={highlightSubNumber}
      />
    </>
  )

  const deadlinePassed = exercise.deadline != null && isPast(new Date(exercise.deadline))

  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 1 }}>
        <IconButton
          component={RouterLink}
          to={`/courses/${courseId}/exercises`}
          size="small"
          aria-label={t('general.back')}
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
          // A date on its own makes the student do the arithmetic (audit X-029): the page knew the
          // deadline had gone and said only when it was. The label says which it is; the colour
          // agrees but does not carry the meaning alone.
          <Chip
            label={`${deadlinePassed ? t('exercises.deadlinePassed') : t('exercises.deadline')}: ${format(new Date(exercise.deadline), 'PPp', { locale: dateFnsLocale })}`}
            size="small"
            variant="outlined"
            color={deadlinePassed && exercise.is_open ? 'warning' : 'default'}
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

function TeacherRightPane({
  courseId,
  courseExerciseId,
  exercise,
}: {
  courseId: string
  courseExerciseId: string
  exercise: TeacherExerciseDetailsType
}) {
  const { t } = useTranslation()
  const [searchParams, setSearchParams] = useSearchParams()
  const selectedStudentId = searchParams.get('student') || undefined

  // Tab state: 0=Students, 1=Testing, 2=Assessment
  const [tabIndex, setTabIndex] = useState(0)

  // No state behind it: the assessment tab only displays. Memoised because AutoAssessTab derives
  // its open file and eval type from the draft's identity.
  const autoAssessDraft = useMemo(() => autoAssessDraftFrom(exercise), [exercise])

  // When student param is set, auto-switch to Students tab
  useEffect(() => {
    if (selectedStudentId) {
      setTabIndex(0)
    }
  }, [selectedStudentId])

  const handleSelectStudent = useCallback((studentId: string) => {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      next.set('student', studentId)
      return next
    }, { replace: true })
  }, [setSearchParams])

  const handleBackToList = useCallback(() => {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      next.delete('student')
      return next
    }, { replace: true })
  }, [setSearchParams])

  return (
    <Box>
      <Tabs
        value={tabIndex}
        onChange={(_, v) => setTabIndex(v)}
        sx={{ mb: 2, minHeight: 36, '& .MuiTab-root': { minHeight: 36, py: 0.5, textTransform: 'none' } }}
      >
        <Tab label={t('submission.tabStudents')} />
        <Tab label={t('submission.tabTesting')} />
        <Tab label={t('submission.tabAssessment')} />
      </Tabs>

      {/* Students tab */}
      {tabIndex === 0 && (
        selectedStudentId ? (
          <StudentGradingView
            courseId={courseId}
            courseExerciseId={courseExerciseId}
            exercise={exercise}
            studentId={selectedStudentId}
            onBack={handleBackToList}
            onSelectStudent={handleSelectStudent}
          />
        ) : (
          <SubmissionsList
            courseId={courseId}
            courseExerciseId={courseExerciseId}
            onSelectStudent={handleSelectStudent}
          />
        )
      )}

      {/* Testing tab */}
      {tabIndex === 1 && (
        <TeacherTestingTab
          exerciseId={exercise.exercise_id}
          solutionFileName={exercise.solution_file_name}
          graderType={exercise.grader_type}
        />
      )}

      {/*
      Assessment tab. Was a placeholder promising that the auto-assessment configuration would
      appear here, next to two chips repeating what the header already says. The server had been
      sending the whole configuration all along — grading script, container, limits and assets, for
      every AUTO exercise — and it was TeacherExerciseDetails that failed to declare any of it, so
      there was no typed way to render what the placeholder was describing.

      The library page's own component, read-only. Read-only because the configuration belongs to
      the library exercise rather than to this course's use of it: editing it here would silently
      change grading for every other course that uses the same exercise. The header already links
      to the library for anyone with access, which is where that edit belongs.
      */}
      {tabIndex === 2 && (
        <Box>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 2 }}>
            {t('submission.assessmentReadOnly')}
          </Typography>
          <AutoAssessTab draft={autoAssessDraft} editing={false} onChange={() => {}} />
        </Box>
      )}
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
  const [embedOpen, setEmbedOpen] = useState(false)

  const {
    data: exercise,
    isLoading,
    error,
  } = useTeacherExerciseDetails(courseId!, courseExerciseId!)
  const { data: participantsData } = useParticipants(courseId!)
  const { data: groupsData } = useCourseGroups(courseId!)

  usePageTitle(exercise ? (exercise.title_alias || exercise.title) : undefined)

  // Captured once at mount rather than read inline below: calling Date.now() during render is
  // impure, so the same state could render differently on a re-render. Only decides whether
  // the "becomes visible at" chip shows, and a page isn't open long enough for the clock to
  // drift past the threshold. Has to sit above the early returns that follow.
  const [now] = useState(() => Date.now())

  if (isLoading) return <CircularProgress />
  if (error)
    return <ErrorAlert />
  if (!exercise) return null

  const effectiveTitle = exercise.title_alias || exercise.title
  const students = participantsData?.students ?? []
  const groups = groupsData ?? []

  // Visibility chip — `now` is captured at mount, see above.
  const visibleFromDate = exercise.student_visible_from ? new Date(exercise.student_visible_from) : null
  const isScheduled = !exercise.student_visible && visibleFromDate && visibleFromDate.getTime() > now

  const leftPane = (
    <>
      {exercise.text_html && (
        <RenderedMarkdown html={exercise.text_html} />
      )}
      {exercise.instructions_html && (
        <RenderedMarkdown sx={{ mt: 2 }} html={exercise.instructions_html} />
      )}
    </>
  )

  const rightPane = (
    <TeacherRightPane
      courseId={courseId!}
      courseExerciseId={courseExerciseId!}
      exercise={exercise}
    />
  )

  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 1 }}>
        <IconButton
          component={RouterLink}
          to={`/courses/${courseId}/exercises`}
          size="small"
          aria-label={t('general.back')}
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
          {/*
          Was `/library/<id>`, which matches no route: the library exercise lives at
          `/library/exercise/<id>/<slug>`, so this landed on NotFoundPage. Built from the
          shared helper now, so it cannot drift from the route table again — and as a real
          anchor, so ctrl/cmd-click opens it in a tab like every other library link.
          */}
        {exercise.has_lib_access && (
          <Tooltip title={t('exercises.openInLib')}>
            <IconButton
              component="a"
              {...spaLinkProps(exerciseLink(exercise.exercise_id, effectiveTitle), navigate)}
              size="small"
            >
              <LibraryBooksOutlined />
            </IconButton>
          </Tooltip>
        )}
        {/*
          Same gate as the library shortcut above: the embed dialog reads the *library* exercise
          (the course exercise response carries neither the embed flag nor the starting code), so
          without library access there is nothing it could show or change.
        */}
        {exercise.has_lib_access && (
          <Tooltip title={t('library.embedding')}>
            <IconButton size="small" onClick={() => setEmbedOpen(true)}>
              <CodeOutlined />
            </IconButton>
          </Tooltip>
        )}
        <Tooltip title={t('exercises.exerciseSettings')}>
          <IconButton size="small" onClick={() => setSettingsOpen(true)}>
            <SettingsOutlined />
          </IconButton>
        </Tooltip>
        <EmbedDialog
          exerciseId={exercise.exercise_id}
          currentCourseId={courseId}
          currentCourseExerciseId={courseExerciseId}
          open={embedOpen}
          onClose={() => setEmbedOpen(false)}
        />
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
