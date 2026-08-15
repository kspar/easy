import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Alert, Box, CircularProgress } from '@mui/material'
import { keymap, type EditorView } from '@codemirror/view'
import type { Extension } from '@codemirror/state'
import CodeEditor from '../CodeEditor.tsx'
import MarkdownToolbar from './MarkdownToolbar.tsx'
import { FULL_TOOLS, type MarkdownTool } from './markdownTools.ts'
import { applyFormat, insertCodeBlock, insertLink } from './markdownActions.ts'
import { useMarkdownUpload } from './useMarkdownUpload.ts'
import { useFileDropExtension } from './useFileDropExtension.ts'

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
  allowUpload = true,
}: {
  value: string
  onChange: (value: string) => void
  readOnly?: boolean
  placeholder?: string
  minHeight?: string
  tools?: MarkdownTool[]
  /** Set false where the caller does not want files accepted at all. */
  allowUpload?: boolean
}) {
  const [lang, setLang] = useState<Extension | undefined>(markdownExtension)
  const [view, setView] = useState<EditorView | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const { uploadFiles, uploading, error, clearError } = useMarkdownUpload()

  const uploadEnabled = allowUpload && !readOnly

  const onFiles = useCallback(
    (files: File[]) => {
      if (view) void uploadFiles(view, files)
    },
    [view, uploadFiles],
  )
  const dropExtension = useFileDropExtension(uploadEnabled ? onFiles : null)

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
  // a box that is not supposed to accept typing. The drop handler is memoised by its own hook and
  // is stable for the same reason — a new array here rebuilds CodeMirror on every render.
  const extensions = useMemo(
    () => (readOnly ? undefined : [...SHORTCUTS, ...(dropExtension ?? [])]),
    [readOnly, dropExtension],
  )

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
          <MarkdownToolbar
            view={view}
            tools={tools}
            onPickFile={uploadEnabled ? () => fileInput.current?.click() : undefined}
          />
          {uploading && <CircularProgress size={14} sx={{ ml: 1 }} />}
        </Box>
      )}

      {uploadEnabled && (
        // No `accept`: the backend takes any type and sniffes it itself, and a filter here would
        // only stop someone attaching the PDF handout they meant to attach.
        <input
          ref={fileInput}
          type="file"
          multiple
          hidden
          onChange={(e) => {
            const files = Array.from(e.target.files ?? [])
            // Reset first, so picking the same file twice in a row still fires a change event.
            e.target.value = ''
            if (view && files.length) void uploadFiles(view, files)
          }}
        />
      )}

      {error && (
        <Alert severity="error" onClose={clearError} sx={{ mb: 1 }}>
          {error}
        </Alert>
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
