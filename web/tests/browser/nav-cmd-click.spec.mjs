// Every navigating thing in the sidebar and the account menu is a real link.
//
// This is a regression guard, not a feature test. The rule has been broken twice already:
// `spaLinkProps` was written for the grade table, left correct-and-unreachable while the course
// cards used bare onClicks (2026-08-16), and then grew to four separate implementations while the
// sidebar — rendered on every authenticated page — used none of them and navigated with
// `navigate()`. Courses and Library, the two most-used items in the app, could not be opened in a
// second tab at all.
//
// The failure is invisible to any test that only clicks normally, and invisible in review because
// the handler looks right: it does navigate. So the assertion here is structural — an `href` on
// every nav item — rather than behavioural.
//
//   cd web && npx playwright test nav-cmd-click
import { test } from '../support/spec.mjs'
import { fakeApi, BASE_URL, waitUntil } from '../support/harness.mjs'

const COURSE_ID = '9006'

test('nav-cmd-click', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ shotPrefix: 'nav-link-' })

  await fakeApi(
    page,
    [
      ['/account/checkin', () => ({})],
      [`/courses/${COURSE_ID}/basic`, () => ({
        id: COURSE_ID, title: 'Programming 101', alias: null,
        // Null, so the sidebar grows no Moodle link here (EZ-1874) — this spec counts the nav items
        // and asserts every one of them is an anchor, and the link has its own spec.
        moodle_course_url: null,
      })],
      [`/courses/${COURSE_ID}/exercises`, () => ({ exercises: [] })],
      ['/courses/teacher', () => ({
        courses: [{ id: COURSE_ID, title: 'Programming 101', alias: null, student_count: 12 }],
      })],
      ['/courses', () => ({ courses: [] })],
      ['/management/common/notifications', () => ({ messages: [] })],
    ],
    { log: false },
  )

  // A course page, not /courses: the sidebar only grows its exercises/grades/participants/similarity
  // section inside a course, and those were four of the broken items.
  await page.goto(`${BASE_URL}/courses/${COURSE_ID}/exercises`)
  await waitUntil(async () => (await page.locator('nav [class*=MuiListItemButton]').count()) > 0)

  const items = await page
    .locator('nav [class*=MuiListItemButton]')
    .evaluateAll((els) =>
      els.map((el) => ({
        text: (el.textContent ?? '').trim().replace(/\s+/g, ' '),
        href: el.getAttribute('href'),
        tag: el.tagName.toLowerCase(),
      })),
    )

  // The positive control, and it is the point. Every other assertion below is "none of them are
  // broken", which an empty sidebar satisfies perfectly — so a fixture that quietly stopped
  // rendering the nav would look like a pass.
  check(
    `the sidebar rendered something to check (${items.length}: ${items.map((i) => i.text).join(' | ')})`,
    items.length > 0,
  )

  // Sidebar entries that open something rather than going somewhere. An href on one of these would
  // be wrong, so they are excluded by name — and the list is short and explicit so that a *new*
  // item arriving without an href still fails rather than quietly joining the exceptions.
  const ACTION_ITEMS = ['Course settings']
  const isAction = (i) => ACTION_ITEMS.some((a) => i.text.includes(a))
  const navItems = items.filter((i) => !isAction(i))

  check(`and some of them navigate (${navItems.length})`, navItems.length > 0)

  const withoutHref = navItems.filter((i) => !i.href)
  check(
    `every navigating sidebar item is a link${withoutHref.length ? `: missing on ${withoutHref.map((i) => i.text).join(', ')}` : ''}`,
    withoutHref.length === 0,
  )
  // An anchor, not a div with a click handler — that is what makes middle-click and "copy link
  // address" work, and what a screen reader announces as a link.
  const notAnchors = navItems.filter((i) => i.tag !== 'a')
  check(
    `and is rendered as an anchor${notAnchors.length ? `: ${notAnchors.map((i) => `${i.text}=${i.tag}`).join(', ')}` : ''}`,
    notAnchors.length === 0,
  )

  // The two the bug was reported against — asserted by destination, not by label, so this does not
  // break the next time a translation is reworded.
  const hrefs = navItems.map((i) => i.href)
  check(`Courses is linked (${hrefs.join(' ')})`, hrefs.includes('/courses'))
  check('the exercise library is linked', hrefs.includes('/library/dir/root'))

  // The clickable course title above the sub-page links. It is a `ListSubheader`, so the anchor is
  // inside it rather than being it — a `<ul>` may only contain `<li>`.
  const subheader = page.locator('nav a[href$="/exercises"]').filter({ hasText: 'Programming 101' })
  check('the course title is a link too', (await subheader.count()) > 0)

  await shot('01-sidebar')

  // --- the account menu ---------------------------------------------------------------------------

  await page.getByRole('button', { name: 'Account menu' }).click()
  await waitUntil(async () => (await page.locator('[role=menuitem]').count()) > 0)

  const menu = await page
    .locator('[role=menuitem]')
    .evaluateAll((els) =>
      els.map((el) => ({
        text: (el.textContent ?? '').trim().replace(/\s+/g, ' '),
        href: el.getAttribute('href'),
      })),
    )

  check(`the account menu rendered (${menu.length} items)`, menu.length > 0)

  const account = menu.find((i) => i.text.includes('Account'))
  check(`Account settings is a link (${account?.href})`, account?.href === '/account')

  // Not everything in this menu navigates — the theme toggle, the language switch, the role
  // switcher, "Report a bug" and Log out are all actions, and an href on any of them would be
  // wrong. So this asserts the *navigating* ones only, by name, rather than demanding hrefs
  // everywhere and then having to except half the menu.
  const actions = menu.filter((i) => !i.href)
  check(`action items correctly have no href (${actions.length})`, actions.length > 0)

  await shot('02-account-menu')
  await close()
})
