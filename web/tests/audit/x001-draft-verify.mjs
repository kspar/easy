/**
 * Verification driver for the X-001 fix (EZ-1758): the student solution editor autosaves a draft,
 * restores it on return, and guards navigation only when the save could not land.
 *
 * Unlike the audit drivers this one *asserts* — it exists to fail loudly if the fix regresses.
 * It stays out of `tests/browser/` for the same ratchet reasons as the rest of this directory.
 *
 *   cd web && npx vite --config vite.stub.config.ts --port 5299 --strictPort &
 *   HARNESS_PORT=5299 node tests/audit/x001-draft-verify.mjs
 */
import { withBrowser, fakeApi, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import {
  COURSE_ID,
  CE_ID,
  studentCourse,
  studentExercise,
  exerciseDetails,
  submission,
  baseHandlers,
} from './fixtures.mjs'

let failures = 0
const check = (ok, label) => {
  console.log(`  ${ok ? '✓' : '✗ FAIL'} ${label}`)
  if (!ok) failures++
}

/** A stateful draft store, standing in for the server's upsert-per-student row. */
function draftStore(initial = null) {
  const state = { draft: initial, posts: [] }
  const handler = [
    new RegExp(`/exercises/${CE_ID}/draft`),
    async ({ method, body, route }) => {
      if (method === 'POST') {
        state.posts.push(body.solution)
        state.draft = { solution: body.solution, created_at: new Date().toISOString() }
        return {}
      }
      if (state.draft) return state.draft
      // GET with no draft: the real endpoint answers 204 with an empty body.
      await route.fulfill({ status: 204, body: '' })
      return undefined
    },
  ]
  return { state, handler }
}

const studentHandlers = (store, submissions) => [
  ...baseHandlers(),
  [/\/student\/courses(\?|$)/, () => ({ courses: [studentCourse()] })],
  [new RegExp(`/student/courses/${COURSE_ID}/exercises(\\?|$)`), () => ({ exercises: [studentExercise()] })],
  [new RegExp(`/exercises/${CE_ID}/submissions/all`), () => ({ submissions })],
  [new RegExp(`/exercises/${CE_ID}/activities`), () => ({ teacher_activities: [] })],
  store.handler,
  [new RegExp(`/exercises/${CE_ID}(\\?|$)`), () => exerciseDetails()],
]

const openExercise = async (page) => {
  await page.goto(`${BASE_URL}/courses/${COURSE_ID}/exercises/${CE_ID}`)
  await waitUntil(async () => (await page.locator('.cm-content').count()) > 0, { timeout: 15000 })
  await page.locator('.cm-content').click()
}

await withBrowser(async ({ launch }) => {
  // ─── 1. typing autosaves; sidebar navigation flushes silently; return restores ─────────────────
  {
    const { page } = await launch({ role: 'student', language: 'et', viewport: VIEWPORTS.laptop })
    const store = draftStore()
    await fakeApi(page, studentHandlers(store, []), { log: false })

    await openExercise(page)
    const TYPED = 'print("ei kao enam kuhugi")'
    await page.keyboard.type(TYPED)

    await waitUntil(() => store.state.posts.length > 0, { timeout: 6000 }).catch(() => {})
    check(store.state.posts.length > 0, `typing pause fires an autosave (posts: ${store.state.posts.length})`)
    check(store.state.draft?.solution === TYPED, 'the autosaved draft carries the typed content')

    const savedBadge = await page.getByText(/Mustand salvestatud/).count()
    check(savedBadge > 0, 'the editor header says the draft was saved')

    // Type more and leave immediately, inside the debounce window: the blocker must flush.
    await page.keyboard.type('\nprint("teine rida")')
    await page.getByRole('link', { name: /Minu kursused/i }).first().click()
    await waitUntil(async () => page.url().endsWith('/courses'), { timeout: 6000 }).catch(() => {})
    check(page.url().endsWith('/courses'), 'navigation proceeds without a dialog when the flush save lands')
    check(
      store.state.draft?.solution.includes('teine rida'),
      'the in-debounce delta was flushed by the navigation blocker',
    )

    // Come back: the draft is restored and labelled as such.
    await page.goto(`${BASE_URL}/courses/${COURSE_ID}/exercises/${CE_ID}`)
    await waitUntil(async () => (await page.locator('.cm-content').count()) > 0, { timeout: 15000 })
    await page.waitForTimeout(400)
    const content = (await page.locator('.cm-content').first().innerText()).trim()
    check(content.includes('ei kao enam kuhugi') && content.includes('teine rida'), 'returning restores the draft')
    check((await page.getByText(/Mustand taastatud/).count()) > 0, 'the restored draft is labelled')
    await page.close()
  }

  // ─── 2. a draft older than the latest submission is not restored over it ────────────────────────
  {
    const { page } = await launch({ role: 'student', language: 'et', viewport: VIEWPORTS.laptop })
    const store = draftStore({ solution: 'print("vana mustand")', created_at: '2026-01-01T10:00:00Z' })
    const sub = submission()
    await fakeApi(page, studentHandlers(store, [sub]), { log: false })

    await openExercise(page)
    const content = (await page.locator('.cm-content').first().innerText()).trim()
    check(content === sub.solution.trim(), 'a pre-submission draft loses to the submission')
    check((await page.getByText(/Mustand taastatud/).count()) === 0, 'no restored-draft label on submission content')
    await page.close()
  }

  // ─── 3. when the flush save fails, leaving asks — and staying stays ─────────────────────────────
  {
    const { page } = await launch({ role: 'student', language: 'et', viewport: VIEWPORTS.laptop })
    const store = draftStore()
    const handlers = studentHandlers(store, [])
    // Replace the draft handler: GET has nothing, POST breaks.
    const broken = [
      new RegExp(`/exercises/${CE_ID}/draft`),
      async ({ method, route }) => {
        if (method === 'POST') {
          await route.fulfill({ status: 500, contentType: 'application/json', body: '{}' })
        } else {
          await route.fulfill({ status: 204, body: '' })
        }
        return undefined
      },
    ]
    await fakeApi(page, handlers.map((h) => (h === store.handler ? broken : h)), { log: false })

    await openExercise(page)
    await page.keyboard.type('print("see tahaks salvestuda")')
    await page.getByRole('link', { name: /Minu kursused/i }).first().click()
    await waitUntil(async () => (await page.getByText(/Lahku salvestamata/).count()) > 0, { timeout: 6000 }).catch(() => {})
    check((await page.getByText(/Lahku salvestamata/).count()) > 0, 'a failed flush raises the leave dialog')

    await page.getByRole('button', { name: /Jää siia/ }).click()
    await page.waitForTimeout(300)
    check(page.url().includes(`/exercises/${CE_ID}`), '"Stay" keeps the student on the exercise')

    await page.getByRole('link', { name: /Minu kursused/i }).first().click()
    await waitUntil(async () => (await page.getByText(/Lahku salvestamata/).count()) > 0, { timeout: 6000 }).catch(() => {})
    await page.getByRole('button', { name: /Lahku salvestamata/ }).click()
    await waitUntil(async () => page.url().endsWith('/courses'), { timeout: 6000 }).catch(() => {})
    check(page.url().endsWith('/courses'), '"Leave without saving" leaves')
    await page.close()
  }

  // ─── 3b. when the draft READ failed, autosave stays off and leaving asks ────────────────────────
  // The server may hold a draft this session never saw; a single autosave would overwrite it.
  {
    const { page } = await launch({ role: 'student', language: 'et', viewport: VIEWPORTS.laptop })
    const store = draftStore()
    const posts = []
    const handlers = studentHandlers(store, [])
    const failing = [
      new RegExp(`/exercises/${CE_ID}/draft`),
      async ({ method, route, body }) => {
        if (method === 'POST') posts.push(body.solution)
        await route.fulfill({ status: 500, contentType: 'application/json', body: '{}' })
        return undefined
      },
    ]
    await fakeApi(page, handlers.map((h) => (h === store.handler ? failing : h)), { log: false })

    await openExercise(page)
    check(true, 'the page renders even though the draft read failed')
    await page.keyboard.type('print("kirjutan ikka")')
    await page.waitForTimeout(3000)
    check(posts.length === 0, `no autosave POST fires over an unseen server draft (posts: ${posts.length})`)

    await page.getByRole('link', { name: /Minu kursused/i }).first().click()
    await waitUntil(async () => (await page.getByText(/Lahku salvestamata/).count()) > 0, { timeout: 6000 }).catch(() => {})
    check((await page.getByText(/Lahku salvestamata/).count()) > 0, 'leaving with typed work asks instead of flushing')
    check(posts.length === 0, 'the blocked navigation did not POST either')
    await page.getByRole('button', { name: /Lahku salvestamata/ }).click()
    await page.close()
  }

  // ─── 4. submitting clears the guard: no dialog, no draft label ──────────────────────────────────
  {
    const { page } = await launch({ role: 'student', language: 'et', viewport: VIEWPORTS.laptop })
    const store = draftStore()
    let submissions = []
    const handlers = studentHandlers(store, [])
    // Live submissions list so the post-submit refetch sees the new submission.
    const live = [new RegExp(`/exercises/${CE_ID}/submissions/all`), () => ({ submissions })]
    await fakeApi(page, handlers.map((h) => (String(h[0]).includes('submissions/all') ? live : h)), { log: false })
    await page.route('**/submissions', async (route) => {
      if (route.request().method() !== 'POST') return route.fallback()
      submissions = [submission({ solution: 'print("esitatud")' })]
      await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
    })
    await page.route('**/submissions/latest/await', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }),
    )

    await openExercise(page)
    await page.keyboard.type('print("esitatud")')
    await page.getByRole('button', { name: /Esita/ }).first().click()
    await page.waitForTimeout(1000)
    await page.getByRole('link', { name: /Minu kursused/i }).first().click()
    await waitUntil(async () => page.url().endsWith('/courses'), { timeout: 6000 }).catch(() => {})
    check(page.url().endsWith('/courses'), 'after submitting, navigation is unguarded')
    await page.close()
  }
})

console.log(failures === 0 ? '\nX-001 verification: all checks passed' : `\nX-001 verification: ${failures} FAILURES`)
process.exit(failures === 0 ? 0 : 1)
