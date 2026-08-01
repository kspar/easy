/**
 * Property checks for the markdown editing commands, run over a matrix of documents and
 * selections rather than as hand-picked examples.
 *
 * The example tests in `markdown-actions.mjs` pin down what each command *should* produce. These
 * check things that must hold for **every** input, which is where the bugs have actually been:
 * every one found so far — the caret left of a new bullet, the vanished focus, the marker-swallowing
 * selection — was an edge of the input space rather than a wrong main path.
 *
 *   npm run test:unit          # from web/, runs this and the example tests
 */
import { EditorState } from '@codemirror/state'
import {
  applyFormat,
  insertBlock,
  insertCodeBlock,
  insertImage,
  insertLink,
  insertRule,
  insertTable,
  setHeading,
  toggleLinePrefix,
  toggleOrderedList,
} from '../../src/components/markdown/markdownActions.ts'

function makeView(doc, anchor, head) {
  return {
    state: EditorState.create({ doc, selection: { anchor, head } }),
    dispatch(tr) { this.state = this.state.update(tr).state },
    focus() {},
  }
}

/** Documents chosen for their edges, not their realism. */
const DOCS = [
  '',                       // empty
  'hello',                  // one line, no trailing newline
  'hello\n',                // trailing newline — the last line is empty
  '\n',                     // nothing but a newline
  'one\ntwo\nthree',        // multi-line
  '- item',                 // already bulleted
  '- one\n- two',           // fully bulleted
  '- one\ntwo',             // partially bulleted, the mixed-state case
  '## Title',               // already a heading
  '# Title\n## Sub',        // mixed heading levels
  '1. a\n2. b',             // already numbered
  '5. a\n9. b',             // numbered, but not sequentially
  'para\n\nsecond',         // blank line in the middle
  '  indented',             // leading whitespace
  '> quoted',               // already quoted
]

/** Every cursor position, plus a spread of ranges, for a given document. */
function selectionsFor(doc) {
  const out = []
  for (let i = 0; i <= doc.length; i++) out.push([i, i])
  for (let a = 0; a <= doc.length; a += Math.max(1, Math.floor(doc.length / 4))) {
    for (const b of [a + 1, a + 3, doc.length]) {
      if (b > a && b <= doc.length) out.push([a, b])
    }
  }
  return out
}

const LINE_TOGGLES = {
  'bullet': (v) => toggleLinePrefix(v, '- '),
  'quote': (v) => toggleLinePrefix(v, '> '),
  'numbered': (v) => toggleOrderedList(v),
  'heading 2': (v) => setHeading(v, 2),
}

const ALL_ACTIONS = {
  ...LINE_TOGGLES,
  'bold': (v) => applyFormat(v, '**', '**', 'bold'),
  'italic': (v) => applyFormat(v, '_', '_', 'italic'),
  'code': (v) => applyFormat(v, '`', '`', 'code'),
  'link': (v) => insertLink(v, 'https://', 'text'),
  'image': (v) => insertImage(v, 'https://', 'alt'),
  'code block': (v) => insertCodeBlock(v),
  'code block (py)': (v) => insertCodeBlock(v, 'python'),
  'table': (v) => insertTable(v, 'H', 'C'),
  'rule': (v) => insertRule(v),
  'block': (v) => insertBlock(v, '---'),
}

let checked = 0
const failures = []
function fail(property, detail) {
  failures.push(`${property}: ${detail}`)
}
const q = (s) => JSON.stringify(s)

// --- 1. Nothing throws, on any input -----------------------------------------------------------
for (const [name, act] of Object.entries(ALL_ACTIONS)) {
  for (const doc of DOCS) {
    for (const [a, b] of selectionsFor(doc)) {
      const v = makeView(doc, a, b)
      checked++
      try {
        act(v)
      } catch (e) {
        fail('no action throws', `${name} on ${q(doc)} @${a},${b} — ${e.message}`)
      }
    }
  }
}

