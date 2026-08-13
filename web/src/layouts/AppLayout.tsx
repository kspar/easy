import { useState } from 'react'
import {
  Outlet,
  useNavigate,
  useLocation,
  Link as RouterLink,
} from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../auth/useAuth.ts'
import type { Role } from '../auth/auth-context.ts'
import { apiFetch } from '../api/client.ts'
import type { StudentCourse, TeacherCourse } from '../api/types.ts'
import config from '../config.ts'
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
  AccountCircleOutlined,
  Menu as MenuIcon,
  SchoolOutlined,
  ArticleOutlined,
  LibraryBooksOutlined,
  DarkModeOutlined,
  LightModeOutlined,
  TranslateOutlined,
  LogoutOutlined,
  CheckCircle,
  CircleOutlined,
  RadioButtonUnchecked,
  AssignmentOutlined,
  GradingOutlined,
  PeopleOutlined,
  CompareArrowsOutlined,
  SettingsOutlined,
  AdminPanelSettingsOutlined,
  OpenInNewOutlined,
  CampaignOutlined,
} from '@mui/icons-material'
import { useThemeMode } from '../theme/useThemeMode.ts'
import { useCourseExercises } from '../api/exercises.ts'
import { useCourse } from '../api/courses.ts'
import type { StudentExerciseStatus } from '../api/types.ts'
import EditCourseDialog from '../features/course-settings/EditCourseDialog.tsx'
import useRecentExercises from '../hooks/useRecentExercises.ts'
import logoSvg from '../assets/logo.svg'
import SystemMessageBanner from '../components/SystemMessageBanner.tsx'
import UpdateAvailableBanner from '../components/UpdateAvailableBanner.tsx'
import EnvironmentBadge from '../components/EnvironmentBadge.tsx'

function slugify(s: string): string {
  return s.toLowerCase().replace(/\s+/g, '-').replace(/[^a-z0-9\u00C0-\u024F-]/g, '')
}

