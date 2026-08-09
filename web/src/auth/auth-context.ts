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

export interface LoginOptions {
  locale?: string
  /**
   * Where to land after authenticating. Absolute, or a path this app serves.
   *
   * Omit it and keycloak-js falls back to `location.href`, which is right almost everywhere:
   * RequireAuth calls login() from the protected page the user asked for, so they are returned
   * to it. The exception is the landing page, where "back where you started" means the marketing
   * page rather than the app — see LandingPage.
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
