import { Typography, Box, Link, Paper, CircularProgress } from '@mui/material'
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import config from '../../config.ts'
import usePageTitle from '../../hooks/usePageTitle.ts'
import { useStatistics } from '../../api/statistics.ts'
import harnoLogo from '../../assets/sponsors/harno.svg'
import mkmLogo from '../../assets/sponsors/mkm.png'
import itaLogo from '../../assets/sponsors/ita.png'

export default function AboutPage() {
  const { t } = useTranslation()
  usePageTitle(t('nav.about'))
  const stats = useStatistics()

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
      <Typography paragraph>
        {t('about.discord')}{' '}
        <Link href={config.discordInviteUrl} target="_blank" rel="noopener">
          {t('about.discordLink')}
        </Link>
        .
      </Typography>

      <Box display="flex" gap={2} flexWrap="wrap" my={3}>
        <StatCard label={t('about.statsAutograding')} value={stats.inAutoAssessing} isLoading={stats.isLoading} />
        <StatCard label={t('about.statsSubmissions')} value={stats.totalSubmissions} isLoading={stats.isLoading} />
        <StatCard label={t('about.statsAccounts')} value={stats.totalUsers} isLoading={stats.isLoading} />
      </Box>

      <Typography paragraph>{t('about.sponsors')}</Typography>
      <Box display="flex" gap={2} flexWrap="wrap" alignItems="center">
        <Box sx={{ p: 1.5, borderRadius: 1, bgcolor: 'white' }}>
          <img src={harnoLogo} alt="Harno" style={{ height: '3rem', display: 'block' }} />
        </Box>
        <Box sx={{ p: 1.5, borderRadius: 1, bgcolor: 'white' }}>
          <img src={mkmLogo} alt="MKM" style={{ height: '3rem', display: 'block' }} />
        </Box>
        <Box sx={{ p: 1.5, borderRadius: 1, bgcolor: 'white' }}>
          <img src={itaLogo} alt="ITA" style={{ height: '2.5rem', display: 'block' }} />
        </Box>
      </Box>
    </Box>
  )
}

function formatNumber(n: number): string {
  if (n < 10000) return String(n)
  const s = String(n)
  let result = ''
  for (let i = 0; i < s.length; i++) {
    if (i > 0 && (s.length - i) % 3 === 0) result += '\u2009'
    result += s[i]
  }
  return result
}

function StatCard({ label, value, isLoading }: { label: string; value: number; isLoading: boolean }) {
  const [highlight, setHighlight] = useState(false)
  const prevRef = useRef(value)

  useEffect(() => {
    if (prevRef.current !== value && !isLoading) {
      prevRef.current = value
      setHighlight(true)
      const timer = setTimeout(() => setHighlight(false), 600)
      return () => clearTimeout(timer)
    }
  }, [value, isLoading])

  return (
    <Paper
      variant="outlined"
      sx={{
        p: 2,
        textAlign: 'center',
        minWidth: 140,
        flex: 1,
        transition: 'background-color 0.6s ease',
        bgcolor: highlight ? 'action.hover' : 'transparent',
      }}
    >
      {isLoading ? (
        <CircularProgress size={28} />
      ) : (
        <Typography variant="h4" fontWeight="bold">
          {formatNumber(value)}
        </Typography>
      )}
      <Typography variant="body2" color="text.secondary" mt={0.5}>
        {label}
      </Typography>
    </Paper>
  )
}
