import { useCallback, useEffect, useRef, useState } from 'react'
import { Alert, Box, Button, CircularProgress, Snackbar } from '@mui/material'
import { Send } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { EditorView, placeholder as cmPlaceholder } from '@codemirror/view'
import { EditorState } from '@codemirror/state'
import { python } from '@codemirror/lang-python'
import { oneDark } from '@codemirror/theme-one-dark'
import { basicSetup } from 'codemirror'
import { useTheme } from '@mui/material/styles'
import {
  useSubmitSolution,
  useAwaitAutograde,
  useDraft,
  useSaveDraft,
} from '../../api/exercises.ts'
import type { ExerciseDetails } from '../../api/types.ts'

export default function SubmitTab({
  courseId,
  courseExerciseId,
  exercise,
}: {
  courseId: string
  courseExerciseId: string
  exercise: ExerciseDetails
}) {
  const { t } = useTranslation()
  const theme = useTheme()
  const editorRef = useRef<HTMLDivElement>(null)
  const viewRef = useRef<EditorView | null>(null)
  const [snackMsg, setSnackMsg] = useState<string | null>(null)

  const { data: draft } = useDraft(courseId, courseExerciseId)
  const saveDraft = useSaveDraft(courseId, courseExerciseId)
  const submit = useSubmitSolution(courseId, courseExerciseId)
  const awaitAutograde = useAwaitAutograde(courseId, courseExerciseId)
  const isSubmitting = submit.isPending || awaitAutograde.isPending

  // Initialize CodeMirror
  useEffect(() => {
    if (!editorRef.current || viewRef.current) return

    const extensions = [
      basicSetup,
      python(),
      cmPlaceholder(t('submission.editorPlaceholder')),
      EditorView.lineWrapping,
    ]
    if (theme.palette.mode === 'dark') {
      extensions.push(oneDark)
    }

    const state = EditorState.create({
      doc: draft?.solution ?? '',
      extensions,
    })

    viewRef.current = new EditorView({
      state,
      parent: editorRef.current,
    })

    return () => {
      viewRef.current?.destroy()
      viewRef.current = null
    }
    // Only init once; draft loading handled separately
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Load draft into editor when it arrives (only if editor is empty)
  useEffect(() => {
    if (!viewRef.current || !draft?.solution) return
    const currentDoc = viewRef.current.state.doc.toString()
    if (currentDoc === '' && draft.solution !== '') {
      viewRef.current.dispatch({
        changes: {
          from: 0,
          to: currentDoc.length,
          insert: draft.solution,
        },
      })
    }
  }, [draft])

  const getSolution = useCallback(() => {
    return viewRef.current?.state.doc.toString() ?? ''
  }, [])

  const handleSaveDraft = useCallback(() => {
    const solution = getSolution()
    if (!solution.trim()) return
    saveDraft.mutate(solution)
  }, [getSolution, saveDraft])

  const handleSubmit = useCallback(async () => {
    const solution = getSolution()
    if (!solution.trim()) return

    submit.mutate(solution, {
      onSuccess: () => {
        setSnackMsg(t('submission.submitSuccess'))
        if (exercise.grader_type === 'AUTO') {
          awaitAutograde.mutate()
        }
      },
    })
  }, [getSolution, submit, awaitAutograde, exercise.grader_type, t])

  return (
    <Box>
      {exercise.instructions_html && (
        <Box
          sx={{ mb: 2 }}
          dangerouslySetInnerHTML={{ __html: exercise.instructions_html }}
        />
      )}

      <Box
        ref={editorRef}
        sx={{
          border: 1,
          borderColor: 'divider',
          borderRadius: 1,
          overflow: 'hidden',
          mb: 2,
          '& .cm-editor': { minHeight: 200 },
          '& .cm-focused': { outline: 'none' },
        }}
      />

      <Box sx={{ display: 'flex', gap: 1, alignItems: 'center' }}>
        <Button
          variant="contained"
          startIcon={
            isSubmitting ? <CircularProgress size={18} /> : <Send />
          }
          onClick={handleSubmit}
          disabled={!exercise.is_open || isSubmitting}
        >
          {exercise.grader_type === 'AUTO'
            ? t('submission.submitAndCheck')
            : t('submission.submit')}
        </Button>
        <Button
          variant="outlined"
          onClick={handleSaveDraft}
          disabled={saveDraft.isPending}
        >
          {saveDraft.isPending ? t('general.saving') : t('general.save')}
        </Button>
      </Box>

      {submit.isError && (
        <Alert severity="error" sx={{ mt: 2 }}>
          {t('general.somethingWentWrong')}
        </Alert>
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