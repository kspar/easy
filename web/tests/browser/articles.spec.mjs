/**
 * Articles: the admin index, the public view page, editing, aliases and delete.
 *
 * The feature had a backend since 2023 and never had a page, so all of this is new surface. The two
 * behaviours worth watching are that a published article renders for someone with no session — the
 * whole reason `/a/<alias>` is short enough to write on a slide — and that admin-only affordances
 * key off the *response* rather than the role, because core omits `published` and `aliases` for
 * everyone else.
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const ID = '7'

const published = {
  id: ID,
  title: 'Frequently asked questions',
  created_at: '2026-01-01T10:00:00.000Z',
  last_modified: '2026-08-01T12:00:00.000Z',
  owner: { id: 'kspar', given_name: 'Kaspar', family_name: 'Papli' },
  author: { id: 'kspar', given_name: 'Kaspar', family_name: 'Papli' },
  text_html: '<p>How do I <strong>log in</strong>?</p>',
  text_md: 'How do I **log in**?',
  published: true,
  aliases: [{ id: 'faq', created_at: '2026-01-01T10:00:00.000Z', created_by: 'kspar' }],
}

/** What core returns to a reader with no session: no source, no usernames, no admin fields. */
const anonymous = {
  id: ID,
  title: published.title,
  created_at: published.created_at,
  last_modified: published.last_modified,
  owner: { given_name: 'Kaspar', family_name: 'Papli' },
  author: { given_name: 'Kaspar', family_name: 'Papli' },
  text_html: published.text_html,
}

const list = [
  { id: ID, title: published.title, aliases: ['faq'], created_at: published.created_at, last_modified: published.last_modified, published: true },
  { id: '8', title: 'Zzz unfinished guide', aliases: [], created_at: published.created_at, last_modified: published.last_modified, published: false },
]

