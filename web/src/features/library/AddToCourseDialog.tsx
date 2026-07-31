import { useMemo, useState } from 'react'
import {
  Autocomplete,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material'
import { CloseOutlined, PlaylistAddCheckOutlined } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useTeacherCourses } from '../../api/courses.ts'
import { useTeacherCourseExercises } from '../../api/exercises.ts'
import { useAddExerciseToCourse } from '../../api/library.ts'
import type { TeacherCourse } from '../../api/types.ts'

export default function AddToCourseDialog({
  exercises: initialExercises,
  open,
  onClose,
  onSuccess,
}: {
  exercises: { id: string; title: string }[]
  open: boolean
  onClose: () => void
  onSuccess: (msg: string, navigateTo?: string) => void
}) {
  const { t } = useTranslation()
  const { data: courses } = useTeacherCourses()
  const addToCourse = useAddExerciseToCourse()
  const [selected, setSelected] = useState<TeacherCourse | null>(null)
  const [pending, setPending] = useState(false)
  const [removedIds, setRemovedIds] = useState<Set<string>>(new Set())

  const exercises = useMemo(
    () => initialExercises.filter((ex) => !removedIds.has(ex.id)),
    [initialExercises, removedIds],
  )

  const { data: courseExercises } = useTeacherCourseExercises(selected?.id)

  const duplicateIds = useMemo(() => {
    if (!courseExercises) return new Set<string>()
    return new Set(courseExercises.map((ex) => ex.exercise_id))
  }, [courseExercises])

  function handleRemove(id: string) {
    setRemovedIds((prev) => new Set(prev).add(id))
  }

  async function handleAdd() {
    if (!selected || exercises.length === 0) return
    setPending(true)
    const results = await Promise.allSettled(
      exercises.map((ex) =>
        addToCourse.mutateAsync({ courseId: selected.id, exerciseId: ex.id }),
      ),
    )
    setPending(false)
    const courseId = selected.id
    setSelected(null)
    setRemovedIds(new Set())
    onClose()

    if (exercises.length === 1 && results[0].status === 'fulfilled') {
      const courseExerciseId = results[0].value.id
      onSuccess(
        t('library.addedToCourse'),
        `/courses/${courseId}/exercises/${courseExerciseId}`,
      )
    } else {
      onSuccess(t('library.addedToCourse_other', { count: exercises.length }))
    }
  }

  function handleClose() {
    setSelected(null)
    setRemovedIds(new Set())
    onClose()
  }

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="xs" fullWidth>
      <DialogTitle>{t('library.addToCourse')}</DialogTitle>
      <DialogContent sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: '8px !important' }}>
        <Box sx={{ bgcolor: 'action.hover', borderRadius: 1, px: 1.5, py: 0.5, maxHeight: 500, overflow: 'auto' }}>
          {exercises.map((ex) => {
            const isDuplicate = duplicateIds.has(ex.id)
            return (
              <Box
                key={ex.id}
                sx={{ display: 'flex', alignItems: 'center', gap: 0.5, py: 0.25 }}
              >
                <Typography variant="body2" sx={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {ex.title}
                </Typography>
                {isDuplicate && (
                  <Tooltip title={t('library.alreadyOnCourse')} arrow>
                    <PlaylistAddCheckOutlined sx={{ fontSize: 18, color: 'warning.main', flexShrink: 0 }} />
                  </Tooltip>
                )}
                {initialExercises.length > 1 && (
                  <IconButton size="small" onClick={() => handleRemove(ex.id)} sx={{ p: 0.25, flexShrink: 0 }}>
                    <CloseOutlined sx={{ fontSize: 16 }} />
                  </IconButton>
                )}
              </Box>
            )
          })}
        </Box>
        {exercises.some((ex) => duplicateIds.has(ex.id)) && (
          <Typography variant="caption" color="warning.main">
            {exercises.length === 1
              ? t('library.oneAlreadyOnCourse')
              : t('library.someAlreadyOnCourse')}
          </Typography>
        )}
        <Autocomplete
          options={courses ?? []}
          getOptionLabel={(c) => c.alias || c.title}
          value={selected}
          onChange={(_, v) => setSelected(v)}
          renderInput={(params) => (
            <TextField {...params} label={t('library.selectCourse')} autoFocus />
          )}
        />
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose}>{t('general.cancel')}</Button>
        <Button
          onClick={handleAdd}
          variant="contained"
          disabled={pending || !selected || exercises.length === 0}
        >
          {pending ? t('general.adding') : t('general.add')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
