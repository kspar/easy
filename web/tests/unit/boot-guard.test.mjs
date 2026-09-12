/**
 * The boot guard's decision to reload, or not (EZ-1908).
 *
 * The guard is the one script in this app that runs when nothing else has, so it cannot be
 * imported, bundled or type-checked with the rest — it is plain ES5 in `public/` and reaches the
 * browser as its own request. That is exactly why its rules are worth pinning here: every branch
 * below is a case where reloading would be *wrong*, and the cost of getting one of them backwards
 * is a page that reloads itself while somebody is typing into the editor.
 *
 * No jsdom (see vitest.config.ts). The guard touches five globals and nothing else, so the test
 * hands it five objects and watches what it calls.
 */
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, test } from 'vitest'

const HERE = dirname(fileURLToPath(import.meta.url))
const SOURCE = readFileSync(join(HERE, '../../public/boot-guard.js'), 'utf8')

const ORIGIN = 'https://lahendus.ut.ee'
const MARKER = 'easyAssetReloadAt'

/** A sessionStorage backed by a plain object. */
function workingStorage(initial = {}) {
  const store = { ...initial }
  return {
    store,
    getItem: (k) => (k in store ? store[k] : null),
    setItem: (k, v) => {
      store[k] = String(v)
    },
    removeItem: (k) => {
      delete store[k]
    },
  }
}

/** Safari private browsing and a cookie-blocked iframe both look like this. */
function throwingStorage() {
  const fail = () => {
    throw new Error('SecurityError')
  }
  return { getItem: fail, setItem: fail, removeItem: fail }
}

/**
 * Runs the guard against fake globals and returns the handles to poke it with.
 *
 * `new Function` with the five globals as parameters rather than stubbing them on `globalThis`:
 * the guard reads them as bare identifiers, so parameters shadow whatever Node happens to define
 * itself — `navigator` is a read-only global on newer Node and would otherwise need special care.
 */
function loadGuard({ storage = workingStorage(), online = true } = {}) {
  const listeners = {}
  const reloads = []
  const announced = []
  const logged = { warn: [], error: [] }

  const window = {
    location: {
      origin: ORIGIN,
      reload: () => reloads.push(Date.now()),
    },
    addEventListener: (type, handler) => {
      listeners[type] = handler
    },
    dispatchEvent: (event) => {
      announced.push(event)
      return true
    },
  }
  const document = { baseURI: `${ORIGIN}/courses/1/exercises` }
  const navigator = { onLine: online }
  const console = {
    warn: (m) => logged.warn.push(m),
    error: (m) => logged.error.push(m),
  }

  new Function('window', 'document', 'navigator', 'sessionStorage', 'console', SOURCE)(
    window,
    document,
    navigator,
    storage,
    console,
  )

  return {
    window,
    storage,
    reloads,
    announced,
    logged,
    /**
     * A subresource that failed to load, as the browser reports it: an event whose target is the
     * element. A script carries its URL on `src` and a link on `href`, and the guard has to read
     * both — the entry module is one and the stylesheet beside it is the other.
     */
    failAsset: (tagName, url) =>
      listeners.error({ target: tagName === 'LINK' ? { tagName, href: url } : { tagName, src: url } }),
    /** A plain runtime error, which arrives at the same capture-phase listener. */
    failScript: () => listeners.error({ target: window }),
    failPreload: (message) => listeners['vite:preloadError']({ payload: { message } }),
    booted: () => window.__easyBootGuard.booted(),
  }
}

const ENTRY = `${ORIGIN}/assets/index-a1b2c3d4.js`

describe('a missing asset before the app has booted', () => {
  test('reloads the page', () => {
    const g = loadGuard()
    g.failAsset('SCRIPT', ENTRY)
    expect(g.reloads).toHaveLength(1)
  })

  test('remembers that it spent its one reload', () => {
    const g = loadGuard()
    g.failAsset('SCRIPT', ENTRY)
    expect(g.storage.store[MARKER]).toBeDefined()
  })

  test('reloads once even when several assets fail together', () => {
    const g = loadGuard()
    g.failAsset('SCRIPT', ENTRY)
    g.failAsset('LINK', `${ORIGIN}/assets/index-a1b2c3d4.css`)
    expect(g.reloads).toHaveLength(1)
  })

  test('reloads on a failed dynamic import, which is how the app module itself fails', () => {
    const g = loadGuard()
    g.failPreload('Failed to fetch dynamically imported module')
    expect(g.reloads).toHaveLength(1)
  })

  /**
   * The case that would otherwise be a loop. A deploy that is genuinely broken leaves the asset
   * missing after the reload too, and the honest outcome is the blank page we already had — not a
   * tab that reloads forever.
   */
  test('does not reload a second time in a new page load', () => {
    const g = loadGuard({ storage: workingStorage({ [MARKER]: '1757600000000' }) })
    g.failAsset('SCRIPT', ENTRY)
    expect(g.reloads).toEqual([])
    expect(g.logged.error).toHaveLength(1)
  })
})

