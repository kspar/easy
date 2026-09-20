/**
 * Unit S5b — the large monitor again, with a course's worth of data on the page.
 *
 * S5 measured every surface at 2560×1440 and found `main` at 1200px everywhere, but it drove the
 * teacher surfaces against `superset()` — empty lists. An empty grade table does not say whether
 * the cap *costs* anything; thirty-five students by twenty-four exercises does. This drives the
 * three surfaces where width is work — the teacher's grading view, the grade table, the student's
 * editor — with fixtures sized like a real course, at the two monitor sizes people actually own.
 *
 * As first committed (4a2d2627) it shot each surface three ways — as it was, with the shell's cap
 * lifted by one injected rule, and with a CSS-only sketch of the fix — and that report is the
 * before-state: editor 639px on either monitor, 7 of 24 grade columns, a sticky header at −468px.
 * The sketches became EZ-1915, EZ-1916, EZ-1918 and EZ-1919, so they are gone from here and the
 * driver now measures the app as it is. Re-run it after touching any of the four wide pages.
 *
 *   cd web && npx vite --config vite.stub.config.ts --port 5299 --strictPort &
 *   HARNESS_PORT=5299 node tests/audit/s5b-large-monitor-dense.mjs
 */
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { withBrowser, fakeApi, shoot, REPORTS, BASE_URL, waitUntil } from './audit.mjs'

const COURSE = '119'
const CE = '4147'

const MONITORS = [
  ['fhd', { width: 1920, height: 1080 }],
  ['qhd', { width: 2560, height: 1440 }],
]

// --- a course's worth of people -------------------------------------------------------------------

const GIVEN = ['Mari', 'Jaan', 'Kati', 'Peeter', 'Liis', 'Andres', 'Kristiina', 'Martin', 'Anna-Liisa', 'Rasmus', 'Karl-Erik', 'Helena']
const FAMILY = ['Maasikas', 'Tamm', 'Kask', 'Saar', 'Sepp', 'Mägi', 'Kukk', 'Rebane', 'Ilves', 'Pärn', 'Õunapuu', 'Vahtramäe']
const students = Array.from({ length: 35 }, (_, i) => ({
  student_id: `s${i + 1}`,
  given_name: GIVEN[i % GIVEN.length],
  // Offset by the lap, or `i % 12` decides both names and the roster is twelve people three times.
  family_name: FAMILY[(i * 5 + Math.floor(i / GIVEN.length)) % FAMILY.length],
  groups: [{ id: i % 2 ? 'g2' : 'g1', name: i % 2 ? 'Rühm B' : 'Rühm A' }],
}))

const TITLES = [
  'Tere, maailm', 'Muutujad ja avaldised', 'Tingimuslause', 'Liigaasta kontroll', 'Tsükkel while',
  'Tsükkel for ja range', 'Algarvude leidmine', 'Sõnede töötlemine', 'Palindroomi kontroll',
  'Järjendid: keskmine ja mediaan', 'Kahemõõtmelised järjendid', 'Failist lugemine', 'Faili kirjutamine',
  'Funktsioonid ja parameetrid', 'Rekursioon: Fibonacci', 'Rekursioon: Hanoi tornid', 'Sõnastikud',
  'Hulgad ja ennikud', 'Erindite käsitlemine', 'Klassid ja objektid', 'Pärimine', 'Sorteerimine mullimeetodil',
  'Kahendotsing', 'Kontrolltöö 1',
]

const sub = (n, grade, isAutograde) => ({
  id: `sub-${n}`,
  submission_number: n,
  time: '2026-09-01T10:00:00.000Z',
  grade: grade === null ? null : { grade, is_autograde: isAutograde, is_graded_directly: true },
  seen: true,
})

