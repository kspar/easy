import { Typography, Box, Link } from '@mui/material'
import { useTranslation } from 'react-i18next'
import config from '../../config.ts'

export default function AboutPage() {
  const { t } = useTranslation()

  return (
    <Box py={4} maxWidth={600}>
      <Typography variant="h5" gutterBottom>
        Lahendus
      </Typography>
      <Typography paragraph>
        {t('about.s1')}{' '}
        <Link href="https://cs.ut.ee" target="_blank" rel="noopener">
          {t('about.s2')}
        </Link>
        .
      </Typography>
      <Typography paragraph>
        {t('about.s3')}{' '}
        <Link href={config.repoUrl} target="_blank" rel="noopener">
          easy
        </Link>
        .
      </Typography>
      <Typography paragraph>{t('about.sponsors')}</Typography>
    </Box>
  )
}
