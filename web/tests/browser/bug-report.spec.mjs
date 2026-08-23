// Reporting a bug from the account menu: the dialog, the consent checkbox, and the promise that
// what the reporter is shown is what gets sent.
//
//   cd web && npx playwright test bug-report
import { test } from '../support/spec.mjs'
import { fakeApi, BASE_URL, waitUntil, json } from '../support/harness.mjs'

/** The error envelope core returns, with an id a developer could grep its log for. */
const KNOWN_ERROR_ID = 'e7c1a9d0-known-error-id'

test('bug-report', async ({ launch, check }) => {
  const BUG_EP = '/bug-reports'

  /**
   * Open the app with the bug-report endpoint recorded.
   *
   * `failingCall` makes the courses request answer 400 with an error body carrying a known id. That
   * is not scenery: core mints that id into its own log line and into this response, so a report
   * carrying it is the difference between "a teacher says grades are broken" and a grep key. The
   * spec below asserts the id survives into the payload, which is the only way to tell a working
   * capture path from an empty one — an activity log that is always blank looks exactly like one
   * that is working and had nothing to say.
   */
  async function openApp({ failingCall = false } = {}) {
    const { page, shot, close } = await launch({ shotPrefix: 'bug-report-' })
    const posted = []
    await fakeApi(
      page,
      [
        [
          BUG_EP,
          ({ method, body }) => {
            if (method === 'POST') {
              posted.push(body)
              return { id: '4242' }
            }
            return {}
          },
        ],
        ['/account/checkin', () => ({})],
        [
          '/courses',
          ({ route }) =>
            failingCall
              ? // Fulfilled here rather than returned, because a returned value is sent as a 200 —
                // the handler has to fulfil the route itself to produce a failure.
                json(
                  route,
                  {
                    id: KNOWN_ERROR_ID,
                    code: 'INVALID_PARAMETER_VALUE',
                    attrs: {},
                    log_msg: 'deliberate failure',
                  },
                  400,
                )
              : { courses: [] },
        ],
        ['/management/common/notifications', () => ({ messages: [] })],
      ],
      { log: false },
    )
    await page.goto(`${BASE_URL}/courses`)
    await waitUntil(async () => (await page.locator('header').count()) > 0)
    return { page, shot, close, posted }
  }

  /** Open the account menu and pick "Report a bug". */
  async function openDialog(page) {
    await page.getByRole('button', { name: 'Account menu' }).click()
    await waitUntil(async () => (await page.locator('[role=menu]').count()) > 0)
    await page.getByRole('menuitem', { name: 'Report a bug' }).click()
    await waitUntil(async () => (await page.locator('[role=dialog]').count()) > 0)
    // And wait for the menu to actually go. Both it and the dialog animate, and a screenshot taken
    // between the two catches the menu still fading out over a dialog still fading in — which is
    // what the 02-disclosure screenshot showed for its first two runs.
    await page.locator('[role=dialog]').waitFor({ state: 'visible' })
    await waitUntil(async () => (await page.locator('[role=menu]').count()) === 0)
  }

  // --- the dialog is reachable, and asks one thing ---------------------------------------------------
  {
    const { page, shot, close, posted } = await openApp()
    await openDialog(page)

    const dialog = page.locator('[role=dialog]')
    check('the dialog opens from the account menu', (await dialog.count()) === 1)
    check('it asks what went wrong', (await dialog.innerText()).includes('What went wrong?'))

    const send = page.getByRole('button', { name: 'Send' })
    check('send is disabled with an empty box', await send.isDisabled())
    // Whitespace is not a bug report. The server would refuse it with @NotBlank, so the button
    // should refuse it first rather than trading a round trip for a generic error.
    await dialog.locator('textarea').first().fill('   ')
    check('send stays disabled for whitespace only', await send.isDisabled())

    await dialog.locator('textarea').first().fill('Grades page is empty')
    check('send enables once there is text', await send.isEnabled())
    await shot('01-dialog')
    await close()
    check('nothing was posted while just looking', posted.length === 0)
  }

  // --- consent starts on, and is visible ------------------------------------------------------------
  {
    const { page, shot, close } = await openApp()
    await openDialog(page)
    const dialog = page.locator('[role=dialog]')

    const consent = page.getByRole('checkbox', { name: 'Include my recent activity' })
    // Pre-checked on purpose: unticked-by-default produces reports with no diagnostics at all, which
    // is the situation this feature exists to replace. The disclosure below is what makes that fair.
    check('the activity checkbox starts checked', await consent.isChecked())
    check(
      'and the reporter is offered the exact text',
      (await dialog.innerText()).includes('See exactly what will be sent'),
    )

    await page.getByRole('button', { name: 'See exactly what will be sent' }).click()
    // Waited for, not just clicked. The first version of this shot fired during the expand
    // animation, so the screenshot showed a collapsed panel — and since the only assertion was that
    // the *summary* text existed, a disclosure that never opened would have passed.
    // `state: 'visible'`, not a count. MUI's Collapse keeps its children mounted at height 0, so the
    // `pre` is in the DOM — and readable by innerText — while the panel is still shut. A count-based
    // wait therefore returns instantly and proves nothing about the disclosure opening.
    await dialog.locator('pre').waitFor({ state: 'visible' })

    // And `.length > 0` would not do either: the empty state renders "Nothing has been recorded in
    // this tab yet", which is also text. Asserting the route we actually visited proves the buffer
    // is being read rather than that a box exists.
    const shown = await dialog.locator('pre').innerText()
    check(`expanding it reveals real activity (${shown.split('\n')[0]})`, shown.includes('/courses'))
    await shot('02-disclosure')
    await close()
  }

  // --- what is shown is what is sent ----------------------------------------------------------------
  {
    // A failing API call first, so there is something in the buffer worth asserting on.
    const { page, shot, close, posted } = await openApp({ failingCall: true })
    await openDialog(page)
    const dialog = page.locator('[role=dialog]')

    await page.getByRole('button', { name: 'See exactly what will be sent' }).click()
    await waitUntil(async () => (await dialog.locator('pre').count()) > 0)
    const shown = await dialog.locator('pre').innerText()

    await dialog.locator('textarea').first().fill('Courses page will not load')
    await page.getByRole('button', { name: 'Send' }).click()
    await waitUntil(() => posted.length > 0)

    const sent = posted[0]
    check(`posted once (${posted.length})`, posted.length === 1)
    check('the typed message is sent', sent?.message === 'Courses page will not load')

    // The promise the disclosure makes. Not "similar to" — the same string, because a checkbox next
    // to a summary of what will be sent is not consent.
    check('the diagnostics sent are exactly what was shown', sent?.diagnostics === shown)

    // The claim that makes this feature worth building: core's error id reaches the report.
    check(
      `the failed call's error id is in the payload`,
      (sent?.diagnostics ?? '').includes(KNOWN_ERROR_ID),
    )
    check('the page the reporter was on is sent', sent?.page_url === '/courses')
    check('the web build is sent', typeof sent?.web_version === 'string' && sent.web_version.length > 0)
    await shot('03-sent')
    await close()
  }

  // --- declining sends no activity at all -----------------------------------------------------------
  {
    const { page, shot, close, posted } = await openApp({ failingCall: true })
    await openDialog(page)
    const dialog = page.locator('[role=dialog]')

    await page.getByRole('checkbox', { name: 'Include my recent activity' }).uncheck()
    check(
      'the disclosure goes away with the consent',
      !(await dialog.innerText()).includes('See exactly what will be sent'),
    )

    await dialog.locator('textarea').first().fill('Not telling you what I did')
    await page.getByRole('button', { name: 'Send' }).click()
    await waitUntil(() => posted.length > 0)

    // Absent, not empty. Core stores null and reads null as "declined" rather than "had nothing" —
    // sending '' here would throw away the distinction the column exists for.
    check('no diagnostics key is sent when declined', !('diagnostics' in (posted[0] ?? {})))
    check('the message still is', posted[0]?.message === 'Not telling you what I did')
    await shot('04-declined')
    await close()
  }

  // --- the rate limit reads as a wait, not as a breakage --------------------------------------------
  {
    const { page, shot, close } = await launch({ shotPrefix: 'bug-report-' })
    await fakeApi(
      page,
      [
        [
          BUG_EP,
          ({ route }) =>
            json(
              route,
              {
                id: 'x',
                code: 'BUG_REPORT_RATE_LIMITED',
                attrs: { max_per_hour: '10' },
                log_msg: 'too many',
              },
              400,
            ),
        ],
        ['/account/checkin', () => ({})],
        ['/courses', () => ({ courses: [] })],
        ['/management/common/notifications', () => ({ messages: [] })],
      ],
      { log: false },
    )
    await page.goto(`${BASE_URL}/courses`)
    await waitUntil(async () => (await page.locator('header').count()) > 0)
    await openDialog(page)
    const dialog = page.locator('[role=dialog]')

    await dialog.locator('textarea').first().fill('Eleventh report of the hour')
    await page.getByRole('button', { name: 'Send' }).click()
    await waitUntil(async () => (await dialog.locator('[role=alert]').count()) > 0)

    const alert = await dialog.locator('[role=alert]').innerText()
    // Told to come back later, rather than the generic "something went wrong" — the report is fine,
    // there have just been several of them.
    check('the rate limit says to try later', alert.includes('later'))
    check('and the dialog stays open with the text still in it', (await dialog.count()) === 1)
    check(
      'the typed message is not thrown away',
      (await dialog.locator('textarea').first().inputValue()) === 'Eleventh report of the hour',
    )
    await shot('05-rate-limited')
    await close()
  }
})
