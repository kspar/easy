import config from '../config.ts'
import { record } from '../features/bug-report/breadcrumbs.ts'

export interface ApiError {
  id: string
  code: string | null
  attrs: Record<string, string>
  log_msg: string
}

/** Above this, a successful read is itself worth reporting. See the note at its use. */
const SLOW_REQUEST_MS = 5000

/**
 * Endpoints this app calls on a timer, whose *successes* are never recorded.
 *
 * The activity buffer holds four hundred entries, and its value is entirely in what a reporter's
 * last half hour contained. These three would spend it on nothing:
 *
 * - the landing page's statistics **long poll**, which core holds open for thirty seconds and
 *   which then immediately re-arms — twenty entries per ten minutes, every one of them the same,
 *   and (being a POST) it would take the write branch below rather than the slow-read one;
 * - the **draft autosave**, which fires two seconds after a student stops typing — several hundred
 *   entries over one exercise, enough on their own to evict every route, auth and error line;
 * - the **notifications** poll behind the system-message banner.
 *
 * Only successes are dropped. A *failed* draft save is the single most important line this buffer
 * can hold — it is the state behind every "my code disappeared" report — and failures are recorded
 * above this, unconditionally, for every path.
 */
function isRepeating(path: string): boolean {
  return (
    path.startsWith('/unauth/statistics') ||
    path.endsWith('/draft') ||
    path.startsWith('/management/common/notifications')
  )
}

export class ApiResponseError extends Error {
  // Declared and assigned explicitly rather than as constructor parameter properties:
  // those are TS-only syntax, which `erasableSyntaxOnly` forbids.
  readonly status: number
  readonly errorBody: ApiError | null

  constructor(status: number, errorBody: ApiError | null) {
    super(errorBody?.log_msg ?? `HTTP ${status}`)
    this.status = status
    this.errorBody = errorBody
  }
}

export let getToken: (() => Promise<string | undefined>) | null = null

export function setTokenProvider(provider: () => Promise<string | undefined>) {
  getToken = provider
}

/**
 * What to do when core says the caller is not authenticated.
 *
 * Registered the same way as the token provider, and for the same reason: this module cannot import
 * the auth context without a cycle, and the decision does not belong here anyway. `AuthContext`
 * supplies it.
 *
 * Before this existed, nothing anywhere inspected a 401. A session that outlived its refresh token
 * left every query failing, react-query retrying once and giving up, and the user looking at error
 * and spinner states with two `console.warn`s they could not see. The only recovery was a manual
 * reload — which works, because it re-runs `check-sso`, and which nothing told them to do.
 */
let onUnauthorized: (() => void) | null = null

export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler
}

/**
 * The token as Keycloak last minted it, with no refresh attempted. For requests that must be
 * dispatched synchronously — see [apiFetchKeepalive]. Everything else goes through [getToken],
 * whose refresh round-trip is the point.
 */
let getCachedToken: (() => string | undefined) | null = null

export function setCachedTokenProvider(provider: () => string | undefined) {
  getCachedToken = provider
}

/**
 * A last-chance write, for the moment the page may be going away (`visibilitychange` → hidden,
 * unload). Differs from [apiFetch] in exactly the ways that moment demands:
 *
 * - **Dispatches synchronously.** No awaited token refresh before `fetch` — a request that has
 *   not been handed to the browser when the document dies is simply never sent, and `keepalive`
 *   only protects a request that was. The cached token is used as-is; if it has expired the
 *   request fails, which the caller treats as best-effort.
 * - **`keepalive: true`**, so a dispatched request survives teardown. Browsers cap keepalive
 *   bodies at ~64 KB — callers with bigger payloads must take the normal path instead.
 * - **No 401 recovery.** The global handler navigates to the IdP, and this often runs in a tab
 *   the user has switched away from — a hidden tab must never be navigated out from under them.
 */
export function apiFetchKeepalive(path: string, body: unknown, method = 'POST'): Promise<void> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const token = getCachedToken?.()
  if (token) headers['Authorization'] = `Bearer ${token}`
  // Recorded on the way out rather than only on the way back, because the interesting failure is
  // the one with no way back: the document dies mid-flight and no handler below ever runs. A
  // dispatch line with no outcome line after it is exactly that case, and is worth being able to
  // see — "my last edit vanished when I closed the tab" is what this transport exists to prevent.
  record('api', `${method} ${path} (keepalive${token ? '' : ', unauthenticated'})`)
  return fetch(`${config.emsRoot}${path}`, {
    method,
    headers,
    body: JSON.stringify(body),
    keepalive: true,
  }).then(
    (response) => {
      if (!response.ok) {
        record('api', `${method} ${path} -> ${response.status} (keepalive)`)
        throw new ApiResponseError(response.status, null)
      }
      record('api', `${method} ${path} -> ${response.status} (keepalive)`)
    },
    (err: unknown) => {
      record('api', `${method} ${path} -> keepalive failed: ${describeNetworkError(err)}`)
      throw err
    },
  )
}

/**
 * What a rejected `fetch` was, in words.
 *
 * `fetch` rejects with the same opaque `TypeError: Failed to fetch` for an offline laptop, a DNS
 * failure, a CORS rejection and a certificate the browser refused — the four causes that produce
 * "the whole site stopped working" and none of which appear in a log of HTTP statuses, because
 * there was no HTTP status. Recording `navigator.onLine` alongside it separates the commonest one
 * from the other three without guessing.
 */
