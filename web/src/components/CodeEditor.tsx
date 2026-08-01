import { useEffect, useRef } from 'react'
import { Box } from '@mui/material'
import { useTheme } from '@mui/material/styles'
import { EditorView, placeholder as cmPlaceholder } from '@codemirror/view'
import { EditorState, type Extension } from '@codemirror/state'
import { basicSetup } from 'codemirror'
import { oneDark } from '@codemirror/theme-one-dark'

/**
 * Thin CodeMirror 6 wrapper for the places that need a plain text/code box: the exercise
 * markdown editor, the grading script, the asset files, the embed snippets.
 *
 * Deliberately *not* a fully controlled component. Rebuilding the EditorState on every
 * keystroke would lose the cursor and undo history, so `value` seeds the editor and is
 * afterwards only pushed in when it differs from what the editor holds — i.e. when the parent
 * genuinely replaced the content (cancel, tab switch, reload after save).
 */
export default function CodeEditor({
  value,
  onChange,
  language,
  readOnly = false,
  placeholder,
  minHeight = '15rem',
  maxHeight,
  lineNumbers = true,
}: {
  value: string
  onChange?: (value: string) => void
  /** Resolved CodeMirror language extension, e.g. from `languageFromFilename`. */
  language?: Extension
  readOnly?: boolean
  placeholder?: string
  minHeight?: string
  maxHeight?: string
  lineNumbers?: boolean
}) {
  const theme = useTheme()
  const containerRef = useRef<HTMLDivElement>(null)
  const viewRef = useRef<EditorView | null>(null)

  // Kept in a ref so changing the handler doesn't force an editor rebuild
  const onChangeRef = useRef(onChange)
  useEffect(() => {
    onChangeRef.current = onChange
  }, [onChange])

  useEffect(() => {
    if (!containerRef.current) return

    const extensions: Extension[] = [
      basicSetup,
      EditorView.lineWrapping,
      EditorView.updateListener.of((u) => {
        if (u.docChanged) onChangeRef.current?.(u.state.doc.toString())
      }),
      EditorState.readOnly.of(readOnly),
      EditorView.editable.of(!readOnly),
      EditorView.theme({
        '&': { minHeight, ...(maxHeight ? { maxHeight } : {}) },
        '.cm-scroller': { overflow: 'auto' },
        '.cm-content': { paddingTop: '4px' },
        ...(lineNumbers ? {} : { '.cm-gutters': { display: 'none' } }),
      }),
    ]
    if (language) extensions.push(language)
    if (placeholder) extensions.push(cmPlaceholder(placeholder))
    if (theme.palette.mode === 'dark') extensions.push(oneDark)

    const view = new EditorView({
      state: EditorState.create({ doc: value, extensions }),
      parent: containerRef.current,
    })
    viewRef.current = view

    return () => {
      view.destroy()
      viewRef.current = null
    }
    // `value` is intentionally absent — it seeds the doc, the effect below syncs it afterwards.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [language, readOnly, placeholder, minHeight, maxHeight, lineNumbers, theme.palette.mode])

  useEffect(() => {
    const view = viewRef.current
    if (!view) return
    const current = view.state.doc.toString()
    if (current !== value) {
      view.dispatch({ changes: { from: 0, to: current.length, insert: value } })
    }
  }, [value])

  return (
    <Box
      ref={containerRef}
      sx={{
        border: 1,
        borderColor: 'divider',
        borderRadius: 1,
        overflow: 'hidden',
        '& .cm-editor.cm-focused': { outline: 'none' },
      }}
    />
  )
}
