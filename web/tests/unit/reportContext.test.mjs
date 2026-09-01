/**
 * The context header a bug report carries above its activity log.
 *
 * Tested here rather than in the browser suite because every property worth asserting is about
 * *absence* — a field that was never registered must not appear as `undefined`, a page URL must
 * not carry an authorization code, a tab that is up to date must not claim to be stale. None of
 * those are visible in a screenshot, and all of them are the difference between a header a
 * reporter can be shown honestly and one that leaks or lies.
 */
import { beforeEach, describe, expect, test, vi } from 'vitest'

/**
 * The browser globals the header reads, stubbed to fixed values.
 *
 * Fixed, because the point of every assertion below is a *shape* — that the row exists, that it is
 * omitted, that it was redacted — and a test that read the real machine's screen size would assert
 * whatever the machine happened to be.
 */
function stubBrowser({ online = true } = {}) {
  vi.stubGlobal('navigator', {
    onLine: online,
    userAgent: 'Mozilla/5.0 (Macintosh) TestBrowser/1.0',
    language: 'en-GB',
    cookieEnabled: true,
    hardwareConcurrency: 8,
  })
  vi.stubGlobal('window', {
    innerWidth: 1512,
    innerHeight: 857,
    devicePixelRatio: 2,
    screen: { width: 1512, height: 982 },
    // Every media query answers no, so `preferencesLine` contributes nothing and the row drops out.
    matchMedia: () => ({ matches: false }),
    // Both storages work; the failing case is asserted separately.
    localStorage: { setItem: () => {}, removeItem: () => {} },
    sessionStorage: { setItem: () => {}, removeItem: () => {} },
  })
  vi.stubGlobal('document', { visibilityState: 'visible' })
  vi.stubGlobal('sessionStorage', {
    getItem: () => null,
    setItem: () => {},
    removeItem: () => {},
  })
}

/** A fresh module, because the registry and the page-load timestamp are both module state. */
async function freshModule() {
  vi.resetModules()
  stubBrowser()
  return import('../../src/features/bug-report/reportContext.ts')
}

beforeEach(() => {
  vi.unstubAllGlobals()
})

describe('what the header says', () => {
  test('always names the build, the page and the time, even from a session that knows nothing', async () => {
    const { describeReportContext } = await freshModule()

    const header = describeReportContext('/courses/12')

    // The values `vitest.config.ts` substitutes for the build-time constants.
    expect(header).toContain('0.0-test (testing)')
    expect(header).toContain('/courses/12')
    expect(header).toMatch(/^filed {9}\d{4}-\d{2}-\d{2}T/)
  })

  test('omits a row entirely rather than printing undefined', async () => {
    const { describeReportContext } = await freshModule()

    const header = describeReportContext('/')

    // Nothing has registered an account, a role or a session state — which is the honest state of
    // a report filed before the app got that far, and must not read as a field that failed.
    expect(header).not.toContain('undefined')
    expect(header).not.toMatch(/^account/m)
    expect(header).not.toMatch(/^role/m)
    expect(header).not.toMatch(/^session/m)
  })

  test('names the account and the acting role once auth has registered them', async () => {
    const { describeReportContext, updateReportContext } = await freshModule()

    updateReportContext({
      username: 'someone',
      availableRoles: ['admin', 'teacher', 'student'],
      activeRole: 'student',
      session: 'authenticated and checked in',
    })
    const header = describeReportContext('/')

    expect(header).toContain('someone')
    // The distinction that explains a whole class of false bug reports: an admin looking at the
    // student view. Both halves have to be in the line for that to be readable.
    expect(header).toContain('student of admin, teacher, student')
    expect(header).toContain('authenticated and checked in')
  })

  test('shouts when the tab is running a build that has been superseded', async () => {
    const { describeReportContext, updateReportContext } = await freshModule()

    updateReportContext({
      deployedBuild: { version: '4.1', commit: 'abc1234', builtAt: '2026-08-30T09:12:00Z' },
    })

    expect(describeReportContext('/')).toContain('THIS TAB IS RUNNING AN OLDER BUILD')
  })

  test('says nothing about a deployed build when this tab is current', async () => {
    const { describeReportContext } = await freshModule()

    expect(describeReportContext('/')).not.toContain('OLDER BUILD')
  })

  test('marks an offline browser, which is the commonest cause of everything failing at once', async () => {
    vi.resetModules()
    stubBrowser({ online: false })
    const { describeReportContext } = await import('../../src/features/bug-report/reportContext.ts')

    expect(describeReportContext('/')).toContain('OFFLINE')
  })
})

describe('what the header must not say', () => {
  test('an authorization code in the page URL is redacted', async () => {
    const { describeReportContext } = await freshModule()

    // The reporter can be sitting on the IdP callback when they file — that is exactly when the
    // login is going wrong — and this string is written to a database, an email and an issue.
    const header = describeReportContext('/?code=6b1e-secret-value&session_state=aa11')

    expect(header).not.toContain('6b1e-secret-value')
    expect(header).toContain('code=[redacted]')
  })
})

describe('the registry', () => {
  test('merges rather than replaces, so two callers do not overwrite each other', async () => {
    const { describeReportContext, updateReportContext } = await freshModule()

    // AuthContext and AppLayout both push, from separate effects, and neither knows about the
    // other's fields.
    updateReportContext({ username: 'someone' })
    updateReportContext({ language: 'et' })
    const header = describeReportContext('/')

    expect(header).toContain('someone')
    expect(header).toContain('et (browser en-GB)')
  })

  test('a later push of the same field wins, which is what a role switch is', async () => {
    const { describeReportContext, updateReportContext } = await freshModule()

    updateReportContext({ activeRole: 'teacher', availableRoles: ['teacher'] })
    updateReportContext({ activeRole: 'student' })

    expect(describeReportContext('/')).toContain('student of teacher')
  })
})
