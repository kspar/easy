import { ViewPlugin, Decoration, EditorView } from '@codemirror/view'
import type { DecorationSet, ViewUpdate } from '@codemirror/view'
import { RangeSetBuilder } from '@codemirror/state'
import type { Text } from '@codemirror/state'
import { getChunks } from '@codemirror/merge'
import type { Chunk } from '@codemirror/merge'

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
 * Two ways of knowing what is shared, chosen by what the provider that scored the pair can say
 * (`match_ranges`, EZ-1667):
 *
 * - **Its own matches** ([tokenMatchHighlighting]). JPlag sends the regions it actually matched, so
 *   the ink follows the number above it. This is the better answer wherever there is one: on a copy
 *   with every identifier renamed the score is ~100 and a character diff aligns almost nothing.
 * - **The merge view's chunks** ([chunkHighlighting]), where the provider says how alike two texts are
 *   but not where — edit distance. A character diff is then the right reading, because it is what the
 *   score measured: a renamed copy scores low there too, and the highlight agrees with it.
 *
 * One hue, low saturation, no underlines, because the code still has to be read.
 */

const commonLine = Decoration.line({ class: 'cm-commonLine' })
const commonSpan = Decoration.mark({ class: 'cm-commonSpan' })

/**
 * Rebuilds only when [stale] says the decorations no longer describe the view. Not on every update:
 * once readOnlyEditor (EZ-1920) made these editors focusable, every arrow key, focus change and
 * drag-selection frame was an update, and rebuilding on each re-sorted the whole line set.
 */
