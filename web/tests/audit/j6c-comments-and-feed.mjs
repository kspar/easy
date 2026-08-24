/**
 * Unit J6 remainder — inline comments and the activity feed, from both seats.
 *
 *  A. Teacher seat: how does a teacher discover they can comment on a line of the student's code?
 *     (AnnotatedCodeEditor is 871 lines; discoverability is the audit question, not the mechanics.)
 *  B. Student seat: the same submission with an inline comment and a teacher activity — do both
 *     actually reach the student, and is the conversation legible?
 *
 *   HARNESS_PORT=5299 node tests/audit/j6c-comments-and-feed.mjs
 */
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { withBrowser, fakeApi, shoot, REPORTS, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import { COURSE_ID, CE_ID, studentCourse, studentExercise, exerciseDetails, submission, teacherActivity, baseHandlers } from './fixtures.mjs'

const inlineComment = () => ({
  id: 'c1', submission_id: '9001', submission_number: 1,
  teacher: { id: 't1', given_name: 'Mari', family_name: 'Tamm' },
  created_at: '2026-08-22T16:00:00.000Z', edited_at: null,
  line_start: 3, line_end: 3, code: 'print(a - b)',
  text_md: 'Miinus, mitte pluss — loe ülesanne uuesti läbi.',
  text_html: '<p>Miinus, mitte pluss — loe ülesanne uuesti läbi.</p>',
})

// ── A. teacher seat ────────────────────────────────────────────────────────────────────────────────
await withBrowser(async ({ launch }) => {
  const { page } = await launch({ role: 'teacher', language: 'et', viewport: VIEWPORTS.laptop })
  await fakeApi(
    page,
    [
      ...baseHandlers(),
      [/\/teacher\/courses(\?|$)/, () => ({ courses: [{ ...studentCourse(), student_count: 1, last_submission_at: null, moodle_short_name: null }] })],
      [new RegExp(`/submissions/latest/students`), () => ({
        latest_submissions: [{ student_id: 's1', given_name: 'Anna', family_name: 'Aare', status: 'UNGRADED', groups: [], submission: { id: '9001', submission_number: 1, time: '2026-08-22T10:00:00.000Z', grade: null, seen: true } }],
      })],
      [new RegExp(`/submissions/all/students/s1`), () => ({ submissions: [{ id: '9001', submission_number: 1, created_at: '2026-08-22T10:00:00.000Z', status: 'UNGRADED', grade: null }] })],
      [new RegExp(`/students/s1/inline-comments`), () => ({ inline_comments: [inlineComment()] })],
      [new RegExp(`/students/s1/activities`), () => ({ teacher_activities: [teacherActivity()] })],
      [new RegExp(`/submissions/9001(\\?|$)`), () => ({
        id: '9001', submission_number: 1, solution: 'a = int(input())\nb = int(input())\nprint(a - b)\n',
        created_at: '2026-08-22T10:00:00.000Z', seen: true, autograde_status: 'NONE', grade: null, auto_assessment: null,
      })],
      [new RegExp(`/teacher/courses/${COURSE_ID}/exercises/${CE_ID}(\\?|$)`), () => ({
        exercise_id: '4242', title: 'Kahe arvu summa', title_alias: null,
        text_html: '<p>Liida.</p>', text_md: 'Liida.', instructions_html: null, instructions_md: null,
        soft_deadline: null, hard_deadline: null, grader_type: 'TEACHER',
        solution_file_name: 'lahendus.py', solution_file_type: 'TEXT_EDITOR', threshold: 100,
        last_modified: 'x', student_visible: true, student_visible_from: null, assessments_student_visible: true,
        grading_script: null, container_image: null, max_time_sec: null, max_mem_mb: null, assets: null, executors: null,
      })],
      [/\/v2\//, () => ({ courses: [], exercises: [], submissions: [], groups: [], count: 0 })],
    ],
    { log: false, contract: false },
  )

  await page.goto(`${BASE_URL}/courses/${COURSE_ID}/exercises/${CE_ID}`)
  await waitUntil(async () => (await page.getByText('Aare').count()) > 0, { timeout: 15000 })
  await page.getByText('Aare').first().click()
  await page.waitForTimeout(2000)
  await shoot(page, 'j6c-A1-teacher-view')

  // What tells the teacher a line is commentable? Hover line 2 (uncommented) and diff the DOM.
  const line2 = page.locator('.cm-line').nth(1)
  const beforeHover = await page.evaluate(() => document.querySelectorAll('.cm-gutterElement *, .cm-line button, [class*=comment]').length)
  if (await line2.count()) await line2.hover()
  await page.waitForTimeout(600)
  const afterHover = await page.evaluate(() => document.querySelectorAll('.cm-gutterElement *, .cm-line button, [class*=comment]').length)
  const hoverAffordance = await page.evaluate(() => {
    const els = [...document.querySelectorAll('button, [role=button]')].filter((b) => b.offsetParent !== null)
    return els.map((b) => (b.getAttribute('aria-label') || b.title || b.innerText || '').trim()).filter((t) => /komm|comment|lisa/i.test(t)).slice(0, 4)
  })
  console.log(`[A] existing comment visible: ${await page.getByText('Miinus, mitte pluss').count() > 0}`)
  console.log(`[A] hover on an uncommented line: dom Δ ${afterHover - beforeHover}, affordances: ${JSON.stringify(hoverAffordance)}`)
  await shoot(page, 'j6c-A2-hover-line')

  // The activity feed on this seat.
  const feed = await page.evaluate(() => {
    const t = (document.querySelector('main')?.innerText ?? '').replace(/\s+/g, ' ')
    return { hasFeedback: /kõnekamad nimed/.test(t), hasGrade80: /\b80\b/.test(t) }
  })
  console.log(`[A] activity feed: teacher feedback visible=${feed.hasFeedback}, grade 80 visible=${feed.hasGrade80}`)
  await page.close()
})

// ── B. student seat, same data ─────────────────────────────────────────────────────────────────────
await withBrowser(async ({ launch }) => {
  const { page } = await launch({ role: 'student', language: 'et', viewport: VIEWPORTS.laptop })
  await fakeApi(
    page,
    [
      ...baseHandlers(),
      [/\/student\/courses(\?|$)/, () => ({ courses: [studentCourse()] })],
      [new RegExp(`/student/courses/${COURSE_ID}/exercises(\\?|$)`), () => ({ exercises: [studentExercise({ status: 'COMPLETED', grade: { grade: 80, is_autograde: false, is_graded_directly: true } })] })],
      [new RegExp(`/exercises/${CE_ID}/submissions/all`), () => ({
        submissions: [submission({ solution: 'a = int(input())\nb = int(input())\nprint(a - b)\n', grade: { grade: 80, is_autograde: false, is_graded_directly: true }, auto_assessment: null, autograde_status: 'NONE' })],
      })],
      [new RegExp(`/exercises/${CE_ID}/activities`), () => ({ teacher_activities: [teacherActivity()] })],
      [new RegExp(`/exercises/${CE_ID}/inline-comments`), () => ({ inline_comments: [inlineComment()] })],
      [new RegExp(`/exercises/${CE_ID}(\\?|$)`), () => exerciseDetails()],
    ],
    { log: false, contract: false },
  )

  await page.goto(`${BASE_URL}/courses/${COURSE_ID}/exercises/${CE_ID}`)
  await waitUntil(async () => (await page.locator('main').count()) > 0, { timeout: 15000 })
  await page.waitForTimeout(2500)

  const seen = await page.evaluate(() => {
    const t = (document.querySelector('main')?.innerText ?? '').replace(/\s+/g, ' ')
    return {
      inlineCommentVisible: /Miinus, mitte pluss/.test(t),
      teacherFeedbackVisible: /kõnekamad nimed/.test(t),
      grade80: /\b80\b/.test(t),
      teacherNamed: /Mari Tamm/.test(t),
      text: t.slice(0, 500),
    }
  })
  console.log(`\n[B] student seat: inline comment=${seen.inlineCommentVisible}, feedback=${seen.teacherFeedbackVisible}, grade=${seen.grade80}, teacher named=${seen.teacherNamed}`)
  console.log(`[B] text: ${JSON.stringify(seen.text.slice(0, 320))}`)
  await shoot(page, 'j6c-B1-student-view')

  writeFileSync(join(REPORTS, 'j6c-comments-and-feed.json'), JSON.stringify(seen, null, 2))
  await page.close()
})
console.log('\nreport written')
