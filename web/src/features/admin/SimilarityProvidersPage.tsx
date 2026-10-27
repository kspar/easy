import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  FormControlLabel,
  IconButton,
  Paper,
  Snackbar,
  Stack,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material'
import { ArrowDownwardOutlined, ArrowUpwardOutlined } from '@mui/icons-material'
import { useAdminSimilarityProviders, useUpdateSimilarityLanguage, useUpdateSimilarityProvider } from '../../api/similarityProviders.ts'
import type { AdminSimilarityProvider } from '../../api/types.ts'
import { errorMessage } from '../../api/errorMessage.ts'
import usePageTitle from '../../hooks/usePageTitle.ts'
import { languageName, providerHelp, providerName } from '../similarity/similarityProvider.ts'
import {
  defaultsRequest,
  differsFromDefaults,
  draftOf,
  languageVerdict,
  moveUpRequests,
  pinnedCount,
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
            {data.providers.map((provider, index) => (
              <ProviderCard
                key={provider.id}
                provider={provider}
                providers={data.providers}
                index={index}
                onSaved={() => setSaved(true)}
              />
            ))}
          </Stack>
        </>
      )}

      <Snackbar open={saved} autoHideDuration={3000} onClose={() => setSaved(false)} message={t('admin.similarity.saved')} />
    </Box>
  )
}

/**
 * One provider's settings, each change saved as it is made - as the languages above are: a card that
 * waited for its own Save beside a table that did not left an admin unsure which of their changes had
 * applied. Its place in the order is moved up or down rather than typed as a number.
 */
function ProviderCard({
  provider,
  providers,
  index,
  onSaved,
}: {
  provider: AdminSimilarityProvider
  providers: AdminSimilarityProvider[]
  index: number
  onSaved: () => void
}) {
  const { t } = useTranslation()
  const update = useUpdateSimilarityProvider()
  const draft = draftOf(provider)
  const help = providerHelp(provider.id, t)
  // Every card has an "On" switch, order buttons and the same languages: named by their labels alone, a
  // screen reader could not say whose they are.
  const name = providerName(provider.id, t)
  const pinned = pinnedCount(provider)

  function save(edited: ProviderDraft) {
    const body = updateRequest(provider, edited)
    if (body) update.mutate({ id: provider.id, body }, { onSuccess: onSaved })
  }

  async function move(requests: ReturnType<typeof moveUpRequests>) {
    // One after the other: the second is read against the order the first left.
    for (const request of requests) await update.mutateAsync(request)
    if (requests.length > 0) onSaved()
  }

  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
        <Typography variant="subtitle2" sx={{ flex: '1 1 auto' }}>{`${index + 1}. ${name}`}</Typography>
        <IconButton
          size="small"
          disabled={index === 0 || update.isPending}
          onClick={() => move(moveUpRequests(providers, index))}
          aria-label={t('admin.similarity.moveUp', { provider: name })}
        >
          <ArrowUpwardOutlined fontSize="small" />
        </IconButton>
        <IconButton
          size="small"
          disabled={index === providers.length - 1 || update.isPending}
          onClick={() => move(moveUpRequests(providers, index + 1))}
          aria-label={t('admin.similarity.moveDown', { provider: name })}
        >
          <ArrowDownwardOutlined fontSize="small" />
        </IconButton>
        <FormControlLabel
          control={
            <Switch
              size="small"
              checked={draft.enabled}
              disabled={update.isPending}
              onChange={(e) => save({ ...draft, enabled: e.target.checked })}
              slotProps={{ input: { 'aria-label': t('admin.similarity.providerSwitchName', { provider: name }) } }}
            />
          }
          label={t('admin.similarity.providerOn')}
        />
      </Box>
      {help && <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1 }}>{help}</Typography>}
      {/* Said before the switch is used: courses that chose it would move to Automatic without a word. */}
      {pinned > 0 && (
        <Typography variant="caption" color="warning.main" sx={{ display: 'block', mb: 1 }}>
          {t('admin.similarity.pinned', { count: pinned })}
        </Typography>
      )}

      <Box sx={{ display: 'flex', flexWrap: 'wrap', columnGap: 3 }}>
        {provider.languages.map((language) => (
          <FormControlLabel
            key={language.language}
            control={
              <Switch
                size="small"
                checked={draft.languages[language.language] ?? language.enabled}
                disabled={update.isPending}
                onChange={(e) => save({ ...draft, languages: { ...draft.languages, [language.language]: e.target.checked } })}
                slotProps={{ input: { 'aria-label': t('admin.similarity.providerLanguageName', { provider: name, language: languageName(language.language, t) }) } }}
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
          onClick={() => update.mutate({ id: provider.id, body: defaultsRequest(provider) }, { onSuccess: onSaved })}
          disabled={update.isPending || !differsFromDefaults(provider)}
        >
          {t('admin.similarity.restoreDefaults')}
        </Button>
      </Box>
    </Paper>
  )
}
