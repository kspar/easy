/**
 * The course similarity settings dialog's choices, and what saving them sends (EZ-1667).
 *
 * Both go wrong without a sign. A pin an admin has since switched off is still stored and still
 * passed over on every check; a dialog that left it out would open on "Automatic", and the teacher
 * would take that for their own choice. And the mode and the provider are saved together, so a save
 * that sent a default for the one not touched would change a setting the teacher did not.
 */
import { describe, expect, test } from 'vitest'
import {
  AUTOMATIC,
  canSave,
  initialChoice,
  initialMode,
  providerChoices,
  settingsRequest,
} from '../../src/features/course-settings/courseSimilaritySettings.ts'

const providers = [
  { id: 'edit-distance', languages: ['java', 'python', 'sql', 'text'] },
  { id: 'jplag', languages: ['java', 'python', 'text'] },
]

describe('course similarity settings', () => {
  test('Automatic first, then the providers an admin has on, in their order', () => {
    const choices = providerChoices({ provider: null, mode: 'optimised', providers })
    expect(choices.map((c) => c.value)).toEqual([AUTOMATIC, 'edit-distance', 'jplag'])
    expect(choices.every((c) => c.available)).toBe(true)
    expect(choices[2].languages).toEqual(['java', 'python', 'text'])
  })

  test('a pin switched off since is shown, and opened on, but cannot be chosen again', () => {
    const settings = { provider: 'moss', mode: 'optimised', providers }
    const stale = providerChoices(settings).at(-1)
    expect(stale).toEqual({ value: 'moss', languages: [], available: false })
    expect(initialChoice(settings)).toBe('moss')
    expect(canSave(settings, 'moss', 'optimised')).toBe(false)
    // Not even to change the mode alone: core refuses to store a provider that is off.
    expect(canSave(settings, 'moss', 'full')).toBe(false)
    // Moving off it is a change, and allowed.
    expect(canSave(settings, AUTOMATIC, 'optimised')).toBe(true)
  })

  test('saving is offered for a change only, of either the provider or the mode', () => {
    const settings = { provider: 'jplag', mode: 'optimised', providers }
    expect(canSave(settings, 'jplag', 'optimised')).toBe(false)
    expect(canSave(settings, 'edit-distance', 'optimised')).toBe(true)
    expect(canSave(settings, 'jplag', 'full')).toBe(true)
    expect(canSave(settings, 'not-installed', 'optimised')).toBe(false)
  })

  test('the dialog opens on the stored mode, and on the default for one it does not know', () => {
    expect(initialMode({ provider: null, mode: 'full', providers })).toBe('full')
    expect(initialMode({ provider: null, mode: 'optimised', providers })).toBe('optimised')
    expect(initialMode({ provider: null, mode: 'everything', providers })).toBe('optimised')
  })

  test('what is sent is what is chosen, and Automatic unpins', () => {
    expect(settingsRequest(AUTOMATIC, 'full')).toEqual({ provider: null, mode: 'full' })
    expect(settingsRequest('edit-distance', 'optimised')).toEqual({ provider: 'edit-distance', mode: 'optimised' })
  })
})
