// Teacher/admin course exercises list — row actions, mass actions, filters,
// reorder dialog, add-from-library dialog.
//
//   npx vite --config ../vite.stub.config.ts --port 5199 --strictPort   (in web/)
//   node scripts/course-exercises.mjs
import { launch, fakeApi, json, checker, BASE_URL } from '../harness.mjs'

const COURSE_ID = '9006'

const hoursFromNow = (h) =>
  new Date(Date.now() + h * 3600_000).toISOString().replace(/\.\d+Z$/, 'Z')

// completed/started/ungraded/unstarted sum to 12 students on every exercise
function ex(overrides) {
  return {
    course_exercise_id: '1',
    exercise_id: 'e1',
    library_title: 'Untitled',
    title_alias: null,
    effective_title: 'Untitled',
    grade_threshold: 100,
    student_visible: true,
    student_visible_from: hoursFromNow(-100),
    soft_deadline: null,
    hard_deadline: null,
    grader_type: 'AUTO',
    ordering_idx: 0,
    unstarted_count: 4,
    ungraded_count: 0,
    started_count: 2,
    completed_count: 6,
    latest_submissions: [],
    ...overrides,
  }
}

// Mutable so the stub can model what the real backend would do
let exercises = [
  ex({
    course_exercise_id: '1', exercise_id: 'e1', ordering_idx: 0,
    library_title: 'Loops and conditions', effective_title: 'Loops and conditions',
    soft_deadline: hoursFromNow(-48), completed_count: 9, started_count: 1, unstarted_count: 2,
  }),
  ex({
    course_exercise_id: '2', exercise_id: 'e2', ordering_idx: 1,
    library_title: 'Recursion', effective_title: 'Recursion',
    soft_deadline: hoursFromNow(72), ungraded_count: 3, completed_count: 4,
    started_count: 1, unstarted_count: 4, grader_type: 'TEACHER',
  }),
  ex({
    course_exercise_id: '3', exercise_id: 'e3', ordering_idx: 2,
    library_title: 'Lists and dictionaries', effective_title: 'Lists (renamed)',
    title_alias: 'Lists (renamed)',
    student_visible: false, student_visible_from: null,
    completed_count: 0, started_count: 0, ungraded_count: 0, unstarted_count: 12,
  }),
  ex({
    course_exercise_id: '4', exercise_id: 'e4', ordering_idx: 3,
    library_title: 'File handling', effective_title: 'File handling',
    student_visible: false, student_visible_from: hoursFromNow(48),
    completed_count: 0, started_count: 0, ungraded_count: 0, unstarted_count: 12,
    grader_type: 'TEACHER',
  }),
]

const GROUPS = [
  { id: '11', name: 'Group A', student_count: 6 },
  { id: '12', name: 'Group B', student_count: 6 },
]

const LIB_ROOT = {
  current_dir: null,
  child_dirs: [
    { id: 'd1', name: 'Python basics', effective_access: 'PRAWM', is_shared: false },
    { id: 'd2', name: 'Shared with me', effective_access: 'PR', is_shared: true },
  ],
  child_exercises: [
    {
      exercise_id: 'e9', dir_id: 'root', title: 'Sorting algorithms',
      effective_access: 'PRAWM', is_shared: false, grader_type: 'AUTO', courses_count: 3,
      created_at: hoursFromNow(-900), created_by: 'x', modified_at: hoursFromNow(-100), modified_by: 'x',
    },
    {
      exercise_id: 'e11', dir_id: 'root', title: 'Binary search',
      effective_access: 'PRAWM', is_shared: false, grader_type: 'AUTO', courses_count: 2,
      created_at: hoursFromNow(-800), created_by: 'x', modified_at: hoursFromNow(-80), modified_by: 'x',
    },
    {
      // Shares an exercise_id with a course exercise, so it reads as already added
      exercise_id: 'e1', dir_id: 'root', title: 'Loops and conditions',
      effective_access: 'PRAWM', is_shared: false, grader_type: 'AUTO', courses_count: 5,
      created_at: hoursFromNow(-900), created_by: 'x', modified_at: hoursFromNow(-90), modified_by: 'x',
    },
  ],
}

const LIB_D1 = {
  current_dir: { id: 'd1', name: 'Python basics', effective_access: 'PRAWM', is_shared: false },
  child_dirs: [],
  child_exercises: [
    {
      exercise_id: 'e10', dir_id: 'd1', title: 'String formatting',
      effective_access: 'PRAWM', is_shared: false, grader_type: 'TEACHER', courses_count: 1,
      created_at: hoursFromNow(-500), created_by: 'x', modified_at: hoursFromNow(-50), modified_by: 'x',
    },
  ],
}

