import AxeBuilder from '@axe-core/playwright'
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const BASELINE_PATH = join(HERE, '../a11y-baseline.json')

/**
 * Accessibility checks, run **inside existing specs at states they have already reached**.
 *
 * That placement is the whole design. The interesting violations are in states — a dialog open, an
 * accordion expanded, a table sorted — and only the spec that drove the app there knows how to get
 * there. A separate a11y suite would re-navigate to the states that are easy to reach, which are
 * the ones least likely to be broken.
 *
 * ### Fingerprints, not messages
 *
 * A finding is identified by rule id plus a *normalised* selector. The raw selector is unusable as
 * an identity: it carries `nth-child` indices that shift when a list gains a row, and emotion's
 * generated class names (`css-1a2b3c`) which change whenever a style does. Both would make the
 * baseline churn on changes that have nothing to do with accessibility, and a baseline that churns
 * is one people regenerate without reading.
 *
 * ### The baseline can only shrink
 *
 * - a **new** fingerprint fails the run — that is a regression
 * - a fingerprint that **no longer fires** also fails — the entry is stale and must be deleted, so
 *   the file stays an honest list of what is still wrong rather than a growing archive
 * - an entry with no `note` or no `issue` is **rejected at load**, so nothing gets silenced without
 *   somebody writing down why and where it is tracked
 *
 * ### What is deliberately not in the gate
 *
 * `color-contrast`. It is theme-wide and font-stack dependent, so one palette decision produces
 * hundreds of findings that are all the same decision; and it is a design question rather than a
 * deploy blocker. It is still *run* — see `contrastFindings` — so it can be reported without
 * failing a build.
 */
const GATE_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']

/** Rules excluded from the gate, with the reason. Still run and still reported. */
const NOT_IN_GATE = {
  'color-contrast':
    'Theme-wide and font-stack dependent: one palette decision becomes hundreds of findings, and it ' +
    'is a design call rather than a deploy blocker. Reported, never fatal.',
}

/**
 * Make a selector stable across unrelated change.
 *
 * `nth-child(4)` becomes `nth-child(n)` because a list gaining a row is not an accessibility
 * change, and emotion's hashed class names go entirely because they change with any style edit.
 */
export function normaliseSelector(selector) {
  return (
    String(selector)
      .replace(/:nth-child\(\d+\)/g, ':nth-child(n)')
      .replace(/:nth-of-type\(\d+\)/g, ':nth-of-type(n)')
      // MUI labels its emotion classes with the component they style, so one class is
      // `css-1wxaqej-MuiButtonBase-root-MuiIconButton-root`. Drop the hash and keep the label: the
      // hash changes with any style edit, the label is what tells a reader which control this is.
      .replace(/\.css-[a-z0-9]+-/gi, '.')
      .replace(/\.css-[a-z0-9]+/gi, '')
      // styled-components' own generated names, which carry no label worth keeping.
      .replace(/\.e[a-z0-9]{8,}/gi, '')
      .replace(/\s+/g, ' ')
      .trim()
  )
}

/** `rule@selector` — the identity a baseline entry is keyed by. */
export function fingerprint(ruleId, selector) {
  return `${ruleId}@${normaliseSelector(selector)}`
}

/**
 * Load and validate the baseline.
 *
 * Rejecting an entry without `note` or `issue` at load rather than at use is deliberate: a silenced
 * finding with no reason is indistinguishable from one nobody has looked at, and the moment to
 * demand the reason is when it is being added.
 */
export function loadBaseline(raw = readFileSync(BASELINE_PATH, 'utf8')) {
  const parsed = JSON.parse(raw)
  const entries = parsed.known ?? {}

  const bad = Object.entries(entries)
    .filter(([, v]) => !v || !String(v.note ?? '').trim() || !String(v.issue ?? '').trim())
    .map(([k]) => k)

  if (bad.length) {
    throw new Error(
      `a11y-baseline.json entries must carry both a "note" and an "issue":\n` +
        bad.map((k) => `  ${k}`).join('\n') +
        `\n\nAn entry without them is a finding silenced for no recorded reason, which is the same ` +
        `as not having looked at it.`,
    )
  }
  return entries
}