describe('what must never trigger a reload', () => {
  /**
   * The one with teeth. EZ-1752 settled that this app does not reload itself out from under
   * anyone, because a student's half-written solution lives in the editor until they submit it.
   * A CodeMirror mode or KaTeX going missing in a live tab is exactly that situation.
   */
  test('an asset that fails after the app has rendered', () => {
    const g = loadGuard()
    g.booted()
    g.failPreload('Failed to fetch dynamically imported module')
    g.failAsset('SCRIPT', `${ORIGIN}/assets/lang-python-9f8e7d6c.js`)
    expect(g.reloads).toEqual([])
    expect(g.logged.warn).toHaveLength(2)
  })

  /** `index.html` links a Google Fonts stylesheet. An ad blocker is not a deploy. */
  test('a cross-origin stylesheet', () => {
    const g = loadGuard()
    g.failAsset('LINK', 'https://fonts.googleapis.com/css2?family=Outfit')
    expect(g.reloads).toEqual([])
  })

  test('a same-origin file that is not part of the build output', () => {
    const g = loadGuard()
    g.failAsset('LINK', `${ORIGIN}/favicon.ico`)
    expect(g.reloads).toEqual([])
  })

  test('a plain runtime error, which reaches the same listener with window as its target', () => {
    const g = loadGuard()
    g.failScript()
    expect(g.reloads).toEqual([])
  })

  /** Offline, a reload trades the blank page for the browser's network error page. */
  test('an asset that fails while the browser is offline', () => {
    const g = loadGuard({ online: false })
    g.failAsset('SCRIPT', ENTRY)
    expect(g.reloads).toEqual([])
  })

  /** No memory means no way to bound a loop, so the guard stands down rather than guessing. */
  test('anything at all when sessionStorage is unavailable', () => {
    const g = loadGuard({ storage: throwingStorage() })
    g.failAsset('SCRIPT', ENTRY)
    expect(g.reloads).toEqual([])
  })
})

/**
 * The live-tab case is not silent, it is just not a reload (EZ-1908).
 *
 * Every one of those dynamic imports is behind a `catch`, so without this the failure presents as
 * a feature that was never wired up — no syntax colouring, a formula left as `$x^2$`. The event is
 * what lets `UpdateAvailableBanner` say what actually happened.
 */
describe('telling the app', () => {
  test('announces a missing asset once the app has rendered', () => {
    const g = loadGuard()
    g.booted()
    g.failAsset('SCRIPT', `${ORIGIN}/assets/lang-python-9f8e7d6c.js`)
    expect(g.announced).toHaveLength(1)
    expect(g.announced[0].type).toBe('easy:asset-missing')
    expect(g.announced[0].detail.url).toContain('lang-python')
  })

  test('says nothing when it is about to reload instead', () => {
    const g = loadGuard()
    g.failAsset('SCRIPT', ENTRY)
    expect(g.reloads).toHaveLength(1)
    expect(g.announced).toEqual([])
  })

  test('says nothing about an asset that is not ours', () => {
    const g = loadGuard()
    g.booted()
    g.failAsset('LINK', 'https://fonts.googleapis.com/css2?family=Outfit')
    expect(g.announced).toEqual([])
  })
})

describe('booting', () => {
  test('clears the spent reload, so a later stale document gets its own attempt', () => {
    const g = loadGuard({ storage: workingStorage({ [MARKER]: '1757600000000' }) })
    g.booted()
    expect(g.storage.store[MARKER]).toBeUndefined()
  })

  test('survives a sessionStorage that throws', () => {
    const g = loadGuard({ storage: throwingStorage() })
    expect(() => g.booted()).not.toThrow()
    expect(g.window.__easyAppBooted).toBe(true)
  })
})
