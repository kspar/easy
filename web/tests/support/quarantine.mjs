/**
 * Checks that are allowed to fail, for a named reason, until a named date.
 *
 * The alternative people reach for is `retries`, and it is worse: a retry turns an intermittent
 * failure into a green build plus a log line nobody reads, and it hides exactly the class of bug
 * this suite exists to find — product timing bugs, refetch races, a redirect that fires 90ms late.
 * Quarantine costs the same silence but writes down who owes what, and expires on its own.
 *
 * Rules, all enforced at load so a bad entry is a build failure rather than a quiet exemption:
 *
 * - **A quarantine names a check, never a spec.** Muting a whole file removes coverage nobody is
 *   counting; the check-count ratchet exists precisely to make that impossible to do by accident.
 * - **`issue` is required.** An exemption with no owner is a permanent one.
 * - **`expires` is required, is a date, and is at most 14 days out.** Longer is indistinguishable
 *   from forever, and the point of the mechanism is that it ends.
 * - **An expired entry fails the build.** That is the whole design: the debt comes due by itself
 *   rather than waiting for somebody to notice a stale file.
 * - **An entry that matches nothing also fails**, because a quarantine whose check was renamed or
 *   deleted is dead text that reads as coverage being deliberately suppressed when it is not.
 *   (Enforced per-spec by the runner, which is the only thing that knows what ran.)
 *
 * Shape:
 *
 *     [
 *       {
 *         "spec": "grade-table.spec.mjs",
 *         "label": "the CSV keeps a name containing a semicolon in one field",
 *         "issue": "EZ-1234",
 *         "expires": "2026-09-01",
 *         "note": "Excel quoting differs on the CI runner's locale; fix lands with EZ-1234."
 *       }
 *     ]
 */
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
export const QUARANTINE_PATH = join(HERE, '../quarantine.json')

/** The longest a check may be muted. Two weeks is one sprint; a month is "we gave up". */
export const MAX_DAYS = 14

const DAY = 24 * 60 * 60 * 1000

/**
 * Validate and return the entries, or throw with everything that is wrong at once.
 *
 * Pure, and separated from the file read so it can be unit-tested — a validator whose failure mode
 * is "silently allows anything" is worth a test more than most code here is.
 */
export function parseQuarantine(raw, now = new Date()) {
  if (!Array.isArray(raw)) throw new Error('quarantine.json must be a JSON array')

  const problems = []
  const entries = []

  raw.forEach((e, i) => {
    const at = `quarantine.json[${i}]`
    if (e === null || typeof e !== 'object' || Array.isArray(e)) {
      problems.push(`${at} is not an object`)
      return
    }
    for (const field of ['spec', 'label', 'issue', 'expires', 'note']) {
      if (typeof e[field] !== 'string' || !e[field].trim()) {
        problems.push(`${at} is missing "${field}" — every quarantine needs a spec, the exact check label, an issue, an expiry and a reason`)
      }
    }
    if (typeof e.issue === 'string' && e.issue.trim() && !/^EZ-\d+$/.test(e.issue.trim())) {
      problems.push(`${at}.issue "${e.issue}" is not a YouTrack id like EZ-1234`)
    }
    if (typeof e.expires !== 'string') return

    const expires = new Date(`${e.expires}T23:59:59Z`)
    if (Number.isNaN(expires.getTime())) {
      problems.push(`${at}.expires "${e.expires}" is not a YYYY-MM-DD date`)
      return
    }
    if (expires.getTime() < now.getTime()) {
      problems.push(
        `${at} expired on ${e.expires} (${e.issue} — ${e.label}). Fix the check, or extend the ` +
          `entry and say in the commit why it is still owed.`,
      )
      return
    }
    if (expires.getTime() - now.getTime() > MAX_DAYS * DAY) {
      problems.push(
        `${at}.expires "${e.expires}" is more than ${MAX_DAYS} days out. A longer mute is a ` +
          `permanent one wearing a date.`,
      )
      return
    }
    entries.push({ ...e })
  })

  if (problems.length) {
    throw new Error(`quarantine.json is not usable:\n  - ${problems.join('\n  - ')}`)
  }
  return entries
}

let cached = null

/** Entries for the whole suite, read once per worker. Throws — loudly — if the file is unusable. */
export function loadQuarantine() {
  if (cached) return cached
  let raw
  try {
    raw = JSON.parse(readFileSync(QUARANTINE_PATH, 'utf8'))
  } catch (e) {
    // Deliberately not a silent empty list. "No quarantine file" and "the quarantine file is
    // broken" look identical from here, and the second one silently un-mutes nothing while also
    // silently failing to enforce the expiry dates — the mechanism would be off with no sign.
    throw new Error(`could not read ${QUARANTINE_PATH}: ${e.message}`)
  }
  cached = parseQuarantine(raw)
  return cached
}
