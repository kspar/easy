import { useState } from 'react'
import {
  Outlet,
  useNavigate,
  useLocation,
  Link as RouterLink,
} from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../auth/AuthContext.tsx'
import {
  AppBar,
  Avatar,
  Box,
  Toolbar,
  Typography,
  IconButton,
  Menu,
  MenuItem,
  Drawer,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Divider,
  Chip,
  Container,
  useMediaQuery,
  useTheme,
  ListSubheader,
} from '@mui/material'
import {
  AccountCircle,
  Menu as MenuIcon,
  School,
  LibraryBooks,
  Info,
  DarkMode,
  LightMode,
  Translate,
  Logout,
} from '@mui/icons-material'
import { useThemeMode } from '../theme/ThemeContext.tsx'
import logoSvg from '../assets/logo.svg'

const DRAWER_WIDTH = 260

export default function AppLayout() {
  const { t, i18n } = useTranslation()
  const {
    authenticated,
    firstName,
    activeRole,
    availableRoles,
    switchRole,
    logout,
  } = useAuth()
  const { mode, toggleMode } = useThemeMode()
  const navigate = useNavigate()
  const location = useLocation()
  const theme = useTheme()
  const isMobile = useMediaQuery(theme.breakpoints.down('md'))

  const [drawerOpen, setDrawerOpen] = useState(false)
  const [profileAnchor, setProfileAnchor] = useState<HTMLElement | null>(null)

  const toggleDrawer = () => setDrawerOpen((prev) => !prev)

  const toggleLanguage = () => {
    const newLang = i18n.language === 'et' ? 'en' : 'et'
    i18n.changeLanguage(newLang)
  }

  const roleLabel = (role: string) =>
    t(`nav.role${role.charAt(0).toUpperCase() + role.slice(1)}`)

  const handleRoleSwitch = (role: typeof activeRole) => {
    switchRole(role)
    navigate('/courses')
  }

  const isActive = (path: string) => location.pathname.startsWith(path)

  const navTo = (path: string) => {
    navigate(path)
    if (isMobile) setDrawerOpen(false)
  }

  const initials = firstName?.charAt(0)?.toUpperCase() ?? '?'

  const sidenavContent = (
    <Box
      sx={{
        width: DRAWER_WIDTH,
        height: '100%',
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      {/* Logo area */}
      <Box
        component={RouterLink}
        to="/courses"
        sx={{
          px: 2.5,
          pt: 2.5,
          pb: 1.5,
          display: 'flex',
          alignItems: 'center',
          gap: 1,
          textDecoration: 'none',
        }}
      >
        <Box
          component="img"
          src={logoSvg}
          alt=""
          sx={{
            width: 28,
            height: 28,
            color: 'primary.main',
            filter: (theme) =>
              theme.palette.mode === 'light'
                ? 'invert(42%) sepia(52%) saturate(600%) hue-rotate(84deg) brightness(92%)'
                : 'invert(70%) sepia(30%) saturate(500%) hue-rotate(84deg) brightness(95%)',
          }}
        />
        <Typography
          sx={{
            fontFamily: "'Sniglet', cursive",
            fontSize: '1.35rem',
            color: 'primary.main',
            letterSpacing: '0.01em',
          }}
        >
          LAHENDUS
        </Typography>
      </Box>

      {/* User & role switcher */}
      {authenticated && (
        <Box sx={{ px: 2.5, pb: 2, pt: 1 }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 1.5 }}>
            <Avatar
              sx={{
                width: 36,
                height: 36,
                bgcolor: 'primary.main',
                fontSize: '0.9rem',
                fontWeight: 600,
              }}
            >
              {initials}
            </Avatar>
            <Box>
              <Typography variant="subtitle2" sx={{ lineHeight: 1.3 }}>
                {firstName}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {roleLabel(activeRole)}
              </Typography>
            </Box>
          </Box>
          <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap' }}>
            {availableRoles.map((role) => (
              <Chip
                key={role}
                label={roleLabel(role)}
                size="small"
                color={role === activeRole ? 'primary' : 'default'}
                variant={role === activeRole ? 'filled' : 'outlined'}
                onClick={() => handleRoleSwitch(role)}
                sx={{ cursor: 'pointer' }}
              />
            ))}
          </Box>
        </Box>
      )}

      <Divider />

      {/* Navigation */}
      <List sx={{ py: 1.5, flexGrow: 1 }}>
        <ListSubheader
          disableSticky
          sx={{
            fontSize: '0.68rem',
            fontWeight: 600,
            letterSpacing: '0.08em',
            textTransform: 'uppercase',
            color: 'text.secondary',
            lineHeight: '32px',
            px: 2.5,
          }}
        >
          {t('nav.myCourses')}
        </ListSubheader>
        <ListItemButton
          selected={isActive('/courses')}
          onClick={() => navTo('/courses')}
        >
          <ListItemIcon>
            <School color={isActive('/courses') ? 'primary' : 'action'} />
          </ListItemIcon>
          <ListItemText
            primary={t('nav.myCourses')}
            primaryTypographyProps={{ variant: 'body2', fontWeight: 500 }}
          />
        </ListItemButton>

        {(activeRole === 'teacher' || activeRole === 'admin') && (
          <>
            <ListSubheader
              disableSticky
              sx={{
                fontSize: '0.68rem',
                fontWeight: 600,
                letterSpacing: '0.08em',
                textTransform: 'uppercase',
                color: 'text.secondary',
                lineHeight: '32px',
                mt: 1,
                px: 2.5,
              }}
            >
              {t('nav.exerciseLibrary')}
            </ListSubheader>
            <ListItemButton
              selected={isActive('/library')}
              onClick={() => navTo('/library')}
            >
              <ListItemIcon>
                <LibraryBooks
                  color={isActive('/library') ? 'primary' : 'action'}
                />
              </ListItemIcon>
              <ListItemText
                primary={t('nav.exerciseLibrary')}
                primaryTypographyProps={{ variant: 'body2', fontWeight: 500 }}
              />
            </ListItemButton>
          </>
        )}
      </List>

      {/* Footer */}
      <Divider />
      <List sx={{ py: 0.5 }}>
        <ListItemButton dense onClick={() => navTo('/about')}>
          <ListItemIcon>
            <Info fontSize="small" color="action" />
          </ListItemIcon>
          <ListItemText
            primary={t('nav.about')}
            primaryTypographyProps={{ variant: 'body2', color: 'text.secondary' }}
          />
        </ListItemButton>
      </List>
    </Box>
  )

  return (
    <Box
      sx={{
        display: 'flex',
        minHeight: '100vh',
        bgcolor: 'background.default',
      }}
    >
      {/* Permanent sidenav on desktop */}
      {!isMobile && (
        <Drawer
          variant="permanent"
          sx={{
            width: DRAWER_WIDTH,
            flexShrink: 0,
            '& .MuiDrawer-paper': {
              width: DRAWER_WIDTH,
              boxSizing: 'border-box',
              bgcolor: 'background.paper',
            },
          }}
        >
          {sidenavContent}
        </Drawer>
      )}

      {/* Temporary drawer on mobile */}
      {isMobile && (
        <Drawer open={drawerOpen} onClose={toggleDrawer}>
          {sidenavContent}
        </Drawer>
      )}

      <Box
        sx={{
          flexGrow: 1,
          display: 'flex',
          flexDirection: 'column',
          minWidth: 0,
        }}
      >
        <AppBar
          position="sticky"
          elevation={0}
          sx={{
            bgcolor: 'background.paper',
            borderBottom: '1px solid',
            borderColor: 'divider',
          }}
        >
          <Toolbar variant="dense" sx={{ minHeight: 48, gap: 0.5 }}>
            {isMobile && (
              <IconButton edge="start" onClick={toggleDrawer} sx={{ mr: 0.5 }}>
                <MenuIcon />
              </IconButton>
            )}

            {isMobile && (
              <Box
                component={RouterLink}
                to="/courses"
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 0.75,
                  textDecoration: 'none',
                }}
              >
                <Box
                  component="img"
                  src={logoSvg}
                  alt=""
                  sx={{
                    width: 22,
                    height: 22,
                    filter: (theme) =>
                      theme.palette.mode === 'light'
                        ? 'invert(42%) sepia(52%) saturate(600%) hue-rotate(84deg) brightness(92%)'
                        : 'invert(70%) sepia(30%) saturate(500%) hue-rotate(84deg) brightness(95%)',
                  }}
                />
                <Typography
                  sx={{
                    fontFamily: "'Sniglet', cursive",
                    fontSize: '1.1rem',
                    color: 'primary.main',
                  }}
                >
                  LAHENDUS
                </Typography>
              </Box>
            )}

            <Box sx={{ flexGrow: 1 }} />

            <IconButton size="small" onClick={toggleMode} title={mode === 'dark' ? 'Light mode' : 'Dark mode'}>
              {mode === 'dark' ? (
                <LightMode fontSize="small" />
              ) : (
                <DarkMode fontSize="small" />
              )}
            </IconButton>

            <IconButton size="small" onClick={toggleLanguage} title={t('general.otherLanguage')}>
              <Translate fontSize="small" />
            </IconButton>

            {authenticated && (
              <>
                <IconButton
                  size="small"
                  onClick={(e) => setProfileAnchor(e.currentTarget)}
                  sx={{ ml: 0.5 }}
                >
                  <AccountCircle />
                </IconButton>
                <Menu
                  anchorEl={profileAnchor}
                  open={Boolean(profileAnchor)}
                  onClose={() => setProfileAnchor(null)}
                  anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
                  transformOrigin={{ vertical: 'top', horizontal: 'right' }}
                  slotProps={{
                    paper: {
                      sx: { minWidth: 180, mt: 0.5, borderRadius: 2 },
                    },
                  }}
                >
                  <Box sx={{ px: 2, py: 1 }}>
                    <Typography variant="subtitle2">{firstName}</Typography>
                    <Typography variant="caption" color="text.secondary">
                      {roleLabel(activeRole)}
                    </Typography>
                  </Box>
                  <Divider />
                  <MenuItem
                    onClick={() => {
                      setProfileAnchor(null)
                    }}
                  >
                    <ListItemIcon>
                      <AccountCircle fontSize="small" />
                    </ListItemIcon>
                    <ListItemText>{t('nav.accountSettings')}</ListItemText>
                  </MenuItem>
                  <MenuItem
                    onClick={() => {
                      logout()
                      setProfileAnchor(null)
                    }}
                  >
                    <ListItemIcon>
                      <Logout fontSize="small" />
                    </ListItemIcon>
                    <ListItemText>{t('nav.logOut')}</ListItemText>
                  </MenuItem>
                </Menu>
              </>
            )}
          </Toolbar>
        </AppBar>

        <Container maxWidth="lg" sx={{ py: 3, flexGrow: 1 }}>
          <Outlet />
        </Container>
      </Box>
    </Box>
  )
}
