import { useState } from 'react'
import { Box, Button, Stack, Typography } from '@mui/material'
import { useTranslation } from 'react-i18next'
import BugReportDialog from '../features/bug-report/BugReportDialog.tsx'

/**
 * What [ErrorBoundary] shows in place of the page it could not draw.
 *
 * A separate file from the boundary itself, and not by preference: `react-refresh` refuses a module
 * that exports a class component alongside a function one, and the boundary has to be a class
 * because `getDerivedStateFromError` and `componentDidCatch` have no hook equivalent. Same split,
 * same reason, as the `auth-context` / `AuthContext` / `useAuth` trio.
 *
 * Translated, unlike React Router's default error screen that this replaces — and if i18n is itself
 * the thing that broke, `useTranslation` hands back the key, which is still more use than a blank
 * page.
 */
export default function CrashScreen({ error }: { error: Error }) {
  const { t } = useTranslation()
  const [reporting, setReporting] = useState(false)

  return (
    <Box sx={{ maxWidth: '40rem', mx: 'auto', mt: 8, px: 3 }}>
      <Typography variant="h5" gutterBottom>
        {t('bugReport.crashTitle')}
      </Typography>
      <Typography sx={{ mb: 3 }}>{t('bugReport.crashBody')}</Typography>
      <Stack direction="row" spacing={1}>
        {/*
          Reporting first, reloading second, and that order is the decision. A reload makes the
          symptom go away and takes the reporter with it — the page comes back working-ish, they get
          on with their day, and the crash is never recorded. Recovery is still one click away.
        */}
        <Button variant="contained" onClick={() => setReporting(true)}>
          {t('bugReport.reportThis')}
        </Button>
        <Button onClick={() => window.location.reload()}>{t('bugReport.reloadPage')}</Button>
      </Stack>

      {reporting && (
        <BugReportDialog
          open
          onClose={() => setReporting(false)}
          // A reload, because there is no app underneath to show a snackbar in — the page behind
          // this dialog is a crash screen. Reloading is both the acknowledgement and the recovery.
          onSuccess={() => window.location.reload()}
          // From `window`, not `useLocation`: the boundary sits outside the router, so there is no
          // router context to read here.
          pageUrl={window.location.pathname + window.location.search}
          // Prefilled with what actually happened, so a reporter with nothing to add can just send
          // it. Every word of it is editable.
          initialMessage={t('bugReport.crashPrefill', { message: error.message })}
        />
      )}
    </Box>
  )
}
