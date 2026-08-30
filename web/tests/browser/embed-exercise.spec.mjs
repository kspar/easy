/**
 * The embedded exercise page: the URL scheme it has to honour, and the things that make it
 * different from every other page in the app — no auth, forced light theme, self-reported height.
 *
 * The URL scheme is wui's, and the reason it is tested rather than tidied is that embeds already
 * published in PmWiki pages carry these exact URLs. A test that accepts `?showTitle=false` would
 * be testing something nobody has deployed.
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const ID = '4242'

const AUTO_EXERCISE = {
  title: 'Sum of two numbers',
  text_html: '<p>Read two numbers and print their sum.</p>',
  anonymous_autoassess_template: '# your code here\n',
  submit_allowed: true,
}

let exercise = { ...AUTO_EXERCISE }
let lastSolution = null

test('embed-exercise', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ shotPrefix: 'embed-' })

  // Installed before any navigation: a listener added with page.evaluate dies with the document it
  // was added to, which silently turns "no messages were posted" into a test that cannot fail.
  await page.addInitScript(() => {
    window.__resizeMessages = []
    window.addEventListener('message', (e) => window.__resizeMessages.push(e.data))
  })

  /** Authorization headers seen on the anonymous endpoints, so the no-auth claim is checked. */
  const authHeaders = []

  await fakeApi(
    page,
    [
      [
        `/unauth/exercises/${ID}/anonymous/details`,
        ({ route }) => {
          authHeaders.push(route.request().headers()['authorization'] ?? null)
          return exercise
        },
      ],
      [
        `/unauth/exercises/${ID}/anonymous/autoassess`,
        ({ route, body }) => {
          authHeaders.push(route.request().headers()['authorization'] ?? null)
          lastSolution = body.solution
          // Plain text, deliberately: this is the legacy graders' real answer shape (aae's
          // 'grade:' format) and mock-executor's, and it must keep rendering as an assessment.
          return { grade: 100, feedback: 'All tests passed.' }
        },
      ],
    ],
    { log: false },
  )

  const embedUrl = (query = '') => `${BASE_URL}/embed/exercises/${ID}/Sum%20of%20two%20numbers${query}`
  // The page's own outer Box, which is a <main> since EZ-1799 gave the two shell-less routes a
  // landmark — so this doubles as the assertion that the landmark is still there.
  const container = page.locator('#root > main')
  const editor = page.locator('.cm-content')

  async function open(query = '') {
    await page.goto(embedUrl(query))
    await page.getByText('Read two numbers').waitFor()
  }

  // --- the default embed ---------------------------------------------------------------------
  await open()
  check('exercise text renders', await page.getByText('Read two numbers and print their sum.').isVisible())
  check('title shows by default', await page.getByText('Sum of two numbers').isVisible())
  check('no editor without the submit flag', (await editor.count()) === 0)
  check(
    'the page is bordered by default',
    (await container.evaluate((el) => getComputedStyle(el).borderTopWidth)) !== '0px',
  )
  check('footer links back to the exercise', await page.getByRole('link', { name: `#${ID}` }).isVisible())
  await shot('01-default')

  // --- it must not authenticate ------------------------------------------------------------------
  // An exercise embedded in someone else's page has no user, and sending a bearer token from an
  // unrelated Lahendus session would be worse than sending none.
  check('the details call carried no Authorization header', authHeaders.length > 0 && authHeaders.every((h) => h === null))

  // --- the negative flags --------------------------------------------------------------------
  await open('?no-title')
  check('no-title hides the title', (await page.getByRole('heading', { name: 'Sum of two numbers' }).count()) === 0)
  check('but the text is still there', await page.getByText('Read two numbers').isVisible())

  await open('?no-border')
  check(
    'no-border removes the border',
    (await container.evaluate((el) => getComputedStyle(el).borderTopWidth)) === '0px',
  )

  // --- submitting ------------------------------------------------------------------------------
  await open('?submit')
  await editor.waitFor()
  check('submit adds an editor', (await editor.count()) === 1)
  check('the template pre-fills it', (await editor.innerText()).includes('# your code here'))

  await open('?submit&no-template')
  await editor.waitFor()
  // Not `innerText === ''`: CodeMirror renders the placeholder inside .cm-content, so an empty
  // editor still reads as "Write your solution here...".
  check('no-template leaves the editor empty', !(await editor.innerText()).includes('# your code here'))

  await open('?submit')
  await editor.waitFor()
  await editor.click()
  await page.keyboard.press('ControlOrMeta+a')
  await page.keyboard.type('print(a + b)')
  await page.getByRole('button', { name: 'Submit' }).click()
  check('the grader result renders', await waitUntil(() => page.getByText('All tests passed.').isVisible()))
  check('the typed solution was sent', lastSolution === 'print(a + b)')
  await shot('02-submitted')

  // --- an exercise nobody can submit to ---------------------------------------------------------
  exercise = { ...AUTO_EXERCISE, submit_allowed: false, anonymous_autoassess_template: '' }
  await open('?submit')
  check(
    'asking to submit on a teacher-graded exercise explains itself',
    await page.getByText(/graded by a teacher/).isVisible(),
  )
  check('and offers no editor', (await editor.count()) === 0)
  await shot('03-no-autograde')

  // --- height reporting --------------------------------------------------------------------------
  // A top-level page's `window.parent` is itself, so the message it posts to the parent is
  // observable right here. The shape is fixed by the resizer script already living in published
  // pages: it JSON.parses the payload, matches on `type`, and finds the iframe by exact `src`.
  exercise = { ...AUTO_EXERCISE }
  await open()
  const messages = await waitUntil(
    async () => {
      const msgs = await page.evaluate(() => window.__resizeMessages ?? [])
      return msgs.length ? msgs : null
    },
    { timeout: 5000 },
  )
  const parsed = (messages ?? []).map((m) => { try { return JSON.parse(m) } catch { return null } }).filter(Boolean)
  const resize = parsed.find((m) => m.type === 'ez-frame-resize')
  check('it posts a resize message', Boolean(resize))
  check('with a positive height', typeof resize?.height === 'number' && resize.height > 0)
  check('and its own url, for the parent to match the iframe by', resize?.url?.includes(`/embed/exercises/${ID}/`))

  // --- no-dynamic-resize opts out ------------------------------------------------------------
  await open('?no-dynamic-resize')
  await page.waitForTimeout(600)
  const silent = await page.evaluate(() =>
    (window.__resizeMessages ?? []).filter((m) => String(m).includes('ez-frame-resize')),
  )
  check('no-dynamic-resize posts nothing', silent.length === 0)

  // --- the value-carrying options ---------------------------------------------------------------
  await open('?title-alias=Warm-up')
  check('title-alias replaces the title', await page.getByText('Warm-up').isVisible())
  check('and the real title is gone', (await page.getByText('Sum of two numbers').count()) === 0)

  await open('?course=119&exercise=4147')
  const courseLink = page.getByRole('link', { name: /Sum of two numbers\s+Lahendus/ })
  check('a course link renders when both ids are given', await courseLink.isVisible())
  check(
    'and points at the course exercise',
    (await courseLink.getAttribute('href')) === '/courses/119/exercises/4147',
  )
  await open('?course=119')
  check('but not from a course id alone', (await page.getByRole('link', { name: /Sum of two numbers\s+Lahendus/ }).count()) === 0)

  // --- URL shapes that exist in production right now ---------------------------------------------
  // Taken verbatim from live PmWiki embeds. They predate wui commit 9b995488 (Sept 2023), which
  // swapped the opt-in flags for opt-out ones without back-compat, so `title` and `dynamic-resize`
  // here are names nothing reads any more — the defaults just happen to agree. What matters is that
  // these keep rendering, including the two shapes the newer generator never produces: no title slug
  // at all, and a slug with dots and non-ASCII in it.
  for (const [label, path, expectEditor] of [
    ['no title slug, pre-2023 flags', `/embed/exercises/${ID}?title&dynamic-resize&course=119&exercise=4147`, false],
    ['dotted non-ASCII slug', `/embed/exercises/${ID}/Koduülesanne-3.1.-Hinde-kujunemine?submit&course=148&exercise=5944`, true],
  ]) {
    await page.goto(BASE_URL + path)
    await page.getByText('Read two numbers').waitFor()
    check(`live URL routes: ${label}`, true)
    check(
      `live URL keeps its title: ${label}`,
      await page.getByText('Sum of two numbers').first().isVisible(),
    )
    check(
      `live URL editor presence is right: ${label}`,
      ((await editor.count()) > 0) === expectEditor,
    )
    check(
      `live URL renders the course link: ${label}`,
      (await page.getByRole('link', { name: /Sum of two numbers\s+Lahendus/ }).count()) === 1,
    )
  }

  // --- the visitor's theme -------------------------------------------------------------------------
  // Chosen by whoever is reading the embed, not by the teacher who pasted it, and shared by every
  // embed on the page. Tested before anything is stored, because a stored choice overrules the OS.
  const bodyBg = (frameUrlPart) =>
    page.frames().find((f) => f.url().includes(frameUrlPart))
      ?.evaluate(() => getComputedStyle(document.body).backgroundColor)

  await page.evaluate(() => localStorage.removeItem('embedTheme'))
  await page.emulateMedia({ colorScheme: 'dark' })
  await open()
  check('with nothing stored it follows the OS into dark', (await bodyBg('/embed/')) === 'rgb(18, 18, 18)')
  await page.emulateMedia({ colorScheme: 'light' })
  await open()
  check('and back into light', (await bodyBg('/embed/')) === 'rgb(245, 245, 245)')

  // Two embeds on one page: separate iframes, separate React trees, no direct channel between them.
  // They share an origin, so the choice travels by localStorage and its `storage` event.
  //
  // The host page must be built from BASE_URL, not a hardcoded port. Sharing an origin with the
  // iframes is the entire mechanism under test — pin the host to :5199 while HARNESS_PORT moves the
  // iframes elsewhere and the two are cross-origin, localStorage throws "Access is denied", and the
  // failure surfaces as an unrelated TypeError further down. It passed for a year because the
  // default port happened to match.
  const HOST_URL = `${BASE_URL}/host`
  await page.route(HOST_URL, (r) => r.fulfill({
    contentType: 'text/html',
    body: `<html><body style="margin:0">
      <iframe src="${BASE_URL}/embed/exercises/${ID}/a" width="100%" height="300"></iframe>
      <iframe src="${BASE_URL}/embed/exercises/${ID}/b" width="100%" height="300"></iframe>
    </body></html>`,
  }))
  await page.goto(HOST_URL)
  await waitUntil(async () => page.frames().filter((f) => f.url().includes('/embed/')).length === 2)
  await waitUntil(async () => (await bodyBg('/embed/exercises/' + ID + '/b')) != null)

  const first = page.frames().find((f) => f.url().includes(`/embed/exercises/${ID}/a`))
  // Asserted rather than left to throw: when this came back undefined the script died on
  // "Cannot read properties of undefined", which pointed at the click instead of the missing frame
  // and hid a plain origin mismatch for as long as nobody ran on a non-default port.
  check('the first embed is reachable as a frame', first !== undefined, page.frames().map((f) => f.url()).join(' | '))

  if (first) {
    await first.getByRole('button', { name: /Switch to/ }).click()
    check(
      'toggling in one embed switches the other one too',
      await waitUntil(async () => (await bodyBg(`/embed/exercises/${ID}/b`)) === 'rgb(18, 18, 18)'),
    )
    check(
      'and the choice is remembered',
      (await first.evaluate(() => localStorage.getItem('embedTheme'))) === 'dark',
    )
  }

  await close()
})
