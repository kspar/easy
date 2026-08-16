/**
 * The grade table's arithmetic: who appears in it, in what order, and what an export contains.
 *
 * All three are things `doc/testing.md` calls out as worth unit-testing because **being wrong is
 * invisible**. A dropped student looks like a complete table. A comparator that is not a total
 * order looks like a table that occasionally reorders itself. A CSV cell that eats a separator
 * looks like a spreadsheet, until somebody sums a column.
 *
 * Two of the tests below (`buildRows` roster) fail against the code as it was before EZ-1767 —
 * written that way on purpose, per this repo's rule that a new test is made to fail once before it
 * is trusted.
 */
import { expect, test, describe } from 'vitest'
import {
  buildRows,
  compareStudents,
  csvFilename,
  toCsv,
} from '../../src/features/grade-table/gradeTable.ts'

/** A `SubmissionRow` as core sends it. `grade: null` means started-but-ungraded. */
const sub = (id, family, { grade = null, number = null, status = 'UNSTARTED', auto = true } = {}) => ({
  student_id: id,
  given_name: `Given${id}`,
  family_name: family,
  groups: [],
  status,
  submission:
    number === null && grade === null
      ? null
      : {
          submission_number: number ?? 1,
          grade: grade === null ? null : { grade, is_autograde: auto, is_graded_directly: !auto },
        },
})

const exercise = (id, idx, title, rows) => ({
  course_exercise_id: id,
  exercise_id: `e${id}`,
  effective_title: title,
  ordering_idx: idx,
  latest_submissions: rows,
})

describe('buildRows', () => {
  test('exercises come back in ordering_idx order regardless of arrival order', () => {
    const model = buildRows([
      exercise('2', 1, 'Second', []),
      exercise('1', 0, 'First', []),
    ])
    expect(model.sortedExercises.map((e) => e.course_exercise_id)).toEqual(['1', '2'])
  })

  test('a student gets one cell per exercise, carrying grade, count, status and autograde', () => {
    const model = buildRows([
      exercise('1', 0, 'First', [sub('s1', 'Aab', { grade: 80, number: 3, status: 'COMPLETED' })]),
      exercise('2', 1, 'Second', [sub('s1', 'Aab', { status: 'STARTED', number: 1 })]),
    ])
    expect(model.students).toHaveLength(1)
    expect(model.students[0].grades).toEqual([
      { grade: 80, submissionNumber: 3, status: 'COMPLETED', isAutograde: true, courseExerciseId: '1' },
      { grade: null, submissionNumber: 1, status: 'STARTED', isAutograde: null, courseExerciseId: '2' },
    ])
    expect(model.students[0].finishedCount).toBe(1)
  })

  /**
   * EZ-1767, direction one. The roster used to be `sorted[0].latest_submissions`, so a student who
   * joined after the first exercise was **silently absent from the whole table**.
   */
  test('a student missing from the first exercise still appears', () => {
    const model = buildRows([
      exercise('1', 0, 'First', [sub('s1', 'Aab', { grade: 50, status: 'COMPLETED' })]),
      exercise('2', 1, 'Second', [
        sub('s1', 'Aab', { grade: 60, status: 'COMPLETED' }),
        sub('s2', 'Bee', { grade: 70, status: 'COMPLETED' }),
      ]),
    ])
    expect(model.students.map((s) => s.id).sort()).toEqual(['s1', 's2'])
  })

  /**
   * EZ-1767, direction two — and the worse one. `find(...)!` returned `undefined` and the next
   * line read `.submission` off it, throwing a TypeError that took down the entire page rather
   * than one cell.
   */
  test('a student missing from a later exercise gets an empty cell rather than a crash', () => {
    const model = buildRows([
      exercise('1', 0, 'First', [
        sub('s1', 'Aab', { grade: 50, status: 'COMPLETED' }),
        sub('s2', 'Bee', { grade: 60, status: 'COMPLETED' }),
      ]),
      exercise('2', 1, 'Second', [sub('s1', 'Aab', { grade: 70, status: 'COMPLETED' })]),
    ])
    const s2 = model.students.find((s) => s.id === 's2')
    expect(s2.grades).toHaveLength(2)
    expect(s2.grades[1]).toEqual({
      grade: null,
      submissionNumber: null,
      // UNSTARTED, not UNGRADED: the student has no row at all, which is a different thing from
      // having submitted and not been graded, and the cell colour says so.
      status: 'UNSTARTED',
      isAutograde: null,
      courseExerciseId: '2',
    })
    expect(s2.finishedCount).toBe(1)
  })

  test('every student has exactly as many cells as there are exercises, in order', () => {
    const model = buildRows([
      exercise('1', 0, 'a', [sub('s1', 'Aab')]),
      exercise('2', 1, 'b', [sub('s2', 'Bee')]),
      exercise('3', 2, 'c', [sub('s3', 'Cee')]),
    ])
    for (const s of model.students) {
      expect(s.grades.map((g) => g.courseExerciseId)).toEqual(['1', '2', '3'])
    }
  })

  test('per-exercise completion counts are over that exercise, not the roster', () => {
    const model = buildRows([
      exercise('1', 0, 'a', [
        sub('s1', 'Aab', { status: 'COMPLETED' }),
        sub('s2', 'Bee', { status: 'STARTED' }),
      ]),
      exercise('2', 1, 'b', [sub('s1', 'Aab', { status: 'COMPLETED' })]),
    ])
    expect(model.exerciseFinishedCounts).toEqual([1, 1])
  })

  test('no exercises, and undefined, both give an empty model rather than throwing', () => {
    expect(buildRows([])).toEqual({ students: [], sortedExercises: [], exerciseFinishedCounts: [] })
    expect(buildRows(undefined).students).toEqual([])
  })
})

