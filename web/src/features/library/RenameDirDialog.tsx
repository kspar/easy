import { useState, useEffect } from 'react'
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  TextField,
} from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useUpdateDir } from '../../api/library.ts'

export default function RenameDirDialog({
  dirId,
  currentName,
  open,
  onClose,
  onSuccess,
}: {
  dirId: string
  currentName: string
  open: boolean
  onClose: () => void
  onSuccess: (msg: string) => void
}) {
  const { t } = useTranslation()
  const [name, setName] = useState(currentName)
  const updateDir = useUpdateDir()

  useEffect(() => {
    if (open) setName(currentName)
  }, [open, currentName])

  function handleSave() {
    if (!name.trim() || name.trim() === currentName) return
    updateDir.mutate(
      { dirId, name: name.trim() },
      {
        onSuccess: () => {
          onClose()
          onSuccess(t('library.dirRenamed'))
        },
      },
    )
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="xs" fullWidth
      TransitionProps={{ onEntered: (node) => { (node as HTMLElement).querySelector('input')?.focus() } }}
    >
      <DialogTitle>{t('library.rename')}</DialogTitle>
      <DialogContent sx={{ pt: '8px !important' }}>
        <TextField
          fullWidth
          label={t('library.directoryName')}
          value={name}
          onChange={(e) => setName(e.target.value)}
          autoFocus
          inputProps={{ maxLength: 100 }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && name.trim() && name.trim() !== currentName && !updateDir.isPending) handleSave()
          }}
        />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t('general.cancel')}</Button>
        <Button
          onClick={handleSave}
          variant="contained"
          disabled={updateDir.isPending || !name.trim() || name.trim() === currentName}
        >
          {updateDir.isPending ? t('general.saving') : t('general.save')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
