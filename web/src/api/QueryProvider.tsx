import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { type ReactNode, useEffect, useRef } from 'react'
import { setTokenProvider } from './client.ts'
import { useAuth } from '../auth/AuthContext.tsx'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
    },
  },
})

export function QueryProvider({ children }: { children: ReactNode }) {
  const { keycloak } = useAuth()
  const keycloakRef = useRef(keycloak)
  keycloakRef.current = keycloak

  useEffect(() => {
    setTokenProvider(async () => {
      const kc = keycloakRef.current
      if (!kc?.authenticated) return undefined
      try {
        await kc.updateToken(30)
      } catch {
        console.warn('Token refresh failed in query provider')
      }
      return kc.token
    })
  }, [])

  return (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
}