/**
 * Run axe against the current page state.
 *
 * Returns `{ gate, contrast }` — findings that count, and the ones that are reported only. The
 * caller records them; nothing here throws, so one spec reports every state it visited rather than
 * dying at the first.
 */
export async function scan(page, { include } = {}) {
  let builder = new AxeBuilder({ page }).withTags(GATE_TAGS)
  if (include) builder = builder.include(include)

  const { violations } = await builder.analyze()

  const flat = violations.flatMap((v) =>
    v.nodes.map((n) => ({
      rule: v.id,
      impact: v.impact,
      help: v.help,
      selector: Array.isArray(n.target) ? n.target.join(' ') : String(n.target),
      summary: (n.failureSummary || '').split('\n').slice(0, 2).join(' ').trim(),
    })),
  )

  // Our own rules, folded in as findings so they flow through the same fingerprints, the same
  // baseline and the same gate. axe has nothing to say about any of the three.
  flat.push(...(await checkMainLandmark(page)))
  flat.push(...(await checkDuplicateLinkNames(page)))
  flat.push(...(await checkFocusVisible(page)))

  return {
    gate: flat.filter((f) => !(f.rule in NOT_IN_GATE)),
    contrast: flat.filter((f) => f.rule in NOT_IN_GATE),
  }
}

/**
 * Every page needs one `<main>`.
 *
 * Without it there is nothing to skip to — a screen-reader user tabs through the whole sidebar on
 * every navigation — and the two checks below have no region to scope themselves to, which is why
 * this one runs first. axe's own `region` rule is about content *outside* landmarks and does not
 * ask whether `main` exists at all.
 *
 * Recorded as a defect in `doc/testing-log.md` (#17) before there was anything to catch it.
 */
export async function checkMainLandmark(page) {
  const count = await page.locator('main, [role=main]').count()
  if (count === 1) return []

  return [{
    rule: count === 0 ? 'main-landmark-missing' : 'main-landmark-duplicated',
    impact: 'serious',
    help: count === 0
      ? 'The page has no <main> landmark, so there is nothing to skip to'
      : `The page has ${count} <main> landmarks; exactly one is meaningful`,
    selector: 'body',
    summary: `found ${count}`,
  }]
}

/**
 * Two links inside `<main>` with the same words and different destinations.
 *
 * WCAG 2.4.4: a link's purpose has to be clear from its text. A screen-reader user listing the
 * links on a page hears the names without the surrounding table row or card, so two "Open"s going
 * to different places are indistinguishable — and the fix is usually a `aria-label` naming the
 * thing, not a redesign.
 *
 * Deliberately narrow, because the obvious version of this rule is unusable. "No duplicate
 * accessible names" fires on every table where each row has an Edit button, which is a legitimate
 * pattern that context resolves; requiring *different destinations* is what makes a finding
 * actionable rather than noise. The converse — different words, same href — is fine and not
 * reported.
 */
export async function checkDuplicateLinkNames(page) {
  const dupes = await page.evaluate(() => {
    const main = document.querySelector('main, [role=main]')
    if (!main) return []

    const name = (el) =>
      (el.getAttribute('aria-label') || el.textContent || '').replace(/\s+/g, ' ').trim()

    const byName = new Map()
    for (const a of main.querySelectorAll('a[href]')) {
      const n = name(a)
      if (!n) continue
      const href = a.getAttribute('href')
      const seen = byName.get(n) ?? new Set()
      seen.add(href)
      byName.set(n, seen)
    }
    return [...byName]
      .filter(([, hrefs]) => hrefs.size > 1)
      .map(([n, hrefs]) => ({ name: n, hrefs: [...hrefs].slice(0, 3) }))
  })

  return dupes.map((d) => ({
    rule: 'duplicate-link-name',
    impact: 'moderate',
    help: `${d.hrefs.length} links inside <main> are called "${d.name}" but go to different places`,
    // The name, not a selector: it is what identifies the finding and what a fix changes.
    selector: `a[name="${d.name}"]`,
    summary: d.hrefs.join(' , '),
  }))
}

