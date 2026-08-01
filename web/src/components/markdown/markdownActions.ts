import type { EditorView } from '@codemirror/view'

/**
 * Markdown editing commands, shared by the exercise text editor and the inline feedback editor.
 *
 * These started as two private helpers in `AnnotatedCodeEditor.tsx`. They live here so the two
 * editors cannot drift into formatting the same document differently — the exercise editor offers
 * a superset of the buttons, but every button they share does the same thing.
 *
 * All of them are CodeMirror commands in spirit: they take a view, dispatch one transaction, and
 * return focus to the editor. One transaction matters — it keeps a formatting action as a single
 * step for undo.
 */

/** The lines the selection touches, in document order. */
function selectedLines(view: EditorView) {
  const { from, to } = view.state.selection.main
  const first = view.state.doc.lineAt(from).number
  const last = view.state.doc.lineAt(to).number
  const lines = []
  for (let n = first; n <= last; n++) lines.push(view.state.doc.line(n))
  return lines
}

/**
 * Toggle an inline format on the selection: wrap it, or unwrap it if it is already wrapped.
 *
 * The **text** ends up selected and the markers do not. That is what GitHub, Obsidian and
 * StackEdit all do, and it is what makes the toggle work at all — the selection left behind by
 * wrapping is exactly the selection that unwrapping recognises, so the same button reverses
 * itself. It also composes: with `word` selected, bold then italic gives `**_word_**`, where
 * selecting `**word**` and hitting italic would wrap the wrapper.
 *
 * Untoggling is selection-driven, so a bare cursor sitting inside bold text does not remove it —
 * it inserts a fresh empty pair, same as GitHub. Doing better needs the markdown syntax tree to
 * find the enclosing node; scanning the line for the nearest markers gets `**a** b **c**` wrong
 * in a way that silently mangles the text.
 */
export function applyFormat(view: EditorView, before: string, after: string, placeholder: string) {
  const { from, to } = view.state.selection.main
  const selected = view.state.sliceDoc(from, to)

  // Already formatted, with the markers *inside* the selection — someone selected `**word**`
  // by hand, or dragged across the whole thing.
  if (
    selected.length >= before.length + after.length &&
    selected.startsWith(before) &&
    selected.endsWith(after)
  ) {
    const inner = selected.slice(before.length, selected.length - after.length)
    view.dispatch({
      changes: { from, to, insert: inner },
      selection: { anchor: from, head: from + inner.length },
    })
    view.focus()
    return
  }

  // Already formatted, with the markers just *outside* the selection. This is the one that
  // matters: applying a format leaves the text selected and the markers outside it, so this is
  // the state the editor is in when you click the same button a second time.
  const outerFrom = from - before.length
  const outerTo = to + after.length
  if (
    outerFrom >= 0 &&
    outerTo <= view.state.doc.length &&
    view.state.sliceDoc(outerFrom, from) === before &&
    view.state.sliceDoc(to, outerTo) === after
  ) {
    view.dispatch({
      changes: { from: outerFrom, to: outerTo, insert: selected },
      selection: { anchor: outerFrom, head: outerFrom + selected.length },
    })
    view.focus()
    return
  }

  const body = selected || placeholder
  view.dispatch({
    changes: { from, to, insert: `${before}${body}${after}` },
    selection: { anchor: from + before.length, head: from + before.length + body.length },
  })
  view.focus()
}

/**
 * Apply line-level changes and put the selection somewhere that reads as "the thing I just acted
 * on". Which of two rules applies depends on what was selected:
 *
 * **A selection spanning several lines** ends up covering those lines in full, markers included.
 * You acted on whole lines, so whole lines are what stays selected — and toggling straight back
 * off works, which it does not if the first marker is left outside.
 *
 * **A cursor, or a selection inside one line**, keeps hold of its own text. The association is
 * the whole point here: a prefix is inserted at the very start of the line, which is exactly
 * where the cursor sits when you are at the start of one, and CodeMirror's default leaves a
 * position *before* an insertion made at it. The caret therefore stayed to the left of the new
 * `- `, which reads as it jumping to the beginning of the line. Associating forward moves it
 * with the text instead.
 */
