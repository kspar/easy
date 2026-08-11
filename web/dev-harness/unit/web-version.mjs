/**
 * The two pure pieces of the update check (EZ-1752): deciding that the deployed build differs from
 * this one, and reading a fetched body as a build stamp.
 *
 * Both failure directions cost something real. A false positive nags people to reload a page they
 * are working in, over and over, for a build that never changed. A false negative is the bug this
 * feature exists to fix, silently reinstated — and it is the one nobody notices, because "no banner"
 * is also what a correctly up-to-date tab looks like.
 */
import { isDifferentBuild, parseBuild } from '../../src/api/webVersion.ts'

let pass = 0
const failures = []
function check(name, actual, expected) {
  const a = JSON.stringify(actual)
  const e = JSON.stringify(expected)
  if (a === e) { pass++; return }
  failures.push(`${name}\n    expected ${e}\n    actual   ${a}`)
}

const build = (commit, version = '4.0') => ({ version, commit, builtAt: '2026-08-11T09:00:00.000Z' })

// --- the ordinary answers ---------------------------------------------------------------------
check('same commit is not an update', isDifferentBuild(build('abc1234'), build('abc1234')), false)
check('a different commit is', isDifferentBuild(build('abc1234'), build('def5678')), true)

// A rollback is a change the tab needs to hear about for the same reason a roll-forward is: the
// bundle it is running is no longer the one being served. There is no ordering in two hashes.
check(
  'a rollback counts as an update',
  isDifferentBuild(build('def5678', '4.1'), build('abc1234', '4.0')),
  true,
)

// Several deploys share a version — a fortnight off master is all 4.0 — so the version alone would
// notice none of them, and must not be what decides.
check(
  'same version, different build, still an update',
  isDifferentBuild(build('abc1234', '4.0'), build('def5678', '4.0')),
  true,
)

// --- when one side cannot say what it is --------------------------------------------------------
// A local build with no VERSION file, no GITHUB_SHA and no git says "unknown". Comparing against
// that could only produce noise on someone's laptop.
check('unknown deployed is never an update', isDifferentBuild(build('abc1234'), build('unknown')), false)
check('unknown running is never an update', isDifferentBuild(build('unknown'), build('abc1234')), false)
check('both unknown is not an update', isDifferentBuild(build('unknown'), build('unknown')), false)
check('nothing fetched is not an update', isDifferentBuild(build('abc1234'), null), false)
check('empty commit is not an update', isDifferentBuild(build('abc1234'), build('')), false)

// --- reading the body ----------------------------------------------------------------------------
check(
  'a well-formed stamp parses',
  parseBuild({ version: '4.0', commit: 'abc1234', builtAt: '2026-08-11T09:00:00.000Z' }),
  { version: '4.0', commit: 'abc1234', builtAt: '2026-08-11T09:00:00.000Z' },
)

// The SPA fallback answers a request for a missing file with index.html and a 200, so "not JSON"
// and "JSON that is not a build stamp" are both routine rather than exotic. Neither may be allowed
// to look like a deploy.
check('a string is not a stamp', parseBuild('<!doctype html>'), null)
check('null is not a stamp', parseBuild(null), null)
check('an array is not a stamp', parseBuild([]), null)
check('an object without a commit is not a stamp', parseBuild({ version: '4.0' }), null)
check('a non-string commit is not a stamp', parseBuild({ commit: 42 }), null)
check('an empty commit is not a stamp', parseBuild({ commit: '' }), null)

// A commit is the only field the comparison needs; the other two are for display and are allowed
// to be missing without throwing the stamp away.
check(
  'a commit alone is enough',
  parseBuild({ commit: 'abc1234' }),
  { version: 'unknown', commit: 'abc1234', builtAt: '' },
)

if (failures.length) {
  console.log(`\n  ${failures.length} failure(s):\n`)
  for (const f of failures) console.log(`  ✗ ${f}`)
  process.exit(1)
}
console.log(`\n  ${pass} web-version checks passed`)
