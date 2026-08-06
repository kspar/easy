import { useState } from 'react'
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  IconButton,
  Paper,
  Stack,
  Switch,
  Typography,
} from '@mui/material'
import {
  ArrowBackOutlined,
  DarkModeOutlined,
  DownloadOutlined,
  LanguageOutlined,
  OpenInNewOutlined,
  ShieldOutlined,
} from '@mui/icons-material'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import usePageTitle from '../../hooks/usePageTitle.ts'
import { useAuth } from '../../auth/useAuth.ts'
import { useThemeMode } from '../../theme/useThemeMode.ts'
import { getToken } from '../../api/client.ts'
import config from '../../config.ts'

/** One labelled row: what it is on the left, the control on the right. */
function SettingRow({
  icon,
  title,
  description,
  control,
}: {
  icon: React.ReactNode
  title: string
  description?: string
  control: React.ReactNode
}) {
  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, py: 1.5 }}>
      <Box sx={{ color: 'text.secondary', display: 'flex' }}>{icon}</Box>
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Typography variant="body2">{title}</Typography>
        {description && (
          <Typography variant="caption" color="text.secondary">
            {description}
          </Typography>
        )}
      </Box>
      <Box sx={{ flexShrink: 0 }}>{control}</Box>
    </Box>
  )
}

/**
 * Account settings — the page the profile menu item has been pointing at nothing for (EZ-1701).
 *
 * Deliberately small, because most of what belongs on a page like this is not ours to change:
 *
 * - **Name and email come from the university account**, through Keycloak, and core only mirrors
 *   them at check-in. Editing them here would either not stick or would silently disagree with the
 *   identity provider, so they are shown read-only and the page points at the place that owns them.
 * - **Password, two-factor and sessions live in Keycloak's own account console.** Rebuilding those
 *   screens against the admin API would mean maintaining a worse copy of something Keycloak already
 *   ships and keeps current with its own security fixes.
 *
 * What is genuinely ours: appearance, language, and the personal-data export.
 *
 * Not here on purpose: notification preferences. Core sends a student mail on new feedback, edited
 * feedback and a changed grade, and there is nowhere to record a preference about them — the
 * `account` table has no settings columns and there is no endpoint. Rendering switches that quietly
 * do nothing would be worse than the menu item that did nothing.
 */
