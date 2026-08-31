import { useEffect, useState } from 'react'
import { apiFetch } from './client.ts'

/**
 * The three aggregate counts on the landing page and the About page, on a long poll.
 *
 * ### Public, and called as such
 *
 * `noAuth`, like the other deliberately public reads in `anonymousExercise.ts`: core serves this
 * from `/unauth/statistics/common`, and the landing page is the first thing a visitor who has never
 * logged in sees. It used to call an endpoint that was `@Secured` for the three roles, so an
 * anonymous visitor got a 401, waited five seconds, and asked again — for as long as the tab stayed
 * open, writing two lines into core's log every time round (EZ-1844). Nothing failed; the page just
 * showed a spinner where the counts go, and core's log filled up.
 *
 * ### Long poll, not an interval
 *
 * The request body is what this client already has. Core answers immediately when its counts differ
 * and otherwise holds the request until they change, which is how the counters move without polling
 * on a timer. So the body posted after the first round is a body core itself serialised — the wire
 * names have to work in both directions, and `StatisticsApiTest` posts one back to prove they do.
 *
 * A held request is a held thread on the server, and core bounds how many callers it will hold at
 * once — past that it answers immediately instead. From here the two are indistinguishable, which is
 * exactly why [MIN_ROUND_MS] exists: without a floor, being answered at once would turn this loop
 * into a tight one against an endpoint that now needs no account.
 */

/**
 * @endpoint POST /v2/unauth/statistics/common -> (root)
 * @requestBody POST /v2/unauth/statistics/common
 */
interface Stats {
  in_auto_assessing: number
  total_submissions: number
  total_users: number
}

interface UseStatisticsResult {
  inAutoAssessing: number
  totalSubmissions: number
  totalUsers: number
  isLoading: boolean
  /**
   * The poll gave up without ever getting an answer, so there is no number to show.
   *
   * Separate from `isLoading` because the two want opposite things on screen: a spinner says "any
   * moment now", and after this there is no next attempt. Rendering the `0` these counts fall back
   * to would put a confident falsehood on the front page, which is worse than admitting we do not
   * know.
   *
   * False once anything has arrived, even if the poll later gave up: numbers a few minutes stale are
   * still numbers, and there is nothing useful to tell a visitor about the difference.
   */
  isUnavailable: boolean
}

/**
 * How long to wait before each consecutive retry — and, by running out, when to stop retrying.
 *
 * The loop this feeds had no end at all, which is why a landing page open on a broken endpoint
 * talked to core every five seconds indefinitely. A 401 or a 404 is not a transient error and no
 * number of retries fixes one; a core restart is transient and wants a few. Growing delays serve
 * both: quick enough to ride out a restart, and out of patience within a couple of minutes.
 */
const RETRY_BACKOFF_MS = [2_000, 5_000, 15_000, 30_000, 60_000]

/**
 * The delay before retry number [consecutiveFailures] (1 for the first retry), or `null` to give up.
 *
 * Exported for `tests/unit/statistics-retry.test.mjs`. The hook around it needs a browser and a
 * server to say anything, and the property worth pinning — that this returns `null` rather than
 * going round forever — is a property of a pure function.
 */
export function retryDelayMs(consecutiveFailures: number): number | null {
  if (consecutiveFailures < 1) return null
  return RETRY_BACKOFF_MS[consecutiveFailures - 1] ?? null
}

/**
 * The shortest a successful round may take, so the loop cannot spin.
 *
 * Normally free: core holds the request until the counts change, so a round already takes as long as
 * it takes. It earns its place when core answers at once — a short server-side hold, or a caller
 * turned away because too many are already waiting — where without it the loop would hit a public
 * endpoint as fast as the round trip allows.
 */
const MIN_ROUND_MS = 1_000

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

export function useStatistics(): UseStatisticsResult {
  const [stats, setStats] = useState<Stats | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [isUnavailable, setIsUnavailable] = useState(false)
  /** Bumped to start the poll over after it has given up — see the listeners below. */
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false
    let gaveUp = false
    // One controller for the whole loop rather than one per request: it is aborted exactly once,
    // on unmount, and a request in flight at that moment is the only one there can be.
    const controller = new AbortController()

    async function poll() {
      /** What core last told us, i.e. what the next request says it already has. */
      let known: Stats | null = null
      let consecutiveFailures = 0

      while (!cancelled) {
        const startedAt = Date.now()
        try {
          // Annotated, not inferred. `known` appears in this initializer and is assigned from the
          // result below, and tsc reads the pair as a cycle (TS7022) rather than resolving it
          // through the explicit type argument. `undefined` is in the type because apiFetch returns
          // it for a 204 or an empty 200 body, which the next line is about.
          const next: Stats | undefined = await apiFetch<Stats>('/unauth/statistics/common', {
            method: 'POST',
            body: known ?? undefined,
            noAuth: true,
            signal: controller.signal,
          })
          if (cancelled) return

          // An empty body is a failure, not a reading. Storing it would leave `known` empty, so the
          // next request would send no body, so core would answer at once, forever — the tight loop
          // MIN_ROUND_MS also guards, arrived at from the other side.
          if (next == null) throw new Error('empty statistics response')

          setStats(next)
          setIsLoading(false)
          known = next
          consecutiveFailures = 0
        } catch {
          // An abort lands here too, and must not be spent out of the retry budget — the component
          // is going away, so there is nothing left to retry for.
          if (cancelled) return

          consecutiveFailures += 1
          const delay = retryDelayMs(consecutiveFailures)
          if (delay === null) {
            // Out of patience. Both flags matter: `isLoading` false so the spinner stops promising
            // an answer, `isUnavailable` true so the page can say it has none.
            gaveUp = true
            setIsLoading(false)
            setIsUnavailable(true)
            return
          }
          await sleep(delay)
          continue
        }
        await sleep(Math.max(0, MIN_ROUND_MS - (Date.now() - startedAt)))
      }
    }

    poll()

    /**
     * Start over, once, on a signal that something outside may have changed.
     *
     * Re-arming rather than a longer ladder. `core_autodeploy` allows core up to 120 seconds to come
     * back, which outlasts the ladder — so a routine deploy would otherwise leave every open landing
     * page showing a dash until somebody reloaded, and a laptop asleep for three minutes the same.
     * Waking on these events recovers from the transient case while keeping the property the ladder
     * is for: an endpoint that will never answer is asked five times, not forever.
     */
    const rearm = () => {
      if (!gaveUp || document.visibilityState !== 'visible') return
      gaveUp = false
      setIsLoading(true)
      setIsUnavailable(false)
      setAttempt((n) => n + 1)
    }
    window.addEventListener('online', rearm)
    document.addEventListener('visibilitychange', rearm)

    return () => {
      cancelled = true
      controller.abort()
      window.removeEventListener('online', rearm)
      document.removeEventListener('visibilitychange', rearm)
    }
  }, [attempt])

  return {
    inAutoAssessing: stats?.in_auto_assessing ?? 0,
    totalSubmissions: stats?.total_submissions ?? 0,
    totalUsers: stats?.total_users ?? 0,
    // Both gated on having nothing to show, so each means "no number yet, and here is why". Numbers
    // already on screen outrank either: a re-arm after a give-up must not replace real counts with a
    // spinner, and stale counts are better than a dash. `== null` rather than `=== null` because an
    // empty response body reaches this state as undefined.
    isLoading: isLoading && stats == null,
    isUnavailable: isUnavailable && stats == null,
  }
}