/**
 * Everything reachable by Tab has to be **visibly** focused when it gets there.
 *
 * A focus ring removed for looks is the single change that makes an application unusable by keyboard
 * while looking perfect to everyone else, and no automated rule catches it: axe checks that things
 * *can* be focused, never that you can see where you are.
 *
 * Driven with real `Tab` presses rather than `element.focus()`, and that is not a detail. MUI styles
 * its rings with `:focus-visible`, which the browser only applies to focus it considers
 * keyboard-driven — a programmatic `.focus()` leaves the pseudo-class off and every element would
 * report as unstyled. The first version of this did exactly that and produced 40 false findings.
 */
export async function checkFocusVisible(page, { maxStops = 40 } = {}) {
  const seen = new Set()

  // Pass 1: tab through, tagging each stop and recording how it looks *while focused*.
  //
  // Comparing focused against unfocused, rather than looking for an outline, is the correction that
  // makes this usable. The first version required `outline` or `box-shadow` and reported seven
  // violations — every one a false positive, all of them carrying `Mui-focusVisible`, because MUI
  // indicates focus on these components with a background change. A rule that prescribes *how*
  // focus is shown fails on any design that shows it differently, which is most of them. What
  // matters is that something changes, so that is what is measured.
  // Freeze transitions for the duration of the check.
  //
  // MUI animates `background-color` on focus, so `getComputedStyle` immediately after a Tab — or
  // immediately after a blur — returns a value part-way through the transition. Both readings are
  // then arbitrary points on a curve rather than the styles a user ends up looking at, and they can
  // compare equal by coincidence. Removing the transition changes only how fast the element gets to
  // its final appearance, which is not what this measures.
  const FREEZE_ID = 'a11y-freeze-transitions'
  await page.evaluate((id) => {
    const style = document.createElement('style')
    style.id = id
    style.textContent = '*, *::before, *::after { transition: none !important; animation: none !important; }'
    document.head.appendChild(style)
    if (document.activeElement && document.activeElement !== document.body) document.activeElement.blur()
  }, FREEZE_ID)

  const stops = []
  for (let i = 0; i < maxStops; i++) {
    await page.keyboard.press('Tab')

    const stop = await page.evaluate((index) => {
      const el = document.activeElement
      if (!el || el === document.body || el === document.documentElement) return null

      const key = `${el.tagName}#${el.id}#${el.className}#${(el.textContent || '').slice(0, 30)}`
      const s = getComputedStyle(el)
      el.setAttribute('data-a11y-stop', String(index))

      return {
        key,
        focused: [s.outlineStyle, s.outlineWidth, s.outlineColor, s.boxShadow,
                  s.backgroundColor, s.borderColor, s.color, s.textDecorationLine].join('|'),
        name: (el.getAttribute('aria-label') || el.textContent || '')
          .replace(/\s+/g, ' ').trim().slice(0, 40),
        path: `${el.tagName.toLowerCase()}${
          typeof el.className === 'string' && el.className.trim()
            ? '.' + el.className.trim().split(/\s+/).join('.')
            : ''
        }`,
      }
    }, i)

    // Focus left the document — the browser chrome has it, so the tab order is exhausted.
    if (!stop) break
    if (seen.has(stop.key)) break // wrapped round to something already visited
    seen.add(stop.key)
    stops.push({ index: i, ...stop })
  }

  // Pass 2: take focus away and read the same elements again. Anything whose appearance is
  // byte-identical focused and unfocused is invisible to a keyboard user.
  const unstyled = await page.evaluate((indices) => {
    // `document.activeElement.blur()`, not `document.body.focus()`. Measured: focusing the body
    // leaves `activeElement` on the button and its background still the focused colour, so pass 2
    // re-reads the *focused* styles, every element compares equal, and the check reports the entire
    // tab order as unstyled. It did — fourteen findings, all false, until this was probed.
    if (document.activeElement && document.activeElement !== document.body) {
      document.activeElement.blur()
    }
    const out = []
    for (const i of indices) {
      const el = document.querySelector(`[data-a11y-stop="${i}"]`)
      if (!el) continue
      const s = getComputedStyle(el)
      out.push({
        index: i,
        unfocused: [s.outlineStyle, s.outlineWidth, s.outlineColor, s.boxShadow,
                    s.backgroundColor, s.borderColor, s.color, s.textDecorationLine].join('|'),
      })
    }
    document.querySelectorAll('[data-a11y-stop]').forEach((el) => el.removeAttribute('data-a11y-stop'))
    return out
  }, stops.map((s) => s.index))

  await page.evaluate((id) => document.getElementById(id)?.remove(), FREEZE_ID)

  const unfocusedByIndex = new Map(unstyled.map((u) => [u.index, u.unfocused]))

  return stops
    .filter((s) => unfocusedByIndex.get(s.index) === s.focused)
    .map((s) => ({
      rule: 'focus-not-visible',
      impact: 'serious',
      help: `Nothing changes visually when this is tabbed to: "${s.name || '(no name)'}"`,
      selector: s.path,
      summary: 'identical computed appearance focused and unfocused',
    }))
}

