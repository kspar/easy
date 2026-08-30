import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Collapse,
  Divider,
  List,
  ListItemButton,
  Typography,
} from '@mui/material'
import {
  ExpandMoreOutlined,
  PlayArrowOutlined,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { EditorView, placeholder as cmPlaceholder } from '@codemirror/view'
import { EditorState } from '@codemirror/state'
import { basicSetup } from 'codemirror'
import { oneDark } from '@codemirror/theme-one-dark'
import { useTheme } from '@mui/material/styles'
import { languageFromFilename } from './editorLanguage.ts'
import {
  useTeacherAutoassess,
  useTeacherTestSubmissions,
} from '../../api/exercises.ts'
import AutoTestResults from './AutoTestResults.tsx'
import RelativeTime from '../../components/RelativeTime.tsx'
import type {
  GraderType,
  TeacherAutoassessResp,
  TeacherTestSubmissionResp,
} from '../../api/types.ts'
import { errorMessage } from '../../api/errorMessage.ts'

export default function TeacherTestingTab({
  exerciseId,
  solutionFileName,
  graderType,
}: {
  exerciseId: string
  solutionFileName: string
  graderType: GraderType
}) {
  const { t } = useTranslation()
  const theme = useTheme()

  const editorContainerRef = useRef<HTMLDivElement>(null)
  const viewRef = useRef<EditorView | null>(null)

  const autoassess = useTeacherAutoassess(exerciseId)
  const { data: previousSubs } = useTeacherTestSubmissions(exerciseId)

  const [result, setResult] = useState<TeacherAutoassessResp | null>(null)
  const [prevSubsOpen, setPrevSubsOpen] = useState(false)

  // Newest first, and only this teacher's, so [0] is the last thing *you* tested. See
  // ReadLatestTeacherSubmissions.
  const latestSubmission = previousSubs?.[0]
  const latestSolution = latestSubmission?.solution

  /**
   * What the editor should hold, kept outside CodeMirror because the view gets destroyed and
   * rebuilt — on a theme change, a language change, and when the previous attempt arrives.
   *
   * Every test run is already stored server-side per teacher, and wui opened this tab with the
   * last one loaded; this app opened it empty, so returning to a tab meant retyping the solution
   * you had just tested. Null means "nothing typed yet", which is what lets the fetched attempt
   * seed the editor without overwriting work in progress.
   */
  const contentRef = useRef<string | null>(null)

  // A different exercise is a different solution; without this, switching exercises would carry
  // the previous one's text across.
  useEffect(() => {
    contentRef.current = null
  }, [exerciseId])

  // Initialize editor
  useEffect(() => {
    if (!editorContainerRef.current) return
    let cancelled = false

    viewRef.current?.destroy()
    viewRef.current = null

    languageFromFilename(solutionFileName).then((lang) => {
      if (cancelled || !editorContainerRef.current) return

      const extensions = [
        basicSetup,
        lang,
        cmPlaceholder(t('submission.editorPlaceholder')),
        EditorView.lineWrapping,
        EditorView.theme({ '.cm-content': { paddingTop: '4px' } }),
      ]
      if (theme.palette.mode === 'dark') {
        extensions.push(oneDark)
      }

      const state = EditorState.create({
        // Typed text wins over the fetched attempt: contentRef is only null until something is
        // in the editor, so this cannot overwrite a solution being written.
        doc: contentRef.current ?? latestSolution ?? '',
        extensions,
      })

      viewRef.current = new EditorView({
        state,
        parent: editorContainerRef.current,
      })
    })

    return () => {
      cancelled = true
      // Carry the content over the rebuild. Toggling dark mode used to empty this editor
      // silently, which is a bad way to lose a solution you were about to test.
      const text = viewRef.current?.state.doc.toString()
      if (text) contentRef.current = text
      viewRef.current?.destroy()
      viewRef.current = null
    }
  }, [theme.palette.mode, solutionFileName, t, latestSolution])

  const handleRunTests = useCallback(() => {
    const solution = viewRef.current?.state.doc.toString() ?? ''
    if (!solution.trim()) return

    autoassess.mutate(solution, {
      onSuccess: (data) => {
        setResult(data)
      },
    })
  }, [autoassess])

  const handleLoadPreviousSolution = useCallback((sub: TeacherTestSubmissionResp) => {
    const view = viewRef.current
    if (view) {
      view.dispatch({
        changes: { from: 0, to: view.state.doc.length, insert: sub.solution },
      })
      // So a later rebuild keeps the one that was picked, rather than reverting to the newest.
      contentRef.current = sub.solution
    }
    // The result that this solution got, not whatever is currently on screen — leaving the last
    // run's feedback above a different solution is worse than showing none.
    setResult(sub.grade === null ? null : { grade: sub.grade, feedback: sub.feedback })
  }, [])

  const isAutoGraded = graderType === 'AUTO'

  return (
    <Box>
      <Typography variant="subtitle2" sx={{ mb: 1 }}>
        {t('submission.testSolution')}
      </Typography>

      {/* Code editor */}
      <Box sx={{ border: 1, borderColor: 'divider', borderRadius: 1, overflow: 'hidden', mb: 2 }}>
        <Box sx={{
          display: 'flex',
          alignItems: 'center',
          px: 1.5,
          py: 0.5,
          borderBottom: 1,
          borderColor: 'divider',
          bgcolor: theme.palette.mode === 'dark' ? '#282c34' : '#f5f5f5',
        }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontFamily: 'monospace' }}>
            {solutionFileName}
          </Typography>
          {/*
          Says why the editor is not empty. Without it a restored solution looks like something
          the teacher typed and forgot, and "Run tests" would silently retest an old attempt.
          */}
          {latestSubmission && (
            <>
              <Box sx={{ flex: 1 }} />
              <Typography variant="caption" color="text.secondary">
                {t('submission.lastTested')} <RelativeTime date={latestSubmission.created_at} />
              </Typography>
            </>
          )}
        </Box>
        <Box
          ref={editorContainerRef}
          sx={{
            '& .cm-editor': { minHeight: 200, cursor: 'text' },
            '& .cm-focused': { outline: 'none' },
            '& .cm-scroller': { cursor: 'text' },
          }}
        />
      </Box>

      {/* Run tests button */}
      <Button
        variant="contained"
        startIcon={autoassess.isPending ? <CircularProgress size={18} /> : <PlayArrowOutlined />}
        onClick={handleRunTests}
        disabled={autoassess.isPending || !isAutoGraded}
        sx={{ textTransform: 'none' }}
      >
        {t('submission.runTests')}
      </Button>

      {!isAutoGraded && (
        <Typography variant="caption" color="text.secondary" sx={{ ml: 1 }}>
          ({t('exercises.gradedByTeacher')})
        </Typography>
      )}

      {autoassess.isError && (
        <Alert severity="error" sx={{ mt: 2 }}>
          {errorMessage(autoassess.error, t)}
        </Alert>
      )}

      {/* Test results */}
      {result && (
        <>
          <Divider sx={{ my: 2 }} />
          <AutoTestResults
            autoAssessment={{ grade: result.grade, feedback: result.feedback }}
            staggerReveal={false}
          />
        </>
      )}

      {/* Previous test submissions */}
      {previousSubs && previousSubs.length > 0 && (
        <>
          <Divider sx={{ my: 2 }} />
          <Box
            onClick={() => setPrevSubsOpen((v) => !v)}
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
                transform: prevSubsOpen ? 'rotate(0deg)' : 'rotate(-90deg)',
                transition: 'transform 0.2s',
              }}
            />
            <Typography variant="subtitle2" color="text.secondary">
              {t('submission.previousTests')} ({previousSubs.length})
            </Typography>
          </Box>
          <Collapse in={prevSubsOpen}>
            <List disablePadding>
              {previousSubs.map((sub) => (
                <ListItemButton
                  key={sub.id}
                  onClick={() => handleLoadPreviousSolution(sub)}
                  sx={{ borderRadius: 1, py: 0.75, gap: 2 }}
                >
                  <Typography variant="body2" color="text.secondary">
                    <RelativeTime date={sub.created_at} />
                  </Typography>
                  <Box sx={{ flex: 1 }} />
                  {/*
                  No pass/fail colouring: the threshold that decides that belongs to a course, and
                  this tab is also opened from the library where there is no course to ask.
                  */}
                  <Typography variant="body2" color="text.secondary">
                    {sub.grade === null ? t('exercises.notGraded') : `${sub.grade} / 100`}
                  </Typography>
                </ListItemButton>
              ))}
            </List>
          </Collapse>
        </>
      )}

      {previousSubs && previousSubs.length === 0 && (
        <>
          <Divider sx={{ my: 2 }} />
          <Typography variant="caption" color="text.secondary">
            {t('submission.noTestsYet')}
          </Typography>
        </>
      )}
    </Box>
  )
}
