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

  describe('for one exercise', () => {
    const course = { provider: 'jplag', mode: 'full', providers }
    const withOwn = (own) => ({ ...course, exercises: [{ course_exercise_id: '7', ...own }] })

    test("an exercise with nothing of its own opens on the course's, for both", () => {
      expect(exerciseInitialChoice(course, '7')).toBe(FROM_COURSE)
      expect(exerciseInitialMode(course, '7')).toBe(FROM_COURSE)
      expect(exerciseCanSave(course, '7', FROM_COURSE, FROM_COURSE)).toBe(false)
      expect(exerciseProviderChoices(course, '7').map((c) => c.value)).toEqual([FROM_COURSE, AUTOMATIC, 'edit-distance', 'jplag'])
    })

    test('its own provider and mode each open on what is stored, apart', () => {
      // Its own provider, none: the admin's order, though the course pins JPlag - not the course's.
      const automatic = withOwn({ own_provider: true, provider: null, mode: null })
      expect(exerciseInitialChoice(automatic, '7')).toBe(AUTOMATIC)
      expect(exerciseInitialMode(automatic, '7')).toBe(FROM_COURSE)
      const modeOnly = withOwn({ own_provider: false, provider: null, mode: 'optimised' })
      expect(exerciseInitialChoice(modeOnly, '7')).toBe(FROM_COURSE)
      expect(exerciseInitialMode(modeOnly, '7')).toBe('optimised')
      // Another exercise's settings are not this one's.
      expect(exerciseInitialMode(modeOnly, '8')).toBe(FROM_COURSE)
    })

    test("its own pin switched off since is shown, but not the course's", () => {
      const stale = withOwn({ own_provider: true, provider: 'moss', mode: null })
      expect(exerciseProviderChoices(stale, '7').at(-1)).toEqual({ value: 'moss', languages: [], available: false })
      expect(exerciseCanSave(stale, '7', 'moss', 'full')).toBe(false)
      expect(exerciseCanSave(stale, '7', FROM_COURSE, FROM_COURSE)).toBe(true)
      const courseStale = { ...course, provider: 'moss' }
      expect(exerciseProviderChoices(courseStale, '7').some((c) => c.value === 'moss')).toBe(false)
    })

    test("the course's is sent as not its own, and Automatic as its own with no pin", () => {
      expect(exerciseSettingsRequest(FROM_COURSE, FROM_COURSE)).toEqual({ own_provider: false, provider: null, mode: null })
      expect(exerciseSettingsRequest(AUTOMATIC, 'full')).toEqual({ own_provider: true, provider: null, mode: 'full' })
      expect(exerciseSettingsRequest('edit-distance', FROM_COURSE)).toEqual({ own_provider: true, provider: 'edit-distance', mode: null })
    })

    test("the course's dialog names the exercises with settings of their own, and only those still on it", () => {
      const settings = { ...course, exercises: [
        { course_exercise_id: '7', own_provider: false, provider: null, mode: 'full' },
        { course_exercise_id: '9', own_provider: true, provider: null, mode: null },
      ] }
      const exercises = [{ course_exercise_id: '7', effective_title: 'Exam' }, { course_exercise_id: '8', effective_title: 'Week 1' }]
      expect(exercisesWithOwnSettings(settings, exercises)).toEqual(['Exam'])
      // An older core does not list them.
      expect(exercisesWithOwnSettings(course, exercises)).toEqual([])
    })
  })
})
