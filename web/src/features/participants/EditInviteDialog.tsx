import { useEffect, useState } from 'react'
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  TextField,
} from '@mui/material'
import { DateTimePicker } from '@mui/x-date-pickers/DateTimePicker'
import { useTranslation } from 'react-i18next'
import { useCreateInvite } from '../../api/exercises.ts'
import type { CourseInviteResp } from '../../api/types.ts'

export default function EditInviteDialog({
  courseId,
  invite,
  open,
  onClose,
  onSuccess,
}: {
  courseId: string
  invite: CourseInviteResp
  open: boolean
  onClose: () => void
  onSuccess: (msg: string) => void
}) {
  const { t } = useTranslation()
  const [expiresAt, setExpiresAt] = useState<Date | null>(null)
  const [maxUses, setMaxUses] = useState('')
  const updateInvite = useCreateInvite(courseId)

  useEffect(() => {
    if (open) {
      setExpiresAt(new Date(invite.expires_at))
      setMaxUses(String(invite.allowed_uses))
    }
  }, [open, invite])

  function handleSave() {
    const uses = parseInt(maxUses, 10)
    if (!expiresAt || isNaN(uses) || uses < 0) return

    updateInvite.mutate(
      {
        expires_at: expiresAt.toISOString(),
        allowed_uses: uses,
      },
      {
        onSuccess: () => {
          onClose()
          onSuccess(t('participants.inviteLinkSaved'))
        },
      },
    )
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>{t('participants.inviteLink')}</DialogTitle>
      <DialogContent
        sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: '8px !important' }}
      >
        <DateTimePicker
          label={t('participants.inviteExpiry')}
          value={expiresAt}
          onChange={setExpiresAt}
          slotProps={{
            textField: { size: 'small' },
          }}
        />
        <TextField
          label={t('participants.inviteMaxUses')}
          type="number"
          size="small"
          value={maxUses}
          onChange={(e) => setMaxUses(e.target.value)}
          slotProps={{ htmlInput: { min: 0, max: 1000000 } }}
        />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t('general.cancel')}</Button>
        <Button
          onClick={handleSave}
          variant="contained"
          disabled={updateInvite.isPending || !expiresAt || !maxUses}
        >
          {updateInvite.isPending ? t('general.saving') : t('general.save')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
