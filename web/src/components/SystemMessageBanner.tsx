import { useCallback, useState } from 'react'
import { Alert, AlertTitle, Box, Button, Collapse } from '@mui/material'
import { OpenInNewOutlined } from '@mui/icons-material'
import { useSystemMessages, type SystemMessage } from '../api/messages.ts'
import { dismissalKey, keepCurrentFormat } from './systemMessageKey.ts'

/**
 * Dismissals live in localStorage, keyed by a hash of the message's content.
 *
 * Per-browser and lost across devices, which is the honest cost. It is acceptable because only INFO
 * can be dismissed at all, and "you already read that we shipped a thing" is not worth a table, a
 * migration and a write on every dismissal. URGENT ignores this entirely — a maintenance notice
 * that a user can make disappear is a maintenance notice half of them will not have seen.
 *
 * Keyed by content and not by `id` since EZ-1790: the id is a `bigserial`, so a dismissal was a
 * claim about a row number, and a dismissal of a long-deleted "message 1" silently suppressed a
 * brand-new one. See `systemMessageKey.ts`.
 */
const DISMISSED_KEY = 'dismissedSystemMessages'

function readDismissed(): string[] {
  try {
    const raw = localStorage.getItem(DISMISSED_KEY)
    const parsed: unknown = raw ? JSON.parse(raw) : []
    const all = Array.isArray(parsed)
      ? parsed.filter((x): x is string => typeof x === 'string')
      : []
    // Legacy row ids are dropped here, which is why every previously dismissed message reappears
    // once after EZ-1790 ships.
    return keepCurrentFormat(all)
  } catch {
    // Corrupt or unavailable storage must not take the banner — or the page — down with it.
    return []
  }
}

function SingleMessage({ msg, onDismiss }: { msg: SystemMessage; onDismiss: () => void }) {
  const urgent = msg.severity === 'URGENT'
  return (
    <Alert
      severity={urgent ? 'warning' : 'info'}
      variant={urgent ? 'filled' : 'standard'}
      // The key is the caller's business — it is derived from the message rather than read off it,
      // so this component has no reason to know how.
      onClose={urgent ? undefined : onDismiss}
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

  const dismiss = useCallback((key: string) => {
    setDismissed((prev) => {
      const next = prev.includes(key) ? prev : [...prev, key]
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
    (m) => m.severity === 'URGENT' || !dismissed.includes(dismissalKey(m)),
  )

  return (
    <Collapse in={visible.length > 0} unmountOnExit>
      <Box>
        {visible.map((m) => (
          // React's `key` stays the id — it identifies this element among its siblings in one
          // render, which the row id is perfectly good for. The dismissal key is a different
          // question and deliberately answered differently.
          <SingleMessage key={m.id} msg={m} onDismiss={() => dismiss(dismissalKey(m))} />
        ))}
      </Box>
    </Collapse>
  )
}
