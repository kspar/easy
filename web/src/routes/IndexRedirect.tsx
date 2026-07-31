import { Navigate } from 'react-router-dom'
import { Box, CircularProgress } from '@mui/material'
import { useAuth } from '../auth/useAuth.ts'

/**
 * Landing decision for `/`: signed-in users go to their courses, everyone else to the
 * public landing page. Lives in its own file so routes.tsx exports only the router —
 * a component alongside that non-component export breaks Fast Refresh.
 */
export default function IndexRedirect() {
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