// --- 2. The selection always survives inside the document --------------------------------------
// A selection that runs off the end is how "the cursor disappeared" would look if the arithmetic
// were wrong by a character, and CodeMirror will happily throw on the next transaction.
for (const [name, act] of Object.entries(ALL_ACTIONS)) {
  for (const doc of DOCS) {
    for (const [a, b] of selectionsFor(doc)) {
      const v = makeView(doc, a, b)
      try { act(v) } catch { continue }
      const { from, to } = v.state.selection.main
      const len = v.state.doc.length
      checked++
      if (!(Number.isInteger(from) && Number.isInteger(to) && 0 <= from && from <= to && to <= len)) {
        fail('selection stays in bounds', `${name} on ${q(doc)} @${a},${b} -> [${from},${to}] of ${len}`)
      }
    }
  }
}

// --- 3. Line toggles settle after one application ----------------------------------------------
// Not f(f(x)) == x: numbering renumbers (`5. a` becomes `1. a`, not `5. a` again) and a heading
// replaces whatever level was there. What must hold is that the orbit closes immediately —
// applying three times lands exactly where applying once did, in both text and selection.
for (const [name, act] of Object.entries(LINE_TOGGLES)) {
  for (const doc of DOCS) {
    for (const [a, b] of selectionsFor(doc)) {
      const once = makeView(doc, a, b)
      const thrice = makeView(doc, a, b)
      try {
        act(once)
        act(thrice); act(thrice); act(thrice)
      } catch { continue }
      checked++
      if (once.state.doc.toString() !== thrice.state.doc.toString()) {
        fail(`${name} settles after one application`,
          `${q(doc)} @${a},${b} — once ${q(once.state.doc.toString())}, thrice ${q(thrice.state.doc.toString())}`)
      } else {
        const s1 = once.state.selection.main, s3 = thrice.state.selection.main
        if (s1.from !== s3.from || s1.to !== s3.to) {
          fail(`${name} settles its selection too`,
            `${q(doc)} @${a},${b} — once [${s1.from},${s1.to}], thrice [${s3.from},${s3.to}]`)
        }
      }
    }
  }
}

// --- 4. A multi-line selection ends up covering whole lines ------------------------------------
for (const [name, act] of Object.entries(LINE_TOGGLES)) {
  for (const doc of DOCS.filter((d) => d.includes('\n'))) {
    const v = makeView(doc, 0, doc.length)
    try { act(v) } catch { continue }
    const { from, to } = v.state.selection.main
    const first = v.state.doc.lineAt(from)
    const last = v.state.doc.lineAt(to)
    checked++
    if (from !== first.from || to !== last.to) {
      fail(`${name} keeps whole lines selected`,
        `${q(doc)} -> [${from},${to}], line bounds [${first.from},${last.to}]`)
    }
  }
}

// --- 5. Inline formats put the marker outside the selection ------------------------------------
for (const [name, before, after] of [['bold', '**', '**'], ['italic', '_', '_'], ['code', '`', '`']]) {
  for (const doc of DOCS) {
    for (const [a, b] of selectionsFor(doc)) {
      if (a === b) continue
      const original = doc.slice(a, b)
      const v = makeView(doc, a, b)
      try { applyFormat(v, before, after, 'x') } catch { continue }
      const { from, to } = v.state.selection.main
      checked++
      if (v.state.sliceDoc(from, to) !== original) {
        fail(`${name} leaves exactly the text selected`,
          `${q(doc)} @${a},${b} — wanted ${q(original)}, got ${q(v.state.sliceDoc(from, to))}`)
      }
    }
  }
}

// --- 5b. Inline formats toggle off ------------------------------------------------------------
// Applying twice to a selection must return the document exactly. This is the property the whole
// toggle rests on: wrapping leaves the text selected with the markers outside, and that is
// precisely the state unwrapping has to recognise.
for (const [name, before, after] of [['bold', '**', '**'], ['italic', '_', '_'], ['code', '`', '`'],
                                     ['strikethrough', '~~', '~~']]) {
  for (const doc of DOCS) {
    for (const [a, b] of selectionsFor(doc)) {
      if (a === b) continue
      const v = makeView(doc, a, b)
      try {
        applyFormat(v, before, after, 'x')
        applyFormat(v, before, after, 'x')
      } catch (e) {
        fail(`${name} toggles off`, `${q(doc)} @${a},${b} threw — ${e.message}`)
        continue
      }
      checked++
      if (v.state.doc.toString() !== doc) {
        fail(`${name} toggles off`, `${q(doc)} @${a},${b} -> ${q(v.state.doc.toString())}`)
      } else {
        const { from, to } = v.state.selection.main
        if (from !== a || to !== b) {
          fail(`${name} restores the selection too`, `${q(doc)} @${a},${b} -> [${from},${to}]`)
        }
      }
    }
  }
}

