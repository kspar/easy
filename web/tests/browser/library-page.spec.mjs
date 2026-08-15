/**
 * The exercise library itself — the directory listing, its sort and filter toolbar, and the
 * per-item actions.
 *
 * 800 lines of page with no browser coverage before this. Everything the library suite tested was
 * one exercise *inside* the library; nothing opened the list that gets there.
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const ROOT = 'root'
const SUB = '77'

const dirs = {
  [ROOT]: {
    current_dir: null,
    child_dirs: [
      { id: SUB, name: 'Algoritmid', effective_access: 'PRAWM', is_shared: false,
        created_at: '2026-01-01T10:00:00.000Z', modified_at: '2026-07-01T10:00:00.000Z' },
      { id: '78', name: 'Jagatud kaust', effective_access: 'PR', is_shared: true,
        created_at: '2026-01-01T10:00:00.000Z', modified_at: '2026-06-01T10:00:00.000Z' },
    ],
    child_exercises: [
      { exercise_id: '1', dir_id: ROOT, title: 'Auto-graded, on courses', effective_access: 'PRAWM',
        is_shared: false, grader_type: 'AUTO', courses_count: 3,
        created_at: '2026-01-01T10:00:00.000Z', created_by: 'kspar',
        modified_at: '2026-07-30T10:00:00.000Z', modified_by: 'kspar' },
      { exercise_id: '2', dir_id: ROOT, title: 'Teacher-graded, shared', effective_access: 'PRAW',
        is_shared: true, grader_type: 'TEACHER', courses_count: 0,
        created_at: '2026-01-01T10:00:00.000Z', created_by: 'kspar',
        modified_at: '2026-05-01T10:00:00.000Z', modified_by: 'kspar' },
      { exercise_id: '3', dir_id: ROOT, title: 'Zebra sorting', effective_access: 'PRAWM',
        is_shared: false, grader_type: 'AUTO', courses_count: 1,
        created_at: '2026-01-01T10:00:00.000Z', created_by: 'kspar',
        modified_at: '2026-06-15T10:00:00.000Z', modified_by: 'kspar' },
    ],
  },
  [SUB]: {
    current_dir: { id: SUB, name: 'Algoritmid', effective_access: 'PRAWM', is_shared: false,
      created_at: '2026-01-01T10:00:00.000Z', modified_at: '2026-07-01T10:00:00.000Z' },
    child_dirs: [],
    child_exercises: [],
  },
}

test('library-page', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'teacher,admin', shotPrefix: 'lib-page-' })

  const posts = []
  const deletes = []
  page.on('dialog', (d) => d.accept())

  await fakeApi(page, [
    ['/account/checkin', () => ({})],
    ['/teacher/courses', () => ({ courses: [] })],
    [/\/lib\/dirs\/[^/]+\/parents/, ({ url }) => ({
      parents: url.includes(`/${SUB}/`) ? [{ id: SUB, name: 'Algoritmid' }] : [],
    })],
    // Creation posts to /lib/dirs with no id at all, so it needs its own handler ahead of the
    // id-shaped one — which would never match it.
    [/\/lib\/dirs$/, ({ method, body }) => {
      if (method === 'POST') posts.push(body)
      return { id: '99' }
    }],
    [/\/lib\/dirs\/[^/?]+(\?|$)/, ({ url, method, body }) => {
      if (method === 'POST') { posts.push(body); return { id: '99' } }
      if (method === 'DELETE') { deletes.push(url); return {} }
      const id = url.match(/\/lib\/dirs\/([^/?]+)/)?.[1]
      return dirs[id] ?? dirs[ROOT]
    }],
    [/\/exercises\/\d+$/, ({ method, url }) => {
      if (method === 'DELETE') { deletes.push(url); return {} }
      return {}
    }],
  ], { log: false })

  await page.goto(`${BASE_URL}/library`)
  await page.getByText('Auto-graded, on courses').waitFor()

  // --- the listing --------------------------------------------------------------------------
  check('child directories are listed', await page.getByText('Algoritmid').first().isVisible())
  check('and exercises alongside them', await page.getByText('Zebra sorting').isVisible())
  check(
    'an exercise used on courses says how many',
    await page.getByText('3').first().isVisible(),
  )
  await shot('01-root')

  // --- navigating into a directory --------------------------------------------------------------
  await page.getByText('Algoritmid').first().click()
  check('opening a directory navigates into it', await waitUntil(() => page.url().includes(`/library/dir/${SUB}`)))
  check('an empty directory says so', await waitUntil(() => page.getByText(/empty|tühi/i).isVisible()))
  await page.goBack()
  await page.getByText('Auto-graded, on courses').waitFor()

  // --- sorting ------------------------------------------------------------------------------------
  // The order the API returned is not the order shown: the page sorts, and remembers the choice.
  const titles = async () =>
    (await page.locator('a[href*="/library/exercise/"]').allInnerTexts()).map((s) => s.split('\n')[0].trim())

  // The sort button is labelled with the *current* mode, so it is found by whatever that is.
  const sortButton = () => page.getByRole('button', { name: /^(Name|Last modified|Popular)$/ })
  await sortButton().click()
  await page.getByRole('menuitem', { name: 'Name' }).click()
  const byName = await waitUntil(async () => {
    const t = await titles()
    return t.length >= 3 && t[0].startsWith('Auto') ? t : null
  })
  check('sorting by name orders A→Z', Boolean(byName) && byName.at(-1).startsWith('Zebra'))

  await sortButton().click()
  await page.getByRole('menuitem', { name: 'Last modified' }).click()
  const byModified = await waitUntil(async () => {
    const t = await titles()
    return t[0].startsWith('Auto') ? t : null
  })
  check('sorting by modified puts the newest first', Boolean(byModified))
  check(
    'the sort choice is remembered',
    (await page.evaluate(() => localStorage.getItem('library_sort'))) === 'modified',
  )

  // --- filtering ------------------------------------------------------------------------------------
  await page.getByRole('button', { name: /Visibility/ }).click()
  await page.getByRole('menuitem', { name: 'Shared' }).click()
  check(
    'filtering to shared hides the private ones',
    await waitUntil(async () => {
      const t = await titles()
      return t.length === 1 && t[0].startsWith('Teacher-graded')
    }),
  )
  // The chip is labelled with the active filter once one is chosen, not with the category.
  await page.getByRole('button', { name: 'Shared' }).click()
  await page.getByRole('menuitem', { name: 'All' }).first().click()
  await waitUntil(async () => (await titles()).length === 3)

  await page.getByRole('button', { name: /Tests/ }).click()
  await page.getByRole('menuitem', { name: 'Without tests' }).click()
  check(
    'filtering by grader keeps only teacher-graded',
    await waitUntil(async () => {
      const t = await titles()
      return t.length === 1 && t[0].startsWith('Teacher-graded')
    }),
  )
  await shot('02-filtered')

  // --- creating -------------------------------------------------------------------------------------
  await page.getByRole('button', { name: /New directory/ }).click()
  const dialog = page.getByRole('dialog')
  await dialog.waitFor()
  await dialog.getByRole('textbox').fill('Uus kaust')
  await dialog.getByRole('button', { name: /Create|Add|Save/ }).first().click()
  check(
    'creating a directory posts its name',
    await waitUntil(() => posts.some((p) => p?.name === 'Uus kaust')),
  )

  await close()
})
