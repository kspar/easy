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
import type { GraderType, TeacherAutoassessResp } from '../../api/types.ts'

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
        doc: '',
        extensions,
      })

      viewRef.current = new EditorView({
        state,
        parent: editorContainerRef.current,
      })
    })

    return () => {
      cancelled = true
      viewRef.current?.destroy()
      viewRef.current = null
    }
  }, [theme.palette.mode, solutionFileName, t])

  const handleRunTests = useCallback(() => {
    const solution = viewRef.current?.state.doc.toString() ?? ''
    if (!solution.trim()) return

    autoassess.mutate(solution, {
      onSuccess: (data) => {
        setResult(data)
      },
    })
  }, [autoassess])

  const handleLoadPreviousSolution = useCallback((solution: string) => {
    const view = viewRef.current
    if (view) {
      view.dispatch({
        changes: { from: 0, to: view.state.doc.length, insert: solution },
      })
    }
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
          {t('general.somethingWentWrong')}
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
                  onClick={() => handleLoadPreviousSolution(sub.solution)}
                  sx={{ borderRadius: 1, py: 0.75 }}
                >
                  <Typography variant="body2" color="text.secondary">
                    <RelativeTime date={sub.created_at} />
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
