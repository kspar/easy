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
  let tokensUsed = 123456
  const puts = []
  const resets = []

  await fakeApi(page, [
    ['/account/checkin', () => ({})],
    // Before the props handler, whose needle is a prefix of this URL.
    [`/courses/${COURSE_ID}/ai/reset-usage`, ({ method }) => {
      if (method === 'POST') {
        resets.push(method)
        tokensUsed = 0
      }
      return {}
    }],
    [`/courses/${COURSE_ID}/ai`, ({ method, body }) => {
      if (method === 'PUT') {
        puts.push(body)
        configured = body.ai_props !== null
        return {}
      }
      return {
        ai_props: configured
          ? { provider: 'ANTHROPIC', model: 'claude-opus-5', api_key_configured: true, api_key_hint: '9876' }
          : null,
        token_budget: 1000000,
        tokens_used: tokensUsed,
        tokens_reset_at: null,
        // Zero, so the estimate falls back to the stock split the numbers below are computed with.
        tokens_in_used: 0,
        tokens_out_used: 0,
        max_solution_chars: 6000,
        base_url: null,
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

  // --- budget, estimate and usage ---------------------------------------------------------------
  // 1 000 000 tokens on claude-opus-5 at 90 % input: 0.9 × $5 + 0.1 × $25 = $7.00.
  const budgetField = dialog.getByLabel(/Token budget/)
  check('the budget is shown', await waitUntil(async () => (await budgetField.inputValue()) === '1000000'))
  check('with its estimated cost', (await dialog.getByText(/≈ \$7\.00/).count()) > 0)
  check('and what has been used so far, priced the same way', (await dialog.getByText(/Used: 123[\s,]456 tokens \(≈ \$0\.86\)/).count()) > 0)

  // The estimate follows the model as it is typed: sonnet is 2/10, so $2.80.
  const modelField = dialog.getByLabel(/^Model/)
  await modelField.fill('claude-sonnet-5')
  check('changing the model re-prices the budget', await waitUntil(async () => (await dialog.getByText(/≈ \$2\.80/).count()) > 0))
  await modelField.fill('my-local-model')
  check('an unknown model gets no estimate rather than a wrong one', await waitUntil(async () => (await dialog.getByText(/No price known/).count()) > 0))
  await modelField.fill('claude-sonnet-5')

  // A budget of 2 500 000 on sonnet: $7.00 again, by a different route.
  await budgetField.fill('2500000')
  check('a new budget is re-estimated', await waitUntil(async () => (await dialog.getByText(/≈ \$7\.00/).count()) > 0))
  await shot('01-dialog')

  await budgetField.fill('abc')
  check('a non-number disables Save', await waitUntil(() => dialog.getByRole('button', { name: /^Save$/ }).isDisabled()))
  await budgetField.fill('2500000')

  // --- edit the model, leave the key alone ------------------------------------------------------
  await dialog.getByRole('button', { name: /^Save$/ }).click()

  check('saving PUTs once', await waitUntil(() => puts.length === 1))
  check('with the new model', puts[0]?.ai_props?.model === 'claude-sonnet-5')
  check('and the new budget as a number', puts[0]?.ai_props?.token_budget === 2500000)
  check('and api_key null, meaning keep the stored one', puts[0]?.ai_props?.api_key === null)
  // The account is an admin but the active role is teacher, and that is the case that used to
  // wipe an admin's proxy URL on every save: core sees the admin role, the web sent null, null
  // meant clear. Now a teacher-mode save carries no base_url at all, which core reads as keep.
  check('and no base_url at all, since the active role is teacher', !('base_url' in (puts[0]?.ai_props ?? {})))
  check('the dialog closes', await waitUntil(async () => (await page.getByRole('dialog').count()) === 0))

  // --- reset the counter ------------------------------------------------------------------------
  await entry.click()
  await waitUntil(() => page.getByRole('dialog').isVisible())
  await page.getByRole('dialog').getByRole('button', { name: /Reset counter/ }).click()
  const resetConfirm = page.getByRole('dialog').filter({ hasText: /Reset this course's token counter/ })
  check('resetting asks first', await waitUntil(() => resetConfirm.isVisible()))
  await resetConfirm.getByRole('button', { name: /Reset counter/ }).click()
  check('confirming POSTs to reset-usage, and nothing to the props', await waitUntil(() => resets.length === 1 && puts.length === 1))
  check(
    'and the dialog now shows zero used, with the reset button gone quiet',
    await waitUntil(async () => (await page.getByRole('dialog').getByText(/Used: 0 tokens/).count()) > 0),
  )
  check('reset is disabled at zero', await page.getByRole('dialog').getByRole('button', { name: /Reset counter/ }).isDisabled())
  await page.getByRole('dialog').getByRole('button', { name: /^Cancel$/ }).click()
  await waitUntil(async () => (await page.getByRole('dialog').count()) === 0)

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
