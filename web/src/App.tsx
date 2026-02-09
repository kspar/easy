import { RouterProvider } from 'react-router-dom'
import { ThemeProvider } from './theme/ThemeContext.tsx'
import { AuthProvider } from './auth/AuthContext.tsx'
import { QueryProvider } from './api/QueryProvider.tsx'
import router from './routes/routes.tsx'

import './i18n/i18n.ts'
import '@fontsource/roboto/400.css'
import '@fontsource/roboto/500.css'
import '@fontsource/roboto/700.css'

export default function App() {
  return (
    <ThemeProvider>
      <AuthProvider>
        <QueryProvider>
          <RouterProvider router={router} />
        </QueryProvider>
      </AuthProvider>
    </ThemeProvider>
  )
}