// --- 6. Composites ------------------------------------------------------------------------------
// Formatting on top of formatting is the case a user hits constantly and a test suite rarely does.
const COMPOSITES = [
  ['bold then italic', 'word', 0, 4, [(v) => applyFormat(v, '**', '**', 'b'), (v) => applyFormat(v, '_', '_', 'i')], '**_word_**'],
  ['italic then bold', 'word', 0, 4, [(v) => applyFormat(v, '_', '_', 'i'), (v) => applyFormat(v, '**', '**', 'b')], '_**word**_'],
  ['code then bold', 'word', 0, 4, [(v) => applyFormat(v, '`', '`', 'c'), (v) => applyFormat(v, '**', '**', 'b')], '`**word**`'],
  ['heading on a bulleted line', '- item', 0, 0, [(v) => setHeading(v, 2)], '## - item'],
  ['bullet on a heading line', '## Title', 0, 0, [(v) => toggleLinePrefix(v, '- ')], '- ## Title'],
  ['quote then bullet', 'text', 0, 0, [(v) => toggleLinePrefix(v, '> '), (v) => toggleLinePrefix(v, '- ')], '- > text'],
  ['bullet then numbered', 'a\nb', 0, 3, [(v) => toggleLinePrefix(v, '- '), (v) => toggleOrderedList(v)], '1. - a\n2. - b'],
  ['heading twice at different levels', 'T', 0, 0, [(v) => setHeading(v, 1), (v) => setHeading(v, 3)], '### T'],
  ['rule after a table', '', 0, 0, [(v) => insertTable(v, 'H', 'C'), (v) => insertRule(v)],
   '| H 1 | H 2 |\n| --- | --- |\n| C | C |\n| C | C |\n\n---'],
]
for (const [name, doc, a, b, steps, expected] of COMPOSITES) {
  const v = makeView(doc, a, b)
  checked++
  try {
    for (const step of steps) step(v)
    if (v.state.doc.toString() !== expected) {
      fail('composite', `${name} — wanted ${q(expected)}, got ${q(v.state.doc.toString())}`)
    }
  } catch (e) {
    fail('composite', `${name} threw — ${e.message}`)
  }
}

// --- 7. Block constructs always start their own line -------------------------------------------
// A table welded onto the end of a paragraph renders as literal pipes, which is the kind of thing
// that only shows up once someone writes a real exercise.
for (const [name, act] of Object.entries({
  'table': (v) => insertTable(v, 'H', 'C'),
  'rule': (v) => insertRule(v),
  'code block': (v) => insertCodeBlock(v),
})) {
  for (const doc of DOCS) {
    for (const [a, b] of selectionsFor(doc)) {
      if (a !== b) continue // the selection cases fence the selection instead
      const v = makeView(doc, a, b)
      const lengthBefore = v.state.doc.length
      try { act(v) } catch { continue }
      const inserted = v.state.doc.length - lengthBefore
      if (inserted <= 0) continue
      // Find where the block landed and confirm nothing shares its opening line.
      const text = v.state.doc.toString()
      const marker = name === 'table' ? '| H 1 |' : name === 'rule' ? '---' : '```'
      const at = text.indexOf(marker)
      checked++
      if (at > 0) {
        const line = v.state.doc.lineAt(at)
        if (line.from !== at) {
          fail(`${name} starts its own line`, `${q(doc)} @${a} -> ${q(text)}`)
        }
      }
    }
  }
}

if (failures.length) {
  console.log(`\n  ${failures.length} property failure(s) out of ${checked} checks:\n`)
  const shown = failures.slice(0, 25)
  for (const f of shown) console.log(`  ✗ ${f}`)
  if (failures.length > shown.length) console.log(`  … and ${failures.length - shown.length} more`)
  process.exit(1)
}
console.log(`\n  ${checked} property checks passed across ${DOCS.length} documents`)
