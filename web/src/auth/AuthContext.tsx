import { useEffect, useState, useCallback, useRef, type ReactNode } from 'react'
import Keycloak from 'keycloak-js'
import config from '../config.ts'
import { AuthContext, type Role, type AuthState, type LoginOptions } from './auth-context.ts'
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
  const recoverSession = useCallback((reason: string) => {
    if (recoveringRef.current) return
    recoveringRef.current = true
    console.warn(`Session lost (${reason}); returning to the identity provider`)
    keycloak.login()
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
      setState((s) => ({ ...s, initialized: true, authenticated: false }))
      return
    }

    keycloak
      .init({
        onLoad: 'check-sso',
        pkceMethod: 'S256',
      })
      .then((authenticated) => {
        if (authenticated) {
          const roles = getRolesFromToken(keycloak)
          const activeRole = getPersistedRole(roles) ?? getMainRole(roles)
          localStorage.setItem(ROLE_STORAGE_KEY, activeRole)

          setState({
            initialized: true,
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
            .then(() => setState((prev) => ({ ...prev, checkedIn: true })))
            .catch((err) => {
              console.error('Account checkin failed', err)
              setState((prev) => ({ ...prev, checkinFailed: true }))
            })
        } else {
          setState((prev) => ({ ...prev, initialized: true }))
        }
      })
      .catch((err) => {
        console.error('Keycloak init failed', err)
        setState((prev) => ({ ...prev, initialized: true }))
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
      keycloak.login({
        locale: options?.locale ?? 'et',
        // Passed through only when a caller asked for it. keycloak-js otherwise defaults to
        // location.href, which is what RequireAuth wants — it is invoked from the protected page
        // the user was trying to reach, so "back where you started" is the correct destination.
        ...(options?.redirectUri ? { redirectUri: options.redirectUri } : {}),
      })
    },
    [],
  )

  const logout = useCallback(() => {
    keycloak.logout()
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

