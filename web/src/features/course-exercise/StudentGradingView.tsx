import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Box,
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
} from '@mui/material'
import {
  ArrowBackOutlined,
  ArrowDropDownOutlined,
  ChevronLeftOutlined,
  ChevronRightOutlined,
  CircleOutlined,
  FlagOutlined,
  FlagRounded,
  FiberManualRecordRounded,
  RefreshOutlined,
  SearchOutlined,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import {
  useCreateInlineComment,
  useDeleteInlineComment,
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
import ActivityFeed from './ActivityFeed.tsx'
import SubmissionSelector from './SubmissionSelector.tsx'
import AnnotatedCodeEditor, { type NewCommentData } from './AnnotatedCodeEditor.tsx'
import type { TeacherExerciseDetails, SubmissionRow } from '../../api/types.ts'

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
  const isViewingLatest = activeSubSummary?.id === latestSub?.id

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

  // Select submission by number (from activity feed clicks)
  const handleSelectSubmissionNumber = useCallback((nr: number) => {
    const sub = submissions?.find((s) => s.submission_number === nr)
    if (sub) setSelectedSubId(sub.id)
  }, [submissions])

  // Keyboard shortcuts
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'ArrowRight' && (e.ctrlKey || e.metaKey) && nextStudent) {
        e.preventDefault()
        onSelectStudent(nextStudent.student_id)
      }
      if (e.key === 'ArrowLeft' && (e.ctrlKey || e.metaKey) && prevStudent) {
        e.preventDefault()
        onSelectStudent(prevStudent.student_id)
      }
    }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [nextStudent, prevStudent, onSelectStudent])

  const studentName = currentRow
    ? `${currentRow.given_name} ${currentRow.family_name}`
    : studentId

  const isLoading = subsLoading || detailLoading

  // Mark seen/unseen
  const markSeenMutation = useMarkSubmissionsSeen(courseId, courseExerciseId)
  const isSeen = subDetail?.seen ?? currentRow?.submission?.seen ?? false

  const toggleSeen = useCallback(() => {
    const subId = subDetail?.id ?? currentRow?.submission?.id
    if (!subId) return
    markSeenMutation.mutate({ submissions: [{ id: subId }], seen: !isSeen })
  }, [subDetail?.id, currentRow?.submission?.id, isSeen, markSeenMutation])

  // Flag for review (stored in localStorage until backend support is added)
  const flagKey = `flagged:${courseExerciseId}:${studentId}`
  const [isFlagged, setIsFlagged] = useState(() => localStorage.getItem(flagKey) === '1')

  useEffect(() => {
    const key = `flagged:${courseExerciseId}:${studentId}`
    setIsFlagged(localStorage.getItem(key) === '1')
  }, [courseExerciseId, studentId])

  const toggleFlag = useCallback(() => {
    setIsFlagged((prev) => {
      const next = !prev
      if (next) localStorage.setItem(flagKey, '1')
      else localStorage.removeItem(flagKey)
      return next
    })
  }, [flagKey])

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
    <Box>
      {/* Student header */}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, mb: 2, flexWrap: 'wrap' }}>
        <Tooltip title={t('submission.backToList')}>
          <IconButton size="small" onClick={onBack}>
            <ArrowBackOutlined fontSize="small" />
          </IconButton>
        </Tooltip>

        <Divider orientation="vertical" flexItem sx={{ mx: 0.5 }} />

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

        {/* Prev/next arrows */}
        <Tooltip title={prevStudent ? `${prevStudent.given_name} ${prevStudent.family_name}` : ''}>
          <span>
            <IconButton size="small" disabled={!prevStudent} onClick={() => prevStudent && onSelectStudent(prevStudent.student_id)}>
              <ChevronLeftOutlined fontSize="small" />
            </IconButton>
          </span>
        </Tooltip>
        <Tooltip title={nextStudent ? `${nextStudent.given_name} ${nextStudent.family_name}` : ''}>
          <span>
            <IconButton size="small" disabled={!nextStudent} onClick={() => nextStudent && onSelectStudent(nextStudent.student_id)}>
              <ChevronRightOutlined fontSize="small" />
            </IconButton>
          </span>
        </Tooltip>

        <Box sx={{ flex: 1 }} />

        {/* Seen + Flag buttons — only on latest submission */}
        {isViewingLatest && (
          <Box sx={{ display: 'flex', alignItems: 'center' }}>
            <Tooltip title={isSeen ? t('submission.markUnseen') : t('submission.markSeen')}>
              <IconButton size="small" onClick={toggleSeen}>
                {isSeen
                  ? <CircleOutlined sx={{ fontSize: 16, color: 'text.disabled' }} />
                  : <FiberManualRecordRounded sx={{ fontSize: 16, color: 'error.main' }} />
                }
              </IconButton>
            </Tooltip>
            <Tooltip title={isFlagged ? t('submission.unflag') : t('submission.flagForReview')}>
              <IconButton size="small" onClick={toggleFlag}>
                {isFlagged
                  ? <FlagRounded fontSize="small" color="warning" />
                  : <FlagOutlined fontSize="small" />
                }
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
        <>
          {/* Code view with inline comments */}
          <Box sx={{ mb: 2 }}>
            <AnnotatedCodeEditor
              key={subDetail.id}
              solution={subDetail.solution}
              fileName={exercise.solution_file_name}
              comments={currentSubComments}
              currentTeacherId={username}
              onCreateComment={isViewingLatest ? handleCreateComment : undefined}
              onUpdateComment={handleUpdateComment}
              onDeleteComment={handleDeleteComment}
            />
          </Box>

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
          <Box sx={{ mt: 3 }}>
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
              activities={activities}
              allInlineComments={allInlineComments}
              solutionFileName={exercise.solution_file_name}
              onSelectSubmissionNumber={handleSelectSubmissionNumber}
              showComposer={isViewingLatest}
            />
          </Box>
        </>
      )}
    </Box>
  )
}
