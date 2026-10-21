import type { CourseSimilaritySettings, CourseSimilaritySettingsReq } from '../../api/types.ts'

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

export interface ProviderChoice {
  /** The control's value: a provider id, or [AUTOMATIC]. */
  value: string
  /** The languages it compares in this course now; empty for Automatic, and for a pin switched off. */
  languages: string[]
  /** False for a pinned provider an admin has since switched off: shown, so the teacher knows, but not choosable. */
  available: boolean
}

export function providerChoices(settings: CourseSimilaritySettings): ProviderChoice[] {
  const choices: ProviderChoice[] = [
    { value: AUTOMATIC, languages: [], available: true },
    ...settings.providers.map((p) => ({ value: p.id, languages: p.languages, available: true })),
  ]
  if (settings.provider !== null && !settings.providers.some((p) => p.id === settings.provider)) {
    choices.push({ value: settings.provider, languages: [], available: false })
  }
  return choices
}

/** What the dialog opens on: the stored pin, switched off or not - it is still what is stored. */
export function initialChoice(settings: CourseSimilaritySettings): string {
  return settings.provider ?? AUTOMATIC
}

/** The stored mode, read as one of the two; anything else core might send is the default. */
export function initialMode(settings: CourseSimilaritySettings): Mode {
  return settings.mode === 'full' ? 'full' : 'optimised'
}

/** Saving is offered when something changed, and the provider chosen can be chosen. */
export function canSave(settings: CourseSimilaritySettings, choice: string, mode: Mode): boolean {
  const available = providerChoices(settings).some((c) => c.value === choice && c.available)
  const changed = choice !== initialChoice(settings) || mode !== initialMode(settings)
  // Not with a pin switched off since, even to change the mode alone: core refuses to store a provider
  // that is off, so the teacher moves off it first.
  return changed && available
}

export function settingsRequest(choice: string, mode: Mode): CourseSimilaritySettingsReq {
  return { provider: choice === AUTOMATIC ? null : choice, mode }
}
