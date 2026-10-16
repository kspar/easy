import { ViewPlugin, Decoration, EditorView } from '@codemirror/view'
import type { DecorationSet, ViewUpdate } from '@codemirror/view'
import { RangeSetBuilder } from '@codemirror/state'

/**
 * Marks what the two solutions have in common, rather than what differs (EZ-1875).
 *
 * A merge view colours the *changes*, which is right when you are reviewing an edit and wrong here.
 * Measured on a 30-line pair, the default treatment paints:
 *
 * | pair                        | lines painted | inline underlines |
 * |-----------------------------|---------------|-------------------|
 * | same file, one name changed |          3/30 |                 4 |
 * | same code, all names changed|         22/30 |                32 |
 * | unrelated solutions         |         29/30 |                22 |
 *
 * So the loudest pair is the one with nothing to answer for, and the actual copy is the quietest —
 * the colour is inversely proportional to the finding.
 *
 * This once inferred the shared regions from CodeMirror's own chunks, which was the best available
 * when the score was a text score. It no longer is: the comparison is token-based and sends the
 * regions it actually matched, so the ink follows the number above it. The chunk-derived version was
 * removed rather than kept as a fallback — it disagreed hardest on exactly the pair that matters, a
 * copy with every identifier renamed, where the score is ~100 and a character diff aligns almost
 * nothing.
 *
 * One hue, low saturation, no underlines, because the code still has to be read.
 */

const commonLine = Decoration.line({ class: 'cm-commonLine' })

function highlighter(build: (view: EditorView) => DecorationSet) {
  return ViewPlugin.fromClass(
    class {
      decorations: DecorationSet
      constructor(view: EditorView) {
        this.decorations = build(view)
      }
      update(update: ViewUpdate) {
        // Only when the document changes, which here it never does. The bands are keyed on document
        // offsets, and layout cannot move those, so the set built in the constructor stays valid. The
        // old "lays its editors out a tick later" reason for rebuilding belonged to the chunk-derived
        // highlighting, which read MergeView's chunks; it outlived that code. It started to cost once
        // readOnlyEditor (EZ-1920) made these editors focusable: every arrow key, focus change and
        // drag-selection frame is an update, and each rebuilt and re-sorted the whole line set.
        if (update.docChanged) this.decorations = build(update.view)
      }
    },
    { decorations: (v) => v.decorations },
  )
}

/**
 * Turns off the library's red/green.
 *
 * `!important` because the merge package's own rules are more specific than anything an
 * `EditorView.theme` can emit: it qualifies with `&light`/`&dark` *and* the merge side, so a plain
 * `.cm-changedText` here loses the cascade and the underlines survive untouched. Those prefixes are
 * a `baseTheme` feature and throw "Unsupported selector" in a `theme`, which takes the whole page
 * down with it — hence the mode being passed in as a value instead.
 *
 * `:not(.cm-commonLine)` keeps this rule off the lines [sharedTheme] paints. Without it, an
 * `!important` here beat that plain declaration whatever the injection order, so every line the
 * merge view called *changed* lost its shared-code band — the exact inversion this feature exists
 * to remove, since on a copy with every identifier renamed a character diff calls all 24 lines
 * changed and the band survived only on the blank and boilerplate ones.
 */
const muteDiffColours = EditorView.theme({
  '&.cm-merge-a .cm-changedLine:not(.cm-commonLine), &.cm-merge-b .cm-changedLine:not(.cm-commonLine), .cm-inlineChangedLine:not(.cm-commonLine)':
    { backgroundColor: 'transparent !important' },
  '&.cm-merge-a .cm-changedText, &.cm-merge-b .cm-changedText, &.cm-merge-b .cm-deletedText, .cm-deletedChunk .cm-deletedText':
    { background: 'none !important' },
})

/**
 * A hairline rather than a saturated block. The gutter keeps saying which side a change belongs to
 * — that is genuinely useful when scrolling — but at 2px it reads as a margin rule, not an alarm.
 */
