import { indentLess, indentMore } from '@codemirror/commands'
import { getIndentUnit, indentUnit } from '@codemirror/language'
import { countColumn, EditorSelection, type Extension } from '@codemirror/state'
import { keymap, type Command } from '@codemirror/view'

/**
 * Tab and the indent unit, shared by every editable editor (EZ-1904).
 *
 * CodeMirror 6 leaves Tab unbound on purpose — a focus trap is a WCAG failure — so out of the box
 * Tab in the solution editor moved focus to the submit button, and nobody could indent a Python
 * block without typing four spaces by hand. Binding it here is safe: CodeMirror ships its own way
 * out, Escape followed by Tab (or Ctrl-m, Shift-Alt-m on macOS, to toggle) hands Tab back to the
 * browser, and that stays bound regardless of what is done here.
 *
 * Four spaces everywhere, not per language. Python is the language this platform teaches and
 * PEP 8's answer is four; the same unit fits markdown (a nested list or indented code block is
 * four spaces), Java, C and SQL, and it makes the editors behave alike. The unit also feeds
 * `insertNewlineAndIndent`, so Enter after `def f():` now indents four rather than the library's
 * default two — that was the same bug wearing a different key.
 */
const INDENT_UNIT = '    '

/**
 * Tab with a cursor: pad to the next tab stop, the way an IDE does, so a Tab in the middle of a
 * line lands on a column rather than shifting the whole line. Tab with a selection: indent every
 * selected line by one unit. Not `indentWithTab` from @codemirror/commands, which does the latter
 * in both cases and so writes the spaces at the start of the line instead of at the cursor.
 */
export const insertSoftTab: Command = (view) => {
  const { state } = view
  if (state.readOnly) return false
  if (state.selection.ranges.some((r) => !r.empty)) return indentMore(view)
  const unit = getIndentUnit(state)
  view.dispatch(
    state.changeByRange((range) => {
      const line = state.doc.lineAt(range.from)
      const column = countColumn(line.text.slice(0, range.from - line.from), state.tabSize)
      const insert = ' '.repeat(unit - (column % unit))
      return {
        changes: { from: range.from, insert },
        range: EditorSelection.cursor(range.from + insert.length),
      }
    }),
    { scrollIntoView: true, userEvent: 'input' },
  )
  return true
}

/** Add to the extensions of any editor a person types code or markdown into. */
export const indentation: Extension = [
  indentUnit.of(INDENT_UNIT),
  keymap.of([{ key: 'Tab', run: insertSoftTab, shift: indentLess }]),
]
