import { useState } from 'react'
import { Outlet, useNavigate, useLocation, Link as RouterLink } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../auth/AuthContext.tsx'
import {
  AppBar,
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
} from '@mui/material'
import {
  AccountCircle,
  Menu as MenuIcon,
  School,
  LibraryBooks,
  Info,
  DarkMode,
  LightMode,
} from '@mui/icons-material'
import { useThemeMode } from '../theme/ThemeContext.tsx'

const DRAWER_WIDTH = 250

export default function AppLayout() {
  const { t, i18n } = useTranslation()
  const { authenticated, firstName, activeRole, availableRoles, switchRole, logout } =
    useAuth()
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

  const sidenavContent = (
    <Box
      sx={{
        width: DRAWER_WIDTH,
        height: '100%',
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      {/* Logo */}
      <Box sx={{ px: 2.5, py: 2.5 }}>
        <Typography
          component={RouterLink}
          to="/courses"
          sx={{
            textDecoration: 'none',
            color: 'primary.main',
            fontWeight: 700,
            fontSize: '1.25rem',
            letterSpacing: '0.06em',
          }}
        >
          LAHENDUS
        </Typography>
      </Box>

      {/* Role switcher */}
      {authenticated && (
        <>
          <Box sx={{ px: 2.5, pb: 2 }}>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
              {firstName ?? ''}
            </Typography>
            <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap' }}>
              {availableRoles.map((role) => (
                <Chip
                  key={role}
                  label={roleLabel(role)}
                  size="small"
                  color={role === activeRole ? 'primary' : 'default'}
                  variant={role === activeRole ? 'filled' : 'outlined'}
                  onClick={() => handleRoleSwitch(role)}
                />
              ))}
            </Box>
          </Box>
          <Divider />
        </>
      )}

      {/* Navigation */}
      <List sx={{ py: 1, flexGrow: 1 }}>
        <ListItemButton
          component={RouterLink}
          to="/courses"
          selected={isActive('/courses')}
          onClick={() => isMobile && setDrawerOpen(false)}
        >
          <ListItemIcon>
            <School color={isActive('/courses') ? 'primary' : 'action'} />
          </ListItemIcon>
          <ListItemText primary={t('nav.myCourses')} />
        </ListItemButton>

        {(activeRole === 'teacher' || activeRole === 'admin') && (
          <ListItemButton
            component={RouterLink}
            to="/library"
            selected={isActive('/library')}
            onClick={() => isMobile && setDrawerOpen(false)}
          >
            <ListItemIcon>
              <LibraryBooks color={isActive('/library') ? 'primary' : 'action'} />
            </ListItemIcon>
            <ListItemText primary={t('nav.exerciseLibrary')} />
          </ListItemButton>
        )}
      </List>

      {/* Footer */}
      <Divider />
      <List sx={{ py: 0.5 }}>
        <ListItemButton
          component={RouterLink}
          to="/about"
          dense
          onClick={() => isMobile && setDrawerOpen(false)}
        >
          <ListItemIcon>
            <Info fontSize="small" color="action" />
          </ListItemIcon>
          <ListItemText
            primary={t('nav.about')}
            primaryTypographyProps={{ variant: 'body2' }}
          />
        </ListItemButton>
      </List>
    </Box>
  )

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh', bgcolor: 'background.default' }}>
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

      <Box sx={{ flexGrow: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
        <AppBar
          position="sticky"
          elevation={0}
          sx={{
            bgcolor: 'background.paper',
            borderBottom: '1px solid',
            borderColor: 'divider',
          }}
        >
          <Toolbar variant="dense" sx={{ minHeight: 52 }}>
            {isMobile && (
              <IconButton edge="start" onClick={toggleDrawer} sx={{ mr: 1 }}>
                <MenuIcon />
              </IconButton>
            )}

            {isMobile && (
              <Typography
                variant="h6"
                component={RouterLink}
                to="/courses"
                sx={{
                  flexGrow: 1,
                  textDecoration: 'none',
                  color: 'primary.main',
                  fontWeight: 700,
                  letterSpacing: '0.06em',
                  fontSize: '1.1rem',
                }}
              >
                LAHENDUS
              </Typography>
            )}

            {!isMobile && <Box sx={{ flexGrow: 1 }} />}

            <IconButton size="small" onClick={toggleMode} sx={{ mr: 0.5 }}>
              {mode === 'dark' ? <LightMode fontSize="small" /> : <DarkMode fontSize="small" />}
            </IconButton>

            {authenticated && (
              <>
                <Typography variant="body2" color="text.secondary" sx={{ mr: 1 }}>
                  {firstName}
                </Typography>
                <IconButton
                  size="small"
                  onClick={(e) => setProfileAnchor(e.currentTarget)}
                >
                  <AccountCircle />
                </IconButton>
                <Menu
                  anchorEl={profileAnchor}
                  open={Boolean(profileAnchor)}
                  onClose={() => setProfileAnchor(null)}
                  anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
                  transformOrigin={{ vertical: 'top', horizontal: 'right' }}
                >
                  <MenuItem
                    onClick={() => {
                      toggleLanguage()
                      setProfileAnchor(null)
                    }}
                  >
                    {t('general.otherLanguage')}
                  </MenuItem>
                  <MenuItem
                    onClick={() => {
                      setProfileAnchor(null)
                    }}
                  >
                    {t('nav.accountSettings')}
                  </MenuItem>
                  <Divider />
                  <MenuItem onClick={() => logout()}>
                    {t('nav.logOut')}
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
