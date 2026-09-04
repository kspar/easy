import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Chip,
  CircularProgress,
  Collapse,
  Divider,
  FormControlLabel,
  IconButton,
  Paper,
  Snackbar,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material'
import {
  CodeOutlined,
  DeleteOutlineOutlined,
  EditOutlined,
  ExpandMoreOutlined,
  FormatBoldOutlined,
  FormatItalicOutlined,
  FormatListBulletedOutlined,
  FormatListNumberedOutlined,
  WrapTextOutlined,
  UpdateOutlined,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import { EditorView, keymap, placeholder as cmPlaceholder } from '@codemirror/view'
import { EditorState } from '@codemirror/state'
import { markdown } from '@codemirror/lang-markdown'
import { minimalSetup } from 'codemirror'
import { oneDark } from '@codemirror/theme-one-dark'
import { useTheme } from '@mui/material/styles'
import {
  usePostGrade,
  usePostFeedback,
  useEditFeedback,
  useMarkdownPreview,
} from '../../api/exercises.ts'
import { useAuth } from '../../auth/useAuth.ts'
import RelativeTime from '../../components/RelativeTime.tsx'
import { RobotIcon, TeacherFaceIcon } from '../../components/icons.tsx'
import type { InlineCommentResp, TeacherActivityResp } from '../../api/types.ts'
import ConfirmDialog from '../../components/ConfirmDialog.tsx'
import ReadOnlyCodeSnippet from './ReadOnlyCodeSnippet.tsx'
import RenderedMarkdown from '../../components/markdown/RenderedMarkdown.tsx'
import { useMarkdownUpload } from '../../components/markdown/useMarkdownUpload.ts'
import { useFileDropExtension } from '../../components/markdown/useFileDropExtension.ts'
import { errorMessage } from '../../api/errorMessage.ts'
import { useSoftWrap } from '../../components/editorWrap.ts'
import SafeText from '../../components/SafeText.tsx'

const NOTIFY_KEY = 'teacherNotifyStudent'
const MERGE_WINDOW_MS = 15 * 60 * 1000 // 15 minutes

function readNotifyPref(): boolean {
  try {
    return localStorage.getItem(NOTIFY_KEY) !== 'false'
  } catch {
    return true
  }
}

/** Wrap or insert markdown formatting around the current selection. */
function applyFormat(view: EditorView, before: string, after: string, placeholder: string) {
  const { from, to } = view.state.selection.main
  const selected = view.state.sliceDoc(from, to)
  const insert = selected ? `${before}${selected}${after}` : `${before}${placeholder}${after}`
  view.dispatch({
    changes: { from, to, insert },
    selection: selected
      ? { anchor: from, head: from + insert.length }
      : { anchor: from + before.length, head: from + before.length + placeholder.length },
  })
  view.focus()
}

/** Insert a prefix at the start of each selected line (for lists). */
function applyLinePrefix(view: EditorView, prefix: string) {
  const { from, to } = view.state.selection.main
  const startLine = view.state.doc.lineAt(from)
  const endLine = view.state.doc.lineAt(to)
  const changes: { from: number; to: number; insert: string }[] = []
  for (let n = startLine.number; n <= endLine.number; n++) {
    const line = view.state.doc.line(n)
    changes.push({ from: line.from, to: line.from, insert: prefix })
  }
  view.dispatch({ changes })
  view.focus()
}

/* ───────── Timeline types ───────── */

interface TimelineEntry {
  teacherId: string
  teacherName: string
  time: string
  activity?: TeacherActivityResp
  inlineComments: InlineCommentResp[]
  submissionNumbers: Set<number>
}

function buildTimeline(
  activities: TeacherActivityResp[] | undefined,
  inlineComments: InlineCommentResp[] | undefined,
): TimelineEntry[] {
  // One entry per activity, sorted ascending by time for matching
  const entries: TimelineEntry[] = (activities ?? []).map((a) => ({
    teacherId: a.teacher.id,
    teacherName: `${a.teacher.given_name} ${a.teacher.family_name}`,
    time: a.created_at,
    activity: a,
    inlineComments: [],
    submissionNumbers: new Set([a.submission_number]),
  }))
  entries.sort((a, b) => new Date(a.time).getTime() - new Date(b.time).getTime())

  // Attach each inline comment to the closest activity by the same teacher within the window
  // Prefer preceding activities; only use a following activity if no preceding one is in the window
  const orphans: InlineCommentResp[] = []
  for (const c of inlineComments ?? []) {
    const cTime = new Date(c.created_at).getTime()
    let bestBefore: TimelineEntry | null = null
    let bestBeforeDiff = Infinity
    let bestAfter: TimelineEntry | null = null
    let bestAfterDiff = Infinity
    for (const entry of entries) {
      if (!entry.activity || entry.teacherId !== c.teacher.id) continue
      const eTime = new Date(entry.time).getTime()
      const diff = Math.abs(eTime - cTime)
      if (diff >= MERGE_WINDOW_MS) continue
      if (eTime <= cTime && diff < bestBeforeDiff) {
        bestBefore = entry
        bestBeforeDiff = diff
      } else if (eTime > cTime && diff < bestAfterDiff) {
        bestAfter = entry
        bestAfterDiff = diff
      }
    }
    const best = bestBefore ?? bestAfter
    if (best) {
      best.inlineComments.push(c)
      best.submissionNumbers.add(c.submission_number)
    } else {
      orphans.push(c)
    }
  }

  // Orphan inline comments (no preceding activity) — group by teacher within the window
  for (const c of orphans) {
    const cTime = new Date(c.created_at).getTime()
    const match = entries.find(
      (e) => !e.activity && e.teacherId === c.teacher.id &&
        Math.abs(new Date(e.time).getTime() - cTime) < MERGE_WINDOW_MS,
    )
    if (match) {
      match.inlineComments.push(c)
      match.submissionNumbers.add(c.submission_number)
    } else {
      entries.push({
        teacherId: c.teacher.id,
        teacherName: `${c.teacher.given_name} ${c.teacher.family_name}`,
        time: c.created_at,
        inlineComments: [c],
        submissionNumbers: new Set([c.submission_number]),
      })
    }
  }

  // Sort descending for display
  entries.sort((a, b) => new Date(b.time).getTime() - new Date(a.time).getTime())
  return entries
}

/* ───────── Component ───────── */

export default function ActivityFeed({
  courseId,
  courseExerciseId,
  submissionId,
  studentId,
  grade,
  onGradeChange,
  initialGrade,
  gradeInfo,
  activities,
  allInlineComments,
  solutionFileName,
  onSubmitted,
  showComposer = true,
  onSelectSubmissionNumber,
}: {
  courseId: string
  courseExerciseId: string
  submissionId: string
  studentId: string
  grade: string
  onGradeChange: (val: string) => void
  initialGrade: string
  gradeInfo: { isAutograde: boolean; isGradedDirectly: boolean } | null
  activities?: TeacherActivityResp[]
  allInlineComments?: InlineCommentResp[]
  solutionFileName?: string
  onSubmitted?: () => void
  showComposer?: boolean
  onSelectSubmissionNumber?: (nr: number) => void
}) {
  const { t } = useTranslation()
  const theme = useTheme()
  const queryClient = useQueryClient()
  const { username } = useAuth()

  // Feedback text with localStorage draft
  // Raw text lives in a ref (updated on every keystroke without re-render).
  // Debounced state drives preview, draft saving, and submit-button reactivity.
  const draftKey = `feedbackDraft:${courseExerciseId}:${studentId}`
  const readDraft = () => {
    try { return localStorage.getItem(draftKey) ?? '' } catch { return '' }
  }
  const feedbackRef = useRef(readDraft())
  const [feedback, setFeedback] = useState(feedbackRef.current)
  const feedbackTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const setFeedbackDebounced = useCallback((text: string) => {
    feedbackRef.current = text
    clearTimeout(feedbackTimerRef.current)
    feedbackTimerRef.current = setTimeout(() => setFeedback(text), 150)
  }, [])
  const [notifyStudent, setNotifyStudent] = useState(readNotifyPref)
  const [snackMsg, setSnackMsg] = useState<string | null>(null)
  const previewHtml = useMarkdownPreview(feedback, 150)

  // CodeMirror refs
  const editorContainerRef = useRef<HTMLDivElement>(null)
  const viewRef = useRef<EditorView | null>(null)
  // Prose, so it follows the markdown setting — the switch is the one in its own toolbar.
  const { wrap, wrapExtension, toggleWrap } = useSoftWrap('markdown', viewRef)

  // Auto-save draft
  useEffect(() => {
    const timer = setTimeout(() => {
      try {
        if (feedback) {
          localStorage.setItem(draftKey, feedback)
        } else {
          localStorage.removeItem(draftKey)
        }
      } catch { /* ignore */ }
    }, 500)
    return () => clearTimeout(timer)
  }, [feedback, draftKey])

  // Persist notify preference
  useEffect(() => {
    try {
      localStorage.setItem(NOTIFY_KEY, String(notifyStudent))
    } catch { /* ignore */ }
  }, [notifyStudent])

  // Initialise CodeMirror (re-creates on theme change)
  useEffect(() => {
    if (!editorContainerRef.current || !showComposer) return

    // Preserve content on theme change
    const prevDoc = viewRef.current?.state.doc.toString()
    viewRef.current?.destroy()
    viewRef.current = null

    const extensions = [
      minimalSetup,
      markdown(),
      wrapExtension(),
      cmPlaceholder(t('submission.feedbackPlaceholder')),
      keymap.of([
        { key: 'Mod-b', run: (v) => { applyFormat(v, '**', '**', 'bold'); return true } },
        { key: 'Mod-i', run: (v) => { applyFormat(v, '_', '_', 'italic'); return true } },
      ]),
      EditorView.updateListener.of((update) => {
        if (update.docChanged) setFeedbackDebounced(update.state.doc.toString())
      }),
      EditorView.theme({
        '.cm-content': { padding: '8px 12px', fontSize: '0.875rem' },
        '&.cm-focused': { outline: 'none' },
      }),
    ]
    if (theme.palette.mode === 'dark') extensions.push(oneDark)

    const state = EditorState.create({
      doc: prevDoc ?? feedback,
      extensions,
    })

    viewRef.current = new EditorView({
      state,
      parent: editorContainerRef.current,
    })

    return () => {
      viewRef.current?.destroy()
      viewRef.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [theme.palette.mode, showComposer])

  // Reset editor when student changes
  useEffect(() => {
    let draft = ''
    try { draft = localStorage.getItem(draftKey) ?? '' } catch { /* ignore */ }
    feedbackRef.current = draft
    setFeedback(draft)
    const view = viewRef.current
    if (view) {
      view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: draft } })
    }
  }, [draftKey])

  const postGrade = usePostGrade(courseId, courseExerciseId)
  const postFeedback = usePostFeedback(courseId, courseExerciseId)
  const editFeedback = useEditFeedback(courseId, courseExerciseId)

  const isSubmitting = postGrade.isPending || postFeedback.isPending || editFeedback.isPending

  const gradeChanged = grade !== initialGrade
  const hasFeedbackText = feedback.trim().length > 0

  // A grade is only postable when it parses and falls in range. Clearing the field
  // is not a change we can send — there is no un-grade endpoint.
  const parsedGrade = grade !== '' ? parseInt(grade, 10) : null
  const gradeInvalid = parsedGrade !== null && (isNaN(parsedGrade) || parsedGrade < 0 || parsedGrade > 100)
  const canPostGrade = parsedGrade !== null && !gradeInvalid && gradeChanged

  const hasChanges = canPostGrade || hasFeedbackText

  const handleSubmit = useCallback(async () => {
    const feedbackText = viewRef.current?.state.doc.toString().trim() ?? ''

    // Normal create mode
    const gradeNum = grade !== '' ? parseInt(grade, 10) : null
    const hasFeedback = !!feedbackText
    const hasGrade = gradeNum !== null && !isNaN(gradeNum) && gradeNum >= 0 && gradeNum <= 100
    const gradeChanged = grade !== initialGrade
    const shouldPostGrade = hasGrade && gradeChanged

    if (!shouldPostGrade && !hasFeedback) return

    try {
      if (shouldPostGrade) {
        await postGrade.mutateAsync({
          submissionId,
          grade: gradeNum!,
          notifyStudent: !hasFeedback && notifyStudent,
        })
      }

      if (hasFeedback) {
        await postFeedback.mutateAsync({
          submissionId,
          feedbackMd: feedbackText || null,
          notifyStudent,
        })
      }

      // Clear draft + editor
      try { localStorage.removeItem(draftKey) } catch { /* ignore */ }
      clearTimeout(feedbackTimerRef.current)
      feedbackRef.current = ''
      setFeedback('')
      const view = viewRef.current
      if (view) view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: '' } })

      // Invalidate queries
      queryClient.invalidateQueries({
        queryKey: ['teacher', 'courses', courseId, 'exercises', courseExerciseId],
      })

      setSnackMsg(t('submission.gradeSubmitted'))
      onSubmitted?.()
    } catch {
      // errors are handled by react-query
    }
  }, [grade, initialGrade, notifyStudent, submissionId, courseId, courseExerciseId, draftKey, postGrade, postFeedback, queryClient, t, onSubmitted])

  const handleEditActivity = useCallback(async (activity: TeacherActivityResp, newFeedbackMd: string | null, notifyStudent: boolean) => {
    try {
      await editFeedback.mutateAsync({
        submissionId: activity.submission_id,
        teacherActivityId: activity.id,
        feedbackMd: newFeedbackMd,
        notifyStudent,
      })
      queryClient.invalidateQueries({
        queryKey: ['teacher', 'courses', courseId, 'exercises', courseExerciseId],
      })
      setSnackMsg(t('submission.gradeSubmitted'))
    } catch {
      // errors are handled by react-query
    }
  }, [editFeedback, courseId, courseExerciseId, queryClient, t])

  const handleDeleteFeedback = useCallback(async (activity: TeacherActivityResp) => {
    try {
      await editFeedback.mutateAsync({
        submissionId: activity.submission_id,
        teacherActivityId: activity.id,
        feedbackMd: null,
        notifyStudent: false,
      })
      queryClient.invalidateQueries({
        queryKey: ['teacher', 'courses', courseId, 'exercises', courseExerciseId],
      })
      setSnackMsg(t('submission.commentDeleted'))
    } catch {
      // errors are handled by react-query
    }
  }, [editFeedback, courseId, courseExerciseId, queryClient, t])

  const timelineEntries = useMemo(
    () => buildTimeline(activities, allInlineComments),
    [activities, allInlineComments],
  )

  const tbSx = {
    p: '5px',
    borderRadius: '6px',
    color: 'text.secondary',
    '&:hover': { color: 'text.primary', bgcolor: 'action.hover' },
  }

  return (
    <Box>
      {/* Composer card */}
      {showComposer && (
        <Paper
          variant="outlined"
          sx={{
            borderLeft: 3,
            borderLeftColor: 'primary.main',
            mb: 1.5,
          }}
        >
          {/* Grade row */}
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, px: 2, py: 1.5, flexWrap: 'wrap' }}>
            <Typography variant="subtitle2" sx={{ flexShrink: 0 }}>
              {t('submission.grade')}
            </Typography>
            <TextField
              value={grade}
              onChange={(e) => {
                const v = e.target.value
                if (v === '' || /^\d{0,3}$/.test(v)) onGradeChange(v)
              }}
              size="small"
              error={gradeInvalid}
              inputProps={{ inputMode: 'numeric', style: { width: 48, textAlign: 'center', fontWeight: 600 } }}
              sx={{
                '& .MuiOutlinedInput-root': {
                  borderRadius: 1,
                  ...(!gradeInvalid && grade !== initialGrade && {
                    bgcolor: 'primary.main',
                    color: 'primary.contrastText',
                    '& input': { color: 'primary.contrastText' },
                    '& .MuiOutlinedInput-notchedOutline': { borderColor: 'primary.main' },
                  }),
                },
              }}
            />
            <Typography variant="body2" color="text.secondary">/ 100</Typography>

            {grade === initialGrade && gradeInfo && (
              <Tooltip title={gradeInfo.isAutograde ? t('submission.autoGrade') : t('exercises.gradedByTeacher')}>
                {gradeInfo.isAutograde
                  ? <RobotIcon sx={{ fontSize: 18, color: 'text.secondary' }} />
                  : <TeacherFaceIcon sx={{ fontSize: 18, color: 'text.secondary' }} />
                }
              </Tooltip>
            )}
            {grade === initialGrade && gradeInfo && !gradeInfo.isGradedDirectly && (
              <Tooltip title={t('submission.gradePreviousSubmission')}>
                <UpdateOutlined sx={{ fontSize: 18, color: 'text.secondary', cursor: 'help' }} />
              </Tooltip>
            )}
          </Box>

          <Divider />

          {/* Formatting toolbar */}
          <Box sx={{
            display: 'flex',
            alignItems: 'center',
            gap: '2px',
            px: 0.75,
            py: 0.5,
            borderBottom: 1,
            borderColor: 'divider',
          }}>
            <Tooltip title={t('markdown.bold')}>
              <IconButton size="small" sx={tbSx} aria-label={t('markdown.bold')} onClick={() => viewRef.current && applyFormat(viewRef.current, '**', '**', 'bold')}>
                <FormatBoldOutlined sx={{ fontSize: 17 }} />
              </IconButton>
            </Tooltip>
            <Tooltip title={t('markdown.italic')}>
              <IconButton size="small" sx={tbSx} aria-label={t('markdown.italic')} onClick={() => viewRef.current && applyFormat(viewRef.current, '_', '_', 'italic')}>
                <FormatItalicOutlined sx={{ fontSize: 17 }} />
              </IconButton>
            </Tooltip>
            <Tooltip title={t('markdown.code')}>
              <IconButton size="small" sx={tbSx} aria-label={t('markdown.code')} onClick={() => viewRef.current && applyFormat(viewRef.current, '`', '`', 'code')}>
                <CodeOutlined sx={{ fontSize: 17 }} />
              </IconButton>
            </Tooltip>
            <Box sx={{ width: 8 }} />
            <Tooltip title={t('markdown.bulletList')}>
              <IconButton size="small" sx={tbSx} aria-label={t('markdown.bulletList')} onClick={() => viewRef.current && applyLinePrefix(viewRef.current, '- ')}>
                <FormatListBulletedOutlined sx={{ fontSize: 17 }} />
              </IconButton>
            </Tooltip>
            <Tooltip title={t('markdown.numberedList')}>
              <IconButton size="small" sx={tbSx} aria-label={t('markdown.numberedList')} onClick={() => viewRef.current && applyLinePrefix(viewRef.current, '1. ')}>
                <FormatListNumberedOutlined sx={{ fontSize: 17 }} />
              </IconButton>
            </Tooltip>
            {/* This row is hand-built rather than a MarkdownToolbar, so the wrap switch every
                other markdown surface gets from that component has to be repeated here. */}
            <Box sx={{ flex: 1, minWidth: 8 }} />
            <Tooltip title={t('general.wrapLines')}>
              <IconButton
                size="small"
                aria-label={t('general.wrapLines')}
                aria-pressed={wrap}
                onClick={toggleWrap}
                sx={{ ...tbSx, ...(wrap && { color: 'text.primary', bgcolor: 'action.selected' }) }}
              >
                <WrapTextOutlined sx={{ fontSize: 17 }} />
              </IconButton>
            </Tooltip>
          </Box>

          {/* CodeMirror editor */}
          <Box
            ref={editorContainerRef}
            sx={{
              '& .cm-editor': { minHeight: 60, cursor: 'text' },
              '& .cm-scroller': { maxHeight: 200, overflow: 'auto' },
            }}
          />

          {/* Markdown preview */}
          {previewHtml && (
            <RenderedMarkdown
              sx={{
                borderTop: 1,
                borderColor: 'divider',
                px: 2,
                py: 1,
                fontSize: '0.85rem',
              }}
              html={previewHtml}
            />
          )}

          {/* Actions */}
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, px: 2, py: 1.5, flexWrap: 'wrap' }}>
            <FormControlLabel
              control={
                <Checkbox
                  checked={notifyStudent}
                  onChange={(e) => setNotifyStudent(e.target.checked)}
                  size="small"
                />
              }
              label={<Typography variant="body2">{t('submission.notifyStudent')}</Typography>}
              sx={{ mr: 0 }}
            />

            <Box sx={{ flex: 1 }} />

            <Button
              variant="contained"
              size="small"
              onClick={handleSubmit}
              disabled={isSubmitting || !hasChanges}
              sx={{ textTransform: 'none' }}
            >
              {/* The label is wrapped for the same reason as the autograde status line: a bare
                  text node swapping for a spinner is a `removeChild` on a node the browser's
                  translator may have replaced. See SafeText. */}
              {isSubmitting ? <CircularProgress size={18} /> : <SafeText>{t('general.save')}</SafeText>}
            </Button>
          </Box>

          {postGrade.isError && (
            <Typography color="error" variant="caption" sx={{ px: 2, pb: 1.5, display: 'block' }}>
              {errorMessage(postGrade.error, t)}
            </Typography>
          )}
        </Paper>
      )}

      {/* Timeline */}
      {timelineEntries.length > 0 && timelineEntries.map((entry, i) => (
        <TimelineEntryCard
          key={entry.activity?.id ?? `orphan-${i}`}
          entry={entry}
          isOwnTeacher={entry.teacherId === username}
          solutionFileName={solutionFileName}
          onEditActivity={handleEditActivity}
          onDeleteFeedback={handleDeleteFeedback}
          onSelectSubmissionNumber={onSelectSubmissionNumber}
          t={t}
        />
      ))}

      {timelineEntries.length === 0 && (
        <Typography variant="caption" color="text.secondary">
          {t('submission.noActivity')}
        </Typography>
      )}

      <Snackbar
        open={snackMsg !== null}
        autoHideDuration={3000}
        onClose={() => setSnackMsg(null)}
        message={snackMsg}
      />
    </Box>
  )
}

