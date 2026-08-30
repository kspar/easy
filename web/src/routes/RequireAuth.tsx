import { useEffect, useRef, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../auth/useAuth.ts'
import type { Role } from '../auth/auth-context.ts'
import { Navigate } from 'react-router-dom'
import { Alert, Box, Button, CircularProgress, Typography } from '@mui/material'
import ErrorAlert from '../components/ErrorAlert.tsx'

interface Props {
  children: ReactNode
  allowedRoles?: Role[]
}

function Loading({ message }: { message: string }) {
  return (
    <Box
      display="flex"
      flexDirection="column"
      alignItems="center"
      justifyContent="center"
      minHeight="60vh"
      gap={2}
    >
      <CircularProgress />
      <Typography variant="body2" color="text.secondary">
        {message}
      </Typography>
    </Box>
  )
}

/**
 * Shown when `keycloak.init()` failed, in place of the redirect that used to happen instead.
 *
 * Refresh rather than "log in", and that is the point: the IdP is what just failed, so a button
 * that goes there is a button that reproduces the problem. Reloading re-runs `init()`, which is
 * the actual recovery for the transient network or IdP hiccup that causes this — and it is what
 * a person would have had to work out for themselves before, from a blank page.
 */
function AuthUnavailable() {
  const { t } = useTranslation()
  return (
    <Box display="flex" justifyContent="center" minHeight="60vh" pt={8} px={2}>
      <Alert
        severity="error"
        sx={{ maxWidth: 480 }}
        action={
          <Button color="inherit" size="small" onClick={() => window.location.reload()}>
            {t('general.refresh')}
          </Button>
        }
      >
        {/* Deliberately not offering the bug reporter that ErrorAlert carries: filing a report is
            itself a call to core, and this is the screen for "the identity provider is the thing
            that is unreachable". */}
        {t('auth.unavailable')}
      </Alert>
    </Box>
  )
}

export default function RequireAuth({ children, allowedRoles }: Props) {
  const { t } = useTranslation()
  const {
    initialized,
    initFailed,
    authFailed,
    authenticated,
    activeRole,
    login,
    checkedIn,
    checkinFailed,
    checkinError,
  } = useAuth()

  /**
   * The redirect to the IdP, in an effect and behind a ref rather than in the render body where it
   * used to sit.
   *
   * Rendering is not allowed to have side effects, and this side effect is a navigation. React
   * invokes render twice under StrictMode and may re-run it whenever this subtree re-renders, so
   * the inline version fired `login()` several times for one arrival — each call minting a fresh
   * `state`, storing it, and racing the others to set `location`. One arrival, one redirect.
   */
  const redirectingRef = useRef(false)
  useEffect(() => {
    if (!initialized || initFailed || authFailed || authenticated || redirectingRef.current) return
    redirectingRef.current = true
    login()
  }, [initialized, initFailed, authFailed, authenticated, login])

  if (initFailed) {
    return <AuthUnavailable />
  }

  // Signed in, but the session cannot be used — an `easy_role` claim this app does not recognise,
  // today. The same alert as a failed checkin, for the same reason: the account exists at the IdP
  // and something about it needs a human, so the way forward is the bug reporter rather than
  // another trip through the login screen, which would hand back the identical token.
  if (authFailed) {
    return <ErrorAlert />
  }

  // Still loading, or already on the way to the IdP — the redirect above is a page navigation, so
  // this is what is on screen for the moment it takes. A spinner rather than the `null` that used
  // to be here: a blank page is what a broken app looks like.
  if (!initialized || !authenticated) {
    return <Loading message={t('general.loading')} />
  }

  if (checkinFailed) {
    return <ErrorAlert error={checkinError} />
  }

  // Nothing else can be requested before the account exists in core
  if (!checkedIn) {
    return <Loading message={t('general.loading')} />
  }

  if (allowedRoles && !allowedRoles.includes(activeRole)) {
    // The relocation is right; the silence was not (audit X-033). A student opening a link their
    // teacher shared watched the spinner for several seconds and then arrived somewhere else, with
    // nothing on the page saying why — so the link reads as broken and the natural next move is to
    // click it again. The flag is read once by the shell, which shows a sentence and clears it.
    return <Navigate to="/courses" replace state={{ roleDenied: true }} />
  }

  return <>{children}</>
}
