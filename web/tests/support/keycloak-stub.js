// Stand-in for keycloak-js, aliased in by ../vite.stub.config.ts.
//
// AuthProvider constructs Keycloak from an ES module import, so there is no way
// to swap it from an init script — it has to be replaced at build time.
//
// The role comes from localStorage so one dev server can serve every role:
// seed `stubRole` (e.g. 'student', 'teacher', 'teacher,admin') before the app
// boots and reload.
export default class Keycloak {
  constructor() {
    const role = localStorage.getItem('stubRole') ?? 'teacher,admin'
    const isStudent = role === 'student'

    // Seed `stubAuth = 'none'` to model a visitor with no session. Defaults to signed in, which
    // is what every script written before this one assumes.
    this.authenticated = localStorage.getItem('stubAuth') !== 'none'
    this.token = this.authenticated ? 'stub-token' : undefined
    this.tokenParsed = this.authenticated
      ? {
          given_name: 'Test',
          family_name: isStudent ? 'Student' : 'Teacher',
          email: isStudent ? 'student@test.ee' : 'teacher@test.ee',
          preferred_username: isStudent ? 'dev-student' : 'dev-teacher',
          easy_role: role,
        }
      : undefined

    // Records what login() was asked to do, so a test can assert where a visitor would be sent
    // instead of following a redirect out of the app. Real keycloak-js navigates away here.
    this.loginCalls = []
    globalThis.__stubLoginCalls = this.loginCalls
  }

  /**
   * The same count, but across page loads — which is the only scale at which a redirect *loop* is
   * visible at all.
   *
   * `loginCalls` above is deliberately per page load, and every spec written against it depends on
   * that. But a redirect ends the page load, so a bug that bounces once per load and does it
   * forever produces the identical `loginCalls.length === 1` on every one of them. That is exactly
   * the shape of EZ-1828's second loop, and nothing here could see it.
   *
   * Kept in `sessionStorage`, which survives a navigation within the tab and dies with it. Callers
   * reset it themselves — see `resetTotal` — because "since when" is the spec's question, not this
   * class's.
   */
  static TOTAL_KEY = 'easyStubLoginTotal'

  static readTotal() {
    try {
      return Number(sessionStorage.getItem(Keycloak.TOTAL_KEY) ?? 0)
    } catch {
      return 0
    }
  }

  /**
   * Resolves to the session state above, after `stubAuthDelayMs` if one is seeded.
   *
   * The delay exists because "signed in" and "signed out" are not the only states the UI has to
   * render: between page load and this promise settling, the answer is genuinely unknown, and
   * that window is long enough to see on a real network. Resolving instantly — as this stub did
   * originally — makes that third state untestable and easy to forget.
   *
   * Seed `stubAuthInit = 'fail'` for the fourth state: **the adapter never came up at all**. Real
   * keycloak-js rejects here more readily than it looks — its 3rd-party-cookie probe runs before
   * the callback is processed and rejects on a 10s timeout — and the app's response to that is
   * the subject of EZ-1825. Without this, "init failed" was unreachable from a test and the app
   * answered it by redirecting to the IdP that had just failed, forever.
   */
  init() {
    const delay = Number(localStorage.getItem('stubAuthDelayMs') ?? 0)
    const fails = localStorage.getItem('stubAuthInit') === 'fail'
    // The real message, so a spec asserting on the console output is asserting on something the
    // application will actually see.
    const settle = fails
      ? (resolve, reject) =>
          reject(new Error('Timeout when waiting for 3rd party check iframe message.'))
      : (resolve) => resolve(this.authenticated)

    if (!delay) return new Promise(settle)
    return new Promise((resolve, reject) => setTimeout(() => settle(resolve, reject), delay))
  }

  updateToken() { return Promise.resolve(false) }

  login(options) {
    this.loginCalls.push(options ?? {})

    try {
      sessionStorage.setItem(Keycloak.TOTAL_KEY, String(Keycloak.readTotal() + 1))
    } catch {
      // Then a spec counting across loads sees nothing, and says so by failing. Nothing to do here.
    }

    // Off unless a spec asks for it, because following the redirect is a page navigation and every
    // spec written before this one assumes login() is inert.
    //
    // Seeded, it models the round trip a live Keycloak makes when the SSO session is healthy: the
    // browser goes to the redirect URI and comes back signed in, with a token as good as the last
    // one. That is the arrangement in which a client that answers every 401 with another login()
    // never stops, and it cannot be reproduced by a stub that only takes notes.
    if (localStorage.getItem('stubLoginNavigates') === 'yes' && options?.redirectUri) {
      window.location.assign(options.redirectUri)
    }
  }

  logout() {}

  // The account console URL, which the settings page links to. Shaped like the real one — the real
  // method builds `<url>realms/<realm>/account?referrer=...` — because a stub that answers with
  // something implausible turns a genuine "this link is wrong" into a passing test.
  //
  // Added after the settings page crashed here with "createAccountUrl is not a function": the stub
  // silently lags the real API surface as pages start using more of it, and each gap looks like an
  // application bug until you read the stack.
  //
  // The referrer is echoed rather than dropped, because the page now passes one and the argument is
  // the part worth checking: it becomes `referrer_uri`, which Keycloak validates against the
  // client's redirect URIs exactly as it validates a login's, so a fragment on it is the same
  // hazard as EZ-1825's. A stub that ignored the argument made that untestable.
  createAccountUrl(options) {
    const base = 'https://idp.example/realms/stub/account'
    const referrer = options?.redirectUri
    return referrer ? `${base}?referrer_uri=${encodeURIComponent(referrer)}` : base
  }
}
