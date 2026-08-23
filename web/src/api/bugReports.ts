import { useMutation } from '@tanstack/react-query'
import { apiFetch } from './client.ts'

/**
 * Filing a bug report.
 *
 * Write-only from the app's point of view: there is no list endpoint and nothing to invalidate. A
 * filed report leaves through YouTrack and the admin mailbox, not back through this API — so a
 * reporter sees their own report exactly once, which is why the dialog shows them the full payload
 * before it goes rather than afterwards.
 */

/**
 * What the reporter is sending.
 *
 * `diagnostics` is **omitted, not emptied**, when they untick the consent checkbox. Core stores the
 * column as null in that case and treats null as "declined" rather than "there was nothing" — a
 * report about a blank page reads very differently depending on which, so sending `''` here would
 * throw away the distinction the column exists for.
 *
 * @requestBody POST /v2/bug-reports
 */
export interface BugReportDraft {
  message: string
  diagnostics?: string
  page_url?: string
  web_version?: string
  user_agent?: string
}

/** @endpoint POST /v2/bug-reports -> (root) */
export interface BugReportCreated {
  id: string
}

/**
 * Post one report.
 *
 * No retry, and that is a deliberate departure from the client's default of one. A retried POST
 * files the report twice — the endpoint is not idempotent and has no request key to deduplicate on
 * — and the failure this would paper over is one the reporter can see and act on, since the dialog
 * stays open with their text still in it.
 *
 * The rate limit surfaces here as an ordinary 400 with `BUG_REPORT_RATE_LIMITED`; the dialog tells
 * them to come back later rather than showing the generic failure.
 */
export function useCreateBugReport() {
  return useMutation({
    mutationFn: (draft: BugReportDraft) =>
      apiFetch<BugReportCreated>('/bug-reports', { method: 'POST', body: draft }),
    retry: false,
  })
}
