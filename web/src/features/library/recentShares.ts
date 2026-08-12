/**
 * Remembers who you just shared something with, so the share dialog can point them out.
 *
 * The list is sorted by access level and then by name, and a new person is always added at `PR` —
 * the lowest level — so they land alphabetically inside the largest block. On a directory shared
 * with a dozen people that is somewhere in the middle, indistinguishable from everyone who was
 * already there, and the only feedback that the add worked at all is the row count changing.
 *
 * In localStorage rather than component state because the useful moment is often after a reload or
 * a second visit: share with three people, close the dialog, come back to check. It expires on its
 * own, so nothing has to clean up after it and a stale entry cannot outlive its usefulness.
 */
const STORAGE_KEY = 'library.recentShares'

/** How long a share stays marked as new. */
export const RECENT_SHARE_MS = 10 * 60 * 1000

/** dir id -> subject -> when it was added, as epoch millis. */
type Store = Record<string, Record<string, number>>

/**
 * What identifies the person or rule a row is about: a lowercased email for an account, or `any`
 * for the everyone-rule. Not the username, which is not known at the time of adding — the dialog
 * sends an email and learns the username only when the list comes back.
 */
export const ANY_SUBJECT = 'any'

export function subjectOf(email: string | null | undefined): string | null {
  return email ? email.trim().toLowerCase() : null
}

function read(): Store {
  try {
    const parsed = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}')
    return parsed && typeof parsed === 'object' ? (parsed as Store) : {}
  } catch {
    return {}
  }
}

/** Drops everything past the window, and any directory left with nothing in it. */
function prune(store: Store, now: number): Store {
  const pruned: Store = {}
  for (const [dirId, subjects] of Object.entries(store)) {
    const fresh = Object.fromEntries(
      Object.entries(subjects).filter(([, at]) => typeof at === 'number' && now - at < RECENT_SHARE_MS),
    )
    if (Object.keys(fresh).length > 0) pruned[dirId] = fresh
  }
  return pruned
}

/**
 * The subjects shared to this directory recently, as subject -> when. Never stale.
 *
 * Writes the pruned store back when it actually dropped something, so expiry is what removes an
 * entry rather than the next share happening to clean up after it — otherwise someone who shares
 * once and never again keeps that entry forever.
 */
export function readRecentShares(dirId: string | undefined, now = Date.now()): Record<string, number> {
  const store = read()
  const pruned = prune(store, now)
  if (JSON.stringify(pruned) !== JSON.stringify(store)) {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(pruned))
    } catch {
      // Reading still works; the stale entry just outlives its window in storage.
    }
  }
  if (!dirId) return {}
  return pruned[dirId] ?? {}
}

/** Records a share. Prunes at the same time, so the key cannot grow without bound. */
export function markShared(dirId: string, subject: string, now = Date.now()): void {
  try {
    const store = prune(read(), now)
    store[dirId] = { ...(store[dirId] ?? {}), [subject]: now }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(store))
  } catch {
    // A share that cannot be remembered is not worth failing the share over.
  }
}
