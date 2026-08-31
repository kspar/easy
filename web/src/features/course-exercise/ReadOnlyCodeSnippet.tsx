import { useEffect, useRef } from 'react'
import { EditorView, lineNumbers } from '@codemirror/view'
import { EditorState } from '@codemirror/state'
import { syntaxHighlighting, defaultHighlightStyle } from '@codemirror/language'
import { oneDark } from '@codemirror/theme-one-dark'
import { useTheme } from '@mui/material/styles'
import { Box } from '@mui/material'
import { languageFromFilename } from './editorLanguage.ts'
import { useSoftWrap } from '../../components/editorWrap.ts'

interface Props {
  code: string
  fileName?: string
  firstLineNumber?: number
  maxHeight?: number
}

export default function ReadOnlyCodeSnippet({
  code,
  fileName,
  firstLineNumber = 1,
  maxHeight,
}: Props) {
  const theme = useTheme()
  const containerRef = useRef<HTMLDivElement>(null)
  const viewRef = useRef<EditorView | null>(null)
  const { wrapExtension } = useSoftWrap('code', viewRef)
  const isDark = theme.palette.mode === 'dark'

  useEffect(() => {
    if (!containerRef.current) return
    let cancelled = false

    const langPromise = fileName
      ? languageFromFilename(fileName)
      : Promise.resolve([])

    langPromise.then((lang) => {
      if (cancelled || !containerRef.current) return

      viewRef.current?.destroy()

      const snippetTheme = EditorView.theme({
        '&': {
          fontSize: '0.75rem',
        },
        '&.cm-editor': {
          borderRadius: '4px',
          overflow: 'hidden',
        },
        '&.cm-editor.cm-focused': {
          outline: 'none',
        },
        '.cm-content': {
          padding: '4px 0',
        },
        '.cm-line': {
          padding: '0 6px',
        },
        '.cm-gutters': {
          border: 'none',
          backgroundColor: 'transparent',
        },
        '.cm-lineNumbers .cm-gutterElement': {
          padding: '0 6px 0 8px',
          minWidth: '28px',
          fontSize: '0.7rem',
          opacity: '0.5',
        },
        '.cm-activeLine': {
          backgroundColor: 'transparent',
        },
        '.cm-activeLineGutter': {
          backgroundColor: 'transparent',
        },
        // Hide cursor
        '.cm-cursor, .cm-dropCursor': {
          display: 'none',
        },
        '.cm-selectionBackground': {
          backgroundColor: 'transparent !important',
        },
      })

      const extensions = [
        lineNumbers({ formatNumber: (n) => String(n + firstLineNumber - 1) }),
        lang,
        syntaxHighlighting(defaultHighlightStyle),
        EditorState.readOnly.of(true),
        EditorView.editable.of(false),
        wrapExtension(),
        snippetTheme,
      ]
      if (isDark) extensions.push(oneDark)

      const state = EditorState.create({ doc: code, extensions })
      viewRef.current = new EditorView({
        state,
        parent: containerRef.current,
      })
    })

    return () => {
      cancelled = true
      viewRef.current?.destroy()
      viewRef.current = null
    }
    // `wrapExtension` is stable, so it is here to satisfy the rule rather than to cause rebuilds.
  }, [code, fileName, firstLineNumber, isDark, wrapExtension])

  return (
    <Box
      ref={containerRef}
      sx={{
        overflow: 'auto',
        maxHeight: maxHeight ?? undefined,
        borderRadius: 0.5,
        bgcolor: isDark ? undefined : 'action.hover',
        '& .cm-editor': {
          backgroundColor: isDark ? undefined : 'transparent',
        },
      }}
    />
  )
}