/* ───────── Edit comment editor ───────── */

const EDIT_NOTIFY_KEY = 'teacherNotifyEdit'

function EditCommentEditor({
  initialText,
  onSave,
  onCancel,
  t,
}: {
  initialText: string
  onSave: (text: string, notifyStudent: boolean) => void
  onCancel: () => void
  t: (key: string, opts?: Record<string, unknown>) => string
}) {
  const theme = useTheme()
  const containerRef = useRef<HTMLDivElement>(null)
  const viewRef = useRef<EditorView | null>(null)
  // Prose, so it follows the markdown setting — the switch is the one in its own toolbar.
  const { wrapExtension } = useSoftWrap('markdown', viewRef)
  const [text, setText] = useState(initialText)
  const textRef = useRef(initialText)
  const timerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const previewHtml = useMarkdownPreview(text, 150)
  const [notify, setNotify] = useState(() => {
    try { return localStorage.getItem(EDIT_NOTIFY_KEY) !== 'false' } catch { return true }
  })

  useEffect(() => {
    try { localStorage.setItem(EDIT_NOTIFY_KEY, String(notify)) } catch { /* ignore */ }
  }, [notify])

  const { uploadFiles, uploading, error: uploadError, clearError } = useMarkdownUpload()
  const dropExtension = useFileDropExtension(
    useCallback((files: File[]) => {
      if (viewRef.current) void uploadFiles(viewRef.current, files)
    }, [uploadFiles]),
  )

  useEffect(() => {
    if (!containerRef.current) return

    const extensions = [
      minimalSetup,
      markdown(),
      wrapExtension(),
      keymap.of([
        { key: 'Mod-b', run: (v) => { applyFormat(v, '**', '**', 'bold'); return true } },
        { key: 'Mod-i', run: (v) => { applyFormat(v, '_', '_', 'italic'); return true } },
      ]),
      EditorView.updateListener.of((update) => {
        if (update.docChanged) {
          textRef.current = update.state.doc.toString()
          clearTimeout(timerRef.current)
          timerRef.current = setTimeout(() => setText(textRef.current), 150)
        }
      }),
      EditorView.theme({
        '.cm-content': { padding: '8px 12px', fontSize: '0.875rem' },
        '&.cm-focused': { outline: 'none' },
      }),
      // No toolbar button here — this box has room for five controls and already uses them — but
      // pasting a screenshot into feedback is the one gesture that needs no room at all, and it is
      // the most likely thing a teacher wants to attach to a comment about someone's code.
      ...(dropExtension ?? []),
    ]
    if (theme.palette.mode === 'dark') extensions.push(oneDark)

    const view = new EditorView({
      state: EditorState.create({ doc: initialText, extensions }),
      parent: containerRef.current,
    })
    viewRef.current = view

    // Focus and move cursor to end
    view.focus()
    view.dispatch({ selection: { anchor: view.state.doc.length } })

    return () => {
      view.destroy()
      viewRef.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [theme.palette.mode, dropExtension])

  const tbSx = {
    p: '5px',
    borderRadius: '6px',
    color: 'text.secondary',
    '&:hover': { color: 'text.primary', bgcolor: 'action.hover' },
  }

  return (
    <Box sx={{ mt: 1, mx: -1.5, mb: -1.5, borderTop: 1, borderColor: 'divider' }}>
      {/* An upload can only be started by pasting or dropping here, but it can still fail, and a
          screenshot that silently does not appear is worse than one that says why. */}
      {uploadError && (
        <Alert severity="error" onClose={clearError} sx={{ borderRadius: 0 }}>{uploadError}</Alert>
      )}
      {/* Toolbar */}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: '2px', px: 0.75, py: 0.5, borderBottom: 1, borderColor: 'divider' }}>
        {uploading && <CircularProgress size={14} sx={{ mr: 0.5 }} />}
        <Tooltip title={t('markdown.bold')}>
          <IconButton size="small" sx={tbSx} aria-label={t('markdown.bold')} onClick={() => viewRef.current && applyFormat(viewRef.current, '**', '**', 'bold')}>
            <FormatBoldOutlined sx={{ fontSize: 17 }} />
          </IconButton>
        </Tooltip>
        <Tooltip title={t('markdown.italic')}>
          <IconButton size="small" sx={tbSx} aria-label={t('markdown.italic')} onClick={() => viewRef.current && applyFormat(viewRef.current, '_', '_', 'italic')}>
            <FormatItalicOutlined sx={{ fontSize: 17 }} />
          </IconButton>
        </Tooltip>
        <Tooltip title={t('markdown.code')}>
          <IconButton size="small" sx={tbSx} aria-label={t('markdown.code')} onClick={() => viewRef.current && applyFormat(viewRef.current, '`', '`', 'code')}>
            <CodeOutlined sx={{ fontSize: 17 }} />
          </IconButton>
        </Tooltip>
        <Box sx={{ width: 8 }} />
        <Tooltip title={t('markdown.bulletList')}>
          <IconButton size="small" sx={tbSx} aria-label={t('markdown.bulletList')} onClick={() => viewRef.current && applyLinePrefix(viewRef.current, '- ')}>
            <FormatListBulletedOutlined sx={{ fontSize: 17 }} />
          </IconButton>
        </Tooltip>
        <Tooltip title={t('markdown.numberedList')}>
          <IconButton size="small" sx={tbSx} aria-label={t('markdown.numberedList')} onClick={() => viewRef.current && applyLinePrefix(viewRef.current, '1. ')}>
            <FormatListNumberedOutlined sx={{ fontSize: 17 }} />
          </IconButton>
        </Tooltip>
      </Box>

      {/* Editor */}
      <Box
        ref={containerRef}
        sx={{
          '& .cm-editor': { minHeight: 40, cursor: 'text' },
          '& .cm-scroller': { maxHeight: 200, overflow: 'auto' },
        }}
      />

      {/* Preview */}
      {previewHtml && (
        <RenderedMarkdown
          sx={{
            borderTop: 1,
            borderColor: 'divider',
            px: 2,
            py: 1,
            fontSize: '0.85rem',
          }}
          html={previewHtml}
        />
      )}

      {/* Actions */}
      <Box sx={{ display: 'flex', gap: 0.5, px: 1, py: 0.75, borderTop: 1, borderColor: 'divider', alignItems: 'center', flexWrap: 'wrap' }}>
        <Button
          size="small"
          variant="contained"
          onClick={() => onSave(textRef.current, notify)}
          sx={{ textTransform: 'none', fontSize: '0.75rem', minWidth: 0, px: 1.5, py: 0.25 }}
        >
          {t('general.save')}
        </Button>
        <Button
          size="small"
          variant="text"
          onClick={onCancel}
          sx={{ textTransform: 'none', fontSize: '0.75rem', minWidth: 0, px: 1, py: 0.25 }}
        >
          {t('general.cancel')}
        </Button>
        <FormControlLabel
          control={
            <Checkbox
              checked={notify}
              onChange={(e) => setNotify(e.target.checked)}
              size="small"
            />
          }
          label={<Typography variant="body2">{t('submission.notifyStudent')}</Typography>}
          sx={{ ml: 'auto', mr: 0 }}
        />
      </Box>
    </Box>
  )
}

