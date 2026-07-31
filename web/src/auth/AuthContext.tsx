import { useEffect, useState, useCallback, useRef, type ReactNode } from 'react'
import Keycloak from 'keycloak-js'
import config from '../config.ts'
import { AuthContext, type Role, type AuthState } from './auth-context.ts'
import { apiFetch } from '../api/client.ts'

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

  useEffect(() => {
    if (initCalled.current) return
    initCalled.current = true

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
        console.warn('Token refresh failed')
      })
    }
    // keycloak is a module-level constant now, so it is not a valid dependency.
  }, [])

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
    (locale?: string) => {
      keycloak.login({ locale: locale ?? 'et' })
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

