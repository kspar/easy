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
import { useCreateGroup } from '../../api/exercises.ts'

export default function CreateGroupDialog({
  courseId,
  open,
  onClose,
  onSuccess,
}: {
  courseId: string
  open: boolean
  onClose: () => void
  onSuccess: (msg: string) => void
}) {
  const { t } = useTranslation()
  const [name, setName] = useState('')
  const createGroup = useCreateGroup(courseId)

  function handleCreate() {
    if (!name.trim()) return

    createGroup.mutate(name.trim(), {
      onSuccess: () => {
        setName('')
        onClose()
        onSuccess(t('participants.groupCreated'))
      },
    })
  }

  function handleClose() {
    setName('')
    onClose()
  }

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="xs" fullWidth>
      <DialogTitle>{t('participants.createGroup')}</DialogTitle>
      <DialogContent sx={{ pt: '8px !important' }}>
        <TextField
          fullWidth
          label={t('participants.groupName')}
          value={name}
          onChange={(e) => setName(e.target.value)}
          autoFocus
          onKeyDown={(e) => {
            if (e.key === 'Enter' && name.trim()) handleCreate()
          }}
        />
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose}>{t('general.cancel')}</Button>
        <Button
          onClick={handleCreate}
          variant="contained"
          disabled={createGroup.isPending || !name.trim()}
        >
          {createGroup.isPending ? t('general.saving') : t('general.add')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
