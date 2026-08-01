import { useState } from 'react'
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  Switch,
  Tab,
  Tabs,
  Typography,
} from '@mui/material'
import { ContentCopyOutlined } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useSetExerciseEmbed } from '../../api/library.ts'
import CodeEditor from '../../components/CodeEditor.tsx'

const RESIZER_SCRIPT_URL = 'https://cdn.jsdelivr.net/npm/@iframe-resizer/child@5/index.umd.js'

/**
 * Embed snippets for the anonymous auto-assessment view, ported from wui's EmbedModal.
 *
 * The embedded page itself (`/embed/exercise/…`) is still wui-only — the snippets are generated
 * against this origin so they keep working once the React embed view lands (EZ-1739).
 */
export default function EmbedDialog({
  exerciseId,
  exerciseTitle,
  embedEnabled,
  canEdit,
  isAutoAssessable,
  open,
  onClose,
}: {
  exerciseId: string
  exerciseTitle: string
  embedEnabled: boolean
  canEdit: boolean
  isAutoAssessable: boolean
  open: boolean
  onClose: () => void
}) {
  const { t } = useTranslation()
  const setEmbed = useSetExerciseEmbed(exerciseId)
  const [allowTesting, setAllowTesting] = useState(false)
  const [format, setFormat] = useState<'html' | 'pmwiki'>('html')
  const [copied, setCopied] = useState(false)

  const src =
    `${window.location.origin}/embed/exercise/${exerciseId}/` +
    `${encodeURIComponent(exerciseTitle)}?showTitle=true&showBorder=true` +
    `&showSubmit=${allowTesting}&showTemplate=true&dynamicResize=true`

  const html = `<script src="${RESIZER_SCRIPT_URL}"></script>\n<iframe src="${src}" width="100%" style="border: none;"></iframe>`
  const snippet = format === 'html' ? html : `(:html:)\n${html}\n(:htmlend:)`

  async function copy() {
    await navigator.clipboard.writeText(snippet)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>{t('library.embedding')}</DialogTitle>
      <DialogContent>
        <FormControlLabel
          control={
            <Switch
              checked={embedEnabled}
              disabled={!canEdit || setEmbed.isPending}
              onChange={(e) => setEmbed.mutate(e.target.checked)}
              // The visible label reads "Enabled"/"Disabled", which names the state rather than
              // the control, so screen readers get the purpose from here instead. `role` is
              // repeated because slotProps.input replaces MUI's own input props, and dropping it
              // would silently demote the switch to a plain checkbox.
              slotProps={{ input: { 'aria-label': t('library.embedToggle'), role: 'switch' } }}
            />
          }
          label={embedEnabled ? t('general.enabled') : t('general.disabled')}
        />
        {!canEdit && !embedEnabled && (
          <Alert severity="info" sx={{ mt: 1 }}>
            {t('library.embedNoEditAccess')}
          </Alert>
        )}

        {embedEnabled && (
          <Box mt={2}>
            {isAutoAssessable && (
              <FormControlLabel
                control={
                  <Switch
                    checked={allowTesting}
                    onChange={(e) => setAllowTesting(e.target.checked)}
                  />
                }
                label={t('library.embedAllowTesting')}
              />
            )}
            <Tabs
              value={format}
              onChange={(_, v) => setFormat(v)}
              sx={{ minHeight: 36, mb: 1, '& .MuiTab-root': { minHeight: 36, textTransform: 'none' } }}
            >
              <Tab value="html" label="HTML" />
              <Tab value="pmwiki" label="PmWiki" />
            </Tabs>
            <CodeEditor value={snippet} readOnly minHeight="8rem" lineNumbers={false} />
            <Typography variant="caption" color="text.secondary">
              {t('library.embedHint')}
            </Typography>
          </Box>
        )}
      </DialogContent>
      <DialogActions>
        {embedEnabled && (
          <Button startIcon={<ContentCopyOutlined />} onClick={copy}>
            {copied ? t('general.copied') : t('general.copy')}
          </Button>
        )}
        <Button onClick={onClose}>{t('general.cancel')}</Button>
      </DialogActions>
    </Dialog>
  )
}
