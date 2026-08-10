import { Typography, Box, Link, Paper, CircularProgress } from '@mui/material'
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import config from '../../config.ts'
import usePageTitle from '../../hooks/usePageTitle.ts'
import { useStatistics } from '../../api/statistics.ts'
import { useVersions, formatVersion, formatBuiltAt } from '../../api/versions.ts'
import { useOperatingInfo, formatUptime } from '../../api/operatingInfo.ts'
import { useAuth } from '../../auth/useAuth.ts'
import harnoLogo from '../../assets/sponsors/harno.svg'
import mkmLogo from '../../assets/sponsors/mkm.png'
import itaLogo from '../../assets/sponsors/ita.png'

export default function AboutPage() {
  const { t } = useTranslation()
  usePageTitle(t('nav.about'))
  const stats = useStatistics()

  return (
    <Box py={4} maxWidth={600}>
      <Typography variant="h5" gutterBottom>
        Lahendus
      </Typography>
      <Typography paragraph>
        {t('about.s1')}{' '}
        <Link href="https://cs.ut.ee" target="_blank" rel="noopener">
          {t('about.s2')}
        </Link>
        .
      </Typography>
      <Typography paragraph>
        {t('about.s3')}{' '}
        <Link href={config.repoUrl} target="_blank" rel="noopener">
          easy
        </Link>
        .
      </Typography>
      <Typography paragraph>
        {t('about.discord')}{' '}
        <Link href={config.discordInviteUrl} target="_blank" rel="noopener">
          {t('about.discordLink')}
        </Link>
        .
      </Typography>

      <Box display="flex" gap={2} flexWrap="wrap" my={3}>
        <StatCard label={t('about.statsAutograding')} value={stats.inAutoAssessing} isLoading={stats.isLoading} />
        <StatCard label={t('about.statsSubmissions')} value={stats.totalSubmissions} isLoading={stats.isLoading} />
        <StatCard label={t('about.statsAccounts')} value={stats.totalUsers} isLoading={stats.isLoading} />
      </Box>

      <Typography paragraph>{t('about.sponsors')}</Typography>
      <Box display="flex" gap={2} flexWrap="wrap" alignItems="center">
        <Box sx={{ p: 1.5, borderRadius: 1, bgcolor: 'white' }}>
          <img src={harnoLogo} alt="Harno" style={{ height: '3rem', display: 'block' }} />
        </Box>
        <Box sx={{ p: 1.5, borderRadius: 1, bgcolor: 'white' }}>
          <img src={mkmLogo} alt="MKM" style={{ height: '3rem', display: 'block' }} />
        </Box>
        <Box sx={{ p: 1.5, borderRadius: 1, bgcolor: 'white' }}>
          <img src={itaLogo} alt="ITA" style={{ height: '2.5rem', display: 'block' }} />
        </Box>
      </Box>

      <Versions />
      <OperatingInfo />
    </Box>
  )
}

/**
 * How the deployment is doing — admins only (EZ-1709).
 *
 * Gated on the *acting* role rather than on having admin available, the same reasoning as the IdP
 * admin link in AppLayout: an admin working as a teacher is doing teacher things. The endpoint is
 * `@Secured("ROLE_ADMIN")` regardless, so this hides a panel rather than protecting anything.
 */
function OperatingInfo() {
  const { t } = useTranslation()
  const { activeRole } = useAuth()
  const isAdmin = activeRole === 'admin'
  const { data, isError } = useOperatingInfo(isAdmin)

  if (!isAdmin) return null

  const rows: { label: string; value: string }[] = []

  if (data) {
    const { jvm, db_pool: pool, schema, disk } = data
    rows.push({
      label: t('about.opUptime'),
      value: `${formatUptime(jvm.uptime_sec)} (${formatBuiltAt(jvm.started_at)})`,
    })
    rows.push({
      label: t('about.opHeap'),
      // -1 means the JVM reports no maximum, so there is nothing to compare against.
      value: jvm.heap_max_mb > 0
        ? `${jvm.heap_used_mb} MB / ${jvm.heap_max_mb} MB`
        : `${jvm.heap_used_mb} MB`,
    })
    rows.push({ label: t('about.opThreads'), value: `${jvm.threads} (Java ${jvm.java_version})` })
    if (pool) {
      rows.push({
        label: t('about.opDbPool'),
        value: t('about.opDbPoolValue', {
          active: pool.active,
          idle: pool.idle,
          waiting: pool.waiting,
          max: pool.max,
        }),
      })
    }
    rows.push({
      label: t('about.opSchema'),
      value: schema.changeset
        ? `${schema.changeset} (${schema.total_changesets})`
        : t('about.opSchemaUnknown'),
    })
    for (const g of data.grading) {
      rows.push({
        label: t('about.opGrading', { executor: g.executor }),
        value: t('about.opGradingValue', { queued: g.queued, running: g.running }),
      })
    }
    rows.push({
      label: t('about.opDisk'),
      value: `${disk.free_gb} GB / ${disk.total_gb} GB`,
    })
  }

  return (
    <Box mt={4}>
      <Typography variant="subtitle2" color="text.secondary" gutterBottom>
        {t('about.operating')}
      </Typography>
      {isError && (
        <Typography variant="caption" color="text.disabled">
          {t('about.operatingFailed')}
        </Typography>
      )}
      <Box
        component="dl"
        sx={{
          m: 0,
          display: 'grid',
          gridTemplateColumns: 'max-content auto',
          columnGap: 2,
          rowGap: 0.25,
          fontFamily: 'monospace',
          fontSize: '0.8rem',
        }}
      >
        {rows.map((row) => (
          <Box key={row.label} sx={{ display: 'contents' }}>
            <Box component="dt" sx={{ color: 'text.secondary' }}>
              {row.label}
            </Box>
            <Box component="dd" sx={{ m: 0 }}>
              {row.value}
            </Box>
          </Box>
        ))}
      </Box>
    </Box>
  )
}

