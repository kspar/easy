import { Box, Typography } from '@mui/material'
import { useTranslation } from 'react-i18next'

export default function NotFoundPage() {
  const { t } = useTranslation()

  return (
    <Box textAlign="center" py={8}>
      <Typography variant="h4" gutterBottom>
        {t('general.notFoundTitle')}
      </Typography>
      <Typography color="text.secondary">
        {t('general.notFoundMsg')}
      </Typography>
    </Box>
  )
}
