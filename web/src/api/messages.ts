import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from './client.ts'

export type MessageSeverity = 'URGENT' | 'INFO'

export interface SystemMessage {
  id: string
  message: string
  severity: MessageSeverity
  link_url?: string
  link_label?: string
}

/** How often to ask. See the note in useSystemMessages. */
const POLL_MS = 60_000

/**
 * System messages the signed-in user should be seeing right now.
 *
 * Polled rather than pushed. The latency that matters here is minutes — "maintenance in two hours"
 * — and a socket per user held open through nginx and Spring Boot's thread pool is a real
 * operational change to buy latency nobody asked for. A minute is well inside what this is for.
 *
 * `refetchOnWindowFocus` is the half that makes it feel immediate in practice: the common case is a
 * tab left open in the background, and returning to it re-asks straight away rather than waiting out
 * the remainder of the interval.
 *
 * The server decides what is visible — the time window and the role targeting are both applied in
 * SQL. Nothing is filtered here, deliberately: a message scheduled for next week that arrived in
 * this response would already have been announced, whatever this component chose to draw.
 */
export function useSystemMessages(enabled = true) {
  return useQuery({
    queryKey: ['system', 'messages'],
    queryFn: () =>
      apiFetch<{ messages?: SystemMessage[] }>('/management/common/notifications').then(
        (r) => r.messages ?? [],
      ),
    enabled,
    refetchInterval: POLL_MS,
    refetchOnWindowFocus: true,
    // A failed poll is not worth a retry storm or an error surfaced to the user: the next tick is
    // a minute away and this is decoration on top of the app, not part of it.
    retry: false,
    // NO staleTime, deliberately. Setting it to the poll interval looks tidy and quietly disables
    // the focus refetch above — React Query will not refetch data it still considers fresh, so a
    // tab returned to after five minutes would show the previous minute's answer until the next
    // tick. That was the first version of this, and it made the one behaviour the feature is for
    // stop working while every other check still passed.
  })
}

// --- admin: authoring -----------------------------------------------------------------------------

export interface AdminSystemMessage extends SystemMessage {
  visible_from?: string | null
  visible_until?: string | null
  for_students: boolean
  for_teachers: boolean
  for_admins: boolean
}

/** What the form sends. Ids are assigned by core, so a draft has none. */
export type SystemMessageDraft = Omit<AdminSystemMessage, 'id'>

/**
 * Every message, whatever its schedule — including ones not yet visible and ones already expired.
 *
 * Deliberately a different query from useSystemMessages: that one asks "what should I be seeing",
 * this one asks "what exists". An admin editing next week's maintenance notice has to be able to
 * see it, which is precisely what the other endpoint is built never to return.
 */
export function useAdminSystemMessages(enabled = true) {
  return useQuery({
    queryKey: ['admin', 'system', 'messages'],
    queryFn: () =>
      apiFetch<{ messages?: AdminSystemMessage[] }>('/management/notifications').then(
        (r) => r.messages ?? [],
      ),
    enabled,
  })
}

/**
 * Both lists are invalidated after every write.
 *
 * The banner's own query is the one the author is about to look at to check their work, and it is
 * otherwise up to a minute stale — which reads as "the save did not take" rather than as polling.
 */
function useInvalidateMessages() {
  const qc = useQueryClient()
  return () => {
    void qc.invalidateQueries({ queryKey: ['admin', 'system', 'messages'] })
    void qc.invalidateQueries({ queryKey: ['system', 'messages'] })
  }
}

export function useCreateSystemMessage() {
  const invalidate = useInvalidateMessages()
  return useMutation({
    mutationFn: (draft: SystemMessageDraft) =>
      apiFetch('/management/notifications', { method: 'POST', body: draft }),
    onSuccess: invalidate,
  })
}

export function useUpdateSystemMessage() {
  const invalidate = useInvalidateMessages()
  return useMutation({
    mutationFn: ({ id, ...draft }: AdminSystemMessage) =>
      apiFetch(`/management/notifications/${id}`, { method: 'PATCH', body: draft }),
    onSuccess: invalidate,
  })
}

export function useDeleteSystemMessage() {
  const invalidate = useInvalidateMessages()
  return useMutation({
    mutationFn: (id: string) =>
      apiFetch(`/management/notifications/${id}`, { method: 'DELETE' }),
    onSuccess: invalidate,
  })
}
