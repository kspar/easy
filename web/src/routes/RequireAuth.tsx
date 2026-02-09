import { type ReactNode } from 'react'
import { useAuth, type Role } from '../auth/AuthContext.tsx'
import { Navigate } from 'react-router-dom'
import { Box, CircularProgress, Typography } from '@mui/material'

interface Props {
  children: ReactNode
  allowedRoles?: Role[]
}

export default function RequireAuth({ children, allowedRoles }: Props) {
  const { initialized, authenticated, activeRole, login } = useAuth()

  if (!initialized) {
    return (
      <Box
        display="flex"
        flexDirection="column"
        alignItems="center"
        justifyContent="center"
        minHeight="60vh"
        gap={2}
      >
        <CircularProgress />
        <Typography variant="body2" color="text.secondary">
          Loading...
        </Typography>
      </Box>
    )
  }

  if (!authenticated) {
    login()
    return null
  }

  if (allowedRoles && !allowedRoles.includes(activeRole)) {
    return <Navigate to="/courses" replace />
  }

  return <>{children}</>
}
