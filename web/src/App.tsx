import { RouterProvider } from 'react-router-dom'
import { LocalizationProvider } from '@mui/x-date-pickers/LocalizationProvider'
import { AdapterDateFns } from '@mui/x-date-pickers/AdapterDateFns'
import { useDateLocale } from './i18n/dateLocale.ts'
import { ThemeProvider } from './theme/ThemeContext.tsx'
import { AuthProvider } from './auth/AuthContext.tsx'
import { QueryProvider } from './api/QueryProvider.tsx'
import ErrorBoundary from './components/ErrorBoundary.tsx'
import router from './routes/routes.tsx'

import './i18n/i18n.ts'
import '@fontsource/roboto/400.css'
import '@fontsource/roboto/500.css'
import '@fontsource/roboto/700.css'

export default function App() {
  // A hook, so a language switch re-renders and the pickers below are not stranded on the old
  // locale. See i18n/dateLocale.ts.
  const dateFnsLocale = useDateLocale()

  return (
    <ThemeProvider>
      <LocalizationProvider dateAdapter={AdapterDateFns} adapterLocale={dateFnsLocale}>
        <AuthProvider>
          <QueryProvider>
            {/*
              Inside QueryProvider so the crash screen can post a report through the ordinary
              mutation, outside RouterProvider so a throw in the router or a layout is caught at all.
              See components/ErrorBoundary.tsx.
            */}
            <ErrorBoundary>
              <RouterProvider router={router} />
            </ErrorBoundary>
          </QueryProvider>
        </AuthProvider>
      </LocalizationProvider>
    </ThemeProvider>
  )
}