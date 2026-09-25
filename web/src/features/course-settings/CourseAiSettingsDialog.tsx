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
import { useCourseAiProps, useResetCourseAiUsage, useUpdateCourseAiProps } from '../../api/courses.ts'
import { errorMessage } from '../../api/errorMessage.ts'
import ConfirmDialog from '../../components/ConfirmDialog.tsx'
import { formatDateTime, useDateLocale } from '../../i18n/dateLocale.ts'
import { estimateUsd, formatUsd } from './aiPricing.ts'

// Mirrors AiProviderFactory.DEFAULT_MODEL in core.
const DEFAULT_MODEL = 'claude-sonnet-5'
// Mirrors AiFeedbackPrompt.SOLUTION_CAP in core.
const MAX_SOLUTION_CHARS_CAP = 10_000

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
  const { data: settings, isLoading } = useCourseAiProps(courseId, open)
  const props = settings?.ai_props ?? null
  const update = useUpdateCourseAiProps(courseId)
  const resetUsage = useResetCourseAiUsage(courseId)
  const dateLocale = useDateLocale()

  const [model, setModel] = useState(DEFAULT_MODEL)
  const [apiKey, setApiKey] = useState('')
  const [baseUrl, setBaseUrl] = useState('')
  const [tokenBudget, setTokenBudget] = useState('')
  const [maxSolutionChars, setMaxSolutionChars] = useState('6000')
  const [advancedOpen, setAdvancedOpen] = useState(false)
  const [confirmDisable, setConfirmDisable] = useState(false)
  const [confirmReset, setConfirmReset] = useState(false)
  const [snack, setSnack] = useState<string | null>(null)

  useEffect(() => {
    if (!open) return
    setModel(props?.model ?? DEFAULT_MODEL)
    setBaseUrl(settings?.base_url ?? '')
    setTokenBudget(settings?.token_budget != null ? String(settings.token_budget) : '')
    setMaxSolutionChars(String(settings?.max_solution_chars ?? 6000))
    setAdvancedOpen(!!settings?.base_url)
    // Never pre-filled: the server does not have it to give, and a field that looks full would
    // invite "save" to overwrite a working key with the placeholder.
    setApiKey('')
  }, [settings, props, open])

  const configured = props?.api_key_configured === true

  // Digits only; an empty field is "no limit". Anything else is refused at the Save button rather
  // than sent for core to refuse.
  const budgetDigits = tokenBudget.replace(/[\s,]/g, '')
  const budgetValid = budgetDigits === '' || /^[1-9]\d*$/.test(budgetDigits)
  const budgetNumber = budgetValid && budgetDigits !== '' ? Number(budgetDigits) : null
  const maxCharsNumber = parseInt(maxSolutionChars.replace(/[\s,]/g, ''), 10)
  // Core caps this at what the prompt carries whole; the same number here, so Save is not offered
  // for a value core will refuse.
  const maxCharsValid = Number.isFinite(maxCharsNumber) && maxCharsNumber >= 1 && maxCharsNumber <= MAX_SOLUTION_CHARS_CAP
  const canSave = model.trim().length > 0 && budgetValid && maxCharsValid &&
    (configured || apiKey.trim().length > 0) && !update.isPending

  // The estimate follows the model field as it is typed, so a teacher weighing two models sees
  // the price move. The input/output split is the course's own once it has any usage — output
  // costs several times input, so a guessed split is the difference between $1 and $3 — and a
  // stock guess before that. What has been spent is priced at the current model's rate, which
  // for a course that changed model mid-way is an approximation; hence the "≈".
  const inUsed = settings?.tokens_in_used ?? 0
  const outUsed = settings?.tokens_out_used ?? 0
  const measuredShare = inUsed + outUsed > 0 ? inUsed / (inUsed + outUsed) : undefined
  const budgetEstimate = budgetNumber != null ? estimateUsd(model, budgetNumber, measuredShare) : null
  const usedTokens = settings?.tokens_used ?? 0
  const usedEstimate = usedTokens > 0 ? estimateUsd(model, usedTokens, measuredShare) : null
  const tokensFmt = (n: number) => n.toLocaleString(dateLocale.code === 'et' ? 'et-EE' : 'en-GB')

  function handleSave() {
    update.mutate(
      {
        ai_props: {
          provider: 'ANTHROPIC',
          model: model.trim(),
          // Only an admin's write carries the URL at all: absent means keep, '' means clear. A
          // teacher's — or an admin's while acting as a teacher — leaves it out, and core keeps it.
          ...(isAdmin ? { base_url: baseUrl.trim() } : {}),
          api_key: apiKey.trim() || null,
          token_budget: budgetNumber,
          max_solution_chars: maxCharsNumber,
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

  function handleReset() {
    setConfirmReset(false)
    resetUsage.mutate(undefined, { onSuccess: () => setSnack(t('courses.aiUsageResetDone')) })
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

          <TextField
            label={t('courses.aiTokenBudget')}
            value={tokenBudget}
            onChange={(e) => setTokenBudget(e.target.value)}
            size="small"
            disabled={isLoading}
            error={!budgetValid}
            inputProps={{ inputMode: 'numeric', maxLength: 15 }}
            helperText={
              budgetNumber == null
                ? t('courses.aiTokenBudgetHelp')
                : budgetEstimate != null
                  ? t('courses.aiTokenBudgetEstimate', { cost: formatUsd(budgetEstimate), provider: 'Anthropic' })
                  : t('courses.aiTokenBudgetNoEstimate')
            }
          />

          <TextField
            label={t('courses.aiMaxSolutionChars')}
            value={maxSolutionChars}
            onChange={(e) => setMaxSolutionChars(e.target.value)}
            size="small"
            disabled={isLoading}
            error={!maxCharsValid}
            inputProps={{ inputMode: 'numeric', maxLength: 9 }}
            helperText={t('courses.aiMaxSolutionCharsHelp')}
          />

          {settings != null && (configured || usedTokens > 0) && (
            <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 1 }}>
              <Typography variant="body2" color="text.secondary">
                {usedEstimate != null
                  ? t('courses.aiUsageWithCost', { tokens: tokensFmt(usedTokens), cost: formatUsd(usedEstimate) })
                  : t('courses.aiUsage', { tokens: tokensFmt(usedTokens) })}
                {settings.tokens_reset_at && (
                  <>
                    {' · '}
                    {t('courses.aiUsageSince', { date: formatDateTime(new Date(settings.tokens_reset_at), dateLocale) })}
                  </>
                )}
              </Typography>
              <Button
                size="small"
                onClick={() => setConfirmReset(true)}
                disabled={resetUsage.isPending || usedTokens === 0}
                sx={{ textTransform: 'none', flexShrink: 0 }}
              >
                {t('courses.aiUsageReset')}
              </Button>
            </Box>
          )}

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

      <ConfirmDialog
        open={confirmReset}
        message={t('courses.aiUsageResetConfirm')}
        confirmLabel={t('courses.aiUsageReset')}
        confirmColor="primary"
        onClose={() => setConfirmReset(false)}
        onConfirm={handleReset}
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