function dispatchLineChanges(
  view: EditorView,
  lines: { number: number; from: number; to: number }[],
  specs: { from: number; to: number; insert: string }[],
) {
  const changes = view.state.changes(specs)

  if (lines.length > 1) {
    view.dispatch({
      changes,
      selection: {
        // -1 so the first line's start stays put rather than skipping over its new marker.
        anchor: changes.mapPos(lines[0].from, -1),
        head: changes.mapPos(lines[lines.length - 1].to, 1),
      },
    })
    view.focus()
    return
  }

  // Single line: hold the position relative to the line's *content*, rather than letting
  // CodeMirror map it.
  //
  // Mapping is right for an insertion but wrong for a replacement, and changing `# ` to `## ` is
  // a replacement — a position at the line start maps to the start of the replaced range, i.e.
  // back to the left of the marker. So re-levelling a heading put the caret at column 0 while
  // adding one to a plain line did not, which is the same jumping-caret bug in a case that only
  // shows up on lines that were already headings.
  const line = lines[0]
  const removed = specs[0].to - specs[0].from
  const added = specs[0].insert.length
  const hold = (pos: number) =>
    // Inside the old marker counts as offset zero: there is no content there to hold on to.
    line.from + added + Math.max(0, pos - line.from - removed)
  const { anchor, head } = view.state.selection.main
  view.dispatch({ changes, selection: { anchor: hold(anchor), head: hold(head) } })
  view.focus()
}

/**
 * Add `prefix` to every selected line, or strip it if every line already has it.
 *
 * Toggling rather than only adding: the original always inserted, so clicking "quote" twice gave
 * you `> > `, and un-bulleting a list meant editing each line by hand.
 */
export function toggleLinePrefix(view: EditorView, prefix: string) {
  const lines = selectedLines(view)
  const allPrefixed = lines.every((l) => l.text.startsWith(prefix))
  dispatchLineChanges(
    view,
    lines,
    lines.map((l) =>
      allPrefixed
        ? { from: l.from, to: l.from + prefix.length, insert: '' }
        : { from: l.from, to: l.from, insert: prefix },
    ),
  )
}

const ORDERED_ITEM = /^\d+\.[ \t]/

/**
 * Number the selected lines 1..n, or strip the numbering if they all already have it.
 *
 * Numbering sequentially rather than writing `1. ` on every line: both are valid Markdown and
 * render identically, but only one of them is readable in the source, which is what the author
 * is actually looking at.
 */
export function toggleOrderedList(view: EditorView) {
  const lines = selectedLines(view)
  const allNumbered = lines.every((l) => ORDERED_ITEM.test(l.text))
  dispatchLineChanges(
    view,
    lines,
    lines.map((l, i) => {
      const existing = l.text.match(ORDERED_ITEM)?.[0] ?? ''
      return {
        from: l.from,
        to: l.from + existing.length,
        insert: allNumbered ? '' : `${i + 1}. `,
      }
    }),
  )
}

const HEADING = /^#{1,6}[ \t]/

/**
 * Make the selected lines headings of `level`, replacing any heading they already are. Asking for
 * the level a line already is removes it, so the button reads as a toggle.
 */
export function setHeading(view: EditorView, level: 1 | 2 | 3) {
  const target = `${'#'.repeat(level)} `
  const lines = selectedLines(view)
  const allAtLevel = lines.every((l) => l.text.startsWith(target))
  dispatchLineChanges(
    view,
    lines,
    lines.map((l) => {
      const existing = l.text.match(HEADING)?.[0] ?? ''
      return { from: l.from, to: l.from + existing.length, insert: allAtLevel ? '' : target }
    }),
  )
}

/**
 * Insert a block construct on its own line below the cursor, with a blank line before it when the
 * current line has content. Markdown block constructs — fences, tables, rules — are only blocks
 * if they start a line, and a table welded onto the end of a paragraph silently renders as text.
 *
 * When `select` is given and occurs in the block, it is left selected so it can be typed over.
 */
