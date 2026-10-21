/**
 * The pure decisions on the similarity page: which failures get their own sentence, which lines a
 * matched region covers, which emptiness to name, who was skipped for what, and what the compared
 * language means for the page.
 *
 * All are the shape `doc/testing.md` calls out as worth unit-testing because **being wrong is
 * invisible**. A refusal misclassified as an ordinary failure shows the teacher advice that cannot
 * work — the busy case is a global lock, so "try a single group" loops forever. A line range that is
 * off by one, or that throws on a range past the end of the document, paints the wrong evidence
 * under a score the teacher is about to act on.
 *
 * `isSimilarityRefusal` is checked against a code-less error first, which is what a 500 looks like
 * on the client: that case shipped once, when the refusal was raised outside the handler that maps
 * it, and no test could see it.
 */
import { expect, test, describe } from 'vitest'
import { isSimilarityRefusal } from '../../src/features/similarity/similarityError.ts'
import { emptyReason, hasUnreadableScores } from '../../src/features/similarity/similarityEmptyState.ts'
import { matchedLineNumbers, sideChunks, unchangedLineNumbers, unchangedSpans } from '../../src/features/similarity/similarityHighlight.ts'
import { comparedBy, pairMetrics, providerName, resultNotes, usesMatchRanges } from '../../src/features/similarity/similarityProvider.ts'
import { estimateView, formatDuration } from '../../src/features/similarity/similarityEstimate.ts'
import { Chunk } from '@codemirror/merge'
import { Text } from '@codemirror/state'
import { skippedByReason } from '../../src/features/similarity/similaritySkipped.ts'
import { highlightFileName, languageNote } from '../../src/features/similarity/similarityLanguage.ts'
import { ApiResponseError } from '../../src/api/client.ts'
import { errorMessage } from '../../src/api/errorMessage.ts'
import { groupAuthors, identicalOthers } from '../../src/features/similarity/similarityGroups.ts'

describe('identical groups', () => {
  test('students are counted, not submissions: one student\'s two attempts are one author', () => {
    // Explicit ids can bring a student's identical attempts into a group; as submissions, three people
    // read as four and each side's chip said one more than there were.
    const students = { 1: 'ann', 2: 'ann', 3: 'ben', 4: 'cid' }
    const studentOf = (id) => students[id]
    const group = { submissions: ['1', '2', '3', '4'] }
    expect(groupAuthors(group, studentOf)).toBe(3)
    const others = identicalOthers([group], studentOf)
    expect(others.get('1')).toBe(2)
    expect(others.get('3')).toBe(2)
    // A submission the page has no student for is an author of its own, as in core.
    expect(groupAuthors({ submissions: ['1', '8', '9'] }, studentOf)).toBe(3)
  })

  test('each member of a group counts the others in it, and a submission in none counts nothing', () => {
    // A pair side that is a group's member stands for the whole group; the chip says how many more.
    const others = identicalOthers([{ submissions: ['1', '2', '3'] }, { submissions: ['7', '8', '9', '10'] }])
    expect(others.get('2')).toBe(2)
    expect(others.get('10')).toBe(3)
    expect(others.has('4')).toBe(false)
  })

  test('a core older than groups sends none, and nothing is counted', () => {
    expect(identicalOthers(undefined).size).toBe(0)
  })
})

const apiError = (code, attrs = {}) =>
  new ApiResponseError(400, code === null ? null : { id: 'x', code, attrs, log_msg: 'failed' })

