import { useMemo, useState } from 'react'
import {
  Box,
  Button,
  Chip,
  CircularProgress,
  List,
  ListItemButton,
  Menu,
  MenuItem,
  Typography,
} from '@mui/material'
import {
  ArrowDropDownOutlined,
  CircleOutlined,
  FiberManualRecordOutlined,
  SortOutlined,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useTeacherSubmissionSummaries, useCourseGroups } from '../../api/exercises.ts'
import useSavedGroup from '../../hooks/useSavedGroup.ts'
import RelativeTime from '../../components/RelativeTime.tsx'
import { RobotIcon } from '../../components/icons.tsx'
import type { SubmissionRow, StudentExerciseStatus } from '../../api/types.ts'

type SortKey = 'name' | 'points' | 'time'

function statusColor(status: StudentExerciseStatus): string {
  switch (status) {
    case 'COMPLETED':
      return 'success.main'
    case 'STARTED':
      return 'warning.main'
    case 'UNGRADED':
      return 'info.main'
    default:
      return 'text.disabled'
  }
}

export default function SubmissionsList({
  courseId,
  courseExerciseId,
  onSelectStudent,
}: {
  courseId: string
  courseExerciseId: string
  onSelectStudent: (studentId: string) => void
}) {
  const { t } = useTranslation()
  const [filterGroup, setFilterGroup] = useSavedGroup(courseId)
  const [filterGroupAnchor, setFilterGroupAnchor] = useState<Element | null>(null)
  const [sortKey, setSortKey] = useState<SortKey>('name')
  const [sortAnchor, setSortAnchor] = useState<Element | null>(null)

  const { data: groups } = useCourseGroups(courseId)
  const { data: students, isLoading } = useTeacherSubmissionSummaries(
    courseId,
    courseExerciseId,
    filterGroup || undefined,
  )

  const sorted = useMemo(() => {
    if (!students) return []
    const arr = [...students]

    const nameCmp = (a: SubmissionRow, b: SubmissionRow) => {
      const last = a.family_name.localeCompare(b.family_name)
      return last !== 0 ? last : a.given_name.localeCompare(b.given_name)
    }

    switch (sortKey) {
      case 'name':
        arr.sort(nameCmp)
        break
      case 'points':
        arr.sort((a, b) => {
          const ga = a.submission?.grade?.grade ?? -1
          const gb = b.submission?.grade?.grade ?? -1
          if (gb !== ga) return gb - ga
          return nameCmp(a, b)
        })
        break
      case 'time':
        arr.sort((a, b) => {
          const ta = a.submission?.time ?? ''
          const tb = b.submission?.time ?? ''
          if (tb !== ta) return tb.localeCompare(ta)
          return nameCmp(a, b)
        })
        break
    }
    return arr
  }, [students, sortKey])

  // Stats
  const stats = useMemo(() => {
    if (!students) return { completed: 0, started: 0, ungraded: 0, unstarted: 0, total: 0 }
    let completed = 0, started = 0, ungraded = 0, unstarted = 0
    for (const s of students) {
      switch (s.status) {
        case 'COMPLETED': completed++; break
        case 'STARTED': started++; break
        case 'UNGRADED': ungraded++; break
        default: unstarted++; break
      }
    }
    return { completed, started, ungraded, unstarted, total: students.length }
  }, [students])

  const gradedCount = stats.completed
  const totalStudents = stats.total

  const sortLabels: Record<SortKey, string> = {
    name: t('submission.sortByName'),
    points: t('submission.sortByPoints'),
    time: t('submission.sortByTime'),
  }

  if (isLoading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
        <CircularProgress size={28} />
      </Box>
    )
  }

  return (
    <Box>
      {/* Header bar */}
      <Box sx={{ display: 'flex', gap: 0.75, mb: 1.5, flexWrap: 'wrap', alignItems: 'center' }}>
        {/* Group filter */}
        {groups && groups.length > 0 && (
          <>
            <Chip
              label={filterGroup
                ? groups.find((g) => g.id === filterGroup)?.name
                : t('participants.groups')
              }
              deleteIcon={<ArrowDropDownOutlined />}
              onDelete={(e: React.MouseEvent<HTMLElement>) =>
                setFilterGroupAnchor(e.currentTarget.closest('div'))
              }
              onClick={(e) => setFilterGroupAnchor(e.currentTarget)}
              variant={filterGroup ? 'filled' : 'outlined'}
              color={filterGroup ? 'primary' : 'default'}
            />
            <Menu
              anchorEl={filterGroupAnchor}
              open={!!filterGroupAnchor}
              onClose={() => setFilterGroupAnchor(null)}
            >
              <MenuItem
                selected={!filterGroup}
                onClick={() => { setFilterGroup(''); setFilterGroupAnchor(null) }}
              >
                {t('participants.allGroups')}
              </MenuItem>
              {groups.map((g) => (
                <MenuItem
                  key={g.id}
                  selected={filterGroup === g.id}
                  onClick={() => { setFilterGroup(g.id); setFilterGroupAnchor(null) }}
                >
                  {g.name}
                </MenuItem>
              ))}
            </Menu>
          </>
        )}

        <Box sx={{ flex: 1 }} />

        {/* Sort button */}
        <Button
          size="small"
          variant="outlined"
          startIcon={<SortOutlined />}
          onClick={(e) => setSortAnchor(e.currentTarget)}
          sx={{ textTransform: 'none', height: 32 }}
        >
          {sortLabels[sortKey]}
        </Button>
        <Menu
          anchorEl={sortAnchor}
          open={!!sortAnchor}
          onClose={() => setSortAnchor(null)}
        >
          {(Object.keys(sortLabels) as SortKey[]).map((key) => (
            <MenuItem
              key={key}
              selected={sortKey === key}
              onClick={() => { setSortKey(key); setSortAnchor(null) }}
            >
              {sortLabels[key]}
            </MenuItem>
          ))}
        </Menu>
      </Box>

      {/* Summary stats */}
      <Box sx={{ display: 'flex', gap: 1, mb: 1.5, flexWrap: 'wrap' }}>
        {stats.completed > 0 && (
          <Chip label={`${stats.completed} ${t('exercises.completed')}`} size="small" color="success" variant="outlined" sx={{ fontSize: '0.75rem' }} />
        )}
        {stats.started > 0 && (
          <Chip label={`${stats.started} ${t('exercises.started')}`} size="small" color="warning" variant="outlined" sx={{ fontSize: '0.75rem' }} />
        )}
        {stats.ungraded > 0 && (
          <Chip label={`${stats.ungraded} ${t('exercises.ungraded')}`} size="small" color="info" variant="outlined" sx={{ fontSize: '0.75rem' }} />
        )}
        {stats.unstarted > 0 && (
          <Chip label={`${stats.unstarted} ${t('exercises.unstarted')}`} size="small" variant="outlined" sx={{ fontSize: '0.75rem' }} />
        )}
      </Box>

      {/* Student list */}
      <List disablePadding>
        {sorted.map((row) => {
          const sub = row.submission
          const grade = sub?.grade?.grade ?? null
          const hasSubmission = sub !== null

          return (
            <ListItemButton
              key={row.student_id}
              onClick={() => onSelectStudent(row.student_id)}
              sx={{
                borderRadius: 1,
                mb: 0.5,
                py: 1,
                px: 1.5,
              }}
            >
              <Box sx={{ display: 'flex', alignItems: 'center', width: '100%', gap: 1.5 }}>
                {/* Unseen dot */}
                <Box sx={{ width: 8, flexShrink: 0 }}>
                  {sub && !sub.seen && (
                    <FiberManualRecordOutlined sx={{ fontSize: 8, color: 'info.main' }} />
                  )}
                </Box>

                {/* Name */}
                <Typography variant="body2" sx={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {row.family_name}, {row.given_name}
                </Typography>

                {/* Submission time */}
                {sub && (
                  <Typography variant="caption" color="text.secondary" sx={{ flexShrink: 0 }}>
                    <RelativeTime date={sub.time} />
                  </Typography>
                )}

                {/* Grade chip */}
                {hasSubmission ? (
                  <Chip
                    label={grade !== null ? grade : '–'}
                    size="small"
                    icon={sub.grade?.is_autograde ? <RobotIcon sx={{ fontSize: '14px !important' }} /> : undefined}
                    sx={{
                      minWidth: 48,
                      fontWeight: 600,
                      fontSize: '0.75rem',
                      color: statusColor(row.status),
                      borderColor: statusColor(row.status),
                    }}
                    variant="outlined"
                  />
                ) : (
                  <Chip
                    icon={<CircleOutlined sx={{ fontSize: '14px !important' }} />}
                    label="–"
                    size="small"
                    variant="outlined"
                    sx={{ minWidth: 48, fontSize: '0.75rem' }}
                  />
                )}
              </Box>
            </ListItemButton>
          )
        })}
      </List>

      {/* Footer */}
      {totalStudents > 0 && (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', textAlign: 'center', mt: 1.5 }}>
          {t('submission.gradedCount', { done: gradedCount, total: totalStudents })}
        </Typography>
      )}

      {totalStudents === 0 && !isLoading && (
        <Typography color="text.secondary" sx={{ textAlign: 'center', py: 4 }}>
          {t('submission.noSubmissions')}
        </Typography>
      )}
    </Box>
  )
}
