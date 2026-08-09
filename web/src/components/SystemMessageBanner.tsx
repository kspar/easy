import { useCallback, useState } from 'react'
import { Alert, AlertTitle, Box, Button, Collapse } from '@mui/material'
import { OpenInNewOutlined } from '@mui/icons-material'
import { useSystemMessages, type SystemMessage } from '../api/messages.ts'

/**
 * Dismissals live in localStorage, keyed by message id.
 *
 * Per-browser and lost across devices, which is the honest cost. It is acceptable because only INFO
 * can be dismissed at all, and "you already read that we shipped a thing" is not worth a table, a
 * migration and a write on every dismissal. URGENT ignores this entirely — a maintenance notice
 * that a user can make disappear is a maintenance notice half of them will not have seen.
 */
const DISMISSED_KEY = 'dismissedSystemMessages'

function readDismissed(): string[] {
  try {
    const raw = localStorage.getItem(DISMISSED_KEY)
    const parsed: unknown = raw ? JSON.parse(raw) : []
    return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === 'string') : []
  } catch {
    // Corrupt or unavailable storage must not take the banner — or the page — down with it.
    return []
  }
}

function SingleMessage({ msg, onDismiss }: { msg: SystemMessage; onDismiss: (id: string) => void }) {
  const urgent = msg.severity === 'URGENT'
  return (
    <Alert
      severity={urgent ? 'warning' : 'info'}
      variant={urgent ? 'filled' : 'standard'}
      onClose={urgent ? undefined : () => onDismiss(msg.id)}
      sx={{ borderRadius: 0, alignItems: 'center' }}
      action={
        msg.link_url && msg.link_label ? (
          <Button
            size="small"
            color="inherit"
            href={msg.link_url}
            target="_blank"
            rel="noopener noreferrer"
            endIcon={<OpenInNewOutlined sx={{ fontSize: '1rem !important' }} />}
            sx={{ whiteSpace: 'nowrap', mr: urgent ? 0 : 1 }}
          >
            {msg.link_label}
          </Button>
        ) : undefined
      }
    >
      {urgent && <AlertTitle sx={{ mb: 0 }}>{msg.message}</AlertTitle>}
      {!urgent && msg.message}
    </Alert>
  )
}

/**
 * System-wide messages, above everything else in the layout.
 *
 * Renders nothing at all when there is nothing to say — no empty box, no reserved space — so the
 * ordinary case costs the layout nothing.
 */
export default function SystemMessageBanner({ enabled = true }: { enabled?: boolean }) {
  const { data: messages } = useSystemMessages(enabled)
  const [dismissed, setDismissed] = useState<string[]>(readDismissed)

  const dismiss = useCallback((id: string) => {
    setDismissed((prev) => {
      const next = prev.includes(id) ? prev : [...prev, id]
      try {
        // Only ids still being shown are worth keeping, but pruning needs the server's list and
        // this runs without it; the list is tiny and bounded by how many messages ever existed.
        localStorage.setItem(DISMISSED_KEY, JSON.stringify(next))
      } catch {
        // Dismissal that does not survive a reload is better than a crash on click.
      }
      return next
    })
  }, [])

  const visible = (messages ?? []).filter(
    (m) => m.severity === 'URGENT' || !dismissed.includes(m.id),
  )

  return (
    <Collapse in={visible.length > 0} unmountOnExit>
      <Box>
        {visible.map((m) => (
          <SingleMessage key={m.id} msg={m} onDismiss={dismiss} />
        ))}
      </Box>
    </Collapse>
  )
}
