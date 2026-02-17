import { useState } from 'react'
import {
  Typography,
  CircularProgress,
  Alert,
  Box,
  Tab,
  Tabs,
  IconButton,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Chip,
} from '@mui/material'
import { ArrowBackOutlined } from '@mui/icons-material'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useParticipants } from '../../api/exercises.ts'
import usePageTitle from '../../hooks/usePageTitle.ts'

export default function ParticipantsPage() {
  const { courseId } = useParams<{ courseId: string }>()
  const navigate = useNavigate()
  const { t } = useTranslation()
  const [tab, setTab] = useState(0)
  const { data, isLoading, error } = useParticipants(courseId!)
  usePageTitle(t('participants.students'))

  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
        <IconButton
          onClick={() => navigate(`/courses/${courseId}/exercises`)}
          size="small"
        >
          <ArrowBackOutlined />
        </IconButton>
        <Typography variant="h5">{t('participants.students')}</Typography>
      </Box>

      {isLoading && <CircularProgress />}
      {error && (
        <Alert severity="error">{t('general.somethingWentWrong')}</Alert>
      )}

      {data && (
        <>
          <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>
            <Tab
              label={`${t('participants.students')} (${data.students?.length ?? 0})`}
            />
            <Tab
              label={`${t('participants.teachers')} (${data.teachers?.length ?? 0})`}
            />
          </Tabs>

          {tab === 0 && data.students && (
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>{t('general.name')}</TableCell>
                    <TableCell>{t('general.email')}</TableCell>
                    <TableCell>{t('participants.groups')}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {data.students.map((s) => (
                    <TableRow key={s.id}>
                      <TableCell>
                        {s.given_name} {s.family_name}
                      </TableCell>
                      <TableCell>{s.email}</TableCell>
                      <TableCell>
                        <Box
                          sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap' }}
                        >
                          {s.groups.map((g) => (
                            <Chip
                              key={g.id}
                              label={g.name}
                              size="small"
                              variant="outlined"
                            />
                          ))}
                        </Box>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}

          {tab === 1 && data.teachers && (
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>{t('general.name')}</TableCell>
                    <TableCell>{t('general.email')}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {data.teachers.map((teacher) => (
                    <TableRow key={teacher.id}>
                      <TableCell>
                        {teacher.given_name} {teacher.family_name}
                      </TableCell>
                      <TableCell>{teacher.email}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </>
      )}
    </>
  )
}
