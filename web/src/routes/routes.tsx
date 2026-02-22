import { createBrowserRouter, Navigate } from 'react-router-dom'
import { Box, CircularProgress } from '@mui/material'
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
import { useAuth } from '../auth/AuthContext.tsx'

function IndexRedirect() {
  const { initialized, authenticated } = useAuth()
  if (!initialized) {
    return (
      <Box display="flex" justifyContent="center" alignItems="center" minHeight="60vh">
        <CircularProgress />
      </Box>
    )
  }
  return <Navigate to={authenticated ? '/courses' : '/landing'} replace />
}

const router = createBrowserRouter([
  {
    path: '/landing',
    element: <LandingPage />,
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
        element: (
          <RequireAuth allowedRoles={['teacher', 'admin']}>
            <ExerciseLibraryPage />
          </RequireAuth>
        ),
      },
      {
        path: 'library/:exerciseId',
        element: (
          <RequireAuth allowedRoles={['teacher', 'admin']}>
            <ExercisePage />
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
