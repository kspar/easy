/**
 * The bug-report activity buffer: its caps, its eviction order, and its redaction.
 *
 * Worth testing directly rather than through the browser suite, because every property here is one
 * that only shows up at a boundary the UI cannot easily reach — a tab left open for half an hour, a
 * render loop logging two hundred times, a token in a console message. A buffer that silently kept
 * everything would look identical in a screenshot.
 *
 * The redaction test is the one that matters most. `AuthContext.tsx` calls `console.error` when a
 * token refresh fails, so a JWT reaching this buffer is not a hypothetical — and the buffer is shown
 * to the reporter and then posted to a server. Redaction happens on the way in, so this asserts the
 * *stored* form, not the serialised one.
 */
import { beforeEach, describe, expect, test, vi } from 'vitest'

/** A sessionStorage that behaves, backed by a plain object. */
function workingStorage() {
  const store = {}
  return {
    getItem: (k) => (k in store ? store[k] : null),
    setItem: (k, v) => {
      store[k] = String(v)
    },
    removeItem: (k) => {
      delete store[k]
    },
    store,
  }
}

/**
 * A fresh module per test.
 *
 * The buffer is module-level state seeded from storage at import time, which is what makes it
 * survive a reload in the real app — and what makes it leak between tests unless the module is
 * re-imported after the storage stub is in place.
 */
async function freshModule() {
  vi.resetModules()
  vi.stubGlobal('sessionStorage', workingStorage())
  return import('../../src/features/bug-report/breadcrumbs.ts')
}

beforeEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('caps', () => {
  test('keeps at most 400 entries, dropping the oldest', async () => {
    const { record, readBreadcrumbs } = await freshModule()

    for (let i = 0; i < 450; i++) record('route', `/page/${i}`)

    const entries = readBreadcrumbs()
    expect(entries).toHaveLength(400)
    // Oldest-first eviction: the survivors are the *last* 400, and the newest is last in the array.
    expect(entries[0].text).toBe('/page/50')
    expect(entries[399].text).toBe('/page/449')
  })

  test('drops anything older than 30 minutes', async () => {
    vi.useFakeTimers()
    const { record, readBreadcrumbs } = await freshModule()

    record('route', '/ancient')
    vi.advanceTimersByTime(31 * 60 * 1000)
    record('route', '/recent')

    const entries = readBreadcrumbs()
    expect(entries.map((e) => e.text)).toEqual(['/recent'])
  })

  test('collapses a route recorded twice in a row, but not a repeated error', async () => {
    const { record, readBreadcrumbs } = await freshModule()

    // StrictMode double-invokes effects in development, so every navigation was arriving twice and
    // the reporter saw each page listed twice in the panel they are asked to read.
    record('route', '/courses')
    record('route', '/courses')
    record('route', '/courses/1/exercises')
    record('route', '/courses')

    expect(readBreadcrumbs().map((e) => e.text)).toEqual([
      '/courses',
      '/courses/1/exercises',
      // Not collapsed: leaving and coming back is a different fact from never having left.
      '/courses',
    ])
  })

  test('does not collapse repeated errors or api failures, which is what a retry loop looks like', async () => {
    const { record, readBreadcrumbs } = await freshModule()

    record('api', 'GET /courses -> 500')
    record('api', 'GET /courses -> 500')
    record('error', 'boom')
    record('error', 'boom')

    expect(readBreadcrumbs()).toHaveLength(4)
  })

  test('truncates one enormous entry rather than letting it fill the buffer', async () => {
    const { record, readBreadcrumbs } = await freshModule()

    record('console', 'x'.repeat(5000))

    expect(readBreadcrumbs()[0].text).toHaveLength(400)
  })

  test('the serialised log stays inside what core will accept', async () => {
    const { record, serialiseBreadcrumbs } = await freshModule()

    // The buffer's own caps multiply out to well over core's `@Size` on the column, so a busy
    // session used to produce a payload the server rejected outright — a 400 for the reporter
    // whose session had the most to say. Distinct texts, so nothing is collapsed on the way in.
    for (let i = 0; i < 400; i++) record('console', `${i} ${'x'.repeat(400)}`)

    const serialised = serialiseBreadcrumbs()
    // The bound holds *including* the trimming notice, which the budget reserves room for rather
    // than appending on top of a fully spent allowance. Asserted here because the difference is
    // invisible until the notice's wording or MAX_TEXT changes and the slack runs out.
    expect(serialised.length).toBeLessThanOrEqual(40000)
    // Trimmed from the old end, and said so rather than silently starting mid-story.
    const [notice, ...rest] = serialised.split('\n')
    expect(notice).toMatch(/^… \d+ earlier entries trimmed$/)
    expect(Number(notice.match(/\d+/)[0])).toBe(400 - rest.length)
    // The newest entry is the one that must survive: it is what the reporter is complaining about.
    expect(serialised).toContain('399 ')
  })

  test('says nothing about trimming when everything fits', async () => {
    const { record, serialiseBreadcrumbs } = await freshModule()

    record('route', '/courses')
    record('api', 'POST /courses -> 200 in 41ms')

    expect(serialiseBreadcrumbs()).not.toContain('trimmed')
  })
})

