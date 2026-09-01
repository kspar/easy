/**
 * How dates are written in each language (`src/i18n/dateLocale.ts`).
 *
 * Nothing asserted date formats until EZ-1870, and the whole audit that produced this file found
 * defects a full green suite had never noticed — because every one of them was invisible in
 * English. The English half of each case below is the half that always passed.
 *
 * Three bugs are pinned here:
 *
 *  - `RelativeTime` formatted with `'MMM d, '`, hard-coding English month-before-day order. Estonian
 *    came out as "sept 1", which is not how anyone writes a date in Estonian, and en-GB came out as
 *    "Sep 1", which is not how anyone writes one in Britain either.
 *  - The similarity page pinned `enGB` and `dd/MM/yyyy`, showing "01/09/2026" to Estonian readers.
 *    Estonian numeric dates are dot-separated, and core has always agreed — see
 *    `BugReportForwardService`, which writes `dd.MM.yyyy`.
 *  - `PPp` joins the Estonian date and time with a period, so a deadline read "1. sept 2026. 14:05"
 *    — two dots in one label, the second landing like a sentence break.
 *
 * The `enGB` choice is load-bearing rather than incidental, so the 24-hour test guards it: date-fns
 * gives `enUS` a 12-hour short time, and swapping the locale would put "2:05 PM" on one side of the
 * language switch and "14:05" on the other.
 */
import { describe, expect, test } from 'vitest'
import { et, enGB } from 'date-fns/locale'
import {
  dateLocaleFor,
  formatDateTime,
  formatShortDateTime,
  formatTime,
} from '../../src/i18n/dateLocale.ts'

// 1 September 2026, 14:05. September because its Estonian abbreviation ("sept") is long enough to
// be unmistakable in an assertion, and the 1st because a single digit is where an ordinal dot is
// easiest to lose.
const AFTERNOON = new Date(2026, 8, 1, 14, 5)
// A two-digit day in a month whose Estonian name is not abbreviated at all.
const MORNING = new Date(2026, 2, 9, 9, 30)

describe('dateLocaleFor', () => {
  test('maps the two languages i18n.ts actually registers', () => {
    expect(dateLocaleFor('et')).toBe(et)
    expect(dateLocaleFor('en')).toBe(enGB)
  })

  test('a region-tagged English still gets British English, never American', () => {
    // enUS would bring a 12-hour clock, so no English tag may fall through to it.
    expect(dateLocaleFor('en-US')).toBe(enGB)
    expect(dateLocaleFor('en-GB')).toBe(enGB)
  })

  test('anything unrecognised falls back to Estonian, matching i18n.ts fallbackLng', () => {
    // i18n.ts seeds the language from localStorage without validating it, so an unknown tag really
    // can arrive. Falling back to English here would pair Estonian text with English dates.
    expect(dateLocaleFor('')).toBe(et)
    expect(dateLocaleFor('et-EE')).toBe(et)
    expect(dateLocaleFor('fi')).toBe(et)
  })
})

describe('formatDateTime', () => {
  test('Estonian separates date from time with a comma, not a second period', () => {
    // `PPp` produced '1. sept 2026. 14:05' here.
    expect(formatDateTime(AFTERNOON, et)).toBe('1. sept 2026, 14:05')
  })

  test('English is unchanged by the fix', () => {
    expect(formatDateTime(AFTERNOON, enGB)).toBe('1 Sep 2026, 14:05')
  })

  test('a two-digit day and a full month name', () => {
    expect(formatDateTime(MORNING, et)).toBe('9. märts 2026, 09:30')
    expect(formatDateTime(MORNING, enGB)).toBe('9 Mar 2026, 09:30')
  })
})

describe('formatShortDateTime', () => {
  test('Estonian is day-first and takes the ordinal dot', () => {
    // `'MMM d, '` produced 'sept 1, 14:05' here.
    expect(formatShortDateTime(AFTERNOON, et)).toBe('1. sept, 14:05')
  })

  test('English is day-first and must not take the dot', () => {
    expect(formatShortDateTime(AFTERNOON, enGB)).toBe('1 Sep, 14:05')
  })

  test('drops the year, which is the only thing separating it from the long form', () => {
    expect(formatShortDateTime(AFTERNOON, et)).not.toContain('2026')
    expect(formatShortDateTime(AFTERNOON, enGB)).not.toContain('2026')
  })
})

describe('both languages agree on the things they should', () => {
  const everyFormatter = [formatDateTime, formatShortDateTime, formatTime]

  test('a 24-hour clock, so neither language ever shows AM or PM', () => {
    for (const fn of everyFormatter) {
      for (const locale of [et, enGB]) {
        expect(fn(AFTERNOON, locale)).toContain('14:05')
        expect(fn(AFTERNOON, locale)).not.toMatch(/[AP]M/i)
      }
    }
  })

  test('no slashes anywhere — a numeric date is dot-separated, as core writes it', () => {
    for (const fn of everyFormatter) {
      for (const locale of [et, enGB]) {
        expect(fn(MORNING, locale)).not.toContain('/')
      }
    }
  })

  test('the day is written before the month in both languages', () => {
    for (const fn of [formatDateTime, formatShortDateTime]) {
      const estonian = fn(AFTERNOON, et)
      expect(estonian.indexOf('1')).toBeLessThan(estonian.indexOf('sept'))
      const english = fn(AFTERNOON, enGB)
      expect(english.indexOf('1')).toBeLessThan(english.indexOf('Sep'))
    }
  })

  test('leading zeroes on the hour, so times stay column-aligned', () => {
    expect(formatTime(MORNING, et)).toBe('09:30')
    expect(formatTime(MORNING, enGB)).toBe('09:30')
  })
})
