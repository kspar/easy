/**
 * The three-way merge that decides what a save writes when someone else edited the same exercise
 * first.
 *
 * `ExercisePage.save()` runs this per field: local is what you typed, remote is what the server
 * has now, base is what you started editing from. Getting it wrong loses a colleague's work
 * without saying so, and the only visible symptom is a merge-conflict prompt that did or did not
 * appear — which is exactly the kind of thing worth pinning by example.
 */
import { mergeField } from '../../src/features/library/exerciseDraft.ts'

let pass = 0
const failures = []
function check(name, actual, expected) {
  const a = JSON.stringify(actual)
  const e = JSON.stringify(expected)
  if (a === e) { pass++; return }
  failures.push(`${name}\n    expected ${e}\n    actual   ${a}`)
}

// value, conflict
const merge = (local, remote, base) => mergeField(local, remote, base)

// --- nobody moved --------------------------------------------------------------------------
check('untouched by both keeps the value', merge('a', 'a', 'a'), ['a', false])

// --- one side moved ------------------------------------------------------------------------
check('only I moved: mine wins', merge('mine', 'base', 'base'), ['mine', false])
check('only they moved: theirs wins', merge('base', 'theirs', 'base'), ['theirs', false])

// --- both moved ----------------------------------------------------------------------------
check('both moved the same way is not a conflict', merge('same', 'same', 'base'), ['same', false])
check('genuinely divergent edits conflict, keeping mine', merge('mine', 'theirs', 'base'), ['mine', true])

// --- the empty-ish values a form actually produces -------------------------------------------
// A cleared field is a real edit, and '' must not be mistaken for "unset" the way a falsy check
// would have it.
check('clearing a field is an edit like any other', merge('', 'base', 'base'), ['', false])
check('them clearing it wins when I did not touch it', merge('base', '', 'base'), ['', false])
check('both clearing it agrees', merge('', '', 'base'), ['', false])
check('me clearing while they typed conflicts', merge('', 'theirs', 'base'), ['', true])

// --- null and undefined are values, not absences ------------------------------------------------
check('null from base to a value', merge('mine', null, null), ['mine', false])
check('value to null is an edit', merge(null, 'base', 'base'), [null, false])
check('both to null agrees', merge(null, null, 'base'), [null, false])

// --- structural fields: assets are arrays of objects ---------------------------------------------
// Compared by JSON, so deep equality holds and key order matters. Both facts are load-bearing:
// the assets list is rebuilt on every render, so reference equality would report every save as a
// conflict.
const asset = (n, c) => ({ file_name: n, file_content: c })
check(
  'identical asset lists rebuilt separately are equal',
  merge([asset('a.py', 'x')], [asset('a.py', 'x')], [asset('a.py', 'x')]),
  [[asset('a.py', 'x')], false],
)
check(
  'only I added an asset',
  merge([asset('a.py', 'x'), asset('b.py', 'y')], [asset('a.py', 'x')], [asset('a.py', 'x')]),
  [[asset('a.py', 'x'), asset('b.py', 'y')], false],
)
check(
  'we added different assets — conflict, mine kept',
  merge([asset('a.py', 'x'), asset('mine.py', '1')], [asset('a.py', 'x'), asset('theirs.py', '2')], [asset('a.py', 'x')]),
  [[asset('a.py', 'x'), asset('mine.py', '1')], true],
)
check('an emptied asset list is an edit', merge([], [asset('a.py', 'x')], [asset('a.py', 'x')]), [[], false])

// A known sharp edge, recorded rather than asserted as desirable: JSON comparison is order- and
// key-order-sensitive, so a field reordered but otherwise identical reads as a divergent edit.
check(
  'reordering counts as a change (JSON comparison, by design)',
  merge([asset('b.py', 'y'), asset('a.py', 'x')], [asset('a.py', 'x'), asset('b.py', 'y')], [asset('a.py', 'x'), asset('b.py', 'y')]),
  [[asset('b.py', 'y'), asset('a.py', 'x')], false],
)

// --- numbers, which the auto-assess fields are -----------------------------------------------
check('numeric field, only they changed it', merge(7, 30, 7), [30, false])
check('numeric field, divergent', merge(10, 30, 7), [10, true])
check('zero is a value, not an absence', merge(0, 7, 7), [0, false])

// --- the property that makes it safe ------------------------------------------------------------
// Whatever else it does, it must never silently discard *my* edit: when I moved and they moved
// differently, the result is mine and the caller is told.
const CASES = ['a', 'b', '', null, 0, 1, [], [{ x: 1 }]]
for (const local of CASES) {
  for (const remote of CASES) {
    for (const base of CASES) {
      const [value, conflict] = merge(local, remote, base)
      const localMoved = JSON.stringify(local) !== JSON.stringify(base)
      const remoteMoved = JSON.stringify(remote) !== JSON.stringify(base)
      const divergent = localMoved && remoteMoved && JSON.stringify(local) !== JSON.stringify(remote)
      if (divergent && (JSON.stringify(value) !== JSON.stringify(local) || !conflict)) {
        failures.push(
          `divergent edits must keep mine and report a conflict\n` +
          `    local=${JSON.stringify(local)} remote=${JSON.stringify(remote)} base=${JSON.stringify(base)}` +
          ` -> ${JSON.stringify([value, conflict])}`,
        )
      }
      if (localMoved && !remoteMoved && JSON.stringify(value) !== JSON.stringify(local)) {
        failures.push(
          `my edit must survive when theirs did not move\n` +
          `    local=${JSON.stringify(local)} remote=${JSON.stringify(remote)} base=${JSON.stringify(base)}` +
          ` -> ${JSON.stringify(value)}`,
        )
      }
      pass++
    }
  }
}

if (failures.length) {
  console.log(`\n  ${failures.length} failure(s):\n`)
  for (const f of failures.slice(0, 20)) console.log(`  ✗ ${f}`)
  process.exit(1)
}
console.log(`\n  ${pass} merge checks passed`)