/**
 * Compare a run's findings against the baseline and describe what is wrong with it.
 *
 * Pure, so it is unit-testable without a browser — `tests/unit/a11y.test.mjs` does exactly that,
 * which matters because this function decides whether the gate is honest.
 */
export function reconcile(found, baseline, { seenStates } = { seenStates: 1 }) {
  const problems = []
  const foundIds = new Set(found.map((f) => f.fingerprint))

  const introduced = found.filter((f) => !(f.fingerprint in baseline))
  if (introduced.length) {
    problems.push(
      `${introduced.length} new accessibility violation(s):\n` +
        introduced
          .map((f) => `  ${f.fingerprint}\n      ${f.impact ?? '?'} — ${f.help}\n      at ${f.state}`)
          .join('\n') +
        `\n\nFix it, or add it to tests/a11y-baseline.json with a note and an issue.`,
    )
  }

  // Only meaningful when every state that can produce a finding has been visited. A filtered run
  // (`npx playwright test grade-table`) visits a fraction of them, so this half is skipped there —
  // otherwise the common local command would demand the deletion of entries that are still true.
  if (seenStates === 'all') {
    const stale = Object.keys(baseline).filter((k) => !foundIds.has(k))
    if (stale.length) {
      problems.push(
        `${stale.length} baseline entr(ies) no longer fire:\n` +
          stale.map((k) => `  ${k}`).join('\n') +
          `\n\nDelete them. The baseline is a list of what is still wrong, and an entry that has ` +
          `been fixed makes it a worse one.`,
      )
    }
  }

  return problems
}

export const baselinePath = BASELINE_PATH
export const notInGate = NOT_IN_GATE

/** Rewrite the baseline from a run. `npm run test:a11y:record`; entries still need notes. */
export function writeBaseline(found) {
  const existing = JSON.parse(readFileSync(BASELINE_PATH, 'utf8'))
  const known = {}
  for (const f of found) {
    known[f.fingerprint] = existing.known?.[f.fingerprint] ?? {
      note: `TODO: describe the impact — ${f.help}`,
      issue: 'TODO',
      impact: f.impact,
      firstSeenAt: f.state,
    }
  }
  writeFileSync(BASELINE_PATH, JSON.stringify({ ...existing, known }, null, 2) + '\n')
}
