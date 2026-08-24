/**
 * Unit J6 — teacher grading, the flow `doc/testing.md` calls priority 1.
 *
 * `course-exercise-grading.spec.mjs` already proves the mechanics: a teacher can open a submission,
 * read the solution, and save a grade with the right request body. So the audit's question is the
 * workflow around it, which nothing tests — **what does it cost to grade thirty submissions?**
 *
 *  - How does a teacher see who needs grading? `LatestSubmissionResp` carries `seen`, so the primitive
 *    for a queue exists; the question is whether the list uses it to help.
 *  - After grading one student, how do they reach the next ungraded one? If that means going back to a
 *    list every time, thirty submissions is thirty round trips — a punishing path in the plan's terms.
 *  - Is graded-versus-ungraded legible at a glance in the roster?
 *
 * Six students, deliberately mixed: unseen+ungraded, seen+ungraded, graded, and not started.
 *
 *   HARNESS_PORT=5299 node tests/audit/j6-grading-workflow.mjs
 */
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { withBrowser, fakeApi, shoot, REPORTS, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import { COURSE_ID, CE_ID, studentCourse, baseHandlers } from './fixtures.mjs'

const grade = (n) => ({ grade: n, is_autograde: false, is_graded_directly: true })

const ROWS = [
  { student_id: 's1', given_name: 'Anna', family_name: 'Aare', status: 'UNGRADED', groups: [],
    submission: { id: '9001', submission_number: 1, time: '2026-08-22T10:00:00.000Z', grade: null, seen: false } },
  { student_id: 's2', given_name: 'Boris', family_name: 'Bome', status: 'UNGRADED', groups: [],
    submission: { id: '9002', submission_number: 2, time: '2026-08-22T11:00:00.000Z', grade: null, seen: false } },
  { student_id: 's3', given_name: 'Carla', family_name: 'Curro', status: 'UNGRADED', groups: [],
    submission: { id: '9003', submission_number: 1, time: '2026-08-22T12:00:00.000Z', grade: null, seen: true } },
  { student_id: 's4', given_name: 'Dmitri', family_name: 'Doe', status: 'COMPLETED', groups: [],
    submission: { id: '9004', submission_number: 3, time: '2026-08-21T09:00:00.000Z', grade: grade(90), seen: true } },
  { student_id: 's5', given_name: 'Eeva', family_name: 'Eesti', status: 'COMPLETED', groups: [],
    submission: { id: '9005', submission_number: 1, time: '2026-08-20T09:00:00.000Z', grade: grade(55), seen: true } },
  { student_id: 's6', given_name: 'Fred', family_name: 'Fox', status: 'UNSTARTED', groups: [], submission: null },
]

const teacherDetails = () => ({
  exercise_id: '4242', title: 'Kahe arvu summa', title_alias: null,
  text_html: '<p>Liida kaks arvu.</p>', text_md: 'Liida kaks arvu.',
  instructions_html: null, instructions_md: null,
  soft_deadline: null, hard_deadline: null,
  grader_type: 'TEACHER', solution_file_name: 'lahendus.py', solution_file_type: 'TEXT_EDITOR',
  threshold: 100, last_modified: '2026-08-20T09:00:00.000Z',
  student_visible: true, student_visible_from: null, assessments_student_visible: true,
  grading_script: null, container_image: null, max_time_sec: null, max_mem_mb: null,
  assets: null, executors: null,
})

const detail = (id, g) => ({
  id, submission_number: 1,
  solution: 'a = int(input())\nb = int(input())\nprint(a - b)\n',
  created_at: '2026-08-22T10:00:00.000Z', seen: false,
  autograde_status: 'NONE', grade: g, auto_assessment: null,
})

const putBodies = []

await withBrowser(async ({ launch }) => {
  const { page } = await launch({ role: 'teacher', language: 'et', viewport: VIEWPORTS.laptop })
  await fakeApi(
    page,
    [
      ...baseHandlers(),
      [/\/teacher\/courses(\?|$)/, () => ({ courses: [{ ...studentCourse(), student_count: 6, last_submission_at: null, moodle_short_name: null }] })],
      [new RegExp(`/teacher/courses/${COURSE_ID}/exercises/${CE_ID}/submissions/latest/students`), () => ({ latest_submissions: ROWS })],
      // The grading pane reads a *list* of the student's submissions and renders "Esitamata" when it
      // is empty (StudentGradingView.tsx:459). The first run of this driver never stubbed it, so the
      // pane correctly said "not submitted" and the run proved nothing about the grading form.
      // Anchored before the by-id stub, because `/submissions/all/students/s1` also matches a looser
      // `/submissions/9\d+`-style needle only by accident of ordering.
      [
        new RegExp(`/teacher/courses/${COURSE_ID}/exercises/${CE_ID}/submissions/all/students/(\\w+)`),
        ({ url }) => {
          const sid = url.match(/students\/(\w+)/)?.[1]
          const row = ROWS.find((r) => r.student_id === sid)
          if (!row?.submission) return { submissions: [] }
          return {
            submissions: [
              {
                id: row.submission.id,
                submission_number: row.submission.submission_number,
                created_at: row.submission.time,
                status: row.status,
                grade: row.submission.grade,
              },
            ],
          }
        },
      ],
      [new RegExp(`/teacher/courses/${COURSE_ID}/exercises/${CE_ID}/submissions/9\\d+`), ({ url }) => {
        const id = url.match(/submissions\/(9\d+)/)?.[1] ?? '9001'
        const row = ROWS.find((r) => r.submission?.id === id)
        return detail(id, row?.submission?.grade ?? null)
      }],
      [new RegExp(`/teacher/courses/${COURSE_ID}/exercises/${CE_ID}(\\?|$)`), () => teacherDetails()],
      [new RegExp(`/courses/${COURSE_ID}/participants`), () => ({ students: [], teachers: [], students_pending: [], students_moodle_pending: [] })],
      [new RegExp(`/courses/${COURSE_ID}/groups`), () => ({ groups: [] })],
      [/inline-comments/, () => ({ inline_comments: [] })],
      [/activities/, () => ({ teacher_activities: [] })],
      [/\/v2\//, () => ({ courses: [], exercises: [], submissions: [], count: 0 })],
    ],
    { log: false, contract: false },
  )
  await page.route('**/activities', async (route) => {
    if (route.request().method() !== 'PUT' && route.request().method() !== 'POST') return route.fallback()
    try { putBodies.push(route.request().postDataJSON()) } catch { /* ignore */ }
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
  })

  await page.goto(`${BASE_URL}/courses/${COURSE_ID}/exercises/${CE_ID}`)
  await waitUntil(async () => (await page.getByText('Anna').count()) > 0, { timeout: 15000 })
  await page.waitForTimeout(1200)
  await shoot(page, 'j6-01-student-list')

  // ── how is the roster presented, and is `seen` used? ────────────────────────────────────────────
  const list = await page.evaluate(() => {
    const main = document.querySelector('main')
    const txt = (main?.innerText ?? '').replace(/\s+/g, ' ')
    return {
      text: txt.slice(0, 700),
      // Any sort/filter affordance for "needs grading"?
      controls: [...main.querySelectorAll('button, [role=combobox], .MuiChip-root')]
        .filter((b) => b.offsetParent !== null)
        .map((b) => (b.innerText || b.getAttribute('aria-label') || '').trim())
        .filter(Boolean)
        .slice(0, 20),
      rows: main.querySelectorAll('tr').length,
      badges: main.querySelectorAll('.MuiBadge-badge').length,
    }
  })
  console.log(`\n[roster] rows=${list.rows} badges=${list.badges}`)
  console.log(`[roster] controls: ${JSON.stringify(list.controls)}`)
  console.log(`[roster] text: ${JSON.stringify(list.text.slice(0, 420))}`)

  // ── grade the first student, counting clicks ────────────────────────────────────────────────────
  let clicks = 0
  const click = async (locator, what) => {
    clicks++
    await locator.click()
    console.log(`   click ${clicks}: ${what}`)
  }

  await click(page.getByText('Anna').first(), 'open Anna')
  await page.waitForTimeout(1500)
  await shoot(page, 'j6-02-grading-anna')

  const gradingUi = await page.evaluate(() => {
    const main = document.querySelector('main')
    return {
      textFields: main.querySelectorAll('input[type=text], input[type=number], textarea').length,
      buttons: [...main.querySelectorAll('button')]
        .filter((b) => b.offsetParent !== null)
        .map((b) => (b.innerText || b.getAttribute('aria-label') || '').trim())
        .filter(Boolean),
      // Is there any way to reach the next student from here? The first version of this looked only
      // for *text* matching /järgmine|next|edasi/ and reported false — while the UI has icon-only
      // `‹ ›` chevrons and a name dropdown sitting right there. Count the controls in the student
      // header region instead of trusting labels, and record their accessible names so the
      // icon-without-a-name question is answered at the same time.
      studentNav: [...main.querySelectorAll('button')]
        .filter((b) => b.offsetParent !== null && b.closest('[class*=MuiBox]'))
        .map((b) => ({
          text: (b.innerText || '').trim(),
          ariaLabel: b.getAttribute('aria-label'),
          title: b.getAttribute('title'),
          svg: !!b.querySelector('svg'),
        }))
        .filter((b) => !b.text && b.svg)
        .slice(0, 10),
      text: (main?.innerText ?? '').replace(/\s+/g, ' ').slice(0, 400),
    }
  })
  console.log(`\n[grading view] fields=${gradingUi.textFields}`)
  console.log(`[grading view] buttons: ${JSON.stringify(gradingUi.buttons)}`)
  console.log(`[grading view] icon-only controls in the student header: ${JSON.stringify(gradingUi.studentNav)}`)

  // ── now reach the SECOND ungraded student, counting every click ─────────────────────────────────
  const before = clicks
  const back = page.getByRole('button', { name: /tagasi|Back/i }).first()
  if (await back.count()) {
    await click(back, 'back to the list')
    await page.waitForTimeout(1000)
  }
  const boris = page.getByText('Boris').first()
  if (await boris.count()) {
    await click(boris, 'open Boris')
    await page.waitForTimeout(1200)
  }
  console.log(`\n[next student] clicks to move from grading Anna to grading Boris: ${clicks - before}`)
  await shoot(page, 'j6-03-grading-boris')

  writeFileSync(join(REPORTS, 'j6-grading-workflow.json'), JSON.stringify({ list, gradingUi, clicksToNextStudent: clicks - before, putBodies }, null, 2))
  await page.close()
})
console.log('\nreport written')
