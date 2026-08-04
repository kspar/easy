import { createBrowserRouter, Navigate } from 'react-router-dom'
import AppLayout from '../layouts/AppLayout.tsx'
import RequireAuth from './RequireAuth.tsx'
import NotFoundPage from '../features/NotFoundPage.tsx'
import CoursesPage from '../features/courses/CoursesPage.tsx'
import CourseExercisesPage from '../features/course-exercises/CourseExercisesPage.tsx'
import CourseExercisePage from '../features/course-exercise/CourseExercisePage.tsx'
import ParticipantsPage from '../features/participants/ParticipantsPage.tsx'
import GradeTablePage from '../features/grade-table/GradeTablePage.tsx'
import SimilarityPage from '../features/similarity/SimilarityPage.tsx'
import ExerciseLibraryPage from '../features/library/ExerciseLibraryPage.tsx'
import ExercisePage from '../features/library/ExercisePage.tsx'
import AboutPage from '../features/about/AboutPage.tsx'
import LandingPage from '../features/landing/LandingPage.tsx'
import JoinByLinkPage from '../features/join/JoinByLinkPage.tsx'
import IndexRedirect from './IndexRedirect.tsx'
import EmbedExercisePage from '../features/embed/EmbedExercisePage.tsx'
import TermsRedirect from '../features/terms/TermsRedirect.tsx'

const router = createBrowserRouter([
  {
    path: '/landing',
    element: <LandingPage />,
  },
  {
    // Outside AppLayout on purpose: no nav, no sidebar, no auth. `exercises` is plural and the
    // trailing `*` swallows the title slug because that is the URL wui minted, and embeds carrying
    // it are published on pages nobody here can edit. See EmbedExercisePage.
    path: '/embed/exercises/:exerciseId/*',
    element: <EmbedExercisePage />,
  },
  {
    // Outside AppLayout and outside RequireAuth: it redirects straight out of the app, so rendering
    // nav and a sidebar first would be a flash of chrome nobody sees on purpose — and reading the
    // terms before having an account is the normal case, not an edge one.
    path: '/tos',
    element: <TermsRedirect />,
  },
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <IndexRedirect /> },
      {
        path: 'courses',
        element: (
          <RequireAuth>
            <CoursesPage />
          </RequireAuth>
        ),
      },
      {
        path: 'courses/:courseId/exercises',
        element: (
          <RequireAuth>
            <CourseExercisesPage />
          </RequireAuth>
        ),
      },
      {
        path: 'courses/:courseId/exercises/:courseExerciseId',
        element: (
          <RequireAuth>
            <CourseExercisePage />
          </RequireAuth>
        ),
      },
      {
        path: 'courses/:courseId/participants',
        element: (
          <RequireAuth allowedRoles={['teacher', 'admin']}>
            <ParticipantsPage />
          </RequireAuth>
        ),
      },
      {
        path: 'courses/:courseId/grades',
        element: (
          <RequireAuth allowedRoles={['teacher', 'admin']}>
            <GradeTablePage />
          </RequireAuth>
        ),
      },
      {
        path: 'courses/:courseId/similarity',
        element: (
          <RequireAuth allowedRoles={['teacher', 'admin']}>
            <SimilarityPage />
          </RequireAuth>
        ),
      },
      {
        path: 'library',
        element: <Navigate to="/library/dir/root" replace />,
      },
      {
        path: 'library/dir/:dirId/*',
        element: (
          <RequireAuth allowedRoles={['teacher', 'admin']}>
            <ExerciseLibraryPage />
          </RequireAuth>
        ),
      },
      {
        path: 'library/exercise/:exerciseId/*',
        element: (
          <RequireAuth allowedRoles={['teacher', 'admin']}>
            <ExercisePage />
          </RequireAuth>
        ),
      },
      {
        path: 'link/:inviteId',
        element: (
          <RequireAuth>
            <JoinByLinkPage />
          </RequireAuth>
        ),
      },
      {
        path: 'moodle/link/:inviteId',
        element: (
          <RequireAuth>
            <JoinByLinkPage isMoodle />
          </RequireAuth>
        ),
      },
      {
        path: 'about',
        element: <AboutPage />,
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])

export default router