describe('isSimilarityRefusal', () => {
  test('the four named refusals are recognised', () => {
    expect(isSimilarityRefusal(apiError('TOO_MANY_SUBMISSIONS'))).toBe(true)
    expect(isSimilarityRefusal(apiError('SIMILARITY_BUSY'))).toBe(true)
    expect(isSimilarityRefusal(apiError('SIMILARITY_TIMEOUT'))).toBe(true)
    // A language switched off is not a failure to retry: the page says so rather than "try a group".
    expect(isSimilarityRefusal(apiError('SIMILARITY_UNAVAILABLE'))).toBe(true)
    // Nor is a cohort priced over the budget: its own sentence says how long, against how long.
    expect(isSimilarityRefusal(apiError('SIMILARITY_TOO_COSTLY'))).toBe(true)
  })

  test('too many submissions names the limit core sent, and says the generic thing only without one', () => {
    // A number the teacher can act on beats "too many"; the fallback is for a core that sent none.
    const t = (key, options) => `${key} ${JSON.stringify(options ?? {})}`
    expect(errorMessage(apiError('TOO_MANY_SUBMISSIONS', { limit: '500', requested: '731' }), t))
      .toBe('errors.tooManySubmissionsLimit {"value":"500"}')
    expect(errorMessage(apiError('TOO_MANY_SUBMISSIONS'), t)).toBe('errors.tooManySubmissions {}')
  })

  test('an unrelated API error is not, so the page keeps its timeout advice', () => {
    expect(isSimilarityRefusal(apiError('ENTITY_WITH_ID_NOT_FOUND'))).toBe(false)
  })

  test('a failure with no code at all is not — this is what a 500 looks like here', () => {
    expect(isSimilarityRefusal(apiError(null))).toBe(false)
  })

  test('a non-API failure is not', () => {
    expect(isSimilarityRefusal(new TypeError('fetch failed'))).toBe(false)
    expect(isSimilarityRefusal(undefined)).toBe(false)
  })
})

/**
 * The line arithmetic `tokenMatchHighlighting` paints from, imported rather than re-expressed: a copy
 * declared here tested the *rule* and could not fail whatever the function did. It is the part that
 * is easy to get wrong and impossible to see — an off-by-one paints a neighbouring line, and an
 * unclamped range throws inside the view plugin and switches that side's highlighting off.
 */
describe('matched line ranges', () => {
  test('a range is inclusive of both ends', () => {
    expect(matchedLineNumbers([{ startLine: 2, endLine: 5 }], 10)).toEqual([2, 3, 4, 5])
  })

  test('overlapping ranges collapse, because a builder needs strictly increasing positions', () => {
    expect(matchedLineNumbers([{ startLine: 1, endLine: 3 }, { startLine: 2, endLine: 4 }], 10))
      .toEqual([1, 2, 3, 4])
  })

  test('a range past the end of the document is clamped rather than throwing', () => {
    expect(matchedLineNumbers([{ startLine: 8, endLine: 99 }], 10)).toEqual([8, 9, 10])
    expect(matchedLineNumbers([{ startLine: 50, endLine: 99 }], 10)).toEqual([])
  })

  test('a zero range paints nothing, and a reversed one is read in the order it means', () => {
    // Core answers 0 for a token index it cannot map to a line, so 0..0 is a value the client really
    // receives — and it must paint nothing rather than line 1, which would put ink on a line the
    // comparison never claimed. (This assertion was wrong the first time it was written, and the
    // test is what said so.)
    expect(matchedLineNumbers([{ startLine: 0, endLine: 0 }], 10)).toEqual([])
    expect(matchedLineNumbers([{ startLine: 5, endLine: 2 }], 10)).toEqual([2, 3, 4, 5])
  })

  test('an end core could not resolve paints nothing of its own, not everything up to the other end', () => {
    // Clamped to line 1, a (0, 40) range banded the file from its top on a claim about one line.
    expect(matchedLineNumbers([{ startLine: 0, endLine: 5 }], 10)).toEqual([5])
    expect(matchedLineNumbers([{ startLine: 7, endLine: 0 }], 10)).toEqual([7])
  })

  test('no matches means no highlight, which is what a 0% pair should look like', () => {
    expect(matchedLineNumbers([], 10)).toEqual([])
  })
})

/**
 * Which emptiness the page names. Both branches render a plausible grey sentence, so being wrong
 * here is invisible — and it was wrong: a 91% pair hidden by the slider was reported as "no similar
 * pairs found", which tells a teacher to stop looking.
 */
