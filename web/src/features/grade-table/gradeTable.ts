/**
 * The grade table's arithmetic, extracted from the page so it can be tested against values.
 *
 * Everything here is the kind of logic `doc/testing.md` argues is worth unit-testing: **being
 * wrong is invisible**. A dropped student, a comparator that is not a total order, a CSV cell that
 * eats a semicolon — none of them throws, and all of them end up in a real gradebook.
 *
 * (Also: exporting non-components from a `.tsx` component file breaks Fast Refresh and is a lint
 * error here, so a separate module is the only shape this can take anyway.)
 */
import type {
  StudentExerciseStatus,
  SubmissionRow,
  TeacherCourseExercise,
} from '../../api/types.ts'

export type SortDir = 'asc' | 'desc'

export interface GradeCell {
  grade: number | null
  submissionNumber: number | null
  status: StudentExerciseStatus
  isAutograde: boolean | null
  courseExerciseId: string
}

export interface StudentRow {
  id: string
  givenName: string
  familyName: string
  finishedCount: number
  grades: GradeCell[]
}

export interface GradeTableModel {
  students: StudentRow[]
  sortedExercises: TeacherCourseExercise[]
  exerciseFinishedCounts: number[]
}

/** A cell for a student the exercise has no row for at all. Absent is not the same as ungraded. */
function absentCell(courseExerciseId: string): GradeCell {
  return {
    grade: null,
    submissionNumber: null,
    status: 'UNSTARTED',
    isAutograde: null,
    courseExerciseId,
  }
}

function cellFor(row: SubmissionRow, courseExerciseId: string): GradeCell {
  return {
    grade: row.submission?.grade?.grade ?? null,
    submissionNumber: row.submission?.submission_number ?? null,
    status: row.status,
    isAutograde: row.submission?.grade?.is_autograde ?? null,
    courseExerciseId,
  }
}

/**
 * One row per student, one cell per exercise, in `ordering_idx` order.
 *
 * **The roster is the union across every exercise, not the first one's** (EZ-1767). It used to be
 * `sorted[0].latest_submissions`, with each later exercise's row fetched by
 * `find(...)!` — an assertion of a cross-product the data does not guarantee. That failed in both
 * directions at once: a student present in exercise 2 but not exercise 1 was **silently missing
 * from the table**, and a student present in exercise 1 but not in a later one **crashed the whole
 * page** when `undefined.submission` was dereferenced.
 *
 * The rosters normally match, because core builds each exercise's list from course enrolment. They
 * diverge when enrolment changes between exercises being added, and under group filtering — which
 * is to say, on exactly the courses a teacher is most likely to be looking at.
 *
 * First-seen order is preserved so that the union is deterministic; the caller sorts afterwards.
 */
export function buildRows(exercises: TeacherCourseExercise[] | undefined): GradeTableModel {
  if (!exercises) return { students: [], sortedExercises: [], exerciseFinishedCounts: [] }

  const sorted = [...exercises].sort((a, b) => a.ordering_idx - b.ordering_idx)

  // Union of students, keyed by id, in the order they are first met.
  const roster = new Map<string, SubmissionRow>()
  for (const ex of sorted) {
    for (const sub of ex.latest_submissions) {
      if (!roster.has(sub.student_id)) roster.set(sub.student_id, sub)
    }
  }

  // One lookup table per exercise rather than a `find` per cell: the old code was O(students ×
  // exercises × students), which on a 300-student course with 20 exercises is nearly two million
  // comparisons on every sort.
  const byExercise = sorted.map((ex) => {
    const m = new Map<string, SubmissionRow>()
    for (const sub of ex.latest_submissions) m.set(sub.student_id, sub)
    return m
  })

  const students: StudentRow[] = [...roster.values()].map((sub) => {
    const grades = sorted.map((ex, i) => {
      const row = byExercise[i].get(sub.student_id)
      return row ? cellFor(row, ex.course_exercise_id) : absentCell(ex.course_exercise_id)
    })
    return {
      id: sub.student_id,
      givenName: sub.given_name,
      familyName: sub.family_name,
      finishedCount: grades.filter((g) => g.status === 'COMPLETED').length,
      grades,
    }
  })

  const exerciseFinishedCounts = sorted.map(
    (ex) => ex.latest_submissions.filter((s) => s.status === 'COMPLETED').length,
  )

  return { students, sortedExercises: sorted, exerciseFinishedCounts }
}

