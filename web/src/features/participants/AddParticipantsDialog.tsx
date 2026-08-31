import { useEffect, useState } from 'react'
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  TextField,
} from '@mui/material'
import { useTranslation } from 'react-i18next'
import ErrorAlert from '../../components/ErrorAlert.tsx'

/**
 * The addresses in one pasted entry.
 *
 * Entries are separated by the punctuation people's own lists use — never by bare whitespace, which
 * would shred `Tiiu Tamm <tiiu@example.com>` into three tokens and get a teacher told that "Tamm"
 * has no account (EZ-1830 is about a message naming the wrong thing; this would be a new way to do
 * that). Within an entry:
 *
 *   `Tiiu Tamm <tiiu@example.com>`   the display name every mail client adds is not an address
 *   `a@x.ee b@y.ee`                  two bare addresses on one line are two addresses
 *   `Tiiu Tamm`                      nothing address-shaped: passed through whole, so core names
 *                                    back exactly what was typed rather than silently dropping it
 */
function addressesIn(entry: string): string[] {
  const angled = [...entry.matchAll(/<([^>]*)>/g)].map((m) => m[1].trim()).filter(Boolean)
  if (angled.length > 0) return angled

  const parts = entry.split(/\s+/).filter(Boolean)
  const withAt = parts.filter((p) => p.includes('@'))
  if (withAt.length > 0) return withAt
  return parts.length > 0 ? [entry.trim()] : []
}

export default function AddParticipantsDialog({
  open,
  title,
  isPending,
  error,
  onClose,
  onSubmit,
}: {
  open: boolean
  title: string
  isPending: boolean
  /** The failed mutation's error, if the last attempt failed. Rendered below the box. */
  error?: unknown
  onClose: () => void
  onSubmit: (emails: string[]) => void
}) {
  const { t } = useTranslation()
  const [emails, setEmails] = useState('')

  useEffect(() => {
    if (open) setEmails('')
  }, [open])

  function handleAdd() {
    // The placeholder asks for one address per line, and the box is fed by a clipboard: a
    // spreadsheet column arrives comma-separated, a mail client's recipient list semicolon-separated
    // and wrapped in display names. Deduplicated, because core does too and the error should not
    // name the same address twice.
    const parsed = [...new Set(emails.split(/[\n,;]+/).flatMap(addressesIn))]

    if (parsed.length === 0) return
    onSubmit(parsed)
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{title}</DialogTitle>
      <DialogContent sx={{ pt: '8px !important' }}>
        <TextField
          multiline
          minRows={4}
          maxRows={12}
          fullWidth
          error={error != null}
          placeholder={t('participants.addParticipantsHelp')}
          value={emails}
          onChange={(e) => setEmails(e.target.value)}
          autoFocus
        />
        {/*
          The dialog stays open on failure and keeps what was typed, because the message names the
          addresses that could not be resolved and the list they are in is right here to correct.
          Closing it — or sending this to a snackbar — would make the reader memorise the addresses
          before they could act on them.

          [ErrorAlert] rather than a coloured line of text: its `role="alert"` is what makes the
          rejection reach a screen reader at all, and pressing Add and being told nothing is the
          symptom EZ-1830 was filed for.
        */}
        {error != null && <ErrorAlert error={error} sx={{ mt: 1.5 }} />}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t('general.cancel')}</Button>
        <Button
          onClick={handleAdd}
          variant="contained"
          disabled={isPending || !emails.trim()}
        >
          {isPending ? t('general.adding') : t('general.add')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
