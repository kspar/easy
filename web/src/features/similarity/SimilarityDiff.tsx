import { useEffect, useRef, useState } from 'react'
import { MergeView } from '@codemirror/merge'
import { EditorView, lineNumbers } from '@codemirror/view'
import { syntaxHighlighting, defaultHighlightStyle } from '@codemirror/language'
import { oneDark } from '@codemirror/theme-one-dark'
import { useTheme } from '@mui/material/styles'
import { Box, Typography } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { languageFromFilename } from '../course-exercise/editorLanguage.ts'
import { useSoftWrap } from '../../components/editorWrap.ts'
import { readOnlyEditor } from '../../components/editorReadOnly.ts'
import { chunkHighlighting, tokenMatchHighlighting } from './similarityHighlight.ts'
import { hiddenMarking } from './similarityHidden.ts'
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
 * On a phone the two sides stack, each named: side by side, two panes of some 150 px showed a few
 * characters of every line.
 */
export default function SimilarityDiff({
  left,
  right,
  fileName,
  matchedLeft,
  matchedRight,
  matchRanges = true,
  selectedMatch,
  leftLabel,
  rightLabel,
}: {
  left: string
  right: string
  fileName?: string
  /**
   * The regions the comparison matched, per side, from `scores[].matches`, read when [matchRanges].
   *
   * An empty list is a real answer — the pair matched nothing — and renders as no highlight. Omitted
   * behaves the same way.
   */
  matchedLeft?: MatchedLines[]
  matchedRight?: MatchedLines[]
  /** The match picked from the page's list, as an index into both: marked apart, and scrolled to on each side. */
  selectedMatch?: number
  /** Whose each side is, said above it where the sides stack. Two strings, not a pair, so a render keeps the editors. */
  leftLabel?: string
  rightLabel?: string
  /**
   * Whether the provider says where a pair matches (`match_ranges`). When it does not — edit distance,
   * which says only how alike two texts are — the shared code is what the merge view's own character
   * diff leaves unchanged, which is also what that score measured. Never the other way round: a
   * character diff disagrees with a token score hardest on the renamed copy the tokens exist to catch.
   */
  matchRanges?: boolean
}) {
  const host = useRef<HTMLDivElement>(null)
  const { t } = useTranslation()
  // A character diff that marked nothing on either side: the score counted characters scattered through
  // both, and the page says so rather than show a 55% pair with nothing to look at.
  const [nothingShared, setNothingShared] = useState(false)
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
    //
    // A grammar that fails to load is no grammar, not no diff. After a deploy, a tab still on the old
    // bundle asks for chunks the new release no longer has, and a rejected import built nothing: the
    // teacher got the names and the legend over an empty box.
    const langPromise = (fileName ? languageFromFilename(fileName) : Promise.resolve([])).catch(() => [])

    langPromise.then((lang) => {
      if (cancelled || !host.current) return
      const painted: Partial<Record<'a' | 'b', number>> = {}
      const report = (side: 'a' | 'b', count: number) => {
        painted[side] = count
        if (painted.a !== undefined && painted.b !== undefined) setNothingShared(painted.a === 0 && painted.b === 0)
      }

      const base = [
        lineNumbers(),
        readOnlyEditor,
        wrapExtension(),
        syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
        // What prints as nothing, and names mixing alphabets, marked where they are; see similarityHidden.
        hiddenMarking(t('similarity.invisibleTitle'), t('similarity.mixedScriptTitle')),
        lang,
        ...(dark ? [oneDark] : []),
      ]
      // Each side gets its own ranges, so the two editors cannot share one extension list. The chunk
      // version reads which side it is on from the merge view itself.
      const sided = (matched?: MatchedLines[]) =>
        matchRanges
          ? tokenMatchHighlighting(dark, matched ?? [], selectedMatch === undefined ? undefined : matched?.[selectedMatch])
          : chunkHighlighting(dark, report)

      view = new MergeView({
        a: { doc: left, extensions: [...base, ...sided(matchedLeft)] },
        b: { doc: right, extensions: [...base, ...sided(matchedRight)] },
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
      // The picked match, brought into view on each side: on a long pair it was below the fold on both.
      if (selectedMatch !== undefined) {
        const views = [view.a, view.b]
        ;[matchedLeft?.[selectedMatch], matchedRight?.[selectedMatch]].forEach((range, side) => {
          const editor = views[side]
          const line = range ? Math.max(1, Math.min(range.startLine, editor.state.doc.lines)) : 0
          if (line > 0) editor.dispatch({ effects: EditorView.scrollIntoView(editor.state.doc.line(line).from, { y: 'start', yMargin: 24 }) })
        })
      }
    })

    return () => {
      cancelled = true
      view?.destroy()
    }
  }, [left, right, fileName, dark, wrap, wrapExtension, matchedLeft, matchedRight, matchRanges, selectedMatch, leftLabel, rightLabel, t])

  return (
    <>
      <Box
        ref={host}
        sx={{
          '& .cm-mergeView': { maxHeight: 460 },
          '& .cm-editor': { maxHeight: 460 },
          '& .cm-scroller': { overflow: 'auto' },
          '& .cm-mergeViewEditor::before': { display: 'none' },
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
      {nothingShared && (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.75 }}>
          {t('similarity.nothingShared')}
        </Typography>
      )}
    </>
  )
}