const listExercise = (idx, title) => ({
  course_exercise_id: String(4147 + idx),
  exercise_id: `ex-${idx}`,
  library_title: title,
  title_alias: null,
  effective_title: title,
  grade_threshold: 100,
  student_visible: true,
  student_visible_from: null,
  soft_deadline: null,
  hard_deadline: null,
  grader_type: idx % 4 === 3 ? 'TEACHER' : 'AUTO',
  ordering_idx: idx,
  unstarted_count: 4,
  ungraded_count: idx % 4 === 3 ? 6 : 0,
  started_count: 5,
  completed_count: 20,
  latest_submissions: students.map((s, i) => {
    const k = (i * 7 + idx * 3) % 10
    if (k === 0) return { ...s, status: 'UNSTARTED', submission: null }
    if (k === 1 && idx % 4 === 3) return { ...s, status: 'UNGRADED', submission: sub(1, null, null) }
    const grade = k < 4 ? 40 + k * 10 : 100
    return { ...s, status: grade === 100 ? 'COMPLETED' : 'STARTED', submission: sub(1 + (k % 3), grade, idx % 4 !== 3) }
  }),
})
const exercises = TITLES.map((t, i) => listExercise(i, t))

// --- one exercise, the way a second-month homework actually looks -----------------------------------

const statement =
  '<p>Kirjuta programm, mis loeb failist <code>andmed.txt</code> õpilaste nimed ja hinded ning väljastab iga õpilase kohta tema keskmise hinde, ümardatuna kahe komakohani. Faili iga rida on kujul <code>Eesnimi Perenimi;5;4;3;5</code>.</p>' +
  '<p>Kui failis on vigane rida (puudub semikoolon või hinne ei ole täisarv), tuleb see rida vahele jätta ja väljastada hoiatus kujul <code>Vigane rida: …</code>. Programm ei tohi sellise rea peale katki minna.</p>' +
  '<h3>Näide</h3><pre><code>Mari Maasikas: 4.25\nJaan Tamm: 3.50\nVigane rida: Kati Kask 5 4\n</code></pre>' +
  '<p>Lõpuks väljasta kogu rühma keskmine ning parima keskmisega õpilase nimi. Kui parimaid on mitu, väljasta nad tähestiku järjekorras, igaüks eraldi real.</p>' +
  '<ul><li>Kasuta funktsiooni <code>loe_hinded(failinimi)</code>, mis tagastab sõnastiku.</li><li>Ära kasuta moodulit <code>statistics</code>.</li><li>Faili nimi küsitakse kasutajalt.</li></ul>'

const solution = `def loe_hinded(failinimi):
    """Loeb failist õpilaste hinded ja tagastab sõnastiku kujul {nimi: [hinded]}."""
    tulemus = {}
    with open(failinimi, encoding="utf-8") as fail:
        for reanumber, rida in enumerate(fail, start=1):
            rida = rida.strip()
            if not rida:
                continue
            osad = rida.split(";")
            if len(osad) < 2:
                print(f"Vigane rida: {rida}")
                continue
            nimi, hinded_tekstina = osad[0].strip(), osad[1:]
            try:
                hinded = [int(hinne) for hinne in hinded_tekstina if hinne.strip() != ""]
            except ValueError:
                print(f"Vigane rida: {rida}  # rida {reanumber}, hinnet ei saanud täisarvuks teisendada")
                continue
            tulemus[nimi] = tulemus.get(nimi, []) + hinded
    return tulemus


def keskmine(arvud):
    return sum(arvud) / len(arvud) if len(arvud) > 0 else 0.0


def parimad(keskmised):
    parim = max(keskmised.values())
    return sorted(nimi for nimi, väärtus in keskmised.items() if abs(väärtus - parim) < 1e-9)


failinimi = input("Sisesta faili nimi: ")
hinded = loe_hinded(failinimi)
keskmised = {nimi: keskmine(õpilase_hinded) for nimi, õpilase_hinded in hinded.items()}
for nimi, väärtus in keskmised.items():
    print(f"{nimi}: {väärtus:.2f}")

print(f"Rühma keskmine: {keskmine([h for õpilase_hinded in hinded.values() for h in õpilase_hinded]):.2f}")
for nimi in parimad(keskmised):
    print(nimi)
`

