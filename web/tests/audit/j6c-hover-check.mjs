/**
 * J6, Stage 2 on the hover question: is there a comment affordance on hover?
 *
 * The first measurement counted DOM nodes across a hover and saw Δ0 — but the `+` markers exist
 * permanently in a `.cm-add-comment-gutter` column and are revealed by a `gutter-hovered` CSS class,
 * so node-counting is structurally blind to it. This hovers the gutter itself and reads the class.
 */
import { withBrowser, fakeApi, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import { COURSE_ID, CE_ID, studentCourse, baseHandlers } from './fixtures.mjs'

await withBrowser(async ({ launch }) => {
  const { page } = await launch({ role: 'teacher', language: 'et', viewport: VIEWPORTS.laptop })
  await fakeApi(page, [
    ...baseHandlers(),
    [/\/teacher\/courses(\?|$)/, () => ({ courses: [{ ...studentCourse(), student_count: 1, last_submission_at: null, moodle_short_name: null }] })],
    [/submissions\/latest\/students/, () => ({ latest_submissions: [{ student_id: 's1', given_name: 'Anna', family_name: 'Aare', status: 'UNGRADED', groups: [], submission: { id: '9001', submission_number: 1, time: '2026-08-22T10:00:00.000Z', grade: null, seen: true } }] })],
    [/submissions\/all\/students\/s1/, () => ({ submissions: [{ id: '9001', submission_number: 1, created_at: '2026-08-22T10:00:00.000Z', status: 'UNGRADED', grade: null }] })],
    [/students\/s1\/inline-comments/, () => ({ inline_comments: [] })],
    [/students\/s1\/activities/, () => ({ teacher_activities: [] })],
    [/submissions\/9001(\?|$)/, () => ({ id: '9001', submission_number: 1, solution: 'a = 1\nb = 2\nprint(a + b)\n', created_at: '2026-08-22T10:00:00.000Z', seen: true, autograde_status: 'NONE', grade: null, auto_assessment: null })],
    [new RegExp(`/teacher/courses/${COURSE_ID}/exercises/${CE_ID}(\\?|$)`), () => ({ exercise_id: '4242', title: 'X', title_alias: null, text_html: '<p>x</p>', text_md: 'x', instructions_html: null, instructions_md: null, soft_deadline: null, hard_deadline: null, grader_type: 'TEACHER', solution_file_name: 'l.py', solution_file_type: 'TEXT_EDITOR', threshold: 100, last_modified: 'x', student_visible: true, student_visible_from: null, assessments_student_visible: true, grading_script: null, container_image: null, max_time_sec: null, max_mem_mb: null, assets: null, executors: null })],
    [/\/v2\//, () => ({ courses: [], exercises: [], submissions: [], groups: [], count: 0 })],
  ], { log: false, contract: false })
  await page.goto(`${BASE_URL}/courses/${COURSE_ID}/exercises/${CE_ID}`)
  await waitUntil(async () => (await page.getByText('Aare').count()) > 0, { timeout: 15000 })
  await page.getByText('Aare').first().click()
  await page.waitForTimeout(2000)
  const g = await page.evaluate(() => {
    const col = document.querySelector('.cm-add-comment-gutter')
    return col ? { exists: true, width: Math.round(col.getBoundingClientRect().width), markers: col.querySelectorAll('.cm-gutterElement').length } : { exists: false }
  })
  console.log('gutter:', JSON.stringify(g))
  if (g.exists) {
    const line2 = page.locator('.cm-line').nth(1)
    const box = await line2.boundingBox()
    const col = await page.locator('.cm-add-comment-gutter').boundingBox()
    await page.mouse.move(col.x + col.width / 2, box.y + box.height / 2)
    await page.waitForTimeout(400)
    const hovered = await page.evaluate(() => {
      const el = document.querySelector('.gutter-hovered')
      if (!el) return { cls: false }
      const cs = getComputedStyle(el)
      return { cls: true, text: (el.textContent ?? '').trim(), visible: cs.opacity !== '0' && cs.visibility !== 'hidden' }
    })
    console.log('hover over gutter:', JSON.stringify(hovered))
  }
  await page.close()
})
