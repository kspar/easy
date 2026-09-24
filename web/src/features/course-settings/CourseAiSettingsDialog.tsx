import { useEffect, useState } from 'react'
import {
  Box,
  Button,
  Collapse,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControl,
  InputLabel,
  MenuItem,
  Select,
  Snackbar,
  TextField,
  Typography,
} from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../../auth/useAuth.ts'
import { useCourseAiProps, useUpdateCourseAiProps } from '../../api/courses.ts'
import { errorMessage } from '../../api/errorMessage.ts'
import ConfirmDialog from '../../components/ConfirmDialog.tsx'

const DEFAULT_MODEL = 'claude-opus-5'

/**
 * Per-course AI provider settings (EZ-1711): which vendor, which model, whose key.
 *
 * A sibling of `EditCourseDialog` rather than a section in it, because the two save differently.
 * That one PUTs four fields it can always read back; this one holds a secret the server will never
 * return, so "save" has to mean "keep the key unless I typed a new one", and "disable" is its own
 * action with its own confirmation rather than a blank field.
 */
export default function CourseAiSettingsDialog({
  courseId,
  open,
  onClose,
}: {
  courseId: string
  open: boolean
  onClose: () => void
}) {
  const { t } = useTranslation()
  const { activeRole } = useAuth()
  // Core only lets an admin set the base URL (it is where a key and a student's code get sent), so
  // the field is not offered to a teacher at all rather than offered and refused.
  const isAdmin = activeRole === 'admin'
  const { data: props, isLoading } = useCourseAiProps(courseId, open)
  const update = useUpdateCourseAiProps(courseId)

  const [model, setModel] = useState(DEFAULT_MODEL)
  const [apiKey, setApiKey] = useState('')
  const [baseUrl, setBaseUrl] = useState('')
  const [advancedOpen, setAdvancedOpen] = useState(false)
  const [confirmDisable, setConfirmDisable] = useState(false)
  const [snack, setSnack] = useState<string | null>(null)

  useEffect(() => {
    if (!open) return
    setModel(props?.model ?? DEFAULT_MODEL)
    setBaseUrl(props?.base_url ?? '')
    setAdvancedOpen(!!props?.base_url)
    // Never pre-filled: the server does not have it to give, and a field that looks full would
    // invite "save" to overwrite a working key with the placeholder.
    setApiKey('')
  }, [props, open])

  const configured = props?.api_key_configured === true
  const canSave = model.trim().length > 0 && (configured || apiKey.trim().length > 0) && !update.isPending

  function handleSave() {
    update.mutate(
      {
        ai_props: {
          provider: 'ANTHROPIC',
          model: model.trim(),
          // A teacher's write never carries a URL; core keeps the stored one for them.
          base_url: isAdmin ? baseUrl.trim() || null : null,
          api_key: apiKey.trim() || null,
        },
      },
      {
        onSuccess: () => {
          onClose()
          setSnack(t('courses.aiSaved'))
        },
      },
    )
  }

  function handleDisable() {
    setConfirmDisable(false)
    update.mutate(
      { ai_props: null },
      {
        onSuccess: () => {
          onClose()
          setSnack(t('courses.aiDisabled'))
        },
      },
    )
  }

  return (
    <>
      <Dialog open={open} onClose={onClose} maxWidth="xs" fullWidth>
        <DialogTitle>{t('courses.aiSettings')}</DialogTitle>
        <DialogContent sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: '8px !important' }}>
          <Typography variant="body2" color="text.secondary">
            {t('courses.aiHelp')}
          </Typography>

          {/* Both ids, or MUI never wires the label to the control — see doc/web/browser-testing.md. */}
          <FormControl fullWidth size="small">
            <InputLabel id="ai-provider-label">{t('courses.aiProvider')}</InputLabel>
            <Select
              labelId="ai-provider-label"
              id="ai-provider"
              label={t('courses.aiProvider')}
              value="ANTHROPIC"
            >
              <MenuItem value="ANTHROPIC">Anthropic</MenuItem>
            </Select>
          </FormControl>

          <TextField
            label={t('courses.aiModel')}
            value={model}
            onChange={(e) => setModel(e.target.value)}
            required
            size="small"
            disabled={isLoading}
            inputProps={{ maxLength: 100 }}
          />

          <TextField
            label={t('courses.aiApiKey')}
            type="password"
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
            required={!configured}
            size="small"
            disabled={isLoading}
            autoComplete="off"
            placeholder={configured ? t('courses.aiApiKeyConfigured', { hint: props?.api_key_hint ?? '' }) : undefined}
            helperText={configured ? t('courses.aiApiKeyKeep') : undefined}
            inputProps={{ maxLength: 500 }}
          />

          {isAdmin && (
          <Box>
            <Button size="small" onClick={() => setAdvancedOpen((v) => !v)} sx={{ textTransform: 'none', px: 0 }}>
              {advancedOpen ? t('courses.aiAdvancedHide') : t('courses.aiAdvancedShow')}
            </Button>
            <Collapse in={advancedOpen}>
              <TextField
                label={t('courses.aiBaseUrl')}
                value={baseUrl}
                onChange={(e) => setBaseUrl(e.target.value)}
                size="small"
                fullWidth
                sx={{ mt: 1 }}
                helperText={t('courses.aiBaseUrlHelp')}
                inputProps={{ maxLength: 500 }}
              />
            </Collapse>
          </Box>
          )}

          {update.isError && (
            <Typography variant="caption" color="error">
              {errorMessage(update.error, t)}
            </Typography>
          )}
        </DialogContent>
        <DialogActions>
          {configured && (
            <Button color="error" onClick={() => setConfirmDisable(true)} disabled={update.isPending} sx={{ mr: 'auto' }}>
              {t('courses.aiDisable')}
            </Button>
          )}
          <Button onClick={onClose}>{t('general.cancel')}</Button>
          <Button onClick={handleSave} variant="contained" disabled={!canSave}>
            {update.isPending ? t('general.saving') : t('general.save')}
          </Button>
        </DialogActions>
      </Dialog>

      <ConfirmDialog
        open={confirmDisable}
        message={t('courses.aiDisableConfirm')}
        confirmLabel={t('courses.aiDisable')}
        onClose={() => setConfirmDisable(false)}
        onConfirm={handleDisable}
      />

      <Snackbar
        open={snack !== null}
        autoHideDuration={3000}
        onClose={() => setSnack(null)}
        message={snack}
      />
    </>
  )
}
