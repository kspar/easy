import { useEffect, useRef } from 'react'
import { MergeView } from '@codemirror/merge'
import { EditorView, lineNumbers } from '@codemirror/view'
import { EditorState } from '@codemirror/state'
import { syntaxHighlighting, defaultHighlightStyle } from '@codemirror/language'
import { oneDark } from '@codemirror/theme-one-dark'
import { useTheme } from '@mui/material/styles'
import { Box } from '@mui/material'
import { languageFromFilename } from '../course-exercise/editorLanguage.ts'

/**
 * Two solutions side by side, with the matching regions marked.
 *
 * The alignment is the point. A pair of scores tells a teacher that two submissions resemble each
 * other; only seeing *where* they line up tells them whether that is two people solving the same
 * small exercise the obvious way, or the same file with the names changed. Reading two plain editors
 * and diffing by eye is the thing this page exists to avoid.
 *
 * Read-only on both sides — this is evidence, not a workspace.
 */
export default function SimilarityDiff({
  left,
  right,
  fileName,
}: {
  left: string
  right: string
  fileName?: string
}) {
  const host = useRef<HTMLDivElement>(null)
  const theme = useTheme()
  const dark = theme.palette.mode === 'dark'

  useEffect(() => {
    if (!host.current) return
    let cancelled = false
    let view: MergeView | undefined

    // The language modes are code-split, so this resolves a tick later — the same shape
    // ReadOnlyCodeSnippet uses. Without the cancelled flag, a fast re-render leaves an orphaned
    // MergeView attached to a host the effect has already cleaned up.
    const langPromise = fileName ? languageFromFilename(fileName) : Promise.resolve([])

    langPromise.then((lang) => {
      if (cancelled || !host.current) return

      const extensions = [
        lineNumbers(),
        EditorView.editable.of(false),
        EditorState.readOnly.of(true),
        EditorView.lineWrapping,
        syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
        lang,
        ...(dark ? [oneDark] : []),
      ]

      view = new MergeView({
        a: { doc: left, extensions },
        b: { doc: right, extensions },
        parent: host.current,
        // Nothing here is editable, so the revert arrows between the panes would be controls that
        // cannot do anything.
        revertControls: false,
        gutter: true,
      })
    })

    return () => {
      cancelled = true
      view?.destroy()
    }
  }, [left, right, fileName, dark])

  return (
    <Box
      ref={host}
      sx={{
        '& .cm-mergeView': { maxHeight: 460 },
        '& .cm-editor': { maxHeight: 460 },
        '& .cm-scroller': { overflow: 'auto' },
        border: 1,
        borderColor: 'divider',
        borderRadius: 1,
        overflow: 'hidden',
        fontSize: 13,
      }}
    />
  )
}