/** A caller who stopped caring, rather than anything going wrong. See the catch in [apiFetch]. */
function isAbort(err: unknown): boolean {
  return err instanceof Error && err.name === 'AbortError'
}

function describeNetworkError(err: unknown): string {
  const message = err instanceof Error ? `${err.name}: ${err.message}` : String(err)
  return navigator.onLine ? message : `${message} (browser reports offline)`
}

export async function apiFetch<T>(
  path: string,
  options: {
    method?: string
    body?: unknown
    headers?: Record<string, string>
    noAuth?: boolean
    /**
     * Abort the request when the caller stops caring.
     *
     * For the one call in the app that outlives a render rather than a query — the statistics long
     * poll, which core holds open for up to thirty seconds. Without this, navigating away from the
     * landing page leaves that request in flight and core holding a thread for it, since nothing
     * tells a blocked handler that the socket went away. react-query's own calls do not need it: it
     * discards a resolved promise it no longer wants.
     */
    signal?: AbortSignal
  } = {},
): Promise<T> {
  const { method = 'GET', body, headers = {}, noAuth = false, signal } = options

  const isFormData = body instanceof FormData

  const combinedHeaders: Record<string, string> = {
    'Content-Type': 'application/json',
    ...headers,
  }

  // Deleted, not overridden. A multipart body needs `multipart/form-data; boundary=…`, and the
  // boundary is generated by the browser when it serialises the FormData — there is no way to know
  // it here. Setting the header by hand omits it, and the request then fails server-side looking
  // like a corrupt upload rather than a client bug.
  if (isFormData) delete combinedHeaders['Content-Type']

  if (!noAuth && getToken) {
    const token = await getToken()
    if (token) {
      combinedHeaders['Authorization'] = `Bearer ${token}`
    }
  }

  const startedAt = Date.now()
  let response: Response
  try {
    response = await fetch(`${config.emsRoot}${path}`, {
      method,
      headers: combinedHeaders,
      body: isFormData ? body : body != null ? JSON.stringify(body) : undefined,
      signal,
    })
  } catch (err) {
    // A request that never reached core, which the previous version of this recorded as nothing at
    // all — the `record` below only runs for a response. So the log of an offline tab showed the
    // route the person was on and then silence, which reads like the app stopped trying.
    //
    // Except an abort, which is not a failure and is not rare: react-query cancels in-flight
    // queries on unmount, so every navigation aborts whatever the page it left had outstanding.
    // Recorded, those read as network errors in a report where nothing went wrong.
    if (!isAbort(err)) {
      record('api', `${method} ${path} -> ${describeNetworkError(err)} after ${Date.now() - startedAt}ms`)
    }
    throw err
  }

  const tookMs = Date.now() - startedAt

  if (!response.ok) {
    let errorBody: ApiError | null = null
    try {
      errorBody = await response.json()
    } catch {
      // no parseable error body
    }

    // The one place every core call fails, so the one place worth recording them (EZ-1786).
    //
    // `errorBody.id` is the reason this is here rather than just logging a status. Core mints that
    // UUID in its exception handler and writes the *same* value to its log line, to this response
    // and to the admin email — so a bug report carrying these ids gives whoever picks it up an exact
    // grep key into the backend log, instead of a timestamp and a username to search around.
    record(
      'api',
      `${method} ${path} -> ${response.status} in ${tookMs}ms` +
        `${errorBody?.code ? ` ${errorBody.code}` : ''}${errorBody?.id ? ` id=${errorBody.id}` : ''}`,
    )

    // A 401 means the token we sent — or the absence of one — was not accepted, so no amount of
    // retrying will help and the error the caller is about to see is not the useful part. Told once,
    // here, because this is the only place every core call passes through.
    //
    // `noAuth` requests are exempt: those are the deliberately public reads (a published article, an
    // uploaded file), and a 401 from one of them is about that resource rather than about the
    // session. Treating it as a dead session would bounce a logged-out visitor off a page that is
    // meant to be readable without logging in.
    if (response.status === 401 && !noAuth) onUnauthorized?.()

    throw new ApiResponseError(response.status, errorBody)
  }

  // Successes are recorded selectively, because recording all of them would be recording nothing:
  // a single page of this app issues a dozen reads, and two hundred `GET … -> 200` lines would
  // push the half hour of history the buffer exists for straight out of it.
  //
  // A **write** earns a line. It is the closest thing this app has to a log of what the person
  // actually did — saved an exercise, submitted a solution, added a student, deleted a course —
  // and a report reading "I saved it and it did not save" is answered by whether the POST is in
  // this list at all.
  //
  // A **slow read** earns one too. "It just spins" is a real report with no error anywhere in it,
  // and the only evidence is a request that took eleven seconds and then succeeded.
  //
  // Neither rule applies to [isRepeating], whose whole point is that it fires over and over.
  if (isRepeating(path)) {
    // nothing: see the note on isRepeating
  } else if (method !== 'GET') {
    record('api', `${method} ${path} -> ${response.status} in ${tookMs}ms`)
  } else if (tookMs >= SLOW_REQUEST_MS) {
    record('api', `${method} ${path} -> ${response.status} in ${tookMs}ms (slow)`)
  }

  if (response.status === 204) {
    return undefined as T
  }

  const text = await response.text()
  if (!text) {
    return undefined as T
  }
  return JSON.parse(text)
}