describe('compareStudents', () => {
  const rows = (...specs) =>
    buildRows([
      exercise('1', 0, 'First', specs.map(([id, family, grade, status]) =>
        sub(id, family, { grade, status: status ?? (grade === null ? 'UNGRADED' : 'COMPLETED') }))),
    ]).students

  const order = (students, key, dir, exercises = [{ course_exercise_id: '1' }]) =>
    [...students].sort(compareStudents(key, dir, exercises)).map((s) => s.id)

  test('by name, both directions', () => {
    const students = rows(['s1', 'Cee', 10], ['s2', 'Aab', 20], ['s3', 'Bee', 30])
    expect(order(students, 'name', 'asc')).toEqual(['s2', 's3', 's1'])
    expect(order(students, 'name', 'desc')).toEqual(['s1', 's3', 's2'])
  })

  test('by grade, with ungraded held at one end in each direction', () => {
    const students = rows(['s1', 'Aab', 50], ['s2', 'Bee', null], ['s3', 'Cee', 90])
    // Ascending: no grade first, then low to high.
    expect(order(students, '1', 'asc')).toEqual(['s2', 's1', 's3'])
    // Descending: high to low, and the ungraded one moves to the other end rather than staying put.
    expect(order(students, '1', 'desc')).toEqual(['s3', 's1', 's2'])
  })

  test('equal grades fall back to name, not to input order', () => {
    const students = rows(['s1', 'Zed', 70], ['s2', 'Aab', 70])
    expect(order(students, '1', 'asc')).toEqual(['s2', 's1'])
    expect(order(students, '1', 'desc')).toEqual(['s2', 's1'])
  })

  test('by completion count, ties on name', () => {
    const model = buildRows([
      exercise('1', 0, 'a', [
        sub('s1', 'Aab', { status: 'COMPLETED' }),
        sub('s2', 'Bee', { status: 'STARTED' }),
        sub('s3', 'Cee', { status: 'COMPLETED' }),
      ]),
    ])
    expect(order(model.students, 'completion', 'desc')).toEqual(['s1', 's3', 's2'])
    expect(order(model.students, 'completion', 'asc')).toEqual(['s2', 's1', 's3'])
  })

  /**
   * A stale sort key — the exercise it named was removed from the course while the page was open —
   * used to return 0 for every pair, leaving the table in whatever order the roster happened to
   * have. Falling back to name means the table is always in *some* explicable order.
   */
  test('an unknown sort key sorts by name rather than leaving the order to chance', () => {
    const students = rows(['s1', 'Cee', 10], ['s2', 'Aab', 20])
    expect(order(students, 'no-such-exercise', 'asc')).toEqual(['s2', 's1'])
  })

  /**
   * The property that matters, and the one the plan singled out: **the comparator must be a total
   * order.** `Array.prototype.sort` gives unspecified results otherwise — in practice a table that
   * reorders itself between renders, which reads as a data bug and is not one.
   *
   * Checked exhaustively over a deliberately nasty roster: duplicate names, duplicate grades,
   * missing grades, and a student absent from the exercise being sorted on.
   */
  test('is a total order for every key and direction', () => {
    const model = buildRows([
      exercise('1', 0, 'First', [
        sub('a', 'Same', { grade: 50, status: 'COMPLETED' }),
        sub('b', 'Same', { grade: 50, status: 'COMPLETED' }),
        sub('c', 'Other', { grade: null, status: 'UNGRADED' }),
        sub('d', 'Other', { grade: null, status: 'UNGRADED' }),
        sub('e', 'Zed', { grade: 100, status: 'COMPLETED' }),
      ]),
      // 'f' exists only here, so it has an absent cell for exercise 1 — the case that produced
      // `undefined - undefined` = NaN before the `?? null`.
      exercise('2', 1, 'Second', [sub('f', 'Aab', { grade: 10, status: 'COMPLETED' })]),
    ])
    const students = model.students
    expect(students).toHaveLength(6)

    let compared = 0
    for (const key of ['name', 'completion', '1', '2', 'gone']) {
      for (const dir of ['asc', 'desc']) {
        const cmp = compareStudents(key, dir, model.sortedExercises)
        for (const a of students) {
          for (const b of students) {
            compared++
            const ab = cmp(a, b)
            const ba = cmp(b, a)
            expect(Number.isNaN(ab), `${key}/${dir} ${a.id}v${b.id} is NaN`).toBe(false)
            // Antisymmetry: cmp(a,b) and cmp(b,a) must disagree in sign, and agree only on equal.
            // Compared as a boolean rather than with toBe, because `Math.sign(0)` is `0` while
            // `-Math.sign(0)` is `-0`, and toBe uses Object.is, which tells those apart. `===`
            // does not, which is the comparison actually meant here.
            expect(
              Math.sign(ab) === -Math.sign(ba),
              `${key}/${dir} ${a.id}v${b.id} not antisymmetric (${ab} vs ${ba})`,
            ).toBe(true)
            // `=== 0` rather than `toBe(0)` for the same Object.is reason: descending multiplies
            // by -1, so a student compared with itself returns `-0`. Sort treats that as zero;
            // only the assertion library cares.
            if (a.id === b.id) expect(ab === 0, `${key}/${dir} ${a.id} vs itself gave ${ab}`).toBe(true)
            // Only a student compared with itself may be "equal" — anything else means two rows
            // whose relative order the sort is free to change on a whim.
            if (a.id !== b.id) expect(ab, `${key}/${dir} ${a.id} ties with ${b.id}`).not.toBe(0)
          }
        }
        // Transitivity, over every ordered triple.
        for (const a of students) {
          for (const b of students) {
            for (const c of students) {
              compared++
              if (cmp(a, b) < 0 && cmp(b, c) < 0) {
                expect(cmp(a, c), `${key}/${dir} ${a.id}<${b.id}<${c.id} but not ${a.id}<${c.id}`).toBeLessThan(0)
              }
            }
          }
        }
      }
    }
    // The loops ran. Zero comparisons out of zero is indistinguishable from a clean pass.
    expect(compared).toBeGreaterThan(2000)
  })
})

