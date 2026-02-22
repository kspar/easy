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

export default function AddParticipantsDialog({
  open,
  title,
  isPending,
  onClose,
  onSubmit,
}: {
  open: boolean
  title: string
  isPending: boolean
  onClose: () => void
  onSubmit: (emails: string[]) => void
}) {
  const { t } = useTranslation()
  const [emails, setEmails] = useState('')

  useEffect(() => {
    if (open) setEmails('')
  }, [open])

  function handleAdd() {
    const parsed = emails
      .split('\n')
      .map((e) => e.trim())
      .filter(Boolean)

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
          placeholder={t('participants.addParticipantsHelp')}
          value={emails}
          onChange={(e) => setEmails(e.target.value)}
          autoFocus
        />
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
