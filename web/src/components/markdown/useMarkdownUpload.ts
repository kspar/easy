import { useCallback, useState } from 'react'
import type { EditorView } from '@codemirror/view'
import { useTranslation } from 'react-i18next'
import { storedFileUrl, UPLOAD_LIMIT_MB, uploadErrorKind, useUploadFile } from '../../api/files.ts'
import { findPlaceholder, markdownForUpload, uploadPlaceholder } from './markdownActions.ts'

/**
 * Uploading a file from inside a Markdown editor, and putting a link to it where the caret was.
 *
 * Shared by every editor that accepts a file, which is all four of them — the two full ones through
 * their toolbar, the two compact ones through paste and drop only.
 *
 * **The placeholder is the interesting part.** The file is inserted as a marker first and rewritten
 * when the upload finishes, so the author is never blocked: a 20 MB upload over a domestic
 * connection is tens of seconds, and freezing the editor for it would be worse than useless. That
 * means the document has almost certainly changed by the time the response arrives, so the marker is
 * found by *searching for it* rather than by remembering where it was put. Offsets recorded at
 * insert time are stale the moment anyone types above them.
 */
export function useMarkdownUpload() {
  const { t } = useTranslation()
  const upload = useUploadFile()
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(0)

  const uploadOne = useCallback(
    async (view: EditorView, file: File) => {
      // Math.random is fine: this only has to be unique among the handful of uploads in flight in
      // one editor, not unguessable. The real key comes from the server.
      const nonce = Math.random().toString(16).slice(2, 10)
      const placeholder = uploadPlaceholder(file.name, nonce)

      const at = view.state.selection.main
      view.dispatch({
        changes: { from: at.from, to: at.to, insert: placeholder },
        selection: { anchor: at.from + placeholder.length },
      })
      view.focus()

      const replaceWith = (text: string) => {
        const found = findPlaceholder(view.state.doc.toString(), file.name, nonce)
        // Gone means the author deleted it while we were uploading, which is a perfectly reasonable
        // thing to do and not an error. The file stays in storage and the sweep collects it.
        if (!found) return
        view.dispatch({
          changes: { from: found.from, to: found.to, insert: text },
          selection: { anchor: found.from + text.length },
        })
      }

      setBusy((n) => n + 1)
      try {
        const uploaded = await upload.mutateAsync(file)
        replaceWith(markdownForUpload(storedFileUrl(uploaded), uploaded.filename, uploaded.mime_type))
        setError(null)
      } catch (e) {
        replaceWith('')
        setError(t(`markdown.uploadError.${uploadErrorKind(e)}`, { limit: UPLOAD_LIMIT_MB }))
      } finally {
        setBusy((n) => n - 1)
      }
    },
    [upload, t],
  )

  /**
   * Sequential, not parallel. Dropping ten screenshots at once would otherwise open ten concurrent
   * uploads and interleave their placeholders in whatever order the responses came back.
   */
  const uploadFiles = useCallback(
    async (view: EditorView, files: File[]) => {
      for (const file of files) await uploadOne(view, file)
    },
    [uploadOne],
  )

  return { uploadFiles, uploading: busy > 0, error, clearError: () => setError(null) }
}
