/**
 * Unit coverage for the markdown editing commands in `src/components/markdown/markdownActions.ts`.
 *
 * No DOM: the commands only touch `view.state`, `view.dispatch` and `view.focus`, so a stub view
 * over a real `EditorState` exercises all of the offset arithmetic — which is where these go wrong.
 * Vitest resolves the TypeScript source directly, so there is no build step between this file and
 * the module it is testing.
 *
 *   npm run test:unit          # from web/
 *
 * The browser suite covers the other half: that the toolbar buttons are wired to these at all.
 */
import { expect, test } from 'vitest'
import { EditorState } from '@codemirror/state'
import {
  applyFormat, toggleLinePrefix, toggleOrderedList, setHeading,
  insertBlock, insertCodeBlock, insertLink, insertImage, insertTable,
  uploadPlaceholder, findPlaceholder, markdownForUpload,
} from '../../src/components/markdown/markdownActions.ts'

test('markdown-actions', () => {
  function view(doc, anchor = 0, head = anchor) {
    return {
      state: EditorState.create({ doc, selection: { anchor, head } }),
      dispatch(tr) { this.state = this.state.update(tr).state },
      focus() {},
    }
  }
  const sel = (v) => v.state.sliceDoc(v.state.selection.main.from, v.state.selection.main.to)

  let pass = 0, fail = 0
  function check(name, actual, expected) {
    if (actual === expected) { pass++; return }
    fail++
    console.log(`  FAIL ${name}\n    expected ${JSON.stringify(expected)}\n    actual   ${JSON.stringify(actual)}`)
  }

  // --- applyFormat ---
  let v = view('hello world', 0, 5)
  applyFormat(v, '**', '**', 'bold')
  check('bold wraps selection', v.state.doc.toString(), '**hello** world')
  // The markers stay OUTSIDE the selection — what GitHub/Obsidian/StackEdit do, and what lets a
  // second format nest correctly instead of wrapping the first one's markers.
  check('bold leaves the text selected, not the markers', sel(v), 'hello')

  v = view('')
  applyFormat(v, '**', '**', 'bold')
  check('bold inserts placeholder', v.state.doc.toString(), '**bold**')
  check('placeholder is selected for overtyping', sel(v), 'bold')

  // Composing is the practical reason the markers are excluded.
  v = view('word', 0, 4)
  applyFormat(v, '**', '**', 'b')
  applyFormat(v, '_', '_', 'i')
  check('bold then italic nests', v.state.doc.toString(), '**_word_**')
  check('and the text is still what is selected', sel(v), 'word')

  // --- applyFormat toggles off ---
  // The state after wrapping is exactly the state unwrapping recognises, so the same button undoes
  // itself — markers outside the selection is what makes that work.
  v = view('hello world', 0, 5)
  applyFormat(v, '**', '**', 'bold')
  applyFormat(v, '**', '**', 'bold')
  check('bold twice returns the original', v.state.doc.toString(), 'hello world')
  check('and the original selection', sel(v), 'hello')

  // Markers dragged into the selection by hand.
  v = view('**hello** world', 0, 9)
  applyFormat(v, '**', '**', 'bold')
  check('unwraps when the markers are inside the selection', v.state.doc.toString(), 'hello world')
  check('leaving the text selected', sel(v), 'hello')

  // A different marker must not be mistaken for this one.
  v = view('**word**', 2, 6)
  applyFormat(v, '_', '_', 'i')
  check('italic inside bold nests rather than unwrapping', v.state.doc.toString(), '**_word_**')

  v = view('~~gone~~', 2, 6)
  applyFormat(v, '~~', '~~', 's')
  check('strikethrough toggles off too', v.state.doc.toString(), 'gone')

  v = view('a`x`b', 2, 3)
  applyFormat(v, '`', '`', 'c')
  check('inline code toggles off mid-document', v.state.doc.toString(), 'axb')

  // Bold sitting inside other bold text is still just an unwrap of the inner pair.
  v = view('lead **word** tail', 7, 11)
  applyFormat(v, '**', '**', 'b')
  check('unwrap works away from the document edges', v.state.doc.toString(), 'lead word tail')
  check('selection follows the unwrapped text', sel(v), 'word')

  // --- toggleLinePrefix ---
  v = view('one\ntwo\nthree', 0, 11)
  toggleLinePrefix(v, '- ')
  check('bullets all selected lines', v.state.doc.toString(), '- one\n- two\n- three')
  v = view('- one\n- two', 0, 11)
  toggleLinePrefix(v, '- ')
  check('bullets toggle off', v.state.doc.toString(), 'one\ntwo')
  v = view('- one\ntwo', 0, 9)
  toggleLinePrefix(v, '- ')
  check('partial bullets are completed, not stripped', v.state.doc.toString(), '- - one\n- two')

  // A prefix is inserted exactly where the cursor sits when you are at the start of a line, so
  // without a forward-associated mapping the caret is left to the LEFT of the new marker.
  v = view('hello', 0, 0)
  toggleLinePrefix(v, '- ')
  check('cursor at line start moves with the text', v.state.selection.main.head, 2)
  v = view('hello', 3, 3)
  toggleLinePrefix(v, '- ')
  check('cursor mid-word keeps its offset', v.state.selection.main.head, 5)
  v = view('- hello', 4, 4)
  toggleLinePrefix(v, '- ')
  check('un-bulleting keeps the cursor on the word', v.state.selection.main.head, 2)

  // --- toggleOrderedList ---
  v = view('a\nb\nc', 0, 5)
  toggleOrderedList(v)
  check('numbers sequentially', v.state.doc.toString(), '1. a\n2. b\n3. c')
  v = view('1. a\n2. b', 0, 9)
  toggleOrderedList(v)
  check('numbering toggles off', v.state.doc.toString(), 'a\nb')
  v = view('5. a\n9. b', 0, 9)
  toggleOrderedList(v)
  check('renumbers from any start', v.state.doc.toString(), 'a\nb')

  v = view('hello', 0, 0)
  toggleOrderedList(v)
  check('numbered list moves the cursor past the marker', v.state.selection.main.head, 3)

  // --- setHeading ---
  v = view('Title')
  setHeading(v, 2)
  check('h2 applied', v.state.doc.toString(), '## Title')
  setHeading(v, 2)
  check('same level toggles off', v.state.doc.toString(), 'Title')
  v = view('# Title')
  setHeading(v, 3)
  check('replaces existing heading level', v.state.doc.toString(), '### Title')

  v = view('Title', 0, 0)
  setHeading(v, 2)
  check('heading moves the cursor past the hashes', v.state.selection.main.head, 3)

  // --- insertBlock ---
  v = view('para', 4)
  insertBlock(v, '---')
  check('block gets a blank line after content', v.state.doc.toString(), 'para\n\n---')
  v = view('')
  insertBlock(v, '---')
  check('empty document needs no separator', v.state.doc.toString(), '---')
  // Cursor already on the blank line under a paragraph: one newline gets the separation right.
  v = view('para\n', 5)
  insertBlock(v, '---')
  check('blank line below content needs only one newline', v.state.doc.toString(), 'para\n\n---')

  // --- insertLink / insertImage ---
  v = view('click here', 0, 10)
  insertLink(v, 'https://', 'link text')
  check('link uses selection as text', v.state.doc.toString(), '[click here](https://)')
  check('link leaves url selected', sel(v), 'https://')
  v = view('')
  insertLink(v, 'https://', 'link text')
  check('link placeholder', v.state.doc.toString(), '[link text](https://)')
  check('empty-selection link still selects url', sel(v), 'https://')

  v = view('diagram', 0, 7)
  insertImage(v, 'https://', 'description')
  check('image uses selection as alt', v.state.doc.toString(), '![diagram](https://)')
  check('image leaves url selected', sel(v), 'https://')

  // --- insertCodeBlock ---
  v = view('x = 1', 0, 5)
  insertCodeBlock(v)
  check('fences the selection', v.state.doc.toString(), '```\nx = 1\n```')
  v = view('x = 1', 0, 5)
  insertCodeBlock(v, 'python')
  check('fence carries the language', v.state.doc.toString(), '```python\nx = 1\n```')
  v = view('para', 4)
  insertCodeBlock(v)
  check('empty fence below content', v.state.doc.toString(), 'para\n\n```\n\n```')
  check('cursor lands inside the fence', v.state.selection.main.head, 'para\n\n```\n'.length)

  // --- insertTable ---
  v = view('para', 4)
  insertTable(v, 'Heading', 'Cell')
  check('table is a full GFM block', v.state.doc.toString(),
    'para\n\n| Heading 1 | Heading 2 |\n| --- | --- |\n| Cell | Cell |\n| Cell | Cell |')
  check('first header cell selected', sel(v), 'Heading 1')

  // --- upload placeholders ---------------------------------------------------------------------
  // The placeholder is found by SEARCHING for it, not by remembering an offset, because a 20 MB
  // upload takes long enough that the author keeps typing — including above the insertion point,
  // which invalidates every offset recorded at insert time. These check that the search survives
  // exactly that.

  const ph = uploadPlaceholder('shot.png', 'abc12345')
  check('placeholder is not a valid image', ph.startsWith('!'), false)
  check('placeholder carries the nonce', ph.includes('abc12345'), true)

  let doc = `before\n${ph}\nafter`
  let found = findPlaceholder(doc, 'shot.png', 'abc12345')
  check('found at the right offset', doc.slice(found.from, found.to), ph)

  // The author types above it while the upload runs.
  doc = `a much longer first line now\n${ph}\nafter`
  found = findPlaceholder(doc, 'shot.png', 'abc12345')
  check('still found after edits above it', doc.slice(found.from, found.to), ph)

  // And deletes it, which is allowed and must not throw.
  check('gone is null, not an error', findPlaceholder('nothing here', 'shot.png', 'abc12345'), null)

  // Two uploads in flight must not find each other's placeholder.
  const ph2 = uploadPlaceholder('shot.png', 'def67890')
  doc = `${ph}\n${ph2}`
  check('nonces keep concurrent uploads apart',
    doc.slice(findPlaceholder(doc, 'shot.png', 'def67890').from), ph2)

  // --- markdownForUpload -------------------------------------------------------------------------
  check('an image is an image', markdownForUpload('/v2/resource/k/a.png', 'a.png', 'image/png'),
    '![a.png](/v2/resource/k/a.png)')
  // Decided by the sniffed type, not the extension — the extension is whatever the filesystem said.
  check('anything else is a link', markdownForUpload('/v2/resource/k/h.pdf', 'h.pdf', 'application/pdf'),
    '[h.pdf](/v2/resource/k/h.pdf)')
  check('a png named .txt is still an image',
    markdownForUpload('/v2/resource/k/x.txt', 'x.txt', 'image/png'), '![x.txt](/v2/resource/k/x.txt)')
  // Brackets would otherwise close the link early and leave the rest as literal text.
  check('brackets in the name are escaped',
    markdownForUpload('/v2/resource/k/a.png', 'a[1](2).png', 'image/png'),
    '![a\\[1\\]\\(2\\).png](/v2/resource/k/a.png)')

  console.log(`\n  ${pass} passed, ${fail} failed`)
  // One assertion for the whole file, because the file is one property: every command leaves the
  // document in the state its name promises. The individual failures are already printed above
  // with their names; this is what turns them into a red build.
  expect(fail, `${fail} of ${pass + fail} markdown command checks failed — see the ✗ lines above`).toBe(0)
  // And a floor on how many ran. Zero failures out of zero checks is indistinguishable from a
  // clean run, so an early `return` anywhere in the 200 lines above would be green without this.
  // 54 today; kept just under so adding a case is not a chore, close enough that a body which
  // stops part-way is caught.
  expect(pass + fail).toBeGreaterThanOrEqual(50)
})
