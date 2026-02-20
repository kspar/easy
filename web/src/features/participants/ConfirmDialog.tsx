import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  Typography,
} from '@mui/material'
import { useTranslation } from 'react-i18next'

export default function ConfirmDialog({
  open,
  message,
  confirmLabel,
  confirmColor = 'error',
  isPending,
  onClose,
  onConfirm,
}: {
  open: boolean
  message: React.ReactNode
  confirmLabel?: string
  confirmColor?: 'error' | 'primary' | 'warning'
  isPending: boolean
  onClose: () => void
  onConfirm: () => void
}) {
  const { t } = useTranslation()

  return (
    <Dialog open={open} onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>{confirmLabel ?? t('general.remove')}</DialogTitle>
      <DialogContent>
        <Typography>{message}</Typography>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t('general.cancel')}</Button>
        <Button
          onClick={onConfirm}
          color={confirmColor}
          variant="contained"
          disabled={isPending}
        >
          {isPending
            ? t('general.removing')
            : (confirmLabel ?? t('general.remove'))}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
