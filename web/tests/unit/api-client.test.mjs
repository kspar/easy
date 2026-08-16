/**
 * `apiFetch` — the one function every request in this application goes through.
 *
 * Three of its behaviours are the kind that break everything at once and are invisible in review:
 *
 * 1. **A FormData body must have no `Content-Type` header at all.** Multipart needs
 *    `multipart/form-data; boundary=…`, and only the browser knows the boundary it will generate.
 *    Setting the header by hand omits the boundary and the server sees a corrupt upload — which
 *    reads as a backend bug. The source deletes the header rather than overriding it, and the
 *    difference between `delete` and assigning `undefined` is the difference between a working
 *    upload and every upload in the app failing.
 * 2. **A FormData body must not be JSON-stringified**, which would send the string
 *    `"[object FormData]"`.
 * 3. **204 means undefined, not a parse error.** Several endpoints answer with no content, and
 *    `response.json()` on an empty body throws.
 *
 * The browser suite exercises uploads end to end, but through a stub that accepts anything — it
 * cannot tell a request with the right headers from one without. This can.
 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

/**
 * `client.ts` reads `config.emsRoot` at call time from a module that loads runtime config, so the
 * import has to be mocked before the module under test is pulled in.
 */
// A *default* export, because that is how client.ts imports it (`import config from '../config.ts'`).
// Named-exporting `config` here produced twelve identical failures saying so, which is the mock
// telling the truth about the module it stands in for.
vi.mock('../../src/config.ts', () => ({ default: { emsRoot: 'https://api.test/v2' } }))

const { apiFetch, setTokenProvider } = await import('../../src/api/client.ts')

/** The last call `fetch` received, so assertions read like the request that went out. */
let calls = []

beforeEach(() => {
  calls = []
  setTokenProvider(async () => 'tok-123')
  vi.stubGlobal('fetch', async (url, init) => {
    calls.push({ url, init })
    return {
      ok: true,
      status: 200,
      text: async () => JSON.stringify({ ok: true }),
      json: async () => ({ ok: true }),
    }
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

const lastHeaders = () => calls.at(-1).init.headers

describe('a JSON body', () => {
  test('is serialised and declared as JSON', async () => {
    await apiFetch('/things', { method: 'POST', body: { a: 1 } })
    expect(lastHeaders()['Content-Type']).toBe('application/json')
    expect(calls.at(-1).init.body).toBe('{"a":1}')
  })

  test('a GET with no body sends no body at all', async () => {
    // `JSON.stringify(undefined)` is `undefined`, but `JSON.stringify(null)` is the string "null",
    // which some servers read as a body. The source distinguishes them with `body != null`.
    await apiFetch('/things')
    expect(calls.at(-1).init.body).toBeUndefined()
  })

  test('the path is appended to the configured root', async () => {
    await apiFetch('/things')
    expect(calls.at(-1).url).toBe('https://api.test/v2/things')
  })
})

describe('a FormData body', () => {
  test('carries NO Content-Type header, so the browser can set the boundary', async () => {
    const fd = new FormData()
    fd.append('file', new Blob(['x']), 'x.png')
    await apiFetch('/files', { method: 'POST', body: fd })

    // `not.toHaveProperty` rather than `toBeUndefined`: a header explicitly present with the value
    // `undefined` is a different thing, and `fetch` would stringify it to "undefined".
    expect(lastHeaders()).not.toHaveProperty('Content-Type')
  })

  test('and is passed through untouched rather than stringified', async () => {
    const fd = new FormData()
    fd.append('file', new Blob(['x']), 'x.png')
    await apiFetch('/files', { method: 'POST', body: fd })
    expect(calls.at(-1).init.body).toBe(fd)
    expect(typeof calls.at(-1).init.body).not.toBe('string')
  })

  test('while still authenticating', async () => {
    // Dropping the whole header object would also "fix" the Content-Type problem, and break auth.
    const fd = new FormData()
    await apiFetch('/files', { method: 'POST', body: fd })
    expect(lastHeaders()['Authorization']).toBe('Bearer tok-123')
  })
})

describe('auth', () => {
  test('a bearer token is attached when one is available', async () => {
    await apiFetch('/things')
    expect(lastHeaders()['Authorization']).toBe('Bearer tok-123')
  })

  test('noAuth omits it, which is what the public endpoints need', async () => {
    await apiFetch('/unauth/versions', { noAuth: true })
    expect(lastHeaders()).not.toHaveProperty('Authorization')
  })

  test('no header at all when the provider has no token, rather than "Bearer undefined"', async () => {
    setTokenProvider(async () => undefined)
    await apiFetch('/things')
    expect(lastHeaders()).not.toHaveProperty('Authorization')
  })
})

describe('responses', () => {
  test('204 resolves to undefined instead of failing to parse an empty body', async () => {
    vi.stubGlobal('fetch', async () => ({
      ok: true,
      status: 204,
      text: async () => '',
      json: async () => {
        throw new Error('should not be called for 204')
      },
    }))
    await expect(apiFetch('/things', { method: 'DELETE' })).resolves.toBeUndefined()
  })

  test('a failure throws with the status, so callers can tell 403 from 500', async () => {
    vi.stubGlobal('fetch', async () => ({
      ok: false,
      status: 403,
      json: async () => ({ code: 'NO_COURSE_ACCESS' }),
    }))
    await expect(apiFetch('/things')).rejects.toMatchObject({ status: 403 })
  })

  test('and still throws when the error body is not parseable', async () => {
    // A 502 from a proxy is HTML, not JSON. Swallowing the parse failure must not swallow the error.
    vi.stubGlobal('fetch', async () => ({
      ok: false,
      status: 502,
      json: async () => {
        throw new Error('not json')
      },
    }))
    await expect(apiFetch('/things')).rejects.toMatchObject({ status: 502 })
  })
})
