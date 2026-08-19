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
        {/*
          `component="div"` because `message` is a ReactNode and several callers pass block content
          in it — the group-deletion confirmation renders a `<ul>` of the students who will be pulled
          out, wrapped in `<Box>`. Typography's default body1 element is `<p>`, which may not contain
          either, so React logged "<p> cannot contain a nested <div>" and the browser silently closed
          the paragraph early. No visual change: body1 carries its own margins.
        */}
        <Typography component="div">{message}</Typography>
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
