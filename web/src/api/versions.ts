import { useQuery } from '@tanstack/react-query'
import type { Locale } from 'date-fns'
import { formatDateTime } from '../i18n/dateLocale.ts'
import { apiFetch } from './client.ts'

/**
 * What is deployed (EZ-1709).
 *
 * **Teacher and admin only, since EZ-1782.** It was `permitAll()` and fetched with `noAuth` for its
 * first year, on the argument that the bug report needing a version most is the one from somebody who
 * could not sign in. That is no longer the trade being made, so the endpoint moved off `/unauth/` and
 * this asks with a session.
 *
 * Web's own version is not in here — it is baked into this bundle at build time, see
 * `build-info.d.ts`. It is still gated with the rest, because "which versions is this deployment
 * running" is now one question with one audience rather than one public half and one private half.
 */

export interface ComponentVersion {
  version: string
  commit: string
  /** ISO timestamp, absent when core was built without build-info. */
  built_at: string | null
}

/**
 * One grading library inside one image (EZ-1781).
 *
 * `declared` is the version the pins file asked for; `installed` is what is actually in the image.
 * They match for every image CI built, because its own smoke check refuses to publish otherwise — so
 * a mismatch means somebody's belief about a host is wrong, and that is what the row shows.
 *
 * Either can be null: an unpinned library declares nothing, and a version that cannot be read out of
 * an unlabelled image has nothing installed.
 */
export interface GradingLibrary {
  name: string
  declared: string | null
  installed: string | null
}

export interface GradingImage {
  name: string
  /** When the image was built. Often the more useful field: it answers "was this ever rebuilt?". */
  created_at: string | null
  /** How the versions were established — `label`, `pip`, or `unknown`. Diagnostic; not rendered. */
  source: string | null
  libraries: GradingLibrary[]
}

export interface ExecutorVersion {
  name: string
  /** Null when the executor did not answer. */
  version: string | null
  commit: string | null
  /** aae has no build step, so this is when its source was last written to its host. */
  built_at: string | null
  reachable: boolean
  /**
   * Empty for an executor that did not answer, one running an aae that predates this, and one whose
   * Docker daemon is down. All three mean "we cannot say" and render the same way.
   */
  grading_images: GradingImage[]
}

export interface Versions {
  core: ComponentVersion
  executors: ExecutorVersion[]
}

export function useVersions(enabled: boolean) {
  return useQuery({
    queryKey: ['versions'],
    queryFn: () => apiFetch<Versions>('/versions'),
    // Not asked for at all unless the viewer may have it, so a student loading the About page does
    // not spend a request earning a 403. Same shape as `useOperatingInfo`.
    enabled,
    // A deployed version changes only when someone deploys, and core caches the executor half for
    // five minutes anyway — re-asking on every focus would be pure noise.
    staleTime: 5 * 60 * 1000,
    retry: false,
  })
}

/** `v4.0 (b14b916)`, or just the version when there is no usable commit. */
export function formatVersion(version: string, commit?: string | null): string {
  const hasCommit = commit && commit !== 'unknown'
  return hasCommit ? `v${version} (${commit})` : `v${version}`
}

/**
 * `silmused 1.7.11`, or `pillow 12.3.0, requests 2.34.2` for an image with more than one.
 *
 * Empty string when nothing is known, so the caller can substitute its own wording rather than
 * render a blank cell.
 *
 * Shows the installed version, falling back to the declared one. Installed is the number that says
 * grading actually works; declared only says what was asked for. The library name is repeated even
 * when it matches the image name, because the exception — imgrec, which contains Pillow and requests
 * and no library called imgrec — is the case where dropping it would be a lie.
 */
export function formatLibraries(image: GradingImage): string {
  return image.libraries
    .map((lib) => {
      const version = lib.installed ?? lib.declared
      return version ? `${lib.name} ${version}` : ''
    })
    .filter(Boolean)
    .join(', ')
}

/**
 * `10. aug 2026, 09:43` / `10 Aug 2026, 09:43` — with the time, because several builds share a day
 * and "which build" is the question this whole block exists to answer.
 *
 * This used to be a hard-coded `dd/MM/yyyy HH:mm` with no locale at all, on the stated grounds that
 * it matched every other date in the app. It did not: slashes are not the Estonian convention, and
 * this was one of only two places still writing them (EZ-1870).
 *
 * Rendered in the reader's own timezone: the timestamps arrive as UTC, and a person comparing what
 * the page says against when they pushed is comparing against their own clock.
 */
export function formatBuiltAt(
  builtAt: string | null | undefined,
  locale: Locale,
): string {
  if (!builtAt) return ''
  const date = new Date(builtAt)
  if (Number.isNaN(date.getTime())) return ''
  return formatDateTime(date, locale)
}
