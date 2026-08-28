/**
 * Verification driver for the X-017 fix: in-app navigation out of a dirty library-exercise edit
 * session asks through the same dialog as Cancel, and neither uses window.confirm any more.
 *
 * Asserting, like x001-draft-verify.mjs — it exists to fail loudly if the guard regresses.
 *
 *   cd web && npx vite --config vite.stub.config.ts --port 5299 --strictPort &
 *   HARNESS_PORT=5299 node tests/audit/x017-guard-verify.mjs
 */
import { withBrowser, fakeApi, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import { baseHandlers } from './fixtures.mjs'

const EX_ID = '4242'

const exercise = () => ({
  dir_id: 'root',
  effective_access: 'PRAWM',
  created_at: '2026-08-23T10:00:00.000Z',
  is_public: false,
  is_anonymous_autoassess_enabled: false,
  owner_id: 'kspar',
  last_modified: '2026-08-23T10:00:00.000Z',
  last_modified_by_id: 'kspar',
  grader_type: 'TEACHER',
  solution_file_name: 'lahendus.py',
  solution_file_type: 'TEXT_EDITOR',
  title: 'Kahe arvu summa',
  text_html: '<p>Liida kaks arvu.</p>',
  text_md: 'Liida kaks arvu.',
  anonymous_autoassess_template: '',
  grading_script: null,
  container_image: null,
  max_time_sec: null,
  max_mem_mb: null,
  assets: null,
  executors: null,
  on_courses: [],
  on_courses_no_access: 0,
})

const handlers = () => [
  ...baseHandlers(),
  [new RegExp(`/exercises/${EX_ID}(\\?|$)`), () => exercise()],
  [/\/lib\/dirs\/[^/]+\/parents/, () => ({ parents: [] })],
  [/\/lib\/dirs\//, () => ({ dirs: [], exercises: [] })],
  [/\/v2\//, () => ({ courses: [], exercises: [], dirs: [], count: 0 })],
]

let failures = 0
const check = (ok, label) => {
  console.log(`  ${ok ? '✓' : '✗ FAIL'} ${label}`)
  if (!ok) failures++
}

/** Open the exercise, enter edit mode, dirty the title. */
async function openDirty(launch) {
  const { page } = await launch({ role: 'teacher', language: 'et', viewport: VIEWPORTS.laptop })
  const dialogs = []
  page.on('dialog', async (d) => {
    dialogs.push(d.message())
    await d.dismiss()
  })
  await fakeApi(page, handlers(), { log: false, contract: false })
  await page.goto(`${BASE_URL}/library/exercise/${EX_ID}/kahe-arvu-summa`)
  await waitUntil(async () => (await page.getByText('Kahe arvu summa').count()) > 0, { timeout: 15000 })
  await page.getByRole('button', { name: /^Muuda/i }).first().click()
  await page.waitForTimeout(700)
  await page.getByLabel(/Pealkiri/i).first().fill('Kahe arvu summa (muudetud)')
  await page.waitForTimeout(300)
  return { page, dialogs }
}

await withBrowser(async ({ launch }) => {
  // ─── 1. breadcrumb navigation asks; keep editing stays; discard leaves ──────────────────────────
  {
    const { page, dialogs } = await openDirty(launch)
    await page.getByRole('link', { name: /Ülesandekogu/i }).first().click()
    await waitUntil(async () => (await page.getByText(/Salvestamata muudatused/).count()) > 0, { timeout: 6000 }).catch(() => {})
    check((await page.getByText(/Salvestamata muudatused/).count()) > 0, 'breadcrumb click raises the discard dialog')
    check(page.url().includes(`/library/exercise/${EX_ID}`), 'the navigation is held while the dialog is open')

    await page.getByRole('button', { name: /Jätka muutmist/ }).click()
    await page.waitForTimeout(300)
    check(page.url().includes(`/library/exercise/${EX_ID}`), '"Keep editing" stays on the page')
    check(
      (await page.getByLabel(/Pealkiri/i).first().inputValue()) === 'Kahe arvu summa (muudetud)',
      'the edit survives staying',
    )

    await page.getByRole('link', { name: /Ülesandekogu/i }).first().click()
    await waitUntil(async () => (await page.getByText(/Salvestamata muudatused/).count()) > 0, { timeout: 6000 }).catch(() => {})
    await page.getByRole('button', { name: /Viska ära/ }).click()
    await waitUntil(async () => page.url().includes('/library/dir/root'), { timeout: 6000 }).catch(() => {})
    check(page.url().includes('/library/dir/root'), '"Discard" proceeds with the navigation')
    check(dialogs.length === 0, 'no native window.confirm was involved')
    await page.close()
  }

  // ─── 2. Cancel asks through the same dialog, and a clean session never asks ─────────────────────
  {
    const { page, dialogs } = await openDirty(launch)
    await page.getByRole('button', { name: /Tühista/i }).first().click()
    await waitUntil(async () => (await page.getByText(/Salvestamata muudatused/).count()) > 0, { timeout: 6000 }).catch(() => {})
    check((await page.getByText(/Salvestamata muudatused/).count()) > 0, 'Cancel raises the same dialog')
    await page.getByRole('button', { name: /Viska ära/ }).click()
    await page.waitForTimeout(400)
    check((await page.getByText('Kahe arvu summa').count()) > 0, 'discarding from Cancel restores the saved title')
    check(dialogs.length === 0, 'Cancel no longer uses window.confirm')

    // Now clean: navigation must not ask.
    await page.getByRole('link', { name: /Ülesandekogu/i }).first().click()
    await waitUntil(async () => page.url().includes('/library/dir/root'), { timeout: 6000 }).catch(() => {})
    check(page.url().includes('/library/dir/root'), 'a clean session navigates without a dialog')
    await page.close()
  }
})

console.log(failures === 0 ? '\nX-017 verification: all checks passed' : `\nX-017 verification: ${failures} FAILURES`)
process.exit(failures === 0 ? 0 : 1)
