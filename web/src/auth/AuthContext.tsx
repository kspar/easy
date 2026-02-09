import {
  createContext,
  useContext,
  useEffect,
  useState,
  useCallback,
  useRef,
  type ReactNode,
} from 'react'
import Keycloak from 'keycloak-js'
import config from '../config.ts'

export type Role = 'student' | 'teacher' | 'admin'

interface AuthState {
  initialized: boolean
  authenticated: boolean
  token: string | undefined
  firstName: string | undefined
  lastName: string | undefined
  email: string | undefined
  username: string | undefined
  activeRole: Role
  availableRoles: Role[]
}

interface AuthContextType extends AuthState {
  keycloak: Keycloak | null
  switchRole: (role: Role) => void
  login: (locale?: string) => void
  logout: (locale?: string) => void
  refreshToken: () => Promise<boolean>
}

const AuthContext = createContext<AuthContextType | null>(null)

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

function getPersistedRole(roles: Role[]): Role | null {
  const stored = localStorage.getItem(ROLE_STORAGE_KEY)
  if (!stored) return null
  const role = stored as Role
  return roles.includes(role) ? role : null
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [keycloak] = useState(
    () => new Keycloak(config.keycloak),
  )
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
  }, [keycloak])

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
    [keycloak],
  )

  const logout = useCallback(
    (locale?: string) => {
      keycloak.logout({ locale: locale ?? 'et' })
    },
    [keycloak],
  )

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
  }, [keycloak])

  return (
    <AuthContext.Provider
      value={{ ...state, keycloak, switchRole, login, logout, refreshToken }}
    >
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthContextType {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
