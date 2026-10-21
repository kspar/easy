import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  FormControlLabel,
  Paper,
  Snackbar,
  Stack,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material'
import { useAdminSimilarityProviders, useUpdateSimilarityLanguage, useUpdateSimilarityProvider } from '../../api/similarityProviders.ts'
import type { AdminSimilarityProvider } from '../../api/types.ts'
import { errorMessage } from '../../api/errorMessage.ts'
import usePageTitle from '../../hooks/usePageTitle.ts'
import { languageName, providerHelp, providerName } from '../similarity/similarityProvider.ts'
import {
  defaultsRequest,
  differsFromDefaults,
  draftOf,
  isChanged,
  languageVerdict,
  MAX_PRIORITY,
  parsedPriority,
  updateRequest,
} from './similarityProvidersAdmin.ts'
import type { ProviderDraft } from './similarityProvidersAdmin.ts'

/**
 * Which similarity provider compares each language (EZ-1667): the languages, each with the provider that
 * compares it now and a switch for the language as a whole; then the providers, in the order a language
 * is offered to them, each with its own switch, priority and languages.
 *
 * The first table is the answer and the cards below are the inputs to it, so the table comes first: what
 * an admin most often wants to know is "who compares Python here", and that is a row, not a calculation.
 */
export default function SimilarityProvidersPage() {
  const { t } = useTranslation()
  usePageTitle(t('admin.similarity.title'))
  const { data, isLoading, isError, error } = useAdminSimilarityProviders()
  const updateLanguage = useUpdateSimilarityLanguage()
  const [saved, setSaved] = useState(false)

  return (
    <Box sx={{ maxWidth: 900 }}>
      <Typography variant="h5" sx={{ mb: 1 }}>{t('admin.similarity.title')}</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3, maxWidth: '75ch' }}>
        {t('admin.similarity.intro')}
      </Typography>

      {isLoading && <CircularProgress size={24} />}
      {isError && <Alert severity="error">{errorMessage(error, t)}</Alert>}

      {data && (
        <>
          <Typography variant="subtitle1" sx={{ mb: 1 }}>{t('admin.similarity.languagesTitle')}</Typography>
          <Paper variant="outlined" sx={{ mb: 4 }}>
            <Table size="small" aria-label={t('admin.similarity.languagesTitle')}>
              <TableHead>
                <TableRow>
                  <TableCell>{t('admin.similarity.language')}</TableCell>
                  <TableCell>{t('admin.similarity.comparedBy')}</TableCell>
                  <TableCell align="right">{t('admin.similarity.languageOn')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {data.languages.map((row) => {
                  const verdict = languageVerdict(row)
                  return (
                    <TableRow key={row.language}>
                      {/* Capitalised here only: "plain text" is written for the middle of a sentence. */}
                      <TableCell sx={{ '&::first-letter': { textTransform: 'uppercase' } }}>{languageName(row.language, t)}</TableCell>
                      <TableCell>
                        {verdict.kind === 'off' && (
                          <Typography variant="body2" color="text.secondary">{t('admin.similarity.refused')}</Typography>
                        )}
                        {verdict.kind === 'nobody' && (
                          <Typography variant="body2" color="warning.main">{t('admin.similarity.nobody')}</Typography>
                        )}
                        {verdict.kind === 'provider' && (
                          <Typography variant="body2">
                            {verdict.asText
                              ? t('admin.similarity.byAsText', { provider: providerName(verdict.provider, t) })
                              : providerName(verdict.provider, t)}
                          </Typography>
                        )}
                      </TableCell>
                      <TableCell align="right">
                        <Switch
                          size="small"
                          checked={row.enabled}
                          disabled={updateLanguage.isPending}
                          onChange={(e) => updateLanguage.mutate({ language: row.language, body: { enabled: e.target.checked } })}
                          slotProps={{ input: { 'aria-label': t('admin.similarity.languageSwitch', { language: languageName(row.language, t) }) } }}
                        />
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          </Paper>
          {updateLanguage.isError && <Alert severity="error" sx={{ mb: 2 }}>{errorMessage(updateLanguage.error, t)}</Alert>}

          <Typography variant="subtitle1" sx={{ mb: 0.5 }}>{t('admin.similarity.providersTitle')}</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2, maxWidth: '75ch' }}>
            {t('admin.similarity.providersHelp')}
          </Typography>
          <Stack spacing={2}>
            {data.providers.map((provider) => (
              <ProviderCard key={provider.id} provider={provider} onSaved={() => setSaved(true)} />
            ))}
          </Stack>
        </>
      )}

      <Snackbar open={saved} autoHideDuration={3000} onClose={() => setSaved(false)} message={t('admin.similarity.saved')} />
    </Box>
  )
}

/** One provider's settings, edited in place and saved on their own. */
function ProviderCard({ provider, onSaved }: { provider: AdminSimilarityProvider; onSaved: () => void }) {
  const { t } = useTranslation()
  const update = useUpdateSimilarityProvider()
  // Null until edited: until then the card shows what core has, refetched after any save.
  const [edited, setEdited] = useState<ProviderDraft | null>(null)
  const draft = edited ?? draftOf(provider)
  const priorityValid = parsedPriority(draft) !== null
  const changed = isChanged(provider, draft)
  const help = providerHelp(provider.id, t)

  function save(body: ReturnType<typeof updateRequest>) {
    if (!body) return
    update.mutate({ id: provider.id, body }, {
      onSuccess: () => {
        setEdited(null)
        onSaved()
      },
    })
  }

  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
        <Typography variant="subtitle2" sx={{ flex: '1 1 auto' }}>{providerName(provider.id, t)}</Typography>
        <FormControlLabel
          control={<Switch size="small" checked={draft.enabled} onChange={(e) => setEdited({ ...draft, enabled: e.target.checked })} />}
          label={t('admin.similarity.providerOn')}
        />
      </Box>
      {help && <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1.5 }}>{help}</Typography>}

      <TextField
        label={t('admin.similarity.priority')}
        value={draft.priority}
        onChange={(e) => setEdited({ ...draft, priority: e.target.value })}
        size="small"
        error={!priorityValid}
        helperText={t('admin.similarity.priorityHelp', { value: provider.default_priority, max: MAX_PRIORITY })}
        inputProps={{ inputMode: 'numeric', maxLength: 4 }}
        sx={{ maxWidth: 360, mb: 1 }}
      />

      <Box sx={{ display: 'flex', flexWrap: 'wrap', columnGap: 3 }}>
        {provider.languages.map((language) => (
          <FormControlLabel
            key={language.language}
            control={
              <Switch
                size="small"
                checked={draft.languages[language.language] ?? language.enabled}
                onChange={(e) => setEdited({ ...draft, languages: { ...draft.languages, [language.language]: e.target.checked } })}
              />
            }
            label={
              <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75 }}>
                {languageName(language.language, t)}
                {language.support === 'experimental' && (
                  <Chip size="small" variant="outlined" color="warning" label={t('admin.similarity.experimental')} />
                )}
              </Box>
            }
          />
        ))}
      </Box>

      {update.isError && <Alert severity="error" sx={{ mt: 1 }}>{errorMessage(update.error, t)}</Alert>}

      <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 1, mt: 1 }}>
        <Button
          size="small"
          onClick={() => save(defaultsRequest(provider))}
          disabled={update.isPending || (!differsFromDefaults(provider) && !changed)}
        >
          {t('admin.similarity.restoreDefaults')}
        </Button>
        <Button
          size="small"
          variant="contained"
          onClick={() => save(updateRequest(provider, draft))}
          disabled={update.isPending || !changed || !priorityValid}
        >
          {update.isPending ? t('general.saving') : t('general.save')}
        </Button>
      </Box>
    </Paper>
  )
}
