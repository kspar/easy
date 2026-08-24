/**
 * Unit J6 — F-035's user-visible consequence, driven end to end.
 *
 * The keys, at this sha:
 *   grade table   ['teacher','courses',c,'exercises',{groupId}]      (useTeacherCourseExercises)
 *   roster        ['teacher','courses',c,'exercises',ce,'submissions','latest',{groupId}]
 *   usePostGrade invalidates ['teacher','courses',c,'exercises',ce]
 *
 * The roster's key starts with the invalidated prefix; the grade table's does not — `ce` is a string
 * and `{groupId}` is an object, so react-query's prefix match fails at position 4. With
 * `staleTime: 30_000`, the sequence a teacher actually performs — check the table, go grade someone,
 * come back — serves the *cached* table on return, no refetch, stale grade.
 *
 * The teacher's sequence, verbatim:
 *   1. open /grades — Anna shows no grade;
 *   2. sidebar → exercise → open Anna → type a grade → Salvesta;
 *   3. sidebar → Hinded, within 30s (client-side nav, cache intact).
 * Measure: does the table refetch, and what does Anna's cell show?
 *
 *   HARNESS_PORT=5299 node tests/audit/j6b-grade-then-gradetable.mjs
 */
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { withBrowser, fakeApi, shoot, REPORTS, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import { COURSE_ID, CE_ID, studentCourse, baseHandlers } from './fixtures.mjs'

let annaGrade = null // server-side truth: null until the POST lands

const gradeTableExercise = () => ({
  course_exercise_id: CE_ID,
  exercise_id: '4242',
  library_title: 'Kahe arvu summa',
  title_alias: null,
  effective_title: 'Kahe arvu summa',
  grade_threshold: 100,
  student_visible: true,
  grader_type: 'TEACHER',
  ordering_idx: 0,
  latest_submissions: [
    {
      student_id: 's1', given_name: 'Anna', family_name: 'Aare', groups: [],
      submission: annaGrade === null
        ? { id: '9001', submission_number: 1, time: '2026-08-22T10:00:00.000Z', grade: null, seen: true }
        : { id: '9001', submission_number: 1, time: '2026-08-22T10:00:00.000Z', grade: { grade: annaGrade, is_autograde: false, is_graded_directly: true }, seen: true },
      status: annaGrade === null ? 'UNGRADED' : 'COMPLETED',
    },
  ],
})

const ROW = () => ({
  student_id: 's1', given_name: 'Anna', family_name: 'Aare', status: annaGrade === null ? 'UNGRADED' : 'COMPLETED', groups: [],
  submission: { id: '9001', submission_number: 1, time: '2026-08-22T10:00:00.000Z', grade: annaGrade === null ? null : { grade: annaGrade, is_autograde: false, is_graded_directly: true }, seen: true },
})

const teacherDetails = () => ({
  exercise_id: '4242', title: 'Kahe arvu summa', title_alias: null,
  text_html: '<p>Liida.</p>', text_md: 'Liida.', instructions_html: null, instructions_md: null,
  soft_deadline: null, hard_deadline: null, grader_type: 'TEACHER',
  solution_file_name: 'lahendus.py', solution_file_type: 'TEXT_EDITOR', threshold: 100,
  last_modified: '2026-08-20T09:00:00.000Z', student_visible: true, student_visible_from: null,
  assessments_student_visible: true, grading_script: null, container_image: null,
  max_time_sec: null, max_mem_mb: null, assets: null, executors: null,
})

const exercisesListCalls = []

await withBrowser(async ({ launch }) => {
  const { page } = await launch({ role: 'teacher', language: 'et', viewport: VIEWPORTS.laptop })
  await fakeApi(
    page,
    [
      ...baseHandlers(),
      [/\/teacher\/courses(\?|$)/, () => ({ courses: [{ ...studentCourse(), student_count: 1, last_submission_at: null, moodle_short_name: null }] })],
      [new RegExp(`/teacher/courses/${COURSE_ID}/exercises/${CE_ID}/submissions/latest/students`), () => ({ latest_submissions: [ROW()] })],
      [new RegExp(`/teacher/courses/${COURSE_ID}/exercises/${CE_ID}/submissions/all/students/s1`), () => ({
        submissions: [{ id: '9001', submission_number: 1, created_at: '2026-08-22T10:00:00.000Z', status: annaGrade === null ? 'UNGRADED' : 'COMPLETED', grade: annaGrade === null ? null : { grade: annaGrade, is_autograde: false, is_graded_directly: true } }],
      })],
      [new RegExp(`/teacher/courses/${COURSE_ID}/exercises/${CE_ID}/submissions/9001/grade`), ({ body }) => {
        annaGrade = body?.grade ?? 77
        return {}
      }],
      [new RegExp(`/teacher/courses/${COURSE_ID}/exercises/${CE_ID}/submissions/9001`), () => ({
        id: '9001', submission_number: 1, solution: 'print(1)', created_at: '2026-08-22T10:00:00.000Z',
        seen: true, autograde_status: 'NONE', grade: annaGrade === null ? null : { grade: annaGrade, is_autograde: false, is_graded_directly: true }, auto_assessment: null,
      })],
      [new RegExp(`/teacher/courses/${COURSE_ID}/exercises/${CE_ID}(\\?|$)`), () => teacherDetails()],
      // The grade table's list — count every fetch of it.
      [new RegExp(`/teacher/courses/${COURSE_ID}/exercises(\\?|$)`), () => {
        exercisesListCalls.push(annaGrade)
        return { exercises: [gradeTableExercise()] }
      }],
      [new RegExp(`/courses/${COURSE_ID}/groups`), () => ({ groups: [] })],
      [new RegExp(`/courses/${COURSE_ID}/participants`), () => ({ students: [], teachers: [], students_pending: [], students_moodle_pending: [] })],
      [/inline-comments/, () => ({ inline_comments: [] })],
      [/activities/, () => ({ teacher_activities: [] })],
      [/\/v2\//, () => ({ courses: [], exercises: [], submissions: [], count: 0 })],
    ],
    { log: false, contract: false },
  )

  // 1. the grade table, before grading
  await page.goto(`${BASE_URL}/courses/${COURSE_ID}/grades`)
  await waitUntil(async () => (await page.getByText('Aare').count()) > 0, { timeout: 15000 })
  await page.waitForTimeout(800)
  const cellBefore = await page.evaluate(() => document.querySelector('main table')?.innerText.replace(/\s+/g, ' ') ?? '')
  console.log(`[1] grade table before: ${JSON.stringify(cellBefore.slice(0, 160))}`)
  console.log(`    fetches of the exercises list so far: ${exercisesListCalls.length}`)
  await shoot(page, 'j6b-1-table-before')

  // 2. grade Anna, via the sidebar (client-side nav)
  // Client-side navigation only — a goto() reloads the SPA and wipes the query cache, which is the
  // thing under test. Sidebar → exercise list (which lists *exercises*; the first version of this
  // waited for a student name here and timed out) → the exercise → the student.
  await page.getByRole('link', { name: /Ülesanded/i }).first().click()
  await waitUntil(async () => (await page.getByText('Kahe arvu summa').count()) > 0, { timeout: 15000 })
  await page.getByText('Kahe arvu summa').first().click()
  await waitUntil(async () => (await page.getByText('Aare').count()) > 0, { timeout: 15000 })
  await page.getByText('Aare').first().click()
  await page.waitForTimeout(1500)
  const gradeInput = page.locator('main input').first()
  await gradeInput.fill('77')
  await page.getByRole('button', { name: /Salvesta/i }).first().click()
  await page.waitForTimeout(1500)
  console.log(`[2] grade saved; server-side annaGrade is now ${annaGrade}`)
  await shoot(page, 'j6b-2-graded')

  // 3. back to the grade table within staleTime
  await page.getByRole('link', { name: /Hinded/i }).first().click()
  await waitUntil(async () => (await page.getByText('Aare').count()) > 0, { timeout: 15000 })
  await page.waitForTimeout(1200)
  const cellAfter = await page.evaluate(() => document.querySelector('main table')?.innerText.replace(/\s+/g, ' ') ?? '')
  console.log(`[3] grade table after: ${JSON.stringify(cellAfter.slice(0, 160))}`)
  console.log(`    total fetches of the exercises list: ${exercisesListCalls.length} (grades at fetch time: ${JSON.stringify(exercisesListCalls)})`)
  console.log(`    table shows 77: ${cellAfter.includes('77')}`)
  await shoot(page, 'j6b-3-table-after')

  writeFileSync(join(REPORTS, 'j6b-grade-then-gradetable.json'), JSON.stringify({ cellBefore, cellAfter, exercisesListCalls, annaGrade }, null, 2))
  await page.close()
})
console.log('\nreport written')
