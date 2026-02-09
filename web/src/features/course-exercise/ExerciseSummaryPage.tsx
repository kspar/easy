import { Typography } from '@mui/material'
import { useParams } from 'react-router-dom'

export default function ExerciseSummaryPage() {
  const { courseId, courseExerciseId } = useParams()

  return (
    <>
      <Typography variant="h5" gutterBottom>
        Exercise
      </Typography>
      <Typography color="text.secondary">
        Exercise {courseExerciseId} on course {courseId} will be shown here.
      </Typography>
    </>
  )
}
