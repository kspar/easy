import { useQuery } from '@tanstack/react-query'
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