export default function AccountSettingsPage() {
  const navigate = useNavigate()
  const { t, i18n } = useTranslation()
  usePageTitle(t('account.title'))

  const { keycloak, email, username, availableRoles } = useAuth()
  const { mode, toggleMode } = useThemeMode()

  // keycloak-js builds this URL itself — realm included, and a return link — so the account console
  // path is not hardcoded here and survives a Keycloak upgrade that moves it.
  const accountUrl = keycloak?.createAccountUrl()

  const [exporting, setExporting] = useState(false)
  const [exportError, setExportError] = useState(false)

  // Nullable in the context type even though this page is behind RequireAuth — the provider starts
  // with no instance. Optional chaining rather than a non-null assertion, so a change to that
  // lifecycle degrades to a missing name instead of a crash on a settings page.
  const name = [
    keycloak?.tokenParsed?.given_name as string | undefined,
    keycloak?.tokenParsed?.family_name as string | undefined,
  ]
    .filter(Boolean)
    .join(' ')

  /**
   * The export is a zip, so it cannot go through `apiFetch` — that always parses the body as JSON.
   * A plain `<a href>` will not do either: the endpoint needs an Authorization header.
   */
  const downloadData = async () => {
    setExporting(true)
    setExportError(false)
    try {
      const token = await getToken?.()
      const response = await fetch(`${config.emsRoot}/account/export`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      })
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      const blob = await response.blob()
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = 'personal-data.zip'
      a.click()
      URL.revokeObjectURL(url)
    } catch {
      setExportError(true)
    } finally {
      setExporting(false)
    }
  }

  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 3 }}>
        <IconButton onClick={() => navigate(-1)} size="small">
          <ArrowBackOutlined />
        </IconButton>
        <Typography variant="h5">{t('account.title')}</Typography>
      </Box>

      <Stack spacing={3} sx={{ maxWidth: 720 }}>
        {/* --- who you are, as the identity provider sees you ------------------------------------ */}
        <Paper variant="outlined" sx={{ p: 2.5 }}>
          <Typography variant="subtitle2" sx={{ mb: 2 }}>
            {t('account.profile')}
          </Typography>
          <Stack spacing={1.5}>
            {[
              { label: t('account.name'), value: name },
              { label: t('account.email'), value: email },
              { label: t('account.username'), value: username },
            ].map(({ label, value }) => (
              <Box key={label} sx={{ display: 'flex', gap: 2 }}>
                <Typography variant="body2" color="text.secondary" sx={{ minWidth: 120 }}>
                  {label}
                </Typography>
                <Typography variant="body2">{value || '—'}</Typography>
              </Box>
            ))}
            <Box sx={{ display: 'flex', gap: 2, alignItems: 'center' }}>
              <Typography variant="body2" color="text.secondary" sx={{ minWidth: 120 }}>
                {t('account.roles')}
              </Typography>
              <Box sx={{ display: 'flex', gap: 0.5 }}>
                {availableRoles.map((r) => (
                  <Chip key={r} size="small" label={t(`nav.role${r.charAt(0).toUpperCase() + r.slice(1)}`)} />
                ))}
              </Box>
            </Box>
          </Stack>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 2 }}>
            {t('account.profileManagedElsewhere')}
          </Typography>
        </Paper>

        {/* --- security, which Keycloak owns ------------------------------------------------------ */}
        <Paper variant="outlined" sx={{ p: 2.5 }}>
          <Typography variant="subtitle2" sx={{ mb: 1 }}>
            {t('account.security')}
          </Typography>
          <SettingRow
            icon={<ShieldOutlined />}
            title={t('account.securityConsole')}
            description={t('account.securityConsoleHint')}
            // Two renders rather than one with a conditional href: MUI types `href` and `disabled`
            // as mutually exclusive, since the anchor overload has no disabled state.
            control={
              accountUrl ? (
                <Button
                  href={accountUrl}
                  target="_blank"
                  rel="noopener"
                  variant="outlined"
                  size="small"
                  endIcon={<OpenInNewOutlined />}
                >
                  {t('account.open')}
                </Button>
              ) : (
                <Button disabled variant="outlined" size="small">
                  {t('account.open')}
                </Button>
              )
            }
          />
        </Paper>

        {/* --- what this app actually controls ---------------------------------------------------- */}
        <Paper variant="outlined" sx={{ p: 2.5 }}>
          <Typography variant="subtitle2" sx={{ mb: 1 }}>
            {t('account.appearance')}
          </Typography>
          <SettingRow
            icon={<DarkModeOutlined />}
            title={t('account.darkMode')}
            description={t('account.storedLocally')}
            control={
              <Switch
                checked={mode === 'dark'}
                onChange={toggleMode}
                // slotProps.input, not the deprecated inputProps: the row already shows the label
                // beside the control, so the switch needs an accessible name of its own rather than
                // a second visible one via FormControlLabel.
                slotProps={{ input: { 'aria-label': t('account.darkMode') } }}
              />
            }
          />
          <Divider />
          <SettingRow
            icon={<LanguageOutlined />}
            title={t('account.language')}
            description={t('account.storedLocally')}
            control={
              <Button
                size="small"
                variant="outlined"
                onClick={() => i18n.changeLanguage(i18n.language === 'et' ? 'en' : 'et')}
              >
                {i18n.language === 'et' ? 'English' : 'Eesti'}
              </Button>
            }
          />
        </Paper>

        {/* --- data ------------------------------------------------------------------------------- */}
        <Paper variant="outlined" sx={{ p: 2.5 }}>
          <Typography variant="subtitle2" sx={{ mb: 1 }}>
            {t('account.yourData')}
          </Typography>
          <SettingRow
            icon={<DownloadOutlined />}
            title={t('account.exportData')}
            description={t('account.exportDataHint')}
            control={
              <Button
                size="small"
                variant="outlined"
                onClick={downloadData}
                disabled={exporting}
                startIcon={exporting ? <CircularProgress size={16} color="inherit" /> : <DownloadOutlined />}
              >
                {t('account.download')}
              </Button>
            }
          />
          {exportError && (
            <Alert severity="error" sx={{ mt: 1 }}>
              {t('account.exportFailed')}
            </Alert>
          )}
        </Paper>
      </Stack>
    </>
  )
}
