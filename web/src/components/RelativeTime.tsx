import { useEffect, useState } from 'react'
import { Tooltip } from '@mui/material'
import { format, isToday, isYesterday, isTomorrow } from 'date-fns'
import { et, enGB } from 'date-fns/locale'
import { useTranslation } from 'react-i18next'

function formatRelative(
  date: Date,
  t: (key: string) => string,
  locale: Locale,
): string {
  const now = new Date()
  const time = format(date, 'p', { locale })
  const diffMs = now.getTime() - date.getTime()
  const diffSec = Math.floor(diffMs / 1000)

  // Future
  if (diffMs < 0) {
    if (isToday(date)) return `${t('datetime.today')}, ${time}`
    if (isTomorrow(date)) return `${t('datetime.tomorrow')}, ${time}`
    if (date.getFullYear() === now.getFullYear()) {
      return format(date, 'MMM d, ', { locale }) + time
    }
    return format(date, 'PPp', { locale })
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
    return format(date, 'MMM d, ', { locale }) + time
  }
  return format(date, 'PPp', { locale })
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
  const { t, i18n } = useTranslation()
  const dateFnsLocale = i18n.language === 'et' ? et : enGB
  const parsed = new Date(date)
  const fullFormatted = format(parsed, 'PPp', { locale: dateFnsLocale })

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
