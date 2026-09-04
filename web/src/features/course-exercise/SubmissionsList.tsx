import { useMemo, useState } from 'react'
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  LinearProgress,
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
  RefreshOutlined,
  SortOutlined,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useTeacherSubmissionSummaries, useCourseGroups } from '../../api/exercises.ts'
import useSavedGroup from '../../hooks/useSavedGroup.ts'
import type { RerunController } from './useRerunAllTests.ts'
import RelativeTime from '../../components/RelativeTime.tsx'
import ConfirmDialog from '../../components/ConfirmDialog.tsx'
import { RobotIcon } from '../../components/icons.tsx'
import type { GraderType, SubmissionRow, StudentExerciseStatus } from '../../api/types.ts'

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
  graderType,
  rerun,
  onSelectStudent,
}: {
  courseId: string
  courseExerciseId: string
  graderType: GraderType
  /** Owned by the page, so a run outlives this list being swapped out for a student's code. */
  rerun: RerunController
  onSelectStudent: (studentId: string) => void
}) {
  const { t } = useTranslation()
  const [filterGroup, setFilterGroup] = useSavedGroup(courseId)
  const [filterGroupAnchor, setFilterGroupAnchor] = useState<Element | null>(null)
  const [sortKey, setSortKey] = useState<SortKey>('name')
  const [sortAnchor, setSortAnchor] = useState<Element | null>(null)
  const [confirmRerun, setConfirmRerun] = useState(false)

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

  // The run follows the list on screen: this group's students, in the order they are shown, and only
  // the ones with a submission to re-run. Snapshotted when the run starts — the list is re-read after
  // every submission, so anything read from it mid-run would be a moving target.
  const rerunnableIds = useMemo(
    () => sorted.flatMap((r) => (r.submission ? [r.submission.id] : [])),
    [sorted],
  )

  const canRerun = graderType === 'AUTO'
  const { running, currentSubmissionId } = rerun.progress
  const groupName = filterGroup ? groups?.find((g) => g.id === filterGroup)?.name : undefined

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
              // Frozen during a run: the progress counter refers to the group that was on screen
              // when it started, and letting the filter move under it would make "4 of 17" a claim
              // about a list nobody can see.
              disabled={running}
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

        {/* Re-run every shown submission's tests */}
        {canRerun && (
          running ? (
            <Button
              size="small"
              variant="outlined"
              color="warning"
              onClick={rerun.cancel}
              disabled={rerun.progress.cancelled}
              sx={{ textTransform: 'none', height: 32 }}
            >
              {rerun.progress.cancelled ? t('submission.rerunStopping') : t('general.cancel')}
            </Button>
          ) : (
            <Button
              size="small"
              variant="outlined"
              startIcon={<RefreshOutlined />}
              onClick={() => setConfirmRerun(true)}
              disabled={rerunnableIds.length === 0}
              sx={{ textTransform: 'none', height: 32 }}
            >
              {t('submission.rerunAll')}
            </Button>
          )
        )}

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

      {/* Re-run progress */}
      {running && (
        <Box sx={{ mb: 1.5 }}>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.5 }}>
            {t('submission.rerunProgress', {
              done: rerun.progress.done,
              total: rerun.progress.total,
            })}
          </Typography>
          <LinearProgress
            variant="determinate"
            value={rerun.progress.total ? (rerun.progress.done / rerun.progress.total) * 100 : 0}
            sx={{ height: 4, borderRadius: 2 }}
          />
        </Box>
      )}

      {/* What the run came to. Not "all graded successfully": core records an assessment that blew
          up as an activity and answers 200 anyway, so the only claim we can make is that the runs
          happened and the grades on screen are the ones they produced. */}
      {rerun.progress.finished && (
        <Alert
          // A run the teacher stopped halfway is not a success, and a green tick over "Stopped after
          // 4 of 17" reads as though the 13 were fine rather than untouched.
          severity={
            rerun.progress.failed > 0 ? 'warning' : rerun.progress.cancelled ? 'info' : 'success'
          }
          onClose={rerun.dismiss}
          sx={{ mb: 1.5 }}
        >
          {/* `done` counts submissions the run went through, failures included, because that is what
              the progress bar has been counting all along. The sentence below is about the ones that
              actually re-ran, so it has to take the failures back out — otherwise a run of three with
              one failure reports "Tests re-run on 3 submissions. 1 could not be run." */}
          {rerun.progress.gaveUp
            ? t('submission.rerunGaveUp', { count: rerun.progress.failed })
            : rerun.progress.cancelled
              ? t('submission.rerunCancelled', {
                  done: rerun.progress.done,
                  total: rerun.progress.total,
                })
              : t('submission.rerunDone', { count: rerun.progress.done - rerun.progress.failed })}
          {rerun.progress.failed > 0 && !rerun.progress.gaveUp
            && ` ${t('submission.rerunFailed', { count: rerun.progress.failed })}`}
        </Alert>
      )}

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
          const isRerunning = sub !== null && sub.id === currentSubmissionId

          return (
            <ListItemButton
              key={row.student_id}
              onClick={() => onSelectStudent(row.student_id)}
              sx={{
                borderRadius: 1,
                mb: 0.5,
                py: 1,
                px: 1.5,
                // The row being graded, tinted. A 12px spinner tucked inside a grade chip is the
                // right detail at arm's length and invisible at the distance someone actually
                // watches a hundred-student run from; the row is the part that reads across a desk.
                bgcolor: isRerunning ? 'action.hover' : 'transparent',
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

                {/* Beside the chip rather than inside it. As the chip's `icon` it was a 13px arc on
                    the chip's own muted outline colour — invisible at the distance anyone actually
                    watches a hundred-student run from, and it displaced the robot icon, so the only
                    legible effect was that the icon vanished. */}
                {isRerunning && <CircularProgress size={16} sx={{ flexShrink: 0 }} />}

                {/* Grade chip. Mid-run the row keeps its old grade, dimmed, so the change reads as a
                    change rather than as a value appearing out of a gap. */}
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
                      opacity: isRerunning ? 0.5 : 1,
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

      {/* Confirmed rather than fired straight off the button: it re-grades other people's work, it
          can overwrite a grade, and how much of it happens depends on a filter chip several
          centimetres away. The count and the group name are the two things to check before saying
          yes, so they are what the dialog says. */}
      <ConfirmDialog
        open={confirmRerun}
        confirmLabel={t('submission.rerunAll')}
        confirmColor="primary"
        message={
          groupName
            ? t('submission.rerunConfirmGroup', { count: rerunnableIds.length, group: groupName })
            : t('submission.rerunConfirm', { count: rerunnableIds.length })
        }
        onClose={() => setConfirmRerun(false)}
        onConfirm={() => {
          setConfirmRerun(false)
          void rerun.start(rerunnableIds)
        }}
      />
    </Box>
  )
}