/* ───────── Timeline entry card ───────── */

function TimelineEntryCard({
  entry,
  isOwnTeacher,
  solutionFileName,
  onEditActivity,
  onDeleteFeedback,
  onSelectSubmissionNumber,
  t,
}: {
  entry: TimelineEntry
  isOwnTeacher: boolean
  solutionFileName?: string
  onEditActivity?: (activity: TeacherActivityResp, newFeedbackMd: string | null, notifyStudent: boolean) => void
  onDeleteFeedback?: (activity: TeacherActivityResp) => void
  onSelectSubmissionNumber?: (nr: number) => void
  t: (key: string, opts?: Record<string, unknown>) => string
}) {
  const [inlineExpanded, setInlineExpanded] = useState(false)
  const [editing, setEditing] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)

  const { activity } = entry
  const subNums = [...entry.submissionNumbers].sort((a, b) => a - b)

  return (
    <Paper
      variant="outlined"
      sx={{ p: 1.5, mb: 1.5 }}
    >
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 0.5 }}>
        <Typography variant="caption" fontWeight={500}>
          {entry.teacherName}
        </Typography>
        <Typography variant="caption" color="text.secondary">
          <RelativeTime date={entry.time} />
          {subNums.length > 0 && (
            <>
              {' · '}
              {subNums.map((n, i) => (
                <span key={n}>
                  {i > 0 && ', '}
                  {onSelectSubmissionNumber ? (
                    <Box
                      component="span"
                      onClick={() => onSelectSubmissionNumber(n)}
                      sx={{ cursor: 'pointer', '&:hover': { textDecoration: 'underline', color: 'primary.main' } }}
                    >
                      {t('submission.submissionNr', { nr: n })}
                    </Box>
                  ) : (
                    t('submission.submissionNr', { nr: n })
                  )}
                </span>
              ))}
            </>
          )}
        </Typography>
      </Box>

      {activity && activity.grade != null && (
        <Chip
          label={t('submission.gradedPoints', { points: activity.grade })}
          size="small"
          color="primary"
          variant="outlined"
          sx={{ mb: 0.5, fontWeight: 600, fontSize: '0.7rem', height: 22 }}
        />
      )}

      {activity?.feedback_html && (
        editing ? (
          <EditCommentEditor
            initialText={activity.feedback_md ?? ''}
            onSave={(text, notify) => {
              onEditActivity?.(activity, text.trim() || null, notify)
              setEditing(false)
            }}
            onCancel={() => setEditing(false)}
            t={t}
          />
        ) : (
          <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 0.5 }}>
            <RenderedMarkdown
              sx={{
                flex: 1,
                fontSize: '0.85rem',
              }}
              html={activity.feedback_html}
            />
            {isOwnTeacher && onEditActivity && onDeleteFeedback && (
              <Box sx={{ display: 'flex', gap: 0.25, flexShrink: 0 }}>
                <Tooltip title={t('general.edit')}>
                  <IconButton
                    size="small"
                    onClick={() => setEditing(true)}
                    sx={{ p: 0.25 }}
                  >
                    <EditOutlined sx={{ fontSize: 15, color: 'text.secondary' }} />
                  </IconButton>
                </Tooltip>
                <Tooltip title={t('general.delete')}>
                  <IconButton
                    size="small"
                    onClick={() => setConfirmDelete(true)}
                    sx={{ p: 0.25 }}
                  >
                    <DeleteOutlineOutlined sx={{ fontSize: 15, color: 'text.secondary' }} />
                  </IconButton>
                </Tooltip>
              </Box>
            )}
          </Box>
        )
      )}

      {entry.inlineComments.length > 0 && (
        <Box sx={{ mt: 0.5 }}>
          <Box
            onClick={() => setInlineExpanded((v) => !v)}
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: 0.5,
              cursor: 'pointer',
              userSelect: 'none',
            }}
          >
            <ExpandMoreOutlined
              sx={{
                fontSize: 16,
                color: 'text.secondary',
                transform: inlineExpanded ? 'rotate(0deg)' : 'rotate(-90deg)',
                transition: 'transform 0.2s',
              }}
            />
            <Typography variant="caption" color="text.secondary">
              {t('submission.inlineCommentCount', { count: entry.inlineComments.length })}
            </Typography>
          </Box>
          <Collapse in={inlineExpanded}>
            <Box sx={{ mt: 0.5, pl: 1, borderLeft: 2, borderColor: 'divider' }}>
              {entry.inlineComments.map((c) => (
                <Box key={c.id} sx={{ mb: 1 }}>
                  <ReadOnlyCodeSnippet
                    code={c.code}
                    fileName={solutionFileName}
                    firstLineNumber={c.line_start}
                    maxHeight={80}
                  />
                  <RenderedMarkdown
                    sx={{
                      mt: 0.25,
                      fontSize: '0.8rem',
                    }}
                    html={c.text_html}
                  />
                </Box>
              ))}
            </Box>
          </Collapse>
        </Box>
      )}

      {activity && onDeleteFeedback && (
        <ConfirmDialog
          open={confirmDelete}
          message={t('submission.confirmDeleteComment')}
          confirmLabel={t('general.delete')}
          confirmColor="error"
          onClose={() => setConfirmDelete(false)}
          onConfirm={() => {
            onDeleteFeedback(activity)
            setConfirmDelete(false)
          }}
        />
      )}
    </Paper>
  )
}
