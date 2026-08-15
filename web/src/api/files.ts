import { useMutation } from '@tanstack/react-query'
import { apiFetch, ApiResponseError } from './client.ts'

/** What `POST /v2/files` answers with. */
export interface UploadedFile {
  id: string
  /** Sanitised server-side, so not necessarily what was sent. Use this one in the URL. */
  filename: string
  /** Sniffed from the content by Tika, never taken from the client. */
  mime_type: string
}

/**
 * Why an upload failed, in the terms a person can act on.
 *
 * `unknown` is deliberately last and deliberately rare: "something went wrong" for a file that was
 * one megabyte over a limit is a bad afternoon, and the two cases below are the two that actually
 * happen.
 */
export type UploadErrorKind = 'tooLarge' | 'rejected' | 'unknown'

export function uploadErrorKind(e: unknown): UploadErrorKind {
  if (!(e instanceof ApiResponseError)) return 'unknown'
  // 413 without a body is the reverse proxy refusing the request before core ever saw it, so core's
  // role-aware message ("over the N-byte limit for this account") never gets written. Only the
  // proxy knows, and all it says is the status.
  if (e.status === 413) return 'tooLarge'
  if (e.errorBody?.code === 'INVALID_PARAMETER_VALUE') return 'rejected'
  return 'unknown'
}

/**
 * Upload one file.
 *
 * No query keys to invalidate: nothing in the app lists stored files, and a file is reachable only
 * through the URL this returns. If an admin file browser ever appears, that is when this grows one.
 */
export function useUploadFile() {
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData()
      // The part name the endpoint reads. Changing it is a 400 with no useful message.
      form.append('file', file)
      return apiFetch<UploadedFile>('/files', { method: 'POST', body: form })
    },
  })
}

/**
 * Where a stored file is served from. **Relative on purpose** — this string is written into content
 * that gets rendered to HTML once and cached, so an absolute URL would bake one environment's
 * hostname into every article. It resolves because the web origin proxies `/v2/resource/` to core.
 */
export function storedFileUrl(file: UploadedFile) {
  return `/v2/resource/${file.id}/${encodeURIComponent(file.filename)}`
}