const feedback = JSON.stringify({
  result_type: 'OK_V3',
  producer: 'tiivad 3.2.0',
  pre_evaluate_error: null,
  points: 75,
  tests: [
    ['Korrektne fail kolme õpilasega', 'PASS'],
    ['Vigane rida jäetakse vahele', 'PASS'],
    ['Tühi fail', 'FAIL'],
    ['Mitu parimat õpilast tähestiku järjekorras', 'PASS'],
  ].map(([title, status]) => ({
    title,
    status,
    exception_message: status === 'FAIL' ? 'ValueError: max() arg is an empty sequence' : null,
    user_inputs: ['andmed.txt'],
    created_files: [{ name: 'andmed.txt', content: status === 'FAIL' ? '' : 'Mari Maasikas;5;4;3;5\nJaan Tamm;3;4\n' }],
    actual_output: status === 'FAIL' ? 'Sisesta faili nimi: ' : 'Sisesta faili nimi: Mari Maasikas: 4.25\nJaan Tamm: 3.50\nRühma keskmine: 4.00\nMari Maasikas\n',
    converted_submission: null,
    checks: [{ title: `Väljund sisaldab oodatud ridu (${title.toLowerCase()})`, status, feedback: status === 'FAIL' ? 'Programm lõpetas veaga enne väljundi trükkimist.' : '' }],
  })),
})

const teacherExercise = {
  exercise_id: '9001',
  title: 'Failist lugemine: hinnete keskmised',
  title_alias: null,
  text_html: statement,
  text_md: null,
  instructions_html: null,
  instructions_md: null,
  soft_deadline: '2026-09-25T20:59:00.000Z',
  hard_deadline: null,
  grader_type: 'AUTO',
  solution_file_name: 'lahendus.py',
  solution_file_type: 'TEXT_EDITOR',
  threshold: 100,
  last_modified: '2026-07-30T12:00:00.000Z',
  student_visible: true,
  student_visible_from: null,
  assessments_student_visible: true,
  grading_script: null,
  container_image: null,
  max_time_sec: null,
  max_mem_mb: null,
  assets: null,
  executors: null,
  has_lib_access: true,
  exception_students: null,
  exception_groups: null,
}

const submissionDetail = {
  id: 'sub-77',
  solution,
  submission_number: 3,
  created_at: '2026-09-01T10:00:00.000Z',
  grade: { grade: 75, is_autograde: true, is_graded_directly: true },
  seen: true,
  autograde_status: 'COMPLETED',
  auto_assessment: { grade: 75, feedback },
}

const course = { title: 'Programmeerimine', alias: null, archived: false, color: '#1976d2', course_code: 'LTAT.03.001', moodle_course_url: null }

const teacherHandlers = [
  ['/account/checkin', () => ({})],
  [`/courses/${COURSE}/basic`, () => course],
  [`/courses/${COURSE}/groups`, () => ({ groups: [{ id: 'g1', name: 'Rühm A' }, { id: 'g2', name: 'Rühm B' }] })],
  [`/student/courses/${COURSE}/exercises`, () => ({ exercises: [] })],
  [/\/submissions\/latest\/students(\?|$)/, () => ({ ...exercises[11], course_exercise_id: CE, effective_title: teacherExercise.title })],
  [/\/submissions\/all\/students\//, () => ({
    submissions: [3, 2, 1].map((n) => ({
      id: n === 3 ? 'sub-77' : `sub-7${n}`,
      submission_number: n,
      created_at: `2026-09-01T1${n}:00:00.000Z`,
      status: n === 3 ? 'STARTED' : 'STARTED',
      grade: { grade: 25 * n, is_autograde: true, is_graded_directly: true },
    })),
  })],
  [/\/students\/[^/]+\/activities$/, () => ({ teacher_activities: [] })],
  [/\/students\/[^/]+\/inline-comments$/, () => ({ inline_comments: [] })],
  [/\/submissions\/[^/]+$/, () => submissionDetail],
  [new RegExp(`/teacher/courses/${COURSE}/exercises/${CE}(\\?|$)`), () => teacherExercise],
  [new RegExp(`/teacher/courses/${COURSE}/exercises(\\?|$)`), () => ({ exercises })],
  [/\/teacher\/courses(\?|$)/, () => ({ courses: [] })],
]

