import { ViewPlugin, Decoration, EditorView } from '@codemirror/view'
import type { DecorationSet, ViewUpdate } from '@codemirror/view'
import { RangeSetBuilder } from '@codemirror/state'
import { getChunks } from '@codemirror/merge'

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
 * the colour is inversely proportional to the finding. Worse, in the middle row the underlines land
 * on exactly the renamed identifiers, which are the part a teacher wants to look past to see that
 * the skeleton underneath is identical.
 *
 * Inverting fixes both. Shared code is the evidence, so shared code is what gets the ink: a copy
 * lights up, unrelated solutions stay clean, and a renamed copy shows its skeleton in colour with
 * the renamed names left plain. The page's own help text says the scores "cannot tell a shared
 * approach from a copy" — this is the part of the page that can.
 *
 * One hue, low saturation, no underlines, because the code still has to be read.
 */

const commonLine = Decoration.line({ class: 'cm-commonLine' })
const commonSpan = Decoration.mark({ class: 'cm-commonSpan' })

/**
 * The line's text, without its indent or any trailing whitespace — `[from, to]`, or null if the
 * line has no text at all.
 *
 * Only the *partial* marks are clamped to this. Inside a line that differs, the indent is not
 * evidence: it is shared by construction in Python, so marking it draws a column down the left of
 * both panes saying only "this code is indented", and it makes the marks read as blocks rather than
 * as the words they are meant to pick out.
 *
 * A line that is identical end to end is a different claim, and keeps its full-width band — see
 * [commonLines].
 */
function textRange(doc: EditorView['state']['doc'], line: { from: number; to: number }) {
  const text = doc.sliceString(line.from, line.to)
  const start = text.search(/\S/)
  if (start < 0) return null
  const end = text.replace(/\s+$/, '').length
  return [line.from + start, line.from + end] as const
}

/**
 * Lines no chunk touches — code both students wrote byte for byte.
 *
 * These get the full-width band, indent included, because the shape is itself the message: a solid
 * run down the pane says "these lines are identical end to end", which is a stronger and different
 * claim from the word-shaped marks that pick out fragments inside a line that differs. Reading the
 * two apart at a glance is most of the value.
 */
function commonLines(view: EditorView): DecorationSet {
  const builder = new RangeSetBuilder<Decoration>()
  const info = getChunks(view.state)
  if (!info) return builder.finish()
  const { chunks, side } = info
  const spans = chunks.map((c) =>
    side === 'b' ? ([c.fromB, c.endB] as const) : ([c.fromA, c.endA] as const),
  )
  const doc = view.state.doc
  for (let n = 1; n <= doc.lines; n++) {
    const line = doc.line(n)
    if (line.length === 0) continue
    if (spans.some(([f, t]) => line.from <= t && line.to >= f)) continue
    if (doc.sliceString(line.from, line.to).trim().length === 0) continue
    builder.add(line.from, line.from, commonLine)
  }
  return builder.finish()
}

/**
 * Inside a changed chunk, the runs that did *not* change — the skeleton left once the renamed
 * identifiers are taken out. This is the half that does the work on a disguised copy, where whole
 * identical lines are rare but identical structure is everywhere.
 */
function commonSpansIn(view: EditorView): DecorationSet {
  const builder = new RangeSetBuilder<Decoration>()
  const info = getChunks(view.state)
  if (!info) return builder.finish()
  const { chunks, side } = info
  const doc = view.state.doc
  const marks: [number, number][] = []

  for (const chunk of chunks) {
    const base = side === 'b' ? chunk.fromB : chunk.fromA
    const end = side === 'b' ? chunk.endB : chunk.endA
    let cursor = base
    for (const change of chunk.changes) {
      const from = base + (side === 'b' ? change.fromB : change.fromA)
      const to = base + (side === 'b' ? change.toB : change.toA)
      if (from > cursor) marks.push([cursor, Math.min(from, end)])
      cursor = Math.max(cursor, to)
    }
    if (cursor < end) marks.push([cursor, end])
  }

  // Split at line boundaries so a mark never straddles a line, then clamp each piece to the line's
  // text: a run that begins at the start of a line would otherwise carry the indent with it, and a
  // whitespace-only run says nothing at all.
  const perLine: [number, number][] = []
  for (const [from, to] of marks) {
    let pos = Math.max(0, from)
    const stop = Math.min(to, doc.length)
    while (pos < stop) {
      const line = doc.lineAt(pos)
      const segEnd = Math.min(stop, line.to)
      const text = textRange(doc, line)
      if (text) {
        const clampedFrom = Math.max(pos, text[0])
        const clampedTo = Math.min(segEnd, text[1])
        if (clampedTo > clampedFrom) perLine.push([clampedFrom, clampedTo])
      }
      pos = line.to + 1
    }
  }
  perLine.sort((x, y) => x[0] - y[0])
  for (const [from, to] of perLine) builder.add(from, to, commonSpan)
  return builder.finish()
}

function highlighter(build: (view: EditorView) => DecorationSet) {
  return ViewPlugin.fromClass(
    class {
      decorations: DecorationSet
      constructor(view: EditorView) {
        this.decorations = build(view)
      }
      update(update: ViewUpdate) {
        // Chunks arrive a tick after construction — `getChunks` returns null until the merge
        // extension has settled — so this recomputes rather than trying to guess when that was.
        this.decorations = build(update.view)
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
 */
const muteDiffColours = EditorView.theme({
  '&.cm-merge-a .cm-changedLine, &.cm-merge-b .cm-changedLine, .cm-inlineChangedLine': {
    backgroundColor: 'transparent !important',
  },
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
  return EditorView.theme({
    // A full-width band for an identical line; a rounded, text-hugging mark for a fragment inside a
    // line that differs. The band is the lighter of the two — it covers far more area, and the
    // fragment is the rarer, more telling signal.
    '.cm-commonLine': { backgroundColor: dark ? SHARED_DARK : SHARED_LIGHT },
    '.cm-commonSpan': {
      backgroundColor: dark ? 'rgba(120, 180, 220, .22)' : 'rgba(90, 150, 190, .18)',
      borderRadius: '2px',
    },
  })
}

/** Everything the similarity diff adds on top of a plain merge view. */
export function sharedCodeHighlighting(dark: boolean) {
  return [
    muteDiffColours,
    gutterRule(dark),
    sharedTheme(dark),
    highlighter(commonLines),
    highlighter(commonSpansIn),
  ]
}
