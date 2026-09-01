import { useEffect, useState } from 'react'
import { Tooltip } from '@mui/material'
import { isToday, isYesterday, isTomorrow } from 'date-fns'
import type { Locale } from 'date-fns'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import {
  formatDateTime,
  formatShortDateTime,
  formatTime,
  useDateLocale,
} from '../i18n/dateLocale.ts'

function formatRelative(
  date: Date,
  // TFunction rather than (key: string) => string — the relative-time strings below
  // interpolate a { count }, which a single-argument signature can't express.
  t: TFunction,
  locale: Locale,
): string {
  const now = new Date()
  const time = formatTime(date, locale)
  const diffMs = now.getTime() - date.getTime()
  const diffSec = Math.floor(diffMs / 1000)

  // Future
  if (diffMs < 0) {
    if (isToday(date)) return `${t('datetime.today')}, ${time}`
    if (isTomorrow(date)) return `${t('datetime.tomorrow')}, ${time}`
    if (date.getFullYear() === now.getFullYear()) {
      return formatShortDateTime(date, locale)
    }
    return formatDateTime(date, locale)
  }

  // Past — today
  if (isToday(date)) {
    if (diffSec < 60) return t('datetime.justNow')
    const diffMin = Math.floor(diffSec / 60)
    if (diffMin < 60) return t('datetime.minutesAgo', { count: diffMin })
    const diffHours = Math.floor(diffMin / 60)
    return t('datetime.hoursAgo', { count: diffHours })
  }

  // Past — yesterday
  if (isYesterday(date)) return `${t('datetime.yesterday')}, ${time}`
  if (date.getFullYear() === now.getFullYear()) {
    return formatShortDateTime(date, locale)
  }
  return formatDateTime(date, locale)
}

function getRefreshInterval(date: Date): number {
  const diffSec = Math.floor((Date.now() - date.getTime()) / 1000)
  if (diffSec < 0) return 0 // future — no refresh
  if (diffSec < 60) return 10_000 // < 1 min: every 10s
  if (diffSec < 3600) return 30_000 // < 1 hour: every 30s
  if (diffSec < 86400) return 600_000 // < 1 day: every 10 min
  return 0 // older — no refresh
}

export default function RelativeTime({ date }: { date: string }) {
  const { t } = useTranslation()
  const dateFnsLocale = useDateLocale()
  const parsed = new Date(date)
  const fullFormatted = formatDateTime(parsed, dateFnsLocale)

  const [, setTick] = useState(0)

  useEffect(() => {
    const interval = getRefreshInterval(parsed)
    if (!interval) return
    const id = setInterval(() => setTick((n) => n + 1), interval)
    return () => clearInterval(id)
  }, [date]) // eslint-disable-line react-hooks/exhaustive-deps

  const display = formatRelative(parsed, t, dateFnsLocale)

  return (
    <Tooltip title={fullFormatted}>
      <span>{display}</span>
    </Tooltip>
  )
}