/** Family name then given name, always ascending — the tiebreaker every other order falls back to. */
function byName(a: StudentRow, b: StudentRow): number {
  const family = a.familyName.localeCompare(b.familyName)
  if (family !== 0) return family
  const given = a.givenName.localeCompare(b.givenName)
  if (given !== 0) return given
  // Two people can share a name. Without this the order between them is whatever the sort
  // implementation feels like, which means the table can reorder itself on a re-render.
  return a.id.localeCompare(b.id)
}

/**
 * The comparator for a given sort key and direction.
 *
 * `sortKey` is `'name'`, `'completion'`, or a `course_exercise_id`.
 *
 * **It must be a total order**, and that is the whole reason this is testable code rather than a
 * closure inside a `useMemo`. `Array.prototype.sort` is only required to behave sensibly if the
 * comparator is consistent; give it one that says `a < b` and `b < a` and the result is
 * unspecified — in practice, a table that shuffles when you re-render it. Every branch below
 * therefore ends at [byName], which ends at the student id.
 */
export function compareStudents(
  sortKey: string,
  sortDir: SortDir,
  sortedExercises: TeacherCourseExercise[],
): (a: StudentRow, b: StudentRow) => number {
  const dir = sortDir === 'asc' ? 1 : -1

  if (sortKey === 'completion') {
    return (a, b) => {
      const diff = a.finishedCount - b.finishedCount
      return diff !== 0 ? diff * dir : byName(a, b)
    }
  }

  const exIdx = sortedExercises.findIndex((ex) => ex.course_exercise_id === sortKey)
  if (exIdx >= 0) {
    return (a, b) => {
      // `?? null` rather than the bare value: an out-of-range index yields `undefined`, and
      // `undefined - undefined` is NaN, which a comparator reads as "equal" in one direction and
      // not the other. Collapsing both absences to null keeps the two-null branch reachable.
      const ga = a.grades[exIdx]?.grade ?? null
      const gb = b.grades[exIdx]?.grade ?? null
      if (ga === null && gb === null) return byName(a, b)
      // Ungraded sorts to one end and stays there, rather than interleaving with zeroes.
      if (ga === null) return -1 * dir
      if (gb === null) return 1 * dir
      const diff = ga - gb
      return diff !== 0 ? diff * dir : byName(a, b)
    }
  }

  // 'name', and anything unrecognised — a stale sort key from a deleted exercise lands here rather
  // than returning 0 for every pair, which would leave the rows in Map-insertion order.
  return (a, b) => byName(a, b) * dir
}

const CSV_SEPARATOR = ';'

/** Every field is quoted and its own quotes doubled — RFC 4180, and what a spreadsheet expects. */
function csvCell(value: string): string {
  return `"${value.replace(/"/g, '""')}"`
}

/**
 * The exported gradebook.
 *
 * Semicolon-separated because that is what a European spreadsheet locale expects from a `.csv`;
 * every field is quoted regardless, so a name containing a `;`, a `"` or a newline survives rather
 * than silently splitting one student across two columns.
 *
 * `labels` are the translated column headings, passed in so this stays free of i18n.
 */
export function toCsv(
  students: StudentRow[],
  exercises: TeacherCourseExercise[],
  showSubCount: boolean,
  labels: { name: string; submissionCount: string },
): string {
  const headers = [
    labels.name,
    ...exercises.flatMap((ex) =>
      showSubCount
        ? [ex.effective_title, `${labels.submissionCount} - ${ex.effective_title}`]
        : [ex.effective_title],
    ),
  ]

  const rows = students.map((student) =>
    [
      `${student.givenName} ${student.familyName}`,
      ...student.grades.flatMap((g) => {
        const grade = g.grade !== null ? String(g.grade) : ''
        const count = g.submissionNumber !== null ? String(g.submissionNumber) : ''
        return showSubCount ? [grade, count] : [grade]
      }),
    ]
      .map(csvCell)
      .join(CSV_SEPARATOR),
  )

  return [headers.map(csvCell).join(CSV_SEPARATOR), ...rows].join('\n')
}

/** The filename a download gets. `now` is a parameter so the caller owns the clock. */
export function csvFilename(courseId: string | undefined, now: number): string {
  return `grades-${courseId}-${now}.csv`
}
