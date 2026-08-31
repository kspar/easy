import type { DirAccessLevel } from '../../api/types.ts'

/**
 * Re-exported, not defined here.
 *
 * This file had its own copy, differing from `components/spaLink.ts` only by a `stopPropagation`
 * flag — which is exactly how a codebase ends up with four of them and a sidebar that has none. The
 * flag moved to the canonical one; this export stays so the dozen `from './links.ts'` imports in
 * `features/library` and `features/articles` keep working.
 */
export { spaLinkProps } from '../../components/spaLink.ts'

/**
 * Everything a slug may keep. wui's whitelist, verbatim: letters, digits, Estonian diacritics, and
 * the four punctuation marks that read as part of a title rather than as URL syntax.
 */
const SLUG_STRIP = /[^A-Za-z0-9ÕÄÖÜŠŽõäöüšž()._\- ]/g

/**
 * A title as it appears in a URL path — readable, and never percent-encoded.
 *
 * **The one slug rule in the app.** It had four copies (here, `ExerciseLibraryPage`,
 * `CreateExerciseDialog`, `AppLayout`), three of them byte-identical and the fourth differing only
 * in how it spelled the same Unicode range — the same drift `spaLinkProps` above was consolidated
 * out of.
 *
 * This is wui's rule rather than the one those four shared, because percent-encoding a slug breaks
 * embeds (EZ-1831). `EmbedDialog` built its path with `encodeURIComponent`, so a snippet copied out
 * of v4 carried `Kodu%C3%BClesanne%201.2%20Arvutamine`; the resizer script published pages load
 * runs `decodeURI` over the URL the iframe reports and matches it against the `src` attribute, so
 * the encoded spelling never matched and every new embed kept its 150px default height. wui emitted
 * `Koduülesanne-1.2-Arvutamine`, which survives that round trip — and reads better besides, which is
 * the entire point of putting a title in a URL nobody has to type.
 *
 * Case is kept, again as wui had it: `Koduülesanne-1.2-Arvutamine`, not `koduülesanne-12-arvutamine`.
 * The old copies lowercased *and* stripped `.`, which quietly turned "1.2" into "12". Nothing routes
 * on a slug — every path carrying one ends in a `*` splat — so this changes appearance only.
 *
 * Two deliberate departures from wui, both about not emitting something ugly: runs of separators
 * collapse to one hyphen, and a leading or trailing hyphen is trimmed. wui would render a title with
 * a double space as `A--B`.
 */
export function slugify(s: string): string {
  return s
    .replace(SLUG_STRIP, '')
    .replace(/\s+/g, '-')
    .replace(/-{2,}/g, '-')
    .replace(/^-+|-+$/g, '')
}

export function dirLink(id: string, name: string): string {
  return `/library/dir/${id}/${slugify(name)}`
}

export function exerciseLink(id: string, title: string): string {
  return `/library/exercise/${id}/${slugify(title)}`
}

const ACCESS_ORDER: DirAccessLevel[] = ['P', 'PR', 'PRA', 'PRAW', 'PRAWM']

export function hasAccess(level: DirAccessLevel, required: DirAccessLevel): boolean {
  return ACCESS_ORDER.indexOf(level) >= ACCESS_ORDER.indexOf(required)
}
