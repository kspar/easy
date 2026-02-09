import { Typography } from '@mui/material'
import { useTranslation } from 'react-i18next'

export default function ExerciseLibraryPage() {
  const { t } = useTranslation()

  return (
    <>
      <Typography variant="h5" gutterBottom>
        {t('library.title')}
      </Typography>
      <Typography color="text.secondary">
        Exercise library will be shown here.
      </Typography>
    </>
  )
}