describe('redaction', () => {
  test('a JWT-shaped string never reaches the buffer', async () => {
    const { record, readBreadcrumbs } = await freshModule()

    // The shape AuthContext.tsx can log on a failed token refresh.
    record('console', 'error: refresh failed for eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.abc.def')

    const stored = readBreadcrumbs()[0].text
    expect(stored).not.toContain('eyJhbGci')
    expect(stored).toContain('[redacted-token]')
  })

  test('a bearer header value is redacted even when the token is opaque', async () => {
    const { record, readBreadcrumbs } = await freshModule()

    record('api', 'GET /courses failed, Authorization: perm-abc123-not-a-jwt')

    expect(readBreadcrumbs()[0].text).not.toContain('perm-abc123')
  })

  test('the IdP callback URL keeps its shape but loses its authorization code', async () => {
    const { record, readBreadcrumbs } = await freshModule()

    // Newly reachable: the module records the URL the page loaded on, and one of those is the
    // callback Keycloak redirects to.
    record('route', '/?state=8f2c-4a&session_state=aa11&code=6b1e-secret-value (page load)')

    const stored = readBreadcrumbs()[0].text
    expect(stored).not.toContain('6b1e-secret-value')
    expect(stored).not.toContain('8f2c-4a')
    // Still legible as a callback, which is the diagnostic half of the line.
    expect(stored).toContain('code=[redacted]')
    expect(stored).toContain('(page load)')
  })

  test('ordinary prose containing the word code or state is left alone', async () => {
    const { record, readBreadcrumbs } = await freshModule()

    // The parameter patterns are anchored on `?`/`&`/`#` precisely so this stays readable.
    record('action', 'save conflict on exercise 42, fields: gradingScript, state')

    expect(readBreadcrumbs()[0].text).toContain('gradingScript, state')
  })
})

describe('serialisation', () => {
  test('is oldest first, one line per entry, and names the kind', async () => {
    const { record, serialiseBreadcrumbs } = await freshModule()

    record('route', '/courses')
    record('api', 'POST /submissions -> 500 id=abc-123')

    const lines = serialiseBreadcrumbs().split('\n')
    expect(lines).toHaveLength(2)
    expect(lines[0]).toContain('ROUTE')
    expect(lines[0]).toContain('/courses')
    // The error id is the whole point of recording failed calls: it is the grep key into core's log.
    expect(lines[1]).toContain('id=abc-123')
    expect(lines[1]).toContain('API')
  })

  test('is empty rather than throwing when nothing has happened', async () => {
    const { serialiseBreadcrumbs } = await freshModule()
    expect(serialiseBreadcrumbs()).toBe('')
  })
})

describe('storage', () => {
  test('survives a reload, which is the case the whole thing exists for', async () => {
    const storage = workingStorage()
    vi.resetModules()
    vi.stubGlobal('sessionStorage', storage)

    const first = await import('../../src/features/bug-report/breadcrumbs.ts')
    // An error flushes synchronously — a debounced write would not survive the reload that follows.
    first.record('error', 'render error: everything is on fire')

    // Same storage, new module instance: exactly what a reload does.
    vi.resetModules()
    const second = await import('../../src/features/bug-report/breadcrumbs.ts')

    expect(second.serialiseBreadcrumbs()).toContain('everything is on fire')
  })

  test('a storage that throws on every access loses breadcrumbs and nothing else', async () => {
    vi.resetModules()
    vi.stubGlobal('sessionStorage', {
      getItem: () => {
        throw new Error('QuotaExceededError')
      },
      setItem: () => {
        throw new Error('QuotaExceededError')
      },
    })

    // The import itself reads storage, so a throw there would take the whole app down at startup —
    // this runs inside `main.tsx` before anything renders.
    const { record, readBreadcrumbs, serialiseBreadcrumbs } = await import(
      '../../src/features/bug-report/breadcrumbs.ts'
    )

    expect(() => record('error', 'boom')).not.toThrow()
    expect(() => serialiseBreadcrumbs()).not.toThrow()
    // Still usable in memory for the current page; only persistence is lost.
    expect(readBreadcrumbs()[0].text).toBe('boom')
  })

  test('clearing empties it, so a second report is not the first one again', async () => {
    const { record, clearBreadcrumbs, serialiseBreadcrumbs } = await freshModule()

    record('route', '/courses')
    clearBreadcrumbs()

    expect(serialiseBreadcrumbs()).toBe('')
  })
})
