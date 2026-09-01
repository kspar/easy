import { useState } from 'react'
import { Alert, Box, Button, Snackbar, type SxProps, type Theme } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useLocation } from 'react-router-dom'
import BugReportDialog from '../features/bug-report/BugReportDialog.tsx'
import { errorMessage, isAccessError } from '../api/errorMessage.ts'

/**
 * The generic "something went wrong" alert, with the one thing the old copy lacked: somewhere to go.
 *
 * The string used to end "please contact an administrator", which named no administrator and gave
 * no way to reach one — a dead end repeated on a dozen pages. The reporter it now offers is the
 * same dialog the crash screen opens, and it arrives with the reporter's recent activity already
 * attached, so a person who clicks it has done more for the bug than one who sends an email.
 *
 * **Except on a refusal.** `isAccessError` splits "you may not" off from "it broke", and the first
 * gets pointed at the course organiser rather than at us (EZ-1861). Offering the reporter to a
 * student who is simply not enrolled is how EZ-1858 arrived: an accurate message, a button beside
 * it, and an enrolment question routed into the bug tracker.
 *
 * Not used by [BugReportDialog] itself, for what should be obvious reasons.
 *
 * Pass `error` wherever one is in hand. Core answers failures with a typed code and until X-035
 * nothing read it, so a course name that was taken and a server that was down produced the same
 * sentence. With the error, this says which; without it, it says what it always said. The reporter
 * stays either way — a named cause is still a cause worth reporting.
 */
export default function ErrorAlert({ sx, error }: { sx?: SxProps<Theme>; error?: unknown }) {
  const { t } = useTranslation()
  const location = useLocation()
  const [reporting, setReporting] = useState(false)
  const [sent, setSent] = useState<string | null>(null)

  // A refusal is not a defect, so it gets a person to ask instead of a bug reporter (EZ-1861).
  const refused = isAccessError(error)

  return (
    <>
      <Alert
        severity="error"
        sx={sx}
        action={
          refused ? undefined : (
            <Button color="inherit" size="small" onClick={() => setReporting(true)}>
              {t('general.reportIt')}
            </Button>
          )
        }
      >
        {error === undefined ? t('general.somethingWentWrong') : errorMessage(error, t)}
        {refused && (
          <Box component="span" display="block" mt={0.5}>
            {t('general.askCourseOrganiser')}
          </Box>
        )}
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
