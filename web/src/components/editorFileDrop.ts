import { useEffect, useMemo, useRef } from 'react'
import { EditorView, ViewPlugin } from '@codemirror/view'
import type { Extension } from '@codemirror/state'

/**
 * Dropping a file onto a CodeMirror editor, and — for the prose editors — pasting one.
 *
 * **Why the drop is not an `EditorView.domEventHandlers` entry.** Those are registered on
 * `view.contentDOM`, and nothing in CodeMirror ever calls `preventDefault` on `dragover`. An
 * element is only a drop target if something says so, and the sole reason a drop ever reached the
 * editor at all is that a `contenteditable` is one by default — for *content the browser considers
 * compatible*. A file is not: Chromium's plain-text editable accepts a drag only when it carries
 * plain text, so it refuses the drag, no drop event is fired, and the browser falls back to what it
 * does with a file dropped on a page, which is to open the file and throw the editor away with the
 * page. The gutter and the padding around the text are not the contenteditable at all, so a drop
 * landing on the line numbers was never handled under any browser.
 *
 * So the listeners go on `view.dom` — the whole editor, gutter included — in the **capture** phase.
 * Capture, because `view.dom` is an ancestor of `contentDOM`: in the bubble phase CodeMirror's own
 * file-drop handler would run first and splice the file in at the drop position, on top of whatever
 * the caller then does with the same file.
 *
 * Only drags carrying files are claimed. Dragging a selection from one place in the document to
 * another is a move that CodeMirror implements itself, and swallowing `dragover` wholesale would
 * silently break it.
 *
 * **Why the ref.** `extensions` sits in the effect that builds the editor, so it has to be
 * referentially stable — a new array per render tears CodeMirror down and rebuilds it, which in
 * practice means losing focus and the undo history mid-word. The handler is therefore built once,
 * and the only way for a once-built handler to see a callback that changes every render is to read
 * it from a ref at call time.
 *
 * Both hooks return `undefined` when there is nothing to do, so a read-only editor gets no handler
 * at all rather than one that checks a flag.
 */
export function useFileDropExtension(
  onFiles: ((files: File[]) => void) | null,
): Extension[] | undefined {
  return useFileExtension(onFiles, false)
}

/**
 * Drop, plus paste. For the markdown editors, where a screenshot on the clipboard is as natural a
 * way to attach an image as dragging the file in. Code editors use [useFileDropExtension] instead:
 * there a paste has to stay a paste.
 */
export function useFileDropAndPasteExtension(
  onFiles: ((files: File[]) => void) | null,
): Extension[] | undefined {
  return useFileExtension(onFiles, true)
}

function useFileExtension(
  onFiles: ((files: File[]) => void) | null,
  handlePaste: boolean,
): Extension[] | undefined {
  const ref = useRef(onFiles)
  useEffect(() => {
    ref.current = onFiles
  }, [onFiles])

  const enabled = onFiles !== null

  return useMemo(() => {
    if (!enabled) return undefined
    const deliver = (files: File[]) => ref.current?.(files)
    const extensions: Extension[] = [
      ViewPlugin.define((view) => new FileDropHandler(view, deliver)),
      dropHighlightTheme,
    ]
    if (handlePaste) {
      extensions.push(
        EditorView.domEventHandlers({
          paste(event) {
            const files = filesFrom(event.clipboardData)
            // Only claim the event when there is actually a file. Calling preventDefault on every
            // paste would break pasting text, which is the thing people do a thousand times more
            // often — and the clipboard carries both when you copy an image out of a document.
            if (!files.length) return false
            event.preventDefault()
            deliver(files)
            return true
          },
        }),
      )
    }
    return extensions
  }, [enabled, handlePaste])
}

/** Says the drop will land here, which a plain outline-less editor does not. */
const DRAG_OVER_CLASS = 'easy-cm-fileDragOver'

const dropHighlightTheme = EditorView.theme({
  [`&.${DRAG_OVER_CLASS}`]: {
    outline: '2px dashed currentColor',
    outlineOffset: '-2px',
  },
})

class FileDropHandler {
  private readonly view: EditorView
  private readonly onFiles: (files: File[]) => void

  constructor(view: EditorView, onFiles: (files: File[]) => void) {
    this.view = view
    this.onFiles = onFiles
    const dom = this.view.dom
    dom.addEventListener('dragenter', this.dragOver, true)
    dom.addEventListener('dragover', this.dragOver, true)
    dom.addEventListener('dragleave', this.dragLeave, true)
    dom.addEventListener('dragend', this.dragEnd, true)
    dom.addEventListener('drop', this.drop, true)
  }

  destroy() {
    const dom = this.view.dom
    dom.removeEventListener('dragenter', this.dragOver, true)
    dom.removeEventListener('dragover', this.dragOver, true)
    dom.removeEventListener('dragleave', this.dragLeave, true)
    dom.removeEventListener('dragend', this.dragEnd, true)
    dom.removeEventListener('drop', this.drop, true)
  }

  private dragOver = (event: DragEvent) => {
    if (!carriesFiles(event.dataTransfer)) return
    // This, and only this, is what makes the editor a drop target for a file.
    event.preventDefault()
    if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy'
    this.view.dom.classList.add(DRAG_OVER_CLASS)
  }

  private dragLeave = (event: DragEvent) => {
    // Moving between the gutter and the text fires dragleave on the way out of each; only a
    // relatedTarget outside the editor means the pointer has actually left it.
    const to = event.relatedTarget
    if (to instanceof Node && this.view.dom.contains(to)) return
    this.dragEnd()
  }

  private dragEnd = () => {
    this.view.dom.classList.remove(DRAG_OVER_CLASS)
  }

  private drop = (event: DragEvent) => {
    if (!carriesFiles(event.dataTransfer)) return
    event.preventDefault()
    // CodeMirror's own drop handler sits on the contentDOM below this one and would otherwise
    // insert the file's text at the drop position as well.
    event.stopPropagation()
    this.dragEnd()
    const files = filesFrom(event.dataTransfer)
    if (files.length) this.onFiles(files)
  }
}

/**
 * Whether a *drag in progress* carries files. `dataTransfer.files` is empty until the drop — the
 * page is not allowed to read what it is being offered while the drag is still moving — so the
 * question can only be asked of `types`.
 */
function carriesFiles(data: DataTransfer | null): boolean {
  return data !== null && Array.from(data.types).includes('Files')
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
