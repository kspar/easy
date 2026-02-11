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
import { useQueryClient } from '@tanstack/react-query'
import {
  useSubmitSolution,
  useAwaitAutograde,
} from '../../api/exercises.ts'
import type { ExerciseDetails } from '../../api/types.ts'

export default function SubmitTab({
  courseId,
  courseExerciseId,
  exercise,
  onSubmitted,
}: {
  courseId: string
  courseExerciseId: string
  exercise: ExerciseDetails
  onSubmitted?: () => void
}) {
  const { t } = useTranslation()
  const theme = useTheme()
  const queryClient = useQueryClient()
  const editorRef = useRef<HTMLDivElement>(null)
  const viewRef = useRef<EditorView | null>(null)
  const [snackMsg, setSnackMsg] = useState<string | null>(null)

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
      doc: '',
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const getSolution = useCallback(() => {
    return viewRef.current?.state.doc.toString() ?? ''
  }, [])

  const refetchAfterSubmit = useCallback(() => {
    queryClient.refetchQueries({
      queryKey: ['student', 'courses', courseId, 'exercises', courseExerciseId, 'submissions'],
    })
    queryClient.refetchQueries({
      queryKey: ['student', 'courses', courseId, 'exercises'],
    })
  }, [queryClient, courseId, courseExerciseId])

  const handleSubmit = useCallback(() => {
    const solution = getSolution()
    if (!solution.trim()) return

    submit.mutate(solution, {
      onSuccess: () => {
        setSnackMsg(t('submission.submitSuccess'))
        refetchAfterSubmit()
        if (exercise.grader_type === 'AUTO') {
          awaitAutograde.mutate()
        } else {
          onSubmitted?.()
        }
      },
    })
  }, [getSolution, submit, awaitAutograde, exercise.grader_type, t, onSubmitted, refetchAfterSubmit])

  // Switch to submissions tab after autograde completes
  useEffect(() => {
    if (awaitAutograde.isSuccess) {
      refetchAfterSubmit()
      onSubmitted?.()
    }
  }, [awaitAutograde.isSuccess, onSubmitted, refetchAfterSubmit])

  return (
    <Box>
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
