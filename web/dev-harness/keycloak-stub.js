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

    this.authenticated = true
    this.token = 'stub-token'
    this.tokenParsed = {
      given_name: 'Test',
      family_name: isStudent ? 'Student' : 'Teacher',
      email: isStudent ? 'student@test.ee' : 'teacher@test.ee',
      preferred_username: isStudent ? 'dev-student' : 'dev-teacher',
      easy_role: role,
    }
  }

  init() { return Promise.resolve(true) }
  updateToken() { return Promise.resolve(false) }
  login() {}
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
