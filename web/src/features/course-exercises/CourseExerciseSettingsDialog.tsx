import { Backdrop, CircularProgress } from '@mui/material'
import { useTeacherExerciseDetails } from '../../api/exercises.ts'
import ExerciseSettingsDialog from '../course-exercise/ExerciseSettingsDialog.tsx'

/**
 * The settings dialog needs the full exercise detail (thresholds, exceptions),
 * which the list response doesn't carry — so fetch it on open and only then
 * mount the dialog.
 */
export default function CourseExerciseSettingsDialog({
  courseId,
  courseExerciseId,
  onClose,
}: {
  courseId: string
  courseExerciseId: string
  onClose: () => void
}) {
  const { data: exercise, isLoading } = useTeacherExerciseDetails(
    courseId,
    courseExerciseId,
  )

  if (isLoading || !exercise) {
    return (
      <Backdrop open sx={{ zIndex: (theme) => theme.zIndex.modal }}>
        <CircularProgress color="inherit" />
      </Backdrop>
    )
  }

  return (
    <ExerciseSettingsDialog
      courseId={courseId}
      courseExerciseId={courseExerciseId}
      exercise={exercise}
      open
      onClose={onClose}
    />
  )
}
