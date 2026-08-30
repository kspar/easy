import { type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../auth/useAuth.ts'
import type { Role } from '../auth/auth-context.ts'
import { Navigate } from 'react-router-dom'
import { Box, CircularProgress, Typography } from '@mui/material'
import ErrorAlert from '../components/ErrorAlert.tsx'

interface Props {
  children: ReactNode
  allowedRoles?: Role[]
}

function Loading({ message }: { message: string }) {
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
        {message}
      </Typography>
    </Box>
  )
}

export default function RequireAuth({ children, allowedRoles }: Props) {
  const { t } = useTranslation()
  const { initialized, authenticated, activeRole, login, checkedIn, checkinFailed } = useAuth()

  if (!initialized) {
    return <Loading message={t('general.loading')} />
  }

  if (!authenticated) {
    login()
    return null
  }

  if (checkinFailed) {
    return <ErrorAlert />
  }

  // Nothing else can be requested before the account exists in core
  if (!checkedIn) {
    return <Loading message={t('general.loading')} />
  }

  if (allowedRoles && !allowedRoles.includes(activeRole)) {
    return <Navigate to="/courses" replace />
  }

  return <>{children}</>
}