/**
 * What is deployed (EZ-1709).
 *
 * Last on the page and deliberately quiet: nobody comes to About for this, but when a bug report
 * needs "which version were you on", it has to be somewhere a person can be pointed at in one
 * sentence. Web's line needs no request — it is compiled into this bundle.
 */
function Versions() {
  const { t } = useTranslation()
  const { data, isLoading, isError } = useVersions()

  const rows: { name: string; value: string; builtAt?: string; muted?: boolean }[] = [
    {
      name: 'web',
      value: formatVersion(__APP_VERSION__, __APP_COMMIT__),
      builtAt: formatBuiltAt(__APP_BUILT_AT__),
    },
  ]

  if (data) {
    rows.push({
      name: 'core',
      value: formatVersion(data.core.version, data.core.commit),
      builtAt: formatBuiltAt(data.core.built_at),
    })
    for (const ex of data.executors) {
      rows.push({
        name: ex.name,
        value: ex.reachable && ex.version
          ? formatVersion(ex.version, ex.commit)
          : t('about.versionUnreachable'),
        builtAt: ex.reachable ? formatBuiltAt(ex.built_at) : '',
        muted: !ex.reachable,
      })
    }
  }

  return (
    <Box mt={5}>
      <Typography variant="subtitle2" color="text.secondary" gutterBottom>
        {t('about.versions')}
      </Typography>
      <Box
        component="dl"
        sx={{
          m: 0,
          display: 'grid',
          // Three columns rather than a table: a handful of rows of short strings is a description
          // list, and a DataTable here would be furniture around nothing. The build time is its own
          // column so the versions stay aligned and scannable rather than being pushed around by
          // dates of differing width.
          gridTemplateColumns: 'max-content max-content auto',
          columnGap: 2,
          rowGap: 0.25,
          fontFamily: 'monospace',
          fontSize: '0.8rem',
        }}
      >
        {rows.map((row) => (
          <Box key={row.name} sx={{ display: 'contents' }}>
            <Box component="dt" sx={{ color: 'text.secondary' }}>
              {row.name}
            </Box>
            <Box component="dd" sx={{ m: 0, color: row.muted ? 'text.disabled' : 'text.primary' }}>
              {row.value}
            </Box>
            {/* Dimmer than the version: it answers a follow-up question ("is that today's build?"),
                not the first one. Empty rather than absent when unknown, so the grid keeps its
                rows aligned. */}
            <Box component="dd" sx={{ m: 0, color: 'text.disabled' }}>
              {row.builtAt}
            </Box>
          </Box>
        ))}
      </Box>
      {/* Core's half can fail on its own — web's line is still true, so the block stays rather than
          disappearing and taking the one version we do know with it. */}
      {isLoading && (
        <Typography variant="caption" color="text.disabled">
          {t('about.versionsLoading')}
        </Typography>
      )}
      {isError && (
        <Typography variant="caption" color="text.disabled">
          {t('about.versionsFailed')}
        </Typography>
      )}
    </Box>
  )
}

function formatNumber(n: number): string {
  if (n < 10000) return String(n)
  const s = String(n)
  let result = ''
  for (let i = 0; i < s.length; i++) {
    if (i > 0 && (s.length - i) % 3 === 0) result += '\u2009'
    result += s[i]
  }
  return result
}

function StatCard({ label, value, isLoading }: { label: string; value: number; isLoading: boolean }) {
  const [highlight, setHighlight] = useState(false)
  const prevRef = useRef(value)

  useEffect(() => {
    if (prevRef.current !== value && !isLoading) {
      prevRef.current = value
      setHighlight(true)
      const timer = setTimeout(() => setHighlight(false), 600)
      return () => clearTimeout(timer)
    }
  }, [value, isLoading])

  return (
    <Paper
      variant="outlined"
      sx={{
        p: 2,
        textAlign: 'center',
        minWidth: 140,
        flex: 1,
        transition: 'background-color 0.6s ease',
        bgcolor: highlight ? 'action.hover' : 'transparent',
      }}
    >
      {isLoading ? (
        <CircularProgress size={28} />
      ) : (
        <Typography variant="h4" fontWeight="bold">
          {formatNumber(value)}
        </Typography>
      )}
      <Typography variant="body2" color="text.secondary" mt={0.5}>
        {label}
      </Typography>
    </Paper>
  )
}
