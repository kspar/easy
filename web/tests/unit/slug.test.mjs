/**
 * The one slug rule (`src/features/library/links.ts`).
 *
 * A slug is decoration — every route carrying one ends in a `*` splat, so nothing resolves on it —
 * which is exactly why getting it wrong is invisible. It was wrong for a month: `EmbedDialog` built
 * its path with `encodeURIComponent`, and the parent-side resizer script that published embeds load
 * matches the iframe by running `decodeURI` over the URL the frame reports. A percent-encoded slug
 * decodes to something the `src` attribute never said, so no iframe was found, the height was never
 * applied, and every embed published from v4 sat at the iframe default of 150px with the exercise
 * cut off. A teacher reported it as "the exercise does not fit in the embed window" (EZ-1831).
 *
 * So the last test here is the one with teeth: the round trip through `decodeURI` has to be the
 * identity, which is the property the resizer script has always depended on and nothing checked.
 * It fails against `encodeURIComponent`, and against any rule that lets a space or a `%` through.
 */
import { describe, expect, test } from 'vitest'
import { slugify } from '../../src/features/library/links.ts'

describe('slugify', () => {
  test('the reported case, as wui spelled it', () => {
    expect(slugify('Koduülesanne 1.2 Arvutamine')).toBe('Koduülesanne-1.2-Arvutamine')
  })

  test('keeps case and Estonian letters, rather than transliterating or lowercasing', () => {
    expect(slugify('Šokolaad ja Žanrid ÕÄÖÜ')).toBe('Šokolaad-ja-Žanrid-ÕÄÖÜ')
  })

  test('keeps the punctuation that reads as part of a title', () => {
    // The old copies stripped `.`, which quietly turned "1.2" into "12".
    expect(slugify('Hinne (2. osa)_lõpp-1.0')).toBe('Hinne-(2.-osa)_lõpp-1.0')
  })

  test('strips what would be URL syntax', () => {
    expect(slugify('a?b#c/d&e=f%g')).toBe('abcdefg')
  })

  test('collapses runs and trims, so no slug ends up with -- or a dangling -', () => {
    expect(slugify('  A   B  ')).toBe('A-B')
    expect(slugify('??? ???')).toBe('')
  })

  test('an untitled thing slugs to nothing rather than to junk', () => {
    expect(slugify('')).toBe('')
  })

  test('survives decodeURI unchanged — the property the resizer script relies on', () => {
    const titles = [
      'Koduülesanne 1.2 Arvutamine',
      'Šokolaad ja Žanrid ÕÄÖÜ',
      'Hinne (2. osa)_lõpp-1.0',
      'Ülesanne 3.1. Hinde kujunemine',
    ]
    for (const title of titles) {
      const url = `https://lahendus.ut.ee/embed/exercises/1202/${slugify(title)}`
      // What the browser reports for a frame loaded at `url`, then what the resizer does to it.
      const reported = new URL(url).href
      expect(decodeURI(reported.split('?')[0])).toBe(url)
    }
  })

  test('encodeURIComponent does not — this is the regression, pinned', () => {
    const url = `https://lahendus.ut.ee/embed/exercises/1202/${encodeURIComponent('Koduülesanne 1.2 Arvutamine')}`
    expect(decodeURI(new URL(url).href.split('?')[0])).not.toBe(url)
  })
})
