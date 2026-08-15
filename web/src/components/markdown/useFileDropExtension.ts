import { useEffect, useMemo, useRef } from 'react'
import { EditorView } from '@codemirror/view'
import type { Extension } from '@codemirror/state'

/**
 * A CodeMirror extension that turns pasting or dropping files into an upload.
 *
 * **Why the ref.** `CodeEditor`'s `extensions` prop sits in the effect that builds the editor, so it
 * has to be referentially stable — a new array per render tears CodeMirror down and rebuilds it,
 * which in practice means losing focus and the undo history mid-word. The handler therefore has to
 * be built once, and the only way for a once-built handler to see a callback that changes every
 * render is to read it from a ref at call time.
 *
 * Returns `undefined` when there is nothing to do, so a read-only editor gets no handler at all
 * rather than one that checks a flag.
 */
export function useFileDropExtension(
  onFiles: ((files: File[]) => void) | null,
): Extension[] | undefined {
  const ref = useRef(onFiles)
  useEffect(() => {
    ref.current = onFiles
  }, [onFiles])

  const enabled = onFiles !== null

  return useMemo(() => {
    if (!enabled) return undefined
    return [
      EditorView.domEventHandlers({
        paste(event) {
          const files = filesFrom(event.clipboardData)
          // Only claim the event when there is actually a file. Calling preventDefault on every
          // paste would break pasting text, which is the thing people do a thousand times more
          // often — and the clipboard carries both when you copy an image out of a document.
          if (!files.length) return false
          event.preventDefault()
          ref.current?.(files)
          return true
        },
        drop(event) {
          const files = filesFrom(event.dataTransfer)
          // Same reasoning, plus one more: dropping selected text within the editor is a move, and
          // swallowing it would silently break that.
          if (!files.length) return false
          event.preventDefault()
          ref.current?.(files)
          return true
        },
      }),
    ]
  }, [enabled])
}

function filesFrom(data: DataTransfer | null): File[] {
  if (!data) return []
  // `items` rather than `files` for the paste case: a screenshot on the clipboard arrives as an
  // item of kind "file" and shows up in both, but text pasted alongside it only pollutes `items`,
  // so filtering by kind is what keeps a mixed paste from being treated as a file drop.
  return Array.from(data.items ?? [])
    .filter((i) => i.kind === 'file')
    .map((i) => i.getAsFile())
    .filter((f): f is File => f !== null)
}
