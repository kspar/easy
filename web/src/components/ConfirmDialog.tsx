import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  Typography,
} from '@mui/material'
import { useTranslation } from 'react-i18next'
import SafeText from './SafeText.tsx'

export default function ConfirmDialog({
  open,
  message,
  confirmLabel,
  confirmColor = 'error',
  isPending = false,
  pendingLabel,
  onClose,
  onConfirm,
}: {
  open: boolean
  message: React.ReactNode
  confirmLabel?: string
  confirmColor?: 'error' | 'primary' | 'warning'
  /** For async confirms; a synchronous confirm simply omits it. */
  isPending?: boolean
  /** What the confirm button says while pending. Defaults to the remove-flavoured house label. */
  pendingLabel?: string
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
          {/* Wrapped so the label actually changes on a translated page. A MUI Button's children
              are an array, so a bare string here gets a real text node — and an in-place update to
              one the translator has replaced lands nowhere, leaving the button reading "Remove"
              while the deletion runs. No crash, no error, just no feedback. See SafeText. */}
          <SafeText>
            {isPending
              ? (pendingLabel ?? t('general.removing'))
              : (confirmLabel ?? t('general.remove'))}
          </SafeText>
        </Button>
      </DialogActions>
    </Dialog>
  )
}
