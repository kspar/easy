import { useState } from 'react'
import {
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  TextField,
  Typography,
} from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { useCreateExercise, useAddExerciseToCourse } from '../../api/library.ts'

/**
 * WUI parity: create a brand new library exercise and attach it to this course
 * in one step, then open it. The exercise lands in the library root — the
 * teacher can move it later.
 */
export default function NewCourseExerciseDialog({
  courseId,
  open,
  onClose,
}: {
  courseId: string
  open: boolean
  onClose: () => void
}) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const createExercise = useCreateExercise()
  const addToCourse = useAddExerciseToCourse()

  const [title, setTitle] = useState('')
  const [error, setError] = useState(false)

  const isPending = createExercise.isPending || addToCourse.isPending

  async function handleCreate() {
    if (!title.trim() || isPending) return
    setError(false)
    try {
      const { id: exerciseId } = await createExercise.mutateAsync({
        title: title.trim(),
        parent_dir_id: null,
        public: true,
        grader_type: 'TEACHER',
        solution_file_name: 'lahendus.py',
        solution_file_type: 'TEXT_EDITOR',
        anonymous_autoassess_enabled: false,
      })
      const { id: courseExerciseId } = await addToCourse.mutateAsync({
        courseId,
        exerciseId,
      })
      await queryClient.invalidateQueries({
        queryKey: ['teacher', 'courses', courseId, 'exercises'],
      })
      handleClose()
      navigate(`/courses/${courseId}/exercises/${courseExerciseId}`)
    } catch {
      setError(true)
    }
  }

  function handleClose() {
    setTitle('')
    setError(false)
    onClose()
  }

  return (
    <Dialog
      open={open}
      onClose={handleClose}
      maxWidth="xs"
      fullWidth
      TransitionProps={{
        onEntered: (node) => {
          (node as HTMLElement).querySelector('input')?.focus()
        },
      }}
    >
      <DialogTitle>{t('exercises.newExercise')}</DialogTitle>
      <DialogContent sx={{ pt: '8px !important' }}>
        <TextField
          fullWidth
          autoFocus
          label={t('library.exerciseTitle')}
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          inputProps={{ maxLength: 100 }}
          onKeyDown={(e) => {
            if (e.key === 'Enter') handleCreate()
          }}
        />
        {error && (
          <Typography variant="body2" color="error" sx={{ mt: 1.5 }}>
            {t('general.somethingWentWrong')}
          </Typography>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose}>{t('general.cancel')}</Button>
        <Button
          onClick={handleCreate}
          variant="contained"
          disabled={isPending || !title.trim()}
        >
          {isPending ? t('general.adding') : t('general.add')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
