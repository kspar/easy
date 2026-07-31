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

export interface AuthState {
  initialized: boolean
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

export interface AuthContextType extends AuthState {
  keycloak: Keycloak | null
  switchRole: (role: Role) => void
  login: (locale?: string) => void
  // No locale parameter: KeycloakLogoutOptions has no `locale` field (only login does),
  // so the value passed here was always discarded. No caller supplied one either.
  logout: () => void
  refreshToken: () => Promise<boolean>
}

export const AuthContext = createContext<AuthContextType | null>(null)