describe('emptyReason', () => {
  test('nothing scored at all is not the threshold, whatever it is set to', () => {
    expect(emptyReason(0, 0)).toBe('noPairs')
  })

  test('something scored and nothing cleared the slider is exactly the threshold case', () => {
    // The bug: one pair at 91%, slider at 99. This returned 'noPairs'.
    expect(emptyReason(1, 0)).toBe('allFiltered')
    expect(emptyReason(25, 0)).toBe('allFiltered')
  })

  test('pairs above the threshold that cannot be rendered do not blame the threshold', () => {
    // A score naming a submission the response does not describe. The slider let it through, so
    // saying "no pair is above the threshold" would be false.
    expect(emptyReason(3, 2)).toBe('noPairs')
  })

  test('with groups listed, "no pairs" is not said, and the threshold still is when it hid every pair', () => {
    // The groups are the result, so "no similar pairs found" above them would read as nothing found.
    // But hidden along with it, a threshold that filtered out every pair left "0 pairs" unexplained.
    expect(emptyReason(0, 0, 2)).toBeNull()
    expect(emptyReason(3, 2, 1)).toBeNull()
    expect(emptyReason(25, 0, 1)).toBe('allFiltered')
  })
})

/**
 * Whether the scores can be read at all. A response from an older core, which a deploy keeps
 * answering for minutes, has score_a/score_b and no max_similarity; read as current it fails every
 * threshold and the page says no pair clears zero — "nothing similar" off a response with pairs in it.
 */
describe('hasUnreadableScores', () => {
  test('scores from an older core are unreadable, so no emptiness sentence is chosen for them', () => {
    expect(hasUnreadableScores([{ sub_1: '1', sub_2: '2', score_a: 91, score_b: 94 }])).toBe(true)
  })

  test('current scores are readable, and so is an empty list', () => {
    expect(hasUnreadableScores([{ sub_1: '1', sub_2: '2', similarity: 91, max_similarity: 94, matches: [] }])).toBe(false)
    expect(hasUnreadableScores([])).toBe(false)
  })
})

/**
 * Who was skipped, by name. Counts alone were enough while every skip was an accident; a student can
 * also drop out of the comparison on purpose, with code that still passes its tests, and then the
 * name is the finding. Being wrong here is invisible: a name under the wrong reason, or a reason that
 * lists nobody, reads as plausibly as the right answer.
 */
describe('skippedByReason', () => {
  const row = (submissionId, studentId, given, family) => ({
    submission: { id: submissionId }, status: 'UNGRADED', student_id: studentId,
    given_name: given, family_name: family, groups: [],
  })
  const rows = [
    row('11', 's-mari', 'Mari', 'Maasikas'), row('12', 's-jaan', 'Jaan', 'Tamm'),
    row('13', 's-anne', 'Anne', 'Aas'), row('14', 's-toomas', 'Toomas', 'Kask'),
    row('15', 's-kati', 'Kati', 'Karu'),
    { submission: null, status: 'NOT_STARTED', student_id: 's-none', given_name: 'No', family_name: 'Submission', groups: [] },
  ]

  test('each skipped submission is named under its reason, the suspicious reasons first', () => {
    const groups = skippedByReason([
      { id: '12', reason: 'TOO_SMALL' }, { id: '11', reason: 'TOO_REPETITIVE' },
      { id: '13', reason: 'CANNOT_PARSE' }, { id: '14', reason: 'TOO_LARGE' }, { id: '15', reason: 'TOO_COMPLEX' },
    ], rows)

    expect(groups.map((g) => g.reason)).toEqual(['CANNOT_PARSE', 'TOO_LARGE', 'TOO_COMPLEX', 'TOO_REPETITIVE', 'TOO_SMALL'])
    expect(groups.map((g) => g.students.map((s) => s.name))).toEqual(
      [['Anne Aas'], ['Toomas Kask'], ['Kati Karu'], ['Mari Maasikas'], ['Jaan Tamm']],
    )
    expect(groups[3].students[0]).toEqual({ submissionId: '11', studentId: 's-mari', name: 'Mari Maasikas' })
  })

  test('a reason the page does not know is shown under a generic heading, not dropped', () => {
    const groups = skippedByReason([{ id: '11', reason: 'EMPTY' }, { id: '12', reason: 'SOMETHING_NEW' }], rows)
    expect(groups).toEqual([{
      reason: 'OTHER',
      students: [
        { submissionId: '12', studentId: 's-jaan', name: 'Jaan Tamm' },
        { submissionId: '11', studentId: 's-mari', name: 'Mari Maasikas' },
      ],
    }])
  })

  test('names within a reason are alphabetical, and one the rows do not describe comes last, unnamed', () => {
    const groups = skippedByReason([
      { id: '99', reason: 'TOO_SMALL' }, { id: '14', reason: 'TOO_SMALL' }, { id: '13', reason: 'TOO_SMALL' },
    ], rows)
    expect(groups[0].students.map((s) => s.name)).toEqual(['Anne Aas', 'Toomas Kask', null])
    expect(groups[0].students[2].submissionId).toBe('99')
  })

  test('nothing skipped is no groups', () => {
    expect(skippedByReason([], rows)).toEqual([])
  })
})

