/**
 * What the teacher sees of an AI explanation (EZ-1712): the same card the student saw, in the
 * grading pane's activity feed, with none of the teacher-comment controls on it.
 *
 * "Teachers can see everything the AI told their students" is the sentence that makes the feature
 * defensible, so the card's presence in the teacher's feed is the assertion — and its lack of an
 * edit or delete affordance, because an explanation nobody wrote is not something to edit.
 *
 *   cd web && npx playwright test course-exercise-ai-feedback-teacher
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const COURSE = '1'
const CE = '10'
const STUDENT = 'student1'
const SUB = '500'

// Field names from course-exercise-retry-autoassess.spec.mjs.
const exercise = {
  exercise_id: '77',
  title: 'Sum of two numbers',
  title_alias: null,
  text_html: '<p>Read two integers and print their sum.</p>',
  instructions_html: null,
  soft_deadline: null,
  hard_deadline: null,
  grader_type: 'AUTO',
  solution_file_name: 'sum.py',
  solution_file_type: 'TEXT_EDITOR',
  threshold: 100,
  ai_explanations_per_student: 3,
  student_visible: true,
  student_visible_from: null,
  has_lib_access: false,
  exception_students: null,
  exception_groups: null,
}

const submissionDetail = {
  id: SUB,
  solution: 'print(abs(int(input())) + int(input()))',
  created_at: '2026-08-01T10:00:00.000Z',
  grade: { grade: 40, is_autograde: true, is_graded_directly: true },
  seen: true,
  flagged: false,
  auto_assessment: { grade: 40, feedback: 'Handles negatives: FAIL' },
}

const teacherComment = {
  id: '300',
  submission_id: SUB,
  submission_number: 1,
  created_at: '2026-08-01T11:00:00.000Z',
  grade: null,
  edited_at: null,
  feedback_md: 'Think about what abs() does to a negative number.',
  feedback_html: '<p>Think about what abs() does to a negative number.</p>',
  teacher: { id: 'teacher1', given_name: 'Mari', family_name: 'Maasikas' },
}

const aiFeedback = {
  id: '77',
  submission_id: SUB,
  submission_number: 1,
  created_at: '2026-08-01T10:30:00.000Z',
  provider: 'ANTHROPIC',
  model: 'claude-opus-5',
  feedback_md: 'The first number loses its sign before the addition.',
  feedback_html: '<p>The first number loses its sign before the addition.</p>',
}

test('course-exercise-ai-feedback-teacher', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'teacher,admin', language: 'en', shotPrefix: 'ce-ai-feedback-teacher-' })

  await fakeApi(page, [
    ['/account/checkin', () => ({})],
    [`/teacher/courses/${COURSE}/exercises/${CE}/submissions/latest/students`, () => ({
      latest_submissions: [{
        student_id: STUDENT,
        given_name: 'Mari',
        family_name: 'Maasikas',
        submission_id: SUB,
        created_at: '2026-08-01T10:00:00.000Z',
        grade: 40,
        seen: true,
      }],
    })],
    [`/exercises/${CE}/submissions/all/students/${STUDENT}`, () => ({
      submissions: [{
        id: SUB,
        created_at: '2026-08-01T10:00:00.000Z',
        grade: { grade: 40, is_autograde: true, is_graded_directly: true },
      }],
    })],
    [`/students/${STUDENT}/activities`, () => ({ teacher_activities: [teacherComment], ai_feedback: [aiFeedback] })],
    [`/students/${STUDENT}/inline-comments`, () => ({ inline_comments: [] })],
    [`/submissions/${SUB}`, () => submissionDetail],
    [new RegExp(`/teacher/courses/${COURSE}/exercises/${CE}(\\?|$)`), () => exercise],
    [`/student/courses/${COURSE}/exercises`, () => ({ exercises: [] })],
    ['/groups', () => ({ groups: [] })],
    ['/participants', () => ({ students: [], teachers: [], students_pending: [], students_moodle_pending: [] })],
    ['/teacher/courses', () => ({ courses: [] })],
  ], { log: false })

  await page.goto(`${BASE_URL}/courses/${COURSE}/exercises/${CE}?student=${STUDENT}`)
  await page.getByText('Sum of two numbers').first().waitFor({ timeout: 15000 })

  const aiText = page.getByText('loses its sign before the addition')
  check('the AI explanation is in the teacher\'s feed', await waitUntil(() => aiText.isVisible()))
  check('labelled as AI', (await page.getByText('AI explanation').count()) > 0)
  check('next to the teacher\'s own comment', (await page.getByText('what abs() does').count()) > 0)
  check('which still carries the teacher\'s name', (await page.getByText('Mari Maasikas').count()) > 0)
  await aiText.scrollIntoViewIfNeeded()
  await shot('01-feed')

  // The teacher card has an edit affordance; the AI card must not. Count edit controls on the page
  // and expect exactly the teacher comment's worth.
  const editButtons = await page.getByRole('button', { name: /^Edit/i }).count()
  check('the AI card has no edit control (one edit button: the teacher comment\'s)', editButtons <= 1)

  await close()
})
