/**
 * Saving something to the user's machine as a file.
 *
 * Two editors offer this — the student's own solution editor and the teacher's grading panel — and
 * they had a copy each of the same six lines. One of them would have been fixed and the other not.
 *
 * The details that are not obvious, both of them ways the naive version fails outside Chromium:
 *
 * - **The anchor is attached to the document before it is clicked.** A `click()` on a detached node
 *   is ignored by some browsers, and the save silently does nothing — no error, no file.
 * - **The object URL is revoked on a timer, not on the next line.** Revoking synchronously after
 *   `click()` races the browser: where the download is fetched asynchronously, the URL can already
 *   be dead by the time it is read, and again the failure is silent. A minute is far longer than
 *   any download of a source file needs and still bounds the leak.
 */

export function downloadBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  a.rel = 'noopener'
  a.style.display = 'none'
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 60_000)
}

export function downloadTextFile(text: string, fileName: string) {
  downloadBlob(new Blob([text], { type: 'text/plain' }), fileName)
}

/**
 * Save a response core sent as a file, under the name core gave it.
 *
 * Core is the only side that knows the student's real name and the submission's id, so it builds
 * the name and sends it in `Content-Disposition`. `fallbackName` covers a proxy that dropped the
 * header rather than being a name anyone should expect to see.
 */
export async function saveResponseAsFile(response: Response, fallbackName: string) {
  const blob = await response.blob()
  downloadBlob(blob, filenameFromContentDisposition(response.headers.get('Content-Disposition')) ?? fallbackName)
}

/**
 * The `filename` out of a `Content-Disposition` header, or null.
 *
 * Quotes are optional in the header and core sends the name unquoted, so both shapes are read.
 * `filename*`, the RFC 5987 encoded form, is not handled because core does not send it — if that
 * changes, this returns null and the caller falls back rather than producing a mangled name.
 *
 * Any path separator in the result is dropped. The value is a server-supplied string landing in a
 * download attribute, and while core builds it from a name and an id, a filename is not the place
 * to trust that shape blindly.
 */
export function filenameFromContentDisposition(header: string | null): string | null {
  if (!header) return null
  const match = /filename\s*=\s*"([^"]*)"|filename\s*=\s*([^;]+)/i.exec(header)
  const raw = (match?.[1] ?? match?.[2] ?? '').trim()
  if (!raw) return null
  const cleaned = raw.replace(/[/\\]/g, '_')
  return cleaned === '' || cleaned === '.' || cleaned === '..' ? null : cleaned
}

/**
 * A local timestamp for a filename: `2026-09-12_14-32-11`.
 *
 * The old interface used `toLocaleString('sv')` here, which is the neat trick for an ISO-shaped
 * local time — but it leaves the colons in, and a colon cannot appear in a filename on Windows,
 * where most of the people saving these files are. The browser drops the download or rewrites the
 * name. Dashes instead, and the sort order the ISO shape exists for is kept.
 */
export function fileTimestamp(date = new Date()): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())}` +
    `_${p(date.getHours())}-${p(date.getMinutes())}-${p(date.getSeconds())}`
}
