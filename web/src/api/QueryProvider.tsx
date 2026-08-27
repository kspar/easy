import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { type ReactNode, useEffect, useRef } from 'react'
import config from '../config.ts'
import { setTokenProvider } from './client.ts'
import { useAuth } from '../auth/useAuth.ts'

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
        return undefined
      }
      return kc.token
    })
  }, [])

  return (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
}