import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
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
  LastPageOutlined,
  VerticalSplitOutlined,
  LibraryBooksOutlined,
  CodeOutlined,
  SettingsOutlined,
} from '@mui/icons-material'
import { useParams, useNavigate, useSearchParams, Link as RouterLink } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { isPast, type Locale } from 'date-fns'
import { formatDateTime, useDateLocale } from '../../i18n/dateLocale.ts'
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
import useRerunAllTests from './useRerunAllTests.ts'
import StudentGradingView from './StudentGradingView.tsx'
import TeacherTestingTab from './TeacherTestingTab.tsx'
import AutoAssessTab from '../library/AutoAssessTab.tsx'
import { autoAssessDraftFrom } from '../library/exerciseDraft.ts'
import ErrorAlert from '../../components/ErrorAlert.tsx'

/**
 * The student's current grade, as a chip in the page header beside the deadline.
 *
 * It was a full-width Alert above the editor, which cost about 70px of the pane — the one place on
 * this page where vertical space is scarce, now that the editor is bounded by the window rather
 * than by the file (EZ-1835). It also said what the results section's own header already says.
 *
 * A chip in the header keeps the grade permanently on screen (the header sits above the frame and
 * never scrolls) and takes no space from the editor at all, while staying in the vocabulary the
 * row already uses for the deadline: same size, same colour rule, colour never the only carrier.
 */