const studentHandlers = [
  ['/account/checkin', () => ({})],
  [`/courses/${COURSE}/basic`, () => course],
  [/\/student\/courses(\?|$)/, () => ({ courses: [{ id: COURSE, title: course.title, alias: null, archived: false, color: course.color, course_code: course.course_code, last_accessed: null }] })],
  [new RegExp(`/student/courses/${COURSE}/exercises(\\?|$)`), () => ({ exercises: [] })],
  [new RegExp(`/exercises/${CE}/submissions/all`), () => ({
    submissions: [{
      id: '9001', number: 3, solution, submission_time: '2026-09-01T10:19:00.000Z', autograde_status: 'COMPLETED',
      grade: { grade: 75, is_autograde: true, is_graded_directly: true }, submission_status: 'STARTED',
      auto_assessment: { grade: 75, feedback },
    }],
  })],
  [new RegExp(`/exercises/${CE}/activities`), () => ({ teacher_activities: [] })],
  [new RegExp(`/exercises/${CE}/draft`), () => ({})],
  [new RegExp(`/exercises/${CE}(\\?|$)`), () => ({
    effective_title: teacherExercise.title, text_html: statement, deadline: teacherExercise.soft_deadline, grader_type: 'AUTO',
    threshold: 100, instructions_html: null, is_open: true, solution_file_name: 'lahendus.py', solution_file_type: 'TEXT_EDITOR',
  })],
]

const SURFACES = [
  { name: 'teacher-students', role: 'teacher,admin', handlers: teacherHandlers, path: `/courses/${COURSE}/exercises/${CE}`, ready: 'Maasikas' },
  { name: 'teacher-grading', role: 'teacher,admin', handlers: teacherHandlers, path: `/courses/${COURSE}/exercises/${CE}?student=s1`, ready: 'loe_hinded' },
  { name: 'grade-table', role: 'teacher,admin', handlers: teacherHandlers, path: `/courses/${COURSE}/grades`, ready: 'Maasikas' },
  { name: 'student-exercise', role: 'student', handlers: studentHandlers, path: `/courses/${COURSE}/exercises/${CE}`, ready: 'loe_hinded' },
]

const measure = (page) =>
  page.evaluate(() => {
    const w = (el) => (el ? Math.round(el.getBoundingClientRect().width) : null)
    const main = document.querySelector('main')
    const scroller = document.querySelector('.cm-scroller')
    const lines = [...document.querySelectorAll('.cm-line')]
    const tableBox = document.querySelector('.MuiTableContainer-root')
    const table = tableBox?.querySelector('table')
    const heads = [...document.querySelectorAll('thead th')]
    // The content's own bottom edge, not the document's — a framed page is always 100dvh tall.
    const bottoms = [...(main?.querySelectorAll('*') ?? [])]
      .filter((el) => el.children.length === 0 && el.getBoundingClientRect().height > 0)
      .map((el) => el.getBoundingClientRect().bottom)
    return {
      viewportW: document.documentElement.clientWidth,
      viewportH: document.documentElement.clientHeight,
      mainW: w(main),
      usedPct: main ? Math.round((main.getBoundingClientRect().width / document.documentElement.clientWidth) * 100) : null,
      editorW: w(scroller),
      // How far the longest code line runs past the editor's visible width.
      codeClippedPx: scroller ? Math.max(0, scroller.scrollWidth - scroller.clientWidth) : null,
      codeLinesShown: scroller ? lines.filter((l) => { const r = l.getBoundingClientRect(); const s = scroller.getBoundingClientRect(); return r.top >= s.top && r.bottom <= s.bottom }).length : null,
      codeLinesTotal: lines.length || null,
      tableW: w(table),
      tableBoxW: w(tableBox),
      tableHiddenPx: tableBox ? Math.max(0, tableBox.scrollWidth - tableBox.clientWidth) : null,
      // Either direction: a one-line header overflows sideways, a line-clamped one downwards.
      headersTruncated: heads.filter((th) => [...th.querySelectorAll('*')].some((el) => el.scrollWidth > el.clientWidth + 1 || el.scrollHeight > el.clientHeight + 1)).length,
      headers: heads.length,
      contentBottom: bottoms.length ? Math.round(Math.max(...bottoms)) : null,
      docScrollH: document.documentElement.scrollHeight,
    }
  })

