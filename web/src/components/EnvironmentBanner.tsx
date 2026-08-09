import { Box, Typography } from '@mui/material'
import { useTranslation } from 'react-i18next'
import config from '../config.ts'
import { contrastText } from '../environment.ts'

/**
 * A strip saying which environment this is — nothing at all on production, where config.json has
 * no `environment` key (EZ-1733).
 *
 * Sits above the app bar and above the system-message banner, so it is the first thing on the
 * page, and it is **not dismissible**: a permanently dismissed warning is not a warning, and this
 * one exists to be read on the day someone opens the wrong tab, not on the day they first see it.
 *
 * The label carries the meaning on its own — the colour is a second channel, not the only one —
 * and the text colour is computed from that colour so an environment marked in something pale is
 * still readable.
 */
export default function EnvironmentBanner() {
  const { t } = useTranslation()
  const env = config.environment
  if (!env) return null

  return (
    <Box
      // A landmark rather than an alert: it is permanent page furniture, and role="alert" would
      // have a screen reader interrupt whatever it was saying on every single page load.
      role="note"
      sx={{
        bgcolor: env.colour,
        color: contrastText(env.colour),
        px: 2,
        py: 0.4,
        display: 'flex',
        flexWrap: 'wrap',
        alignItems: 'baseline',
        justifyContent: 'center',
        columnGap: 1,
        textAlign: 'center',
      }}
    >
      <Typography variant="caption" sx={{ fontWeight: 700, letterSpacing: '0.12em' }}>
        {env.label}
      </Typography>
      <Typography variant="caption" sx={{ opacity: 0.9 }}>
        {t('environment.notProduction')}
      </Typography>
    </Box>
  )
}
