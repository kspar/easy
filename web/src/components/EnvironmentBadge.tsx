import { Box, Tooltip } from '@mui/material'
import { useTranslation } from 'react-i18next'
import config from '../config.ts'
import { contrastText } from '../environment.ts'

/**
 * A small badge beside the wordmark saying which environment this is — nothing at all on
 * production, where config.json has no `environment` key (EZ-1733).
 *
 * This replaced a full-width banner strip. The banner was louder, but it was loud on every page
 * forever, and the thing it protects against is a *glance* — which tab am I in — not a failure to
 * read. Beside the logo it lands in the same corner the eye already goes to for "which application
 * is this", and costs no vertical space on a page someone is trying to work in.
 *
 * The label still carries the meaning without the colour, and the tooltip carries the warning the
 * banner used to spell out. The tab title and favicon (see `environment.ts`) are unchanged and
 * remain the signals that work when the page is not even visible.
 */
export default function EnvironmentBadge({ compact = false }: { compact?: boolean }) {
  const { t } = useTranslation()
  const env = config.environment
  if (!env) return null

  return (
    <Tooltip title={t('environment.notProduction')}>
      <Box
        component="span"
        // Not aria-hidden, and not decorative: for a screen reader this is the only signal in the
        // page body that says which deployment this is.
        aria-label={t('environment.notProduction')}
        sx={{
          bgcolor: env.colour,
          color: contrastText(env.colour),
          fontSize: compact ? '0.55rem' : '0.6rem',
          fontWeight: 700,
          letterSpacing: '0.08em',
          lineHeight: 1,
          px: 0.6,
          py: 0.35,
          borderRadius: 0.75,
          whiteSpace: 'nowrap',
          // Sits with the wordmark rather than on the text baseline, which for a cap-height
          // uppercase label reads as floating.
          alignSelf: 'center',
          cursor: 'default',
        }}
      >
        {env.label}
      </Box>
    </Tooltip>
  )
}
