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
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from '@mui/material'
import { useTranslation } from 'react-i18next'
import {
  useCourseSimilaritySettings,
  useUpdateCourseExerciseSimilaritySettings,
  useUpdateCourseSimilaritySettings,
} from '../../api/courses.ts'
import { errorMessage } from '../../api/errorMessage.ts'
import { languageName, providerHelp, providerName } from '../similarity/similarityProvider.ts'
import {
  AUTOMATIC,
  canSave,
  exerciseCanSave,
  exerciseInitialChoice,
  exerciseInitialMode,
  exerciseProviderChoices,
  exerciseSettingsRequest,
  exercisesWithOwnSettings,
  FROM_COURSE,
  initialChoice,
  initialMode,
  providerChoices,
  settingsRequest,
} from './courseSimilaritySettings.ts'
import type { ExerciseMode } from './courseSimilaritySettings.ts'

/** A course exercise, as the similarity page has it. */
interface CourseExerciseRef {
  course_exercise_id: string
  effective_title: string
}

/**
 * How a course's similarity checks are compared (EZ-1667): by which provider - Automatic, which follows
 * the order an administrator set for each language, or one pinned for the whole course - and how many of
 * the pairs: the likeliest first, stopping at the time limit with what it found, or every one of them.
 *
 * Given an [exercise], the same for that exercise alone, over the course's: each choice then starts with
 * "As for the course", which is also how an exercise goes back to following it.
 */
