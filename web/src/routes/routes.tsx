import { createBrowserRouter, Navigate } from 'react-router-dom'
import AppLayout from '../layouts/AppLayout.tsx'
import RequireAuth from './RequireAuth.tsx'
import NotFoundPage from '../features/NotFoundPage.tsx'
import CoursesPage from '../features/courses/CoursesPage.tsx'
import CourseExercisesPage from '../features/course-exercises/CourseExercisesPage.tsx'
import ExerciseSummaryPage from '../features/course-exercise/ExerciseSummaryPage.tsx'
import ParticipantsPage from '../features/participants/ParticipantsPage.tsx'
import GradeTablePage from '../features/grade-table/GradeTablePage.tsx'
import ExerciseLibraryPage from '../features/library/ExerciseLibraryPage.tsx'
import ExercisePage from '../features/library/ExercisePage.tsx'
import AboutPage from '../features/about/AboutPage.tsx'

const router = createBrowserRouter([
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <Navigate to="/courses" replace /> },
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
            <ExerciseSummaryPage />
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