function GradeChip({
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
      <Chip
        icon={<CircleOutlined />}
        label={`${t('submission.currentGrade')}: ${t('exercises.notGraded')}`}
        size="small"
        variant="outlined"
      />
    )
  }

  const grade = latest.grade.grade
  const passed = grade >= threshold
  const indirect = !latest.grade.is_graded_directly

  return (
    <Chip
      icon={passed ? <CheckCircle /> : <CircleOutlined />}
      label={
        `${t('submission.currentGrade')}: ${grade} / 100` +
        // Spelled out rather than left to the hover it used to be: an icon explained only by a
        // tooltip says nothing on a phone, and nothing to a screen reader either.
        (indirect ? ` · ${t('submission.gradePreviousSubmission')}` : '')
      }
      size="small"
      variant="outlined"
      // The colour goes on the icon, not on the chip. `color="warning"` would paint the label
      // #f9a825, which is under 2:1 on this background — and a grade below the threshold is the
      // state a student sees most of the time. The number stays at full contrast and the colour
      // stays a second cue on top of a label that already says everything.
      sx={{ '& .MuiChip-icon': { color: passed ? 'success.main' : 'warning.main' } }}
    />
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

/**
 * The space between the bottom of `el` and the bottom of the document — the page container's own
 * padding, in practice — read off the ancestors' box properties rather than off the rendered
 * geometry.
 *
 * Geometry cannot answer this once the frame is in place. The first attempt asked
 * `scrollHeight - (top + height)`, which is right while the content overflows and badly wrong when
 * it does not: `scrollHeight` never drops below the viewport, so an empty page reports its unused
 * space as padding, the frame shrinks to make room for it, which leaves more unused space. It
 * settled at a frame 56px tall. Padding and margins are the same whatever the content does.
 *
 * Assumes the frame is the last thing on the page, which it is in both views here.
 */
function spaceBelow(el: HTMLElement): number {
  let total = 0
  for (let node: HTMLElement | null = el.parentElement; node && node !== document.documentElement; node = node.parentElement) {
    const style = getComputedStyle(node)
    total += parseFloat(style.paddingBottom) + parseFloat(style.borderBottomWidth) + parseFloat(style.marginBottom)
  }
  return total
}

/**
 * The height this page may occupy, measured from where it actually starts rather than assumed.
 *
 * `calc(100vh - 48px)` was the old guess, and it was wrong by the height of everything between the
 * app bar and the panes — the title row, the deadline chips, the exceptions summary — so the
 * statement pane ran past the bottom of the window. Measuring also survives what moves this page's
 * top edge at runtime: a system message or update banner appearing above the app bar, and the chip
 * row wrapping onto a second line when the window narrows. The first changes the page's height,
 * which is why `document.body` is watched; the second does not, which is why the header is too.
 *
 * `frameSx` is `undefined` while disabled (mobile), which leaves the page in ordinary document flow.
 */
function useFrameHeight(enabled: boolean) {
  // Callback refs, held in state rather than in a ref object: the frame only exists once the
  // exercise has loaded, and a `useRef` would still be null on the mount this effect runs in —
  // leaving the page unframed for the rest of its life with nothing to re-trigger the measurement.
  const [frameEl, setFrameEl] = useState<HTMLDivElement | null>(null)
  const [headerEl, setHeaderEl] = useState<HTMLDivElement | null>(null)
  const [top, setTop] = useState<number | null>(null)

  useLayoutEffect(() => {
    // No `setTop(null)` here: whether the frame applies is read off `enabled` below, so the
    // disabled path has nothing to write, and a stale measurement is re-taken on the way back in.
    if (!enabled || !frameEl) return
    const measure = () => {
      // Plus the scroll offset: the rect is viewport-relative, and the first measurement is taken
      // while the page is still an ordinary scrolling document.
      const above = frameEl.getBoundingClientRect().top + window.scrollY
      setTop(Math.round(above + spaceBelow(frameEl)))
    }
    measure()

    const observer = new ResizeObserver(measure)
    observer.observe(document.body)
    if (headerEl) observer.observe(headerEl)
    window.addEventListener('resize', measure)
    return () => {
      observer.disconnect()
      window.removeEventListener('resize', measure)
    }
  }, [enabled, frameEl, headerEl])

  return {
    frameRef: setFrameEl,
    headerRef: setHeaderEl,
    frameSx: !enabled || top == null
      ? undefined
      : { height: `calc(100dvh - ${top}px)`, minHeight: 0, display: 'flex', flexDirection: 'column' as const },
  }
}

function SplitPane({
  left,
  right,
  storageKey,
  fill = false,
}: {
  left: React.ReactNode
  right: React.ReactNode
  storageKey: string
  /**
   * Fill the height of the parent rather than growing with the content. Both panes then scroll
   * inside themselves and the window does not scroll at all — see `useFrameHeight`. The right
   * pane is handed its height and lays itself out; unlike the left one it is not given a
   * scrollbar, because what lives there (the editor and its results, the teacher's tabs) has to
   * decide for itself which of its parts scrolls.
   */
  fill?: boolean
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
      sx={{ display: 'flex', minHeight: 0, gap: 0, ...(fill && { flex: '1 1 auto', height: '100%' }) }}
    >
      {/* Left pane */}
      {showLeft && (
        <Box
          sx={{
            ...(collapsed === 'right'
              ? { flex: 1, minWidth: 0 }
              : { width: `${leftPct}%`, flexShrink: 0 }),
            overflow: 'auto',
            pr: collapsed !== 'none' ? 0 : 2,
            ...(fill
              ? { height: '100%', minHeight: 0, pb: 2 }
              : {
                  position: 'sticky',
                  top: HEADER_HEIGHT,
                  maxHeight: `calc(100vh - ${HEADER_HEIGHT}px)`,
                  alignSelf: 'flex-start',
                }),
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

        {/* Collapse/restore buttons — sticky at top, except in a frame that never scrolls */}
        <Box
          sx={{
            position: fill ? 'static' : 'sticky',
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
            ...(fill && { height: '100%', minHeight: 0, display: 'flex', flexDirection: 'column' }),
          }}
        >
          {right}
        </Box>
      )}
    </Box>
  )
}

const DEFAULT_TOP_PCT = 55
const MIN_TOP_PCT = 25
const MAX_TOP_PCT = 80
/** Below this the top section is a grade banner and a submit button with no editor between them. */
const WORKSPACE_MIN_TOP = 220

/**
 * The right pane of a framed exercise page: the editor and its submit button above, everything a
 * submission produces below, and a divider between them.
 *
 * The percentage is a **ceiling on the lower section, not a fixed division**. An exercise nobody
 * has submitted to yet has nothing down there — no results, no feedback, no history — and holding
 * 45% of the pane empty would be taking it from the only part in use. So the lower section takes
 * the height of its own content and the editor keeps the rest, until that content would pass the
 * ceiling; from there the ceiling holds and the section scrolls. The same rule gives the autograde
 * animation exactly the room it needs on a first submission and no more.
 *
 * The handle therefore appears when there is anything at all below it, and not before: on an
 * exercise nobody has submitted to there is nothing down there to divide from.
 */
function Workspace({
  storageKey,
  top,
  bottom,
  bottomRef,
}: {
  storageKey: string
  top: React.ReactNode
  bottom: React.ReactNode
  /** The scrolling lower section, so a submission can send it back to the top. */
  bottomRef?: React.RefObject<HTMLDivElement | null>
}) {
  const { t } = useTranslation()
  const containerRef = useRef<HTMLDivElement>(null)
  const fallbackRef = useRef<HTMLDivElement>(null)
  const scrollRef = bottomRef ?? fallbackRef
  const contentRef = useRef<HTMLDivElement>(null)

  const pctKey = `splitPane.${storageKey}.topPct`
  const [topPct, setTopPctRaw] = useState(() => readStored<number>(pctKey, DEFAULT_TOP_PCT))
  const setTopPct = useCallback((pct: number) => {
    const clamped = Math.min(MAX_TOP_PCT, Math.max(MIN_TOP_PCT, pct))
    setTopPctRaw(clamped)
    try { localStorage.setItem(pctKey, JSON.stringify(clamped)) } catch { /* ignore */ }
  }, [pctKey])

  // There is a divider when there is something below to divide from, and not before: on an
  // exercise nobody has submitted to, the lower section is empty and a rule across the pane would
  // be a line about nothing.
  //
  // Deliberately *not* keyed on whether the ceiling currently binds, which was the first rule
  // here. It made the handle disappear exactly when a drag downwards had given the lower section
  // all the room its content needed — leaving no way to drag back up, and the split stuck where
  // the last gesture left it.
  const [hasContentBelow, setHasContentBelow] = useState(false)
  useEffect(() => {
    const content = contentRef.current
    if (!content) return
    const check = () => setHasContentBelow(content.getBoundingClientRect().height > 4)
    check()
    const observer = new ResizeObserver(check)
    observer.observe(content)
    return () => observer.disconnect()
  }, [])

  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    if (!containerRef.current) return
    e.preventDefault()

    // Where in the handle the grab happened. Without this the first mouse move puts the divider
    // under the cursor instead of moving it by however far the cursor went, so the split jumps the
    // moment you take hold of it — the jank you can feel but not quite see.
    //
    // Measured from the handle's *bottom* edge, not its middle. What the percentage positions is
    // the top of the lower section, and the handle sits above that in the flex column, so
    // centring the maths on the handle left a residual jump of exactly half its height.
    const grabOffset = e.clientY - e.currentTarget.getBoundingClientRect().bottom

    const onMouseMove = (ev: MouseEvent) => {
      const rect = containerRef.current?.getBoundingClientRect()
      if (!rect) return
      setTopPct(((ev.clientY - grabOffset - rect.top) / rect.height) * 100)
    }
    const onMouseUp = () => {
      document.removeEventListener('mousemove', onMouseMove)
      document.removeEventListener('mouseup', onMouseUp)
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
    }

    document.body.style.cursor = 'row-resize'
    document.body.style.userSelect = 'none'
    document.addEventListener('mousemove', onMouseMove)
    document.addEventListener('mouseup', onMouseUp)
  }, [setTopPct])

  // The vertical splitter has been mouse-only since it was written; this one is not. A separator
  // that only answers a drag is a layout half the class cannot adjust.
  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    const step = e.key === 'ArrowUp' ? -4 : e.key === 'ArrowDown' ? 4 : 0
    if (step === 0) return
    e.preventDefault()
    setTopPct(topPct + step)
  }, [setTopPct, topPct])

  return (
    <Box ref={containerRef} sx={{ flex: '1 1 auto', minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <Box sx={{ flex: '1 1 auto', minHeight: 0, display: 'flex', flexDirection: 'column' }}>
        {top}
      </Box>

      {hasContentBelow && (
        <Box
          role="separator"
          aria-orientation="horizontal"
          aria-valuenow={Math.round(topPct)}
          aria-valuemin={MIN_TOP_PCT}
          aria-valuemax={MAX_TOP_PCT}
          aria-label={t('nav.resizeSections')}
          tabIndex={0}
          onMouseDown={handleMouseDown}
          onKeyDown={handleKeyDown}
          sx={{
            flexShrink: 0,
            height: 13,
            position: 'relative',
            cursor: 'row-resize',
            '&::after': {
              content: '""',
              position: 'absolute',
              left: 0,
              right: 0,
              top: 6,
              height: '1px',
              bgcolor: 'divider',
              transition: 'background-color 0.2s',
            },
            '&:hover::after, &:focus-visible::after': { bgcolor: 'action.disabled' },
          }}
        />
      )}

      {/*
        `0 0 auto`, not `0 1 auto`: with both sections shrinkable, flexbox divides the shortfall
        between them in proportion to their content, and the taller lower section lost the most —
        eight collapsed test rows were squeezed to a 50px sliver under the button. Not shrinking,
        it is exactly its content's height until the ceiling, and the editor above absorbs the
        rest.
      */}
      <Box
        ref={scrollRef}
        sx={{
          flex: '0 0 auto',
          minHeight: 0,
          overflowY: 'auto',
          // The percentage, or whatever leaves the editor a usable 220px — whichever is smaller.
          // Without the second term a quarter-height top on a short window is banner plus submit
          // row and no editor at all, and past that the row itself starts going over the edge.
          maxHeight: `min(${100 - topPct}%, calc(100% - ${WORKSPACE_MIN_TOP}px))`,
          pt: hasContentBelow ? 1 : 0,
        }}
      >
        <Box ref={contentRef}>{bottom}</Box>
      </Box>
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
  const { t } = useTranslation()
  const dateFnsLocale = useDateLocale()

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

  // Desktop only. On a phone the panes are stacked and the page scrolls, which is the right
  // answer there: a viewport-height frame and a virtual keyboard fight over the same space.
  const theme = useTheme()
  const isMobile = useMediaQuery(theme.breakpoints.down('md'))
  const { frameRef, headerRef, frameSx } = useFrameHeight(!isMobile)
  const framed = frameSx != null

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

  // The lower section of the framed workspace. Grading replaces what is in it, and a section
  // scrolled halfway down someone else's feedback would open the animation out of view.
  const resultsRef = useRef<HTMLDivElement>(null)

  const handleAutogradeStart = useCallback(() => {
    setAutogradeStatus('grading')
    resultsRef.current?.scrollTo({ top: 0 })
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
    return <ErrorAlert error={error} />
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

  const editorSection = (
    <>
      <SolutionEditor
        ref={editorRef}
        courseId={courseId!}
        courseExerciseId={courseExerciseId!}
        exercise={exercise}
        initialSolution={restoreDraft ? draft.solution : latestSubmission?.solution}
        initialIsDraft={restoreDraft}
        autosaveEnabled={draftLoaded}
        fill={framed}
        onSubmitted={handleSubmitted}
        onAutogradeStart={handleAutogradeStart}
      />
    </>
  )

  const resultsSection = (
    <>
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
          {/* Framed, the workspace's own divider already separates the two sections, and a
              second rule under it would be a line about nothing. */}
          {!framed && <Divider sx={{ my: 3 }} />}
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

  const rightPane = editorLoading ? (
    <Box display="flex" justifyContent="center" py={8}>
      <CircularProgress />
    </Box>
  ) : framed ? (
    <Workspace
      storageKey="studentExercise"
      top={editorSection}
      bottom={resultsSection}
      bottomRef={resultsRef}
    />
  ) : (
    <>
      {editorSection}
      {resultsSection}
    </>
  )

  const deadlinePassed = exercise.deadline != null && isPast(new Date(exercise.deadline))

  return (
    <>
      {/* The header is measured, not assumed: the chip row wraps onto a second line when the
          window narrows, and the frame below has to lose exactly that much height. */}
      <Box ref={headerRef}>
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

        <Box sx={{ display: 'flex', gap: 1, mb: 2, flexWrap: 'wrap', alignItems: 'center' }}>
          {/* First in the row, because after a submission it is the thing the student came back
              for — and this row is above the frame, so it stays on screen while they work. */}
          <GradeChip submissions={submissions} threshold={exercise.threshold} />
          {exercise.deadline && (
            // A date on its own makes the student do the arithmetic (audit X-029): the page knew the
            // deadline had gone and said only when it was. The label says which it is; the colour
            // agrees but does not carry the meaning alone.
            <Chip
              label={`${deadlinePassed ? t('exercises.deadlinePassed') : t('exercises.deadline')}: ${formatDateTime(new Date(exercise.deadline), dateFnsLocale)}`}
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
      </Box>

      <Box ref={frameRef} sx={frameSx}>
        <SplitPane storageKey="studentExercise" left={leftPane} right={rightPane} fill={framed} />
      </Box>
    </>
  )
}

function formatExceptionValue(
  ev: { value: string | null } | null,
  dateFnsLocale: Locale,
): string | null {
  if (!ev) return null
  if (ev.value === null) return null
  return formatDateTime(new Date(ev.value), dateFnsLocale)
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
  return `${t('exercises.visibleFrom')}: ${formatDateTime(d, dateFnsLocale)}`
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
  framed = false,
}: {
  courseId: string
  courseExerciseId: string
  exercise: TeacherExerciseDetailsType
  /** In a framed pane the tab strip stays put and the tab's content scrolls under it. */
  framed?: boolean
}) {
  const { t } = useTranslation()
  const [searchParams, setSearchParams] = useSearchParams()
  const selectedStudentId = searchParams.get('student') || undefined

  // Tab state: 0=Students, 1=Testing, 2=Assessment
  const [tabIndex, setTabIndex] = useState(0)

  // No state behind it: the assessment tab only displays. Memoised because AutoAssessTab derives
  // its open file and eval type from the draft's identity.
  const autoAssessDraft = useMemo(() => autoAssessDraftFrom(exercise), [exercise])

  // Owned here rather than inside SubmissionsList, which is the point of it being here: selecting a
  // student or changing tab unmounts that list, and a re-run of a whole course takes long enough
  // that a teacher will do one of those while it runs. Held one level up, the run survives both.
  const rerun = useRerunAllTests(courseId, courseExerciseId)

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
    <Box sx={framed ? { flex: '1 1 auto', minHeight: 0, display: 'flex', flexDirection: 'column' } : undefined}>
      <Tabs
        value={tabIndex}
        onChange={(_, v) => setTabIndex(v)}
        sx={{
          mb: 2,
          minHeight: 36,
          '& .MuiTab-root': { minHeight: 36, py: 0.5, textTransform: 'none' },
          ...(framed && { flexShrink: 0 }),
        }}
      >
        <Tab label={t('submission.tabStudents')} />
        <Tab label={t('submission.tabTesting')} />
        <Tab label={t('submission.tabAssessment')} />
      </Tabs>

      <Box sx={framed ? { flex: '1 1 auto', minHeight: 0, overflowY: 'auto', pb: 2 } : undefined}>
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
            graderType={exercise.grader_type}
            rerun={rerun}
            onSelectStudent={handleSelectStudent}
          />
        )
      )}

      {/* Testing tab */}
      {tabIndex === 1 && (
        <TeacherTestingTab
          exerciseId={exercise.exercise_id}
          courseId={courseId}
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
    </Box>
  )
}

function TeacherExerciseView() {
  const { courseId, courseExerciseId } = useParams<{
    courseId: string
    courseExerciseId: string
  }>()
  const navigate = useNavigate()
  const { t } = useTranslation()
  const dateFnsLocale = useDateLocale()
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [embedOpen, setEmbedOpen] = useState(false)

  // The same frame as the student's page. A teacher reading a 1500-line submission in the grading
  // view was scrolling exactly as far as the student who wrote it.
  const theme = useTheme()
  const isMobile = useMediaQuery(theme.breakpoints.down('md'))
  const { frameRef, headerRef, frameSx } = useFrameHeight(!isMobile)
  const framed = frameSx != null

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
    return <ErrorAlert error={error} />
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
      framed={framed}
    />
  )

  return (
    <>
      <Box ref={headerRef}>
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
            label={`${t('exercises.visibleFrom')}: ${formatDateTime(visibleFromDate, dateFnsLocale)}`}
            size="small"
            variant="outlined"
          />
        )}
        {exercise.soft_deadline && (
          <Chip
            label={`${t('exercises.deadline')}: ${formatDateTime(new Date(exercise.soft_deadline), dateFnsLocale)}`}
            size="small"
            variant="outlined"
          />
        )}
        {exercise.hard_deadline && (
          <Chip
            label={`${t('exercises.closingTime')}: ${formatDateTime(new Date(exercise.hard_deadline), dateFnsLocale)}`}
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
      </Box>

      <Box ref={frameRef} sx={frameSx}>
        <SplitPane storageKey="teacherExercise" left={leftPane} right={rightPane} fill={framed} />
      </Box>
    </>
  )
}
