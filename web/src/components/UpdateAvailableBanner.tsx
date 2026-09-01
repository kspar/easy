import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Alert, Box, Button, Collapse, IconButton } from '@mui/material'
import { CloseOutlined, RefreshOutlined } from '@mui/icons-material'
import { useWebUpdate } from '../api/webVersion.ts'
import { record } from '../features/bug-report/breadcrumbs.ts'
import { updateReportContext } from '../features/bug-report/reportContext.ts'

/**
 * "A new version is available — reload" (EZ-1752).
 *
 * **It offers; it never reloads by itself.** This app has a code editor, and a student's
 * half-written solution lives in the page until they submit it. A background deploy that took that
 * away would be a far worse bug than the stale bundle this exists to fix, and it would be
 * unattributable from the outside — the work is simply gone, with no error and nobody to blame but
 * the application. So the only thing that reloads the page is somebody clicking Reload.
 *
 * For the same reason it is `info` rather than `warning`, and dismissible: an old bundle is a
 * papercut, not an outage, and interrupting a grading session over one earns nothing.
 */

/**
 * Which build the reader has already waved away, in localStorage.
 *
 * Keyed by commit rather than a boolean, so dismissing today's deploy says nothing about next
 * week's — the banner comes back for a build they have not seen. Per browser and lost across
 * devices, which costs nothing here: the worst case is being told again about an update they
 * already know about, on a machine that has not been told.
 */
const DISMISSED_KEY = 'dismissedWebUpdate'

function readDismissed(): string | null {
  try {
    return localStorage.getItem(DISMISSED_KEY)
  } catch {
    // Storage can be unavailable or full. Showing the banner again is a fine outcome; taking the
    // page down over a diagnostic nicety is not.
    return null
  }
}

export default function UpdateAvailableBanner({ enabled = true }: { enabled?: boolean }) {
  const { t } = useTranslation()
  const { available, deployed } = useWebUpdate(enabled)
  const [dismissed, setDismissed] = useState<string | null>(readDismissed)

  /**
   * Tell the bug reporter that this tab is behind (EZ-1786).
   *
   * The single most valuable line a report can carry, and the one nobody would think to mention:
   * "the fix is deployed, this tab has never loaded it" is a whole triage in one row, and the
   * reporter has no way of knowing it — that is precisely why the banner exists. It is registered
   * whether or not they dismissed the banner, because dismissing it does not un-stale the bundle.
   */
  // Keyed on the commit rather than on `deployed`, which is a fresh object out of `fetch` every
  // five minutes — depending on it would write the same line into the buffer all afternoon.
  const deployedCommit = deployed?.commit
  useEffect(() => {
    if (!available || !deployed) {
      // Cleared, not merely left alone. `isDifferentBuild` compares commits with no ordering, so a
      // **rollback to the commit this tab is running** makes `available` go false again — and a
      // header that keeps shouting THIS TAB IS RUNNING AN OLDER BUILD at a tab that is now current
      // sends whoever reads the report chasing a stale bundle that does not exist.
      updateReportContext({ deployedBuild: null })
      return
    }
    updateReportContext({ deployedBuild: deployed })
    record('state', `a newer web build is deployed: ${deployed.version} (${deployed.commit})`)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [available, deployedCommit])

  const dismiss = useCallback(() => {
    const commit = deployed?.commit
    if (!commit) return
    record('action', `dismissed the update banner for build ${commit}`)
    setDismissed(commit)
    try {
      localStorage.setItem(DISMISSED_KEY, commit)
    } catch {
      // A dismissal that does not survive a reload is better than a crash on click — and this
      // particular banner is about to be reloaded away anyway.
    }
  }, [deployed?.commit])

  // `location.reload()` and nothing else: no cache clearing, no service worker to coax, no
  // navigation that could lose the current route. The reload re-requests index.html, which is what
  // carries the hashed asset names of the new build.
  const reload = useCallback(() => {
    window.location.reload()
  }, [])

  const show = available && deployed?.commit !== dismissed

  return (
    <Collapse in={show} unmountOnExit>
      <Alert
        severity="info"
        icon={<RefreshOutlined />}
        sx={{ borderRadius: 0, alignItems: 'center' }}
        // Both affordances live here rather than one of them in `onClose`: MUI renders the close
        // button only when `action` is absent, so a banner with an action and an onClose shows the
        // action and silently drops the dismissal.
        action={
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
            <Button size="small" color="inherit" onClick={reload} sx={{ whiteSpace: 'nowrap' }}>
              {t('update.reload')}
            </Button>
            <IconButton
              size="small"
              color="inherit"
              onClick={dismiss}
              aria-label={t('update.dismiss')}
            >
              <CloseOutlined fontSize="small" />
            </IconButton>
          </Box>
        }
      >
        {t('update.available')}
      </Alert>
    </Collapse>
  )
}
