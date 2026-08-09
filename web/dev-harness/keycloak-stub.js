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
   * Resolves to the session state above, after `stubAuthDelayMs` if one is seeded.
   *
   * The delay exists because "signed in" and "signed out" are not the only states the UI has to
   * render: between page load and this promise settling, the answer is genuinely unknown, and
   * that window is long enough to see on a real network. Resolving instantly — as this stub did
   * originally — makes that third state untestable and easy to forget.
   */
  init() {
    const delay = Number(localStorage.getItem('stubAuthDelayMs') ?? 0)
    if (!delay) return Promise.resolve(this.authenticated)
    return new Promise((resolve) => setTimeout(() => resolve(this.authenticated), delay))
  }

  updateToken() { return Promise.resolve(false) }

  login(options) {
    this.loginCalls.push(options ?? {})
  }

  logout() {}

  // The account console URL, which the settings page links to. Shaped like the real one — the real
  // method builds `<url>realms/<realm>/account?referrer=...` — because a stub that answers with
  // something implausible turns a genuine "this link is wrong" into a passing test.
  //
  // Added after the settings page crashed here with "createAccountUrl is not a function": the stub
  // silently lags the real API surface as pages start using more of it, and each gap looks like an
  // application bug until you read the stack.
  createAccountUrl() {
    return 'https://idp.example/realms/stub/account'
  }
}