export default function CourseSimilaritySettingsDialog({
  courseId,
  open,
  onClose,
  exercise,
  exercises = [],
}: {
  courseId: string
  open: boolean
  onClose: () => void
  /** The exercise the page has selected, if any: its settings can be set apart from the course's. */
  exercise?: CourseExerciseRef
  /** The course's exercises, to name those with settings of their own. */
  exercises?: CourseExerciseRef[]
}) {
  const { t } = useTranslation()
  const { data: settings, isLoading, isFetching, isError, error } = useCourseSimilaritySettings(courseId, open)
  const updateCourse = useUpdateCourseSimilaritySettings(courseId)
  const updateExercise = useUpdateCourseExerciseSimilaritySettings(courseId)
  // Null until the teacher picks: until then the dialog shows what is stored, read fresh each time it
  // opens, rather than a copy taken once and left behind by a save from another tab.
  const [picked, setPicked] = useState<string | null>(null)
  const [pickedMode, setPickedMode] = useState<ExerciseMode | null>(null)
  const [scope, setScope] = useState<'course' | 'exercise'>('course')
  const [saved, setSaved] = useState(false)
  const forExercise = scope === 'exercise' && exercise !== undefined
  const exerciseId = exercise?.course_exercise_id ?? ''

  const stored = settings && (forExercise
    ? { choice: exerciseInitialChoice(settings, exerciseId), mode: exerciseInitialMode(settings, exerciseId) }
    : { choice: initialChoice(settings), mode: initialMode(settings) as ExerciseMode })
  const choice = picked ?? stored?.choice ?? AUTOMATIC
  const mode = pickedMode ?? stored?.mode ?? 'optimised'
  const choices = settings ? (forExercise ? exerciseProviderChoices(settings, exerciseId) : providerChoices(settings)) : []
  const savable = settings !== undefined && (forExercise
    ? exerciseCanSave(settings, exerciseId, choice, mode)
    : mode !== FROM_COURSE && canSave(settings, choice, mode))
  const update = forExercise ? updateExercise : updateCourse
  const ownTitles = settings && !forExercise ? exercisesWithOwnSettings(settings, exercises) : []
  const modeChoices: ExerciseMode[] = forExercise ? [FROM_COURSE, 'optimised', 'full'] : ['optimised', 'full']

  function close() {
    setPicked(null)
    setPickedMode(null)
    setScope('course')
    onClose()
  }

  function changeScope(next: 'course' | 'exercise' | null) {
    if (next === null) return
    // A choice made for one scope means nothing in the other.
    setPicked(null)
    setPickedMode(null)
    setScope(next)
  }

  function done() {
    close()
    setSaved(true)
  }

  function handleSave() {
    if (!settings) return
    if (forExercise) {
      updateExercise.mutate({ courseExerciseId: exerciseId, body: exerciseSettingsRequest(choice, mode) }, { onSuccess: done })
    } else if (mode !== FROM_COURSE) {
      updateCourse.mutate(settingsRequest(choice, mode), { onSuccess: done })
    }
  }

  function modeLabel(value: ExerciseMode): string {
    if (value === FROM_COURSE) return t('similarity.settingsFromCourse')
    return value === 'optimised' ? t('similarity.settingsModeOptimised') : t('similarity.settingsModeFull')
  }

  function modeHelp(value: ExerciseMode): string {
    if (value === FROM_COURSE) return settings ? modeLabel(initialMode(settings)) : ''
    return value === 'optimised' ? t('similarity.settingsModeOptimisedHelp') : t('similarity.settingsModeFullHelp')
  }

  return (
    <>
      <Dialog open={open} onClose={close} maxWidth="sm" fullWidth>
        <DialogTitle>{t('similarity.settingsTitle')}</DialogTitle>
        <DialogContent sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: '8px !important' }}>
          {exercise && (
            <Box>
              <Typography variant="subtitle2" sx={{ mb: 0.5 }}>{t('similarity.settingsScope')}</Typography>
              <ToggleButtonGroup
                size="small"
                exclusive
                value={scope}
                onChange={(_, next) => changeScope(next)}
                aria-label={t('similarity.settingsScope')}
              >
                <ToggleButton value="course">{t('similarity.settingsScopeCourse')}</ToggleButton>
                <ToggleButton value="exercise">{t('similarity.settingsScopeExercise', { title: exercise.effective_title })}</ToggleButton>
              </ToggleButtonGroup>
            </Box>
          )}

          <Typography variant="body2" color="text.secondary">
            {forExercise ? t('similarity.settingsExerciseHelp') : t('similarity.settingsHelp')}
          </Typography>

          {isLoading && <CircularProgress size={24} sx={{ alignSelf: 'center' }} />}
          {isError && <Alert severity="error">{errorMessage(error, t)}</Alert>}

          {ownTitles.length > 0 && (
            <Typography variant="caption" color="text.secondary">
              {t('similarity.settingsOwnExercises', { titles: ownTitles.join(', ') })}
            </Typography>
          )}

          {settings && (
            <RadioGroup
              aria-label={t('similarity.settingsTitle')}
              value={choice}
              onChange={(e) => setPicked(e.target.value)}
              sx={{ gap: 1 }}
            >
              {choices.map((c) => {
                const fromCourse = c.value === FROM_COURSE
                const automatic = c.value === AUTOMATIC
                const help = fromCourse
                  ? (settings.provider === null ? t('similarity.settingsAutomatic') : providerName(settings.provider, t))
                  : automatic ? t('similarity.settingsAutomaticHelp') : providerHelp(c.value, t)
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
                          {fromCourse ? t('similarity.settingsFromCourse') : automatic ? t('similarity.settingsAutomatic') : providerName(c.value, t)}
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
                onChange={(e) => setPickedMode(modeChoices.find((m) => m === e.target.value) ?? 'optimised')}
                sx={{ gap: 1 }}
              >
                {modeChoices.map((value) => (
                  <FormControlLabel
                    key={value}
                    value={value}
                    control={<Radio size="small" />}
                    sx={{ alignItems: 'flex-start', '& .MuiRadio-root': { pt: 0.25 } }}
                    label={
                      <Box>
                        <Typography variant="body2" sx={{ fontWeight: 500 }}>{modeLabel(value)}</Typography>
                        <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                          {modeHelp(value)}
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
            disabled={!settings || isFetching || !savable || update.isPending}
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
