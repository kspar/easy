import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { type ReactNode, useEffect, useRef } from 'react'
import config from '../config.ts'
import { setCachedTokenProvider, setTokenProvider } from './client.ts'
import { useAuth } from '../auth/useAuth.ts'
import { record } from '../features/bug-report/breadcrumbs.ts'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
    },
  },
})

export function QueryProvider({ children }: { children: ReactNode }) {
  const { keycloak, checkedIn } = useAuth()
  const keycloakRef = useRef(keycloak)
  keycloakRef.current = keycloak

  // Queries outside RequireAuth can fire before the account has been checked in, refresh those
  useEffect(() => {
    if (checkedIn) {
      queryClient.invalidateQueries()
    }
  }, [checkedIn])

  useEffect(() => {
    setTokenProvider(async () => {
      const kc = keycloakRef.current
      if (!kc?.authenticated) return undefined
      try {
        await kc.updateToken(config.keycloakTokenMinValidSec)
      } catch {
        // Return nothing rather than `kc.token`. The refresh just failed, so that token is the
        // expired one and core will reject it — sending it produces a 401 that looks like a
        // permission problem instead of an expired session. With no token the request is
        // unauthenticated, which is the truth, and `AuthContext`'s 401 handler takes it from there.
        console.warn('Token refresh failed in query provider')
        // The console patch would catch the warning above, but as a `CONSOLE` line among console
        // noise. As an `AUTH` line it sits with the rest of the session's story, which is where
        // someone reading "everything says no permission" will be looking.
        record('auth', 'refreshing the token before a request failed; sending it unauthenticated')
        return undefined
      }
      return kc.token
    })
    setCachedTokenProvider(() => {
      const kc = keycloakRef.current
      return kc?.authenticated ? kc.token : undefined
    })
  }, [])

  return (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
}