// `browser` as well as `launch`: the last section opens a context with no session seeded at all,
// which is the one thing launch() cannot give you — it always writes a stubRole.
test('articles', async ({ launch, check, browser }) => {
  const { page, shot, close } = await launch({ role: 'teacher,admin', shotPrefix: 'articles-' })

  const puts = []
  const posted = []
  const deleted = []

  await fakeApi(page, [
    ['/account/checkin', () => ({})],
    ['/teacher/courses', () => ({ courses: [] })],
    ['/preview/markdown', ({ body }) => ({ content: `<p>PREVIEW:${body.content}</p>` })],
    ['/articles/7/aliases/faq', ({ method }) => { if (method === 'DELETE') deleted.push('alias:faq'); return {} }],
    ['/articles/7/aliases', ({ method, body }) => { if (method === 'POST') posted.push(body); return {} }],
    [
      '/unauth/articles/faq',
      () => anonymous,
    ],
    [
      '/articles/faq',
      ({ method, body }) => {
        if (method === 'PUT') { puts.push(body); return {} }
        return published
      },
    ],
    // The app addresses an article by id once it has loaded one, so writes land here rather than on
    // the alias path it was fetched by.
    [
      '/articles/7',
      ({ method, body }) => {
        if (method === 'DELETE') { deleted.push('article:7'); return {} }
        if (method === 'PUT') { puts.push(body); return {} }
        return published
      },
    ],
    ['/articles', ({ method, body }) => {
      if (method === 'POST') { posted.push(body); return { id: '9' } }
      return { articles: list }
    }],
  ], { log: false })

  // --- the admin index ------------------------------------------------------------------------------

  // launch() pins activeRole to 'teacher'; added after it, so this wins, and it re-runs on every
  // navigation in a way that setting localStorage after goto does not survive.
  await page.addInitScript(() => localStorage.setItem('activeRole', 'admin'))
  await page.goto(`${BASE_URL}/articles`)

  // Wait for content, not for the absence of a spinner: the spinner has not necessarily been
  // rendered yet when the check runs, so "no spinner" is true before the fetch even starts.
  check(
    'the index lists articles',
    await waitUntil(() => page.getByText('Frequently asked questions').first().isVisible()),
  )
  check('a draft is marked as one', await page.getByText('Zzz unfinished guide').first().isVisible())
  const body = () => page.locator('body').innerText().then((s) => s.replace(/\s+/g, ' '))
  check('drafts carry a chip', (await body()).includes('Draft'))
  check('and the alias is shown as its address', (await body()).includes('/a/faq'))
  check('the nav offers Articles to an admin', await page.getByRole('link', { name: 'Articles' }).first().isVisible())
  await shot('01-index')

  // --- the view page, signed in as admin --------------------------------------------------------------

  await page.goto(`${BASE_URL}/a/faq`)
  check(
    'the article renders its html',
    await waitUntil(() => page.getByText('How do I log in?').first().isVisible()),
  )
  check('an admin is offered Edit', await waitUntil(() => page.getByRole('button', { name: 'Edit' }).isVisible()))
  check(
    'but not Delete, because it is published',
    (await page.getByRole('button', { name: 'Delete' }).count()) === 0,
  )

  // --- editing ----------------------------------------------------------------------------------------

  await page.getByRole('button', { name: 'Edit' }).click()
  check('the editor opens with the Markdown source', await waitUntil(async () =>
    (await page.locator('.cm-content').first().innerText()).includes('How do I **log in**?')))
  check('and the aliases are editable', await page.getByText('/a/faq').first().isVisible())

  await page.locator('.cm-content').first().click()
  await page.keyboard.press('ControlOrMeta+a')
  await page.keyboard.type('Rewritten')
  check('the preview follows what is typed', await waitUntil(() =>
    page.getByText('PREVIEW:Rewritten').first().isVisible()))
  await shot('02-editing')

  await page.getByRole('button', { name: 'Save' }).click()
  check('saving sends the draft', await waitUntil(() => puts.length === 1), JSON.stringify(puts[0] ?? {}))
  check('with the edited text', (puts[0]?.text_md ?? '').includes('Rewritten'))
  check('and the published flag', puts[0]?.published === true)

  // --- aliases ------------------------------------------------------------------------------------------

  await page.getByRole('button', { name: 'Edit' }).click()
  await waitUntil(() => page.getByPlaceholder('faq').isVisible())
  const aliasBox = page.getByPlaceholder('how-to-log-in')
  // All digits is the one spelling that must stay rejected: the read path resolves an alias before
  // falling back to a numeric id, so `2023` would shadow the article with that id.
  await aliasBox.fill('2023')
  await page.getByRole('button', { name: 'Add' }).click()
  check(
    'an all-digit alias is refused before it is sent',
    await waitUntil(() => page.getByText(/at least one letter/).isVisible()) && posted.length === 0,
  )

  await aliasBox.fill('how-to-log-in')
  await page.getByRole('button', { name: 'Add' }).click()
  check('a hyphenated alias is accepted', await waitUntil(() => posted.length === 1), JSON.stringify(posted[0] ?? {}))
  check('with the alias', posted[0]?.alias === 'how-to-log-in')

  // --- the public view, with no session at all -------------------------------------------------------

  const anonCtx = await browser.newContext()
  const anonPage = await anonCtx.newPage()
  await fakeApi(anonPage, [
    ['/unauth/articles/faq', () => anonymous],
    ['/articles/faq', () => ({ error: 'should not be called' })],
  ], { log: false })
  // stubAuth = 'none' is what makes the keycloak stub report an unauthenticated visitor; clearing
  // stubRole only changes which roles a signed-in user has.
  await anonPage.addInitScript(() => {
    localStorage.setItem('language', 'en')
    localStorage.setItem('stubAuth', 'none')
  })
  await anonPage.goto(`${BASE_URL}/a/faq`)
  check(
    'a signed-out visitor can read a published article',
    await waitUntil(() => anonPage.getByText('How do I log in?').first().isVisible()),
  )
  const anonBody = (await anonPage.locator('body').innerText()).replace(/\s+/g, ' ')
  check('and is offered no Edit button', !anonBody.includes('Edit'))
  await anonPage.screenshot({ path: new URL('../screenshots/articles-03-anonymous.png', import.meta.url).pathname })
  await anonCtx.close()

  await close()
})
