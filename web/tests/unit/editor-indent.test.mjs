/**
 * Unit coverage for the Tab command in `src/components/editorIndent.ts` (EZ-1904).
 *
 * Same shape as markdown-actions: no DOM, a stub view over a real `EditorState` carrying the
 * extension, because everything the command does is column arithmetic on `view.state`. The
 * browser suite (editor-tab-indent.spec.mjs) covers the half this cannot — that the key reaches
 * the command at all, and that Escape-then-Tab still leaves the editor.
 *
 *   npm run test:unit          # from web/
 */
import { expect, test } from 'vitest'
import { EditorSelection, EditorState } from '@codemirror/state'
import { indentation, insertSoftTab } from '../../src/components/editorIndent.ts'

test('editor-indent', () => {
  // `dispatch` is a closure, not a method: @codemirror/commands destructures `{ state, dispatch }`
  // off the view and calls it unbound.
  function view(doc, anchor = 0, head = anchor, extra = []) {
    const v = {
      state: EditorState.create({
        doc,
        selection: { anchor, head },
        extensions: [indentation, ...extra],
      }),
      dispatch: (tr) => { v.state = v.state.update(tr).state },
    }
    return v
  }
  const doc = (v) => v.state.doc.toString()
  const cursor = (v) => v.state.selection.main.head

  let pass = 0, fail = 0
  function check(name, actual, expected) {
    if (actual === expected) { pass++; return }
    fail++
    console.log(`  FAIL ${name}\n    expected ${JSON.stringify(expected)}\n    actual   ${JSON.stringify(actual)}`)
  }

  // --- a cursor: pad to the next tab stop, at the cursor ---
  let v = view('')
  check('empty doc: Tab handled', insertSoftTab(v), true)
  check('empty doc: four spaces', doc(v), '    ')
  check('empty doc: cursor after them', cursor(v), 4)

  v = view('x', 1)
  insertSoftTab(v)
  check('column 1: pads to column 4, not by four', doc(v), 'x   ')
  check('column 1: cursor at the stop', cursor(v), 4)

  v = view('abcd', 4)
  insertSoftTab(v)
  check('column 4: the whole next unit', doc(v), 'abcd    ')

  v = view('a = {\n"k": 1', 10) // after the colon: column 4 of line 2, offset 10 of the document
  insertSoftTab(v)
  check('mid-document: column counts from the line start', doc(v), 'a = {\n"k":     1')

  v = view('\tx', 2)
  insertSoftTab(v)
  check('an existing tab character counts as tabSize columns', doc(v), '\tx   ')

  // --- a selection: indent the selected lines by one unit ---
  v = view('a\nb\nc', 0, 3)
  insertSoftTab(v)
  check('selection: every touched line indented', doc(v), '    a\n    b\nc')
  check('selection: still selected afterwards', v.state.selection.main.empty, false)

  // --- several cursors, each padded relative to its own line ---
  // (basicSetup turns multiple selections on in the app; a bare state collapses them to one)
  v = view('ab\nabcd', 0, 0, [EditorState.allowMultipleSelections.of(true)])
  v.state = v.state.update({
    selection: EditorSelection.create([EditorSelection.cursor(2), EditorSelection.cursor(7)]),
  }).state
  insertSoftTab(v)
  check('multi-cursor: each lands on its own tab stop', doc(v), 'ab  \nabcd    ')

  // --- read-only editors are left alone, so the key falls through to the browser ---
  v = view('x', 1, 1, [EditorState.readOnly.of(true)])
  check('read-only: not handled', insertSoftTab(v), false)
  check('read-only: untouched', doc(v), 'x')

  console.log(`editor-indent: ${pass} passed, ${fail} failed`)
  expect(fail).toBe(0)
})
