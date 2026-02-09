import { Typography } from '@mui/material'
import { useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'

export default function CourseExercisesPage() {
  const { courseId } = useParams()
  const { t } = useTranslation()

  return (
    <>
      <Typography variant="h5" gutterBottom>
        {t('exercises.title')}
      </Typography>
      <Typography color="text.secondary">
        Exercises for course {courseId} will be listed here.
      </Typography>
    </>
  )
}
