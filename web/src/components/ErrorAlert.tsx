import { useState } from 'react'
import { Alert, Button, Snackbar, type SxProps, type Theme } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useLocation } from 'react-router-dom'
import BugReportDialog from '../features/bug-report/BugReportDialog.tsx'

/**
 * The generic "something went wrong" alert, with the one thing the old copy lacked: somewhere to go.
 *
 * The string used to end "please contact an administrator", which named no administrator and gave
 * no way to reach one — a dead end repeated on a dozen pages. The reporter it now offers is the
 * same dialog the crash screen opens, and it arrives with the reporter's recent activity already
 * attached, so a person who clicks it has done more for the bug than one who sends an email.
 *
 * Not used by [BugReportDialog] itself, for what should be obvious reasons.
 */
export default function ErrorAlert({ sx }: { sx?: SxProps<Theme> }) {
  const { t } = useTranslation()
  const location = useLocation()
  const [reporting, setReporting] = useState(false)
  const [sent, setSent] = useState<string | null>(null)

  return (
    <>
      <Alert
        severity="error"
        sx={sx}
        action={
          <Button color="inherit" size="small" onClick={() => setReporting(true)}>
            {t('general.reportIt')}
          </Button>
        }
      >
        {t('general.somethingWentWrong')}
      </Alert>

      {/* Mounted only while open, so each opening starts from a fresh activity snapshot. */}
      {reporting && (
        <BugReportDialog
          open
          onClose={() => setReporting(false)}
          onSuccess={setSent}
          pageUrl={location.pathname + location.search}
        />
      )}

      <Snackbar
        open={sent !== null}
        autoHideDuration={4000}
        onClose={() => setSent(null)}
        message={sent}
      />
    </>
  )
}