const results = []

for (const [vpName, viewport] of MONITORS) {
  for (const s of SURFACES) {
    await withBrowser(async ({ launch }) => {
      const { page } = await launch({ role: s.role, language: 'et', viewport })
      await fakeApi(page, s.handlers, { log: false, contract: false })
      try {
        await page.goto(`${BASE_URL}${s.path}`, { timeout: 20000 })
        await waitUntil(async () => (await page.getByText(s.ready).count()) > 0, { timeout: 12000 })
        await page.waitForTimeout(1200)
        const m = await measure(page)
        // Does the table's header survive scrolling the roster? `stickyHeader` sticks to the nearest
        // scroll container, and one that only scrolls sideways never moves it. The wheel goes to
        // whatever is under the pointer, so put the pointer on the table: whether it is the table
        // or the window that then scrolls is half of what is being asked.
        if (s.name === 'grade-table') {
          // Near the left: the table is only as wide as its columns, and the middle may be past it.
          await page.mouse.move(400, viewport.height / 2)
          await page.mouse.wheel(0, 900)
          await page.waitForTimeout(300)
          Object.assign(m, await page.evaluate(() => ({
            // A cell, not the `thead`: MUI's `stickyHeader` makes each `th` sticky and lets the row
            // element scroll away underneath them, so the row's own position says nothing.
            headerTopAfterScroll: Math.round(document.querySelector('thead th:nth-child(3)')?.getBoundingClientRect().top ?? NaN),
            windowScrolledBy: Math.round(window.scrollY),
            tableScrolledBy: Math.round(document.querySelector('.MuiTableContainer-root')?.scrollTop ?? NaN),
          })))
        }
        results.push({ surface: s.name, viewport: vpName, ...m })
        console.log(`${vpName} ${s.name.padEnd(18)} main ${m.mainW}/${m.viewportW} (${m.usedPct}%)  editor ${m.editorW ?? '-'} clipped ${m.codeClippedPx ?? '-'}px  table hidden ${m.tableHiddenPx ?? '-'}px  truncated heads ${m.headersTruncated}/${m.headers}  thead top after scroll ${m.headerTopAfterScroll ?? '-'}`)
        await shoot(page, `s5b-${vpName}-${s.name}`, { fullPage: false })
      } catch (e) {
        console.log(`${vpName} ${s.name} FAILED: ${e.message.split('\n')[0].slice(0, 120)}`)
        results.push({ surface: s.name, viewport: vpName, error: e.message.split('\n')[0] })
        await shoot(page, `s5b-${vpName}-${s.name}-FAILED`, { fullPage: false })
      }
      await page.close()
    })
  }
}

const path = join(REPORTS, 's5b-large-monitor-dense.json')
writeFileSync(path, JSON.stringify({ sha: process.env.AUDIT_SHA ?? 'unknown', results }, null, 2))
console.log(`\nreport written to ${path}`)
