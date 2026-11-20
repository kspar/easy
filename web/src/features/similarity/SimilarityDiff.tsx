import { useEffect, useRef } from 'react'
import { MergeView } from '@codemirror/merge'
import { EditorView, lineNumbers } from '@codemirror/view'
import { syntaxHighlighting, defaultHighlightStyle } from '@codemirror/language'
import { oneDark } from '@codemirror/theme-one-dark'
import { useTheme } from '@mui/material/styles'
import { Box } from '@mui/material'
import { languageFromFilename } from '../course-exercise/editorLanguage.ts'
import { useSoftWrap } from '../../components/editorWrap.ts'
import { readOnlyEditor } from '../../components/editorReadOnly.ts'
import { matchHighlighting, sharedCodeHighlighting } from './similarityHighlight.ts'
import type { MatchedLines } from './similarityHighlight.ts'

/**
 * Two solutions side by side, with the matching regions marked.
 *
 * The alignment is the point. A pair of scores tells a teacher that two submissions resemble each
 * other; only seeing *where* they line up tells them whether that is two people solving the same
 * small exercise the obvious way, or the same file with the names changed. Reading two plain editors
 * and diffing by eye is the thing this page exists to avoid.
 *
 * Read-only on both sides — this is evidence, not a workspace.
 *
 * On a phone the two sides stack, each named: side by side, two narrow panes showed a few characters
 * of every line.
 */
export default function SimilarityDiff({
  left,
  right,
  fileName,
  matchedLeft,
  matchedRight,
  selectedMatch,
  leftLabel,
  rightLabel,
}: {
  left: string
  right: string
  fileName?: string
  /**
   * The lines JPlag matched on each side. Given, they are what is marked, and an empty list is a real
   * answer: nothing matched. Not given - edit distance, which says how alike two texts are but not where -
   * what is marked is what the merge view's character diff leaves unchanged, which is what that score measured.
   */
  matchedLeft?: MatchedLines[]
  matchedRight?: MatchedLines[]
  /** The match picked from the page's list, as an index into both: marked apart, and scrolled to on each side. */
  selectedMatch?: number
  /** Whose each side is, shown above it where the sides stack. */
  leftLabel?: string
  rightLabel?: string
}) {
  const host = useRef<HTMLDivElement>(null)
  const theme = useTheme()
  const dark = theme.palette.mode === 'dark'
  // A MergeView owns two editors, and a compartment can only be reconfigured through a view we
  // hold — so this one rebuilds on the setting instead, the same way it already rebuilds on theme.
  const { wrap, wrapExtension } = useSoftWrap('code')

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

      const base = [
        lineNumbers(),
        readOnlyEditor,
        wrapExtension(),
        syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
        lang,
        ...(dark ? [oneDark] : []),
      ]
      // Each side its own ranges, so the two editors cannot share one extension list.
      const marking = (matched?: MatchedLines[]) =>
        matched ? matchHighlighting(dark, matched, selectedMatch === undefined ? undefined : matched[selectedMatch]) : sharedCodeHighlighting(dark)

      view = new MergeView({
        a: { doc: left, extensions: [...base, ...marking(matchedLeft)] },
        b: { doc: right, extensions: [...base, ...marking(matchedRight)] },
        parent: host.current,
        // `revertControls` is deliberately absent rather than false: the option's type is
        // "a-to-b" | "b-to-a", so omitting it is how you get no revert arrows. Passing false type-errors
        // — which CI caught and a bare `tsc --noEmit` did not, because the real check is `tsc -b`.
        //
        // Either way there should be none: nothing here is editable, so they would be controls that
        // cannot do anything.
        gutter: true,
      })
      const editors = view.dom.querySelectorAll('.cm-mergeViewEditor')
      ;[leftLabel, rightLabel].forEach((label, side) => { if (label) editors[side]?.setAttribute('data-label', label) })
      // The picked match, brought into view on each side: on a long pair it is below the fold on both.
      // A frame later, once the merge view has aligned the sides with spacers, and inside the line rather
      // than at its start, where a spacer sits: either way a side whose match came after a spacer showed
      // the empty spacer instead of the code.
      if (selectedMatch !== undefined) {
        const merged = view
        requestAnimationFrame(() => {
          if (cancelled) return
          ;[merged.a, merged.b].forEach((editor, side) => {
            const range = (side === 0 ? matchedLeft : matchedRight)?.[selectedMatch]
            if (!range || range.startLine < 1) return
            const line = editor.state.doc.line(Math.min(range.startLine, editor.state.doc.lines))
            editor.dispatch({ effects: EditorView.scrollIntoView(Math.min(line.from + 1, line.to), { y: 'start', yMargin: 24 }) })
          })
        })
      }
    })

    return () => {
      cancelled = true
      view?.destroy()
    }
  }, [left, right, fileName, dark, wrap, wrapExtension, matchedLeft, matchedRight, selectedMatch, leftLabel, rightLabel])

  return (
    <Box
      ref={host}
      sx={{
        '& .cm-mergeView': { maxHeight: 460 },
        '& .cm-editor': { maxHeight: 460 },
        '& .cm-scroller': { overflow: 'auto' },
        [theme.breakpoints.down('sm')]: {
          '& .cm-mergeView': { maxHeight: 'none' },
          '& .cm-mergeViewEditors': { flexDirection: 'column' },
          '& .cm-mergeViewEditor': { flexBasis: 'auto' },
          '& .cm-editor': { maxHeight: 320 },
          '& .cm-mergeViewEditor::before': {
            content: 'attr(data-label)',
            display: 'block',
            px: 1,
            py: 0.5,
            fontSize: 12,
            fontWeight: 500,
            borderBottom: 1,
            borderColor: 'divider',
          },
        },
        border: 1,
        borderColor: 'divider',
        borderRadius: 1,
        overflow: 'hidden',
        fontSize: 13,
      }}
    />
  )
}
