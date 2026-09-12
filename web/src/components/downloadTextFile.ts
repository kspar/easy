/**
 * Save a string to the user's machine as a text file.
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
export function downloadTextFile(text: string, fileName: string) {
  const blob = new Blob([text], { type: 'text/plain' })
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
