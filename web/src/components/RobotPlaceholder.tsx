import { Box, Typography, keyframes } from '@mui/material'

const looking = keyframes`
  0%, 83.34%, 100% { transform: translate(0px, 0px) }
  16.67% { transform: translate(-2px, -1px) }
  33.34% { transform: translate(3px, 0px) }
  50% { transform: translate(-1px, 2px) }
  66.67% { transform: translate(2px, -1px) }
`

export default function RobotPlaceholder({ message }: { message: string }) {
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', py: 6 }}>
      {/* Antenna ball */}
      <Box
        sx={{
          width: 16,
          height: 16,
          borderRadius: '50%',
          backgroundColor: '#a8a8a8',
          transform: 'translateY(3px)',
          transition: 'background-color 0.3s, box-shadow 0.3s',
          '&:hover': {
            backgroundColor: '#fbff00',
            boxShadow: '0 0 10px 10px rgba(255, 255, 190, 0.8)',
          },
        }}
      />
      {/* Antenna */}
      <Box sx={{ width: 8, height: 10, backgroundColor: '#a8a8a8', flexShrink: 0 }} />
      {/* Robot head */}
      <Box
        sx={{
          display: 'flex',
          justifyContent: 'space-between',
          width: 60,
          height: 50,
          backgroundColor: '#a8a8a8',
          borderRadius: '20%',
          alignItems: 'center',
          px: 1,
        }}
      >
        {/* Eyes */}
        <Box
          sx={{
            width: 14,
            height: 14,
            borderRadius: '50%',
            backgroundColor: 'white',
            animation: `${looking} 5s 1s infinite`,
          }}
        />
        <Box
          sx={{
            width: 14,
            height: 14,
            borderRadius: '50%',
            backgroundColor: 'white',
            animation: `${looking} 5s 1s infinite`,
          }}
        />
      </Box>
      {/* Message */}
      <Typography color="text.secondary" sx={{ mt: 2.5, fontSize: '1.1rem' }}>
        {message}
      </Typography>
    </Box>
  )
}
