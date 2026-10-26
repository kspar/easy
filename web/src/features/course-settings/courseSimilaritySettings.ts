import type {
  CourseExerciseSimilaritySettings,
  CourseExerciseSimilaritySettingsReq,
  CourseSimilaritySettings,
  CourseSimilaritySettingsReq,
} from '../../api/types.ts'

/**
 * The choices in the course's similarity settings dialog (EZ-1667), and what saving them sends.
 *
 * Here rather than in the dialog because both go wrong invisibly. A pin the admin has since switched
 * off is still stored, and still passed over on every check; left out of the choices, the dialog would
 * open on "Automatic" and say nothing, and the teacher would believe that was what they had chosen.
 */

/** The value of the "Automatic" choice: no pin, so the admin's order decides. */
export const AUTOMATIC = ''

/** The two modes, as core names them: the likeliest pairs first, or every pair. */
export type Mode = 'optimised' | 'full'

/** Which of each student's submissions is compared: what they handed in last, or their first attempt. */
export type Attempts = 'latest' | 'first'

export interface ProviderChoice {
  /** The control's value: a provider id, or [AUTOMATIC]. */
  value: string
  /** The languages it compares in this course now; empty for Automatic, and for a pin switched off. */
  languages: string[]
  /** False for a pinned provider an admin has since switched off: shown, so the teacher knows, but not choosable. */
  available: boolean
}

/** [pinned] is the pin stored where the choice is made - the course's, or the exercise's own. */
export function providerChoices(settings: CourseSimilaritySettings, pinned: string | null = settings.provider): ProviderChoice[] {
  const choices: ProviderChoice[] = [
    { value: AUTOMATIC, languages: [], available: true },
    ...settings.providers.map((p) => ({ value: p.id, languages: p.languages, available: true })),
  ]
  if (pinned !== null && !settings.providers.some((p) => p.id === pinned)) {
    choices.push({ value: pinned, languages: [], available: false })
  }
  return choices
}

/**
 * What Automatic means for [language] now: the first provider in the admin's order that compares it, or
 * failing that one that compares plain text, which is what the check then does. Said beside "Automatic",
 * which named no method at all: the teacher could not tell what they would be choosing. Null when no
 * provider would compare it, or the language is not known yet.
 */
export function automaticFor(settings: CourseSimilaritySettings, language: string | undefined): { provider: string; asText: boolean } | null {
  if (!language) return null
  const own = settings.providers.find((p) => p.languages.includes(language))
  if (own) return { provider: own.id, asText: false }
  const text = settings.providers.find((p) => p.languages.includes('text'))
  return text ? { provider: text.id, asText: true } : null
}

/** What the dialog opens on: the stored pin, switched off or not - it is still what is stored. */
export function initialChoice(settings: CourseSimilaritySettings): string {
  return settings.provider ?? AUTOMATIC
}

/** The stored mode, read as one of the two; anything else core might send is the default. */
export function initialMode(settings: CourseSimilaritySettings): Mode {
  return settings.mode === 'full' ? 'full' : 'optimised'
}

/** The stored attempts, read as one of the two; an older core, which has none, compares the latest. */
export function initialAttempts(settings: CourseSimilaritySettings): Attempts {
  return settings.attempts === 'first' ? 'first' : 'latest'
}

/** Saving is offered when something changed, and the provider chosen can be chosen. */
export function canSave(settings: CourseSimilaritySettings, choice: string, mode: Mode, attempts: Attempts): boolean {
  const available = providerChoices(settings).some((c) => c.value === choice && c.available)
  const changed = choice !== initialChoice(settings) || mode !== initialMode(settings) || attempts !== initialAttempts(settings)
  // Not with a pin switched off since, even to change the mode alone: core refuses to store a provider
  // that is off, so the teacher moves off it first.
  return changed && available
}

export function settingsRequest(choice: string, mode: Mode, attempts: Attempts): CourseSimilaritySettingsReq {
  return { provider: choice === AUTOMATIC ? null : choice, mode, attempts }
}

/**
 * One exercise's own settings, over the course's (EZ-1667). Each choice gains one more, first: the
 * course's, whatever it is - for the provider and for the mode apart, since an exam may compare every pair
 * on the course's provider. Its value is not a provider id, nor [AUTOMATIC], which on an exercise means
 * the admin's order even where the course pins a provider.
 */
export const FROM_COURSE = '@course'

export type ExerciseMode = Mode | typeof FROM_COURSE

export type ExerciseAttempts = Attempts | typeof FROM_COURSE

function own(settings: CourseSimilaritySettings, courseExerciseId: string): CourseExerciseSimilaritySettings | undefined {
  return settings.exercises?.find((e) => e.course_exercise_id === courseExerciseId)
}

export function exerciseProviderChoices(settings: CourseSimilaritySettings, courseExerciseId: string): ProviderChoice[] {
  const exercise = own(settings, courseExerciseId)
  const pinned = exercise?.own_provider ? exercise.provider : null
  return [{ value: FROM_COURSE, languages: [], available: true }, ...providerChoices(settings, pinned)]
}

export function exerciseInitialChoice(settings: CourseSimilaritySettings, courseExerciseId: string): string {
  const exercise = own(settings, courseExerciseId)
  if (!exercise?.own_provider) return FROM_COURSE
  return exercise.provider ?? AUTOMATIC
}

export function exerciseInitialMode(settings: CourseSimilaritySettings, courseExerciseId: string): ExerciseMode {
  const mode = own(settings, courseExerciseId)?.mode
  return mode === 'full' || mode === 'optimised' ? mode : FROM_COURSE
}

export function exerciseInitialAttempts(settings: CourseSimilaritySettings, courseExerciseId: string): ExerciseAttempts {
  const attempts = own(settings, courseExerciseId)?.attempts
  return attempts === 'latest' || attempts === 'first' ? attempts : FROM_COURSE
}

export function exerciseCanSave(
  settings: CourseSimilaritySettings,
  courseExerciseId: string,
  choice: string,
  mode: ExerciseMode,
  attempts: ExerciseAttempts,
): boolean {
  const available = exerciseProviderChoices(settings, courseExerciseId).some((c) => c.value === choice && c.available)
  const changed = choice !== exerciseInitialChoice(settings, courseExerciseId) ||
    mode !== exerciseInitialMode(settings, courseExerciseId) ||
    attempts !== exerciseInitialAttempts(settings, courseExerciseId)
  return changed && available
}

/** The course's for every setting is sent as none its own, which core stores as no settings at all. */
export function exerciseSettingsRequest(choice: string, mode: ExerciseMode, attempts: ExerciseAttempts): CourseExerciseSimilaritySettingsReq {
  return {
    own_provider: choice !== FROM_COURSE,
    provider: choice === FROM_COURSE || choice === AUTOMATIC ? null : choice,
    mode: mode === FROM_COURSE ? null : mode,
    attempts: attempts === FROM_COURSE ? null : attempts,
  }
}

/**
 * The course's exercises that have settings of their own, by title, for the course's dialog to name: a
 * teacher changing the course's settings would otherwise expect every exercise to follow. An exercise
 * not in [exercises] - removed from the course since - is left out.
 */
export function exercisesWithOwnSettings(
  settings: CourseSimilaritySettings,
  exercises: { course_exercise_id: string; effective_title: string }[],
): string[] {
  const titleById = new Map(exercises.map((e) => [e.course_exercise_id, e.effective_title]))
  return (settings.exercises ?? []).flatMap((e) => titleById.get(e.course_exercise_id) ?? [])
}
