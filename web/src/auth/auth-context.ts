import { createContext } from 'react'
import type Keycloak from 'keycloak-js'

/**
 * Auth types and the context object.
 *
 * Split out of AuthContext.tsx so that file exports only the AuthProvider component:
 * mixing component and non-component exports breaks React Fast Refresh
 * (react-refresh/only-export-components). The hook lives in useAuth.ts.
 */

export type Role = 'student' | 'teacher' | 'admin'

/**
 * Where the IdP should send the browser back to: the current page, deliberately **without**
 * `location.hash`.
 *
 * That one omission is the difference between a failed login and a runaway URL (EZ-1825).
 * keycloak-js defaults `redirectUri` to `location.href` in full, and Keycloak answers a
 * fragment-mode authorization request by *appending* `state&session_state&iss&code` to whatever
 * fragment the redirect URI already carries rather than replacing it. The only place keycloak-js
 * ever strips those params back off is the `history.replaceState` at the top of its
 * `#processInit` — which does not run at all if `init()` rejects before reaching it. So a failed
 * init left the callback params sitting in the URL, the retry handed them back to Keycloak as
 * part of the redirect URI, and every bounce added another group until the authorization request
 * was refused outright with `invalid_redirect_uri`.
 *
 * Use this for **every** URL handed to Keycloak — `login`, `logout`, and the account console's
 * `referrer_uri`, which is validated against the same list of valid redirect URIs. Not
 * `location.href`, ever.
 *
 * Only the fragment is dropped: `search` is preserved, because query parameters are how this app's
 * pages carry state worth returning to. That is safe while the response mode is `fragment`, which
 * is keycloak-js's default and unset here — a deployment that switched to `responseMode: 'query'`
 * would need this to drop the callback params from `search` too.
 *
 * Computed per call rather than once at module load, because this is a single-page app: the page
 * the user is on when a session needs recovering is usually not the one the bundle loaded on.
 *
 * Here rather than in AuthContext.tsx so the account page can use it too: that file exports only
 * the AuthProvider component, and a second non-component export there breaks Fast Refresh.
 */
export function returnUri(): string {
  const { origin, pathname, search } = window.location
  return `${origin}${pathname}${search}`
}

export interface AuthState {
  initialized: boolean
  /**
   * Whether `keycloak.init()` rejected — the IdP could not be reached, or its answer could not be
   * processed.
   *
   * Distinct from `initialized && !authenticated`, and the distinction is the whole point.
   * "Initialised, no session" means *ask the IdP who this is*, which is a redirect. "The adapter
   * never came up" means the IdP is the thing that is broken, and redirecting to it is the one
   * response guaranteed to make it worse: the redirect comes back, `init()` fails the same way,
   * and the app bounces to the IdP again. Conflating the two is what produced EZ-1825.
   */
  initFailed: boolean
  /**
   * Whether the IdP answered, said the visitor is signed in, and the session still could not be
   * used — today that means [getMainRole] found no role this app recognises in the token.
   *
   * Separate from [initFailed] because the remedy differs: that one is "the IdP is unreachable,
   * try again", this one is "your account cannot use this application", and refreshing will not
   * change it. What the two share is the part that matters here — **neither may be answered with
   * a redirect to the IdP**. This one is the more insidious of the pair, because the redirect
   * succeeds: the IdP hands back the same valid token, the same claim is missing, and round it
   * goes.
   */
  authFailed: boolean
  authenticated: boolean
  token: string | undefined
  firstName: string | undefined
  lastName: string | undefined
  email: string | undefined
  username: string | undefined
  activeRole: Role
  availableRoles: Role[]
  /** Whether the account has been checked in to core, see checkin(). */
  checkedIn: boolean
  checkinFailed: boolean
}

export interface LoginOptions {
  locale?: string
  /**
   * Where to land after authenticating. Absolute, or a path this app serves.
   *
   * Omit it and you get the current page without its fragment, which is right almost everywhere:
   * RequireAuth calls login() from the protected page the user asked for, so they are returned
   * to it. The exception is the landing page, where "back where you started" means the marketing
   * page rather than the app — see LandingPage.
   *
   * Note "without its fragment". The default used to be keycloak-js's own, which is
   * `location.href` in full — see `returnUri()` in AuthContext.tsx for why that one bit is the
   * difference between a failed login and a runaway URL. A value passed here is used as given, so
   * don't build one out of `location.href` either.
   */
  redirectUri?: string
}

export interface AuthContextType extends AuthState {
  keycloak: Keycloak | null
  switchRole: (role: Role) => void
  login: (options?: LoginOptions) => void
  // No locale parameter: KeycloakLogoutOptions has no `locale` field (only login does),
  // so the value passed here was always discarded. No caller supplied one either.
  logout: () => void
  refreshToken: () => Promise<boolean>
}

export const AuthContext = createContext<AuthContextType | null>(null)
