import { useState } from 'react'
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Alert,
  Box,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  FormControlLabel,
  TextField,
} from '@mui/material'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import { useTranslation } from 'react-i18next'
import { useCreateBugReport } from '../../api/bugReports.ts'
import { ApiResponseError } from '../../api/client.ts'
import { runningBuild } from '../../api/webVersion.ts'
import { clearBreadcrumbs, serialiseBreadcrumbs } from './breadcrumbs.ts'

/** Matches the server's `@Size(max = 5000)`, so the limit is felt in the field rather than as a 400. */
const MAX_MESSAGE = 5000

/**
 * One text box, one checkbox, and the full text of what will be sent.
 *
 * The disclosure is not decoration. A checkbox saying "include my recent activity" next to a
 * *description* of that activity is not informed consent — it asks someone to agree to something
 * they cannot see. So the expander holds the exact string the request carries, produced by the same
 * function, and if the two ever diverge the fix is to make them the same again rather than to
 * summarise better.
 *
 * Pre-checked, and that is a judgement call worth naming: a strict reading of opt-in starts it
 * unticked, and the result is reports with no diagnostics at all — which is the situation this
 * feature exists to replace. Ticked-with-full-disclosure gets useful reports while leaving the
 * decision genuinely theirs, and unticking it means the column is stored as null rather than empty.
 */
export default function BugReportDialog({
  open,
  onClose,
  onSuccess,
  pageUrl,
  initialMessage = '',
}: {
  open: boolean
  onClose: () => void
  onSuccess: (msg: string) => void
  /**
   * Where the reporter was, passed in rather than read from `useLocation`.
   *
   * Because one of the two callers has no router to read. `ErrorBoundary` sits *outside*
   * `RouterProvider` — it has to, or a throw while the router itself is rendering escapes it — and
   * a `useLocation` in here would make this component unusable from the one place a bug report is
   * most likely to be filed.
   */
  pageUrl: string
  /** Prefilled when the dialog is opened from a crash the ErrorBoundary caught. */
  initialMessage?: string
}) {
  const { t } = useTranslation()
  const createReport = useCreateBugReport()

  const [message, setMessage] = useState(initialMessage)
  const [includeActivity, setIncludeActivity] = useState(true)

  /**
   * Snapshotted once, at mount, via the lazy initialiser.
   *
   * It has to be a snapshot rather than a live read: the buffer keeps growing underneath — this
   * dialog's own renders can add to it — and a preview that shifted between being read and being
   * sent would break the one promise the disclosure makes.
   *
   * Mount, rather than an effect on `open`, because **the callers mount this only while it is
   * open.** That is what keeps the component free of the reset-everything-on-open effect an
   * always-mounted dialog would need, along with the `set-state-in-effect` warning that comes with
   * one. The cost is the exit transition, which does not play on an unmount.
   */
  const [diagnostics] = useState(serialiseBreadcrumbs)

  const rateLimited =
    createReport.error instanceof ApiResponseError &&
    createReport.error.errorBody?.code === 'BUG_REPORT_RATE_LIMITED'

  function handleSend() {
    if (!message.trim()) return
    createReport.mutate(
      {
        message: message.trim(),
        // Omitted rather than empty when declined — see BugReportDraft.
        ...(includeActivity && diagnostics ? { diagnostics } : {}),
        page_url: pageUrl,
        web_version: `${runningBuild.version} (${runningBuild.commit})`,
        user_agent: navigator.userAgent,
      },
      {
        onSuccess: () => {
          // So a second report in the same session describes what happened after this one, rather
          // than re-sending half an hour the reporter has already told us about.
          clearBreadcrumbs()
          setMessage('')
          onClose()
          onSuccess(t('bugReport.thanks'))
        },
      },
    )
  }

  function handleClose() {
    if (createReport.isPending) return
    onClose()
  }

  return (
    <Dialog
      open={open}
      onClose={handleClose}
      maxWidth="sm"
      fullWidth
      TransitionProps={{
        onEntered: (node) => {
          ;(node as HTMLElement).querySelector('textarea')?.focus()
        },
        onExited: () => {
          ;(document.activeElement as HTMLElement)?.blur()
        },
      }}
    >
      <DialogTitle>{t('bugReport.title')}</DialogTitle>
      <DialogContent sx={{ pt: '8px !important' }}>
        <DialogContentText sx={{ mb: 2 }}>{t('bugReport.intro')}</DialogContentText>

        <TextField
          fullWidth
          multiline
          minRows={5}
          label={t('bugReport.whatWentWrong')}
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          autoFocus
          inputProps={{ maxLength: MAX_MESSAGE }}
        />

        <FormControlLabel
          sx={{ mt: 1 }}
          control={
            <Checkbox
              checked={includeActivity}
              onChange={(e) => setIncludeActivity(e.target.checked)}
            />
          }
          label={t('bugReport.includeActivity')}
        />

        {includeActivity && (
          <Accordion disableGutters elevation={0} sx={{ mt: 1, '&:before': { display: 'none' } }}>
            <AccordionSummary expandIcon={<ExpandMoreIcon />} sx={{ px: 0 }}>
              {t('bugReport.seeWhatIsSent')}
            </AccordionSummary>
            <AccordionDetails sx={{ px: 0 }}>
              <Box
                component="pre"
                sx={{
                  m: 0,
                  p: 1,
                  maxHeight: 200,
                  // Both axes: a stack frame is one long line, and wrapping it would make the log
                  // unreadable, so it scrolls sideways inside its own box instead.
                  overflow: 'auto',
                  fontSize: '0.75rem',
                  bgcolor: 'action.hover',
                  borderRadius: 1,
                }}
              >
                {diagnostics || t('bugReport.nothingRecorded')}
              </Box>
            </AccordionDetails>
          </Accordion>
        )}

        {createReport.isError && (
          <Alert severity={rateLimited ? 'warning' : 'error'} sx={{ mt: 2 }}>
            {rateLimited ? t('bugReport.tooMany') : t('general.somethingWentWrong')}
          </Alert>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose} disabled={createReport.isPending}>
          {t('general.cancel')}
        </Button>
        <Button
          onClick={handleSend}
          variant="contained"
          disabled={createReport.isPending || !message.trim()}
        >
          {createReport.isPending ? t('general.saving') : t('bugReport.send')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
