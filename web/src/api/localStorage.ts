/**
 * Reading and writing `localStorage` without letting it take the page down with it.
 *
 * **Both directions can throw, and only reads were consistently guarded.** Four hooks had grown
 * their own copy of this — `useSavedGroup`, `useSavedFilters`, `useRecentExercises`,
 * `useEmbedTheme` — and all four guarded the *read* while only two guarded the *write*. So
 * selecting a group or opening an exercise threw, from inside a click handler, in situations that
 * are not exotic:
 *
 * - **Safari in private browsing** historically gave a zero quota, so every `setItem` threw
 *   `QuotaExceededError`
 * - **third-party cookie blocking** can make `localStorage` throw on access inside an iframe, which
 *   `useEmbedTheme` documents because the embed hit it
 * - a **full quota** throws for everyone, and "full" is a few megabytes shared with everything else
 *   on the origin
 *
 * Persisting a UI preference is a nice-to-have. Failing to persist it must never be worse than not
 * having tried, which is what an unguarded `setItem` in an event handler makes it.
 *
 * The functions return a `boolean` for writes rather than throwing, so a caller that genuinely
 * cares can tell — none currently do, and that is the right default.
 */

/** Parse `key` as JSON, or return `fallback` if it is absent, unparseable, or unreadable. */
export function readJson<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key)
    if (raw === null) return fallback
    const parsed = JSON.parse(raw)
    // `null` parses fine and is almost never what the caller wants back in place of its fallback.
    return parsed === null ? fallback : (parsed as T)
  } catch {
    return fallback
  }
}

/**
 * Like [readJson], but rejects anything failing `isValid` — so a caller asking for an object gets
 * its fallback rather than the array or number somebody else left under that key.
 *
 * Worth having separately: `JSON.parse('[]')` succeeds, and a hook expecting `Record<string, …>`
 * then spreads an array and behaves strangely rather than falling back.
 */
export function readJsonIf<T>(key: string, isValid: (v: unknown) => boolean, fallback: T): T {
  const value = readJson<unknown>(key, fallback)
  return isValid(value) ? (value as T) : fallback
}

/** Store `value` as JSON. Returns false if storage refused it; never throws. */
export function writeJson(key: string, value: unknown): boolean {
  try {
    localStorage.setItem(key, JSON.stringify(value))
    return true
  } catch {
    return false
  }
}

/** Read a plain string. `null` when absent or unreadable — the two are not worth distinguishing. */
export function readString(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

/** Store a plain string. Returns false if storage refused it; never throws. */
export function writeString(key: string, value: string): boolean {
  try {
    localStorage.setItem(key, value)
    return true
  } catch {
    return false
  }
}

/** A plain object, which is what every map-shaped key in this app expects. */
export const isPlainObject = (v: unknown): boolean =>
  v !== null && typeof v === 'object' && !Array.isArray(v)
