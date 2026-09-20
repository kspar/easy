/**
 * Image sizing in rendered Markdown: the default cap, and the author's `width` overruling it
 * (EZ-1914).
 *
 * Core's half — that `![alt](url){width=300}` becomes `width="300"` — is pinned by
 * `MarkdownServiceTest`. This is the half only a browser can answer, because every rule under test
 * is a layout rule: two maxima resolved against an intrinsic aspect ratio, a presentational
 * attribute against an author-level `height: auto`. None of that exists until something lays the
 * page out, and each of them fails without an error — the image is simply the wrong size.
 *
 * The images are SVG data URLs so that the spec carries its own pixels: an intrinsic size is the
 * input here, and a stubbed `/v2/resource/` would be one more thing to keep in step with it.
 *
 * Run on the library page below the `lg` breakpoint, where the preview is a single column as wide
 * as the page. That is the view the issue was reported from, and the only one in which "capped" and
 * "as wide as the pane" are different numbers by a margin no rounding could explain.
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const ID = '4344'
const DIR = '78'

const svg = (w, h) =>
  `data:image/svg+xml,${encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">` +
      `<rect width="${w}" height="${h}" fill="#2e7d32"/></svg>`,
  )}`

const img = (alt, w, h, attrs = '') => `<p><img src="${svg(w, h)}" alt="${alt}"${attrs}></p>`

const TEXT_HTML = [
  img('wide', 2000, 1000),
  img('small', 100, 50),
  img('tall', 400, 2000),
  img('sized', 2000, 1000, ' width="300"'),
  img('percent', 2000, 1000, ' width="100%"'),
  img('oversized', 2000, 1000, ' width="3000" height="1500"'),
  img('height-only', 2000, 1000, ' height="100"'),
].join('\n')

const EXERCISE = {
  dir_id: DIR,
  effective_access: 'PRAWM',
  created_at: '2026-01-01T10:00:00.000Z',
  is_public: false,
  is_anonymous_autoassess_enabled: false,
  owner_id: 'kspar',
  last_modified: '2026-07-30T12:00:00.000Z',
  last_modified_by_id: 'kspar',
  grader_type: 'TEACHER',
  solution_file_name: 'lahendus.py',
  solution_file_type: 'TEXT_EDITOR',
  title: 'Sized images',
  text_html: TEXT_HTML,
  text_md: '![wide](wide.svg)',
  anonymous_autoassess_template: '',
  grading_script: null,
  container_image: null,
  max_time_sec: null,
  max_mem_mb: null,
  assets: [],
  executors: [],
  on_courses: [],
  on_courses_no_access: 0,
}

test('markdown-image-size', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'teacher,admin', shotPrefix: 'md-image-size-' })

  await fakeApi(page, [
    ['/account/checkin', () => ({})],
    ['/preview/markdown', () => ({ content: TEXT_HTML })],
    ['/teacher/courses', () => ({ courses: [] })],
    [`/lib/dirs/${DIR}/parents`, () => ({ parents: [{ id: DIR, name: 'Pictures' }] })],
    [new RegExp(`/exercises/${ID}(\\?|$)`), () => EXERCISE],
  ])

  await page.goto(`${BASE_URL}/library/exercise/${ID}/sized-images`)
  await page.waitForSelector('text=Sized images')

  // Laid out, not merely present: an `img` has a 0×0 box until its source has decoded, and every
  // number below would be read as zero on a machine fast enough to get there first.
  const allLoaded = () =>
    page.evaluate(() =>
      [...document.querySelectorAll('img[alt]')].every((i) => i.complete && i.naturalWidth > 0),
    )
  check('the images load', await waitUntil(allLoaded))

  const box = async (alt) => {
    const b = await page.locator(`img[alt="${alt}"]`).boundingBox()
    return { w: Math.round(b.width), h: Math.round(b.height) }
  }
  // The width an image is *allowed*: its paragraph's. Read rather than assumed, so the spec does
  // not encode the page's padding.
  const paneWidth = Math.round(
    (await page.locator('img[alt="wide"]').locator('xpath=..').boundingBox()).width,
  )
  const rem = await page.evaluate(() => parseFloat(getComputedStyle(document.documentElement).fontSize))
  const CAP_W = 32 * rem
  const CAP_H = 28 * rem

  // The premise. If the pane were narrower than the cap, every "capped" check below would pass
  // against the old `max-width: 100%` too, and the spec would be testing nothing.
  check('the pane is wider than the default cap', paneWidth > CAP_W + 100)

  // --- no size given: the default --------------------------------------------------------------
  const wide = await box('wide')
  check('a wide image is capped to the default width', wide.w === CAP_W)
  check('and keeps its aspect ratio', wide.h === CAP_W / 2)

  const small = await box('small')
  check('a small image is not enlarged', small.w === 100 && small.h === 50)

  const tall = await box('tall')
  check('a tall image is capped by height', tall.h === CAP_H)
  check('and its width follows', tall.w === Math.round(CAP_H / 5))

  // --- a width given: the author decides -------------------------------------------------------
  const sized = await box('sized')
  check('width=300 is 300 wide', sized.w === 300 && sized.h === 150)

  const percent = await box('percent')
  check('width=100% fills the pane, past the default cap', percent.w === paneWidth)
  check('with no height cap either', percent.h === Math.round(paneWidth / 2))

  const oversized = await box('oversized')
  check('a width wider than the pane is clamped to it', oversized.w === paneWidth)
  check('without being squashed', oversized.h === Math.round(paneWidth / 2))

  const heightOnly = await box('height-only')
  check('a lone height is honoured', heightOnly.h === 100 && heightOnly.w === 200)

  // The one thing the old rule existed for and this must not give back.
  check(
    'nothing scrolls the page sideways',
    await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
  )
  await shot('01-rendered')

  await close()
})
