import type { CourseSimilaritySettings, CourseSimilaritySettingsReq } from '../../api/types.ts'

/**
 * The choices in the course's similarity settings dialog (EZ-1667), and what saving one sends.
 *
 * Here rather than in the dialog because both go wrong invisibly. A pin the admin has since switched
 * off is still stored, and still passed over on every check; left out of the choices, the dialog would
 * open on "Automatic" and say nothing, and the teacher would believe that was what they had chosen.
 * And the mode is not offered yet, so a save that sent a default instead of the stored mode would
 * quietly change a setting the teacher cannot see.
 */

/** The value of the "Automatic" choice: no pin, so the admin's order decides. */
export const AUTOMATIC = ''

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

/** Saving is offered for an available choice other than what is stored. */
export function canSave(settings: CourseSimilaritySettings, choice: string): boolean {
  const available = providerChoices(settings).some((c) => c.value === choice && c.available)
  return available && choice !== initialChoice(settings)
}

/** The stored mode goes back as it came: the dialog does not offer it yet, so it must not change it. */
export function settingsRequest(settings: CourseSimilaritySettings, choice: string): CourseSimilaritySettingsReq {
  return { provider: choice === AUTOMATIC ? null : choice, mode: settings.mode }
}
