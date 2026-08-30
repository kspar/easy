import { useEffect, useState, useCallback, useRef, type ReactNode } from 'react'
import Keycloak from 'keycloak-js'
import config from '../config.ts'
import {
  AuthContext,
  returnUri,
  type Role,
  type AuthState,
  type LoginOptions,
} from './auth-context.ts'
import { apiFetch, setUnauthorizedHandler } from '../api/client.ts'

// Type-only re-export, so the many existing `import { type Role } from './AuthContext.tsx'`
// call sites keep working. Erased at runtime, so it doesn't count as a non-component export
// and Fast Refresh stays happy.
export type { Role } from './auth-context.ts'

const ROLE_STORAGE_KEY = 'activeRole'
const ALL_ROLES: Role[] = ['admin', 'teacher', 'student']

function getRolesFromToken(keycloak: Keycloak): Role[] {
  const easyRole = keycloak.tokenParsed?.easy_role as string | string[] | undefined
  if (!easyRole) return []
  const roleStr = Array.isArray(easyRole) ? easyRole : (easyRole as string).split(',')
  return ALL_ROLES.filter((r) => roleStr.includes(r))
}

function getMainRole(roles: Role[]): Role {
  if (roles.includes('admin')) return 'admin'
  if (roles.includes('teacher')) return 'teacher'
  if (roles.includes('student')) return 'student'
  throw new Error('No valid roles found')
}

/**
 * Push the account's personal data to core. This is also the only place where an account row is
 * created, so it must succeed before any other request for a user who's logging in for the first time.
 */
async function checkin(keycloak: Keycloak) {
  const firstName = keycloak.tokenParsed?.given_name as string | undefined
  const lastName = keycloak.tokenParsed?.family_name as string | undefined
  if (!firstName || !lastName) {
    throw new Error('Token is missing given_name or family_name')
  }
  await apiFetch('/account/checkin', {
    method: 'POST',
    body: { first_name: firstName, last_name: lastName },
    headers: { Authorization: `Bearer ${keycloak.token}` },
  })
}

/**
 * Marks that this tab has already been to the IdP once to recover a session, and has not had a
 * working one since.
 *
 * In `sessionStorage` rather than a ref, and that is the entire point: `recoveringRef` limits the
 * bounces to one *per page load*, and a redirect ends the page load. So it does not limit anything
 * a loop cares about — one bounce per load, forever, is still forever.
 *
 * The case is real and core has code for it. `EasyUserJwtConverter` throws
 * `InvalidBearerTokenException` — a 401, not a 403 — for a token whose `easy_role` claim is missing,
 * empty, or names a role it cannot map, and an issuer or JWKS mismatch between the realm and core
 * behaves the same way. Every one of those is a 401 answering a token Keycloak has just minted and
 * will mint again, identically, as often as it is asked. Without a marker that outlives the
 * navigation, the app asks forever and never says anything.
 *
 * Cleared by a successful checkin, which is the first authenticated call of every page load and so
 * the earliest honest evidence that the session works. Anything that survives that is a session
 * worth spending a redirect on next time.
 *
 * `sessionStorage` and not `localStorage`: it is scoped to the tab, like the problem, and it goes
 * away on its own when the tab does.
 */
const RECOVERY_ATTEMPTED_KEY = 'easyAuthRecoveryAttempted'

/**
 * The three below all swallow their errors. Storage throws rather than degrading in a few real
 * configurations (Safari's private mode historically, and anything with site data blocked), and
 * the failure mode has to be the old behaviour — one redirect, which is what happens when
 * [recoveryAttempted] answers false — rather than an exception thrown out of a 401 handler.
 */
function recoveryAttempted(): boolean {
  try {
    return sessionStorage.getItem(RECOVERY_ATTEMPTED_KEY) !== null
  } catch {
    return false
  }
}

function markRecoveryAttempted() {
  try {
    sessionStorage.setItem(RECOVERY_ATTEMPTED_KEY, String(Date.now()))
  } catch {
    // Then the guard is off, and a loop is caught by nothing. Still better than throwing here.
  }
}

function clearRecoveryAttempt() {
  try {
    sessionStorage.removeItem(RECOVERY_ATTEMPTED_KEY)
  } catch {
    // Nothing to clear if it could not be written in the first place.
  }
}

function getPersistedRole(roles: Role[]): Role | null {
  const stored = localStorage.getItem(ROLE_STORAGE_KEY)
  if (!stored) return null
  const role = stored as Role
  return roles.includes(role) ? role : null
}

