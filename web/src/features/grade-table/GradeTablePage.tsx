import { Typography } from '@mui/material'
import { useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'

export default function GradeTablePage() {
  const { courseId } = useParams()
  const { t } = useTranslation()

  return (
    <>
      <Typography variant="h5" gutterBottom>
        {t('grades.title')}
      </Typography>
      <Typography color="text.secondary">
        Grade table for course {courseId} will be shown here.
      </Typography>
    </>
  )
}
