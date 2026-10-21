import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { apiFetch } from './client.ts'
import type { AdminSimilarityLanguageReq, AdminSimilarityProviderReq, AdminSimilarityProviders } from './types.ts'

const KEY = ['admin', 'similarity-providers']

/** EZ-1667. The similarity providers an admin chooses between, and which compares each language now. */
export function useAdminSimilarityProviders() {
  return useQuery({
    queryKey: KEY,
    queryFn: () => apiFetch<AdminSimilarityProviders>('/admin/similarity-providers'),
  })
}

/**
 * What an admin changes here decides what every course's dialog offers and what every estimate prices,
 * so those go too - in this tab; another tab's catch up on their own next read.
 */
function invalidateDependents(queryClient: QueryClient) {
  queryClient.invalidateQueries({ queryKey: KEY })
  queryClient.invalidateQueries({ predicate: (query) => query.queryKey[0] === 'courses' && query.queryKey[2] === 'similarity-settings' })
  queryClient.invalidateQueries({ queryKey: ['similarity-estimate'] })
}

export function useUpdateSimilarityProvider() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: AdminSimilarityProviderReq }) =>
      apiFetch(`/admin/similarity-providers/${encodeURIComponent(id)}`, { method: 'PUT', body }),
    onSuccess: () => invalidateDependents(queryClient),
  })
}

export function useUpdateSimilarityLanguage() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ language, body }: { language: string; body: AdminSimilarityLanguageReq }) =>
      apiFetch(`/admin/similarity-languages/${encodeURIComponent(language)}`, { method: 'PUT', body }),
    onSuccess: () => invalidateDependents(queryClient),
  })
}
