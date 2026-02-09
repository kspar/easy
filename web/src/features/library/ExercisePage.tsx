import { Typography } from '@mui/material'
import { useParams } from 'react-router-dom'

export default function ExercisePage() {
  const { exerciseId } = useParams()

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
