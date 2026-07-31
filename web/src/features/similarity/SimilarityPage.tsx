import { Typography, Box, IconButton } from '@mui/material'
import { ArrowBackOutlined } from '@mui/icons-material'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import usePageTitle from '../../hooks/usePageTitle.ts'
import RobotPlaceholder from '../../components/RobotPlaceholder.tsx'

export default function SimilarityPage() {
  const { courseId } = useParams<{ courseId: string }>()
  const navigate = useNavigate()
  const { t } = useTranslation()
  usePageTitle(t('similarity.title'))

  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
        <IconButton onClick={() => navigate(`/courses/${courseId}/exercises`)} size="small">
          <ArrowBackOutlined />
        </IconButton>
        <Typography variant="h5">{t('similarity.title')}</Typography>
      </Box>
      <RobotPlaceholder message={t('similarity.comingSoon')} />
    </>
  )
}
