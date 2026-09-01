// The Moodle link in a course's sidebar section (EZ-1874) — teachers only, last in the section, and
// absent where there is no Moodle course to open.
//
// Core decides whether there is a link at all and sends a finished URL or null, so what is testable
// here is the half that lives in the sidebar: that null renders nothing, that a URL renders a real
// external anchor, that it comes last among the course's items, and that a student does not get it
// even though the same response carries the URL. That last one is the assertion worth having — the
// field is on `/courses/{id}/basic`, which students are served too, so nothing but the sidebar's own
// role gate keeps it out of their nav, and a gate has to be watched saying no.
//
//   cd web && npx playwright test nav-moodle-course
import { test } from '../support/spec.mjs'
import { fakeApi, BASE_URL, waitUntil } from '../support/harness.mjs'

const COURSE_ID = '9074'
const MOODLE_URL = 'https://moodle.example/course/view.php?name=LTAT.03.001'

/**
 * A student's exercise-list entry, whole rather than only the two fields the sidebar reads.
 *
 * The sidebar needs `effective_title` and `status` and nothing else, so the short version was
 * tempting — but a partial fixture is what the contract checker counts, and a spec arriving with a
 * budget of ten warnings hands the next person a number to raise rather than a fixture to copy.
 */
const studentExercise = (id, title, status) => ({
  id,
  effective_title: title,
  status,
  grader_type: 'AUTO',
  grade: null,
  deadline: null,
  is_open: true,
  ordering_idx: Number(id),
})

test('nav-moodle-course', async ({ launch, check }) => {
  /**
   * Render a course page's sidebar and report what the Moodle entry looks like.
   *
   * `moodleCourseUrl` is passed through to the `/basic` fixture exactly as core would send it,
   * null included — the null case is the one that has to keep working on every environment that
   * has no Moodle configured, which is every laptop.
   */
  async function sidebarFor({ role = 'teacher,admin', moodleCourseUrl, exercises = [] }) {
    const { page, close } = await launch({ role, shotPrefix: 'nav-moodle-' })

    const basic = () => ({
      title: 'Programming 101',
      alias: null,
      archived: false,
      color: 'blue',
      course_code: 'LTAT.03.001',
      moodle_course_url: moodleCourseUrl,
    })

    await fakeApi(
      page,
      [
        ['/account/checkin', () => ({})],
        [`/courses/${COURSE_ID}/basic`, basic],
        // Before the teacher one below it, because a substring match would otherwise answer the
        // student's request with the teacher's shape — `/courses/9074/exercises` is a substring of
        // `/student/courses/9074/exercises`.
        [`/student/courses/${COURSE_ID}/exercises`, () => ({ exercises })],
        [`/courses/${COURSE_ID}/exercises`, () => ({ exercises: [] })],
        [`/courses/${COURSE_ID}/groups`, () => ({ groups: [] })],
        ['/courses/teacher', () => ({ courses: [] })],
        ['/management/common/notifications', () => ({ messages: [] })],
      ],
      { log: false },
    )

    await page.goto(`${BASE_URL}/courses/${COURSE_ID}/exercises`)
    await waitUntil(async () => (await page.locator('nav [class*=MuiListItemButton]').count()) > 0)
    // The course section is drawn from a second request, so an assertion the moment the nav appears
    // can be reading the sidebar without it. The course title is what that request produces.
    await waitUntil(async () => (await page.locator('nav').getByText('Programming 101').count()) > 0)

    const link = page.locator(`nav a[href="${MOODLE_URL}"]`)
    const found = await link.count()
    return {
      close,
      found,
      target: found > 0 ? await link.getAttribute('target') : null,
      rel: found > 0 ? await link.getAttribute('rel') : null,
      text: found > 0 ? (await link.innerText()).trim() : null,
      // Every nav item in order, for the position assertions. Text and not hrefs, because two of
      // the items being ordered against are exercises and one is the link itself.
      navItems: (await page.locator('nav [class*=MuiListItemButton]').allInnerTexts()).map((s) =>
        s.trim().replace(/\s+/g, ' '),
      ),
    }
  }

  // --- a teacher on a linked course -----------------------------------------------------------------
  {
    const r = await sidebarFor({ moodleCourseUrl: MOODLE_URL })

    // The positive control. Everything below is either "the link is there" or "it is not", and an
    // empty sidebar answers the second group perfectly — so a fixture that stopped rendering the
    // nav would read as a pass on half of this spec.
    check(
      `the sidebar rendered something to check (${r.navItems.length}: ${r.navItems.join(' | ')})`,
      r.navItems.length > 0,
    )

    check(`a teacher gets the link (${r.found})`, r.found === 1)
    check(`it is labelled (${r.text})`, r.text === 'Moodle')
    // It leaves the app, so a new tab — and rel is not optional with target=_blank.
    check(`opens in a new tab (${r.target})`, r.target === '_blank')
    check(`and is not an open redirect vector (${r.rel})`, (r.rel ?? '').includes('noopener'))

    // Last, after the in-app pages — including Course settings, which used to end the section.
    const moodleAt = r.navItems.findIndex((t) => t === 'Moodle')
    check(
      `it comes last in the sidebar (${r.navItems.join(' | ')})`,
      moodleAt >= 0 && moodleAt === r.navItems.length - 1,
    )
    // And it displaced nothing: the same five items, plus one at the end.
    for (const item of ['Exercises', 'Grades', 'Participants', 'Similarity', 'Course settings']) {
      check(`${item} is still in the nav`, r.navItems.some((t) => t.includes(item)))
    }
    await r.close()
  }

  // --- nothing to link to ---------------------------------------------------------------------------
  {
    // What core sends for a course nobody has linked, and for every course on an environment with
    // no `moodle-sync.course-url-prefix` — a laptop, and dev until someone sets it.
    const r = await sidebarFor({ moodleCourseUrl: null })
    check('no Moodle course, no link', r.found === 0)
    check(
      `nor an empty row where it would have been (${r.navItems.join(' | ')})`,
      !r.navItems.some((t) => t.includes('Moodle')),
    )
    check('and the rest of the section is untouched', r.navItems.some((t) => t.includes('Exercises')))
    await r.close()
  }

  // --- a student on the same course ------------------------------------------------------------------
  {
    // The URL is in the response — `/basic` serves students the same fields — so this is the role
    // gate being watched say no, not an absent fixture producing an absent link.
    const r = await sidebarFor({
      role: 'student',
      moodleCourseUrl: MOODLE_URL,
      exercises: [studentExercise('1', 'Loops', 'UNSTARTED'), studentExercise('2', 'Lists', 'COMPLETED')],
    })
    check(`a student does not get it, URL or no URL (${r.found})`, r.found === 0)
    check(
      `nor a row of any kind naming Moodle (${r.navItems.join(' | ')})`,
      !r.navItems.some((t) => t.includes('Moodle')),
    )
    // The positive control for this case: their own section did render, so "no link" is a decision
    // and not an empty sidebar.
    check('while their exercise list is there as before', r.navItems.some((t) => t.includes('Loops')))
    await r.close()
  }
})
