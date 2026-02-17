import { useCallback, useEffect, useImperativeHandle, useRef, useState, forwardRef } from 'react'
import { Alert, Box, Button, CircularProgress, IconButton, Snackbar, Tooltip, Typography } from '@mui/material'
import { SendOutlined, FileUploadOutlined, FileDownloadOutlined } from '@mui/icons-material'
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

export interface SubmitTabHandle {
  setSolution: (solution: string) => void
}

export default forwardRef<SubmitTabHandle, {
  courseId: string
  courseExerciseId: string
  exercise: ExerciseDetails
  initialSolution?: string
  onSubmitted?: () => void
}>(function SubmitTab({
  courseId,
  courseExerciseId,
  exercise,
  initialSolution,
  onSubmitted,
}, ref) {
  const { t } = useTranslation()
  const theme = useTheme()
  const queryClient = useQueryClient()
  const editorRef = useRef<HTMLDivElement>(null)
  const viewRef = useRef<EditorView | null>(null)
  const [snackMsg, setSnackMsg] = useState<string | null>(null)

  const submit = useSubmitSolution(courseId, courseExerciseId)
  const awaitAutograde = useAwaitAutograde(courseId, courseExerciseId)
  const isSubmitting = submit.isPending || awaitAutograde.isPending

  // Initialize CodeMirror (re-creates on theme change)
  useEffect(() => {
    if (!editorRef.current) return

    const prevDoc = viewRef.current?.state.doc.toString()
    viewRef.current?.destroy()

    const extensions = [
      basicSetup,
      python(),
      cmPlaceholder(t('submission.editorPlaceholder')),
      EditorView.lineWrapping,
      EditorView.theme({ '.cm-content': { paddingTop: '4px' } }),
    ]
    if (theme.palette.mode === 'dark') {
      extensions.push(oneDark)
    }

    const state = EditorState.create({
      doc: prevDoc ?? initialSolution ?? '',
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
  }, [theme.palette.mode])

  useImperativeHandle(ref, () => ({
    setSolution: (solution: string) => {
      const view = viewRef.current
      if (view) {
        view.dispatch({
          changes: { from: 0, to: view.state.doc.length, insert: solution },
        })
      }
    },
  }))

  const getSolution = useCallback(() => {
    return viewRef.current?.state.doc.toString() ?? ''
  }, [])

  const handleDownload = useCallback(() => {
    const solution = getSolution()
    const blob = new Blob([solution], { type: 'text/plain' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${courseExerciseId}_${Date.now()}_${exercise.solution_file_name}`
    a.click()
    URL.revokeObjectURL(url)
  }, [getSolution, courseExerciseId, exercise.solution_file_name])

  const handleUpload = useCallback(() => {
    const input = document.createElement('input')
    input.type = 'file'
    input.onchange = () => {
      const file = input.files?.[0]
      if (!file) return
      if (file.size > 300_000) {
        setSnackMsg(t('submission.uploadErrorTooLarge'))
        return
      }
      const reader = new FileReader()
      reader.onload = () => {
        try {
          const text = new TextDecoder('utf-8', { fatal: true }).decode(reader.result as ArrayBuffer)
          const view = viewRef.current
          if (view) {
            view.dispatch({
              changes: { from: 0, to: view.state.doc.length, insert: text },
            })
          }
        } catch {
          setSnackMsg(t('submission.uploadErrorNotText'))
        }
      }
      reader.readAsArrayBuffer(file)
    }
    input.click()
  }, [t])

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
            {exercise.solution_file_name}
          </Typography>
          <Box sx={{ flex: 1 }} />
          {exercise.is_open && (
            <Tooltip title={t('submission.uploadFile')}>
              <IconButton onClick={handleUpload} size="small">
                <FileUploadOutlined fontSize="small" />
              </IconButton>
            </Tooltip>
          )}
          <Tooltip title={t('submission.saveAsFile')}>
            <IconButton onClick={handleDownload} size="small">
              <FileDownloadOutlined fontSize="small" />
            </IconButton>
          </Tooltip>
        </Box>
        <Box
          ref={editorRef}
          sx={{
            '& .cm-editor': { minHeight: 200 },
            '& .cm-focused': { outline: 'none' },
          }}
        />
      </Box>

      <Box sx={{ display: 'flex', gap: 1, alignItems: 'center' }}>
        <Button
          variant="contained"
          startIcon={
            isSubmitting ? <CircularProgress size={18} /> : <SendOutlined />
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
})
