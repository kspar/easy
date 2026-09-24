/**
 * The per-course AI settings dialog (EZ-1711), from the sidebar.
 *
 * The key is the whole point. Core never returns it, so the dialog has to work with a field it
 * cannot fill in — and the two writes that matter are the ones a teacher does without touching the
 * key: saving a model change must send `api_key: null` (keep), and switching off must send
 * `ai_props: null` (clear). A dialog that sent an empty string in the first case would wipe a
 * working key on every edit.
 *
 *   cd web && npx playwright test course-ai-settings
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const COURSE_ID = '9074'

test('course-ai-settings', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'teacher,admin', language: 'en', shotPrefix: 'course-ai-settings-' })

  let configured = true
  const puts = []

  await fakeApi(page, [
    ['/account/checkin', () => ({})],
    [`/courses/${COURSE_ID}/ai`, ({ method, body }) => {
      if (method === 'PUT') {
        puts.push(body)
        configured = body.ai_props !== null
        return {}
      }
      return {
        ai_props: configured
          ? { provider: 'ANTHROPIC', model: 'claude-opus-5', base_url: null, api_key_configured: true, api_key_hint: '9876' }
          : null,
      }
    }],
    [`/courses/${COURSE_ID}/basic`, () => ({
      title: 'Programming 101',
      alias: null,
      archived: false,
      color: 'blue',
      course_code: 'LTAT.03.001',
      moodle_course_url: null,
    })],
    [`/student/courses/${COURSE_ID}/exercises`, () => ({ exercises: [] })],
    [`/courses/${COURSE_ID}/exercises`, () => ({ exercises: [] })],
    [`/courses/${COURSE_ID}/groups`, () => ({ groups: [] })],
    ['/courses/teacher', () => ({ courses: [] })],
    ['/management/common/notifications', () => ({ messages: [] })],
  ], { log: false })

  await page.goto(`${BASE_URL}/courses/${COURSE_ID}/exercises`)
  await waitUntil(async () => (await page.locator('nav').getByText('Programming 101').count()) > 0)

  const entry = page.locator('nav').getByText('AI feedback')
  check('the sidebar offers AI feedback settings to a teacher', await waitUntil(() => entry.isVisible()))
  await entry.click()

  const dialog = page.getByRole('dialog')
  check('the dialog opens', await waitUntil(() => dialog.isVisible()))
  check(
    'a configured key shows as configured, by its tail, never its value',
    await waitUntil(async () => (await dialog.getByPlaceholder(/ends in 9876/).count()) > 0),
  )
  check('and the value is nowhere on the page', (await page.getByText('sk-ant').count()) === 0)
  await shot('01-dialog')

  // --- edit the model, leave the key alone ------------------------------------------------------
  const modelField = dialog.getByLabel(/^Model/)
  await modelField.fill('claude-sonnet-5')
  await dialog.getByRole('button', { name: /^Save$/ }).click()

  check('saving PUTs once', await waitUntil(() => puts.length === 1))
  check('with the new model', puts[0]?.ai_props?.model === 'claude-sonnet-5')
  check('and api_key null, meaning keep the stored one', puts[0]?.ai_props?.api_key === null)
  check('the dialog closes', await waitUntil(async () => (await page.getByRole('dialog').count()) === 0))

  // --- switch off -------------------------------------------------------------------------------
  await entry.click()
  await waitUntil(() => page.getByRole('dialog').isVisible())
  await page.getByRole('dialog').getByRole('button', { name: /Switch off/ }).click()

  // A confirm dialog on top: the destructive one.
  const confirm = page.getByRole('dialog').filter({ hasText: /Switch off AI feedback/ })
  check('switching off asks first', await waitUntil(() => confirm.isVisible()))
  await shot('02-confirm')
  await confirm.getByRole('button', { name: /Switch off/ }).click()

  check('confirming PUTs ai_props null', await waitUntil(() => puts.length === 2 && puts[1]?.ai_props === null))

  // --- from nothing, the key is required --------------------------------------------------------
  await waitUntil(async () => (await page.getByRole('dialog').count()) === 0)
  await entry.click()
  const fresh = page.getByRole('dialog')
  await waitUntil(() => fresh.isVisible())
  check(
    'with no key stored, Save is disabled until one is typed',
    await waitUntil(() => fresh.getByRole('button', { name: /^Save$/ }).isDisabled()),
  )
  await fresh.getByLabel(/API key/).fill('sk-ant-new-key-0001')
  check('and enabled once it is', await waitUntil(() => fresh.getByRole('button', { name: /^Save$/ }).isEnabled()))
  await fresh.getByRole('button', { name: /^Save$/ }).click()
  check(
    'the typed key is sent',
    await waitUntil(() => puts.length === 3 && puts[2]?.ai_props?.api_key === 'sk-ant-new-key-0001'),
  )

  await close()
})