// The language core compared in decides the grammar the diff is highlighted with and the note that
// says how to read the scores. Both are invisible when wrong: an unhighlighted diff still renders.
describe('the compared language', () => {
  test('each language core tokenises is highlighted with its own grammar', () => {
    expect(highlightFileName('python')).toBe('solution.py')
    expect(highlightFileName('java')).toBe('Solution.java')
    expect(highlightFileName('sql')).toBe('solution.sql')
    expect(highlightFileName('text')).toBe('solution.txt')
  })

  test('a language this bundle has not heard of is shown as plain text, not guessed at', () => {
    expect(highlightFileName('cobol')).toBe('solution.txt')
  })

  test('text gets the weaker-answer warning and SQL its reading note; code needs neither', () => {
    expect(languageNote('text')).toBe('text')
    expect(languageNote('sql')).toBe('sql')
    expect(languageNote('python')).toBeNull()
    expect(languageNote('java')).toBeNull()
  })

  test('a newer core comparing a language this bundle does not know is not told it compared text', () => {
    // It compared code. Warning that it did not would be the page contradicting core.
    expect(languageNote('cobol')).toBeNull()
  })
})

/**
 * What the page owes the teacher about the provider that measured a result (EZ-1667). Each wrong
 * answer here reads as a fact: a token-language note under an edit-distance result says renaming does
 * not lower a score that renaming lowers, and a pinned provider passed over in silence reads as the
 * course's own choice having run.
 */
