import { Box, Typography } from '@mui/material'
import RobotFace from './RobotFace.tsx'

export default function RobotPlaceholder({ message }: { message: string }) {
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', py: 6 }}>
      <RobotFace hoverLight />
      <Typography color="text.secondary" sx={{ mt: 2.5, fontSize: '1.1rem' }}>
        {message}
      </Typography>
    </Box>
  )
}
