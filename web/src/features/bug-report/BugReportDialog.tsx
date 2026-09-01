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
  Typography,
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
 * A real monospace stack.
 *
 * The bare `monospace` keyword resolves to Courier on macOS, which is what the first version of this
 * panel rendered in — cramped, dated, and noticeably not the font anything else technical in the app
 * uses. Named faces first, `monospace` last so there is always a fallback.
 */
const MONO =
  'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, "Liberation Mono", monospace'

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

        {/*
          Said here as well as on the alert that offers this dialog, because the toolbar entry
          (EZ-1824) reaches it with no error in front of it at all — and a student wondering why a
          course will not open has no reason to guess that we are the wrong people to ask. An
          `info` alert rather than another line of prose: its whole job is to be read by someone
          about to type the wrong thing, and muted text under an intro is what that person skips.

          `role="note"` overrides MUI's default of `alert`, which is an assertive live region and
          would have a screen reader interrupt itself to announce standing advice on open. It also
          leaves the one alert that *is* live — the send failure below — the only `role=alert` here.
        */}
        <Alert severity="info" role="note" sx={{ mb: 2 }}>
          {t('general.askCourseOrganiser')}
        </Alert>

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
          <Accordion
            disableGutters
            elevation={0}
            sx={{
              mt: 1,
              '&:before': { display: 'none' },
              // Transparent, because an Accordion is a Paper and defaults to
              // `background.paper` — which in dark mode is *darker* than the elevated surface a
              // Dialog sits on. The result was a black slab behind the summary row and the log,
              // clearly a different colour from the dialog around it. Invisible in light mode,
              // where both are white, which is exactly why this needed looking at in both.
              bgcolor: 'transparent',
              // The other half of the same problem: Paper paints an elevation overlay as a
              // background *image* in dark mode, which a background colour does not clear.
              backgroundImage: 'none',
            }}
          >
            <AccordionSummary expandIcon={<ExpandMoreIcon />} sx={{ px: 0 }}>
              {t('bugReport.seeWhatIsSent')}
            </AccordionSummary>
            {/*
              `px: 0` so the panel lines up with the text field above it rather than being inset by
              MUI's default AccordionDetails padding; `pt: 0` because the summary row already leaves
              a gap and a second one reads as a hole.
            */}
            <AccordionDetails sx={{ px: 0, pt: 0 }}>
              {diagnostics ? (
                <Box
                  component="pre"
                  sx={{
                    m: 0,
                    // 8px was not enough for a monospace log — the text touched the edges and the
                    // faint background read as a smudge rather than a panel.
                    px: 1.5,
                    py: 1.25,
                    maxHeight: 220,
                    // Both axes: a stack frame is one long line, and wrapping it would make the log
                    // unreadable, so it scrolls sideways inside its own box instead.
                    overflow: 'auto',
                    fontFamily: MONO,
                    fontSize: '0.75rem',
                    // Logs are scanned, not read. Looser than body text so the eye can find the line
                    // it wants.
                    lineHeight: 1.65,
                    // Secondary, so the reporter's own words stay the most prominent thing in the
                    // dialog and this stays evidence attached to them.
                    color: 'text.secondary',
                    bgcolor: 'action.hover',
                    // The border is what makes it a panel. `action.hover` alone is 4% black, which
                    // all but disappears against the dialog and vanishes entirely in dark mode.
                    border: '1px solid',
                    borderColor: 'divider',
                    borderRadius: 1,
                  }}
                >
                  {diagnostics}
                </Box>
              ) : (
                // Prose, not code. The empty state is a sentence, and rendering it inside the
                // monospace box made it look like log output claiming nothing had been logged.
                <Typography variant="body2" color="text.secondary" sx={{ fontStyle: 'italic' }}>
                  {t('bugReport.nothingRecorded')}
                </Typography>
              )}
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
