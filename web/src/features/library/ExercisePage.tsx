import { useEffect } from 'react'
import { Typography } from '@mui/material'
import { useParams } from 'react-router-dom'
import { useLibraryExercise } from '../../api/library.ts'
import useRecentExercises from '../../hooks/useRecentExercises.ts'

export default function ExercisePage() {
  const { exerciseId } = useParams()
  const { data: exercise } = useLibraryExercise(exerciseId)
  const { addRecent } = useRecentExercises()

  useEffect(() => {
    if (exerciseId && exercise) {
      addRecent(exerciseId, exercise.title)
    }
  }, [exerciseId, exercise?.title]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <>
      <Typography variant="h5" gutterBottom>
        Exercise
      </Typography>
      <Typography color="text.secondary">
        Library exercise {exerciseId} will be shown here.
      </Typography>
    </>
  )
}