// One instance per page load, at module scope rather than in useState. AuthProvider mutates
// it — init() writes to it, and onTokenExpired is assigned below — and React forbids mutating
// a value returned from useState. The constructor only builds an object; nothing touches the
// network until init(), so creating it at import time is safe.
//
// It does, however, read config at import time, and config is now fetched at runtime
// (EZ-1726). `main.tsx` therefore awaits loadConfig() and imports the app dynamically, so this
// module is evaluated only after config.json has been applied. Don't add a static import of
// App.tsx (or of this file) to main.tsx — that would evaluate this line with an empty realm.
const keycloak = new Keycloak(config.keycloak)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({
    initialized: false,
    initFailed: false,
    authFailed: false,
    authenticated: false,
    token: undefined,
    firstName: undefined,
    lastName: undefined,
    email: undefined,
    username: undefined,
    activeRole: 'student',
    availableRoles: [],
    checkedIn: false,
    checkinFailed: false,
  })

  const initCalled = useRef(false)

  /**
   * Get the session back, or find out there isn't one — by redirecting to the IdP.
   *
   * Guarded, because both callers can fire repeatedly: `onTokenExpired` can be raised again, and a
   * page with a dozen queries produces a dozen 401s at once. Without the flag that is a dozen
   * `login()` calls racing to set `location`, which in the worst case is a redirect loop rather than
   * a login. One attempt per page load is all that can help anyway — after it, either the redirect
   * happens or this tab is gone.
   */
  const recoveringRef = useRef(false)
  /**
   * Set once the session is known to be unrecoverable *by redirecting* — either flavour, see
   * `AuthState.initFailed` and `AuthState.authFailed`.
   *
   * A ref rather than the state itself because [recoverSession] is a `useCallback` over no
   * dependencies and so cannot read the state it closes over, and its stability is what keeps the
   * init effect below running exactly once.
   */
  const noRedirectRef = useRef(false)
  /**
   * Whether `init()` has settled — either way, and the "either" is deliberate: this says the
   * adapter has finished asking, not that it liked the answer.
   *
   * A ref for the same reason as [noRedirectRef]: [recoverSession] closes over no state.
   */
  const initializedRef = useRef(false)
  const recoverSession = useCallback((reason: string) => {
    if (recoveringRef.current) return
    // Nothing a redirect can recover. Either the IdP could not be reached — in which case a 401 is
    // a symptom of that rather than a separate problem — or it answered and the session it gave
    // back was unusable, in which case asking again returns the same one.
    if (noRedirectRef.current) return
    // Nothing a redirect can recover *yet*, either — and this one is the loop of EZ-1828.
    //
    // Before `init()` settles, whether there is a session is genuinely unknown, so a 401 is not
    // evidence that one was lost. It is usually evidence of the opposite: a request that went out
    // before there was a token to put on it. That is not hypothetical — AppLayout's sidebar queries
    // start in a mount effect, effects run child-first, so they fire before this component's own
    // effect has called `init()` at all, and they used to go out with no `Authorization` header.
    // Core answers 401, this handler concluded the session was gone, and the IdP handed back a
    // perfectly good token to a page that immediately did it all again.
    //
    // Doing nothing is safe because every outcome of `init()` is already handled: authenticated
    // refetches those queries once checkin completes (see QueryProvider), not authenticated is a
    // redirect from RequireAuth, and a rejection is `initFailed` and the AuthUnavailable screen.
    //
    // Returning *before* `recoveringRef` is set, so a genuine 401 later in the page's life still
    // gets its one attempt.
    if (!initializedRef.current) {
      console.warn(`Ignoring a 401 from before the session was known (${reason})`)
      return
    }
    // Already tried, and here we are again with core still saying no — see
    // [RECOVERY_ATTEMPTED_KEY]. The IdP has answered once and its answer did not help, so asking a
    // second time fetches the identical token and the third time fetches it again. Stop, and say so:
    // `authFailed` is exactly this state, and it puts an error with a bug reporter on the screen
    // instead of a browser that never stops loading.
    if (recoveryAttempted()) {
      noRedirectRef.current = true
      console.error(
        `Signed in, but core still rejects the session after returning from the identity provider (${reason})`,
      )
      setState((prev) => ({ ...prev, authFailed: true }))
      return
    }
    markRecoveryAttempted()
    recoveringRef.current = true
    console.warn(`Session lost (${reason}); returning to the identity provider`)
    keycloak.login({ redirectUri: returnUri() })
  }, [])

  useEffect(() => {
    if (initCalled.current) return
    initCalled.current = true

    // An embedded exercise runs inside someone else's page and has no user. `check-sso` would
    // still open a hidden iframe against the IdP from that third-party context — a cross-site
    // request the host page never asked for, blocked by most browsers' third-party cookie rules
    // anyway, and pure latency in front of content that needs no login. Report "initialised, not
    // authenticated" and never contact Keycloak at all.
    //
    // Matched on the path rather than a prop because AuthProvider sits above the router in
    // App.tsx and so cannot be told which route it is about to render.
    if (window.location.pathname.startsWith('/embed/')) {
      initializedRef.current = true
      setState((s) => ({ ...s, initialized: true, authenticated: false }))
      return
    }

    keycloak
      .init({
        onLoad: 'check-sso',
        pkceMethod: 'S256',
        // Pinned here as well as passed at every login() below, and the belt-and-braces is
        // deliberate: `this.redirectUri` is what keycloak-js's own internal `doLogin` falls back
        // on when `check-sso` finds no session, so pinning it means no code path anywhere — ours
        // or the library's — can hand Keycloak a URL with a fragment on it. It also rescues
        // anyone still holding a URL poisoned by EZ-1825, whose fragment would otherwise be
        // carried straight back into the next redirect.
        redirectUri: returnUri(),
        // Turning the session-status iframe off also skips the probe that runs *before* the
        // callback is processed: keycloak-js only builds its 3rd-party-cookie iframe when this is
        // enabled. That probe is what made EZ-1825 possible — it waits up to `messageReceiveTimeout`
        // (10s) for a postMessage from a hidden iframe at the IdP and **rejects** on timeout rather
        // than degrading, taking `init()` down before it ever looks at the `code` in the URL.
        //
        // Nothing is lost. The iframe's job is noticing a logout in another tab, nothing here
        // listens for `onAuthLogout`, and any browser that blocks third-party cookies disables it
        // a moment later anyway. A dead session is still caught — by core answering 401, which is
        // the path `setUnauthorizedHandler` below exists for.
        checkLoginIframe: false,
      })
      .then(
        (authenticated) => {
          // First line of both settle handlers, before anything below can throw. From here on a
          // 401 means what it says, and [recoverSession] will act on one.
          initializedRef.current = true
          if (authenticated) {
            const roles = getRolesFromToken(keycloak)
            const activeRole = getPersistedRole(roles) ?? getMainRole(roles)
            localStorage.setItem(ROLE_STORAGE_KEY, activeRole)

            setState({
              initialized: true,
              initFailed: false,
              authFailed: false,
              authenticated: true,
              token: keycloak.token,
              firstName: keycloak.tokenParsed?.given_name as string | undefined,
              lastName: keycloak.tokenParsed?.family_name as string | undefined,
              email: keycloak.tokenParsed?.email as string | undefined,
              username: keycloak.tokenParsed?.preferred_username as string | undefined,
              activeRole,
              availableRoles: roles,
              checkedIn: false,
              checkinFailed: false,
            })

            checkin(keycloak)
              .then(() => {
                // The first authenticated call of the page load came back, so whatever was wrong
                // last time is not wrong now and the next lost session gets its redirect. See
                // [RECOVERY_ATTEMPTED_KEY].
                clearRecoveryAttempt()
                setState((prev) => ({ ...prev, checkedIn: true }))
              })
              .catch((err) => {
                console.error('Account checkin failed', err)
                // The error too, not just the flag: check-in failing is the first thing a user
                // meets and the least explicable, and core says why (audit X-035).
                setState((prev) => ({ ...prev, checkinFailed: true, checkinError: err }))
              })
          } else {
            setState((prev) => ({ ...prev, initialized: true }))
          }
        },
        // Rejection handler as `then`'s second argument rather than a `.catch` on the end, and the
        // difference is not stylistic. A trailing `.catch` also catches whatever the success
        // handler above throws — and it can throw: `getMainRole()` does, for a token whose
        // `easy_role` claim names nothing this app knows. That was being reported as "the IdP is
        // unreachable" to someone who had just successfully signed in, and it disabled 401 recovery
        // for the rest of the page besides. Only an `init()` that actually rejected reaches here.
        (err) => {
          initializedRef.current = true
          console.error('Keycloak init failed', err)
          // `initFailed`, not just `initialized`. Reporting a failed init as "initialised, no
          // session" told RequireAuth to send the user to the IdP — the very thing that had just
          // failed — and the answer came back to an init that failed the same way, forever. See
          // AuthState.initFailed.
          noRedirectRef.current = true
          setState((prev) => ({ ...prev, initialized: true, initFailed: true }))
        },
      )
      // What the success handler throws, landing here and nowhere else. Reached with a valid token
      // in hand, so the IdP is not the problem and going back to it would only fetch the same token
      // again — see AuthState.authFailed for why that loops rather than recovers.
      .catch((err) => {
        console.error('Signed in, but the session could not be used', err)
        noRedirectRef.current = true
        setState((prev) => ({ ...prev, initialized: true, authFailed: true }))
      })

    keycloak.onTokenExpired = () => {
      keycloak.updateToken(config.keycloakTokenMinValidSec).catch(() => {
        // The refresh token is gone or rejected, so there is nothing left to recover from in this
        // tab. Previously this logged and stopped: every subsequent query 401'd, react-query gave up
        // after one retry, and the user was left in error and spinner states with no way back that
        // anything told them about. A reload fixed it, by re-running check-sso.
        //
        // So do what the reload does. `login()` goes to the IdP, and if the SSO session is still
        // alive it comes straight back to the same URL with a fresh token and the user notices
        // nothing; if it is not, they get the login page, which is the honest answer.
        recoverSession('the token expired and could not be refreshed')
      })
    }

    // Same recovery, reached the other way: core rejected a token we thought was good. Registered
    // here because `api/client.ts` cannot import this context without a cycle, and because the
    // decision of what to do about it is this module's, not the fetch layer's.
    setUnauthorizedHandler(() => recoverSession('core answered 401'))
    // keycloak is a module-level constant now, so it is not a valid dependency. `recoverSession` is
    // listed and is a `useCallback` over no dependencies, so it is stable and this still runs once.
  }, [recoverSession])

  const switchRole = useCallback(
    (role: Role) => {
      if (!state.availableRoles.includes(role)) {
        console.error(`Cannot switch to role ${role}`)
        return
      }
      localStorage.setItem(ROLE_STORAGE_KEY, role)
      setState((prev) => ({ ...prev, activeRole: role }))
    },
    [state.availableRoles],
  )

  const login = useCallback(
    (options?: LoginOptions) => {
      // The adapter is not in a state to build a login URL, and saying so beats the alternative:
      // when `init()` failed inside `#loadConfig`, `keycloak.endpoints` is still undefined and
      // `createLoginUrl` throws inside a promise — a button that does nothing, reports nothing, and
      // leaves an unhandled rejection in the console. Callers outside RequireAuth (the landing
      // page's call to action, chiefly) can still reach this.
      if (noRedirectRef.current) {
        console.warn('Ignoring login(): the identity provider could not be reached')
        return
      }
      keycloak.login({
        locale: options?.locale ?? 'et',
        // Always sent, never left to keycloak-js's own default. Its default is `location.href`,
        // which is the same page `returnUri()` names but with the fragment still attached — and a
        // fragment attached to a redirect URI is what Keycloak appends the next callback's params
        // to, one group per bounce, until it refuses the request (EZ-1825).
        //
        // The destination is otherwise unchanged: RequireAuth is invoked from the protected page
        // the user was trying to reach, so "back where you started" remains correct there, and the
        // landing page still overrides it to land in the app instead.
        redirectUri: options?.redirectUri ?? returnUri(),
      })
    },
    [],
  )

  const logout = useCallback(() => {
    // Same guard as login(), same reason — `createLogoutUrl` reads the same endpoints.
    if (noRedirectRef.current) {
      console.warn('Ignoring logout(): the identity provider could not be reached')
      return
    }
    // Explicit for the same reason as login(), and it keeps the destination where it already was:
    // the default is `this.redirectUri`, which `init()` now pins to the page the bundle loaded on,
    // and coming back to *that* page after logging out from somewhere else would be a change.
    keycloak.logout({ redirectUri: returnUri() })
  }, [])

  const refreshToken = useCallback(async () => {
    try {
      const refreshed = await keycloak.updateToken(config.keycloakTokenMinValidSec)
      if (refreshed) {
        setState((prev) => ({ ...prev, token: keycloak.token }))
      }
      return true
    } catch {
      console.warn('Token refresh failed')
      return false
    }
  }, [])

  return (
    <AuthContext.Provider
      value={{ ...state, keycloak, switchRole, login, logout, refreshToken }}
    >
      {children}
    </AuthContext.Provider>
  )
}

