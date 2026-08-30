import { useEffect, useState } from 'react'
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
  ListItem,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Divider,
  Chip,
  Container,
  useMediaQuery,
  useTheme,
  ListSubheader,
  Snackbar,
} from '@mui/material'
import {
  AccountCircleOutlined,
  Menu as MenuIcon,
  SchoolOutlined,
  ArticleOutlined,
  LibraryBooksOutlined,
  DarkModeOutlined,
  LightModeOutlined,
  LanguageOutlined,
  LogoutOutlined,
  AssignmentOutlined,
  GradingOutlined,
  PeopleOutlined,
  CompareArrowsOutlined,
  SettingsOutlined,
  AdminPanelSettingsOutlined,
  OpenInNewOutlined,
  CampaignOutlined,
  BugReportOutlined,
  MonitorHeartOutlined,
} from '@mui/icons-material'
import { useThemeMode } from '../theme/useThemeMode.ts'
import { useCourseExercises } from '../api/exercises.ts'
import { useCourse } from '../api/courses.ts'
import EditCourseDialog from '../features/course-settings/EditCourseDialog.tsx'
import BugReportDialog from '../features/bug-report/BugReportDialog.tsx'
import { record } from '../features/bug-report/breadcrumbs.ts'
import { spaLinkProps } from '../components/spaLink.ts'
import useRecentExercises from '../hooks/useRecentExercises.ts'
import logoSvg from '../assets/logo.svg'
import SystemMessageBanner from '../components/SystemMessageBanner.tsx'
import UpdateAvailableBanner from '../components/UpdateAvailableBanner.tsx'
import EnvironmentBadge from '../components/EnvironmentBadge.tsx'
import ExerciseStatusIcon from '../components/ExerciseStatusIcon.tsx'

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

  // Where the reporter has been, for a bug report's activity log (EZ-1786). Here because every
  // authenticated route renders through this layout, so one effect covers all of them.
  //
  // The search string is included and the hash is not: a filter or a tab selection is frequently
  // the difference between a page that works and one that does not, whereas the hash is a scroll
  // position. `search` can carry an exercise or group id, which is the same class of identifier the
  // path already carries.
  useEffect(() => {
    record('route', `${location.pathname}${location.search}`)
  }, [location.pathname, location.search])

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
  const [bugReportOpen, setBugReportOpen] = useState(false)
  const [snackbar, setSnackbar] = useState<string | null>(null)
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

  const isActive = (path: string) => location.pathname.startsWith(path)

  /**
   * Everything the sidebar still needs on a click, now that navigation is the browser's job.
   *
   * This replaced a `navTo` that called `navigate()`, which is why nothing in this sidebar could be
   * opened in a new tab — no `href` means no ctrl/cmd-click, no middle-click, no "copy link
   * address", and nothing announced as a link. Every item below is a real `RouterLink` instead; see
   * `components/spaLink.ts` for the longer version of why.
   *
   * Closing the drawer on a modifier-click is harmless: the new tab opens, this one's drawer shuts,
   * and on the touch devices the drawer actually exists for there are no modifier keys anyway.
   */
  const closeDrawerOnMobile = () => {
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

      {/*
      Navigation. A <Box component="nav">, not a <List>, because this holds both individual
      items and whole sub-lists — and a <ul> may contain neither a role=button nor another <ul>
      as a direct child. Each group below is its own <List>, which is also what gives a screen
      reader a sensible item count per section instead of one long undifferentiated list.
      */}
      <Box component="nav" sx={{ py: 1.5, flexGrow: 1 }}>
      <List disablePadding>
        <ListItem disablePadding>
          <ListItemButton
            component={RouterLink}
            to="/courses"
            selected={location.pathname === '/courses'}
            onClick={closeDrawerOnMobile}
          >
            <ListItemIcon>
              <SchoolOutlined color={location.pathname === '/courses' ? 'primary' : 'action'} />
            </ListItemIcon>
            <ListItemText
              primary={t('nav.myCourses')}
              primaryTypographyProps={{ variant: 'body2', fontWeight: 500 }}
            />
          </ListItemButton>
        </ListItem>

        {isTeacherOrAdmin && (
          <ListItem disablePadding>
            <ListItemButton
              component={RouterLink}
              to="/library/dir/root"
              selected={isActive('/library')}
              onClick={closeDrawerOnMobile}
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
          </ListItem>
        )}

        {/*
        Admin-only: articles are authored by admins and read by everyone from a direct link, so
        nobody else needs the index.

        This was the only item here that was a real anchor, and its comment used to point at "the
        onClick+navTo its neighbours use" as the thing it was avoiding. The neighbours are all
        RouterLinks now, so there is nothing left to contrast with — see the links rule in CLAUDE.md
        and `components/spaLink.ts`.
        */}
        {activeRole === 'admin' && (
          <ListItem disablePadding>
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
          </ListItem>
        )}

      </List>

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
                <ListItem disablePadding>
                  {/*
                    Was the one item in this sidebar that got it right, with `component="a"` and its
                    own copy of the modifier check. A RouterLink does the same thing without the
                    hand-rolled guard — react-router leaves modifier clicks to the browser itself.
                  */}
                  <ListItemButton
                    key={ex.id}
                    component={RouterLink}
                    to={href}
                    selected={location.pathname.startsWith(`/library/exercise/${ex.id}`)}
                    onClick={closeDrawerOnMobile}
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
                </ListItem>
              )
            })}
          </List>
        )}

        {/* Student: exercise list in sidebar */}
        {studentCourseId && exercises && exercises.length > 0 && (
          <List disablePadding>
            <ListSubheader
              disableSticky
              sx={{
                fontSize: '0.68rem',
                fontWeight: 600,
                letterSpacing: '0.08em',
                textTransform: 'uppercase',
                lineHeight: '32px',
                mt: 1,
                px: 2.5,
              }}
              title={courseTitle}
            >
              {/*
                An anchor *inside* the subheader rather than a subheader that is itself an anchor: a
                `<ul>` may only contain `<li>`, and `component={RouterLink}` here would put an `<a>`
                directly in the list. So the link is the text, which is what a reader clicks anyway.
              */}
              <Box
                component="a"
                {...spaLinkProps(`/courses/${studentCourseId}/exercises`, navigate)}
                sx={{
                  display: 'block',
                  color: 'text.secondary',
                  textDecoration: 'none',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                  '&:hover': { color: 'text.primary' },
                }}
              >
                {courseTitle ?? t('exercises.title')}
              </Box>
            </ListSubheader>
            {exercises.map((ex) => (
              <ListItem disablePadding>
                <ListItemButton
                  key={ex.id}
                  component={RouterLink}
                  to={`/courses/${studentCourseId}/exercises/${ex.id}`}
                  selected={activeExerciseId === ex.id}
                  onClick={closeDrawerOnMobile}
                  sx={{ py: 0.5, minHeight: 36, pl: 3 }}
                >
                  <ListItemIcon sx={{ minWidth: 28 }}>
                    <ExerciseStatusIcon status={ex.status} size={16} />
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
              </ListItem>
            ))}
          </List>
        )}

        {/* Teacher/Admin: course sub-page links in sidebar */}
        {isTeacherOrAdmin && courseId && (
          <List disablePadding>
            <ListSubheader
              disableSticky
              sx={{
                fontSize: '0.68rem',
                fontWeight: 600,
                letterSpacing: '0.08em',
                textTransform: 'uppercase',
                lineHeight: '32px',
                mt: 1,
                px: 2.5,
              }}
              title={courseTitle}
            >
              {/* See the student subheader above for why the anchor is inside rather than around. */}
              <Box
                component="a"
                {...spaLinkProps(`/courses/${courseId}/exercises`, navigate)}
                sx={{
                  display: 'block',
                  color: 'text.secondary',
                  textDecoration: 'none',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                  '&:hover': { color: 'text.primary' },
                }}
              >
                {courseTitle ?? t('exercises.title')}
              </Box>
            </ListSubheader>
            <ListItem disablePadding>
              <ListItemButton
                component={RouterLink}
                to={`/courses/${courseId}/exercises`}
                selected={isActive(`/courses/${courseId}/exercises`)}
                onClick={closeDrawerOnMobile}
                sx={{ py: 0.5, minHeight: 36, pl: 3 }}
              >
                <ListItemIcon sx={{ minWidth: 28 }}>
                  <AssignmentOutlined sx={{ fontSize: 18 }} color={isActive(`/courses/${courseId}/exercises`) ? 'primary' : 'action'} />
                </ListItemIcon>
                <ListItemText primary={t('exercises.title')} primaryTypographyProps={{ variant: 'body2', fontSize: '0.85rem' }} />
              </ListItemButton>
            </ListItem>
            <ListItem disablePadding>
              <ListItemButton
                component={RouterLink}
                to={`/courses/${courseId}/grades`}
                selected={isActive(`/courses/${courseId}/grades`)}
                onClick={closeDrawerOnMobile}
                sx={{ py: 0.5, minHeight: 36, pl: 3 }}
              >
                <ListItemIcon sx={{ minWidth: 28 }}>
                  <GradingOutlined sx={{ fontSize: 18 }} color={isActive(`/courses/${courseId}/grades`) ? 'primary' : 'action'} />
                </ListItemIcon>
                <ListItemText primary={t('grades.title')} primaryTypographyProps={{ variant: 'body2', fontSize: '0.85rem' }} />
              </ListItemButton>
            </ListItem>
            <ListItem disablePadding>
              <ListItemButton
                component={RouterLink}
                to={`/courses/${courseId}/participants`}
                selected={isActive(`/courses/${courseId}/participants`)}
                onClick={closeDrawerOnMobile}
                sx={{ py: 0.5, minHeight: 36, pl: 3 }}
              >
                <ListItemIcon sx={{ minWidth: 28 }}>
                  <PeopleOutlined sx={{ fontSize: 18 }} color={isActive(`/courses/${courseId}/participants`) ? 'primary' : 'action'} />
                </ListItemIcon>
                <ListItemText primary={t('participants.title')} primaryTypographyProps={{ variant: 'body2', fontSize: '0.85rem' }} />
              </ListItemButton>
            </ListItem>
            <ListItem disablePadding>
              <ListItemButton
                component={RouterLink}
                to={`/courses/${courseId}/similarity`}
                selected={isActive(`/courses/${courseId}/similarity`)}
                onClick={closeDrawerOnMobile}
                sx={{ py: 0.5, minHeight: 36, pl: 3 }}
              >
                <ListItemIcon sx={{ minWidth: 28 }}>
                  <CompareArrowsOutlined sx={{ fontSize: 18 }} color={isActive(`/courses/${courseId}/similarity`) ? 'primary' : 'action'} />
                </ListItemIcon>
                <ListItemText primary={t('similarity.title')} primaryTypographyProps={{ variant: 'body2', fontSize: '0.85rem' }} />
              </ListItemButton>
            </ListItem>
            <ListItem disablePadding>
              <ListItemButton
                onClick={() => { setSettingsOpen(true); if (isMobile) setDrawerOpen(false) }}
                sx={{ py: 0.5, minHeight: 36, pl: 3 }}
              >
                <ListItemIcon sx={{ minWidth: 28 }}>
                  <SettingsOutlined sx={{ fontSize: 18 }} color="action" />
                </ListItemIcon>
                <ListItemText primary={t('courses.courseSettings')} primaryTypographyProps={{ variant: 'body2', fontSize: '0.85rem' }} />
              </ListItemButton>
            </ListItem>
          </List>
        )}

        {/*
        Administration, last and admin-only.

        These four were scattered: the bug-report dashboard was loose in the main nav, System
        messages and Keycloak admin were in the account menu, and Operating info was reachable only
        by knowing that the About page grows an extra section for admins. Three of them are places
        you go to do a job, which is what a sidebar is for — an account menu is for things about
        *you*, and "Account settings" and "Report a bug" are the only two items there that still are.

        Last, because it is the section a teacher never sees and an admin uses rarely; putting it
        above the course they are actually working in would cost every other visit a little.

        The two external links stay config-gated as well as role-gated, so an environment that has
        nowhere to send an admin shows one item fewer rather than a broken link. Which means this
        section can hold anywhere between two and four items, and that is fine — it is never empty,
        because System messages and Operating info are always there for an admin.
        */}
        {activeRole === 'admin' && (
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
              }}
            >
              {t('nav.administration')}
            </ListSubheader>

            <ListItem disablePadding>
              <ListItemButton
                component={RouterLink}
                to="/admin/messages"
                selected={isActive('/admin/messages')}
                onClick={closeDrawerOnMobile}
                sx={{ py: 0.5, minHeight: 36, pl: 3 }}
              >
                <ListItemIcon sx={{ minWidth: 28 }}>
                  <CampaignOutlined
                    sx={{ fontSize: 18 }}
                    color={isActive('/admin/messages') ? 'primary' : 'action'}
                  />
                </ListItemIcon>
                <ListItemText
                  primary={t('admin.messages.title')}
                  primaryTypographyProps={{ variant: 'body2', fontSize: '0.85rem' }}
                />
              </ListItemButton>
            </ListItem>

            {config.bugReportDashboardUrl && (
              <ListItem disablePadding>
                <ListItemButton
                  component="a"
                  href={config.bugReportDashboardUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  onClick={closeDrawerOnMobile}
                  sx={{ py: 0.5, minHeight: 36, pl: 3 }}
                >
                  <ListItemIcon sx={{ minWidth: 28 }}>
                    <BugReportOutlined sx={{ fontSize: 18 }} color="action" />
                  </ListItemIcon>
                  <ListItemText
                    primary={t('nav.bugReportDashboard')}
                    primaryTypographyProps={{ variant: 'body2', fontSize: '0.85rem' }}
                  />
                  <OpenInNewOutlined sx={{ fontSize: 14, opacity: 0.5 }} />
                </ListItemButton>
              </ListItem>
            )}

            {/*
              Being an app admin does not make you a Keycloak admin — separate systems, separate
              grants. The page behind this exists to say so, which is why it is shown to every app
              admin even though some of them cannot use what is on the other side.
            */}
            {config.idpAdminUrl && (
              <ListItem disablePadding>
                <ListItemButton
                  component="a"
                  href={config.idpAdminUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  onClick={closeDrawerOnMobile}
                  sx={{ py: 0.5, minHeight: 36, pl: 3 }}
                >
                  <ListItemIcon sx={{ minWidth: 28 }}>
                    <AdminPanelSettingsOutlined sx={{ fontSize: 18 }} color="action" />
                  </ListItemIcon>
                  <ListItemText
                    primary={t('nav.idpAdmin')}
                    primaryTypographyProps={{ variant: 'body2', fontSize: '0.85rem' }}
                  />
                  <OpenInNewOutlined sx={{ fontSize: 14, opacity: 0.5 }} />
                </ListItemButton>
              </ListItem>
            )}

            {/*
              The About page, which renders an admin-only operating-info block — uptime, the
              executors, what is grading right now. Labelled for what an admin comes here to read
              rather than for the route it happens to use, because "About Lahendus" in the footer is
              the same page and is not what this is for.
            */}
            <ListItem disablePadding>
              <ListItemButton
                component={RouterLink}
                to="/about"
                selected={isActive('/about')}
                onClick={closeDrawerOnMobile}
                sx={{ py: 0.5, minHeight: 36, pl: 3 }}
              >
                <ListItemIcon sx={{ minWidth: 28 }}>
                  <MonitorHeartOutlined
                    sx={{ fontSize: 18 }}
                    color={isActive('/about') ? 'primary' : 'action'}
                  />
                </ListItemIcon>
                <ListItemText
                  primary={t('nav.operatingInfo')}
                  primaryTypographyProps={{ variant: 'body2', fontSize: '0.85rem' }}
                />
              </ListItemButton>
            </ListItem>
          </List>
        )}

      </Box>

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
              <IconButton edge="start" onClick={toggleDrawer} sx={{ mr: 0.5 }} aria-label={t('nav.openMenu')}>
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
                  {/*
                    A RouterLink, like the admin item below it — which had the comment about
                    ctrl/cmd-click while this one, directly above it, navigated programmatically.
                  */}
                  <MenuItem
                    component={RouterLink}
                    to="/account"
                    onClick={() => setProfileAnchor(null)}
                  >
                    <ListItemIcon>
                      <AccountCircleOutlined fontSize="small" />
                    </ListItemIcon>
                    <ListItemText>{t('nav.accountSettings')}</ListItemText>
                  </MenuItem>
                  {/*
                    For everyone, not gated on a role: the person who cannot use the page is the one
                    with something to say, and most of them are students. In the account menu rather
                    than as a floating button because that is where every other global action in this
                    app lives, and a permanent button in the corner of every page is a lot of chrome
                    to spend on something used once a month.
                  */}
                  <MenuItem
                    onClick={() => {
                      setProfileAnchor(null)
                      setBugReportOpen(true)
                    }}
                  >
                    <ListItemIcon>
                      <BugReportOutlined fontSize="small" />
                    </ListItemIcon>
                    <ListItemText>{t('bugReport.title')}</ListItemText>
                  </MenuItem>
                  {/*
                    System messages and Keycloak admin used to be here, and moved to the
                    Administration section of the sidebar. What is left is the account menu doing
                    only what its name says: things about *you* — your settings, your bug report,
                    your language, your theme, your role, your session. Nothing an admin goes to in
                    order to do a job.
                  */}
                  <MenuItem
                    onClick={() => {
                      toggleLanguage()
                      setProfileAnchor(null)
                    }}
                  >
                    <ListItemIcon>
                      <LanguageOutlined fontSize="small" />
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
          {/*
          `component="main"` — the landmark every page needs and none had.

          Without it there is nothing for a screen reader to skip to, so reaching the content means
          tabbing past the whole sidebar on every single navigation. One here covers every route,
          because every route renders through this Outlet.
          */}
          <Container component="main" maxWidth="lg" sx={{ py: 3 }}>
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

      {/*
        Mounted only while open, so each opening starts from a fresh snapshot of the activity buffer
        and an empty box. See the note on `diagnostics` in BugReportDialog.
      */}
      {bugReportOpen && (
        <BugReportDialog
          open
          onClose={() => setBugReportOpen(false)}
          onSuccess={setSnackbar}
          pageUrl={`${location.pathname}${location.search}`}
        />
      )}

      <Snackbar
        open={snackbar !== null}
        autoHideDuration={4000}
        onClose={() => setSnackbar(null)}
        message={snackbar ?? ''}
      />
    </Box>
  )
}