describe('the provider', () => {
  test('a pinned provider passed over is said, whichever provider ran instead', () => {
    expect(resultNotes({ provider: 'edit-distance', provider_fallback: 'PINNED_UNAVAILABLE', language: 'python' }))
      .toEqual(['pinnedUnavailable'])
    // And JPlag's own note on what SQL means still follows it.
    expect(resultNotes({ provider: 'jplag', provider_fallback: 'PINNED_UNAVAILABLE', language: 'sql' }))
      .toEqual(['pinnedUnavailable', 'languageSql'])
  })

  test('a language no provider may compare is said once, as that, not also as the generic text note', () => {
    expect(resultNotes({ provider: 'jplag', provider_fallback: 'LANGUAGE_UNAVAILABLE', language: 'text' }))
      .toEqual(['languageUnavailable'])
  })

  test('the language notes are about tokens, so edit distance and providers the page does not know get none', () => {
    expect(resultNotes({ provider: 'jplag', provider_fallback: null, language: 'sql' })).toEqual(['languageSql'])
    expect(resultNotes({ provider: 'jplag', provider_fallback: null, language: 'text' })).toEqual(['languageText'])
    expect(resultNotes({ provider: 'edit-distance', provider_fallback: null, language: 'sql' })).toEqual([])
    expect(resultNotes({ provider: 'edit-distance', provider_fallback: null, language: 'text' })).toEqual([])
    expect(resultNotes({ provider: 'moss', provider_fallback: null, language: 'sql' })).toEqual([])
    // A core older than providers sends none, and its scores were JPlag's.
    expect(resultNotes({ language: 'sql' })).toEqual(['languageSql'])
  })

  test('only an explicit false sends the page to a character diff', () => {
    expect(usesMatchRanges({ match_ranges: false })).toBe(false)
    expect(usesMatchRanges({ match_ranges: true })).toBe(true)
    // An older core sent JPlag's matches and no flag.
    expect(usesMatchRanges({})).toBe(true)
  })

  test('a pair shows what its provider measured, and the two scores stand in when it sent nothing', () => {
    const metrics = [{ id: 'levenshtein', value: 64, unit: 'percent' }, { id: 'dice', value: 81, unit: 'percent' }]
    expect(pairMetrics({ similarity: 72, max_similarity: 81, metrics })).toBe(metrics)
    const legacy = [{ id: 'similarity', value: 72, unit: 'percent' }, { id: 'max_similarity', value: 81, unit: 'percent' }]
    expect(pairMetrics({ similarity: 72, max_similarity: 81 })).toEqual(legacy)
    expect(pairMetrics({ similarity: 72, max_similarity: 81, metrics: [] })).toEqual(legacy)
  })

  test('a provider the page has no words for is named by its id, not passed off as a known one', () => {
    const t = (key, options) => (options ? `${key} ${JSON.stringify(options)}` : key)
    expect(providerName('jplag', t)).toBe('similarity.providerJplag')
    expect(providerName('edit-distance', t)).toBe('similarity.providerEditDistance')
    expect(providerName('moss', t)).toBe('moss')
    expect(comparedBy('edit-distance', t)).toBe('similarity.comparedByEditDistance')
    expect(comparedBy('moss', t)).toBe('similarity.comparedByOther {"provider":"moss"}')
  })
})

/**
 * The shared code a character diff finds, for a provider that says how alike two texts are but not
 * where. Built from the merge package's own chunks, so a change in how it reports them shows up here
 * rather than as a pair painted wrong.
 */
describe('shared code from the diff', () => {
  const sides = (a, b) => {
    const docA = Text.of(a.split('\n'))
    const docB = Text.of(b.split('\n'))
    const chunks = Chunk.build(docA, docB)
    return {
      a: { doc: docA, chunks: sideChunks(chunks, 'a') },
      b: { doc: docB, chunks: sideChunks(chunks, 'b') },
    }
  }
  const spanTexts = ({ doc, chunks }) => unchangedSpans(doc, chunks).map(([from, to]) => doc.sliceString(from, to))

  test('identical lines are banded on both sides, and a blank line is not', () => {
    const { a, b } = sides('# sum\n\nx = 1\nprint(x)\n', '# sum\n\ny = 1\nprint(y)\n')
    expect(unchangedLineNumbers(a.doc, a.chunks)).toEqual([1])
    expect(unchangedLineNumbers(b.doc, b.chunks)).toEqual([1])
  })

  test('each side reads its own half of a chunk, and lines only the other side has do not cost it a band', () => {
    // Lopsided on purpose: with both sides the same shape, reading a's half as b's changed nothing.
    const { a, b } = sides('print(1)\nprint(2)\n', '# one\n# two\nprint(1)\nprint(2)\n')
    expect(unchangedLineNumbers(a.doc, a.chunks)).toEqual([1, 2])
    expect(unchangedLineNumbers(b.doc, b.chunks)).toEqual([3, 4])
  })

  test('inside renamed lines the skeleton is marked and the renamed names are not', () => {
    const { a, b } = sides('x = int(input())\nprint(x + 1)\n', 'n = int(input())\nprint(n + 1)\n')
    for (const side of [a, b]) {
      const texts = spanTexts(side)
      expect(texts.join('|')).toContain('= int(input())')
      expect(texts.join('|')).toContain(' + 1)')
      for (const text of texts) expect(text).not.toMatch(/\b[xn]\b/)
    }
  })

  test('a mark never starts in the indent, and never runs across a line', () => {
    const { a } = sides('def f():\n    return 1\n', 'def g():\n    return 2\n')
    const spans = unchangedSpans(a.doc, a.chunks)
    expect(spans.length).toBeGreaterThan(0)
    for (const [from, to] of spans) expect(to).toBeLessThanOrEqual(a.doc.lineAt(from).to)
    // The unchanged run on the second line begins at the line's start; the mark begins at `return`.
    const indented = a.doc.line(2)
    const onIt = spans.filter(([from]) => from >= indented.from && from <= indented.to)
    expect(onIt).toEqual([[indented.from + 4, indented.from + 'return '.length + 4]])
  })

  test('identical solutions are banded throughout, with nothing left to mark', () => {
    const { a } = sides('a = 1\nb = 2\n', 'a = 1\nb = 2\n')
    expect(unchangedLineNumbers(a.doc, a.chunks)).toEqual([1, 2])
    expect(unchangedSpans(a.doc, a.chunks)).toEqual([])
  })
})

