import { Typography } from '@mui/material'
import { useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'

export default function ParticipantsPage() {
  const { courseId } = useParams()
  const { t } = useTranslation()

  return (
    <>
      <Typography variant="h5" gutterBottom>
        {t('participants.students')}
      </Typography>
      <Typography color="text.secondary">
        Participants for course {courseId} will be managed here.
      </Typography>
    </>
  )
}
