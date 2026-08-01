import { useEffect, useMemo, useState } from 'react'
import { Box } from '@mui/material'
import { keymap, type EditorView } from '@codemirror/view'
import type { Extension } from '@codemirror/state'
import CodeEditor from '../CodeEditor.tsx'
import MarkdownToolbar from './MarkdownToolbar.tsx'
import { FULL_TOOLS, type MarkdownTool } from './markdownTools.ts'
import { applyFormat, insertCodeBlock, insertLink } from './markdownActions.ts'

/**
 * A Markdown source editor with a formatting toolbar and the usual keyboard shortcuts.
 *
 * The rendered preview is deliberately *not* here. Both callers already have somewhere better to
 * put it — the exercise page renders it in the facing pane, the feedback editor underneath the
 * box — and a preview inside this component would mean two of them on screen.
 */

/** Loaded once: rebuilding the language extension per render would rebuild the editor with it. */
let markdownExtension: Extension | undefined

/**
 * Module scope on purpose. CodeEditor keeps this in its effect dependencies, so a new array each
 * render would tear down and rebuild CodeMirror on every keystroke.
 *
 * Placeholders are English here rather than translated: a keyboard shortcut cannot reach the
 * `t` function without making this array per-render, and the text is immediately selected for
 * overtyping anyway. The toolbar buttons, which can, do translate them.
 */
const SHORTCUTS: Extension[] = [
  keymap.of([
    { key: 'Mod-b', run: (v) => { applyFormat(v, '**', '**', 'bold'); return true } },
    { key: 'Mod-i', run: (v) => { applyFormat(v, '_', '_', 'italic'); return true } },
    { key: 'Mod-e', run: (v) => { applyFormat(v, '`', '`', 'code'); return true } },
    { key: 'Mod-k', run: (v) => { insertLink(v, 'https://', 'link text'); return true } },
    { key: 'Mod-Shift-c', run: (v) => { insertCodeBlock(v); return true } },
  ]),
]

export default function MarkdownEditor({
  value,
  onChange,
  readOnly = false,
  placeholder,
  minHeight = '30rem',
  tools = FULL_TOOLS,
}: {
  value: string
  onChange: (value: string) => void
  readOnly?: boolean
  placeholder?: string
  minHeight?: string
  tools?: MarkdownTool[]
}) {
  const [lang, setLang] = useState<Extension | undefined>(markdownExtension)
  const [view, setView] = useState<EditorView | null>(null)

  useEffect(() => {
    if (markdownExtension) return
    let cancelled = false
    import('@codemirror/lang-markdown').then((m) => {
      markdownExtension = m.markdown()
      if (!cancelled) setLang(markdownExtension)
    })
    return () => {
      cancelled = true
    }
  }, [])

  // Stable across renders, and dropped entirely when read-only so the shortcuts cannot type into
  // a box that is not supposed to accept typing.
  const extensions = useMemo(() => (readOnly ? undefined : SHORTCUTS), [readOnly])

  return (
    <Box>
      {!readOnly && (
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            px: 0.75,
            py: 0.5,
            border: 1,
            borderBottom: 0,
            borderColor: 'divider',
            borderRadius: '4px 4px 0 0',
          }}
        >
          <MarkdownToolbar view={view} tools={tools} />
        </Box>
      )}
      <Box
        sx={{
          // The editor draws its own border; square off the corner the toolbar sits against.
          '& > div': readOnly ? {} : { borderRadius: '0 0 4px 4px' },
        }}
      >
        <CodeEditor
          value={value}
          onChange={onChange}
          language={lang}
          readOnly={readOnly}
          placeholder={placeholder}
          minHeight={minHeight}
          extensions={extensions}
          onViewReady={setView}
        />
      </Box>
    </Box>
  )
}
