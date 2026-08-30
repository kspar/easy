/**
 * Unit T6 — the TSL builder at every viewport, in both themes, in Estonian.
 *
 * This is where three things the plan flagged separately actually meet: the deepest nesting in the
 * application (card → check card → fields), `TslTestBody`'s seven `minWidth` values and
 * `TslSections`' five, and the longest Estonian string in the app —
 * `tsl.containsName.KEYWORD_WITH_PRECEDING_ARG` at 2.42× its English source. The spec below is built
 * to render exactly those: a filled-in function-execution test and a contains test using the
 * keyword-with-preceding-arg mode.
 *
 * Measures horizontal overflow rather than eyeballing it, and names the widest offending element, so a
 * finding can say *what* is too wide instead of that something is.
 *
 *   HARNESS_PORT=5299 node tests/audit/t6-tsl-under-pressure.mjs
 */
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { withBrowser, fakeApi, shoot, REPORTS, VIEWPORTS, BASE_URL, waitUntil, AUTO_ASSESS_TAB } from './audit.mjs'
import { baseHandlers } from './fixtures.mjs'

const EX_ID = '4242'

const RICH_SPEC = {
  language: 'python3',
  validateFiles: true,
  requiredFiles: ['lahendus.py'],
  tslVersion: '1.0',
  tests: [
    {
      type: 'function_execution_test',
      id: 111,
      name: 'Funktsioon liida peab tagastama summa',
      functionName: 'liida',
      functionType: 'FUNCTION',
      createObject: null,
      arguments: ['2, 3', '-4, 2'],
      standardInputData: ['2', '3'],
      inputFiles: [{ fileName: 'sisend.txt', fileContent: 'rida1\nrida2' }],
      genericChecks: [
        {
          id: 900,
          checkType: 'ALL_OF_THESE',
          expectedValue: ['5', '-2'],
          elementsOrdered: true,
          dataCategory: 'CONTAINS_NUMBERS',
          beforeMessage: '',
          passedMessage: 'Väljund oli ootuspärane',
          failedMessage: 'Väljundis ei olnud oodatud arve',
        },
      ],
      returnValueCheck: {
        returnValue: '5',
        beforeMessage: '',
        passedMessage: 'Tagastas õige väärtuse',
        failedMessage: 'Tagastas vale väärtuse',
      },
      paramValueChecks: [
        { paramNumber: 0, expectedValue: '2', beforeMessage: '', passedMessage: 'ok', failedMessage: 'vale' },
      ],
      outputFileChecks: [],
    },
    {
      // The 2.42x Estonian string lives on this mode.
      type: 'contains_test',
      id: 222,
      name: null,
      scope: 'PROGRAM',
      containsWhat: 'KEYWORD_WITH_PRECEDING_ARG',
      containsWhatArg: 'import',
      functionName: null,
      className: null,
      genericCheck: {
        checkType: 'ANY_OF_THESE',
        expectedValue: ['math', 'random'],
        beforeMessage: '',
        passedMessage: 'Moodul imporditud',
        failedMessage: 'Moodulit ei imporditud',
      },
    },
  ],
}

const exercise = () => ({
  dir_id: 'root', effective_access: 'PRAWM', created_at: '2026-08-23T10:00:00.000Z',
  is_public: false, is_anonymous_autoassess_enabled: false, owner_id: 'kspar',
  last_modified: '2026-08-23T10:00:00.000Z', last_modified_by_id: 'kspar',
  grader_type: 'AUTO', solution_file_name: 'lahendus.py', solution_file_type: 'TEXT_EDITOR',
  title: 'Kahe arvu summa', text_html: '<p>Liida kaks arvu.</p>', text_md: 'Liida kaks arvu.',
  anonymous_autoassess_template: '', grading_script: 'python generated_0.py',
  container_image: 'tiivad:tsl-compose', max_time_sec: 7, max_mem_mb: 30,
  assets: [{ file_name: 'tsl.json', file_content: JSON.stringify(RICH_SPEC, null, 4) }],
  executors: null, on_courses: [], on_courses_no_access: 0,
})

const results = []

