import { useEffect } from 'react'
import { Box, Link, Typography } from '@mui/material'
import { useTranslation } from 'react-i18next'
import config from '../../config.ts'

/**
 * `/tos` — sends the visitor to the terms of service document.
 *
 * A redirect rather than a link, because this URL is a **stable public address for the terms**, and
 * something outside this app depends on it: Keycloak's own terms-of-service link points here, so the
 * document URL is written down once (in `config.ts`) instead of in both the app and the IdP's realm
 * settings. The WUI did the same thing for the same reason, and its comment said so — "IdP is pointed
 * here for ToS, so we have one source of truth".
 *
 * No authentication: someone reading the terms before they have an account is the normal case.
 */
export default function TermsRedirect() {
  const { t } = useTranslation()

  useEffect(() => {
    // `replace`, not `href`. With a history entry, pressing Back from the document returns to /tos,
    // which redirects again — the visitor is trapped. Replacing means Back reaches the page they came
    // from. (The WUI used `href` and had exactly that loop.)
    window.location.replace(config.tosUrl)
  }, [])

  // Briefly visible at most, and the only thing on screen if the redirect is blocked — so it says
  // where it is going and offers the link by hand rather than being an empty white page.
  return (
    <Box sx={{ p: 4 }}>
      <Typography variant="body2" color="text.secondary">
        {t('terms.redirecting')}{' '}
        <Link href={config.tosUrl} rel="noopener">
          {t('terms.openManually')}
        </Link>
      </Typography>
    </Box>
  )
}
