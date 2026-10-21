import { useState } from 'react'
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  Radio,
  RadioGroup,
  Snackbar,
  Typography,
} from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useCourseSimilaritySettings, useUpdateCourseSimilaritySettings } from '../../api/courses.ts'
import { errorMessage } from '../../api/errorMessage.ts'
import { languageName, providerHelp, providerName } from '../similarity/similarityProvider.ts'
import { AUTOMATIC, canSave, initialChoice, initialMode, providerChoices, settingsRequest } from './courseSimilaritySettings.ts'
import type { Mode } from './courseSimilaritySettings.ts'

/**
 * How a course's similarity checks are compared (EZ-1667): by which provider - Automatic, which follows
 * the order an administrator set for each language, or one pinned for the whole course - and how many of
 * the pairs: the likeliest first, stopping at the time limit with what it found, or every one of them.
 */
export default function CourseSimilaritySettingsDialog({
  courseId,
  open,
  onClose,
}: {
  courseId: string
  open: boolean
  onClose: () => void
}) {
  const { t } = useTranslation()
  const { data: settings, isLoading, isFetching, isError, error } = useCourseSimilaritySettings(courseId, open)
  const update = useUpdateCourseSimilaritySettings(courseId)
  // Null until the teacher picks: until then the dialog shows what is stored, read fresh each time it
  // opens, rather than a copy taken once and left behind by a save from another tab.
  const [picked, setPicked] = useState<string | null>(null)
  const [pickedMode, setPickedMode] = useState<Mode | null>(null)
  const [saved, setSaved] = useState(false)
  const choice = picked ?? (settings ? initialChoice(settings) : AUTOMATIC)
  const mode = pickedMode ?? (settings ? initialMode(settings) : 'optimised')
  const choices = settings ? providerChoices(settings) : []

  function close() {
    setPicked(null)
    setPickedMode(null)
    onClose()
  }

  function handleSave() {
    if (!settings) return
    update.mutate(settingsRequest(choice, mode), {
      onSuccess: () => {
        close()
        setSaved(true)
      },
    })
  }

  return (
    <>
      <Dialog open={open} onClose={close} maxWidth="sm" fullWidth>
        <DialogTitle>{t('similarity.settingsTitle')}</DialogTitle>
        <DialogContent sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: '8px !important' }}>
          <Typography variant="body2" color="text.secondary">
            {t('similarity.settingsHelp')}
          </Typography>

          {isLoading && <CircularProgress size={24} sx={{ alignSelf: 'center' }} />}
          {isError && <Alert severity="error">{errorMessage(error, t)}</Alert>}

          {settings && (
            <RadioGroup
              aria-label={t('similarity.settingsTitle')}
              value={choice}
              onChange={(e) => setPicked(e.target.value)}
              sx={{ gap: 1 }}
            >
              {choices.map((c) => {
                const automatic = c.value === AUTOMATIC
                const help = automatic ? t('similarity.settingsAutomaticHelp') : providerHelp(c.value, t)
                return (
                  <FormControlLabel
                    key={c.value}
                    value={c.value}
                    disabled={!c.available}
                    control={<Radio size="small" />}
                    sx={{ alignItems: 'flex-start', '& .MuiRadio-root': { pt: 0.25 } }}
                    label={
                      <Box>
                        <Typography variant="body2" sx={{ fontWeight: 500 }}>
                          {automatic ? t('similarity.settingsAutomatic') : providerName(c.value, t)}
                        </Typography>
                        {help && (
                          <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                            {help}
                          </Typography>
                        )}
                        {c.languages.length > 0 && (
                          <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                            {t('similarity.settingsLanguages', { languages: c.languages.map((l) => languageName(l, t)).join(', ') })}
                          </Typography>
                        )}
                        {/* Still stored, and still passed over on every check: said here, where the
                            teacher would otherwise believe Automatic is what they chose. */}
                        {!c.available && (
                          <Typography variant="caption" color="warning.main" sx={{ display: 'block' }}>
                            {t('similarity.settingsUnavailable')}
                          </Typography>
                        )}
                      </Box>
                    }
                  />
                )
              })}
            </RadioGroup>
          )}

          {settings && (
            <>
              <Typography variant="subtitle2" sx={{ mt: 1 }}>{t('similarity.settingsModeTitle')}</Typography>
              <RadioGroup
                aria-label={t('similarity.settingsModeTitle')}
                value={mode}
                onChange={(e) => setPickedMode(e.target.value === 'full' ? 'full' : 'optimised')}
                sx={{ gap: 1 }}
              >
                {(['optimised', 'full'] as const).map((value) => (
                  <FormControlLabel
                    key={value}
                    value={value}
                    control={<Radio size="small" />}
                    sx={{ alignItems: 'flex-start', '& .MuiRadio-root': { pt: 0.25 } }}
                    label={
                      <Box>
                        <Typography variant="body2" sx={{ fontWeight: 500 }}>
                          {value === 'optimised' ? t('similarity.settingsModeOptimised') : t('similarity.settingsModeFull')}
                        </Typography>
                        <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                          {value === 'optimised' ? t('similarity.settingsModeOptimisedHelp') : t('similarity.settingsModeFullHelp')}
                        </Typography>
                      </Box>
                    }
                  />
                ))}
              </RadioGroup>
            </>
          )}

          {update.isError && (
            <Typography variant="caption" color="error">
              {errorMessage(update.error, t)}
            </Typography>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={close}>{t('general.cancel')}</Button>
          <Button
            onClick={handleSave}
            variant="contained"
            // Not until the fresh read is in: a save sends the stored mode back as read.
            disabled={!settings || isFetching || !canSave(settings, choice, mode) || update.isPending}
          >
            {update.isPending ? t('general.saving') : t('general.save')}
          </Button>
        </DialogActions>
      </Dialog>

      <Snackbar
        open={saved}
        autoHideDuration={3000}
        onClose={() => setSaved(false)}
        message={t('similarity.settingsSaved')}
      />
    </>
  )
}