function exerciseLink(id: string, title: string): string {
  return `/library/exercise/${id}/${slugify(title)}`
}

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

  // Extract courseId from route if inside a course
  const courseMatch = location.pathname.match(/^\/courses\/(\d+)/)
  const courseId = courseMatch ? courseMatch[1] : undefined
  const isTeacherOrAdmin = activeRole === 'teacher' || activeRole === 'admin'
  const onLibrary = location.pathname.startsWith('/library')

  const { recent: recentExercises } = useRecentExercises()

  // Student exercise sidebar
  const studentCourseId = activeRole === 'student' ? courseId : undefined
  const { data: exercises } = useCourseExercises(studentCourseId)

  // Course info for sidebar heading
  const { data: courseInfo } = useCourse(courseId)
  const courseTitle = courseInfo ? (courseInfo.alias || courseInfo.title) : undefined

  // Extract current exercise ID from route for highlighting
  const exerciseMatch = location.pathname.match(/^\/courses\/\d+\/exercises\/(\d+)/)
  const activeExerciseId = exerciseMatch ? exerciseMatch[1] : undefined

  const [drawerOpen, setDrawerOpen] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [profileAnchor, setProfileAnchor] = useState<HTMLElement | null>(null)

  const toggleDrawer = () => setDrawerOpen((prev) => !prev)

  const toggleLanguage = () => {
    const newLang = i18n.language === 'et' ? 'en' : 'et'
    i18n.changeLanguage(newLang)
  }

  const roleLabel = (role: string) =>
    t(`nav.role${role.charAt(0).toUpperCase() + role.slice(1)}`)

  const queryClient = useQueryClient()

  const handleRoleSwitch = async (newRole: Role) => {
    const path = location.pathname
    const target = await resolveRoleTarget(newRole, path)
    switchRole(newRole)
    if (target) navigate(target)
  }

  async function resolveRoleTarget(newRole: Role, path: string): Promise<string | null> {
    // Non-course pages accessible to all roles — stay
    if (path === '/courses' || path === '/about' || path === '/tos') return null

    // Switching between admin ↔ teacher — all pages are shared
    if (newRole !== 'student' && activeRole !== 'student') return null

    // Switching to student
    if (newRole === 'student') {
      if (path.startsWith('/library')) return '/courses'

      if (courseId) {
        const hasAccess = await hasStudentAccess(courseId)
        if (!hasAccess) return '/courses'
        // Teacher-only course sub-pages → exercise list
        if (/\/(grades|participants|similarity)$/.test(path)) {
          return `/courses/${courseId}/exercises`
        }
        return null // exercise pages — stay
      }
    }

    // Switching from student to teacher/admin
    if (activeRole === 'student' && courseId) {
      const hasAccess = await hasTeacherAccess(courseId)
      if (!hasAccess) return '/courses'
    }

    return null
  }

  async function hasStudentAccess(id: string): Promise<boolean> {
    try {
      const courses =
        queryClient.getQueryData<StudentCourse[]>(['student', 'courses']) ??
        (await queryClient.fetchQuery({
          queryKey: ['student', 'courses'],
          queryFn: () =>
            apiFetch<{ courses: StudentCourse[] }>('/student/courses').then((r) => r.courses),
        }))
      // fetchQuery's return type admits undefined; previously that threw and was swallowed
      // by the catch below, which returned false — same result, minus the exception.
      return courses?.some((c) => c.id === id) ?? false
    } catch {
      return false
    }
  }

  async function hasTeacherAccess(id: string): Promise<boolean> {
    try {
      const courses =
        queryClient.getQueryData<TeacherCourse[]>(['teacher', 'courses']) ??
        (await queryClient.fetchQuery({
          queryKey: ['teacher', 'courses'],
          queryFn: () =>
            apiFetch<{ courses: TeacherCourse[] }>('/teacher/courses').then((r) => r.courses),
        }))
      return courses?.some((c) => c.id === id) ?? false
    } catch {
      return false
    }
  }

  const statusIcon = (status: StudentExerciseStatus) => {
    switch (status) {
      case 'COMPLETED':
        return <CheckCircle sx={{ fontSize: 16, color: 'success.main' }} />
      case 'STARTED':
        return <CircleOutlined sx={{ fontSize: 16, color: 'warning.main' }} />
      case 'UNGRADED':
        return <CircleOutlined sx={{ fontSize: 16, color: 'info.main' }} />
      case 'UNSTARTED':
      default:
        return <RadioButtonUnchecked sx={{ fontSize: 16, color: 'text.disabled' }} />
    }
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
        {/* Beside the wordmark, so "which application" and "which deployment" are read in one
            glance. Renders nothing on production. */}
        <EnvironmentBadge />
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
        <ListItemButton
          selected={location.pathname === '/courses'}
          onClick={() => navTo('/courses')}
        >
          <ListItemIcon>
            <SchoolOutlined color={location.pathname === '/courses' ? 'primary' : 'action'} />
          </ListItemIcon>
          <ListItemText
            primary={t('nav.myCourses')}
            primaryTypographyProps={{ variant: 'body2', fontWeight: 500 }}
          />
        </ListItemButton>

        {isTeacherOrAdmin && (
          <ListItemButton
            selected={isActive('/library')}
            onClick={() => navTo('/library/dir/root')}
          >
            <ListItemIcon>
              <LibraryBooksOutlined
                color={isActive('/library') ? 'primary' : 'action'}
              />
            </ListItemIcon>
            <ListItemText
              primary={t('nav.exerciseLibrary')}
              primaryTypographyProps={{ variant: 'body2', fontWeight: 500 }}
            />
          </ListItemButton>
        )}

        {/*
        Admin-only: articles are authored by admins and read by everyone from a direct link, so
        nobody else needs the index. A real anchor rather than the onClick+navTo its neighbours
        use, so ctrl-click opens a tab — see the links rule in CLAUDE.md.
        */}
        {activeRole === 'admin' && (
          <ListItemButton
            component={RouterLink}
            to="/articles"
            selected={isActive('/articles')}
          >
            <ListItemIcon>
              <ArticleOutlined color={isActive('/articles') ? 'primary' : 'action'} />
            </ListItemIcon>
            <ListItemText
              primary={t('nav.articles')}
              primaryTypographyProps={{ variant: 'body2', fontWeight: 500 }}
            />
          </ListItemButton>
        )}

        {/* Recently viewed exercises on library pages */}
        {isTeacherOrAdmin && onLibrary && recentExercises.length > 0 && (
          <List disablePadding>
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
                cursor: 'default',
              }}
            >
              {t('library.recentlyViewed')}
            </ListSubheader>
            {recentExercises.map((ex) => {
              const href = exerciseLink(ex.id, ex.title)
              return (
                <ListItemButton
                  key={ex.id}
                  component="a"
                  href={href}
                  selected={location.pathname.startsWith(`/library/exercise/${ex.id}`)}
                  onClick={(e: React.MouseEvent) => {
                    if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return
                    e.preventDefault()
                    navTo(href)
                  }}
                  sx={{ py: 0.5, minHeight: 36, pl: 3 }}
                >
                  <ListItemText
                    primary={ex.title}
                    primaryTypographyProps={{
                      variant: 'body2',
                      noWrap: true,
                      fontSize: '0.82rem',
                    }}
                  />
                </ListItemButton>
              )
            })}
          </List>
        )}

        {/* Student: exercise list in sidebar */}
        {studentCourseId && exercises && exercises.length > 0 && (
          <List disablePadding>
            <ListSubheader
              disableSticky
              onClick={() => navTo(`/courses/${studentCourseId}/exercises`)}
              sx={{
                fontSize: '0.68rem',
                fontWeight: 600,
                letterSpacing: '0.08em',
                textTransform: 'uppercase',
                color: 'text.secondary',
                lineHeight: '32px',
                mt: 1,
                px: 2.5,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
                cursor: 'pointer',
                '&:hover': { color: 'text.primary' },
              }}
              title={courseTitle}
            >
              {courseTitle ?? t('exercises.title')}
            </ListSubheader>
            {exercises.map((ex) => (
              <ListItemButton
                key={ex.id}
                selected={activeExerciseId === ex.id}
                onClick={() => navTo(`/courses/${studentCourseId}/exercises/${ex.id}`)}
                sx={{ py: 0.5, minHeight: 36, pl: 3 }}
              >
                <ListItemIcon sx={{ minWidth: 28 }}>
                  {statusIcon(ex.status)}
                </ListItemIcon>
                <ListItemText
                  primary={ex.effective_title}
                  primaryTypographyProps={{
                    variant: 'body2',
                    noWrap: true,
                    fontSize: '0.82rem',
                  }}
                />
              </ListItemButton>
            ))}
          </List>
        )}

        {/* Teacher/Admin: course sub-page links in sidebar */}
        {isTeacherOrAdmin && courseId && (
          <List disablePadding>
            <ListSubheader
              disableSticky
              onClick={() => navTo(`/courses/${courseId}/exercises`)}
              sx={{
                fontSize: '0.68rem',
                fontWeight: 600,
                letterSpacing: '0.08em',
                textTransform: 'uppercase',
                color: 'text.secondary',
                lineHeight: '32px',
                mt: 1,
                px: 2.5,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
                cursor: 'pointer',
                '&:hover': { color: 'text.primary' },
              }}
              title={courseTitle}
            >
              {courseTitle ?? t('exercises.title')}
            </ListSubheader>
            <ListItemButton
              selected={isActive(`/courses/${courseId}/exercises`)}
              onClick={() => navTo(`/courses/${courseId}/exercises`)}
              sx={{ py: 0.5, minHeight: 36, pl: 3 }}
            >
              <ListItemIcon sx={{ minWidth: 28 }}>
                <AssignmentOutlined sx={{ fontSize: 18 }} color={isActive(`/courses/${courseId}/exercises`) ? 'primary' : 'action'} />
              </ListItemIcon>
              <ListItemText primary={t('exercises.title')} primaryTypographyProps={{ variant: 'body2', fontSize: '0.85rem' }} />
            </ListItemButton>
            <ListItemButton
              selected={isActive(`/courses/${courseId}/grades`)}
              onClick={() => navTo(`/courses/${courseId}/grades`)}
              sx={{ py: 0.5, minHeight: 36, pl: 3 }}
            >
              <ListItemIcon sx={{ minWidth: 28 }}>
                <GradingOutlined sx={{ fontSize: 18 }} color={isActive(`/courses/${courseId}/grades`) ? 'primary' : 'action'} />
              </ListItemIcon>
              <ListItemText primary={t('grades.title')} primaryTypographyProps={{ variant: 'body2', fontSize: '0.85rem' }} />
            </ListItemButton>
            <ListItemButton
              selected={isActive(`/courses/${courseId}/participants`)}
              onClick={() => navTo(`/courses/${courseId}/participants`)}
              sx={{ py: 0.5, minHeight: 36, pl: 3 }}
            >
              <ListItemIcon sx={{ minWidth: 28 }}>
                <PeopleOutlined sx={{ fontSize: 18 }} color={isActive(`/courses/${courseId}/participants`) ? 'primary' : 'action'} />
              </ListItemIcon>
              <ListItemText primary={t('participants.title')} primaryTypographyProps={{ variant: 'body2', fontSize: '0.85rem' }} />
            </ListItemButton>
            <ListItemButton
              selected={isActive(`/courses/${courseId}/similarity`)}
              onClick={() => navTo(`/courses/${courseId}/similarity`)}
              sx={{ py: 0.5, minHeight: 36, pl: 3 }}
            >
              <ListItemIcon sx={{ minWidth: 28 }}>
                <CompareArrowsOutlined sx={{ fontSize: 18 }} color={isActive(`/courses/${courseId}/similarity`) ? 'primary' : 'action'} />
              </ListItemIcon>
              <ListItemText primary={t('similarity.title')} primaryTypographyProps={{ variant: 'body2', fontSize: '0.85rem' }} />
            </ListItemButton>
            <ListItemButton
              onClick={() => { setSettingsOpen(true); if (isMobile) setDrawerOpen(false) }}
              sx={{ py: 0.5, minHeight: 36, pl: 3 }}
            >
              <ListItemIcon sx={{ minWidth: 28 }}>
                <SettingsOutlined sx={{ fontSize: 18 }} color="action" />
              </ListItemIcon>
              <ListItemText primary={t('courses.courseSettings')} primaryTypographyProps={{ variant: 'body2', fontSize: '0.85rem' }} />
            </ListItemButton>
          </List>
        )}

      </List>

      {/* Footer */}
      <Divider />
      <Box sx={{ px: 2.5, py: 2, display: 'flex', flexDirection: 'column', gap: 0.5 }}>
        <Box sx={{ display: 'flex', gap: 1.5 }}>
          <Typography
            variant="caption"
            color="text.secondary"
            component={RouterLink}
            to="/about"
            sx={{ textDecoration: 'none', '&:hover': { textDecoration: 'underline' } }}
          >
            {t('nav.about')}
          </Typography>
          <Typography
            variant="caption"
            color="text.secondary"
            component={RouterLink}
            to="/landing"
            sx={{ textDecoration: 'none', '&:hover': { textDecoration: 'underline' } }}
          >
            {t('nav.landingPage')}
          </Typography>
        </Box>
        <Box sx={{ display: 'flex', gap: 1.5 }}>
          <Typography
            variant="caption"
            color="text.secondary"
            component={RouterLink}
            to="/tos"
            sx={{ textDecoration: 'none', '&:hover': { textDecoration: 'underline' } }}
          >
            {t('nav.terms')}
          </Typography>
        </Box>
        <Typography variant="caption" color="text.disabled">
          {t('nav.university', { year: new Date().getFullYear() })}
        </Typography>
      </Box>
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
              bgcolor: 'background.default',
              border: 'none',
              boxShadow: 'none',
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
          mt: isMobile ? 0 : 1,
        }}
      >
        {/* Above the AppBar and outside it, so an urgent notice sits at the very top of the page
            and pushes the chrome down rather than competing with it. Renders nothing when there is
            nothing to say, so the ordinary case costs no layout. Gated on `authenticated` because
            the endpoint requires a session — polling it while signed out is a guaranteed 401 once a
            minute. */}
        <SystemMessageBanner enabled={authenticated} />

        {/* Under the system messages, because a maintenance notice outranks "there is a newer
            build". Not gated on `authenticated`: this reads a static file rather than the API, so
            there is no 401 to avoid, and somebody solving a public exercise without an account
            benefits from a current bundle exactly as much as anyone else. */}
        <UpdateAvailableBanner />

        <AppBar
          position="sticky"
          elevation={0}
          sx={{
            bgcolor: 'background.default',
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
                <EnvironmentBadge compact />
              </Box>
            )}

            <Box sx={{ flexGrow: 1 }} />

            {authenticated && (
              <>
                <IconButton
                  size="small"
                  // Icon-only, so without this it has no accessible name at all: unreachable by
                  // screen reader and unfindable by anything querying the accessibility tree.
                  aria-label={t('nav.accountMenu')}
                  onClick={(e) => setProfileAnchor(e.currentTarget)}
                  sx={{ ml: 0.5 }}
                >
                  <AccountCircleOutlined />
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
                      navigate('/account')
                    }}
                  >
                    <ListItemIcon>
                      <AccountCircleOutlined fontSize="small" />
                    </ListItemIcon>
                    <ListItemText>{t('nav.accountSettings')}</ListItemText>
                  </MenuItem>
                  {activeRole === 'admin' && (
                    // An internal route, so a RouterLink rather than an anchor — but still a real
                    // href, so ctrl/cmd-click opens it in a tab like any other link.
                    <MenuItem
                      component={RouterLink}
                      to="/admin/messages"
                      onClick={() => setProfileAnchor(null)}
                    >
                      <ListItemIcon>
                        <CampaignOutlined fontSize="small" />
                      </ListItemIcon>
                      <ListItemText>{t('admin.messages.title')}</ListItemText>
                    </MenuItem>
                  )}
                  {activeRole === 'admin' && config.idpAdminUrl && (
                    // Only while actually acting as an admin, not merely for someone who could
                    // switch to it — an admin working as a teacher is doing teacher things, and
                    // this is the same reasoning as isTeacherOrAdmin above.
                    //
                    // A real anchor, so ctrl/cmd-click opens it in a new tab like any other link.
                    // It leaves the application entirely, hence target=_blank and the trailing
                    // icon that says so before you click rather than after.
                    //
                    // Being an app admin does not make you a Keycloak admin — they are separate
                    // systems and separate grants. That is exactly what the page this points at
                    // exists to explain, so showing the link to every app admin is right even
                    // though some of them cannot use what is behind it.
                    <MenuItem
                      component="a"
                      href={config.idpAdminUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      onClick={() => setProfileAnchor(null)}
                    >
                      <ListItemIcon>
                        <AdminPanelSettingsOutlined fontSize="small" />
                      </ListItemIcon>
                      <ListItemText>{t('nav.idpAdmin')}</ListItemText>
                      <OpenInNewOutlined
                        fontSize="small"
                        sx={{ ml: 1.5, opacity: 0.5 }}
                      />
                    </MenuItem>
                  )}
                  <MenuItem
                    onClick={() => {
                      toggleLanguage()
                      setProfileAnchor(null)
                    }}
                  >
                    <ListItemIcon>
                      <TranslateOutlined fontSize="small" />
                    </ListItemIcon>
                    <ListItemText>{t('general.otherLanguage')}</ListItemText>
                  </MenuItem>
                  <MenuItem
                    onClick={() => {
                      toggleMode()
                      setProfileAnchor(null)
                    }}
                  >
                    <ListItemIcon>
                      {mode === 'dark'
                        ? <LightModeOutlined fontSize="small" />
                        : <DarkModeOutlined fontSize="small" />}
                    </ListItemIcon>
                    <ListItemText>{mode === 'dark' ? t('nav.lightMode') : t('nav.darkMode')}</ListItemText>
                  </MenuItem>
                  <Divider />
                  <MenuItem
                    onClick={() => {
                      logout()
                      setProfileAnchor(null)
                    }}
                  >
                    <ListItemIcon>
                      <LogoutOutlined fontSize="small" />
                    </ListItemIcon>
                    <ListItemText>{t('nav.logOut')}</ListItemText>
                  </MenuItem>
                </Menu>
              </>
            )}
          </Toolbar>
        </AppBar>

        <Box
          sx={{
            flexGrow: 1,
            bgcolor: 'background.paper',
            borderTopLeftRadius: isMobile ? 0 : 16,
            minHeight: 0,
          }}
        >
          <Container maxWidth="lg" sx={{ py: 3 }}>
            <Outlet />
          </Container>
        </Box>
      </Box>

      {isTeacherOrAdmin && courseId && (
        <EditCourseDialog
          courseId={courseId}
          open={settingsOpen}
          onClose={() => setSettingsOpen(false)}
        />
      )}
    </Box>
  )
}
