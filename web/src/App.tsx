import { RouterProvider } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { LocalizationProvider } from '@mui/x-date-pickers/LocalizationProvider'
import { AdapterDateFns } from '@mui/x-date-pickers/AdapterDateFns'
import { et, enGB } from 'date-fns/locale'
import { ThemeProvider } from './theme/ThemeContext.tsx'
import { AuthProvider } from './auth/AuthContext.tsx'
import { QueryProvider } from './api/QueryProvider.tsx'
import router from './routes/routes.tsx'

import './i18n/i18n.ts'
import '@fontsource/roboto/400.css'
import '@fontsource/roboto/500.css'
import '@fontsource/roboto/700.css'

export default function App() {
  const { i18n } = useTranslation()
  const dateFnsLocale = i18n.language === 'et' ? et : enGB

  return (
    <ThemeProvider>
      <LocalizationProvider dateAdapter={AdapterDateFns} adapterLocale={dateFnsLocale}>
        <AuthProvider>
          <QueryProvider>
            <RouterProvider router={router} />
          </QueryProvider>
        </AuthProvider>
      </LocalizationProvider>
    </ThemeProvider>
  )
}