/**
 * What the page says before Find, from core's estimate (EZ-1667). A misread here switches Find off for a
 * check that would run, or offers one core is about to refuse - and an older core's missing estimate
 * must read as "no estimate", never as "about NaN s".
 */
describe('the estimate', () => {
  const fits = { submissions: 30, pairs: 435, provider: 'edit-distance', language: 'python', mode: 'optimised', provider_fallback: null, estimated_sec: 12, budget_sec: 120, within_budget: true }

  test('within the budget it is a duration to show, and over it a refusal to say before the click', () => {
    expect(estimateView(fits)).toEqual({ kind: 'fits', submissions: 30, pairs: 435, seconds: 12 })
    expect(estimateView({ ...fits, estimated_sec: 540, within_budget: false }))
      .toEqual({ kind: 'tooCostly', submissions: 30, pairs: 435, seconds: 540, budget: 120 })
  })

  test('no estimate, or one the page cannot read, falls back to what the page said before estimates', () => {
    expect(estimateView(undefined)).toEqual({ kind: 'unknown' })
    // An unstubbed request, or a proxy's empty body.
    expect(estimateView({})).toEqual({ kind: 'unknown' })
    expect(estimateView({ ...fits, estimated_sec: 'soon' })).toEqual({ kind: 'unknown' })
    expect(estimateView({ ...fits, within_budget: undefined })).toEqual({ kind: 'unknown' })
  })

  test('seconds under a minute and a half, whole minutes rounded up from there, and nothing raised', () => {
    const t = (key, options) => `${key.replace('similarity.duration', '')}:${options.value}`
    // A budget of none is said as none: raised to one, it read "about 1 s, longer than the 1 s".
    expect(formatDuration(0, t)).toBe('Seconds:0')
    expect(formatDuration(0.2, t)).toBe('Seconds:1')
    expect(formatDuration(12, t)).toBe('Seconds:12')
    expect(formatDuration(89, t)).toBe('Seconds:89')
    expect(formatDuration(90, t)).toBe('Minutes:2')
    expect(formatDuration(121, t)).toBe('Minutes:3')
  })

  test('the refusal says how long against how long, in minutes, and the bare sentence without them', () => {
    const t = (key, options) => `${key} ${JSON.stringify(options ?? {})}`
    expect(errorMessage(apiError('SIMILARITY_TOO_COSTLY', { estimated_sec: '540', budget_sec: '120' }), t))
      .toBe('errors.similarityTooCostlyMinutes {"estimated":9,"budget":2}')
    expect(errorMessage(apiError('SIMILARITY_TOO_COSTLY'), t)).toBe('errors.similarityTooCostly {}')
  })
})