describe('toCsv', () => {
  const labels = { name: 'Name', submissionCount: 'Submissions' }
  const model = buildRows([
    exercise('1', 0, 'Loops', [
      sub('s1', 'Aab', { grade: 80, number: 2, status: 'COMPLETED' }),
      sub('s2', 'Bee', { status: 'UNSTARTED' }),
    ]),
    exercise('2', 1, 'Recursion', [sub('s1', 'Aab', { grade: 0, number: 5, status: 'COMPLETED' })]),
  ])

  test('a header row and one row per student, semicolon separated', () => {
    const lines = toCsv(model.students, model.sortedExercises, false, labels).split('\n')
    expect(lines[0]).toBe('"Name";"Loops";"Recursion"')
    expect(lines).toHaveLength(3)
  })

  test('grade 0 is exported as 0, and a missing grade as empty', () => {
    // The distinction is the point: `0` and `` mean very different things in a gradebook, and a
    // falsy check would collapse them.
    const csv = toCsv(model.students, model.sortedExercises, false, labels)
    expect(csv).toContain('"Givens1 Aab";"80";"0"')
    expect(csv).toContain('"Givens2 Bee";"";""')
  })

  test('submission counts are a second column per exercise when asked for', () => {
    const lines = toCsv(model.students, model.sortedExercises, true, labels).split('\n')
    expect(lines[0]).toBe('"Name";"Loops";"Submissions - Loops";"Recursion";"Submissions - Recursion"')
    expect(lines[1]).toBe('"Givens1 Aab";"80";"2";"0";"5"')
  })

  /**
   * The reason every field is quoted rather than only the awkward ones. This file gets pasted into
   * a real gradebook; a name that splits one student across two columns is a grade attributed to
   * the wrong person.
   */
  test('a name containing the separator, a quote or a newline survives as one field', () => {
    const nasty = buildRows([
      exercise('1', 0, 'Ex;1', [
        { ...sub('s1', 'O";Brien', { grade: 5, status: 'COMPLETED' }), given_name: 'Line\nBreak' },
      ]),
    ])
    const lines = toCsv(nasty.students, nasty.sortedExercises, false, labels).split('\n')
    expect(lines[0]).toBe('"Name";"Ex;1"')
    // The embedded newline does split the *text* into two lines — that is legal CSV, and a parser
    // reading quoted fields rejoins them. What must not happen is an unescaped quote.
    expect(lines[1]).toBe('"Line')
    expect(lines[2]).toBe('Break O"";Brien";"5"')
  })

  test('no students gives a header and nothing else', () => {
    expect(toCsv([], model.sortedExercises, false, labels)).toBe('"Name";"Loops";"Recursion"')
  })
})

test('csvFilename carries the course and the moment, and takes the clock as an argument', () => {
  // A parameter rather than Date.now() inside: a function that reads the clock cannot be asserted
  // on, and the react-hooks lint rule here forbids reading it during render anyway.
  expect(csvFilename('9006', 1700000000000)).toBe('grades-9006-1700000000000.csv')
})
