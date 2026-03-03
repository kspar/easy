import { useState } from 'react'
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  TextField,
} from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useCreateDir } from '../../api/library.ts'

export default function CreateDirDialog({
  parentDirId,
  open,
  onClose,
  onSuccess,
}: {
  parentDirId: string | null
  open: boolean
  onClose: () => void
  onSuccess: (msg: string) => void
}) {
  const { t } = useTranslation()
  const [name, setName] = useState('')
  const createDir = useCreateDir()

  function handleCreate() {
    if (!name.trim()) return
    createDir.mutate(
      { name: name.trim(), parent_dir_id: parentDirId },
      {
        onSuccess: () => {
          setName('')
          onClose()
          onSuccess(t('library.dirCreated'))
        },
      },
    )
  }

  function handleClose() {
    setName('')
    onClose()
  }

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="xs" fullWidth
      TransitionProps={{
        onEntered: (node) => { (node as HTMLElement).querySelector('input')?.focus() },
        onExited: () => { (document.activeElement as HTMLElement)?.blur() },
      }}
    >
      <DialogTitle>{t('library.createDir')}</DialogTitle>
      <DialogContent sx={{ pt: '8px !important' }}>
        <TextField
          fullWidth
          label={t('library.directoryName')}
          value={name}
          onChange={(e) => setName(e.target.value)}
          autoFocus
          inputProps={{ maxLength: 100 }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && name.trim() && !createDir.isPending) handleCreate()
          }}
        />
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose}>{t('general.cancel')}</Button>
        <Button
          onClick={handleCreate}
          variant="contained"
          disabled={createDir.isPending || !name.trim()}
        >
          {createDir.isPending ? t('general.saving') : t('general.add')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
