/**
 * The accessibility gate's own bookkeeping.
 *
 * `reconcile` and `loadBaseline` decide whether the gate is honest, and both are pure — so they can
 * be tested without a browser, which is the only way anyone will actually run them. The gate has the
 * failure mode every guard in this suite has: **a baseline that accepts everything is
 * indistinguishable from a clean app.**
 */
import { describe, expect, test } from 'vitest'
import { fingerprint, loadBaseline, normaliseSelector, reconcile } from '../support/a11y.mjs'

const entry = (over = {}) => ({ note: 'known', issue: 'EZ-1', ...over })

describe('a fingerprint survives changes that are not about accessibility', () => {
  test('a row index moving does not change it', () => {
    // A list gaining a row shifts every nth-child below it. Without this the baseline churns on
    // fixture edits, and a baseline that churns is one people regenerate without reading.
    expect(normaliseSelector('tr:nth-child(4) > td:nth-child(2)'))
      .toBe(normaliseSelector('tr:nth-child(9) > td:nth-child(2)'))
  })

  test("MUI's emotion hash is dropped and the component label kept", () => {
    // One class, not two: MUI writes `css-<hash>-MuiButtonBase-root`. The hash changes with any
    // style edit; the label is what tells a reader which control the finding is about.
    expect(normaliseSelector('.css-1wxaqej-MuiButtonBase-root-MuiIconButton-root'))
      .toBe('.MuiButtonBase-root-MuiIconButton-root')
    expect(normaliseSelector('.css-9zzabc-MuiButtonBase-root-MuiIconButton-root'))
      .toBe(normaliseSelector('.css-1wxaqej-MuiButtonBase-root-MuiIconButton-root'))
  })

  test('but two genuinely different controls stay different', () => {
    // The failure that would make the whole gate useless: over-normalising until every finding has
    // the same fingerprint, so one baseline entry silences the entire app.
    expect(fingerprint('button-name', '.MuiIconButton-root'))
      .not.toBe(fingerprint('button-name', '.MuiTableSortLabel-root'))
    expect(fingerprint('button-name', 'button'))
      .not.toBe(fingerprint('list', 'button'))
  })
})

describe('what the gate does with a run', () => {
  const found = [
    { fingerprint: 'button-name@button', impact: 'critical', help: 'discernible text', state: 'a page' },
  ]

  test('a finding already in the baseline is allowed through', () => {
    expect(reconcile(found, { 'button-name@button': entry() })).toEqual([])
  })

  test('a finding that is not is a failure naming it', () => {
    const problems = reconcile(found, {})
    expect(problems).toHaveLength(1)
    expect(problems[0]).toContain('button-name@button')
    expect(problems[0]).toContain('critical')
  })

  test('an entry that no longer fires is a failure, but only on a full run', () => {
    // Both halves matter. A filtered run (`npx playwright test grade-table`) visits a fraction of
    // the states, so demanding deletion there would make the common local command tell people to
    // delete entries that are still true.
    expect(reconcile(found, { 'button-name@button': entry(), 'list@ul': entry() })).toEqual([])

    const problems = reconcile(found, { 'button-name@button': entry(), 'list@ul': entry() }, {
      seenStates: 'all',
    })
    expect(problems).toHaveLength(1)
    expect(problems[0]).toContain('list@ul')
    expect(problems[0]).toContain('no longer fire')
  })
})

describe('the baseline refuses to silence anything anonymously', () => {
  const load = (known) => () => loadBaseline(JSON.stringify({ known }))

  test('an entry needs a note and an issue', () => {
    expect(load({ 'a@b': entry() })).not.toThrow()
    expect(load({ 'a@b': { issue: 'EZ-1' } })).toThrow(/note/)
    expect(load({ 'a@b': { note: 'x' } })).toThrow(/issue/)
    expect(load({ 'a@b': { note: '  ', issue: 'EZ-1' } })).toThrow(/note/)
    expect(load({ 'a@b': { note: 'x', issue: '' } })).toThrow(/issue/)
  })

  test('and the error names which entries are wrong', () => {
    // A guard that fails without saying what to fix gets worked around rather than satisfied.
    expect(load({ 'good@x': entry(), 'bad@y': { note: 'x' } })).toThrow(/bad@y/)
  })

  test('an empty baseline is fine — it means nothing is known-broken', () => {
    expect(loadBaseline(JSON.stringify({ known: {} }))).toEqual({})
  })
})