export function insertBlock(view: EditorView, block: string, select?: string): number {
  // Insert after the current *block*, not the current line. Markdown separates blocks with blank
  // lines, so the run of non-blank lines around the cursor is one construct — and dropping a rule
  // in after line one of a table cuts the table in half. Walking to the end of the run also keeps
  // a multi-line paragraph intact, which is the same problem in a less visible form.
  const doc = view.state.doc
  let line = doc.lineAt(view.state.selection.main.to)
  while (line.number < doc.lines && line.text.trim() !== '' && doc.line(line.number + 1).text.trim() !== '') {
    line = doc.line(line.number + 1)
  }
  const at = line.to
  // An empty document needs no separator at all; a blank current line needs one newline to get
  // off it; a line with content needs two, so the block is not glued to the paragraph above.
  const separator = view.state.doc.length === 0 ? '' : line.text.trim() === '' ? '\n' : '\n\n'
  const blockStart = at + separator.length
  const offset = select ? block.indexOf(select) : -1
  view.dispatch({
    changes: { from: at, to: at, insert: `${separator}${block}` },
    selection:
      offset >= 0
        ? { anchor: blockStart + offset, head: blockStart + offset + select!.length }
        : { anchor: blockStart + block.length },
  })
  view.focus()
  return blockStart
}

/**
 * Fence the selection as a code block, or insert an empty fence with the cursor on the blank line
 * inside it. The language sits on the opening fence so the rendered HTML gets `language-*` and
 * the highlighter has something to work with.
 */
export function insertCodeBlock(view: EditorView, language = '') {
  const { from, to } = view.state.selection.main
  const selected = view.state.sliceDoc(from, to)
  if (selected) {
    const line = view.state.doc.lineAt(from)
    const lead = from === line.from ? '' : '\n'
    view.dispatch({
      changes: { from, to, insert: `${lead}\`\`\`${language}\n${selected}\n\`\`\`` },
    })
    view.focus()
    return
  }
  // There is nothing to select in an empty fence, so put the cursor on its blank middle line —
  // measured from where the block landed rather than counted backwards from the end, which was a
  // magic offset that any change to the fence would have quietly invalidated.
  const open = `\`\`\`${language}\n`
  const blockStart = insertBlock(view, `${open}\n\`\`\``)
  view.dispatch({ selection: { anchor: blockStart + open.length } })
  view.focus()
}

/** `[text](url)`, using the selection as the text and leaving the url selected. */
export function insertLink(view: EditorView, urlPlaceholder: string, textPlaceholder: string) {
  const { from, to } = view.state.selection.main
  const selected = view.state.sliceDoc(from, to)
  const text = selected || textPlaceholder
  const insert = `[${text}](${urlPlaceholder})`
  const urlAt = from + text.length + 3
  view.dispatch({
    changes: { from, to, insert },
    selection: { anchor: urlAt, head: urlAt + urlPlaceholder.length },
  })
  view.focus()
}

/** `![alt](url)`, with the url selected — the part you always have to fill in. */
export function insertImage(view: EditorView, urlPlaceholder: string, altPlaceholder: string) {
  const { from, to } = view.state.selection.main
  const selected = view.state.sliceDoc(from, to)
  const alt = selected || altPlaceholder
  const insert = `![${alt}](${urlPlaceholder})`
  const urlAt = from + alt.length + 4
  view.dispatch({
    changes: { from, to, insert },
    selection: { anchor: urlAt, head: urlAt + urlPlaceholder.length },
  })
  view.focus()
}

/**
 * A thematic break.
 *
 * An action rather than an inline dispatch in the toolbar, which is what it used to be — that
 * version never called `view.focus()`, so the caret was left on the button and appeared to vanish,
 * and it hard-coded its own newlines instead of using the separator logic every other block gets.
 */
export function insertRule(view: EditorView) {
  insertBlock(view, '---')
}

/** A 2x2 GFM table, header row included, with the first header cell selected. */
export function insertTable(view: EditorView, header: string, cell: string) {
  const block = [
    `| ${header} 1 | ${header} 2 |`,
    '| --- | --- |',
    `| ${cell} | ${cell} |`,
    `| ${cell} | ${cell} |`,
  ].join('\n')
  insertBlock(view, block, `${header} 1`)
}