for (const [vpName, viewport] of [
  ['phone', VIEWPORTS.phone],
  ['laptop', VIEWPORTS.laptop],
  ['monitor', VIEWPORTS.monitor],
]) {
  for (const theme of ['light', 'dark']) {
    await withBrowser(async ({ launch }) => {
      const { page } = await launch({ role: 'teacher', language: 'et', theme, colorScheme: theme, viewport })
      await fakeApi(
        page,
        [
          ...baseHandlers(),
          ['/tsl/compile', () => ({
            scripts: [{ name: 'generated_0.py', value: '# compiled\nfrom tiivad import *\n' }],
            meta: { timestamp: 'x', compiler_version: '1', backend_id: 'tiivad', backend_version: '?' },
            feedback: null,
          })],
          [new RegExp(`/exercises/${EX_ID}(\\?|$)`), () => exercise()],
          [/\/lib\/dirs\/[^/]+\/parents/, () => ({ parents: [] })],
          [/\/v2\//, () => ({ courses: [], exercises: [], dirs: [], count: 0 })],
        ],
        { log: false, contract: false },
      )

      await page.goto(`${BASE_URL}/library/exercise/${EX_ID}/x`)
      await waitUntil(async () => (await page.getByText('Kahe arvu summa').count()) > 0, { timeout: 15000 })
      await page.getByRole('button', { name: /^Muuda/i }).first().click()
      await page.waitForTimeout(800)
      const autoTab = page.getByRole('tab', { name: AUTO_ASSESS_TAB }).first()
      if (await autoTab.count()) await autoTab.click()
      await page.waitForTimeout(1500)

      // Expand the filled-in test so the deep nesting is on screen.
      const card = page.getByText('Funktsioon liida peab tagastama summa').first()
      const expanded = (await card.count()) > 0
      if (expanded) {
        await card.click()
        await page.waitForTimeout(1200)
      }

      const m = await page.evaluate(() => {
        const de = document.documentElement
        const overflow = de.scrollWidth - de.clientWidth
        // Name the widest thing that sticks out past the viewport.
        let worst = null
        for (const el of document.querySelectorAll('main *')) {
          const r = el.getBoundingClientRect()
          if (r.width === 0) continue
          const over = Math.round(r.right - de.clientWidth)
          if (over > 2 && (!worst || over > worst.over)) {
            worst = {
              over,
              width: Math.round(r.width),
              tag: el.tagName.toLowerCase(),
              cls: (el.className?.toString() ?? '').split(' ').filter((c) => c.startsWith('Mui')).slice(0, 2).join('.'),
              text: (el.textContent ?? '').trim().replace(/\s+/g, ' ').slice(0, 60),
            }
          }
        }
        // The longest visible label, to see whether the 2.42x string wraps or truncates.
        let longest = ''
        for (const el of document.querySelectorAll('main label, main .MuiFormLabel-root, main .MuiTypography-root')) {
          const t = (el.textContent ?? '').trim()
          if (t.length > longest.length) longest = t
        }
        return {
          viewport: { w: innerWidth, h: innerHeight },
          horizontalOverflowPx: overflow,
          worstOffender: worst,
          longestVisibleLabel: longest.slice(0, 90),
          clipped: [...document.querySelectorAll('main *')].filter((el) => el.scrollWidth > el.clientWidth + 2).length,
        }
      })

      results.push({ viewport: vpName, theme, expanded, ...m })
      console.log(
        `\n[${vpName} ${m.viewport.w}x${m.viewport.h} ${theme}] card expanded: ${expanded}` +
          `\n   horizontal overflow: ${m.horizontalOverflowPx}px` +
          `\n   elements clipping their own content: ${m.clipped}` +
          (m.worstOffender
            ? `\n   worst offender: +${m.worstOffender.over}px  ${m.worstOffender.tag}.${m.worstOffender.cls} w=${m.worstOffender.width} "${m.worstOffender.text}"`
            : '\n   worst offender: none') +
          `\n   longest visible label: "${m.longestVisibleLabel}"`,
      )
      await shoot(page, `t6-${vpName}-${theme}`)
      await page.close()
    })
  }
}

const path = join(REPORTS, 't6-tsl-under-pressure.json')
writeFileSync(path, JSON.stringify(results, null, 2))
console.log(`\nreport written to ${path}`)
