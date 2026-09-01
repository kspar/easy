// EZ-1709: the About page says what is deployed — web's version from the bundle, core's and the
// executors' from the server, and something honest when an executor is not answering.
//
// EZ-1781 added a row per grading image under its executor, showing the version actually
// installed and warning when that disagrees with what was declared.
//
// EZ-1782 made the whole block teacher-and-admin. It was public for a year, so the checks that
// used to prove it was readable with no account now prove the opposite.
//
//   cd web && npx playwright test about-versions
import { test } from '../support/spec.mjs'
import { fakeApi, BASE_URL } from '../support/harness.mjs'

test('about-versions', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ shotPrefix: 'about-versions-' })

  const VERSIONS = {
    core: { version: '4.0', commit: 'abc1234', built_at: '2026-08-10T09:26:52.903Z' },
    executors: [
      {
        name: 'executor-1',
        version: '4.0',
        commit: 'abc1234',
        built_at: '2026-08-09T18:05:00Z',
        reachable: true,
        grading_images: [
          // The ordinary case: declared and installed agree, so one number is shown.
          {
            name: 'silmused',
            created_at: '2026-08-12T11:14:00Z',
            source: 'label',
            libraries: [{ name: 'silmused', declared: '1.7.11', installed: '1.7.11' }],
          },
          // The case worth shouting about, and the one that was invisible before: the image contains
          // something other than what was asked for. This is the real August 2026 state.
          {
            name: 'tiivad',
            created_at: '2026-08-12T11:03:00Z',
            source: 'label',
            libraries: [{ name: 'tiivad', declared: '0.0.33', installed: '0.0.30' }],
          },
          // Two libraries and no library of its own name — the real shape of imgrec, and the case
          // where dropping the library names from the row would make it a lie.
          {
            name: 'imgrec',
            created_at: '2026-08-12T11:20:00Z',
            source: 'label',
            libraries: [
              { name: 'pillow', declared: '12.3.0', installed: '12.3.0' },
              { name: 'requests', declared: '2.34.2', installed: '2.34.2' },
            ],
          },
          // Nothing knowable. The build date still is, and it answers "was this ever rebuilt?".
          {
            name: 'pygrader',
            created_at: '2024-03-12T08:20:00Z',
            source: 'unknown',
            libraries: [],
          },
        ],
      },
      // Registered but silent. Rendering it is the point: an executor that is down is exactly what
      // someone reading this page needs to see. It gets no image rows — a stale list would read as
      // current.
      { name: 'executor-2', version: null, commit: null, built_at: null, reachable: false, grading_images: [] },
    ],
  }

  let versionsStatus = 200
  const versionsCalls = []

  await fakeApi(
    page,
    [
      ['/account/checkin', () => ({})],
      ['/statistics/common', () => ({ in_auto_assessing: 1, total_submissions: 2, total_users: 3 })],
      [
        '/versions',
        ({ route }) => {
          versionsCalls.push(route.request().headers()['authorization'] ?? null)
          if (versionsStatus !== 200) {
            route.fulfill({ status: versionsStatus, contentType: 'application/json', body: '{}' })
            return
          }
          return VERSIONS
        },
      ],
    ],
    { log: false },
  )

  /**
   * The versions block, as `{ label: value }` — it renders as a <dl>.
   *
   * Waits on the <dl> rather than on the text "Versions": the failure caption contains the word too,
   * so a text locator matches two elements and dies on strict mode in exactly the case being tested.
   */
  async function readVersions() {
    await page.locator('dl').first().waitFor()
    return await page.evaluate(() => {
      const dl = document.querySelector('dl')
      if (!dl) return {}
      const out = {}
      for (const dt of dl.querySelectorAll('dt')) {
        const version = dt.nextElementSibling
        const builtAt = version ? version.nextElementSibling : null
        out[dt.textContent.trim()] = {
          version: version ? version.textContent.trim() : null,
          builtAt: builtAt ? builtAt.textContent.trim() : null,
        }
      }
      return out
    })
  }

  /**
   * The same block as an ordered array, with indentation.
   *
   * Separate from readVersions() rather than replacing it: that returns a map keyed by label, and
   * two executors can each have an image called `tiivad`, so a map silently loses one. The existing
   * checks below read the map and are left alone.
   */
  async function readVersionRows() {
    await page.locator('dl').first().waitFor()
    return await page.evaluate(() => {
      const dl = document.querySelector('dl')
      if (!dl) return []
      return [...dl.querySelectorAll('dt')].map((dt) => {
        const version = dt.nextElementSibling
        const builtAt = version ? version.nextElementSibling : null
        return {
          name: dt.textContent.trim(),
          value: version ? version.textContent.trim() : null,
          builtAt: builtAt ? builtAt.textContent.trim() : null,
          // The nesting is expressed as padding on the <dt>, since a nested <dl> would break the
          // three-column grid.
          indented: parseFloat(getComputedStyle(dt).paddingLeft) > 0,
        }
      })
    })
  }

  await page.goto(`${BASE_URL}/about`)
  const rows = await readVersions()

  // web's version is compiled in by Vite's define, so this is also the check that the define
  // survived the build rather than leaving the literal `__APP_VERSION__` in the bundle.
  check(`web reports its own version (${rows.web?.version})`, /^v\d/.test(rows.web?.version ?? ''))
  check(
    'web version carries a commit',
    /^v[\w.]+ \([0-9a-f]{7}\)$/.test(rows.web?.version ?? '') || /unknown/.test(rows.web?.version ?? ''),
  )
  check(`core comes from the server (${rows.core?.version})`, rows.core?.version === 'v4.0 (abc1234)')
  check(`a reachable executor shows its version (${rows['executor-1']?.version})`, rows['executor-1']?.version === 'v4.0 (abc1234)')
  check(
    `an unreachable executor says so rather than vanishing (${rows['executor-2']?.version})`,
    (rows['executor-2']?.version ?? '').includes('not responding'),
  )

  // --- build times ----------------------------------------------------------------------------------
  // `10 Aug 2026, 12:26` — the shared format from i18n/dateLocale.ts. This used to assert
  // `dd/MM/yyyy HH:mm` on the stated grounds that it was "British order like every other date in the
  // app"; it was neither British nor like the rest, and slashes are not how Estonian writes a date
  // (EZ-1870).
  //
  // Asserted as a shape rather than an exact string, because these render in the viewer's timezone
  // and a fixed expectation would pass in Tartu and fail in CI — but the shape is the *English* one,
  // with no dot after the day. `launch` puts the app in English (see harness.mjs), so Estonian
  // leaking through the locale wiring would render "10. aug" and fail here. A pattern that accepted
  // either language would have let exactly the bug EZ-1870 is about walk straight past.
  const DATE_TIME = /^\d{1,2} \p{L}+ \d{4}, \d{2}:\d{2}$/u
  check(`web has a build time (${rows.web?.builtAt})`, DATE_TIME.test(rows.web?.builtAt ?? ''))
  check(`core's build time is rendered (${rows.core?.builtAt})`, DATE_TIME.test(rows.core?.builtAt ?? ''))
  check(
    `the executor's is too (${rows['executor-1']?.builtAt})`,
    DATE_TIME.test(rows['executor-1']?.builtAt ?? ''),
  )
  // 2026-08-10T09:26:52Z is the 10th in every timezone this app is read in, so the day is safe to
  // assert even though the hour is not.
  check(
    `core's date matches what the server sent (${rows.core?.builtAt})`,
    /^10 \p{L}+ 2026,/u.test(rows.core?.builtAt ?? ''),
  )
  check(
    'an unreachable executor gets no invented timestamp',
    (rows['executor-2']?.builtAt ?? '') === '',
  )
  // A bearer token on the wire, which is the inversion EZ-1782 made. Until then this asserted the
  // opposite: the endpoint was permitAll and deliberately asked for without a session.
  check(
    `called with a bearer token (${versionsCalls.length} call(s))`,
    versionsCalls.length > 0 && versionsCalls.every((h) => (h ?? '').startsWith('Bearer ')),
  )
  await shot('01-versions')

  // --- grading images (EZ-1781) --------------------------------------------------------------------
  const ordered = await readVersionRows()
  const at = (name) => ordered.find((r) => r.name === name)

  check(
    `an image reports its installed version (${at('silmused')?.value})`,
    at('silmused')?.value === 'silmused 1.7.11',
  )
  check('an image row is indented under its executor', at('silmused')?.indented === true)
  check('an executor row is not indented', at('executor-1')?.indented === false)
  check(
    `a mismatch shows both numbers rather than picking one (${at('tiivad')?.value})`,
    (at('tiivad')?.value ?? '').includes('0.0.30') && (at('tiivad')?.value ?? '').includes('0.0.33'),
  )
  check(
    `an image with several libraries names them all (${at('imgrec')?.value})`,
    at('imgrec')?.value === 'pillow 12.3.0, requests 2.34.2',
  )
  check(
    `an image with no knowable version says so (${at('pygrader')?.value})`,
    (at('pygrader')?.value ?? '').includes('version unknown'),
  )
  check(
    `and still shows when it was built (${at('pygrader')?.builtAt})`,
    /^12 \p{L}+ 2024,/u.test(at('pygrader')?.builtAt ?? ''),
  )
  // Order matters: the images belong to executor-1, so they must appear after it and before the
  // next executor. Rendered flat, position is the only thing saying which executor they are under.
  const names = ordered.map((r) => r.name)
  check(
    'image rows sit between their executor and the next one',
    names.indexOf('executor-1') < names.indexOf('silmused') &&
      names.indexOf('pygrader') < names.indexOf('executor-2'),
  )
  check(
    'an unreachable executor grows no image rows',
    names.indexOf('executor-2') === names.length - 1,
  )
  await shot('03-grading-images')

  // --- a student sees none of it (EZ-1782) ----------------------------------------------------------
  {
    // launch({ role }) rather than a second init script setting activeRole. Setting activeRole alone
    // does not work: stubRole would still say teacher,admin, and the app corrects an acting role that
    // is not among the available ones — so the page came back as a teacher and both this block and the
    // admin panel rendered.
    const { page: studentPage, shot: studentShot, close: closeStudent } =
      await launch({ shotPrefix: 'about-versions-student-', role: 'student' })

    const studentCalls = []
    await fakeApi(
      studentPage,
      [
        ['/account/checkin', () => ({})],
        ['/statistics/common', () => ({ in_auto_assessing: 1, total_submissions: 2, total_users: 3 })],
        [
          '/versions',
          ({ route }) => {
            studentCalls.push(route.request().url())
            return VERSIONS
          },
        ],
      ],
      { log: false },
    )

    await studentPage.goto(`${BASE_URL}/about`)
    await studentPage.getByText('Lahendus is operated').waitFor()
    const studentText = (await studentPage.locator('#root').innerText()).replace(/\s+/g, ' ')

    check('student: the versions block is absent', !studentText.includes('Versions'))
    check(
      `student: no version is leaked into the page (${studentText.includes('abc1234')})`,
      !studentText.includes('abc1234'),
    )
    // Not asked for at all, rather than asked for and refused. A 403 per About page view would be
    // noise in the logs and a request nobody needed.
    check(`student: the endpoint is never called (${studentCalls.length})`, studentCalls.length === 0)
    await studentShot('04-student')
    await closeStudent()
  }

  // --- core unreachable: web's own line must survive ------------------------------------------------
  // The two halves fail independently. Losing the server half must not take away the one version the
  // page knows for certain.
  versionsStatus = 500
  await page.goto(`${BASE_URL}/about?again=1`)
  const degraded = await readVersions()
  check(`web still reported when the server 500s (${degraded.web?.version})`, /^v\d/.test(degraded.web?.version ?? ''))
  check('no core row is invented', degraded.core === undefined)
  check(
    'and the page says why',
    (await page.getByText('Could not reach the server').count()) > 0,
  )
  await shot('02-server-unreachable')

  await close()
})