function highlighter(build: (view: EditorView) => DecorationSet, stale: (update: ViewUpdate) => boolean) {
  return ViewPlugin.fromClass(
    class {
      decorations: DecorationSet
      constructor(view: EditorView) {
        this.decorations = build(view)
      }
      update(update: ViewUpdate) {
        if (stale(update)) this.decorations = build(update.view)
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
    // A full-width band per shared line: a matched line, or one no chunk touches.
    //
    // Qualified by merge side and `!important` so it outranks the merge package's *own* base rule,
    // `.cm-merge-a .cm-changedLine` at (0,3,0), which a bare `.cm-commonLine` at (0,2,0) loses to.
    // Muting that rule is not enough on its own: a matched line that the character diff also calls
    // changed is styled by both, and whichever wins is what the teacher sees. `!important` against
    // a plain declaration is decided by neither order nor specificity, so this cannot drift.
    '&.cm-merge-a .cm-commonLine, &.cm-merge-b .cm-commonLine': {
      backgroundColor: shared + ' !important',
    },
    // A rounded, text-hugging mark for a fragment inside a line that differs (chunks only). Darker
    // than the band: it covers far less area, and the fragment is the rarer, more telling signal.
    '&.cm-merge-a .cm-commonSpan, &.cm-merge-b .cm-commonSpan': {
      backgroundColor: dark ? 'rgba(120, 180, 220, .22)' : 'rgba(90, 150, 190, .18)',
      borderRadius: '2px',
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
 * number above it. An empty list is a real answer — nothing matched — and renders as no highlight,
 * which is what a 0% pair should look like.
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
  // The bands are keyed on document offsets, and layout cannot move those, so the set built in the
  // constructor stays valid until the document changes, which here it never does.
  return [muteDiffColours, gutterRule(dark), sharedTheme(dark), highlighter(build, (u) => u.docChanged)]
}

/**
 * One side of a merge chunk, in that side's document offsets: the region the chunk spans, and the
 * changed runs inside it, still relative to its start as the merge package keeps them.
 */
export interface SideChunk {
  from: number
  to: number
  changes: { from: number; to: number }[]
}

/** The chunks as one side of the merge view sees them. */
export function sideChunks(chunks: readonly Chunk[], side: 'a' | 'b'): SideChunk[] {
  return chunks.map((c) => ({
    from: side === 'a' ? c.fromA : c.fromB,
    to: side === 'a' ? c.endA : c.endB,
    changes: c.changes.map((ch) => (side === 'a' ? { from: ch.fromA, to: ch.toA } : { from: ch.fromB, to: ch.toB })),
  }))
}

/**
 * Lines no chunk touches — code both students wrote byte for byte — as 1-based line numbers. Blank and
 * whitespace-only lines are left out: they are shared by every pair, and say nothing.
 *
 * These get the full-width band, indent included, because the shape is itself the message: a solid
 * run down the pane says "these lines are identical end to end", which is a stronger and different
 * claim from the word-shaped marks that pick out fragments inside a line that differs.
 */
export function unchangedLineNumbers(doc: Text, chunks: SideChunk[]): number[] {
  const lines: number[] = []
  for (let n = 1; n <= doc.lines; n++) {
    const line = doc.line(n)
    if (line.text.trim().length === 0) continue
    // Overlap, not contact. Lines the other side added leave this side an empty chunk at the line
    // after them, and counting contact as change took that line's band though it is identical.
    if (chunks.some((c) => line.from < c.to && line.to > c.from)) continue
    lines.push(n)
  }
  return lines
}

/**
 * Inside the chunks, the runs that did *not* change — the skeleton left once the renamed identifiers
 * are taken out — as `[from, to)` offsets, sorted, one per line and never straddling one.
 *
 * Each run is clamped to its line's text. Inside a line that differs, the indent is not evidence: it
 * is shared by construction in Python, so marking it draws a column down both panes saying only "this
 * code is indented", and it makes the marks read as blocks rather than as the words they pick out. A
 * whitespace-only run says nothing at all and is dropped.
 */
export function unchangedSpans(doc: Text, chunks: SideChunk[]): [number, number][] {
  const runs: [number, number][] = []
  for (const chunk of chunks) {
    let cursor = chunk.from
    for (const change of chunk.changes) {
      const from = chunk.from + change.from
      if (from > cursor) runs.push([cursor, Math.min(from, chunk.to)])
      cursor = Math.max(cursor, chunk.from + change.to)
    }
    if (cursor < chunk.to) runs.push([cursor, chunk.to])
  }

  const spans: [number, number][] = []
  for (const [from, to] of runs) {
    let pos = Math.max(0, from)
    const stop = Math.min(to, doc.length)
    while (pos < stop) {
      const line = doc.lineAt(pos)
      const start = line.text.search(/\S/)
      if (start >= 0) {
        const textFrom = line.from + start
        const textTo = line.from + line.text.trimEnd().length
        const spanFrom = Math.max(pos, textFrom)
        const spanTo = Math.min(stop, line.to, textTo)
        if (spanTo > spanFrom) spans.push([spanFrom, spanTo])
      }
      pos = line.to + 1
    }
  }
  return spans.sort((x, y) => x[0] - y[0])
}

/**
 * Highlights what a character diff finds the two have in common, for a provider that says how alike
 * two texts are but not where. Identical lines get the band, and inside lines that differ, the runs
 * that did not change get the mark.
 */
export function chunkHighlighting(dark: boolean) {
  const build = (view: EditorView) => {
    const builder = new RangeSetBuilder<Decoration>()
    const info = getChunks(view.state)
    if (!info?.side) return builder.finish()
    const doc = view.state.doc
    const chunks = sideChunks(info.chunks, info.side)
    // Line and mark decorations share one set, which RangeSetBuilder wants in document order: a band
    // sits at its line's start, and a span on a banded line cannot exist - a banded line has no chunk.
    const decorations: [number, number, Decoration][] = [
      ...unchangedLineNumbers(doc, chunks).map((n): [number, number, Decoration] => [doc.line(n).from, doc.line(n).from, commonLine]),
      ...unchangedSpans(doc, chunks).map(([from, to]): [number, number, Decoration] => [from, to, commonSpan]),
    ]
    decorations.sort((x, y) => x[0] - y[0])
    for (const [from, to, decoration] of decorations) builder.add(from, to, decoration)
    return builder.finish()
  }
  // The merge view computes its chunks a tick after the editors exist - `getChunks` is null until then
  // - and hands them over in a transaction, so a change in them is the one update that needs a rebuild.
  const stale = (u: ViewUpdate) => u.docChanged || getChunks(u.state)?.chunks !== getChunks(u.startState)?.chunks
  return [muteDiffColours, gutterRule(dark), sharedTheme(dark), highlighter(build, stale)]
}