const run = async () => {
  const { browser, page, shot } = await launch({ role: 'teacher,admin', shotPrefix: 'course-ex-' })
  const check = checker()

  const patches = []
  const deletes = []
  const reorders = []
  const adds = []

  await fakeApi(page, [
    ['/account/checkin', () => ({})],
    ['/courses/9006/basic', () => ({ id: COURSE_ID, title: 'Programming 101', alias: null })],
    ['/student/courses/9006/exercises', () => ({ exercises: [] })],
    ['/courses/teacher', () => ({
      courses: [{ id: COURSE_ID, title: 'Programming 101', alias: null, student_count: 12 }],
    })],
    [`/courses/${COURSE_ID}/groups`, () => ({ groups: GROUPS })],
    [`/courses/${COURSE_ID}/participants`, () => ({ students: [], teachers: [], students_pending: [], students_moodle_pending: [] })],
    ['/lib/dirs/root', () => LIB_ROOT],
    ['/lib/dirs/d1', () => LIB_D1],
    ['/lib/dirs/d2', () => ({ current_dir: { id: 'd2', name: 'Shared with me', effective_access: 'PR', is_shared: true }, child_dirs: [], child_exercises: [] })],
    // Reorder — move the exercise and renumber, like the backend does
    [/\/exercises\/\d+\/reorder$/, ({ body, url }) => {
      const id = url.match(/exercises\/(\d+)\/reorder/)[1]
      reorders.push({ id, newIndex: body.new_index })
      const moving = exercises.find((e) => e.course_exercise_id === id)
      const rest = exercises.filter((e) => e.course_exercise_id !== id)
      rest.splice(body.new_index, 0, moving)
      exercises = rest.map((e, i) => ({ ...e, ordering_idx: i }))
      return {}
    }],
    // PATCH visibility / DELETE from course
    [/\/courses\/\d+\/exercises\/\d+$/, ({ route, method, body, url }) => {
      const id = url.match(/exercises\/(\d+)/)[1]
      if (method === 'PATCH') {
        patches.push({ id, body })
        const vis = body?.replace?.student_visible
        if (vis !== undefined) {
          exercises = exercises.map((e) =>
            e.course_exercise_id === id
              ? { ...e, student_visible: vis, student_visible_from: vis ? hoursFromNow(-1) : null }
              : e)
        }
        return {}
      }
      if (method === 'DELETE') {
        deletes.push(id)
        exercises = exercises
          .filter((e) => e.course_exercise_id !== id)
          .map((e, i) => ({ ...e, ordering_idx: i }))
        return {}
      }
      return {}
    }],
    // Add exercise to course
    [/\/teacher\/courses\/\d+\/exercises(\?|$)/, ({ route, method, body }) => {
      if (method === 'POST') {
        adds.push(body.exercise_id)
        const nid = String(100 + adds.length)
        exercises = [...exercises, ex({
          course_exercise_id: nid, exercise_id: body.exercise_id,
          library_title: 'Added exercise', effective_title: 'Added exercise',
          ordering_idx: exercises.length, student_visible: false, student_visible_from: null,
          completed_count: 0, started_count: 0, ungraded_count: 0, unstarted_count: 12,
        })]
        return json(route, { id: nid })
      }
      // GET, optionally filtered by ?group=
      const g = new URL(route.request().url()).searchParams.get('group')
      const list = g === '11' ? exercises.slice(0, 2) : exercises
      return json(route, { exercises: list })
    }],
  ])

  // Counts *rendered* progress-bar tracks in a row. Uses getBoundingClientRect
  // because a display:none element still reports height:6px via computed style,
  // and overflow:hidden to tell the outer track from its coloured segments.
  const barsIn = (locator) => locator.evaluate((row) =>
    [...row.querySelectorAll('div')].filter((d) => {
      const r = d.getBoundingClientRect()
      return Math.round(r.height) === 6 && r.width > 20
        && getComputedStyle(d).overflowY === 'hidden'
    }).length)

  // Ticks a row's checkbox. The checkbox only appears while the cursor is over
  // the icon slot, so hover that first — hovering the row body does nothing.
  const tickRow = async (row) => {
    await row.locator('.MuiListItemIcon-root').hover()
    await page.waitForTimeout(150)
    await row.locator('input[type="checkbox"]').click()
    await page.waitForTimeout(200)
  }

  const url = `${BASE_URL}/courses/${COURSE_ID}/exercises`
  await page.goto(url)
  await page.waitForSelector('text=Loops and conditions', { timeout: 15000 })
  await page.waitForTimeout(400)

  // ---- 1. Rows render with the new layout ----
  console.log('\n1. Row layout')
  check('4 exercises listed', (await page.locator('a[href*="/exercises/"]').count()) === 4)
  check('grader-type icons present', (await page.locator('svg').count()) > 4)
  check('deadline shown', await page.locator('text=/Deadline:/').first().isVisible())
  check('ungraded chip on Recursion', await page.locator('text=/3 ungraded/').first().isVisible())
  check('hidden exercise dimmed/marked', await page.locator('text=Lists (renamed)').first().isVisible())
  const schedChip = page.locator('a', { hasText: 'File handling' }).locator('.MuiChip-root').first()
  check('scheduled exercise shows a date chip', await schedChip.isVisible())
  // A clock face means "deadline" in this product, so the visibility chip must
  // not use one. (MUI stamps data-testid on icons in dev builds.)
  const schedIcon = await schedChip.locator('svg').first().getAttribute('data-testid')
  check('scheduled chip uses an eye, not a clock',
    schedIcon === 'VisibilityOutlinedIcon', String(schedIcon))
  check('count footer', await page.locator('text=/4 exercises/').first().isVisible())
  const firstRowText = await page.locator('a[href*="/exercises/"]').first().innerText()
  check('no finished-count text at desktop width',
    !/\d+\s*\/\s*\d+/.test(firstRowText), JSON.stringify(firstRowText))
  const desktopBars = await barsIn(page.locator('a[href*="/exercises/"]').first())
  check('progress bar shown at desktop width', desktopBars === 1, `${desktopBars} bars`)
  await shot('01-list')
  // The grader icon becomes a checkbox only while the cursor is over the icon,
  // not anywhere on the row.
  const row1 = page.locator('a[href*="/exercises/"]').first()
  const row1Box = await row1.boundingBox()
  const row1Check = row1.locator('input[type="checkbox"]')

  // Hovering the title / middle of the row must NOT reveal it
  await page.mouse.move(row1Box.x + row1Box.width * 0.5, row1Box.y + row1Box.height / 2)
  await page.waitForTimeout(300)
  check('checkbox stays hidden when hovering the row body',
    !(await row1Check.isVisible()))
  await shot('01b-hover-row-body')

  // Hovering the icon does
  const iconBox = await row1.locator('svg').first().boundingBox()
  await page.mouse.move(iconBox.x + iconBox.width / 2, iconBox.y + iconBox.height / 2)
  await page.waitForTimeout(300)
  check('checkbox revealed when hovering the icon', await row1Check.isVisible())
  await shot('01b-hover-icon')

  // The zone is a little wider than the 24px icon, so near-misses still work
  await page.mouse.move(iconBox.x + iconBox.width / 2, iconBox.y - 5)
  await page.waitForTimeout(300)
  check('checkbox revealed just above the icon too', await row1Check.isVisible())

  await page.mouse.move(0, 0)
  await page.waitForTimeout(250)
  check('checkbox hidden again after leaving', !(await row1Check.isVisible()))

  // ---- 2. Toolbar buttons ----
  console.log('\n2. Toolbar')
  // Adding is a setup-time task, so it sits behind a single "+" in the header
  // rather than two coloured buttons in the toolbar.
  const addBtn = page.getByRole('button', { name: 'Add exercise', exact: true })
  check('single add button in the header', await addBtn.isVisible())
  check('label shown at desktop width',
    (await addBtn.innerText()).trim() === 'Add exercise', JSON.stringify(await addBtn.innerText()))
  // Captured now — the desktop browser is closed before the mobile section runs
  const deskAddWidth = (await addBtn.boundingBox()).width
  check('no prominent add/create buttons in the toolbar',
    (await page.getByRole('button', { name: 'New exercise' }).count()) === 0)
  await addBtn.click()
  await page.waitForTimeout(250)
  const addMenu = await page.locator('[role="menuitem"]').allInnerTexts()
  check('"+" menu offers both ways to add',
    addMenu.includes('Add exercise from library') && addMenu.includes('New exercise'),
    JSON.stringify(addMenu))
  await shot('01c-add-menu')
  await page.keyboard.press('Escape')
  await page.waitForTimeout(250)
  check('Group filter chip', await page.locator('.MuiChip-root', { hasText: 'Groups' }).isVisible())
  check('Visibility filter chip', await page.locator('.MuiChip-root', { hasText: 'Visibility' }).isVisible())
  check('Deadline filter chip', await page.locator('.MuiChip-root', { hasText: 'Deadline' }).first().isVisible())
  check('Ungraded toggle chip', await page.locator('.MuiChip-root', { hasText: 'Ungraded' }).first().isVisible())

  // ---- 3. Filters ----
  console.log('\n3. Filters')
  await page.locator('.MuiChip-root', { hasText: 'Visibility' }).click()
  await page.getByRole('menuitem', { name: 'Hidden' }).click()
  await page.waitForTimeout(250)
  check('Visibility=Hidden leaves 2 rows',
    (await page.locator('a[href*="/exercises/"]').count()) === 2,
    `got ${await page.locator('a[href*="/exercises/"]').count()}`)
  await shot('02-filter-hidden')
  await page.locator('.MuiChip-root').filter({ hasText: 'Hidden' }).first().click()
  await page.getByRole('menuitem', { name: 'All', exact: true }).click()
  await page.waitForTimeout(250)

  await page.locator('.MuiChip-root', { hasText: 'Ungraded' }).first().click()
  await page.waitForTimeout(250)
  check('Ungraded toggle leaves 1 row',
    (await page.locator('a[href*="/exercises/"]').count()) === 1,
    `got ${await page.locator('a[href*="/exercises/"]').count()}`)
  await page.locator('.MuiChip-root', { hasText: 'Ungraded' }).first().click()
  await page.waitForTimeout(250)

  await page.locator('.MuiChip-root', { hasText: 'Deadline' }).first().click()
  await page.getByRole('menuitem', { name: 'Deadline passed' }).click()
  await page.waitForTimeout(250)
  check('Deadline=passed leaves 1 row',
    (await page.locator('a[href*="/exercises/"]').count()) === 1,
    `got ${await page.locator('a[href*="/exercises/"]').count()}`)
  await page.locator('.MuiChip-root').filter({ hasText: 'Deadline passed' }).first().click()
  await page.getByRole('menuitem', { name: 'All', exact: true }).click()
  await page.waitForTimeout(250)

  // Group filter must refetch with ?group=
  await page.locator('.MuiChip-root', { hasText: 'Groups' }).click()
  await page.getByRole('menuitem', { name: 'Group A' }).click()
  await page.waitForTimeout(500)
  check('Group A filter refetches to 2 rows',
    (await page.locator('a[href*="/exercises/"]').count()) === 2,
    `got ${await page.locator('a[href*="/exercises/"]').count()}`)
  await page.locator('.MuiChip-root', { hasText: 'Group A' }).click()
  await page.getByRole('menuitem', { name: 'All groups' }).click()
  await page.waitForTimeout(500)

  // ---- 3b. Filters persist across a reload, per course ----
  console.log('\n3b. Filter persistence')
  await page.locator('.MuiChip-root', { hasText: 'Visibility' }).click()
  await page.getByRole('menuitem', { name: 'Hidden' }).click()
  await page.waitForTimeout(200)
  await page.locator('.MuiChip-root', { hasText: 'Ungraded' }).first().click()
  await page.waitForTimeout(200)
  const stored = await page.evaluate(() =>
    localStorage.getItem('teacherCourseExerciseFilters'))
  check('filters written to localStorage per course',
    stored !== null && JSON.parse(stored)['9006']?.visibility === 'hidden'
      && JSON.parse(stored)['9006']?.ungradedOnly === true, String(stored))

  await page.reload()
  await page.waitForSelector('.MuiChip-root', { timeout: 15000 })
  await page.waitForTimeout(600)
  check('Visibility chip still Hidden after reload',
    await page.locator('.MuiChip-root').filter({ hasText: 'Hidden' }).first().isVisible())
  check('Ungraded chip still active after reload',
    (await page.locator('.MuiChip-root').filter({ hasText: 'Ungraded' }).first()
      .getAttribute('class'))?.includes('filled'))
  await shot('02b-filters-restored')

  // The empty-on-arrival state must offer a way out
  check('filtered-empty list offers Clear filters',
    await page.getByRole('button', { name: 'Clear filters' }).isVisible())
  await page.getByRole('button', { name: 'Clear filters' }).click()
  await page.waitForTimeout(400)
  check('Clear filters restores all rows',
    (await page.locator('a[href*="/exercises/"]').count()) === 4,
    `got ${await page.locator('a[href*="/exercises/"]').count()}`)
  check('cleared state is persisted too',
    JSON.parse(await page.evaluate(() =>
      localStorage.getItem('teacherCourseExerciseFilters')))['9006']?.visibility === 'all')

  // A different course must not inherit them
  await page.goto(`${BASE_URL}/courses/9007/exercises`)
  await page.waitForSelector('.MuiChip-root', { timeout: 15000 })
  await page.waitForTimeout(600)
  check('another course starts unfiltered',
    await page.locator('.MuiChip-root').filter({ hasText: 'Visibility' }).first().isVisible())

  // A blob from an older build must not break the page
  await page.evaluate(() => localStorage.setItem('teacherCourseExerciseFilters',
    JSON.stringify({ 9006: { visibility: 'bogus', deadline: 42, gone: 'x' } })))
  await page.goto(url)
  await page.waitForSelector('a[href*="/exercises/"]', { timeout: 15000 })
  await page.waitForTimeout(600)
  check('corrupt stored filters fall back to defaults',
    (await page.locator('a[href*="/exercises/"]').count()) === 4
      && await page.locator('.MuiChip-root').filter({ hasText: 'Visibility' }).first().isVisible(),
    `${await page.locator('a[href*="/exercises/"]').count()} rows`)
  await page.evaluate(() => localStorage.removeItem('teacherCourseExerciseFilters'))
  await page.reload()
  await page.waitForSelector('a[href*="/exercises/"]', { timeout: 15000 })
  await page.waitForTimeout(500)

  // ---- 4. Row menu: hide/reveal ----
  console.log('\n4. Row action: hide')
  const firstRow = page.locator('a[href*="/exercises/"]').first()
  await firstRow.hover()
  await firstRow.locator('button[aria-label="More options"]').click()
  await page.waitForTimeout(200)
  check('menu has Hide', await page.getByRole('menuitem', { name: 'Hide', exact: true }).isVisible())
  check('menu has Move to…', await page.getByRole('menuitem', { name: 'Move to…' }).isVisible())
  check('menu has Exercise settings', await page.getByRole('menuitem', { name: 'Exercise settings' }).isVisible())
  check('menu has Remove from course', await page.getByRole('menuitem', { name: 'Remove from course' }).isVisible())
  await shot('03-row-menu')
  await page.getByRole('menuitem', { name: 'Hide', exact: true }).click()
  await page.waitForTimeout(600)
  check('PATCH sent with student_visible:false',
    patches.some((p) => p.id === '1' && p.body?.replace?.student_visible === false),
    JSON.stringify(patches))
  check('toast shown', await page.locator('.MuiSnackbar-root').isVisible())
  await shot('04-after-hide')

  // ---- 5. Selection + mass actions ----
  console.log('\n5. Mass actions')
  await tickRow(page.locator('a[href*="/exercises/"]').nth(1))
  check('mass action bar appears', await page.locator('text=/1 selected/').isVisible())
  await tickRow(page.locator('a[href*="/exercises/"]').nth(2))
  check('2 selected', await page.locator('text=/2 selected/').isVisible())
  check('Reveal button', await page.getByRole('button', { name: 'Reveal', exact: true }).isVisible())
  check('Hide button', await page.getByRole('button', { name: 'Hide', exact: true }).isVisible())
  await shot('05-mass-actions')

  // Reload between scenarios: selection is component state, so a fresh load is
  // a clean slate and avoids un-toggling gymnastics.
  // Row order here: 1 hidden (hidden in step 4), 2 visible, 3 hidden, 4 scheduled.
  const selectRows = async (indices) => {
    for (const i of indices) {
      await tickRow(page.locator('a[href*="/exercises/"]').nth(i))
    }
  }
  const freshLoad = async () => {
    await page.reload()
    await page.waitForSelector('a[href*="/exercises/"]', { timeout: 15000 })
    await page.waitForTimeout(500)
  }

  await freshLoad()
  await selectRows([0, 2]) // both hidden
  check('2 hidden selected', await page.locator('text=/2 selected/').isVisible())
  check('Hide disabled when every selection is already hidden',
    await page.getByRole('button', { name: 'Hide', exact: true }).isDisabled())
  check('Reveal enabled when a selection is hidden',
    await page.getByRole('button', { name: 'Reveal', exact: true }).isEnabled())
  await shot('05b-hide-disabled')

  await freshLoad()
  await selectRows([1]) // visible only
  check('Reveal disabled when every selection is already visible',
    await page.getByRole('button', { name: 'Reveal', exact: true }).isDisabled())
  check('Hide enabled when a selection is visible',
    await page.getByRole('button', { name: 'Hide', exact: true }).isEnabled())

  // A scheduled exercise: Hide must be enabled, because it cancels the schedule
  await freshLoad()
  await selectRows([3]) // scheduled only
  check('scheduled-only selection enables Hide (cancels the schedule)',
    await page.getByRole('button', { name: 'Hide', exact: true }).isEnabled())
  patches.length = 0
  await page.getByRole('button', { name: 'Hide', exact: true }).click()
  await page.waitForTimeout(800)
  check('hiding a scheduled exercise actually PATCHes it',
    patches.length === 1 && patches[0].body?.replace?.student_visible === false,
    JSON.stringify(patches))

  // Row menu on a scheduled exercise offers both Reveal and Hide.
  // The one above is now plain-hidden, so use a freshly scheduled row.
  exercises = exercises.map((e) => e.course_exercise_id === '4'
    ? { ...e, student_visible: false, student_visible_from: hoursFromNow(48) } : e)
  await freshLoad()
  const schedRow = page.locator('a', { hasText: 'File handling' }).first()
  await schedRow.hover()
  await schedRow.locator('button[aria-label="More options"]').click()
  await page.waitForTimeout(300)
  const menuItems = await page.locator('[role="menuitem"]').allInnerTexts()
  check('scheduled row menu has both Reveal and Hide',
    menuItems.includes('Reveal') && menuItems.includes('Hide'), JSON.stringify(menuItems))
  await shot('05c-scheduled-row-menu')
  await page.keyboard.press('Escape')
  await page.waitForTimeout(300)

  // Two rows for the mass-reveal assertion below
  await freshLoad()
  await selectRows([0, 2])
  patches.length = 0
  await page.getByRole('button', { name: 'Reveal', exact: true }).click()
  await page.waitForTimeout(700)
  check('mass reveal PATCHed only the not-already-visible ones',
    patches.length > 0 && patches.every((p) => p.body?.replace?.student_visible === true),
    `${patches.length} patches: ${JSON.stringify(patches.map((p) => p.id))}`)
  check('selection cleared after mass action',
    !(await page.locator('text=/selected/').first().isVisible().catch(() => false)))

  // ---- 6. Reorder dialog ----
  console.log('\n6. Reorder dialog')
  const rowA = page.locator('a[href*="/exercises/"]').first()
  await rowA.hover()
  await rowA.locator('button[aria-label="More options"]').click()
  await page.getByRole('menuitem', { name: 'Move to…' }).click()
  await page.waitForTimeout(400)
  check('reorder dialog open', await page.locator('text=Move exercise').isVisible())
  const moveBtn = page.getByRole('button', { name: 'Move', exact: true })
  check('Move disabled at current position', await moveBtn.isDisabled())
  await shot('06-reorder-dialog')
  // Pick the last slot
  const slots = page.locator('[aria-label="Move here"]')
  const slotCount = await slots.count()
  check('slot per position', slotCount === 3, `got ${slotCount} slots for 4 exercises (1 occupied)`)
  await slots.first().hover()
  await page.waitForTimeout(300)
  await shot('06b-reorder-slot-hover')

  // The drop slot's hit area is deliberately taller than its 28px layout box, so
  // you don't have to land on the 2px dashed line. Measure it: leave and re-enter
  // per probe, and wait past the 120ms fade — an exact opacity check mid-transition
  // reports a false negative.
  const probeSlot = slots.nth(1)
  const pb = await probeSlot.boundingBox()
  const pcx = pb.x + pb.width / 2
  const pcy = pb.y + pb.height / 2
  const activeAt = async (y) => {
    await page.mouse.move(pcx, pb.y - 60)
    await page.waitForTimeout(30)
    await page.mouse.move(pcx, y)
    await page.waitForTimeout(220)
    return probeSlot.locator('.slot-label')
      .evaluate((el) => parseFloat(getComputedStyle(el).opacity) > 0.9)
      .catch(() => false)
  }
  let bandUp = 0, bandDown = 0
  for (let d = 0; d <= 40; d++) { if (await activeAt(pcy - d)) bandUp = d; else break }
  for (let d = 0; d <= 40; d++) { if (await activeAt(pcy + d)) bandDown = d; else break }
  const band = bandUp + bandDown + 1
  check('drop slot hit area is taller than its 28px box', band >= 36,
    `${band}px band (${bandUp} up / ${bandDown} down), box is ${pb.height}px`)

  // The expansion must not bleed into a neighbour's target
  const routing = await page.evaluate(() => {
    const all = [...document.querySelectorAll('[aria-label="Move here"]')]
    const rects = all.map((s) => { const r = s.getBoundingClientRect(); return { top: r.top - 5, bottom: r.bottom + 5 } })
    let overlaps = 0
    for (let i = 1; i < rects.length; i++) if (rects[i].top < rects[i - 1].bottom) overlaps++
    const cx = all[0].getBoundingClientRect().x + 20
    let misrouted = 0
    all.forEach((s) => {
      const r = s.getBoundingClientRect()
      for (const y of [r.top - 4, r.top + r.height / 2, r.bottom + 4]) {
        const el = document.elementFromPoint(cx, y)
        if (el !== s && !s.contains(el)) misrouted++
      }
    })
    return { overlaps, misrouted }
  })
  check('expanded hit areas do not overlap or steal from neighbours',
    routing.overlaps === 0 && routing.misrouted === 0, JSON.stringify(routing))
  await slots.last().click()
  await page.waitForTimeout(250)
  check('Move enabled after picking a slot', await moveBtn.isEnabled())
  await shot('07-reorder-picked')
  await moveBtn.click()
  await page.waitForTimeout(700)
  check('reorder POSTed', reorders.length === 1, JSON.stringify(reorders))
  check('new_index is the last position', reorders[0]?.newIndex === 3, JSON.stringify(reorders))
  await shot('08-after-reorder')

  // ---- 6b. Move up / move down by one ----
  console.log('\n6b. Move up / down')
  exercises = Array.from({ length: 5 }, (_, i) => ex({
    course_exercise_id: String(300 + i), exercise_id: `e${300 + i}`, ordering_idx: i,
    library_title: `Item ${i + 1}`, effective_title: `Item ${i + 1}`,
    // Items 2 and 4 hidden, so a Visibility filter leaves a non-contiguous list
    student_visible: i !== 1 && i !== 3,
    student_visible_from: i !== 1 && i !== 3 ? hoursFromNow(-10) : null,
  }))
  await freshLoad()

  const openMenuFor = async (title) => {
    const r = page.locator('a', { hasText: title }).first()
    await r.hover()
    await r.locator('button[aria-label="More options"]').click()
    await page.waitForTimeout(250)
  }
  const titles = () => page.locator('a[href*="/exercises/"] .MuiListItemText-primary')
    .allInnerTexts().then((ts) => ts.map((x) => x.trim().split('\n')[0]))

  // First row: Move up disabled. Last row: Move down disabled.
  await openMenuFor('Item 1')
  check('Move up disabled on the first row',
    await page.getByRole('menuitem', { name: 'Move up' }).isDisabled())
  check('Move down enabled on the first row',
    await page.getByRole('menuitem', { name: 'Move down' }).isEnabled())
  await shot('06c-move-menu')
  await page.keyboard.press('Escape')
  await page.waitForTimeout(200)

  await openMenuFor('Item 5')
  check('Move down disabled on the last row',
    await page.getByRole('menuitem', { name: 'Move down' }).isDisabled())
  await page.keyboard.press('Escape')
  await page.waitForTimeout(200)

  // Move Item 1 down one place -> order becomes 2,1,3,4,5
  reorders.length = 0
  await openMenuFor('Item 1')
  await page.getByRole('menuitem', { name: 'Move down' }).click()
  await page.waitForTimeout(800)
  check('move down POSTs the neighbour index', reorders[0]?.newIndex === 1,
    JSON.stringify(reorders))
  check('order after move down is 2,1,3,4,5',
    (await titles()).slice(0, 3).join(',') === 'Item 2,Item 1,Item 3',
    (await titles()).join(','))

  // And back up again -> 1,2,3,4,5
  reorders.length = 0
  await openMenuFor('Item 1')
  await page.getByRole('menuitem', { name: 'Move up' }).click()
  await page.waitForTimeout(800)
  check('move up restores the original order',
    (await titles()).slice(0, 3).join(',') === 'Item 1,Item 2,Item 3',
    (await titles()).join(','))

  // Under a filter, moving must step past the neighbour the teacher can SEE.
  // Visible-only list is Item 1, Item 3, Item 5 (ordering_idx 0, 2, 4).
  await page.locator('.MuiChip-root', { hasText: 'Visibility' }).click()
  await page.getByRole('menuitem', { name: 'Visible' }).click()
  await page.waitForTimeout(500)
  check('filter leaves the 3 visible items',
    (await titles()).join(',') === 'Item 1,Item 3,Item 5', (await titles()).join(','))
  reorders.length = 0
  await openMenuFor('Item 3')
  await page.getByRole('menuitem', { name: 'Move up' }).click()
  await page.waitForTimeout(800)
  check('filtered move up targets the visible neighbour idx, not idx-1',
    reorders[0]?.newIndex === 0, JSON.stringify(reorders))
  check('filtered move up visibly reorders',
    (await titles()).join(',') === 'Item 3,Item 1,Item 5', (await titles()).join(','))
  await shot('06d-move-filtered')
  // Reset the filter for later sections
  await page.locator('.MuiChip-root').filter({ hasText: 'Visible' }).first().click()
  await page.getByRole('menuitem', { name: 'All', exact: true }).click()
  await page.waitForTimeout(400)

  // ---- 7. Remove from course ----
  console.log('\n7. Remove from course')
  const rowR = page.locator('a[href*="/exercises/"]').first()
  await rowR.hover()
  await rowR.locator('button[aria-label="More options"]').click()
  await page.getByRole('menuitem', { name: 'Remove from course' }).click()
  await page.waitForTimeout(350)
  check('confirm dialog open', await page.locator('[role="dialog"]').isVisible())
  const dialogText = await page.locator('[role="dialog"]').innerText()
  check('confirm names the exercise', /Remove/i.test(dialogText), dialogText.replace(/\n/g, ' | '))
  check('warns about submissions', /deleted for good/i.test(dialogText), dialogText.replace(/\n/g, ' | '))
  await shot('09-remove-confirm')
  const before = await page.locator('a[href*="/exercises/"]').count()
  await page.locator('[role="dialog"]').getByRole('button', { name: 'Remove' }).click()
  await page.waitForTimeout(800)
  check('DELETE sent', deletes.length === 1, JSON.stringify(deletes))
  check('row disappeared',
    (await page.locator('a[href*="/exercises/"]').count()) === before - 1,
    `${before} -> ${await page.locator('a[href*="/exercises/"]').count()}`)

  // ---- 8. Add from library ----
  console.log('\n8. Add from library')
  // Reset the course to exactly one exercise that also exists in the library root
  // (e1 / "Loops and conditions"), so select-all has a real duplicate to skip.
  exercises = [ex({
    course_exercise_id: '400', exercise_id: 'e1', ordering_idx: 0,
    library_title: 'Loops and conditions', effective_title: 'Loops and conditions',
  })]
  await freshLoad()
  const countBeforeAdd = await page.locator('a[href*="/exercises/"]').count()
  await page.getByRole('button', { name: 'Add exercise', exact: true }).click()
  await page.waitForTimeout(250)
  await page.getByRole('menuitem', { name: 'Add exercise from library' }).click()
  await page.waitForTimeout(500)
  check('library dialog open',
    await page.locator('[role="dialog"]').getByText('Add exercise from library').first().isVisible())
  check('root dirs listed', await page.locator('text=Python basics').isVisible())
  await page.locator('[role="dialog"] .MuiListItemButton-root', { hasText: 'Loops and conditions' })
    .locator('svg').last().hover()
  await page.waitForTimeout(500)
  check('already-on-course marker explains itself on hover',
    await page.getByRole('tooltip').filter({ hasText: 'Already on this course' }).isVisible())
  await shot('10-library-root')

  // Select all in this directory: 3 exercises here, one of which (Loops and
  // conditions) is already on the course and must be left alone.
  const selectAll = page.locator('[role="dialog"] [aria-label="Select all in this directory"]')
  check('select-all row present', await selectAll.isVisible())
  check('select-all counts only the addable exercises',
    (await selectAll.innerText()).includes('2 exercises'), await selectAll.innerText())
  await selectAll.click()
  await page.waitForTimeout(300)
  const chipTexts = async () => (await page.locator('[role="dialog"] .MuiChip-root').allInnerTexts())
    .map((x) => x.trim()).sort()
  check('select-all skips the exercise already on the course',
    JSON.stringify(await chipTexts()) === JSON.stringify(['Binary search', 'Sorting algorithms']),
    JSON.stringify(await chipTexts()))
  check('Add button counts 2 after select-all',
    /\(2\)/.test(await page.locator('[role="dialog"]').getByRole('button', { name: /Add/ }).innerText()))
  await shot('10b-select-all')

  // Clicking again clears just this directory's exercises
  await selectAll.click()
  await page.waitForTimeout(300)
  check('select-all toggles off', (await chipTexts()).length === 0, JSON.stringify(await chipTexts()))

  // Indeterminate state when only some are ticked
  await page.locator('[role="dialog"] .MuiListItemButton-root', { hasText: 'Binary search' }).click()
  await page.waitForTimeout(250)
  check('select-all shows indeterminate for a partial selection',
    await selectAll.locator('input[type="checkbox"]')
      .evaluate((el) => el.getAttribute('data-indeterminate') === 'true'))

  // Selection made in another directory must not be cleared by this dir's toggle
  await page.locator('[role="dialog"] .MuiListItemButton-root', { hasText: 'Python basics' }).click()
  await page.waitForTimeout(500)
  const dirSelectAll = page.locator('[role="dialog"] [aria-label="Select all in this directory"]')
  await dirSelectAll.click()
  await page.waitForTimeout(300)
  check('select-all in a subdirectory keeps the earlier selection',
    JSON.stringify(await chipTexts()) === JSON.stringify(['Binary search', 'String formatting']),
    JSON.stringify(await chipTexts()))
  await dirSelectAll.click()
  await page.waitForTimeout(300)
  check('toggling off in a subdirectory leaves the earlier selection intact',
    JSON.stringify(await chipTexts()) === JSON.stringify(['Binary search']),
    JSON.stringify(await chipTexts()))
  // Back to root and clear, so the pre-existing flow below starts clean
  await page.locator('[role="dialog"]').getByText('Exercise library', { exact: true }).click()
    .catch(async () => { await page.locator('[role="dialog"] nav button, [role="dialog"] nav a').first().click() })
  await page.waitForTimeout(500)
  await page.locator('[role="dialog"] .MuiChip-root', { hasText: 'Binary search' })
    .locator('svg').last().click()
  await page.waitForTimeout(300)
  check('cleared before the navigation flow', (await chipTexts()).length === 0,
    JSON.stringify(await chipTexts()))

  // Select one here, navigate into a dir, select another — selection must survive
  await page.locator('[role="dialog"] .MuiListItemButton-root', { hasText: 'Sorting algorithms' }).click()
  await page.waitForTimeout(200)
  await page.locator('[role="dialog"] .MuiListItemButton-root', { hasText: 'Python basics' }).click()
  await page.waitForTimeout(500)
  check('navigated into dir', await page.locator('text=String formatting').isVisible())
  check('selection chip survives navigation',
    await page.locator('[role="dialog"] .MuiChip-root', { hasText: 'Sorting algorithms' }).isVisible())
  await page.locator('[role="dialog"] .MuiListItemButton-root', { hasText: 'String formatting' }).click()
  await page.waitForTimeout(250)
  check('Add button counts 2', /\(2\)/.test(await page.locator('[role="dialog"]').getByRole('button', { name: /Add/ }).innerText()))
  await shot('11-library-selected')
  await page.locator('[role="dialog"]').getByRole('button', { name: /Add/ }).click()
  await page.waitForTimeout(900)
  check('2 POSTs to add to course', adds.length === 2, JSON.stringify(adds))
  check('list grew by the 2 added',
    (await page.locator('a[href*="/exercises/"]').count()) === countBeforeAdd + 2,
    `${countBeforeAdd} -> ${await page.locator('a[href*="/exercises/"]').count()}`)
  await shot('12-after-add')

  // ---- 9. Settings dialog opens from the list ----
  console.log('\n9. Settings from list')
  const rowS = page.locator('a[href*="/exercises/"]').first()
  await rowS.hover()
  await rowS.locator('button[aria-label="More options"]').click()
  await page.getByRole('menuitem', { name: 'Exercise settings' }).click()
  await page.waitForTimeout(900)
  const settingsText = await page.locator('[role="dialog"]').innerText().catch(() => '')
  check('settings dialog opened', /Threshold|Visibility/i.test(settingsText),
    settingsText.slice(0, 120).replace(/\n/g, ' | '))
  await shot('13-settings')
  await page.keyboard.press('Escape')
  await page.waitForTimeout(300)

  // ---- 10. ctrl+click opens a new tab (link semantics) ----
  console.log('\n10. Link semantics')
  const href = await page.locator('a[href*="/exercises/"]').first().getAttribute('href')
  check('row title is a real <a href>', !!href && href.includes('/exercises/'), String(href))

  // ---- 10b. Reorder on a long course scrolls the moved exercise into view ----
  console.log('\n10b. Reorder scroll position')
  exercises = Array.from({ length: 24 }, (_, i) => ex({
    course_exercise_id: String(200 + i), exercise_id: `e${200 + i}`, ordering_idx: i,
    library_title: `Exercise ${i + 1}`, effective_title: `Exercise ${i + 1}`,
  }))
  await freshLoad()
  check('24 exercises listed', (await page.locator('a[href*="/exercises/"]').count()) === 24)

  // Open the reorder dialog for exercise 20 — far below the list's 420px fold
  const lateRow = page.locator('a', { hasText: 'Exercise 20' }).first()
  await lateRow.hover()
  await lateRow.locator('button[aria-label="More options"]').click()
  await page.getByRole('menuitem', { name: 'Move to…' }).click()
  await page.waitForTimeout(700)

  const scrollState = await page.evaluate(() => {
    const dialog = document.querySelector('[role="dialog"]')
    // The scrollable list is the only overflowing overflow-auto box in the dialog
    const list = [...dialog.querySelectorAll('div')].find(
      (d) => getComputedStyle(d).overflowY === 'auto' && d.scrollHeight > d.clientHeight)
    if (!list) return null
    const moved = [...list.querySelectorAll('div')].find(
      (d) => d.textContent?.trim().startsWith('20.'))
    const base = {
      scrollTop: list.scrollTop,
      scrollHeight: list.scrollHeight,
      clientHeight: list.clientHeight,
    }
    if (!moved) return { ...base, found: false }
    const lr = list.getBoundingClientRect()
    const mr = moved.getBoundingClientRect()
    return {
      ...base,
      found: true,
      fullyInView: mr.top >= lr.top - 1 && mr.bottom <= lr.bottom + 1,
    }
  })
  check('list is actually scrollable',
    scrollState !== null && scrollState.scrollHeight > scrollState.clientHeight,
    JSON.stringify(scrollState))
  check('scrolled down rather than left at the top', (scrollState?.scrollTop ?? 0) > 0,
    `scrollTop=${scrollState?.scrollTop}`)
  check('moved exercise fully in view on open', scrollState?.fullyInView === true,
    JSON.stringify(scrollState))
  await shot('16-reorder-scrolled')
  await page.keyboard.press('Escape')
  await page.waitForTimeout(300)

  // ---- 10c. A course with no students shows no progress bar at all ----
  console.log('\n10c. No students')
  exercises = [ex({
    course_exercise_id: '500', exercise_id: 'e500', ordering_idx: 0,
    library_title: 'Nobody enrolled', effective_title: 'Nobody enrolled',
    completed_count: 0, started_count: 0, ungraded_count: 0, unstarted_count: 0,
  })]
  await freshLoad()
  const emptyRow = page.locator('a[href*="/exercises/"]').first()
  check('row still renders', await emptyRow.isVisible())
  const barCount = await barsIn(emptyRow)
  check('no progress bar rendered when there are no students', barCount === 0,
    `${barCount} bar elements`)
  check('no leftover placeholder text',
    !/no students/i.test(await emptyRow.innerText()),
    JSON.stringify(await emptyRow.innerText()))
  await shot('17-no-students')

  // Restore a populated fixture — the dark/mobile sections below assert on bars
  exercises = Array.from({ length: 10 }, (_, i) => ex({
    course_exercise_id: String(600 + i), exercise_id: `e${600 + i}`, ordering_idx: i,
    library_title: `Exercise ${i + 1}`, effective_title: `Exercise ${i + 1}`,
  }))

  // ---- 11. Dark mode + mobile ----
  console.log('\n11. Dark + mobile')
  await browser.close()

  const dark = await launch({ role: 'teacher,admin', theme: 'dark', colorScheme: 'dark', shotPrefix: 'course-ex-' })
  await fakeApi(dark.page, [
    ['/account/checkin', () => ({})],
    ['/courses/teacher', () => ({ courses: [{ id: COURSE_ID, title: 'Programming 101', alias: null, student_count: 12 }] })],
    [`/courses/${COURSE_ID}/groups`, () => ({ groups: GROUPS })],
    [/\/teacher\/courses\/\d+\/exercises(\?|$)/, () => ({ exercises })],
  ])
  await dark.page.goto(url)
  await dark.page.waitForSelector('a[href*="/exercises/"]', { timeout: 15000 })
  await dark.page.waitForTimeout(500)
  await dark.shot('14-dark')
  check('dark mode renders rows', (await dark.page.locator('a[href*="/exercises/"]').count()) > 0)
  await dark.browser.close()

  const mob = await launch({
    role: 'teacher,admin', viewport: { width: 390, height: 780 }, shotPrefix: 'course-ex-',
  })
  await fakeApi(mob.page, [
    ['/account/checkin', () => ({})],
    ['/courses/teacher', () => ({ courses: [{ id: COURSE_ID, title: 'Programming 101', alias: null, student_count: 12 }] })],
    [`/courses/${COURSE_ID}/groups`, () => ({ groups: GROUPS })],
    [/\/teacher\/courses\/\d+\/exercises(\?|$)/, () => ({ exercises })],
  ])
  await mob.page.goto(url)
  await mob.page.waitForSelector('a[href*="/exercises/"]', { timeout: 15000 })
  await mob.page.waitForTimeout(500)
  await mob.shot('15-mobile')
  const scrollsSideways = await mob.page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1)
  check('no horizontal scroll on mobile', !scrollsSideways)
  // On phones the count stands in for the bar — exactly one of the two, never both
  const mobRow = mob.page.locator('a[href*="/exercises/"]').first()
  const mobShape = {
    bars: await barsIn(mobRow),
    text: await mobRow.innerText(),
  }
  check('no progress bar on mobile', mobShape?.bars === 0, JSON.stringify(mobShape))
  check('count shown instead on mobile', /\d+\/\d+/.test(mobShape?.text ?? ''),
    JSON.stringify(mobShape?.text))

  // The add button collapses to an icon-only square, but keeps its name
  const mobAdd = mob.page.getByRole('button', { name: 'Add exercise', exact: true })
  check('add button still reachable by name on mobile', await mobAdd.isVisible())
  check('add button label hidden on mobile',
    (await mobAdd.innerText()).trim() === '', JSON.stringify(await mobAdd.innerText()))
  const mobAddBox = await mobAdd.boundingBox()
  check('add button is roughly square on mobile',
    mobAddBox.width < 60 && mobAddBox.width >= mobAddBox.height - 8,
    `${Math.round(mobAddBox.width)}x${Math.round(mobAddBox.height)}`)
  check('narrower than the labelled desktop button',
    mobAddBox.width < deskAddWidth,
    `mobile ${Math.round(mobAddBox.width)} vs desktop ${Math.round(deskAddWidth)}`)
  await mob.browser.close()

  process.exit(check.summary() ? 0 : 1)
}

run().catch((e) => {
  console.error(e)
  process.exit(1)
})