function gutterRule(dark: boolean) {
  const a = dark ? '#d98b6a' : '#c2603f'
  const b = dark ? '#6fbc8c' : '#3f8f5f'
  return EditorView.theme({
    '&.cm-merge-a .cm-changedLineGutter, &.cm-merge-a .cm-deletedLineGutter': {
      background: `linear-gradient(to right, ${a} 0 2px, transparent 2px) !important`,
    },
    '&.cm-merge-b .cm-changedLineGutter': {
      background: `linear-gradient(to right, ${b} 0 2px, transparent 2px) !important`,
    },
  })
}

/**
 * Blue, deliberately: green and red are spoken for on this page — the scores and the pass/fail
 * language everywhere else in the product — and "shared" is neither good news nor bad news. It is
 * the thing to look at.
 */
export const SHARED_LIGHT = 'rgba(90, 150, 190, .13)'
export const SHARED_DARK = 'rgba(120, 180, 220, .16)'

function sharedTheme(dark: boolean) {
  const shared = dark ? SHARED_DARK : SHARED_LIGHT
  return EditorView.theme({
    // A full-width band per matched line. Sub-line marks went with the chunk-derived version: a
    // token match is a run of tokens, and the line is the smallest unit its start and end honestly
    // describe — painting part of a line would claim a precision the line ranges do not carry.
    //
    // Qualified by merge side and `!important` so it outranks the merge package's *own* base rule,
    // `.cm-merge-a .cm-changedLine` at (0,3,0), which a bare `.cm-commonLine` at (0,2,0) loses to.
    // Muting that rule is not enough on its own: a matched line that the character diff also calls
    // changed is styled by both, and whichever wins is what the teacher sees. `!important` against
    // a plain declaration is decided by neither order nor specificity, so this cannot drift.
    '&.cm-merge-a .cm-commonLine, &.cm-merge-b .cm-commonLine': {
      backgroundColor: shared + ' !important',
    },
  })
}

/** A region core reported as matched, as a 1-based inclusive line range. */
export interface MatchedLines {
  startLine: number
  endLine: number
}

/**
 * The 1-based lines a set of matched regions covers, clamped to a document of `docLines` lines and
 * sorted without duplicates.
 *
 * Ranges are clamped rather than trusted: they are computed from token indices on the server, core
 * sends 0 for a line it could not resolve, and a line outside the document makes `doc.line(n)` throw
 * inside the view plugin, which CodeMirror answers by switching the plugin off — so that side would
 * silently lose every band. Ranges can overlap, and a RangeSetBuilder needs strictly increasing
 * positions, hence the set. Exported so a unit test can reach it; being wrong here is invisible.
 */
export function matchedLineNumbers(ranges: MatchedLines[], docLines: number): number[] {
  const lines = new Set<number>()
  for (const range of ranges) {
    // An end core could not resolve is 0, which says nothing about where the range starts or stops -
    // clamped to line 1 instead, (0, 40) painted the file from its top. Only the ends it knows count.
    const known = [range.startLine, range.endLine].filter((line) => line >= 1)
    if (known.length === 0) continue
    const from = Math.min(...known)
    const to = Math.min(docLines, Math.max(...known))
    for (let line = from; line <= to; line++) lines.add(line)
  }
  return [...lines].sort((a, b) => a - b)
}

/**
 * Highlights the regions the *comparison* matched, rather than the ones a character diff aligned.
 *
 * Core sends the matched token runs as line ranges (`scores[].matches`), so the ink follows the
 * number above it. Ranges are clamped to the document rather than trusted: they are computed from
 * token indices on the server, and one stale highlight is a better failure than a thrown decoration.
 *
 * An empty list is a real answer — nothing matched — and renders as no highlight, which is what a
 * 0% pair should look like.
 */
export function tokenMatchHighlighting(dark: boolean, ranges: MatchedLines[]) {
  const build = (view: EditorView) => {
    const builder = new RangeSetBuilder<Decoration>()
    const doc = view.state.doc
    for (const line of matchedLineNumbers(ranges, doc.lines)) {
      builder.add(doc.line(line).from, doc.line(line).from, commonLine)
    }
    return builder.finish()
  }
  return [muteDiffColours, gutterRule(dark), sharedTheme(dark), highlighter(build)]
}
