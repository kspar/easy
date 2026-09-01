import { format } from 'date-fns'
import type { Locale } from 'date-fns'
import { et, enGB } from 'date-fns/locale'
import { useTranslation } from 'react-i18next'

/**
 * Every user-facing date in the app goes through this module (EZ-1870).
 *
 * It exists because the locale lookup used to be a ternary copy-pasted into seven files, and the
 * eighth call site — the similarity page — simply forgot it and shipped English dates to Estonian
 * users for a year. With nothing to import there was nothing to forget to import.
 *
 * `enGB` rather than `enUS` is load-bearing: date-fns's British short time is `HH:mm`, matching
 * Estonian, so neither language ever shows `2:05 PM`. Changing it would introduce a 12-hour clock
 * on one side of the language switch and not the other.
 */

/**
 * The date-fns locale for a language tag.
 *
 * Estonian is the fallback, not English, because `i18n.ts` sets `fallbackLng: 'et'` — and it seeds
 * the language straight from `localStorage` with no validation, so an unrecognised tag is something
 * that can actually reach here. Falling back the other way would put Estonian text and English dates
 * on the same screen, which is the exact split this module exists to prevent.
 *
 * Matched by prefix so that a region-tagged `en-GB` or `en-US` still gets British English rather
 * than dropping through to Estonian.
 */
export function dateLocaleFor(language: string): Locale {
  return language.startsWith('en') ? enGB : et
}

/**
 * The date-fns locale for the active language.
 *
 * A hook rather than a plain read of the i18n singleton so that switching language re-renders the
 * caller — the same reason `App.tsx` subscribes before handing a locale to `LocalizationProvider`.
 */
export function useDateLocale(): Locale {
  const { i18n } = useTranslation()
  return dateLocaleFor(i18n.language)
}

/** True for the Estonian locale, which is the only one that needs the ordinal dot below. */
function isEstonian(locale: Locale): boolean {
  return locale.code === 'et'
}

/**
 * A date with its time, for labels and tooltips: `1. sept 2026, 14:05` / `1 Sep 2026, 14:05`.
 *
 * Composed from `PP` and `p` rather than the single `PPp` token because date-fns joins the halves
 * of an Estonian `PPp` with a period — `1. sept 2026. 14:05` — putting two dots in one short label
 * and reading as a sentence break in the middle of it. English output is identical either way.
 */
export function formatDateTime(date: Date, locale: Locale): string {
  return `${format(date, 'PP', { locale })}, ${format(date, 'p', { locale })}`
}

/**
 * The same moment without the year, for chips and inline timestamps: `1. sept, 14:05` /
 * `1 Sep, 14:05`.
 *
 * Both languages are day-first, but Estonian writes the day as an ordinal and so takes a dot while
 * English must not have one. That is the one thing a single shared pattern cannot express, which is
 * why this branches rather than handing date-fns one format string.
 */
export function formatShortDateTime(date: Date, locale: Locale): string {
  const day = isEstonian(locale) ? 'd. MMM' : 'd MMM'
  return `${format(date, day, { locale })}, ${format(date, 'p', { locale })}`
}

/** Just the time, `14:05` in both languages. */
export function formatTime(date: Date, locale: Locale): string {
  return format(date, 'p', { locale })
}
