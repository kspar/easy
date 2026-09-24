import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import {
  Alert,
  Box,
  Button,
  ButtonBase,
  Chip,
  CircularProgress,
  Divider,
  IconButton,
  InputAdornment,
  Popover,
  Skeleton,
  Snackbar,
  TextField,
  Tooltip,
  Typography,
  keyframes,
} from '@mui/material'
import {
  ArrowBackOutlined,
  ArrowDropDownOutlined,
  ChevronLeftOutlined,
  ChevronRightOutlined,
  FlagOutlined,
  FlagRounded,
  RefreshOutlined,
  SearchOutlined,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import {
  exportSubmissions,
  useCreateInlineComment,
  useDeleteInlineComment,
  useMarkSubmissionsFlagged,
  useMarkSubmissionsSeen,
  useRetryAutoassess,
  useTeacherStudentInlineComments,
  useTeacherStudentSubmissions,
  useTeacherSubmissionDetails,
  useTeacherSubmissionSummaries,
  useTeacherStudentActivities,
  useUpdateInlineComment,
} from '../../api/exercises.ts'
import { useAuth } from '../../auth/useAuth.ts'
import useSavedGroup from '../../hooks/useSavedGroup.ts'
import AutoTestResults from './AutoTestResults.tsx'
import { isGraderFailed } from './okV3.ts'
import ActivityFeed from './ActivityFeed.tsx'
import SubmissionSelector from './SubmissionSelector.tsx'
import AnnotatedCodeEditor, { type NewCommentData } from './AnnotatedCodeEditor.tsx'
import type { TeacherExerciseDetails, SubmissionRow } from '../../api/types.ts'
import SafeText from '../../components/SafeText.tsx'
import { saveResponseAsFile } from '../../components/downloadTextFile.ts'
import UnseenIndicator, { SeenIndicator } from './UnseenIndicator.tsx'

/**
 * The two marks in the grading header move when they change, because both of them can change
 * without being pressed: `seen` marks itself the moment the submission opens, and `flagged` is
 * shared, so a colleague's flag arrives on the next refetch. A state that appears fully formed
 * looks like it was always that way.
 *
 * Small on purpose — under a fifth of a second, no bounce past the resting size on the quiet one.
 * These sit next to a grade field a teacher uses a hundred times an hour.
 */
const settle = keyframes`
  from { opacity: 0; transform: scale(0.72) }
  to { opacity: 1; transform: none }
`

/**
 * The flag lights up rather than jumping: a bloom in its own colour that swells and fades out.
 *
 * `currentColor` keeps the glow the icon's warning colour in both themes without naming a hex here,
 * and a drop-shadow follows the flag's outline instead of boxing it, which a `box-shadow` on the
 * wrapper would not. Nothing moves — the row of controls beside the grade field stays where the
 * teacher's eye left it.
 */
const glow = keyframes`
  0% { opacity: 0.55; filter: drop-shadow(0 0 0 currentColor) }
  45% { opacity: 1; filter: drop-shadow(0 0 5px currentColor) }
  100% { opacity: 1; filter: drop-shadow(0 0 0 currentColor) }
`

const markTransition = {
  display: 'flex',
  animation: `${settle} 160ms cubic-bezier(0.2, 0.7, 0.3, 1)`,
  '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
}

const flagRaise = {
  display: 'flex',
  '& > svg': { animation: `${glow} 450ms ease-out` },
  '@media (prefers-reduced-motion: reduce)': { '& > svg': { animation: 'none' } },
}

/**
 * The student's code, and beside it what the teacher does about it — EZ-1917.
 *
 * In one column the test results and the grade form sat under the code, which at 1920×1080 put the
 * bottom of that column at 1769px in a 1080px window: read the code, scroll to the grade field,
 * type, next student, scroll back up, thirty-five times. Given the width, the two go side by side
 * and the grade form is in view for as long as the code is.
 *
 * The threshold is about what is left for the code: below ~1300px a side column would hand back the
 * clipping that going wide (EZ-1915) had just removed. And it is this view's width that is asked
 * about, not the window's — it is what the task text and the divider leave. On a 1920 monitor that
 * means the row appears once the task text is collapsed, which is how student twelve of thirty-five
 * gets graded anyway.
 *
 * Measured rather than asked of CSS. A container query is the obvious tool and the wrong one here:
 * `container-type` makes the element the containing block for `position: fixed` descendants in
 * Safari and Firefox, and the three Snackbars under this view are exactly that — "comment saved"
 * would anchor to the bottom of a 1700px pane instead of the window.
 */
const GRADING_ROW_MIN_WIDTH = 1300

function useIsAtLeast(minWidth: number) {
  const [el, setEl] = useState<HTMLElement | null>(null)
  const [atLeast, setAtLeast] = useState(false)
  useLayoutEffect(() => {
    if (!el) return
    const measure = () => setAtLeast(el.getBoundingClientRect().width >= minWidth)
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(el)
    return () => observer.disconnect()
  }, [el, minWidth])
  return [setEl, atLeast] as const
}

export default function StudentGradingView({
  courseId,
  courseExerciseId,
  exercise,
  studentId,
  onBack,
  onSelectStudent,
}: {
  courseId: string
  courseExerciseId: string
  exercise: TeacherExerciseDetails
  studentId: string
  onBack: () => void
  onSelectStudent: (studentId: string) => void
}) {
  const { t } = useTranslation()
  const [rootRef, sideBySide] = useIsAtLeast(GRADING_ROW_MIN_WIDTH)
  const { username } = useAuth()
  const [filterGroup] = useSavedGroup(courseId)

  // Re-running auto-assessment. Only offered on AUTO exercises — core rejects it otherwise — and
  // only when there is already an assessment to replace, since the button is a fix for a bad one
  // rather than a way to grade something that was never graded.
  const retryAutoassess = useRetryAutoassess(courseId, courseExerciseId)
  const [retryDone, setRetryDone] = useState(false)

  // Fetch student list for prev/next navigation
  const { data: allStudents } = useTeacherSubmissionSummaries(
    courseId,
    courseExerciseId,
    filterGroup || undefined,
  )

  // Sort the same way as SubmissionsList default (by name)
  const sortedStudents = useMemo(() => {
    if (!allStudents) return []
    return [...allStudents].sort((a, b) => {
      const last = a.family_name.localeCompare(b.family_name)
      return last !== 0 ? last : a.given_name.localeCompare(b.given_name)
    })
  }, [allStudents])

  const currentIndex = sortedStudents.findIndex((s) => s.student_id === studentId)
  const prevStudent = currentIndex > 0 ? sortedStudents[currentIndex - 1] : null
  const nextStudent = currentIndex < sortedStudents.length - 1 ? sortedStudents[currentIndex + 1] : null
  const currentRow: SubmissionRow | undefined = sortedStudents[currentIndex]

  // Fetch all submission summaries for this student (for the dropdown)
  const { data: submissions, isLoading: subsLoading } = useTeacherStudentSubmissions(
    courseId,
    courseExerciseId,
    studentId,
  )

  // Selected submission ID (default: latest = first in list)
  const [selectedSubId, setSelectedSubId] = useState<string | undefined>(undefined)

  // Reset selected submission when student changes
  useEffect(() => {
    setSelectedSubId(undefined)
  }, [studentId])

  const latestSub = submissions?.[0]
  const activeSubSummary = selectedSubId
    ? submissions?.find((s) => s.id === selectedSubId) ?? latestSub
    : latestSub
  /**
   * On the newest of this student's submissions — and there has to be one.
   *
   * Without the null check this was `undefined === undefined` for a student who has submitted
   * nothing, which is how the seen and flag buttons came to sit in the header of a page with no
   * submission on it. Both toggles bail on a missing id, so they were two controls that did
   * nothing at all. The other two readers of this are inside `subDetail &&` blocks and cannot
   * reach the empty case.
   */
  const isViewingLatest = latestSub != null && activeSubSummary?.id === latestSub.id

  // Fetch full detail (with solution) for the selected submission
  const { data: subDetail, isLoading: detailLoading } = useTeacherSubmissionDetails(
    courseId,
    courseExerciseId,
    activeSubSummary?.id,
  )

  // Grade state
  const [grade, setGrade] = useState('')

  // The grade value as loaded from the server (used to skip re-posting unchanged grades)
  const initialGrade = useMemo(() => {
    if (subDetail?.grade) return String(subDetail.grade.grade)
    if (subDetail?.auto_assessment) return String(subDetail.auto_assessment.grade)
    return ''
  }, [subDetail?.grade?.grade, subDetail?.auto_assessment?.grade])

  // Grade origin info for display (autograde vs teacher, direct vs inherited)
  const gradeInfo = useMemo(() => {
    if (subDetail?.grade) return {
      isAutograde: subDetail.grade.is_autograde,
      isGradedDirectly: subDetail.grade.is_graded_directly,
    }
    if (subDetail?.auto_assessment) return { isAutograde: true, isGradedDirectly: true }
    return null
  }, [subDetail?.grade, subDetail?.auto_assessment])

  // Initialize grade when submission detail changes
  useEffect(() => {
    setGrade(initialGrade)
  }, [initialGrade])

  // Fetch activities for this student
  const { data: activities } = useTeacherStudentActivities(courseId, courseExerciseId, studentId)

  // Fetch ALL inline comments for this student (one query, not per-submission)
  const { data: allInlineComments } = useTeacherStudentInlineComments(courseId, courseExerciseId, studentId)

  // Inline comment mutations
  const createComment = useCreateInlineComment(courseId, courseExerciseId)
  const updateComment = useUpdateInlineComment(courseId, courseExerciseId)
  const deleteComment = useDeleteInlineComment(courseId, courseExerciseId)

  // Filter inline comments for the current submission
  const currentSubComments = useMemo(() =>
    allInlineComments?.filter((c) => c.submission_id === subDetail?.id) ?? [],
    [allInlineComments, subDetail?.id],
  )

  const handleCreateComment = useCallback(async (data: NewCommentData) => {
    if (!subDetail) return
    await createComment.mutateAsync({ submissionId: subDetail.id, ...data })
  }, [subDetail, createComment])

  const handleUpdateComment = useCallback(async (commentId: string, data: NewCommentData) => {
    if (!subDetail) return
    await updateComment.mutateAsync({ submissionId: subDetail.id, commentId, ...data })
  }, [subDetail, updateComment])

  const handleDeleteComment = useCallback(async (commentId: string) => {
    if (!subDetail) return
    await deleteComment.mutateAsync({ submissionId: subDetail.id, commentId })
  }, [subDetail, deleteComment])

  /**
   * Core names the exported file, not the browser.
   *
   * It has the student's family and given name and the submission id; this view has a username and
   * a submission number. The old interface's teacher-side save went through the same endpoint for
   * the same reason. The endpoint also takes a list and answers with a zip, which is where a "save
   * all of these" belongs when somebody asks for it.
   */
  const handleDownloadSubmission = useCallback(async () => {
    if (!activeSubSummary) return
    const response = await exportSubmissions(courseId, courseExerciseId, [activeSubSummary.id])
    await saveResponseAsFile(response, exercise.solution_file_name)
  }, [courseId, courseExerciseId, activeSubSummary, exercise.solution_file_name])

  // Select submission by number (from activity feed clicks)
  const handleSelectSubmissionNumber = useCallback((nr: number) => {
    const sub = submissions?.find((s) => s.submission_number === nr)
    if (sub) setSelectedSubId(sub.id)
  }, [submissions])

  const studentName = currentRow
    ? `${currentRow.given_name} ${currentRow.family_name}`
    : studentId

  const isLoading = subsLoading || detailLoading

  // Mark seen/unseen
  const markSeenMutation = useMarkSubmissionsSeen(courseId, courseExerciseId)
  const isSeen = subDetail?.seen ?? currentRow?.submission?.seen ?? false

  /**
   * What the view has already decided about, so that opening a submission and then marking it
   * unseen does not immediately undo itself.
   *
   * Auto-marking is an effect on the submission that is on screen; without this it would fire again
   * the moment the manual toggle turned `seen` back off, and the button would look broken.
   */
  const seenHandledRef = useRef(new Set<string>())

  const toggleSeen = useCallback(() => {
    const subId = subDetail?.id ?? currentRow?.submission?.id
    if (!subId) return
    seenHandledRef.current.add(subId)
    markSeenMutation.mutate({ submissions: [{ id: subId }], seen: !isSeen })
  }, [subDetail?.id, currentRow?.submission?.id, isSeen, markSeenMutation])

  /**
   * Looking at a submission is what marks it seen.
   *
   * The dot in the students list says "nobody has opened this yet", and a teacher reading the code
   * has opened it — before this, the dot only cleared if they also remembered to press the button,
   * which meant the list quietly filled up with rows already graded. Only the latest submission
   * counts, the same one the list's dot tracks: paging back through history is not the same as
   * having read the new work.
   */
  useEffect(() => {
    if (!isViewingLatest || !subDetail || subDetail.seen) return
    if (seenHandledRef.current.has(subDetail.id)) return
    seenHandledRef.current.add(subDetail.id)
    markSeenMutation.mutate({ submissions: [{ id: subDetail.id }], seen: true })
  }, [isViewingLatest, subDetail, markSeenMutation])

  /**
   * Flag for review, on the submission and shared with the rest of the teaching team.
   *
   * It lived in `localStorage` keyed by course exercise and student — per browser, so a colleague
   * never saw it, the teacher lost it on their other machine, and clearing the cache cleared the
   * pile. The flag is a note to whoever grades next, which is the case for storing it where they
   * can read it; `seen` beside it goes the other way, per teacher, because that one is about the
   * reader rather than the submission.
   */
  const markFlaggedMutation = useMarkSubmissionsFlagged(courseId, courseExerciseId)
  const isFlagged = subDetail?.flagged ?? currentRow?.submission?.flagged ?? false

  const toggleFlag = useCallback(() => {
    const subId = subDetail?.id ?? currentRow?.submission?.id
    if (!subId) return
    markFlaggedMutation.mutate({ submissions: [{ id: subId }], flagged: !isFlagged })
  }, [subDetail?.id, currentRow?.submission?.id, isFlagged, markFlaggedMutation])

  // Student picker popover
  const [pickerAnchor, setPickerAnchor] = useState<Element | null>(null)
  const [pickerSearch, setPickerSearch] = useState('')

  const filteredStudents = useMemo(() => {
    if (!pickerSearch.trim()) return sortedStudents
    const q = pickerSearch.toLowerCase()
    return sortedStudents.filter(
      (s) =>
        s.given_name.toLowerCase().includes(q) ||
        s.family_name.toLowerCase().includes(q),
    )
  }, [sortedStudents, pickerSearch])

  return (
    <Box ref={rootRef}>
      {/* Student header */}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, mb: 2, flexWrap: 'wrap' }}>
        <Tooltip title={t('submission.backToList')}>
          <IconButton size="small" onClick={onBack} aria-label={t('general.back')}>
            <ArrowBackOutlined fontSize="small" />
          </IconButton>
        </Tooltip>

        <Divider orientation="vertical" flexItem sx={{ mx: 0.5 }} />

        {/*
          Prev/next before the name, not after it (EZ-1900). Everything to the left of these two is
          fixed width, so they land in the same place for every student. Behind the name they were
          positioned by however long that name happened to be, and clicking next moved the button
          out from under the pointer — which is the one gesture this pair exists for.
        */}
        <Tooltip title={prevStudent ? `${prevStudent.given_name} ${prevStudent.family_name}` : ''}>
          <span>
            <IconButton
              size="small"
              disabled={!prevStudent}
              // The tooltip names *who* is next; the label has to say what the button does — and
              // every sibling icon button in this view already carries one (audit X-030).
              aria-label={t('submission.previousStudent')}
              onClick={() => prevStudent && onSelectStudent(prevStudent.student_id)}
            >
              <ChevronLeftOutlined fontSize="small" />
            </IconButton>
          </span>
        </Tooltip>
        <Tooltip title={nextStudent ? `${nextStudent.given_name} ${nextStudent.family_name}` : ''}>
          <span>
            <IconButton
              size="small"
              disabled={!nextStudent}
              aria-label={t('submission.nextStudent')}
              onClick={() => nextStudent && onSelectStudent(nextStudent.student_id)}
            >
              <ChevronRightOutlined fontSize="small" />
            </IconButton>
          </span>
        </Tooltip>

        {/* Clickable student name — opens picker */}
        <ButtonBase
          onClick={(e) => { setPickerAnchor(e.currentTarget); setPickerSearch('') }}
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 0.5,
            px: 1,
            py: 0.5,
            borderRadius: 1,
            minWidth: 0,
            '&:hover': { bgcolor: 'action.hover' },
          }}
        >
          <Typography
            variant="subtitle1"
            sx={{
              fontWeight: 600,
              minWidth: 0,
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
            }}
          >
            {studentName}
          </Typography>
          <ArrowDropDownOutlined sx={{ fontSize: 20, color: 'text.secondary', flexShrink: 0 }} />
        </ButtonBase>

        {/* Student picker popover */}
        <Popover
          open={!!pickerAnchor}
          anchorEl={pickerAnchor}
          onClose={() => setPickerAnchor(null)}
          anchorOrigin={{ vertical: 'bottom', horizontal: 'left' }}
          transformOrigin={{ vertical: 'top', horizontal: 'left' }}
          slotProps={{ paper: { sx: { width: 320, maxHeight: 420, display: 'flex', flexDirection: 'column' } } }}
        >
          {/* Search */}
          <Box sx={{ p: 1, pb: 0.5 }}>
            <TextField
              value={pickerSearch}
              onChange={(e) => setPickerSearch(e.target.value)}
              placeholder={t('submission.searchStudents')}
              size="small"
              fullWidth
              autoFocus
              slotProps={{
                input: {
                  startAdornment: (
                    <InputAdornment position="start">
                      <SearchOutlined sx={{ fontSize: 18, color: 'text.disabled' }} />
                    </InputAdornment>
                  ),
                },
              }}
            />
          </Box>

          {/* Student list */}
          <Box sx={{ flex: 1, overflow: 'auto', py: 0.5 }}>
            {filteredStudents.map((s) => {
              const isSelected = s.student_id === studentId
              const name = `${s.given_name} ${s.family_name}`
              const sub = s.submission
              return (
                <ButtonBase
                  key={s.student_id}
                  onClick={() => {
                    onSelectStudent(s.student_id)
                    setPickerAnchor(null)
                  }}
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 1,
                    width: '100%',
                    px: 1.5,
                    py: 0.75,
                    textAlign: 'left',
                    bgcolor: isSelected ? 'action.selected' : 'transparent',
                    '&:hover': { bgcolor: isSelected ? 'action.selected' : 'action.hover' },
                  }}
                >
                  <Typography
                    variant="body2"
                    sx={{
                      flex: 1,
                      minWidth: 0,
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                      fontWeight: isSelected ? 600 : 400,
                    }}
                  >
                    {name}
                  </Typography>
                  {/* The same mark the students list carries. This picker is how a teacher moves
                      between students without going back, so it is the other place the pile is
                      worked through, and a flag missing from it means doubling back to find one. */}
                  {sub?.flagged && (
                    <Tooltip title={t('submission.flaggedForReview')}>
                      <FlagRounded
                        titleAccess={t('submission.flaggedForReview')}
                        sx={{ fontSize: 16, color: 'warning.main', flexShrink: 0 }}
                      />
                    </Tooltip>
                  )}
                  {sub?.grade && (
                    <Chip
                      label={sub.grade.grade}
                      size="small"
                      variant="outlined"
                      color={
                        s.status === 'COMPLETED' ? 'success'
                          : s.status === 'STARTED' ? 'warning'
                            : 'default'
                      }
                      sx={{ fontSize: '0.7rem', height: 20, minWidth: 32 }}
                    />
                  )}
                </ButtonBase>
              )
            })}
            {filteredStudents.length === 0 && (
              <Typography variant="body2" color="text.secondary" sx={{ px: 1.5, py: 2, textAlign: 'center' }}>
                {t('submission.noMatchingStudents')}
              </Typography>
            )}
          </Box>

          {/* Footer: progress */}
          <Box sx={{ px: 1.5, py: 1, borderTop: 1, borderColor: 'divider' }}>
            <Typography variant="caption" color="text.secondary">
              {currentIndex >= 0 ? `${currentIndex + 1} / ${sortedStudents.length}` : `${sortedStudents.length} ${t('submission.tabStudents').toLowerCase()}`}
            </Typography>
          </Box>
        </Popover>

        <Box sx={{ flex: 1 }} />

        {/* Seen + Flag buttons — only on a latest submission that exists */}
        {isViewingLatest && (
          <Box sx={{ display: 'flex', alignItems: 'center' }}>
            <Tooltip title={isSeen ? t('submission.markUnseen') : t('submission.markSeen')}>
              <IconButton size="small" onClick={toggleSeen}>
                {/* Keyed on the state so the mark replays its animation on every change, including
                    the one nobody asked for: opening a submission marks it seen by itself, and the
                    ring settling to grey is how a teacher sees that happen rather than finding it
                    already done. */}
                <Box key={isSeen ? 'seen' : 'unseen'} sx={markTransition}>
                  {isSeen ? <SeenIndicator size={16} /> : <UnseenIndicator size={16} />}
                </Box>
              </IconButton>
            </Tooltip>
            <Tooltip title={isFlagged ? t('submission.unflag') : t('submission.flagForReview')}>
              <IconButton size="small" onClick={toggleFlag}>
                <Box key={isFlagged ? 'flagged' : 'unflagged'} sx={isFlagged ? flagRaise : markTransition}>
                  {isFlagged
                    ? <FlagRounded fontSize="small" color="warning" />
                    : <FlagOutlined fontSize="small" />
                  }
                </Box>
              </IconButton>
            </Tooltip>
          </Box>
        )}

        {/* Submission selector */}
        {submissions && submissions.length > 0 && (
          <SubmissionSelector
            submissions={submissions}
            selectedId={activeSubSummary?.id ?? ''}
            onSelect={setSelectedSubId}
          />
        )}
      </Box>

      {/* Non-latest warning */}
      {activeSubSummary && !isViewingLatest && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          {t('submission.olderSubmissionWarning')}
        </Alert>
      )}

      {/* Loading state */}
      {isLoading && (
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <Skeleton variant="rectangular" height={200} sx={{ borderRadius: 1 }} />
          <Skeleton variant="rectangular" height={40} sx={{ borderRadius: 1 }} />
          <Skeleton variant="rectangular" height={80} sx={{ borderRadius: 1 }} />
        </Box>
      )}

      {/* No submissions */}
      {!subsLoading && (!submissions || submissions.length === 0) && (
        <Typography color="text.secondary" sx={{ py: 4, textAlign: 'center' }}>
          {t('exercises.notSubmitted')}
        </Typography>
      )}

      {/* Submission content — only render when detail (with solution) is loaded */}
      {subDetail && (
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: sideBySide ? 'minmax(0, 1fr) clamp(400px, 30%, 560px)' : 'minmax(0, 1fr)',
            columnGap: sideBySide ? 3 : 0,
            alignItems: 'start',
          }}
        >
          {/* Code view with inline comments */}
          <Box sx={{ mb: 2, minWidth: 0 }}>
            <AnnotatedCodeEditor
              key={subDetail.id}
              solution={subDetail.solution}
              fileName={exercise.solution_file_name}
              onDownload={handleDownloadSubmission}
              comments={currentSubComments}
              currentTeacherId={username}
              onCreateComment={isViewingLatest ? handleCreateComment : undefined}
              onUpdateComment={handleUpdateComment}
              onDeleteComment={handleDeleteComment}
            />
          </Box>

          {/* Everything that is *about* the code rather than the code: beside it when there is
              room, under it when there is not — see `GRADING_ROW_MIN_WIDTH`. */}
          <Box sx={{ minWidth: 0 }}>
          {/* Grading itself failed: without this the teacher a student was told to contact sees
              nothing at all — no failure label, and the retry affordance below is gated behind an
              assessment a FAILED run does not produce (audit X-026). */}
          {isGraderFailed(subDetail) && (
            <Alert
              severity="warning"
              sx={{ mb: 2 }}
              action={
                exercise.grader_type === 'AUTO' ? (
                  <Button
                    color="inherit"
                    size="small"
                    disabled={retryAutoassess.isPending}
                    startIcon={
                      retryAutoassess.isPending
                        ? <CircularProgress size={14} color="inherit" />
                        : <RefreshOutlined fontSize="small" />
                    }
                    onClick={() => {
                      retryAutoassess.mutate(subDetail.id, {
                        onSuccess: () => setRetryDone(true),
                      })
                    }}
                  >
                    {t('submission.retryAutoassess')}
                  </Button>
                ) : undefined
              }
            >
              {/* One string in one span, rather than a sentence plus a conditional second text
                  node. Retrying resets the mutation, so `isError` goes true→false on a second
                  attempt and React would delete that second text node out of a surviving div —
                  the EZ-1888 crash exactly, on a page the teacher had translated. */}
              <SafeText>
                {t('submission.graderFailedTeacherView') +
                  (retryAutoassess.isError ? ` ${t('submission.retryAutoassessFailed')}` : '')}
              </SafeText>
            </Alert>
          )}

          {/* Auto test results */}
          {subDetail.auto_assessment && (
            <Box sx={{ mb: 2 }}>
              <AutoTestResults
                autoAssessment={subDetail.auto_assessment}
                staggerReveal={false}
                collapsible
                defaultExpanded={false}
                headerAction={
                  exercise.grader_type === 'AUTO' ? (
                    // Rare action, so it earns an icon rather than a labelled button — the tooltip
                    // and the accessible name carry the meaning.
                    <Tooltip title={t('submission.retryAutoassessHint')}>
                      <span>
                        <IconButton
                          size="small"
                          aria-label={t('submission.retryAutoassess')}
                          disabled={retryAutoassess.isPending}
                          onClick={() => {
                            retryAutoassess.mutate(subDetail.id, {
                              onSuccess: () => setRetryDone(true),
                            })
                          }}
                        >
                          {retryAutoassess.isPending
                            ? <CircularProgress size={16} color="inherit" />
                            : <RefreshOutlined fontSize="small" />}
                        </IconButton>
                      </span>
                    </Tooltip>
                  ) : undefined
                }
              />
              {retryAutoassess.isError && (
                <Typography variant="caption" color="error">
                  {t('submission.retryAutoassessFailed')}
                </Typography>
              )}
            </Box>
          )}

          {/* Deliberately not "graded successfully": core returns 200 even when the assessment
              failed again, having recorded that as an activity. The honest message is that it ran
              and the result is now on screen. */}
          <Snackbar
            open={retryDone}
            autoHideDuration={4000}
            onClose={() => setRetryDone(false)}
            message={t('submission.retryAutoassessDone')}
          />

          {/* Activity feed (grade + feedback composer + history) */}
          {/* At the head of its own column the heading lines up with the top of the code; the gap
              is only for when something sits above it. */}
          <Box sx={{ mt: sideBySide && !subDetail.auto_assessment && !isGraderFailed(subDetail) ? 0 : 3 }}>
            <Typography variant="subtitle2" sx={{ mb: 1.5 }}>
              {t('submission.activity')}
            </Typography>
            <ActivityFeed
              courseId={courseId}
              courseExerciseId={courseExerciseId}
              submissionId={subDetail.id}
              studentId={studentId}
              grade={grade}
              onGradeChange={setGrade}
              initialGrade={initialGrade}
              gradeInfo={gradeInfo}
              activities={activities?.teacher_activities}
              allInlineComments={allInlineComments}
              aiFeedback={activities?.ai_feedback}
              solutionFileName={exercise.solution_file_name}
              onSelectSubmissionNumber={handleSelectSubmissionNumber}
              showComposer={isViewingLatest}
            />
          </Box>
          </Box>
        </Box>
      )}
    </Box>
  )
}
