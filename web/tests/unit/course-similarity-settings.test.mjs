/**
 * The course similarity settings dialog's choices, and what saving one sends (EZ-1667).
 *
 * Both go wrong without a sign. A pin an admin has since switched off is still stored and still
 * passed over on every check; a dialog that left it out would open on "Automatic", and the teacher
 * would take that for their own choice. And the dialog does not offer the mode yet, so a save that
 * sent a default would change a setting the teacher cannot see.
 */
import { describe, expect, test } from 'vitest'
import {
  AUTOMATIC,
  canSave,
  initialChoice,
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
    expect(canSave(settings, 'moss')).toBe(false)
    // Moving off it is a change, and allowed.
    expect(canSave(settings, AUTOMATIC)).toBe(true)
  })

  test('saving is offered for a change only', () => {
    const settings = { provider: 'jplag', mode: 'optimised', providers }
    expect(canSave(settings, 'jplag')).toBe(false)
    expect(canSave(settings, 'edit-distance')).toBe(true)
    expect(canSave(settings, 'not-installed')).toBe(false)
  })

  test('the stored mode goes back unchanged, and Automatic unpins', () => {
    const settings = { provider: 'jplag', mode: 'full', providers }
    expect(settingsRequest(settings, AUTOMATIC)).toEqual({ provider: null, mode: 'full' })
    expect(settingsRequest(settings, 'edit-distance')).toEqual({ provider: 'edit-distance', mode: 'full' })
  })
})
